package co.agrovision.feature.scan

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import co.agrovision.core.designsystem.theme.AgroTheme
import co.agrovision.core.model.scan.RgbImage
import co.agrovision.core.model.zone.LeafDamageResult

/** % estimado, o `null` si aún no hay o no fue estimable. */
internal val LeafDamage?.estimatedPct: Int?
    get() = (this?.result as? LeafDamageResult.Estimated)?.pct

/**
 * PROTOTIPO: línea de daño estimado en la hoja, debajo del diagnóstico. Siempre
 * rotulada como muy aproximada; si no se pudo estimar, se dice qué hacer. En
 * builds debug ofrece «Ver máscara» para ajustar los umbrales.
 */
@Composable
internal fun LeafDamageBlock(damage: LeafDamage?) {
    Column(
        verticalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutterTight),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val text = when (val result = damage?.result) {
            null -> stringResource(R.string.dano_calculando)
            is LeafDamageResult.Estimated -> stringResource(R.string.dano_estimado, result.pct)
            is LeafDamageResult.NotEstimable -> stringResource(R.string.dano_no_estimable)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = if (damage == null) AgroTheme.semantic.mutedText else MaterialTheme.colorScheme.onSurface,
            // El cálculo llega después del diagnóstico: el lector de pantalla lo anuncia.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        val mask = damage?.debugMask
        if (BuildConfig.DEBUG && mask != null) DebugMaskButton(mask)
    }
}

@Composable
private fun DebugMaskButton(mask: RgbImage) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.dano_ver_mascara)) }
    if (!open) return
    val bitmap = remember(mask) { mask.toImageBitmap() }
    var zoomed by remember { mutableStateOf(false) }
    if (zoomed) ZoomedMask(bitmap, onClose = { zoomed = false })
    AlertDialog(
        onDismissRequest = { open = false },
        // Casi todo el ancho: la máscara es pequeña y en el diálogo estándar se veía diminuta.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f),
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.zona_cerrar)) } },
        title = { Text(stringResource(R.string.dano_mascara_titulo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutter)) {
                Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(R.string.dano_mascara_descripcion),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { zoomed = true },
                )
                Text(
                    stringResource(R.string.dano_mascara_tocar),
                    style = MaterialTheme.typography.bodySmall,
                    color = AgroTheme.semantic.mutedText,
                )
                MaskLegendItem(Color(0xFF2DDC2D), stringResource(R.string.dano_leyenda_hoja))
                MaskLegendItem(Color(0xFFFF00FF), stringResource(R.string.dano_leyenda_dano))
                MaskLegendItem(Color(0xFF2B2B2B), stringResource(R.string.dano_leyenda_fondo))
            }
        },
    )
}

/** Pantalla completa: pellizcar para acercar, arrastrar para mover, tocar la X para cerrar. */
@Composable
private fun ZoomedMask(bitmap: ImageBitmap, onClose: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.dano_mascara_descripcion),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            scale = if (scale > 1f) 1f else 3f
                            offset = Offset.Zero
                        })
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.zona_cerrar), tint = Color.White)
            }
        }
    }
}

@Composable
private fun MaskLegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Borde con Android: ARGB empaquetado a `Bitmap` solo para mostrarlo. */
private fun RgbImage.toImageBitmap(): ImageBitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
