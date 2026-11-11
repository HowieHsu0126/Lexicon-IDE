package dev.lexicon.ide.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lexicon.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

internal data class DictionaryLookupUiState(
    val entries: List<DictionaryEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** The UI coordinates repository operations here; it never opens SQL or HTTP clients. */
class LexiconViewModel(private val repository: AppRepository) : ViewModel() {
    val articles = repository.articles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val sources = repository.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val captures = repository.captures.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val captureArchiveFlow: Flow<Set<String>?> = repository.captureInbox?.archivedCaptureIds ?: flowOf(emptySet())
    val archivedCaptureIds = captureArchiveFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val canOrganizeCaptures: Boolean = repository.captureInbox != null
    val cards = repository.cards.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val status = repository.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "[ready] 本地优先")
    val jobs = repository.jobs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    internal var reviewProgress by mutableStateOf<ReviewProgressUiState?>(
        repository.reviewHistory?.let { ReviewProgressUiState(loading = true) }
    ); private set
    private var reviewHistoryJob: Job? = null
    private var reviewHistoryGeneration = 0L

    /** Date boundaries are supplied by the shared UI clock; only committed logs form the summary. */
    internal fun observeReviewDay(range: ReviewDayRange, retry: Boolean = false) {
        val history = repository.reviewHistory ?: return
        if (!retry && reviewProgress?.range == range && reviewHistoryJob?.isActive == true) return
        val generation = ++reviewHistoryGeneration
        val previous = reviewProgress?.takeIf { it.range == range }?.summary
        reviewHistoryJob?.cancel()
        reviewProgress = ReviewProgressUiState(range, previous, loading = true)
        reviewHistoryJob = viewModelScope.launch {
            try {
                history.observeDay(range).collect { summary ->
                    if (reviewHistoryGeneration == generation) reviewProgress = ReviewProgressUiState(range, summary)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (reviewHistoryGeneration == generation) reviewProgress = ReviewProgressUiState(
                    range, reviewProgress?.summary, failed = true,
                )
            }
        }
    }

    internal fun retryReviewHistory() {
        reviewProgress?.range?.let { observeReviewDay(it, retry = true) }
    }

    var busy by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var hasKey by mutableStateOf(false); private set
    var dictionaryStatus by mutableStateOf(DictionaryStatus()); private set
    var dictionaryInitializing by mutableStateOf(true); private set
    var installedDictionaries by mutableStateOf<List<InstalledDictionary>>(emptyList()); private set
    var body by mutableStateOf<ArticleBody?>(null); private set
    var bodyLoading by mutableStateOf(false); private set
    var bodyFailed by mutableStateOf(false); private set
    private var dictionaryLookup by mutableStateOf(DictionaryLookupUiState())
    val dictionary: List<DictionaryEntry> get() = dictionaryLookup.entries
    val lookingUp: Boolean get() = dictionaryLookup.loading
    val dictionaryLookupError: String? get() = dictionaryLookup.error
    private var bodyJob: Job? = null
    private var lookupJob: Job? = null
    private var requestedArticle: String? = null
    private data class LookupRequest(val captureId: String?, val query: String, val dictionaryEpoch: Long, val generation: Long)
    private var lookupRequest by mutableStateOf<LookupRequest?>(null)
    private var lookupGeneration = 0L
    private var dictionaryEpoch = 0L
    private var dictionarySwitchPending = false
    var analysisProbeLoading by mutableStateOf(false); private set
    var analysisProbeError by mutableStateOf<String?>(null); private set
    private var analysisProbeInput by mutableStateOf<Capture?>(null)
    private var analysisProbeResult by mutableStateOf<CaptureAnalysisAvailability>(CaptureAnalysisAvailability.Missing)
    private var analysisProbeJob: Job? = null
    private var analysisProbeVersion = 0L

    private fun input(capture: Capture) = capture.copy(createdAt = 0, updatedAt = 0)
    fun availableAnalysisFor(capture: Capture): CaptureAnalysisAvailability? =
        analysisProbeResult.takeIf { analysisProbeInput == input(capture) && !analysisProbeLoading && analysisProbeError == null }

    /** A cancelled or slow lookup can never publish a result for a different editor snapshot. */
    fun inspectAvailableAnalysis(capture: Capture) {
        val version = ++analysisProbeVersion
        analysisProbeJob?.cancel()
        analysisProbeInput = input(capture)
        analysisProbeResult = CaptureAnalysisAvailability.Missing
        analysisProbeError = null
        analysisProbeLoading = true
        analysisProbeJob = viewModelScope.launch {
            try {
                val result = repository.availableCaptureAnalysis(capture)
                if (analysisProbeVersion == version) analysisProbeResult = result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (analysisProbeVersion == version) analysisProbeError = "本地分析暂不可用，请重试"
            } finally {
                if (analysisProbeVersion == version) analysisProbeLoading = false
            }
        }
    }
    fun closeAnalysisProbe() {
        ++analysisProbeVersion; analysisProbeJob?.cancel(); analysisProbeJob = null
        analysisProbeInput = null; analysisProbeResult = CaptureAnalysisAvailability.Missing
        analysisProbeLoading = false; analysisProbeError = null
    }
    fun saveAvailableAnalysis(capture: Capture, receiptId: String, done: (Card) -> Unit) =
        perform("saving cached analysis", { repository.saveAvailableCaptureAnalysis(capture, receiptId) }) {
            notice = "[done] 生词卡已加入复习队列"; done(it)
        }

    init {
      val initialDictionaryEpoch = dictionaryEpoch
      viewModelScope.launch {
        try {
            repository.initialize(); hasKey = repository.hasApiKey()
            val status = repository.dictionaryStatus()
            val installed = repository.installedDictionaries()
            // A slow initialization must not restore the dictionary selected before an import or switch.
            if (dictionaryEpoch == initialDictionaryEpoch) {
                dictionaryStatus = status; installedDictionaries = installed
                ++dictionaryEpoch; restartDictionaryLookup()
            }
        }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (dictionaryEpoch == initialDictionaryEpoch) notice = "[error] 本地初始化未完成，请重新打开应用。" }
        finally { dictionaryInitializing = false }
      }
    }

    fun clearNotice() { notice = null }
    fun notify(message: String) { notice = message }
    fun <T> perform(label: String, block: suspend () -> T, onSuccess: (T) -> Unit = {}) {
        if (busy != null) return
        busy = label
        viewModelScope.launch {
            try { val result = block(); busy = null; onSuccess(result) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice = when (label) {
                "importing dictionary" -> "[error] 导入失败，请选择未加密的 MDX 或支持的 SQLite 词典。"
                "saving cached analysis", "saving card" -> "[error] 本地保存未完成；请核对原句与来源后重试。"
                "review preference" -> "[error] 复习设置未保存，请重试。"
                "scheduling" -> "[error] 本地评分未保存，请重试。"
                "organizing captures" -> "[error] 归档状态未保存，请重试。"
                "saving" -> "[error] 本地保存未完成，请重试。"
                else -> "[error] 操作未完成。请检查网络、API Key 或来源设置后重试。"
            } }
            finally { busy = null }
        }
    }
    fun sync() = perform("syncing", { repository.sync() }) {
        notice = if (it.failures.isEmpty()) "[done] 已检查 ${it.sourceCount} 个来源 · ${it.articleCount} 篇文章"
        else "[partial] 部分来源失败，请在来源设置中查看并重试。"
    }
    fun openBody(articleId: String) {
        if (body?.articleId == articleId && body!!.expiresAt > System.currentTimeMillis()) {
            viewModelScope.launch { runCatching { repository.markArticleRead(articleId) } }
            return
        }
        requestedArticle = articleId
        bodyJob?.cancel(); body = null; bodyLoading = true; bodyFailed = false
        bodyJob = viewModelScope.launch {
            try {
                val result = repository.openArticle(articleId)
                if (requestedArticle == articleId) { body = result; bodyFailed = result == null }
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { bodyFailed = true }
            finally { if (requestedArticle == articleId) bodyLoading = false }
        }
    }
    fun markArticleRead(articleId: String) { viewModelScope.launch {
        try { repository.markArticleRead(articleId); repository.recordEvent("external_article_opened") }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice = "[error] 阅读标记未保存" }
    } }
    /** A panel reads only the state belonging to its current draft, even before its opening effect runs. */
    internal fun dictionaryLookupFor(captureId: String, text: String): DictionaryLookupUiState? =
        dictionaryLookup.takeIf {
            lookupRequest?.let { request -> request.captureId == captureId && request.query == text.trim() &&
                request.dictionaryEpoch == dictionaryEpoch } == true
        }

    fun lookup(text: String) = requestDictionaryLookup(lookupRequest?.captureId, text)
    internal fun openDictionaryLookup(captureId: String, text: String) = requestDictionaryLookup(captureId, text)
    internal fun editDictionaryLookup(captureId: String, text: String) {
        val current = lookupRequest
        if (current?.captureId == captureId && current.query == text.trim() && current.dictionaryEpoch == dictionaryEpoch) return
        requestDictionaryLookup(captureId, text, debounceMillis = 180)
    }
    internal fun retryDictionaryLookup(captureId: String, text: String) {
        if (dictionaryLookupFor(captureId, text)?.error != null) requestDictionaryLookup(captureId, text)
    }
    internal fun closeDictionaryLookup(captureId: String) {
        if (lookupRequest?.captureId != captureId) return
        ++lookupGeneration; lookupJob?.cancel(); lookupJob = null
        lookupRequest = null; dictionaryLookup = DictionaryLookupUiState()
    }
    private fun restartDictionaryLookup() {
        lookupRequest?.let { requestDictionaryLookup(it.captureId, it.query) }
    }
    private fun requestDictionaryLookup(captureId: String?, text: String, debounceMillis: Long = 0) {
        val request = LookupRequest(captureId, text.trim(), dictionaryEpoch, ++lookupGeneration)
        lookupJob?.cancel()
        lookupJob = null; lookupRequest = request
        // Clear all old output synchronously. Debouncing only delays repository work, never invalidation.
        dictionaryLookup = DictionaryLookupUiState(loading = request.query.isNotEmpty())
        if (request.query.isEmpty() || dictionarySwitchPending) return
        lookupJob = viewModelScope.launch {
            try {
                if (debounceMillis > 0) delay(debounceMillis)
                val result = repository.lookup(request.query)
                if (lookupRequest == request) dictionaryLookup = DictionaryLookupUiState(entries = result)
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (lookupRequest == request) dictionaryLookup = DictionaryLookupUiState(error = "本地查词暂不可用，请重试")
            }
            finally {
                if (lookupRequest == request && dictionaryLookup.loading) dictionaryLookup = dictionaryLookup.copy(loading = false)
            }
        }
    }
    fun saveCapture(capture: Capture, done: () -> Unit = {}) = perform("saving", { repository.saveCapture(capture) }) {
        notice = if (capture.sentence.isBlank()) "[saved] 已保存，补充原句后可制卡" else "[saved] 捕获已保存"
        done()
    }
    fun recordCaptureOpened() { viewModelScope.launch {
        try { repository.recordEvent("capture_opened") }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* A local metric failure must not interrupt a lookup. */ }
    } }
    fun setCaptureArchived(capture: Capture, archived: Boolean) = perform("organizing captures", {
        requireNotNull(repository.captureInbox).setArchived(capture.id, archived)
    }) { saved -> notice = when {
        !saved -> "[info] 捕获已不可用，请返回列表查看"
        archived -> "[done] 已归档，可在已归档中恢复"
        cards.value.any { it.captureId == capture.id } -> "[done] 已恢复，已有生词卡保留"
        else -> "[done] 已恢复到待处理"
    } }
    fun analyze(capture: Capture, deep: Boolean, onResult: (AnalysisResult) -> Unit) =
        perform(if (deep) "analyzing / pro" else "generating / flash", {
            repository.saveCapture(capture)
            repository.recordEvent(if (deep) "deep_analysis_requested" else "card_generation_requested")
            repository.analyzeCapture(capture, deep)
        }, onResult)
    fun saveAnalysis(capture: Capture, analysis: AnalysisResult, done: (Card) -> Unit) =
        perform("saving card", {
            repository.saveCard(capture, analysis)
        }) { notice = "[done] 生词卡已加入复习队列"; done(it) }
    fun generateCard(capture: Capture, done: (Card) -> Unit) = perform("generating / flash", {
        repository.saveCapture(capture)
        repository.recordEvent("card_generation_requested")
        val analysis = repository.analyzeCapture(capture, false)
        repository.saveCard(capture, analysis)
    }) { notice = "[done] 生词卡已加入复习队列"; done(it) }
    fun updateCard(card: Card, done: () -> Unit = {}) = perform("saving", { repository.updateCard(card) }) {
        notice = "[saved] 卡片已更新"; done()
    }
    fun review(card: Card, rating: Rating, done: () -> Unit) = perform("scheduling", {
        repository.reviewCard(card.id, rating, card.state.reps)
    }) { outcome ->
        when (outcome) {
            ReviewOutcome.APPLIED -> {
                if (notice == "[error] 本地评分未保存，请重试。") notice = null
                done()
            }
            ReviewOutcome.PAUSED -> notice = "[info] 此卡片已暂停，未记录评分"
            ReviewOutcome.STALE -> notice = "[info] 评分已更新，请继续下一张"
            ReviewOutcome.MISSING -> notice = "[error] 卡片已不可用，未记录评分"
        }
    }
    fun setReviewPaused(card: Card, paused: Boolean, done: () -> Unit = {}) = perform("review preference", {
        checkNotNull(repository.setCardReviewPaused(card.id, paused)) { "卡片已不可用" }
    }) {
        notice = if (it.reviewPaused) "[saved] 已暂停，可在卡片中恢复" else "[saved] 已恢复原复习计划"
        done()
    }
    fun setSource(id: String, enabled: Boolean) = perform("source", { repository.setSourceEnabled(id, enabled) }) {}
    fun setSettings(value: AppSettings) { viewModelScope.launch {
        try { repository.updateSettings(value) } catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice = "[error] 设置未保存" }
    } }
    fun setKey(key: String) = perform("securing key", { repository.setApiKey(key.trim()); repository.hasApiKey() }) {
        hasKey = it; notice = "[saved] API Key 已加密保存在本机"
    }
    suspend fun importKey(key: String): Boolean { repository.setApiKey(key.trim()); return repository.hasApiKey() }
    fun keyImported(ready: Boolean) { hasKey = ready; notice = if (ready) "[saved] API Key 已加密保存" else "[error] API Key 未保存" }
    fun clearKey() = perform("removing key", { repository.clearApiKey() }) { hasKey = false; notice = "[done] API Key 已移除" }
    suspend fun createAnki(file: File): File = repository.exportAnki(file)
    suspend fun confirmAnkiExport(file: File) = repository.confirmAnkiExport(file)
    suspend fun createEvents(file: File): File = repository.exportEvents(file)
    suspend fun importDictionary(file: File): DictionaryStatus = repository.importDictionary(file)
    fun dictionaryImported(status: DictionaryStatus) {
        ++dictionaryEpoch; dictionaryStatus = status; dictionaryInitializing = false; restartDictionaryLookup()
        notice = if (status.ready) "[done] 词典已导入" else "[error] 词典导入未完成"
        val epoch = dictionaryEpoch
        viewModelScope.launch {
            try {
                val installed = repository.installedDictionaries()
                if (dictionaryEpoch == epoch) installedDictionaries = installed
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice = "[error] 词典列表暂不可用，请重新打开设置。" }
        }
    }
    fun selectDictionary(id: String) {
        if (busy != null || (dictionaryStatus.ready && dictionaryStatus.id == id)) return
        busy = "dictionary"; dictionarySwitchPending = true; ++dictionaryEpoch
        restartDictionaryLookup()
        viewModelScope.launch {
            try {
                dictionaryStatus = repository.selectDictionary(id)
                dictionaryInitializing = false
                installedDictionaries = repository.installedDictionaries()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice = "[error] 词典切换未完成，请重试。" }
            finally {
                dictionarySwitchPending = false; ++dictionaryEpoch; busy = null
                restartDictionaryLookup()
            }
        }
    }
    fun event(type: String, value: Int = 1) { viewModelScope.launch {
        try { repository.recordEvent(type, value) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    } }
}
