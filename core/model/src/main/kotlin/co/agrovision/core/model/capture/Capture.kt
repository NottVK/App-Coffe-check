package co.agrovision.core.model.capture

import co.agrovision.core.common.AgroConstants
import co.agrovision.core.model.pest.CropType
import co.agrovision.core.model.pest.PlantOrgan
import co.agrovision.core.model.scan.Embedding
import co.agrovision.core.model.scan.GateOutcome
import co.agrovision.core.model.scan.Prediction
import kotlin.math.round

/**
 * Un escaneo persistido. La fila existe antes de que se intente cualquier
 * operación remota; esa es la razón por la que una captura jamás se pierde
 * (§9.1).
 */
data class Capture(
    val id: String,
    val imagePath: String,
    val thumbnailPath: String,
    val capturedAtEpochMillis: Long,
    val location: GeoPoint?,
    val cropType: CropType?,
    val plantOrgan: PlantOrgan?,
    val modelVersion: String,
    val predictedClassId: String?,
    val calibratedConfidence: Float?,
    val top: List<Prediction>,
    /** Se persiste comprimido a float16 en Room. Viaja al servidor para
     *  alimentar la agrupación por similitud de la bandeja de revisión. */
    val embedding: Embedding?,
    val oodScore: Float,
    val gateOutcome: GateOutcome,
    /** Corrección del productor. Es dato de entrenamiento de alto valor: son
     *  las muestras donde el modelo falló sobre la distribución real. */
    val userCorrectionClassId: String?,
    val userConfirmed: Boolean,
    val sync: SyncStatus,
) {
    val isTrainingCandidate: Boolean
        get() = userConfirmed || userCorrectionClassId != null

    /** Elegible para purga: ya está a salvo en el servidor y el productor la
     *  resolvió. La copia autoritativa vive allá (§9.3). */
    fun isPurgeable(nowEpochMillis: Long): Boolean =
        sync.state == SyncState.UPLOADED &&
            isTrainingCandidate &&
            nowEpochMillis - capturedAtEpochMillis > AgroConstants.Image.RETENTION.inWholeMilliseconds
}

data class SyncStatus(
    val state: SyncState,
    val attempts: Int = 0,
    val lastError: String? = null,
    val serverId: String? = null,
    val nextAttemptAtEpochMillis: Long? = null,
) {
    val hasExhaustedRetries: Boolean get() = attempts >= AgroConstants.Sync.MAX_ATTEMPTS

    companion object {
        val PENDING = SyncStatus(SyncState.PENDING)
    }
}

enum class SyncState {
    PENDING,
    UPLOADING,
    UPLOADED,
    FAILED,

    /**
     * El productor decidió no contribuir esta captura, o revocó el
     * consentimiento de uso de imágenes para entrenamiento. Nunca se sube y se
     * excluye de futuras instantáneas de dataset (§12.2).
     */
    SKIPPED;

    val isTerminal: Boolean get() = this == UPLOADED || this == SKIPPED
}

/**
 * Coordenada del lote.
 *
 * La ubicación es opcional y se pide en contexto, nunca al arrancar. El
 * productor puede además elegir precisión reducida: en un conflicto de tierras
 * o frente a un comprador, la coordenada exacta de un lote es información
 * sensible, y no es asunto nuestro decidir por él (§12.2).
 *
 * **La coordenada se guarda siempre exacta en el dispositivo** y la precisión
 * elegida se aplica solo al construir lo que SALE del teléfono
 * ([withPrecision]). Si se redondeara al guardar, una solicitud de revisión
 * posterior ya no tendría la coordenada exacta de la foto.
 *
 * [isExact] es falso en dos casos: tras redondear a vereda y en las filas
 * anteriores a esta distinción, de las que no se sabe si se guardaron
 * redondeadas. Una coordenada no exacta jamás se envía como si lo fuera.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    /** Solo si el fix la trae (`Location.hasAltitude()`). */
    val altitudeMeters: Double? = null,
    /** El sistema marcó el fix como simulado (`isMock` / `isFromMockProvider`). */
    val isMock: Boolean = false,
    val isExact: Boolean = true,
) {
    fun withPrecision(precision: LocationPrecision): GeoPoint = when (precision) {
        LocationPrecision.EXACT -> this
        LocationPrecision.VEREDA -> {
            val grid = AgroConstants.Location.COARSE_GRID_DEGREES
            GeoPoint(
                latitude = round(latitude / grid) * grid,
                longitude = round(longitude / grid) * grid,
                accuracyMeters = null,
                altitudeMeters = null,
                isMock = isMock,
                isExact = false,
            )
        }
    }
}

enum class LocationPrecision {
    EXACT,
    VEREDA,
}
