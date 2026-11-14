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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import co.agrovision.core.designsystem.component.PlantsPer100Counter
import co.agrovision.core.designsystem.component.PlantsPerHaField
import co.agrovision.core.designsystem.component.PrimaryFieldButton
import co.agrovision.core.designsystem.component.SecondaryFieldButton
import co.agrovision.core.designsystem.component.SeverityBandSelector
import co.agrovision.core.designsystem.component.StatusPill
import co.agrovision.core.designsystem.component.StatusTone
import co.agrovision.core.designsystem.theme.AgroTheme
import co.agrovision.core.model.zone.SeverityBand
import co.agrovision.core.model.zone.ZoneEstimate
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Botón «Estimar zona a vigilar» y su hoja de controles manuales. El cálculo lo
 * hace el ViewModel con la regla en caché; aquí solo se recogen los tres valores.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ZoneSection(zone: ZoneAvailability, onIntent: (ScanIntent) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }

    SecondaryFieldButton(
        text = stringResource(R.string.zona_estimar),
        icon = Icons.Filled.MyLocation,
        onClick = { open = true },
    )
    zone.estimate?.let { ZoneResult(it) }

    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ZoneControlsSheet(zone, onIntent, onClose = { open = false })
        }
    }
}

@Composable
private fun ZoneControlsSheet(zone: ZoneAvailability, onIntent: (ScanIntent) -> Unit, onClose: () -> Unit) {
    var severity by rememberSaveable { mutableStateOf<SeverityBand?>(null) }
    var incidence by rememberSaveable { mutableIntStateOf(0) }
    var plantsPerHa by rememberSaveable(zone.plantsPerHa) { mutableIntStateOf(zone.plantsPerHa) }

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
        SeverityBandSelector(selected = severity, onSelect = { severity = it })

        Text(stringResource(R.string.zona_pregunta_incidencia), style = MaterialTheme.typography.titleMedium)
        PlantsPer100Counter(value = incidence, onValueChange = { incidence = it })

        PlantsPerHaField(value = plantsPerHa, onValueChange = { plantsPerHa = it })

        if (severity == null) {
            Text(
                stringResource(R.string.zona_elige_severidad),
                style = MaterialTheme.typography.bodyMedium,
                color = AgroTheme.semantic.mutedText,
            )
        }
        PrimaryFieldButton(
            text = stringResource(R.string.zona_calcular),
            enabled = severity != null,
            onClick = {
                severity?.let { onIntent(ScanIntent.EstimateZone(it, incidence, plantsPerHa)) }
            },
        )
        zone.estimate?.let { ZoneResult(it) }
        SecondaryFieldButton(text = stringResource(R.string.zona_cerrar), onClick = onClose)
    }
}

@Composable
private fun ZoneResult(estimate: ZoneEstimate) {
    Column(verticalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutterTight)) {
        Text(
            stringResource(R.string.zona_resultado_radio, formatMeters(estimate.radioMinM), formatMeters(estimate.radioMaxM)),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.zona_resultado_plantas, estimate.plantasMin, estimate.plantasMax),
            style = MaterialTheme.typography.bodyLarge,
        )
        // Aviso fijo: mientras la regla no esté calibrada, el número es un criterio experto, no una medición.
        StatusPill(
            text = if (estimate.calibrada) stringResource(R.string.zona_calibrada, estimate.reglaVersion)
            else stringResource(R.string.zona_sin_calibrar, estimate.reglaVersion),
            tone = if (estimate.calibrada) StatusTone.POSITIVE else StatusTone.WARNING,
        )
    }
}

/** «15» en vez de «15.0»; «2,5» con coma decimal, como se escribe en Colombia. */
private fun formatMeters(value: Double): String =
    DecimalFormat("0.#", DecimalFormatSymbols(Locale.forLanguageTag("es-CO"))).format(value)
