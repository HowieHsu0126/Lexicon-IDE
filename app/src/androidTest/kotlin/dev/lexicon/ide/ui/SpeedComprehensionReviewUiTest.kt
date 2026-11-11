package dev.lexicon.ide.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeedComprehensionReviewUiTest {
    @get:Rule val compose = createComposeRule()
    private val quote = "Residents requested evening access. The council changed the library hours."
    private val question = SpeedQuestion("same-id", 0, "Why did the hours change?",
        listOf("Residents requested evening access", "The building closed", "The budget fell"),
        0, 0, quote.length, "The council responded to residents' requests.")
    private val run = SpeedRun("review-a", "article", "body", SpeedGranularity.L2, "plan", 0, 1000,
        1000, 10, 1, listOf(1000L), 0, 0, listOf(SpeedAnswer(question.id, 1, false)), 1, true, 60, "ai")
    private val item = SpeedComprehensionReviewItem(question, run.answers.single(), quote)
    private fun state(r: SpeedRun = run, q: SpeedComprehensionReviewItem = item) = SpeedRecapInspection(r,
        loading = false, comprehension = SpeedComprehensionReview(SpeedComprehensionReviewStatus.AVAILABLE, listOf(q)))

    @Test fun wrongAnswerAndSourceAreExplicitlyRevealedWithoutRegrading() {
        compose.setContent { LexiconTheme(AppSettings()) { SpeedRecapInspectionDialog(state()) {} } }
        compose.onNodeWithTag("speed_comprehension_summary").assertTextEquals("答对 0/1 题 · 当时的 AI 参考答案")
        compose.onNodeWithTag("speed_comprehension_prompt_same-id").assertDoesNotExist()
        compose.onNodeWithTag("speed_comprehension_question_same-id").performScrollTo().performClick()
        compose.onNodeWithTag("speed_comprehension_selected_same-id").performScrollTo().assertTextEquals("你的选择：B")
        compose.onNodeWithTag("speed_comprehension_reference_same-id").performScrollTo().assertTextEquals("参考答案：A · AI")
        compose.onNodeWithTag("speed_comprehension_evidence_same-id").assertDoesNotExist()
        compose.onNodeWithTag("speed_comprehension_evidence_toggle_same-id").performScrollTo().performClick()
        compose.onNodeWithTag("speed_comprehension_evidence_same-id").performScrollTo().assertTextEquals(quote)
    }

    @Test fun changingRunEvenWithSameQuestionIdDoesNotReuseExpandedAnswers() {
        var current by mutableStateOf(state())
        compose.setContent { LexiconTheme(AppSettings()) { SpeedRecapInspectionDialog(current) {} } }
        compose.onNodeWithTag("speed_comprehension_question_same-id").performScrollTo().performClick()
        compose.onNodeWithTag("speed_comprehension_evidence_toggle_same-id").performScrollTo().performClick()
        val next = item.copy(question = question.copy(prompt = "What did the next article discuss?"))
        compose.runOnIdle { current = state(run.copy(id = "review-b"), next) }
        compose.onNodeWithText(question.prompt).assertDoesNotExist()
        compose.onNodeWithTag("speed_comprehension_evidence_same-id").assertDoesNotExist()
        compose.onNodeWithTag("speed_comprehension_prompt_same-id").assertDoesNotExist()
        compose.onNodeWithTag("speed_comprehension_question_same-id").performScrollTo().performClick()
        compose.onNodeWithTag("speed_comprehension_prompt_same-id").assertTextEquals(next.question.prompt)
    }

    @Test fun unfinishedOrUnavailableReviewNeverShowsSolutionsOrAZeroScore() {
        var current by mutableStateOf(state().copy(comprehension =
            SpeedComprehensionReview(SpeedComprehensionReviewStatus.INCOMPLETE, listOf(item))))
        compose.setContent { LexiconTheme(AppSettings()) { SpeedRecapInspectionDialog(current) {} } }
        compose.onNodeWithTag("speed_comprehension_status").assertTextEquals("本轮尚未完成理解检测，暂不展开参考答案。")
        compose.onNodeWithTag("speed_comprehension_question_same-id").assertDoesNotExist()
        compose.onNodeWithTag("speed_comprehension_summary").assertDoesNotExist()
        compose.runOnIdle { current = current.copy(comprehension = null) }
        compose.onNodeWithTag("speed_comprehension_status").assertTextEquals("理解题资料暂不可用，已有成绩保留。")
        compose.onNodeWithTag("speed_comprehension_reference_same-id").assertDoesNotExist()
    }
}
