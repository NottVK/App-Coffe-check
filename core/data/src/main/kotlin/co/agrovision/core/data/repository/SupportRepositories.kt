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
import co.agrovision.core.datastore.SessionTokenStore
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.capture.GeoPoint
import co.agrovision.core.model.identity.Account
import co.agrovision.core.model.identity.CoffeeVariety
import co.agrovision.core.model.identity.PlantingAge
import co.agrovision.core.model.identity.ProducerProfile
import co.agrovision.core.model.identity.UserTier
import co.agrovision.core.model.port.AccountGateway
import co.agrovision.core.model.port.AccountRepository
import co.agrovision.core.model.port.Clock
import co.agrovision.core.model.port.ProfileRegistration
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
 * El registro NO es un muro: el nivel T0 cubre escanear sin límite, catálogo
 * completo, historial local, correcciones y OTA de modelo. La cuenta solo hace
 * falta para que una Solicitud de Revisión llegue a un agrónomo, y hasta entonces
 * la cola local la retiene sin perder nada (§6.3).
 *
 * Niveles con el backend actual:
 *  - OTP validado → T1. Hay JWT, pero el backend no acepta solicitudes todavía.
 *  - Perfil registrado en `productores/me` → T2. Es lo único que el backend exige
 *    para aceptar solicitudes; no hay aprobación de administrador.
 */
@Singleton
class AccountRepositoryImpl @Inject constructor(
    private val store: AccountStore,
    private val tokens: SessionTokenStore,
    private val gateway: AccountGateway,
    private val clock: Clock,
) : AccountRepository {

    override fun observe(): Flow<Account> = store.observe()

    override suspend fun current(): Account = store.current()

    /** El backend aún no expone la vinculación del `device_id`. */
    override suspend fun linkAnonymousDevice(phone: String): AgroResult<Account, DomainError> =
        AgroResult.failure(DomainError.NoConnection)

    override suspend fun requestOtp(phone: String): AgroResult<Unit, DomainError> =
        gateway.requestOtp(phone.trim())

    override suspend fun verifyOtp(
        phone: String,
        code: String,
    ): AgroResult<Account, DomainError> {
        val normalizedPhone = phone.trim()
        val token = when (val result = gateway.verifyOtp(normalizedPhone, code.trim())) {
            is AgroResult.Failure -> return result
            is AgroResult.Success -> result.value
        }
        tokens.save(token)

        val now = clock.nowEpochMillis()
        store.update { account ->
            account.copy(
                tier = maxOf(account.tier, UserTier.T1_REGISTRADO),
                phone = normalizedPhone,
                registeredAtEpochMillis = account.registeredAtEpochMillis ?: now,
                // La cola retenida por el 401 se reanuda en la próxima
                // sincronización: la compuerta vuelve a dejar pasar.
                sessionExpired = false,
            )
        }

        // Un productor que reinstala ya tiene perfil: se le devuelve a T2 sin
        // pedirle otra vez los datos de la finca. Si esto falla la sesión sigue
        // siendo válida; el perfil se vuelve a consultar más tarde.
        return when (val refreshed = refreshValidationStatus()) {
            is AgroResult.Success -> refreshed
            is AgroResult.Failure -> AgroResult.success(store.current())
        }
    }

    override suspend fun refreshValidationStatus(): AgroResult<Account, DomainError> {
        if (tokens.accessToken() == null) return AgroResult.failure(DomainError.NotSignedIn)
        return when (val result = gateway.fetchProducerProfile()) {
            is AgroResult.Failure -> expireSessionOn(result)
            is AgroResult.Success -> AgroResult.success(applyProfile(result.value))
        }
    }

    override suspend fun saveProducerProfile(
        profile: ProducerProfile,
    ): AgroResult<Account, DomainError> {
        if (tokens.accessToken() == null) return AgroResult.failure(DomainError.NotSignedIn)
        val invalid = profile.invalidFields()
        if (invalid.isNotEmpty()) {
            return AgroResult.failure(DomainError.InvalidProducerProfile(invalid))
        }
        val clean = profile.trimmed()
        return when (val result = gateway.saveProducerProfile(clean)) {
            is AgroResult.Failure -> expireSessionOn(result)
            is AgroResult.Success -> when (result.value) {
                ProfileRegistration.CREATED -> AgroResult.success(applyProfile(clean))
                // 409: ya había perfil y no se puede editar. Vale el del
                // servidor, no el que se acaba de escribir.
                ProfileRegistration.ALREADY_REGISTERED -> refreshValidationStatus()
            }
        }
    }

    /**
     * Local primero, porque es lo que el productor eligió. Pero si tiene sesión,
     * la revocación tiene que llegar al servidor para excluir sus fotos de las
     * siguientes instantáneas del dataset: si falla, se devuelve el error.
     */
    override suspend fun setTrainingConsent(granted: Boolean): AgroResult<Unit, DomainError> {
        store.update { it.copy(trainingConsent = granted) }
        if (tokens.accessToken() == null) {
            // Anónimo: el consentimiento solo existe en el teléfono. Registrado
            // con la sesión vencida: el servidor aún no lo sabe, y la interfaz
            // tiene que pedir que entre de nuevo en vez de darlo por hecho.
            return if (store.current().tier == UserTier.T0_ANONIMO) {
                AgroResult.unit
            } else {
                AgroResult.failure(DomainError.NotSignedIn)
            }
        }
        return expireSessionOn(gateway.setTrainingConsent(granted))
    }

    override suspend fun markSessionExpired() {
        tokens.clear()
        store.update { account ->
            // Un anónimo no tiene sesión que vencer.
            account.copy(sessionExpired = account.tier != UserTier.T0_ANONIMO)
        }
    }

    override suspend fun updateFarmDetails(variety: CoffeeVariety, age: PlantingAge) {
        store.update { it.copy(coffeeVariety = variety, plantingAge = age) }
    }

    /** Un 401 en cualquier llamada autenticada vence la sesión. */
    private suspend fun <T> expireSessionOn(
        result: AgroResult<T, DomainError>,
    ): AgroResult<T, DomainError> {
        if (result is AgroResult.Failure && result.error == DomainError.NotSignedIn) {
            markSessionExpired()
        }
        return result
    }

    private suspend fun applyProfile(profile: ProducerProfile?): Account {
        val now = clock.nowEpochMillis()
        store.update { account ->
            if (profile == null) {
                account.copy(tier = UserTier.T1_REGISTRADO)
            } else {
                account.copy(
                    tier = UserTier.T2_VALIDADO,
                    displayName = profile.fullName,
                    farmName = profile.farmName,
                    vereda = profile.vereda,
                    municipality = profile.municipality,
                    trainingConsent = profile.trainingConsent,
                    validatedAtEpochMillis = account.validatedAtEpochMillis ?: now,
                )
            }
        }
        return store.current()
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
