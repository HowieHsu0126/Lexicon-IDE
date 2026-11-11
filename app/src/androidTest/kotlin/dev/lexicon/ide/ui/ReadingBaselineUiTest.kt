package dev.lexicon.ide.ui

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Self-authored content: verifies exposure boundaries, not a real learner's ability. */
@RunWith(AndroidJUnit4::class)
class ReadingBaselineUiTest {
    @get:Rule val compose = createComposeRule()
    private val first = "Researchers collected evidence from three coastal cities."
    private val second = "The report asks readers to consider the limits of this evidence before drawing a conclusion."
    private val text = "$first\n\n$second"
    private val article = Article("baseline-ui", "g", listOf("s"), "Evidence and its limits", "https://example.org/self-test",
        "A private summary that must stay hidden before this self-test.", sourceName = "Self-authored fixture")
    private val body = ArticleBody(article.id, text, fingerprint(text), 1, Long.MAX_VALUE, "Self-authored fixture")
    private val questions = listOf(
        SpeedQuestion("q1", 0, "Where was the evidence collected?", listOf("Coastal cities", "Mountain villages", "A laboratory"),
            0, 0, first.length, "The opening paragraph identifies three coastal cities."),
        SpeedQuestion("q2", 1, "What should readers consider?", listOf("The limits", "The price", "The date"),
            0, first.length + 2, text.length, "The final paragraph asks readers to consider the limits."),
    )
    private val plan = SpeedChunker.plan(article.id, body.contentHash, text, SpeedGranularity.L2, 0, 2, 1)
        .copy(planKey = "self-test-plan", questions = questions, origin = "ai")
    private fun attempt(complete: Boolean = false, unseen: Boolean = true) = ReadingBaselineAttempt("baseline-record", article.id, body.contentHash,
        plan.planKey, 1_000, 41_000, listOf(NaturalParagraphTiming(0, 15_000, true), NaturalParagraphTiming(1, 15_000, true)),
        if (complete) listOf(SpeedAnswer("q1", 0, true), SpeedAnswer("q2", 1, false)) else emptyList(), 2,
        readingFinishedAt = 31_000, revealedAt = if (complete) 41_000 else null, completed = complete,
        pauses = 1, localUnseen = unseen, userConfirmedUnseen = true)

    @Test fun dailyBaselineEntryDoesNotDisplayTheArticleSummaryOrOpenTheReader() {
        var selected: Article? = null
        var readerOpened = false
        compose.setContent { LexiconTheme(AppSettings()) {
            DailyReadingHome(listOf(article), DailyEdition("2026-11-02", listOf(article.id), listOf("s")), false, {},
                { _, _ -> readerOpened = true }, {}, {}, onBaseline = { selected = it })
        } }
        compose.onNodeWithText(article.title).assertIsDisplayed()
        compose.onNodeWithText(article.summary).assertDoesNotExist()
        compose.onNodeWithTag("daily_baseline_${article.id}").performClick()
        compose.runOnIdle { assertEquals(article, selected); assertFalse(readerOpened) }
    }

