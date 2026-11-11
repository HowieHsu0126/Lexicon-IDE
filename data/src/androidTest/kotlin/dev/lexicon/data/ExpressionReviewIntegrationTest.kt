package dev.lexicon.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ExpressionReviewIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = Instant.parse("2026-10-30T08:00:00Z").toEpochMilli()
    private fun database() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
    private fun note(id: String, createdAt: Long = now, quote: String = "Synthetic quote $id.", comment: String = "Comment") =
        ReadingNote(id, null, null, quote, comment, emptyList(), createdAt)
    private fun repository(db: LexiconDatabase, ai: Recorder) = LocalReadingRepository(db, ai, { true }, { it.id != "blocked" }, { now })

    @Test fun newestWindowIsBoundedAndRestrictedNotesAreExcludedWithoutDeletionOrExtraApiCalls() = runBlocking {
        val db = database(); val ai = Recorder()
        try {
            val repo = repository(db, ai)
            val article = Article("blocked", "blocked", emptyList(), "Restricted example", "https://example.org/blocked", "Summary")
            db.dao().putArticle(ArticleRow(article.id, article.url, now, Json.encodeToString(article)))
            repo.saveNote(note("restricted").copy(articleId = article.id))
            repeat(31) { index -> repo.saveNote(note("n$index", now - index * 1000L).copy(origin = if (index == 0) ContextOrigin.AI_GENERATED else ContextOrigin.ORIGINAL)) }
            repo.saveNote(note("older", now - 10L * 86400000))
            val review = repo.expressionReview()
            assertEquals(30, ai.last.size)
            assertEquals("n0", ai.last.first().id)
            assertEquals("n29", ai.last.last().id)
            assertEquals(ContextOrigin.AI_GENERATED, ai.last.first().origin)
            assertTrue(review.evidence.contains("2026-10-24—2026-10-30"))
            assertTrue(review.evidence.contains("入选 30 条"))
            assertTrue(review.evidence.contains("来源限制 1 条"))
            assertTrue(review.evidence.contains("数量/输入上限 1 条"))
            assertTrue(review.evidence.contains("窗口外 1 条"))
            assertEquals(33, repo.library.first().notes.size)
            repo.saveNote(note("restricted-2").copy(articleId = article.id))
            val updated = repo.expressionReview()
            assertEquals(1, ai.calls)
            assertTrue(updated.evidence.contains("来源限制 2 条"))
            assertEquals(34, repo.library.first().notes.size)
            assertEquals(1, repo.library.first().reviews.size)
        } finally { db.close() }
    }

    @Test fun characterBudgetIncludesMetadataAndNeverClipsAnIndividualNote() = runBlocking {
        val db = database(); val ai = Recorder()
        try {
            val repo = repository(db, ai)
            repo.saveNote(note("huge", now, "x".repeat(12000), "y".repeat(12000)))
            repo.saveNote(note("big", now - 1, "x".repeat(12000), "y".repeat(11900)))
            repo.saveNote(note("wide", now - 2, "z".repeat(100), ""))
            repo.saveNote(note("tiny", now - 3, "z".repeat(30), ""))
            val result = repo.expressionReview()
            assertEquals(listOf("big", "tiny"), ai.last.map { it.id })
            assertEquals(12000, ai.last.first().quote.length)
            assertEquals(11900, ai.last.first().comment.length)
            ReadingAiProtocol.reviewPayload(ai.last)
            assertTrue(result.evidence.contains("数量/输入上限 2 条"))
            assertEquals(4, repo.library.first().notes.size)
        } finally { db.close() }
    }

    @Test fun noRecentNotesDoesNotSendOlderMaterialWithoutSayingSo() = runBlocking {
        val db = database(); val ai = Recorder()
        try {
            val repo = repository(db, ai)
            repo.saveNote(note("older", now - 10L * 86400000))
            try { repo.expressionReview(); fail("Expected an explicit empty-window result") }
            catch (failure: ApiFailure) { assertEquals("no_recent_notes", failure.code) }
            assertEquals(0, ai.calls)
            assertEquals(1, repo.library.first().notes.size)
        } finally { db.close() }
    }

    private class Recorder : ReadingAi {
        var calls = 0
        var last: List<ReadingNote> = emptyList()
        override suspend fun analyze(article: Article, body: ArticleBody): ArticleStudy = error("not used")
        override suspend fun assist(request: StudyRequest): StudyAssist = error("not used")
        override suspend fun review(notes: List<ReadingNote>): StudyAssist {
            calls++; last = notes.toList()
            return StudyAssist("review", "Synthetic review", "Synthetic response", "test", 1, "AI-generated practice")
        }
    }
}
