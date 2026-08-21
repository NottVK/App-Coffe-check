package co.agrovision.core.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import co.agrovision.core.common.AgroConstants
import co.agrovision.core.common.AgroDispatcher
import co.agrovision.core.common.AgroResult
import co.agrovision.core.common.Dispatcher
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.port.ImageStore
import co.agrovision.core.model.port.StoredImage
import co.agrovision.core.model.scan.RgbImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Almacenamiento de imágenes en `filesDir` (§9.3).
 *
 * Tres decisiones que vienen del contexto de campo, no de la comodidad:
 *
 * **`filesDir`, nunca MediaStore.** Evita pedir permisos de almacenamiento, no
 * ensucia la galería del productor con doscientas fotos de hojas enfermas, y se
 * limpia solo al desinstalar.
 *
 * **Sin original a resolución completa.** Un teléfono de 32 GB con el
 * almacenamiento casi lleno es el caso común. Se guarda la versión de 1280 px y
 * ya está.
 *
 * **EXIF eliminado ANTES de persistir.** Los metadatos llevan modelo de
 * dispositivo y, con frecuencia, coordenadas que el usuario nunca consintió
 * compartir. Se borran en el dispositivo, no en el servidor: lo que no se escribe
 * no se puede filtrar.
 */
@Singleton
class AndroidImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(AgroDispatcher.IO) private val io: CoroutineDispatcher,
) : ImageStore {

    private val capturesDir: File
        get() = File(context.filesDir, CAPTURES_DIR).apply { mkdirs() }

    private val thumbnailsDir: File
        get() = File(context.filesDir, THUMBNAILS_DIR).apply { mkdirs() }

    override suspend fun persist(
        captureId: String,
        image: RgbImage,
    ): AgroResult<StoredImage, DomainError> = withContext(io) {
        val full = File(capturesDir, "$captureId.jpg")
        val thumb = File(thumbnailsDir, "$captureId.jpg")

        try {
            val bitmap = image.toBitmap()
            val resized = bitmap.scaledToLongSide(AgroConstants.Image.MAX_LONG_SIDE_PX)
            val thumbnail = bitmap.scaledToLongSide(AgroConstants.Image.THUMBNAIL_PX)

            // Se escribe con `compress`, que produce un JPEG sin ningún segmento
            // EXIF. Es la forma más fiable de eliminarlo: no se copia y luego se
            // borra, directamente nunca existe.
            resized.writeJpeg(full)
            thumbnail.writeJpeg(thumb)

            if (resized !== bitmap) resized.recycle()
            if (thumbnail !== bitmap) thumbnail.recycle()
            bitmap.recycle()

            AgroResult.success(
                StoredImage(
                    imagePath = full.absolutePath,
                    thumbnailPath = thumb.absolutePath,
                    sizeBytes = full.length() + thumb.length(),
                )
            )
        } catch (error: IOException) {
            // Escritura a medias: si quedó un archivo parcial, se borra. Una fila
            // en Room apuntando a un JPEG truncado es peor que no tener la fila.
            full.delete()
            thumb.delete()
            AgroResult.failure(
                if (isOutOfSpace(error)) DomainError.StorageFull
                else DomainError.PersistenceFailed(error.message ?: "escritura fallida")
            )
        } catch (error: OutOfMemoryError) {
            // Real en dispositivos de 2 GB con una imagen de 12 MP. No es
            // paranoia: es el modo de fallo habitual de la gama baja.
            full.delete()
            thumb.delete()
            AgroResult.failure(DomainError.PersistenceFailed("memoria insuficiente al comprimir"))
        }
    }

    override suspend fun load(path: String): RgbImage? = withContext(io) {
        val file = File(path)
        if (!file.exists()) return@withContext null
        BitmapFactory.decodeFile(path)?.let { bitmap ->
            bitmap.toRgbImage().also { bitmap.recycle() }
        }
    }

    override suspend fun delete(paths: List<String>) = withContext(io) {
        paths.forEach { runCatching { File(it).delete() } }
    }

    override suspend fun totalBytes(): Long = withContext(io) {
        sequenceOf(capturesDir, thumbnailsDir)
            .flatMap { it.listFiles()?.asSequence().orEmpty() }
            .sumOf { it.length() }
    }

    private fun Bitmap.writeJpeg(target: File) {
        target.outputStream().use { output ->
            val ok = compress(Bitmap.CompressFormat.JPEG, AgroConstants.Image.JPEG_QUALITY, output)
            if (!ok) throw IOException("No se pudo comprimir a JPEG: ${target.name}")
            output.flush()
        }
    }

    private fun isOutOfSpace(error: IOException): Boolean =
        error.message?.contains("ENOSPC", ignoreCase = true) == true ||
            error.message?.contains("No space", ignoreCase = true) == true

    private companion object {
        const val CAPTURES_DIR = "captures"
        const val THUMBNAILS_DIR = "thumbnails"
    }
}

/** Redimensiona conservando la relación de aspecto. Devuelve el mismo bitmap si
 *  ya cabe: reescalar hacia arriba solo gastaría memoria y no añade detalle. */
internal fun Bitmap.scaledToLongSide(longSidePx: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest <= longSidePx) return this
    val ratio = longSidePx.toFloat() / longest
    return Bitmap.createScaledBitmap(
        this,
        (width * ratio).roundToInt().coerceAtLeast(1),
        (height * ratio).roundToInt().coerceAtLeast(1),
        true,
    )
}

internal fun Bitmap.toRgbImage(): RgbImage {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return RgbImage(width, height, pixels)
}

internal fun RgbImage.toBitmap(): Bitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
