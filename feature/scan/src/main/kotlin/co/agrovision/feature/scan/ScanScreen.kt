package co.agrovision.feature.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.agrovision.core.designsystem.component.ConfidenceBar
import co.agrovision.core.designsystem.component.EmptyState
import co.agrovision.core.designsystem.component.PrimaryFieldButton
import co.agrovision.core.designsystem.component.SecondaryFieldButton
import co.agrovision.core.designsystem.component.SeverityStrip
import co.agrovision.core.designsystem.component.SeverityStripDensity
import co.agrovision.core.designsystem.text.resolve
import co.agrovision.core.designsystem.theme.AgroTheme
import co.agrovision.core.common.UiText
import co.agrovision.core.model.pest.SeverityLevel

/**
 * Pantalla de escaneo.
 *
 * Recibe `state` y `onIntent`; no conoce el ViewModel. Eso la hace previsualizable
 * y probable con Compose UI Test sin montar el grafo de Hilt.
 *
 * **Lo que esta pantalla NO hace, y es deliberado:** cuando el modelo duda o no
 * reconoce, no muestra ninguna predicción como si fuera un diagnóstico. Mostrar
 * «roya, 43 %» ante una deficiencia de manganeso provoca una fumigación que el
 * productor no puede pagar, y la pérdida definitiva de su confianza (§1.2).
 */
@Composable
fun ScanScreen(
    state: ScanUiState,
    onIntent: (ScanIntent) -> Unit,
    cameraPreview: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            // Fondo neutro obligatorio detrás de la imagen del espécimen:
            // cualquier cromaticidad adyacente desplaza el tono percibido de la
            // lesión (§11.1).
            .background(AgroTheme.semantic.specimenBackdrop),
    ) {
        if (state is ScanUiState.Preview) {
            // La vista previa va A SANGRE, sin recortes: la cámara tiene que
            // ocupar la pantalla entera. Sus propios controles ya se apartan de
            // la barra de navegación.
            cameraPreview(Modifier.fillMaxSize())
            return@Box
        }

        // Todo lo demás SÍ respeta los insets.
        //
        // Con `enableEdgeToEdge()` la ventana se dibuja bajo la barra de estado.
        // Sin este padding el título se superpone al reloj y a los iconos de
        // señal — que es exactamente como se veía la primera vez que se ejecutó
        // en el emulador.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
        when (state) {
            // Idle y WarmingUp se dibujan DISTINTO a propósito. Cuando ambos
            // mostraban lo mismo, un fallo en el que nadie despachaba
            // `StartCamera` se veía exactamente igual que un precalentamiento en
            // curso, y la pantalla se quedaba «cargando» para siempre sin que
            // nada lo delatara.
            ScanUiState.Idle -> IdleContent()
            ScanUiState.WarmingUp -> WarmingUpContent()

            // Ya se resolvió arriba, a sangre.
            is ScanUiState.Preview -> Unit

            ScanUiState.Analyzing -> AnalyzingContent()

            is ScanUiState.Identified -> IdentifiedContent(state, onIntent)
            is ScanUiState.NotAPest -> NotAPestContent(state, onIntent)
            is ScanUiState.Uncertain -> UncertainContent(state, onIntent)
            is ScanUiState.OutOfDomain -> OutOfDomainContent(state, onIntent)
            is ScanUiState.QualityRejected -> QualityRejectedContent(state, onIntent)
            is ScanUiState.Error -> ErrorContent(state, onIntent)
        }
        }
    }
}

/** Estado inicial: aún no se pidió el precalentamiento. Si esta pantalla se
 *  queda visible, es que nadie despachó `StartCamera`. */
@Composable
private fun IdleContent() {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.scan_iniciando),
            style = MaterialTheme.typography.bodyMedium,
            color = AgroTheme.semantic.mutedText,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun WarmingUpContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.scan_preparando),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun AnalyzingContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.scan_analizando),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun IdentifiedContent(state: ScanUiState.Identified, onIntent: (ScanIntent) -> Unit) {
    ResultSheet {
        SeverityStrip(
            level = SeverityLevel.MODERADO,
            density = SeverityStripDensity.PROMINENT,
        )
        Text(state.pest.commonName, style = MaterialTheme.typography.headlineMedium)
        Text(
            text = state.pest.scientificName,
            style = MaterialTheme.typography.bodyMedium,
            color = AgroTheme.semantic.mutedText,
        )

        ConfidenceBar(confidence = state.confidence)

        // Honestidad sobre las capacidades del reconocedor activo (ADR-014).
        // Con el modelo heredado no hay compuertas 2 y 3, y callarlo sería
        // presentar como diagnóstico algo que el sistema no puede respaldar.
        if (!state.uncertaintyGatesActive) {
            Text(
                text = stringResource(R.string.scan_aviso_modelo_heredado),
                style = MaterialTheme.typography.bodyMedium,
                color = AgroTheme.semantic.mutedText,
            )
        }

        PrimaryFieldButton(
            text = stringResource(R.string.scan_es_correcto),
            icon = Icons.Filled.Check,
            onClick = { onIntent(ScanIntent.ConfirmPrediction(state.captureId)) },
        )
        SecondaryFieldButton(
            text = stringResource(R.string.scan_no_es_esto),
            icon = Icons.Filled.HelpOutline,
            onClick = { onIntent(ScanIntent.CreateReviewRequest(state.captureId)) },
        )
    }
}

