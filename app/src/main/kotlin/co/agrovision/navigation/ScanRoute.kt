package co.agrovision.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.agrovision.core.designsystem.component.EmptyState
import co.agrovision.feature.scan.FramingGuidance
import co.agrovision.feature.scan.ScanEffect
import co.agrovision.feature.scan.ScanIntent
import co.agrovision.feature.scan.ScanScreen
import co.agrovision.feature.scan.ScanUiState
import co.agrovision.feature.scan.ScanViewModel
import co.agrovision.feature.scan.camera.CameraCapture

/**
 * Ruta de escaneo: conecta el ViewModel con la pantalla y con la cámara real.
 *
 * Vive en `:app` y no en `:feature:scan` porque es donde se resuelve la
 * navegación. `ScanScreen` recibe la cámara como parámetro justamente para
 * seguir siendo previsualizable y probable con Compose UI Test sin cámara, sin
 * permisos y sin emulador.
 */
@Composable
fun ScanRoute(
    onPestDetail: (String) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: ScanViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Precalentamiento al montar (§7.3). `LaunchedEffect(Unit)`: una vez por
    // entrada a la pantalla, no en cada recomposición.
    LaunchedEffect(Unit) { viewModel.onIntent(ScanIntent.StartCamera) }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ScanEffect.NavigateToPestDetail -> onPestDetail(effect.pestId)
                // ⚠️ Pendiente: la pantalla de creación de solicitud. Hasta que
                // exista, el productor vuelve a la cámara — la captura YA quedó
                // guardada en Room, así que no se pierde nada.
                is ScanEffect.NavigateToReviewRequest -> onBack()
                is ScanEffect.ShowMessage -> Unit
                ScanEffect.RequestCameraPermission -> Unit
            }
        }
    }

    ScanScreen(
        state = state,
        onIntent = viewModel::onIntent,
        cameraPreview = { modifier ->
            CameraCapture(
                guidance = (state as? ScanUiState.Preview)?.guidance ?: FramingGuidance.LISTO,
                onCapture = { image -> viewModel.onIntent(ScanIntent.Capture(image)) },
                modifier = modifier,
            )
        },
    )
}

/** Marcador del historial hasta que exista `:feature:history`. */
@Composable
fun HistoryPlaceholder() {
    EmptyState(
        modifier = Modifier.fillMaxSize(),
        title = "Historial en construcción",
        body = "Aquí verás cada análisis que hagas, con su fecha y su resultado. " +
            "Tus capturas ya se están guardando.",
        icon = Icons.Filled.History,
    )
}
