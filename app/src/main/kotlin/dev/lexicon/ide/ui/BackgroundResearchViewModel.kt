package dev.lexicon.ide.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lexicon.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A background query owns its evidence and selected meaning independently of other reading tools. */
internal class BackgroundResearchViewModel(
    private val repository: BackgroundRepository,
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    var input by mutableStateOf(runCatching { saved.get<String>("query")?.let { codec.decodeFromString<BackgroundQuery>(it) } }.getOrNull() ?: BackgroundQuery("")); private set
    var lookup by mutableStateOf<BackgroundLookup?>(null); private set
    var selectedCandidateId by mutableStateOf<String?>(null); private set
    var explanation by mutableStateOf<StudyAssist?>(null); private set
    var loadingCache by mutableStateOf(false); private set
    var loadingExplanation by mutableStateOf(false); private set
    var lookingUp by mutableStateOf(false); private set
    var explaining by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var explanationError by mutableStateOf<String?>(null); private set
    var explanationRetryNeeded by mutableStateOf(false); private set
    var savedRecord by mutableStateOf<BackgroundRecord?>(null); private set
    var loadingRecord by mutableStateOf(false); private set
    var savingRecord by mutableStateOf(false); private set
    var recordError by mutableStateOf<String?>(null); private set
    var recordNotice by mutableStateOf<String?>(null); private set
    private var bound: String? = null
    private var fixedLookupId: String? = null
    private var inputEpoch = 0
    private var retrievalEpoch = 0
    private var selectionEpoch = 0
    private var recordReadEpoch = 0
    private var cacheJob: Job? = null
    private var lookupJob: Job? = null
    private var explanationCacheJob: Job? = null
    private var explanationJob: Job? = null
    private var recordReadJob: Job? = null
    val validInput: Boolean get() = runCatching { BackgroundResearch.normalize(input) }.isSuccess
    val expired: Boolean get() = lookup?.let { BackgroundResearch.expired(it, clock()) } == true

    fun bind(identity: String, initial: BackgroundQuery) {
        if (bound == identity) { retrySavedRecord(); return }
        bound = identity
        if (saved.get<String>("binding") == identity) loadCached()
        else {
            saved["binding"] = identity
            update(initial, force = true)
        }
    }

    /** History reopens one immutable receipt, even if its query index now points to a newer lookup. */
    fun bindLookup(identity: String, lookupId: String) {
        if (bound == identity && fixedLookupId == lookupId) { retrySavedRecord(); return }
        bound = identity; fixedLookupId = lookupId
        if (saved.get<String>("binding") != identity) {
            saved.remove<String>("candidate"); saved.remove<String>("lookup")
        }
        saved["binding"] = identity
        resetForInput()
        loadFixedLookup(lookupId)
    }

    private fun loadFixedLookup(id: String) {
        val epoch = inputEpoch; val read = ++retrievalEpoch
        cacheJob?.cancel(); loadingCache = true; error = null
        cacheJob = viewModelScope.launch {
            try {
                val value = requireNotNull(repository.readLookup(id)) { "Missing lookup" }
                require(value.id == id)
                if (epoch == inputEpoch && read == retrievalEpoch) {
                    input = value.query; saved["query"] = codec.encodeToString(value.query)
                    accept(value, value.query)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (epoch == inputEpoch && read == retrievalEpoch) error = "这份检索资料暂未读取；已留存笔记可在知识库中查看。" }
            finally { if (epoch == inputEpoch && read == retrievalEpoch) loadingCache = false }
        }
    }

    /** Editing or restoring only reads local caches. It never starts retrieval or generation. */
    fun update(value: BackgroundQuery, force: Boolean = false) {
        if (!force && value == input) return
        fixedLookupId = null; input = value; saved["query"] = codec.encodeToString(value)
        saved.remove<String>("candidate"); saved.remove<String>("lookup")
        resetForInput()
        loadCached()
    }

    private fun resetForInput() {
        inputEpoch++; retrievalEpoch++; selectionEpoch++; recordReadEpoch++
        cacheJob?.cancel(); lookupJob?.cancel(); explanationCacheJob?.cancel(); explanationJob?.cancel(); recordReadJob?.cancel()
        lookup = null; selectedCandidateId = null; explanation = null
        loadingCache = false; loadingExplanation = false; lookingUp = false; explaining = false
        error = null; explanationError = null; explanationRetryNeeded = false
        savedRecord = null; loadingRecord = false; recordError = null; recordNotice = null
    }

    private fun loadCached() {
        val query = runCatching { BackgroundResearch.normalize(input) }.getOrNull() ?: return
        val epoch = inputEpoch
        val read = ++retrievalEpoch
        cacheJob?.cancel(); lookupJob?.cancel(); lookingUp = false; loadingCache = true; error = null
        cacheJob = viewModelScope.launch {
            try {
                val cached = repository.cached(query)
                if (epoch == inputEpoch && read == retrievalEpoch && cached != null) accept(cached, query)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (epoch == inputEpoch && read == retrievalEpoch) error = "已有背景暂未读取，可重试读取。" }
            finally { if (epoch == inputEpoch && read == retrievalEpoch) loadingCache = false }
        }
    }

    fun retryCache() { fixedLookupId?.let(::loadFixedLookup) ?: loadCached() }

    /** The only network lookup entry point; refresh is always an explicit user action. */
    fun retrieve(refresh: Boolean = false) {
        if (lookingUp || fixedLookupId != null) return
        val query = runCatching { BackgroundResearch.normalize(input) }.getOrElse { error = "请填写概念，并将上下文控制在 1,200 字符内。"; return }
        cacheJob?.cancel(); loadingCache = false
        val epoch = inputEpoch
        val read = ++retrievalEpoch
        lookingUp = true; error = null
        lookupJob = viewModelScope.launch {
            try {
                val value = repository.lookup(query, refresh)
                if (epoch == inputEpoch && read == retrievalEpoch) accept(value, query)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (epoch == inputEpoch && read == retrievalEpoch) error = safeMessage(failure, "资料暂未检索完成，已有结果保留。") }
            finally { if (epoch == inputEpoch && read == retrievalEpoch) lookingUp = false }
        }
    }

    private fun accept(value: BackgroundLookup, query: BackgroundQuery) {
        BackgroundResearch.validateLookup(value)
        require(BackgroundResearch.queryKey(value.query) == BackgroundResearch.queryKey(query))
        val restored = saved.get<String>("candidate").takeIf { saved.get<String>("lookup") == value.id }
        if (lookup?.id != value.id) {
            selectionEpoch++; explanationJob?.cancel(); explanationCacheJob?.cancel()
            explanation = null; explaining = false; loadingExplanation = false; explanationError = null; explanationRetryNeeded = false
        }
        lookup = value
        savedRecord = null; recordError = null; recordNotice = null
        val selected = restored?.takeIf { id -> value.candidates.any { it.id == id } } ?: value.candidates.singleOrNull()?.id
        selectedCandidateId = null
        if (selected != null) choose(selected)
    }

    fun choose(candidateId: String) = choose(candidateId, reload = false)

    fun retryExplanationCache() { selectedCandidateId?.let { choose(it, reload = true) } }

    private fun choose(candidateId: String, reload: Boolean) {
        val value = lookup ?: return
        if (value.candidates.none { it.id == candidateId } || (!reload && selectedCandidateId == candidateId)) return
        val epoch = ++selectionEpoch
        explanationJob?.cancel(); explanationCacheJob?.cancel()
        selectedCandidateId = candidateId; saved["candidate"] = candidateId; saved["lookup"] = value.id
        explanation = null; explanationError = null; explanationRetryNeeded = false; explaining = false; loadingExplanation = true
        savedRecord = null; recordError = null; recordNotice = null
        explanationCacheJob = viewModelScope.launch {
            try {
                val cached = repository.cachedExplanation(value.id, candidateId)
                if (epoch == selectionEpoch && lookup?.id == value.id) explanation = cached
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (epoch == selectionEpoch) explanationError = "已有解释暂未读取，可重试读取。" }
            finally { if (epoch == selectionEpoch) loadingExplanation = false }
        }
        readSavedRecord(value, candidateId, epoch)
    }

    private fun readSavedRecord(value: BackgroundLookup, candidateId: String, epoch: Int) {
        val read = ++recordReadEpoch
        recordReadJob?.cancel(); loadingRecord = true; recordError = null; recordNotice = null
        recordReadJob = viewModelScope.launch {
            try {
                val record = repository.savedRecord(value.id, candidateId)
                record?.let(BackgroundRecords::validate)
                record?.let { require(it.lookup.id == value.id && it.selectedCandidateId == candidateId) }
                if (epoch == selectionEpoch && read == recordReadEpoch && lookup?.id == value.id && selectedCandidateId == candidateId) savedRecord = record
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (epoch == selectionEpoch && read == recordReadEpoch) recordError = "留存状态暂未读取，可重读后保存。" }
            finally { if (epoch == selectionEpoch && read == recordReadEpoch) loadingRecord = false }
        }
    }

    fun retrySavedRecord() {
        if (savingRecord) return
        val value = lookup ?: return
        selectedCandidateId?.let { recordError = null; readSavedRecord(value, it, selectionEpoch) }
    }

    /** Saves only the selected receipt and, when explicitly shown, its already-cached explanation. */
    fun saveToKnowledge(appendExplanation: Boolean = false) {
        val value = lookup ?: return
        val candidateId = selectedCandidateId ?: return
        if (savingRecord || loadingRecord || loadingExplanation || recordError != null) return
        val prior = savedRecord
        val displayed = explanation
        if (prior != null && !appendExplanation) return
        if (appendExplanation && (prior == null || displayed == null || prior.explanation != null)) return
        val epoch = selectionEpoch
        recordReadEpoch++; recordReadJob?.cancel(); loadingRecord = false; savingRecord = true; recordError = null; recordNotice = null
        viewModelScope.launch {
            try {
                val stored = repository.saveRecord(value.id, candidateId, displayed, if (appendExplanation) prior?.revision else null)
                BackgroundRecords.validate(stored)
                require(stored.lookup.id == value.id && stored.selectedCandidateId == candidateId)
                if (epoch == selectionEpoch && lookup?.id == value.id && selectedCandidateId == candidateId) {
                    savedRecord = stored; recordNotice = if (appendExplanation) "已加入当前解释" else "已留存到知识库"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (epoch == selectionEpoch) recordError = safeMessage(failure, "未能留存，请重读当前记录后重试。") }
            finally { savingRecord = false }
        }
    }

    /** No caller-provided evidence crosses this boundary. The repository resolves the frozen meaning. */
    fun explain(explicitRetry: Boolean = false) {
        val value = lookup ?: return
        val selected = selectedCandidateId ?: return
        if (explaining || loadingExplanation || lookingUp || !input.aiAllowed || expired) return
        val epoch = selectionEpoch
        explaining = true; explanationError = null
        explanationJob = viewModelScope.launch {
            try {
                val result = repository.explain(value.id, selected, explicitRetry)
                if (epoch == selectionEpoch && lookup?.id == value.id && selectedCandidateId == selected) {
                    explanation = result; explanationRetryNeeded = false
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (epoch == selectionEpoch && lookup?.id == value.id) {
                    explanationError = safeMessage(failure, "解释暂未完成，检索资料仍可阅读。")
                    explanationRetryNeeded = true
                }
            } finally { if (epoch == selectionEpoch) explaining = false }
        }
    }

    private fun safeMessage(failure: Exception, fallback: String): String =
        (failure as? dev.lexicon.data.ApiFailure)?.message?.take(160) ?: fallback
}
