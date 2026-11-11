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
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Every analysis is a local fixture; upgrades never invoke a real API or user database. */
@RunWith(AndroidJUnit4::class)
class ReadingQuestionCacheTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val codec = Json { encodeDefaults = true }
    private val article = Article("question-cache", "fixture", listOf("fixture"), "A reading room",
        "https://example.org/question-cache", "Summary outside the learning request", sourceName = "Self-authored fixture")
    private val text = "Residents discuss a reading room. Volunteers compare the available books.\n\nThe committee asks about opening hours. Families describe their needs."
    private val body = ArticleBody(article.id, text, sha256(text), 0, Long.MAX_VALUE, "Self-authored fixture")
    private fun db() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).build()
    private fun key(version: Int) = "reading-cache:study:$version:${sha256(article.id)}:${body.contentHash}"
    private fun result(article: Article = this.article, body: ArticleBody = this.body): ArticleStudy {
        val evidence = ReadingSkimPlanner.sentences(body.text).last().evidenceRange()
        return ArticleStudy(article.id, body.contentHash, "A synthetic overview", "B2", listOf(MindMapNode("root", null, "A reading room")), emptyList(),
            ParagraphFocus.paragraphs(body.text).indices.map { StudyParagraph(it, "example", "Synthetic passage") },
            listOf(ReadingQuestion("q1", "What do families describe?", "Their needs.", listOf(evidence.paragraphIndex), listOf(evidence))),
            emptyList(), "synthetic/no-network", 100, schemaVersion = ReadingAiProtocol.VERSION)
    }
    private inner class FakeAi : ReadingAi {
        var calls = 0
        var cancelNext = false
        var alter: (ArticleStudy) -> ArticleStudy = { it }
        override suspend fun analyze(article: Article, body: ArticleBody): ArticleStudy {
            calls++
            if (cancelNext) { cancelNext = false; throw CancellationException("Synthetic interrupted request") }
            return alter(result(article, body))
        }
        override suspend fun assist(request: StudyRequest): StudyAssist = error("Unrelated operation")
        override suspend fun review(notes: List<ReadingNote>): StudyAssist = error("Unrelated operation")
    }
    private fun repository(db: LexiconDatabase, ai: FakeAi, keyAvailable: Boolean = true, allowed: Boolean = true) =
        LocalReadingRepository(db, ai, { keyAvailable }, { allowed }, clock = { 1000 })
    private suspend fun seedLegacy(db: LexiconDatabase): PreferenceRow {
        val legacy = result().copy(schemaVersion = 1, questions = result().questions.map { it.copy(evidence = emptyList()) })
        val objectValue = codec.parseToJsonElement(codec.encodeToString(legacy)).jsonObject
        val withoutNewFields = JsonObject(objectValue + ("questions" to JsonArray(objectValue.getValue("questions").jsonArray.map {
            JsonObject(it.jsonObject - "evidence")
        })))
        return PreferenceRow(key(1), withoutNewFields.toString()).also { db.readingDao().put(it) }
    }
    private suspend fun failure(code: String, block: suspend () -> Unit) {
        try { block(); fail("Expected $code") } catch (error: ApiFailure) { assertEquals(code, error.code) }
    }

    @Test fun legacyStudyRemainsReadableWithoutKeyOrImplicitUpgrade() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val original = seedLegacy(db)
            val repo = repository(db, ai, keyAvailable = false)
            val cached = requireNotNull(repo.cachedStudy(article.id, body.contentHash))
            assertEquals(1, cached.schemaVersion); assertTrue(cached.questions.single().evidence.isEmpty())
            assertEquals(cached, repo.study(article, body, true))
            assertEquals(cached, repo.study(article, body, true))
            assertEquals(original, db.readingDao().entry(key(1))); assertNull(db.readingDao().entry(key(2)))
            assertEquals(0, ai.calls); assertTrue(db.dao().observeJobs().first().isEmpty())
        } finally { db.close() }
    }

    @Test fun explicitUpdateCreatesV2OnceAndRetainsTheLegacyBytes() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val original = seedLegacy(db)
            val repo = repository(db, ai)
            val updated = repo.retryStudy(article, body, true)
            assertEquals(2, updated.schemaVersion); assertEquals(result().questions, updated.questions)
            assertEquals(original, db.readingDao().entry(key(1)))
            assertNotNull(db.readingDao().entry(key(2)))
            assertEquals(updated, repo.cachedStudy(article.id, body.contentHash))
            assertEquals(updated, repo.study(article, body, true))
            assertEquals(updated, repo.retryStudy(article, body, true))
            assertEquals(updated, repository(db, ai, keyAvailable = false).cachedStudy(article.id, body.contentHash))
            assertEquals(1, ai.calls)
        } finally { db.close() }
    }

    @Test fun concurrentExplicitUpdatesShareOneValidatedResult() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            seedLegacy(db)
            val repo = repository(db, ai)
            val results = coroutineScope { (1..3).map { async { repo.retryStudy(article, body, true) } }.awaitAll() }
            assertEquals(1, results.distinct().size); assertEquals(1, ai.calls)
            assertEquals(1, db.dao().observeJobs().first().size)
        } finally { db.close() }
    }

    @Test fun interruptedUpgradeKeepsLegacyResultsAndRequiresAnotherExplicitRequest() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val original = seedLegacy(db)
            val repo = repository(db, ai)
            ai.cancelNext = true
            try { repo.retryStudy(article, body, true); fail("Expected synthetic cancellation") } catch (_: CancellationException) { }
            assertEquals(1, ai.calls); assertNull(db.readingDao().entry(key(2)))
            val reopened = repository(db, ai)
            assertEquals(1, reopened.study(article, body, true).schemaVersion)
            assertEquals(1, reopened.cachedStudy(article.id, body.contentHash)!!.schemaVersion)
            assertEquals(1, ai.calls); assertEquals(original, db.readingDao().entry(key(1)))
            assertEquals(2, reopened.retryStudy(article, body, true).schemaVersion)
            assertEquals(2, ai.calls)
        } finally { db.close() }
    }

    @Test fun malformedLegacyCacheCannotCauseAnAutomaticChargedUpgrade() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val original = PreferenceRow(key(1), "broken-cache")
            db.readingDao().put(original)
            val repo = repository(db, ai)
            assertNull(repo.cachedStudy(article.id, body.contentHash))
            failure("retry_required") { repo.study(article, body, true) }
            assertEquals(0, ai.calls); assertTrue(db.dao().observeJobs().first().isEmpty())
            assertEquals(2, repo.retryStudy(article, body, true).schemaVersion)
            assertEquals(1, ai.calls); assertEquals(original, db.readingDao().entry(key(1)))
        } finally { db.close() }
    }

    @Test fun oldInterruptedProtocolJobsDoNotBecomeNewAutomaticRequests() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val job = AiJob("reading-ai:${sha256(key(1))}", "reading_study", article.id, "interrupted", 1, 2, errorCode = "cancelled")
            db.readingDao().putJob(JobRow(job.id, job.status, job.updatedAt, codec.encodeToString(job)))
            val repo = repository(db, ai)
            failure("retry_required") { repo.study(article, body, true) }
            assertEquals(0, ai.calls); assertNull(db.readingDao().entry(key(2)))
            assertEquals(2, repo.retryStudy(article, body, true).schemaVersion)
            assertEquals(1, ai.calls)
            assertEquals(job, codec.decodeFromString<AiJob>(db.readingDao().job(job.id)!!.data))
        } finally { db.close() }
    }

    @Test fun invalidGeneratedEvidenceCannotBeCachedOrAutomaticallyReplayed() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            ai.alter = { study -> study.copy(questions = study.questions.map { question -> question.copy(
                evidence = question.evidence.map { it.copy(start = it.start + 1) }) }) }
            val repo = repository(db, ai)
            failure("reading_failed") { repo.study(article, body, true) }
            assertNull(db.readingDao().entry(key(2))); assertEquals(1, ai.calls)
            failure("retry_required") { repo.study(article, body, true) }
            assertEquals(1, ai.calls)
            ai.alter = { it }
            assertEquals(2, repo.retryStudy(article, body, true).schemaVersion)
            assertEquals(2, ai.calls)
        } finally { db.close() }
    }

    @Test fun structurallyDamagedV2FallsBackToLegacyUntilAnExplicitUpdate() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val old = seedLegacy(db)
            val invalid = result().copy(questions = result().questions.map { it.copy(evidence = emptyList()) })
            db.readingDao().put(PreferenceRow(key(2), codec.encodeToString(invalid)))
            val repo = repository(db, ai)
            assertEquals(1, repo.cachedStudy(article.id, body.contentHash)!!.schemaVersion)
            assertEquals(1, repo.study(article, body, true).schemaVersion); assertEquals(0, ai.calls)
            assertEquals(2, repo.retryStudy(article, body, true).schemaVersion)
            assertEquals(1, ai.calls); assertEquals(old, db.readingDao().entry(key(1)))
        } finally { db.close() }
    }

    @Test fun missingKeyAndRestrictedSourceLeaveLegacyCacheAvailableWithoutModelCalls() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val old = seedLegacy(db)
            val withoutKey = repository(db, ai, keyAvailable = false)
            failure("missing_key") { withoutKey.retryStudy(article, body, true) }
            failure("source_restricted") { repository(db, ai, allowed = false).retryStudy(article, body, true) }
            assertEquals(0, ai.calls); assertEquals(old, db.readingDao().entry(key(1)))
            assertEquals(1, withoutKey.cachedStudy(article.id, body.contentHash)!!.schemaVersion)
        } finally { db.close() }
    }
}
