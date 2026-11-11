package dev.lexicon.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReadingRepositoryIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = Instant.parse("2026-10-29T12:00:00Z").toEpochMilli()
    private val enabledSources = listOf("theconversation.com", "economist.com", "bbc.com", "ft.com").map { host -> Source(host, "Synthetic $host", "https://$host/feed", enabled = true) }
    private val json = Json { encodeDefaults = true }
    private fun db() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
    private fun article(id: String = "a", host: String = "theconversation.com") = Article(id, id, listOf(host), "Synthetic article", "https://$host/$id", "Summary", publishedAt = now - 1000)
    private fun body(text: String = "A complete synthetic sentence.") = ArticleBody("a", text, sha256(text), now, now + 60000, "test")
    private fun createRepo(db: LexiconDatabase, ai: FakeAi = FakeAi(), key: Boolean = true, allowed: Boolean = true) = LocalReadingRepository(db, ai, { key }, { allowed }, { now })

    @Test fun dailyEditionIsAtomicStableAndRestoredAcrossRepositoryInstances() = runBlocking {
        val db = db()
        try {
            val first = createRepo(db)
            assertTrue(first.dailyEdition(emptyList(), enabledSources, now, "Asia/Shanghai").articleIds.isEmpty())
            val edition = first.dailyEdition(listOf(article()), enabledSources, now, "Asia/Shanghai")
            assertEquals(listOf("a"), edition.articleIds)
            val second = createRepo(db)
            val full = second.dailyEdition(listOf(article("b", "economist.com"), article("b2", "economist.com"), article("c", "bbc.com")), enabledSources, now, "Asia/Shanghai")
            assertEquals(3, full.articleIds.size); assertEquals("a", full.articleIds.first())
            assertEquals(3, full.publisherKeys.distinct().size)
            val again = first.dailyEdition(listOf(article("new", "ft.com")), enabledSources, now, "Asia/Shanghai")
            assertEquals(full, again)
            assertEquals(1, second.library.first().editions.size)
        } finally { db.close() }
    }

    @Test fun studyAndAssistCachesAreContentAddressedAndPermissionGuarded() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val repo = createRepo(db, ai)
            repo.study(article(), body(), true); repo.study(article(), body(), true)
            assertEquals(1, ai.analyses)
            repo.study(article(), body("A different full sentence."), true); assertEquals(2, ai.analyses)
            val request = StudyRequest("translation", "A sentence.")
            repo.assist(request); repo.assist(request); assertEquals(1, ai.assists)
            repo.assist(request.copy(context = "Context")); assertEquals(2, ai.assists)
            try { repo.study(article(), body(), false); fail("Expected restricted source") } catch (e: ApiFailure) { assertEquals("source_restricted", e.code) }
            try { repo.assist(request.copy(aiAllowed = false)); fail("Expected restricted source") } catch (e: ApiFailure) { assertEquals("source_restricted", e.code) }
            assertNotNull(createRepo(db, ai, key = false).cachedStudy("a", body().contentHash))
            assertEquals(2, ai.analyses)
        } finally { db.close() }
    }

    @Test fun missingKeyAndInterruptedJobsNeverReplayAutomatically() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val orphan = AiJob("reading-ai:orphan", "reading_study", "a", "running", now - 100, now - 100)
            db.readingDao().putJob(JobRow(orphan.id, orphan.status, orphan.updatedAt, json.encodeToString(orphan)))
            val repo = createRepo(db, ai, key = false)
            repo.initialize()
            assertEquals("interrupted", db.readingDao().job(orphan.id)!!.status)
            try { repo.assist(StudyRequest("translation", "Text.")); fail("Expected missing key") } catch (e: ApiFailure) { assertEquals("missing_key", e.code) }
            assertEquals(0, ai.assists)
            val job = db.dao().observeJobs().first().first { it.id != orphan.id }
            assertEquals("failed", job.status)
        } finally { db.close() }
    }

    @Test fun automaticStudyDoesNotReplayInterruptedRequestButExplicitRetryCan() = runBlocking {
        val db = db(); val ai = FakeAi().apply { cancelAnalysis = true }
        try {
            val first = createRepo(db, ai)
            try { first.study(article(), body(), true); fail("Expected interrupted request") } catch (_: CancellationException) { }
            assertEquals(1, ai.analyses)
            val reopened = createRepo(db, ai)
            try { reopened.study(article(), body(), true); fail("Automatic replay must be blocked") } catch (e: ApiFailure) { assertEquals("retry_required", e.code) }
            assertEquals(1, ai.analyses)
            ai.cancelAnalysis = false
            reopened.retryStudy(article(), body(), true)
            assertEquals(2, ai.analyses)
            reopened.study(article(), body(), true)
            assertEquals(2, ai.analyses)
        } finally { db.close() }
    }

    @Test fun sessionsChecksAndExpressionReviewsAreIdempotentAndPersisted() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val repo = createRepo(db, ai)
            val note = ReadingNote("n", null, null, "Original synthetic quote.", "My comment", listOf("Science"), now)
            repo.saveNote(note)
            val session = ReadingSession("s", "a", StudyMode.SKIM, "Science", now - 60000, now, 60000, 100, 1, 1, 0, true)
            val check = ComprehensionCheck("c", "a", "Science", "q", "Answer", .5, "test-ai", now)
            coroutineScope { (1..4).map { async { repo.recordSession(session); repo.saveCheck(check) } }.awaitAll() }
            repo.expressionReview(); repo.expressionReview()
            assertEquals(1, ai.reviews)
            val library = createRepo(db).library.first()
            assertEquals(1, library.sessions.size); assertEquals(1, library.checks.size); assertEquals(1, library.reviews.size)
            assertEquals(2, db.dao().allEvents().size)
            repo.saveNote(note.copy(comment = "Revised comment")); repo.expressionReview(); assertEquals(2, ai.reviews)
            try { repo.saveCheck(check.copy(id = "bad", score = Double.NaN)); fail("Expected invalid score") } catch (_: IllegalArgumentException) { }
            val output = File(context.cacheDir, "knowledge-test-${UUID.randomUUID()}.md")
            try { repo.exportKnowledge(output); assertTrue(output.readText().contains("Revised comment")); assertTrue(output.readText().contains("AI 表达整理")) } finally { output.delete() }
            repo.deleteNote("n"); assertTrue(repo.library.first().notes.isEmpty())
        } finally { db.close() }
    }

    @Test fun exactNotesRequireVerifiedSnapshotAndExpiredNotesOnlyKeepExistingQuote() = runBlocking {
        val db = db()
        try {
            val repo = createRepo(db); val body = body()
            db.dao().putBody(BodyRow(body.articleId, body.expiresAt, json.encodeToString(body)))
            val note = ReadingNote("exact", body.articleId, body.contentHash, body.text, "Comment", emptyList(), now, start = 0, end = body.text.length)
            try { repo.saveNote(note.copy(bodyHash = "different")); fail("Hash mismatch must fail") } catch (_: IllegalArgumentException) { }
            repo.saveNote(note)
            db.dao().expireBodies(Long.MAX_VALUE)
            repo.saveNote(note.copy(comment = "Updated comment"))
            assertEquals("Updated comment", repo.library.first().notes.single().comment)
            try { repo.saveNote(note.copy(id = "new")); fail("New exact quote requires snapshot") } catch (_: IllegalArgumentException) { }
            try { repo.saveNote(note.copy(quote = "X".repeat(note.quote.length))); fail("Cannot replace expired quote without proof") } catch (_: IllegalArgumentException) { }
            assertEquals(body.text, repo.library.first().notes.single().quote)
        } finally { db.close() }
    }

    @Test fun fullyRestrictedNotesDoNotReachAiAndLargeCollectionsUseBoundedCoverage() = runBlocking {
        val db = db(); val ai = FakeAi()
        try {
            val article = article()
            db.dao().putArticle(ArticleRow(article.id, article.url, now, json.encodeToString(article)))
            val repo = createRepo(db, ai, allowed = false)
            repo.saveNote(ReadingNote("restricted", article.id, null, "Quote", "Comment", emptyList(), now))
            try { repo.expressionReview(); fail("Restricted notes must fail") } catch (e: ApiFailure) { assertEquals("source_restricted", e.code) }
            assertEquals(0, ai.reviews)
            repo.deleteNote("restricted")
            repeat(31) { repo.saveNote(ReadingNote("n$it", null, null, "Quote $it", "Comment", emptyList(), now)) }
            val review = repo.expressionReview()
            assertTrue(review.evidence.contains("入选 30 条"))
            assertTrue(review.evidence.contains("数量/输入上限 1 条"))
            assertEquals(31, repo.library.first().notes.size); assertEquals(1, ai.reviews)
        } finally { db.close() }
    }

    @Test fun quotaFailurePausesFurtherWorkAndPreservesMaterial() = runBlocking {
        val db = db(); val ai = FakeAi().apply { assistFailure = "quota" }
        try {
            val repo = createRepo(db, ai)
            repo.saveNote(ReadingNote("n", null, null, "Quote", "Comment", emptyList(), now))
            try { repo.assist(StudyRequest("translation", "First sentence.")); fail("Expected quota failure") } catch (e: ApiFailure) { assertEquals("quota", e.code) }
            assertEquals("true", db.readingDao().entry("ai_paused")!!.data)
            assertEquals(1, ai.assists)
            try { repo.assist(StudyRequest("translation", "Different sentence.")); fail("Expected pause") } catch (e: ApiFailure) { assertEquals("paused", e.code) }
            assertEquals(1, ai.assists); assertEquals(1, repo.library.first().notes.size)
            db.readingDao().put(PreferenceRow("ai_paused", "false")); ai.assistFailure = null
            repo.assist(StudyRequest("translation", "First sentence.")); assertEquals(2, ai.assists)
        } finally { db.close() }
    }

    @Test fun partialSessionCanBecomeCompleteAndStaleUpdatesNeverUndoProgress() = runBlocking {
        val db = db()
        try {
            val repo = createRepo(db)
            val partial = ReadingSession("merged", "a", StudyMode.INTENSIVE, "Science", now - 60000, now - 30000, 20000, 40, 1, 4, 0, false)
            val completed = partial.copy(finishedAt = now, activeMillis = 55000, wordsRead = 120, paragraphsRead = 4, assistanceCount = 2, completed = true)
            repo.recordSession(partial); repo.recordSession(partial)
            repo.recordSession(completed); repo.recordSession(completed)
            repo.recordSession(partial)
            repo.recordSession(completed.copy(finishedAt = now + 1000, activeMillis = 56000, wordsRead = 125, completed = false))
            val library = repo.library.first()
            val stored = library.sessions.single()
            assertTrue(stored.completed); assertEquals(56000L, stored.activeMillis); assertEquals(125, stored.wordsRead)
            assertEquals(4, stored.paragraphsRead); assertEquals(2, stored.assistanceCount)
            val event = db.dao().allEvents().single()
            assertEquals("reading_completed", json.decodeFromString<LocalEvent>(event.data).type)
            assertEquals(now, event.createdAt)
            assertEquals(10, ReadingProgressCalculator.summarize(library).growthPoints)
        } finally { db.close() }
    }

    @Test fun aiNotesPreserveOriginAndCannotClaimOriginalOffsets() = runBlocking {
        val db = db()
        try {
            val repo = createRepo(db)
            val note = ReadingNote("ai-note", "a", null, "AI rewritten sentence.", "Comment", emptyList(), now, origin = ContextOrigin.AI_GENERATED)
            repo.saveNote(note)
            assertEquals(ContextOrigin.AI_GENERATED, repo.library.first().notes.single().origin)
            try { repo.saveNote(note.copy(start = 0, end = note.quote.length)); fail("AI text must not claim original offsets") } catch (_: IllegalArgumentException) { }
            try { repo.saveNote(note.copy(origin = ContextOrigin.ORIGINAL)); fail("Existing origin must be retained") } catch (_: IllegalArgumentException) { }
            assertEquals(ContextOrigin.AI_GENERATED, repo.library.first().notes.single().origin)
        } finally { db.close() }
    }

    @Test fun existingVersionOneDatabaseKeepsOriginalDataAndAcceptsReadingRows() = runBlocking {
        val file = File(context.cacheDir, "old-lexicon-${UUID.randomUUID()}.sqlite")
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("lexicon-room-v1.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject.getValue("database").jsonObject }
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            for (element in schema.getValue("entities").jsonArray) {
                val entity = element.jsonObject; val table = entity.getValue("tableName").jsonPrimitive.content
                fun sql(value: String) = value.replace("\${TABLE_NAME}", table)
                connection.exec(sql(entity.getValue("createSql").jsonPrimitive.content))
                entity["indices"]?.jsonArray?.forEach { connection.exec(sql(it.jsonObject.getValue("createSql").jsonPrimitive.content)) }
            }
            connection.exec("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            connection.prepare("INSERT INTO room_master_table VALUES (42,?)").use { it.bindText(1, schema.getValue("identityHash").jsonPrimitive.content); it.step() }
            connection.exec("PRAGMA user_version=1")
            connection.exec("INSERT INTO preferences VALUES ('legacy-marker','preserved')")
            connection.exec("INSERT INTO sources VALUES ('legacy-source','{\"original\":true}')")
            connection.exec("INSERT INTO cards VALUES ('legacy-card','legacy-capture',99,2,100,'{\"original\":true}')")
        }
        val db = Room.databaseBuilder<LexiconDatabase>(context, file.absolutePath).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
        try {
            assertEquals("preserved", db.dao().preference("legacy-marker")!!.data)
            assertEquals(2, db.dao().card("legacy-card")!!.reps)
            assertNotNull(db.dao().source("legacy-source"))
            createRepo(db).saveNote(ReadingNote("n", null, null, "Quote", "Comment", emptyList(), now))
            assertEquals(1, createRepo(db).library.first().notes.size)
            assertEquals("preserved", db.dao().preference("legacy-marker")!!.data)
            assertEquals(2, db.dao().card("legacy-card")!!.reps)
        } finally { db.close(); file.delete(); File(file.path + "-wal").delete(); File(file.path + "-shm").delete() }
    }

    private class FakeAi : ReadingAi {
        var analyses = 0; var assists = 0; var reviews = 0; var cancelAnalysis = false; var assistFailure: String? = null
        override suspend fun analyze(article: Article, body: ArticleBody): ArticleStudy {
            analyses++
            if (cancelAnalysis) throw CancellationException("Synthetic interrupted call")
            return ArticleStudy(article.id, body.contentHash, "Synthetic summary", "B2", listOf(MindMapNode("root", null, "Topic")), emptyList(),
                ParagraphFocus.paragraphs(body.text).indices.map { StudyParagraph(it, "claim", "Summary") },
                listOf(ReadingQuestion("q", "Question", "Answer", listOf(0),
                    listOf(ReadingSkimPlanner.sentences(body.text).first().evidenceRange()))), emptyList(), "test", 1,
                schemaVersion = ReadingAiProtocol.VERSION)
        }
        override suspend fun assist(request: StudyRequest): StudyAssist { assists++; assistFailure?.let { throw ApiFailure(it, "Synthetic failure") }; return StudyAssist(request.kind, "Result", "Synthetic response", "test", 1) }
        override suspend fun review(notes: List<ReadingNote>): StudyAssist { reviews++; return StudyAssist("review", "Review", "Synthetic review", "test", 1) }
    }
}
