package co.agrovision.core.model.scan

import co.agrovision.core.common.MessageKey
import co.agrovision.core.model.pest.PestTaxon

/**
 * Imagen en memoria, independiente de Android.
 *
 * El dominio y los módulos de preprocesado trabajan sobre esta representación
 * para que la lógica de las compuertas se pueda probar en la JVM con imágenes
 * sintéticas, sin emulador y sin `Bitmap`.
 *
 * @param pixels ARGB empaquetado, fila por fila, igual que `Bitmap.getPixels`.
 */
class RgbImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "Dimensiones inválidas: ${width}x$height" }
        require(pixels.size == width * height) {
            "Se esperaban ${width * height} píxeles, llegaron ${pixels.size}"
        }
    }
}

/** Salida cruda del intérprete: las DOS cabezas del modelo (§7.1). */
class RawInference(
    val logits: FloatArray,
    /**
     * Vector de la penúltima capa. No es opcional: alimenta el detector OOD en
     * el dispositivo y la agrupación por similitud en el servidor. Un modelo
     * exportado solo con logits inutiliza dos subsistemas completos
     * (§23, error nº 3).
     */
    val embedding: Embedding,
    val modelVersion: String,
    val latencyMillis: Long,
)

data class Prediction(
    val classId: String,
    val label: String,
    val probability: Float,
    /**
     * Vínculo con el catálogo de plagas. Nulo en la clase de rechazo
     * —deficiencia nutricional, daño mecánico, quemadura por sol, hoja sana—,
     * que es tan importante como las demás: sin ella el modelo se ve obligado a
     * elegir una plaga cuando no hay ninguna (§2.2).
     */
    val pestId: String?,
) {
    val isRejectClass: Boolean get() = pestId == null
}

// ── Compuerta 1: calidad ─────────────────────────────────────────────────────

sealed interface QualityAssessment {
    data class Pass(val sharpness: Float, val luminance: Float) : QualityAssessment
    data class Reject(
        val hint: QualityHint,
        val measured: Float,
        val threshold: Float,
    ) : QualityAssessment
}

/**
 * Motivo de rechazo, con la instrucción accionable que le corresponde. El
 * productor necesita saber qué hacer distinto, no que «la imagen no es válida».
 */
enum class QualityHint(val message: MessageKey) {
    TOO_BLURRY(MessageKey.QUALITY_TOO_BLURRY),
    TOO_DARK(MessageKey.QUALITY_TOO_DARK),
    TOO_BRIGHT(MessageKey.QUALITY_TOO_BRIGHT),
    TOO_FAR(MessageKey.QUALITY_TOO_FAR),
    NO_PLANT_CONTENT(MessageKey.QUALITY_NO_PLANT),
}

// ── Compuertas 2 y 3: incertidumbre ──────────────────────────────────────────

/** Veredicto combinado de la compuerta OOD y la de calibración. */
sealed interface GateDecision {

    data class InDomain(
        val top: List<Prediction>,
        val calibratedConfidence: Float,
        val oodScore: Float,
    ) : GateDecision

    data class Uncertain(
        val reason: UncertaintyReason,
        val top: List<Prediction>,
        val calibratedConfidence: Float,
        val oodScore: Float,
    ) : GateDecision

    data class OutOfDomain(
        val oodScore: Float,
        val energyScore: Float,
        val mahalanobisDistance: Float,
    ) : GateDecision
}

enum class UncertaintyReason(val message: MessageKey) {
    /** La probabilidad calibrada máxima no alcanza el umbral τ. */
    LOW_CONFIDENCE(MessageKey.RESULT_LOW_CONFIDENCE),

    /**
     * El margen entre la primera y la segunda clase es menor que δ. Detecta la
     * confusión entre clases visualmente parecidas, que el criterio de
     * probabilidad máxima deja pasar (§7.2).
     */
    AMBIGUOUS(MessageKey.RESULT_AMBIGUOUS),
}

enum class GateOutcome {
    IDENTIFIED,
    UNCERTAIN,
    OOD,
    QUALITY_REJECTED;

    companion object {
        fun parse(raw: String): GateOutcome =
            entries.firstOrNull { it.name == raw } ?: UNCERTAIN
    }
}

// ── Resultado que ve la pantalla ─────────────────────────────────────────────

sealed interface ClassificationResult {

    val outcome: GateOutcome

    data class Identified(
        val pest: PestTaxon,
        val confidence: Float,
        val top: List<Prediction>,
        val oodScore: Float,
        val modelVersion: String,
        val latencyMillis: Long,
    ) : ClassificationResult {
        override val outcome = GateOutcome.IDENTIFIED
    }

    /**
     * La clase de rechazo ganó con confianza suficiente: lo que hay en la
     * imagen no es una plaga.
     *
     * Es un resultado de pleno derecho, no un «no sé». Decirle al productor que
     * su planta está sana, o que lo que ve es una deficiencia nutricional y no
     * un hongo, le ahorra una fumigación que no puede permitirse.
     */
    data class NotAPest(
        val label: String,
        val confidence: Float,
        val modelVersion: String,
        val latencyMillis: Long,
    ) : ClassificationResult {
        override val outcome = GateOutcome.IDENTIFIED
    }

    /**
     * El modelo duda. Se ofrece Solicitud de Revisión y NO se muestra ninguna
     * predicción como si fuera un diagnóstico: mostrar «roya, 43 %» ante una
     * deficiencia de manganeso provoca una fumigación innecesaria y la pérdida
     * definitiva de la confianza del productor (§1.2, primera apuesta).
     */
    data class Uncertain(
        val reason: UncertaintyReason,
        val candidates: List<Prediction>,
        val oodScore: Float,
        val modelVersion: String,
        val latencyMillis: Long,
    ) : ClassificationResult {
        override val outcome = GateOutcome.UNCERTAIN
    }

    /** El modelo no conoce lo que ve. Distinto de dudar: aquí es ignorancia. */
    data class OutOfDomain(
        val oodScore: Float,
        val modelVersion: String,
        val latencyMillis: Long,
    ) : ClassificationResult {
        override val outcome = GateOutcome.OOD
    }

    /**
     * Rechazo antes de gastar cómputo en inferencia.
     *
     * @param measured el valor que disparó el rechazo —varianza del laplaciano,
     *   luminancia o fracción de verde según la pista— y @param threshold el
     *   umbral contra el que se comparó.
     *
     * Van aquí porque **sin ellos los umbrales no se pueden calibrar**. La Fase 0
     * tiene que ajustarlos contra imágenes reales etiquetadas por agrónomo, y
     * para eso hace falta saber qué midió la app, no solo que rechazó. Un umbral
     * estricto de más frustra al productor y le hace abandonar; uno laxo mete
     * basura al dataset. Sin el número, los dos casos se ven igual.
     */
    data class QualityRejected(
        val hint: QualityHint,
        val measured: Float,
        val threshold: Float,
    ) : ClassificationResult {
        override val outcome = GateOutcome.QUALITY_REJECTED
    }
}
