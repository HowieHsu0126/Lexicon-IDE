package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalizationHistoryUiTest {
    @get:Rule val compose = createComposeRule()
    private val fixture = PaceHistoryTestFixture()

    @Test fun listSelectsEachActualDecisionAndReturningDoesNotApplyOrUndoIt() {
        var state by mutableStateOf(fixture.state())
        var closed = 0
        val selected = mutableListOf<String?>()
        compose.setContent { LexiconTheme(AppSettings()) {
            PersonalizationHistoryDialog(state, fixture.articles, onSelect = { id ->
                selected += id; state = fixture.state(fixture.profile.decisions.singleOrNull { it.id == id })
            }, onClose = { closed++ }, onRetry = {}, onInspectRun = {})
        } }
        for (decision in PersonalizationHistory.decisions(fixture.profile)) {
            compose.onNodeWithTag("pace_history_content").performScrollToNode(hasTestTag("pace_history_decision_${decision.id}"))
            compose.onNodeWithTag("pace_history_decision_${decision.id}").performClick()
            compose.onNodeWithTag("pace_history_detail_status").assertTextEquals(paceDecisionStatus(decision))
            compose.onNodeWithTag("speed_pace_apply").assertDoesNotExist()
            compose.onNodeWithTag("speed_pace_undo").assertDoesNotExist()
            compose.onNodeWithTag("pace_history_back").assertIsDisplayed().performClick()
            compose.onNodeWithTag("pace_history_detail").assertDoesNotExist()
        }
        compose.onNodeWithTag("pace_history_back").performClick()
        compose.runOnIdle {
            assertEquals(1, closed)
            assertEquals(PersonalizationHistory.decisions(fixture.profile).flatMap { listOf(it.id, null) }, selected)
        }
    }

    @Test fun oldVerifiedEvidenceOpensOnlyItsFrozenRunsDespiteLaterDecisionsAndPractice() {
        val opened = mutableListOf<SpeedRun>()
        show(fixture.state(fixture.applied), onInspect = { opened += it })
        compose.onNodeWithTag("pace_history_evidence_toggle").performScrollTo().performClick()
        val detail = fixture.detail(fixture.applied)
        for (run in detail.originalEvidence.runs) {
            compose.onNodeWithTag("pace_history_original_open_${run.id}").performScrollTo().assertIsDisplayed().performClick()
        }
        compose.onNodeWithTag("pace_history_original_run_${fixture.undone.suggestion.evidenceRunIds.first()}").assertDoesNotExist()
        compose.runOnIdle { assertEquals(detail.originalEvidence.runs, opened) }
    }

    @Test fun missingAndChangedOriginalEvidenceHaveNoPartialOrReplacementEntry() {
        var state by mutableStateOf(fixture.state(fixture.applied))
        compose.setContent { LexiconTheme(AppSettings()) {
            PersonalizationHistoryScreen(state, fixture.articles, {}, {}, {}, {})
        } }
        val missing = fixture.runs.filterNot { it.id == fixture.applied.suggestion.evidenceRunIds.first() }
        val changed = fixture.runs.map { if (it.id == fixture.applied.suggestion.evidenceRunIds.first()) it.copy(pauses = 1) else it }
        listOf(missing to PaceHistoricalEvidenceStatus.MISSING, changed to PaceHistoricalEvidenceStatus.CHANGED).forEach { (runs, status) ->
            compose.runOnIdle { state = fixture.state(fixture.applied).copy(detail = fixture.detail(fixture.applied, runs)) }
            assertEquals(status, state.detail?.originalEvidence?.status)
            compose.onNodeWithTag("pace_history_evidence_status").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("pace_history_evidence_toggle").assertDoesNotExist()
            fixture.applied.suggestion.evidenceRunIds.forEach { id ->
                compose.onNodeWithTag("pace_history_original_open_$id").assertDoesNotExist()
            }
        }
    }

    @Test fun aKeptSuggestionExplainsThatItWasNotAppliedWithoutAZeroOutcome() {
        show(fixture.state(fixture.kept))
        compose.onNodeWithTag("pace_history_detail_status").assertTextEquals("已保持")
        compose.onNodeWithTag("pace_history_kept").performScrollTo().assertTextEquals("当时选择保持 60 意群/分钟，未应用该建议。")
        compose.onNodeWithTag("speed_outcome_toggle").assertDoesNotExist()
        compose.onNodeWithText("0%").assertDoesNotExist()
        compose.onNodeWithTag("speed_pace_apply").assertDoesNotExist()
    }

    @Test fun undoneDetailShowsTheCutoffAndOnlyOpensBeforeUndoBoundPractice() {
        val opened = mutableListOf<SpeedRun>()
        show(fixture.state(fixture.undone), onInspect = { opened += it })
        compose.onNodeWithTag("pace_history_undone_at").performScrollTo().assertTextEquals("撤销于 " + paceDecisionDate(fixture.undone.updatedAt))
        compose.onNodeWithText("后续记录只统计撤销前完成的练习。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("speed_outcome_toggle").performScrollTo().performClick()
        for (run in fixture.undoneBefore) {
            compose.onNodeWithTag("pace_outcome_open_${run.id}").performScrollTo().performClick()
        }
        compose.onNodeWithTag("pace_outcome_run_${fixture.afterUndo.id}").assertDoesNotExist()
        compose.runOnIdle { assertEquals(fixture.undoneBefore, opened) }
    }

    @Test fun selectedDetailExpansionSurvivesReturningToListAndSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        var state by mutableStateOf(fixture.state(fixture.applied))
        restoration.setContent { LexiconTheme(AppSettings()) {
            PersonalizationHistoryScreen(state, fixture.articles, onSelect = { id ->
                state = fixture.state(fixture.profile.decisions.singleOrNull { it.id == id })
            }, onBack = { state = fixture.state() }, onRetry = {}, onInspectRun = {})
        } }
        compose.onNodeWithTag("pace_history_evidence_toggle").performScrollTo().performClick()
        val row = "pace_history_original_open_${fixture.applied.suggestion.evidenceRunIds.first()}"
        compose.onNodeWithTag(row).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pace_history_back").performClick()
        compose.onNodeWithTag("pace_history_content").performScrollToNode(hasTestTag("pace_history_decision_${fixture.applied.id}"))
        compose.onNodeWithTag("pace_history_decision_${fixture.applied.id}").performClick()
        compose.onNodeWithTag(row).performScrollTo().assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag(row).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pace_history_detail_rate").performScrollTo().assertTextContains("60 → 63", substring = true)
    }

    @Test fun narrowFont200RetainsReadableHistoryAndFullTouchTargets() {
        val opened = mutableListOf<SpeedRun>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                LexiconTheme(AppSettings(theme = ThemeMode.DARK)) {
                    Box(Modifier.width(320.dp).fillMaxHeight().testTag("history_test_viewport")) {
                        PersonalizationHistoryScreen(fixture.state(fixture.applied), fixture.articles, {}, {}, {}, { opened += it })
                    }
                }
            }
        }
        for (tag in listOf("pace_history_detail_status", "pace_history_detail_rate", "pace_history_evidence_status")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("$tag must remain fully laid out", layouts.single().hasVisualOverflow)
        }
        compose.onNodeWithTag("pace_history_evidence_toggle").performScrollTo().performClick()
        val run = fixture.detail(fixture.applied).originalEvidence.runs.first()
        val entry = compose.onNodeWithTag("pace_history_original_open_${run.id}").performScrollTo().assertIsDisplayed()
        val bounds = entry.getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("history_test_viewport").getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top >= 48.dp)
        assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
        compose.onNodeWithTag("pace_history_back").assertIsDisplayed()
        entry.performClick()
        compose.runOnIdle { assertEquals(listOf(run), opened) }
    }

    @Test fun errorStateKeepsRetryAndBackAvailableWithoutFabricatedDecisions() {
        var retries = 0
        var backs = 0
        compose.setContent { LexiconTheme(AppSettings()) {
            PersonalizationHistoryScreen(PaceHistoryUiState(selectedId = fixture.applied.id, loading = false, error = "调整记录暂时无法读取，请重试。"),
                fixture.articles, {}, { backs++ }, { retries++ }, {})
        } }
        compose.onNodeWithTag("pace_history_error").assertIsDisplayed()
        compose.onNodeWithText("重试").performClick()
        compose.onNodeWithTag("pace_history_back").performClick()
        compose.onNodeWithTag("pace_history_detail").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, backs) }
    }

    private fun show(state: PaceHistoryUiState, onInspect: (SpeedRun) -> Unit = {}) {
        compose.setContent { LexiconTheme(AppSettings()) {
            PersonalizationHistoryScreen(state, fixture.articles, {}, {}, {}, onInspect)
        } }
    }
}
