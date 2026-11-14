package co.agrovision.feature.scan

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
    AlertDialog(
        onDismissRequest = { open = false },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.zona_cerrar)) } },
        title = { Text(stringResource(R.string.dano_mascara_titulo)) },
        text = {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.dano_mascara_descripcion),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/** Borde con Android: ARGB empaquetado a `Bitmap` solo para mostrarlo. */
private fun RgbImage.toImageBitmap(): ImageBitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
