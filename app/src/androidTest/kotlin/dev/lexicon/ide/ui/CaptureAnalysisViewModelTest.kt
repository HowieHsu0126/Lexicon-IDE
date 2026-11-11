package dev.lexicon.ide.ui

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CaptureAnalysisViewModelTest {
    private lateinit var dispatcher: TestDispatcher
    private lateinit var store: ViewModelStore
    private val capture = Capture("probe", target = "robust", selectedText = "robust", sentence = "A robust method handles variation.",
        contextOrigin = ContextOrigin.USER_SUPPLIED, createdAt = 1)
    private val content = VocabularyContent("robust", "Remaining effective under changing conditions.",
        listOf(Collocation("robust method", "A robust method remains useful."), Collocation("robust evidence", "The study offers robust evidence.")),
        "reliable", "neutral", "medium")
    private val card get() = Card("card", capture.id, content, capture.sentence, capture.sourceRef, createdAt = 2)
    private val ready get() = CaptureAnalysisAvailability.Ready(AnalysisResult(content, "synthetic"), "receipt")
    @Before fun before() { dispatcher = StandardTestDispatcher(); Dispatchers.setMain(dispatcher); store = ViewModelStore() }
    @After fun after() { store.clear(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }
    private fun model(repository: ProbeRepository) = LexiconViewModel(repository).also { store.put("model", it) }

    @Test fun lateProbeCannotReplaceAnotherInputOrItsReadyResult() = runTest(dispatcher) {
        val first = CompletableDeferred<CaptureAnalysisAvailability>()
        val repo = ProbeRepository().apply { query = { if (it.target == "robust") withContext(NonCancellable) { first.await() } else ready } }
        val vm = model(repo); runCurrent(); vm.inspectAvailableAnalysis(capture); runCurrent()
        val changed = capture.copy(target = "variation")
        assertNull(vm.availableAnalysisFor(changed))
        vm.inspectAvailableAnalysis(changed); runCurrent()
        assertEquals(ready, vm.availableAnalysisFor(changed))
        first.complete(CaptureAnalysisAvailability.Restricted); runCurrent()
        assertEquals(ready, vm.availableAnalysisFor(changed)); assertNull(vm.availableAnalysisFor(capture))
        assertEquals(0, repo.aiCalls); assertEquals(0, repo.captureWrites)
    }
    @Test fun closedProbeDoesNotPublishAfterThePanelHasGone() = runTest(dispatcher) {
        val gate = CompletableDeferred<CaptureAnalysisAvailability>()
        val repo = ProbeRepository().apply { query = { withContext(NonCancellable) { gate.await() } } }
        val vm = model(repo); runCurrent(); vm.inspectAvailableAnalysis(capture); runCurrent()
        vm.closeAnalysisProbe(); gate.complete(ready); runCurrent()
        assertNull(vm.availableAnalysisFor(capture)); assertFalse(vm.analysisProbeLoading); assertNull(vm.analysisProbeError)
    }
    @Test fun probeErrorHasNoGenerationFallbackAndExplicitRetryCanRecover() = runTest(dispatcher) {
        val repo = ProbeRepository().apply { query = { throw IOException("private-diagnostic") } }
        val vm = model(repo); runCurrent(); vm.inspectAvailableAnalysis(capture); runCurrent()
        assertEquals("本地分析暂不可用，请重试", vm.analysisProbeError)
        assertNull(vm.availableAnalysisFor(capture)); assertEquals(0, repo.aiCalls)
        repo.query = { ready }; vm.inspectAvailableAnalysis(capture); runCurrent()
        assertEquals(ready, vm.availableAnalysisFor(capture)); assertNull(vm.analysisProbeError)
    }
    @Test fun localSaveFailureKeepsReadyResultAndRetriesWithoutWritingCapture() = runTest(dispatcher) {
        val repo = ProbeRepository().apply { query = { ready }; save = { _, _ -> throw IOException("local failure") } }
        val vm = model(repo); runCurrent(); vm.inspectAvailableAnalysis(capture); runCurrent()
        var done = 0
        vm.saveAvailableAnalysis(capture, ready.receiptId) { done++ }; runCurrent()
        assertEquals(0, done); assertEquals(ready, vm.availableAnalysisFor(capture)); assertTrue(vm.notice!!.contains("本地保存未完成"))
        repo.save = { input, receipt -> assertEquals(capture, input); assertEquals("receipt", receipt); card }
        vm.saveAvailableAnalysis(capture, ready.receiptId) { done++ }; runCurrent()
        assertEquals(1, done); assertEquals(0, repo.captureWrites); assertEquals(0, repo.aiCalls); assertFalse(vm.hasKey)
    }
    @Test fun rawAnalysisSaveDoesNotOverwriteTheCaptureBeforeAtomicCommit() = runTest(dispatcher) {
        val repo = ProbeRepository().apply { directSave = { _, _ -> card } }
        val vm = model(repo); runCurrent(); var done = false
        vm.saveAnalysis(capture, ready.analysis) { done = true }; runCurrent()
        assertTrue(done); assertEquals(0, repo.captureWrites); assertEquals(0, repo.aiCalls)
    }
    @Test fun onlyAnAppliedReviewInvokesTheCompletionCallback() = runTest(dispatcher) {
        val repo = ProbeRepository(); val vm = model(repo); runCurrent(); var completed = 0
        for (outcome in ReviewOutcome.entries) {
            repo.outcome = outcome; vm.review(card, Rating.GOOD) { completed++ }; runCurrent()
        }
        assertEquals(1, completed)
    }
    @Test fun pauseFailureKeepsTheStoredDecisionAndCanBeRetried() = runTest(dispatcher) {
        val repo = ProbeRepository().apply { pause = { _, _ -> throw IOException("storage") } }
        val vm = model(repo); runCurrent(); var done = 0
        vm.setReviewPaused(card, true) { done++ }; runCurrent(); assertEquals(0, done)
        assertEquals("[error] 复习设置未保存，请重试。", vm.notice)
        repo.pause = { id, paused -> assertEquals(card.id, id); card.copy(reviewPaused = paused) }
        vm.setReviewPaused(card, true) { done++ }; runCurrent(); assertEquals(1, done)
        assertEquals(0, repo.aiCalls)
    }
}

private class ProbeRepository : AppRepository {
    override val articles = MutableStateFlow<List<Article>>(emptyList())
    override val sources = MutableStateFlow<List<Source>>(emptyList())
    override val captures = MutableStateFlow<List<Capture>>(emptyList())
    override val cards = MutableStateFlow<List<Card>>(emptyList())
    override val settings = MutableStateFlow(AppSettings())
    override val status = MutableStateFlow("[ready]")
    override val jobs = MutableStateFlow<List<AiJob>>(emptyList())
    var query: suspend (Capture) -> CaptureAnalysisAvailability = { CaptureAnalysisAvailability.Missing }
    var save: suspend (Capture, String) -> Card = { _, _ -> error("Unexpected write") }
    var directSave: suspend (Capture, AnalysisResult) -> Card = { _, _ -> error("Unexpected write") }
    var pause: suspend (String, Boolean) -> Card? = { _, _ -> null }
    var outcome = ReviewOutcome.APPLIED
    var aiCalls = 0; var captureWrites = 0
    override suspend fun availableCaptureAnalysis(capture: Capture) = query(capture)
    override suspend fun saveAvailableCaptureAnalysis(capture: Capture, expectedAnalysisId: String) = save(capture, expectedAnalysisId)
    override suspend fun initialize() = Unit
    override suspend fun sync() = SyncResult(0, 0, emptyList())
    override suspend fun setSourceEnabled(id: String, enabled: Boolean) = Unit
    override suspend fun markArticleRead(id: String) = Unit
    override suspend fun openArticle(id: String): ArticleBody? = null
    override suspend fun classifyPending() = Unit
    override suspend fun saveCapture(capture: Capture) { captureWrites++ }
    override suspend fun lookup(text: String) = emptyList<DictionaryEntry>()
    override suspend fun updateSettings(settings: AppSettings) { this.settings.value = settings }
    override suspend fun hasApiKey() = false
    override suspend fun setApiKey(key: String) = Unit
    override suspend fun clearApiKey() = Unit
    override suspend fun dictionaryStatus() = DictionaryStatus()
    override suspend fun importDictionary(file: File) = DictionaryStatus()
    override suspend fun analyzeCapture(capture: Capture, deep: Boolean): AnalysisResult { aiCalls++; error("Unexpected AI") }
    override suspend fun saveCard(capture: Capture, analysis: AnalysisResult) = directSave(capture, analysis)
    override suspend fun updateCard(card: Card) = Unit
    override suspend fun setCardReviewPaused(cardId: String, paused: Boolean) = pause(cardId, paused)
    override suspend fun reviewCard(cardId: String, rating: Rating, expectedReps: Int, now: Long) = outcome
    override suspend fun exportAnki(destination: File) = destination
    override suspend fun confirmAnkiExport(exportFile: File) = Unit
    override suspend fun exportEvents(destination: File) = destination
    override suspend fun recordEvent(type: String, value: Int) = Unit
}
