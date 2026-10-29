package co.agrovision.core.model.usecase

import co.agrovision.core.common.AgroConstants
import co.agrovision.core.common.AgroResult
import co.agrovision.core.common.map
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.capture.GeoPoint
import co.agrovision.core.model.capture.SyncStatus
import co.agrovision.core.model.pest.CropType
import co.agrovision.core.model.pest.PlantOrgan
import co.agrovision.core.model.port.CaptureRepository
import co.agrovision.core.model.port.Clock
import co.agrovision.core.model.port.FixResult
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
 *
 * **La solicitud viaja siempre con la ubicación EXACTA**, sin importar la
 * preferencia de precisión de las capturas (esa solo afecta a lo que se envía de
 * capturas y detecciones). Sin esa coordenada el agrónomo no sabe de qué lote
 * habla, así que no se crea la solicitud a medias:
 *  - sin consentimiento en contexto del productor, no se lee la ubicación;
 *  - con captura, se usa la coordenada exacta de la foto; si esa captura no la
 *    tiene (filas antiguas, ubicación apagada) se exige que el productor
 *    confirme que sigue en el lote y entonces se toma un fix nuevo;
 *  - sin captura, se toma un fix nuevo de GPS.
 * Una coordenada aproximada nunca se hace pasar por exacta.
 */
class CreateReviewRequestUseCase @Inject constructor(
    private val requests: ReviewRequestRepository,
    private val captures: CaptureRepository,
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

        // El consentimiento va ANTES de leer el GPS: sin él no se toca la ubicación.
        if (settings.current().locationShareConsentAtEpochMillis == null) {
            return AgroResult.failure(DomainError.LocationConsentRequired)
        }

        val geo = when (val resolved = resolveExactLocation(request)) {
            is AgroResult.Failure -> return resolved
            is AgroResult.Success -> resolved.value
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

    private suspend fun resolveExactLocation(
        request: Request,
    ): AgroResult<GeoPoint, DomainError> {
        if (request.captureId != null) {
            val fromPhoto = captures.findById(request.captureId)?.location?.takeIf { it.isExact }
            if (fromPhoto != null) return AgroResult.success(fromPhoto)
            // La foto no tiene coordenada exacta fiable: solo se sigue si el
            // productor confirmó que sigue en el lote.
            if (!request.confirmedStillAtLot) {
                return AgroResult.failure(DomainError.LocationNeedsConfirmation)
            }
        }
        return when (val fix = location.acquireExactFix()) {
            is FixResult.Fix -> AgroResult.success(fix.point.copy(isExact = true))
            FixResult.PermissionDenied -> AgroResult.failure(DomainError.LocationPermissionDenied)
            FixResult.GpsDisabled -> AgroResult.failure(DomainError.GpsDisabled)
            FixResult.TimedOut -> AgroResult.failure(DomainError.LocationFixTimeout)
        }
    }

    data class Request(
        val captureId: String?,
        val images: List<ReviewImage>,
        val cropType: CropType,
        val plantOrgan: PlantOrgan,
        val noteText: String?,
        val noteAudioPath: String?,
        /** El productor confirmó que sigue en el lote de la captura (solo se usa
         *  si la captura no tiene una coordenada exacta). */
        val confirmedStillAtLot: Boolean = false,
    )
}
