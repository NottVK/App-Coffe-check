package co.agrovision.feature.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.agrovision.core.designsystem.component.EmptyState
import co.agrovision.core.designsystem.component.LocalImage
import co.agrovision.core.designsystem.component.SeverityStrip
import co.agrovision.core.designsystem.component.SeverityStripDensity
import co.agrovision.core.designsystem.component.StatusPill
import co.agrovision.core.designsystem.component.StatusTone
import co.agrovision.core.designsystem.theme.AgroTheme
import co.agrovision.core.model.pest.PestKind
import co.agrovision.core.model.pest.PestTaxon
import co.agrovision.core.model.pest.SeverityLevel

/**
 * Catálogo de plagas y problemas del cultivo.
 *
 * Es la pantalla que el productor consulta **en el lote, sin señal**, así que
 * todo lo que muestra sale de Room. Nada aquí depende de la red ni del modelo.
 *
 * La búsqueda incluye los sinónimos regionales (§2.1): en el Macizo una misma
 * plaga tiene tres o cuatro nombres según la vereda, y el productor teclea el
 * que conoce, no el binomio latino.
 */
@Composable
fun CatalogScreen(
    state: CatalogUiState,
    onQueryChange: (String) -> Unit,
    onPestClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        SearchField(
            query = state.query,
            onQueryChange = onQueryChange,
            modifier = Modifier.padding(
                start = AgroTheme.dimensions.gutter,
                end = AgroTheme.dimensions.gutter,
                top = AgroTheme.dimensions.gutterTight,
                bottom = AgroTheme.dimensions.gutterTight,
            ),
        )

        when {
            state.isEmptyCatalog -> EmptyState(
                title = stringResource(R.string.catalogo_vacio_titulo),
                body = stringResource(R.string.catalogo_vacio_cuerpo),
                icon = Icons.Filled.Search,
            )

            state.isEmptySearch -> EmptyState(
                title = stringResource(R.string.catalogo_sin_resultados_titulo, state.query),
                body = stringResource(R.string.catalogo_sin_resultados_cuerpo),
                icon = Icons.Filled.Search,
            )

            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = AgroTheme.dimensions.gutter,
                    end = AgroTheme.dimensions.gutter,
                    bottom = AgroTheme.dimensions.gutterWide,
                ),
                verticalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutterTight),
            ) {
                items(state.results, key = { it.id }) { pest ->
                    PestCard(pest = pest, onClick = { onPestClick(pest.id) })
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(AgroTheme.dimensions.cardRadius),
        placeholder = { Text(stringResource(R.string.catalogo_buscar)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Borrar búsqueda")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

/**
 * Tarjeta de espécimen.
 *
 * Lleva la **tira de severidad** en el borde superior, que es el elemento firma
 * del sistema (§11.4): permite escanear una lista y detectar el patrón sin leer
 * una sola etiqueta.
 *
 * ⚠️ La severidad que muestra es la de la **escala típica de la plaga**, no la
 * de un caso concreto. Cuando exista una captura asociada, será la medida.
 */
@Composable
private fun PestCard(pest: PestTaxon, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(AgroTheme.dimensions.cardRadius),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        SeverityStrip(
            level = pest.kind.indicativeSeverity(),
            density = SeverityStripDensity.NORMAL,
        )

        Row(
            modifier = Modifier.padding(AgroTheme.dimensions.gutterTight),
            horizontalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutterTight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LocalImage(
                source = pest.imageRefs.firstOrNull().orEmpty(),
                contentDescription = null,
                targetPx = 256,
                modifier = Modifier
                    .size(THUMBNAIL)
                    .clip(RoundedCornerShape(8.dp)),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = pest.commonName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pest.scientificName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = AgroTheme.semantic.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(modifier = Modifier.padding(top = 4.dp)) {
                    KindPill(pest.kind)
                }
            }
        }
    }
}

@Composable
internal fun KindPill(kind: PestKind) {
    StatusPill(
        text = stringResource(kind.labelRes()),
        // El tono NO es decorativo: una enfermedad y una deficiencia exigen
        // acciones distintas —fungicida contra fertilización— y confundirlas
        // hace que el productor fumigue una carencia de potasio.
        tone = when (kind) {
            PestKind.ENFERMEDAD -> StatusTone.CRITICAL
            PestKind.PLAGA -> StatusTone.WARNING
            PestKind.DEFICIENCIA -> StatusTone.NEUTRAL
            PestKind.SANO -> StatusTone.POSITIVE
            PestKind.OTRO -> StatusTone.NEUTRAL
        },
    )
}

internal fun PestKind.labelRes(): Int = when (this) {
    PestKind.ENFERMEDAD -> R.string.tipo_enfermedad
    PestKind.PLAGA -> R.string.tipo_plaga
    PestKind.DEFICIENCIA -> R.string.tipo_deficiencia
    PestKind.SANO -> R.string.tipo_sano
    PestKind.OTRO -> R.string.tipo_otro
}

/** Severidad orientativa por tipo de problema, para la tira de la tarjeta. */
private fun PestKind.indicativeSeverity(): SeverityLevel = when (this) {
    PestKind.ENFERMEDAD -> SeverityLevel.ALTO
    PestKind.PLAGA -> SeverityLevel.MODERADO
    PestKind.DEFICIENCIA -> SeverityLevel.INCIPIENTE
    PestKind.SANO -> SeverityLevel.SANO
    PestKind.OTRO -> SeverityLevel.INCIPIENTE
}

private val THUMBNAIL = 72.dp
