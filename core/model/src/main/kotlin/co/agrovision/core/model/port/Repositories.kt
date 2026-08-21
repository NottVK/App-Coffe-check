package co.agrovision.core.model.port

import co.agrovision.core.common.AgroResult
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.capture.Capture
import co.agrovision.core.model.capture.GeoPoint
import co.agrovision.core.model.capture.SyncState
import co.agrovision.core.model.identity.Account
import co.agrovision.core.model.mlmodel.CalibrationParams
import co.agrovision.core.model.mlmodel.InstalledModel
import co.agrovision.core.model.mlmodel.ModelManifest
import co.agrovision.core.model.mlmodel.ModelRegistryState
import co.agrovision.core.model.mlmodel.ModelSlot
import co.agrovision.core.model.pest.PestTaxon
import co.agrovision.core.model.review.ReviewRequest
import co.agrovision.core.model.scan.RgbImage
import co.agrovision.core.model.settings.UserPreferences
import kotlinx.coroutines.flow.Flow

/**
 * Puertos de salida del dominio. Las implementaciones viven en `:core:data`,
 * `:core:database`, `:core:storage`, `:ml:registry` y `:network:api`.
 *
 * Regla que atraviesa todos: **ninguno de estos métodos habla con la red desde
 * la ruta de la interfaz**. La UI escribe en Room y observa Room; la capa de
 * sincronización lee de Room, habla con el servidor y actualiza Room (§9.1).
 * Esa separación es lo que hace que la aplicación se comporte igual con y sin
 * señal.
 */

interface CaptureRepository {

    fun observeHistory(): Flow<List<Capture>>

    fun observeById(id: String): Flow<Capture?>

    fun observePendingCount(): Flow<Int>

    /** Bytes pendientes de subir. Se muestra al productor en megabytes antes de
     *  gastar sus datos prepago (§9.4). */
    fun observePendingBytes(): Flow<Long>

    suspend fun save(capture: Capture): AgroResult<Unit, DomainError>

    suspend fun findById(id: String): Capture?

    suspend fun nextBatchToUpload(limit: Int, nowEpochMillis: Long): List<Capture>

    suspend fun updateSyncState(
        id: String,
        state: SyncState,
        serverId: String? = null,
        error: String? = null,
        nextAttemptAtEpochMillis: Long? = null,
    )

    suspend fun confirmPrediction(id: String): AgroResult<Unit, DomainError>

    suspend fun correctPrediction(id: String, classId: String): AgroResult<Unit, DomainError>

    suspend fun purgeExpired(nowEpochMillis: Long): Int
}

interface PestCatalogRepository {

    fun observeAll(): Flow<List<PestTaxon>>

    fun observeById(id: String): Flow<PestTaxon?>

    suspend fun findById(id: String): PestTaxon?

    suspend fun search(query: String): List<PestTaxon>

    suspend fun upsertAll(taxa: List<PestTaxon>)

    suspend fun deleteAll(ids: List<String>)

    /** ETag de la última sincronización incremental del catálogo (§10.3). */
    suspend fun syncCursor(): CatalogCursor

    suspend fun updateSyncCursor(cursor: CatalogCursor)
}

data class CatalogCursor(val sinceEpochMillis: Long?, val etag: String?)

interface ReviewRequestRepository {

    fun observeMine(): Flow<List<ReviewRequest>>

    fun observeById(id: String): Flow<ReviewRequest?>

    fun observeUnreadResolutions(): Flow<List<ReviewRequest>>

    suspend fun save(request: ReviewRequest): AgroResult<Unit, DomainError>

    suspend fun nextBatchToUpload(limit: Int, nowEpochMillis: Long): List<ReviewRequest>

    suspend fun applyResolution(id: String, request: ReviewRequest)

    suspend fun markResolutionRead(id: String)
}

/**
 * Almacenamiento de imágenes en `filesDir`, nunca en MediaStore: evita permisos
 * de almacenamiento, no ensucia la galería del productor y se limpia al
 * desinstalar (§9.3).
 */
