package dev.lexicon.ide.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeedHistoryUiTest {
    @get:Rule val compose = createComposeRule()
    private val article = Article("a", "g", listOf("s"), "A self-authored history example", "https://example.org/a", "")
    private fun run(id: String, time: Long, first: Boolean, correct: Boolean) = SpeedRun(id, "a", "hash", SpeedGranularity.L2, "plan",
        time - 10_000, time, 10_000, 30, 6, List(6) { 1000L }, 1, 1,
        listOf(SpeedAnswer("q", 0, correct)), 1, true, 60, "ai", first)

    @Test fun emptyHistoryDoesNotInventAccuracyOrTrend() {
        compose.setContent { LexiconTheme(AppSettings()) { SpeedHistoryScreen(SpeedLibrary(), emptyList(), {}, {}) } }
        compose.onNodeWithText("未测").assertIsDisplayed()
        compose.onNodeWithTag("speed_trend").assertDoesNotExist()
        compose.onNodeWithText("100%").assertDoesNotExist()
        compose.onNodeWithText("从文章的“意群速读”开始一次训练。").assertIsDisplayed()
    }

    @Test fun correctionCountDoesNotBecomeAnAccuracyAndCommentRemainsVisibleAtLargeFont() {
        val correction = SpeedCorrection("c", "a", "hash", SpeedGranularity.L2, 0, 20, "merge_next", "保留这个固定表达", 1_000_000)
        var inspected: SpeedCorrection? = null
        compose.setContent {
            val original = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(original.density, 2f)) {
                LexiconTheme(AppSettings()) { SpeedHistoryScreen(SpeedLibrary(corrections = listOf(correction)), listOf(article), {}, {},
                    onInspectCorrection = { inspected = it }) }
            }
        }
        compose.onNodeWithTag("speed_history").performScrollToNode(hasText("切分反馈"))
        compose.onNodeWithText("切分反馈").performClick()
        compose.onNodeWithTag("speed_history").performScrollToNode(hasText("切分准确率：待人工复核"))
        compose.onNodeWithText("切分准确率：待人工复核").assertIsDisplayed()
        compose.onNodeWithTag("speed_history").performScrollToNode(hasText("保留这个固定表达"))
        compose.onNodeWithText("保留这个固定表达").assertIsDisplayed()
        compose.onNodeWithText("100%").assertDoesNotExist()
        compose.onNodeWithTag("speed_history").performScrollToNode(hasTestTag("speed_inspect_correction_c"))
        compose.onNodeWithTag("speed_inspect_correction_c").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(correction, inspected) }
        compose.onNodeWithText("原文范围", substring = true).assertDoesNotExist()
    }

    @Test fun repeatPracticeIsExcludedFromFirstAttemptScoreButShowsItsPlaybackTrend() {
        var opened: String? = null
        val runs = listOf(run("one", 1_000_000, true, false), run("two", 2_000_000, false, true))
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedHistoryScreen(SpeedLibrary(runs), listOf(article), {}, { opened = it })
        } }
        compose.onNodeWithText("0 / 1").assertIsDisplayed()
        compose.onNodeWithTag("speed_trend").assertIsDisplayed()
        compose.onNodeWithTag("speed_history").performScrollToNode(hasText("重复练习，不计入首次理解成绩。"))
        compose.onNodeWithText("重复练习，不计入首次理解成绩。").assertIsDisplayed()
        compose.onNodeWithTag("speed_history").performScrollToNode(hasText("回到文章训练"))
        compose.onAllNodesWithText("回到文章训练").onFirst().performClick()
        compose.runOnIdle { assertEquals("a", opened) }
    }

    @Test fun savedPersonalSegmentationIsAHistoricalEventAndCanInspectTheExactRecord() {
        val correction = SpeedCorrection("saved-boundaries", "missing-article", "body-hash", SpeedGranularity.L3,
            12345, 12999, "boundary_edit", "用户采用个人意群切分", 1_000_000, "historical-plan")
        var inspected: SpeedCorrection? = null
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedHistoryScreen(SpeedLibrary(corrections = listOf(correction)), emptyList(), {}, {},
                onInspectCorrection = { inspected = it })
        } }
        compose.onNodeWithTag("speed_history").performScrollToNode(hasText("切分反馈"))
        compose.onNodeWithText("切分反馈").performClick()
        compose.onNodeWithTag("speed_history").performScrollToNode(hasText("个人切分已保存"))
        compose.onNodeWithText("个人切分已保存").assertIsDisplayed()
        compose.onNodeWithText("其他切分问题").assertDoesNotExist()
        compose.onNodeWithText("待人工复核", substring = true).assertDoesNotExist()
        compose.onNodeWithText("用户采用个人意群切分").assertDoesNotExist()
        compose.onNodeWithText("原文范围", substring = true).assertDoesNotExist()
        compose.onNodeWithText("12345", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("speed_history").performScrollToNode(hasText("历史记录，不表示当前仍在使用。"))
        compose.onNodeWithText("历史记录，不表示当前仍在使用。").assertIsDisplayed()
        compose.onNodeWithTag("speed_history").performScrollToNode(hasTestTag("speed_inspect_correction_saved-boundaries"))
        compose.onNodeWithTag("speed_inspect_correction_saved-boundaries").performClick()
        compose.runOnIdle { assertEquals(correction, inspected) }
        compose.onNodeWithText("回到文章").assertDoesNotExist()
    }

    @Test fun missingInspectCallbackDoesNotAdvertiseAnUnavailableSentenceAction() {
        val correction = SpeedCorrection("c", article.id, "hash", SpeedGranularity.L2, 123, 155,
            "too_long", "保留完整短语", 1_000_000)
        var opened: String? = null
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedHistoryScreen(SpeedLibrary(corrections = listOf(correction)), listOf(article), {}, { opened = it })
        } }
        compose.onNodeWithText("切分反馈").performClick()
        compose.onNodeWithTag("speed_history").performScrollToNode(hasText("回到文章"))
        compose.onNodeWithText("查看这句").assertDoesNotExist()
        compose.onNodeWithText("回到文章").performClick()
        compose.runOnIdle { assertEquals(article.id, opened) }
    }

    @Test fun naturalHistoryIsSeparateFromPlaybackTrendAndFirstAnswerScores() {
        val attempt = NaturalReadingAttempt("self-timed", article.id, "hash", "plan", "faded", 1_000_000, 1_080_000,
            listOf(NaturalParagraphTiming(1, 60_000, true), NaturalParagraphTiming(2, 20_000, false)), false, 1, 0)
        var original: String? = null
        var trained = false
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedHistoryScreen(SpeedLibrary(naturalAttempts = listOf(attempt)), listOf(article), {}, { trained = true },
                onReadOriginal = { original = it })
        } }
        compose.onNodeWithText("未测").assertIsDisplayed()
        compose.onNodeWithTag("speed_trend").assertDoesNotExist()
        compose.onNodeWithText("自主阅读").performClick()
        compose.onNodeWithTag("speed_history").performScrollToNode(hasText("自主计时 1 分 20 秒"))
        compose.onNodeWithText("自主计时 1 分 20 秒").assertIsDisplayed()
        compose.onNodeWithText("读完 1 / 2 段").assertIsDisplayed()
        compose.onNodeWithText("WPM", substring = true).assertDoesNotExist()
        compose.onNodeWithText("有效速度", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("speed_history").performScrollToNode(hasTestTag("natural_history_original_self-timed"))
        compose.onNodeWithTag("natural_history_original_self-timed").performClick()
        compose.runOnIdle { assertEquals(article.id, original); assertFalse(trained) }
    }

    @Test fun fadedHistoryDoesNotDisplayAnotherComprehensionResult() {
        val repeated = run("faded", 1_000_000, false, true).copy(round = SpeedPacedRound.FADED, parentRunId = "guided")
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedHistoryScreen(SpeedLibrary(runs = listOf(repeated)), listOf(article), {}, {})
        } }
        compose.onNodeWithTag("speed_history").performScrollToNode(hasText("淡出练习", substring = true))
        compose.onNodeWithText("有效速度", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("speed_history").performScrollToNode(hasText("重复练习，不计入首次理解成绩。"))
        compose.onNodeWithText("重复练习，不计入首次理解成绩。").assertIsDisplayed()
    }

    @Test fun historyInspectsTheExactSavedRunWithoutStartingAnotherTraining() {
        val saved = run("legacy-run", 1_000_000, true, false)
        var inspected: SpeedRun? = null
        var trained = false
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedHistoryScreen(SpeedLibrary(runs = listOf(saved)), emptyList(), {}, { trained = true },
                onInspectRun = { inspected = it })
        } }
        compose.onNodeWithTag("speed_history").performScrollToNode(hasTestTag("speed_inspect_run_legacy-run"))
        compose.onNodeWithTag("speed_inspect_run_legacy-run").performClick()
        compose.runOnIdle { assertEquals(saved, inspected); assertFalse(trained) }
    }
}
