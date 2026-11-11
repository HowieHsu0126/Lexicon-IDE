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

/** Local library actions never reconstruct queries or start retrieval/generation. */
internal class BackgroundKnowledgeViewModel(
    private val repository: BackgroundRepository,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    var search by mutableStateOf(saved.get<String>("search").orEmpty()); private set
    var recentView by mutableStateOf(saved.get<Boolean>("recent") ?: false); private set
    var recent by mutableStateOf<List<BackgroundLookup>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var selectedRecord by mutableStateOf<BackgroundRecord?>(null); private set
    var historyId by mutableStateOf(saved.get<String>("history")); private set
    var editing by mutableStateOf(false); private set
    var deleting by mutableStateOf(false); private set
    var mutating by mutableStateOf(false); private set
    var mutationError by mutableStateOf<String?>(null); private set
    private var historyEpoch = 0
    private var selectionEpoch = 0
    private var historyJob: Job? = null

    fun updateSearch(value: String) { search = value; saved["search"] = value }
    fun showRecent(value: Boolean) {
        recentView = value; saved["recent"] = value
        if (value) loadRecent()
    }
    fun loadRecent() {
        val epoch = ++historyEpoch
        historyJob?.cancel(); loading = true; error = null
        historyJob = viewModelScope.launch {
            try {
                val values = repository.recentLookups().onEach(BackgroundResearch::validateLookup)
                if (epoch == historyEpoch) recent = values
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (epoch == historyEpoch) error = "最近检索暂未读取，已留存背景仍可查看。" }
            finally { if (epoch == historyEpoch) loading = false }
        }
    }
    fun restoreRecord(records: List<BackgroundRecord>) {
        if (selectedRecord == null) saved.get<String>("record")?.let { id -> records.firstOrNull { it.id == id }?.let(::openRecord) }
    }
    fun openRecord(record: BackgroundRecord) {
        runCatching { BackgroundRecords.validate(record) }.onFailure { error = "这条背景记录不可用，原数据已保留。" }.getOrNull() ?: return
        selectionEpoch++; selectedRecord = record; saved["record"] = record.id
        historyId = null; saved.remove<String>("history"); editing = false; deleting = false; mutationError = null
    }
    fun openHistory(id: String) {
        selectionEpoch++; historyId = id; saved["history"] = id
        selectedRecord = null; saved.remove<String>("record"); editing = false; deleting = false; mutationError = null
    }
    fun closeDetail() {
        selectionEpoch++; selectedRecord = null; historyId = null; editing = false; deleting = false; mutationError = null
        saved.remove<String>("record"); saved.remove<String>("history")
    }
    fun edit() { if (!mutating && selectedRecord != null) { editing = true; mutationError = null } }
    fun cancelEdit() { if (!mutating) { editing = false; mutationError = null } }
    fun requestDelete() { if (!mutating && selectedRecord != null) { deleting = true; mutationError = null } }
    fun cancelDelete() { if (!mutating) { deleting = false; mutationError = null } }
    fun reloadSelected(records: List<BackgroundRecord>) {
        selectedRecord?.id?.let { id ->
            records.firstOrNull { it.id == id }?.let(::openRecord) ?: run { closeDetail(); notice = "这条背景已删除" }
        }
    }
    fun update(comment: String, tags: List<String>) {
        val original = selectedRecord ?: return
        if (mutating || !editing) return
        val epoch = selectionEpoch
        mutating = true; mutationError = null
        viewModelScope.launch {
            try {
                val updated = repository.updateRecord(original.id, original.revision, comment, tags)
                BackgroundRecords.validate(updated)
                require(updated.id == original.id && updated.lookup == original.lookup && updated.explanation == original.explanation &&
                    updated.selectedCandidateId == original.selectedCandidateId)
                if (epoch == selectionEpoch) { selectedRecord = updated; editing = false; notice = "背景笔记已更新" }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (epoch == selectionEpoch) mutationError = safeMessage(failure, "保存未完成；记录可能已变化，请重新读取后再编辑。") }
            finally { mutating = false }
        }
    }
    fun confirmDelete() {
        val original = selectedRecord ?: return
        if (mutating || !deleting) return
        val epoch = selectionEpoch
        mutating = true; mutationError = null
        viewModelScope.launch {
            try {
                repository.deleteRecord(original.id, original.revision)
                if (epoch == selectionEpoch) { closeDetail(); notice = "已删除留存记录" }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (epoch == selectionEpoch) mutationError = safeMessage(failure, "删除未完成；请重新读取当前记录后再确认。") }
            finally { mutating = false }
        }
    }
    private fun safeMessage(failure: Exception, fallback: String) =
        (failure as? dev.lexicon.data.ApiFailure)?.message?.take(160) ?: fallback
}

internal fun backgroundRecordMatches(record: BackgroundRecord, search: String): Boolean {
    val needle = search.trim()
    if (needle.isEmpty()) return true
    val lookup = record.lookup
    val selected = lookup.candidates.firstOrNull { it.id == record.selectedCandidateId }
    return backgroundQueryMatches(lookup.query, needle) || selected?.title?.contains(needle, ignoreCase = true) == true ||
        lookup.sources.filter { it.id in selected?.sourceIds.orEmpty() }.any { it.title.contains(needle, ignoreCase = true) || it.provider.contains(needle, ignoreCase = true) } ||
        record.comment.contains(needle, ignoreCase = true) ||
        record.tags.any { it.contains(needle, ignoreCase = true) }
}

internal fun backgroundLookupMatches(lookup: BackgroundLookup, search: String): Boolean {
    val needle = search.trim()
    if (needle.isEmpty()) return true
    return backgroundQueryMatches(lookup.query, needle) ||
        lookup.candidates.any { it.title.contains(needle, ignoreCase = true) } ||
        lookup.sources.any { it.title.contains(needle, ignoreCase = true) || it.provider.contains(needle, ignoreCase = true) }
}

private fun backgroundQueryMatches(query: BackgroundQuery, needle: String) =
    listOfNotNull(query.term, query.context, query.source.title, query.source.source).any { it.contains(needle, ignoreCase = true) }
