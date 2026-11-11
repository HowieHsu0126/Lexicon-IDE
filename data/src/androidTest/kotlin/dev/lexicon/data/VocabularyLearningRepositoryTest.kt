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
import java.io.IOException

/** Synthetic in-memory fixtures; no production database, dictionary, key or model request. */
@RunWith(AndroidJUnit4::class)
class VocabularyLearningRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val json = Json { encodeDefaults = true }
    private val now = 1000L
    private val source = Source("vocab-fixture", "Synthetic fixture", "https://example.org/feed", aiAllowed = true)
    private val article = Article("vocab-article", "fixture", listOf(source.id), "A shared reading room", "https://example.org/room", "Unrelated summary", sourceName = source.name)
    private val text = "Residents compare available books in a shared reading room.\n\nVolunteers discuss opening hours with the families."
    private val body = ArticleBody(article.id, text, sha256(text), 0, 100_000, "Self-authored fixture")
    private val study = ArticleStudy(article.id, body.contentHash, "A synthetic overview", "B2", listOf(MindMapNode("root", null, "Reading")),
        listOf(highlight("residents", "Residents"), highlight("opening-hours", "opening hours", "collocation")),
        listOf(StudyParagraph(0, "example", "First"), StudyParagraph(1, "example", "Second")),
        emptyList(), emptyList(), "synthetic/no-network", 900, schemaVersion = 2)
    private fun highlight(id: String, quote: String, kind: String = "vocabulary") = text.indexOf(quote).let {
        StudyHighlight(id, quote, it, it + quote.length, kind, "Synthetic explanation")
    }
    private val candidates get() = VocabularyLearning.candidates(article, body, study, true)
    private val ids get() = candidates.map { it.id }
    private fun db() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).build()
    private suspend fun seed(db: LexiconDatabase) {
        db.dao().putSource(SourceRow(source.id, json.encodeToString(source)))
        db.dao().putArticle(ArticleRow(article.id, article.url, 0, json.encodeToString(article)))
        db.dao().putBody(BodyRow(article.id, body.expiresAt, json.encodeToString(body)))
        db.readingDao().put(PreferenceRow("reading-cache:study:2:${sha256(article.id)}:${body.contentHash}", json.encodeToString(study)))
    }
    private fun content(target: String) = VocabularyContent(target.lowercase(), "A meaning in this synthetic context.",
        listOf(Collocation("local $target", "A synthetic example."), Collocation("shared $target", "Another synthetic example.")),
        "An alternative expression.", "neutral", "medium")
    private inner class Harness(val db: LexiconDatabase) {
        var calls = 0
        var saves = 0
        var key = true
        var failOnCall: Int? = null
        var failSave = false
        var failAfterCommit = false
        var cancelAfterCommit = false
        var invalidContent = false
        var awaitAnalysis: (suspend () -> Unit)? = null
        fun repository() = LocalVocabularyLearningRepository(db, analyze = { capture ->
            calls++
            awaitAnalysis?.invoke()
            if (calls == failOnCall) throw ApiFailure("rate_limit", "Synthetic rate limit")
            AnalysisResult(content(capture.target).let { if (invalidContent) it.copy(collocations = emptyList()) else it }, "synthetic/no-network")
        }, saveCard = { capture, result ->
            saves++
            if (failSave) { failSave = false; throw IOException("Synthetic local failure") }
            val card = Card("card:${capture.id}", capture.id, result.content, capture.sentence, capture.sourceRef,
                state = FsrsState(dueAt = now), createdAt = now)
            db.dao().insertCard(CardRow(card.id, card.captureId, now, 0, now, json.encodeToString(card)))
            if (cancelAfterCommit) { cancelAfterCommit = false; throw CancellationException("Synthetic cancellation after commit") }
            if (failAfterCommit) { failAfterCommit = false; throw IOException("Synthetic interruption after commit") }
            card
        }, hasKey = { key }, clock = { now })
    }
    private suspend fun failure(code: String, block: suspend () -> Unit) {
        try { block(); fail("Expected $code") } catch (failure: ApiFailure) { assertEquals(code, failure.code) }
    }
    private suspend fun batch(repo: VocabularyLearningRepository) = repo.library.first().batches.single()

    @Test fun preparationIsAtomicIdempotentAndHasNoModelSideEffect() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db); val a = h.repository(); val b = h.repository()
            val prepared = coroutineScope { listOf(async { a.prepareBatch(article, body, study, ids) },
                async { b.prepareBatch(article, body, study, ids.reversed()) }).awaitAll() }
            assertEquals(1, prepared.distinct().size)
            assertEquals(1, a.library.first().batches.size)
            assertEquals(2, db.dao().observeCaptures().first().size)
            assertEquals(0, h.calls); assertEquals(0, h.saves)
            assertTrue(batch(a).items.all { it.status == VocabularyItemStatus.PENDING })
        } finally { db.close() }
    }

    @Test fun successfulCardsAreNeverGeneratedAgain() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db); val repo = h.repository()
            val first = repo.prepareBatch(article, body, study, ids)
            repo.runBatch(first.id); repo.runBatch(first.id)
            assertEquals(2, h.calls); assertEquals(2, h.saves); assertEquals(2, db.dao().allCards().size)
            assertTrue(batch(repo).items.all { it.status == VocabularyItemStatus.COMPLETED })
            assertEquals(first.id, repo.prepareBatch(article, body, study, ids).id)
        } finally { db.close() }
    }

    @Test fun matchingManualCaptureAndItsCardAreReused() = runBlocking {
        val db = db()
        try {
            seed(db); val manual = candidates.first().capture.copy(id = "manual-existing", createdAt = 50)
            db.dao().putCapture(CaptureRow(manual.id, manual.createdAt, json.encodeToString(manual)))
            val card = Card("existing-card", manual.id, content(manual.target), manual.sentence, manual.sourceRef, createdAt = 60)
            db.dao().insertCard(CardRow(card.id, card.captureId, 0, 0, 60, json.encodeToString(card)))
            val h = Harness(db); val repo = h.repository(); val first = repo.prepareBatch(article, body, study, listOf(ids.first()))
            assertEquals(manual, first.items.single().candidate.capture)
            repo.runBatch(first.id)
            assertEquals(0, h.calls); assertEquals(0, h.saves); assertEquals(1, db.dao().observeCaptures().first().size)
            assertEquals("existing-card", batch(repo).items.single().cardId)
        } finally { db.close() }
    }

    @Test fun firstFailureStopsAndExplicitResumeOnlyHandlesUnfinishedItems() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db).apply { failOnCall = 2 }; val repo = h.repository()
            val first = repo.prepareBatch(article, body, study, ids)
            failure("rate_limit") { repo.runBatch(first.id) }
            assertEquals(listOf(VocabularyItemStatus.COMPLETED, VocabularyItemStatus.FAILED), batch(repo).items.map { it.status })
            assertEquals(2, h.calls); assertEquals(1, h.saves)
            val reopened = h.repository(); assertEquals(2, h.calls)
            reopened.runBatch(first.id)
            assertEquals(3, h.calls); assertEquals(2, h.saves)
            assertTrue(batch(reopened).items.all { it.status == VocabularyItemStatus.COMPLETED })
        } finally { db.close() }
    }

    @Test fun validatedAnalysisSurvivesLocalSaveFailureAndRepositoryRecreationWithoutKey() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db).apply { failSave = true }; val repo = h.repository()
            val first = repo.prepareBatch(article, body, study, listOf(ids.first()))
            failure("queue_failed") { repo.runBatch(first.id) }
            assertNotNull(batch(repo).items.single().content); assertEquals(1, h.calls)
            h.key = false
            val reopened = h.repository(); reopened.runBatch(first.id)
            assertEquals(1, h.calls); assertEquals(2, h.saves); assertEquals(1, db.dao().allCards().size)
        } finally { db.close() }
    }

    @Test fun cancellationRetainsInterruptedMaterialAndDoesNotReplayOnObservation() = runBlocking {
        val db = db()
        try {
            seed(db); val entered = CompletableDeferred<Unit>()
            val h = Harness(db).apply { awaitAnalysis = { entered.complete(Unit); awaitCancellation() } }; val repo = h.repository()
            val first = repo.prepareBatch(article, body, study, listOf(ids.first()))
            val running = launch { repo.runBatch(first.id) }; entered.await()
            assertEquals(VocabularyItemStatus.RUNNING, batch(repo).items.single().status)
            running.cancelAndJoin()
            val reopened = h.repository()
            assertEquals(VocabularyItemStatus.INTERRUPTED, batch(reopened).items.single().status)
            assertEquals(1, h.calls); assertEquals(0, h.saves)
            h.awaitAnalysis = null; reopened.runBatch(first.id)
            assertEquals(2, h.calls); assertEquals(1, h.saves)
        } finally { db.close() }
    }

    @Test fun aSecondRunIsRejectedWhileTheFirstIsActiveInsteadOfQueuedForAutomaticRetry() = runBlocking {
        val db = db()
        try {
            seed(db); val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            val h = Harness(db).apply { awaitAnalysis = { entered.complete(Unit); finish.await() }; failOnCall = 1 }
            val repo = h.repository(); val first = repo.prepareBatch(article, body, study, listOf(ids.first()))
            val running = async { runCatching { repo.runBatch(first.id) } }; entered.await()
            failure("queue_running") { h.repository().runBatch(first.id) }
            finish.complete(Unit); assertTrue(running.await().isFailure)
            assertEquals(1, h.calls); assertEquals(0, h.saves)
        } finally { db.close() }
    }

    @Test fun familiaritySkipsOnlyTheChosenTermAndUndoCanResumeWithoutChangingExistingFsrs() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db); val repo = h.repository(); val first = repo.prepareBatch(article, body, study, ids)
            repo.setFamiliar("  RESIDENTS  ", true)
            repo.runBatch(first.id)
            assertEquals(listOf(VocabularyItemStatus.SKIPPED_FAMILIAR, VocabularyItemStatus.COMPLETED), batch(repo).items.map { it.status })
            val cardBefore = db.dao().allCards().single()
            repo.setFamiliar("opening hours", true)
            assertEquals(cardBefore, db.dao().allCards().single())
            repo.setFamiliar("residents", false); h.repository().runBatch(first.id)
            assertEquals(2, h.calls); assertEquals(2, db.dao().allCards().size)
            assertEquals(cardBefore, db.dao().card(cardBefore.id))
        } finally { db.close() }
    }

    @Test fun sourcePolicyIsRecheckedAndPreviouslyPreparedMaterialIsKept() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db); val repo = h.repository(); val first = repo.prepareBatch(article, body, study, ids)
            db.dao().putSource(SourceRow(source.id, json.encodeToString(source.copy(aiAllowed = false))))
            failure("source_restricted") { repo.runBatch(first.id) }
            assertEquals(0, h.calls); assertEquals(2, db.dao().observeCaptures().first().size)
            assertEquals(VocabularyItemStatus.PENDING, batch(repo).items.last().status)
            db.dao().putSource(SourceRow(source.id, json.encodeToString(source)))
            repo.runBatch(first.id); assertEquals(2, h.calls)
        } finally { db.close() }
    }

    @Test fun frozenQueueRemainsUsableAfterBodyCacheExpiry() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db); val repo = h.repository(); val first = repo.prepareBatch(article, body, study, ids)
            db.dao().expireBodies(Long.MAX_VALUE)
            h.repository().runBatch(first.id)
            assertEquals(2, h.calls)
            assertEquals(candidates.map { it.capture.sentence }.toSet(), db.dao().allCards().map { json.decodeFromString<Card>(it.data).sourceSentence }.toSet())
            failure("snapshot_mismatch") { repo.prepareBatch(article, body, study, ids) }
        } finally { db.close() }
    }

    @Test fun editedCaptureStopsTheQueueWithoutRestoringTheOldText() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db); val repo = h.repository(); val first = repo.prepareBatch(article, body, study, listOf(ids.first()))
            val original = first.items.single().candidate.capture
            val edit = original.copy(target = "local residents", contextOrigin = ContextOrigin.USER_SUPPLIED)
            val row = CaptureRow(edit.id, edit.createdAt, json.encodeToString(edit)); db.dao().putCapture(row)
            failure("capture_changed") { repo.runBatch(first.id) }
            assertEquals(0, h.calls); assertEquals(row, db.dao().capture(edit.id))
            // Preparing the same snapshot cannot replace the edited stable capture either.
            val secondIds = ids
            failure("capture_changed") { repo.prepareBatch(article, body, study, secondIds) }
            assertEquals(row, db.dao().capture(edit.id))
        } finally { db.close() }
    }

    @Test fun forgedOrStaleStudyCannotCreateCaptures() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db); val repo = h.repository()
            try { repo.prepareBatch(article, body, study.copy(summary = "Changed"), ids); fail("Expected stale study") } catch (_: IllegalArgumentException) { }
            try { repo.prepareBatch(article, body, study, listOf("unknown")); fail("Expected unknown candidate") } catch (_: IllegalArgumentException) { }
            assertTrue(db.dao().observeCaptures().first().isEmpty()); assertEquals(0, h.calls)
        } finally { db.close() }
    }

    @Test fun malformedStoredResultFailsClosedAndPreservesOriginalBytes() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db); val repo = h.repository(); val first = repo.prepareBatch(article, body, study, listOf(ids.first()))
            val key = LocalVocabularyLearningRepository.BATCH_PREFIX + first.id
            val corrupt = PreferenceRow(key, json.encodeToString(first.copy(items = first.items.map { it.copy(content = content("word"), model = null) })))
            db.vocabularyDao().put(corrupt)
            failure("queue_damaged") { repo.library.first() }
            failure("queue_damaged") { repo.runBatch(first.id) }
            assertEquals(corrupt, db.vocabularyDao().entry(key)); assertEquals(0, h.calls)
        } finally { db.close() }
    }

    @Test fun invalidModelContentCannotReachTheCardStore() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db).apply { invalidContent = true }; val repo = h.repository()
            val first = repo.prepareBatch(article, body, study, ids)
            failure("queue_failed") { repo.runBatch(first.id) }
            assertEquals(1, h.calls); assertEquals(0, h.saves); assertTrue(db.dao().allCards().isEmpty())
            assertNull(batch(repo).items.first().content); assertEquals(VocabularyItemStatus.PENDING, batch(repo).items.last().status)
        } finally { db.close() }
    }

    @Test fun aCardCommittedBeforeQueueCompletionIsReconciledWithoutAnotherModelOrSave() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db).apply { failAfterCommit = true }; val repo = h.repository()
            val first = repo.prepareBatch(article, body, study, listOf(ids.first()))
            failure("queue_failed") { repo.runBatch(first.id) }
            assertEquals(1, h.calls); assertEquals(1, h.saves); assertEquals(1, db.dao().allCards().size)
            h.repository().runBatch(first.id)
            assertEquals(1, h.calls); assertEquals(1, h.saves); assertEquals(VocabularyItemStatus.COMPLETED, batch(repo).items.single().status)
        } finally { db.close() }
    }

    @Test fun existingValidatedCardIsReturnedByTheAppRepositoryWithoutAKeyOrNewAiJob() = runBlocking {
        val db = db()
        try {
            seed(db); val repo = LexiconRepository(context, db); val capture = candidates.first().capture
            val card = repo.saveCard(capture, AnalysisResult(content(capture.target), "synthetic/no-network"))
            val jobs = db.dao().observeJobs().first()
            val result = repo.analyzeCapture(capture)
            assertEquals("saved-card", result.model); assertEquals(card.content, result.content)
            assertEquals(jobs, db.dao().observeJobs().first()); assertEquals(card, repo.saveCard(capture, result))
        } finally { db.close() }
    }

    @Test fun overlappingSelectionsReuseAnalysisThatWasSavedBeforeALocalFailure() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db).apply { failSave = true }; val repo = h.repository()
            val first = repo.prepareBatch(article, body, study, listOf(ids.first()))
            failure("queue_failed") { repo.runBatch(first.id) }
            val second = repo.prepareBatch(article, body, study, ids)
            repo.runBatch(second.id)
            assertEquals(2, h.calls); assertEquals(3, h.saves); assertEquals(2, db.dao().allCards().size)
            repo.runBatch(first.id)
            assertEquals(2, h.calls); assertEquals(3, h.saves)
        } finally { db.close() }
    }

    @Test fun orphanRunningStateIsDisplayedAsInterruptedWithoutAnyReplayOrRewrite() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db); val repo = h.repository()
            val first = repo.prepareBatch(article, body, study, listOf(ids.first()))
            val row = PreferenceRow(LocalVocabularyLearningRepository.BATCH_PREFIX + first.id,
                json.encodeToString(first.copy(items = first.items.map { it.copy(status = VocabularyItemStatus.RUNNING) })))
            db.vocabularyDao().put(row)
            assertEquals(VocabularyItemStatus.INTERRUPTED, batch(h.repository()).items.single().status)
            assertEquals(row, db.vocabularyDao().entry(row.key)); assertEquals(0, h.calls)
        } finally { db.close() }
    }

    @Test fun mismatchedLegacyCardCannotMarkTheCandidateComplete() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db); val repo = h.repository(); val capture = candidates.first().capture
            db.dao().putCapture(CaptureRow(capture.id, capture.createdAt, json.encodeToString(capture)))
            val card = Card("old-card", capture.id, content(capture.target), "A different sentence.", capture.sourceRef, createdAt = now)
            db.dao().insertCard(CardRow(card.id, card.captureId, 0, 0, now, json.encodeToString(card)))
            failure("capture_changed") { repo.prepareBatch(article, body, study, listOf(ids.first())) }
            assertTrue(repo.library.first().batches.isEmpty()); assertEquals(0, h.calls)
            assertEquals(1, db.dao().allCards().size)
        } finally { db.close() }
    }

    @Test fun normalizedManualTargetIsReusedWithoutRewritingUserText() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db); val repo = h.repository()
            val capture = candidates.first().capture.copy(id = "manual-lowercase", target = "residents")
            val row = CaptureRow(capture.id, capture.createdAt, json.encodeToString(capture)); db.dao().putCapture(row)
            val first = repo.prepareBatch(article, body, study, listOf(ids.first()))
            assertEquals(capture, first.items.single().candidate.capture)
            repo.runBatch(first.id); assertEquals(row, db.dao().capture(capture.id))
        } finally { db.close() }
    }

    @Test fun cancellationAfterCardCommitRetainsAReadableQueueAndReconcilesWithoutAnotherRequest() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db).apply { cancelAfterCommit = true }; val repo = h.repository()
            val first = repo.prepareBatch(article, body, study, listOf(ids.first()))
            try { repo.runBatch(first.id); fail("Expected cancellation") } catch (_: CancellationException) { }
            assertEquals(1, db.dao().allCards().size)
            assertEquals(VocabularyItemStatus.INTERRUPTED, batch(repo).items.single().status)
            h.repository().runBatch(first.id)
            assertEquals(VocabularyItemStatus.COMPLETED, batch(repo).items.single().status)
            assertEquals(1, h.calls); assertEquals(1, h.saves)
        } finally { db.close() }
    }

    private suspend fun commitDifferentTargetThenRestoreCapture(db: LexiconDatabase, original: Capture): CardRow {
        val changed = original.copy(target = "books")
        db.dao().putCapture(CaptureRow(changed.id, changed.createdAt, json.encodeToString(changed)))
        val proposed = Card("changed-target-card", changed.id, content(changed.target), changed.sentence, changed.sourceRef, createdAt = now)
        db.vocabularyDao().commitCard(changed, proposed)
        db.dao().putCapture(CaptureRow(original.id, original.createdAt, json.encodeToString(original)))
        return requireNotNull(db.dao().cardByCapture(original.id))
    }

    @Test fun preparingACandidateDoesNotCountACardForAnotherPreviouslyEditedTargetAsComplete() = runBlocking {
        val db = db()
        try {
            seed(db); val original = candidates.first().capture
            val oldCard = commitDifferentTargetThenRestoreCapture(db, original)
            val h = Harness(db); val repo = h.repository()
            failure("capture_changed") { repo.prepareBatch(article, body, study, listOf(ids.first())) }
            assertTrue(repo.library.first().batches.isEmpty()); assertEquals(0, h.calls)
            assertEquals(oldCard, db.dao().cardByCapture(original.id))
        } finally { db.close() }
    }

    @Test fun resumingACandidateRejectsACardForAnotherTargetEvenWhenTheSentenceAndSourceMatch() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db); val repo = h.repository()
            val prepared = repo.prepareBatch(article, body, study, listOf(ids.first()))
            val oldCard = commitDifferentTargetThenRestoreCapture(db, prepared.items.single().candidate.capture)
            failure("capture_changed") { repo.runBatch(prepared.id) }
            assertEquals(VocabularyItemStatus.FAILED, batch(repo).items.single().status)
            assertNull(batch(repo).items.single().cardId); assertEquals(0, h.calls)
            assertEquals(oldCard, db.dao().cardByCapture(oldCard.captureId))
        } finally { db.close() }
    }

    @Test fun ordinaryGenerationAfterRecreationUsesValidatedQueueAnalysisWithoutPassingTheNetworkGate() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db).apply { failSave = true }; val queue = h.repository()
            val prepared = queue.prepareBatch(article, body, study, listOf(ids.first()))
            failure("queue_failed") { queue.runBatch(prepared.id) }
            assertEquals(1, h.calls)
            val frozen = prepared.items.single().candidate.capture
            // The real transport cannot run even if this regression breaks; no key is read or installed.
            db.dao().putPreference(PreferenceRow("ai_paused", "true"))
            val restarted = LexiconRepository(context, db)
            val jobsBefore = db.dao().observeJobs().first()
            val result = restarted.analyzeCapture(frozen, false)
            assertEquals(content(frozen.target), result.content); assertEquals("synthetic/no-network", result.model)
            assertEquals(jobsBefore, db.dao().observeJobs().first()); assertEquals(1, h.calls)
            val saved = restarted.saveCard(frozen, result)
            assertEquals(frozen.sentence, saved.sourceSentence)
            assertEquals("saved-card", LexiconRepository(context, db).analyzeCapture(frozen, false).model)
            assertEquals(1, db.dao().allCards().size); assertEquals(jobsBefore, db.dao().observeJobs().first())
        } finally { db.close() }
    }

    @Test fun deepAnalysisAndEditedInputCannotReuseTheOrdinaryFrozenQueueResult() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db).apply { failSave = true }; val queue = h.repository()
            val prepared = queue.prepareBatch(article, body, study, listOf(ids.first()))
            failure("queue_failed") { queue.runBatch(prepared.id) }
            val frozen = prepared.items.single().candidate.capture
            db.dao().putPreference(PreferenceRow("ai_paused", "true"))
            val restarted = LexiconRepository(context, db)
            failure("ai_paused") { restarted.analyzeCapture(frozen, true) }
            val edit = frozen.copy(target = "books", updatedAt = now + 1)
            failure("ai_paused") { restarted.analyzeCapture(edit, false) }
            assertEquals(edit.target, json.decodeFromString<Capture>(db.dao().capture(edit.id)!!.data).target)
            assertEquals(1, h.calls); assertTrue(db.dao().observeJobs().first().isEmpty())
        } finally { db.close() }
    }

    @Test fun revokedSourceAndCorruptDurableAnalysisCannotBypassOrdinaryGenerationChecks() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db).apply { failSave = true }; val queue = h.repository()
            val prepared = queue.prepareBatch(article, body, study, listOf(ids.first()))
            failure("queue_failed") { queue.runBatch(prepared.id) }
            val frozen = prepared.items.single().candidate.capture
            db.dao().putPreference(PreferenceRow("ai_paused", "true"))
            db.dao().putSource(SourceRow(source.id, json.encodeToString(source.copy(aiAllowed = false))))
            failure("source_restricted") { LexiconRepository(context, db).analyzeCapture(frozen, false) }
            db.dao().putSource(SourceRow(source.id, json.encodeToString(source)))
            val key = LocalVocabularyLearningRepository.BATCH_PREFIX + prepared.id
            val valid = batch(queue)
            val corrupt = PreferenceRow(key, json.encodeToString(valid.copy(items = valid.items.map { it.copy(model = null) })))
            db.vocabularyDao().put(corrupt)
            failure("queue_damaged") { LexiconRepository(context, db).analyzeCapture(frozen, false) }
            assertEquals(corrupt, db.vocabularyDao().entry(key))
            assertEquals(1, h.calls); assertTrue(db.dao().observeJobs().first().isEmpty())
        } finally { db.close() }
    }

    @Test fun changingOnlyTheRangeOrBodyIdentityMissesBothMemoryAndDurableAnalysis() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db).apply { failSave = true }; val queue = h.repository()
            val prepared = queue.prepareBatch(article, body, study, listOf(ids.first()))
            failure("queue_failed") { queue.runBatch(prepared.id) }
            val frozen = prepared.items.single().candidate.capture
            db.dao().putPreference(PreferenceRow("ai_paused", "true"))
            val repo = LexiconRepository(context, db)
            assertEquals(content(frozen.target), repo.analyzeCapture(frozen, false).content)
            val moved = frozen.copy(selectionStart = frozen.selectionStart!! + 1, selectionEnd = frozen.selectionEnd!! + 1)
            assertEquals(VocabularyCardInput.signature(frozen), VocabularyCardInput.signature(moved))
            assertNotEquals(VocabularyCardInput.analysisSignature(frozen), VocabularyCardInput.analysisSignature(moved))
            failure("ai_paused") { repo.analyzeCapture(moved, false) }
            val newBody = frozen.copy(bodyHash = sha256("A different synthetic snapshot"))
            assertEquals(VocabularyCardInput.signature(frozen), VocabularyCardInput.signature(newBody))
            assertNotEquals(VocabularyCardInput.analysisSignature(frozen), VocabularyCardInput.analysisSignature(newBody))
            failure("ai_paused") { repo.analyzeCapture(newBody, false) }
            assertEquals(1, h.calls); assertTrue(db.dao().observeJobs().first().isEmpty())
        } finally { db.close() }
    }

    @Test fun aSourceRevokedAfterMemoryCachingCannotUseThatAnalysisForNewGeneration() = runBlocking {
        val db = db()
        try {
            seed(db); val h = Harness(db).apply { failSave = true }; val queue = h.repository()
            val prepared = queue.prepareBatch(article, body, study, listOf(ids.first()))
            failure("queue_failed") { queue.runBatch(prepared.id) }
            val frozen = prepared.items.single().candidate.capture
            db.dao().putPreference(PreferenceRow("ai_paused", "true"))
            val repo = LexiconRepository(context, db)
            assertEquals(content(frozen.target), repo.analyzeCapture(frozen, false).content)
            db.dao().putSource(SourceRow(source.id, json.encodeToString(source.copy(aiAllowed = false))))
            failure("source_restricted") { repo.analyzeCapture(frozen, false) }
            assertEquals(1, h.calls); assertTrue(db.dao().observeJobs().first().isEmpty())
        } finally { db.close() }
    }
}
