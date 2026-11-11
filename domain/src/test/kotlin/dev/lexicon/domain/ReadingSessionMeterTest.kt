package dev.lexicon.domain

import org.junit.Assert.*
import org.junit.Test

class ReadingSessionMeterTest {
    @Test fun rapidPassCannotCountWholeParagraphOrComplete() {
        assertEquals(8, ReadingSessionMeter.estimatedWords(listOf(240), listOf(1)))
        assertTrue(ReadingSessionMeter.visited(listOf(240), listOf(1)).isEmpty())
        assertFalse(ReadingSessionMeter.canComplete(listOf(240), listOf(1), 1000, StudyMode.INTENSIVE, emptySet()))
    }
    @Test fun completionUsesSelectedSkimCoverageAndMinimumDwell() {
        val words = List(10) { 100 }
        val dwell = listOf(0, 15, 0, 15, 0, 0, 0, 0, 0, 0)
        assertTrue(ReadingSessionMeter.canComplete(words, dwell, 30_000, StudyMode.SKIM, setOf(1,3)))
        assertFalse(ReadingSessionMeter.canComplete(words, dwell, 30_000, StudyMode.INTENSIVE, emptySet()))
        assertFalse(ReadingSessionMeter.canComplete(words, dwell, 30_000, StudyMode.SKIM, emptySet()))
        assertEquals(200, ReadingSessionMeter.estimatedWords(words, dwell))
    }
}