interface ImageStore {

    /**
     * Comprime, elimina EXIF y genera miniatura, en ese orden. La eliminación
     * de EXIF ocurre ANTES de persistir: los metadatos llevan identificadores de
     * dispositivo y ubicación no consentida (§12.2).
     */
    suspend fun persist(captureId: String, image: RgbImage): AgroResult<StoredImage, DomainError>

    suspend fun load(path: String): RgbImage?

    suspend fun delete(paths: List<String>)

    suspend fun totalBytes(): Long
}

data class StoredImage(
    val imagePath: String,
    val thumbnailPath: String,
    val sizeBytes: Long,
)

interface SettingsRepository {
    fun observe(): Flow<UserPreferences>
    suspend fun current(): UserPreferences
    suspend fun update(transform: (UserPreferences) -> UserPreferences)
}

interface AccountRepository {

    fun observe(): Flow<Account>

    suspend fun current(): Account

    /** Vinculación progresiva: al registrarse, el `device_id` anónimo se asocia
     *  a la cuenta nueva y las contribuciones encoladas se atribuyen
     *  retroactivamente (§6.3). El historial local se conserva. */
    suspend fun linkAnonymousDevice(phone: String): AgroResult<Account, DomainError>

    suspend fun requestOtp(phone: String): AgroResult<Unit, DomainError>

    suspend fun verifyOtp(phone: String, code: String): AgroResult<Account, DomainError>

    suspend fun refreshValidationStatus(): AgroResult<Account, DomainError>

    suspend fun setTrainingConsent(granted: Boolean)
}

interface ModelRegistryRepository {

    fun observeState(): Flow<ModelRegistryState>

    suspend fun currentState(): ModelRegistryState

    /** Consulta el manifiesto. Es la ÚNICA llamada de red del subsistema de
     *  modelos, y vive fuera de los módulos aislados a propósito. */
    suspend fun fetchManifest(): AgroResult<ModelManifest, DomainError>

    /**
     * Descarga, verifica SHA-256 en flujo, verifica firma Ed25519, escribe en la
     * ranura INACTIVA y ejecuta la inferencia de humo. No conmuta el puntero.
     */
    suspend fun installToStandby(manifest: ModelManifest): AgroResult<InstalledModel, DomainError>

    /** Conmutación atómica del puntero. Único punto de no retorno (§8.3). */
    suspend fun activate(slot: ModelSlot): AgroResult<Unit, DomainError>

    /** Reversión sin descarga: el modelo previo sigue en disco (§8.4). */
    suspend fun rollBack(): AgroResult<Unit, DomainError>

    suspend fun activeCalibration(): CalibrationParams?

    suspend fun recordCheck(nowEpochMillis: Long)
}

interface LocationProvider {
    /** Devuelve null si no hay permiso, no hay consentimiento o no hay fix
     *  reciente. Nunca bloquea la captura: la ubicación es opcional. */
    suspend fun currentLocation(): GeoPoint?
}

interface ConnectivityMonitor {
    fun observe(): Flow<ConnectivityStatus>
    fun current(): ConnectivityStatus
}

data class ConnectivityStatus(
    val isOnline: Boolean,
    val isMetered: Boolean,
)

/**
 * Telemetría agregada, sin datos personales (§10.3). Alimenta los cuatro
 * indicadores de producto; el más importante es la tasa de no reconocimiento
 * por versión de modelo, que es lo que dispara una reversión (§8.4).
 */
interface TelemetryRecorder {
    suspend fun recordScan(
        modelVersion: String,
        outcome: String,
        confidence: Float?,
        latencyMillis: Long,
        delegate: String,
    )

    suspend fun recordCorrection(modelVersion: String, fromClassId: String?, toClassId: String)

    suspend fun recordModelUpdate(fromVersion: String?, toVersion: String, success: Boolean)
}
