package co.agrovision.ml.preprocessing

import co.agrovision.core.common.AgroConstants
import co.agrovision.core.model.scan.RgbImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Convierte una captura en el tensor de entrada del modelo: recorte central,
 * redimensionado a 224×224 y normalización (§5.1, paso 3).
 *
 * Se implementa en Kotlin puro, sin `Bitmap`, por dos razones:
 *
 *  1. **Se puede probar en la JVM.** El preprocesado es donde se cuelan los
 *     errores silenciosos —canales invertidos, normalización con las
 *     estadísticas equivocadas, recorte descentrado— y todos degradan la
 *     exactitud sin producir ningún fallo visible. Poder probarlo con imágenes
 *     sintéticas y comparar contra valores calculados a mano es lo que los
 *     detecta.
 *  2. **`Bitmap.createScaledBitmap` asigna un bitmap intermedio.** En un
 *     dispositivo de 2 GB, durante una ráfaga de capturas, eso importa.
 */
@Singleton
class TensorPreprocessor @Inject constructor() {

    /**
     * Recorte **central**, no escalado deformante.
     *
     * Escalar una foto 4:3 a un cuadrado estira la lesión y cambia su relación de
     * aspecto, que es una de las señales que el modelo aprendió. El recorte
     * central conserva la geometría y además se corresponde con cómo encuadra el
     * productor: la guía de encuadre pone la lesión en el centro.
     */
    fun centerCropAndResize(image: RgbImage, side: Int = AgroConstants.Model.INPUT_SIZE_PX): RgbImage {
        val cropSide = minOf(image.width, image.height)
        val offsetX = (image.width - cropSide) / 2
        val offsetY = (image.height - cropSide) / 2

        val output = IntArray(side * side)
        // Escala en el espacio del recorte, con medio píxel de corrección para
        // muestrear en el centro de cada texel y no en su esquina.
        val scale = cropSide.toFloat() / side

        for (y in 0 until side) {
            val sourceY = (y + 0.5f) * scale - 0.5f + offsetY
            for (x in 0 until side) {
                val sourceX = (x + 0.5f) * scale - 0.5f + offsetX
                output[y * side + x] = image.bilinearSample(sourceX, sourceY)
            }
        }
        return RgbImage(side, side, output)
    }

    /**
     * Escribe el tensor de entrada.
     *
     * @param quantized `true` para modelos INT8 (el caso de producción): los
     *   bytes van crudos 0–255 y el propio grafo aplica la escala de
     *   cuantización. Normalizar aquí *y* dejar que el grafo escale otra vez es
     *   un error clásico que degrada la exactitud sin fallar.
     * @param mean y @param stdDev estadísticas del preentrenamiento. Solo se
     *   aplican en modelos float.
     */
    fun toInputBuffer(
        image: RgbImage,
        quantized: Boolean,
        mean: FloatArray = IMAGENET_MEAN,
        stdDev: FloatArray = IMAGENET_STD,
    ): ByteBuffer {
        val pixelCount = image.width * image.height
        val bytesPerChannel = if (quantized) 1 else Float.SIZE_BYTES
        val buffer = ByteBuffer
            .allocateDirect(pixelCount * AgroConstants.Model.INPUT_CHANNELS * bytesPerChannel)
            .order(ByteOrder.nativeOrder())

        image.pixels.forEach { pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            if (quantized) {
                buffer.put(r.toByte())
                buffer.put(g.toByte())
                buffer.put(b.toByte())
            } else {
                buffer.putFloat((r / 255f - mean[0]) / stdDev[0])
                buffer.putFloat((g / 255f - mean[1]) / stdDev[1])
                buffer.putFloat((b / 255f - mean[2]) / stdDev[2])
            }
        }

        buffer.rewind()
        return buffer
    }

    /** Interpolación bilineal con acotado en bordes. */
    private fun RgbImage.bilinearSample(x: Float, y: Float): Int {
        val x0 = x.toIntFloor().coerceIn(0, width - 1)
        val y0 = y.toIntFloor().coerceIn(0, height - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)

        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)

        val p00 = pixels[y0 * width + x0]
        val p10 = pixels[y0 * width + x1]
        val p01 = pixels[y1 * width + x0]
        val p11 = pixels[y1 * width + x1]

        fun channel(shift: Int): Int {
            val c00 = (p00 shr shift) and 0xFF
            val c10 = (p10 shr shift) and 0xFF
            val c01 = (p01 shr shift) and 0xFF
            val c11 = (p11 shr shift) and 0xFF
            val top = c00 + (c10 - c00) * fx
            val bottom = c01 + (c11 - c01) * fx
            return (top + (bottom - top) * fy).toInt().coerceIn(0, 255)
        }

        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun Float.toIntFloor(): Int = kotlin.math.floor(this).toInt()

    companion object {
        /** Estadísticas de ImageNet, que es donde se preentrena la red base. */
        val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
