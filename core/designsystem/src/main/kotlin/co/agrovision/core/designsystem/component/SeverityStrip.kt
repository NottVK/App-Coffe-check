package co.agrovision.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.agrovision.core.common.AgroConstants
import co.agrovision.core.designsystem.R
import co.agrovision.core.designsystem.theme.AgroTheme
import co.agrovision.core.model.pest.SeverityLevel

/**
 * **Elemento firma del sistema de diseño (§11.4).**
 *
 * Cinco muescas en el borde superior de toda tarjeta de espécimen, fila de tabla
 * y marcador del mapa. Es el único elemento consistente en los tres contextos.
 *
 * ```
 * ▬▬▬▬▬ sano     ▰▰▬▬▬ moderado     ▰▰▰▰▰ severo
 * ```
 *
 * Permite escanear sesenta especímenes y detectar el patrón sin leer una sola
 * etiqueta. **Codifica información real; no decora.** Esa distinción es la
 * razón por la que no se le añaden gradientes, sombras ni animaciones de
 * entrada: cualquier adorno reduce la velocidad con la que se lee el patrón,
 * que es exactamente lo que la tira existe para maximizar.
 *
 * Accesibilidad: el color por sí solo nunca es el canal único de información.
 * La descripción de contenido enuncia el nivel, y la posición de las muescas
 * llenas lo comunica sin depender del color en absoluto — lo que además es lo
 * que hace que funcione a pleno sol.
 */
@Composable
fun SeverityStrip(
    level: SeverityLevel,
    modifier: Modifier = Modifier,
    density: SeverityStripDensity = SeverityStripDensity.NORMAL,
) {
    val ramp = AgroTheme.semantic.severity
    val description = stringResource(R.string.severidad_descripcion, severityLabel(level), level.step)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(density.height)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(NOTCH_GAP),
    ) {
        repeat(NOTCH_COUNT) { index ->
            val filled = index <= level.step
            val target = if (filled) ramp[level.step] else ramp.empty
            val color by animateColorAsState(
                targetValue = target,
                animationSpec = tween(AgroConstants.Ui.MOTION_FAST_MS),
                label = "muesca-$index",
            )
            Row(
                Modifier
                    .weight(1f)
                    .height(density.height)
                    .background(color)
            ) {}
        }
    }
}

enum class SeverityStripDensity(val height: Dp) {
    COMPACT(2.dp),
    NORMAL(3.dp),
    PROMINENT(5.dp),
}

private const val NOTCH_COUNT = 5
private val NOTCH_GAP = 1.dp

@Composable
private fun severityLabel(level: SeverityLevel): String = stringResource(
    when (level) {
        SeverityLevel.SANO -> R.string.severidad_sano
        SeverityLevel.INCIPIENTE -> R.string.severidad_incipiente
        SeverityLevel.MODERADO -> R.string.severidad_moderado
        SeverityLevel.ALTO -> R.string.severidad_alto
        SeverityLevel.SEVERO -> R.string.severidad_severo
    }
)

@Preview(widthDp = 200, backgroundColor = 0xFFF9FAFB, showBackground = true)
@Composable
private fun SeverityStripPreview() {
    AgroTheme {
        androidx.compose.foundation.layout.Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.width(200.dp),
        ) {
            SeverityLevel.entries.forEach { SeverityStrip(it) }
        }
    }
}
