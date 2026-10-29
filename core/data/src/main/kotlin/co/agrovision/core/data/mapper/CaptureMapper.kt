package co.agrovision.core.data.mapper

import co.agrovision.core.common.Float16Codec
import co.agrovision.core.database.entity.CaptureEntity
import co.agrovision.core.model.capture.Capture
import co.agrovision.core.model.capture.GeoPoint
import co.agrovision.core.model.capture.SyncState
import co.agrovision.core.model.capture.SyncStatus
import co.agrovision.core.model.pest.CropType
import co.agrovision.core.model.pest.PlantOrgan
import co.agrovision.core.model.scan.Embedding
import co.agrovision.core.model.scan.GateOutcome
import co.agrovision.core.model.scan.Prediction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Traducción entre la fila de Room y la entidad de dominio.
 *
 * Existe para que `CaptureEntity` no salga nunca de la capa de datos. El dominio
 * no debe saber que el `top3` viaja como JSON en una columna de texto ni que el
 * embedding va comprimido a float16: son decisiones de almacenamiento, y atarlas
 * al dominio significaría que cambiar de formato obliga a tocar los casos de uso.
 */

@Serializable
private data class PredictionJson(
    val classId: String,
    val label: String,
    val probability: Float,
    val pestId: String? = null,
)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun CaptureEntity.toDomain(): Capture = Capture(
    id = id,
    imagePath = imagePath,
    thumbnailPath = thumbnailPath,
    capturedAtEpochMillis = capturedAt,
    location = latitude?.let { lat ->
        longitude?.let { lon ->
            GeoPoint(
                latitude = lat,
                longitude = lon,
                accuracyMeters = locationAccuracyM,
                altitudeMeters = altitudeM,
                isMock = locationIsMock,
                isExact = locationIsExact,
            )
        }
    },
    cropType = cropType?.let { name -> CropType.entries.firstOrNull { it.name == name } },
    plantOrgan = plantOrgan?.let { name -> PlantOrgan.entries.firstOrNull { it.name == name } },
    modelVersion = modelVersion,
    predictedClassId = predictedClassId,
    calibratedConfidence = calibratedConfidence,
    top = top3Json.parsePredictions(),
    embedding = embedding?.let { Embedding(Float16Codec.decode(it)) },
    oodScore = oodScore,
    gateOutcome = GateOutcome.parse(gateOutcome),
    userCorrectionClassId = userCorrectionClassId,
    userConfirmed = userConfirmed,
    sync = SyncStatus(
        state = SyncState.entries.firstOrNull { it.name == syncState } ?: SyncState.PENDING,
        attempts = syncAttempts,
        lastError = lastSyncError,
        serverId = serverId,
        nextAttemptAtEpochMillis = nextAttemptAt,
    ),
)

fun Capture.toEntity(sizeBytes: Long = 0L): CaptureEntity = CaptureEntity(
    id = id,
    imagePath = imagePath,
    thumbnailPath = thumbnailPath,
    capturedAt = capturedAtEpochMillis,
    latitude = location?.latitude,
    longitude = location?.longitude,
    locationAccuracyM = location?.accuracyMeters,
    cropType = cropType?.name,
    plantOrgan = plantOrgan?.name,
    modelVersion = modelVersion,
    predictedClassId = predictedClassId,
    calibratedConfidence = calibratedConfidence,
    top3Json = top.takeIf { it.isNotEmpty() }?.let { predictions ->
        json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(PredictionJson.serializer()),
            predictions.map { PredictionJson(it.classId, it.label, it.probability, it.pestId) },
        )
    },
    embedding = embedding?.let { Float16Codec.encode(it.values) },
    oodScore = oodScore,
    gateOutcome = gateOutcome.name,
    userCorrectionClassId = userCorrectionClassId,
    userConfirmed = userConfirmed,
    syncState = sync.state.name,
    syncAttempts = sync.attempts,
    lastSyncError = sync.lastError,
    nextAttemptAt = sync.nextAttemptAtEpochMillis,
    serverId = sync.serverId,
    sizeBytes = sizeBytes,
    altitudeM = location?.altitudeMeters,
    locationIsMock = location?.isMock ?: false,
    locationIsExact = location?.isExact ?: false,
)

/**
 * JSON malformado devuelve lista vacía en lugar de lanzar.
 *
 * Una fila cuyo `top3_json` se corrompió sigue siendo una captura válida: tiene
 * su imagen, su fecha y su predicción principal en columnas aparte. Lanzar aquí
 * haría que el historial entero dejara de cargar por una fila mala.
 */
private fun String?.parsePredictions(): List<Prediction> {
    if (isNullOrBlank()) return emptyList()
    return runCatching {
        json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(PredictionJson.serializer()),
            this,
        ).map { Prediction(it.classId, it.label, it.probability, it.pestId) }
    }.getOrDefault(emptyList())
}
