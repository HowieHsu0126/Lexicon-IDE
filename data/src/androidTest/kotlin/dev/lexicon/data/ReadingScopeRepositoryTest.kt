package dev.lexicon.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Self-authored text and an in-memory database; no real API, dictionary, or user data. */
@RunWith(AndroidJUnit4::class)
class ReadingScopeRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val codec = Json { encodeDefaults = true }
    private val source = Source("range-fixture", "Self-authored fixture", "https://example.org/range-feed", aiAllowed = true)
    private val article = Article("range-article", "fixture", listOf(source.id), "A neighbourhood archive",
        "https://example.org/range-article", "Unrelated summary", sourceName = source.name)
    private val text = (0 until 120).joinToString("\n\n") { index ->
        "Unique marker P${index.toString().padStart(3, '0')}. " +
            "Residents review historical records and compare the available evidence before deciding how the public reading room should operate. ".repeat(3) +
            "The collection remains open."
    }
    private val body = ArticleBody(article.id, text, sha256(text), 0, Long.MAX_VALUE, "Self-authored fixture")
    private val scopeA get() = ReadingStudyScopes.create(body, 90, 93)
    private val scopeB get() = ReadingStudyScopes.create(body, 94, 97)
    private fun db() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).build()
    private fun key(scope: StudyScope?, snapshot: ArticleBody = body) =
        ReadingStudyScopes.cacheKey(article.id, snapshot.contentHash, ReadingAiProtocol.VERSION, scope)
    private fun result(article: Article, snapshot: ArticleBody, scope: StudyScope?): ArticleStudy {
        val paragraphs = ParagraphFocus.paragraphs(snapshot.text)
        val indices = scope?.let { it.firstParagraph until it.endParagraphExclusive } ?: paragraphs.indices
        val first = indices.first()
        val start = snapshot.text.indexOf("historical records", paragraphs[first].start)
        val evidence = ReadingSkimPlanner.sentences(snapshot.text).first { it.paragraphIndex == first }.evidenceRange()
        val questionId = if (scope == null) "q1" else "sc-${sha256(key(scope, snapshot)).take(16)}-q1"
        return ArticleStudy(article.id, snapshot.contentHash, "The selected archive discussion", "B2",
            listOf(MindMapNode("root", null, "An archive", first)),
            listOf(StudyHighlight("h1", "historical records", start, start + "historical records".length,
                "collocation", "Evidence retained in the archive", "both")),
            indices.map { StudyParagraph(it, "example", "A selected archive paragraph") },
            listOf(ReadingQuestion(questionId, "What does the paragraph identify?", "Its unique marker.", listOf(first), listOf(evidence))),
            emptyList(), "synthetic/no-network", 100, schemaVersion = ReadingAiProtocol.VERSION, scope = scope)
    }
    private inner class FakeAi : ReadingAi {
        var calls = 0
        var nextFailure: Exception? = null
        var afterCall: () -> Unit = {}
        var alter: (ArticleStudy) -> ArticleStudy = { it }
        private fun generated(article: Article, body: ArticleBody, scope: StudyScope?): ArticleStudy {
            calls++
            nextFailure?.let { nextFailure = null; throw it }
            return alter(result(article, body, scope)).also { afterCall() }
        }
        override suspend fun analyze(article: Article, body: ArticleBody) = generated(article, body, null)
        override suspend fun analyzeRange(article: Article, body: ArticleBody, scope: StudyScope) = generated(article, body, scope)
        override suspend fun assist(request: StudyRequest): StudyAssist = error("Unrelated operation")
        override suspend fun review(notes: List<ReadingNote>): StudyAssist = error("Unrelated operation")
    }
    private fun repository(db: LexiconDatabase, ai: FakeAi, hasKey: Boolean = true, allowed: suspend (Article) -> Boolean = { true }) =
        LocalReadingRepository(db, ai, { hasKey }, allowed, clock = { 1000 })
    private suspend fun failure(code: String, block: suspend () -> Unit) {
        try { block(); fail("Expected $code") } catch (failure: ApiFailure) { assertEquals(code, failure.code) }
    }

    @Test fun independentRangesKeepTheirOwnCachesWhenAnotherRangeFails() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val repo = repository(db, ai)
            val first = repo.studyRange(article, body, scopeA, true)
            val original = db.readingDao().entry(key(scopeA))
            ai.nextFailure = ApiFailure("timeout", "Synthetic timeout")
            failure("timeout") { repo.studyRange(article, body, scopeB, true) }
            assertEquals(first, repo.studyRange(article, body, scopeA, true))
            assertEquals(listOf(first), repo.cachedStudies(article.id, body.contentHash))
            assertEquals(original, db.readingDao().entry(key(scopeA))); assertNull(db.readingDao().entry(key(scopeB)))
            failure("retry_required") { repo.studyRange(article, body, scopeB, true) }
            assertEquals(2, ai.calls)
            val second = repo.studyRange(article, body, scopeB, true, explicitRetry = true)
            assertEquals(listOf(first, second), repo.cachedStudies(article.id, body.contentHash))
            assertEquals(original, db.readingDao().entry(key(scopeA))); assertEquals(3, ai.calls)
            assertEquals(2, db.dao().observeJobs().first().size)
        } finally { db.close() }
    }

    @Test fun fullAndSelectedCachesCoexistAndOrderingDoesNotDependOnRequestOrder() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val shortText = ParagraphFocus.paragraphs(text).take(4).joinToString("\n\n") { text.substring(it.start, it.endExclusive) }
            val short = body.copy(text = shortText, contentHash = sha256(shortText))
            val a = ReadingStudyScopes.create(short, 0, 2); val b = ReadingStudyScopes.create(short, 2, 4)
            val repo = repository(db, ai)
            val second = repo.studyRange(article, short, b, true)
            val full = repo.study(article, short, true)
            val first = repo.studyRange(article, short, a, true)
            assertEquals(listOf(full, first, second), repo.cachedStudies(article.id, short.contentHash))
            assertEquals(full, repo.cachedStudy(article.id, short.contentHash))
            assertNotNull(db.readingDao().entry(key(null, short)))
            assertNotNull(db.readingDao().entry(key(a, short))); assertNotNull(db.readingDao().entry(key(b, short)))
            assertEquals(3, ai.calls)
        } finally { db.close() }
    }

    @Test fun sameRangeReusesItsValidatedResultWithoutAKeyAfterRecreation() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val first = repository(db, ai).studyRange(article, body, scopeA, true)
            assertTrue(first.questions.single().evidence.single().start > 24_000)
            val original = db.readingDao().entry(key(scopeA))
            val reopened = repository(db, ai, hasKey = false)
            assertEquals(listOf(first), reopened.cachedStudies(article.id, body.contentHash))
            assertEquals(first, reopened.studyRange(article, body, scopeA, true))
            assertEquals(first, reopened.studyRange(article, body, scopeA, true, explicitRetry = true))
            assertEquals(original, db.readingDao().entry(key(scopeA))); assertEquals(1, ai.calls)
        } finally { db.close() }
    }

    @Test fun aPartialResultCannotBeReadOrGeneratedAsAFullStudy() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val shortText = ParagraphFocus.paragraphs(text).take(4).joinToString("\n\n") { text.substring(it.start, it.endExclusive) }
            val short = body.copy(text = shortText, contentHash = sha256(shortText))
            val scope = ReadingStudyScopes.create(short, 0, 2)
            val partial = result(article, short, scope)
            val misplaced = PreferenceRow(key(null, short), codec.encodeToString(partial))
            db.readingDao().put(misplaced)
            val repo = repository(db, ai)
            assertNull(repo.cachedStudy(article.id, short.contentHash))
            assertTrue(repo.cachedStudies(article.id, short.contentHash).isEmpty())
            failure("retry_required") { repo.study(article, short, true) }
            assertEquals(0, ai.calls); assertEquals(misplaced, db.readingDao().entry(key(null, short)))
            ai.alter = { partial }
            failure("reading_failed") { repo.retryStudy(article, short, true) }
            assertEquals(misplaced, db.readingDao().entry(key(null, short)))
            ai.alter = { it }
            assertNull(repo.retryStudy(article, short, true).scope)
            assertEquals(2, ai.calls)
        } finally { db.close() }
    }

    @Test fun concurrentRequestsForOneRangeProduceOneResultAndOneJob() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val repo = repository(db, ai)
            val results = coroutineScope {
                (1..3).map { async { repo.studyRange(article, body, scopeA, true, explicitRetry = true) } }.awaitAll()
            }
            assertEquals(1, results.distinct().size); assertEquals(1, ai.calls)
            assertEquals(1, db.dao().observeJobs().first().size)
        } finally { db.close() }
    }

    @Test fun interruptionRequiresExplicitRetryForOnlyThatRange() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val repo = repository(db, ai)
            val first = repo.studyRange(article, body, scopeA, true)
            ai.nextFailure = CancellationException("Synthetic interrupted selection")
            try { repo.studyRange(article, body, scopeB, true); fail("Expected cancellation") } catch (_: CancellationException) { }
            val reopened = repository(db, ai)
            assertEquals(listOf(first), reopened.cachedStudies(article.id, body.contentHash))
            failure("retry_required") { reopened.studyRange(article, body, scopeB, true) }
            assertEquals(first, reopened.studyRange(article, body, scopeA, true)); assertEquals(2, ai.calls)
            assertEquals(scopeB, reopened.studyRange(article, body, scopeB, true, explicitRetry = true).scope)
            assertEquals(3, ai.calls)
        } finally { db.close() }
    }

    @Test fun corruptRangeIsPreservedUntilAnExplicitRetryWithoutDamagingOtherRanges() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val repo = repository(db, ai)
            val first = repo.studyRange(article, body, scopeA, true)
            val original = PreferenceRow(key(scopeB), "broken-selected-cache")
            db.readingDao().put(original)
            assertEquals(listOf(first), repo.cachedStudies(article.id, body.contentHash))
            failure("retry_required") { repo.studyRange(article, body, scopeB, true) }
            assertEquals(original, db.readingDao().entry(key(scopeB))); assertEquals(1, ai.calls)
            assertEquals(1, db.dao().observeJobs().first().size)
            val second = repo.studyRange(article, body, scopeB, true, explicitRetry = true)
            assertEquals(listOf(first, second), repo.cachedStudies(article.id, body.contentHash)); assertEquals(2, ai.calls)
        } finally { db.close() }
    }

    @Test fun changingUnselectedTextCreatesANewSnapshotCacheEvenWhenTheSelectionIsUnchanged() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val repo = repository(db, ai)
            val first = repo.studyRange(article, body, scopeA, true)
            val revisedText = text + " Additional unselected evidence."
            val revisedBody = body.copy(text = revisedText, contentHash = sha256(revisedText))
            val revisedScope = ReadingStudyScopes.create(revisedBody, 90, 93)
            assertEquals(scopeA, revisedScope)
            assertNotEquals(key(scopeA), key(revisedScope, revisedBody))
            assertTrue(repo.cachedStudies(article.id, revisedBody.contentHash).isEmpty())
            val second = repo.studyRange(article, revisedBody, revisedScope, true)
            assertNotEquals(first.bodyHash, second.bodyHash); assertEquals(2, ai.calls)
            assertEquals(listOf(first), repo.cachedStudies(article.id, body.contentHash))
            assertEquals(listOf(second), repo.cachedStudies(article.id, revisedBody.contentHash))
        } finally { db.close() }
    }

    @Test fun invalidGeneratedScopeAndGlobalReferencesNeverEnterTheCache() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val repo = repository(db, ai)
            val first = repo.studyRange(article, body, scopeA, true)
            val original = db.readingDao().entry(key(scopeA))
            val edits: List<(ArticleStudy) -> ArticleStudy> = listOf(
                { it.copy(scope = scopeA) },
                { it.copy(bodyHash = "0".repeat(64)) },
                { it.copy(questions = it.questions.map { q -> q.copy(evidence = q.evidence.map { e -> e.copy(start = e.start + 1) }) }) },
                { it.copy(nodes = it.nodes.map { node -> node.copy(paragraph = 0) }) },
                { it.copy(highlights = it.highlights.map { h -> h.copy(start = 0, end = h.quote.length) }) },
            )
            edits.forEach { edit ->
                ai.alter = edit
                failure("reading_failed") { repo.studyRange(article, body, scopeB, true, explicitRetry = true) }
                assertNull(db.readingDao().entry(key(scopeB))); assertEquals(original, db.readingDao().entry(key(scopeA)))
            }
            assertEquals(1 + edits.size, ai.calls)
            assertEquals(listOf(first), repo.cachedStudies(article.id, body.contentHash))
        } finally { db.close() }
    }

    @Test fun permissionRevokedDuringTheRequestKeepsExistingResultsAndDoesNotCacheTheNewOne() = runBlocking {
        val db = db(); val ai = FakeAi(); var allowed = true
        try {
            val repo = repository(db, ai, allowed = { allowed })
            val first = repo.studyRange(article, body, scopeA, true)
            val original = db.readingDao().entry(key(scopeA))
            ai.afterCall = { allowed = false }
            failure("source_restricted") { repo.studyRange(article, body, scopeB, true) }
            assertNull(db.readingDao().entry(key(scopeB))); assertEquals(original, db.readingDao().entry(key(scopeA)))
            assertEquals(listOf(first), repo.cachedStudies(article.id, body.contentHash))
            failure("source_restricted") { repo.studyRange(article, body, scopeA, true) }
            assertEquals(2, ai.calls)
        } finally { db.close() }
    }

    @Test fun enumeratingRangesDoesNotInitializeOrReplayInterruptedJobs() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val stored = result(article, body, scopeA)
            db.readingDao().put(PreferenceRow(key(scopeA), codec.encodeToString(stored)))
            val job = AiJob("reading-ai:${sha256(key(scopeB))}", "reading_study_range", article.id, "running", 1, 2)
            val jobRow = JobRow(job.id, job.status, job.updatedAt, codec.encodeToString(job))
            db.readingDao().putJob(jobRow)
            val reopened = repository(db, ai, hasKey = false)
            assertEquals(listOf(stored), reopened.cachedStudies(article.id, body.contentHash))
            assertEquals(jobRow, db.readingDao().job(job.id)); assertEquals(0, ai.calls)
            failure("retry_required") { reopened.studyRange(article, body, scopeB, true) }
            assertEquals("interrupted", db.readingDao().job(job.id)!!.status); assertEquals(0, ai.calls)
        } finally { db.close() }
    }

    @Test fun overlappingScopesKeepDistinctQuestionProgressForTheSameEvidence() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val repo = repository(db, ai)
            val first = repo.studyRange(article, body, scopeA, true)
            val otherScope = ReadingStudyScopes.create(body, 90, 94)
            val second = repo.studyRange(article, body, otherScope, true)
            val left = first.questions.single(); val right = second.questions.single()
            assertNotEquals(left.id, right.id); assertEquals(left.evidence, right.evidence)
            repo.saveCheck(ComprehensionCheck("check-left", article.id, null, left.id, "A marker", 1.0, "synthetic", 1001))
            repo.saveCheck(ComprehensionCheck("check-right", article.id, null, right.id, "A collection", 0.5, "synthetic", 1002))
            assertEquals(setOf(left.id, right.id), repo.library.first().checks.map { it.questionId }.toSet())
            assertEquals(2, repo.library.first().checks.size)
        } finally { db.close() }
    }

    @Test fun selectedHighlightsBeyondTheFullBudgetPrepareVocabularyFromOriginalGlobalOffsets() = runBlocking {
        val db = db(); val ai = FakeAi(); var generated = 0; var saved = 0
        try {
            db.dao().putSource(SourceRow(source.id, codec.encodeToString(source)))
            db.dao().putArticle(ArticleRow(article.id, article.url, 0, codec.encodeToString(article)))
            db.dao().putBody(BodyRow(article.id, body.expiresAt, codec.encodeToString(body)))
            val study = repository(db, ai).studyRange(article, body, scopeA, true)
            val candidates = VocabularyLearning.candidates(article, body, study, true)
            assertTrue(candidates.single().capture.selectionStart!! > 24_000)
            val queue = LocalVocabularyLearningRepository(db, analyze = { generated++; error("Preparation must stay offline") },
                saveCard = { _, _ -> saved++; error("Preparation must not create cards") }, hasKey = { false }, clock = { 1000 })
            val batch = queue.prepareBatch(article, body, study, candidates.map { it.id })
            assertEquals(candidates.single().capture, batch.items.single().candidate.capture)
            val capture = batch.items.single().candidate.capture
            assertEquals("historical records", body.text.substring(capture.selectionStart!!, capture.selectionEnd!!))
            assertEquals(body.contentHash, capture.bodyHash); assertEquals(ContextOrigin.ORIGINAL, capture.contextOrigin)
            assertEquals(batch, queue.prepareBatch(article, body, study, candidates.map { it.id }))
            assertEquals(1, db.dao().observeCaptures().first().size)
            assertEquals(0, generated); assertEquals(0, saved); assertEquals(1, ai.calls)
            assertNull(db.readingDao().entry(key(null)))
        } finally { db.close() }
    }
}
