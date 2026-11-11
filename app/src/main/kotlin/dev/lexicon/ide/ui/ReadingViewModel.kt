package dev.lexicon.ide.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lexicon.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId
import java.util.UUID

/** Reading feature orchestration; persistence, policy and transport remain behind the repository. */
class ReadingViewModel(private val app: AppRepository, private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default) : ViewModel() {
    private val repository get() = app.reading
    val backgroundRepository get() = repository?.backgrounds
    val resumeRepository get() = repository?.resumes
    val resumeLibrary = (repository?.resumes?.library ?: flowOf(ReadingResumeLibrary()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingResumeLibrary())
    val library = (repository?.library ?: flowOf(ReadingLibrary())).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingLibrary())
    var edition by mutableStateOf<DailyEdition?>(null); private set
    var study by mutableStateOf<ArticleStudy?>(null); private set
    var availableStudies by mutableStateOf<List<ArticleStudy>>(emptyList()); private set
    var loadingStudies by mutableStateOf(false); private set
    private var studySelectionEpoch = 0L
    private var activeStudyKey: String? = null
    var busy by mutableStateOf<String?>(null); private set
    var noteBusy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var result by mutableStateOf<StudyAssist?>(null); private set
    var currentArticle by mutableStateOf<String?>(null); private set
    private var loadJob: Job? = null
    private val attempted = mutableSetOf<String>()
    private var resultGeneration = 0
    var resultRequest by mutableStateOf<StudyRequest?>(null); private set
    private val assistanceCounts = mutableStateMapOf<String, Int>()
    fun assistanceCount(articleId: String) = assistanceCounts[articleId] ?: 0

    fun refreshEdition(articles: List<Article>, sources: List<Source>, now: Long) {
        viewModelScope.launch {
            try { edition = repository?.dailyEdition(articles, sources, now, ZoneId.systemDefault().id) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "今日阅读暂未准备好，可重试同步" }
        }
    }

    fun prepare(article: Article, body: ArticleBody, automatic: Boolean, allowed: Boolean) {
        val signature = "${article.id}:${body.contentHash}"
        if (currentArticle == signature) return
        val prepareEpoch = ++studySelectionEpoch
        loadJob?.cancel(); study = null; availableStudies = emptyList(); activeStudyKey = null
        error = null; clearResult(); currentArticle = signature; loadingStudies = true
        loadJob = viewModelScope.launch {
            try {
                val cached = withContext(analysisDispatcher) {
                    repository?.cachedStudies(article.id, body.contentHash).orEmpty().filter {
                        it.articleId == article.id && it.bodyHash == body.contentHash &&
                            (it.scope == null || runCatching { ReadingStudyScopes.validateStudy(it, body) }.isSuccess)
                    }
                }
                if (currentArticle != signature) return@launch
                // One visible result per range; older schema caches remain intact in storage.
                availableStudies = (availableStudies + cached).sortedWith(
                    compareByDescending<ArticleStudy> { it.schemaVersion }.thenByDescending { it.createdAt }
                ).distinctBy { it.scope }
                if (studySelectionEpoch == prepareEpoch) {
                    study = availableStudies.firstOrNull { it.scope == null } ?: availableStudies.maxByOrNull { it.createdAt }
                    activeStudyKey = study?.let(::cacheKey)
                }
                if (studySelectionEpoch == prepareEpoch && study == null && automatic && allowed && ReadingStudyScopes.supportsFull(body) && app.hasApiKey() && attempted.add(signature))
                    generate(article, body, allowed, retry = false)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (currentArticle == signature && studySelectionEpoch == prepareEpoch) error = "辅助内容未加载，原文可继续阅读" }
            finally { if (currentArticle == signature) loadingStudies = false }
        }
    }

    /** A cache switch changes assistance only, without model work, body replacement or reader navigation. */
    fun chooseStudy(value: ArticleStudy) {
        if (currentArticle != "${value.articleId}:${value.bodyHash}" || value !in availableStudies) return
        ++studySelectionEpoch; activeStudyKey = cacheKey(value); study = value; clearResult()
    }

    fun generate(article: Article, body: ArticleBody, allowed: Boolean, retry: Boolean = true) =
        generateStudy(article, body, null, allowed, retry)

    fun generateRange(article: Article, body: ArticleBody, scope: StudyScope, allowed: Boolean, retry: Boolean = true) =
        generateStudy(article, body, scope, allowed, retry)

