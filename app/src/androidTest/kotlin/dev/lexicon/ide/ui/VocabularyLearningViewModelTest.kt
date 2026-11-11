package dev.lexicon.ide.ui

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class VocabularyLearningViewModelTest {
    private lateinit var dispatcher: TestDispatcher
    private lateinit var store: ViewModelStore
    @Before fun setup() { dispatcher = StandardTestDispatcher(); Dispatchers.setMain(dispatcher); store = ViewModelStore() }
    @After fun cleanup() { try { store.clear(); dispatcher.scheduler.runCurrent() } finally { Dispatchers.resetMain() } }
    private fun model(repo: VocabularyUiRepository) = VocabularyLearningViewModel(repo).also { store.put("vocab", it) }

    @Test fun constructionAndLoadingNeverStartTheQueue() = runTest(dispatcher) {
        val repo = VocabularyUiRepository(); val vm = model(repo)
        runCurrent(); assertEquals(0, repo.subscriptions); assertTrue(repo.runs.isEmpty())
        val subscription = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent(); assertFalse(vm.state.value.loading); assertEquals(1, repo.subscriptions)
        repo.values.value = VocabularyLibrary(batches = listOf(VocabularyUiFixture.batch(VocabularyItemStatus.INTERRUPTED)))
        runCurrent(); assertTrue(repo.runs.isEmpty()); assertNull(vm.activeBatchId)
        subscription.cancel(); runCurrent(); assertEquals(0, repo.activeSubscriptions)
    }

    @Test fun failedLoadRetainsPreviousLibraryAndExplicitRetryRecovers() = runTest(dispatcher) {
        val repo = VocabularyUiRepository(); val vm = model(repo)
        val subscription = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        val old = VocabularyLibrary(familiar = listOf(FamiliarTerm("robust", "robust", 1)))
        repo.values.value = old; runCurrent(); repo.failLoad = true; vm.retryLoad(); runCurrent()
        assertEquals(old, vm.state.value.library); assertEquals("词汇记录暂时无法读取，请重试。", vm.state.value.error)
        assertFalse(vm.state.value.error.orEmpty().contains("private"))
        repo.failLoad = false; vm.retryLoad(); runCurrent(); assertNull(vm.state.value.error); assertEquals(old, vm.state.value.library)
        assertTrue(repo.runs.isEmpty()); subscription.cancel()
    }

    @Test fun confirmationPersistsBeforeRunningAndRepeatedClicksStartOnlyOneRun() = runTest(dispatcher) {
        val repo = VocabularyUiRepository(); val gate = CompletableDeferred<Unit>(); repo.runAction = { gate.await() }
        val vm = model(repo); val f = VocabularyUiFixture
        vm.prepareAndRun(f.article, f.body, f.study, f.candidates.map { it.id })
        vm.prepareAndRun(f.article, f.body, f.study, f.candidates.map { it.id }); runCurrent()
        assertEquals(1, repo.prepares); assertEquals(listOf("prepare", "run"), repo.operations)
        assertEquals(repo.values.value.batches.single().id, vm.activeBatchId)
        assertFalse(vm.preparing); assertTrue(vm.busy)
        gate.complete(Unit); runCurrent(); assertFalse(vm.busy)
    }

    @Test fun pauseCancelsOnlyTheActiveRunnerAndDoesNotAutomaticallyResume() = runTest(dispatcher) {
        val repo = VocabularyUiRepository(); repo.runAction = { awaitCancellation() }
        val vm = model(repo); val f = VocabularyUiFixture
        vm.prepareAndRun(f.article, f.body, f.study, listOf(f.candidates.first().id)); runCurrent()
        vm.pause(); runCurrent()
        assertEquals(1, repo.cancellations); assertFalse(vm.busy); assertNull(vm.operationError)
        assertEquals(1, repo.runs.size)
        repo.runAction = {}; vm.resume(repo.values.value.batches.single()); runCurrent(); assertEquals(2, repo.runs.size)
    }

    @Test fun completedAndFamiliarSkippedBatchesCannotBeRestartedFromTheViewModel() = runTest(dispatcher) {
        val repo = VocabularyUiRepository(); val vm = model(repo)
        repo.values.value = VocabularyLibrary(familiar = VocabularyUiFixture.candidates.map { FamiliarTerm(it.termKey, it.capture.target, 1) })
        val subscription = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }; runCurrent()
        vm.resume(VocabularyUiFixture.batch(VocabularyItemStatus.COMPLETED))
        vm.resume(VocabularyUiFixture.batch(VocabularyItemStatus.SKIPPED_FAMILIAR)); runCurrent()
        assertTrue(repo.runs.isEmpty()); assertFalse(vm.busy)
        repo.values.value = VocabularyLibrary(); runCurrent()
        vm.resume(VocabularyUiFixture.batch(VocabularyItemStatus.SKIPPED_FAMILIAR)); runCurrent()
        assertEquals(1, repo.runs.size); subscription.cancel()
    }

    @Test fun familiarityWritesAreExplicitSerializedPerTermAndDoNotTouchQueue() = runTest(dispatcher) {
        val repo = VocabularyUiRepository(); val gate = CompletableDeferred<Unit>(); repo.familiarAction = { gate.await() }
        val vm = model(repo)
        vm.setFamiliar("robust", true); vm.setFamiliar("ROBUST", false); runCurrent()
        assertEquals(listOf("robust" to true), repo.familiarWrites); assertEquals(setOf("robust"), vm.busyTerms)
        gate.complete(Unit); runCurrent(); assertTrue(vm.busyTerms.isEmpty())
        repo.familiarAction = {}; vm.setFamiliar("robust", false); runCurrent()
        assertEquals(listOf("robust" to true, "robust" to false), repo.familiarWrites)
        assertTrue(repo.runs.isEmpty()); assertEquals(0, repo.prepares)
    }

    @Test fun failuresReleaseBusyStateAndNeverExposeRawMessages() = runTest(dispatcher) {
        val repo = VocabularyUiRepository(); repo.runAction = { throw IOException("private response and credential path") }
        val vm = model(repo); vm.resume(VocabularyUiFixture.batch()); runCurrent()
        assertFalse(vm.busy); assertNotNull(vm.operationError); assertFalse(vm.operationError.orEmpty().contains("private"))
        vm.clearError(); assertNull(vm.operationError)
    }
}

