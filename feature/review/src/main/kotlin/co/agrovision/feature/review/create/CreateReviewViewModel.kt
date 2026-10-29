package co.agrovision.feature.review.create

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.agrovision.core.common.AgroResult
import co.agrovision.core.common.MessageKey
import co.agrovision.core.common.UiText
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.identity.CoffeeVariety
import co.agrovision.core.model.identity.PlantingAge
import co.agrovision.core.model.identity.VARIETY_PREFIX
import co.agrovision.core.model.pest.CropType
import co.agrovision.core.model.pest.PlantOrgan
import co.agrovision.core.model.port.AccountRepository
import co.agrovision.core.model.port.CaptureRepository
import co.agrovision.core.model.port.IdGenerator
import co.agrovision.core.model.port.ImageStore
import co.agrovision.core.model.review.CaptureAngle
import co.agrovision.core.model.review.ReviewImage
import co.agrovision.core.model.scan.RgbImage
import co.agrovision.core.model.usecase.CreateReviewRequestUseCase
import co.agrovision.core.model.usecase.LocationShareConsentUseCase
import co.agrovision.feature.review.components.GUIDED_ANGLES
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Una foto ya escrita en disco (sin EXIF, con miniatura). */
data class ReviewPhoto(val id: String, val imagePath: String, val thumbnailPath: String)

sealed interface CreateStage {
    /** Cámara abierta pidiendo esta toma. */
    data class Capturing(val angle: CaptureAngle) : CreateStage

    /** Escribiendo la foto a disco. Unos cientos de milisegundos. */
    data class Saving(val angle: CaptureAngle) : CreateStage

    /** La foto recién tomada, para aceptarla o repetirla. */
    data class Confirming(val angle: CaptureAngle, val photo: ReviewPhoto) : CreateStage

    data object Form : CreateStage
}

data class CreateReviewState(
    val stage: CreateStage = CreateStage.Capturing(GUIDED_ANGLES.first()),
    /** La foto del escaneo que no se reconoció. Abre la solicitud. */
    val original: ReviewPhoto? = null,
    val shots: Map<CaptureAngle, ReviewPhoto> = emptyMap(),
    val cropType: CropType = CropType.CAFE,
    val plantOrgan: PlantOrgan = PlantOrgan.HOJA,
    val note: String = "",
    val variety: CoffeeVariety? = null,
    val plantingAge: PlantingAge? = null,
    val submitting: Boolean = false,
    val error: UiText? = null,
    /** Diálogo de ubicación abierto, si lo hay. Ver [LocationPrompt]. */
    val locationPrompt: LocationPrompt? = null,
) {
    val photoCount: Int get() = shots.size + if (original != null) 1 else 0

    /** Índice de la toma en curso, para la barra de pasos. */
    fun stepOf(angle: CaptureAngle): Int = GUIDED_ANGLES.indexOf(angle)
}

/**
 * Lo que la solicitud necesita del productor antes de poder enviarse con la
 * ubicación exacta. Todo se pide aquí, en contexto, nunca al arrancar la app.
 */
sealed interface LocationPrompt {
    /** Explica quién verá la ubicación y para qué, y pide aceptar. */
    data object Consent : LocationPrompt

    /** La foto del escaneo no tiene coordenada exacta fiable. */
    data object ConfirmStillAtLot : LocationPrompt

    /** Falta el permiso de ubicación precisa del sistema. */
    data object PermissionDenied : LocationPrompt

    /** El GPS del teléfono está apagado. */
    data object GpsOff : LocationPrompt

    /** No llegó un fix en 15 s. */
    data object Timeout : LocationPrompt
}

sealed interface CreateReviewIntent {
    data class Captured(val image: RgbImage) : CreateReviewIntent
    data object AcceptShot : CreateReviewIntent
    data object RetakeShot : CreateReviewIntent
    data class RetakeAngle(val angle: CaptureAngle) : CreateReviewIntent
    data class SetCrop(val crop: CropType) : CreateReviewIntent
    data class SetOrgan(val organ: PlantOrgan) : CreateReviewIntent
    data class SetVariety(val variety: CoffeeVariety) : CreateReviewIntent
    data class SetNote(val note: String) : CreateReviewIntent
    data object Submit : CreateReviewIntent
    data object AcceptLocationConsent : CreateReviewIntent
    data object ConfirmStillAtLot : CreateReviewIntent

    /** Reintenta el envío tras dar el permiso, activar el GPS o por falta de señal. */
    data object RetryLocation : CreateReviewIntent

    /** Cierra el diálogo de ubicación sin enviar nada. */
    data object DismissLocationPrompt : CreateReviewIntent
    data object Cancel : CreateReviewIntent
}

sealed interface CreateReviewEffect {
    /** Guardada. Falta entrar con OTP o completar el perfil antes de enviarla. */
    data class SignIn(val reviewId: String) : CreateReviewEffect
    data class Sent(val reviewId: String) : CreateReviewEffect
    data object Closed : CreateReviewEffect
}

