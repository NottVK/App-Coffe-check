package co.agrovision.core.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import co.agrovision.core.common.AgroConstants
import co.agrovision.core.common.AgroDispatcher
import co.agrovision.core.common.AgroResult
import co.agrovision.core.common.Dispatcher
import co.agrovision.core.datastore.AccountStore
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.capture.GeoPoint
import co.agrovision.core.model.identity.Account
import co.agrovision.core.model.port.AccountRepository
import co.agrovision.core.model.port.Clock
import co.agrovision.core.model.port.LocationProvider
import co.agrovision.core.model.port.TelemetryRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cuenta del productor.
 *
 * ⚠️ **Sin backend todavía.** El registro por OTP (§6.1, Fase 10) no existe, así
 * que `requestOtp` y `verifyOtp` devuelven `NoConnection`.
 *
 * Que eso NO impida usar la app es el punto entero del diseño: el nivel T0 cubre
 * escanear sin límite, catálogo completo, historial local, correcciones y OTA de
 * modelo. La cuenta solo hace falta para que una Solicitud de Revisión llegue a
 * un agrónomo, y hasta entonces la cola local la retiene sin perder nada (§6.3).
 */
@Singleton
class AccountRepositoryImpl @Inject constructor(
    private val store: AccountStore,
    private val clock: Clock,
) : AccountRepository {

    override fun observe(): Flow<Account> = store.observe()

    override suspend fun current(): Account = store.current()

    override suspend fun linkAnonymousDevice(phone: String): AgroResult<Account, DomainError> =
        AgroResult.failure(DomainError.NoConnection)

    override suspend fun requestOtp(phone: String): AgroResult<Unit, DomainError> =
        AgroResult.failure(DomainError.NoConnection)

    override suspend fun verifyOtp(
        phone: String,
        code: String,
    ): AgroResult<Account, DomainError> = AgroResult.failure(DomainError.NoConnection)

    override suspend fun refreshValidationStatus(): AgroResult<Account, DomainError> =
        AgroResult.failure(DomainError.NoConnection)

    override suspend fun setTrainingConsent(granted: Boolean) {
        store.update { it.copy(trainingConsent = granted) }
    }
}

/**
 * Ubicación opcional del lote.
 *
 * Usa `LocationManager` del sistema y no Play Services a propósito: son 0 KB de
 * dependencia extra, funciona en dispositivos sin servicios de Google —que los
 * hay en el segmento objetivo— y `getLastKnownLocation` no enciende el GPS.
 *
 * Eso último importa: **la ubicación nunca puede retrasar una captura.** Encender
 * el GPS y esperar un fix puede tardar treinta segundos a cielo cubierto bajo
 * sombrío de café. Un escaneo sin coordenada sigue siendo útil; un escaneo
 * perdido por esperar al GPS, no.
 */
@Singleton
class LocationProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
    @Dispatcher(AgroDispatcher.IO) private val io: CoroutineDispatcher,
) : LocationProvider {

    override suspend fun currentLocation(): GeoPoint? = withContext(io) {
        if (!hasPermission()) return@withContext null

        val manager = context.getSystemService(LocationManager::class.java)
            ?: return@withContext null

        val freshnessWindow = AgroConstants.Location.FRESHNESS.inWholeMilliseconds
        val now = clock.nowEpochMillis()

        // Se recorren todos los proveedores y se toma el fix más reciente que
        // siga dentro de la ventana de frescura. Una coordenada de hace media
        // hora es de otro lote.
        manager.providers(enabledOnly = true)
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .filter { now - it.time <= freshnessWindow }
            .maxByOrNull { it.time }
            ?.let { GeoPoint(it.latitude, it.longitude, it.accuracy) }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun LocationManager.providers(enabledOnly: Boolean): List<String> =
        runCatching { getProviders(enabledOnly) }.getOrDefault(emptyList())
}

/**
 * Telemetría de campo (§19.2).
 *
 * ⚠️ **Solo registra en logcat por ahora.** El endpoint
 * `/public/v1/telemetry/field-metrics` existe en el contrato pero no hay backend
 * que lo reciba.
 *
 * Conviene ser explícito sobre lo que eso significa: **los cuatro indicadores de
 * producto no se están midiendo.** En particular la tasa de no reconocimiento y
 * la tasa de corrección, que son dos de las cuatro señales que disparan la
 * reversión de un modelo (§8.4). Hasta que esto suba datos, una regresión del
 * modelo en campo no la detecta nadie.
 */
@Singleton
class TelemetryRecorderImpl @Inject constructor() : TelemetryRecorder {

    override suspend fun recordScan(
        modelVersion: String,
        outcome: String,
        confidence: Float?,
        latencyMillis: Long,
        delegate: String,
    ) {
        Log.i(TAG, "escaneo modelo=$modelVersion resultado=$outcome conf=$confidence " +
            "latencia=${latencyMillis}ms delegate=$delegate")
    }

    override suspend fun recordCorrection(
        modelVersion: String,
        fromClassId: String?,
        toClassId: String,
    ) {
        Log.i(TAG, "corrección modelo=$modelVersion de=$fromClassId a=$toClassId")
    }

    override suspend fun recordModelUpdate(
        fromVersion: String?,
        toVersion: String,
        success: Boolean,
    ) {
        Log.i(TAG, "actualización modelo $fromVersion → $toVersion éxito=$success")
    }

    private companion object {
        const val TAG = "AgroVision.Telemetry"
    }
}
