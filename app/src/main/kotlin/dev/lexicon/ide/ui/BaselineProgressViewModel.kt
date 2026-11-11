package dev.lexicon.ide.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lexicon.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

internal data class BaselineProgressUiState(
    val summary: BaselineProgressSummary? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

/** Frozen local records load only while the progress screen has a lifecycle-active collector. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class BaselineProgressViewModel(
    baseline: ReadingBaselineRepository,
    plans: SpeedReadingRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    private var previous: BaselineProgressSummary? = null
    val state = refresh.flatMapLatest {
        baseline.library.transformLatest { attempts ->
            emit(BaselineProgressUiState(previous))
            try {
                val summary = withContext(dispatcher) {
                    val cachedPlans = mutableMapOf<String, SpeedPlan?>()
                    val records = attempts.map { attempt ->
                        if (attempt.planKey !in cachedPlans) cachedPlans[attempt.planKey] = plans.cachedPlan(attempt.planKey)
                        BaselineProgressRecord(attempt, cachedPlans[attempt.planKey], baseline.material(attempt.id))
                    }
                    BaselineProgressCalculator.summarize(records)
                }
                previous = summary
                emit(BaselineProgressUiState(summary, loading = false))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                emit(BaselineProgressUiState(previous, loading = false, error = "自测记录暂时无法读取，请重试。"))
            }
        }.catch { e ->
            if (e is CancellationException) throw e
            emit(BaselineProgressUiState(previous, loading = false, error = "自测记录暂时无法读取，请重试。"))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0), BaselineProgressUiState())

    fun retry() { refresh.update { it + 1 } }
}
