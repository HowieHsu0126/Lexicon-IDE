package dev.lexicon.ide.ui

import androidx.compose.runtime.saveable.SaverScope
import dev.lexicon.domain.StudyMode
import org.junit.Assert.*
import org.junit.Test

class ReadingSessionTrackerTest {
    private val saveScope = object : SaverScope {
        override fun canBeSaved(value: Any) = value is Long
    }

    @Test fun tracksOnlyAvailableParagraphsWithoutAliasingQuerySnapshots() {
        val tracker = ReadingSessionTracker(10_000)
        tracker.recordSecond(0, 0)
        assertEquals(0L, tracker.activeMillis)
        tracker.recordSecond(0, 2)
        tracker.recordSecond(1, 2)
        val before = tracker.dwellSeconds
        tracker.recordSecond(1, 2)
        assertEquals(listOf(1, 1), before)
        assertEquals(listOf(1, 2), tracker.dwellSeconds)
        assertEquals(3_000L, tracker.activeMillis)
        // A temporarily unknown focus accounts for time, but invents no paragraph dwell.
        tracker.recordSecond(-1, 2)
        assertEquals(4_000L, tracker.activeMillis)
        assertEquals(listOf(1, 2), tracker.dwellSeconds)
    }

    @Test fun snapshotUsesModeAndLevelAndBoundsTimeByRealSessionDuration() {
        val tracker = ReadingSessionTracker(10_000, initialAssistanceCount = 4)
        repeat(4) { tracker.recordSecond(0, 2) }
        tracker.observeAssistanceCount(6)
        val session = tracker.snapshot("s", "a", StudyMode.SKIM, "Science", listOf(20, 30), true, "B1", now = 12_500)
        assertEquals(2_500L, session.activeMillis)
        assertEquals(20, session.wordsRead)
        assertEquals(1, session.paragraphsRead)
        assertEquals(2, session.totalParagraphs)
        assertEquals(2, session.assistanceCount)
        assertEquals(StudyMode.SKIM, session.mode)
        assertEquals("B1", session.level)
        assertTrue(session.completed)
        val rolledBack = tracker.snapshot("s", "a", StudyMode.SKIM, null, listOf(20, 30), false, "B1", now = 9_000)
        assertEquals(0L, rolledBack.activeMillis)
    }

    @Test fun savedStateRestoresCountersAndAssistanceBaselineAcrossRecreation() {
        val original = ReadingSessionTracker(42_000, initialAssistanceCount = 3)
        repeat(3) { original.recordSecond(0, 2) }
        original.recordSecond(1, 2)
        original.observeAssistanceCount(5)
        val saved = with(ReadingSessionTracker.Saver) { saveScope.save(original) }
        assertNotNull(saved)
        val restored = ReadingSessionTracker.Saver.restore(saved!!)!!
        assertEquals(original.startedAt, restored.startedAt)
        assertEquals(original.activeMillis, restored.activeMillis)
        assertEquals(original.dwellSeconds, restored.dwellSeconds)
        assertEquals(2, restored.assistanceCount)
        restored.observeAssistanceCount(5)
        assertEquals(2, restored.assistanceCount) // rotation must not count the same assistance twice
        restored.observeAssistanceCount(0) // new ViewModel after process death
        restored.observeAssistanceCount(1)
        assertEquals(3, restored.assistanceCount)
        restored.recordSecond(1, 2)
        assertEquals(listOf(3, 2), restored.dwellSeconds)
        assertEquals(listOf(3, 1), original.dwellSeconds)
    }

    @Test fun completionUsesCoverageForTheSelectedReadingMode() {
        val tracker = ReadingSessionTracker(0)
        val words = listOf(24, 24, 24)
        repeat(5) { tracker.recordSecond(1, 3) }
        assertTrue(tracker.canComplete(words, StudyMode.SKIM, setOf(1)))
        assertFalse(tracker.canComplete(words, StudyMode.INTENSIVE, emptySet()))
        repeat(4) { tracker.recordSecond(2, 3) }
        assertTrue(tracker.canComplete(words, StudyMode.INTENSIVE, emptySet()))
        assertFalse(tracker.canComplete(words, StudyMode.SKIM, emptySet()))
    }

    @Test fun malformedSavedStateCannotCreateNegativeTimeOrOverflowWordEstimates() {
        assertNull(ReadingSessionTracker.Saver.restore(listOf(99L, 1L, 0L, 0L, 0L)))
        assertNull(ReadingSessionTracker.Saver.restore(listOf(1L)))
        val restored = ReadingSessionTracker.Saver.restore(listOf(1L, 10L, -1L, -2L, -3L, Long.MAX_VALUE))!!
        assertEquals(0L, restored.activeMillis)
        assertEquals(0, restored.assistanceCount)
        val session = restored.snapshot("s", "a", StudyMode.INTENSIVE, null, listOf(400), false, "原文", now = 10L)
        assertEquals(400, session.wordsRead)
        assertEquals(1, session.paragraphsRead)
    }
}
