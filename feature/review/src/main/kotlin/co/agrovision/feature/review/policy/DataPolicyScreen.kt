package co.agrovision.feature.review.policy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.agrovision.core.designsystem.theme.AgroTheme
import co.agrovision.core.model.identity.DataPolicy
import co.agrovision.feature.review.R
import co.agrovision.feature.review.components.ReviewTopBar

/**
 * Texto completo de la Política de Tratamiento de Datos Personales (habeas data,
 * Ley 1581 de 2012). Solo se lee: la aceptación se da en el ingreso y se retira
 * en Ajustes. El responsable del tratamiento es el SENA.
 */
@Composable
fun DataPolicyScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val gutter = AgroTheme.dimensions.gutter
    val sections = listOf(
        R.string.politica_responsable_titulo to R.string.politica_responsable_cuerpo,
        R.string.politica_datos_titulo to R.string.politica_datos_cuerpo,
        R.string.politica_finalidad_titulo to R.string.politica_finalidad_cuerpo,
        R.string.politica_compartir_titulo to R.string.politica_compartir_cuerpo,
        R.string.politica_derechos_titulo to R.string.politica_derechos_cuerpo,
        R.string.politica_facultativo_titulo to R.string.politica_facultativo_cuerpo,
        R.string.politica_contacto_titulo to R.string.politica_contacto_cuerpo,
        R.string.politica_vigencia_titulo to R.string.politica_vigencia_cuerpo,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ReviewTopBar(
            title = stringResource(R.string.politica_titulo),
            onBack = onBack,
            backDescription = stringResource(R.string.politica_atras),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = gutter, end = gutter, bottom = AgroTheme.dimensions.gutterWide),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.politica_nombre),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.politica_version, DataPolicy.CURRENT_VERSION),
                    style = MaterialTheme.typography.labelLarge,
                    color = AgroTheme.semantic.mutedText,
                )
            }
            Text(
                text = stringResource(R.string.politica_intro),
                style = MaterialTheme.typography.bodyLarge,
            )
            sections.forEach { (title, body) ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = stringResource(title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AgroTheme.semantic.mutedText,
                    )
                }
            }
        }
    }
}

/**
 * Casilla de autorización con enlace a la política. Es explícita: nace
 * desmarcada y el productor la marca a conciencia. Se usa en el ingreso y al
 * volver a pedir la aceptación.
 */
@Composable
fun PolicyConsentRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpenPolicy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = AgroTheme.semantic
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(semantic.card)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Text(
                text = stringResource(R.string.ingreso_politica_acepto),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = stringResource(R.string.ingreso_politica_leer),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 48.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onOpenPolicy)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}
