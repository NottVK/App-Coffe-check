package co.agrovision.sync.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import co.agrovision.core.common.AgroResult
import co.agrovision.core.model.usecase.SyncPendingUseCase
import co.agrovision.core.model.usecase.SyncTrigger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Disparador **terciario** de la sincronización (§9.4, ADR-007).
 *
 * Para lotes grandes acumulados tras varios días sin señal. Sobrevive a que el
 * productor cambie de aplicación durante una subida larga, que es lo que un
 * `CoroutineWorker` no garantiza y una corrutina en primer plano tampoco.
 *
 * **Lo inicia siempre una acción visible del usuario**, nunca la app por su
 * cuenta. Dos razones: es lo que exige la política de Play para
 * `FOREGROUND_SERVICE_DATA_SYNC`, y es honesto — subir cuarenta megabytes de
 * datos prepago es una decisión de quien los paga.
 *
 * ⚠️ Requiere `android:foregroundServiceType="dataSync"` en el manifiesto y
 * justificación en el formulario de Play (PRESERVE.md §4).
 */
@AndroidEntryPoint
class SyncForegroundService : Service() {

    @Inject lateinit var syncPending: SyncPendingUseCase

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        // Si ya hay una subida en curso, una segunda pulsación no arranca otra.
        // Dos subidas concurrentes del mismo lote duplicarían el consumo de datos
        // sin acelerar nada.
        if (job?.isActive == true) return START_NOT_STICKY

        job = scope.launch {
            val result = syncPending(SyncTrigger.FOREGROUND_SERVICE)
            if (result is AgroResult.Failure) {
                // Sin reintento aquí: el productor pidió esta subida
                // explícitamente y merece saber si funcionó. Reintentar en
                // silencio gastaría sus datos sin que él lo decidiera.
                notifyResult(success = false)
            }
            stopSelf()
        }

        // NOT_STICKY: si el sistema mata el servicio por presión de memoria, NO
        // se relanza solo. La cola sigue intacta y el disparador primario la
        // recogerá; relanzarse solo consumiría datos sin que el usuario lo pida.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.sync_service_titulo))
            .setContentText(getString(R.string.sync_service_texto))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.sync_canal_nombre),
                // LOW: informa del progreso sin sonar. El productor está
                // trabajando; no se le interrumpe por una subida que él pidió.
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.sync_canal_descripcion) }
        )
    }

    private fun notifyResult(success: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            RESULT_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(
                    getString(
                        if (success) R.string.sync_resultado_ok else R.string.sync_resultado_fallo
                    )
                )
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setAutoCancel(true)
                .build()
        )
    }

    companion object {
        private const val CHANNEL_ID = "agrovision.sync"
        private const val NOTIFICATION_ID = 1001
        private const val RESULT_NOTIFICATION_ID = 1002

        fun start(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
