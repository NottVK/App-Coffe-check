package co.agrovision.ml.preprocessing

import co.agrovision.core.model.port.ImageQualityInspector
import co.agrovision.core.model.scan.QualityAssessment
import co.agrovision.core.model.scan.QualityHint
import co.agrovision.core.model.scan.RgbImage
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * **Compuerta 1 — calidad de imagen** (§7.2).
 *
 * Se evalúa ANTES de gastar cómputo en inferencia, y eso importa por dos razones
 * que se suelen olvidar: en un teléfono de gama baja la inferencia cuesta batería
 * real, y una imagen mala que llega al modelo produce una predicción con la que
 * el productor va a tomar una decisión agronómica.
 *
 * Esta compuerta protege la calidad del dataset tanto como la experiencia.
 *
 * Es además la única de las tres que **no depende del modelo**: funciona con el
 * modelo heredado sin calibración (ADR-014), y por eso aporta valor desde el
 * primer día del refactor.
 *
 * ## Las tres señales
 *
 * **Nitidez — varianza del laplaciano.** El laplaciano es la segunda derivada
 * espacial: responde a bordes. Una imagen enfocada tiene bordes marcados y por
 * tanto varianza alta; una movida los tiene difuminados y la varianza se
 * desploma. Es el detector de desenfoque estándar y cuesta una pasada sobre la
 * luminancia.
 *
 * **Exposición — luminancia media y desviación.** Media baja: hay poca luz. Media
 * alta con desviación baja: la imagen está lavada, que en campo significa sol
 * directo pegando sobre la hoja. Ambos casos hacen que el color de la lesión —el
 * dato que importa— deje de ser fiable.
 *
 * **Contenido vegetal — heurística de verde.** Una fracción mínima de píxeles
 * donde el canal verde domina. Es deliberadamente burda: distingue una hoja de un
 * suelo, un zapato o un techo, que es todo lo que se le pide. Afinarla más
 * empezaría a rechazar hojas cloróticas o necrosadas, que son exactamente las
 * enfermas.
 *
 * ## Sobre los umbrales
 *
 * Los valores de abajo son un **punto de partida razonado, no calibrado en
 * campo**. Calibrarlos exige el conjunto de imágenes de la Fase 0 con etiquetas
 * de «usable / no usable» puestas por un agrónomo.
 *
 * El coste de equivocarse es asimétrico y hay que tenerlo presente: un umbral
 * demasiado estricto frustra al productor y le hace abandonar; uno demasiado laxo
 * mete basura al dataset. **Ante la duda, ser laxo**: una imagen mala que llega a
 * revisión la descarta un agrónomo en dos segundos; un productor que abandona no
 * vuelve.
 */
