package co.agrovision.history

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.agrovision.core.model.capture.Capture
import co.agrovision.core.model.port.CaptureRepository
import co.agrovision.core.model.scan.GateOutcome
import co.agrovision.core.model.usecase.SearchCatalogUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@Immutable
data class HistoryItem(
    val id: String,
    val thumbnailPath: String,
    val capturedAtEpochMillis: Long,
    /** Ficha a la que apunta el análisis, si hubo una. */
    val pestId: String?,
    val pestName: String?,
    /** 0..100, solo cuando el análisis identificó algo. */
    val confidencePercent: Int?,
    val outcome: GateOutcome,
)

@Immutable
data class HistoryUiState(
    val loading: Boolean = true,
    val items: List<HistoryItem> = emptyList(),
)

/**
 * Historial de análisis: lo que salió de Room, sin red. El nombre de la plaga se
 * resuelve contra el catálogo local; si la ficha ya no existe, la fila se
 * muestra igual como «sin identificar» en vez de desaparecer.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    captures: CaptureRepository,
    searchCatalog: SearchCatalogUseCase,
) : ViewModel() {

    val state: StateFlow<HistoryUiState> = combine(
        captures.observeHistory(),
        searchCatalog(""),
    ) { history, catalog ->
        val names = catalog.associate { it.id to it.commonName }
        HistoryUiState(
            loading = false,
            items = history
                .sortedByDescending { it.capturedAtEpochMillis }
                .map { it.toItem(names) },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HistoryUiState(),
    )
}

private fun Capture.toItem(names: Map<String, String>): HistoryItem {
    // La corrección del productor manda sobre lo que dijo el modelo.
    val pestId = (userCorrectionClassId ?: predictedClassId)?.takeIf { it in names }
    return HistoryItem(
        id = id,
        thumbnailPath = thumbnailPath,
        capturedAtEpochMillis = capturedAtEpochMillis,
        pestId = pestId,
        pestName = pestId?.let(names::get),
        confidencePercent = calibratedConfidence
            ?.takeIf { gateOutcome == GateOutcome.IDENTIFIED && userCorrectionClassId == null }
            ?.let { (it * 100).toInt().coerceIn(0, 100) },
        outcome = gateOutcome,
    )
}
