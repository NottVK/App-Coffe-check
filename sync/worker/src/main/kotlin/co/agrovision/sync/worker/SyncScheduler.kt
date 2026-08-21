package co.agrovision.sync.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import co.agrovision.core.common.AgroConstants
import co.agrovision.core.model.settings.SyncNetworkPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Disparador **secundario** de la sincronización de tres disparadores (§9.4,
 * ADR-007).
 *
 * ## Por qué esto es el secundario y no el principal
 *
 * WorkManager es la herramienta correcta y sus restricciones funcionan bien en
 * Android puro. Pero buena parte del mercado corre capas de personalización de
 * fabricante que terminan procesos en segundo plano de forma agresiva para
 * mejorar sus cifras de autonomía. Un trabajo que se ejecuta con fiabilidad en un
 * Pixel puede no ejecutarse **nunca** en cierto dispositivo de gama baja hasta que
 * el usuario abra la aplicación.
 *
 * Y ese es exactamente el segmento del público objetivo: Xiaomi, Tecno, Infinix.
 * Diseñar la sincronización asumiendo que el trabajo en segundo plano se ejecutará
 * es diseñar para el usuario equivocado — el error prohibido nº 8 (§23).
 *
 * Así que esto es **optimización**: cuando corre, mejora la frescura de los datos;
 * cuando el fabricante lo suprime, no se pierde nada, porque el disparador
 * primario —primer plano— cubre el caso.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val workManager get() = WorkManager.getInstance(context)

    /**
     * @param policy decide la restricción de red.
     *
     * **ADR-008: no se exige red no medida.** En zona rural la única conectividad
     * suele ser datos móviles; forzar `UNMETERED` condenaría las capturas a no
     * sincronizarse nunca. La decisión sobre el gasto de datos le corresponde a
     * quien lo paga, y aquí solo se traduce lo que el productor eligió.
     */
    fun scheduleOpportunistic(policy: SyncNetworkPolicy) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (policy.allowsMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
            )
            // Sin restricción de batería baja: una captura pendiente pesa
            // kilobytes y esperar a que el productor cargue el teléfono puede
            // significar días.
            .setRequiresBatteryNotLow(false)
            .setRequiresStorageNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                AgroConstants.Sync.INITIAL_BACKOFF.inWholeMilliseconds,
                TimeUnit.MILLISECONDS,
            )
            .addTag(TAG_SYNC)
            .build()

        // KEEP, no REPLACE: si ya hay una sincronización encolada o corriendo,
        // encolar otra no la acelera y sí reinicia su backoff, que es justo lo
        // contrario de lo que hace falta tras varios fallos seguidos.
        workManager.enqueueUniqueWork(WORK_SYNC, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Comprobación de modelo. Preferentemente sobre WiFi —un modelo pesa entre 4
     * y 9 MB— pero sin exigirlo: quien tiene solo datos también necesita recibir
     * correcciones del reconocedor.
     */
    fun scheduleModelCheck(policy: SyncNetworkPolicy) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (policy == SyncNetworkPolicy.SOLO_WIFI) NetworkType.UNMETERED
                else NetworkType.CONNECTED
            )
            .setRequiresStorageNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<ModelUpdateWorker>()
            .setConstraints(constraints)
            .setInitialDelay(MODEL_CHECK_DELAY_MINUTES, TimeUnit.MINUTES)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                MODEL_BACKOFF_MINUTES,
                TimeUnit.MINUTES,
            )
            .addTag(TAG_MODEL)
            .build()

        workManager.enqueueUniqueWork(WORK_MODEL, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(WORK_SYNC)
        workManager.cancelUniqueWork(WORK_MODEL)
    }

    private companion object {
        const val WORK_SYNC = "agrovision.sync"
        const val WORK_MODEL = "agrovision.model-check"
        const val TAG_SYNC = "sync"
        const val TAG_MODEL = "model"

        /** Retraso inicial: al abrir la app, primero sube lo pendiente; el modelo
         *  puede esperar unos minutos y no compite por el mismo ancho de banda. */
        const val MODEL_CHECK_DELAY_MINUTES = 5L
        const val MODEL_BACKOFF_MINUTES = 30L
    }
}
