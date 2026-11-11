package dev.lexicon.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Self-authored material in an isolated Room database. No production learning data or API calls. */
@RunWith(AndroidJUnit4::class)
class CaptureInboxStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val codec = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val source = Source("inbox-source", "Synthetic source", "https://example.invalid/feed", aiAllowed = true)
    private val article = Article("inbox-article", "fixture", listOf(source.id), "Shared reading", "https://example.invalid/article",
        "Synthetic summary", sourceName = source.name, topic = "Education")
    private val text = "Residents compare available books in a shared reading room."
    private val body = ArticleBody(article.id, text, sha256(text), 0, Long.MAX_VALUE, "Self-authored fixture")
    private val input = Capture("inbox-capture", article.id, "Residents", "Residents", text, ContextOrigin.ORIGINAL,
        SourceRef(article.title, source.name, article.url), 0, 9, body.contentHash, 100)
    private val content = VocabularyContent("resident", "A person who lives near this reading room.",
        listOf(Collocation("local residents", "Local residents compare books."), Collocation("nearby residents", "Nearby residents use this room.")),
        "A person living in the area.", "neutral", "medium")
    private val result = AnalysisResult(content, "synthetic/no-network")
    private val study = ArticleStudy(article.id, body.contentHash, "A synthetic overview", "B2",
        listOf(MindMapNode("root", null, "Reading")),
        listOf(StudyHighlight("residents", "Residents", 0, 9, "vocabulary", "Synthetic explanation")),
        listOf(StudyParagraph(0, "example", "A reading room")), emptyList(), emptyList(), result.model, 100, schemaVersion = 2)

    private fun database() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).build()
    private fun inbox(db: LexiconDatabase, now: Long = 500) = CaptureInboxStore(db.captureInboxDao()) { now }
    private fun analyses(db: LexiconDatabase) = CaptureAnalysisStore(db.captureAnalysisDao(), db.vocabularyDao()) { 600 }
    private suspend fun putCapture(db: LexiconDatabase, capture: Capture) {
        db.dao().putCapture(CaptureRow(capture.id, capture.createdAt, codec.encodeToString(capture)))
    }
    private suspend fun seed(db: LexiconDatabase, capture: Capture = input) {
        db.dao().putSource(SourceRow(source.id, codec.encodeToString(source)))
        db.dao().putArticle(ArticleRow(article.id, article.url, 0, codec.encodeToString(article)))
        db.dao().putBody(BodyRow(body.articleId, body.expiresAt, codec.encodeToString(body)))
        putCapture(db, capture)
    }
    private fun proposed(capture: Capture) = Card("card:${capture.id}", capture.id, content, capture.sentence, capture.sourceRef,
        state = FsrsState(dueAt = 600), createdAt = 600, contextOrigin = capture.contextOrigin)

    @Test fun legacyCapturesRemainVisibleAndMissingIdsDoNotWriteMetadata() = runBlocking {
        val db = database()
        try {
            seed(db)
            val before = snapshot(db)
            assertTrue(inbox(db).archivedCaptureIds.first().isEmpty())
            assertEquals(listOf(input.id), db.dao().observeCaptures().first().map { it.id })
            assertFalse(inbox(db).setArchived("missing", true))
            assertFalse(inbox(db).setArchived("missing", false))
            assertEquals(before, snapshot(db))
        } finally { db.close() }
    }

    @Test fun archiveAndRestorePreserveAllRawMaterialCardsJobsAndCachedAnalysis() = runBlocking {
        val db = database()
        try {
            seed(db)
            val raw = JsonObject(codec.parseToJsonElement(codec.encodeToString(input)).jsonObject +
                ("retained_extra" to JsonPrimitive("Legacy metadata"))).toString()
            db.dao().putCapture(CaptureRow(input.id, 42, raw))
            analyses(db).persist(input, result, false)
            val card = proposed(input)
            db.dao().putCard(CardRow(card.id, card.captureId, card.state.dueAt, 0, card.updatedAt, codec.encodeToString(card)))
            val job = AiJob("existing-job", "vocabulary", input.id, "running", 100, 200)
            db.dao().putJob(JobRow(job.id, job.status, job.updatedAt, codec.encodeToString(job)))
            db.dao().putPreference(PreferenceRow("unrelated", "keep these exact bytes"))
            val before = snapshot(db)

            assertTrue(inbox(db).setArchived(input.id, true))
            val archiveRow = db.dao().preference(CaptureInboxMetadata.key(input.id))
            assertEquals(setOf(input.id), inbox(db).archivedCaptureIds.first())
            assertEquals(before, withoutArchiveRows(snapshot(db)))
            // Repeating the same decision must not rewrite its original timestamp.
            assertTrue(inbox(db, 900).setArchived(input.id, true))
            assertEquals(archiveRow, db.dao().preference(CaptureInboxMetadata.key(input.id)))
            assertEquals(before, withoutArchiveRows(snapshot(db)))

            assertTrue(inbox(db).setArchived(input.id, false))
            assertTrue(inbox(db).archivedCaptureIds.first().isEmpty())
            assertEquals(before, snapshot(db))
        } finally { db.close() }
    }

    @Test fun concurrentDecisionsForDifferentCapturesDoNotOverwriteEachOther() = runBlocking {
        val db = database()
        try {
            seed(db)
            val ids = (0 until 16).map { "parallel-$it" }
            ids.forEach { putCapture(db, input.copy(id = it)) }
            val before = snapshot(db)
            coroutineScope {
                val start = CompletableDeferred<Unit>()
                val writes = ids.map { id -> async { start.await(); inbox(db).setArchived(id, true) } }
                start.complete(Unit)
                assertTrue(writes.awaitAll().all { it })
            }
            assertEquals(ids.toSet(), inbox(db).archivedCaptureIds.first())
            assertEquals(before, withoutArchiveRows(snapshot(db)))
            coroutineScope {
                ids.mapIndexed { index, id -> async { inbox(db).setArchived(id, index % 2 == 0) } }.awaitAll()
            }
            assertEquals(ids.filterIndexed { index, _ -> index % 2 == 0 }.toSet(), inbox(db).archivedCaptureIds.first())
            assertEquals(before, withoutArchiveRows(snapshot(db)))
        } finally { db.close() }
    }

    @Test fun corruptedOrMismatchedMetadataDoesNotHideCapturesOrRewriteItself() = runBlocking {
        val db = database()
        try {
            seed(db)
            val broken = input.copy(id = "broken"); val mismatched = input.copy(id = "mismatched")
            putCapture(db, broken); putCapture(db, mismatched)
            assertTrue(inbox(db).setArchived(input.id, true))
            val corrupt = PreferenceRow(CaptureInboxMetadata.key(broken.id), "{\"version\":1,")
            val conflict = PreferenceRow(CaptureInboxMetadata.key(mismatched.id), CaptureInboxMetadata.encode(broken.id, 500))
            db.dao().putPreference(corrupt); db.dao().putPreference(conflict)
            val before = snapshot(db)
            assertEquals(setOf(input.id), inbox(db).archivedCaptureIds.first())
            assertEquals(before, snapshot(db))
            assertEquals(setOf(input.id, broken.id, mismatched.id), db.dao().observeCaptures().first().map { it.id }.toSet())
            // The user can explicitly archive or restore the affected item; observation alone cannot repair it.
            assertTrue(inbox(db).setArchived(broken.id, true))
            assertEquals(setOf(input.id, broken.id), inbox(db).archivedCaptureIds.first())
            assertEquals(conflict, db.dao().preference(conflict.key))
            assertTrue(inbox(db).setArchived(mismatched.id, false))
            assertNull(db.dao().preference(conflict.key))
            assertEquals(withoutArchiveRows(before), withoutArchiveRows(snapshot(db)))
        } finally { db.close() }
    }

    @Test fun savingEditedCaptureThroughTheRealRepositoryDoesNotRestoreItToTheInbox() = runBlocking {
        val db = database()
        val folder = File(context.cacheDir, "capture-inbox-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(folder, "no_backup").apply { check(isDirectory || mkdirs()) }
        }
        var modelCalls = 0
        val fake = object : LearningAiService {
            override suspend fun analyze(capture: Capture, deep: Boolean, dictionary: List<DictionaryEntry>): AnalysisResult {
                modelCalls++; error("Saving a capture must not request analysis")
            }
            override suspend fun classify(inputs: List<TopicInput>): List<TopicResult> = error("No classification requested")
        }
        try {
            val repository = LexiconRepository(isolated, db, fake)
            repository.initialize()
            seed(db)
            assertTrue(requireNotNull(repository.captureInbox).setArchived(input.id, true))
            val archived = db.dao().preference(CaptureInboxMetadata.key(input.id))
            val edited = input.copy(sentence = "Residents choose books for their shared reading room.",
                contextOrigin = ContextOrigin.USER_SUPPLIED, updatedAt = 900)
            repository.saveCapture(edited)
            assertEquals(edited, codec.decodeFromString<Capture>(requireNotNull(db.dao().capture(input.id)).data))
            assertEquals(archived, db.dao().preference(CaptureInboxMetadata.key(input.id)))
            assertEquals(setOf(input.id), requireNotNull(repository.captureInbox).archivedCaptureIds.first())
            assertEquals(listOf(edited), repository.captures.first())
            assertTrue(db.dao().allCards().isEmpty()); assertTrue(db.dao().observeJobs().first().isEmpty())
            assertEquals(0, modelCalls)
            assertFalse(File(isolated.noBackupFilesDir, "deepseek-key.aesgcm").exists())
            assertTrue(File(isolated.noBackupFilesDir, "dictionary").listFiles().orEmpty().isEmpty())
        } finally { db.close(); folder.deleteRecursively() }
    }

    @Test fun archivedCaptureCanUseItsExactCachedAnalysisWithoutLosingTheDecision() = runBlocking {
        val db = database()
        try {
            seed(db)
            val original = db.dao().capture(input.id)
            val receipt = analyses(db).persist(input, result, false)
            val cachedRow = db.dao().preference(CaptureAnalysisDao.key(input, false))
            assertTrue(inbox(db).setArchived(input.id, true))
            val archived = db.dao().preference(CaptureInboxMetadata.key(input.id))
            val available = analyses(db).available(input) as CaptureAnalysisAvailability.Ready
            assertEquals(receipt, available.receiptId)
            assertEquals(VocabularyCardInput.analysisSignature(input), available.analysis.inputSignature)
            val card = analyses(db).save(input, receipt)
            assertEquals(content, card.content)
            assertEquals(original, db.dao().capture(input.id))
            assertEquals(cachedRow, db.dao().preference(CaptureAnalysisDao.key(input, false)))
            assertEquals(archived, db.dao().preference(CaptureInboxMetadata.key(input.id)))
            assertEquals(setOf(input.id), inbox(db).archivedCaptureIds.first())
            assertTrue(db.dao().observeJobs().first().isEmpty())
        } finally { db.close() }
    }

    @Test fun archivingDuringAnAuthorizedQueueRunDoesNotCancelItOrBecomeUndoneOnCommit() = runBlocking {
        val db = database()
        try {
            seed(db)
            db.dao().putPreference(PreferenceRow(ReadingStudyScopes.cacheKey(article.id, body.contentHash, study.schemaVersion, study.scope), codec.encodeToString(study)))
            val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            var modelCalls = 0
            fun queue() = LocalVocabularyLearningRepository(db,
                analyze = {
                    modelCalls++; entered.complete(Unit); finish.await(); result
                },
                saveCard = { capture, analysis -> db.vocabularyDao().commitCard(capture, proposed(capture).copy(content = analysis.content)) },
                hasKey = { true }, clock = { 500 })
            val repository = queue()
            val candidate = VocabularyLearning.candidates(article, body, study, true).single()
            val prepared = repository.prepareBatch(article, body, study, listOf(candidate.id))
            val actual = prepared.items.single().candidate.capture
            val pending = async { repository.runBatch(prepared.id) }
            try {
                withTimeout(5_000) { entered.await() }
                val before = snapshot(db)
                assertTrue(inbox(db).setArchived(actual.id, true))
                val archived = db.dao().preference(CaptureInboxMetadata.key(actual.id))
                assertEquals(before, withoutArchiveRows(snapshot(db)))
                assertTrue(pending.isActive)
                finish.complete(Unit)
                withTimeout(10_000) { pending.await() }
                assertEquals(VocabularyItemStatus.COMPLETED, repository.library.first().batches.single().items.single().status)
                assertEquals(1, db.dao().allCards().size)
                assertEquals(archived, db.dao().preference(CaptureInboxMetadata.key(actual.id)))
                assertEquals(setOf(actual.id), inbox(db).archivedCaptureIds.first())
                assertEquals(VocabularyCardInput.signature(actual), db.dao().preference("card-input:${actual.id}")?.data)
                // Explicitly revisiting a completed batch reuses its card and cannot change organization metadata.
                queue().runBatch(prepared.id)
                assertEquals(1, modelCalls)
                assertEquals(archived, db.dao().preference(CaptureInboxMetadata.key(actual.id)))
            } finally { pending.cancelAndJoin() }
        } finally { db.close() }
    }

    private suspend fun snapshot(db: LexiconDatabase): Map<String, List<List<String?>>> = db.useReaderConnection { connection ->
        val names = connection.usePrepared("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name") { statement ->
            buildList { while (statement.step()) add(statement.getText(0)) }
        }
        names.associateWith { table ->
            val quoted = "\"" + table.replace("\"", "\"\"") + "\""
            connection.usePrepared("SELECT * FROM $quoted ORDER BY 1") { statement ->
                buildList {
                    while (statement.step()) add((0 until statement.getColumnCount()).map { index ->
                        if (statement.isNull(index)) null else statement.getText(index)
                    })
                }
            }
        }
    }
    private fun withoutArchiveRows(value: Map<String, List<List<String?>>>): Map<String, List<List<String?>>> =
        value.toMutableMap().apply { put("preferences", value.getValue("preferences").filterNot { it[0]?.startsWith(CaptureInboxMetadata.PREFIX) == true }) }
}
