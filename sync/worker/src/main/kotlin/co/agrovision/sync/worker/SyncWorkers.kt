package co.agrovision.sync.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import co.agrovision.core.common.AgroConstants
import co.agrovision.core.common.AgroResult
import co.agrovision.core.model.mlmodel.ModelUpdateDecision
import co.agrovision.core.model.usecase.ActivateModelUseCase
import co.agrovision.core.model.usecase.CheckModelUpdateUseCase
import co.agrovision.core.model.usecase.PurgeExpiredCapturesUseCase
import co.agrovision.core.model.usecase.RollBackModelUseCase
import co.agrovision.core.model.usecase.SyncPendingUseCase
import co.agrovision.core.model.usecase.SyncTrigger
import co.agrovision.network.api.CatalogSync
import co.agrovision.network.api.ZoneRuleSync
import co.agrovision.core.data.catalog.CatalogSeeder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Trabajador de sincronización. Disparador **secundario**.
 *
 * Nota sobre `Result.retry()`: WorkManager aplica su propio backoff exponencial
 * **encima** del que ya lleva cada fila en `next_attempt_at`. No es duplicación:
 * el de la fila decide qué se sube, el del worker decide cuándo se vuelve a
 * intentar el lote entero. Un fallo de red no debe hacer avanzar el contador de
 * intentos de quince capturas.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncPending: SyncPendingUseCase,
    private val purgeExpired: PurgeExpiredCapturesUseCase,
    private val catalogSync: CatalogSync,
    private val catalogSeeder: CatalogSeeder,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        catalogSeeder.seedIfEmpty()
        // La cola del productor va primero: un catálogo lento o enorme no puede
        // retrasar las solicitudes de revisión que alguien está esperando.
        val outcome = syncPending(SyncTrigger.WORKER)
        val catalogOk = catalogSync.sync()
        // Las reglas de zona NO van aquí: tienen su propio trabajo
        // ([ZoneRuleWorker]). Seis consultas más antes de devolver `retry()`
        // retrasaban el reintento de la cola y, con `KEEP`, descartaban las
        // solicitudes que llegaran mientras tanto.

        // La purga corre tras una sincronización con éxito, no antes: borrar
        // capturas cuya subida todavía no se ha confirmado sería perder trabajo
        // de campo.
        if (outcome is AgroResult.Success) {
            runCatching { purgeExpired() }
                .onFailure { Log.w(TAG, "La purga falló; se reintentará", it) }
        }

        return when (outcome) {
            is AgroResult.Success -> {
                if ((!catalogOk || outcome.value.failed > 0) && runAttemptCount < AgroConstants.Sync.MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.success()
                }
            }
            is AgroResult.Failure -> {
                if (runAttemptCount < AgroConstants.Sync.MAX_ATTEMPTS) Result.retry()
                // Rendirse aquí NO pierde nada: las filas siguen en PENDING y el
                // disparador primario las recogerá la próxima vez que el
                // productor abra la app, que es cada vez que escanea.
                else Result.failure()
            }
        }
    }

    private companion object {
        const val TAG = "AgroVision.SyncWorker"
    }
}

/**
 * Reglas de «zona a vigilar», en un trabajo **propio**.
 *
 * Separado de [SyncWorker] para que la cola del productor resuelva (y reintente)
 * sin esperar a las reglas, y para que estas tengan su backoff y su restricción
 * de red. [ZoneRuleSync.syncAll] ya lleva un presupuesto total corto y cancela
 * la llamada HTTP en vuelo al agotarlo o si WorkManager detiene el trabajo.
 *
 * Un fallo conserva la última regla en caché. Los reintentos tienen tope: un
 * tipo que el servidor rechaza siempre (400) no debe consultarse sin fin; la
 * próxima apertura de la app encola el trabajo de nuevo.
 */
@HiltWorker
class ZoneRuleWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val zoneRuleSync: ZoneRuleSync,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (zoneRuleSync.syncAll()) return Result.success()
        Log.i(TAG, "Reglas de zona sin actualizar; se usa la caché")
        // `runAttemptCount` empieza en 0: se ejecuta como mucho MAX_ATTEMPTS veces.
        return if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    internal companion object {
        const val MAX_ATTEMPTS = 3
        private const val TAG = "AgroVision.ZoneRules"
    }
}

/**
 * Comprobación y activación de modelo OTA.
 *
 * Implementa el procedimiento de §8.3 de principio a fin, con una particularidad:
 * **`killSwitch` se atiende antes que nada**. Su razón de ser es retirar una
 * versión de inmediato sin esperar al ciclo normal, y la reversión no requiere
 * descarga porque el modelo previo sigue en disco.
 */
@HiltWorker
class ModelUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val checkUpdate: CheckModelUpdateUseCase,
    private val activate: ActivateModelUseCase,
    private val rollBack: RollBackModelUseCase,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val decision = when (val result = checkUpdate()) {
            is AgroResult.Failure -> return Result.retry()
            is AgroResult.Success -> result.value
        }

        return when (decision) {
            ModelUpdateDecision.UpToDate -> Result.success()

            is ModelUpdateDecision.KillSwitchTriggered -> {
                Log.w(TAG, "killSwitch activo para ${decision.version}: se revierte")
                rollBack()
                Result.success()
            }

            // No es un error y no se reintenta: la app tiene que actualizarse
            // desde Play primero. Reintentar cada media hora solo gastaría datos.
            is ModelUpdateDecision.RequiresAppUpdate -> {
                Log.i(TAG, "El modelo exige la app ${decision.minAppVersion} o superior")
                Result.success()
            }

            is ModelUpdateDecision.Available -> {
                when (activate(decision.manifest)) {
                    is AgroResult.Success -> Result.success()
                    // La ranura activa quedó intacta: el productor sigue pudiendo
                    // escanear con el modelo anterior mientras esto se reintenta.
                    // Con tope: un modelo que falla siempre no debe redescargarse sin fin
                    is AgroResult.Failure ->
                        if (runAttemptCount >= MAX_ACTIVATION_ATTEMPTS) Result.failure() else Result.retry()
                }
            }
        }
    }

    private companion object {
        const val MAX_ACTIVATION_ATTEMPTS = 3
        const val TAG = "AgroVision.ModelWorker"
    }
}
