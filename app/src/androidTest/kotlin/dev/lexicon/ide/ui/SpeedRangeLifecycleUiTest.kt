package dev.lexicon.ide.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
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

/** Measured UI lifecycle contracts; no production range/target helper or ViewModel timer. */
@RunWith(AndroidJUnit4::class)
class SpeedRangeLifecycleUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun anOversizedBlockRevealsItsBeginningOnceThenPreservesManualScrolling() {
        // The compact controls enlarge the viewport; use supported extreme typography so this
        // remains an actual oversized-range case, with every geometry assertion unchanged.
        val fixture = mount(ReaderPreferences(font = ReaderFont.SERIF, sizeSp = 32f,
            lineHeight = 3f, automaticStudy = false))
        val initial = snapshot(fixture)
        val target = choose(fixture, initial, "an oversized interior block") { block, view ->
            block.height > view.height + EPSILON && block.firstBottom - block.top < view.height &&
                block.top > view.height && block.top + view.height < view.maximum
        }
        activate(fixture, target.index)
        val positioned = snapshot(fixture)
        val active = block(positioned, target.index, fixture.plan.chunks[target.index])
        assertTrue(active.height > positioned.height + EPSILON)
        assertEquals("The oversized block must begin at the viewport top", active.top, positioned.scroll, EPSILON)
        assertTrue("At least the complete first line must be visible", active.firstBottom <= positioned.bottom + EPSILON)

