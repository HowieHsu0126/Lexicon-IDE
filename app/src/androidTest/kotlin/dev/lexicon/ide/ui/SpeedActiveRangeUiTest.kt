package dev.lexicon.ide.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real reading surface and measured line boxes. No future production geometry helper is used. */
@RunWith(AndroidJUnit4::class)
class SpeedActiveRangeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun changingToAMultilineBlockRevealsItsTailEvenWhenItsFirstLineIsAlreadyVisible() {
        val raw = SpeedChunker.plan(ARTICLE.id, BODY.contentHash, TEXT, SpeedGranularity.L3, 0, 1, 1L)
        val plan = raw.copy(planKey = SpeedChunker.planKey(raw))
        assertTrue("The fixture needs several existing L3 chunks", plan.chunks.size > 10)
        var state by mutableStateOf(SpeedUiState(stage = SpeedStage.READING, plan = plan,
            selectedGranularity = SpeedGranularity.L3, chunkIndex = 1, playing = false, sentenceOnly = false))
        val settings = AppSettings(theme = ThemeMode.LIGHT, reader = ReaderPreferences(
            font = ReaderFont.SERIF, sizeSp = 24f, lineHeight = 1.8f, automaticStudy = false))
        compose.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density, 2f)) {
                LexiconTheme(settings) {
                    SpeedReadingScreen(ARTICLE, BODY, settings, state, SpeedUiActions())
                }
            }
        }
        compose.waitForIdle()

        val first = snapshot()
        assertMeasuredViewport(first)
        assertEquals(24f, first.layout.layoutInput.style.fontSize.value, 0.01f)
        assertEquals(43.2f, first.layout.layoutInput.style.lineHeight.value, 0.01f)
        assertEquals(2f, first.layout.layoutInput.density.fontScale, 0.01f)

        // Select an existing block by an independent measured precondition. Never invent a longer
        // range or alter the chunker to make the regression happen. Selection is frozen below.
        val target = plan.chunks.withIndex().filter { it.index in 3 until plan.chunks.lastIndex }
            .map { (index, chunk) -> measuredBlock(first.layout, index, chunk) }
            .filter { block -> canStageFirstLineOnly(block, first) }
            .minByOrNull { abs(it.top - first.layout.size.height * 0.5f) }
        assertNotNull("No existing multiline L3 block fits the actual 200% viewport: ${first.describe()}; " +
            "blockHeights=${plan.chunks.map { measuredBlock(first.layout, 0, it).height }.distinct()}", target)
        val frozen = requireNotNull(target)
        val frozenChunk = plan.chunks[frozen.index]

        // Both indices are nonzero, so changing the active block does not add/remove the initial
        // timing-estimate row. The production screen owns all layout and scrolling behavior.
        compose.runOnIdle { state = state.copy(chunkIndex = frozen.index - 1) }
        compose.waitForIdle()
        val previous = snapshot()
        assertMeasuredViewport(previous)
        assertSameOriginalAndLineBreaks(first, previous)
        val beforeBlock = measuredBlock(previous.layout, frozen.index, frozenChunk)
        assertTrue("Frozen target must still fit before staging: ${previous.describe()}; $beforeBlock",
            canStageFirstLineOnly(beforeBlock, previous))

        val wanted = stagedScroll(beforeBlock, previous.viewportHeight)
        scrollBy(wanted - previous.scroll)
        val staged = snapshot()
        assertMeasuredViewport(staged)
        assertSameOriginalAndLineBreaks(previous, staged)
        val stagedBlock = measuredBlock(staged.layout, frozen.index, frozenChunk)
        assertEquals("Semantic scrolling must establish the independent starting position", wanted, staged.scroll, EPSILON)
        assertTrue("The whole first line is above the viewport bottom before activation", stagedBlock.firstBottom <= staged.bottom - EPSILON)
        assertTrue("The first line must not be clipped at the top before activation", stagedBlock.top >= staged.scroll + EPSILON)
        assertTrue("The final line is deliberately outside the viewport before activation", stagedBlock.bottom > staged.bottom + EPSILON)
        assertTrue("The complete block could fit without changing its line breaks", stagedBlock.height <= staged.viewportHeight - EPSILON)

        compose.runOnIdle { state = state.copy(chunkIndex = frozen.index) }
        compose.waitForIdle()
        val after = snapshot()
        assertMeasuredViewport(after)
        assertSameOriginalAndLineBreaks(staged, after)
        val active = measuredBlock(after.layout, frozen.index, frozenChunk)
        assertTrue("Active block start is clipped: ${after.describe()}; active=$active", active.top >= after.scroll - EPSILON)
        assertTrue("The active block's final line must become visible even when its first line was already visible: " +
            "${after.describe()}; active=$active; stagedScroll=${staged.scroll}", active.bottom <= after.bottom + EPSILON)
        assertEquals(frozenChunk, plan.chunks[frozen.index])
        compose.runOnIdle {
            assertEquals(SpeedStage.READING, state.stage)
            assertFalse(state.playing)
            assertTrue(state.answers.isEmpty())
            assertNull(state.result)
        }
    }

    private data class MeasuredBlock(val index: Int, val start: Int, val end: Int,
        val firstLine: Int, val lastLine: Int, val top: Float, val firstBottom: Float, val bottom: Float) {
        val height: Float get() = bottom - top
    }

    private data class Snapshot(val text: String, val layout: TextLayoutResult, val scroll: Float,
        val maximum: Float, val clip: Rect) {
        // For a fully measured verticalScroll child, max = content height - viewport height.
        // Cross-check this with the actual clipped semantic rectangle before using the result.
        val viewportHeight: Float get() = layout.size.height.toFloat() - maximum
        val bottom: Float get() = scroll + viewportHeight
        fun describe() = "layout=${layout.size}, scroll=$scroll, max=$maximum, viewport=$viewportHeight, clip=$clip"
    }

    private fun snapshot(): Snapshot {
        val field = compose.onNodeWithTag("speed_original_text")
        val results = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        val node = field.fetchSemanticsNode()
        val shown = node.config[SemanticsProperties.Text].joinToString("") { it.text }
        val axis = node.config[SemanticsProperties.VerticalScrollAxisRange]
        assertFalse("The independent coordinate formula assumes normal vertical scroll direction", axis.reverseScrolling)
        return Snapshot(shown, layout, axis.value(), axis.maxValue(), node.boundsInRoot)
    }

    private fun measuredBlock(layout: TextLayoutResult, index: Int, chunk: SenseChunk): MeasuredBlock {
        assertTrue(chunk.start >= 0 && chunk.end > chunk.start && chunk.end <= layout.layoutInput.text.length)
        val first = layout.getLineForOffset(chunk.start)
        val last = layout.getLineForOffset(chunk.end - 1)
        return MeasuredBlock(index, chunk.start, chunk.end, first, last,
            layout.getLineTop(first), layout.getLineBottom(first), layout.getLineBottom(last))
    }

    private fun stagedScroll(block: MeasuredBlock, viewportHeight: Float): Float =
        (((block.firstBottom + block.bottom) / 2f) - viewportHeight).roundToInt().toFloat()

    private fun canStageFirstLineOnly(block: MeasuredBlock, snapshot: Snapshot): Boolean {
        if (block.firstLine == block.lastLine || block.height > snapshot.viewportHeight - EPSILON) return false
        val scroll = stagedScroll(block, snapshot.viewportHeight)
        val bottom = scroll + snapshot.viewportHeight
        return scroll >= EPSILON && scroll <= snapshot.maximum - EPSILON &&
            block.top >= scroll + EPSILON && block.firstBottom <= bottom - EPSILON && block.bottom > bottom + EPSILON
    }

    private fun assertMeasuredViewport(snapshot: Snapshot) {
        assertEquals(TEXT, snapshot.text)
        assertEquals(TEXT, snapshot.layout.layoutInput.text.text)
        assertTrue("A finite, scrollable viewport is required: ${snapshot.describe()}", snapshot.maximum.isFinite() &&
            snapshot.maximum > 0f && snapshot.maximum < Int.MAX_VALUE && snapshot.viewportHeight > 0f)
        assertTrue(snapshot.scroll in 0f..snapshot.maximum)
        assertEquals("Text/scroll semantics must refer to the actual clipped viewport before testing visibility: " +
            snapshot.describe(), snapshot.viewportHeight, snapshot.clip.height, 2f)
    }

    private fun assertSameOriginalAndLineBreaks(before: Snapshot, after: Snapshot) {
        assertEquals(before.text, after.text)
        assertEquals(before.layout.size, after.layout.size)
        assertEquals(before.layout.lineCount, after.layout.lineCount)
        repeat(before.layout.lineCount) { line ->
            assertEquals(before.layout.getLineStart(line), after.layout.getLineStart(line))
            assertEquals(before.layout.getLineEnd(line), after.layout.getLineEnd(line))
            assertEquals(before.layout.getLineTop(line), after.layout.getLineTop(line), EPSILON)
            assertEquals(before.layout.getLineBottom(line), after.layout.getLineBottom(line), EPSILON)
        }
    }

    private fun scrollBy(delta: Float) {
        var accepted = false
        compose.onNodeWithTag("speed_original_text").performSemanticsAction(SemanticsActions.ScrollBy) {
            accepted = it(0f, delta)
        }
        assertTrue("The actual reading surface must accept the requested manual scroll", accepted)
        compose.waitForIdle()
    }

    companion object {
        private const val EPSILON = 1f
        private val TEXT = listOf(
            "The town has made room for a shared reading club near the old school.",
            "Local teams read the news and share notes in the hall.",
            "Many people use the room to compare views and test a new idea.",
            "Each week a small group picks a topic for a long talk.",
            "The first group looks at how the town can save clean water.",
            "The next group asks what the new bus route means for local shops.",
            "Some readers bring maps and others bring notes from their work.",
            "The group checks each claim against the facts in the text.",
            "A short pause gives each reader time to think about the main point.",
            "The room has wide desks and soft lights for people who stay late.",
            "New members can ask for help when a phrase seems hard to follow.",
            "The host asks them to read the whole line before they seek an answer.",
            "Two teams may see the same report in very different ways.",
            "They write down their reasons and look for gaps in their own views.",
            "The final notes keep the key facts and the source of each claim.",
            "At the end the group makes a plan for the next week.",
            "People can take the notes home and read the full report again.",
            "The club grows as more neighbours find time to join the talks."
        ).joinToString(" ")
        private val ARTICLE = Article("speed-active-range-fixture", "speed-active-range-guid", listOf("self-authored"),
            "A shared reading room", "https://example.test/speed-active-range", "", sourceName = "Self-authored fixture")
        private val BODY = ArticleBody(ARTICLE.id, TEXT, fingerprint(TEXT), 1L, Long.MAX_VALUE, "Self-authored test")
    }
}
