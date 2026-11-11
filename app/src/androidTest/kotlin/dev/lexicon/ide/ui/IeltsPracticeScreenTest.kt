package dev.lexicon.ide.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic presentation fixtures only: no repository, model, exam grading or network is involved. */
@RunWith(AndroidJUnit4::class)
class IeltsPracticeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun useTheProductionWindowConfiguration() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
    private val first = "The council approved solar panels for the library. Members considered the cost and the needs of local residents."
    private val second = "The writer argues that public investment should support clean energy while communities discuss competing priorities."
    private val paragraphs = listOf(IeltsParagraph("p1", 0, first.length, first),
        IeltsParagraph("p2", first.length + 2, first.length + 2 + second.length, second))
    private val candidate = IeltsCandidateView(IeltsContract.CANDIDATE, "request-test", "article-test", "b".repeat(64), "a".repeat(64),
        IeltsContract.LABEL, "single_passage_practice", 4, paragraphs, listOf(
            IeltsCandidateChoice("q1", "Which proposal did the council approve?", listOf(
                IeltsOption(IeltsChoice.A, "Solar panels for the library"), IeltsOption(IeltsChoice.B, "A new railway station"),
                IeltsOption(IeltsChoice.C, "A sports stadium"), IeltsOption(IeltsChoice.D, "A new regional airport")), 1),
            IeltsCandidateInformation("q2", "The council considered local residents' needs.", IeltsInformationAnswer.entries.toList()),
            IeltsCandidateViewQuestion("q3", "The writer wants the investment budget doubled next year.", IeltsViewAnswer.entries.toList()),
            IeltsCandidateCompletion("q4", "The council approved ____ for the library.", 2, false, IeltsContract.WORD_RULE)),
        IeltsCandidateWriting("w2", IeltsWritingType.EXTENT_OF_AGREEMENT,
            "Public funds should prioritise clean energy over other community projects. To what extent do you agree or disagree?", 250, 40),
        IeltsUnavailableTask1("unavailable", "no_verified_visual"), listOf("synthetic-style"),
        "official_public_format_not_recent_three_year_test_corpus")
    private val filled = listOf(IeltsResponse("q1", "B"), IeltsResponse("q2", "true"), IeltsResponse("q3", "not_given"), IeltsResponse("q4", "solar panels"))
    private val review = IeltsAnswerView("attempt-test", candidate.packageSha256, "c".repeat(64), listOf(
        IeltsAnswerItem("q1", IeltsReadingType.MULTIPLE_CHOICE, "B", "A", "PRIVATE_REVIEW_ONE：原文明确写出图书馆太阳能板方案。",
            listOf(IeltsEvidence("p1", 0, first.indexOf('.') + 1, first.substring(0, first.indexOf('.') + 1)))),
        IeltsAnswerItem("q2", IeltsReadingType.IDENTIFYING_INFORMATION, "true", "true", "PRIVATE_REVIEW_TWO：原文提到居民需求。",
            listOf(IeltsEvidence("p1", first.indexOf("Members"), first.length, first.substring(first.indexOf("Members"))))),
        IeltsAnswerItem("q3", IeltsReadingType.IDENTIFYING_WRITERS_VIEWS, "not_given", "not_given", "PRIVATE_REVIEW_THREE：作者未说明预算翻倍。",
            emptyList(), listOf("p1", "p2"), "PRIVATE_ABSENCE：支持投资不等于主张明年将预算翻倍。"),
        IeltsAnswerItem("q4", IeltsReadingType.SHORT_TEXT_COMPLETION, "solar panels", "solar panels", "PRIVATE_REVIEW_FOUR：保留原文的两个词。",
            listOf(IeltsEvidence("p1", first.indexOf("solar panels"), first.indexOf("solar panels") + 12, "solar panels")),
            wordCount = 2, withinWordLimit = true, numberRuleSatisfied = true)), 4, false)

    private fun scrollTo(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("ielts_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun mount(responses: List<IeltsResponse> = emptyList(), writing: String = "", saving: Boolean = false,
        result: IeltsAnswerView? = null, error: String? = null,
        onAnswer: (String, String) -> Unit = { _, _ -> }, onWriting: (String) -> Unit = {},
        onSubmit: () -> Unit = {}, onBack: () -> Unit = {}, onRetry: () -> Unit = {}) {
        compose.setContent { LexiconTheme(AppSettings()) {
            IeltsPracticeScreen(candidate, responses, writing, saving, result, error,
                onAnswer, onWriting, onSubmit, onBack, onRetry)
        } }
    }

    @Test fun beforeSubmissionNoReferenceKeyExplanationOrEvidenceEntersSemantics() {
        mount()
        compose.onNodeWithTag("ielts_submit").assertIsNotEnabled()
        compose.onNodeWithTag("ielts_source").assertDoesNotExist()
        candidate.readingQuestions.forEach { question ->
            scrollTo("ielts_prompt_${question.questionId}")
            compose.onNodeWithTag("ielts_reference_${question.questionId}").assertDoesNotExist()
            compose.onNodeWithTag("ielts_review_toggle_${question.questionId}").assertDoesNotExist()
            compose.onNodeWithText("PRIVATE_", substring = true).assertDoesNotExist()
        }
        compose.onNodeWithTag("ielts_content").performScrollToIndex(0)
        compose.onNodeWithTag("ielts_source_toggle").performClick()
        compose.onNodeWithTag("ielts_source_p1").assertTextEquals(first)
        compose.onNodeWithTag("ielts_source_toggle").performClick()
        compose.onNodeWithTag("ielts_source").assertDoesNotExist()
    }

    @Test fun allFourFormsEmitTheirOwnResponseVocabularyAndShortWritingDoesNotBlockSubmit() {
        var answers by mutableStateOf(emptyList<IeltsResponse>())
        var writing by mutableStateOf("")
        val events = mutableListOf<IeltsResponse>()
        var submissions = 0
        compose.setContent { LexiconTheme(AppSettings()) {
            IeltsPracticeScreen(candidate, answers, writing, false, null, null,
                onAnswer = { id, value ->
                    val response = IeltsResponse(id, value); events += response
                    answers = answers.filterNot { it.questionId == id } + response
                }, onWriting = { writing = it }, onSubmit = { submissions++ }, onBack = {}, onRetry = {})
        } }
        scrollTo("ielts_option_q1_D").performClick()
        scrollTo("ielts_option_q2_false").performClick()
        scrollTo("ielts_option_q3_yes").performClick()
        compose.onNodeWithTag("ielts_submit").assertIsNotEnabled()
        scrollTo("ielts_input_q4").performTextInput("solar panels")
        scrollTo("ielts_writing_input").performTextInput("One clear position.")
        scrollTo("ielts_writing_count").assertTextEquals("3 词 / 250 词要求")
        compose.onNodeWithTag("ielts_submit").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("q1", "q2", "q3", "q4"), events.map { it.questionId })
            assertEquals(listOf("D", "false", "yes", "solar panels"), events.map { it.answer })
            assertEquals("One clear position.", writing)
            assertEquals(1, submissions)
        }
    }

    @Test fun invalidOrDuplicateResponsesCannotCountAsCompleteReading() {
        var answers by mutableStateOf(filled.map { if (it.questionId == "q2") it.copy(answer = "yes") else it })
        compose.setContent { LexiconTheme(AppSettings()) {
            IeltsPracticeScreen(candidate, answers, "", false, null, null, { _, _ -> }, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("ielts_submit").assertIsNotEnabled()
        compose.runOnIdle { answers = filled + filled.first() }
        compose.onNodeWithTag("ielts_submit").assertIsNotEnabled()
        compose.runOnIdle { answers = filled }
        compose.onNodeWithTag("ielts_submit").assertIsEnabled()
    }

    @Test fun savingDisablesRepeatedSubmitAndEditsWithoutDiscardingDraft() {
        var saving by mutableStateOf(false)
        var submits = 0
        var edits = 0
        compose.setContent { LexiconTheme(AppSettings()) {
            IeltsPracticeScreen(candidate, filled, "A retained draft.", saving, null, null,
                { _, _ -> edits++ }, { edits++ }, { submits++; saving = true }, {}, {})
        } }
        compose.onNodeWithTag("ielts_submit").performClick().assertIsNotEnabled()
        compose.onNodeWithTag("ielts_submit").performTouchInput { click() }
        compose.onNodeWithTag("ielts_back").assertIsNotEnabled()
        scrollTo("ielts_option_q1_A").assertIsNotEnabled()
        scrollTo("ielts_input_q4").assertIsNotEnabled().assertTextContains("solar panels")
        scrollTo("ielts_writing_input").assertIsNotEnabled().assertTextContains("A retained draft.")
        compose.runOnIdle { assertEquals(1, submits); assertEquals(0, edits) }
    }

    @Test fun failedSaveShowsRetryAndKeepsExistingWritingAndCompletionText() {
        var saving by mutableStateOf(true)
        var error by mutableStateOf<String?>(null)
        var retries = 0
        compose.setContent { LexiconTheme(AppSettings()) {
            IeltsPracticeScreen(candidate, filled, "Keep this argument intact.", saving, null, error,
                { _, _ -> }, {}, {}, {}, { retries++; saving = true })
        } }
        compose.runOnIdle { saving = false; error = "保存暂时失败，作答仍保留。" }
        compose.onNodeWithTag("ielts_error").assertTextEquals("保存暂时失败，作答仍保留。")
        scrollTo("ielts_input_q4").assertTextContains("solar panels")
        scrollTo("ielts_writing_input").assertTextContains("Keep this argument intact.")
        compose.onNodeWithTag("ielts_retry").performClick().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun reviewKeepsOriginalOptionsAndRevealsAiReferenceAndExactQuoteOnlyOnDemand() {
        var answers = 0
        mount(responses = filled, writing = "A short submitted draft.", result = review, onAnswer = { _, _ -> answers++ })
        scrollTo("ielts_prompt_q1").assertTextEquals("Which proposal did the council approve?")
        scrollTo("ielts_option_q1_B").assertIsSelected().assertIsNotEnabled()
        scrollTo("ielts_your_answer_q1").assertTextEquals("你的作答：B")
        compose.onNodeWithTag("ielts_reference_q1").assertDoesNotExist()
        scrollTo("ielts_review_toggle_q1").performClick()
        scrollTo("ielts_reference_q1").assertTextEquals("AI 参考：A. Solar panels for the library")
        scrollTo("ielts_explanation_q1").assertTextEquals(review.readingAnswers[0].explanation)
        scrollTo("ielts_evidence_q1_0").assertTextEquals(review.readingAnswers[0].sourceRefs.single().quote)
        compose.onNodeWithTag("ielts_submit").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, answers) }
    }

    @Test fun notGivenAndWritingReviewRetainTheirLimitsWithoutInventedEvidenceOrBand() {
        mount(responses = filled, writing = "A short submitted draft.", result = review)
        scrollTo("ielts_review_toggle_q3").performClick()
        scrollTo("ielts_ng_scope_q3").assertTextEquals("检查范围：第 1、2 段")
        scrollTo("ielts_ng_reason_q3").assertTextEquals(review.readingAnswers[2].absenceJustification!!)
        compose.onNodeWithTag("ielts_evidence_q3_0").assertDoesNotExist()
        scrollTo("ielts_writing_input").assertTextContains("A short submitted draft.")
        scrollTo("ielts_short_writing").assertTextEquals("篇幅不足已记录，不据此自动判零分。")
        compose.onNodeWithText("Band", substring = true).assertDoesNotExist()
    }

    @Test fun newSubmissionAndMismatchedPackageNeverReuseOpenReferenceSections() {
        var result by mutableStateOf<IeltsAnswerView?>(review)
        var current by mutableStateOf(candidate)
        var retries = 0
        compose.setContent { LexiconTheme(AppSettings()) {
            IeltsPracticeScreen(current, filled, "A submitted draft.", false, result, null,
                { _, _ -> }, {}, {}, {}, { retries++ })
        } }
        scrollTo("ielts_review_toggle_q1").performClick()
        scrollTo("ielts_reference_q1").assertIsDisplayed()
        compose.runOnIdle { result = review.copy(submissionSha256 = "d".repeat(64)) }
        compose.onNodeWithTag("ielts_reference_q1").assertDoesNotExist()
        compose.runOnIdle { current = candidate.copy(packageSha256 = "e".repeat(64)) }
        compose.onNodeWithTag("ielts_error").assertTextEquals("回看记录与当前练习不一致，请重新读取。")
        compose.onNodeWithTag("ielts_reference_q1").assertDoesNotExist()
        compose.onNodeWithTag("ielts_submit").assertDoesNotExist()
        compose.onNodeWithTag("ielts_retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun anInFlightSaveCannotExposeAnAlreadySuppliedAnswerView() {
        mount(responses = filled, saving = true, result = review)
        compose.onNodeWithTag("ielts_submit").assertIsNotEnabled()
        scrollTo("ielts_prompt_q1")
        compose.onNodeWithTag("ielts_review_toggle_q1").assertDoesNotExist()
        compose.onNodeWithTag("ielts_reference_q1").assertDoesNotExist()
        compose.onNodeWithText("PRIVATE_", substring = true).assertDoesNotExist()
    }

    @Test fun draculaAtTwoHundredPercentKeepsTextUnclippedAndActionsAtLeastFortyEightDp() = largeType(ThemeMode.DARK)

    @Test fun daylightAtTwoHundredPercentKeepsTextUnclippedAndActionsAtLeastFortyEightDp() = largeType(ThemeMode.LIGHT)

    private fun largeType(theme: ThemeMode) {
        var pixelsFor48 = 0f
        compose.setContent {
            val original = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(original.density, 2f)) {
                pixelsFor48 = with(LocalDensity.current) { 48.dp.toPx() }
                LexiconTheme(AppSettings(theme = theme)) {
                    Box(Modifier.width(320.dp).fillMaxHeight().testTag("ielts_test_viewport")) {
                        IeltsPracticeScreen(candidate, filled, "A readable submitted draft.", false, review, null,
                            { _, _ -> }, {}, {}, {}, {}, Modifier.fillMaxSize())
                    }
                }
            }
        }
        val variant = if (theme == ThemeMode.DARK) "practice-night-font200" else "practice-day-font200"
        val run = System.currentTimeMillis().toString()
        fun screenshot(name: String) {
            val target = compose.onNodeWithTag("ielts_practice").assertIsDisplayed()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val folder = File(instrumentation.targetContext.filesDir, "validation-1.35.0/ielts-fixture/$variant/$run")
            check(folder.isDirectory || folder.mkdirs())
            val rendered = target.captureToImage().asAndroidBitmap()
            try { File(folder, name).outputStream().use { check(rendered.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { rendered.recycle() }
            instrumentation.waitForIdleSync(); SystemClock.sleep(100)
            val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { File(folder, "screen-$name").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { image.recycle() }
        }
        screenshot("01-review.png")
        val viewport = compose.onNodeWithTag("ielts_test_viewport").fetchSemanticsNode().boundsInRoot
        fun textFits(tag: String, scrollTag: String = tag) {
            scrollTo(scrollTag)
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
            val results = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertTrue("$tag must provide a text layout", results.isNotEmpty())
            assertTrue("$tag must not truncate its text", results.none { it.hasVisualOverflow })
            assertEquals("$tag uses the requested text scale", 2f, results.first().layoutInput.density.fontScale, .01f)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$tag fits the viewport width", bounds.left >= viewport.left - 1 && bounds.right <= viewport.right + 1)
        }
        textFits("ielts_prompt_q1")
        textFits("ielts_option_text_q1_B", "ielts_option_q1_B")
        screenshot("02-options.png")
        scrollTo("ielts_review_toggle_q1").performClick()
        textFits("ielts_reference_q1")
        textFits("ielts_explanation_q1")
        textFits("ielts_evidence_q1_0")
        screenshot("03-reference.png")
        textFits("ielts_writing_prompt")
        screenshot("04-writing.png")
        for (tag in listOf("ielts_review_toggle_q1", "ielts_option_q2_false")) {
            val bounds = scrollTo(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag keeps a 48dp height", bounds.height >= pixelsFor48 - 1)
            assertTrue("$tag keeps a 48dp width", bounds.width >= pixelsFor48 - 1)
        }
        val back = compose.onNodeWithTag("ielts_back").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(back.width >= pixelsFor48 - 1 && back.height >= pixelsFor48 - 1)
    }

    @Test fun writingFocusAndEditingKeepTheSubmitActionVisible() {
        var writing by mutableStateOf("")
        compose.setContent { LexiconTheme(AppSettings()) {
            IeltsPracticeScreen(candidate, filled, writing, false, null, null,
                { _, _ -> }, { writing = it }, {}, {}, {})
        } }
        scrollTo("ielts_writing_input").performClick().performTextInput("Public investment needs clear priorities.")
        compose.onNodeWithTag("ielts_submit").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("ielts_back").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Public investment needs clear priorities.", writing) }
    }
}