/**
 * Solicitud de Revisión a partir de un escaneo que no se reconoció.
 *
 * La foto del escaneo abre la solicitud y se piden **cuatro más, guiadas**: la
 * planta entera, la hoja por arriba, la hoja por abajo y el daño de cerca. Cinco
 * en total, el máximo que acepta una solicitud. Un agrónomo no dictamina sobre
 * una sola toma de una lesión foliar.
 *
 * Cada toma se escribe a disco en cuanto se dispara (no se retiene el bitmap:
 * cinco fotos en memoria en un teléfono de 2 GB es buscarse un cierre), y se
 * borra si se repite o si se cancela la solicitud.
 *
 * La solicitud se **guarda antes** de pedir cuenta: si el productor abandona el
 * registro a medias, no pierde las fotos (§6.3).
 */
@HiltViewModel
class CreateReviewViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val captures: CaptureRepository,
    private val images: ImageStore,
    private val ids: IdGenerator,
    private val account: AccountRepository,
    private val createReviewRequest: CreateReviewRequestUseCase,
    private val locationConsent: LocationShareConsentUseCase,
) : ViewModel() {

    /** `null` cuando la solicitud se abre desde «Nueva solicitud», sin un escaneo previo. */
    private val captureId: String? = savedState.get<String>("captureId")?.takeIf { it != "ninguna" }

    private val _state = MutableStateFlow(CreateReviewState())
    val state: StateFlow<CreateReviewState> = _state.asStateFlow()

    private val _effects = Channel<CreateReviewEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /** Al repetir una toma desde el formulario, se vuelve al formulario y no a la
     *  siguiente toma. */
    private var retakingFromForm = false

    /** El productor dijo que sigue en el lote (solo importa si la captura no
     *  tiene coordenada exacta). Se conserva para los reintentos. */
    private var confirmedStillAtLot = false

    init {
        viewModelScope.launch {
            val capture = captureId?.let { captures.findById(it) }
            val current = account.current()
            _state.update { state ->
                state.copy(
                    original = capture?.let {
                        ReviewPhoto(id = ids.newId(), imagePath = it.imagePath, thumbnailPath = it.thumbnailPath)
                    },
                    cropType = capture?.cropType ?: state.cropType,
                    plantOrgan = capture?.plantOrgan ?: state.plantOrgan,
                    variety = current.coffeeVariety,
                    plantingAge = current.plantingAge,
                )
            }
        }
    }

    fun onIntent(intent: CreateReviewIntent) {
        when (intent) {
            is CreateReviewIntent.Captured -> persistShot(intent.image)
            CreateReviewIntent.AcceptShot -> acceptShot()
            CreateReviewIntent.RetakeShot -> retakeShot()
            is CreateReviewIntent.RetakeAngle -> {
                retakingFromForm = true
                _state.update { it.copy(stage = CreateStage.Capturing(intent.angle)) }
            }
            is CreateReviewIntent.SetCrop -> _state.update { it.copy(cropType = intent.crop) }
            is CreateReviewIntent.SetOrgan -> _state.update { it.copy(plantOrgan = intent.organ) }
            is CreateReviewIntent.SetVariety -> _state.update { it.copy(variety = intent.variety) }
            is CreateReviewIntent.SetNote -> _state.update { it.copy(note = intent.note) }
            CreateReviewIntent.Submit -> submit()
            CreateReviewIntent.AcceptLocationConsent -> acceptLocationConsent()
            CreateReviewIntent.ConfirmStillAtLot -> {
                confirmedStillAtLot = true
                send()
            }
            CreateReviewIntent.RetryLocation -> send()
            CreateReviewIntent.DismissLocationPrompt -> declineLocation()
            CreateReviewIntent.Cancel -> cancel()
        }
    }

    private fun persistShot(image: RgbImage) {
        val angle = (_state.value.stage as? CreateStage.Capturing)?.angle ?: return
        _state.update { it.copy(stage = CreateStage.Saving(angle)) }
        viewModelScope.launch {
            val id = ids.newId()
            when (val stored = images.persist(id, image)) {
                is AgroResult.Success -> _state.update {
                    it.copy(
                        stage = CreateStage.Confirming(
                            angle = angle,
                            photo = ReviewPhoto(id, stored.value.imagePath, stored.value.thumbnailPath),
                        ),
                        error = null,
                    )
                }
                is AgroResult.Failure -> _state.update {
                    it.copy(stage = CreateStage.Capturing(angle), error = UiText.Message(stored.error.message))
                }
            }
        }
    }

    private fun acceptShot() {
        val confirming = _state.value.stage as? CreateStage.Confirming ?: return
        val replaced = _state.value.shots[confirming.angle]
        val shots = _state.value.shots + (confirming.angle to confirming.photo)
        val next = GUIDED_ANGLES.firstOrNull { it !in shots }?.takeUnless { retakingFromForm }
        retakingFromForm = false

        _state.update {
            it.copy(
                shots = shots,
                stage = next?.let { angle -> CreateStage.Capturing(angle) } ?: CreateStage.Form,
            )
        }
        replaced?.let { deleteFiles(listOf(it)) }
    }

    private fun retakeShot() {
        val confirming = _state.value.stage as? CreateStage.Confirming ?: return
        deleteFiles(listOf(confirming.photo))
        _state.update { it.copy(stage = CreateStage.Capturing(confirming.angle)) }
    }

    /**
     * Antes de enviar se pide, en contexto, compartir la ubicación exacta. Sin
     * aceptarlo no se lee el GPS ni se envía nada.
     */
    private fun submit() {
        if (_state.value.submitting) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            if (locationConsent.isGranted()) {
                send()
            } else {
                _state.update { it.copy(locationPrompt = LocationPrompt.Consent) }
            }
        }
    }

    private fun acceptLocationConsent() {
        _state.update { it.copy(locationPrompt = null) }
        viewModelScope.launch {
            locationConsent.grant()
            send()
        }
    }

    /** Cancelar el diálogo no envía nada ni pierde las fotos: la solicitud sigue
     *  en el formulario y el productor entiende por qué no salió. */
    private fun declineLocation() {
        _state.update {
            it.copy(
                locationPrompt = null,
                submitting = false,
                error = UiText.Message(it.locationPrompt.explanationKey()),
            )
        }
    }

    private fun send() {
        val state = _state.value
        _state.update { it.copy(submitting = true, error = null, locationPrompt = null) }

        viewModelScope.launch {
            // La foto del escaneo es la que no se reconoció: va primero y como
            // detalle, que es como el productor la encuadró.
            val photos = listOfNotNull(state.original?.let { it to CaptureAngle.DETALLE }) +
                GUIDED_ANGLES.mapNotNull { angle -> state.shots[angle]?.let { it to angle } }

            val result = createReviewRequest(
                CreateReviewRequestUseCase.Request(
                    captureId = captureId,
                    images = photos.map { (photo, angle) ->
                        ReviewImage(
                            id = photo.id,
                            imagePath = photo.imagePath,
                            thumbnailPath = photo.thumbnailPath,
                            angle = angle,
                            embedding = null,
                        )
                    },
                    cropType = state.cropType,
                    plantOrgan = state.plantOrgan,
                    // La variedad elegida viaja al agrónomo en la nota, igual que
                    // la del perfil (ver `withFarmContext`).
                    noteText = listOfNotNull(
                        state.variety?.let { "$VARIETY_PREFIX${it.label}" },
                        state.note.trim().takeIf { it.isNotEmpty() },
                    ).joinToString(NOTE_SEPARATOR).takeIf { it.isNotEmpty() },
                    noteAudioPath = null,
                    confirmedStillAtLot = confirmedStillAtLot,
                )
            )

            when (result) {
                is AgroResult.Failure -> _state.update { current ->
                    val prompt = result.error.toLocationPrompt()
                    current.copy(
                        submitting = false,
                        locationPrompt = prompt,
                        error = if (prompt == null) UiText.Message(result.error.message) else null,
                    )
                }
                is AgroResult.Success -> {
                    val current = account.current()
                    val effect = if (current.needsSignIn || current.needsProfile) {
                        CreateReviewEffect.SignIn(result.value.id)
                    } else {
                        CreateReviewEffect.Sent(result.value.id)
                    }
                    _effects.send(effect)
                }
            }
        }
    }

    /** Cancelar borra las tomas guiadas. La del escaneo NO: pertenece a la
     *  captura y sigue en el historial. */
    private fun cancel() {
        val state = _state.value
        val pending = state.shots.values.toList() +
            listOfNotNull((state.stage as? CreateStage.Confirming)?.photo)
        deleteFiles(pending)
        viewModelScope.launch { _effects.send(CreateReviewEffect.Closed) }
    }

    private fun deleteFiles(photos: List<ReviewPhoto>) {
        if (photos.isEmpty()) return
        viewModelScope.launch {
            images.delete(photos.flatMap { listOf(it.imagePath, it.thumbnailPath) })
        }
    }
}