@Composable
private fun NotAPestContent(state: ScanUiState.NotAPest, onIntent: (ScanIntent) -> Unit) {
    ResultSheet {
        SeverityStrip(level = SeverityLevel.SANO, density = SeverityStripDensity.PROMINENT)
        Text(stringResource(R.string.scan_no_es_plaga), style = MaterialTheme.typography.headlineMedium)
        Text(state.label, style = MaterialTheme.typography.bodyLarge)
        ConfidenceBar(confidence = state.confidence)

        PrimaryFieldButton(
            text = stringResource(R.string.scan_entendido),
            onClick = { onIntent(ScanIntent.DismissResult) },
        )
        SecondaryFieldButton(
            text = stringResource(R.string.scan_no_es_esto),
            onClick = { onIntent(ScanIntent.CreateReviewRequest(state.captureId)) },
        )
    }
}

@Composable
private fun UncertainContent(state: ScanUiState.Uncertain, onIntent: (ScanIntent) -> Unit) {
    ResultSheet {
        Text(
            text = UiText.Message(state.reason.message).resolve(),
            style = MaterialTheme.typography.headlineMedium,
        )
        // Nótese que NO se listan los candidatos como si fueran un diagnóstico.
        // El modelo dijo que no está seguro; enseñar «probablemente roya» aquí
        // anularía el propósito de la compuerta.
        Text(
            text = stringResource(R.string.scan_incierto_explicacion),
            style = MaterialTheme.typography.bodyLarge,
        )

        PrimaryFieldButton(
            text = stringResource(R.string.scan_pedir_agronomo),
            icon = Icons.Filled.HelpOutline,
            onClick = { onIntent(ScanIntent.CreateReviewRequest(state.captureId)) },
        )
        SecondaryFieldButton(
            text = stringResource(R.string.scan_tomar_otra),
            icon = Icons.Filled.CameraAlt,
            onClick = { onIntent(ScanIntent.Retry) },
        )
    }
}

@Composable
private fun OutOfDomainContent(state: ScanUiState.OutOfDomain, onIntent: (ScanIntent) -> Unit) {
    ResultSheet {
        Text(
            text = stringResource(
                if (state.modelWasAvailable) R.string.scan_no_reconozco
                else R.string.scan_sin_reconocedor
            ),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            // Sin reconocedor instalado el mensaje es distinto: no es que el
            // modelo mirara y no supiera, es que no hay modelo. Decir lo mismo
            // en los dos casos sería mentir sobre lo que la app hizo.
            text = stringResource(
                if (state.modelWasAvailable) R.string.scan_no_reconozco_explicacion
                else R.string.scan_sin_reconocedor_explicacion
            ),
            style = MaterialTheme.typography.bodyLarge,
        )

        PrimaryFieldButton(
            text = stringResource(R.string.scan_pedir_agronomo),
            icon = Icons.Filled.HelpOutline,
            onClick = { onIntent(ScanIntent.CreateReviewRequest(state.captureId)) },
        )
        SecondaryFieldButton(
            text = stringResource(R.string.scan_tomar_otra),
            icon = Icons.Filled.CameraAlt,
            onClick = { onIntent(ScanIntent.Retry) },
        )
    }
}

@Composable
private fun QualityRejectedContent(
    state: ScanUiState.QualityRejected,
    onIntent: (ScanIntent) -> Unit,
) {
    // Instrucción accionable, no «imagen inválida». El productor necesita saber
    // qué hacer distinto (§7.2, compuerta 1).
    EmptyState(
        title = UiText.Message(state.hint.message).resolve(),
        body = stringResource(R.string.scan_calidad_explicacion),
        icon = Icons.Filled.CameraAlt,
        action = {
            PrimaryFieldButton(
                text = stringResource(R.string.scan_tomar_otra),
                icon = Icons.Filled.CameraAlt,
                onClick = { onIntent(ScanIntent.Retry) },
            )
        },
    )
}

@Composable
private fun ErrorContent(state: ScanUiState.Error, onIntent: (ScanIntent) -> Unit) {
    EmptyState(
        title = state.message.resolve(),
        body = stringResource(R.string.scan_error_explicacion),
        action = if (state.recoverable) {
            {
                PrimaryFieldButton(
                    text = stringResource(R.string.scan_reintentar),
                    icon = Icons.Filled.Refresh,
                    onClick = { onIntent(ScanIntent.Retry) },
                )
            }
        } else {
            null
        },
    )
}

/** Contenedor común de los resultados. Desplazable porque en un teléfono de
 *  pantalla pequeña con fuente ampliada, una ficha no cabe. */
@Composable
private fun ResultSheet(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(AgroTheme.dimensions.gutter),
        verticalArrangement = Arrangement.spacedBy(AgroTheme.dimensions.gutter),
    ) {
        content()
    }
}
