package dev.lexicon.ide.ui

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SpeedInteractionViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher
    private val stores = linkedMapOf<SpeedReadingViewModel, ViewModelStore>()
    private val article = Article("interactions", "g", listOf("source"), "A synthetic exercise", "https://example.org/reading", "")
    private val text = "Researchers compare several explanations before they reach a cautious conclusion. " +
        "Reliable observations help them identify which claims need additional supporting evidence."
    private val body = ArticleBody(article.id, text, fingerprint(text), 1, Long.MAX_VALUE, "Synthetic")

    @Before fun setUp() {
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
    }
    @After fun tearDown() {
        try { stores.values.forEach { it.clear() }; scheduler.runCurrent() }
        finally { stores.clear(); Dispatchers.resetMain() }
    }

    @Test fun preparationDoesNotInventPlaybackVisits() = runTest(dispatcher) {
        val repository = InteractionRepository()
        val saved = SavedStateHandle()
        val model = prepared(repository, saved)
        assertTrue(requireNotNull(saved.get<Bundle>(KEY)).getIntArray("chunk-visits")!!.all { it == 0 })
        model.seek(1); model.repeat()
        assertTrue(requireNotNull(saved.get<Bundle>(KEY)).getIntArray("chunk-visits")!!.all { it == 0 })
        model.togglePlay(); advanceTimeBy(20); model.pause(false)
        val run = persisted(model, repository)
        assertEquals(0, requireNotNull(run.chunkInteractions)[0].visits)
        assertEquals(1, requireNotNull(run.chunkInteractions)[1].visits)
        assertEquals(0, run.revisits)
    }

    @Test fun pauseAndResumeStayInOneVisitAndCountOnlyExplicitPauses() = runTest(dispatcher) {
        val repository = InteractionRepository()
        val model = prepared(repository)
        model.togglePlay(); advanceTimeBy(20); model.togglePlay()
        advanceTimeBy(5_000)
        model.togglePlay(); advanceTimeBy(20); model.togglePlay()
        val run = persisted(model, repository)
        assertEquals(SpeedChunkInteraction(1, 2, 0), requireNotNull(run.chunkInteractions).first())
        assertEquals(40L, run.groupMillis.first())
        assertEquals(2, run.pauses)
        assertEquals(0, run.revisits)
    }

    @Test fun repeatDoesNotAddAVisitUntilPlaybackActuallyStartsAgain() = runTest(dispatcher) {
        val repository = InteractionRepository()
        val saved = SavedStateHandle()
        val model = prepared(repository, saved)
        model.togglePlay(); advanceTimeBy(20); model.pause(false)
        model.repeat(); model.repeat()
        assertEquals(1, requireNotNull(saved.get<Bundle>(KEY)).getIntArray("chunk-visits")!![0])
        model.togglePlay(); advanceTimeBy(20); model.pause(false)
        val run = persisted(model, repository)
        assertEquals(SpeedChunkInteraction(2, 0, 1), requireNotNull(run.chunkInteractions).first())
        assertEquals(1, run.revisits)
    }

    @Test fun forwardNavigationToAnAlreadyPlayedChunkCountsAsARealRevisit() = runTest(dispatcher) {
        val repository = InteractionRepository()
        val model = prepared(repository)
        for (index in listOf(0, 1, 0, 1)) {
            model.seek(index); model.togglePlay(); advanceTimeBy(20); model.pause(false)
        }
        val run = persisted(model, repository)
        assertEquals(listOf(SpeedChunkInteraction(2, 0, 1), SpeedChunkInteraction(2, 0, 1)), requireNotNull(run.chunkInteractions).take(2))
        assertEquals(2, run.revisits)
        assertEquals(80L, run.activeMillis)
    }

    @Test fun backgroundPauseDoesNotBecomeAnExplicitUserPauseOrANewVisit() = runTest(dispatcher) {
        val repository = InteractionRepository()
        val model = prepared(repository)
        model.togglePlay(); advanceTimeBy(20); model.setForeground(false)
        advanceTimeBy(50_000)
        model.setForeground(true)
        assertFalse(model.state.value.playing)
        model.togglePlay(); advanceTimeBy(20); model.pause(false)
        val run = persisted(model, repository)
        assertEquals(SpeedChunkInteraction(1, 0, 0), requireNotNull(run.chunkInteractions).first())
        assertEquals(40L, run.activeMillis)
        assertTrue(SpeedInteractionRecap.summarize(run, requireNotNull(model.state.value.plan)).items.isEmpty())
    }

    @Test fun changingPaceKeepsOneVisitButDisablesNormalizedDwellEvenAfterChangingBack() = runTest(dispatcher) {
        val repository = InteractionRepository()
        val model = prepared(repository)
        model.togglePlay(); advanceTimeBy(20); model.setSpeed(90)
        advanceTimeBy(20); model.togglePlay(); model.setSpeed(60)
        val plan = requireNotNull(model.state.value.plan)
        val run = persisted(model, repository)
        assertEquals(SpeedChunkInteraction(1, 1, 0), requireNotNull(run.chunkInteractions).first())
        assertEquals(false, run.pacingStable)
        val summary = SpeedInteractionRecap.summarize(run, plan)
        assertTrue(SpeedInteractionLimit.VARIABLE_PACE in summary.limitations)
        assertNull(summary.items.single().normalizedMeanDwell)
    }

    @Test fun restoredOpenVisitIsNotCountedAgainAndCheckpointArraysStayFrozen() = runTest(dispatcher) {
        val repository = InteractionRepository()
        val saved = SavedStateHandle()
        val original = prepared(repository, saved)
        original.togglePlay()
        val checkpoint = requireNotNull(saved.get<Bundle>(KEY))
        assertTrue(checkpoint.getBoolean("visit-open"))
        assertEquals(1, checkpoint.getIntArray("chunk-visits")!![0])
        original.repeat(); original.togglePlay()
        assertEquals("A saved checkpoint must not alias live counter arrays", 1, checkpoint.getIntArray("chunk-visits")!![0])
        stores.remove(original)?.clear()
        val restored = model(repository, SavedStateHandle(mapOf(KEY to Bundle(checkpoint))))
        restored.bind(article, body, true, false, 1, 0); runCurrent()
        restored.togglePlay(); advanceTimeBy(20); restored.pause(false)
        val run = persisted(restored, repository)
        assertEquals(SpeedChunkInteraction(1, 0, 0), requireNotNull(run.chunkInteractions).first())
        assertEquals(20L, run.groupMillis.first())
    }

    @Test fun anOldCheckpointCannotInventVisitsForItsExistingPlaybackTime() = runTest(dispatcher) {
        val repository = InteractionRepository()
        val saved = SavedStateHandle()
        val original = prepared(repository, saved)
        original.togglePlay(); advanceTimeBy(20); original.pause(false)
        val checkpoint = Bundle(requireNotNull(saved.get<Bundle>(KEY))).apply {
            remove("chunk-visits"); remove("chunk-pauses"); remove("visit-open")
        }
        stores.remove(original)?.clear()
        val restored = model(repository, SavedStateHandle(mapOf(KEY to checkpoint)))
        restored.bind(article, body, true, false, 1, 0); runCurrent()
        restored.togglePlay(); advanceTimeBy(20); restored.pause(false)
        val plan = requireNotNull(restored.state.value.plan)
        val run = persisted(restored, repository)
        assertEquals(40L, run.groupMillis.first())
        assertNull(run.chunkInteractions)
        assertTrue(SpeedInteractionLimit.MISSING_VISIT_DATA in SpeedInteractionRecap.summarize(run, plan).limitations)
    }

    @Test fun newerDatabasePlaybackWinsOverAStaleCheckpointAndStartsAFreshVisit() = runTest(dispatcher) {
        val repository = InteractionRepository()
        val saved = SavedStateHandle()
        val original = prepared(repository, saved)
        original.togglePlay(); advanceTimeBy(20); original.pause(false)
        val checkpoint = Bundle(requireNotNull(saved.get<Bundle>(KEY)))
        val older = persisted(original, repository)
        advanceTimeBy(100)
        val newer = older.copy(
            finishedAt = 1_000_000 + scheduler.currentTime,
            activeMillis = 100,
            groupMillis = older.groupMillis.mapIndexed { index, value -> if (index == 0) 100L else value },
            pauses = 1,
            revisits = 1,
            chunkInteractions = requireNotNull(older.chunkInteractions).mapIndexed { index, value ->
                if (index == 0) SpeedChunkInteraction(2, 1, 1) else value
            },
        )
        repository.install(newer)
        stores.remove(original)?.clear()
        val restoredSaved = SavedStateHandle(mapOf(KEY to checkpoint))
        val restored = model(repository, restoredSaved)
        restored.bind(article, body, true, false, 1, 0); runCurrent()
        assertNull(restored.state.value.error)
        val merged = requireNotNull(restoredSaved.get<Bundle>(KEY))
        assertEquals(100L, merged.getLongArray("durations")!![0])
        assertEquals(0L, merged.getLong("visit"))
        assertFalse(merged.getBoolean("visit-open"))
        restored.togglePlay(); advanceTimeBy(20); restored.pause(false)
        val run = persisted(restored, repository)
        assertEquals(older.id, run.id)
        assertEquals(120L, run.activeMillis)
        assertEquals(120L, run.groupMillis.first())
        assertEquals(SpeedChunkInteraction(3, 1, 2), requireNotNull(run.chunkInteractions).first())
        assertEquals(1, run.pauses)
        assertEquals(2, run.revisits)
    }

    @Test fun databaseAnswersStayLockedWhenTheCheckpointPredatesSubmission() = runTest(dispatcher) {
        val repository = InteractionRepository()
        val saved = SavedStateHandle()
        val original = prepared(repository, saved, useAi = true)
        val plan = requireNotNull(original.state.value.plan)
        val average = plan.chunks.map { it.wordCount }.average()
        val duration = plan.chunks.sumOf { SpeedPacing.durationMillis(it, 60, average) }
        original.togglePlay(); advanceTimeBy(duration + 1); runCurrent()
        assertEquals(SpeedStage.QUESTION, original.state.value.stage)
        val checkpoint = Bundle(requireNotNull(saved.get<Bundle>(KEY)))
        assertFalse(checkpoint.getBoolean("revealed"))
        original.answer(0); runCurrent()
        val submitted = repository.library.value.runs.single().answers.single()
        stores.remove(original)?.clear()
        val restored = model(repository, SavedStateHandle(mapOf(KEY to checkpoint)))
        restored.bind(article, body, true, true, 1, 0); runCurrent()
        assertEquals(SpeedStage.QUESTION, restored.state.value.stage)
        assertTrue(restored.state.value.answerRevealed)
        assertEquals(submitted.selectedIndex, restored.state.value.selectedAnswer)
        restored.answer(1); runCurrent()
        assertEquals(listOf(submitted), restored.state.value.answers)
        assertEquals(listOf(submitted), persisted(restored, repository).answers)
    }

    @Test fun knownDatabaseTraceDoesNotAdoptUnpairedTimeFromALegacyCheckpoint() = runTest(dispatcher) {
        val repository = InteractionRepository()
        val saved = SavedStateHandle()
        val original = prepared(repository, saved)
        original.togglePlay(); advanceTimeBy(20); original.pause(false)
        val checkpoint = Bundle(requireNotNull(saved.get<Bundle>(KEY))).apply {
            putLongArray("durations", getLongArray("durations")!!.copyOf().also { it[0] = 100 })
            putLong("visit", 100)
            remove("chunk-visits"); remove("chunk-pauses"); remove("visit-open")
        }
        val canonical = persisted(original, repository)
        stores.remove(original)?.clear()
        advanceTimeBy(100)
        val restored = model(repository, SavedStateHandle(mapOf(KEY to checkpoint)))
        restored.bind(article, body, true, false, 1, 0); runCurrent()
        restored.togglePlay(); advanceTimeBy(20); restored.pause(false)
        val run = persisted(restored, repository)
        assertEquals(canonical.id, run.id)
        assertEquals(40L, run.groupMillis.first())
        assertEquals(SpeedChunkInteraction(2, 0, 1), requireNotNull(run.chunkInteractions).first())
    }

    @Test fun legacyDatabaseTraceRemainsUnknownEvenWhenTheCheckpointHasCounters() = runTest(dispatcher) {
        val repository = InteractionRepository()
        val saved = SavedStateHandle()
        val original = prepared(repository, saved)
        original.togglePlay(); advanceTimeBy(20); original.pause(false)
        val checkpoint = Bundle(requireNotNull(saved.get<Bundle>(KEY)))
        val canonical = persisted(original, repository)
        repository.install(canonical.copy(chunkInteractions = null))
        stores.remove(original)?.clear()
        val restored = model(repository, SavedStateHandle(mapOf(KEY to checkpoint)))
        restored.bind(article, body, true, false, 1, 0); runCurrent()
        restored.togglePlay(); advanceTimeBy(20); restored.pause(false)
        val run = persisted(restored, repository)
        assertEquals(40L, run.groupMillis.first())
        assertNull(run.chunkInteractions)
    }

    @Test fun guidedFadedAndNaturalRoundsDoNotShareInteractionCounters() = runTest(dispatcher) {
        val repository = InteractionRepository()
        val model = prepared(repository)
        val plan = requireNotNull(model.state.value.plan)
        val duration = plan.chunks.sumOf { SpeedPacing.durationMillis(it, 60, plan.chunks.map { chunk -> chunk.wordCount }.average()) }
        model.togglePlay(); advanceTimeBy(duration + 1); runCurrent()
        val guided = requireNotNull(model.state.value.result)
        assertTrue(requireNotNull(guided.chunkInteractions).all { it == SpeedChunkInteraction(1, 0, 0) })
        model.nextRound()
        assertEquals(SpeedPacedRound.FADED, model.state.value.round)
        model.togglePlay(); advanceTimeBy(20); model.togglePlay()
        model.togglePlay(); advanceTimeBy(duration + 1); runCurrent()
        val faded = requireNotNull(model.state.value.result)
        assertEquals(SpeedPacedRound.FADED, faded.round)
        assertEquals(SpeedChunkInteraction(1, 1, 0), requireNotNull(faded.chunkInteractions).first())
        assertNotEquals(guided.id, faded.id)
        model.nextRound(); model.naturalPlayPause(); advanceTimeBy(10_000); model.naturalCompleteParagraph(); runCurrent()
        assertEquals(2, repository.library.value.runs.size)
        assertEquals(1, repository.library.value.naturalAttempts.size)
        assertEquals(guided.chunkInteractions, repository.library.value.runs.first { it.id == guided.id }.chunkInteractions)
        assertEquals(faded.chunkInteractions, repository.library.value.runs.first { it.id == faded.id }.chunkInteractions)
    }

    private fun model(repository: InteractionRepository, saved: SavedStateHandle): SpeedReadingViewModel =
        SpeedReadingViewModel(repository, saved, { 1_000_000 + scheduler.currentTime }, { scheduler.currentTime }, dispatcher).also { model ->
            ViewModelStore().also { it.put("speed", model); stores[model] = it }
        }
    private fun prepared(repository: InteractionRepository, saved: SavedStateHandle = SavedStateHandle(), useAi: Boolean = false): SpeedReadingViewModel =
        model(repository, saved).also { it.bind(article, body, true, useAi, 1, 0); it.prepare(useAi); scheduler.runCurrent() }
    private fun persisted(model: SpeedReadingViewModel, repository: InteractionRepository): SpeedRun {
        model.leave {}
        scheduler.runCurrent()
        return repository.library.value.runs.last()
    }
    companion object { private const val KEY = "speed-training-state" }
}

