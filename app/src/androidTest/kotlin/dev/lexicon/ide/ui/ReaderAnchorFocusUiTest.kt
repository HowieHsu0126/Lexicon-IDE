package dev.lexicon.ide.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.AppSettings
import dev.lexicon.domain.Article
import dev.lexicon.domain.ArticleBody
import dev.lexicon.domain.ParagraphFocus
import dev.lexicon.domain.ReaderFont
import dev.lexicon.domain.ReaderPreferences
import dev.lexicon.domain.ReadingMode
import dev.lexicon.domain.StudyMode
import dev.lexicon.domain.ThemeMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Exercises an explicit ZenReader navigation request, not an arbitrary paint-only active range. */
@RunWith(AndroidJUnit4::class)
class ReaderAnchorFocusUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun anExplicitShortOriginalRangeKeepsItsParagraphFocusedAfterJumpSettlesAtLargeType() {
        val paragraphs = buildList {
            repeat(5) { add("Before $it. " + "The reader follows the evidence and checks the scope of each claim. ".repeat(5)) }
            add("Anchor.")
            repeat(7) { add("After $it. " + "This neighbouring paragraph provides enough continuous prose for ordinary viewport focus. ".repeat(5)) }
        }
        val text = paragraphs.joinToString("\n\n")
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        val body = ArticleBody("focus-anchor", text, hash, 1, Long.MAX_VALUE, "Self-authored synthetic fixture")
        val source = Article(body.articleId, "focus-anchor-guid", listOf("synthetic"), "A focused reading fixture",
            "https://example.invalid/focus-anchor", "", readingMode = ReadingMode.NATIVE, sourceName = "Synthetic source")
        val targetIndex = 5
        val target = ParagraphFocus.paragraphs(text)[targetIndex]
        val settings = AppSettings(theme = ThemeMode.DARK, focusTimer = false,
            reader = ReaderPreferences(font = ReaderFont.MONO, sizeSp = 18f, lineHeight = 1.8f, automaticStudy = false))
        var display by mutableStateOf(ReaderDisplay())
        val observed = mutableListOf<Int>()
        val run = (InstrumentationRegistry.getArguments().getString("focusAnchorRun") ?: "local-${UUID.randomUUID()}").also {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")))
        }
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "validation-1.25.0/focus-anchor/$run")
        check(!output.exists() && output.mkdirs()) { "Use a new focusAnchorRun to preserve earlier evidence" }
        val report = JSONObject().put("schema", "lexicon.focus.anchor.component.v1")
            .put("scope", "Synthetic ZenReader component; LocalDensity font-scale override, no repository or model calls")
            .put("fontScaleOverride", 2.0).put("textHash", hash).put("requestedParagraph", targetIndex)
            .put("rangeStart", target.start).put("rangeEnd", target.endExclusive)
        var passed = false
        try {
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    LexiconTheme(settings) {
                        ZenReader(source, body, false, settings, false, false, {},
                            { error("An explicit jump must not capture text") }, {}, {}, {}, { _, _ -> },
                            display = display, onFocusedParagraph = { observed += it })
                    }
                }
            }
            compose.mainClock.advanceTimeBy(700)
            compose.waitForIdle()
            compose.runOnIdle {
                display = ReaderDisplay(jumpParagraph = targetIndex, jumpVersion = 1, jumpBodyHash = hash,
                    jumpStart = target.start, jumpEnd = target.endExclusive)
            }
            // Wait beyond scroll/arrival/paint durations and the viewport candidate settle delay.
            // A visible range alone is insufficient: the resulting focus must still honour the jump.
            compose.mainClock.advanceTimeBy(2_000)
            compose.waitForIdle()
            compose.waitUntil(5_000) {
                compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().config[ReaderPositionReadyKey]
            }
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("zen_article_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val node = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode()
            val viewportNode = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode()
            val viewport = viewportNode.boundsInRoot
            val top = node.positionInRoot.y + layout.getLineTop(layout.getLineForOffset(target.start))
            val bottom = node.positionInRoot.y + layout.getLineBottom(layout.getLineForOffset(target.endExclusive - 1))
            report.put("viewport", JSONArray(listOf(viewport.left, viewport.top, viewport.right, viewport.bottom)))
                .put("textOriginY", node.positionInRoot.y).put("anchorTop", top).put("anchorBottom", bottom)
                .put("anchorTopFraction", (top - viewport.top) / viewport.height)
                .put("focusedAfterSettle", node.config[FocusedParagraphIndex])
                .put("viewportParagraphAfterSettle", viewportNode.config[ReaderViewportParagraphKey])
                .put("focusEvents", JSONArray(observed.toList()))
                .put("neighbourBounds", JSONArray(ParagraphFocus.paragraphs(text).mapIndexedNotNull { index, paragraph ->
                    if (index !in targetIndex - 1..targetIndex + 2) null else JSONObject().put("index", index)
                        .put("top", node.positionInRoot.y + layout.getLineTop(layout.getLineForOffset(paragraph.start)))
                        .put("bottom", node.positionInRoot.y + layout.getLineBottom(layout.getLineForOffset(paragraph.endExclusive - 1)))
                }))
            anchorScreenshot(output, "settled.png")
            assertEquals("The original field must keep its exact content", text, layout.layoutInput.text.text)
            assertTrue("Programmatic navigation must not fake a selection to freeze focus",
                node.config[SemanticsProperties.TextSelectionRange].collapsed)
            assertTrue("The requested range must be fully visible before its focus is assessed",
                top >= viewport.top && bottom <= viewport.bottom)
            assertTrue("The explicit range must have reached the reader's upper reading area",
                (top - viewport.top) / viewport.height in 0.15f..0.30f)
            assertEquals("A settled explicit original-range jump must focus its target paragraph",
                targetIndex, node.config[FocusedParagraphIndex])
            assertEquals("The reported viewport must honour the same explicit target",
                targetIndex, viewportNode.config[ReaderViewportParagraphKey])
            assertEquals("Reading tools must receive the paragraph that is visually focused", targetIndex, observed.last())
            compose.mainClock.advanceTimeBy(800)
            compose.waitForIdle()
            val stable = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode()
            report.put("focusedAfterIdle", stable.config[FocusedParagraphIndex])
            assertEquals("An idle viewport must not take focus away from the requested paragraph",
                targetIndex, stable.config[FocusedParagraphIndex])
            val beforeRestore = stable.positionInRoot.y
            restoration.emulateSavedInstanceStateRestore()
            settleReader()
            val restored = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode()
            report.put("focusedAfterRecreation", restored.config[FocusedParagraphIndex])
                .put("textOriginAfterRecreation", restored.positionInRoot.y)
            assertEquals("The same pinned request must survive saved-instance recreation", targetIndex, focused())
            assertEquals(targetIndex, viewportParagraph())
            assertEquals(targetIndex, observed.last())
            assertEquals("Recreation must preserve the existing anchored position", beforeRestore, restored.positionInRoot.y, 8f)
            passed = true
        } catch (error: Throwable) {
            report.put("failureClass", error.javaClass.simpleName)
                .put("failureLocations", JSONArray(error.stackTrace.take(5).map { "${it.fileName}:${it.lineNumber}" }))
            runCatching { anchorScreenshot(output, "failure.png") }
            throw error
        } finally {
            report.put("status", if (passed) "passed" else "failed")
            File(output, "focus-report.json").writeText(report.toString(2))
        }
    }

    @Test fun userScrollTakesOverAndRecreationDoesNotReplayUntilANewJumpVersion() {
        val fixture = AnchorFixture()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FixtureReader(fixture) }
        settleReader()
        jump(fixture, 5)
        settleReader()
        assertFocusAgreement(fixture, 5)
        assertParagraphVisible(fixture, 5)

        compose.onNodeWithTag("zen_reader_viewport").performTouchInput { swipeUp(durationMillis = 450) }
        settleReader()
        val userParagraph = focused()
        assertTrue("A real upward drag must move reading beyond the requested paragraph", userParagraph > 5)
        assertFocusAgreement(fixture, userParagraph)
        val userPosition = textOrigin()
        restoration.emulateSavedInstanceStateRestore()
        settleReader()
        assertEquals("A consumed jump must not restart when the screen is recreated", userPosition, textOrigin(), 8f)
        assertFocusAgreement(fixture, userParagraph)

        jump(fixture, 5)
        settleReader()
        assertFocusAgreement(fixture, 5)
        assertParagraphVisible(fixture, 5)
        assertSourceAndSelection(fixture, TextRange.Zero)
    }

    @Test fun shortFirstAndLastParagraphsCanOwnFocusAtTheScrollLimits() {
        val fixture = AnchorFixture(listOf("Opening.") +
            (0 until 7).map { "Middle $it. " + "The reader compares the scope and the supporting evidence. ".repeat(5) } +
            listOf("Conclusion."))
        compose.setContent { FixtureReader(fixture) }
        settleReader()
        for (target in listOf(fixture.paragraphs.lastIndex, 0)) {
            jump(fixture, target, withRange = false)
            settleReader()
            assertParagraphVisible(fixture, target)
            assertFocusAgreement(fixture, target)
            val settled = textOrigin()
            compose.mainClock.advanceTimeBy(800)
            compose.waitForIdle()
            assertEquals("A clamped jump must not keep oscillating", settled, textOrigin(), 1f)
            assertFocusAgreement(fixture, target)
        }
        assertSourceAndSelection(fixture, TextRange.Zero)
    }

    @Test fun typographyReflowAndASmallerViewportRetainTheExplicitReadingTarget() {
        val fixture = AnchorFixture()
        compose.setContent { FixtureReader(fixture) }
        settleReader()
        jump(fixture, 5)
        settleReader()
        assertFocusAgreement(fixture, 5)
        compose.runOnIdle {
            fixture.settings = fixture.settings.copy(reader = fixture.settings.reader.copy(
                font = ReaderFont.SERIF, sizeSp = 24f, lineHeight = 2f, letterSpacingSp = .75f))
            fixture.height = 420.dp
        }
        settleReader()
        assertParagraphVisible(fixture, 5)
        assertFocusAgreement(fixture, 5)
        assertSourceAndSelection(fixture, TextRange.Zero)
    }

    @Test fun quietAndSkimNavigationKeepUniformParagraphPaintAndExactReverseUtf16Selection() {
        val fixture = AnchorFixture().apply { display = ReaderDisplay(quiet = true) }
        compose.setContent { FixtureReader(fixture) }
        settleReader()
        jump(fixture, 5)
        settleReader()
        assertParagraphVisible(fixture, 5)
        assertGlyphsUseConfiguredInk(fixture, listOf(5, 6))
        val start = fixture.paragraphs[4].endExclusive - 18
        val end = fixture.paragraphs[5].endExclusive
        val selection = TextRange(end, start)
        compose.onNodeWithTag("zen_article_text").performSemanticsAction(SemanticsActions.SetSelection) {
            it(selection.start, selection.end, false)
        }
        compose.waitForIdle()
        assertSourceAndSelection(fixture, selection)
        compose.runOnIdle { fixture.display = fixture.display.copy(mode = StudyMode.SKIM, quiet = false, jumpVersion = fixture.display.jumpVersion + 1) }
        // Selection intentionally freezes ordinary viewport focus, so readiness is not required.
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onNodeWithText("略读 · 工具").assertExists()
        assertGlyphsUseConfiguredInk(fixture, listOf(5, 6))
        assertSourceAndSelection(fixture, selection)
        val actual = editorLayout().layoutInput.text.text.substring(selection.min, selection.max)
        assertEquals(fixture.body.text.substring(start, end), actual)
    }

    @Test fun aMismatchedBodyHashCannotMovePinOrBlockTheExistingViewport() {
        val fixture = AnchorFixture()
        compose.setContent { FixtureReader(fixture) }
        settleReader()
        val originalParagraph = focused()
        val originalPosition = textOrigin()
        jump(fixture, 5, hash = "a-different-body-snapshot")
        settleReader()
        assertEquals("An unverified range must not move the text", originalPosition, textOrigin(), 1f)
        assertFocusAgreement(fixture, originalParagraph)
        assertSourceAndSelection(fixture, TextRange.Zero)
    }

    private class AnchorFixture(paragraphTexts: List<String> = buildList {
        repeat(5) { add("🌍 Before $it. " + "The reader follows the evidence and checks the scope of each claim. ".repeat(5)) }
        add("Anchor.")
        repeat(7) { add("After $it. " + "This neighbouring paragraph provides enough continuous prose for ordinary viewport focus. ".repeat(5)) }
    }) {
        private val text = paragraphTexts.joinToString("\n\n")
        private val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        val body = ArticleBody("anchor-boundaries", text, hash, 1, Long.MAX_VALUE, "Self-authored synthetic fixture")
        val article = Article(body.articleId, "anchor-boundaries-guid", listOf("synthetic"), "Reading anchor boundaries",
            "https://example.invalid/anchor-boundaries", "", readingMode = ReadingMode.NATIVE, sourceName = "Synthetic source")
        val paragraphs = ParagraphFocus.paragraphs(text)
        var settings by mutableStateOf(AppSettings(theme = ThemeMode.DARK, focusTimer = false,
            reader = ReaderPreferences(font = ReaderFont.MONO, sizeSp = 18f, lineHeight = 1.8f, automaticStudy = false)))
        var display by mutableStateOf(ReaderDisplay())
        var height by mutableStateOf<Dp?>(null)
        val focusEvents = mutableListOf<Int>()
        var inkArgb = 0
    }

    @Composable private fun FixtureReader(fixture: AnchorFixture) {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
            LexiconTheme(fixture.settings) {
                val ink = readerTextStyle(fixture.settings.reader).color.toArgb()
                SideEffect { fixture.inkArgb = ink }
                val sizing = fixture.height?.let { Modifier.fillMaxWidth().height(it) } ?: Modifier.fillMaxSize()
                Box(sizing) {
                    ZenReader(fixture.article, fixture.body, false, fixture.settings, false, false, {},
                        { error("Navigation tests must not create captures") }, {}, {}, {}, { _, _ -> },
                        display = fixture.display, onFocusedParagraph = { fixture.focusEvents += it })
                }
            }
        }
    }

    private fun jump(fixture: AnchorFixture, index: Int, hash: String = fixture.body.contentHash, withRange: Boolean = true) {
        val paragraph = fixture.paragraphs[index]
        compose.runOnIdle {
            fixture.display = fixture.display.copy(jumpParagraph = index, jumpVersion = fixture.display.jumpVersion + 1,
                jumpBodyHash = hash, jumpStart = paragraph.start.takeIf { withRange }, jumpEnd = paragraph.endExclusive.takeIf { withRange })
        }
    }

    private fun settleReader() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        try {
            compose.waitUntil(5_000) {
                compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().config[ReaderPositionReadyKey]
            }
        } catch (error: Throwable) {
            val viewport = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode()
            val text = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode()
            throw AssertionError("Reader did not settle: ready=${viewport.config[ReaderPositionReadyKey]}, " +
                "phase=${viewport.config[ReaderNavigationFocusPhaseKey]}, focus=${text.config[FocusedParagraphIndex]}, " +
                "viewportParagraph=${viewport.config[ReaderViewportParagraphKey]}, origin=${text.positionInRoot}, " +
                "selection=${text.config[SemanticsProperties.TextSelectionRange]}, viewport=${viewport.boundsInRoot}", error)
        }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }

    private fun focused() = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().config[FocusedParagraphIndex]
    private fun viewportParagraph() = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().config[ReaderViewportParagraphKey]
    private fun textOrigin() = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().positionInRoot.y
    private fun editorLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("zen_article_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }
    private fun assertFocusAgreement(fixture: AnchorFixture, expected: Int) {
        assertEquals("Visual paragraph focus", expected, focused())
        assertEquals("Viewport paragraph used for reading state", expected, viewportParagraph())
        assertEquals("Paragraph reported to reading tools", expected, fixture.focusEvents.last())
    }
    private fun assertSourceAndSelection(fixture: AnchorFixture, selection: TextRange) {
        assertEquals(fixture.body.text, editorLayout().layoutInput.text.text)
        assertEquals(selection, compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }
    private fun assertParagraphVisible(fixture: AnchorFixture, index: Int) {
        val layout = editorLayout()
        val paragraph = fixture.paragraphs[index]
        val viewport = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().boundsInRoot
        val top = textOrigin() + layout.getLineTop(layout.getLineForOffset(paragraph.start))
        val bottom = textOrigin() + layout.getLineBottom(layout.getLineForOffset(paragraph.endExclusive - 1))
        assertTrue("The requested short paragraph must remain fully visible: $index, $top..$bottom in $viewport",
            top >= viewport.top - 1f && bottom <= viewport.bottom + 1f)
    }

    private fun assertGlyphsUseConfiguredInk(fixture: AnchorFixture, indices: List<Int>) {
        val layout = editorLayout()
        val textNode = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode()
        val viewportNode = compose.onNodeWithTag("zen_reader_viewport")
        val viewport = viewportNode.fetchSemanticsNode().boundsInRoot
        val bitmap = viewportNode.captureToImage().asAndroidBitmap()
        val scaleX = bitmap.width / viewport.width
        val scaleY = bitmap.height / viewport.height
        // Check the rendered core of matching initial 'A' glyphs, not a gradient implementation
        // counter or a single hard-coded pixel. Dimming would move all core pixels away from ink.
        for (index in indices) {
            val start = fixture.paragraphs[index].start
            assertEquals('A', fixture.body.text[start])
            val glyph = layout.getBoundingBox(start)
            val left = (textNode.positionInRoot.x + glyph.left - viewport.left) * scaleX
            val right = (textNode.positionInRoot.x + glyph.right - viewport.left) * scaleX
            val top = (textNode.positionInRoot.y + glyph.top - viewport.top) * scaleY
            val bottom = (textNode.positionInRoot.y + glyph.bottom - viewport.top) * scaleY
            assertTrue("Comparison glyph $index must be visible", left >= 0 && top >= 0 && right <= bitmap.width && bottom <= bitmap.height)
            var closest = 255
            for (y in floor(top).toInt() until ceil(bottom).toInt().coerceAtMost(bitmap.height)) {
                for (x in floor(left).toInt() until ceil(right).toInt().coerceAtMost(bitmap.width)) {
                    val pixel = bitmap.getPixel(x, y)
                    val distance = listOf(16, 8, 0).maxOf { shift ->
                        abs(((pixel ushr shift) and 255) - ((fixture.inkArgb ushr shift) and 255))
                    }
                    closest = minOf(closest, distance)
                }
            }
            assertTrue("Paragraph $index must retain its configured full text ink; closest RGB distance=$closest", closest <= 16)
        }
    }

    private fun anchorScreenshot(output: File, name: String) {
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(output, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

}
