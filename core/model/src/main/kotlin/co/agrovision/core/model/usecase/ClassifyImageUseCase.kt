package co.agrovision.core.model.usecase

import co.agrovision.core.common.AgroResult
import co.agrovision.core.common.runCatchingDomain
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.port.ImageQualityInspector
import co.agrovision.core.model.port.PestCatalogRepository
import co.agrovision.core.model.port.PestClassifier
import co.agrovision.core.model.port.UncertaintyGate
import co.agrovision.core.model.scan.ClassificationResult
import co.agrovision.core.model.scan.GateDecision
import co.agrovision.core.model.scan.QualityAssessment
import co.agrovision.core.model.scan.RgbImage
import javax.inject.Inject

/**
 * Orquesta la cadena de tres compuertas y traduce su veredicto a un resultado
 * que la pantalla puede mostrar.
 *
 * Este caso de uso es el núcleo técnico del producto y el punto donde la
 * mayoría de implementaciones falla. La intuición habitual —«si la confianza es
 * menor al 60 %, es desconocida»— es incorrecta y peligrosa: las redes modernas
 * están sistemáticamente sobreconfiadas, y una plaga nunca vista puede producir
 * un softmax de 0,95 sobre la clase visualmente parecida.
 *
 * El umbral detecta la **duda** del modelo; lo que hay que detectar es su
 * **ignorancia**. Por eso la compuerta 2 opera sobre el embedding y no sobre el
 * softmax, y por eso hay tres compuertas y no un `if`.
 *
 * ```
 * ENTRADA
 *   ├─► C1 calidad ──── falla ──► "Acércate / mejor luz"  [sin inferencia]
 *   ▼
 * INFERENCIA ──► logits + embedding
 *   ├─► C2 OOD ──── fuera ──► "No la reconozco" ──► Solicitud de Revisión
 *   ├─► C3 calibración ──── p_max < τ ó margen < δ ──► "No estoy seguro"
 *   ▼
 * RESULTADO: plaga + ficha + recomendaciones + opción de corregir
 * ```
 *
 * El caso de uso NO persiste nada: eso es responsabilidad de
 * [SaveCaptureUseCase], que se invoca a continuación y antes de mostrar el
 * resultado. Mantenerlo puro es lo que permite probar la cadena completa en la
 * JVM con compuertas falsas.
 */
class ClassifyImageUseCase @Inject constructor(
    private val quality: ImageQualityInspector,
    private val classifier: PestClassifier,
    private val gate: UncertaintyGate,
    private val catalog: PestCatalogRepository,
) {

    suspend operator fun invoke(image: RgbImage): AgroResult<ClassificationResult, DomainError> {
        // ── Compuerta 1 ── Antes de gastar cómputo. Protege la calidad del
        // dataset tanto como la experiencia del productor.
        val assessment = quality.inspect(image)
        if (assessment is QualityAssessment.Reject) {
            return AgroResult.success(
                ClassificationResult.QualityRejected(
                    hint = assessment.hint,
                    measured = assessment.measured,
                    threshold = assessment.threshold,
                )
            )
        }

        if (classifier.activeModelVersion() == null) {
            return AgroResult.failure(DomainError.ModelUnavailable)
        }

        val inference = runCatchingDomain(
            onThrow = { DomainError.InferenceFailed(it.message ?: it::class.simpleName.orEmpty()) },
        ) {
            classifier.classify(image)
        }
        val raw = when (inference) {
            is AgroResult.Failure -> return inference
            is AgroResult.Success -> inference.value
        }

        // ── Compuertas 2 y 3 ──
        return when (val decision = gate.evaluate(raw)) {

            is GateDecision.OutOfDomain -> AgroResult.success(
                ClassificationResult.OutOfDomain(
                    oodScore = decision.oodScore,
                    modelVersion = raw.modelVersion,
                    latencyMillis = raw.latencyMillis,
                )
            )

            is GateDecision.Uncertain -> AgroResult.success(
                ClassificationResult.Uncertain(
                    reason = decision.reason,
                    candidates = decision.top,
                    oodScore = decision.oodScore,
                    modelVersion = raw.modelVersion,
                    latencyMillis = raw.latencyMillis,
                )
            )

            is GateDecision.InDomain -> resolveIdentified(decision, raw.modelVersion, raw.latencyMillis)
        }
    }

    private suspend fun resolveIdentified(
        decision: GateDecision.InDomain,
        modelVersion: String,
        latencyMillis: Long,
    ): AgroResult<ClassificationResult, DomainError> {
        val top = decision.top.firstOrNull()
            ?: return AgroResult.failure(DomainError.InferenceFailed("El modelo no devolvió predicciones."))

        if (top.isRejectClass) {
            return AgroResult.success(
                ClassificationResult.NotAPest(
                    label = top.label,
                    confidence = decision.calibratedConfidence,
                    modelVersion = modelVersion,
                    latencyMillis = latencyMillis,
                )
            )
        }

        val pest = catalog.findById(top.pestId.orEmpty())
            // El modelo va por delante del catálogo: se publicó una versión con
            // una clase nueva y el catálogo aún no se ha sincronizado. No es un
            // fallo del modelo y no se puede resolver mostrando una ficha vacía.
            ?: return AgroResult.failure(DomainError.UnknownPestClass(top.classId))

        return AgroResult.success(
            ClassificationResult.Identified(
                pest = pest,
                confidence = decision.calibratedConfidence,
                top = decision.top,
                oodScore = decision.oodScore,
                modelVersion = modelVersion,
                latencyMillis = latencyMillis,
            )
        )
    }
}
