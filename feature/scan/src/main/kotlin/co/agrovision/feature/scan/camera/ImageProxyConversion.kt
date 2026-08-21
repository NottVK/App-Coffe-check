package co.agrovision.feature.scan.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import co.agrovision.core.model.scan.RgbImage

/**
 * Convierte lo que entrega CameraX en la representación que entiende el dominio.
 *
 * `ImageCapture` produce JPEG por defecto, así que basta decodificarlo: no hace
 * falta la conversión YUV_420_888 → RGB, que es donde se cuelan los errores de
 * planos y *stride* y donde acaban saliendo imágenes con franjas verdes.
 *
 * ## La rotación no es opcional
 *
 * El sensor de la cámara está montado en horizontal casi siempre. Si no se
 * aplica `rotationDegrees`, el modelo recibe la hoja girada 90°, y como se
 * entrenó con fotos derechas, la exactitud cae sin que nada falle de forma
 * visible. Es el tipo de error que se atribuye al modelo cuando en realidad
 * está en el preprocesado.
 */
internal fun ImageProxy.toRgbImage(maxLongSide: Int): RgbImage? {
    val jpeg = planes.firstOrNull()?.buffer ?: return null
    val bytes = ByteArray(jpeg.remaining()).also { jpeg.get(it) }

    // Se decodifica ya reducido: una foto de 12 MP en un teléfono de 2 GB es
    // memoria que puede no haber, y de todos modos se va a escalar a 224 px.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight), maxLongSide)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

    val rotated = decoded.rotatedBy(imageInfo.rotationDegrees)
    val image = rotated.toRgbImage()

    if (rotated !== decoded) rotated.recycle()
    decoded.recycle()
    return image
}

/** Potencia de dos mayor que no baje del objetivo: `inSampleSize` solo acepta
 *  potencias de dos y redondea hacia abajo si le das otra cosa. */
private fun sampleSizeFor(sourceLongSide: Int, targetLongSide: Int): Int {
    var sample = 1
    while (sourceLongSide / (sample * 2) >= targetLongSide) sample *= 2
    return sample
}

private fun Bitmap.rotatedBy(degrees: Int): Bitmap {
    if (degrees % 360 == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

private fun Bitmap.toRgbImage(): RgbImage {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return RgbImage(width, height, pixels)
}
