package dev.lexicon.ide.ui

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Visible exit affordance and busy/error feedback; no user database, model or playback clock. */
@RunWith(AndroidJUnit4::class)
class SpeedReadingTransitionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun intensiveReadingRequestKeepsToolsOpenUntilSavingSucceeds() {
        val fixture = mount(ThemeMode.LIGHT)
        compose.onNodeWithTag("speed_tools").performClick()
        compose.onNodeWithTag("speed_read_original").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("speed_tools_sheet").assertExists()
        compose.onNodeWithTag("speed_read_original").assertIsNotEnabled()
        compose.onNodeWithTag("speed_sentence_mode").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("speed_slider").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("speed_edit_segmentation").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("speed_feedback").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, fixture.readRequests)
            assertEquals(0, fixture.backRequests)
            assertEquals(2, fixture.state.chunkIndex)
            assertFalse(fixture.state.playing)
        }
    }

    @Test fun failedTransitionShowsRetryInsideToolsAndRetainsTheCurrentChunkAtLargeType() {
        val fixture = mount(ThemeMode.DARK)
        compose.onNodeWithTag("speed_tools").performClick()
        compose.onNodeWithTag("speed_read_original").performScrollTo().performClick()
        compose.runOnIdle { fixture.state = fixture.state.copy(saving = false, error = "训练记录尚未保存，请重试返回。") }
        compose.onNodeWithTag("speed_tools_sheet").assertExists()
        compose.onNodeWithTag("speed_transition_error").performScrollTo().assertIsDisplayed()
            .assertTextEquals("训练记录尚未保存，请重试返回。")
        compose.onNodeWithTag("speed_read_original").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(2, fixture.readRequests)
            assertEquals(2, fixture.state.chunkIndex)
            assertEquals(0, fixture.backRequests)
        }
        compose.onNodeWithTag("speed_tools_sheet").assertExists()
    }

    @Test fun closingToolsWhileSavingDoesNotExposeEnabledPlaybackOrLeakingShortcuts() {
        val fixture = mount(ThemeMode.DARK)
        compose.onNodeWithTag("speed_tools").performClick()
        compose.runOnIdle { fixture.state = fixture.state.copy(saving = true) }
        compose.onNodeWithTag("speed_tools_close").performClick()
        listOf("speed_previous", "speed_play_pause", "speed_next", "speed_repeat", "speed_tools").forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled()
        }
        readingKeys()
        compose.runOnIdle { assertEquals(0, fixture.transportRequests) }
        compose.runOnIdle { fixture.state = fixture.state.copy(saving = false) }
        listOf("speed_previous", "speed_play_pause", "speed_next", "speed_repeat", "speed_tools").forEach {
            compose.onNodeWithTag(it).assertIsEnabled()
        }
        readingKeys()
        compose.runOnIdle {
            assertEquals(3, fixture.transportRequests)
            assertEquals(0, fixture.readRequests)
            assertEquals(0, fixture.backRequests)
        }
    }

    private fun readingKeys() = compose.onNodeWithTag("speed_reading_screen").performKeyInput {
        keyDown(Key.Spacebar); keyUp(Key.Spacebar)
        keyDown(Key.DirectionLeft); keyUp(Key.DirectionLeft)
        keyDown(Key.DirectionRight); keyUp(Key.DirectionRight)
    }

    private class Fixture {
        var state by mutableStateOf(SpeedUiState(stage = SpeedStage.READING, plan = plan, chunkIndex = 2))
        var readRequests = 0
        var backRequests = 0
        var transportRequests = 0
    }

    private fun mount(theme: ThemeMode): Fixture {
        val fixture = Fixture()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                val settings = AppSettings(theme = theme)
                LexiconTheme(settings) {
                    SpeedReadingScreen(article, body, settings, fixture.state, SpeedUiActions(
                        onBack = { fixture.backRequests++ },
                        onPauseForTools = { fixture.state = fixture.state.copy(playing = false) },
                        onReadOriginal = {
                            fixture.readRequests++
                            fixture.state = fixture.state.copy(saving = true, error = null)
                        },
                        onPlayPause = { fixture.transportRequests++ },
                        onPrevious = { fixture.transportRequests++ },
                        onNext = { fixture.transportRequests++ },
                        onRepeat = { fixture.transportRequests++ },
                    ))
                }
            }
        }
        return fixture
    }

    companion object {
        private val text = "The readers compare two proposals for a community library. " +
            "They consider the cost of longer opening hours and discuss how the building could serve local families."
        private val article = Article("transition-ui", "transition-ui", emptyList(), "Self-authored transition fixture", "https://example.invalid/transition", "")
        private val body = ArticleBody(article.id, text, "transition-ui-body", 0, Long.MAX_VALUE, "Self-authored fixture")
        private val plan = SpeedChunker.plan(article.id, body.contentHash, text, SpeedGranularity.L2, 0, 1, 1)
    }
}
