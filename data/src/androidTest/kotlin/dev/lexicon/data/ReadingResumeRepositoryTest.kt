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
import kotlin.coroutines.CoroutineContext

/** In-memory Room and self-authored original bodies. No networking, credentials, or training writes. */
@RunWith(AndroidJUnit4::class)
class ReadingResumeRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val json = Json { encodeDefaults = true }
    private val controls = ReadingResumeControls(StudyMode.SKIM, quiet = true, budgetMinutes = 10, roleFilter = "证据", toolTab = "重点")
    private fun db() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).build()
    private fun body(articleId: String = "resume-article", text: String = "  First original paragraph.  \r\n\r\nSecond original paragraph with 😀 and repeated words.\n\nThird original paragraph.") =
        ArticleBody(articleId, text, sha256(text), 100, 200, "Self-authored fixture")
    private suspend fun seed(db: LexiconDatabase, body: ArticleBody) {
        val article = Article(body.articleId, body.articleId, listOf("fixture"), "Synthetic original article", "https://example.org/${body.articleId}", "Unused fixture summary")
        db.dao().putArticle(ArticleRow(article.id, article.url, 0, json.encodeToString(article)))
        db.dao().putBody(BodyRow(body.articleId, body.expiresAt, json.encodeToString(body)))
    }
    private suspend fun fails(code: String, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: ApiFailure) { assertEquals(code, error.code) }
    }
    private suspend fun rejected(action: suspend () -> Unit) {
        try { action(); fail("Expected invalid continuation to be rejected") } catch (_: IllegalArgumentException) { }
    }

    /** A normal dispatcher boundary held by the test, with no sleeps or repository-specific hooks. */
    private class HeldDispatcher(private val delegate: CoroutineDispatcher, initiallyHeld: Boolean) : CoroutineDispatcher() {
        private var held = initiallyHeld
        private val waiting = mutableListOf<Pair<CoroutineContext, Runnable>>()
        val pending: Int get() = synchronized(this) { waiting.size }
        fun hold() { synchronized(this) { held = true } }
        fun release() {
            val queued = synchronized(this) { held = false; waiting.toList().also { waiting.clear() } }
            queued.forEach { (context, block) -> delegate.dispatch(context, block) }
        }
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val queued = synchronized(this) {
                if (held) { waiting += context to block; true } else false
            }
            if (!queued) delegate.dispatch(context, block)
        }
    }

    @Test fun newEntryWaitsForAlreadyInvokedCheckpointAcrossValidationAndStorageHops() = runBlocking {
        val db = db(); val body = body()
        val validation = HeldDispatcher(Dispatchers.Default, initiallyHeld = true)
        val storage = HeldDispatcher(Dispatchers.IO, initiallyHeld = false)
        var saving: Deferred<Boolean>? = null
        var entering: Deferred<ReadingResumeLease>? = null
        try {
            seed(db, body)
            val repo = LocalReadingResumeRepository(db, validationDispatcher = validation, storageDispatcher = storage)
            val lease = repo.begin(body.articleId, body.contentHash)
            storage.hold()
            val save = async(start = CoroutineStart.UNDISPATCHED) { repo.checkpoint(lease, 1, body, 2, controls) }
            saving = save
            assertEquals(1, validation.pending)
            val nextEntry = async(start = CoroutineStart.UNDISPATCHED) { repo.begin(body.articleId, body.contentHash) }
            entering = nextEntry
            // Without the whole-operation mutex, begin would synchronously dispatch into storage.
            assertEquals(0, storage.pending)
            assertFalse(nextEntry.isCompleted)
            storage.release(); validation.release()
            assertTrue(withTimeout(5000) { save.await() })
            val next = withTimeout(5000) { nextEntry.await() }
            assertEquals(2, next.previous!!.paragraphIndex)
            assertEquals(controls, next.previous!!.controls)
            assertEquals(lease.generation + 1, next.generation)
        } finally {
            storage.release(); validation.release()
            withContext(NonCancellable) { saving?.join(); entering?.join() }
            db.close()
        }
    }

    @Test fun beginAllocatesNewLeaseWithoutErasingThePreviousParagraphOrControls() = runBlocking {
        val db = db(); val body = body(); var now = 1000L
        try {
            seed(db, body); val repo = LocalReadingResumeRepository(db) { now }
            val first = repo.begin(body.articleId, body.contentHash)
            assertEquals(1L, first.generation); assertNull(first.previous); assertTrue(repo.library.first().records.isEmpty())
            assertTrue(repo.checkpoint(first, 1, body, 1, controls))
            val saved = repo.library.first().records.single()
            now = 900 // Wall time does not decide writer order.
            val reopened = LocalReadingResumeRepository(db) { now }.begin(body.articleId, body.contentHash)
            assertEquals(first.generation + 1, reopened.generation); assertEquals(saved, reopened.previous)
            assertEquals(saved, repo.library.first().records.single()); assertEquals(controls, saved.controls)
            ReadingResumes.validate(saved, body)
        } finally { db.close() }
    }

    @Test fun sameLeaseUsesSequenceRatherThanWallTimeAndRejectsDuplicateWrites() = runBlocking {
        val db = db(); val body = body(); var now = 5000L
        try {
            seed(db, body); val repo = LocalReadingResumeRepository(db) { now }; val lease = repo.begin(body.articleId, body.contentHash)
            assertTrue(repo.checkpoint(lease, 1, body, 0, controls))
            now = 1000
            assertTrue(repo.checkpoint(lease, 3, body, 2, controls))
            now = 9000
            assertFalse(repo.checkpoint(lease, 2, body, 1, controls)); assertFalse(repo.checkpoint(lease, 3, body, 0, controls))
            val saved = repo.library.first().records.single()
            assertEquals(3L, saved.sequence); assertEquals(2, saved.paragraphIndex); assertEquals(1000L, saved.updatedAt)
        } finally { db.close() }
    }

    @Test fun laterEntryInvalidatesEarlierWritersEvenWithHigherSequencesAndTimestamps() = runBlocking {
        val db = db(); val body = body(); var now = 1000L
        try {
            seed(db, body); val firstRepo = LocalReadingResumeRepository(db) { now }; val secondRepo = LocalReadingResumeRepository(db) { now }
            val first = firstRepo.begin(body.articleId, body.contentHash)
            assertTrue(firstRepo.checkpoint(first, 1, body, 0, controls))
            val second = secondRepo.begin(body.articleId, body.contentHash)
            assertTrue(secondRepo.checkpoint(second, 1, body, 1, controls.copy(mode = StudyMode.INTENSIVE)))
            now = 100_000
            assertFalse(firstRepo.checkpoint(first, 100, body, 2, controls))
            assertEquals(second.generation, firstRepo.library.first().records.single().generation)
            assertEquals(1, firstRepo.library.first().records.single().paragraphIndex)
        } finally { db.close() }
    }

    @Test fun simultaneousBeginsHaveUniqueGenerationsAndOnlyTheLatestLeaseWrites() = runBlocking {
        val db = db(); val body = body()
        try {
            seed(db, body)
            val leases = coroutineScope { (1..5).map { async { LocalReadingResumeRepository(db).begin(body.articleId, body.contentHash) } }.awaitAll() }
            assertEquals(5, leases.map { it.generation }.distinct().size)
            val latest = leases.maxBy { it.generation }; val repo = LocalReadingResumeRepository(db)
            leases.filter { it != latest }.forEach { assertFalse(repo.checkpoint(it, 1, body, 0, controls)) }
            assertTrue(repo.checkpoint(latest, 1, body, 2, controls))
        } finally { db.close() }
    }

    @Test fun concurrentSameLeaseCheckpointsFinishAtHighestSequence() = runBlocking {
        val db = db(); val body = body()
        try {
            seed(db, body); val repo = LocalReadingResumeRepository(db); val lease = repo.begin(body.articleId, body.contentHash)
            coroutineScope { (1L..12L).map { sequence -> async {
                LocalReadingResumeRepository(db).checkpoint(lease, sequence, body, (sequence % 3).toInt(), controls)
            } }.awaitAll() }
            assertEquals(12L, repo.library.first().records.single().sequence)
            assertEquals(0, repo.library.first().records.single().paragraphIndex)
        } finally { db.close() }
    }

    @Test fun completionIsStickyWithinLeaseAndANewEntryCanStartAgain() = runBlocking {
        val db = db(); val body = body()
        try {
            seed(db, body); val repo = LocalReadingResumeRepository(db); val first = repo.begin(body.articleId, body.contentHash)
            assertTrue(repo.checkpoint(first, 1, body, 2, controls, completed = true))
            assertTrue(repo.checkpoint(first, 2, body, 1, controls, completed = false))
            assertTrue(repo.library.first().records.single().completed)
            val second = repo.begin(body.articleId, body.contentHash)
            assertTrue(second.previous!!.completed)
            assertTrue(repo.checkpoint(second, 1, body, 0, controls, completed = false))
            assertFalse(repo.library.first().records.single().completed)
        } finally { db.close() }
    }

    @Test fun clearInvalidatesAllAlreadyIssuedLeasesAndAllowsOnlyANewBegin() = runBlocking {
        val db = db(); val body = body()
        try {
            seed(db, body); val repo = LocalReadingResumeRepository(db); val first = repo.begin(body.articleId, body.contentHash)
            assertTrue(repo.checkpoint(first, 1, body, 1, controls)); val saved = repo.library.first().records.single()
            val waitingEntry = repo.begin(body.articleId, body.contentHash)
            assertTrue(repo.clear(saved)); assertFalse(repo.clear(saved)); assertTrue(repo.library.first().records.isEmpty())
            assertFalse(repo.checkpoint(first, 9, body, 2, controls)); assertFalse(repo.checkpoint(waitingEntry, 1, body, 2, controls))
            val restarted = repo.begin(body.articleId, body.contentHash)
            assertTrue(restarted.generation > waitingEntry.generation); assertNull(restarted.previous)
            assertTrue(repo.checkpoint(restarted, 1, body, 0, controls))
            assertFalse(repo.clear(saved)); assertEquals(restarted.generation, repo.library.first().records.single().generation)
        } finally { db.close() }
    }

    @Test fun staleClearDoesNotEraseANewerCheckpointOrInvalidateItsLease() = runBlocking {
        val db = db(); val body = body()
        try {
            seed(db, body); val repo = LocalReadingResumeRepository(db); val lease = repo.begin(body.articleId, body.contentHash)
            assertTrue(repo.checkpoint(lease, 1, body, 0, controls)); val old = repo.library.first().records.single()
            assertTrue(repo.checkpoint(lease, 2, body, 1, controls))
            assertFalse(repo.clear(old)); assertTrue(repo.checkpoint(lease, 3, body, 2, controls))
            assertEquals(3L, repo.library.first().records.single().sequence)
        } finally { db.close() }
    }

    @Test fun changedOriginalIsNotRestoredOrWrittenUntilItsActualSnapshotMatches() = runBlocking {
        val db = db(); val old = body(); val replacement = body(text = "A changed original.\n\nIts second paragraph.")
        try {
            seed(db, old); val repo = LocalReadingResumeRepository(db); val first = repo.begin(old.articleId, old.contentHash)
            assertTrue(repo.checkpoint(first, 1, old, 1, controls)); val saved = repo.library.first().records.single()
            val newLease = repo.begin(replacement.articleId, replacement.contentHash)
            assertEquals(saved, newLease.previous); assertNull(ReadingResumes.restore(saved, replacement))
            assertFalse(repo.checkpoint(newLease, 1, replacement, 1, controls)); assertEquals(saved, repo.library.first().records.single())
            seed(db, replacement)
            assertTrue(repo.checkpoint(newLease, 1, replacement, 1, controls))
            assertEquals(replacement.contentHash, repo.library.first().records.single().bodyHash)
        } finally { db.close() }
    }

    @Test fun forgedPreviousOrWrongBodyLeaseCannotUseTheCurrentGeneration() = runBlocking {
        val db = db(); val body = body()
        try {
            seed(db, body); val repo = LocalReadingResumeRepository(db); val first = repo.begin(body.articleId, body.contentHash)
            assertTrue(repo.checkpoint(first, 1, body, 0, controls)); val next = repo.begin(body.articleId, body.contentHash)
            val forged = next.copy(previous = next.previous!!.copy(completed = true))
            assertFalse(repo.checkpoint(forged, 1, body, 2, controls))
            val other = this@ReadingResumeRepositoryTest.body(text = "A rehashed generated variant.")
            assertFalse(repo.checkpoint(next.copy(bodyHash = other.contentHash), 1, other, 0, controls))
            assertTrue(repo.checkpoint(next, 1, body, 1, controls))
        } finally { db.close() }
    }

    @Test fun invalidBodyHashParagraphAndControlsNeverReplaceAnExistingLocation() = runBlocking {
        val db = db(); val body = body()
        try {
            seed(db, body); val repo = LocalReadingResumeRepository(db); val lease = repo.begin(body.articleId, body.contentHash)
            assertTrue(repo.checkpoint(lease, 1, body, 1, controls)); val before = repo.library.first().records.single()
            rejected { repo.checkpoint(lease, 2, body.copy(text = body.text + "changed"), 1, controls) }
            rejected { repo.checkpoint(lease, 2, body, 10, controls) }
            rejected { repo.checkpoint(lease, 2, body, 1, controls.copy(budgetMinutes = 7)) }
            rejected { repo.checkpoint(lease, 0, body, 1, controls) }
            assertEquals(before, repo.library.first().records.single())
        } finally { db.close() }
    }

    @Test fun expiredBodyMayStillSaveWhenOriginalProofAndKnownArticleRemain() = runBlocking {
        val db = db(); val body = body()
        try {
            seed(db, body); val repo = LocalReadingResumeRepository(db); val lease = repo.begin(body.articleId, body.contentHash)
            assertTrue(repo.checkpoint(lease, 1, body, 0, controls))
            db.dao().expireBodies(Long.MAX_VALUE)
            assertTrue(repo.checkpoint(lease, 2, body, 2, controls))
            assertEquals(2, repo.library.first().records.single().paragraphIndex)
            val previous = repo.library.first().records.single(); assertTrue(repo.clear(previous))
            db.readingDao().put(PreferenceRow("reading-exposure:body:${body.contentHash}", "100"))
            val newLease = repo.begin(body.articleId, body.contentHash)
            assertTrue(repo.checkpoint(newLease, 1, body, 1, controls))
        } finally { db.close() }
    }

    @Test fun missingOriginalProofRejectsRehashedAiAndDoesNotDeleteExistingResume() = runBlocking {
        val db = db(); val original = body(); val aiBody = body(text = "A simple generated rewrite.\n\nIt is not the original article.")
        try {
            seed(db, original); val repo = LocalReadingResumeRepository(db); val originalLease = repo.begin(original.articleId, original.contentHash)
            assertTrue(repo.checkpoint(originalLease, 1, original, 1, controls)); val saved = repo.library.first().records.single()
            db.dao().expireBodies(Long.MAX_VALUE)
            val wrong = repo.begin(aiBody.articleId, aiBody.contentHash)
            assertFalse(repo.checkpoint(wrong, 1, aiBody, 0, controls)); assertEquals(saved, repo.library.first().records.single())
            val unknown = body(articleId = "unknown", text = "Unknown original material.")
            db.readingDao().put(PreferenceRow("reading-exposure:body:${unknown.contentHash}", "100"))
            val unknownLease = repo.begin(unknown.articleId, unknown.contentHash)
            assertFalse(repo.checkpoint(unknownLease, 1, unknown, 0, controls))
            assertEquals(saved, repo.library.first().records.single())
        } finally { db.close() }
    }

    @Test fun corruptRecordIsCountedAndBeginPreservesItsBytesAndGeneration() = runBlocking {
        val db = db(); val body = body()
        try {
            seed(db, body); val repo = LocalReadingResumeRepository(db); val lease = repo.begin(body.articleId, body.contentHash)
            assertTrue(repo.checkpoint(lease, 1, body, 1, controls))
            val generation = db.readingDao().entry(ReadingResumeStorage.generationKey(body.articleId))
            val broken = PreferenceRow(ReadingResumeStorage.recordKey(body.articleId), "{broken resume")
            db.readingDao().put(broken)
            assertEquals(1, repo.library.first().errors); assertTrue(repo.library.first().records.isEmpty())
            fails("reading_resume_invalid") { repo.begin(body.articleId, body.contentHash) }
            assertEquals(broken, db.readingDao().entry(broken.key)); assertEquals(generation, db.readingDao().entry(generation!!.key))
        } finally { db.close() }
    }

    @Test fun corruptOrUnknownGenerationNeverResetsTheLeaseCounter() = runBlocking {
        val db = db(); val body = body()
        try {
            seed(db, body); val repo = LocalReadingResumeRepository(db)
            val key = ReadingResumeStorage.generationKey(body.articleId)
            val invalidValues = listOf("{broken generation", "{\"generation\":4,\"bodyHash\":null,\"previousHash\":null,\"version\":2}",
                "{\"generation\":0,\"bodyHash\":null,\"previousHash\":null,\"version\":1}")
            invalidValues.forEach { value ->
                val row = PreferenceRow(key, value); db.readingDao().put(row)
                fails("reading_resume_invalid") { repo.begin(body.articleId, body.contentHash) }
                assertEquals(row, db.readingDao().entry(key)); assertEquals(1, repo.library.first().errors)
            }
        } finally { db.close() }
    }

    @Test fun generationOverflowCannotEraseAnExistingRecord() = runBlocking {
        val db = db(); val body = body()
        try {
            seed(db, body); val repo = LocalReadingResumeRepository(db); val lease = repo.begin(body.articleId, body.contentHash)
            assertTrue(repo.checkpoint(lease, 1, body, 1, controls)); val saved = repo.library.first().records.single()
            val limit = ReadingResumeStorage.generationRow(body.articleId, Long.MAX_VALUE, body.contentHash, saved)
            db.readingDao().put(limit)
            fails("reading_resume_limit") { repo.begin(body.articleId, body.contentHash) }
            fails("reading_resume_limit") { repo.clear(saved) }
            assertEquals(saved, repo.library.first().records.single()); assertEquals(limit, db.readingDao().entry(limit.key))
        } finally { db.close() }
    }

    @Test fun mainCollectorReceivesExactUnicodeParagraphAndRowsDoNotContainArticleTextOrChangeMetrics() = runBlocking {
        val db = db(); val body = body(text = (0..85).joinToString("\n\n") { "😀 Original paragraph $it with repeated words." })
        try {
            seed(db, body); val repo = LocalReadingResumeRepository(db); val lease = repo.begin(body.articleId, body.contentHash)
            val emission = async(Dispatchers.Main.immediate) { withTimeout(5000) { repo.library.first { it.records.isNotEmpty() } } }
            assertTrue(repo.checkpoint(lease, 1, body, 84, controls))
            val saved = emission.await().records.single(); ReadingResumes.validate(saved, body)
            assertEquals(84, saved.paragraphIndex)
            assertTrue(db.dao().allEvents().isEmpty()); assertTrue(db.dao().allCards().isEmpty()); assertTrue(db.dao().observeJobs().first().isEmpty())
            assertTrue(db.readingDao().libraryRows().isEmpty())
            val rows = db.readingDao().studyRows("reading-resume:")
            assertEquals(2, rows.size); assertTrue(rows.none { it.data.contains("Original paragraph") })
        } finally { db.close() }
    }
}
