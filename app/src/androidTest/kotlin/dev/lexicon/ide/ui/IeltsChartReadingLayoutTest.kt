package dev.lexicon.ide.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Rendering fixture uses the exact long title observed in the self-authored 146 trial. */
class IeltsChartReadingLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val title = "Evening visits to the North and South archive branches during the first and final three months of the six-month pilot in Merebridge"

    @Test fun longDescriptionKeepsEveryWordAndScaledTypeWhileBringingDataCloser() {
        val raw = instrumentation.context.assets.open("ielts-task1.json").bufferedReader().use { it.readText() }
        val root = Json.parseToJsonElement(raw).jsonObject
        val request = IeltsExerciseJson.decodeRequest(root.getValue("request").toString())
        val original = IeltsExerciseJson.decodePackage(root.getValue("package").toString()).writingTask1.task!!
        val visual = original.visual.copy(title = title)
        val task = original.copy(visual = visual, prompt = IeltsVisualContract.taskPrompt(visual))
        val night = InstrumentationRegistry.getArguments().getString("variant") == "night"
        val folder = File(instrumentation.targetContext.filesDir,
            "validation-chart-147/${System.currentTimeMillis()}-${UUID.randomUUID()}").apply { check(mkdirs()) }
        var width by mutableStateOf(280)
        var scale by mutableFloatStateOf(1f)
        lateinit var measurer: TextMeasurer
        lateinit var previousHeading: TextStyle
        lateinit var readingStyle: TextStyle
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                LexiconTheme(AppSettings(theme = if (night) ThemeMode.DARK else ThemeMode.LIGHT)) {
                    measurer = rememberTextMeasurer()
                    previousHeading = MaterialTheme.typography.titleMedium
                    readingStyle = MaterialTheme.typography.bodyLarge
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            Column(Modifier.width(width.dp).fillMaxHeight()
                                .verticalScroll(rememberScrollState()).padding(12.dp)) {
                                IeltsVisualWritingCard(task, request.paragraphs, "", 0, true, false, {})
                            }
                        }
                    }
                }
            }
        }
        val cases = mutableListOf<JsonObject>()
        for (size in listOf(280, 340)) for (fontScale in listOf(1f, 2f)) {
            compose.runOnIdle { width = size; scale = fontScale }
            compose.onNodeWithTag("ielts_visual_title").performScrollTo().assertTextEquals(title)
            val layout = completeLayout("ielts_visual_title", fontScale)
            assertEquals("The requested narrow viewport is actually applied", size - 56f,
                layout.size.width / layout.layoutInput.density.density, 1f)
            assertEquals(readingStyle.fontSize, layout.layoutInput.style.fontSize)
            assertEquals(ReadingSerif, layout.layoutInput.style.fontFamily)
            val old = measurer.measure(AnnotatedString(title), previousHeading,
                constraints = Constraints(maxWidth = layout.size.width))
            assertEquals(fontScale, old.layoutInput.density.fontScale, .01f)
            assertTrue("Same text at the same width uses less height than the former monospace heading",
                layout.size.height < old.size.height)
            compose.onNodeWithTag("ielts_visual_unit").assertTextEquals(visual.unit)
            completeLayout("ielts_visual_unit", fontScale)
            val name = "title-${size}-${fontScale}.png"
            val screenshot = instrumentation.uiAutomation.takeScreenshot()!!
            try { File(folder, name).outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { screenshot.recycle() }
            visual.series.forEachIndexed { si, _ -> visual.categories.forEachIndexed { ci, _ ->
                compose.onNodeWithTag("ielts_visual_cell_${si}_$ci").performScrollTo().assertIsDisplayed()
            } }
            compose.onNodeWithTag("ielts_visual_instruction").performScrollTo().assertTextEquals(IeltsVisualContract.INSTRUCTION)
            completeLayout("ielts_visual_instruction", fontScale)
            cases += buildJsonObject {
                put("width_dp", size); put("font_scale", fontScale); put("layout_width_px", layout.size.width)
                put("title_height_px", layout.size.height); put("previous_title_height_px", old.size.height)
                put("title_lines", layout.lineCount); put("previous_title_lines", old.lineCount)
                put("full_text_retained", true); put("all_cells_reachable", true)
                put("screenshot", name); put("screenshot_sha256", sha(File(folder, name).readBytes()))
            }
        }
        File(folder, "receipt.json").writeText(buildJsonObject {
            put("passed", true); put("night", night); put("model_calls", 0)
            put("fixture", "self_authored_long_title_with_existing_synthetic_chart")
            put("cases", JsonArray(cases))
        }.toString())
    }

    private fun completeLayout(tag: String, scale: Float): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(scale, layout.layoutInput.density.fontScale, .01f)
        assertFalse("$tag fits its layout: size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, lines=${layout.lineCount}, scale=$scale", layout.hasVisualOverflow)
        for (line in 0 until layout.lineCount) {
            assertFalse(layout.isLineEllipsized(line))
            assertTrue(layout.getLineLeft(line) >= -1.5f)
            assertTrue(layout.getLineRight(line) <= layout.size.width + 1.5f)
        }
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = false))
        return layout
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
