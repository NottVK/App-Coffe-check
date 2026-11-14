package co.agrovision.feature.scan

import androidx.compose.runtime.Immutable
import co.agrovision.core.common.UiText
import co.agrovision.core.model.pest.PestTaxon
import co.agrovision.core.model.scan.Prediction
import co.agrovision.core.model.scan.QualityHint
import co.agrovision.core.model.scan.RgbImage
import co.agrovision.core.model.scan.UncertaintyReason
import co.agrovision.core.model.zone.LeafDamageResult
import co.agrovision.core.model.zone.SeverityBand
import co.agrovision.core.model.zone.ZoneEstimate
import co.agrovision.core.model.zone.ZoneRule

/**
 * Contrato MVI de la pantalla de escaneo (§12.4).
 *
 * Se usa MVI aquí, y no un puñado de campos independientes, por la naturaleza de
 * los estados: el escaneo tiene estados **mutuamente excluyentes** —no se puede
 * estar analizando y mostrando resultado a la vez— y un modelo de estado único
 * los representa con más fidelidad. Con campos sueltos, tarde o temprano alguien
 * deja `isLoading = true` junto a un resultado y la pantalla muestra las dos
 * cosas.
 */
@Immutable
sealed interface ScanUiState {

    data object Idle : ScanUiState

    /** Se está creando el intérprete y ejecutando la inferencia con tensor vacío.
     *  Sin esto, la primera clasificación real tarda el doble o el triple. */
    data object WarmingUp : ScanUiState

    /**
     * Cámara lista.
     *
     * @param modelReady `false` cuando no hay ninguna ranura de modelo activa.
     *
     * **La cámara funciona igual sin modelo.** Un productor con la instalación
     * recién hecha, o al que le falló la descarga, tiene que poder documentar la
     * plaga que está viendo: esa captura entra a la cola y un agrónomo la
     * resuelve (§5.2). Bloquear la cámara por falta de modelo convertiría un
     * problema de red en pérdida de trabajo de campo.
     */
    data class Preview(
        val guidance: FramingGuidance,
        val modelReady: Boolean,
    ) : ScanUiState

    data object Analyzing : ScanUiState

    data class Identified(
        val captureId: String,
        val pest: PestTaxon,
        val confidence: Float,
        val top: List<Prediction>,
        /**
         * `false` con el modelo heredado (ADR-014). La pantalla lo dice
         * explícitamente: este reconocedor todavía no sabe reconocer cuándo no
         * sabe. Ocultarlo sería vender detección de desconocidos que no existe.
         */
        val uncertaintyGatesActive: Boolean,
        val latencyMillis: Long,
        /**
         * Estimación de «zona a vigilar». `null` si la clase no tiene tipo de
         * regla (deficiencias, hoja sana) o si nunca llegó una regla del
         * servidor: en ese caso no se ofrece nada, ni se inventa una tabla.
         */
        val zone: ZoneAvailability? = null,
        /**
         * PROTOTIPO: daño en la hoja estimado por color sobre esta foto. `null`
         * mientras se calcula o si la clase no tiene tipo de regla. Se calcula
         * una sola vez por captura y sobrevive a los cambios de regla.
         */
        val leafDamage: LeafDamage? = null,
    ) : ScanUiState

    /** La clase de rechazo ganó: lo que hay en la imagen no es una plaga. Es un
     *  resultado de pleno derecho, no un «no sé». */
    data class NotAPest(
        val captureId: String,
        val label: String,
        val confidence: Float,
    ) : ScanUiState

    /**
     * El modelo duda. **No se muestra ninguna predicción como diagnóstico.**
     * Mostrar «roya, 43 %» ante una deficiencia de manganeso provoca una
     * fumigación que el productor no puede pagar (§1.2, primera apuesta).
     */
    data class Uncertain(
        val captureId: String,
        val reason: UncertaintyReason,
        val candidates: List<Prediction>,
    ) : ScanUiState

    /**
     * El modelo no conoce lo que ve. Distinto de dudar: aquí es ignorancia.
     *
     * @param modelWasAvailable `false` si ni siquiera había reconocedor
     *   instalado. Los dos casos acaban en Solicitud de Revisión, pero **no se
     *   le dice lo mismo al productor**: «miré y no lo reconozco» y «no tengo con
     *   qué mirar» son cosas distintas, y confundirlas es mentir sobre lo que la
     *   app hizo.
     */
    data class OutOfDomain(
        val captureId: String,
        val modelWasAvailable: Boolean,
    ) : ScanUiState

    /**
     * Rechazo antes de gastar cómputo, con instrucción accionable.
     *
     * `measured` y `threshold` no se muestran al productor —no le sirven de
     * nada— pero viajan hasta aquí para que la pantalla de diagnóstico y la
     * telemetría puedan calibrar los umbrales contra imágenes reales (Fase 0).
     */
    data class QualityRejected(
        val hint: QualityHint,
        val measured: Float,
        val threshold: Float,
    ) : ScanUiState

    data class Error(val message: UiText, val recoverable: Boolean) : ScanUiState
}

/**
 * Regla de zona disponible en caché para la clase detectada, y la última
 * estimación junto con los datos con que se calculó ([inputs]): el resultado
 * nunca se muestra desligado de sus parámetros.
 */
@Immutable
data class ZoneAvailability(
    val rule: ZoneRule,
    val plantsPerHa: Int,
    val estimate: ZoneEstimate? = null,
    val inputs: ZoneInputs? = null,
) {
    /**
     * `true` si hay resultado y los controles actuales ([form]; `null` si están
     * incompletos o inválidos) ya no son los del cálculo: está desactualizado.
     */
    fun isStale(form: ZoneInputs?): Boolean = estimate != null && inputs != form
}

/**
 * Estimación de daño (prototipo, muy aproximada) y, solo en builds debug, la
 * máscara superpuesta (hoja/daño) para ajustar umbrales.
 */
@Immutable
data class LeafDamage(val result: LeafDamageResult, val debugMask: RgbImage? = null)

/** Valores de los controles manuales con que se calcula una estimación. */
@Immutable
data class ZoneInputs(val severity: SeverityBand, val incidencePct: Int, val plantsPerHa: Int)

/**
 * Guía de encuadre. Es lo que impone la disciplina de acercarse y encuadrar antes
 * de disparar, que es la mitad de la calidad del dataset.
 */
enum class FramingGuidance {
    ACERCATE,
    ALEJATE,
    MAS_LUZ,
    ESTABILIZA,
    LISTO,
}

sealed interface ScanIntent {
    data object StartCamera : ScanIntent
    data class Capture(val image: co.agrovision.core.model.scan.RgbImage) : ScanIntent
    data class ConfirmPrediction(val captureId: String) : ScanIntent
    data class CorrectPrediction(val captureId: String, val classId: String) : ScanIntent
    data class CreateReviewRequest(val captureId: String) : ScanIntent
    data object Retry : ScanIntent
    data object DismissResult : ScanIntent

    /** Controles manuales: severidad por rango, plantas de cada 100 y densidad de siembra. */
    data class EstimateZone(val severity: SeverityBand, val incidencePct: Int, val plantsPerHa: Int) : ScanIntent
}

/** Efectos de una sola vez. `Channel`, no `StateFlow`: un `StateFlow` reemitiría
 *  la navegación en cada rotación de pantalla. */
sealed interface ScanEffect {
    data class NavigateToPestDetail(val pestId: String) : ScanEffect
    data class NavigateToReviewRequest(val captureId: String) : ScanEffect
    data class ShowMessage(val message: UiText) : ScanEffect
    data object RequestCameraPermission : ScanEffect
}
