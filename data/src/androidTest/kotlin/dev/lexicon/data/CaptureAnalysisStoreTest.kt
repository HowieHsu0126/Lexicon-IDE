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
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Self-authored input in isolated Room databases. No key store, HTTP client or model is constructed. */
@RunWith(AndroidJUnit4::class)
class CaptureAnalysisStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val codec = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val source = Source("analysis-fixture", "Synthetic publication", "https://example.org/feed", aiAllowed = true)
    private val article = Article("analysis-article", "fixture", listOf(source.id), "A reading room", "https://example.org/reading",
        "Synthetic summary", sourceName = source.name, topic = "Education")
    private val text = "Residents compare available books in a shared reading room."
    private val input = Capture("analysis-capture", article.id, "Residents", "Residents", text, ContextOrigin.ORIGINAL,
        SourceRef(article.title, article.sourceName, article.url), 0, 9, sha256(text), 100)
    private val content = VocabularyContent("resident", "A person who lives in this area.",
        listOf(Collocation("local residents", "Local residents compare books."), Collocation("nearby residents", "Nearby residents visit the room.")),
        "A person living in the area.", "neutral", "medium")
    private val result = AnalysisResult(content, "synthetic/no-network")
    private fun database() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).build()
    private fun store(db: LexiconDatabase, now: Long = 500) = CaptureAnalysisStore(db.captureAnalysisDao(), db.vocabularyDao()) { now }
    private suspend fun saveCapture(db: LexiconDatabase, capture: Capture) {
        db.dao().putCapture(CaptureRow(capture.id, capture.createdAt, codec.encodeToString(capture)))
    }
    private suspend fun seed(db: LexiconDatabase, capture: Capture = input) {
        db.dao().putSource(SourceRow(source.id, codec.encodeToString(source)))
        db.dao().putArticle(ArticleRow(article.id, article.url, 0, codec.encodeToString(article)))
        saveCapture(db, capture)
        // This guard also makes accidental future integration through the real repository refuse new work.
        db.dao().putPreference(PreferenceRow("ai_paused", "true"))
    }
    private fun candidate(): VocabularyCandidate {
        val body = ArticleBody(article.id, text, sha256(text), 0, 1_000, "Synthetic fixture")
        val study = ArticleStudy(article.id, body.contentHash, "A synthetic overview", "B2", listOf(MindMapNode("root", null, "Reading")),
            listOf(StudyHighlight("residents", "Residents", 0, 9, "vocabulary", "Synthetic explanation")),
            listOf(StudyParagraph(0, "example", "A reading room")), emptyList(), emptyList(), "synthetic/no-network", 100, schemaVersion = 2)
        return VocabularyLearning.candidates(article, body, study, true).single()
    }
    private suspend fun fails(code: String, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (failure: ApiFailure) { assertEquals(code, failure.code) }
    }
    private suspend fun ready(store: CaptureAnalysisStore, capture: Capture = input): CaptureAnalysisAvailability.Ready =
        store.available(capture) as CaptureAnalysisAvailability.Ready
    private suspend fun assertNoWork(db: LexiconDatabase) {
        assertTrue(db.dao().allCards().isEmpty())
        assertTrue(db.dao().observeJobs().first().isEmpty())
        assertTrue(db.dao().allEvents().isEmpty())
        assertEquals("true", db.dao().preference("ai_paused")?.data)
    }

    @Test fun missingProbeDoesNotCreateCapturesJobsEventsOrAnalysisRows() = runBlocking {
        val db = database()
        try {
            db.dao().putPreference(PreferenceRow("ai_paused", "true"))
            assertEquals(CaptureAnalysisAvailability.Missing, store(db).available(input))
            assertNull(db.dao().capture(input.id))
            assertNull(db.dao().preference(CaptureAnalysisDao.key(input, false)))
            assertNoWork(db)
        } finally { db.close() }
    }

    @Test fun normalAnalysisSurvivesStoreRecreationAndSavesWithoutKeyOrNewWork() = runBlocking {
        val db = database()
        try {
            seed(db); val original = db.dao().capture(input.id)
            val receipt = store(db).persist(input, result, false)
            val restored = store(db, 700); val available = ready(restored)
            assertEquals(receipt, available.receiptId); assertEquals(result.content, available.analysis.content)
            assertEquals(VocabularyCardInput.analysisSignature(input), available.analysis.inputSignature)
            assertFalse(available.deep); assertNoWork(db)
            val saved = restored.save(input, receipt)
            assertEquals(input.sentence, saved.sourceSentence); assertEquals(input.sourceRef, saved.sourceRef)
            assertEquals("Education", saved.topic); assertEquals(700L, saved.state.dueAt)
            assertEquals(original, db.dao().capture(input.id)); assertTrue(db.dao().observeJobs().first().isEmpty())
            assertEquals(listOf("card_saved"), db.dao().allEvents().map { codec.decodeFromString<LocalEvent>(it.data).type })
        } finally { db.close() }
    }

    @Test fun deepAnalysisIsRetainedAndNewestResultIsSelectedWithoutGenerating() = runBlocking {
        val db = database()
        try {
            seed(db)
            store(db, 500).persist(input, result, false)
            val deeper = result.copy(content = content.copy(contextDefinition = "A local person using the shared reading room."), model = "synthetic/deep")
            val receipt = store(db, 600).persist(input, deeper, true)
            val available = ready(store(db, 700))
            assertTrue(available.deep); assertEquals(deeper.content, available.analysis.content); assertEquals(deeper.model, available.analysis.model)
            assertEquals(receipt, available.receiptId); assertNoWork(db)
        } finally { db.close() }
    }

    @Test fun externalUserSentenceKeepsUnassociatedSourceAndCanSaveLocally() = runBlocking {
        val db = database()
        try {
            val external = input.copy(id = "external", articleId = null, sourceRef = SourceRef(), contextOrigin = ContextOrigin.USER_SUPPLIED,
                bodyHash = null, selectionStart = null, selectionEnd = null)
            seed(db, external); val receipt = store(db).persist(external, result, true)
            val saved = store(db).save(external, receipt)
            assertEquals(SourceRef(), saved.sourceRef); assertEquals("未关联来源", saved.sourceRef.display())
            assertEquals(ContextOrigin.USER_SUPPLIED, saved.contextOrigin); assertNull(saved.topic)
            assertTrue(db.dao().observeJobs().first().isEmpty())
        } finally { db.close() }
    }

    @Test fun everySemanticInputChangeInvalidatesTheOldResultWithoutDeletingIt() = runBlocking {
        val db = database()
        try {
            seed(db); store(db).persist(input, result, false)
            val key = CaptureAnalysisDao.key(input, false); val original = db.dao().preference(key)
            val variants = listOf(input.copy(target = "local residents"), input.copy(selectedText = "Residents compare"),
                input.copy(sentence = "Residents select books."), input.copy(sourceRef = input.sourceRef.copy(title = "A different title")),
                input.copy(contextOrigin = ContextOrigin.USER_SUPPLIED), input.copy(bodyHash = sha256("Different body")),
                input.copy(selectionStart = 1, selectionEnd = 10), input.copy(articleId = null))
            variants.forEach { changed ->
                saveCapture(db, changed)
                assertEquals(CaptureAnalysisAvailability.Missing, store(db).available(changed))
                assertEquals(CaptureAnalysisAvailability.Conflict, store(db).available(input))
                assertEquals(original, db.dao().preference(key))
            }
            assertNoWork(db)
        } finally { db.close() }
    }

    @Test fun metadataTimestampChangesDoNotInvalidateTheSameExactInput() = runBlocking {
        val db = database()
        try {
            seed(db); val receipt = store(db).persist(input, result, false)
            val later = input.copy(createdAt = 200, updatedAt = 400)
            saveCapture(db, later)
            assertEquals(receipt, ready(store(db), later).receiptId)
            assertNoWork(db)
        } finally { db.close() }
    }

    @Test fun editAfterPreviewRefusesCommitAndNeverRestoresTheOldCapture() = runBlocking {
        val db = database()
        try {
            seed(db); val receipt = store(db).persist(input, result, false)
            val changed = input.copy(sentence = "Residents choose another book.", updatedAt = 900)
            saveCapture(db, changed); val row = db.dao().capture(input.id)
            fails("capture_changed") { store(db).save(input, receipt) }
            assertEquals(row, db.dao().capture(input.id)); assertNoWork(db)
            assertNotNull(db.dao().preference(CaptureAnalysisDao.key(input, false)))
        } finally { db.close() }
    }

    @Test fun sourceRevocationAfterPreviewBlocksCommitWhileRetainingTheAnalysis() = runBlocking {
        val db = database()
        try {
            seed(db); val receipt = store(db).persist(input, result, false)
            db.dao().putSource(SourceRow(source.id, codec.encodeToString(source.copy(aiAllowed = false))))
            assertEquals(CaptureAnalysisAvailability.Restricted, store(db).available(input))
            fails("source_restricted") { store(db).save(input, receipt) }
            assertNoWork(db); assertNotNull(db.dao().preference(CaptureAnalysisDao.key(input, false)))
            db.dao().putSource(SourceRow(source.id, codec.encodeToString(source)))
            assertEquals(receipt, ready(store(db)).receiptId)
        } finally { db.close() }
    }

    @Test fun unresolvableArticleDoesNotFallBackToStaleCapturePermission() = runBlocking {
        val db = database()
        try {
            val missing = input.copy(articleId = "missing-article", aiAllowed = true)
            seed(db, missing); store(db).persist(missing, result, false)
            assertEquals(CaptureAnalysisAvailability.Restricted, store(db).available(missing)); assertNoWork(db)
        } finally { db.close() }
    }

    @Test fun duplicateConcurrentSavesProduceOneCardAndOneSaveEvent() = runBlocking {
        val db = database()
        try {
            seed(db); val receipt = store(db).persist(input, result, false)
            val saved = coroutineScope { (1..3).map { async { store(db).save(input, receipt) } }.awaitAll() }
            assertEquals(1, saved.distinct().size); assertEquals(1, db.dao().allCards().size)
            assertEquals(1, db.dao().allEvents().size)
            val existing = store(db).available(input) as CaptureAnalysisAvailability.ExistingCard
            assertTrue(existing.matchesInput); assertEquals(saved.first(), existing.card)
        } finally { db.close() }
    }

    @Test fun aChangedInputOffersItsExistingCardWithoutClaimingAMatchOrCreatingAnother() = runBlocking {
        val db = database()
        try {
            seed(db); val receipt = store(db).persist(input, result, false); val saved = store(db).save(input, receipt)
            val changed = input.copy(target = "books"); saveCapture(db, changed)
            val existing = store(db).available(changed) as CaptureAnalysisAvailability.ExistingCard
            assertFalse(existing.matchesInput); assertEquals(saved, existing.card)
            fails("capture_changed") { store(db).save(changed, receipt) }
            assertEquals(1, db.dao().allCards().size); assertEquals(1, db.dao().allEvents().size)
        } finally { db.close() }
    }

    @Test fun equalGeneratedContentForDifferentInputsRemainsIndependentlySaveable() = runBlocking {
        val db = database()
        try {
            seed(db)
            val another = input.copy(id = "another-capture", sentence = "Residents share a book.", bodyHash = sha256("Another snapshot"))
            saveCapture(db, another)
            val first = store(db).persist(input, result, false); val second = store(db).persist(another, result, false)
            assertNotEquals(first, second)
            store(db).save(another, second); store(db).save(input, first)
            assertEquals(2, db.dao().allCards().size)
        } finally { db.close() }
    }

    @Test fun changedAnalysisReceiptRequiresAnotherPreviewBeforeSaving() = runBlocking {
        val db = database()
        try {
            seed(db); val old = store(db, 500).persist(input, result, false)
            val newer = result.copy(content = content.copy(contextDefinition = "A person living near the reading room."))
            val latest = store(db, 600).persist(input, newer, false)
            fails("analysis_changed") { store(db).save(input, old) }; assertNoWork(db)
            assertEquals(newer.content, store(db).save(input, latest).content)
        } finally { db.close() }
    }

    @Test fun malformedStoredResultRemainsUntouchedAndDoesNotTriggerAnyFallbackRequest() = runBlocking {
        val db = database()
        try {
            seed(db); val key = CaptureAnalysisDao.key(input, true)
            val malformed = PreferenceRow(key, "{\"version\":1,\"content\":")
            db.dao().putPreference(malformed)
            fails("analysis_damaged") { store(db).available(input) }
            fails("analysis_damaged") { store(db).persist(input, result, true) }
            assertEquals(malformed, db.dao().preference(key)); assertNoWork(db)
        } finally { db.close() }
    }

    @Test fun queueAnalysisIsAvailableAndCommittedWithoutPromotingOrChangingItsRecord() = runBlocking {
        val db = database()
        try {
            val candidate = candidate()
            seed(db, candidate.capture)
            val batch = VocabularyBatch(VocabularyLearning.batchId(listOf(candidate)), article.id, requireNotNull(candidate.capture.bodyHash), candidate.capture.sourceRef,
                listOf(VocabularyQueueItem(candidate, VocabularyItemStatus.FAILED, errorCode = "queue_failed", content = content, model = result.model)), 100, 500)
            val row = PreferenceRow(LocalVocabularyLearningRepository.BATCH_PREFIX + batch.id, codec.encodeToString(batch)); db.dao().putPreference(row)
            val available = ready(store(db), candidate.capture)
            assertEquals(content, available.analysis.content); assertNoWork(db)
            val card = store(db).save(candidate.capture, available.receiptId)
            assertEquals(content, card.content); assertEquals(row, db.dao().preference(row.key))
            assertNull(db.dao().preference(CaptureAnalysisDao.key(candidate.capture, false)))
            assertTrue(db.dao().observeJobs().first().isEmpty())
        } finally { db.close() }
    }

    @Test fun cardCommitFailureRollsBackLocalWritesAndKeepsThePersistedAnalysis() = runBlocking {
        val db = database()
        try {
            seed(db); val receipt = store(db).persist(input, result, false); val original = db.dao().capture(input.id)
            db.useWriterConnection { connection ->
                connection.usePrepared("CREATE TRIGGER fail_receipt_card BEFORE INSERT ON preferences WHEN NEW.key LIKE 'card-input:%' BEGIN SELECT RAISE(ABORT, 'synthetic commit failure'); END") { it.step() }
            }
            assertTrue(runCatching { store(db).save(input, receipt) }.isFailure)
            assertEquals(original, db.dao().capture(input.id)); assertNoWork(db)
            assertEquals(receipt, ready(store(db)).receiptId)
        } finally { db.close() }
    }

    @Test fun invalidModelOutputOrAnotherInputBindingCannotCreateAPersistedReceipt() = runBlocking {
        val db = database()
        try {
            seed(db)
            listOf(result.copy(content = content.copy(collocations = emptyList())), result.copy(model = ""),
                result.copy(inputSignature = VocabularyCardInput.analysisSignature(input.copy(target = "books")))).forEach { invalid ->
                assertTrue(runCatching { store(db).persist(input, invalid, false) }.isFailure)
            }
            assertNull(db.dao().preference(CaptureAnalysisDao.key(input, false))); assertNoWork(db)
        } finally { db.close() }
    }

    @Test fun exactCardSignatureRejectsChangedNativeRangeEvenWithUnchangedLegacySignature() = runBlocking {
        val db = database()
        try {
            seed(db); val receipt = store(db).persist(input, result, false); val saved = store(db).save(input, receipt)
            assertEquals(VocabularyCardInput.analysisSignature(input), db.dao().preference("card-analysis-input:${input.id}")?.data)
            val moved = input.copy(selectionStart = 1, selectionEnd = 10); saveCapture(db, moved)
            assertEquals(VocabularyCardInput.signature(input), VocabularyCardInput.signature(moved))
            val available = store(db).available(moved) as CaptureAnalysisAvailability.ExistingCard
            assertFalse(available.matchesInput); assertEquals(saved.id, available.card.id)
            fails("capture_changed") { store(db).save(moved, receipt) }
            assertEquals(1, db.dao().allCards().size)
        } finally { db.close() }
    }

    @Test fun legacyNativeCardNeedsAGeneratedCompletedQueueReceiptForExactInputProof() = runBlocking {
        val db = database()
        try {
            val candidate = candidate(); val frozen = candidate.capture
            seed(db, frozen)
            val card = Card("legacy-card", frozen.id, content, frozen.sentence, frozen.sourceRef, createdAt = 300)
            db.dao().putCard(CardRow(card.id, card.captureId, card.state.dueAt, card.state.reps, card.updatedAt, codec.encodeToString(card)))
            db.dao().putPreference(PreferenceRow("card-input:${frozen.id}", VocabularyCardInput.signature(frozen)))
            assertFalse((store(db).available(frozen) as CaptureAnalysisAvailability.ExistingCard).matchesInput)
            val item = VocabularyQueueItem(candidate, VocabularyItemStatus.COMPLETED, card.id, content = content, model = result.model)
            val batch = VocabularyBatch(VocabularyLearning.batchId(listOf(candidate)), article.id, requireNotNull(frozen.bodyHash), frozen.sourceRef,
                listOf(item), 100, 500)
            val row = PreferenceRow(LocalVocabularyLearningRepository.BATCH_PREFIX + batch.id, codec.encodeToString(batch)); db.dao().putPreference(row)
            assertTrue((store(db).available(frozen) as CaptureAnalysisAvailability.ExistingCard).matchesInput)
            val emptyReceipt = row.copy(data = codec.encodeToString(batch.copy(items = listOf(item.copy(content = null, model = null)))))
            db.dao().putPreference(emptyReceipt)
            assertFalse((store(db).available(frozen) as CaptureAnalysisAvailability.ExistingCard).matchesInput)
            assertEquals(emptyReceipt, db.dao().preference(row.key)); assertTrue(db.dao().allEvents().isEmpty())
        } finally { db.close() }
    }

    @Test fun realRepositoryPersistsBothNormalAndDeepResultsBeforeReportingCompleted() = runBlocking {
        for (deep in listOf(false, true)) {
            val db = database()
            try {
                seed(db); db.dao().putPreference(PreferenceRow("ai_paused", "false"))
                var requests = 0
                val fake = object : LearningAiService {
                    override suspend fun analyze(capture: Capture, deep: Boolean, dictionary: List<DictionaryEntry>): AnalysisResult {
                        requests++; return result.copy(model = if (deep) "synthetic/deep" else "synthetic/normal")
                    }
                    override suspend fun classify(inputs: List<TopicInput>): List<TopicResult> = error("No classification request is expected")
                }
                val repository = LexiconRepository(context, db, learningAi = fake)
                val generated = repository.analyzeCapture(input, deep)
                assertEquals(1, requests)
                val beforeJobs = db.dao().observeJobs().first()
                assertEquals("completed", beforeJobs.single().status)
                assertNotNull(db.dao().preference(CaptureAnalysisDao.key(input, deep)))
                db.dao().putPreference(PreferenceRow("ai_paused", "true"))
                val rebuilt = LexiconRepository(context, db, learningAi = object : LearningAiService {
                    override suspend fun analyze(capture: Capture, deep: Boolean, dictionary: List<DictionaryEntry>): AnalysisResult = error("Restoring must not run AI")
                    override suspend fun classify(inputs: List<TopicInput>): List<TopicResult> = error("Restoring must not classify")
                })
                val available = rebuilt.availableCaptureAnalysis(input) as CaptureAnalysisAvailability.Ready
                assertEquals(deep, available.deep); assertEquals(generated, available.analysis)
                val saved = rebuilt.saveAvailableCaptureAnalysis(input, available.receiptId)
                assertEquals(generated.content, saved.content); assertEquals(beforeJobs, db.dao().observeJobs().first())
                assertEquals(1, requests)
            } finally { db.close() }
        }
    }

    @Test fun aFreshQueueUsesPersistedOrdinaryDeepAnalysisWithoutAKeyOrModelCall() = runBlocking {
        val db = database()
        try {
            val candidate = candidate(); val frozen = candidate.capture
            seed(db, frozen)
            val deepResult = result.copy(model = "synthetic/deep")
            store(db).persist(frozen, deepResult, true)
            val batch = VocabularyBatch(VocabularyLearning.batchId(listOf(candidate)), article.id, requireNotNull(frozen.bodyHash), frozen.sourceRef,
                listOf(VocabularyQueueItem(candidate)), 100)
            db.dao().putPreference(PreferenceRow(LocalVocabularyLearningRepository.BATCH_PREFIX + batch.id, codec.encodeToString(batch)))
            var calls = 0; var saves = 0
            val queue = LocalVocabularyLearningRepository(db,
                analyze = { calls++; error("An existing deep result must prevent a model call") },
                saveCard = { capture, analysis ->
                    saves++
                    assertEquals(deepResult.content, analysis.content); assertEquals(deepResult.model, analysis.model)
                    db.vocabularyDao().commitCard(capture, Card("queue-card", capture.id, analysis.content, capture.sentence,
                        capture.sourceRef, createdAt = 700, contextOrigin = capture.contextOrigin))
                }, hasKey = { false }, clock = { 700 })
            queue.runBatch(batch.id)
            assertEquals(0, calls); assertEquals(1, saves)
            val completed = queue.library.first().batches.single().items.single()
            assertEquals(VocabularyItemStatus.COMPLETED, completed.status)
            assertEquals(deepResult.content, completed.content); assertEquals(deepResult.model, completed.model)
            assertTrue(db.dao().observeJobs().first().isEmpty())
        } finally { db.close() }
    }
}