    private fun generateStudy(article: Article, body: ArticleBody, scope: StudyScope?, allowed: Boolean, retry: Boolean) {
        if (busy != null || currentArticle != "${article.id}:${body.contentHash}") return
        val requestedKey = ReadingStudyScopes.cacheKey(article.id, body.contentHash, 2, scope)
        // Explicit full-study refresh must re-read repository state; the repository still reuses
        // valid persisted results before considering a network request.
        if (scope != null || !retry) availableStudies.firstOrNull { cacheKey(it) == requestedKey }?.let { chooseStudy(it); return }
        val epoch = ++studySelectionEpoch
        activeStudyKey = requestedKey
        // Keep previous successful assistance visible while the explicitly requested range loads.
        launch(if (scope == null) "生成阅读辅助" else "分析所选段落", shouldReport = {
            currentArticle == "${article.id}:${body.contentHash}" && studySelectionEpoch == epoch
        }) {
            val generated = if (scope == null) {
                if (retry) requireNotNull(repository).retryStudy(article, body, allowed) else requireNotNull(repository).study(article, body, allowed)
            } else requireNotNull(repository).studyRange(article, body, scope, allowed, retry)
            require(generated.articleId == article.id && generated.bodyHash == body.contentHash && generated.scope == scope)
            if (scope != null) ReadingStudyScopes.validateStudy(generated, body)
            if (currentArticle == "${article.id}:${body.contentHash}") {
                availableStudies = (availableStudies.filterNot { it.scope == generated.scope } + generated).sortedByDescending { it.createdAt }
                if (studySelectionEpoch == epoch && activeStudyKey == requestedKey) {
                    study = generated
                    clearResult()
                }
            }
        }
    }

    private fun cacheKey(value: ArticleStudy) = ReadingStudyScopes.cacheKey(value.articleId, value.bodyHash, value.schemaVersion, value.scope)

    suspend fun cachedAssist(request: StudyRequest) = repository?.cachedAssist(request)

    fun assist(request: StudyRequest, articleId: String? = null, onResult: (StudyAssist) -> Unit = {}) {
        if (busy != null) return
        clearResult()
        val generation = resultGeneration
        launch(if (request.kind == "background") "检索背景" else "分析中") {
            val value = requireNotNull(repository).assist(request)
            if (articleId != null) assistanceCounts[articleId] = assistanceCount(articleId) + 1
            if (generation == resultGeneration) { result = value; resultRequest = request; onResult(value) }
        }
    }

    fun answer(article: Article, question: ReadingQuestion, answer: String, allowed: Boolean) = assist(
        StudyRequest("check_answer", answer.trim(), "Question: ${question.question}\nReference answer: ${question.answer}",
            title = article.title, source = article.sourceName, aiAllowed = allowed), articleId = article.id,
    ) { value ->
        value.score?.let { score -> viewModelScope.launch {
            repository?.saveCheck(ComprehensionCheck(UUID.randomUUID().toString(), article.id, article.topic, question.id,
                answer.trim(), score, "AI · ${value.model}", System.currentTimeMillis()))
        } }
    }

    fun saveNote(note: ReadingNote, onSaved: () -> Unit = {}) = saveNote(note, onSaved, {})

    fun saveNote(note: ReadingNote, onSaved: () -> Unit, onError: (String) -> Unit) {
        if (noteBusy) { onError("笔记正在保存，请稍后重试"); return }
        noteBusy = true
        error = null
        viewModelScope.launch {
            val saved = try { requireNotNull(repository).saveNote(note); true }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                error = "笔记未保存，请重试"
                false
            }
            finally { noteBusy = false }
            if (saved) onSaved() else onError(requireNotNull(error))
        }
    }
    fun deleteNote(id: String) = launch("移除笔记") { requireNotNull(repository).deleteNote(id) }
    fun expressionReview() = launch("整理表达回顾") { result = requireNotNull(repository).expressionReview() }
    fun record(session: ReadingSession) { viewModelScope.launch {
        try { repository?.recordSession(session) } catch (e: CancellationException) { throw e } catch (_: Exception) { error = "阅读记录未保存" }
    } }
    suspend fun export(file: File): File = requireNotNull(repository).exportKnowledge(file)
    fun clearResult() { resultGeneration++; result = null; resultRequest = null; error = null }
    fun clearError() { error = null }
    private fun launch(label: String, shouldReport: () -> Boolean = { true }, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label; error = null
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (shouldReport()) error = when (e) {
                    is dev.lexicon.data.ApiFailure -> e.message
                    is IllegalArgumentException -> e.message?.take(140) ?: "输入不符合要求"
                    else -> "操作未完成，请检查连接后重试"
                }
            } finally { busy = null }
        }
    }
}
