package co.agrovision.core.designsystem.component

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import co.agrovision.core.designsystem.theme.AgroTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Carga una imagen desde `assets/` o desde `filesDir`, decodificada al tamaño en
 * que se va a mostrar.
 *
 * Se escribe a mano en lugar de traer Coil o Glide por una razón concreta: son
 * ~800 KB de dependencia para resolver un caso —imágenes locales, sin red, sin
 * caché HTTP— que aquí cabe en cien líneas. Y el presupuesto de tamaño del APK
 * es un requisito, no una preferencia: cada megabyte sobre datos prepago es
 * fricción real de adopción (§3.2 RNF).
 *
 * `inSampleSize` es lo que evita el cierre por memoria: decodificar una foto de
 * 12 MP para pintarla en una miniatura de 96 dp reserva 48 MB de heap en un
 * teléfono que tiene 2 GB para todo.
 */
@Composable
fun LocalImage(
    /** Nombre dentro de `assets/catalog/`, o ruta absoluta si empieza por `/`. */
    source: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    targetPx: Int = 1024,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    var bitmap by remember(source, targetPx) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(source, targetPx) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openStream(context, source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0) return@runCatching null

                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(
                        maxOf(bounds.outWidth, bounds.outHeight),
                        targetPx,
                    )
                }
                openStream(context, source)?.use {
                    BitmapFactory.decodeStream(it, null, options)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }

    Box(
        modifier = modifier.background(AgroTheme.semantic.specimenBackdrop),
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
        // Sin imagen se deja el fondo neutro del banco. NUNCA un color de marca
        // detrás de un espécimen: desplazaría el tono percibido de la lesión
        // (§11.1).
    }
}

private fun openStream(context: android.content.Context, source: String) =
    runCatching {
        if (source.startsWith("/")) File(source).takeIf { it.isFile }?.inputStream()
        else context.assets.open("catalog/$source")
    }.getOrNull()

private fun sampleSizeFor(sourceLongSide: Int, targetLongSide: Int): Int {
    var sample = 1
    while (sourceLongSide / (sample * 2) >= targetLongSide) sample *= 2
    return sample
}
