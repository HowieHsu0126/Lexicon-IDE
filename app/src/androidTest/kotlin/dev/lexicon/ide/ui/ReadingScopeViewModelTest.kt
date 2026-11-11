package dev.lexicon.ide.ui

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ReadingScopeViewModelTest {
    private lateinit var dispatcher: TestDispatcher
    private lateinit var store: ViewModelStore
    private val article = Article("scope", "scope", listOf("fixture"), "Synthetic reading", "https://example.org/read", "not submitted")
    private val text = (0 until 100).joinToString("\n\n") { "Paragraph $it describes a robust approach to changing conditions. " + "Researchers examine evidence before drawing a conclusion. ".repeat(5) }
    private val body = ArticleBody(article.id, text, fingerprint(text), 1, Long.MAX_VALUE, "Self-authored fixture")
    private fun study(scope: StudyScope, now: Long = 1): ArticleStudy {
        val refs = ReadingSkimPlanner.sentences(body.text).filter { it.paragraphIndex == scope.firstParagraph }
        return ArticleStudy(article.id, body.contentHash, "仅分析选中段落。", "B2", listOf(MindMapNode("root", null, "论点", scope.firstParagraph)),
            emptyList(), (scope.firstParagraph until scope.endParagraphExclusive).map { StudyParagraph(it, "evidence", "核对证据") },
            listOf(ReadingQuestion("range-${scope.firstParagraph}", "What is examined?", "Evidence.", listOf(scope.firstParagraph), listOf(refs.first().evidenceRange()))),
            emptyList(), "synthetic", now, 2, scope)
    }
    @Before fun before() { dispatcher = StandardTestDispatcher(); Dispatchers.setMain(dispatcher); store = ViewModelStore() }
    @After fun after() { store.clear(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }
    private class FakeReading : ReadingRepository by UiTestReadingRepository() {
        var cached = emptyList<ArticleStudy>()
        var read: suspend () -> List<ArticleStudy> = { cached }
        var fullCalls = 0; var rangeCalls = 0
        val retries = mutableListOf<Boolean>()
        var range: suspend (StudyScope) -> ArticleStudy = { error("Unexpected range generation") }
        var upgradedFull: ArticleStudy? = null
        override suspend fun retryStudy(article: Article, body: ArticleBody, aiAllowed: Boolean): ArticleStudy {
            fullCalls++; return requireNotNull(upgradedFull)
        }
        override suspend fun assist(request: StudyRequest) = StudyAssist(request.kind, "Synthetic assessment", "Original scope feedback", "synthetic", 1, "Fixture", score = 0.5)
        override suspend fun cachedStudies(articleId: String, bodyHash: String) = read()
        override suspend fun study(article: Article, body: ArticleBody, aiAllowed: Boolean): ArticleStudy { fullCalls++; error("Unexpected automatic full study") }
        override suspend fun studyRange(article: Article, body: ArticleBody, scope: StudyScope, aiAllowed: Boolean, explicitRetry: Boolean): ArticleStudy {
            rangeCalls++; retries += explicitRetry; return range(scope)
        }
    }
    private fun vm(reading: ReadingRepository, key: Boolean = true) = ReadingViewModel(object : AppRepository by CaptureAnalysisTestRepository() {
        override val reading = reading
        override suspend fun hasApiKey() = key
    }, dispatcher).also { store.put("reading", it) }

    @Test fun longArticlePrepareNeverAttemptsAutomaticFullAnalysis() = runTest(dispatcher) {
        val repo = FakeReading(); val model = vm(repo)
        model.prepare(article, body, true, true); runCurrent()
        assertEquals(0, repo.fullCalls); assertEquals(0, repo.rangeCalls); assertNull(model.study); assertNull(model.error)
        assertFalse(model.loadingStudies)
    }
    @Test fun newestScopeCanRestoreWithoutKeyAndCacheSwitchNeverGenerates() = runTest(dispatcher) {
        val a = study(ReadingStudyScopes.create(body, 81, 83), 1); val b = study(ReadingStudyScopes.create(body, 90, 92), 2)
        val repo = FakeReading().apply { cached = listOf(a, b) }; val model = vm(repo, false)
        model.prepare(article, body, true, true); runCurrent(); assertEquals(b, model.study)
        model.chooseStudy(a); assertEquals(a, model.study); model.chooseStudy(b)
        assertEquals(0, repo.rangeCalls); assertEquals(0, repo.fullCalls)
    }
    @Test fun lateRangeResponseIsCachedButCannotReplaceExplicitlyChosenScope() = runTest(dispatcher) {
        val a = study(ReadingStudyScopes.create(body, 80, 82)); val b = study(ReadingStudyScopes.create(body, 90, 92), 2)
        val gate = CompletableDeferred<ArticleStudy>(); val repo = FakeReading().apply { cached = listOf(a); range = { gate.await() } }
        val model = vm(repo); model.prepare(article, body, false, true); runCurrent()
        model.generateRange(article, body, b.scope!!, true); runCurrent(); model.chooseStudy(a)
        gate.complete(b); runCurrent()
        assertEquals(a, model.study); assertTrue(b in model.availableStudies); assertEquals(1, repo.rangeCalls)
    }
    @Test fun rangeFailureDoesNotReplaceCachedStudyOrPublishAfterCacheSwitch() = runTest(dispatcher) {
        val a = study(ReadingStudyScopes.create(body, 80, 82)); val b = ReadingStudyScopes.create(body, 90, 92)
        val gate = CompletableDeferred<ArticleStudy>(); val repo = FakeReading().apply { cached = listOf(a); range = { gate.await() } }
        val model = vm(repo); model.prepare(article, body, false, true); runCurrent()
        model.generateRange(article, body, b, true); runCurrent(); model.chooseStudy(a)
        gate.completeExceptionally(IllegalArgumentException("late failure")); runCurrent()
        assertEquals(a, model.study); assertNull(model.error); assertNull(model.busy)
    }
    @Test fun duplicateExplicitRangeRequestSharesOneBusyOperation() = runTest(dispatcher) {
        val selected = study(ReadingStudyScopes.create(body, 90, 92)); val gate = CompletableDeferred<ArticleStudy>()
        val repo = FakeReading().apply { range = { gate.await() } }; val model = vm(repo)
        model.prepare(article, body, false, true); runCurrent()
        model.generateRange(article, body, selected.scope!!, true); model.generateRange(article, body, selected.scope!!, true); runCurrent()
        assertEquals(1, repo.rangeCalls); gate.complete(selected); runCurrent()
        assertEquals(selected, model.study); model.generateRange(article, body, selected.scope!!, true); runCurrent(); assertEquals(1, repo.rangeCalls)
    }
    @Test fun scopeFromDifferentBodyCannotBeSelectedFromOutsideTheLoadedCache() = runTest(dispatcher) {
        val selected = study(ReadingStudyScopes.create(body, 90, 92)); val other = selected.copy(bodyHash = "b".repeat(64))
        val repo = FakeReading().apply { cached = listOf(selected, other) }; val model = vm(repo)
        model.prepare(article, body, false, true); runCurrent(); assertEquals(listOf(selected), model.availableStudies)
        model.chooseStudy(other); assertEquals(selected, model.study)
    }
    @Test fun switchingArticlesRejectsOldRangeResultAndError() = runTest(dispatcher) {
        val selected = study(ReadingStudyScopes.create(body, 90, 92)); val gate = CompletableDeferred<ArticleStudy>()
        val repo = FakeReading().apply { range = { gate.await() } }; val model = vm(repo)
        model.prepare(article, body, false, true); runCurrent(); model.generateRange(article, body, selected.scope!!, true); runCurrent()
        val other = article.copy(id = "other"); model.prepare(other, body.copy(articleId = other.id), false, true); runCurrent()
        gate.complete(selected); runCurrent(); assertNull(model.study); assertTrue(model.availableStudies.isEmpty())
    }

    @Test fun cachedVersionsOfTheSameRangeKeepOnlyNewestAndDoNotCallModel() = runTest(dispatcher) {
        val a = study(ReadingStudyScopes.create(body, 80, 82), 1)
        val updated = a.copy(summary = "更新的分析", createdAt = 2)
        val repo = FakeReading().apply { cached = listOf(a, updated) }; val model = vm(repo)
        model.prepare(article, body, false, true); runCurrent()
        assertEquals(listOf(updated), model.availableStudies)
        model.generateRange(article, body, a.scope!!, true); runCurrent()
        assertEquals(updated, model.study); assertEquals(0, repo.rangeCalls)
    }

    @Test fun pressingAnalyzeAfterFailureExplicitlyRetriesAndPreservesPreviousScope() = runTest(dispatcher) {
        val previous = study(ReadingStudyScopes.create(body, 80, 82))
        val selected = study(ReadingStudyScopes.create(body, 90, 92), 2)
        val repo = FakeReading().apply { cached = listOf(previous); range = { throw IllegalArgumentException("暂不可用") } }
        val model = vm(repo); model.prepare(article, body, false, true); runCurrent()
        model.generateRange(article, body, selected.scope!!, true); runCurrent()
        assertEquals(previous, model.study); assertNotNull(model.error)
        repo.range = { selected }
        model.generateRange(article, body, selected.scope!!, true); runCurrent()
        assertEquals(selected, model.study); assertNull(model.error); assertEquals(listOf(true, true), repo.retries)
    }

    @Test fun generatingNewScopeClearsThePreviousScopesAssessmentAndRetryRequest() = runTest(dispatcher) {
        val a = study(ReadingStudyScopes.create(body, 80, 82)); val b = study(ReadingStudyScopes.create(body, 90, 92), 2)
        val gate = CompletableDeferred<ArticleStudy>(); val repo = FakeReading().apply { cached = listOf(a); range = { gate.await() } }
        val model = vm(repo); model.prepare(article, body, false, true); runCurrent()
        model.assist(StudyRequest("check_answer", "An answer", "Earlier scope question")); runCurrent()
        assertNotNull(model.result); assertNotNull(model.resultRequest)
        model.generateRange(article, body, b.scope!!, true); runCurrent()
        assertEquals(a, model.study); assertNotNull(model.result)
        gate.complete(b); runCurrent()
        assertEquals(b, model.study); assertNull(model.result); assertNull(model.resultRequest)
    }

    @Test fun lateInitialCacheFailureCannotReplaceSuccessfulExplicitAnalysis() = runTest(dispatcher) {
        val selected = study(ReadingStudyScopes.create(body, 90, 92))
        val gate = CompletableDeferred<List<ArticleStudy>>()
        val repo = FakeReading().apply { read = { gate.await() }; range = { selected } }
        val model = vm(repo); model.prepare(article, body, true, true); runCurrent()
        model.generateRange(article, body, selected.scope!!, true); runCurrent()
        assertEquals(selected, model.study)
        gate.completeExceptionally(IllegalStateException("late cache failure")); runCurrent()
        assertEquals(selected, model.study); assertNull(model.error); assertFalse(model.loadingStudies)
        assertEquals(0, repo.fullCalls); assertEquals(1, repo.rangeCalls)
    }

    private fun shortBody(): ArticleBody {
        val text = body.text.split("\n\n").take(4).joinToString("\n\n")
        return body.copy(text = text, contentHash = fingerprint(text))
    }
    private fun fullStudy(snapshot: ArticleBody, version: Int): ArticleStudy {
        val evidence = ReadingSkimPlanner.sentences(snapshot.text).first().evidenceRange()
        return ArticleStudy(article.id, snapshot.contentHash, "Whole original fixture", "B2",
            listOf(MindMapNode("root", null, "Evidence", 0)), emptyList(),
            ParagraphFocus.paragraphs(snapshot.text).indices.map { StudyParagraph(it, "evidence", "Test evidence") },
            listOf(ReadingQuestion("q1", "What is examined?", "Evidence", listOf(0), if (version == 1) emptyList() else listOf(evidence))),
            emptyList(), "synthetic", version.toLong(), version)
    }
    @Test fun upgradingLegacyFullStudyKeepsOneFullChoiceAndPreservesPartialChoice() = runTest(dispatcher) {
        val snapshot = shortBody(); val legacy = fullStudy(snapshot, 1); val current = fullStudy(snapshot, 2)
        val partial = study(ReadingStudyScopes.create(snapshot, 1, 3)).copy(bodyHash = snapshot.contentHash)
        val repo = FakeReading().apply { cached = listOf(legacy, partial); upgradedFull = current }; val model = vm(repo)
        model.prepare(article, snapshot, false, true); runCurrent(); assertEquals(legacy, model.study)
        model.generate(article, snapshot, true); runCurrent()
        assertEquals(current, model.study); assertEquals(setOf(current, partial), model.availableStudies.toSet())
        assertEquals(1, model.availableStudies.count { it.scope == null }); assertEquals(1, repo.fullCalls)
        assertEquals(listOf(legacy, partial), repo.cached)
    }
    @Test fun lateLegacyCacheCannotAddADuplicateAfterExplicitFullUpgrade() = runTest(dispatcher) {
        val snapshot = shortBody(); val legacy = fullStudy(snapshot, 1); val current = fullStudy(snapshot, 2)
        val gate = CompletableDeferred<List<ArticleStudy>>()
        val repo = FakeReading().apply { read = { gate.await() }; upgradedFull = current }; val model = vm(repo)
        model.prepare(article, snapshot, false, true); runCurrent(); model.generate(article, snapshot, true); runCurrent()
        gate.complete(listOf(legacy)); runCurrent()
        assertEquals(current, model.study); assertEquals(listOf(current), model.availableStudies)
        assertEquals(1, repo.fullCalls)
    }
}
