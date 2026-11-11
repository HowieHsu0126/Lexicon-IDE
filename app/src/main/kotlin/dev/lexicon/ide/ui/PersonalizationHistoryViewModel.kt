package dev.lexicon.ide.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class PaceHistoryUiState(
    val decisions: List<PersonalizationDecision> = emptyList(),
    val detail: PaceDecisionDetail? = null,
    val selectedId: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

/** Read-only history observes one profile/run snapshot, and resolves only the selected decision. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PersonalizationHistoryViewModel(
    preferences: PersonalizationRepository,
    speed: SpeedReadingRepository,
    private val saved: SavedStateHandle = SavedStateHandle(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val selected = saved.getStateFlow<String?>("pace-history-selection", null)
    private val refresh = MutableStateFlow(0)
    private data class Input(val profile: PersonalizationProfile, val health: PersonalizationHealth,
        val runs: List<SpeedRun>, val selectedId: String?)
    val state: StateFlow<PaceHistoryUiState> = refresh.flatMapLatest {
        combine(preferences.state, preferences.health, speed.library, selected) { profile, health, library, id ->
            Input(profile, health, library.runs, id)
        }.mapLatest { input ->
            withContext(dispatcher) {
                if (input.health.status == PersonalizationHealthStatus.CORRUPT)
                    PaceHistoryUiState(selectedId = input.selectedId, loading = false, error = "偏好档案暂时无法读取；原记录仍保留。")
                else {
                    val decisions = PersonalizationHistory.decisions(input.profile)
                    val chosen = decisions.singleOrNull { it.id == input.selectedId }
                    PaceHistoryUiState(decisions, chosen?.let { PersonalizationHistory.inspect(it, input.runs) }, input.selectedId, false,
                        if (input.selectedId != null && chosen == null) "这条决定暂时不可用，请返回记录列表。" else null)
                }
            }
        }.catch { e ->
            if (e is CancellationException) throw e
            emit(PaceHistoryUiState(selectedId = selected.value, loading = false, error = "调整记录暂时无法读取，请重试。"))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), PaceHistoryUiState())
    fun select(id: String?) { saved["pace-history-selection"] = id }
    fun retry() { refresh.update { it + 1 } }
}