        scrollBy(positioned.height * .45f)
        val manuallyMoved = snapshot(fixture)
        assertTrue("The reader must actually move away from the block head", manuallyMoved.scroll > positioned.scroll + positioned.height * .25f)
        assertSameLines(positioned, manuallyMoved)
        settleBeyondAnimations()
        val settled = snapshot(fixture)
        assertEquals("The active oversized block must not repeatedly pull the reader back", manuallyMoved.scroll, settled.scroll, EPSILON)
        assertSameLines(manuallyMoved, settled)
        assertPassive(fixture, target.index)
    }

    @Test fun aUserDragCancelsAnInFlightRevealAndTheSurfaceDoesNotPullBackAfterward() {
        val fixture = mount()
        val initial = snapshot(fixture)
        val target = choose(fixture, initial, "a fitting multiline block with a stageable hidden tail") { block, view ->
            stageable(block, view)
        }
        activate(fixture, target.index - 1)
        val previous = snapshot(fixture)
        val inactive = block(previous, target.index, fixture.plan.chunks[target.index])
        assertTrue(stageable(inactive, previous))
        scrollBy(stagedScroll(inactive, previous.height) - previous.scroll)
        val staged = snapshot(fixture)
        val before = block(staged, target.index, fixture.plan.chunks[target.index])
        assertFirstVisibleTailHidden(before, staged)

        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { fixture.state = fixture.state.copy(chunkIndex = target.index) }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(32)
            val moving = snapshot(fixture)
            assertTrue("Automatic reveal must have started before the user intervenes", moving.scroll > staged.scroll + EPSILON)
            val during = block(moving, target.index, fixture.plan.chunks[target.index])
            assertTrue("The test must intervene before the hidden tail has reached its final visible position", during.bottom > moving.bottom + EPSILON)

            // Inject a downward drag into the actual clipped reading viewport. This moves back
            // through the text and gives user input ownership of the running scroll mutation.
            val clip = moving.clip
            compose.onRoot().performTouchInput {
                val from = Offset(clip.center.x, clip.top + clip.height * .25f)
                val to = Offset(clip.center.x, clip.top + clip.height * .70f)
                down(from)
                for (step in 1..4) moveTo(from + (to - from) * (step / 4f), delayMillis = 100)
                // A stationary final event removes release velocity. A valid fling must not be
                // mistaken for an automatic reveal restarting after this cancellation check.
                moveTo(to, delayMillis = 250)
                up()
            }
            val dragged = snapshot(fixture)
            assertTrue("The drag must move the actual reading surface", dragged.scroll < moving.scroll - moving.height * .10f)
            assertSameLines(staged, dragged)
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            val settled = snapshot(fixture)
            assertEquals("A canceled reveal must not resume after the finger is released", dragged.scroll, settled.scroll, 2f)
            assertSameLines(dragged, settled)
            assertPassive(fixture, target.index)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun changingOnlyTheThemePreservesTheReadersManualPosition() {
        val fixture = mount()
        val initial = snapshot(fixture)
        val target = choose(fixture, initial, "a fitting interior block") { block, view ->
            block.height <= view.height - EPSILON && block.top > view.height && block.bottom + view.height < view.maximum
        }
        activate(fixture, target.index)
        val positioned = snapshot(fixture)
        assertFullyVisible(block(positioned, target.index, fixture.plan.chunks[target.index]), positioned)
        scrollBy(positioned.height * .80f)
        val manual = snapshot(fixture)
        val movedBlock = block(manual, target.index, fixture.plan.chunks[target.index])
        assertTrue("The active block must be above the manual viewport so a hidden recapture is observable", movedBlock.top < manual.scroll - EPSILON)
        val lightBackground = renderedBackground()

        compose.runOnIdle { fixture.settings = fixture.settings.copy(theme = ThemeMode.DARK) }
        settleBeyondAnimations()
        val dark = snapshot(fixture)
        assertNotEquals("The theme must actually change rendered paint", lightBackground, renderedBackground())
        assertSameLines(manual, dark)
        assertEquals("A paint-only change must not reposition the active range", manual.scroll, dark.scroll, EPSILON)
        assertPassive(fixture, target.index)
    }

    @Test fun changingTextSizeUsesTheNewMeasuredRangeWithoutChangingTheOriginal() {
        val fixture = mount()
        val initial = snapshot(fixture)
        val target = choose(fixture, initial, "a fitting multiline block away from document boundaries") { block, view ->
            block.firstLine < block.lastLine && block.height <= view.height - EPSILON &&
                block.top > view.height * 2f && block.bottom + view.height < view.maximum
        }
        activate(fixture, target.index)
        val large = snapshot(fixture)
        assertFullyVisible(block(large, target.index, fixture.plan.chunks[target.index]), large)

        compose.runOnIdle { fixture.settings = fixture.settings.copy(reader = fixture.settings.reader.copy(sizeSp = 20f)) }
        settleBeyondAnimations()
        val resized = snapshot(fixture)
        assertEquals(20f, resized.layout.layoutInput.style.fontSize.value, .01f)
        assertEquals(36f, resized.layout.layoutInput.style.lineHeight.value, .01f)
        assertEquals(TEXT, resized.text)
        assertNotEquals("The test must produce new text geometry", large.layout.size, resized.layout.size)
        val active = block(resized, target.index, fixture.plan.chunks[target.index])
        assertTrue("The resized active block must still be a fitting case", active.height <= resized.height + EPSILON)
        assertFullyVisible(active, resized)
        assertPassive(fixture, target.index)
    }

    @Test fun sentenceAndParagraphViewsResolveTheSameAbsoluteChunkAgainstEachNewWindow() {
        val fixture = mount()
        val initial = snapshot(fixture)
        val target = choose(fixture, initial, "a fitting block in a later multi-block sentence") { block, view ->
            val chunk = fixture.plan.chunks[block.index]
            chunk.sentence > 1 && fixture.plan.chunks.count { it.sentence == chunk.sentence } > 1 &&
                block.height <= view.height - EPSILON && block.top > view.height
        }
        activate(fixture, target.index)
        val paragraph = snapshot(fixture)
        assertFullyVisible(block(paragraph, target.index, fixture.plan.chunks[target.index]), paragraph)
        compose.runOnIdle { fixture.state = fixture.state.copy(sentenceOnly = true) }
        settleBeyondAnimations()
        val sentence = snapshot(fixture)
        assertTrue("The sentence window must have a nonzero source offset", sentence.sourceStart > 0)
        assertTrue("The source window must actually become shorter", sentence.text.length < paragraph.text.length)
        val sentenceBlock = block(sentence, target.index, fixture.plan.chunks[target.index])
        assertTrue(sentenceBlock.height <= sentence.height + EPSILON)
        assertFullyVisible(sentenceBlock, sentence)

        compose.runOnIdle { fixture.state = fixture.state.copy(sentenceOnly = false) }
        settleBeyondAnimations()
        val restoredParagraph = snapshot(fixture)
        assertEquals(0, restoredParagraph.sourceStart)
        assertSameLines(paragraph, restoredParagraph)
        assertFullyVisible(block(restoredParagraph, target.index, fixture.plan.chunks[target.index]), restoredParagraph)
        assertPassive(fixture, target.index)
    }

    private class Fixture {
        val plan = SpeedChunker.plan(ARTICLE.id, BODY.contentHash, TEXT, SpeedGranularity.L3, 0, 1, 1L)
            .let { it.copy(planKey = SpeedChunker.planKey(it)) }
        var state by mutableStateOf(SpeedUiState(stage = SpeedStage.READING, plan = plan,
            selectedGranularity = SpeedGranularity.L3, chunkIndex = 1, playing = false, sentenceOnly = false))
        var settings by mutableStateOf(AppSettings(theme = ThemeMode.LIGHT, reader = ReaderPreferences(
            font = ReaderFont.SERIF, sizeSp = 24f, lineHeight = 1.8f, automaticStudy = false)))
    }

    private fun mount(reader: ReaderPreferences? = null): Fixture {
        val fixture = Fixture()
        if (reader != null) fixture.settings = fixture.settings.copy(reader = reader)
        assertTrue(fixture.plan.chunks.size > 10)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                LexiconTheme(fixture.settings) {
                    SpeedReadingScreen(ARTICLE, BODY, fixture.settings, fixture.state, SpeedUiActions())
                }
            }
        }
        compose.waitForIdle()
        val measured = snapshot(fixture)
        assertEquals(2f, measured.layout.layoutInput.density.fontScale, .01f)
        return fixture
    }

    private data class Block(val index: Int, val firstLine: Int, val lastLine: Int,
        val top: Float, val firstBottom: Float, val bottom: Float) {
        val height: Float get() = bottom - top
    }
    private data class View(val text: String, val sourceStart: Int, val layout: TextLayoutResult,
        val scroll: Float, val maximum: Float, val clip: Rect) {
        val height: Float get() = layout.size.height.toFloat() - maximum
        val bottom: Float get() = scroll + height
        fun describe() = "window=$sourceStart..${sourceStart + text.length}, layout=${layout.size}, scroll=$scroll, max=$maximum, height=$height, clip=$clip"
    }

    private fun snapshot(fixture: Fixture): View {
        val current = fixture.plan.chunks[fixture.state.chunkIndex]
        val window = if (fixture.state.sentenceOnly) fixture.plan.chunks.filter { it.sentence == current.sentence }
            .let { it.first().start until it.last().end } else 0 until TEXT.length
        val field = compose.onNodeWithTag("speed_original_text")
        val results = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val node = field.fetchSemanticsNode()
        val layout = results.single()
        val text = node.config[SemanticsProperties.Text].joinToString("") { it.text }
        val axis = node.config[SemanticsProperties.VerticalScrollAxisRange]
        assertFalse(axis.reverseScrolling)
        val view = View(text, window.first, layout, axis.value(), axis.maxValue(), node.boundsInRoot)
        assertEquals(TEXT.substring(window.first, window.last + 1), text)
        assertEquals(text, layout.layoutInput.text.text)
        assertTrue("Measured scrolling must be finite: ${view.describe()}", view.maximum.isFinite() &&
            view.maximum >= 0f && view.maximum < Int.MAX_VALUE && view.height > 0f && view.scroll in 0f..view.maximum)
        assertEquals("Validate the scroll-coordinate viewport against actual clipping before checking range behavior: " +
            view.describe(), view.height, view.clip.height, 2f)
        return view
    }

    private fun block(view: View, index: Int, chunk: SenseChunk): Block {
        val start = chunk.start - view.sourceStart
        val end = chunk.end - view.sourceStart
        assertTrue(start >= 0 && end > start && end <= view.text.length)
        val first = view.layout.getLineForOffset(start)
        val last = view.layout.getLineForOffset(end - 1)
        return Block(index, first, last, view.layout.getLineTop(first), view.layout.getLineBottom(first), view.layout.getLineBottom(last))
    }

    private fun choose(fixture: Fixture, view: View, requirement: String, accepts: (Block, View) -> Boolean): Block {
        val choices = fixture.plan.chunks.withIndex().filter { it.index in 3 until fixture.plan.chunks.lastIndex }
            .map { (index, chunk) -> block(view, index, chunk) }
        val chosen = choices.filter { accepts(it, view) }.minByOrNull { abs(it.top - view.layout.size.height * .5f) }
        assertNotNull("Fixture needs $requirement; ${view.describe()}; heights=${choices.map { it.height }.distinct()}", chosen)
        return requireNotNull(chosen)
    }

    private fun activate(fixture: Fixture, index: Int) {
        compose.runOnIdle { fixture.state = fixture.state.copy(chunkIndex = index) }
        compose.waitForIdle()
    }
    private fun settleBeyondAnimations() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }
    private fun stagedScroll(block: Block, height: Float): Float =
        ((block.firstBottom + block.bottom) * .5f - height).roundToInt().toFloat()
    private fun stageable(block: Block, view: View): Boolean {
        val scroll = stagedScroll(block, view.height)
        val bottom = scroll + view.height
        return block.firstLine < block.lastLine && block.height <= view.height - EPSILON &&
            scroll >= EPSILON && scroll <= view.maximum - view.height && block.top >= scroll + EPSILON &&
            block.firstBottom <= bottom - EPSILON && block.bottom > bottom + EPSILON
    }
    private fun assertFirstVisibleTailHidden(block: Block, view: View) {
        assertTrue(block.top >= view.scroll + EPSILON)
        assertTrue(block.firstBottom <= view.bottom - EPSILON)
        assertTrue(block.bottom > view.bottom + EPSILON)
        assertTrue(block.height <= view.height - EPSILON)
    }
    private fun assertFullyVisible(block: Block, view: View) {
        assertTrue("Active range top is clipped: $block; ${view.describe()}", block.top >= view.scroll - EPSILON)
        assertTrue("Active range tail is clipped: $block; ${view.describe()}", block.bottom <= view.bottom + EPSILON)
    }
    private fun assertSameLines(before: View, after: View) {
        assertEquals(before.sourceStart, after.sourceStart)
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
        compose.onNodeWithTag("speed_original_text").performSemanticsAction(SemanticsActions.ScrollBy) { accepted = it(0f, delta) }
        assertTrue("The actual reading surface must accept manual scrolling", accepted)
        compose.waitForIdle()
    }
    private fun renderedBackground(): Int {
        val bitmap = compose.onNodeWithTag("speed_reading_screen").captureToImage().asAndroidBitmap()
        // This corner belongs to the screen background, outside the padded toolbar text.
        return bitmap.getPixel(1.coerceAtMost(bitmap.width - 1), 1.coerceAtMost(bitmap.height - 1))
    }
    private fun assertPassive(fixture: Fixture, index: Int) = compose.runOnIdle {
        assertEquals(index, fixture.state.chunkIndex)
        assertEquals(SpeedStage.READING, fixture.state.stage)
        assertFalse(fixture.state.playing)
        assertTrue(fixture.state.answers.isEmpty())
        assertNull(fixture.state.result)
    }

    companion object {
        private const val EPSILON = 1f
        private val TEXT = listOf(
            "The town has made room for a shared reading club near the old school.",
            "Local teams read the news and share notes in the hall.",
            "Many people use the room to compare views and test a new idea.",
            "Each week a small group picks a topic for a long talk.",
            "Intergovernmental coordination strengthens institutional accountability across jurisdictions.",
            "The next group asks what the new bus route means for local shops.",
            "Some readers bring maps and others bring notes from their work.",
            "The group checks each claim against the facts in the text.",
            "A short pause gives each reader time to think about the main point.",
            "The room has wide desks and soft lights for people who stay late.",
            "Interdisciplinary collaboration strengthens environmental communication throughout communities.",
            "New members can ask for help when a phrase seems hard to follow.",
            "The host asks them to read the whole line before they seek an answer.",
            "Two teams may see the same report in very different ways.",
            "They write down their reasons and look for gaps in their own views.",
            "The final notes keep the key facts and the source of each claim.",
            "At the end the group makes a plan for the next week.",
            "People can take the notes home and read the full report again.",
            "The club grows as more neighbours find time to join the talks."
        ).joinToString(" ")
        private val ARTICLE = Article("speed-range-lifecycle", "speed-range-lifecycle-guid", listOf("self-authored"),
            "Reading together", "https://example.test/speed-range-lifecycle", "", sourceName = "Self-authored fixture")
        private val BODY = ArticleBody(ARTICLE.id, TEXT, fingerprint(TEXT), 1L, Long.MAX_VALUE, "Self-authored test")
    }
}
