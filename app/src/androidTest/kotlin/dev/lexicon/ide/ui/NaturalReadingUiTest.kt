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

@RunWith(AndroidJUnit4::class)
class NaturalReadingUiTest {
    @get:Rule val compose = createComposeRule()
    private val text = "The first paragraph gives the context.\n\nThe report gives clear evidence, while the final paragraph asks readers to consider its limits."
    private val article = Article("natural-ui", "g", listOf("s"), "A self-authored training example", "https://example.org/natural", "")
    private val body = ArticleBody(article.id, text, fingerprint(text), 1, Long.MAX_VALUE, "Self-authored test")
    private val plan = SpeedChunker.plan(article.id, body.contentHash, text, SpeedGranularity.L2, 1, 1)
    private fun attempt() = NaturalReadingAttempt("natural", article.id, body.contentHash, plan.planKey, "faded",
        1_000, 31_000, listOf(NaturalParagraphTiming(1, 30_000, true)), true, 1, 0)

    @Test fun naturalRoundUsesTheSameOriginalLayoutAndHasNoPacedNavigation() {
        var state by mutableStateOf(SpeedUiState(stage = SpeedStage.READING, plan = plan, round = SpeedPacedRound.FADED))
        var seeks = 0
        var moves = 0
        val settings = AppSettings(theme = ThemeMode.DARK)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                LexiconTheme(settings) {
                    SpeedReadingScreen(article, body, settings, state,
                        SpeedUiActions(onSeek = { seeks++ }, onNext = { moves++ }, onPrevious = { moves++ }))
                }
            }
        }
        val faded = layout("speed_original_text")
        compose.runOnIdle { state = state.copy(stage = SpeedStage.RESULT, natural = NaturalUiState(plan)) }
        val natural = layout("natural_original_text")
        assertEquals(text.substring(text.indexOf("The report")), natural.layoutInput.text.text)
        assertEquals(faded.layoutInput.text, natural.layoutInput.text)
        assertEquals(faded.lineCount, natural.lineCount)
        repeat(faded.lineCount) { line ->
            assertEquals(faded.getLineStart(line), natural.getLineStart(line))
            assertEquals(faded.getLineEnd(line), natural.getLineEnd(line))
        }
        compose.onNodeWithTag("natural_original_text").assert(hasScrollAction()).performTouchInput { click() }
        compose.onNodeWithTag("speed_reading_screen").performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(0, seeks); assertEquals(0, moves) }
        listOf("speed_slider", "speed_group_counter", "speed_next", "speed_previous", "speed_repeat", "speed_edit_segmentation").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
        compose.onNodeWithTag("natural_play_pause").assertIsDisplayed()
        compose.onNodeWithTag("natural_complete_paragraph").assertIsDisplayed().assertIsNotEnabled()
        screenshot("natural-reading-dracula-font200.png")
    }

    @Test fun timerAndCompletionAreExplicitAndSpaceOnlyTogglesNaturalReading() {
        var natural by mutableStateOf(NaturalUiState(plan))
        var toggles = 0
        var completes = 0
        var paced = 0
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedReadingScreen(article, body, AppSettings(), SpeedUiState(stage = SpeedStage.RESULT, plan = plan, natural = natural),
                SpeedUiActions(onPlayPause = { paced++ }, onNaturalPlayPause = {
                    toggles++
                    natural = natural.copy(playing = !natural.playing)
                }, onNaturalCompleteParagraph = { completes++ }))
        } }
        compose.onNodeWithTag("natural_timer_status").assertTextEquals("按自己的节奏读完这一段")
        compose.onNodeWithTag("natural_play_pause").performClick()
        compose.onNodeWithTag("natural_timer_status").assertTextEquals("正在计时")
        compose.onNodeWithTag("natural_complete_paragraph").performClick()
        compose.onNodeWithTag("speed_reading_screen").performKeyInput { pressKey(Key.Spacebar) }
        compose.runOnIdle { assertEquals(2, toggles); assertEquals(1, completes); assertEquals(0, paced) }
        compose.runOnIdle { natural = natural.copy(elapsedMillis = 65_000, paragraphMillis = 65_000) }
        compose.onNodeWithTag("natural_timer_status").assertTextEquals("已读 1 分 5 秒 · 已暂停")
        compose.onNodeWithText("继续阅读").assertIsDisplayed()
    }

    @Test fun naturalResultHasSelfTimedRateButNoRepeatedComprehensionOrEffectiveSpeed() {
        var original = 0
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedReadingScreen(article, body, AppSettings(), SpeedUiState(stage = SpeedStage.RESULT, plan = plan,
                natural = NaturalUiState(plan, result = attempt())), SpeedUiActions(onReadOriginal = { original++ }))
        } }
        compose.onNodeWithText("自主阅读完成").assertIsDisplayed()
        compose.onNodeWithText("30 秒").assertIsDisplayed()
        compose.onNodeWithText("自测阅读速度").assertIsDisplayed()
        compose.onNodeWithText("理解正确率").assertDoesNotExist()
        compose.onNodeWithText("有效速度").assertDoesNotExist()
        compose.onNodeWithTag("speed_personalization").assertDoesNotExist()
        compose.onNodeWithTag("speed_read_original").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, original) }
        screenshot("natural-reading-result.png")
    }

    @Test fun failedNaturalSaveRetainsTheResultAndOnlyExplicitlyRetries() {
        var retries = 0
        var natural by mutableStateOf(NaturalUiState(plan, result = attempt(), error = "保存失败"))
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedReadingScreen(article, body, AppSettings(), SpeedUiState(stage = SpeedStage.RESULT, plan = plan, natural = natural),
                SpeedUiActions(onNaturalRetry = { retries++; natural = natural.copy(error = null, saving = true) }))
        } }
        compose.onNodeWithTag("speed_read_original").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("natural_retry").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(attempt(), natural.result) }
        compose.onNodeWithTag("natural_retry").assertDoesNotExist()
        compose.onNodeWithText("正在保存…").performScrollTo().assertIsDisplayed()
    }

    @Test fun quickFinishRetainsTimeWithoutPresentingAnImplausibleSpeedScore() {
        val quick = attempt().copy(paragraphs = attempt().paragraphs.map { it.copy(activeMillis = 100) })
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedReadingScreen(article, body, AppSettings(), SpeedUiState(stage = SpeedStage.RESULT, plan = plan,
                natural = NaturalUiState(plan, result = quick)), SpeedUiActions())
        } }
        compose.onNodeWithText("自主阅读完成").assertExists()
        compose.onNodeWithText("暂不估算").assertExists()
        compose.onNodeWithTag("natural_short_timing").assertExists()
        compose.onAllNodesWithText("WPM", substring = true).assertCountEquals(0)
    }

    private fun layout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun screenshot(name: String) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("speed-ui-artifacts").apply { mkdirs() }
        directory.resolve(name).outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
