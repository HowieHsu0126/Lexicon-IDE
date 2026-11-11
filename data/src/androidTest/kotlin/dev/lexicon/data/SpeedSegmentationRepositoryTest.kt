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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SpeedSegmentationRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val text = "This unselected introduction remains outside the retained training material.\n\n" +
        "The regional team studies local transport; residents compare several proposals and discuss how each route could improve access to work. A map marked north 🧭 supports the discussion.\n\n" +
        "Volunteers collect comments from neighbouring towns; the committee reviews the evidence before choosing a practical timetable."
    private val article = Article("segmentation-fixture", "g", listOf("s"), "A local transport proposal", "https://example.org/segmentation", "", sourceName = "Self-authored test")
    private val body = ArticleBody(article.id, text, sha256(text), 0, 1, "Self-authored fixture")
    private val calls = AtomicInteger()
    private val generator = SpeedPlanGenerator { _, body, plan ->
        calls.incrementAndGet()
        val paragraphs = ParagraphFocus.paragraphs(body.text)
        plan.copy(origin = "ai", model = "synthetic/no-network", questions = plan.paragraphIndices.map { index ->
            val p = paragraphs[index]
            SpeedQuestion("p$index", index, "What is being considered?", listOf("A local proposal", "A sporting event", "A flight"), 0,
                p.start, p.endExclusive, "Self-authored test evidence.")
        })
    }
    private fun db() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).build()
    private fun repo(db: LexiconDatabase, key: Boolean = true, allowed: Boolean = true) =
        LocalSpeedReadingRepository(db, generator, hasKey = { key }, sourceAllowed = { allowed }, clock = { 1000 })
    private suspend fun plan(repository: LocalSpeedReadingRepository, ai: Boolean = true, level: SpeedGranularity = SpeedGranularity.L2) =
        repository.prepare(article, body, level, 1, 2, true, ai)
    private fun run(plan: SpeedPlan, id: String): SpeedRun {
        val dwell = List(plan.chunks.size) { 1000L }
        return SpeedRun(id, plan.articleId, plan.bodyHash, plan.granularity, plan.planKey, 1000, 1000 + dwell.sum(), dwell.sum(),
            plan.chunks.sumOf { it.wordCount }, plan.chunks.size, dwell, 0, 0,
            plan.questions.map { SpeedAnswer(it.id, it.answerIndex, true) }, plan.questions.size, true, 60, plan.origin,
            firstAttempt = true, pacingStable = true, pacedCompletion = true)
    }
    private suspend fun stale(block: suspend () -> Unit) {
        try { block(); fail("Expected stale editing state") } catch (e: ApiFailure) { assertEquals("stale_segmentation", e.code) }
    }

    @Test fun editingUsesRetainedTextCreatesImmutablePlanAndReusesItOfflineAfterRestart() = runBlocking {
        val db = db()
        try {
            val repository = repo(db); val original = plan(repository)
            repository.saveRun(run(original, "before-edit"))
            val before = repository.library.first().runs.single()
            val sentence = requireNotNull(repository.sentenceForEditing(original.planKey, 0))
            assertEquals(1, sentence.paragraph); assertTrue(sentence.start > 0)
            assertEquals(text.substring(sentence.start, sentence.end), sentence.text)
            assertTrue(sentence.breaks.isNotEmpty())
            assertNull(db.dao().body(article.id, Long.MIN_VALUE))
            val edited = repository.editSentence(original.planKey, sentence.paragraph, sentence.sentence, emptyList())
            assertEquals("manual", edited.origin); assertEquals(original.planKey, edited.basePlanKey)
            assertNotEquals(original.planKey, edited.planKey); assertEquals(original.questions, edited.questions)
            assertEquals(original, repository.cachedPlan(original.planKey))
            assertEquals(before, repository.library.first().runs.single())
            assertEquals("boundary_edit", repository.library.first().corrections.single().kind)
            val restarted = repo(db, key = false, allowed = false)
            assertEquals(edited, restarted.prepare(article, body, SpeedGranularity.L2, 1, 2, false, true))
            val restoredEditor = requireNotNull(restarted.sentenceForEditing(edited.planKey, 0))
            assertEquals(sentence.text, restoredEditor.text)
            assertEquals(sentence.breaks, restoredEditor.suggestedBreaks)
            assertTrue(restoredEditor.breaks.isEmpty()); assertTrue(restoredEditor.hasPersonalEdits)
            assertEquals(1, calls.get())
        } finally { db.close() }
    }

    @Test fun restoringTheBaseKeepsEditExposureAndDoesNotRestoreFirstAttemptAcrossArticleIds() = runBlocking {
        val db = db()
        try {
            val repository = repo(db); val original = plan(repository)
            val s = requireNotNull(repository.sentenceForEditing(original.planKey, 0))
            val edited = repository.editSentence(original.planKey, s.paragraph, s.sentence, emptyList())
            assertEquals(original, repository.restoreSegmentation(edited.planKey))
            assertEquals(original, plan(repository)); assertEquals(1, calls.get())
            repository.saveRun(run(original, "after-restore"))
            assertFalse(repository.library.first().runs.single().firstAttempt)
            val alias = article.copy(id = "syndicated-edit-fixture")
            val aliasPlan = repository.prepare(alias, body.copy(articleId = alias.id), SpeedGranularity.L1, 1, 1, true, false)
            repository.saveRun(run(aliasPlan, "after-syndication"))
            assertFalse(repository.library.first().runs.single { it.id == "after-syndication" }.firstAttempt)
            assertEquals(edited, repository.cachedPlan(edited.planKey))
        } finally { db.close() }
    }

    @Test fun oldEditorsAndOldRestoreRequestsCannotOverwriteANewerPersonalPlan() = runBlocking {
        val db = db()
        try {
            val repository = repo(db); val original = plan(repository)
            val s = requireNotNull(repository.sentenceForEditing(original.planKey, 0))
            val first = repository.editSentence(original.planKey, s.paragraph, s.sentence, emptyList())
            stale { repository.editSentence(original.planKey, s.paragraph, s.sentence, s.legalBreaks) }
            val second = repository.editSentence(first.planKey, s.paragraph, s.sentence, s.legalBreaks.take(1))
            stale { repository.restoreSegmentation(first.planKey) }
            assertEquals(second, plan(repository))
            assertEquals(original.planKey, second.basePlanKey)
            assertEquals(original, repository.restoreSegmentation(second.planKey))
            stale { repository.editSentence(second.planKey, s.paragraph, s.sentence, emptyList()) }
        } finally { db.close() }
    }

    @Test fun concurrentEditorsCommitOnlyOneOverride() = runBlocking {
        val db = db()
        try {
            val firstRepo = repo(db); val secondRepo = repo(db); val original = plan(firstRepo)
            val s = requireNotNull(firstRepo.sentenceForEditing(original.planKey, 0))
            assertTrue(s.breaks.isNotEmpty())
            val outcomes = coroutineScope { listOf(
                async { runCatching { firstRepo.editSentence(original.planKey, s.paragraph, s.sentence, emptyList()) } },
                async { runCatching { secondRepo.editSentence(original.planKey, s.paragraph, s.sentence, emptyList()) } },
            ).awaitAll() }
            assertEquals(1, outcomes.count { it.isSuccess })
            assertEquals("stale_segmentation", (outcomes.single { it.isFailure }.exceptionOrNull() as ApiFailure).code)
            assertEquals(outcomes.single { it.isSuccess }.getOrThrow(), plan(firstRepo))
        } finally { db.close() }
    }

    @Test fun invalidOffsetsDoNotChangeThePlanAndChangedBodyDoesNotReceiveOldEdits() = runBlocking {
        val db = db()
        try {
            val repository = repo(db); val original = plan(repository)
            val s = requireNotNull(repository.sentenceForEditing(original.planKey, 0))
            for (offsets in listOf(listOf(s.start), listOf(s.end), listOf(s.start + 1), listOf(s.legalBreaks.first(), s.legalBreaks.first()))) {
                try { repository.editSentence(original.planKey, s.paragraph, s.sentence, offsets); fail("Illegal boundary") }
                catch (_: IllegalArgumentException) { }
            }
            assertTrue(repository.library.first().corrections.isEmpty())
            val edited = repository.editSentence(original.planKey, s.paragraph, s.sentence, emptyList())
            val updatedText = text.replace("local transport", "regional education")
            val updatedBody = body.copy(text = updatedText, contentHash = sha256(updatedText))
            val newPlan = repository.prepare(article, updatedBody, SpeedGranularity.L2, 1, 2, true, false)
            assertEquals("rules", newPlan.origin); assertNull(newPlan.basePlanKey)
            assertNotEquals(edited.bodyHash, newPlan.bodyHash)
            assertEquals(edited, repository.cachedPlan(edited.planKey))
        } finally { db.close() }
    }

    @Test fun unchangedSavedBoundariesStillRecordExposureWithoutInventingAPersonalOverride() = runBlocking {
        val db = db()
        try {
            val repository = repo(db); val original = plan(repository, ai = false)
            val s = requireNotNull(repository.sentenceForEditing(original.planKey, 0))
            assertEquals(original, repository.editSentence(original.planKey, s.paragraph, s.sentence, s.breaks))
            assertEquals(original, repository.restoreSegmentation(original.planKey))
            repository.saveRun(run(original, "confirmed-preview"))
            assertFalse(repository.library.first().runs.single().firstAttempt)
            assertEquals(0, calls.get())
        } finally { db.close() }
    }

    @Test fun previewWithoutEditingOrPlaybackPersistsExposureAcrossRestartAndArticleIds() = runBlocking {
        val db = db()
        try {
            val repository = repo(db); val original = plan(repository, ai = false)
            val sentence = requireNotNull(repository.sentenceForEditing(original.planKey, 0))
            val exposureKey = "speed-exposure:${body.contentHash}:${sentence.paragraph}"
            assertNull(db.readingDao().entry(exposureKey)) // Historical inspection remains read-only.
            try { repository.markSentencePreviewed(original.planKey, 0); fail("Unselected paragraph") }
            catch (_: IllegalArgumentException) { }
            repository.markSentencePreviewed(original.planKey, sentence.paragraph)
            repository.markSentencePreviewed(original.planKey, sentence.paragraph)
            assertNotNull(db.readingDao().entry(exposureKey))
            assertEquals(original, repository.cachedPlan(original.planKey))
            assertTrue(repository.library.first().runs.isEmpty())
            assertTrue(repository.library.first().corrections.isEmpty())
            val reopened = repo(db, key = false)
            val alias = article.copy(id = "preview-syndication")
            val aliasBody = body.copy(articleId = alias.id)
            val exposed = reopened.prepare(alias, aliasBody, SpeedGranularity.L1, 1, 1, true, false)
            reopened.saveRun(run(exposed, "after-preview"))
            val unseen = reopened.prepare(alias, aliasBody, SpeedGranularity.L1, 2, 1, true, false)
            reopened.saveRun(run(unseen, "unpreviewed-paragraph"))
            val rows = reopened.library.first().runs.associateBy { it.id }
            assertFalse(rows.getValue("after-preview").firstAttempt)
            assertTrue(rows.getValue("unpreviewed-paragraph").firstAttempt)
            assertEquals(0, calls.get())
        } finally { db.close() }
    }

    @Test fun corruptedPersonalSnapshotFailsClosedWithoutCallingAiOrDamagingItsBase() = runBlocking {
        val db = db()
        try {
            val repository = repo(db); val original = plan(repository)
            val s = requireNotNull(repository.sentenceForEditing(original.planKey, 0))
            val edited = repository.editSentence(original.planKey, s.paragraph, s.sentence, emptyList())
            val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
            val key = "speed-plan:${edited.planKey}"
            val stored = json.decodeFromString<SpeedPlanRecord>(requireNotNull(db.readingDao().entry(key)).data)
            val corrupted = stored.copy(source = stored.source.map { it.copy(text = it.text + " changed") })
            db.readingDao().put(PreferenceRow(key, json.encodeToString(corrupted)))
            assertNull(repository.cachedPlan(edited.planKey))
            try { plan(repository); fail("Invalid override cannot be reused") } catch (e: ApiFailure) { assertEquals("invalid_override", e.code) }
            assertEquals(original, repository.cachedPlan(original.planKey))
            assertEquals(1, calls.get())
        } finally { db.close() }
    }
}
