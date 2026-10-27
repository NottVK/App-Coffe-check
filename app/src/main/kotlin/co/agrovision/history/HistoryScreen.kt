package co.agrovision.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.agrovision.R
import co.agrovision.core.designsystem.component.EmptyState
import co.agrovision.core.designsystem.component.LocalImage
import co.agrovision.core.designsystem.theme.AgroTheme
import co.agrovision.core.model.scan.GateOutcome
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale("es", "CO"))

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onItemClick: (HistoryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gutter = AgroTheme.dimensions.gutter

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Text(
            text = stringResource(R.string.historial_titulo),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = gutter, end = gutter, top = gutter, bottom = 8.dp),
        )

        if (!state.loading && state.items.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.historial_vacio_titulo),
                body = stringResource(R.string.historial_vacio_cuerpo),
                icon = Icons.Filled.History,
            )
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 8.dp, bottom = gutter),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.items, key = { it.id }) { item ->
                HistoryRow(item = item, onClick = { onItemClick(item) })
            }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, onClick: () -> Unit) {
    val semantic = AgroTheme.semantic
    // Solo se puede abrir la ficha si el análisis apuntó a una.
    val tappable = if (item.pestId != null) Modifier.clickable(onClick = onClick) else Modifier

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(semantic.card)
            .then(tappable)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LocalImage(
            source = item.thumbnailPath,
            contentDescription = null,
            targetPx = 256,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = item.pestName ?: stringResource(item.outcome.fallbackLabel()),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = Instant.ofEpochMilli(item.capturedAtEpochMillis)
                    .atZone(ZoneId.systemDefault())
                    .format(DATE_FORMAT),
                style = MaterialTheme.typography.bodyMedium,
                color = semantic.mutedText,
            )
        }
        item.confidencePercent?.let {
            Text(
                text = stringResource(R.string.historial_confianza, it),
                style = MaterialTheme.typography.labelLarge,
                color = semantic.onAccent,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(semantic.accent)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

private fun GateOutcome.fallbackLabel(): Int = when (this) {
    GateOutcome.IDENTIFIED, GateOutcome.UNCERTAIN, GateOutcome.OOD -> R.string.historial_sin_identificar
    GateOutcome.QUALITY_REJECTED -> R.string.historial_foto_rechazada
}