@Singleton
class LaplacianQualityInspector @Inject constructor(
    private val thresholds: QualityThresholds = QualityThresholds.DEFAULT,
) : ImageQualityInspector {

    override fun inspect(image: RgbImage): QualityAssessment {
        val luminance = image.toLuminance()

        val stats = luminance.statistics()

        // El orden de las comprobaciones es el orden de utilidad del mensaje.
        // Si la foto está oscura Y movida, «hay poca luz» es más accionable que
        // «salió movida», porque la falta de luz es la CAUSA del movimiento: el
        // obturador se abre más tiempo.
        if (stats.mean < thresholds.minLuminance) {
            return QualityAssessment.Reject(QualityHint.TOO_DARK, stats.mean, thresholds.minLuminance)
        }
        if (stats.mean > thresholds.maxLuminance && stats.stdDev < thresholds.minLuminanceStdDev) {
            return QualityAssessment.Reject(QualityHint.TOO_BRIGHT, stats.mean, thresholds.maxLuminance)
        }

        val sharpness = laplacianVariance(luminance, image.width, image.height)
        if (sharpness < thresholds.minSharpness) {
            return QualityAssessment.Reject(QualityHint.TOO_BLURRY, sharpness, thresholds.minSharpness)
        }

        val greenFraction = image.greenDominantFraction()
        if (greenFraction < thresholds.minGreenFraction) {
            return QualityAssessment.Reject(QualityHint.NO_PLANT_CONTENT, greenFraction, thresholds.minGreenFraction)
        }

        return QualityAssessment.Pass(sharpness = sharpness, luminance = stats.mean)
    }

    /**
     * Varianza de la respuesta al núcleo laplaciano de 4-vecinos:
     *
     * ```
     *      0   1   0
     *      1  -4   1
     *      0   1   0
     * ```
     *
     * Se recorre sin los bordes: el núcleo no está definido ahí y extender la
     * imagen introduciría bordes artificiales que inflarían la varianza justo en
     * las fotos peor encuadradas.
     */
    private fun laplacianVariance(luminance: FloatArray, width: Int, height: Int): Float {
        if (width < 3 || height < 3) return 0f

        var sum = 0.0
        var sumOfSquares = 0.0
        var count = 0

        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val index = row + x
                val response = luminance[index - width] +
                    luminance[index + width] +
                    luminance[index - 1] +
                    luminance[index + 1] -
                    4f * luminance[index]

                sum += response
                sumOfSquares += response.toDouble() * response
                count++
            }
        }

        if (count == 0) return 0f
        val mean = sum / count
        // En doble precisión: con imágenes de 1280 px el acumulado en Float
        // pierde precisión suficiente para mover la varianza cerca del umbral.
        return ((sumOfSquares / count) - mean * mean).toFloat().coerceAtLeast(0f)
    }

    /** Luminancia perceptual Rec. 601. Los coeficientes reflejan la sensibilidad
     *  del ojo, no un promedio aritmético de canales. */
    private fun RgbImage.toLuminance(): FloatArray = FloatArray(pixels.size) { index ->
        val pixel = pixels[index]
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        0.299f * r + 0.587f * g + 0.114f * b
    }

    /**
     * Fracción de píxeles donde el verde domina con margen sobre rojo y azul.
     *
     * El margen es lo que evita contar píxeles grises: en un gris puro los tres
     * canales son casi iguales y el verde «gana» por ruido de sensor.
     */
    private fun RgbImage.greenDominantFraction(): Float {
        var green = 0
        pixels.forEach { pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (g > r + CHANNEL_MARGIN && g > b + CHANNEL_MARGIN) green++
        }
        return green.toFloat() / pixels.size
    }

    private fun FloatArray.statistics(): Statistics {
        if (isEmpty()) return Statistics(0f, 0f)
        var sum = 0.0
        var sumOfSquares = 0.0
        forEach {
            sum += it
            sumOfSquares += it.toDouble() * it
        }
        val mean = sum / size
        val variance = (sumOfSquares / size) - mean * mean
        return Statistics(mean.toFloat(), sqrt(variance.coerceAtLeast(0.0)).toFloat())
    }

    private data class Statistics(val mean: Float, val stdDev: Float)

    private companion object {
        /** Diferencia mínima entre canales para considerar que el verde domina.
         *  Por debajo de esto el píxel es gris y el «verde» es ruido. */
        const val CHANNEL_MARGIN = 12
    }
}

/**
 * Umbrales de la compuerta 1.
 *
 * Se inyectan en lugar de estar incrustados para que la Fase 0 pueda calibrarlos
 * contra imágenes reales etiquetadas por agrónomo, y para que las pruebas puedan
 * fijarlos sin depender de los valores de producción.
 */
data class QualityThresholds(
    /** Varianza del laplaciano. Por debajo, la imagen está movida o desenfocada. */
    val minSharpness: Float,
    /** Luminancia media 0–255. Por debajo, no hay luz suficiente. */
    val minLuminance: Float,
    /** Luminancia media 0–255. Por encima Y con poca desviación, imagen lavada. */
    val maxLuminance: Float,
    /** Desviación de luminancia. Poca desviación con media alta = sobreexposición
     *  plana; poca desviación con media normal puede ser una hoja uniforme
     *  perfectamente válida, por eso se exigen ambas condiciones. */
    val minLuminanceStdDev: Float,
    /** Fracción de píxeles con verde dominante. */
    val minGreenFraction: Float,
) {
    companion object {
        /**
         * Punto de partida, pendiente de calibración con el dataset de Fase 0.
         *
         * `minGreenFraction` en 0,12 y no más alto porque una hoja con roya
         * avanzada o mancha de hierro extensa tiene poco verde: subirlo
         * rechazaría precisamente los casos más graves, que son los que más
         * importa que lleguen.
         */
        val DEFAULT = QualityThresholds(
            minSharpness = 60f,
            minLuminance = 35f,
            maxLuminance = 225f,
            minLuminanceStdDev = 18f,
            minGreenFraction = 0.12f,
        )

        /** Todo pasa. Para pruebas de capas superiores que no quieren pelearse
         *  con imágenes sintéticas realistas. */
        val PERMISSIVE = QualityThresholds(
            minSharpness = 0f,
            minLuminance = 0f,
            maxLuminance = 255f,
            minLuminanceStdDev = 0f,
            minGreenFraction = 0f,
        )
    }
}
