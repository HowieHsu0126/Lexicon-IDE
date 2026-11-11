package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local synthetic records verify what a reader can inspect, not learning efficacy or model quality. */
@RunWith(AndroidJUnit4::class)
class PaceEvidenceControlsTest {
    @get:Rule val compose = createComposeRule()
    private val first = run("first", "article-a")
    private val second = run("second", "article-b")
    private val articles = listOf(article("article-a", "Evidence from the first article"), article("article-b", "A second independent article"))

    @Test fun insufficientEvidenceHasAReasonButNoInventedZeroPercentOrRecommendation() {
        val assessment = assessment(PaceAssessmentStatus.INSUFFICIENT_EVIDENCE).copy(evidence = emptyList(), correct = 0, total = 0, materialCount = 0,
            excludedCounts = mapOf(PaceExclusionReason.NO_AI_QUESTIONS to 2, PaceExclusionReason.REPEAT_OR_FADED to 1))
        show(assessment)
        compose.onNodeWithTag("speed_pace_status").assertTextEquals("继续积累：0 题 · 0 份不同正文")
        compose.onNodeWithTag("speed_pace_requirements").assertDoesNotExist()
        compose.onNodeWithTag("speed_pace_apply").assertDoesNotExist()
        compose.onNodeWithText("0%").assertDoesNotExist()
        compose.onNodeWithTag("speed_pace_details_toggle").performClick()
        compose.onNodeWithTag("speed_pace_requirements").assertTextContains("已采用 0 题 · 0 份不同正文", substring = true)
        compose.onNodeWithTag("pace_excluded_NO_AI_QUESTIONS").performScrollTo().assertTextEquals("没有 AI 理解题或尚未作答 · 2 轮")
        compose.onNodeWithTag("pace_excluded_REPEAT_OR_FADED").performScrollTo().assertTextEquals("重复或淡出练习 · 1 轮")
        compose.onNodeWithText("0%").assertDoesNotExist()
    }

    @Test fun disabledAndInvalidProfilesDoNotImplyThatHistoryWasEvaluated() {
        var current by mutableStateOf(assessment(PaceAssessmentStatus.DISABLED).copy(evidence = emptyList(), total = 0, correct = 0, materialCount = 0))
        compose.setContent { LexiconTheme(AppSettings()) { EvidenceControls(current) } }
        compose.onNodeWithTag("speed_pace_status").assertTextEquals("训练建议已关闭")
        compose.onNodeWithTag("speed_pace_details_toggle").assertDoesNotExist()
        compose.runOnIdle { current = current.copy(status = PaceAssessmentStatus.INVALID_PROFILE) }
        compose.onNodeWithTag("speed_pace_status").assertTextEquals("训练设置暂不可用")
        compose.onNodeWithTag("speed_pace_details_toggle").assertDoesNotExist()
        compose.onNodeWithTag("speed_pace_apply").assertDoesNotExist()
    }

    @Test fun enoughQuestionsFromOneBodyExplainsWhyAnotherArticleIsNeeded() {
        val oneBody = assessment(PaceAssessmentStatus.INSUFFICIENT_EVIDENCE).copy(materialCount = 1)
        show(oneBody)
        compose.onNodeWithTag("speed_pace_details_toggle").performClick()
        compose.onNodeWithTag("pace_assessment_requirements").assertTextContains("同一篇换段落仍算一份", substring = true)
        compose.onNodeWithTag("pace_assessment_next_material").assertTextEquals("题数已够，下一次请换一篇文章完成首次 AI 理解练习。")
        compose.onNodeWithTag("speed_pace_apply").assertDoesNotExist()
    }

    @Test fun currentEvidenceOpensExactlyTheSelectedRecordAndNoOtherHistory() {
        val opened = mutableListOf<SpeedRun>()
        val value = assessment(PaceAssessmentStatus.MAINTAIN).copy(excludedCounts = mapOf(PaceExclusionReason.OUTSIDE_SELECTED_EVIDENCE to 4))
        show(value, onInspect = { opened += it })
        compose.onNodeWithTag("speed_pace_status").assertTextEquals("本次判断保持当前节奏")
        compose.onNodeWithTag("speed_pace_details_toggle").performClick()
        compose.onNodeWithText(articles[0].title).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pace_assessment_open_first").performScrollTo().performClick()
        compose.onNodeWithTag("pace_assessment_open_second").performScrollTo().performClick()
        compose.onNodeWithTag("pace_excluded_OUTSIDE_SELECTED_EVIDENCE").performScrollTo()
            .assertTextEquals("符合条件，未进入本次选取范围 · 4 轮")
        compose.runOnIdle { assertEquals(listOf(first, second), opened) }
    }

