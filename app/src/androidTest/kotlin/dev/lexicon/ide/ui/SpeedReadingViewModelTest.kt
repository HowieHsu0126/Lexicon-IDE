package dev.lexicon.ide.ui

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.Article
import dev.lexicon.domain.ArticleBody
import dev.lexicon.domain.ParagraphFocus
import dev.lexicon.domain.PaceSuggestion
import dev.lexicon.domain.PersonalizationConflict
import dev.lexicon.domain.PersonalizationDecision
import dev.lexicon.domain.PersonalizationDecisionStatus
import dev.lexicon.domain.PersonalizationProfile
import dev.lexicon.domain.PersonalizationRepository
import dev.lexicon.domain.SpeedAnswer
import dev.lexicon.domain.SpeedChunker
import dev.lexicon.domain.SpeedCorrection
import dev.lexicon.domain.SpeedGranularity
import dev.lexicon.domain.SpeedLibrary
import dev.lexicon.domain.SpeedMetrics
import dev.lexicon.domain.SpeedPacing
import dev.lexicon.domain.SpeedPlan
import dev.lexicon.domain.SpeedQuestion
import dev.lexicon.domain.SpeedReadingRepository
import dev.lexicon.domain.SpeedRun
import dev.lexicon.domain.SpeedSegmentation
import dev.lexicon.domain.SpeedSegmentationEditor
import dev.lexicon.domain.SpeedPacedRound
import dev.lexicon.domain.NaturalReadingAttempt
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
// Real Android Bundle/state behavior with deterministic controller time; no Activity or UI launch.
class SpeedReadingViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher
    private val stores = linkedMapOf<SpeedReadingViewModel, ViewModelStore>()

    @Before fun setUp() {
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        try {
            stores.values.forEach { it.clear() }
            scheduler.runCurrent()
        } finally {
            stores.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun pauseExcludesIdleTimeFromSavedRun() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = model(repository)
        bind(model)
        model.prepare(false)
        runCurrent()

        model.togglePlay()
        runCurrent()
        advanceTimeBy(120)
        model.pause(count = true)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, model.state.value.chunkIndex)
        assertFalse(model.state.value.playing)

        model.togglePlay()
        runCurrent()
        advanceTimeBy(80)
        model.pause()
        model.leave()
        runCurrent()

        val run = repository.saveAttempts.single()
        assertEquals(200L, run.activeMillis)
        assertEquals(200L, run.groupMillis.first())
        assertEquals(1, run.groupsRead)
        assertEquals(1, run.pauses)
        assertEquals(10_200L, run.finishedAt - run.startedAt)
        assertFalse(run.completed)
        assertEquals(true, run.pacingStable)
        assertEquals(false, run.pacedCompletion)
    }

    @Test fun rapidSpeedChangesKeepOneTimerAndPreserveCurrentBlockTime() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = model(repository)
        bind(model)
        model.prepare(false)
        runCurrent()
        val plan = requireNotNull(model.state.value.plan)
        assertTrue(plan.chunks.size >= 3)

        model.setSpeed(90)
        model.togglePlay()
        runCurrent()
        advanceTimeBy(100)
        listOf(150, 40, 180, 120).forEach(model::setSpeed)
        runCurrent()
        val firstDuration = duration(plan, 0, 120)
        assertTrue(firstDuration > 100)

        advanceTimeBy(firstDuration - 101)
        runCurrent()
        assertEquals(0, model.state.value.chunkIndex)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, model.state.value.chunkIndex)
        assertTrue(model.state.value.playing)

        model.pause()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, model.state.value.chunkIndex)
        model.leave()
        runCurrent()
        val run = repository.saveAttempts.single()
        assertEquals(firstDuration, run.activeMillis)
        assertEquals(firstDuration, run.groupMillis.first())
        assertEquals(1, run.groupsRead)
        assertEquals(0, run.pauses)
        assertEquals(false, run.pacingStable)
    }

    @Test fun paragraphQuestionsPausePlaybackAndLockTheFirstAnswer() = runTest(dispatcher) {
        val repository = FakeRepository(withQuestions = true)
        val model = model(repository)
        bind(model)
        model.setCount(2)
        model.prepare(true)
        runCurrent()
        val plan = requireNotNull(model.state.value.plan)
        val lastInFirstParagraph = plan.chunks.indexOfLast { it.paragraph == 0 }

        model.togglePlay()
        runCurrent()
        advanceTimeBy(100_000)
        runCurrent()
        assertEquals(SpeedStage.QUESTION, model.state.value.stage)
        assertEquals(lastInFirstParagraph, model.state.value.chunkIndex)
        assertFalse(model.state.value.playing)
        model.continueAfterQuestion()
        model.togglePlay()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(SpeedStage.QUESTION, model.state.value.stage)
        assertEquals(lastInFirstParagraph, model.state.value.chunkIndex)

        model.answer(0)
        model.answer(1)
        assertEquals(0, model.state.value.selectedAnswer)
        assertFalse(model.state.value.answers.single().correct)
        model.continueAfterQuestion()
        assertEquals(SpeedStage.READING, model.state.value.stage)
        assertEquals(lastInFirstParagraph + 1, model.state.value.chunkIndex)
        assertFalse(model.state.value.playing)

        model.togglePlay()
        runCurrent()
        advanceTimeBy(100_000)
        runCurrent()
        assertEquals(SpeedStage.QUESTION, model.state.value.stage)
        assertFalse(model.state.value.playing)
        model.answer(1)
        model.continueAfterQuestion()
        runCurrent()

        val run = repository.saveAttempts.last { it.completed }
        assertEquals(SpeedStage.RESULT, model.state.value.stage)
        assertTrue(run.completed)
        assertEquals(true, run.pacedCompletion)
        assertEquals(2, run.answers.size)
        assertEquals(plan.chunks.indices.sumOf { duration(plan, it, 60) }, run.activeMillis)
        assertEquals(0.5, SpeedMetrics.summarize(run).comprehension!!, 0.0)
    }

    @Test fun skippingUnreadBlocksDoesNotBecomeACompletedTraining() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = model(repository)
        bind(model)
        model.prepare(false)
        runCurrent()
        val plan = requireNotNull(model.state.value.plan)

        repeat(plan.chunks.size) { model.next() }
        runCurrent()

        val run = repository.saveAttempts.single()
        assertEquals(SpeedStage.RESULT, model.state.value.stage)
        assertFalse(run.completed)
        assertEquals(0L, run.activeMillis)
        assertEquals(0, run.groupsRead)
        assertEquals(0, run.wordsRead)
        assertNull(SpeedMetrics.summarize(run).wordsPerMinute)
        assertNull(SpeedMetrics.summarize(run).comprehension)
        assertNull(SpeedMetrics.summarize(run).effectiveWordsPerMinute)
    }

    @Test fun rapidManualAdvancesCannotBecomePacedCompletion() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = model(repository)
        bind(model); model.prepare(false); runCurrent()
        val plan = requireNotNull(model.state.value.plan)
        repeat(plan.chunks.size) {
            model.togglePlay(); runCurrent(); advanceTimeBy(1)
            model.next(); runCurrent()
        }
        val run = repository.saveAttempts.last()
        assertTrue(run.completed) // Each block appeared, but never for the scheduled exposure.
        assertEquals(true, run.pacingStable)
        assertEquals(false, run.pacedCompletion)
        assertEquals(plan.chunks.size.toLong(), run.activeMillis)
    }

    @Test fun pauseAndBackwardRepeatPreserveAutomaticExposureEvidenceAcrossRestore() = runTest(dispatcher) {
        val repository = FakeRepository()
        val saved = SavedStateHandle()
        val original = model(repository, saved)
        bind(original); original.prepare(false); runCurrent()
        val plan = requireNotNull(original.state.value.plan)
        original.togglePlay(); runCurrent()
        advanceTimeBy(duration(plan, 0, 60) + 50); runCurrent()
        original.pause(); original.previous(); original.togglePlay(); runCurrent()
        advanceTimeBy(120); original.pause()
        val snapshot = Bundle(requireNotNull(saved.get<Bundle>(STATE_KEY)))
        stores.remove(original)!!.clear(); runCurrent()
        val restored = model(repository, SavedStateHandle(mapOf(STATE_KEY to snapshot)))
        bind(restored); runCurrent()
        assertFalse(restored.state.value.playing)
        advanceTimeBy(10_000); restored.togglePlay(); runCurrent()
        advanceTimeBy(100_000); runCurrent()
        val run = requireNotNull(restored.state.value.result)
        assertTrue(run.completed)
        assertEquals(true, run.pacedCompletion)
        // Both the explicit backward replay of block 0 and the later replay of already-seen
        // block 1 count as actual visits; pause/resume inside block 0 adds no extra visit.
        assertEquals(2, run.revisits)
        assertEquals(listOf(2, 2), requireNotNull(run.chunkInteractions).take(2).map { it.visits })
        assertEquals(listOf(1, 1), requireNotNull(run.chunkInteractions).take(2).map { it.revisits })
    }

    @Test fun legacyCheckpointDoesNotGainPacedEvidenceOnAutomaticFinish() = runTest(dispatcher) {
        val repository = FakeRepository()
        val saved = SavedStateHandle()
        val original = model(repository, saved)
        bind(original); original.prepare(false); runCurrent()
        val snapshot = Bundle(requireNotNull(saved.get<Bundle>(STATE_KEY))).apply {
            remove("manual-advance"); remove("paced-blocks")
        }
        stores.remove(original)!!.clear(); runCurrent()
        val restored = model(repository, SavedStateHandle(mapOf(STATE_KEY to snapshot)))
        bind(restored); runCurrent(); restored.togglePlay(); runCurrent()
        advanceTimeBy(100_000); runCurrent()
        assertNull(restored.state.value.result!!.pacedCompletion)
    }

    @Test fun personalCutAppliesOnNextRunAndFailedSaveKeepsTheEditorOpen() = runTest(dispatcher) {
        val repository = FakeRepository().apply { remainingEditFailures = 1 }
        val model = model(repository)
        bind(model); model.prepare(false); runCurrent()
        val activePlan = requireNotNull(model.state.value.plan)
        model.togglePlay(); runCurrent(); advanceTimeBy(40)
        model.editSegmentation(); runCurrent()
        assertFalse(model.state.value.playing)
        val draft = requireNotNull(model.state.value.segmentation)
        val breaks = if (draft.breaks.isNotEmpty()) draft.breaks.drop(1) else listOf(draft.legalBreaks.first())
        model.togglePlay(); advanceTimeBy(10_000); runCurrent()
        assertFalse(model.state.value.playing)
        model.saveSegmentation(breaks); model.saveSegmentation(breaks); runCurrent()
        assertEquals(1, repository.editCalls)
        assertEquals(draft, model.state.value.segmentation)
        assertNotNull(model.state.value.segmentationError)
        assertEquals(activePlan, model.state.value.plan)
        model.saveSegmentation(breaks); runCurrent()
        assertNull(model.state.value.segmentation)
        assertNull(model.state.value.segmentationError)
        assertNotNull(model.state.value.segmentationMessage)
        assertEquals(activePlan, model.state.value.plan)
        model.restart(); model.prepare(false); runCurrent()
        val personalPlan = requireNotNull(model.state.value.plan)
        assertEquals("manual", personalPlan.origin)
        assertEquals(activePlan.planKey, personalPlan.basePlanKey)
        model.editSegmentation(); runCurrent(); model.restoreSegmentation(); runCurrent()
        assertEquals(personalPlan, model.state.value.plan)
        model.restart(); model.prepare(false); runCurrent()
        assertEquals(activePlan, model.state.value.plan)
    }

    @Test fun previewExposureIsSavedEvenWhenTheUserClosesWithoutPlayingOrEditing() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = model(repository)
        bind(model); model.prepare(false); runCurrent()
        val plan = requireNotNull(model.state.value.plan)
        model.editSegmentation(); runCurrent()
        assertEquals(listOf(plan.planKey to 0), repository.previewed)
        model.closeSegmentation()
        var left = false
        model.leave { left = true }; runCurrent()
        assertTrue(left)
        assertTrue(repository.saveAttempts.isEmpty())
        assertTrue(repository.library.value.corrections.isEmpty())
        assertEquals(0, repository.editCalls)
    }

    @Test fun threeRoundsReuseFrozenPlanAndOnlyTheFirstAsksQuestions() = runTest(dispatcher) {
        val repository = FakeRepository(withQuestions = true)
        val saved = SavedStateHandle()
        val model = model(repository, saved)
        bind(model); model.setCount(2); model.prepare(true); runCurrent()
        val plan = requireNotNull(model.state.value.plan)
        repeat(2) {
            model.togglePlay(); runCurrent(); advanceTimeBy(100_000); runCurrent()
            assertEquals(SpeedStage.QUESTION, model.state.value.stage)
            model.answer(1); model.continueAfterQuestion(); runCurrent()
        }
        val guided = requireNotNull(model.state.value.result)
        assertTrue(model.state.value.canContinueRound)
        assertEquals(2, guided.answers.size)
        model.nextRound(); model.nextRound(); runCurrent()
        assertEquals(SpeedPacedRound.FADED, model.state.value.round)
        assertEquals(plan, model.state.value.plan)
        assertFalse(model.state.value.playing)
        model.togglePlay(); runCurrent(); advanceTimeBy(100_000); runCurrent()
        val faded = requireNotNull(model.state.value.result)
        assertEquals(SpeedStage.RESULT, model.state.value.stage)
        assertEquals(guided.id, faded.parentRunId)
        assertFalse(faded.firstAttempt)
        assertEquals(0, faded.questionCount)
        assertTrue(faded.answers.isEmpty())
        assertNull(SpeedMetrics.summarize(faded).comprehension)
        assertEquals(listOf(guided, faded), repository.library.value.runs)
        assertEquals(1, repository.prepareRequests.size)

        model.nextRound(); runCurrent()
        assertNotNull(model.state.value.natural)
        assertFalse(model.state.value.natural!!.playing)
        model.naturalPlayPause(); advanceTimeBy(1000)
        model.setForeground(false); advanceTimeBy(50_000)
        assertEquals(1000L, model.state.value.natural!!.elapsedMillis)
        model.setForeground(true)
        assertFalse(model.state.value.natural!!.playing)
        model.naturalPlayPause(); advanceTimeBy(500); model.naturalCompleteParagraph(); runCurrent()
        model.naturalPlayPause(); advanceTimeBy(2000); model.naturalCompleteParagraph(); runCurrent()
        val natural = requireNotNull(model.state.value.natural!!.result)
        assertEquals(faded.id, natural.parentRunId)
        assertEquals(plan.planKey, natural.planKey)
        assertEquals(listOf(1500L, 2000L), natural.paragraphs.map { it.activeMillis })
        assertEquals(1, repository.prepareRequests.size)
        assertEquals(listOf(guided, faded), repository.library.value.runs)
        assertEquals(listOf(natural), repository.library.value.naturalAttempts)

        val bundle = Bundle(requireNotNull(saved.get<Bundle>(STATE_KEY)))
        stores.remove(model)!!.clear(); runCurrent()
        val restored = model(repository, SavedStateHandle(mapOf(STATE_KEY to bundle)))
        bind(restored); runCurrent()
        assertEquals(natural, restored.state.value.natural!!.result)
        assertEquals(faded, restored.state.value.result)
        assertFalse(restored.state.value.natural!!.playing)
        assertEquals(1, repository.prepareRequests.size)
    }

    @Test fun fadedCheckpointRestoresTheRoundAndParentWithoutReaskingQuestions() = runTest(dispatcher) {
        val repository = FakeRepository(withQuestions = true)
        val saved = SavedStateHandle()
        val first = model(repository, saved)
        bind(first); first.prepare(true); runCurrent()
        first.togglePlay(); runCurrent(); advanceTimeBy(100_000); runCurrent()
        first.answer(1); first.continueAfterQuestion(); runCurrent()
        val guided = requireNotNull(first.state.value.result)
        first.nextRound(); first.togglePlay(); runCurrent(); advanceTimeBy(200); first.pause()
        val bundle = Bundle(requireNotNull(saved.get<Bundle>(STATE_KEY)))
        stores.remove(first)!!.clear(); runCurrent(); advanceTimeBy(60_000)
        val restored = model(repository, SavedStateHandle(mapOf(STATE_KEY to bundle)))
        bind(restored); runCurrent()
        assertEquals(SpeedPacedRound.FADED, restored.state.value.round)
        assertFalse(restored.state.value.playing)
        restored.togglePlay(); runCurrent(); advanceTimeBy(100_000); runCurrent()
        val result = requireNotNull(restored.state.value.result)
        assertEquals(guided.id, result.parentRunId)
        assertTrue(result.answers.isEmpty())
        assertEquals(0, result.questionCount)
        assertEquals(1, repository.prepareRequests.size)
    }

    @Test fun corruptNaturalCheckpointCannotHijackANewGuidedRunAfterRecovery() = runTest(dispatcher) {
        val repository = FakeRepository()
        val saved = SavedStateHandle()
        val first = model(repository, saved)
        bind(first); first.prepare(false); runCurrent()
        repeat(2) {
            first.togglePlay(); runCurrent(); advanceTimeBy(100_000); runCurrent()
            first.nextRound(); runCurrent()
        }
        assertNotNull(first.state.value.natural)
        val broken = Bundle(requireNotNull(saved.get<Bundle>(STATE_KEY)))
        broken.getBundle("natural")!!.remove("times")
        stores.remove(first)!!.clear(); runCurrent()
        val restored = model(repository, SavedStateHandle(mapOf(STATE_KEY to broken)))
        bind(restored); runCurrent()
        assertEquals(SpeedStage.SETUP, restored.state.value.stage)
        assertNull(restored.state.value.natural)
        assertNull(restored.state.value.plan)
        restored.prepare(false); runCurrent(); restored.togglePlay(); runCurrent(); advanceTimeBy(300)
        var left = false
        restored.leave { left = true }; runCurrent()
        assertTrue(left)
        assertTrue(repository.library.value.naturalAttempts.isEmpty())
        val new = repository.saveAttempts.last()
        assertEquals(SpeedPacedRound.GUIDED, new.round)
        assertEquals(300L, new.activeMillis)
        assertFalse(new.completed)
    }

    @Test fun restoredTrainingUsesCachedPlanWithoutAiAndRemainsPaused() = runTest(dispatcher) {
        val repository = FakeRepository(withQuestions = true)
        val saved = SavedStateHandle()
        val original = model(repository, saved)
        bind(original)
        original.setCount(2)
        original.prepare(true)
        runCurrent()
        val plan = requireNotNull(original.state.value.plan)
        original.togglePlay()
        runCurrent()
        advanceTimeBy(150)
        original.pause()
        original.seek(1)
        original.setSpeed(105)
        original.setSentenceOnly(true)
        original.togglePlay()
        runCurrent()
        advanceTimeBy(120)
        original.pause()
        val snapshot = Bundle(requireNotNull(saved.get<Bundle>(STATE_KEY)))
        stores.remove(original)!!.clear()
        runCurrent()

        val preferences = FakePreferences(PersonalizationProfile(granularity = SpeedGranularity.L3,
            rates = mapOf(SpeedGranularity.L3 to 130), sentenceOnly = false))
        val restored = model(repository, SavedStateHandle(mapOf(STATE_KEY to snapshot)), preferences)
        bind(restored)
        runCurrent()
        assertEquals(listOf(plan.planKey), repository.cacheReads)
        assertEquals(1, repository.prepareRequests.size)
        assertEquals(plan, restored.state.value.plan)
        assertEquals(SpeedStage.READING, restored.state.value.stage)
        assertEquals(1, restored.state.value.chunkIndex)
        assertEquals(105, restored.state.value.groupsPerMinute)
        assertTrue(restored.state.value.sentenceOnly)
        assertFalse(restored.state.value.playing)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, restored.state.value.chunkIndex)
        assertFalse(restored.state.value.playing)
        restored.leave()
        runCurrent()
        assertEquals(270L, repository.saveAttempts.single().activeMillis)
        assertEquals(false, repository.saveAttempts.single().pacingStable)
    }

    @Test fun obsoleteAsyncPrepareCannotReplaceTheNewerArticle() = runTest(dispatcher) {
        val repository = FakeRepository()
        var pending: Continuation<SpeedPlan>? = null
        repository.prepareHook = { request ->
            if (request.article.id == ARTICLE.id) suspendCoroutine { pending = it }
            else plan(request, false)
        }
        val model = model(repository)
        bind(model)
        model.prepare(true)
        runCurrent()
        assertTrue(model.state.value.loading)
        assertNotNull(pending)
        val oldRequest = repository.prepareRequests.single()

        val newerArticle = ARTICLE.copy(id = "newer-article", guid = "newer-guid")
        val newerBody = BODY.copy(articleId = newerArticle.id, contentHash = "newer-body-hash")
        model.bind(newerArticle, newerBody, true, true, 2, 0)
        model.prepare(false)
        runCurrent()
        val newerPlan = requireNotNull(model.state.value.plan)
        assertEquals(newerArticle.id, newerPlan.articleId)

        // Simulate an uncancellable callback that arrives after the user opens another article.
        requireNotNull(pending).resume(plan(oldRequest, false))
        runCurrent()
        assertEquals(newerPlan, model.state.value.plan)
        assertEquals(SpeedStage.READING, model.state.value.stage)
        assertFalse(model.state.value.loading)
        assertNull(model.state.value.error)
        assertEquals(2, repository.prepareRequests.size)
    }

    @Test fun failedSaveRetriesTheSameImmutableRunAndIdentifier() = runTest(dispatcher) {
        val repository = FakeRepository().apply { remainingSaveFailures = 1 }
        val model = model(repository)
        bind(model)
        model.prepare(false)
        runCurrent()
        model.togglePlay()
        runCurrent()
        advanceTimeBy(100_000)
        runCurrent()

        assertEquals(SpeedStage.RESULT, model.state.value.stage)
        assertNotNull(model.state.value.error)
        assertFalse(model.state.value.saving)
        val firstAttempt = repository.saveAttempts.single()
        assertTrue(firstAttempt.completed)
        assertTrue(repository.library.value.runs.isEmpty())

        advanceTimeBy(60_000)
        model.continueAfterQuestion()
        runCurrent()

        assertEquals(2, repository.saveAttempts.size)
        assertSame(firstAttempt, repository.saveAttempts.last())
        assertEquals(firstAttempt, repository.library.value.runs.single())
        assertEquals(firstAttempt.id, model.state.value.result!!.id)
        assertEquals(firstAttempt.finishedAt, model.state.value.result!!.finishedAt)
        assertNull(model.state.value.error)
        assertFalse(model.state.value.saving)
        assertTrue(model.state.value.canContinueParagraph)
    }

    @Test fun leavingAfterSaveFailureKeepsTheCheckpointUntilRetrySucceeds() = runTest(dispatcher) {
        val repository = FakeRepository().apply { remainingSaveFailures = 1 }
        val saved = SavedStateHandle()
        val model = model(repository, saved)
        var exits = 0
        bind(model)
        model.prepare(false)
        runCurrent()
        model.togglePlay()
        runCurrent()
        advanceTimeBy(200)
        model.leave { exits++ }
        runCurrent()

        assertEquals(0, exits)
        assertEquals(SpeedStage.READING, model.state.value.stage)
        assertFalse(model.state.value.playing)
        assertFalse(model.state.value.saving)
        assertNotNull(model.state.value.error)
        assertNotNull(saved.get<Bundle>(STATE_KEY))
        val failedRun = repository.saveAttempts.single()
        assertFalse(failedRun.completed)

        advanceTimeBy(10_000)
        model.leave { exits++ }
        runCurrent()

        assertEquals(1, exits)
        assertNull(saved.get<Bundle>(STATE_KEY))
        assertEquals(failedRun.id, repository.saveAttempts.last().id)
        assertEquals(200L, repository.library.value.runs.single().activeMillis)
        assertEquals(1, repository.library.value.runs.size)
    }

    @Test fun restoringAnUnsavedResultPreservesItsFinishTimeAndSavesOnlyOneRun() = runTest(dispatcher) {
        val repository = FakeRepository().apply { remainingSaveFailures = 1 }
        val saved = SavedStateHandle()
        val original = model(repository, saved)
        bind(original)
        original.prepare(false)
        runCurrent()
        original.togglePlay()
        runCurrent()
        advanceTimeBy(100_000)
        runCurrent()
        val failedRun = repository.saveAttempts.single()
        assertEquals(SpeedStage.RESULT, original.state.value.stage)
        assertNotNull(original.state.value.error)
        val snapshot = Bundle(requireNotNull(saved.get<Bundle>(STATE_KEY)))
        stores.remove(original)!!.clear()
        runCurrent()
        advanceTimeBy(60_000)

        val restored = model(repository, SavedStateHandle(mapOf(STATE_KEY to snapshot)))
        bind(restored)
        runCurrent()

        assertEquals(SpeedStage.RESULT, restored.state.value.stage)
        assertFalse(restored.state.value.playing)
        assertFalse(restored.state.value.saving)
        assertNull(restored.state.value.error)
        assertEquals(1, repository.prepareRequests.size)
        assertEquals(2, repository.saveAttempts.size)
        assertEquals(failedRun, repository.saveAttempts.last())
        assertEquals(failedRun, repository.library.value.runs.single())
        assertEquals(failedRun, restored.state.value.result)
    }

    @Test fun enabledProfileRestoresPerGranularityRatesAndRemembersConfirmedConfiguration() = runTest(dispatcher) {
        val preferences = FakePreferences(PersonalizationProfile(granularity = SpeedGranularity.L3,
            rates = mapOf(SpeedGranularity.L1 to 75, SpeedGranularity.L2 to 95, SpeedGranularity.L3 to 110), sentenceOnly = true))
        val repository = FakeRepository()
        val model = model(repository, preferences = preferences)
        bind(model)
        runCurrent()
        assertTrue(model.state.value.personalizationAvailable)
        assertEquals(SpeedGranularity.L3, model.state.value.selectedGranularity)
        assertEquals(110, model.state.value.groupsPerMinute)
        assertTrue(model.state.value.sentenceOnly)

        model.setGranularity(SpeedGranularity.L1)
        assertEquals(75, model.state.value.groupsPerMinute)
        model.setSpeed(80)
        model.setSentenceOnly(false)
        model.prepare(false)
        runCurrent()
        assertEquals(Triple(SpeedGranularity.L1, 80, false), preferences.rememberCalls.single())
        assertEquals(80, preferences.current.value.rateFor(SpeedGranularity.L1))
        assertEquals(110, preferences.current.value.rateFor(SpeedGranularity.L3))
        assertFalse(model.state.value.personalizationBusy)
    }

    @Test fun disabledMemoryUsesFreshDefaultsAndDoesNotWriteTrainingChoices() = runTest(dispatcher) {
        val preferences = FakePreferences(PersonalizationProfile(enabled = false, granularity = SpeedGranularity.L3,
            rates = mapOf(SpeedGranularity.L3 to 110), sentenceOnly = true))
        val repository = FakeRepository()
        val model = model(repository, preferences = preferences)
        bind(model)
        runCurrent()
        assertEquals(SpeedGranularity.L2, model.state.value.selectedGranularity)
        assertEquals(60, model.state.value.groupsPerMinute)
        assertFalse(model.state.value.sentenceOnly)
        model.setGranularity(SpeedGranularity.L3)
        model.setSpeed(90)
        model.setSentenceOnly(true)
        model.prepare(false)
        runCurrent()
        model.togglePlay()
        runCurrent()
        advanceTimeBy(100)
        var exits = 0
        model.leave { exits++ }
        runCurrent()
        assertEquals(1, exits)
        assertTrue(preferences.rememberCalls.isEmpty())

        model.bind(ARTICLE, BODY, true, true, 2, 0)
        runCurrent()
        assertEquals(SpeedGranularity.L2, model.state.value.selectedGranularity)
        assertEquals(60, model.state.value.groupsPerMinute)
        assertFalse(model.state.value.sentenceOnly)
    }

    @Test fun delayedProfileCannotOverwriteTheNewEntryUserConfiguration() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val preferences = FakePreferences(PersonalizationProfile(granularity = SpeedGranularity.L3,
            rates = mapOf(SpeedGranularity.L3 to 120), sentenceOnly = true), gate)
        val repository = FakeRepository()
        val model = model(repository, preferences = preferences)
        bind(model)
        runCurrent()
        model.bind(ARTICLE.copy(id = "new"), BODY.copy(articleId = "new"), true, true, 2, 0)
        model.setGranularity(SpeedGranularity.L1)
        model.setSpeed(100)
        gate.complete(Unit)
        runCurrent()
        assertEquals(SpeedGranularity.L1, model.state.value.selectedGranularity)
        assertEquals(100, model.state.value.groupsPerMinute)
        assertTrue("Untouched display preference comes from the delayed profile", model.state.value.sentenceOnly)
        assertEquals("Confirmed SETUP edits are saved without preparing", SpeedGranularity.L1, model.state.value.profile.granularity)

        model.bind(ARTICLE, BODY, true, true, 3, 0)
        runCurrent()
        assertEquals(SpeedGranularity.L1, model.state.value.selectedGranularity)
        assertEquals(100, model.state.value.groupsPerMinute)
        assertTrue(model.state.value.sentenceOnly)
    }

    @Test fun speedChosenBeforePlaybackRemainsStableButPausedMidRunChangesDoNot() = runTest(dispatcher) {
        val repository = FakeRepository()
        val saved = SavedStateHandle()
        val model = model(repository, saved)
        bind(model)
        model.setSpeed(120)
        model.prepare(false)
        runCurrent()
        model.setSpeed(90)
        assertEquals(true, saved.get<Bundle>(STATE_KEY)!!.getBoolean("pacing-stable"))
        model.togglePlay()
        runCurrent()
        advanceTimeBy(100)
        model.pause()
        model.setSpeed(80)
        assertEquals(false, saved.get<Bundle>(STATE_KEY)!!.getBoolean("pacing-stable"))
        model.leave()
        runCurrent()
        assertEquals(false, repository.saveAttempts.single().pacingStable)
    }

    @Test fun oldSavedStateDoesNotInventStablePacingEvidence() = runTest(dispatcher) {
        val repository = FakeRepository()
        val saved = SavedStateHandle()
        val original = model(repository, saved)
        bind(original)
        original.prepare(false)
        runCurrent()
        original.togglePlay()
        runCurrent()
        advanceTimeBy(100)
        original.pause()
        val snapshot = Bundle(requireNotNull(saved.get<Bundle>(STATE_KEY))).apply { remove("pacing-stable") }
        stores.remove(original)!!.clear()
        runCurrent()
        val restored = model(repository, SavedStateHandle(mapOf(STATE_KEY to snapshot)))
        bind(restored)
        runCurrent()
        restored.leave()
        runCurrent()
        assertNull(repository.saveAttempts.single().pacingStable)
    }

    @Test fun applyingNextPaceDoesNotTouchTheRunningTimerOrGetOverwrittenAtFinish() = runTest(dispatcher) {
        val preferences = FakePreferences()
        val repository = FakeRepository().apply {
            library.value = SpeedLibrary(runs = listOf(eligiblePaceRun(1), eligiblePaceRun(2)))
        }
        val model = model(repository, preferences = preferences)
        bind(model)
        runCurrent()
        val suggestion = requireNotNull(model.state.value.suggestion)
        assertEquals(63, suggestion.toRate)
        model.prepare(false)
        runCurrent()
        val plan = requireNotNull(model.state.value.plan)
        model.togglePlay()
        runCurrent()
        advanceTimeBy(100)
        model.applyPaceSuggestion(suggestion.id)
        model.applyPaceSuggestion(suggestion.id)
        runCurrent()
        assertEquals(1, preferences.applied.size)
        assertEquals(63, model.state.value.profile.rateFor())
        assertEquals(60, model.state.value.groupsPerMinute)
        assertTrue(model.state.value.playing)

        advanceTimeBy(duration(plan, 0, 60) - 100)
        runCurrent()
        assertEquals(1, model.state.value.chunkIndex)
        advanceTimeBy(100_000)
        runCurrent()
        assertEquals(SpeedStage.RESULT, model.state.value.stage)
        assertEquals(63, preferences.current.value.rateFor())
        assertEquals(true, repository.saveAttempts.last { it.completed }.pacingStable)
        assertTrue(model.state.value.canContinueParagraph)
        model.nextParagraph()
        assertEquals(SpeedStage.SETUP, model.state.value.stage)
        assertEquals(1, model.state.value.startParagraph)
        assertEquals(1, model.state.value.paragraphCount)
        assertEquals(63, model.state.value.groupsPerMinute)
        assertNull(model.state.value.plan)
        assertNull(model.state.value.result)
        assertTrue(model.state.value.answers.isEmpty())
        assertEquals(1, repository.prepareRequests.size)
    }

    @Test fun preferenceFailuresStaySeparateFromTrainingSaveErrors() = runTest(dispatcher) {
        val preferences = FakePreferences().apply { failNextChange = true }
        val model = model(FakeRepository(), preferences = preferences)
        bind(model)
        runCurrent()
        model.setRememberTraining(false)
        model.setRememberTraining(false)
        runCurrent()
        assertNull(model.state.value.error)
        assertNotNull(model.state.value.personalizationError)
        assertFalse(model.state.value.personalizationBusy)
        assertTrue(model.state.value.profile.enabled)
        model.setRememberTraining(false)
        runCurrent()
        assertFalse(model.state.value.profile.enabled)
        assertNull(model.state.value.personalizationError)
    }

    @Test fun appliedNextPaceSurvivesResultRestorationWithoutRewritingTheOldRun() = runTest(dispatcher) {
        val preferences = FakePreferences()
        val repository = FakeRepository().apply {
            library.value = SpeedLibrary(runs = listOf(eligiblePaceRun(1), eligiblePaceRun(2)))
        }
        val saved = SavedStateHandle()
        val original = model(repository, saved, preferences)
        bind(original)
        runCurrent()
        original.prepare(false)
        runCurrent()
        original.togglePlay()
        runCurrent()
        advanceTimeBy(100_000)
        runCurrent()
        val oldRun = requireNotNull(original.state.value.result)
        val suggestion = requireNotNull(original.state.value.suggestion)
        original.applyPaceSuggestion(suggestion.id)
        runCurrent()
        assertEquals(60, original.state.value.groupsPerMinute)
        val snapshot = Bundle(requireNotNull(saved.get<Bundle>(STATE_KEY)))
        assertEquals(63, snapshot.getInt("next-speed"))
        stores.remove(original)!!.clear()
        runCurrent()

        val restored = model(repository, SavedStateHandle(mapOf(STATE_KEY to snapshot)), preferences)
        bind(restored)
        runCurrent()
        assertEquals(oldRun, restored.state.value.result)
        assertEquals(60, restored.state.value.groupsPerMinute)
        restored.leave()
        runCurrent()
        assertEquals(63, preferences.current.value.rateFor(SpeedGranularity.L2))
        assertEquals(oldRun, repository.library.value.runs.single { it.id == oldRun.id })
    }

    @Test fun aLaterManualRateWinsOverAnAppliedNextPace() = runTest(dispatcher) {
        val preferences = FakePreferences()
        val repository = FakeRepository().apply {
            library.value = SpeedLibrary(runs = listOf(eligiblePaceRun(1), eligiblePaceRun(2)))
        }
        val model = model(repository, preferences = preferences)
        bind(model)
        runCurrent()
        val suggestion = requireNotNull(model.state.value.suggestion)
        model.prepare(false)
        runCurrent()
        model.togglePlay()
        runCurrent()
        advanceTimeBy(100)
        model.applyPaceSuggestion(suggestion.id)
        runCurrent()
        model.setSpeed(80)
        model.leave()
        runCurrent()
        assertEquals(80, preferences.current.value.rateFor(SpeedGranularity.L2))
        assertEquals(false, repository.saveAttempts.last().pacingStable)
        assertNull(model.state.value.lastApplied)
    }

    private fun model(repository: FakeRepository, saved: SavedStateHandle = SavedStateHandle(),
        preferences: PersonalizationRepository? = null): SpeedReadingViewModel {
        val model = SpeedReadingViewModel(repository, saved,
            wall = { WALL_START + scheduler.currentTime }, monotonic = { scheduler.currentTime },
            computationDispatcher = dispatcher, personalizationRepository = preferences)
        stores[model] = ViewModelStore().also { it.put("speed", model) }
        return model
    }

    private fun bind(model: SpeedReadingViewModel) = model.bind(ARTICLE, BODY, true, true, 1, 0)

    private fun duration(plan: SpeedPlan, index: Int, speed: Int): Long = SpeedPacing.durationMillis(
        plan.chunks[index], speed, plan.chunks.sumOf { it.wordCount }.toDouble() / plan.chunks.size,
    )

    private data class PrepareRequest(
        val article: Article, val body: ArticleBody, val granularity: SpeedGranularity,
        val startParagraph: Int, val paragraphCount: Int, val aiAllowed: Boolean, val useAi: Boolean,
    )

    private class FakeRepository(private val withQuestions: Boolean = false) : SpeedReadingRepository {
        override val library = MutableStateFlow(SpeedLibrary())
        val prepareRequests = mutableListOf<PrepareRequest>()
        val cacheReads = mutableListOf<String>()
        val cache = mutableMapOf<String, SpeedPlan>()
        val saveAttempts = mutableListOf<SpeedRun>()
        var prepareHook: (suspend (PrepareRequest) -> SpeedPlan)? = null
        var remainingSaveFailures = 0
        var remainingEditFailures = 0
        var editCalls = 0
        var personalOverride: SpeedPlan? = null
        val previewed = mutableListOf<Pair<String, Int>>()

        override suspend fun cachedPlan(planKey: String): SpeedPlan? {
            cacheReads += planKey
            return cache[planKey]
        }

        override suspend fun prepare(
            article: Article, body: ArticleBody, granularity: SpeedGranularity,
            startParagraph: Int, paragraphCount: Int, aiAllowed: Boolean, useAi: Boolean,
        ): SpeedPlan {
            val request = PrepareRequest(article, body, granularity, startParagraph, paragraphCount, aiAllowed, useAi)
            prepareRequests += request
            val plan = personalOverride ?: prepareHook?.invoke(request) ?: plan(request, withQuestions)
            cache[plan.planKey] = plan
            return plan
        }

        override suspend fun saveRun(run: SpeedRun) {
            saveAttempts += run
            if (remainingSaveFailures > 0) {
                remainingSaveFailures--
                throw IOException("Test storage unavailable")
            }
            library.value = library.value.copy(runs = library.value.runs.filterNot { it.id == run.id } + run)
        }

        override suspend fun saveNaturalAttempt(attempt: NaturalReadingAttempt) {
            library.value = library.value.copy(naturalAttempts = library.value.naturalAttempts.filterNot { it.id == attempt.id } + attempt)
        }

        override suspend fun saveCorrection(correction: SpeedCorrection) {
            library.value = library.value.copy(corrections = library.value.corrections + correction)
        }

        override suspend fun sentenceForEditing(planKey: String, chunkIndex: Int): SpeedSegmentation? {
            val plan = cache[planKey] ?: return null
            return SpeedSegmentationEditor.sentence(plan, TEXT, chunkIndex, plan.basePlanKey?.let { cache[it] })
        }

        override suspend fun markSentencePreviewed(planKey: String, paragraph: Int) { previewed += planKey to paragraph }

        override suspend fun editSentence(planKey: String, paragraph: Int, sentence: Int, breakOffsets: List<Int>): SpeedPlan {
            editCalls++
            if (remainingEditFailures-- > 0) throw IOException("Test edit write failed")
            val before = cache.getValue(planKey)
            val next = SpeedSegmentationEditor.edit(before, TEXT, paragraph, sentence, breakOffsets, before.basePlanKey?.let { cache[it] })
            return next.copy(planKey = SpeedChunker.planKey(next)).also { cache[it.planKey] = it; personalOverride = it }
        }

        override suspend fun restoreSegmentation(planKey: String): SpeedPlan {
            val plan = cache.getValue(planKey)
            personalOverride = null
            return plan.basePlanKey?.let { cache.getValue(it) } ?: plan
        }
    }

    private class FakePreferences(initial: PersonalizationProfile = PersonalizationProfile(), gate: CompletableDeferred<Unit>? = null) : PersonalizationRepository {
        val current = MutableStateFlow(initial)
        override val state: Flow<PersonalizationProfile> = if (gate == null) current else flow { gate.await(); emitAll(current) }
        val rememberCalls = mutableListOf<Triple<SpeedGranularity, Int, Boolean>>()
        val applied = mutableListOf<PaceSuggestion>()
        var failNextChange = false
        override suspend fun remember(granularity: SpeedGranularity, rate: Int, sentenceOnly: Boolean, expectedRevision: Long, paceManuallyAdjusted: Boolean): PersonalizationProfile {
            checkRevision(expectedRevision)
            rememberCalls += Triple(granularity, rate, sentenceOnly)
            val next = current.value.copy(granularity = granularity, rates = current.value.rates + (granularity to rate), sentenceOnly = sentenceOnly)
            return commit(next)
        }
        override suspend fun invalidatePaceBinding(expectedRevision: Long): PersonalizationProfile {
            checkRevision(expectedRevision)
            return commit(current.value.copy(activePaceBinding = null))
        }
        override suspend fun setEnabled(enabled: Boolean, expectedRevision: Long): PersonalizationProfile {
            checkRevision(expectedRevision)
            return commit(current.value.copy(enabled = enabled))
        }
        override suspend fun apply(suggestion: PaceSuggestion, expectedRevision: Long): PersonalizationProfile {
            checkRevision(expectedRevision)
            applied += suggestion
            val decision = PersonalizationDecision(suggestion.id, suggestion, PersonalizationDecisionStatus.APPLIED,
                1_000, appliedRevision = current.value.revision + 1)
            return commit(current.value.copy(rates = current.value.rates + (suggestion.granularity to suggestion.toRate),
                decisions = current.value.decisions + decision))
        }
        override suspend fun dismiss(suggestion: PaceSuggestion, expectedRevision: Long): PersonalizationProfile {
            checkRevision(expectedRevision)
            return commit(current.value.copy(decisions = current.value.decisions + PersonalizationDecision(suggestion.id, suggestion,
                PersonalizationDecisionStatus.DISMISSED, 1_000)))
        }
        override suspend fun undo(decisionId: String, expectedRevision: Long): PersonalizationProfile {
            checkRevision(expectedRevision)
            val decision = current.value.decisions.single { it.id == decisionId }
            return commit(current.value.copy(rates = current.value.rates + (decision.suggestion.granularity to decision.suggestion.fromRate),
                decisions = current.value.decisions.map { if (it.id == decisionId) it.copy(status = PersonalizationDecisionStatus.UNDONE) else it }))
        }
        private fun checkRevision(revision: Long) {
            if (failNextChange) { failNextChange = false; throw IOException("Test preference write failed") }
            if (revision != current.value.revision) throw PersonalizationConflict("Stale test revision")
        }
        private fun commit(next: PersonalizationProfile): PersonalizationProfile {
            if (next != current.value) current.value = next.copy(revision = current.value.revision + 1)
            return current.value
        }
    }

    companion object {
        private const val STATE_KEY = "speed-training-state"
        private const val WALL_START = 1_000_000L
        private const val TEXT = "Coastal cities face growing risks from rising sea levels. " +
            "Local councils plan stronger flood defences.\n\n" +
            "Researchers compare new measurements with earlier records. " +
            "Their report describes the change in regional temperatures."
        private val ARTICLE = Article("article", "guid", listOf("source"), "Coastal research",
            "https://example.test/article", "Research report")
        private val BODY = ArticleBody(ARTICLE.id, TEXT, "body-hash", WALL_START, WALL_START + 30_000_000L, "Test fixture")

        private fun eligiblePaceRun(index: Int) = SpeedRun("prior-$index", "prior-article-$index", "prior-body-$index",
            SpeedGranularity.L2, "prior-plan-$index", index * 10_000L, index * 10_000L + 3_000,
            3_000, 12, 3, listOf(1_000, 1_000, 1_000), 0, 0,
            (0..2).map { SpeedAnswer("prior-$index-q$it", 0, true) }, 3, true, 60, "ai", true, true, true)

        private fun plan(request: PrepareRequest, withQuestions: Boolean): SpeedPlan {
            val base = SpeedChunker.plan(request.article.id, request.body.contentHash, request.body.text,
                request.granularity, request.startParagraph, request.paragraphCount, WALL_START)
            val paragraphs = ParagraphFocus.paragraphs(request.body.text)
            val questions = if (!withQuestions) emptyList() else base.paragraphIndices.map { index ->
                val paragraph = paragraphs[index]
                SpeedQuestion("question-$index", index, "Which statement matches this paragraph?",
                    listOf("A conflicting claim", "The claim supported by the paragraph", "An unrelated claim"),
                    1, paragraph.start, paragraph.endExclusive, "The cited paragraph supports the second option.")
            }
            val plan = base.copy(questions = questions, origin = if (withQuestions) "ai" else "rules")
            SpeedChunker.validate(plan, request.body.text)
            return plan.copy(planKey = SpeedChunker.planKey(plan))
        }
    }
}
