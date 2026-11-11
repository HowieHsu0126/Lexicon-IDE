package dev.lexicon.ide.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
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

@RunWith(AndroidJUnit4::class)
class SpeedOutcomeControlsTest {
    @get:Rule val compose = createComposeRule()
    private val empty = PersonalizationOutcome("decision", SpeedGranularity.L2, 63, 0, 0, 0, 0, 0, 0, false, null, emptyList())

    @Test fun absentOutcomeDoesNotAdvertiseAnUnavailableEvidencePanel() {
        compose.setContent { LexiconTheme(AppSettings()) { SpeedOutcomeControls(null) } }
        compose.onNodeWithTag("speed_outcome").assertDoesNotExist()
    }

    @Test fun noSamplesStayUnknownAndEvidenceIsCollapsedInitially() {
        compose.setContent { LexiconTheme(AppSettings()) { SpeedOutcomeControls(empty) } }
        compose.onNodeWithText("尚无符合条件的后续练习。").assertDoesNotExist()
        compose.onNodeWithTag("speed_outcome_toggle").performClick()
        compose.onNodeWithText("尚无符合条件的后续练习。").assertIsDisplayed()
        compose.onNodeWithText("0%").assertDoesNotExist()
        compose.onNodeWithText("100%").assertDoesNotExist()
        compose.onNodeWithText("答对 / 总题数").assertDoesNotExist()
    }

    @Test fun outcomeShowsRealDenominatorsPausesAndRevisitsAt200PercentWithoutClaimingGrowth() {
        val outcome = empty.copy(sampleCount = 3, materialCount = 3, correct = 5, total = 6,
            pauses = 4, revisits = 2, evidenceEnough = true, accuracy = 5.0 / 6, evidenceRunIds = listOf("a", "b", "c"))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                LexiconTheme(AppSettings(theme = ThemeMode.DARK)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { SpeedOutcomeControls(outcome) }
                }
            }
        }
        compose.onNodeWithTag("speed_outcome_toggle").performClick()
        compose.onNodeWithText("3 次 / 3 份").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("5 / 6").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("4 / 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("83%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("提升", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("speed_outcome_scope").performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("speed_outcome_scope").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
    }
}
