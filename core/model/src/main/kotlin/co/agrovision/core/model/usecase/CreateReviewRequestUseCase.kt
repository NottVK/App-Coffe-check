package co.agrovision.core.model.usecase

import co.agrovision.core.common.AgroConstants
import co.agrovision.core.common.AgroResult
import co.agrovision.core.common.map
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.capture.SyncStatus
import co.agrovision.core.model.pest.CropType
import co.agrovision.core.model.pest.PlantOrgan
import co.agrovision.core.model.port.Clock
import co.agrovision.core.model.port.IdGenerator
import co.agrovision.core.model.port.LocationProvider
import co.agrovision.core.model.port.ReviewRequestRepository
import co.agrovision.core.model.port.SettingsRepository
import co.agrovision.core.model.review.ReviewImage
import co.agrovision.core.model.review.ReviewRequest
import co.agrovision.core.model.review.ReviewStatus
import javax.inject.Inject

/**
 * Crea y encola una Solicitud de Revisión.
 *
 * Dos decisiones importantes conviven aquí:
 *
 * **La solicitud se encola pase lo que pase con la cuenta.** La compuerta de
 * subida se evalúa al sincronizar, no al crear (§6.3). Un productor anónimo que
 * documenta una plaga rara no pierde ese trabajo: queda en cola y sube cuando su
 * cuenta se valide. Bloquear la creación aquí sería el error prohibido nº 6.
 *
 * **Se exige un mínimo de imágenes.** No es burocracia: un agrónomo no puede
 * dictaminar sobre una sola toma de una lesión foliar. Aceptar solicitudes mal
 * formadas gasta el recurso más escaso del sistema —el tiempo del agrónomo— y
 * termina con el productor recibiendo «imagen inutilizable» en vez de una
 * respuesta.
 */
class CreateReviewRequestUseCase @Inject constructor(
    private val requests: ReviewRequestRepository,
    private val location: LocationProvider,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
) {

    suspend operator fun invoke(request: Request): AgroResult<ReviewRequest, DomainError> {
        if (request.images.size < AgroConstants.Review.MIN_IMAGES) {
            return AgroResult.failure(
                DomainError.NotEnoughImages(
                    provided = request.images.size,
                    required = AgroConstants.Review.MIN_IMAGES,
                )
            )
        }

        val preferences = settings.current()
        val geo = if (preferences.locationEnabled) {
            location.currentLocation()?.withPrecision(preferences.locationPrecision)
        } else {
            null
        }

        val review = ReviewRequest(
            id = ids.newId(),
            captureId = request.captureId,
            images = request.images.take(AgroConstants.Review.MAX_IMAGES),
            cropType = request.cropType,
            plantOrgan = request.plantOrgan,
            noteText = request.noteText?.take(AgroConstants.Review.MAX_NOTE_CHARS),
            noteAudioPath = request.noteAudioPath,
            location = geo,
            createdAtEpochMillis = clock.nowEpochMillis(),
            status = ReviewStatus.PENDING_UPLOAD,
            resolution = null,
            sync = SyncStatus.PENDING,
        )

        return requests.save(review).map { review }
    }

    data class Request(
        val captureId: String?,
        val images: List<ReviewImage>,
        val cropType: CropType,
        val plantOrgan: PlantOrgan,
        val noteText: String?,
        val noteAudioPath: String?,
    )
}
