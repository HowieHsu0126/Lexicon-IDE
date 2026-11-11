package dev.lexicon.ide.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lexicon.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal data class VocabularyLearningUiState(
    val library: VocabularyLibrary = VocabularyLibrary(), val loading: Boolean = true, val error: String? = null,
)

/** One explicit queue runner outlives its sheet. Recreating the model never resumes a paid request. */
class VocabularyLearningViewModel(private val repository: VocabularyLearningRepository) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    private var previous = VocabularyLibrary()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal val state = refresh.flatMapLatest {
        flow {
            emit(VocabularyLearningUiState(previous, loading = true))
            repository.library.collect { value ->
                previous = value
                emit(VocabularyLearningUiState(value, loading = false))
            }
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(VocabularyLearningUiState(previous, loading = false, error = "词汇记录暂时无法读取，请重试。"))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), VocabularyLearningUiState())
    var activeBatchId by mutableStateOf<String?>(null); private set
    var preparing by mutableStateOf(false); private set
    var operationError by mutableStateOf<String?>(null); private set
    var busyTerms by mutableStateOf(emptySet<String>()); private set
    private var queueJob: Job? = null
    val busy get() = preparing || activeBatchId != null

    fun retryLoad() { refresh.value++ }
    fun clearError() { operationError = null }

    fun prepareAndRun(article: Article, body: ArticleBody, study: ArticleStudy, ids: List<String>, onPrepared: () -> Unit = {}) {
        if (busy || queueJob?.isActive == true || ids.isEmpty()) return
        preparing = true; operationError = null
        val frozenIds = ids.toList()
        queueJob = viewModelScope.launch {
            try {
                val batch = repository.prepareBatch(article, body, study, frozenIds)
                preparing = false; activeBatchId = batch.id
                onPrepared()
                repository.runBatch(batch.id)
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { operationError = "词汇队列未完成。已保存的项目会保留，可在队列中继续。" }
            finally { preparing = false; activeBatchId = null }
        }
    }

    fun resume(batch: VocabularyBatch) {
        if (busy || queueJob?.isActive == true || batch.items.none { retryableVocabularyItem(it, previous.familiar) }) return
        operationError = null; activeBatchId = batch.id
        queueJob = viewModelScope.launch {
            try { repository.runBatch(batch.id) }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { operationError = "词汇队列未完成。请查看项目状态后重试。" }
            finally { activeBatchId = null }
        }
    }

    fun pause() { queueJob?.cancel() }

    fun setFamiliar(term: String, familiar: Boolean) {
        val key = VocabularyLearning.termKey(term)
        if (key in busyTerms) return
        busyTerms = busyTerms + key; operationError = null
        viewModelScope.launch {
            try { repository.setFamiliar(term, familiar) }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { operationError = "熟悉状态未保存，请重试。" }
            finally { busyTerms = busyTerms - key }
        }
    }
}

internal val retryableVocabularyStatuses = setOf(VocabularyItemStatus.PENDING, VocabularyItemStatus.FAILED,
    VocabularyItemStatus.INTERRUPTED, VocabularyItemStatus.RUNNING)

internal fun retryableVocabularyItem(item: VocabularyQueueItem, familiar: List<FamiliarTerm>): Boolean =
    item.status in retryableVocabularyStatuses || (item.status == VocabularyItemStatus.SKIPPED_FAMILIAR &&
        !VocabularyLearning.isFamiliar(item.candidate.capture.target, familiar))
