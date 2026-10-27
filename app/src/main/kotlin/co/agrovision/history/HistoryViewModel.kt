package co.agrovision.history

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.agrovision.core.model.capture.Capture
import co.agrovision.core.model.port.CaptureRepository
import co.agrovision.core.model.scan.GateOutcome
import co.agrovision.core.model.usecase.SearchCatalogUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private val captures: CaptureRepository,
    searchCatalog: SearchCatalogUseCase,
) : ViewModel() {

    /** Filas que el productor acaba de quitar y aún puede deshacer. Se ocultan
     *  al instante; el borrado real espera [UNDO_WINDOW_MS]. */
    private val hiddenIds = MutableStateFlow<Set<String>>(emptySet())
    private val pendingDeletes = mutableMapOf<String, Job>()

    val state: StateFlow<HistoryUiState> = combine(
        captures.observeHistory(),
        searchCatalog(""),
        hiddenIds,
    ) { history, catalog, hidden ->
        val names = catalog.associate { it.id to it.commonName }
        HistoryUiState(
            loading = false,
            items = history
                .filter { it.id !in hidden }
                .sortedByDescending { it.capturedAtEpochMillis }
                .map { it.toItem(names) },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HistoryUiState(),
    )

    /** Oculta la fila y programa el borrado real. [undo] lo cancela. */
    fun delete(id: String) {
        hiddenIds.update { it + id }
        pendingDeletes[id] = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            captures.delete(id)
            pendingDeletes.remove(id)
            hiddenIds.update { it - id }
        }
    }

    fun undo(id: String) {
        pendingDeletes.remove(id)?.cancel()
        hiddenIds.update { it - id }
    }

    private companion object {
        /** Tiene que cubrir el tiempo que el aviso «Deshacer» está en pantalla. */
        const val UNDO_WINDOW_MS = 6_000L
    }
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
