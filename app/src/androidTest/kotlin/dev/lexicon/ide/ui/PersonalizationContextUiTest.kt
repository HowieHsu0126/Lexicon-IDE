package dev.lexicon.ide.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** User-visible context isolation; synthetic evidence never claims a measured learning benefit. */
@RunWith(AndroidJUnit4::class)
class PersonalizationContextUiTest {
    @get:Rule val compose = createComposeRule()
    private val text = "Residents review library proposals before choosing a practical schedule."
    private val article = Article("context-ui", "g", listOf("s"), "A local reading plan", "https://example.org/context-ui", "")
    private val body = ArticleBody(article.id, text, fingerprint(text), 1, Long.MAX_VALUE, "Self-authored fixture")
    private val l1 = decision(SpeedGranularity.L1, 1)
    private val l2 = decision(SpeedGranularity.L2, 2)

    @Test fun aStaleOtherGranularityCannotExposeAnUndoOrOutcomeInTheCurrentSetup() {
        var state by mutableStateOf(base().copy(lastApplied = l2, paceOutcome = outcome(l2), paceOutcomeDecision = l2))
        val undone = mutableListOf<String>()
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedReadingScreen(article, body, AppSettings(), state, SpeedUiActions(onUndoSuggestion = { undone += it }))
        } }
        compose.onNodeWithTag("speed_pace_undo").assertDoesNotExist()
        compose.onNodeWithTag("speed_outcome_toggle").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(lastApplied = l1, paceOutcome = outcome(l1), paceOutcomeDecision = l1) }
        compose.onNodeWithTag("speed_pace_undo").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(l1.id), undone) }
        compose.onNodeWithTag("speed_outcome_toggle").performScrollTo().performClick()
        compose.onNodeWithText("L1 · 63 意群/分钟").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("L2 · 63 意群/分钟").assertDoesNotExist()
    }

    @Test fun draculaLargeFontKeepsCurrentGranularityUndoReachableWhenProfileSelectionDiffers() {
        val settings = AppSettings(theme = ThemeMode.DARK)
        val state = base().copy(lastApplied = l1, paceOutcome = outcome(l1), paceOutcomeDecision = l1,
            profile = base().profile.copy(enabled = false, granularity = SpeedGranularity.L2))
        val undone = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                LexiconTheme(settings) {
                    SpeedReadingScreen(article, body, settings, state, SpeedUiActions(onUndoSuggestion = { undone += it }))
                }
            }
        }
        compose.onNodeWithText("已应用 L1 · 60 → 63 意群/分钟").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("speed_pace_undo").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(l1.id), undone) }
        compose.onNodeWithText("已应用 L2", substring = true).assertDoesNotExist()
    }

    @Test fun aFinishedL1RunShowsL1ControlsEvenWhenTheLatestProfileDecisionBelongsToL2() {
        val plan = SpeedChunker.plan(article.id, body.contentHash, text, SpeedGranularity.L1, 0, 1)
        val elapsed = plan.chunks.size * 1_000L
        val run = SpeedRun("context-run", article.id, body.contentHash, SpeedGranularity.L1, plan.planKey,
            1_000, 1_000 + elapsed, elapsed, plan.chunks.sumOf { it.wordCount }, plan.chunks.size,
            List(plan.chunks.size) { 1_000L }, 0, 0, emptyList(), 0, true, 60, "rules", true, true, true)
        val state = base().copy(stage = SpeedStage.RESULT, plan = plan, result = run, groupsPerMinute = 60,
            lastApplied = l1, paceOutcome = outcome(l1), paceOutcomeDecision = l1)
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedReadingScreen(article, body, AppSettings(), state, SpeedUiActions())
        } }
        compose.onNodeWithText("已应用 L1 · 60 → 63 意群/分钟").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("speed_outcome_toggle").performScrollTo().performClick()
        compose.onNodeWithText("L1 · 63 意群/分钟").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("已应用 L2", substring = true).assertDoesNotExist()
    }

    private fun base() = SpeedUiState(selectedGranularity = SpeedGranularity.L1, groupsPerMinute = 63,
        personalizationAvailable = true,
        profile = PersonalizationProfile(revision = 2, granularity = SpeedGranularity.L2,
            rates = mapOf(SpeedGranularity.L1 to 63, SpeedGranularity.L2 to 63), decisions = listOf(l1, l2)))
    private fun decision(granularity: SpeedGranularity, index: Long): PersonalizationDecision {
        val offer = PaceSuggestion("context-$granularity", granularity, 60, 63, listOf("run-$granularity-a", "run-$granularity-b"),
            6, 6, 2, "synthetic", "pace-v2")
        return PersonalizationDecision(offer.id, offer, PersonalizationDecisionStatus.APPLIED, index * 1_000, appliedRevision = index)
    }
    private fun outcome(decision: PersonalizationDecision) = PersonalizationOutcome(decision.id, decision.suggestion.granularity,
        63, 0, 0, 0, 0, 0, 0, false, null, emptyList())
}
