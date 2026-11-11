package dev.lexicon.data

import android.content.Context
import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Isolated card lifecycle regressions. No key, model request, publisher text or installed learning DB. */
@RunWith(AndroidJUnit4::class)
class CardLifecycleDaoTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val codec = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val content = VocabularyContent("robust", "Remaining effective under these conditions.",
        listOf(Collocation("robust method", "The robust method handles changing inputs."),
            Collocation("robust evidence", "The test supplies robust evidence.")), "reliable", "formal", "medium")
    private fun card(id: String = "card", state: FsrsState = FsrsState(dueAt = 1000)) =
        Card(id, "capture:$id", content, "The robust method handles changing inputs.", SourceRef("Synthetic methods", "Fixture"),
            topic = "Education", state = state, createdAt = 10, updatedAt = 50, ankiExportedAt = 80, noteGuid = "note:$id")
    private fun db() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).build()
    private suspend fun seed(db: LexiconDatabase, card: Card, legacy: Boolean = false): CardRow {
        val data = codec.parseToJsonElement(codec.encodeToString(card)).jsonObject
        val raw = JsonObject((if (legacy) data - "reviewPaused" else data) +
            ("retained_metadata" to buildJsonObject { put("owner", "synthetic-fixture") })).toString()
        return CardRow(card.id, card.captureId, card.state.dueAt, card.state.reps, card.updatedAt, raw)
            .also { db.dao().putCard(it) }
    }
    private suspend fun saved(db: LexiconDatabase, id: String = "card") = codec.decodeFromString<Card>(db.dao().card(id)!!.data)
    private suspend fun logs(db: LexiconDatabase): List<ReviewLog> = db.useReaderConnection { connection ->
        connection.usePrepared("SELECT data FROM review_logs ORDER BY reviewedAt, id") { statement ->
            buildList { while (statement.step()) add(codec.decodeFromString<ReviewLog>(statement.getText(0))) }
        }
    }
    private suspend fun events(db: LexiconDatabase) = db.dao().allEvents().map { codec.decodeFromString<LocalEvent>(it.data) }
    private suspend fun metadataRetained(db: LexiconDatabase, id: String = "card") =
        assertEquals("synthetic-fixture", codec.parseToJsonElement(db.dao().card(id)!!.data).jsonObject
            .getValue("retained_metadata").jsonObject.getValue("owner").jsonPrimitive.content)

    @Test fun legacyPauseAndResumeChangeOnlyTheirOwnedJsonField() = runBlocking {
        val db = db()
        try {
            val original = card(state = FsrsState(CardPhase.REVIEW, 3.5, 4.0, 2000, 1000, null, 4, 1))
            val raw = seed(db, original, legacy = true)
            val paused = db.cardLifecycleDao().setReviewPaused(original.id, true)!!
            assertEquals(original.copy(reviewPaused = true), paused)
            val pauseRow = db.dao().card(original.id)!!
            assertEquals(raw.copy(data = pauseRow.data), pauseRow)
            assertEquals(codec.parseToJsonElement(raw.data).jsonObject,
                JsonObject(codec.parseToJsonElement(pauseRow.data).jsonObject - "reviewPaused"))
            db.cardLifecycleDao().setReviewPaused(original.id, true)
            assertEquals(pauseRow, db.dao().card(original.id))
            val resumed = db.cardLifecycleDao().setReviewPaused(original.id, false)!!
            assertEquals(original, resumed)
            assertTrue(resumed.isDueForReview(5000)); assertFalse(resumed.isDueForReview(1999))
            assertTrue(logs(db).isEmpty()); assertTrue(events(db).isEmpty())
            metadataRetained(db)
        } finally { db.close() }
    }

    @Test fun staleUiCannotReviewAnyPausedPhaseAndMissingCardsRemainMissing() = runBlocking {
        val db = db()
        try {
            for (phase in CardPhase.entries) {
                val original = card(phase.name, FsrsState(phase = phase, dueAt = 1000)).copy(reviewPaused = true)
                val before = seed(db, original)
                assertEquals(ReviewOutcome.PAUSED, db.cardLifecycleDao().review(original.id, Rating.GOOD, 0, 2000))
                assertEquals(before, db.dao().card(original.id))
            }
            assertEquals(ReviewOutcome.MISSING, db.cardLifecycleDao().review("missing", Rating.GOOD, 0, 2000))
            assertNull(db.cardLifecycleDao().setReviewPaused("missing", true))
            assertTrue(logs(db).isEmpty()); assertTrue(events(db).isEmpty())
        } finally { db.close() }
    }

    @Test fun successfulReviewCommitsOnlyScheduleLogAndOneCompletionEvent() = runBlocking {
        val db = db()
        try {
            val original = card(); seed(db, original)
            assertEquals(ReviewOutcome.APPLIED, db.cardLifecycleDao().review(original.id, Rating.GOOD, 0, 2000))
            val expected = Fsrs6Scheduler().review(original.state, Rating.GOOD, 2000)
            assertEquals(original.copy(state = expected), saved(db))
            val log = logs(db).single()
            assertEquals(original.state, log.before); assertEquals(expected, log.after)
            assertEquals(Rating.GOOD, log.rating); assertEquals(2000L, log.reviewedAt)
            assertEquals(listOf("review_completed"), events(db).map { it.type })
            assertEquals(ReviewOutcome.STALE, db.cardLifecycleDao().review(original.id, Rating.AGAIN, 0, 2000))
            assertEquals(1, logs(db).size); assertEquals(1, events(db).size)
            metadataRetained(db)
        } finally { db.close() }
    }

    @Test fun duplicateReviewSubmissionsApplyOnceAcrossRepositoryInstances() = runBlocking {
        val db = db()
        try {
            val original = card(); seed(db, original)
            val left = LexiconRepository(context, db); val right = LexiconRepository(context, db)
            val gate = CompletableDeferred<Unit>()
            val results = coroutineScope {
                val first = async { gate.await(); left.reviewCard(original.id, Rating.GOOD, 0, 2000) }
                val second = async { gate.await(); right.reviewCard(original.id, Rating.GOOD, 0, 2000) }
                gate.complete(Unit); listOf(first.await(), second.await())
            }
            assertEquals(1, results.count { it == ReviewOutcome.APPLIED })
            assertEquals(1, results.count { it == ReviewOutcome.STALE })
            assertEquals(1, saved(db).state.reps)
            assertEquals(1, logs(db).size); assertEquals(1, events(db).size)
        } finally { db.close() }
    }

    @Test fun concurrentPauseAndReviewHaveOneValidSerialOutcome() = runBlocking {
        val db = db()
        try {
            val left = LexiconRepository(context, db); val right = LexiconRepository(context, db)
            repeat(8) { index ->
                val original = card("race-$index"); seed(db, original)
                val gate = CompletableDeferred<Unit>()
                val result = coroutineScope {
                    val pause = async { gate.await(); left.setCardReviewPaused(original.id, true) }
                    val review = async { gate.await(); right.reviewCard(original.id, Rating.GOOD, 0, 2000) }
                    gate.complete(Unit); pause.await(); review.await()
                }
                val current = saved(db, original.id)
                assertTrue(current.reviewPaused)
                assertTrue(result == ReviewOutcome.APPLIED || result == ReviewOutcome.PAUSED)
                val expected = if (result == ReviewOutcome.APPLIED) Fsrs6Scheduler().review(original.state, Rating.GOOD, 2000) else original.state
                assertEquals(original.copy(state = expected, reviewPaused = true), current)
                assertEquals(if (result == ReviewOutcome.APPLIED) 1 else 0, logs(db).count { it.cardId == original.id })
                assertEquals(ReviewOutcome.PAUSED, right.reviewCard(original.id, Rating.EASY, current.state.reps, 3000))
                metadataRetained(db, original.id)
            }
            assertEquals(logs(db).size, events(db).count { it.type == "review_completed" })
        } finally { db.close() }
    }

    @Test fun staleEditorRetainsNewReviewAndPauseAndOnlyContentInvalidatesExport() = runBlocking {
        val db = db()
        try {
            val original = card(); seed(db, original)
            db.cardLifecycleDao().review(original.id, Rating.GOOD, 0, 2000)
            db.cardLifecycleDao().setReviewPaused(original.id, true)
            val latest = saved(db)
            val edit = original.copy(content = original.content.copy(contextDefinition = "An edited synthetic definition."),
                state = FsrsState(dueAt = -1), reviewPaused = false, noteGuid = "wrong", ankiExportedAt = 9999)
            val result = db.cardLifecycleDao().edit(edit, 3000)!!
            assertEquals(latest.copy(content = edit.content, updatedAt = 3000, ankiExportedAt = null), result)
            assertEquals(1, logs(db).size)
            metadataRetained(db)
        } finally { db.close() }
    }

    @Test fun inaccuracyFlagAndNoopEditKeepContentVersionAndExportReceipt() = runBlocking {
        val db = db()
        try {
            val original = card().copy(reviewPaused = true); seed(db, original)
            val flagged = db.cardLifecycleDao().edit(original.copy(inaccurate = true, reviewPaused = false), 5000)!!
            assertEquals(original.copy(inaccurate = true), flagged)
            val before = db.dao().card(original.id)
            db.cardLifecycleDao().edit(flagged, 8000)
            assertEquals(before, db.dao().card(original.id))
            assertEquals(listOf("card_flagged_inaccurate"), events(db).map { it.type })
            assertTrue(logs(db).isEmpty())
        } finally { db.close() }
    }

    @Test fun sameMillisecondContentEditsStillInvalidateAnOlderExportVersion() = runBlocking {
        val db = db()
        try {
            val original = card(); seed(db, original)
            val edited = db.cardLifecycleDao().edit(original.copy(content = original.content.copy(paraphrase = "dependable")), original.updatedAt)!!
            assertEquals(original.updatedAt + 1, edited.updatedAt)
            assertEquals(0, db.cardLifecycleDao().confirmExport(mapOf(original.id to original.updatedAt), 5000))
            assertNull(saved(db).ankiExportedAt)
        } finally { db.close() }
    }

    @Test fun exportConfirmationPreservesNewPauseAndReviewAndRejectsNewerContent() = runBlocking {
        val db = db()
        try {
            val first = card("first"); val second = card("second"); seed(db, first); seed(db, second)
            db.cardLifecycleDao().review(first.id, Rating.GOOD, 0, 2000)
            db.cardLifecycleDao().setReviewPaused(first.id, true)
            db.cardLifecycleDao().edit(second.copy(content = second.content.copy(paraphrase = "dependable")), 3000)
            val before = saved(db, first.id)
            assertEquals(1, db.cardLifecycleDao().confirmExport(mapOf(first.id to first.updatedAt, second.id to second.updatedAt, "missing" to 0L), 5000))
            assertEquals(before.copy(ankiExportedAt = 5000), saved(db, first.id))
            assertNull(saved(db, second.id).ankiExportedAt)
            assertEquals(3, events(db).single { it.type == "anki_export_completed" }.value)
            metadataRetained(db, first.id); metadataRetained(db, second.id)
        } finally { db.close() }
    }

    @Test fun concurrentPauseAndContentEditFromDifferentRepositoriesRetainBoth() = runBlocking {
        val db = db()
        try {
            val left = LexiconRepository(context, db); val right = LexiconRepository(context, db)
            repeat(8) { index ->
                val original = card("edit-$index"); seed(db, original)
                val changed = original.content.copy(contextDefinition = "A revised synthetic meaning.")
                val gate = CompletableDeferred<Unit>()
                coroutineScope {
                    val pause = async { gate.await(); left.setCardReviewPaused(original.id, true) }
                    val edit = async { gate.await(); right.updateCard(original.copy(content = changed)) }
                    gate.complete(Unit); pause.await(); edit.await()
                }
                val current = saved(db, original.id)
                assertTrue(current.reviewPaused); assertEquals(changed, current.content)
                assertEquals(original.state, current.state); assertNull(current.ankiExportedAt)
                assertEquals(original.noteGuid, current.noteGuid); assertTrue(current.updatedAt > original.updatedAt)
                metadataRetained(db, original.id)
            }
        } finally { db.close() }
    }

    @Test fun concurrentPauseAndExportReceiptRetainBoth() = runBlocking {
        val db = db()
        try {
            repeat(8) { index ->
                val original = card("export-$index"); seed(db, original)
                val gate = CompletableDeferred<Unit>()
                coroutineScope {
                    val pause = async { gate.await(); db.cardLifecycleDao().setReviewPaused(original.id, true) }
                    val receipt = async { gate.await(); db.cardLifecycleDao().confirmExport(mapOf(original.id to original.updatedAt), 5000) }
                    gate.complete(Unit); pause.await(); assertEquals(1, receipt.await())
                }
                assertEquals(original.copy(reviewPaused = true, ankiExportedAt = 5000), saved(db, original.id))
            }
        } finally { db.close() }
    }

    @Test fun failedReviewLogWriteRollsBackScheduleAndCompletionEvent() = runBlocking {
        val db = db()
        try {
            val original = card(); val before = seed(db, original)
            db.useWriterConnection { connection ->
                connection.usePrepared("CREATE TRIGGER fail_lifecycle_log BEFORE INSERT ON review_logs BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END") { it.step() }
            }
            assertTrue(runCatching { db.cardLifecycleDao().review(original.id, Rating.GOOD, 0, 2000) }.isFailure)
            assertEquals(before, db.dao().card(original.id)); assertTrue(events(db).isEmpty()); assertTrue(logs(db).isEmpty())
        } finally { db.close() }
    }

    @Test fun failedExportEventWriteRollsBackReceiptAndKeepsPause() = runBlocking {
        val db = db()
        try {
            val original = card().copy(reviewPaused = true); val before = seed(db, original)
            db.useWriterConnection { connection ->
                connection.usePrepared("CREATE TRIGGER fail_lifecycle_event BEFORE INSERT ON local_events BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END") { it.step() }
            }
            assertTrue(runCatching { db.cardLifecycleDao().confirmExport(mapOf(original.id to original.updatedAt), 5000) }.isFailure)
            assertEquals(before, db.dao().card(original.id)); assertTrue(events(db).isEmpty())
        } finally { db.close() }
    }

    @Test fun exportRetainsPausedCardsWithoutChangingAnkiContentOrSchedule() = runBlocking {
        val folder = File(context.cacheDir, "lifecycle-export-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val original = card()
            val activePackage = LegacyAnkiExporter().export(listOf(original), File(folder, "active.apkg"))
            val pausedPackage = LegacyAnkiExporter().export(listOf(original.copy(reviewPaused = true)), File(folder, "paused.apkg"))
            fun tables(file: File, name: String): Map<String, List<String>> {
                val sqlite = File(folder, "$name.sqlite")
                ZipFile(file).use { zip -> sqlite.outputStream().use { out -> zip.getInputStream(zip.getEntry("collection.anki2")).use { it.copyTo(out) } } }
                return BundledSQLiteDriver().open(sqlite.absolutePath).use { connection ->
                    listOf("notes", "cards", "revlog").associateWith { table ->
                        connection.prepare("SELECT * FROM $table ORDER BY id").use { statement ->
                            buildList {
                                while (statement.step()) add((0 until statement.getColumnCount()).joinToString("\u001f") { statement.getText(it) })
                            }
                        }
                    }
                }
            }
            val active = tables(activePackage, "active")
            val paused = tables(pausedPackage, "paused")
            assertEquals(active, paused)
            assertEquals(1, paused.getValue("notes").size); assertEquals(1, paused.getValue("cards").size)
            assertTrue(paused.getValue("revlog").isEmpty())
            assertEquals(LegacyAnkiExporter.fields(original), LegacyAnkiExporter.fields(original.copy(reviewPaused = true)))
            assertEquals(LegacyAnkiExporter.tags(original), LegacyAnkiExporter.tags(original.copy(reviewPaused = true)))
        } finally { folder.deleteRecursively() }
    }
}