/** Por qué no salió la solicitud, según el diálogo que el productor cerró. */
private fun LocationPrompt?.explanationKey(): MessageKey = when (this) {
    LocationPrompt.PermissionDenied -> MessageKey.LOCATION_PERMISSION_DENIED
    LocationPrompt.GpsOff -> MessageKey.LOCATION_GPS_OFF
    LocationPrompt.Timeout -> MessageKey.LOCATION_FIX_TIMEOUT
    LocationPrompt.ConfirmStillAtLot -> MessageKey.LOCATION_NEEDS_CONFIRMATION
    LocationPrompt.Consent, null -> MessageKey.LOCATION_CONSENT_REQUIRED
}

private fun DomainError.toLocationPrompt(): LocationPrompt? = when (this) {
    DomainError.LocationConsentRequired -> LocationPrompt.Consent
    DomainError.LocationNeedsConfirmation -> LocationPrompt.ConfirmStillAtLot
    DomainError.LocationPermissionDenied -> LocationPrompt.PermissionDenied
    DomainError.GpsDisabled -> LocationPrompt.GpsOff
    DomainError.LocationFixTimeout -> LocationPrompt.Timeout
    else -> null
}

/** Separa la variedad de lo que escribió el productor en la nota. */
private val NOTE_SEPARATOR = "${Char(10)}${Char(10)}"
