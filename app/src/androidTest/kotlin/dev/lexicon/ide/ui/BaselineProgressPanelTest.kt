package dev.lexicon.ide.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI fixtures show descriptive measurements; they are never stored as learner records. */
@RunWith(AndroidJUnit4::class)
class BaselineProgressPanelTest {
    @get:Rule val compose = createComposeRule()
    private val time = Instant.parse("2026-11-01T08:00:00Z").toEpochMilli()
    private fun point(id: String, offset: Long, topic: String, wpm: Double, correct: Int, questions: Int) =
        BaselineProgressPoint("attempt-$id", "article-$id", "hash-$id", time + offset,
            SourceRef("Frozen reading material $id", "Self-authored fixture"), topic,
            (wpm / 2).toInt(), 30_000, correct, questions, wpm, wpm * correct / questions)
    private val scienceA = point("a", 0, "Science", 120.0, 3, 4)
    private val culture = point("b", 86_400_000, "Culture", 180.0, 1, 2)
    private val scienceC = point("c", 172_800_000, "Science", 150.0, 3, 3)
    private fun summary(vararg points: BaselineProgressPoint) = BaselineProgressSummary(points.toList(), materialCount = points.size,
        correct = points.sumOf { it.correct }, questions = points.sumOf { it.questions })

    @Test fun noQualifiedSamplesShowExclusionCategoriesWithoutInventingATrend() {
        val empty = BaselineProgressSummary(exclusions = mapOf(BaselineExclusion.FAMILIAR to 2,
            BaselineExclusion.CONTINUATION to 1, BaselineExclusion.INCOMPLETE to 3))
        compose.setContent { LexiconTheme(AppSettings()) {
            Column(Modifier.verticalScroll(rememberScrollState())) { BaselineProgressPanel(empty, false, null, {}, {}, {}) }
        } }
        compose.onNodeWithTag("baseline_progress_empty").assertIsDisplayed()
        compose.onNodeWithTag("baseline_progress_trend").assertDoesNotExist()
        compose.onNodeWithTag("baseline_progress_counts").assertDoesNotExist()
        compose.onNodeWithText("100%").assertDoesNotExist()
        compose.onNodeWithTag("baseline_progress_exclusions_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("baseline_exclusion_FAMILIAR").performScrollTo().assertTextEquals("熟悉材料练习 · 2 条")
        compose.onNodeWithTag("baseline_exclusion_CONTINUATION").performScrollTo().assertTextEquals("跨次续读 · 1 条")
        compose.onNodeWithTag("baseline_exclusion_INCOMPLETE").performScrollTo().assertTextEquals("未完成 · 3 条")
    }

    @Test fun oneSampleShowsItsActualDenominatorAndOpensTheOriginalAttempt() {
        var opened: String? = null
        var history = 0
        compose.setContent { LexiconTheme(AppSettings()) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BaselineProgressPanel(summary(scienceA), false, null, {}, { opened = it }, { history++ })
            }
        } }
        compose.onNodeWithTag("baseline_progress_trend").assertDoesNotExist()
        compose.onNodeWithTag("baseline_progress_one_sample").assertIsDisplayed()
        compose.onNodeWithText("120 WPM").assertIsDisplayed()
        compose.onNodeWithText("答对 3 / 4").assertIsDisplayed()
        compose.onNodeWithText("有效 90 WPM").assertIsDisplayed()
        compose.onNodeWithTag("baseline_progress_sample_attempt-a").performClick()
        compose.runOnIdle { assertEquals("attempt-a", opened); assertNotEquals("article-a", opened) }
        compose.onNodeWithTag("baseline_progress_history").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, history) }
    }

    @Test fun twoSamplesProduceOnlyTheirOwnSpeedAndEffectiveSpeedSeries() {
        compose.setContent { LexiconTheme(AppSettings()) {
            Column(Modifier.verticalScroll(rememberScrollState())) { BaselineProgressPanel(summary(scienceA, culture), false, null, {}, {}, {}) }
        } }
        compose.onNodeWithTag("baseline_progress_trend").assertIsDisplayed()
        compose.onNodeWithContentDescription("自测阅读速度；", substring = true).assertExists()
        compose.onNodeWithContentDescription("180 WPM", substring = true).assertExists()
        compose.onNodeWithTag("baseline_metric_effective").performClick()
        compose.onNodeWithContentDescription("有效阅读速度；", substring = true).assertExists()
        compose.onNodeWithContentDescription("180 WPM", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("90 WPM", substring = true).assertExists()
        screenshot("baseline-progress-two-samples.png")
    }

    @Test fun topicFilterRecomputesCountsAndHidesTheTrendWhenOnlyOneSampleMatches() {
        val state = summary(scienceA, culture, scienceC).copy(exclusions = mapOf(BaselineExclusion.CONTINUATION to 2))
        compose.setContent { LexiconTheme(AppSettings()) {
            Column(Modifier.verticalScroll(rememberScrollState())) { BaselineProgressPanel(state, false, null, {}, {}, {}) }
        } }
        compose.onNodeWithTag("baseline_progress_counts").assertTextEquals("3 份材料 · 合计答对 7 / 9 题")
        compose.onNodeWithTag("baseline_topic_Science").performClick()
        compose.onNodeWithTag("baseline_progress_counts").assertTextEquals("2 份材料 · 合计答对 6 / 7 题")
        compose.onNodeWithTag("baseline_progress_trend").assertExists()
        compose.onNodeWithTag("baseline_progress_sample_attempt-b").assertDoesNotExist()
        compose.onNodeWithTag("baseline_topic_Culture").performClick()
        compose.onNodeWithTag("baseline_progress_counts").assertTextEquals("1 份材料 · 合计答对 1 / 2 题")
        compose.onNodeWithTag("baseline_progress_trend").assertDoesNotExist()
        compose.onNodeWithTag("baseline_progress_sample_attempt-a").assertDoesNotExist()
        compose.onNodeWithTag("baseline_progress_exclusions_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("baseline_exclusion_CONTINUATION").performScrollTo().assertTextEquals("跨次续读 · 2 条")
        compose.onNodeWithText("以下为全部主题的互斥分类，不随当前筛选变化。").performScrollTo().assertIsDisplayed()
    }

    @Test fun loadingAndFailureDoNotBecomeAnEmptyMeasurementAndRetryRemainsExplicit() {
        var loading by mutableStateOf(true)
        var failure by mutableStateOf<String?>(null)
        var retries = 0
        compose.setContent { LexiconTheme(AppSettings()) { BaselineProgressPanel(null, loading, failure, { retries++ }, {}, {}) } }
        compose.onNodeWithTag("baseline_progress_loading").assertIsDisplayed()
        compose.onNodeWithTag("baseline_progress_empty").assertDoesNotExist()
        compose.onNodeWithTag("baseline_progress_trend").assertDoesNotExist()
        compose.runOnIdle { loading = false; failure = "自测记录读取失败" }
        compose.onNodeWithTag("baseline_progress_error").assertTextEquals("自测记录读取失败")
        compose.onNodeWithTag("baseline_progress_retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun originalReadingEstimatesStayOutsideTheIndependentBaselinePanel() {
        val normal = ReadingLibrary(sessions = listOf(ReadingSession("ordinary", "ordinary-article", StudyMode.INTENSIVE,
            "Science", time, time + 60_000, 60_000, 999, 1, 1, 0, true)))
        compose.setContent { LexiconTheme(AppSettings()) {
            ProgressScreen(normal, emptyList(), {}, baselineSummary = summary(scienceA))
        } }
        compose.onNodeWithTag("progress_screen").performScrollToNode(hasTestTag("baseline_progress_sample_attempt-a"))
        compose.onNodeWithText("120 WPM").assertIsDisplayed()
        compose.onNode(hasAnyAncestor(hasTestTag("baseline_progress_panel")) and hasText("999", substring = true)).assertDoesNotExist()
        compose.onNodeWithTag("progress_screen").performScrollToNode(hasText("阅读速度估计"))
        compose.onNodeWithText("999").assertExists()
    }

    @Test fun draculaAtTwoHundredPercentKeepsTitlesNumbersAndChartScopeReadable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                LexiconTheme(AppSettings(theme = ThemeMode.DARK)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { BaselineProgressPanel(summary(scienceA, culture), false, null, {}, {}, {}) }
                }
            }
        }
        compose.onNodeWithTag("baseline_progress_counts").performScrollTo().assertTextEquals("2 份材料 · 合计答对 4 / 6 题")
        val counts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("baseline_progress_counts").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(counts) }
        assertFalse(counts.single().hasVisualOverflow)
        compose.onNodeWithTag("baseline_progress_trend").performScrollTo().assertIsDisplayed()
        screenshot("baseline-progress-dracula-font200.png")
        compose.onNodeWithTag("baseline_progress_scope").performScrollTo().assertIsDisplayed()
        val scopes = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("baseline_progress_scope").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(scopes) }
        assertFalse(scopes.single().hasVisualOverflow)
    }

    private fun screenshot(name: String) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("baseline-progress-ui-fixtures").apply { mkdirs() }
        directory.resolve(name).outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
