package dev.lexicon.ide.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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

/** Display, exact-range hit testing and user decisions; no HTTP or simulated ability scores. */
@RunWith(AndroidJUnit4::class)
class SpeedReadingUiTest {
    @get:Rule val compose = createComposeRule()
    private val text = "The report shows clear evidence. The report supports change."
    private val article = Article("speed-ui", "g", listOf("s"), "Evidence and change", "https://example.org/speed", "", sourceName = "Self-authored fixture")
    private val body = ArticleBody(article.id, text, fingerprint(text), 1, Long.MAX_VALUE, "Self-authored test")
    private val second = text.indexOf("The report", 1)
    private val plan = SpeedPlan(article.id, body.contentHash, SpeedGranularity.L2, listOf(0), listOf(
        SenseChunk(0, 11, 0, 0, 2, "phrase"),
        SenseChunk(11, second, 0, 0, 3, "sentence"),
        SenseChunk(second, second + 11, 0, 1, 2, "phrase"),
        SenseChunk(second + 11, text.length, 0, 1, 2, "sentence"),
    ))

    @Test fun setupOffersOfflineWithoutPretendingToHaveComprehensionQuestions() {
        var useAi: Boolean? = null
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedReadingScreen(article, body, AppSettings(), SpeedUiState(hasKey = false), SpeedUiActions(onPrepare = { useAi = it }))
        } }
        compose.onNodeWithTag("speed_prepare_ai").assertDoesNotExist()
        compose.onNodeWithTag("speed_prepare_offline").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(false, useAi) }
        compose.onNodeWithText("离线切分供练习参考；没有理解题时不计算有效速度。").performScrollTo().assertIsDisplayed()
    }

    @Test fun groupTapUsesTheSecondOccurrenceAndKeepsOriginalCharacters() {
        var selected = -1
        var pauses = 0
        var previous = 0
        var next = 0
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedReadingScreen(article, body, AppSettings(), SpeedUiState(stage = SpeedStage.READING, plan = plan),
                SpeedUiActions(onSeek = { selected = it }, onPlayPause = { pauses++ }, onPrevious = { previous++ }, onNext = { next++ }))
        } }
        val shown = compose.onNodeWithTag("speed_original_text").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
        assertEquals(text, shown)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("speed_original_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        compose.onNodeWithTag("speed_original_text").performTouchInput { click(layouts.single().getBoundingBox(second + 5).center) }
        compose.runOnIdle { assertEquals(2, selected) }
        saveScreenshot("speed-reading-original.png")
        compose.onNodeWithTag("speed_reading_screen").performKeyInput { pressKey(Key.Spacebar); pressKey(Key.DirectionLeft); pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(1, pauses); assertEquals(1, previous); assertEquals(1, next) }
        assertEquals(text, compose.onNodeWithTag("speed_original_text").fetchSemanticsNode().config[SemanticsProperties.Text].single().text)
    }

    @Test fun changingGranularityDoesNotReuseThePreviousPlansSpeedEstimate() {
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedReadingScreen(article, body, AppSettings(),
                SpeedUiState(plan = plan, selectedGranularity = SpeedGranularity.L3, groupsPerMinute = 60), SpeedUiActions())
        } }
        compose.onNodeWithText("播放约 360 WPM").performScrollTo().assertIsDisplayed()
    }

    @Test fun fadedRoundChangesPaintWithoutChangingOriginalTextOrLineBreaks() {
        var state by mutableStateOf(SpeedUiState(stage = SpeedStage.READING, plan = plan))
        val settings = AppSettings(theme = ThemeMode.DARK)
        compose.setContent {
            val original = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(original.density, 2f)) {
                LexiconTheme(settings) { SpeedReadingScreen(article, body, settings, state, SpeedUiActions()) }
            }
        }
        val guided = originalLayout()
        saveScreenshot("speed-guided-dracula-font200.png")
        compose.runOnIdle { state = state.copy(round = SpeedPacedRound.FADED) }
        compose.waitForIdle()
        val faded = originalLayout()
        assertEquals(text, faded.layoutInput.text.text)
        assertEquals(guided.size, faded.size)
        assertEquals(guided.lineCount, faded.lineCount)
        repeat(guided.lineCount) { line ->
            assertEquals(guided.getLineStart(line), faded.getLineStart(line))
            assertEquals(guided.getLineEnd(line), faded.getLineEnd(line))
        }
        assertEquals(.72f, guided.layoutInput.style.color.alpha, .005f)
        assertEquals(.90f, faded.layoutInput.style.color.alpha, .005f)
        compose.onNodeWithTag("speed_tools").performClick()
        compose.onNodeWithText("重复练习 · 保留节奏，逐步减少提示").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("speed_tools_close").performClick()
        compose.onNodeWithTag("speed_play_pause").assertIsDisplayed()
        saveScreenshot("speed-faded-dracula-font200.png")
    }

    @Test fun nextRoundRequiresAnExplicitActionAndFadedRecapDoesNotOfferNewScoresOrAdvice() {
        val run = SpeedRun("guided", article.id, body.contentHash, SpeedGranularity.L2, plan.planKey, 0, 10_000, 10_000,
            9, 4, List(4) { 2500L }, 0, 0, emptyList(), 0, true, 60, "rules")
        var state by mutableStateOf(SpeedUiState(stage = SpeedStage.RESULT, plan = plan, result = run,
            canContinueRound = true, personalizationAvailable = true))
        var nextRound = 0
        var original = 0
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedReadingScreen(article, body, AppSettings(), state,
                SpeedUiActions(onNextRound = { nextRound++ }, onReadOriginal = { original++ }))
        } }
        compose.runOnIdle { assertEquals(0, nextRound) }
        compose.onNodeWithText("淡出提示，再读一遍").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, nextRound)
            state = state.copy(round = SpeedPacedRound.FADED, result = run.copy(firstAttempt = false, round = SpeedPacedRound.FADED))
        }
        compose.onNodeWithText("理解正确率").assertDoesNotExist()
        compose.onNodeWithText("有效速度").assertDoesNotExist()
        compose.onNodeWithTag("speed_personalization").assertDoesNotExist()
        compose.onNodeWithText("去掉提示，自主阅读").performScrollTo().assertIsEnabled()
        compose.runOnIdle { state = state.copy(saving = true) }
        compose.onNodeWithTag("speed_next_round").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(saving = false) }
        compose.onNodeWithTag("speed_read_original").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, original) }
    }

    @Test fun preparedTimingEstimateUsesEveryActualChunkAtTheChosenPace() {
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedReadingScreen(article, body, AppSettings(), SpeedUiState(stage = SpeedStage.READING, plan = plan), SpeedUiActions())
        } }
        val average = plan.chunks.map { it.wordCount }.average()
        val durations = plan.chunks.map { SpeedPacing.durationMillis(it, 60, average) }
        compose.onNodeWithTag("speed_tools").performClick()
        compose.onNodeWithTag("speed_timing_estimate").performScrollTo().assertTextEquals(
            "平均每块 ${"%.1f".format(durations.average() / 1000.0)} 秒 · 本组约 ${"%.1f".format(durations.sum() / 60_000.0)} 分钟")
    }

    private fun originalLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("speed_original_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    @Test fun comprehensionKeepsReferenceHiddenUntilUserAnswers() {
        val question = SpeedQuestion("q1", 0, "What does the report provide?", listOf("Evidence", "A timetable", "A price"),
            0, 0, second, "The first sentence explicitly mentions clear evidence.")
        var state by mutableStateOf(SpeedUiState(stage = SpeedStage.QUESTION, plan = plan.copy(questions = listOf(question))))
        var continued = false
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedReadingScreen(article, body, AppSettings(), state, SpeedUiActions(onAnswer = {
                state = state.copy(selectedAnswer = it, answerRevealed = true)
            }, onContinue = { continued = true }))
        } }
        compose.onNodeWithTag("speed_answer_explanation").assertDoesNotExist()
        compose.onNodeWithTag("speed_continue").assertDoesNotExist()
        compose.onNodeWithTag("speed_answer_1").performClick()
        compose.onNodeWithText("再核对这处信息").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("speed_answer_explanation").performScrollTo().assertTextEquals(question.explanation)
        compose.onNodeWithTag("speed_continue").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(continued); assertEquals(1, state.selectedAnswer) }
    }

    @Test fun resultWithoutCompleteChecksShowsUnknownRatherThanZeroOrFullAccuracy() {
        val run = SpeedRun("run", article.id, body.contentHash, SpeedGranularity.L2, "plan", 0, 10_000, 10_000,
            9, 4, listOf(2_000, 3_000, 2_000, 3_000), 1, 1, emptyList(), 0, true, 60, "rules")
        var restarted = false
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedReadingScreen(article, body, AppSettings(), SpeedUiState(stage = SpeedStage.RESULT, plan = plan, result = run),
                SpeedUiActions(onRestart = { restarted = true }))
        } }
        compose.onAllNodesWithText("未测").assertCountEquals(2)
        compose.onNodeWithText("0%").assertDoesNotExist()
        compose.onNodeWithText("100%").assertDoesNotExist()
        compose.onNodeWithText("缺少完整理解检测，不计算有效速度。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("speed_restart").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(restarted) }
    }

    @Test fun correctionPausesPlaybackAndRecordsTheUsersChoice() {
        var paused = false
        var correction: Pair<String, String>? = null
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedReadingScreen(article, body, AppSettings(), SpeedUiState(stage = SpeedStage.READING, plan = plan, playing = true),
                SpeedUiActions(onPauseForTools = { paused = true }, onCorrection = { kind, comment -> correction = kind to comment }))
        } }
        compose.onNodeWithTag("speed_tools").performClick()
        compose.onNodeWithText("切分纠错").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(paused) }
        compose.onNodeWithText("这一块过长").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals("too_long" to "", correction) }
    }

    @Test fun correctionCannotMergeAcrossTwoSentencesInTheSameParagraph() {
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedReadingScreen(article, body, AppSettings(),
                SpeedUiState(stage = SpeedStage.READING, plan = plan, chunkIndex = 1), SpeedUiActions())
        } }
        compose.onNodeWithTag("speed_tools").performClick()
        compose.onNodeWithText("切分纠错").performScrollTo().performClick()
        compose.onNodeWithText("这里不该断").assertIsNotEnabled()
        compose.onNodeWithText("这一块过长").assertIsEnabled()
    }

    @Test fun readingAndResultOfferSegmentationEditingWithoutStartingAnotherRun() {
        val run = SpeedRun("edit-run", article.id, body.contentHash, SpeedGranularity.L2, "plan", 0, 10_000, 10_000,
            9, 4, listOf(2_000, 3_000, 2_000, 3_000), 0, 0, emptyList(), 0, true, 60, "rules")
        var state by mutableStateOf(SpeedUiState(stage = SpeedStage.READING, plan = plan))
        var edits = 0
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedReadingScreen(article, body, AppSettings(), state, SpeedUiActions(onEditSegmentation = { edits++ }))
        } }
        compose.onNodeWithTag("speed_tools").performClick()
        compose.onNodeWithTag("speed_edit_segmentation").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, edits); state = state.copy(segmentationBusy = true) }
        // Editing closes tools; the parent entry blocks another edit while preparation is busy.
        compose.onNodeWithTag("speed_tools").assertIsNotEnabled()
        compose.onNodeWithTag("speed_edit_segmentation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, edits) }
        compose.runOnIdle { state = state.copy(stage = SpeedStage.RESULT, result = run, segmentationBusy = false) }
        compose.onNodeWithTag("speed_edit_segmentation").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, edits); assertEquals(SpeedStage.RESULT, state.stage) }
    }

    @Test fun personalSegmentationKeepsQuestionAvailabilitySeparateFromItsOriginLabel() {
        val question = SpeedQuestion("manual-q", 0, "What is described?", listOf("Evidence", "Cost", "Time"),
            0, 0, second, "The report describes evidence.")
        var editedPlan by mutableStateOf(plan.copy(origin = "manual", basePlanKey = "base",
            questions = listOf(question)))
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedReadingScreen(article, body, AppSettings(),
                SpeedUiState(stage = SpeedStage.READING, plan = editedPlan), SpeedUiActions())
        } }
        compose.onNodeWithTag("speed_tools").performClick()
        compose.onNodeWithText("个人切分 · 段末检查理解").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("AI 校准切分", substring = true).assertDoesNotExist()
        compose.runOnIdle { editedPlan = editedPlan.copy(questions = emptyList()) }
        compose.onNodeWithText("个人切分 · 本轮不测理解率").performScrollTo().assertIsDisplayed()
    }

    @Test fun failedResultSaveOffersRetryBeforeStartingAnotherRun() {
        val run = SpeedRun("run", article.id, body.contentHash, SpeedGranularity.L2, "plan", 0, 10_000, 10_000,
            9, 4, listOf(2_000, 3_000, 2_000, 3_000), 0, 0, emptyList(), 0, true, 60, "rules")
        var retries = 0
        var state by mutableStateOf(SpeedUiState(stage = SpeedStage.RESULT, plan = plan, result = run, error = "保存失败"))
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedReadingScreen(article, body, AppSettings(), state, SpeedUiActions(onContinue = {
                retries++
                state = state.copy(error = null, saving = true)
            }))
        } }
        compose.onNodeWithTag("speed_restart").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("speed_retry_save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithTag("speed_retry_save").assertDoesNotExist()
        compose.onNodeWithText("正在保存…").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state = state.copy(saving = false) }
        compose.onNodeWithTag("speed_restart").performScrollTo().assertIsEnabled()
    }

    @Test fun largeFontPreservesPlaybackControlsAndScrollableOriginal() {
        compose.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density, 2f)) {
                LexiconTheme(AppSettings()) {
                    SpeedReadingScreen(article, body, AppSettings(), SpeedUiState(stage = SpeedStage.READING, plan = plan), SpeedUiActions())
                }
            }
        }
        compose.onNodeWithTag("speed_play_pause").assertIsDisplayed()
        compose.onNodeWithTag("speed_next").assertIsDisplayed()
        compose.onNodeWithTag("speed_tools").performClick()
        compose.onNodeWithTag("speed_sentence_mode").performScrollTo().assertIsDisplayed()
        val sentenceLabel = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("speed_sentence_label", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(sentenceLabel) }
        assertEquals("The sentence-view action must wrap as a whole control, not split its label", 1, sentenceLabel.single().lineCount)
        saveScreenshot("speed-reading-font-200-check.png", "speed_tools_sheet")
        val result = sentenceLabel.single()
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("speed-ui-artifacts/font-layout.txt")
            .writeText("size=${result.size}; constraints=${result.layoutInput.constraints}; widthOverflow=${result.didOverflowWidth}; heightOverflow=${result.didOverflowHeight}; lineEnd=${result.getLineEnd(0)}; textLength=${result.layoutInput.text.length}; lineWidth=${result.getLineRight(0) - result.getLineLeft(0)}; lineBottom=${result.getLineBottom(0)}; font=${result.layoutInput.style.fontSize}; lineHeight=${result.layoutInput.style.lineHeight}")
        assertFalse(sentenceLabel.single().hasVisualOverflow)
        compose.onNodeWithTag("speed_tools_close").performClick()
        compose.onNodeWithContentDescription("上一意群").assertExists()
        compose.onNodeWithContentDescription("下一意群").assertIsDisplayed()
        compose.onNodeWithContentDescription("重读当前意群").assertIsDisplayed()
        compose.onNodeWithTag("speed_chunk_surface").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("speed_chunk_surface").fetchSemanticsNode().boundsInRoot.height > 0)
        compose.onNodeWithTag("speed_original_text").assert(hasScrollAction())
        saveScreenshot("speed-reading-font-200.png")
    }

    @Test fun draculaTextKeepsItsReaderColorWithoutAParentSurface() {
        var expectedInk = Color.Unspecified
        var expectedSheetInk = Color.Unspecified
        var background = Color.Unspecified
        val settings = AppSettings(theme = ThemeMode.DARK)
        compose.setContent { LexiconTheme(settings) {
            expectedInk = readerTextStyle(settings.reader).color
            expectedSheetInk = MaterialTheme.colorScheme.onSurface
            background = readerBackground(settings.reader)
            SpeedReadingScreen(article, body, settings,
                SpeedUiState(stage = SpeedStage.READING, plan = plan), SpeedUiActions())
        } }
        val counterLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("speed_group_counter").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(counterLayouts) }
        val counterInk = counterLayouts.single().layoutInput.style.color
        assertEquals("The counter must inherit the reader's content color", expectedInk, counterInk)
        val readerContrast = (counterInk.luminance() + .05f) / (background.luminance() + .05f)
        assertTrue("The counter must remain legible on the Dracula reading background", readerContrast >= 4.5f)

        compose.onNodeWithTag("speed_tools").performClick()
        val rateLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("speed_rate_label").performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(rateLayouts) }
        val rateInk = rateLayouts.single().layoutInput.style.color
        assertEquals("The speed label must inherit its modal surface content color", expectedSheetInk, rateInk)
        val sheet = compose.onNodeWithTag("speed_tools_sheet").captureToImage().asAndroidBitmap()
        // The tagged content corner is inside the modal surface and outside the padded header.
        val sheetBackground = Color(sheet.getPixel(1.coerceAtMost(sheet.width - 1), 1.coerceAtMost(sheet.height - 1)))
        val sheetContrast = (rateInk.luminance() + .05f) / (sheetBackground.luminance() + .05f)
        assertTrue("The speed label must remain legible on the Dracula tools surface", sheetContrast >= 4.5f)
        compose.onNodeWithTag("speed_tools_close").performClick()
        saveScreenshot("speed-reading-dracula-content.png")
    }

    private fun saveScreenshot(name: String, tag: String? = null) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("speed-ui-artifacts").apply { mkdirs() }
        val target = if (tag == null) compose.onRoot() else compose.onNodeWithTag(tag)
        val bitmap = target.captureToImage().asAndroidBitmap()
        directory.resolve(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
