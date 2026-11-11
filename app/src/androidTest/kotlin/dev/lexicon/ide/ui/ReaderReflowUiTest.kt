package dev.lexicon.ide.ui

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.AppSettings
import dev.lexicon.domain.Article
import dev.lexicon.domain.ArticleBody
import dev.lexicon.domain.ParagraphFocus
import dev.lexicon.domain.ReaderFont
import dev.lexicon.domain.ReaderPreferences
import dev.lexicon.domain.ReadingComfort
import dev.lexicon.domain.ReadingMode
import dev.lexicon.domain.ThemeMode
import java.security.MessageDigest
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Independent ZenReader reflow regression. Layout values supply the observation; no production
 * reflow helper is called. Functional cases use real swipes; the source-work performance contract
 * uses deterministic semantic ScrollBy. Neither source counters nor the test clock measure FPS.
 * The MainActivity device trial separately covers the actual appearance sheet controls.
 */
@RunWith(AndroidJUnit4::class)
class ReaderReflowUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ordinaryReadingRetainsItsOriginalLineThroughSeparateTypographyChanges() {
        val fixture = Fixture()
        compose.setContent { Reader(fixture) }
        scrollIntoMiddle(fixture)
        val changes: List<(ReaderPreferences) -> ReaderPreferences> = listOf(
            { it.copy(sizeSp = 26f) },
            { it.copy(lineHeight = 2.4f) },
            { it.copy(font = ReaderFont.MONO) },
            { it.copy(letterSpacingSp = 1.25f) },
        )
        changes.forEachIndexed { step, change ->
            val stage = listOf("size_26", "line_height_2.4", "font_mono", "letter_spacing_1.25")[step]
            val before = settled(fixture)
            assertEquals("Ordinary reading must not borrow an explicit navigation pin", "none", before.phase)
            val anchor = freezeReadingLine(before)
            compose.runOnIdle { fixture.settings = fixture.settings.copy(reader = change(fixture.settings.reader)) }
            val after = settled(fixture)
            assertRetained(anchor, after, "ordinary:$stage")
            assertRenderedPreferences(fixture, after)
            assertEquals("none", after.phase)
            assertCanContinue(fixture)
        }
    }

    @Test fun widthChangesAndConsecutiveSettingCommitsKeepTheSameOriginalLine() {
        val fixture = Fixture()
        compose.setContent { Reader(fixture) }
        scrollIntoMiddle(fixture)
        val anchor = freezeReadingLine(settled(fixture))
        compose.runOnIdle { fixture.width = 300.dp }
        assertRetained(anchor, settled(fixture), "width:360_to_300")

        // Separate commits with rendered frames, without a long settle between every update.
        compose.runOnIdle { fixture.settings = fixture.settings.copy(reader = fixture.settings.reader.copy(sizeSp = 22f)) }
        compose.waitForIdle()
        logIntermediateReflow("consecutive:size_22_after_waitForIdle", anchor, fixture)
        compose.runOnIdle { fixture.settings = fixture.settings.copy(reader = fixture.settings.reader.copy(lineHeight = 2.2f)) }
        compose.waitForIdle()
        logIntermediateReflow("consecutive:line_height_2.2_after_waitForIdle", anchor, fixture)
        compose.runOnIdle { fixture.settings = fixture.settings.copy(reader = fixture.settings.reader.copy(font = ReaderFont.MONO)) }
        val after = settled(fixture)
        assertRetained(anchor, after, "consecutive:font_mono_after_all_three_commits")
        assertRenderedPreferences(fixture, after)
        assertCanContinue(fixture)
    }

    @Test fun colorOnlyChangesDoNotMoveTheOriginalTextOrSelection() {
        val fixture = Fixture()
        compose.setContent { Reader(fixture) }
        scrollIntoMiddle(fixture)
        val before = settled(fixture)
        val anchor = freezeReadingLine(before)
        compose.runOnIdle {
            fixture.settings = fixture.settings.copy(theme = ThemeMode.DARK,
                reader = fixture.settings.reader.copy(background = "paper", comfort = ReadingComfort.NIGHT_PAPER))
        }
        val warm = settled(fixture)
        assertRetained(anchor, warm, "color:dark_and_warm_paper")
        assertEquals("Color-only changes must not issue a compensating scroll", before.scroll, warm.scroll, 1f)
        assertEquals(before.origin.y, warm.origin.y, 1f)
        compose.runOnIdle {
            fixture.settings = fixture.settings.copy(reader = fixture.settings.reader.copy(comfort = ReadingComfort.HIGH_CONTRAST))
        }
        val contrast = settled(fixture)
        assertRetained(anchor, contrast, "color:high_contrast")
        assertEquals(before.scroll, contrast.scroll, 1f)
        assertEquals(before.origin.y, contrast.origin.y, 1f)
        assertCanContinue(fixture)
    }

    @Test fun releasedNavigationAndNewReadingIdentitiesCannotBeTakenOverByAnOldAnchor() {
        val fixture = Fixture()
        compose.setContent { Reader(fixture) }
        settled(fixture)
        val target = ParagraphFocus.paragraphs(fixture.body.text)[5]
        compose.runOnIdle {
            fixture.display = ReaderDisplay(checkpointKey = "first-entry", jumpParagraph = 5, jumpVersion = 1,
                jumpBodyHash = fixture.body.contentHash, jumpStart = target.start, jumpEnd = target.endExclusive)
        }
        settled(fixture)
        realSwipe()
        val released = settled(fixture)
        assertEquals("The actual user swipe must release the old explicit navigation", "released", released.phase)
        val releasedAnchor = freezeReadingLine(released)
        compose.runOnIdle { fixture.settings = fixture.settings.copy(reader = fixture.settings.reader.copy(sizeSp = 26f)) }
        val resized = settled(fixture)
        assertRetained(releasedAnchor, resized, "released_navigation:size_26")
        assertEquals("Typography must not reactivate the consumed navigation", "released", resized.phase)
        assertCanContinue(fixture)

        // Change the snapshot and typography together while the previous ordinary anchor exists.
        val oldHash = fixture.body.contentHash
        compose.runOnIdle {
            fixture.body = makeBody("replacement")
            fixture.display = ReaderDisplay(checkpointKey = "replacement-entry")
            fixture.settings = fixture.settings.copy(reader = fixture.settings.reader.copy(lineHeight = 2.1f))
        }
        val replacement = settled(fixture)
        assertFalse(oldHash == replacement.hash)
        assertEquals("A different body snapshot must retain its normal start behavior", 0f, replacement.scroll, 2f)
        assertEquals(TextRange.Zero, replacement.selection)
        assertEquals("none", replacement.phase)

        scrollIntoMiddle(fixture)
        val oldEntryAnchor = freezeReadingLine(settled(fixture))
        compose.runOnIdle {
            fixture.display = ReaderDisplay(checkpointKey = "new-entry", restoreToken = "new-entry-restore", restoreParagraph = 0)
            fixture.settings = fixture.settings.copy(reader = fixture.settings.reader.copy(font = ReaderFont.MONO))
        }
        val newEntry = settled(fixture)
        assertEquals("An explicit new-entry restore has precedence over the previous reading line", 0, newEntry.paragraph)
        assertTrue(wordVisible(newEntry, firstWordRange(newEntry.text).first, firstWordRange(newEntry.text).last + 1))
        assertFalse("The previous middle-of-article anchor must not pull the new entry back",
            wordVisible(newEntry, oldEntryAnchor.start, oldEntryAnchor.end))
        assertEquals("none", newEntry.phase)
        assertCanContinue(fixture)
    }

    @Test fun ordinaryPlacementAfterReflowDoesNotRecomposeTheReaderOrReplaceTextLayout() {
        val fixture = Fixture()
        val probe = ReaderRenderProbe()
        compose.setContent {
            CompositionLocalProvider(LocalReaderRenderProbe provides probe) { Reader(fixture) }
        }
        scrollIntoMiddle(fixture)
        val anchor = freezeReadingLine(settled(fixture))
        compose.runOnIdle { fixture.settings = fixture.settings.copy(reader = fixture.settings.reader.copy(sizeSp = 26f)) }
        val reflowed = settled(fixture)
        assertRetained(anchor, reflowed, "performance_precondition:size_26")
        assertRenderedPreferences(fixture, reflowed)
        assertEquals("none", reflowed.phase)

        // Inspect this fixture's actual geometry before measuring: keep the complete viewport
        // inside one long paragraph, with room for the entire deterministic 72px scroll sequence.
        val paragraphIndex = reflowed.paragraph
        val paragraph = ParagraphFocus.paragraphs(reflowed.text)[paragraphIndex]
        val paragraphTop = reflowed.layout.getLineTop(reflowed.layout.getLineForOffset(paragraph.start))
        val paragraphBottom = reflowed.layout.getLineBottom(reflowed.layout.getLineForOffset(paragraph.endExclusive - 1))
        val totalScroll = 72f
        assertTrue("The measured paragraph must fit the viewport and both scroll margins",
            paragraphBottom - paragraphTop > reflowed.viewport.height + totalScroll * 2f)
        val centeredTop = paragraphTop + (paragraphBottom - paragraphTop - reflowed.viewport.height) / 2f
        semanticScrollBy(reflowed.origin.y + centeredTop - reflowed.viewport.top)
        val before = settled(fixture)
        assertEquals(paragraphIndex, before.paragraph)
        val rootParagraphTop = before.origin.y + paragraphTop
        val rootParagraphBottom = before.origin.y + paragraphBottom
        assertTrue(rootParagraphTop < before.viewport.top - totalScroll)
        assertTrue(rootParagraphBottom > before.viewport.bottom + totalScroll)
        assertTrue(probe.count(ReaderRenderEvent.TEXT_COMPOSITION) > 0)
        assertTrue(probe.count(ReaderRenderEvent.TEXT_LAYOUT_UPDATE) > 0)
        assertTrue(probe.count(ReaderRenderEvent.READER_COMPOSITION) > 0)
        val countsBefore = probe.snapshot()

        repeat(12) {
            semanticScrollBy(6f)
            val step = snapshot(fixture)
            assertEquals("The counter interval must not include a paragraph-focus transition", paragraphIndex, step.paragraph)
            assertEquals("none", step.phase)
        }
        val after = settled(fixture)
        val countsAfter = probe.snapshot()
        assertEquals("The semantic scroll must move real content by the requested pixels", totalScroll, after.scroll - before.scroll, 1f)
        assertEquals(totalScroll, before.origin.y - after.origin.y, 1f)
        assertTrue("Global placement callbacks must occur during the measured scroll",
            countsAfter.getValue("VIEWPORT_CALLBACK") > countsBefore.getValue("VIEWPORT_CALLBACK"))
        listOf("TEXT_COMPOSITION", "TEXT_LAYOUT_UPDATE", "READER_COMPOSITION").forEach { event ->
            assertEquals("Ordinary same-paragraph scrolling after reflow must not increase $event",
                countsBefore.getValue(event), countsAfter.getValue(event))
        }
        assertEquals(before.text, after.text)
        assertEquals(fixture.body.contentHash, after.hash)
        assertEquals(before.selection, after.selection)
        assertEquals(paragraphIndex, after.paragraph)
        Log.i("LexiconRenderProbe", "zen-after-reflow same-paragraph before=$countsBefore after=$countsAfter scrollPx=${after.scroll - before.scroll}")
    }

    private class Fixture {
        var body by mutableStateOf(makeBody("initial"))
        val article = Article("reader-reflow", "reader-reflow-guid", listOf("self-authored"), "A place to read",
            "https://example.invalid/reader-reflow", "", readingMode = ReadingMode.NATIVE,
            sourceName = "Self-authored reading fixture", author = "Lexicon UI test")
        var settings by mutableStateOf(AppSettings(theme = ThemeMode.LIGHT, focusTimer = false,
            reader = ReaderPreferences(font = ReaderFont.SERIF, sizeSp = 18f, lineHeight = 1.8f, automaticStudy = false)))
        var display by mutableStateOf(ReaderDisplay(checkpointKey = "first-entry"))
        var width by mutableStateOf(360.dp)
    }

    @Composable private fun Reader(fixture: Fixture) {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
            LexiconTheme(fixture.settings) {
                Box(Modifier.width(fixture.width).height(620.dp)) {
                    ZenReader(fixture.article, fixture.body, false, fixture.settings, false, false, {},
                        { error("A scrolling/reflow test must not create a capture") }, {}, {}, {}, { _, _ -> },
                        display = fixture.display)
                }
            }
        }
    }

    private data class Snapshot(val text: String, val hash: String, val layout: TextLayoutResult, val origin: Offset,
        val viewport: Rect, val clip: Rect, val selection: TextRange, val scroll: Float, val maximum: Float,
        val phase: String, val paragraph: Int) {
        val stableKey get() = listOf(hash, origin, viewport, selection, scroll, maximum, phase, paragraph,
            layout.size, layout.lineCount, layout.layoutInput.style.fontSize, layout.layoutInput.style.lineHeight,
            layout.layoutInput.style.letterSpacing, layout.layoutInput.style.fontFamily)
    }
    private data class Anchor(val hash: String, val start: Int, val end: Int, val lineTop: Float,
        val viewportHeight: Float, val selection: TextRange, val diagnostic: String)

    private fun snapshot(fixture: Fixture): Snapshot {
        val viewportNode = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode()
        check(viewportNode.config[ReaderPositionReadyKey])
        val field = compose.onNodeWithTag("zen_article_text")
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val node = field.fetchSemanticsNode()
        val text = node.config[SemanticsProperties.EditableText].text
        check(text == fixture.body.text && layout.layoutInput.text.text == text)
        val axis = compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("zen_article_text")))
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return Snapshot(text, sha256(text), layout, node.positionInRoot, viewportNode.boundsInRoot, node.boundsInRoot,
            node.config[SemanticsProperties.TextSelectionRange], axis.value(), axis.maxValue(),
            viewportNode.config[ReaderNavigationFocusPhaseKey], viewportNode.config[ReaderViewportParagraphKey])
    }

    private fun settled(fixture: Fixture): Snapshot {
        var previous: List<Any?>? = null
        var since = SystemClock.elapsedRealtime()
        var answer: Snapshot? = null
        compose.waitUntil(15_000) {
            val current = runCatching { snapshot(fixture) }.getOrNull()
            val now = SystemClock.elapsedRealtime()
            if (current == null) { previous = null; since = now; false }
            else {
                if (current.stableKey != previous) since = now
                previous = current.stableKey
                if (now - since >= 1_200) { answer = current; true } else false
            }
        }
        return requireNotNull(answer)
    }

    private fun scrollIntoMiddle(fixture: Fixture) {
        var observed = settled(fixture)
        repeat(32) {
            if (observed.maximum > 0f && observed.scroll >= observed.maximum * .28f) {
                assertTrue("The fixture must leave room on both sides of the reading point", observed.scroll < observed.maximum * .75f)
                settled(fixture)
                return
            }
            realSwipe()
            compose.waitUntil(5_000) { runCatching { snapshot(fixture) }.isSuccess }
            observed = snapshot(fixture)
        }
        error("Real swipes did not reach the middle of the self-authored text")
    }

    private fun realSwipe() {
        compose.onNodeWithTag("zen_reader_viewport").performTouchInput {
            swipe(Offset(centerX, height * .72f), Offset(centerX, height * .28f), 650)
        }
        compose.waitForIdle()
    }

    /** Performance-only input: observes source work without gesture velocity or paragraph changes. */
    private fun semanticScrollBy(delta: Float) {
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("zen_article_text")))
            .performSemanticsAction(SemanticsActions.ScrollBy) { action -> assertTrue(action(0f, delta)) }
        compose.waitForIdle()
    }

    private fun freezeReadingLine(snapshot: Snapshot): Anchor {
        val layout = snapshot.layout
        val line = layout.getLineForVerticalPosition(snapshot.viewport.top + snapshot.viewport.height * .42f - snapshot.origin.y)
        val words = WORD.findAll(snapshot.text).toList()
        val lines = (0 until layout.lineCount).sortedWith(compareBy<Int> { abs(it - line) }.thenBy { it })
        for (candidate in lines) {
            val start = layout.getLineStart(candidate)
            val end = layout.getLineEnd(candidate, visibleEnd = true)
            val word = words.firstOrNull { it.range.first >= start && it.range.first < end &&
                wordVisible(snapshot, it.range.first, it.range.last + 1) } ?: continue
            val first = word.range.first
            val top = layout.getLineTop(layout.getLineForOffset(first)) + snapshot.origin.y - snapshot.viewport.top
            return Anchor(snapshot.hash, first, word.range.last + 1, top, snapshot.viewport.height, snapshot.selection,
                "wordRange=[$first,${word.range.last + 1}); ${describeSnapshot(snapshot, first)}")
        }
        error("The reading line has no visible English word")
    }

    private fun assertRetained(anchor: Anchor, after: Snapshot, stage: String) {
        val diagnostic = logReflowDiagnostic(stage, anchor, after)
        assertEquals("Reflow must keep the same original body snapshot; $diagnostic", anchor.hash, after.hash)
        assertEquals("Reflow must not manufacture a text selection; $diagnostic", anchor.selection, after.selection)
        assertEquals("These cases change width or typography, not viewport height; $diagnostic", anchor.viewportHeight, after.viewport.height, 0f)
        assertTrue("The same frozen UTF-16 word must remain fully visible; $diagnostic", wordVisible(after, anchor.start, anchor.end))
        val line = after.layout.getLineForOffset(anchor.start)
        val top = after.layout.getLineTop(line) + after.origin.y - after.viewport.top
        assertEquals("The original reading line must remain in place while scroll pixels adapt; $diagnostic", anchor.lineTop, top, 2f)
    }

    private fun logReflowDiagnostic(stage: String, anchor: Anchor, after: Snapshot): String {
        val diagnostic = "stage=$stage; before={${anchor.diagnostic}}; after={${describeSnapshot(after, anchor.start)}}; " +
            "wordVisible=${wordVisible(after, anchor.start, anchor.end)}"
        Log.i("LexiconReaderReflow", diagnostic)
        return diagnostic
    }

    private fun logIntermediateReflow(stage: String, anchor: Anchor, fixture: Fixture) {
        runCatching { snapshot(fixture) }
            .onSuccess { logReflowDiagnostic(stage, anchor, it) }
            .onFailure { Log.i("LexiconReaderReflow", "stage=$stage; intermediateSnapshotUnavailable=${it.javaClass.simpleName}; before={${anchor.diagnostic}}") }
    }

    private fun describeSnapshot(snapshot: Snapshot, offset: Int): String {
        val layout = snapshot.layout
        val style = layout.layoutInput.style
        val line = if (offset in snapshot.text.indices) layout.getLineForOffset(offset) else null
        val lineTop = line?.let { layout.getLineTop(it) + snapshot.origin.y - snapshot.viewport.top }
        val lineStart = line?.let(layout::getLineStart)
        val lineEnd = line?.let { layout.getLineEnd(it, visibleEnd = false) }
        return "body=${snapshot.hash}; offset=$offset; line=$line; lineRange=[$lineStart,$lineEnd); lineTop=$lineTop; " +
            "viewport=${snapshot.viewport}; viewportHeight=${snapshot.viewport.height}; origin=${snapshot.origin}; " +
            "scroll=${snapshot.scroll}; maxScroll=${snapshot.maximum}; paragraph=${snapshot.paragraph}; phase=${snapshot.phase}; " +
            "selection=${snapshot.selection}; layoutSize=${layout.size}; lineCount=${layout.lineCount}; " +
            "fontSize=${style.fontSize}; lineHeight=${style.lineHeight}; letterSpacing=${style.letterSpacing}; font=${style.fontFamily}"
    }

    private fun assertRenderedPreferences(fixture: Fixture, snapshot: Snapshot) {
        val prefs = fixture.settings.reader
        val style = snapshot.layout.layoutInput.style
        assertEquals(prefs.sizeSp, style.fontSize.value, .02f)
        assertEquals(prefs.sizeSp * prefs.lineHeight, style.lineHeight.value, .02f)
        assertEquals(prefs.letterSpacingSp, style.letterSpacing.value, .02f)
        assertEquals(if (prefs.font == ReaderFont.MONO) EditorMono else ReadingSerif, style.fontFamily)
    }

    private fun assertCanContinue(fixture: Fixture) {
        val before = settled(fixture)
        realSwipe()
        val after = settled(fixture)
        assertTrue("A real swipe must continue reading without being pulled back", after.scroll - before.scroll > 8f)
        assertTrue(before.origin.y - after.origin.y > 8f)
        assertEquals(before.hash, after.hash)
        assertEquals(before.selection, after.selection)
        assertTrue("A consumed explicit navigation must remain released", after.phase == "none" || after.phase == "released")
    }

    private fun wordVisible(snapshot: Snapshot, start: Int, end: Int): Boolean {
        if (start < 0 || end <= start || end > snapshot.text.length) return false
        val boxes = (start until end).map { offset -> snapshot.layout.getBoundingBox(offset).let {
            Rect(it.left + snapshot.origin.x, it.top + snapshot.origin.y, it.right + snapshot.origin.x, it.bottom + snapshot.origin.y)
        } }
        return boxes.any { it.width > 0f } && boxes.all { it.width >= 0f && it.height > 0f && inside(it, snapshot.viewport) && inside(it, snapshot.clip) }
    }

    private fun inside(inner: Rect, outer: Rect) = outer.width > 0f && outer.height > 0f &&
        inner.left >= outer.left - 1f && inner.top >= outer.top - 1f && inner.right <= outer.right + 1f && inner.bottom <= outer.bottom + 1f
    private fun firstWordRange(text: String) = requireNotNull(WORD.find(text)).range

    private companion object {
        val WORD = Regex("[A-Za-z0-9]+(?:['’-][A-Za-z0-9]+)*")
        fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        fun makeBody(edition: String): ArticleBody {
            val text = (1..16).joinToString("\n\n") { number ->
                "Notebook $number in the $edition account records a new day at an imagined reading room. " +
                    "Readers carry books to a quiet table and leave enough room for another person. " +
                    "A clear label helps a visitor choose a shelf without asking for permission. " +
                    "The host notes which questions return and changes one small part of the room at a time. " +
                    "Each change is tested against the ordinary act of returning to a page. " +
                    "The group values a calm space where the next useful idea can appear without haste. " +
                    "This account describes a fictional place and supplies original text for an interface test."
            }
            return ArticleBody("reader-reflow", text, sha256(text), 1, Long.MAX_VALUE, "Self-authored fictional fixture; CC0")
        }
    }
}
