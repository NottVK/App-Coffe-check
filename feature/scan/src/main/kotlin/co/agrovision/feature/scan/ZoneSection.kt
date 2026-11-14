package co.agrovision.feature.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import co.agrovision.core.designsystem.component.PlantsPer100Counter
import co.agrovision.core.designsystem.component.PlantsPerHaField
import co.agrovision.core.designsystem.component.PrimaryFieldButton
import co.agrovision.core.designsystem.component.SecondaryFieldButton
import co.agrovision.core.designsystem.component.SeverityBandSelector
import co.agrovision.core.designsystem.component.StatusPill
import co.agrovision.core.designsystem.component.StatusTone
import co.agrovision.core.designsystem.component.severityBandLabel
import co.agrovision.core.designsystem.theme.AgroTheme
import co.agrovision.core.model.zone.SeverityBand
import co.agrovision.core.model.zone.ZoneDefaults
import co.agrovision.core.model.zone.ZoneRule
import co.agrovision.core.model.zone.ZoneScenario
import co.agrovision.core.model.zone.formatZoneNumber
import co.agrovision.core.model.zone.scenarios

/**
 * Botón «Estimar zona a vigilar» y su hoja de controles manuales. El cálculo lo
 * hace el ViewModel con la regla en caché; aquí solo se recogen los tres valores.
 *
 * El formulario vive aquí y no dentro de la hoja: cerrar y reabrir conserva lo
 * elegido, y si los controles ya no son los del cálculo el resultado se marca
 * como desactualizado (dentro y fuera de la hoja).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ZoneSection(zone: ZoneAvailability, onIntent: (ScanIntent) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val form = rememberZoneForm(zone)

    SecondaryFieldButton(
        text = stringResource(R.string.zona_estimar),
        icon = Icons.Filled.MyLocation,
        onClick = { open = true },
    )
    ZoneResult(zone, stale = zone.isStale(form.inputs), announce = false)

    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ZoneControlsSheet(zone, form, onIntent, onClose = { open = false })
        }
    }
}

/** Estado del formulario: texto de la densidad incluido, para validar lo que se ve. */
private class ZoneFormState(
    severityState: MutableState<SeverityBand?>,
    incidenceState: MutableIntState,
    plantsTextState: MutableState<String>,
) {
    var severity by severityState
    var incidence by incidenceState
    var plantsText by plantsTextState

    /** Densidad válida, o `null`: con `null` no se puede calcular. */
    val plantsPerHa: Int? get() = ZoneDefaults.parsePlantsPerHa(plantsText)

    /** Valores completos y válidos para calcular, o `null`. */
    val inputs: ZoneInputs? get() = severity?.let { s -> plantsPerHa?.let { ZoneInputs(s, incidence, it) } }
}

@Composable
private fun rememberZoneForm(zone: ZoneAvailability): ZoneFormState {
    // Si ya hay un cálculo, el formulario parte de sus datos.
    val severity = rememberSaveable { mutableStateOf(zone.inputs?.severity) }
    val incidence = rememberSaveable { mutableIntStateOf(zone.inputs?.incidencePct ?: 0) }
    val plantsText = rememberSaveable { mutableStateOf((zone.inputs?.plantsPerHa ?: zone.plantsPerHa).toString()) }
    return remember(severity, incidence, plantsText) { ZoneFormState(severity, incidence, plantsText) }
}

@Composable
private fun ZoneControlsSheet(
    zone: ZoneAvailability,
    form: ZoneFormState,
    onIntent: (ScanIntent) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = AgroTheme.dimensions.gutterWide)
            .padding(bottom = AgroTheme.dimensions.gutterWide),
        verticalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutter),
    ) {
        Text(
            stringResource(R.string.zona_titulo),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.zona_pregunta_severidad), style = MaterialTheme.typography.titleMedium)
        SeverityBandSelector(selected = form.severity, onSelect = { form.severity = it })

        Text(stringResource(R.string.zona_pregunta_incidencia), style = MaterialTheme.typography.titleMedium)
        PlantsPer100Counter(value = form.incidence, onValueChange = { form.incidence = it })

        PlantsPerHaField(text = form.plantsText, onTextChange = { form.plantsText = it })

        val hint = when {
            form.severity == null -> R.string.zona_elige_severidad
            form.plantsPerHa == null -> R.string.zona_densidad_invalida
            else -> null
        }
        hint?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                color = AgroTheme.semantic.mutedText,
            )
        }
        val inputs = form.inputs
        PrimaryFieldButton(
            text = stringResource(R.string.zona_calcular),
            // Sin densidad válida no se calcula: nunca con un valor distinto del visible.
            enabled = inputs != null,
            onClick = {
                inputs?.let { onIntent(ScanIntent.EstimateZone(it.severity, it.incidencePct, it.plantsPerHa)) }
            },
        )
        ZoneResult(zone, stale = zone.isStale(inputs), announce = true)
        SecondaryFieldButton(text = stringResource(R.string.zona_cerrar), onClick = onClose)
    }
}