    @Test fun aChangedAssessmentDoesNotLeaveOldEvidenceExpanded() {
        var current by mutableStateOf(assessment(PaceAssessmentStatus.MAINTAIN))
        compose.setContent { LexiconTheme(AppSettings()) { Column(Modifier.verticalScroll(rememberScrollState())) { EvidenceControls(current) } } }
        compose.onNodeWithTag("speed_pace_details_toggle").performClick()
        compose.onNodeWithTag("pace_assessment_run_first").assertExists()
        compose.runOnIdle { current = current.copy(rate = 80, evidence = emptyList(), total = 0, correct = 0, materialCount = 0,
            status = PaceAssessmentStatus.INSUFFICIENT_EVIDENCE) }
        compose.onNodeWithTag("speed_pace_requirements").assertDoesNotExist()
        compose.onNodeWithTag("pace_assessment_run_first").assertDoesNotExist()
        compose.onNodeWithTag("speed_pace_details_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("speed_pace_requirements").assertTextContains("80 意群/分钟", substring = true)
    }

    @Test fun outcomeUsesFrozenIdsAndReportsMissingOrConflictingRecordsWithoutReplacement() {
        val outcome = PersonalizationOutcome("applied", SpeedGranularity.L2, 63, 3, 3, 9, 9, 0, 0, true, 1.0,
            listOf("second", "missing", "conflict"))
        val conflict = run("conflict", "article-a")
        val opened = mutableListOf<SpeedRun>()
        compose.setContent { LexiconTheme(AppSettings()) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SpeedOutcomeControls(outcome, evidenceRuns = listOf(first, second, second, conflict, conflict.copy(pauses = 1)),
                    articles = articles, onInspectRun = { opened += it })
            }
        } }
        compose.onNodeWithTag("speed_outcome_toggle").performClick()
        compose.onNodeWithTag("pace_outcome_unavailable").performScrollTo().assertTextEquals("2 条记录不可用")
        compose.onNodeWithTag("pace_outcome_run_first").assertDoesNotExist()
        compose.onNodeWithTag("pace_outcome_run_conflict").assertDoesNotExist()
        compose.onAllNodesWithTag("pace_outcome_run_second").assertCountEquals(1)
        compose.onNodeWithTag("pace_outcome_open_second").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(second), opened) }
    }

    @Test fun noInspectionCallbackDoesNotAdvertiseAnInertAction() {
        show(assessment(PaceAssessmentStatus.MAINTAIN))
        compose.onNodeWithTag("speed_pace_details_toggle").performClick()
        compose.onNodeWithTag("pace_assessment_run_first").assertExists()
        compose.onNodeWithTag("pace_assessment_open_first").assertDoesNotExist()
    }

    @Test fun largeFontKeepsTheReasonEvidenceAndRecordActionReadableAtNarrowWidth() {
        val opened = mutableListOf<SpeedRun>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                LexiconTheme(AppSettings(theme = ThemeMode.DARK)) {
                    Column(Modifier.width(320.dp).fillMaxHeight().testTag("pace_evidence_viewport")
                        .verticalScroll(rememberScrollState()).padding(16.dp)) {
                        EvidenceControls(assessment(PaceAssessmentStatus.MAINTAIN), onInspect = { opened += it })
                    }
                }
            }
        }
        compose.onNodeWithTag("speed_pace_details_toggle").performScrollTo().performClick()
        for (tag in listOf("speed_pace_status", "speed_pace_requirements", "pace_assessment_requirements")) {
            val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("$tag must not clip text", layouts.single().hasVisualOverflow)
        }
        val node = compose.onNodeWithTag("pace_assessment_open_first").performScrollTo().assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top >= 48.dp)
        val viewport = compose.onNodeWithTag("pace_evidence_viewport").getUnclippedBoundsInRoot()
        assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
        node.performClick()
        compose.runOnIdle { assertEquals(listOf(first), opened) }
    }

    private fun show(value: PaceAssessment, onInspect: ((SpeedRun) -> Unit)? = null) {
        compose.setContent { LexiconTheme(AppSettings()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { EvidenceControls(value, onInspect) }
        } }
    }

    @Composable private fun EvidenceControls(value: PaceAssessment, onInspect: ((SpeedRun) -> Unit)? = null) {
        SpeedPersonalizationControls(value.suggestion, null, {}, {}, {}, assessment = value, articles = articles, onInspectRun = onInspect)
    }

    private fun assessment(status: PaceAssessmentStatus) = PaceAssessment(SpeedGranularity.L2, 60, status,
        listOf(first, second), 6, 6, 2, null, emptyMap())

    private fun run(id: String, article: String) = SpeedRun(id, article, "body-$article", SpeedGranularity.L2,
        "plan-$id", 1_700_000_000_000, 1_700_000_010_000, 10_000, 30, 2, listOf(5_000L, 5_000L), 0, 0,
        (1..3).map { SpeedAnswer("$id-q$it", 0, true) }, 3, true, 60, "ai", pacingStable = true, pacedCompletion = true)

    private fun article(id: String, title: String) = Article(id, id, listOf("source"), title, "https://example.org/$id", "")
}