internal object VocabularyUiFixture {
    const val text = "A robust method takes into account prior evidence. The robust finding needs context."
    val article = Article("vocabulary-ui", "fixture", listOf("source"), "Reading evidence", "https://example.org/evidence", "", sourceName = "Self-authored")
    val body = ArticleBody(article.id, text, fingerprint(text), 1, Long.MAX_VALUE, "Self-authored")
    val study = ArticleStudy(article.id, body.contentHash, "A synthetic overview", "B2", listOf(MindMapNode("root", null, "Evidence")),
        listOf(StudyHighlight("h1", "robust", 2, 8, "vocabulary", "在此处修饰方法"),
            text.indexOf("takes into account").let { start -> StudyHighlight("h2", "takes into account", start, start + 18, "collocation", "考虑相关证据") },
            StudyHighlight("argument", text, 0, text.length, "argument", "保留完整论点")),
        listOf(StudyParagraph(0, "evidence", "Evidence")), emptyList(), emptyList(), "synthetic", 1)
    val candidates get() = VocabularyLearning.candidates(article, body, study, true)
    fun batch(status: VocabularyItemStatus = VocabularyItemStatus.PENDING) = VocabularyBatch("fixture-batch", article.id, body.contentHash,
        SourceRef(article.title, article.sourceName, article.url), candidates.map { VocabularyQueueItem(it, status) }, 1)
    val content = VocabularyContent("robust", "Reliable in this context.", listOf(Collocation("robust evidence", "The evidence is robust."),
        Collocation("robust method", "A robust method handles variation.")), "reliable", "formal", "high")
}

internal class VocabularyUiRepository : VocabularyLearningRepository {
    val values = MutableStateFlow(VocabularyLibrary())
    var failLoad = false
    var subscriptions = 0
    var activeSubscriptions = 0
    override val library: Flow<VocabularyLibrary> get() = flow {
        subscriptions++; activeSubscriptions++
        try { if (failLoad) throw IOException("private path"); emitAll(values) } finally { activeSubscriptions-- }
    }
    var prepares = 0
    val runs = mutableListOf<String>()
    val operations = mutableListOf<String>()
    val familiarWrites = mutableListOf<Pair<String, Boolean>>()
    var cancellations = 0
    var runAction: suspend () -> Unit = {}
    var familiarAction: suspend () -> Unit = {}
    override suspend fun prepareBatch(article: Article, body: ArticleBody, study: ArticleStudy, candidateIds: List<String>): VocabularyBatch {
        prepares++; operations += "prepare"
        val batch = VocabularyUiFixture.batch().copy(items = VocabularyUiFixture.candidates.filter { it.id in candidateIds }.map { VocabularyQueueItem(it) })
        values.value = values.value.copy(batches = listOf(batch)); return batch
    }
    override suspend fun runBatch(id: String) {
        runs += id; operations += "run"
        try { runAction() } catch (e: CancellationException) { cancellations++; throw e }
    }
    override suspend fun setFamiliar(term: String, familiar: Boolean) {
        familiarWrites += term to familiar; familiarAction()
        val key = VocabularyLearning.termKey(term)
        values.value = values.value.copy(familiar = values.value.familiar.filter { it.key != key } + if (familiar) listOf(FamiliarTerm(key, term, 1)) else emptyList())
    }
}
