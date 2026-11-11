package dev.lexicon.ide.ui

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.SpeedGranularity
import dev.lexicon.domain.SpeedSegmentation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Authored sentence fixtures exercise exact legal offsets, draft ownership and touch targets. */
@RunWith(AndroidJUnit4::class)
class SpeedSegmentationEditorTest {
    @get:Rule val compose = createComposeRule()
    private val source = "The report shows how the report changed."
    private val start = 103
    private val first = start + source.indexOf("shows")
    private val second = start + source.indexOf("the report")
    private val third = start + source.indexOf("changed")
    private val segmentation = SpeedSegmentation("fixture-base-plan", "fixture-base-plan", "fixture-article", "fixture-body",
        SpeedGranularity.L2, 2, 4, start, start + source.length, source,
        listOf(first, second), listOf(first, second), listOf(first, second, third), false)

    @Test fun onlySuppliedLegalOffsetsCanChangeAndPreviewKeepsOriginalCharacters() {
        var saved: List<Int>? = null
        compose.setContent { LexiconTheme(dev.lexicon.domain.AppSettings(theme = dev.lexicon.domain.ThemeMode.LIGHT)) {
            SpeedSegmentationEditor(segmentation, false, null, { saved = it }, {}, {})
        } }
        compose.onNodeWithTag("segmentation_original_preview").assertTextEquals(source)
        compose.onNodeWithTag("segmentation_boundary_${start + 4}").assertDoesNotExist()
        compose.onNodeWithTag("segmentation_content").performScrollToNode(hasTestTag("segmentation_boundary_$first"))
        compose.onNodeWithTag("segmentation_boundary_$first").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("segmentation_content").performScrollToNode(hasTestTag("segmentation_boundary_$third"))
        compose.onNodeWithTag("segmentation_boundary_$third").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("segmentation_save").performClick()
        compose.runOnIdle { assertEquals(listOf(second, third), saved) }
        compose.onNodeWithTag("segmentation_content").performScrollToNode(hasTestTag("segmentation_difference"))
        compose.onNodeWithTag("segmentation_difference").assertTextEquals("3 个意群 · 与原切分相差 2 处")
        compose.onNodeWithTag("segmentation_content").performScrollToNode(hasTestTag("segmentation_original_preview"))
        compose.onNodeWithTag("segmentation_original_preview").assertTextEquals(source)
        compose.onNodeWithText("准确率", substring = true).assertDoesNotExist()
        saveScreenshot("segmentation-fixture-daylight.png")
    }

    @Test fun saveFailureKeepsDraftAndPendingSaveDisablesRepeatSubmission() {
        var busy by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        val saves = mutableListOf<List<Int>>()
        compose.setContent { LexiconTheme(dev.lexicon.domain.AppSettings()) {
            SpeedSegmentationEditor(segmentation, busy, error, {
                saves += it
                busy = true
            }, {}, {})
        } }
        compose.onNodeWithTag("segmentation_content").performScrollToNode(hasTestTag("segmentation_boundary_$first"))
        compose.onNodeWithTag("segmentation_boundary_$first").performClick()
        compose.onNodeWithTag("segmentation_save").performClick().assertIsNotEnabled()
        compose.onNodeWithTag("segmentation_close").assertIsNotEnabled()
        compose.onNodeWithTag("segmentation_boundary_$first").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, saves.size)
            error = "本机保存未完成，请重试。"
            busy = false
        }
        compose.onNodeWithTag("segmentation_boundary_$first").assertIsOff()
        compose.onNodeWithTag("segmentation_error").assertTextEquals("本机保存未完成，请重试。")
        compose.onNodeWithTag("segmentation_save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(listOf(second), listOf(second)), saves) }
    }

    @Test fun personalPlanOffersRestoreAndBackClosesOnlyTheEditor() {
        var restores = 0
        var open by mutableStateOf(true)
        compose.setContent { LexiconTheme(dev.lexicon.domain.AppSettings()) {
            Text("阅读仍在这里")
            if (open) SpeedSegmentationEditor(segmentation.copy(planKey = "fixture-personal-plan", hasPersonalEdits = false, breaks = listOf(second)),
                false, null, {}, { restores++ }, { open = false })
        } }
        compose.onNodeWithTag("segmentation_restore").assertIsEnabled().performClick()
        compose.onNodeWithText("恢复整组原切分").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, restores) }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("speed_segmentation_editor").assertDoesNotExist()
        compose.onNodeWithText("阅读仍在这里").assertIsDisplayed()
    }

    @Test fun longSentenceAtTwoHundredPercentKeepsEveryBoundaryAndSaveReachable() {
        val pieces = listOf("While the community reviewed the proposal, ", "which included a new public library, ",
            "the council listened to local teachers ", "and asked residents about practical opening hours ",
            "before deciding how to use the available space.")
        val longText = pieces.joinToString("")
        val offsets = pieces.dropLast(1).runningFold(500) { offset, piece -> offset + piece.length }.drop(1)
        val longModel = segmentation.copy(start = 500, end = 500 + longText.length, text = longText,
            suggestedBreaks = offsets.take(2), breaks = offsets.take(2), legalBreaks = offsets)
        var density = 1f
        compose.setContent {
            val existing = LocalDensity.current
            density = existing.density
            CompositionLocalProvider(LocalDensity provides Density(existing.density, 2f)) {
                LexiconTheme(dev.lexicon.domain.AppSettings(theme = dev.lexicon.domain.ThemeMode.DARK)) {
                    SpeedSegmentationEditor(longModel, false, null, {}, {}, {})
                }
            }
        }
        val screen = compose.onNodeWithTag("speed_segmentation_editor").fetchSemanticsNode().boundsInRoot
        offsets.forEachIndexed { index, offset ->
            compose.onNodeWithTag("segmentation_content").performScrollToNode(hasTestTag("segmentation_boundary_$offset"))
            compose.onNodeWithTag("segmentation_boundary_$offset").assertIsDisplayed()
            val bounds = compose.onNodeWithTag("segmentation_boundary_$offset").fetchSemanticsNode().boundsInRoot
            assertTrue("Every legal boundary needs a 48dp touch target", bounds.width >= 48 * density && bounds.height >= 48 * density)
            assertTrue(bounds.left >= screen.left && bounds.right <= screen.right)
            listOf("left" to pieces[index].trim(), "right" to pieces[index + 1].trim()).forEach { (side, text) ->
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag("segmentation_boundary_${offset}_$side", useUnmergedTree = true)
                    .assertTextEquals(text)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertFalse("The $side words adjacent to $offset must not be clipped or ellipsized",
                    layouts.single().hasVisualOverflow)
                assertEquals(text.length, layouts.single().getLineEnd(layouts.single().lineCount - 1))
            }
        }
        compose.onNodeWithTag("segmentation_boundary_${offsets.last()}").performClick()
        compose.onNodeWithTag("segmentation_save").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("segmentation_content").performScrollToNode(hasTestTag("segmentation_original_preview"))
        compose.onNodeWithTag("segmentation_original_preview").assertTextEquals(longText)
        saveScreenshot("segmentation-fixture-dracula-font200.png")
    }

    private fun saveScreenshot(name: String) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("speed-segmentation-ui").apply { mkdirs() }
        val bitmap = compose.onNodeWithTag("speed_segmentation_editor").captureToImage().asAndroidBitmap()
        directory.resolve(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        directory.resolve("README.txt").writeText("Authored test sentences; real Compose rendering; no AI, repository or API Key is used by this UI test.\n")
    }
}
