package co.agrovision.feature.scan

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.agrovision.core.common.AgroDispatcher
import co.agrovision.core.common.AgroResult
import co.agrovision.core.common.Dispatcher
import co.agrovision.core.common.MessageKey
import co.agrovision.core.common.UiText
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.capture.Capture
import co.agrovision.core.model.port.PestClassifier
import co.agrovision.core.model.port.TelemetryRecorder
import co.agrovision.core.model.scan.ClassificationResult
import co.agrovision.core.model.scan.RgbImage
import co.agrovision.core.model.usecase.ClassifyImageUseCase
import co.agrovision.core.model.usecase.ConfirmPredictionUseCase
import co.agrovision.core.model.usecase.CorrectPredictionUseCase
import co.agrovision.core.model.usecase.SaveCaptureUseCase
import co.agrovision.core.model.port.ZoneRuleRepository
import co.agrovision.core.model.zone.LeafDamageEstimator
import co.agrovision.core.model.zone.LeafDamageResult
import co.agrovision.core.model.zone.ZoneDefaults
import co.agrovision.core.model.zone.ZoneRuleTypes
import co.agrovision.core.model.zone.estimateZone
import co.agrovision.core.model.zone.isValidFor
import co.agrovision.core.model.zone.ZoneRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel de la pantalla de escaneo.
 *
 * Expone **un solo** `onIntent`. La alternativa —un método público por acción—
 * hace que la pantalla pueda invocar cosas en órdenes que el estado no admite, y
 * el compilador no ayuda a impedirlo.
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val classifyImage: ClassifyImageUseCase,
    private val saveCapture: SaveCaptureUseCase,
    private val confirmPrediction: ConfirmPredictionUseCase,
    private val correctPrediction: CorrectPredictionUseCase,
    private val classifier: PestClassifier,
    private val telemetry: TelemetryRecorder,
    private val zoneRules: ZoneRuleRepository,
    @Dispatcher(AgroDispatcher.Default) private val computation: CoroutineDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val _effects = Channel<ScanEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /** Observación de la regla de zona del resultado en pantalla; ver [watchZone]. */
    private var zoneWatcher: Job? = null

    /** Cálculo del daño en la hoja de la captura en pantalla; ver [estimateLeafDamage]. */
    private var damageJob: Job? = null

    fun onIntent(intent: ScanIntent) {
        when (intent) {
            ScanIntent.StartCamera -> warmUp()
            is ScanIntent.Capture -> analyze(intent.image)
            is ScanIntent.ConfirmPrediction -> confirm(intent.captureId)
            is ScanIntent.CorrectPrediction -> correct(intent.captureId, intent.classId)
            is ScanIntent.CreateReviewRequest -> emit(
                ScanEffect.NavigateToReviewRequest(intent.captureId)
            )
            ScanIntent.Retry -> backToPreview()
            ScanIntent.DismissResult -> backToPreview()
            is ScanIntent.EstimateZone -> estimate(intent)
        }
    }

    private fun warmUp() {
        if (_state.value !is ScanUiState.Idle) return
        _state.value = ScanUiState.WarmingUp
        viewModelScope.launch {
            classifier.warmUp()
            // Sin modelo NO se bloquea la cámara: se entra igual, marcando que el
            // reconocedor no está. Documentar la plaga es la función que no puede
            // depender de la red.
            _state.value = ScanUiState.Preview(
                guidance = FramingGuidance.LISTO,
                modelReady = classifier.activeModelVersion() != null,
            )
        }
    }

    /**
     * Clasificar y **persistir antes de mostrar**.
     *
     * Ese orden es lo que sostiene el requisito de cero pérdidas ante cierre
     * forzado: cuando el productor ve el resultado, la fila ya existe en Room. Si
     * se mostrara primero, un cierre en ese instante perdería la captura y él
     * creería que quedó guardada.
     */
    private fun analyze(image: RgbImage) {
        stopWatchingZone()
        cancelLeafDamage()
        _state.value = ScanUiState.Analyzing
        viewModelScope.launch {
            val classification = when (val result = classifyImage(image)) {
                is AgroResult.Failure -> when (result.error) {
                    // Sin modelo la app no sabe qué es, que es exactamente lo
                    // que significa «fuera de dominio». La captura se guarda y se
                    // ofrece revisión: el mismo camino que una plaga desconocida.
                    is DomainError.ModelUnavailable -> ClassificationResult.OutOfDomain(
                        oodScore = 0f,
                        modelVersion = NO_MODEL_VERSION,
                        latencyMillis = 0L,
                    )
                    else -> {
                        Log.w(TAG, "el análisis falló: ${result.error}")
                        _state.value = result.error.toErrorState()
                        return@launch
                    }
                }
                is AgroResult.Success -> result.value
            }

            // Cada escaneo cuenta para saber cómo se porta el modelo en campo, haya o no
            // consentimiento de entrenamiento: la métrica es anónima y no lleva la imagen.
            recordScan(classification)

            // Una imagen rechazada por calidad NO se persiste: no aporta nada al
            // dataset y ocuparía espacio en un teléfono que suele estar lleno.
            if (classification is ClassificationResult.QualityRejected) {
                Log.i(
                    TAG,
                    "compuerta 1 rechazó: ${classification.hint} " +
                        "medido=${classification.measured} umbral=${classification.threshold}",
                )
                _state.value = ScanUiState.QualityRejected(
                    hint = classification.hint,
                    measured = classification.measured,
                    threshold = classification.threshold,
                )
                return@launch
            }

            val saved = saveCapture(
                SaveCaptureUseCase.Request(
                    image = image,
                    result = classification,
                    embedding = null,
                    cropType = null,
                    plantOrgan = null,
                )
            )

            when (saved) {
                is AgroResult.Failure -> _state.value = saved.error.toErrorState()
                is AgroResult.Success -> {
                    val shown = classification.toUiState(saved.value).withZone()
                    _state.value = shown
                    watchZone(shown)
                    estimateLeafDamage(shown, image)
                }
            }
        }
    }

    /**
     * Ofrece la zona solo si la clase tiene tipo de regla Y hay regla en caché.
     * Sin regla descargada no hay botón: no se inventa una tabla.
     */
    private suspend fun ScanUiState.withZone(): ScanUiState {
        if (this !is ScanUiState.Identified) return this
        val type = zoneRuleType() ?: return this
        val rule = runCatching { zoneRules.cached(type)?.rule }
            .onFailure { Log.w(TAG, "no se pudo leer la regla de zona", it) }
            .getOrNull()
            ?.takeIf { it.isValidFor(type) }
            ?: return this
        return copy(zone = ZoneAvailability(rule, storedPlantsPerHa()))
    }

    private fun ScanUiState.Identified.zoneRuleType(): String? =
        ZoneRuleTypes.forClass(top.firstOrNull { it.pestId == pest.id }?.classId, pest.id)

    private suspend fun storedPlantsPerHa(): Int =
        runCatching { zoneRules.observePlantsPerHa().first() }.getOrDefault(ZoneDefaults.PLANTS_PER_HA)

    /**
     * Sigue la regla del tipo mientras el resultado esté en pantalla.
     *
     * [withZone] copia la regla una vez; sin esto, si la sincronización la retira
     * (204) o la reemplaza con el resultado abierto, el productor seguiría
     * calculando con una tabla que el servidor ya no publica.
     */
    private fun watchZone(shown: ScanUiState) {
        stopWatchingZone()
        val identified = shown as? ScanUiState.Identified ?: return
        val type = identified.zoneRuleType() ?: return
        zoneWatcher = viewModelScope.launch {
            zoneRules.observe(type)
                .map { cached -> cached?.rule?.takeIf { it.isValidFor(type) } }
                .distinctUntilChanged()
                // Un fallo de lectura no retira nada: se queda la última regla mostrada.
                .catch { Log.w(TAG, "no se pudo observar la regla de zona", it) }
                .collect { rule -> applyZoneRule(identified.captureId, rule) }
        }
    }

    private fun stopWatchingZone() {
        zoneWatcher?.cancel()
        zoneWatcher = null
    }

    /**
     * Regla retirada: desaparecen el botón y la estimación. Regla distinta: se
     * ofrece la nueva **sin** estimación, porque el resultado anterior salió de
     * otra tabla y marcarlo solo como «desactualizado» lo dejaría a la vista.
     * La misma regla no toca nada, así que el resultado y su aviso de
     * desactualizado ([ZoneAvailability.isStale]) se conservan.
     */
    private suspend fun applyZoneRule(captureId: String, rule: ZoneRule?) {
        fun shown() = (_state.value as? ScanUiState.Identified)?.takeIf { it.captureId == captureId }
        val before = shown() ?: return
        if (before.zone?.rule == rule) return
        val plantsPerHa = if (rule == null) null else before.zone?.plantsPerHa ?: storedPlantsPerHa()
        // Se relee el estado: la lectura de la densidad suspende.
        val current = shown() ?: return
        if (current.zone?.rule == rule) return
        _state.value = current.copy(zone = if (rule == null || plantsPerHa == null) null else ZoneAvailability(rule, plantsPerHa))
    }

    /**
     * PROTOTIPO: daño en la hoja por color, fuera del hilo principal y una sola
     * vez por captura (el resultado queda en el estado y los cambios de regla lo
     * conservan). Se calcula para toda clase con tipo de regla, haya o no regla
     * en caché, para que esté listo si la regla llega con la pantalla abierta.
     * El estado se escribe de vuelta en el hilo principal, como los demás.
     */
    private fun estimateLeafDamage(shown: ScanUiState, image: RgbImage) {
        val identified = shown as? ScanUiState.Identified ?: return
        if (identified.zoneRuleType() == null) return
        damageJob = viewModelScope.launch {
            val damage = withContext(computation) {
                runCatching {
                    val analysis = LeafDamageEstimator.analyze(image)
                    LeafDamage(analysis.result, debugMask = if (BuildConfig.DEBUG) analysis.overlay() else null)
                }.getOrElse {
                    Log.w(TAG, "no se pudo estimar el daño en la hoja", it)
                    LeafDamage(LeafDamageResult.NotEstimable(LeafDamageResult.Reason.FAILED))
                }
            }
            val current = (_state.value as? ScanUiState.Identified)
                ?.takeIf { it.captureId == identified.captureId } ?: return@launch
            _state.value = current.copy(leafDamage = damage)
        }
    }

    private fun cancelLeafDamage() {
        damageJob?.cancel()
        damageJob = null
    }

    /** Solo cálculo local: el resultado no se guarda en Room ni se envía al API. */
    private fun estimate(intent: ScanIntent.EstimateZone) {
        val current = _state.value as? ScanUiState.Identified ?: return
        val zone = current.zone ?: return
        val plantsPerHa = intent.plantsPerHa.coerceIn(ZoneDefaults.MIN_PLANTS_PER_HA, ZoneDefaults.MAX_PLANTS_PER_HA)
        val incidencePct = intent.incidencePct.coerceIn(0, 100)
        val result = estimateZone(
            rule = zone.rule,
            severityPct = intent.severity.representativePct,
            incidencePct = incidencePct.toDouble(),
            plantsPerHa = plantsPerHa,
        ) ?: return
        _state.value = current.copy(
            zone = zone.copy(
                plantsPerHa = plantsPerHa,
                estimate = result,
                inputs = ZoneInputs(intent.severity, incidencePct, plantsPerHa),
            ),
        )
        if (plantsPerHa != zone.plantsPerHa) {
            viewModelScope.launch {
                runCatching { zoneRules.setPlantsPerHa(plantsPerHa) }
                    .onFailure { Log.w(TAG, "no se pudo guardar la densidad de siembra", it) }
            }
        }
    }

    private suspend fun recordScan(result: ClassificationResult) {
        val (version, confidence, latency) = when (result) {
            is ClassificationResult.Identified -> Triple(result.modelVersion, result.confidence, result.latencyMillis)
            is ClassificationResult.NotAPest -> Triple(result.modelVersion, result.confidence, result.latencyMillis)
            is ClassificationResult.Uncertain -> Triple(
                result.modelVersion,
                result.candidates.firstOrNull()?.probability,
                result.latencyMillis,
            )
            is ClassificationResult.OutOfDomain -> Triple(result.modelVersion, null, result.latencyMillis)
            is ClassificationResult.QualityRejected -> Triple(classifier.activeModelVersion(), null, 0L)
        }
        // Sin modelo no hay versión que atribuir: el servidor exige una, así que no se manda
        if (version == null || version == NO_MODEL_VERSION) return

        telemetry.recordScan(
            modelVersion = version,
            outcome = result.outcome.name,
            confidence = confidence,
            latencyMillis = latency,
            delegate = "",
        )
    }

    private fun confirm(captureId: String) {
        viewModelScope.launch {
            confirmPrediction(captureId)
            emit(ScanEffect.ShowMessage(UiText.of(MessageKey.SCAN_PREDICTION_CONFIRMED)))
            backToPreview()
        }
    }

    private fun correct(captureId: String, classId: String) {
        viewModelScope.launch {
            correctPrediction(captureId, classId)
            emit(ScanEffect.ShowMessage(UiText.of(MessageKey.SCAN_PREDICTION_CORRECTED)))
            backToPreview()
        }
    }

    private fun backToPreview() {
        stopWatchingZone()
        cancelLeafDamage()
        _state.value = ScanUiState.Preview(
            guidance = FramingGuidance.LISTO,
            modelReady = classifier.activeModelVersion() != null,
        )
    }

    private fun emit(effect: ScanEffect) {
        viewModelScope.launch { _effects.send(effect) }
    }

    private fun ClassificationResult.toUiState(capture: Capture): ScanUiState = when (this) {
        is ClassificationResult.Identified -> ScanUiState.Identified(
            captureId = capture.id,
            pest = pest,
            confidence = confidence,
            top = top,
            // Con el modelo heredado no hay calibración, luego las compuertas 2 y
            // 3 no operan y la pantalla tiene que decirlo (ADR-014).
            uncertaintyGatesActive = oodScore != 0f,
            latencyMillis = latencyMillis,
        )

        is ClassificationResult.NotAPest -> ScanUiState.NotAPest(
            captureId = capture.id,
            label = label,
            confidence = confidence,
        )

        is ClassificationResult.Uncertain -> ScanUiState.Uncertain(
            captureId = capture.id,
            reason = reason,
            candidates = candidates,
        )

        is ClassificationResult.OutOfDomain -> ScanUiState.OutOfDomain(
            captureId = capture.id,
            modelWasAvailable = modelVersion != NO_MODEL_VERSION,
        )

        is ClassificationResult.QualityRejected ->
            ScanUiState.QualityRejected(hint, measured, threshold)
    }

    private fun DomainError.toErrorState() = ScanUiState.Error(
        message = UiText.Message(message),
        // Sin modelo no se recupera reintentando: hace falta conectarse. Los
        // demás fallos sí admiten reintento, y ofrecerlo cuando no sirve es peor
        // que no ofrecerlo.
        recoverable = this !is DomainError.ModelUnavailable,
    )

    /**
     * ⚠️ Liberación de la memoria nativa de LiteRT (§7.3).
     *
     * El recolector de basura de la JVM no la administra. Omitir esto produce
     * fugas que en dispositivos de 2 GB terminan en cierre forzado tras unas
     * cuantas navegaciones de entrada y salida de la cámara.
     */
    override fun onCleared() {
        classifier.release()
        super.onCleared()
    }

    private companion object {
        /** Marca las capturas tomadas sin reconocedor activo. Nunca deben
         *  contaminar las métricas de exactitud de una versión real. */
        const val TAG = "AgroVision.Scan"

        /** Marca las capturas tomadas sin reconocedor activo. Nunca deben
         *  contaminar las métricas de exactitud de una versión real. */
        const val NO_MODEL_VERSION = "sin-modelo"
    }
}
