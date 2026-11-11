package dev.lexicon.data

import android.content.Context
import androidx.room3.Room
import androidx.room3.useWriterConnection
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

/** Atomic commit regressions use only self-authored material and an isolated Room database. */
@RunWith(AndroidJUnit4::class)
class VocabularyCardCommitTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val codec = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val source = Source("commit-source", "Synthetic source", "https://example.org/feed", aiAllowed = true)
    private val article = Article("commit-article", "fixture", listOf(source.id), "Synthetic article", "https://example.org/article", "Summary",
        topic = "Education", sourceName = source.name)
    private val content = VocabularyContent("evidence", "Information supporting this claim.",
        listOf(Collocation("provide evidence", "A synthetic example."), Collocation("limited evidence", "Another synthetic example.")),
        "Supporting information.", "formal", "medium")
    private fun capture(id: String = "commit-capture") = Capture(id, article.id, "evidence", "evidence", "The report provides evidence.",
        ContextOrigin.ORIGINAL, SourceRef(article.title, article.sourceName, article.url), 20, 28, sha256("A synthetic body"), 100, updatedAt = 200)
    private fun proposed(capture: Capture, id: String = "card:${capture.id}") = Card(id, capture.id, content, capture.sentence, capture.sourceRef,
        topic = "Outdated topic", state = FsrsState(dueAt = 1000), createdAt = 1000, contextOrigin = capture.contextOrigin)
    private fun db() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).build()
    private suspend fun seed(db: LexiconDatabase) {
        db.dao().putSource(SourceRow(source.id, codec.encodeToString(source)))
        db.dao().putArticle(ArticleRow(article.id, article.url, 0, codec.encodeToString(article)))
    }
    private suspend fun fails(code: String, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (failure: ApiFailure) { assertEquals(code, failure.code) }
    }

    @Test fun anEditAfterAnEarlierReadIsNeverOverwrittenByTheCardCommit() = runBlocking {
        val db = db()
        try {
            seed(db); val old = capture()
            db.dao().putCapture(CaptureRow(old.id, old.createdAt, codec.encodeToString(old)))
            val earlierRead = codec.decodeFromString<Capture>(db.dao().capture(old.id)!!.data)
            val edit = old.copy(target = "limited evidence", sentence = "The report provides limited evidence.", contextOrigin = ContextOrigin.USER_SUPPLIED, updatedAt = 900)
            val editedRow = CaptureRow(edit.id, edit.createdAt, codec.encodeToString(edit)); db.dao().putCapture(editedRow)
            fails("capture_changed") { db.vocabularyDao().commitCard(earlierRead, proposed(earlierRead)) }
            assertEquals(editedRow, db.dao().capture(old.id))
            assertTrue(db.dao().allCards().isEmpty())
            assertNull(db.dao().preference("card-input:${old.id}")); assertTrue(db.dao().allEvents().isEmpty())
        } finally { db.close() }
    }

    @Test fun successfulCommitPreservesExistingRawCaptureMetadataAndCapturesCurrentTopic() = runBlocking {
        val db = db()
        try {
            seed(db); val old = capture()
            val raw = JsonObject(codec.parseToJsonElement(codec.encodeToString(old.copy(updatedAt = 850))).jsonObject +
                ("retained_extra" to JsonPrimitive("Synthetic legacy metadata"))).toString()
            val original = CaptureRow(old.id, 42, raw); db.dao().putCapture(original)
            val card = db.vocabularyDao().commitCard(old, proposed(old))
            assertEquals(original, db.dao().capture(old.id))
            assertEquals("Education", card.topic)
            assertEquals(VocabularyCardInput.signature(old), db.dao().preference("card-input:${old.id}")!!.data)
            assertEquals(listOf("card_saved"), db.dao().allEvents().map { codec.decodeFromString<LocalEvent>(it.data).type })
            assertTrue(codec.decodeFromString<Article>(db.dao().article(article.id)!!.data).captured)
        } finally { db.close() }
    }

    @Test fun concurrentDuplicateCommitsCreateOneCardOneSignatureAndOneEvent() = runBlocking {
        val db = db()
        try {
            seed(db); val original = capture()
            val values = coroutineScope { (1..3).map { index -> async { db.vocabularyDao().commitCard(original, proposed(original, "card-$index")) } }.awaitAll() }
            assertEquals(1, values.distinct().size); assertEquals(1, db.dao().allCards().size)
            assertEquals(1, db.dao().observeCaptures().first().size)
            assertEquals(VocabularyCardInput.signature(original), db.dao().preference("card-input:${original.id}")!!.data)
            assertEquals(setOf("capture_saved", "card_saved"), db.dao().allEvents().map { codec.decodeFromString<LocalEvent>(it.data).type }.toSet())
            assertEquals(2, db.dao().allEvents().size)
        } finally { db.close() }
    }

    @Test fun interleavedUserEditsRemainStoredWhetherTheyPrecedeOrFollowTheAtomicCommit() = runBlocking {
        val db = db()
        try {
            seed(db)
            repeat(12) { index ->
                val old = capture("race-$index"); val edit = old.copy(sentence = "A changed sentence about evidence.", updatedAt = 900)
                db.dao().putCapture(CaptureRow(old.id, old.createdAt, codec.encodeToString(old)))
                val row = CaptureRow(edit.id, edit.createdAt, codec.encodeToString(edit))
                val start = CompletableDeferred<Unit>()
                val outcome = coroutineScope {
                    val commit = async { start.await(); runCatching { db.vocabularyDao().commitCard(old, proposed(old)) } }
                    val write = async { start.await(); db.dao().putCapture(row) }
                    start.complete(Unit); write.await(); commit.await()
                }
                assertEquals(row, db.dao().capture(edit.id))
                if (outcome.isSuccess) {
                    assertEquals(old.sentence, outcome.getOrThrow().sourceSentence)
                    assertEquals(VocabularyCardInput.signature(old), db.dao().preference("card-input:${old.id}")!!.data)
                } else {
                    assertEquals("capture_changed", (outcome.exceptionOrNull() as ApiFailure).code)
                    assertNull(db.dao().cardByCapture(old.id)); assertNull(db.dao().preference("card-input:${old.id}"))
                }
            }
        } finally { db.close() }
    }

    @Test fun metadataInsertionFailureRollsBackCaptureCardEventAndArticleChangesTogether() = runBlocking {
        val db = db()
        try {
            seed(db); val beforeArticle = db.dao().article(article.id)
            val unrelated = PreferenceRow("unrelated-fixture", "keep"); db.dao().putPreference(unrelated)
            db.useWriterConnection { connection ->
                connection.usePrepared("CREATE TRIGGER fail_card_input BEFORE INSERT ON preferences WHEN NEW.key LIKE 'card-input:%' BEGIN SELECT RAISE(ABORT, 'synthetic commit failure'); END") { it.step() }
            }
            val original = capture()
            assertTrue(runCatching { db.vocabularyDao().commitCard(original, proposed(original)) }.isFailure)
            assertNull(db.dao().capture(original.id)); assertTrue(db.dao().allCards().isEmpty())
            assertNull(db.dao().preference("card-input:${original.id}")); assertTrue(db.dao().allEvents().isEmpty())
            assertEquals(beforeArticle, db.dao().article(article.id)); assertEquals(unrelated, db.dao().preference(unrelated.key))
        } finally { db.close() }
    }

    @Test fun commitChecksCurrentSourcePolicyWhileUnassociatedManualCaptureRetainsItsPermission() = runBlocking {
        val db = db()
        try {
            seed(db); val original = capture()
            db.dao().putSource(SourceRow(source.id, codec.encodeToString(source.copy(aiAllowed = false))))
            fails("source_restricted") { db.vocabularyDao().commitCard(original, proposed(original)) }
            assertNull(db.dao().capture(original.id)); assertTrue(db.dao().allCards().isEmpty())
            val manual = original.copy(id = "unassociated", articleId = null, bodyHash = null, selectionStart = null, selectionEnd = null,
                sourceRef = SourceRef(), contextOrigin = ContextOrigin.USER_SUPPLIED, aiAllowed = true)
            val saved = db.vocabularyDao().commitCard(manual, proposed(manual))
            assertEquals(manual.sourceRef, saved.sourceRef); assertNull(saved.topic)
            assertEquals(manual, codec.decodeFromString<Capture>(db.dao().capture(manual.id)!!.data))
        } finally { db.close() }
    }
}
