package dev.lexicon.ide.ui

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class NaturalReadingControllerTest {
    @Test fun selfPacedParagraphTimesExcludePausesAndNeverInventGroupTimes() = runTest {
        val repository = FakeRepository()
        val controller = controller(repository)
        controller.completeParagraph()
        assertNotNull(controller.state.error)
        assertTrue(repository.saved.isEmpty())
        controller.toggle(true); advanceTimeBy(1200); controller.pause(true)
        advanceTimeBy(10_000); controller.toggle(true); advanceTimeBy(800)
        controller.completeParagraph(); runCurrent()
        assertEquals(1, controller.state.paragraphIndex)
        assertFalse(controller.state.playing)
        assertEquals(2000L, controller.state.elapsedMillis)
        assertEquals(2000L, repository.saved.single().paragraphs[0].activeMillis)
        assertFalse(repository.saved.single().completed)
        controller.toggle(true); advanceTimeBy(3000); controller.completeParagraph(); runCurrent()
        val result = requireNotNull(controller.state.result)
        assertTrue(result.completed)
        assertEquals(listOf(2000L, 3000L), result.paragraphs.map { it.activeMillis })
        assertEquals(1, result.pauses)
        assertEquals(5000L, NaturalReadingMetrics.summarize(result, PLAN).activeMillis)
        assertNotNull(NaturalReadingMetrics.summarize(result, PLAN).wordsPerMinute)
    }

    @Test fun restoredSelfPacedReadingStaysPausedAndDoesNotCountProcessGap() = runTest {
        val repository = FakeRepository()
        val first = controller(repository)
        first.toggle(true); advanceTimeBy(700); first.pause()
        val saved = Bundle(first.savedState())
        first.dispose(); advanceTimeBy(90_000)
        val restored = controller(repository)
        restored.restore(saved)
        assertFalse(restored.state.playing)
        assertEquals(700L, restored.state.elapsedMillis)
        restored.toggle(false)
        assertFalse(restored.state.playing)
        restored.toggle(true); advanceTimeBy(300); restored.completeParagraph(); runCurrent()
        assertEquals(1000L, repository.saved.single().paragraphs[0].activeMillis)
        assertNull(NaturalReadingMetrics.summarize(repository.saved.single(), PLAN).wordsPerMinute)
    }

    @Test fun finalWriteFailureRetriesTheSameSnapshotAndPreventsDoubleSave() = runTest {
        val repository = FakeRepository()
        val controller = controller(repository)
        controller.toggle(true); advanceTimeBy(1000); controller.completeParagraph(); runCurrent()
        repository.failNext = true
        controller.toggle(true); advanceTimeBy(2000); controller.completeParagraph()
        controller.save(); runCurrent()
        val failed = repository.saved.last()
        assertTrue(failed.completed)
        assertNotNull(controller.state.error)
        assertEquals(2, repository.saved.size)
        advanceTimeBy(60_000)
        controller.save(); runCurrent()
        assertEquals(3, repository.saved.size)
        assertEquals(failed, repository.saved.last())
        assertNull(controller.state.error)
        val restored = controller(repository)
        restored.restore(Bundle(controller.savedState()))
        assertEquals(failed, restored.state.result)
        assertFalse(restored.state.playing)
    }

    @Test fun leavingBeforeStartingDoesNotFabricateATrainingRecord() = runTest {
        val repository = FakeRepository()
        val controller = controller(repository)
        var exits = 0
        controller.save { exits++ }; runCurrent()
        assertEquals(1, exits)
        assertTrue(repository.saved.isEmpty())
    }

    @Test fun olderCheckpointMergesNewerPersistedParagraphTimeInsteadOfRegressingIt() = runTest {
        val repository = FakeRepository()
        val original = controller(repository)
        original.toggle(true); advanceTimeBy(600); original.pause()
        val older = Bundle(original.savedState())
        original.toggle(true); advanceTimeBy(400); original.completeParagraph(); runCurrent()
        original.dispose()
        val restored = controller(repository)
        restored.restore(older)
        assertEquals(1, restored.state.paragraphIndex)
        assertEquals(1000L, restored.state.elapsedMillis)
        restored.toggle(true); advanceTimeBy(1200); restored.completeParagraph(); runCurrent()
        assertEquals(listOf(1000L, 1200L), repository.saved.last().paragraphs.map { it.activeMillis })
        assertTrue(repository.saved.last().completed)
    }

    @Test fun restoringUnsavedCompletionOverAPersistedPartialCommitsTheFinalSnapshot() = runTest {
        val repository = FakeRepository()
        val original = controller(repository)
        original.toggle(true); advanceTimeBy(1000); original.completeParagraph(); runCurrent()
        repository.failNext = true
        original.toggle(true); advanceTimeBy(2000); original.completeParagraph(); runCurrent()
        val terminal = requireNotNull(original.state.result)
        assertFalse(repository.library.value.naturalAttempts.single().completed)
        val finalState = Bundle(original.savedState()); original.dispose()
        val restored = controller(repository)
        restored.restore(finalState); runCurrent()
        assertEquals(terminal, repository.library.value.naturalAttempts.single())
        assertEquals(terminal, restored.state.result)
        assertNull(restored.state.error)
    }

    private fun TestScope.controller(repository: FakeRepository) = NaturalReadingController(PLAN, PARENT, repository,
        this, { 1_000_000L + testScheduler.currentTime }, { testScheduler.currentTime }, {}, {})

    private class FakeRepository : SpeedReadingRepository {
        override val library = MutableStateFlow(SpeedLibrary())
        val saved = mutableListOf<NaturalReadingAttempt>()
        var failNext = false
        override suspend fun prepare(article: Article, body: ArticleBody, granularity: SpeedGranularity,
            startParagraph: Int, paragraphCount: Int, aiAllowed: Boolean, useAi: Boolean) = error("No new generation in transfer rounds")
        override suspend fun saveRun(run: SpeedRun) = error("Natural reading is not a paced run")
        override suspend fun saveCorrection(correction: SpeedCorrection) = Unit
        override suspend fun saveNaturalAttempt(attempt: NaturalReadingAttempt) {
            saved += attempt
            if (failNext) { failNext = false; throw IOException("Fixture storage failure") }
            library.value = library.value.copy(naturalAttempts = library.value.naturalAttempts.filterNot { it.id == attempt.id } + attempt)
        }
    }

    companion object {
        private val TEXT = "The local library offers a peaceful place to study.\n\nResidents can share books with their neighbours."
        private val BASE = SpeedChunker.plan("natural-fixture", "natural-hash", TEXT, SpeedGranularity.L2, 0, 2, 0)
        private val PLAN = BASE.copy(planKey = SpeedChunker.planKey(BASE))
        private val PARENT = SpeedRun("faded-parent", PLAN.articleId, PLAN.bodyHash, PLAN.granularity, PLAN.planKey,
            0, 1000, 1000, PLAN.chunks.sumOf { it.wordCount }, PLAN.chunks.size,
            PLAN.chunks.map { 100L }, 0, 0, emptyList(), 0, true, 60, "rules", false, true, true,
            SpeedPacedRound.FADED, "guided-parent")
    }
}