/**
 * Resultado con los datos con que se calculó. [announce]: región viva para que
 * el lector de pantalla lea la nueva estimación (o el aviso de desactualizada)
 * al pulsar «Calcular»; solo en la hoja, para no anunciarlo dos veces.
 */
@Composable
private fun ZoneResult(zone: ZoneAvailability, stale: Boolean, announce: Boolean) {
    val estimate = zone.estimate ?: return
    Column(
        verticalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutterTight),
        modifier = Modifier.semantics(mergeDescendants = true) {
            if (announce) liveRegion = LiveRegionMode.Polite
        },
    ) {
        Text(
            stringResource(R.string.zona_resultado_radio, formatZoneNumber(estimate.radioMinM), formatZoneNumber(estimate.radioMaxM)),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.zona_resultado_plantas, estimate.plantasMin, estimate.plantasMax),
            style = MaterialTheme.typography.bodyLarge,
        )
        zone.inputs?.let { inputs ->
            Text(
                stringResource(
                    R.string.zona_resultado_datos,
                    severityBandLabel(inputs.severity),
                    inputs.incidencePct,
                    inputs.plantsPerHa,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = AgroTheme.semantic.mutedText,
            )
        }
        if (stale) {
            StatusPill(text = stringResource(R.string.zona_resultado_desactualizado), tone = StatusTone.WARNING)
        }
        // Aviso fijo: mientras la regla no esté calibrada, el número es un criterio experto, no una medición.
        StatusPill(
            text = if (estimate.calibrada) stringResource(R.string.zona_calibrada, estimate.reglaVersion)
            else stringResource(R.string.zona_sin_calibrar, estimate.reglaVersion),
            tone = if (estimate.calibrada) StatusTone.POSITIVE else StatusTone.WARNING,
        )
    }
}

/**
 * Tabla de la regla como escenarios, sin que el productor toque nada. Va debajo
 * del diagnóstico; los controles manuales de [ZoneSection] siguen aparte. Si la
 * regla no es válida no se muestra nada (ni siquiera el título).
 */
@Composable
internal fun ZoneScenariosBlock(rule: ZoneRule) {
    val scenarios = remember(rule) { rule.scenarios(rule.tipoEnfermedad) }
    if (scenarios.isEmpty()) return
    Column(
        verticalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutterTight),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.zona_escenarios_titulo),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        scenarios.forEach { s ->
            // Cada fila es un solo texto: el lector de pantalla la lee completa, en orden.
            Text(
                text = when (s.kind) {
                    ZoneScenario.Kind.HASTA ->
                        stringResource(R.string.zona_escenario_hasta, s.incidenciaPct, s.severidadPct, s.radioMin, s.radioMax)
                    ZoneScenario.Kind.MAS -> stringResource(R.string.zona_escenario_mas, s.radioMin, s.radioMax)
                    ZoneScenario.Kind.CUALQUIERA -> stringResource(R.string.zona_escenario_cualquiera, s.radioMin, s.radioMax)
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = AgroTheme.dimensions.gutter),
            )
        }
        // Aviso fijo: sin calibrar, los radios son criterio experto, no una medición.
        StatusPill(
            text = if (rule.calibrada) stringResource(R.string.zona_calibrada, rule.version)
            else stringResource(R.string.zona_sin_calibrar, rule.version),
            tone = if (rule.calibrada) StatusTone.POSITIVE else StatusTone.WARNING,
        )
    }
}
