package co.agrovision.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.agrovision.core.designsystem.R
import co.agrovision.core.designsystem.theme.AgroTheme
import co.agrovision.core.model.zone.SeverityBand
import co.agrovision.core.model.zone.ZoneDefaults
import kotlinx.coroutines.delay

/**
 * Controles manuales de la estimación de zona: el productor elige con botones
 * grandes, no escribe porcentajes. Cada rango tiene un hueco para la foto de
 * referencia que pondrá el agrónomo; mientras no exista se dibuja un marcador.
 */
@Composable
fun SeverityBandSelector(
    selected: SeverityBand?,
    onSelect: (SeverityBand) -> Unit,
    modifier: Modifier = Modifier,
    referenceImage: @Composable (SeverityBand) -> Unit = { ReferenceImagePlaceholder() },
) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutterTight),
    ) {
        SeverityBand.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutterTight)) {
                pair.forEach { band ->
                    val isSelected = band == selected
                    val label = stringResource(band.labelRes())
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = maxOf(96.dp, AgroTheme.dimensions.minTouchTarget))
                            .clip(MaterialTheme.shapes.large)
                            .border(
                                BorderStroke(
                                    if (isSelected) 3.dp else AgroTheme.dimensions.borderWidth * 1.5f,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                ),
                                MaterialTheme.shapes.large,
                            )
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                            )
                            .selectable(selected = isSelected, role = Role.RadioButton) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSelect(band)
                            }
                            .semantics { contentDescription = label }
                            .padding(AgroTheme.dimensions.gutterTight),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        referenceImage(band)
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.Center,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/** Marcador de la foto de referencia por nivel. Decorativo: el texto del botón ya lo describe. */
@Composable
fun ReferenceImagePlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.medium)
            .background(AgroTheme.semantic.divider),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Image,
            contentDescription = null,
            tint = AgroTheme.semantic.mutedText,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * «Plantas de cada 100» (incidencia). Toque: ±[step]; mantener pulsado: saltos
 * de [holdStep] mientras dure la pulsación.
 */
@Composable
fun PlantsPer100Counter(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 5,
    holdStep: Int = 10,
) {
    val description = stringResource(R.string.zona_incidencia_descripcion, value)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutter),
    ) {
        RepeatingStepButton(
            icon = Icons.Filled.Remove,
            contentDescription = stringResource(R.string.zona_incidencia_menos),
            enabled = value > 0,
            onStep = { hold -> onValueChange((value - if (hold) holdStep else step).coerceIn(0, 100)) },
        )
        Text(
            text = stringResource(R.string.zona_incidencia_valor, value),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .semantics {
                    stateDescription = description
                    liveRegion = LiveRegionMode.Polite
                },
        )
        RepeatingStepButton(
            icon = Icons.Filled.Add,
            contentDescription = stringResource(R.string.zona_incidencia_mas),
            enabled = value < 100,
            onStep = { hold -> onValueChange((value + if (hold) holdStep else step).coerceIn(0, 100)) },
        )
    }
}

@Composable
private fun RepeatingStepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onStep: (hold: Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val currentStep by rememberUpdatedState(onStep)
    // Si la pulsación ya repitió, el clic al soltar no suma otro paso.
    var repeated by remember { mutableStateOf(false) }

    LaunchedEffect(pressed, enabled) {
        if (!pressed || !enabled) return@LaunchedEffect
        repeated = false
        delay(HOLD_START_MS)
        while (true) {
            repeated = true
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentStep(true)
            delay(HOLD_REPEAT_MS)
        }
    }

    FilledTonalIconButton(
        onClick = {
            if (repeated) { repeated = false; return@FilledTonalIconButton }
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            currentStep(false)
        },
        enabled = enabled,
        interactionSource = interaction,
        modifier = Modifier.size(maxOf(64.dp, AgroTheme.dimensions.minTouchTarget)),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(32.dp))
    }
}

/**
 * Densidad de siembra editable. Solo notifica valores válidos
 * (entre [ZoneDefaults.MIN_PLANTS_PER_HA] y [ZoneDefaults.MAX_PLANTS_PER_HA]).
 */
@Composable
fun PlantsPerHaField(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in ZoneDefaults.MIN_PLANTS_PER_HA..ZoneDefaults.MAX_PLANTS_PER_HA
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val digits = input.filter(Char::isDigit).take(7)
            text = digits
            digits.toIntOrNull()
                ?.takeIf { it in ZoneDefaults.MIN_PLANTS_PER_HA..ZoneDefaults.MAX_PLANTS_PER_HA }
                ?.let(onValueChange)
        },
        label = { Text(stringResource(R.string.zona_plantas_ha)) },
        supportingText = {
            Text(stringResource(if (valid) R.string.zona_plantas_ha_ayuda else R.string.zona_plantas_ha_invalido))
        },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AgroTheme.dimensions.minTouchTarget),
    )
}

private fun SeverityBand.labelRes(): Int = when (this) {
    SeverityBand.POCA -> R.string.zona_severidad_poca
    SeverityBand.MEDIA -> R.string.zona_severidad_media
    SeverityBand.ALTA -> R.string.zona_severidad_alta
    SeverityBand.MUY_ALTA -> R.string.zona_severidad_muy_alta
}

private const val HOLD_START_MS = 450L
private const val HOLD_REPEAT_MS = 180L