private class InteractionRepository : SpeedReadingRepository {
    override val library = MutableStateFlow(SpeedLibrary())
    private val plans = mutableMapOf<String, SpeedPlan>()
    override suspend fun cachedPlan(planKey: String) = plans[planKey]
    override suspend fun prepare(article: Article, body: ArticleBody, granularity: SpeedGranularity, startParagraph: Int,
        paragraphCount: Int, aiAllowed: Boolean, useAi: Boolean): SpeedPlan {
        val base = SpeedChunker.plan(article.id, body.contentHash, body.text, granularity, startParagraph, paragraphCount, 0)
        val paragraphs = ParagraphFocus.paragraphs(body.text)
        val draft = base.copy(origin = if (useAi) "ai" else "rules", questions = if (!useAi) emptyList() else base.paragraphIndices.map { index ->
            SpeedQuestion("question-$index", index, "What do researchers compare?", listOf("Explanations", "Prices", "Seasons", "Routes"),
                0, paragraphs[index].start, paragraphs[index].endExclusive, "The paragraph describes comparing explanations and evidence.")
        })
        return draft.copy(planKey = SpeedChunker.planKey(draft)).also { plans[it.planKey] = it }
    }
    fun install(run: SpeedRun) {
        library.value = library.value.copy(runs = library.value.runs.filterNot { it.id == run.id } + run)
    }
    override suspend fun saveRun(run: SpeedRun) = install(run)
    override suspend fun saveNaturalAttempt(attempt: NaturalReadingAttempt) {
        library.value = library.value.copy(naturalAttempts = library.value.naturalAttempts.filterNot { it.id == attempt.id } + attempt)
    }
    override suspend fun saveCorrection(correction: SpeedCorrection) = Unit
}