    @Test fun setupKeepsTheOriginalAndQuestionsHiddenUntilExplicitPreparation() {
        var unseen = false
        var requested: Boolean? = null
        compose.setContent { LexiconTheme(AppSettings()) {
            ReadingBaselineScreen(article, AppSettings(), BaselineUiState(body = body, plan = plan, paragraphCount = 2, aiAllowed = true, hasKey = true),
                BaselineUiActions(onConfirmUnseen = { unseen = it }, onPrepare = { requested = it }))
        } }
        compose.onNodeWithText(first).assertDoesNotExist()
        compose.onNodeWithText(second).assertDoesNotExist()
        compose.onNodeWithText(article.summary).assertDoesNotExist()
        compose.onNodeWithText(questions.first().prompt).assertDoesNotExist()
        compose.onNodeWithTag("baseline_unseen_confirmation").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(unseen); assertNull(requested) }
        compose.onNodeWithTag("baseline_prepare_ai").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, requested) }
        screenshot("baseline-setup.png")
    }

    @Test fun readyAndPausedStatesHideOriginalTextWhileDracula200PercentControlsRemainUsable() {
        val settings = AppSettings(theme = ThemeMode.DARK)
        var state by mutableStateOf(BaselineUiState(stage = BaselineStage.READY, body = body, plan = plan))
        var completions = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                LexiconTheme(settings) {
                    ReadingBaselineScreen(article, settings, state, BaselineUiActions(onPlayPause = {
                        state = state.copy(stage = BaselineStage.READING, playing = !state.playing)
                    }, onCompleteParagraph = { completions++ }))
                }
            }
        }
        compose.onNodeWithTag("baseline_original_text").assertDoesNotExist()
        compose.onNodeWithTag("baseline_play_pause").assertIsDisplayed().performClick()
        compose.onNodeWithTag("baseline_original_text").assertTextEquals(first).assert(hasScrollAction())
        compose.onNodeWithTag("baseline_complete_paragraph").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, completions) }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("baseline_play_pause_label", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        screenshot("baseline-reading-dracula-font200.png")
        val layout = layouts.single()
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("baseline-ui-artifacts/font-layout.txt")
            .writeText("size=${layout.size}; constraints=${layout.layoutInput.constraints}; widthOverflow=${layout.didOverflowWidth}; heightOverflow=${layout.didOverflowHeight}; lineEnd=${layout.getLineEnd(0)}; textLength=${layout.layoutInput.text.length}; lineWidth=${layout.getLineRight(0) - layout.getLineLeft(0)}; lineBottom=${layout.getLineBottom(0)}; font=${layout.layoutInput.style.fontSize}; lineHeight=${layout.layoutInput.style.lineHeight}")
        assertFalse(layout.hasVisualOverflow)
        compose.onNodeWithTag("baseline_screen").performKeyInput { pressKey(Key.Spacebar) }
        compose.onNodeWithTag("baseline_original_text").assertDoesNotExist()
        compose.onNodeWithText(first).assertDoesNotExist()
        compose.onNodeWithTag("baseline_paused_cover").assertTextEquals("已暂停")
        compose.onNodeWithTag("baseline_complete_paragraph").assertIsNotEnabled()
        screenshot("baseline-paused-dracula-font200.png")
    }

    @Test fun answeringDoesNotRevealCorrectnessOrEvidenceBeforeTheLastSubmission() {
        var state by mutableStateOf(BaselineUiState(stage = BaselineStage.QUESTION, body = body, plan = plan, attempt = attempt()))
        var submissions = 0
        compose.setContent { LexiconTheme(AppSettings()) {
            ReadingBaselineScreen(article, AppSettings(), state, BaselineUiActions(onAnswer = { state = state.copy(selectedAnswer = it) },
                onSubmitAnswer = {
                    submissions++
                    state = state.copy(attempt = state.attempt!!.copy(answers = listOf(SpeedAnswer("q1", state.selectedAnswer!!, false))), selectedAnswer = null)
                }))
        } }
        compose.onNodeWithTag("baseline_submit_answer").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("baseline_answer_1").performScrollTo().performClick()
        compose.onNodeWithText(questions.first().explanation).assertDoesNotExist()
        compose.onNodeWithText(first).assertDoesNotExist()
        compose.onNodeWithText("参考答案：", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("baseline_submit_answer").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, submissions) }
        compose.onNodeWithTag("baseline_question").assertTextEquals(questions[1].prompt)
        compose.onNodeWithText(questions.first().explanation).assertDoesNotExist()
        compose.onNodeWithText("提交全部答案").performScrollTo().assertIsDisplayed()
    }

    @Test fun savedResultUsesSuppliedSummaryAndRevealsEvidenceOnlyOnDemand() {
        val record = attempt(complete = true)
        val summary = ReadingBaselineMetrics.summarize(record, plan)
        var original = 0
        compose.setContent { LexiconTheme(AppSettings()) {
            ReadingBaselineScreen(article, AppSettings(), BaselineUiState(stage = BaselineStage.RESULT, body = body, plan = plan,
                attempt = record, summary = summary), BaselineUiActions(onReadOriginal = { original++ }))
        } }
        compose.onNodeWithText("新材料自测").assertIsDisplayed()
        compose.onNodeWithText("50%").assertIsDisplayed()
        compose.onNodeWithText(questions.first().explanation).assertDoesNotExist()
        compose.onNodeWithText(first).assertDoesNotExist()
        compose.onNodeWithTag("baseline_evidence_toggle_q1").performScrollTo().performClick()
        compose.onNodeWithTag("baseline_explanation_q1").performScrollTo().assertTextEquals(questions.first().explanation)
        compose.onNodeWithTag("baseline_evidence_q1").performScrollTo().assertTextEquals(first)
        compose.onNodeWithTag("baseline_read_original").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, original) }
    }

    @Test fun familiarMaterialRemainsPracticeWithoutAnEffectiveSpeed() {
        val record = attempt(complete = true, unseen = false)
        val summary = ReadingBaselineMetrics.summarize(record, plan)
        compose.setContent { LexiconTheme(AppSettings()) {
            ReadingBaselineScreen(article, AppSettings(), BaselineUiState(stage = BaselineStage.RESULT, body = body, plan = plan,
                attempt = record, summary = summary), BaselineUiActions())
        } }
        compose.onNodeWithText("阅读练习").assertIsDisplayed()
        compose.onNodeWithText("新材料自测").assertDoesNotExist()
        compose.onNodeWithText("未测").assertIsDisplayed()
        compose.onNodeWithText("本次作为练习保留，不计入新材料基线。").performScrollTo().assertIsDisplayed()
    }

    @Test fun offlineResultDoesNotConvertMissingQuestionsIntoPerfectAccuracy() {
        val offlinePlan = plan.copy(questions = emptyList(), origin = "rules")
        val record = attempt(complete = true).copy(answers = emptyList(), questionCount = 0)
        val summary = ReadingBaselineMetrics.summarize(record, offlinePlan)
        compose.setContent { LexiconTheme(AppSettings()) {
            ReadingBaselineScreen(article, AppSettings(), BaselineUiState(stage = BaselineStage.RESULT, body = body, plan = offlinePlan,
                attempt = record, summary = summary), BaselineUiActions())
        } }
        compose.onAllNodesWithText("未测").assertCountEquals(2)
        compose.onNodeWithText("100%").assertDoesNotExist()
        compose.onNodeWithText("新材料自测").assertDoesNotExist()
        compose.onNodeWithText("没有完整理解检测，不计算有效速度。").performScrollTo().assertIsDisplayed()
    }

    @Test fun pendingOrFailedFinalSaveDoesNotExposeAnswersFromTheCompletedCandidate() {
        var state by mutableStateOf(BaselineUiState(stage = BaselineStage.RESULT, body = body, plan = plan,
            attempt = attempt(complete = true), saving = true))
        var retries = 0
        compose.setContent { LexiconTheme(AppSettings()) {
            ReadingBaselineScreen(article, AppSettings(), state, BaselineUiActions(onRetry = { retries++ }))
        } }
        compose.onNodeWithTag("baseline_evidence_toggle_q1").assertDoesNotExist()
        compose.onNodeWithText("50%").assertDoesNotExist()
        compose.onNodeWithText("记录暂不可用。").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(saving = false, error = "记录尚未保存") }
        compose.onNodeWithTag("baseline_evidence_toggle_q1").assertDoesNotExist()
        compose.onNodeWithTag("baseline_retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun baselineHistoryOnlyOpensTheSavedAttemptAndDoesNotInventAScore() {
        var opened: String? = null
        val record = attempt(complete = true)
        compose.setContent { LexiconTheme(AppSettings()) {
            BaselineHistoryScreen(listOf(record), listOf(article), {}, { opened = it })
        } }
        compose.onNodeWithText("WPM", substring = true).assertDoesNotExist()
        compose.onNodeWithText("50%").assertDoesNotExist()
        compose.onNodeWithTag("baseline_open_${record.id}").performClick()
        compose.runOnIdle { assertEquals(record.id, opened); assertNotEquals(article.id, opened) }
    }

    @Test fun incompleteHistoryResumesTheSameAttemptAtReadingOrAnsweringStage() {
        val reading = attempt().copy(id = "reading-resume", readingFinishedAt = null,
            paragraphs = listOf(NaturalParagraphTiming(0, 15_000, false), NaturalParagraphTiming(1, 0, false)))
        val answering = attempt().copy(id = "answer-resume", answers = listOf(SpeedAnswer("q1", 1, false)))
        val opened = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                LexiconTheme(AppSettings(theme = ThemeMode.DARK)) {
                    BaselineHistoryScreen(listOf(reading, answering), listOf(article), {}, { opened += it })
                }
            }
        }
        compose.onNodeWithTag("baseline_history").performScrollToNode(hasTestTag("baseline_open_reading-resume"))
        compose.onNodeWithText("继续自测").assertIsDisplayed().performClick()
        compose.onNodeWithTag("baseline_history").performScrollToNode(hasTestTag("baseline_open_answer-resume"))
        compose.onNodeWithText("继续答题").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("reading-resume", "answer-resume"), opened) }
        compose.onNodeWithText("50%").assertDoesNotExist()
        compose.onNodeWithText("新材料自测").assertDoesNotExist()
    }

    @Test fun resumedReadingUsesFrozenParagraphsAfterBodyCacheExpiresAndDoesNotAutoplay() {
        val material = ReadingBaselineMaterial(plan.planKey, body.contentHash,
            SourceRef("Frozen article title", "Frozen source"),
            listOf(BaselineSourceParagraph(0, 0, first), BaselineSourceParagraph(1, first.length + 2, second)))
        var state by mutableStateOf(BaselineUiState(stage = BaselineStage.READING, plan = plan, material = material,
            attempt = attempt().copy(resumedFromHistory = true), paragraphIndex = 1, elapsedMillis = 15_000))
        compose.setContent { LexiconTheme(AppSettings()) {
            ReadingBaselineScreen(article, AppSettings(), state,
                BaselineUiActions(onPlayPause = { state = state.copy(playing = !state.playing) }))
        } }
        compose.onNodeWithText("续读练习").assertIsDisplayed()
        compose.onNodeWithTag("baseline_original_text").assertDoesNotExist()
        compose.onNodeWithText("继续本段").assertIsDisplayed().performClick()
        compose.onNodeWithTag("baseline_original_text").assertTextEquals(second)
        compose.onNodeWithTag("baseline_resume_notice").assertIsDisplayed()
        compose.onNodeWithTag("baseline_play_pause").performClick()
        compose.onNodeWithTag("baseline_original_text").assertDoesNotExist()
    }

    @Test fun resumedResultUsesFrozenEvidenceAndRemainsOutsideTheFirstReadingBaseline() {
        val record = attempt(complete = true).copy(resumedFromHistory = true)
        val material = ReadingBaselineMaterial(plan.planKey, body.contentHash, SourceRef("Frozen article title", "Frozen source"),
            listOf(BaselineSourceParagraph(0, 0, first), BaselineSourceParagraph(1, first.length + 2, second)))
        compose.setContent { LexiconTheme(AppSettings()) {
            ReadingBaselineScreen(article, AppSettings(), BaselineUiState(stage = BaselineStage.RESULT,
                body = body.copy(text = "Unrelated current content", contentHash = "changed"), plan = plan, material = material,
                attempt = record, summary = ReadingBaselineMetrics.summarize(record, plan)), BaselineUiActions())
        } }
        compose.onNodeWithText("续读记录").assertIsDisplayed()
        compose.onNodeWithText("Frozen article title").assertIsDisplayed()
        compose.onNodeWithText("新材料自测").assertDoesNotExist()
        compose.onNodeWithText("跨次续读作为练习保留，不纳入首次阅读基线。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("baseline_evidence_toggle_q2").performScrollTo().performClick()
        compose.onNodeWithTag("baseline_evidence_q2").performScrollTo().assertTextEquals(second)
        compose.onNodeWithText("Unrelated current content").assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("baseline-ui-artifacts").apply { mkdirs() }
        directory.resolve(name).outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
