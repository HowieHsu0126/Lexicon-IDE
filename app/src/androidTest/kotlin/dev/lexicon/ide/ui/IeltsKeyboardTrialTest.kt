package dev.lexicon.ide.ui

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual IME and user gestures; frozen self-authored visual fixture, no database or network. */
class IeltsKeyboardTrialTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val arguments = InstrumentationRegistry.getArguments()
    private val night = arguments.getString("variant") == "night"
    private val scale = arguments.getString("fontScale")?.toFloat() ?: 1f
    private val output by lazy {
        File(compose.activity.filesDir, "validation-keyboard-144/${System.currentTimeMillis()}-${UUID.randomUUID()}")
            .apply { check(mkdirs()) }
    }
    private var answers by mutableStateOf(emptyList<IeltsResponse>())
    private var task1 by mutableStateOf("")
    private var task2 by mutableStateOf("")
    private var submits = 0
    private lateinit var candidate: IeltsCandidateView

    @Before fun mount() {
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("ielts-task1.json")
            .bufferedReader().use { it.readText() }
        val root = Json.parseToJsonElement(bytes).jsonObject
        val request = IeltsExerciseJson.decodeRequest(root.getValue("request").toString())
        val pkg = IeltsExerciseJson.decodePackage(root.getValue("package").toString())
        candidate = IeltsExerciseValidator.candidateView(request, pkg)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                LexiconTheme(AppSettings(theme = if (night) ThemeMode.DARK else ThemeMode.LIGHT)) {
                    IeltsPracticeScreen(candidate, answers, task2, false, null, null,
                        onAnswer = { id, value -> answers = answers.filterNot { it.questionId == id } + IeltsResponse(id, value) },
                        onWriting = { task2 = it }, onSubmit = { submits++ }, onBack = {}, onRetry = {},
                        writingTask1Text = task1, onWritingTask1 = { task1 = it })
                }
            }
        }
    }

    private fun show(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("ielts_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }

    private fun imeVisible(): Boolean {
        var visible = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            visible = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    private fun awaitIme(visible: Boolean) {
        compose.waitUntil(10_000) { imeVisible() == visible }
    }

    private fun longPressVisibleLetter(tag: String, text: String) {
        val field = show(tag)
        val results = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        fun descendants(node: SemanticsNode): List<SemanticsNode> =
            node.children.flatMap { listOf(it) + descendants(it) }
        val nodes = descendants(compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode())
        // The undecorated text layout retains its full height and scroll origin.
        val textNode = nodes.single { it.size == layout.size }
        val viewport = compose.onNodeWithTag("ielts_content").fetchSemanticsNode().boundsInRoot
        val visible = field.fetchSemanticsNode().boundsInRoot.intersect(viewport)
        val points = text.indices.filter { i -> i > 0 && i < text.lastIndex &&
            text[i - 1].isLetter() && text[i].isLetter() && text[i + 1].isLetter() }
            .map { textNode.positionInRoot + layout.getBoundingBox(it).center }
            .filter { visible.contains(it) }
        check(points.isNotEmpty()) { "No actual editable letter is visible for a long press" }
        val point = points.minBy { kotlin.math.abs(it.y - visible.center.y) }
        val root = compose.onNodeWithTag("ielts_practice")
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        root.performTouchInput { longClick(point - origin) }
    }

    private fun record(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(output, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        File(output, "$name.json").writeText(buildJsonObject {
            put("ime_visible", imeVisible()); put("font_scale", scale); put("night", night)
            put("reading_answers", answers.size); put("task1_characters", task1.length)
            put("task2_characters", task2.length); put("submissions", submits)
        }.toString())
    }

    @Test fun completionImeFinishesInputWithoutJumpingToAnEssayOrSubmitting() {
        val question = candidate.readingQuestions.filterIsInstance<IeltsCandidateCompletion>().last()
        val tag = "ielts_input_${question.questionId}"
        show(tag).performClick().performTextInput("sessions")
        awaitIme(true); record("01-completion-typing")
        compose.onNodeWithTag(tag).performImeAction()
        awaitIme(false)
        record("02-after-ime-action")
        compose.onAllNodes(isFocused()).assertCountEquals(0)
        assertEquals("sessions", answers.single { it.questionId == question.questionId }.answer)
        assertEquals("", task1); assertEquals("", task2); assertEquals(0, submits)
    }

    @Test fun openingOriginalEndsWritingInputAndKeepsTheDraft() {
        show("ielts_writing1_input").performClick().performTextInput("Visits increased at both branches.")
        awaitIme(true)
        show("ielts_source_toggle")
        // Programmatic positioning must not end input; only the explicit read action does.
        assertTrue(imeVisible()); record("03-before-open-source")
        compose.onNodeWithTag("ielts_source_toggle").performClick()
        awaitIme(false)
        compose.onNodeWithTag("ielts_source").assertExists()
        record("04-open-source")
        assertEquals("Visits increased at both branches.", task1)
        assertEquals(0, submits)
    }

    @Test fun outerListDragEndsInputWhileProgrammaticScrollKeepsIt() {
        show("ielts_writing_input").performClick().performTextInput("A local trial can improve public decisions.")
        awaitIme(true)
        show("ielts_writing_prompt")
        assertTrue(imeVisible())
        compose.onNodeWithTag("ielts_writing_input").assertIsFocused()
        // Begin on non-editable prompt text, rather than a multiline editor.
        compose.onNodeWithTag("ielts_writing_prompt").performTouchInput { swipeDown(durationMillis = 500) }
        awaitIme(false)
        record("05-outer-drag")
        assertEquals("A local trial can improve public decisions.", task2)
        assertEquals(0, submits)
    }

    @Test fun readingNumericEvidenceEndsTask1InputWithoutLosingTheDraft() {
        show("ielts_writing1_input").performClick().performTextInput("Visits increased at both branches.")
        awaitIme(true)
        show("ielts_visual_evidence_toggle").performClick()
        awaitIme(false)
        record("06-numeric-evidence")
        assertEquals("Visits increased at both branches.", task1)
        assertEquals(0, submits)
    }

    @Test fun essayInternalScrollAndSelectionKeepTheKeyboardAndText() {
        val text = (1..30).joinToString("\n") { "Paragraph $it discusses a different public priority." }
        show("ielts_writing_input").performClick().performTextInput(text)
        awaitIme(true)
        // Inserting a long draft changes the field height. Locate its rendered
        // area again before injecting touches, rather than using its old position.
        show("ielts_writing_input").performTouchInput { swipeDown(durationMillis = 500) }
        compose.onNodeWithTag("ielts_writing_input").assertIsFocused()
        assertTrue(imeVisible())
        record("07-editor-after-drag")
        longPressVisibleLetter("ielts_writing_input", text)
        compose.onNodeWithTag("ielts_writing_input").assertIsFocused()
        record("08-editor-after-long-press")
        File(output, "editor-layout.txt").writeText(
            compose.onNodeWithTag("ielts_practice", useUnmergedTree = true).printToString(maxDepth = 20))
        val selected = compose.onNodeWithTag("ielts_writing_input").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertFalse("Long press should still select editable text", selected.collapsed)
        assertTrue(imeVisible())
        record("09-editor-scroll-selection")
        assertEquals(text, task2)
        assertEquals(0, submits)
    }
}
