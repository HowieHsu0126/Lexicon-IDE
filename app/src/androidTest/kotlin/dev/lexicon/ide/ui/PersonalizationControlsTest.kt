package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Presentation callbacks and accessibility only; these fixtures do not measure learning ability. */
@RunWith(AndroidJUnit4::class)
class PersonalizationControlsTest {
    @get:Rule val compose = createComposeRule()
    private val suggestion = PaceSuggestion("pace-fixture", SpeedGranularity.L2, 60, 63,
        listOf("fixture-run-1", "fixture-run-2"), 6, 6, 2, "fixture-evidence", "pace-v1")
    private val decision = PersonalizationDecision("decision-fixture", suggestion,
        PersonalizationDecisionStatus.APPLIED, decidedAt = 1_000L, appliedRevision = 1L)

    @Test fun absentEvidenceDoesNotCreateASuggestionOrProgressClaim() {
        compose.setContent { LexiconTheme(AppSettings()) {
            Column {
                RememberTrainingSettingsToggle(false, {})
                SpeedPersonalizationControls(null, null, {}, {}, {})
            }
        } }
        compose.onNodeWithTag("speed_remember_settings").assertIsOff()
        compose.onNodeWithTag("speed_personalization").assertDoesNotExist()
        compose.onNodeWithTag("speed_pace_suggestion").assertDoesNotExist()
        compose.onNodeWithTag("speed_pace_undo").assertDoesNotExist()
        compose.onNodeWithText("建议下轮", substring = true).assertDoesNotExist()
    }

    @Test fun rememberSwitchReportsTheChangeAndRespectsPendingEdits() {
        var enabled by mutableStateOf(false)
        var editable by mutableStateOf(true)
        val choices = mutableListOf<Boolean>()
        compose.setContent { LexiconTheme(AppSettings()) {
            RememberTrainingSettingsToggle(enabled, { choices += it; enabled = it }, editable = editable)
        } }
        compose.onNodeWithTag("speed_remember_settings").assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(listOf(true), choices); editable = false }
        compose.onNodeWithTag("speed_remember_settings").assertIsNotEnabled().assertIsOn()
        compose.runOnIdle { assertEquals(listOf(true), choices) }
    }

    @Test fun applyKeepAndUndoCarryTheCorrectIdentityWithoutChangingEvidence() {
        var pending by mutableStateOf<PaceSuggestion?>(suggestion)
        var applied by mutableStateOf<PersonalizationDecision?>(null)
        val actions = mutableListOf<String>()
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedPersonalizationControls(pending, applied, onApply = { id ->
                actions += "apply:$id"
                pending = null
                applied = decision
            }, onDismiss = { id ->
                actions += "keep:$id"
                pending = null
            }, onUndo = { id ->
                actions += "undo:$id"
                applied = null
            })
        } }
        compose.onNodeWithTag("speed_pace_evidence").assertTextEquals("本次建议采用 2 轮 · 答对 6/6 题")
        compose.onNodeWithTag("speed_pace_apply").performClick()
        compose.onNodeWithTag("speed_pace_suggestion").assertDoesNotExist()
        compose.onNodeWithTag("speed_pace_undo").performClick()
        compose.onNodeWithTag("speed_personalization").assertDoesNotExist()
        compose.runOnIdle { pending = suggestion.copy(id = "pace-second") }
        compose.onNodeWithTag("speed_pace_keep").performClick()
        compose.onNodeWithTag("speed_personalization").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("apply:pace-fixture", "undo:decision-fixture", "keep:pace-second"), actions)
            assertEquals(6, suggestion.correct)
            assertEquals(6, suggestion.total)
        }
    }

    @Test fun largeFontKeepsEvidenceAndActionsWithinANarrowScrollableViewport() {
        compose.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density, 2f)) {
                LexiconTheme(AppSettings(theme = ThemeMode.DARK)) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize()) {
                            Column(Modifier.width(320.dp).fillMaxHeight().testTag("personalization_viewport")
                                .verticalScroll(rememberScrollState()).padding(16.dp)) {
                                RememberTrainingSettingsToggle(true, {})
                                SpeedPersonalizationControls(suggestion, decision, {}, {}, {},
                                    hold = PaceHold(SpeedGranularity.L2, 60, 1, 1_000))
                            }
                        }
                    }
                }
            }
        }
        val viewport = compose.onNodeWithTag("personalization_viewport").fetchSemanticsNode().boundsInRoot
        listOf("speed_remember_settings", "speed_pace_evidence", "speed_pace_apply", "speed_pace_keep", "speed_pace_undo", "speed_pace_release_hold").forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must not extend beyond the reading width", bounds.left >= viewport.left && bounds.right <= viewport.right)
            assertTrue("$tag must retain a visible height", bounds.height > 0)
        }
        compose.onNodeWithTag("speed_pace_evidence").assertTextEquals("本次建议采用 2 轮 · 答对 6/6 题")
    }

    @Test fun heldPaceRemainsExplicitUntilTheUserAllowsLaterSuggestions() {
        var hold by mutableStateOf<PaceHold?>(PaceHold(SpeedGranularity.L2, 60, 1, 1_000))
        var releases = 0
        var editable by mutableStateOf(false)
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedPersonalizationControls(null, null, {}, {}, {}, hold = hold, editable = editable,
                onReleaseHold = { releases++; hold = null })
        } }
        compose.onNodeWithText("已保持此节奏 · L2 · 60 意群/分钟").assertIsDisplayed()
        compose.onNodeWithTag("speed_pace_suggestion").assertDoesNotExist()
        compose.onNodeWithTag("speed_pace_release_hold").assertIsNotEnabled()
        compose.runOnIdle { editable = true }
        compose.onNodeWithText("允许后续建议").performClick()
        compose.runOnIdle { assertEquals(1, releases) }
        compose.onNodeWithTag("speed_personalization").assertDoesNotExist()
        compose.onNodeWithTag("speed_pace_suggestion").assertDoesNotExist()
    }
}
