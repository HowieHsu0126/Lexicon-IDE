package dev.lexicon.ide.ui

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.flowOf
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual LexiconApp navigation; self-authored article and existing in-memory test delegates only. */
@RunWith(AndroidJUnit4::class)
class IeltsMainNavigationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun commandAndReaderToolEntriesReturnWithoutReplacingTheOriginalReaderPosition() {
        val repo = NavigationRepository()
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "validation-1.35.0/ielts-main-navigation/${System.currentTimeMillis()}-${UUID.randomUUID()}")
        check(output.mkdirs())
        val report = JSONObject().put("schema", "lexicon.ielts.main-navigation-ui.v1")
            .put("material", "self_authored_synthetic_article").put("database", "none")
            .put("network", "no_transport_constructed").put("body_hash", repo.body.contentHash)
        var passed = false
        try {
            compose.setContent {
                val activity = LocalContext.current.findActivity()
                DisposableEffect(activity) {
                    val window = activity?.window
                    val previousMode = window?.attributes?.softInputMode
                    val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
                    val lightStatus = controller?.isAppearanceLightStatusBars
                    val lightNavigation = controller?.isAppearanceLightNavigationBars
                    (activity as? ComponentActivity)?.enableEdgeToEdge()
                    if (window != null && previousMode != null) window.setSoftInputMode(
                        (previousMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    onDispose {
                        if (window != null && previousMode != null) {
                            window.setSoftInputMode(previousMode)
                            WindowCompat.setDecorFitsSystemWindows(window, true)
                            lightStatus?.let { controller?.isAppearanceLightStatusBars = it }
                            lightNavigation?.let { controller?.isAppearanceLightNavigationBars = it }
                        }
                    }
                }
                LexiconApp(repo)
            }
            compose.waitUntil(10_000) { exists("daily_intensive_${repo.article.id}") }

            // The command palette belongs to the list/management workspace, not the Zen overlay.
            compose.onNodeWithText(">_  命令").assertIsDisplayed().performClick()
            compose.onNodeWithText("搜索命令…").performTextInput("IELTS")
            compose.onNodeWithText("雅思练习").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { exists("ielts_workspace") }
            compose.onNodeWithTag("ielts_connection").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("ielts_generate").assertDoesNotExist()
            screenshot(output, "01-command-workspace", "ielts_workspace")
            compose.onNodeWithTag("ielts_workspace_back").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { exists("daily_intensive_${repo.article.id}") && !exists("ielts_workspace") }
            compose.onNodeWithText(repo.article.title).assertIsDisplayed()
            assertEquals(0, repo.openCalls.get())
            report.put("command_entry_round_trip", true)

            compose.onNodeWithTag("daily_intensive_${repo.article.id}").performScrollTo().assertIsEnabled().performClick()
            awaitReader()
            assertEquals(repo.body.text, editorText())
            var swipes = 0
            while (viewportParagraph() < 6 && swipes < 16) {
                compose.onNodeWithTag("zen_reader_viewport").performTouchInput {
                    swipe(Offset(centerX, height * .76f), Offset(centerX, height * .24f), 650)
                }
                swipes++
                settleReader()
            }
            assertTrue("Actual reader swipes must reach the middle of the article", viewportParagraph() in 6..11)
            assertEquals(repo.body.text, editorText())

            // Reveal the actual transient toolbar at the top edge; scrolling back to the article
            // header to find its tools button would destroy the position under test.
            compose.onNodeWithTag("zen_reader_viewport").performTouchInput { click(Offset(centerX, 8f)) }
            compose.onNodeWithTag("reader_toolbar_tools").assertIsDisplayed()
            val before = position()
            assertTrue("The tracked character must be inside the visible original text", before.anchorTop > before.viewportTop && before.anchorTop < before.viewportBottom)
            report.put("before", before.json())
            screenshot(output, "02-reader-before", "zen_reader_viewport")
            compose.onNodeWithTag("reader_toolbar_tools").performClick()
            compose.onNodeWithTag("reader_ielts").reachReadingTool().assertIsEnabled().performClick()
            compose.waitUntil(10_000) { exists("ielts_workspace") && !exists("zen_article_text") }
            compose.onNodeWithTag("ielts_workspace_list").performScrollToNode(hasText(repo.article.title))
            compose.onNodeWithText(repo.article.title).assertIsDisplayed()
            compose.onNodeWithText("${repo.paragraphs.size} 段 · 阅读 + 写作").assertExists()
            compose.onNodeWithTag("ielts_generate").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            screenshot(output, "03-reader-workspace", "ielts_workspace")
            compose.onNodeWithTag("ielts_workspace_back").assertIsDisplayed().performClick()
            awaitReader()
            compose.onNodeWithTag("reader_tools_sheet").assertDoesNotExist()
            assertEquals("The same original characters must remain in the actual text field", repo.body.text, editorText())
            val after = position(before.anchorOffset)
            report.put("after", after.json()).put("pixel_tolerance", 8)
            screenshot(output, "04-reader-returned", "zen_reader_viewport")
            assertEquals("The saved viewport paragraph must return", before.viewportParagraph, after.viewportParagraph)
            assertEquals("The current paragraph focus must return", before.focusedParagraph, after.focusedParagraph)
            assertEquals("The same original character must return to its previous screen position", before.anchorTop, after.anchorTop, 8f)
            assertEquals("The original text origin must retain its scroll displacement", before.textTop, after.textTop, 8f)
            assertEquals("Returning from IELTS must not reopen/refetch the article", 1, repo.openCalls.get())
            assertEquals(0, repo.forbidden.get())
            assertEquals(0, repo.reading.analysisCount)
            assertEquals(0, repo.backing.analysisCalls)
            assertEquals(0, repo.backing.captureSaves)
            assertTrue(repo.backing.cards.value.isEmpty())
            report.put("reader_tool_round_trip", true).put("swipes", swipes)
            passed = true
        } finally {
            report.put("passed", passed).put("article_open_calls", repo.openCalls.get())
                .put("forbidden_operations", repo.forbidden.get())
            File(output, "receipt.json").writeText(report.toString(2))
        }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun editorText() = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun viewportParagraph() = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().config[ReaderViewportParagraphKey]
    private fun awaitReader() {
        compose.waitUntil(10_000) { exists("zen_article_text") && !exists("ielts_workspace") }
        settleReader()
    }
    private fun settleReader() {
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.waitUntil(10_000) {
            compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().config[ReaderPositionReadyKey]
        }
    }

    private data class Position(val anchorOffset: Int, val anchorTop: Float, val textTop: Float,
        val viewportTop: Float, val viewportBottom: Float, val viewportParagraph: Int, val focusedParagraph: Int) {
        fun json() = JSONObject().put("anchor_utf16_offset", anchorOffset).put("anchor_screen_top_px", anchorTop)
            .put("text_screen_top_px", textTop).put("viewport_top_px", viewportTop).put("viewport_bottom_px", viewportBottom)
            .put("viewport_paragraph", viewportParagraph).put("focused_paragraph", focusedParagraph)
    }
    private fun position(originalOffset: Int? = null): Position {
        val text = compose.onNodeWithTag("zen_article_text")
        val node = text.fetchSemanticsNode()
        val viewport = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val bounds = viewport.boundsInRoot
        val line = layout.getLineForVerticalPosition(bounds.top + bounds.height * .55f - node.positionInRoot.y)
        val offset = originalOffset ?: (layout.getLineStart(line) until layout.layoutInput.text.length)
            .first { layout.layoutInput.text[it].isLetterOrDigit() }
        assertTrue(offset in 0 until layout.layoutInput.text.length)
        return Position(offset, node.positionInRoot.y + layout.getBoundingBox(offset).top, node.positionInRoot.y,
            bounds.top, bounds.bottom, viewport.config[ReaderViewportParagraphKey], node.config[FocusedParagraphIndex])
    }
    private fun screenshot(folder: File, name: String, tag: String) {
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        val image = compose.onNodeWithTag(tag).assertIsDisplayed().captureToImage().asAndroidBitmap()
        try { File(folder, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
    }

    private class NavigationRepository(val backing: CaptureAnalysisTestRepository = CaptureAnalysisTestRepository()) : AppRepository by backing {
        override val reading = UiTestReadingRepository()
        val forbidden = AtomicInteger()
        val openCalls = AtomicInteger()
        val paragraphs = (1..14).map { number ->
            "Paragraph $number examines a separate decision about the town library. " +
                "The committee records the available evidence before making a recommendation. " +
                "Readers compare the expected benefits with the costs and ask which groups might be affected. " +
                "Each proposal keeps its own reference so that later discussion can return to the same point."
        }
        val article = Article("ielts-navigation", "ielts-navigation-guid", listOf("local-source"),
            "A library considers fourteen practical decisions", "https://example.invalid/ielts-navigation", "A self-authored navigation fixture.",
            sourceName = "Local test source", readingMode = ReadingMode.NATIVE)
        private val original = paragraphs.joinToString("\n\n")
        val body = ArticleBody(article.id, original, fingerprint(original), 1, Long.MAX_VALUE, "Self-authored synthetic test material")
        init {
            backing.articles.value = listOf(article)
            backing.sources.value = listOf(Source("local-source", "Local test source", "https://example.invalid/feed", ReadingMode.NATIVE, aiAllowed = true))
            backing.settings.value = AppSettings(focusTimer = false, reader = ReaderPreferences(automaticStudy = false))
        }
        override suspend fun openArticle(id: String): ArticleBody {
            check(id == article.id); openCalls.incrementAndGet(); return body
        }
        private fun denied(): Nothing { forbidden.incrementAndGet(); error("Generation and practice writes are outside this navigation test") }
        override val ieltsGeneration = object : IeltsGenerationRepository {
            override suspend fun connection() = IeltsGatewayConnection(null, false, null)
            override fun observeHistory() = flowOf(emptyList<IeltsGenerationHistoryItem>())
            override suspend fun get(requestId: String): IeltsGenerationRecord? = null
            override suspend fun pair(endpoint: String, token: String): IeltsGatewayConnection = denied()
            override suspend fun clearPairing(): Unit = denied()
            override suspend fun generate(submit: IeltsGatewaySubmit): IeltsGenerationRecord = denied()
            override suspend fun reconcile(requestId: String): IeltsGenerationRecord = denied()
            override suspend fun cancel(requestId: String): IeltsGenerationRecord = denied()
        }
        override val ieltsPractice = object : IeltsPracticeRepository {
            override fun observeHistory() = flowOf(emptyList<IeltsPracticeHistoryItem>())
            override suspend fun get(attemptId: String): IeltsSavedPractice? = null
            override suspend fun cache(request: IeltsExerciseRequest, exercise: IeltsExercisePackage): String = denied()
            override suspend fun begin(packageHash: String, attemptId: String, now: Long): IeltsSavedPractice = denied()
            override suspend fun saveDraft(id: String, expectedRevision: Long, responses: List<IeltsResponse>, writingText: String, now: Long): IeltsSavedPractice = denied()
            override suspend fun submit(id: String, expectedRevision: Long, now: Long): IeltsSavedPractice = denied()
        }
    }
}
