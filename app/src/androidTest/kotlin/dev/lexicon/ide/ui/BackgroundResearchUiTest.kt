package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundResearchUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = System.currentTimeMillis()
    private val query = BackgroundQuery("White House", "The White House announced a proposal.")
    private val models = mutableListOf<ViewModel>()
    private fun model(repo: BackgroundResearchTestRepository) = BackgroundResearchViewModel(repo, clock = { now }).also { models += it }
    @After fun dispose() { InstrumentationRegistry.getInstrumentation().runOnMainSync { models.forEach { it.viewModelScope.cancel() } } }

    @Test fun lookupRequiresMeaningChoiceAndExplicitAiAction() {
        val repo = BackgroundResearchTestRepository(now); val vm = model(repo)
        compose.setContent { Panel(vm) }
        compose.waitUntil { !vm.loadingCache && vm.input == query }
        compose.runOnIdle { assertTrue(repo.lookupCalls.isEmpty()); assertTrue(repo.explainCalls.isEmpty()) }
        node("background_lookup").performClick()
        compose.waitUntil { vm.lookup != null }
        node("background_candidate_building").assertExists()
        node("background_candidate_administration").performClick()
        compose.waitUntil { !vm.loadingExplanation }
        compose.runOnIdle { assertTrue(repo.explainCalls.isEmpty()) }
        node("background_explain").performClick()
        compose.waitUntil { vm.explanation != null }
        node("background_explanation").assertExists()
        compose.runOnIdle { assertEquals("administration", repo.explainCalls.single().second) }
    }

    @Test fun editingTheConceptRemovesThePreviousBoundExplanation() {
        val repo = BackgroundResearchTestRepository(now).apply { cachedLookup = backgroundFixture(query, now, multiple = false) }
        val saved = requireNotNull(repo.cachedLookup)
        repo.explanations[saved.id to "building"] = backgroundExplanation(saved, "building")
        val vm = model(repo); compose.setContent { Panel(vm) }
        compose.waitUntil { vm.explanation != null }
        node("background_edit_query").performClick()
        node("background_query_term").performTextReplacement("Congress")
        compose.onNodeWithTag("background_explanation").assertDoesNotExist()
        compose.onNodeWithTag("background_bound_term").assertDoesNotExist()
        compose.runOnIdle { assertTrue(repo.lookupCalls.isEmpty()); assertTrue(repo.explainCalls.isEmpty()) }
    }

    @Test fun contextPreviewExpandsWithoutLosingOriginalAndSourceExpansionDoesNotLeakAcrossMeanings() {
        val context = "The White House was mentioned in the account. ".repeat(20)
        val longQuery = query.copy(context = context.trim())
        val sources = backgroundFixture(longQuery, now).sources.map { it.copy(excerpt = (it.excerpt + " ").repeat(10).trim()) }
        val fixture = BackgroundResearch.createLookup(longQuery, backgroundFixture(longQuery, now).candidates, sources,
            listOf(BackgroundProviderOutcome("Synthetic reference", "retrieved")), "retrieved", now)
        val repo = BackgroundResearchTestRepository(now).apply { cachedLookup = fixture }
        val vm = model(repo); compose.setContent { Panel(vm, longQuery) }
        compose.waitUntil { vm.lookup != null }
        val contextLayouts = mutableListOf<TextLayoutResult>()
        node("background_context_preview").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(contextLayouts) }
        assertEquals(3, contextLayouts.single().lineCount)
        node("background_expand_context").performClick()
        val expanded = mutableListOf<TextLayoutResult>()
        node("background_context_preview").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(expanded) }
        assertTrue(expanded.single().lineCount > 3); assertEquals(longQuery.context, expanded.single().layoutInput.text.text)
        node("background_expand_context").performClick()
        node("background_candidate_building").performClick()
        node("background_source_S1").performClick()
        val firstSource = mutableListOf<TextLayoutResult>()
        node("background_excerpt_S1").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(firstSource) }
        assertTrue(firstSource.single().lineCount > 3)
        node("background_change_meaning").performClick()
        node("background_candidate_administration").performClick()
        val secondSource = mutableListOf<TextLayoutResult>()
        node("background_excerpt_S1").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(secondSource) }
        assertEquals(3, secondSource.single().lineCount)
        assertTrue(secondSource.single().layoutInput.text.text.contains("executive officials"))
        compose.runOnIdle { assertTrue(repo.explainCalls.isEmpty()) }
    }

    @Test fun staleSourcesRemainReadableButNewExplanationIsDisabled() {
        val repo = BackgroundResearchTestRepository(now).apply { cachedLookup = backgroundFixture(query,
            now - BackgroundResearch.CACHE_TTL_MILLIS - 1, multiple = false) }
        val vm = model(repo); compose.setContent { Panel(vm) }
        compose.waitUntil { vm.lookup != null && !vm.loadingExplanation }
        node("background_stale").assertExists()
        node("background_explain").assertIsNotEnabled()
        node("background_source_S1").performClick()
        node("background_excerpt_S1").assertIsDisplayed()
        node("background_refresh").assertIsEnabled()
        compose.runOnIdle { assertTrue(repo.lookupCalls.isEmpty()); assertTrue(repo.explainCalls.isEmpty()) }
    }

    @Test fun shortBoundTermUsesTheAvailableLineWidthAndShortContextHasNoExpandAction() {
        val short = query.copy(term = "FOMC", context = "Rates were discussed.")
        val repo = BackgroundResearchTestRepository(now).apply { cachedLookup = backgroundFixture(short, now, multiple = false) }
        val vm = model(repo); compose.setContent { Panel(vm, short) }
        compose.waitUntil { vm.lookup != null }
        val layouts = mutableListOf<TextLayoutResult>()
        node("background_bound_term").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().didOverflowWidth)
        assertFalse(layouts.single().didOverflowHeight)
        assertEquals(short.term, layouts.single().layoutInput.text.text)
        compose.onNodeWithTag("background_expand_context").assertDoesNotExist()
        compose.runOnIdle { assertTrue(repo.lookupCalls.isEmpty()); assertTrue(repo.explainCalls.isEmpty()) }
    }

    @Test fun continuingReadingDuringLookupRetainsTheCompletedResultForReopening() {
        val gate = CompletableDeferred<BackgroundLookup>()
        val repo = BackgroundResearchTestRepository(now).apply { lookupAction = { _, _ -> gate.await() } }
        val vm = model(repo); var visible by mutableStateOf(true)
        compose.setContent { if (visible) Panel(vm, onClose = { visible = false }) }
        compose.waitUntil { !vm.loadingCache && vm.input == query }
        node("background_lookup").performClick()
        node("background_continue_reading").performClick()
        compose.onNodeWithTag("background_research_panel").assertDoesNotExist()
        compose.runOnIdle { gate.complete(backgroundFixture(query, now)) }
        compose.waitUntil { vm.lookup != null }
        compose.runOnIdle { visible = true }
        node("background_bound_term").assertTextEquals(query.term)
        compose.runOnIdle { assertEquals(1, repo.lookupCalls.size); assertTrue(repo.explainCalls.isEmpty()) }
    }

    @Test fun captureBackgroundUsesExactLateSelectionAndReturnsToTheReader() {
        val original = "Earlier context. " + "Detail about evidence and reporting. ".repeat(55) + "The White House issued a statement."
        val article = Article("background-native", "guid", listOf("fixture"), "Synthetic source", "https://example.org/article", "")
        val body = ArticleBody(article.id, original, fingerprint(original), 1, Long.MAX_VALUE, "Synthetic material")
        val start = original.lastIndexOf("White House")
        val capture = captureAt(article, body, start, start + "White House".length, allowed = false, aiText = false)
        val app = CaptureAnalysisTestRepository()
        val vm = LexiconViewModel(app).also { models += it }
        val backgrounds = BackgroundResearchTestRepository(now)
        var closed by mutableStateOf(false)
        compose.setContent { LexiconTheme(AppSettings(theme = ThemeMode.DARK)) {
            if (!closed) CapturePanel(capture, emptyList(), emptyList(), vm, { closed = true }, {}, {},
                assistBusy = true, onAssist = { error("Background must not use the shared reading request") },
                backgroundRepository = backgrounds, captureBody = body)
        } }
        node("capture_background").assertIsEnabled().performClick()
        compose.onNodeWithTag("capture_target").assertDoesNotExist()
        compose.onNodeWithTag("background_research_panel").assertExists()
        node("background_query_context").assertTextContains("White House", substring = true)
        node("background_lookup").assertIsEnabled()
        compose.onNodeWithTag("background_continue_reading").assertIsDisplayed().performClick()
        // ModalBottomSheet.hide awaits its frame-clock animation before invoking the caller.
        // The caller must also remove the sheet, matching LexiconApp's real capture dismissal.
        compose.mainClock.advanceTimeBy(600)
        compose.waitUntil(5_000) { closed }
        compose.onNodeWithTag("background_research_panel").assertDoesNotExist()
        compose.runOnIdle { assertTrue(backgrounds.lookupCalls.isEmpty()); assertTrue(backgrounds.explainCalls.isEmpty()) }
    }

    @Test fun compactCandidateAndActionStayReachableAtTwoHundredPercent() {
        val repo = BackgroundResearchTestRepository(now).apply { cachedLookup = backgroundFixture(query, now) }
        val vm = model(repo)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) { Panel(vm) } }
        compose.waitUntil { vm.lookup != null }
        node("background_candidate_administration").assertIsDisplayed().performClick()
        compose.waitUntil { !vm.loadingExplanation }
        node("background_explain").assertIsDisplayed().assertIsEnabled()
        node("background_continue_reading").assertIsDisplayed()
    }

    @Test fun captureBackgroundEntryWrapsAndOpensAtSystemTwoHundredPercent() {
        val actualScale = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale
        org.junit.Assume.assumeTrue("Actual modal font trial requires system font scale 2.0", actualScale >= 1.99f)
        val app = CaptureAnalysisTestRepository()
        val vm = LexiconViewModel(app).also { models += it }
        val backgrounds = BackgroundResearchTestRepository(now)
        compose.setContent { LexiconTheme(AppSettings(theme = ThemeMode.DARK)) {
            CapturePanel(CaptureAnalysisTestRepository.capture, emptyList(), emptyList(), vm, {}, {}, {},
                onAssist = {}, onNote = { _, _ -> }, backgroundRepository = backgrounds)
        } }
        val entry = node("capture_background").assertIsDisplayed().assertIsEnabled()
        val label = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("背景", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(label) }
        assertTrue("The real bottom-sheet label must render at system 200%", label.single().layoutInput.density.fontScale >= 1.99f)
        compose.onNodeWithTag("capture_assist_actions").assertExists()
        entry.performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("background_research_panel").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("capture_target").assertDoesNotExist()
        compose.runOnIdle { assertTrue(backgrounds.lookupCalls.isEmpty()); assertTrue(backgrounds.explainCalls.isEmpty()) }
    }

    @Composable private fun Panel(vm: BackgroundResearchViewModel, initial: BackgroundQuery = query, onClose: () -> Unit = {}) {
        LexiconTheme(AppSettings(theme = ThemeMode.DARK)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                BackgroundResearchPanel(vm, "test-query", initial, onClose)
            }
        }
    }
    private fun node(tag: String) = compose.onNodeWithTag(tag).performScrollTo()
}
