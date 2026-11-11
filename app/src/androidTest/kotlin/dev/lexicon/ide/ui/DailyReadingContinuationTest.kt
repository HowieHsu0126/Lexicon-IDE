package dev.lexicon.ide.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.lexicon.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DailyReadingContinuationTest {
    @get:Rule val compose = createComposeRule()
    private val old = Article("old", "old", emptyList(), "Yesterday's unfinished article", "", "", sourceName = "Saved source")
    private val today = Article("today", "today", emptyList(), "Today's new article", "", "", sourceName = "New source")
    private fun record(id: String, time: Long = 10, completed: Boolean = false) = ReadingResumeRecord(
        id, "a".repeat(64), 6, 60, 90, ReadingResumeControls(StudyMode.SKIM, budgetMinutes = 10),
        1, 1, time, completed)

    @Test fun yesterdayCanContinueWithoutReplacingTodaysEditionOrExplicitModeActions() {
        var continued: Article? = null
        var started: Pair<Article, StudyMode>? = null
        val edition = DailyEdition("2026-11-02", listOf(today.id), listOf("today-source"))
        compose.setContent {
            LexiconTheme(AppSettings()) { DailyReadingHome(listOf(old, today), edition, false, {},
                { a, mode -> started = a to mode }, {}, {}, resumes = ReadingResumeLibrary(listOf(record(old.id))),
                onContinue = { continued = it }) }
        }
        compose.onNodeWithTag("daily_continue_title").assertTextEquals(old.title)
        compose.onNodeWithTag("daily_continue_position").assertTextEquals("略读 · 第 7 段")
        compose.onNodeWithTag("daily_continue").performClick()
        assertEquals(old, continued)
        assertNull(started)
        compose.onNodeWithTag("daily_reading").performScrollToNode(hasTestTag("daily_skim_today"))
        compose.onNodeWithTag("daily_skim_today").performClick()
        assertEquals(today to StudyMode.SKIM, started)
        compose.onNodeWithTag("daily_intensive_today").performClick()
        assertEquals(today to StudyMode.INTENSIVE, started)
        assertEquals(listOf(today.id), edition.articleIds)
    }

    @Test fun finishedOrUnavailableArticlesDoNotDisplaceAUsableContinuation() {
        compose.setContent { LexiconTheme(AppSettings()) {
            DailyReadingHome(listOf(old, today), DailyEdition("2026-11-02", listOf(today.id), listOf("source")), false,
                {}, { _, _ -> }, {}, {}, resumes = ReadingResumeLibrary(listOf(record("missing", 100), record(today.id, 90, true), record(old.id))), onContinue = {})
        } }
        compose.onNodeWithTag("daily_continue_title").assertTextEquals(old.title)
        compose.onNodeWithTag("daily_continue_today").assertDoesNotExist()
    }

    @Test fun unfinishedTodayHasContinueAndAnIndependentFiveMinuteStart() {
        var continued = 0
        var started = 0
        compose.setContent { LexiconTheme(AppSettings()) {
            DailyReadingHome(listOf(today), DailyEdition("2026-11-02", listOf(today.id), listOf("source")), false,
                {}, { _, _ -> started++ }, {}, {}, resumes = ReadingResumeLibrary(listOf(record(today.id))), onContinue = { continued++ })
        } }
        compose.onNodeWithTag("daily_reading").performScrollToNode(hasTestTag("daily_continue_today"))
        compose.onNodeWithTag("daily_continue_today").performClick()
        assertEquals(1, continued); assertEquals(0, started)
        compose.onNodeWithTag("daily_skim_today").performClick()
        assertEquals(1, continued); assertEquals(1, started)
    }

    @Test fun anEmptyLibraryKeepsTheOriginalDailyScreenAndNoInventedProgress() {
        compose.setContent { LexiconTheme(AppSettings()) {
            DailyReadingHome(emptyList(), null, false, {}, { _, _ -> }, {}, {}, onContinue = {})
        } }
        compose.onNodeWithTag("daily_continue_card").assertDoesNotExist()
        compose.onNodeWithText("0 / 3 篇").assertIsDisplayed()
        compose.onNodeWithText("选择来源").assertIsDisplayed()
    }
}
