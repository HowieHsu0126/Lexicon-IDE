package dev.lexicon.ide.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Independent screen geometry and event routing; no future layout helper, timer or user data. */
@RunWith(AndroidJUnit4::class)
class SpeedFocusLayoutUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bothPlaybackStatesGiveReadingSixtyFivePercentAndKeepSafeControlsFixed() {
        val fixture = mount(playing = false)
        val paused = reading(fixture)
        val pausedControls = controlBounds()
        assertReadingArea(paused)
        assertSafeControls(paused, pausedControls)
        assertToolsHidden()

        compose.onNodeWithTag("speed_play_pause").performClick()
        compose.waitForIdle()
        val playing = reading(fixture)
        val playingControls = controlBounds()
        assertReadingArea(playing)
        assertSafeControls(playing, playingControls)
        assertSameReading(paused, playing)
        CONTROL_TAGS.forEach { assertRect("$it must not move when playback starts", pausedControls.getValue(it), playingControls.getValue(it)) }
        assertToolsHidden()
        compose.runOnIdle {
            assertTrue(fixture.state.playing)
            assertEquals(1, fixture.playPauseRequests)
            assertEquals(0, fixture.pauseForToolsRequests)
        }

        compose.onNodeWithTag("speed_play_pause").performClick()
        compose.waitForIdle()
        val pausedAgain = reading(fixture)
        assertSameReading(paused, pausedAgain)
        val afterControls = controlBounds()
        CONTROL_TAGS.forEach { assertRect("$it must not move when playback pauses", pausedControls.getValue(it), afterControls.getValue(it)) }
        assertToolsHidden()
        compose.runOnIdle { assertFalse(fixture.state.playing); assertEquals(2, fixture.playPauseRequests) }
        compose.onNodeWithTag("speed_previous").performClick()
        compose.onNodeWithTag("speed_next").performClick()
        compose.onNodeWithTag("speed_repeat").performClick()
        compose.runOnIdle {
            assertEquals(1, fixture.previousRequests)
            assertEquals(1, fixture.nextRequests)
            assertEquals(1, fixture.repeatRequests)
            assertEquals(2, fixture.playPauseRequests)
        }
    }

    @Test fun openingToolsPausesOnceAndClosingPreservesTheUnderlyingReaderWithoutResuming() {
        val fixture = mount(playing = true)
        val initial = reading(fixture)
        manualScroll(initial.height * .65f)
        val before = reading(fixture)
        assertTrue("The preservation check must start from a real nonzero scroll", before.scroll > initial.scroll + initial.height * .30f)
        val controls = controlBounds()

        openTools()
        val underneath = reading(fixture)
        assertSameReading(before, underneath)
        val underneathControls = controlBounds(requireDisplayed = false)
        CONTROL_TAGS.forEach { assertRect("Modal tools must not relayout $it", controls.getValue(it), underneathControls.getValue(it)) }
        compose.runOnIdle {
            assertEquals(1, fixture.pauseForToolsRequests)
            assertEquals(1, fixture.effectiveToolPauses)
            assertEquals(0, fixture.playPauseRequests)
            assertFalse(fixture.state.playing)
        }
        closeTools()
        assertSameReading(before, reading(fixture))
        assertToolsHidden()
        compose.runOnIdle {
            assertEquals(1, fixture.pauseForToolsRequests)
            assertEquals(0, fixture.playPauseRequests)
            assertFalse(fixture.state.playing)
        }
    }

    @Test fun readingShortcutsCannotRunBehindToolsAndWorkAgainAfterDismissal() {
        val fixture = mount(playing = true)
        openTools()
        // Deliberately dispatch to the retained reading root: leaked/unhandled keys must not
        // invoke its shortcuts behind the Modal. This does not require a close button to ignore
        // Space when that button itself legitimately owns keyboard focus.
        readingKeys()
        compose.runOnIdle {
            assertEquals(0, fixture.playPauseRequests)
            assertEquals(0, fixture.previousRequests)
            assertEquals(0, fixture.nextRequests)
            assertFalse(fixture.state.playing)
        }
        closeTools()
        readingKeys()
        compose.runOnIdle {
            assertEquals(1, fixture.playPauseRequests)
            assertEquals(1, fixture.previousRequests)
            assertEquals(1, fixture.nextRequests)
            assertEquals(1, fixture.pauseForToolsRequests)
        }
    }

    @Test fun toolControlsDelegateSpeedSentenceAndEditingActionsExactlyOnce() {
        val fixture = mount(playing = false)
        openTools()
        compose.onNodeWithTag("speed_slider").performScrollTo()
        var accepted = false
        compose.onNodeWithTag("speed_slider").performSemanticsAction(SemanticsActions.SetProgress) { accepted = it(110f) }
        assertTrue("The existing speed slider must accept an explicit committed value", accepted)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(110), fixture.speedRequests); assertEquals(110, fixture.state.groupsPerMinute) }
        compose.onNodeWithTag("speed_tools_sheet").assertExists()

        compose.onNodeWithTag("speed_sentence_mode").performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(true), fixture.sentenceRequests); assertTrue(fixture.state.sentenceOnly) }
        compose.onNodeWithTag("speed_tools_sheet").assertExists()
        compose.onNodeWithTag("speed_edit_segmentation").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("speed_tools_sheet").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, fixture.editRequests)
            assertEquals(1, fixture.pauseForToolsRequests)
            assertEquals(0, fixture.effectiveToolPauses)
            assertEquals(0, fixture.playPauseRequests)
            assertFalse(fixture.state.playing)
        }
    }

    @Test fun correctionHandoffClosesToolsAndSubmitsOneExistingFeedbackAction() {
        val fixture = mount(playing = true)
        openTools()
        compose.onNodeWithText("切分纠错").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("speed_tools_sheet").assertDoesNotExist()
        compose.onNodeWithText("这一块过长").performScrollTo().performClick()
        compose.onNodeWithText("补充说明（可选）").performScrollTo().performTextInput("Please keep this phrase together.")
        compose.onNodeWithText("保存", substring = false).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("这一块过长").assertDoesNotExist()
        compose.onNodeWithTag("speed_tools_sheet").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("too_long" to "Please keep this phrase together."), fixture.correctionRequests)
            assertEquals(1, fixture.pauseForToolsRequests)
            assertEquals(1, fixture.effectiveToolPauses)
            assertEquals(0, fixture.playPauseRequests)
            assertFalse(fixture.state.playing)
        }
    }

    private class Fixture(playing: Boolean) {
        val plan = SpeedChunker.plan(ARTICLE.id, BODY.contentHash, TEXT, SpeedGranularity.L3, 0, 1, 1L)
            .let { it.copy(planKey = SpeedChunker.planKey(it)) }
        var state by mutableStateOf(SpeedUiState(stage = SpeedStage.READING, plan = plan,
            selectedGranularity = SpeedGranularity.L3, chunkIndex = 3, playing = playing, sentenceOnly = false))
        val settings = AppSettings(theme = ThemeMode.LIGHT, reader = ReaderPreferences(
            font = ReaderFont.SERIF, sizeSp = 24f, lineHeight = 1.8f, automaticStudy = false))
        var playPauseRequests = 0
        var pauseForToolsRequests = 0
        var effectiveToolPauses = 0
        var previousRequests = 0
        var nextRequests = 0
        var repeatRequests = 0
        var editRequests = 0
        val speedRequests = mutableListOf<Int>()
        val sentenceRequests = mutableListOf<Boolean>()
        val correctionRequests = mutableListOf<Pair<String, String>>()
        val actions = SpeedUiActions(
            onPrepare = { error("This layout fixture must not prepare training") },
            onPlayPause = { playPauseRequests++; state = state.copy(playing = !state.playing) },
            onPrevious = { previousRequests++ },
            onNext = { nextRequests++ },
            onRepeat = { repeatRequests++ },
            onSpeed = { speedRequests += it; state = state.copy(groupsPerMinute = it) },
            onSentenceOnly = { sentenceRequests += it; state = state.copy(sentenceOnly = it, playing = false) },
            onEditSegmentation = { editRequests++ },
            onCorrection = { kind, comment -> correctionRequests += kind to comment },
            onPauseForTools = {
                pauseForToolsRequests++
                if (state.playing) effectiveToolPauses++
                state = state.copy(playing = false)
            },
        )
    }

    private fun mount(playing: Boolean): Fixture {
        val fixture = Fixture(playing)
        assertTrue(fixture.plan.chunks.size > 4)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                LexiconTheme(fixture.settings) { SpeedReadingScreen(ARTICLE, BODY, fixture.settings, fixture.state, fixture.actions) }
            }
        }
        compose.waitForIdle()
        val measured = reading(fixture)
        assertEquals(2f, measured.layout.layoutInput.density.fontScale, .01f)
        assertEquals(24f, measured.layout.layoutInput.style.fontSize.value, .01f)
        assertEquals(43.2f, measured.layout.layoutInput.style.lineHeight.value, .01f)
        return fixture
    }

    private data class Reading(val text: String, val layout: TextLayoutResult, val scroll: Float,
        val maximum: Float, val viewport: Rect, val safeArea: Rect) {
        val height: Float get() = layout.size.height.toFloat() - maximum
    }
    private fun reading(fixture: Fixture): Reading {
        val field = compose.onNodeWithTag("speed_original_text")
        val results = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val node = field.fetchSemanticsNode()
        val layout = results.single()
        val text = node.config[SemanticsProperties.Text].joinToString("") { it.text }
        val axis = node.config[SemanticsProperties.VerticalScrollAxisRange]
        assertFalse(axis.reverseScrolling)
        val active = fixture.plan.chunks[fixture.state.chunkIndex]
        val expected = if (fixture.state.sentenceOnly) fixture.plan.chunks.filter { it.sentence == active.sentence }
            .let { TEXT.substring(it.first().start, it.last().end) } else TEXT
        val result = Reading(text, layout, axis.value(), axis.maxValue(), node.boundsInRoot,
            compose.onNodeWithTag("speed_reading_screen").fetchSemanticsNode().boundsInRoot)
        assertEquals(expected, result.text)
        assertEquals(expected, result.layout.layoutInput.text.text)
        assertTrue(result.maximum.isFinite() && result.maximum >= 0f && result.maximum < Int.MAX_VALUE)
        assertTrue(result.height > 0f && result.scroll in 0f..result.maximum)
        assertEquals("Cross-check content/scroll coordinates against the actual reading clip", result.height, result.viewport.height, TOLERANCE)
        return result
    }
    private fun assertReadingArea(reading: Reading) {
        assertTrue("Reading must occupy at least 65% of the App safe area at 200% font: " +
            "reading=${reading.height}, safe=${reading.safeArea.height}", reading.height >= reading.safeArea.height * .65f)
    }
    private fun controlBounds(requireDisplayed: Boolean = true): Map<String, Rect> = CONTROL_TAGS.associateWith { tag ->
        val node = compose.onNodeWithTag(tag)
        if (requireDisplayed) node.assertIsDisplayed()
        node.fetchSemanticsNode().boundsInRoot
    }
    private fun assertSafeControls(reading: Reading, controls: Map<String, Rect>) {
        val minimum = 48f * reading.layout.layoutInput.density.density
        controls.forEach { (tag, bounds) ->
            assertTrue("$tag must have a 48dp-wide touch target", bounds.width >= minimum - TOLERANCE)
            assertTrue("$tag must have a 48dp-high touch target", bounds.height >= minimum - TOLERANCE)
            assertTrue("$tag must be wholly inside the App safe area", bounds.left >= reading.safeArea.left - TOLERANCE &&
                bounds.top >= reading.safeArea.top - TOLERANCE && bounds.right <= reading.safeArea.right + TOLERANCE &&
                bounds.bottom <= reading.safeArea.bottom + TOLERANCE)
        }
        CONTROL_TAGS.forEachIndexed { index, tag ->
            CONTROL_TAGS.drop(index + 1).forEach { other ->
                assertFalse("$tag and $other must not overlap", controls.getValue(tag).overlaps(controls.getValue(other)))
            }
        }
    }
    private fun assertSameReading(before: Reading, after: Reading) {
        assertEquals(before.text, after.text)
        assertEquals(before.layout.size, after.layout.size)
        assertEquals(before.layout.lineCount, after.layout.lineCount)
        repeat(before.layout.lineCount) { line ->
            assertEquals(before.layout.getLineStart(line), after.layout.getLineStart(line))
            assertEquals(before.layout.getLineEnd(line), after.layout.getLineEnd(line))
            assertEquals(before.layout.getLineTop(line), after.layout.getLineTop(line), TOLERANCE)
            assertEquals(before.layout.getLineBottom(line), after.layout.getLineBottom(line), TOLERANCE)
        }
        assertEquals(before.scroll, after.scroll, TOLERANCE)
        assertEquals(before.maximum, after.maximum, TOLERANCE)
        assertRect("The reading viewport must remain fixed", before.viewport, after.viewport)
        assertRect("The App safe area must remain fixed", before.safeArea, after.safeArea)
    }
    private fun assertRect(message: String, before: Rect, after: Rect) {
        assertEquals("$message: left", before.left, after.left, TOLERANCE)
        assertEquals("$message: top", before.top, after.top, TOLERANCE)
        assertEquals("$message: right", before.right, after.right, TOLERANCE)
        assertEquals("$message: bottom", before.bottom, after.bottom, TOLERANCE)
    }
    private fun assertToolsHidden() {
        compose.onNodeWithTag("speed_tools_sheet").assertDoesNotExist()
        compose.onNodeWithTag("speed_slider").assertDoesNotExist()
        compose.onNodeWithTag("speed_sentence_mode").assertDoesNotExist()
        compose.onNodeWithTag("speed_edit_segmentation").assertDoesNotExist()
        compose.onNodeWithText("切分纠错").assertDoesNotExist()
        compose.onNodeWithTag("speed_tools").assertIsDisplayed().assertIsEnabled()
    }
    private fun openTools() {
        compose.onNodeWithTag("speed_tools").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("speed_tools_sheet").assertIsDisplayed()
        compose.onNodeWithTag("speed_tools_close").assertIsDisplayed().assertIsEnabled()
    }
    private fun closeTools() {
        compose.onNodeWithTag("speed_tools_close").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("speed_tools_sheet").assertDoesNotExist()
    }
    private fun readingKeys() {
        compose.onNodeWithTag("speed_reading_screen").performKeyInput {
            pressKey(Key.Spacebar); pressKey(Key.DirectionLeft); pressKey(Key.DirectionRight)
        }
        compose.waitForIdle()
    }
    private fun manualScroll(delta: Float) {
        var accepted = false
        compose.onNodeWithTag("speed_original_text").performSemanticsAction(SemanticsActions.ScrollBy) { accepted = it(0f, delta) }
        assertTrue("The actual reading surface must accept manual scrolling", accepted)
        compose.waitForIdle()
    }

    companion object {
        private const val TOLERANCE = 2f
        private val CONTROL_TAGS = listOf("speed_previous", "speed_play_pause", "speed_next", "speed_repeat")
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
        private val ARTICLE = Article("speed-focus-layout", "speed-focus-layout-guid", listOf("self-authored"),
            "A shared reading room", "https://example.test/speed-focus-layout", "", sourceName = "Self-authored fixture")
        private val BODY = ArticleBody(ARTICLE.id, TEXT, fingerprint(TEXT), 1L, Long.MAX_VALUE, "Self-authored test")
    }
}
