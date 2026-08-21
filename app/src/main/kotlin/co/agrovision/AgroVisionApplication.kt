package co.agrovision

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import co.agrovision.core.data.catalog.CatalogSeeder
import co.agrovision.core.data.legacy.CapacitorDataImporter
import co.agrovision.core.datastore.DeviceIdentityStore
import co.agrovision.ml.registry.ModelSlotLayout
import co.agrovision.sync.worker.ForegroundSyncTrigger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AgroVisionApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var foregroundSync: ForegroundSyncTrigger
    @Inject lateinit var legacyImporter: CapacitorDataImporter
    @Inject lateinit var catalogSeeder: CatalogSeeder
    @Inject lateinit var identity: DeviceIdentityStore
    @Inject lateinit var slots: ModelSlotLayout

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Disparador PRIMARIO de sincronización (§9.4). Es la ruta que siempre
        // funciona, independientemente de la capa del fabricante.
        foregroundSync.attach()

        scope.launch {
            // Restos de descargas de modelo interrumpidas. Sin esto, un usuario
            // que pierde la señal a mitad de cada intento acumula megabytes de
            // basura en un teléfono que probablemente ya está lleno.
            slots.clearStaging()

            identity.deviceId()
            identity.incrementLaunchCount()

            // El catálogo tiene que estar ANTES que nada: es lo que el productor
            // consulta en el lote sin señal (§3.1 RF-08).
            catalogSeeder.seedIfEmpty()

            importLegacyDataOnce()
        }
    }

    /**
     * Importación de la galería de la app publicada (ADR-013).
     *
     * Corre en segundo plano y **no bloquea el arranque**: la primera pantalla es
     * la cámara, y hacer esperar al productor mientras se procesan cuarenta
     * miniaturas contradice el principio de que el primer contacto es escanear.
     * El historial se rellena solo, porque la UI observa Room.
     */
    private suspend fun importLegacyDataOnce() {
        if (identity.isLegacyImportDone()) return

        val report = legacyImporter.import()

        if (report.hadWork) {
            Log.i(
                TAG,
                "Importación desde Capacitor: ${report.imported} de ${report.found} " +
                    "(sin archivo: ${report.skippedMissingFile}, " +
                    "malformadas: ${report.skippedMalformed}, " +
                    "duplicadas: ${report.skippedDuplicate})",
            )
        }

        // Solo se marca como hecha si no hubo un fallo de parseo total: con los
        // datos originales intactos, una versión futura con mejor parseo todavía
        // puede reintentarlo.
        if (report.shouldMarkComplete) {
            identity.markLegacyImportDone(report.imported)
        } else {
            Log.w(TAG, "El índice de fotos de Capacitor es ilegible. Se reintentará.")
        }
    }

    private companion object {
        const val TAG = "AgroVision"
    }
}
