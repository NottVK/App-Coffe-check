package co.agrovision.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import co.agrovision.core.designsystem.R
import co.agrovision.core.designsystem.theme.AgroTheme
import kotlin.math.roundToInt

/**
 * Barra de confianza calibrada.
 *
 * Dos decisiones que parecen menores y no lo son:
 *
 * **El número va siempre, en monoespaciada.** Una barra sin cifra invita a
 * estimar «como tres cuartos», y la diferencia entre 0,74 y 0,86 sí importa.
 *
 * **La barra es neutra, no verde-amarillo-rojo.** El semáforo lo ocupa la tira
 * de severidad, que codifica el estado de la planta. Si la confianza también
 * usara la rampa, dos informaciones distintas competirían por el mismo canal
 * perceptual y el productor leería «confianza baja» como «planta sana».
 */
@Composable
fun ConfidenceBar(
    confidence: Float,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val clamped = confidence.coerceIn(0f, 1f)
    val percent = (clamped * 100).roundToInt()
    val description = stringResource(R.string.confianza_descripcion, percent)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f, fill = false),
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .height(BAR_HEIGHT)
                .clip(RoundedCornerShape(BAR_HEIGHT / 2))
                .background(AgroTheme.semantic.divider),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .height(BAR_HEIGHT)
                    .background(AgroTheme.semantic.dataText),
            )
        }

        Text(
            // Monoespaciada: las cifras se alinean en columna al listar varias
            // predicciones, y la lista se escanea de un vistazo (§11.3).
            text = "$percent %",
            style = MaterialTheme.typography.titleSmall,
            color = AgroTheme.semantic.dataText,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

private val BAR_HEIGHT = 8.dp
