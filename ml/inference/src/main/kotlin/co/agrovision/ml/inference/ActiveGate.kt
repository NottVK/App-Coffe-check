package co.agrovision.ml.inference

import co.agrovision.core.model.port.LoadedModel
import co.agrovision.core.model.port.UncertaintyGate
import co.agrovision.core.model.scan.GateDecision
import co.agrovision.core.model.scan.Prediction
import co.agrovision.core.model.scan.RawInference
import co.agrovision.ml.ood.EnergyMahalanobisGate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/**
 * Guarda la compuerta que corresponde al modelo cargado en este momento.
 *
 * Existe porque `UncertaintyGate.evaluate` es síncrona y las compuertas 2 y 3
 * dependen de los artefactos de calibración del modelo activo, que se conocen al
 * cargarlo. `LiteRtPestClassifier` la actualiza en `warmUp()`; la compuerta la
 * lee en cada inferencia.
 *
 * `@Volatile` y no un `Mutex`: la escritura ocurre en el despachador de
 * inferencia y la lectura también, así que en la práctica están serializadas. La
 * marca solo garantiza que un cambio de modelo se vea desde otro hilo sin
 * necesidad de sincronizar la ruta caliente.
 */
@Singleton
class ActiveGateHolder @Inject constructor() {

    @Volatile
    var model: LoadedModel? = null
        private set

    @Volatile
    private var calibrated: UncertaintyGate? = null

    fun update(loaded: LoadedModel?) {
        model = loaded
        calibrated = loaded?.calibration?.let { calibration ->
            EnergyMahalanobisGate(calibration, loaded.labels)
        }
    }

    fun gate(): UncertaintyGate? = calibrated
}

/**
 * Compuerta que delega en la del modelo activo, con respaldo para el modelo
 * heredado.
 *
 * ## Por qué existe el respaldo (ADR-014)
 *
 * El modelo publicado hoy es un Teachable Machine sin cabeza de embedding, sin
 * clase de rechazo y sin artefactos de calibración. Con él, las compuertas 2 y 3
 * **no pueden operar**: sin embedding no hay distancia de Mahalanobis y sin
 * temperatura no hay probabilidad calibrada.
 *
 * Aquí está la decisión importante: **el respaldo NO inventa umbrales.** Un
 * umbral de softmax sin calibrar es exactamente lo que §7.2 identifica como
 * incorrecto —detecta la duda del modelo, no su ignorancia— y usarlo daría la
 * apariencia de tener detección de desconocidos sin tenerla, que es peor que no
 * tenerla porque nadie volvería a mirarlo.
 *
 * Lo que hace el respaldo es devolver `InDomain` con la probabilidad cruda y
 * `oodScore = 0`. Ese cero es la señal que la pantalla usa para avisar al
 * productor de que este reconocedor todavía no sabe decir cuándo no sabe.
 */
@Singleton
class DelegatingUncertaintyGate @Inject constructor(
    private val holder: ActiveGateHolder,
) : UncertaintyGate {

    override fun evaluate(inference: RawInference): GateDecision =
        holder.gate()?.evaluate(inference) ?: legacyDecision(inference)

    private fun legacyDecision(inference: RawInference): GateDecision {
        val labels = holder.model?.labels.orEmpty()
        val probabilities = inference.logits.softmax()

        val top = probabilities
            .mapIndexed { index, probability -> index to probability }
            .sortedByDescending { it.second }
            .take(TOP_K)
            .mapNotNull { (index, probability) ->
                labels.getOrNull(index)?.let { label ->
                    Prediction(
                        classId = label.classId,
                        label = label.displayName,
                        probability = probability,
                        pestId = label.pestId,
                    )
                }
            }

        return GateDecision.InDomain(
            top = top,
            calibratedConfidence = top.firstOrNull()?.probability ?: 0f,
            // Cero explícito: es la marca de «este modelo no tiene compuertas de
            // incertidumbre», no una puntuación OOD real de valor cero.
            oodScore = 0f,
        )
    }

    /** Softmax estabilizado. Sin restar el máximo, `exp` desborda a infinito con
     *  logits por encima de ~88 y toda la distribución sale `NaN`. */
    private fun FloatArray.softmax(): FloatArray {
        if (isEmpty()) return this
        val max = max()
        var sum = 0.0
        val exponentials = DoubleArray(size) { exp((this[it] - max).toDouble()).also { e -> sum += e } }
        return FloatArray(size) { (exponentials[it] / sum).toFloat() }
    }

    private companion object {
        const val TOP_K = 3
    }
}
