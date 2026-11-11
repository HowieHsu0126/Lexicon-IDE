package dev.lexicon.ide.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ReadingResumeResolution(
    val key: String,
    val controls: ReadingResumeControls?,
    val paragraphIndex: Int? = null,
    val position: Boolean = true,
    val notice: String? = null,
)

/** Owns one article's writing lease. Viewport/layout observations never perform database work. */
internal class ReadingResumeViewModel(
    private val repository: ReadingResumeRepository,
    private val validationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    var resolution by mutableStateOf<ReadingResumeResolution?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    private var boundKey: String? = null
    private var epoch = 0L
    private var lease: ReadingResumeLease? = null
    private var sourceBody: ArticleBody? = null
    private var paragraphCount = 0
    private var sequence = 0L
    private val beginMutex = Mutex()
    private var debounce: Job? = null
    private var writeTail: Job? = null
    private data class Observation(val paragraphIndex: Int, val controls: ReadingResumeControls, val completed: Boolean)
    private var latest: Observation? = null
    private var submitted: Observation? = null

    fun bind(key: String, body: ArticleBody, kind: ReadingEntryKind, retainedState: Boolean, defaults: ReadingResumeControls) {
        if (boundKey == key) {
            // Returning from a sibling page can recreate Compose content without a new entry.
            if (!retainedState) latest?.let { value -> resolution = ReadingResumeResolution(key, value.controls, value.paragraphIndex) }
            return
        }
        flush()
        val precedingWrites = writeTail
        debounce?.cancel(); boundKey = key; sourceBody = body; lease = null; latest = null; submitted = null
        sequence = 0; paragraphCount = 0; resolution = null; error = null
        val request = ++epoch
        viewModelScope.launch {
            try {
                // A superseded begin must finish before the next one acquires a newer generation.
                val acquired = beginMutex.withLock {
                    precedingWrites?.join()
                    if (request != epoch) return@launch
                    repository.begin(body.articleId, body.contentHash)
                }
                val restored = withContext(validationDispatcher) {
                    ReadingResumes.validateLease(acquired)
                    require(acquired.articleId == body.articleId && acquired.bodyHash == body.contentHash)
                    val previous = acquired.previous?.let { ReadingResumes.restore(it, body) }
                    previous to ParagraphFocus.paragraphs(body.text).size
                }
                if (request != epoch || boundKey != key) return@launch
                lease = acquired; paragraphCount = restored.second
                val previous = restored.first.takeIf { kind == ReadingEntryKind.CONTINUE && !retainedState }
                val fallback = if (kind == ReadingEntryKind.CONTINUE) ReadingResumeControls() else defaults
                val mismatch = kind == ReadingEntryKind.CONTINUE && !retainedState && acquired.previous?.let { !it.completed && restored.first == null } == true
                resolution = ReadingResumeResolution(key, if (retainedState) null else previous?.controls ?: fallback,
                    paragraphIndex = previous?.paragraphIndex, position = !retainedState,
                    notice = if (mismatch) "正文已更新，已从当前正文开始" else null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (request == epoch && boundKey == key) {
                    error = "续读位置暂未读取，当前正文仍可阅读"
                    resolution = ReadingResumeResolution(key, if (retainedState) null else if (kind == ReadingEntryKind.CONTINUE) ReadingResumeControls() else defaults,
                        position = !retainedState)
                }
            }
        }
    }

    /** Null means scrolling, a pending jump, missing layout, or a non-original text. */
    fun observe(key: String, bodyHash: String, paragraphIndex: Int?, controls: ReadingResumeControls, completed: Boolean = false) {
        if (key != boundKey || resolution?.key != key || sourceBody?.contentHash != bodyHash || lease == null) return
        if (paragraphIndex == null) { debounce?.cancel(); return }
        if (paragraphIndex !in 0 until paragraphCount || runCatching { ReadingResumes.validateControls(controls) }.isFailure) return
        val next = Observation(paragraphIndex, controls, completed || latest?.completed == true)
        if (next == latest && debounce?.isActive == true) return
        latest = next; debounce?.cancel()
        if (next == submitted) return
        val request = epoch
        debounce = viewModelScope.launch { delay(600); if (request == epoch && boundKey == key) flush(key) }
    }

    /** Keeps the last valid original observation when a sheet, rewrite, or loading frame intervenes. */
    fun pause(key: String) { if (boundKey == key) debounce?.cancel() }

    fun complete(key: String) {
        if (boundKey != key) return
        latest = latest?.copy(completed = true)
        flush(key)
    }

    fun flush(key: String? = null) {
        if (key != null && boundKey != key) return
        debounce?.cancel()
        val value = latest ?: return
        if (value == submitted) return
        val acquired = lease ?: return
        val body = sourceBody ?: return
        if (sequence == Long.MAX_VALUE) return
        val order = ++sequence; val request = epoch
        submitted = value
        val precedingWrite = writeTail
        writeTail = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // A confirmed exit must finish its bounded local transaction even if the Activity clears its ViewModel.
                val saved = withContext(NonCancellable) {
                    precedingWrite?.join()
                    repository.checkpoint(acquired, order, body, value.paragraphIndex, value.controls, value.completed)
                }
                if (request == epoch && order == sequence) {
                    error = if (saved) null else "续读记录已在其他入口更新，重新打开文章后继续保存"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (request == epoch && order == sequence) { submitted = null; error = "续读位置暂未保存，可继续阅读" }
            }
        }
    }
}

@Composable
internal fun readingResumeViewModel(repository: ReadingResumeRepository?, articleId: String): ReadingResumeViewModel? =
    if (repository == null) null else viewModel(key = "reading-resume:${fingerprint(articleId)}", factory = remember(repository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ReadingResumeViewModel(repository) as T
        }
    })
