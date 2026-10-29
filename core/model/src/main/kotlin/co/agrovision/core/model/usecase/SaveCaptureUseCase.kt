package co.agrovision.core.model.usecase

import co.agrovision.core.common.AgroResult
import co.agrovision.core.common.map
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.capture.Capture
import co.agrovision.core.model.capture.SyncState
import co.agrovision.core.model.capture.SyncStatus
import co.agrovision.core.model.pest.CropType
import co.agrovision.core.model.pest.PlantOrgan
import co.agrovision.core.model.port.CaptureRepository
import co.agrovision.core.model.port.Clock
import co.agrovision.core.model.port.IdGenerator
import co.agrovision.core.model.port.ImageStore
import co.agrovision.core.model.port.LocationProvider
import co.agrovision.core.model.port.SettingsRepository
import co.agrovision.core.model.scan.ClassificationResult
import co.agrovision.core.model.scan.Embedding
import co.agrovision.core.model.scan.Prediction
import javax.inject.Inject

/**
 * Persiste imagen comprimida, metadatos, embedding y estado de sincronización.
 *
 * **Atómico por diseño:** si falla el guardado de la imagen, no se escribe la
 * fila. Lo contrario deja una fila apuntando a un archivo que no existe, y el
 * historial muestra un hueco que ninguna sincronización posterior arregla.
 *
 * Se invoca inmediatamente después de clasificar y **antes** de mostrar el
 * resultado. Ese orden es lo que sostiene el requisito de cero pérdidas ante
 * cierre forzado: la fila existe antes de que el productor vea nada.
 */
class SaveCaptureUseCase @Inject constructor(
    private val captures: CaptureRepository,
    private val images: ImageStore,
    private val location: LocationProvider,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
) {

    suspend operator fun invoke(request: Request): AgroResult<Capture, DomainError> {
        val id = ids.newId()
        val preferences = settings.current()

        val stored = when (val persisted = images.persist(id, request.image)) {
            is AgroResult.Failure -> return persisted
            is AgroResult.Success -> persisted.value
        }

        val capture = Capture(
            id = id,
            imagePath = stored.imagePath,
            thumbnailPath = stored.thumbnailPath,
            capturedAtEpochMillis = clock.nowEpochMillis(),
            location = resolveLocation(preferences),
            cropType = request.cropType,
            plantOrgan = request.plantOrgan,
            modelVersion = request.result.modelVersionOrUnknown(),
            predictedClassId = request.result.predictedClassId(),
            calibratedConfidence = request.result.confidenceOrNull(),
            top = request.result.topPredictions(),
            embedding = request.embedding,
            oodScore = request.result.oodScoreOrZero(),
            gateOutcome = request.result.outcome,
            userCorrectionClassId = null,
            userConfirmed = false,
            // Sin consentimiento la captura se guarda para el historial local
            // del productor, pero nunca sale del dispositivo (§12.2).
            sync = if (preferences.trainingConsent) SyncStatus.PENDING
            else SyncStatus(SyncState.SKIPPED),
        )

        return captures.save(capture).map { capture }
    }

    /**
     * La ubicación nunca bloquea la captura. Sin permiso, sin consentimiento o
     * sin fix reciente, la captura se guarda igual: un escaneo sin coordenada
     * sigue siendo útil; un escaneo perdido por esperar al GPS, no.
     *
     * Se guarda la coordenada EXACTA aunque el productor haya elegido vereda: esa
     * preferencia se aplica al enviar (`SyncPendingUseCase`), no al guardar. Así
     * una solicitud de revisión posterior conserva la coordenada de la foto.
     */
    private suspend fun resolveLocation(
        preferences: co.agrovision.core.model.settings.UserPreferences,
    ) = if (!preferences.locationEnabled) null else location.currentLocation()

    data class Request(
        val image: co.agrovision.core.model.scan.RgbImage,
        val result: ClassificationResult,
        val embedding: Embedding?,
        val cropType: CropType?,
        val plantOrgan: PlantOrgan?,
    )
}

// ── Proyecciones del resultado hacia la fila persistida ──────────────────────

internal const val UNKNOWN_MODEL_VERSION = "desconocida"

internal fun ClassificationResult.modelVersionOrUnknown(): String = when (this) {
    is ClassificationResult.Identified -> modelVersion
    is ClassificationResult.NotAPest -> modelVersion
    is ClassificationResult.Uncertain -> modelVersion
    is ClassificationResult.OutOfDomain -> modelVersion
    is ClassificationResult.QualityRejected -> UNKNOWN_MODEL_VERSION
}

internal fun ClassificationResult.predictedClassId(): String? = when (this) {
    is ClassificationResult.Identified -> top.firstOrNull()?.classId
    is ClassificationResult.Uncertain -> candidates.firstOrNull()?.classId
    else -> null
}

internal fun ClassificationResult.confidenceOrNull(): Float? = when (this) {
    is ClassificationResult.Identified -> confidence
    is ClassificationResult.NotAPest -> confidence
    else -> null
}

internal fun ClassificationResult.topPredictions(): List<Prediction> = when (this) {
    is ClassificationResult.Identified -> top
    is ClassificationResult.Uncertain -> candidates
    else -> emptyList()
}

internal fun ClassificationResult.oodScoreOrZero(): Float = when (this) {
    is ClassificationResult.Identified -> oodScore
    is ClassificationResult.Uncertain -> oodScore
    is ClassificationResult.OutOfDomain -> oodScore
    else -> 0f
}
