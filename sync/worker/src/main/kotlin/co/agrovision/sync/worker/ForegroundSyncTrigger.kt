package co.agrovision.sync.worker

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import co.agrovision.core.common.AgroDispatcher
import co.agrovision.core.common.Dispatcher
import co.agrovision.core.common.AgroResult
import co.agrovision.core.model.mlmodel.ModelUpdateDecision
import co.agrovision.core.model.port.ModelRegistryRepository
import co.agrovision.core.model.port.SettingsRepository
import co.agrovision.core.model.usecase.ActivateModelUseCase
import co.agrovision.core.model.usecase.CheckModelUpdateUseCase
import co.agrovision.core.model.usecase.SyncPendingUseCase
import co.agrovision.core.model.usecase.SyncTrigger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Disparador **primario** de la sincronización (§9.4, ADR-007).
 *
 * ## Por qué este es el primario
 *
 * Es la única ruta que **siempre** funciona, independientemente del fabricante.
 * Las capas de personalización de Xiaomi, Tecno o Infinix pueden suprimir
 * WorkManager indefinidamente, pero no pueden impedir que la app haga trabajo
 * mientras está en primer plano.
 *
 * Y encaja con el comportamiento real del productor: abre la aplicación cada vez
 * que escanea, lo que garantiza ventanas de sincronización frecuentes sin pedirle
 * nada. La sincronización más fiable es la que ocurre cuando el usuario ya está
 * usando la app.
 *
 * Se engancha a `ProcessLifecycleOwner`, no a una `Activity`: cubre tanto el
 * arranque en frío como la vuelta desde segundo plano, y no se dispara en cada
 * rotación de pantalla.
 */
@Singleton
class ForegroundSyncTrigger @Inject constructor(
    private val syncPending: SyncPendingUseCase,
    private val scheduler: SyncScheduler,
    private val settings: SettingsRepository,
    private val registry: ModelRegistryRepository,
    private val checkModelUpdate: CheckModelUpdateUseCase,
    private val activateModel: ActivateModelUseCase,
    @Dispatcher(AgroDispatcher.IO) private val io: CoroutineDispatcher,
) : DefaultLifecycleObserver {

    // Scope propio del proceso, no del ViewModel: la sincronización tiene que
    // sobrevivir a que el productor navegue entre pantallas. `SupervisorJob` para
    // que un fallo no cancele las sincronizaciones siguientes.
    private val scope = CoroutineScope(SupervisorJob() + io)

    fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    private suspend fun installFirstModelIfMissing() {
        if (registry.currentState().active != null) return

        val decision = (checkModelUpdate(force = true) as? AgroResult.Success)?.value
        if (decision is ModelUpdateDecision.Available) activateModel(decision.manifest)
    }

    override fun onStart(owner: LifecycleOwner) {
        scope.launch {
            // No se comprueba conectividad aquí: el caso de uso ya lo hace y
            // devuelve `NoConnection` sin efectos. Duplicar la comprobación
            // abriría la puerta a que las dos discrepen.
            // Sin modelo la app no puede escanear: se pide ya, sin esperar el ciclo de
            // 24 h ni los minutos de retraso del trabajo en segundo plano.
            installFirstModelIfMissing()

            syncPending(SyncTrigger.FOREGROUND)

            // Se encola además el trabajo en segundo plano. Si el fabricante lo
            // ejecuta, mejora la frescura; si lo suprime, no se pierde nada
            // porque acabamos de sincronizar en primer plano.
            val preferences = settings.current()
            scheduler.scheduleOpportunistic(preferences.syncNetworkPolicy)
            scheduler.scheduleModelCheck(preferences.syncNetworkPolicy)
        }
    }
}
