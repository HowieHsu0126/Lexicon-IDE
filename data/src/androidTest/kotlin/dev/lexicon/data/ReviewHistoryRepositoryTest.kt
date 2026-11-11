package dev.lexicon.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Committed history over an isolated database. No installed learning data, key or network request. */
@RunWith(AndroidJUnit4::class)
class ReviewHistoryRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val codec = Json { encodeDefaults = true }
    private val range = ReviewDayRange(1_000, 5_000)
    private val text = "Residents compare available books in a shared reading room."
    private val article = Article("history-article", "fixture", listOf("history-source"), "Shared reading",
        "https://example.invalid/reading", "Self-authored fixture", sourceName = "Fixture")
    private val body = ArticleBody(article.id, text, sha256(text), 10, Long.MAX_VALUE, "Self-authored")
    private val capture = Capture("history-capture", article.id, "Residents", "Residents", text, ContextOrigin.ORIGINAL,
        SourceRef(article.title, article.sourceName, article.url), 0, 9, body.contentHash, 100)
    private val content = VocabularyContent("resident", "A person who lives near this room.",
        listOf(Collocation("local residents", "Local residents compare books."),
            Collocation("nearby residents", "Nearby residents use the room.")), "local inhabitant", "neutral", "medium")
    private val card = Card("history-card", capture.id, content, capture.sentence, capture.sourceRef,
        state = FsrsState(dueAt = 1_000), createdAt = 100, updatedAt = 200)

    private fun database() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).build()
    private fun history(db: LexiconDatabase) = LocalReviewHistoryRepository(db.reviewHistoryDao())
    private suspend fun seed(db: LexiconDatabase) {
        db.dao().putArticle(ArticleRow(article.id, article.url, 100, codec.encodeToString(article)))
        db.dao().putBody(BodyRow(body.articleId, body.expiresAt, codec.encodeToString(body)))
        db.dao().putCapture(CaptureRow(capture.id, capture.createdAt, codec.encodeToString(capture)))
        db.dao().putCard(CardRow(card.id, card.captureId, card.state.dueAt, card.state.reps, card.updatedAt, codec.encodeToString(card)))
    }
    private fun log(id: String, at: Long, cardId: String = card.id): ReviewLog {
        val before = FsrsState(dueAt = at - 1)
        return ReviewLog(id, cardId, at, Rating.GOOD, before, Fsrs6Scheduler().review(before, Rating.GOOD, at))
    }
    private suspend fun putLog(db: LexiconDatabase, log: ReviewLog) {
        db.dao().putReview(ReviewRow(log.id, log.cardId, log.reviewedAt, codec.encodeToString(log)))
    }
    private suspend fun summary(db: LexiconDatabase, day: ReviewDayRange = range) = withTimeout(5_000) { history(db).observeDay(day).first() }

    @Test fun unreviewedCardsAndCompletionEventsDoNotInventHistory() = runBlocking {
        val db = database()
        try {
            seed(db)
            val unrelated = LocalEvent("event-only", "review_completed", 2_000, 50)
            db.dao().putEvent(EventRow(unrelated.id, unrelated.createdAt, codec.encodeToString(unrelated)))
            val before = snapshot(db)
            assertEquals(ReviewDaySummary(), summary(db))
            assertEquals(before, snapshot(db))
        } finally { db.close() }
    }

    @Test fun queryIncludesStartAndExcludesEndWithoutReadingDamagedRowsOutsideTheWindow() = runBlocking {
        val db = database()
        try {
            seed(db)
            db.dao().putReview(ReviewRow("older-damaged", card.id, range.startInclusive - 1, "{}"))
            putLog(db, log("first", range.startInclusive))
            putLog(db, log("last", range.endExclusive - 1))
            db.dao().putReview(ReviewRow("next-damaged", card.id, range.endExclusive, "{}"))
            val before = snapshot(db)
            val result = summary(db)
            assertEquals(2, result.count)
            assertEquals("last", result.latestReceipt?.logId)
            assertEquals(range.endExclusive - 1, result.latestReceipt?.reviewedAt)
            assertEquals(before, snapshot(db))
        } finally { db.close() }
    }

    @Test fun oneLiveObserverReceivesTwoCommittedRepetitionsOfTheSameCard() = runBlocking {
        val db = database()
        val observed = Channel<ReviewDaySummary>(Channel.UNLIMITED)
        try {
            seed(db)
            val collector = launch { history(db).observeDay(range).collect { observed.send(it) } }
            try {
                assertEquals(ReviewDaySummary(), withTimeout(5_000) { observed.receive() })
                assertEquals(ReviewOutcome.APPLIED, db.cardLifecycleDao().review(card.id, Rating.GOOD, 0, 2_000))
                val first = withTimeout(5_000) { observed.receive() }
                assertEquals(1, first.count)
                assertEquals(0, first.latestReceipt?.beforeReps); assertEquals(1, first.latestReceipt?.afterReps)
                assertEquals(ReviewOutcome.APPLIED, db.cardLifecycleDao().review(card.id, Rating.HARD, 1, 3_000))
                val second = withTimeout(5_000) { observed.receive() }
                assertEquals(2, second.count)
                assertEquals(card.id, second.latestReceipt?.cardId)
                assertEquals(Rating.HARD, second.latestReceipt?.rating)
                assertEquals(1, second.latestReceipt?.beforeReps); assertEquals(2, second.latestReceipt?.afterReps)
                assertNotEquals(first.latestReceipt?.logId, second.latestReceipt?.logId)
                // Reconstructing the reader uses persisted truth rather than a success callback.
                assertEquals(second, summary(db))
                val savedCard = codec.decodeFromString<Card>(requireNotNull(db.dao().card(card.id)).data)
                assertEquals(card.copy(state = savedCard.state), savedCard)
                assertEquals(capture, codec.decodeFromString<Capture>(requireNotNull(db.dao().capture(capture.id)).data))
                assertEquals(body, codec.decodeFromString<ArticleBody>(requireNotNull(db.dao().body(article.id, 0)).data))
            } finally { collector.cancelAndJoin() }
        } finally { observed.close(); db.close() }
    }

    @Test fun realRepositoryBindingCountsAppliedOnceAcrossConcurrentStaleSubmissions() = runBlocking {
        val db = database()
        val folder = File(context.cacheDir, "review-history-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(folder, "no_backup").apply { check(isDirectory || mkdirs()) }
        }
        val noAi = object : LearningAiService {
            override suspend fun analyze(capture: Capture, deep: Boolean, dictionary: List<DictionaryEntry>): AnalysisResult = error("History does not call AI")
            override suspend fun classify(inputs: List<TopicInput>): List<TopicResult> = error("History does not classify")
        }
        try {
            seed(db)
            val left = LexiconRepository(isolated, db, noAi)
            val right = LexiconRepository(isolated, db, noAi)
            val gate = CompletableDeferred<Unit>()
            val outcomes = coroutineScope {
                val first = async { gate.await(); left.reviewCard(card.id, Rating.GOOD, 0, 2_000) }
                val second = async { gate.await(); right.reviewCard(card.id, Rating.EASY, 0, 2_000) }
                gate.complete(Unit); listOf(first.await(), second.await())
            }
            assertEquals(1, outcomes.count { it == ReviewOutcome.APPLIED })
            assertEquals(1, outcomes.count { it == ReviewOutcome.STALE })
            val committed = withTimeout(5_000) { left.reviewHistory.observeDay(range).first() }
            assertEquals(1, committed.count)
            assertEquals(committed, withTimeout(5_000) { right.reviewHistory.observeDay(range).first() })
            assertEquals(ReviewOutcome.MISSING, left.reviewCard("missing", Rating.GOOD, 0, 3_000))
            left.setCardReviewPaused(card.id, true)
            assertEquals(ReviewOutcome.PAUSED, right.reviewCard(card.id, Rating.GOOD, 1, 3_000))
            val beforeReads = snapshot(db)
            assertEquals(committed, summary(db))
            assertEquals(beforeReads, snapshot(db))
        } finally { db.close(); check(folder.deleteRecursively()) }
    }

    @Test fun fileBackedCommittedReviewSurvivesClosingAndReopeningWithTheSameBundledDriver() = runBlocking {
        val folder = File(context.cacheDir, "review-history-file-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val databaseFile = File(folder, "history.sqlite")
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(folder, "no_backup").apply { check(isDirectory || mkdirs()) }
            override fun getDatabasePath(name: String): File =
                File(name).takeIf { it.isAbsolute } ?: File(folder, name)
        }
        val noAi = object : LearningAiService {
            override suspend fun analyze(capture: Capture, deep: Boolean, dictionary: List<DictionaryEntry>): AnalysisResult = error("Persistence test does not call AI")
            override suspend fun classify(inputs: List<TopicInput>): List<TopicResult> = error("Persistence test does not classify")
        }
        var connection: LexiconDatabase? = null
        fun openDatabase() = Room.databaseBuilder<LexiconDatabase>(isolated, databaseFile.absolutePath)
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
            .also { connection = it }
        try {
            assertFalse(databaseFile.exists())
            val first = openDatabase()
            seed(first)
            val repository = LexiconRepository(isolated, first, noAi)
            assertEquals(ReviewOutcome.APPLIED, repository.reviewCard(card.id, Rating.GOOD, 0, 2_000))
            val committed = withTimeout(5_000) { repository.reviewHistory.observeDay(range).first() }
            assertEquals(1, committed.count)
            val receipt = requireNotNull(committed.latestReceipt)
            val savedCardRow = requireNotNull(first.dao().card(card.id))
            val savedCard = codec.decodeFromString<Card>(savedCardRow.data)
            val expectedState = Fsrs6Scheduler().review(card.state, Rating.GOOD, 2_000)
            assertEquals(card.copy(state = expectedState), savedCard)
            val logRows = withTimeout(5_000) { first.reviewHistoryDao().observeRows(range.startInclusive, range.endExclusive).first() }
            assertEquals(1, logRows.size)
            val log = codec.decodeFromString<ReviewLog>(logRows.single().data)
            assertEquals(ReviewLog(receipt.logId, card.id, 2_000, Rating.GOOD, card.state, expectedState), log)
            assertEquals(ReviewReceipt(log.id, card.id, 2_000, Rating.GOOD, 0, 1), receipt)
            val committedBytes = snapshot(first)

            first.close(); connection = null
            assertTrue("The committed database must exist on disk before reopening", databaseFile.isFile && databaseFile.length() > 0)
            val reopened = openDatabase()
            val restoredRepository = LexiconRepository(isolated, reopened, noAi)
            val restored = withTimeout(5_000) { restoredRepository.reviewHistory.observeDay(range).first() }
            assertEquals(committed, restored)
            assertEquals(savedCardRow, reopened.dao().card(card.id))
            assertEquals(logRows, withTimeout(5_000) { reopened.reviewHistoryDao().observeRows(range.startInclusive, range.endExclusive).first() })
            assertEquals("Reopening and observing must preserve all learning material and log bytes", committedBytes, snapshot(reopened))
            assertFalse(File(isolated.noBackupFilesDir, "deepseek-key.aesgcm").exists())
        } finally { connection?.close(); check(folder.deleteRecursively()) }
    }

    @Test fun failedCompletionEventRollsBackTheInsertedLogAndCardBeforeHistoryCanCountIt() = runBlocking {
        val db = database()
        try {
            seed(db)
            val before = snapshot(db)
            db.useWriterConnection { connection ->
                connection.usePrepared("CREATE TRIGGER fail_history_event BEFORE INSERT ON local_events BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END") { it.step() }
            }
            assertTrue(runCatching { db.cardLifecycleDao().review(card.id, Rating.GOOD, 0, 2_000) }.isFailure)
            assertEquals(ReviewDaySummary(), summary(db))
            assertEquals(before, snapshot(db))
            db.useWriterConnection { connection -> connection.usePrepared("DROP TRIGGER fail_history_event") { it.step() } }
            assertEquals(ReviewOutcome.APPLIED, db.cardLifecycleDao().review(card.id, Rating.GOOD, 0, 2_000))
            assertEquals(1, summary(db).count)
        } finally { db.close() }
    }

    @Test fun damagedInWindowLogFailsAsUnknownAndPreservesEveryStoredByte() = runBlocking {
        val db = database()
        try {
            seed(db)
            putLog(db, log("valid", 2_000))
            db.dao().putReview(ReviewRow("damaged", card.id, 3_000, "{\"retained\":\"private fixture\"}"))
            val before = snapshot(db)
            try {
                summary(db)
                fail("A damaged log must not emit a fabricated count")
            } catch (failure: ReviewHistoryUnavailableException) {
                assertEquals("Review history is unavailable", failure.message)
                assertNull(failure.cause)
            }
            assertEquals(before, snapshot(db))
        } finally { db.close() }
    }

    @Test fun stableLatestReceiptRemainsAvailableWhenItsCardIsNoLongerPresent() = runBlocking {
        val db = database()
        try {
            putLog(db, log("z", 2_000, "missing-card"))
            putLog(db, log("a", 2_000, "another-missing-card"))
            val before = snapshot(db)
            val expected = ReviewDaySummary(2, ReviewReceipt("z", "missing-card", 2_000, Rating.GOOD, 0, 1))
            assertEquals(expected, summary(db))
            assertEquals(expected, summary(db))
            assertEquals(before, snapshot(db))
            assertTrue(db.dao().allCards().isEmpty())
        } finally { db.close() }
    }

    @Test fun repeatedLocalHourAndBothDstDayBoundariesQueryTheSameCompleteDay() = runBlocking {
        val db = database()
        fun time(value: String) = Instant.parse(value).toEpochMilli()
        try {
            val day = ReviewDayRange.from(time("2026-11-01T05:30:00Z"), ZoneId.of("America/New_York"))
            putLog(db, log("at-start", day.startInclusive))
            putLog(db, log("first-hour", time("2026-11-01T05:30:00Z")))
            putLog(db, log("repeated-hour", time("2026-11-01T06:30:00Z")))
            putLog(db, log("last", day.endExclusive - 1))
            putLog(db, log("next-day", day.endExclusive))
            val result = summary(db, day)
            assertEquals(4, result.count)
            assertEquals("last", result.latestReceipt?.logId)
            assertEquals(1, summary(db, ReviewDayRange.from(day.endExclusive, ZoneId.of("America/New_York"))).count)
        } finally { db.close() }
    }

    private suspend fun snapshot(db: LexiconDatabase): Map<String, List<List<String?>>> = db.useReaderConnection { connection ->
        val tables = listOf("articles", "article_bodies", "captures", "cards", "review_logs", "local_events", "preferences")
        tables.associateWith { table ->
            connection.usePrepared("SELECT * FROM $table ORDER BY 1") { statement ->
                buildList {
                    while (statement.step()) add((0 until statement.getColumnCount()).map { column ->
                        if (statement.isNull(column)) null else statement.getText(column)
                    })
                }
            }
        }
    }
}
