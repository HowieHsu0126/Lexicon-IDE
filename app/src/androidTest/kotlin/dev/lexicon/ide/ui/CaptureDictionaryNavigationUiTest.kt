package dev.lexicon.ide.ui

import android.view.WindowInsets
import android.view.inspector.WindowInspector
import android.util.Log
import androidx.activity.BackEventCompat
import androidx.activity.ComponentDialog
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
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
class CaptureDictionaryNavigationUiTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<LexiconViewModel>()
    private val capture = CaptureAnalysisTestRepository.capture
    private fun model(repo: NavigationRepository) = LexiconViewModel(repo).also { models += it }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun field(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun selection(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
    private fun scroll() = compose.onNodeWithTag("capture_body_scroll").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun setSelection(tag: String, range: TextRange) = compose.onNodeWithTag(tag)
        .performSemanticsAction(SemanticsActions.SetSelection) { it(range.start, range.end, false) }
    private fun settle(vm: LexiconViewModel, word: String = capture.target, id: String = capture.id) {
        compose.waitUntil(5_000) { vm.dictionaryLookupFor(id, word)?.loading == false && !vm.analysisProbeLoading }
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
    }
    private fun hideKeyboard() {
        compose.runOnIdle { WindowInspector.getGlobalWindowViews().forEach { it.windowInsetsController?.hide(WindowInsets.Type.ime()) } }
        compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
    }
    private fun openDictionary() {
        compose.onNodeWithTag("capture_dictionary_toggle").performScrollTo().performClick()
        compose.waitUntil(5_000) { exists("dictionary_detail_list") }
        compose.onNodeWithTag("dictionary_detail_back").assertIsDisplayed()
    }
    @After fun dispose() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { models.forEach { it.viewModelScope.cancel() } }
    }

    @Test fun detailRoundTripPreservesBothDraftSelectionsAndScrollWithoutAnotherLookup() {
        val repo = NavigationRepository(); val vm = model(repo)
        compose.setContent { LexiconTheme(AppSettings()) { CapturePanel(capture, emptyList(), emptyList(), vm, {}, {}, {}) } }
        settle(vm)
        val target = "robust method"
        val sentence = "A robust method works under changing conditions."
        compose.onNodeWithTag("capture_target").performScrollTo().performTextReplacement(target)
        setSelection("capture_target", TextRange(6, 2))
        assertEquals("The focused target starts with a reverse selection", TextRange(6, 2), selection("capture_target"))
        compose.onNodeWithTag("capture_sentence").performScrollTo().performTextReplacement(sentence)
        assertEquals("Moving focus to the sentence natively collapses the target selection", TextRange(6), selection("capture_target"))
        setSelection("capture_sentence", TextRange(16, 4))
        assertEquals("The focused sentence starts with a reverse selection", TextRange(16, 4), selection("capture_sentence"))
        settle(vm, target); hideKeyboard()
        compose.onNodeWithTag("capture_dictionary_toggle").performScrollTo()
        // Focus changes may natively collapse the previous field. Restore the state at entry,
        // not a historical selection made before the sentence took focus.
        val beforeTargetSelection = selection("capture_target")
        val beforeSentenceSelection = selection("capture_sentence")
        assertEquals("Scrolling and hiding the keyboard retain the active sentence selection", TextRange(16, 4), beforeSentenceSelection)
        Log.i("CaptureNavigationTest", "roundTrip beforeEnter target=$beforeTargetSelection sentence=$beforeSentenceSelection")
        val beforeScroll = scroll(); val beforeLookup = repo.lookups.size; val beforeProbe = repo.analysis.probes.size
        compose.onNodeWithTag("capture_dictionary_toggle").performClick()
        compose.waitUntil(5_000) { exists("dictionary_detail_list") }
        compose.onNodeWithTag("capture_target").assertDoesNotExist()
        compose.onNodeWithTag("dictionary_detail_back").performClick()
        compose.waitUntil(5_000) { exists("capture_target") && !exists("dictionary_detail") }
        assertEquals(target, field("capture_target")); assertEquals(sentence, field("capture_sentence"))
        assertEquals("Detail return preserves the target selection present at entry", beforeTargetSelection, selection("capture_target"))
        assertEquals("Detail return preserves the sentence selection present at entry", beforeSentenceSelection, selection("capture_sentence"))
        assertEquals(beforeScroll, scroll(), 1f)
        assertEquals(beforeLookup, repo.lookups.size); assertEquals(beforeProbe, repo.analysis.probes.size)
        assertEquals(0, repo.analysis.analysisCalls)
        openDictionary()
        compose.onNodeWithTag("dictionary_detail_sentence").performClick()
        compose.waitUntil(5_000) { exists("capture_sentence") && !exists("dictionary_detail") }
        compose.onNodeWithTag("capture_sentence").assertIsDisplayed().assertIsNotFocused()
        assertEquals(beforeSentenceSelection, selection("capture_sentence"))
        assertEquals(beforeLookup, repo.lookups.size)
    }

    @Test fun restoredDictionaryPageKeepsTheEditedDraftAndItsSelections() {
        val repo = NavigationRepository(); val vm = model(repo); val restoration = StateRestorationTester(compose)
        restoration.setContent { LexiconTheme(AppSettings()) { CapturePanel(capture, emptyList(), emptyList(), vm, {}, {}, {}) } }
        settle(vm)
        compose.onNodeWithTag("capture_target").performScrollTo().performTextReplacement("robust method")
        setSelection("capture_target", TextRange(8, 1))
        assertEquals("The focused target starts with a reverse selection", TextRange(8, 1), selection("capture_target"))
        compose.onNodeWithTag("capture_sentence").performScrollTo().performTextReplacement("A robust method remains useful.")
        assertEquals("Moving focus to the sentence natively collapses the target selection", TextRange(8), selection("capture_target"))
        setSelection("capture_sentence", TextRange(18, 5))
        assertEquals("The focused sentence starts with a reverse selection", TextRange(18, 5), selection("capture_sentence"))
        settle(vm, "robust method"); hideKeyboard()
        compose.onNodeWithTag("capture_dictionary_toggle").performScrollTo()
        val beforeTargetSelection = selection("capture_target")
        val beforeSentenceSelection = selection("capture_sentence")
        assertEquals("Scrolling and hiding the keyboard retain the active sentence selection", TextRange(18, 5), beforeSentenceSelection)
        Log.i("CaptureNavigationTest", "restoration beforeEnter target=$beforeTargetSelection sentence=$beforeSentenceSelection")
        compose.onNodeWithTag("capture_dictionary_toggle").performClick()
        compose.waitUntil(5_000) { exists("dictionary_detail_list") }
        val before = repo.lookups.size
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { repo.lookups.size > before && exists("dictionary_detail_list") }
        compose.onNodeWithTag("dictionary_detail_word").assertTextEquals("robust method  ")
        compose.onNodeWithTag("dictionary_detail_back").performClick()
        compose.waitUntil(5_000) { exists("capture_target") }
        assertEquals("robust method", field("capture_target"))
        assertEquals("A robust method remains useful.", field("capture_sentence"))
        assertEquals("Restoration preserves the target selection present at entry", beforeTargetSelection, selection("capture_target"))
        assertEquals("Restoration preserves the sentence selection present at entry", beforeSentenceSelection, selection("capture_sentence"))
        assertEquals(0, repo.analysis.analysisCalls)
    }

    @Test fun aFreshViewModelWaitsForDictionaryIdentityAndRestoresTheSelectedEntryAndScroll() {
        freshViewModelRestore(changedDictionary = false)
    }

    @Test fun aFreshViewModelWithAnotherDictionaryNeverRestoresThePreviousDictionaryRows() {
        freshViewModelRestore(changedDictionary = true)
    }

    private fun freshViewModelRestore(changedDictionary: Boolean) {
        val statusReady = CompletableDeferred<Unit>()
        fun entries(prefix: String) = listOf("robust", "robustly", "robustness").map { word ->
            DictionaryEntry(word, "", (1..120).joinToString("\n") { "$prefix $word meaning $it. The complete local explanation remains available." })
        }
        val originalEntries = entries("Original")
        val restoredEntries = if (changedDictionary) entries("Replacement") else originalEntries
        val originalRepository = NavigationRepository().apply { lookupAction = { originalEntries } }
        val restoredRepository = NavigationRepository().apply {
            if (changedDictionary) currentDictionary = currentDictionary.copy(id = "replacement", name = "Replacement dictionary")
            statusAction = { statusReady.await(); currentDictionary }
            lookupAction = { restoredEntries }
        }
        var restoring = false
        var activeModel: LexiconViewModel? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            // Unlike Activity.recreate, recreating this composition makes a genuinely new ViewModel.
            val instance = remember { model(if (restoring) restoredRepository else originalRepository) }
            SideEffect { activeModel = instance }
            DisposableEffect(instance) { onDispose { instance.viewModelScope.cancel() } }
            LexiconTheme(AppSettings()) { CapturePanel(capture, emptyList(), emptyList(), instance, {}, {}, {}) }
        }
        compose.waitUntil(5_000) { activeModel?.dictionaryStatus?.id == "first" }
        val initialModel = requireNotNull(activeModel)
        settle(initialModel); openDictionary()
        compose.onNodeWithTag("dictionary_detail_choose_match").performClick()
        compose.onNodeWithTag("dictionary_detail_match_2").performScrollTo().performClick()
        compose.waitUntil(5_000) { exists("dictionary_detail_list") }
        val anchor = DictionaryPresentation.blocks(originalEntries[2].definition)[4]
        val anchorTag = "dictionary_detail_block_${anchor.start}"
        compose.onNodeWithTag("dictionary_detail_list").performScrollToNode(hasTestTag(anchorTag))
        compose.onNodeWithTag(anchorTag).assertTextEquals(anchor.text)
        val before = compose.onNodeWithTag("dictionary_detail_list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("The test must restore a nonzero position", before > 0f)
        try {
            restoring = true
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(5_000) { activeModel !== initialModel && restoredRepository.lookups.isNotEmpty() && activeModel?.lookingUp == false }
            compose.runOnIdle {
                assertNotSame(initialModel, activeModel)
                assertEquals("The restored ViewModel is still waiting for dictionary metadata", "", activeModel?.dictionaryStatus?.id)
            }
            compose.onNodeWithTag("dictionary_detail_loading").assertExists()
            compose.onNodeWithTag("dictionary_detail_list").assertDoesNotExist()
            compose.onNodeWithTag("dictionary_detail_source").assertDoesNotExist()
            compose.onNodeWithTag("dictionary_detail_word").assertDoesNotExist()
            compose.runOnIdle { statusReady.complete(Unit) }
            compose.waitUntil(5_000) { activeModel?.dictionaryStatus?.id == restoredRepository.currentDictionary.id && exists("dictionary_detail_list") }
            if (changedDictionary) {
                compose.onNodeWithTag("dictionary_detail_choose_match").assertTextEquals("1 / 3")
                compose.onNodeWithTag("dictionary_detail_source").assertTextEquals("Replacement dictionary")
                compose.onNodeWithTag("dictionary_detail_block_0").assertTextEquals(DictionaryPresentation.blocks(restoredEntries[0].definition).first().text)
                val position = compose.onNodeWithTag("dictionary_detail_list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                assertEquals(0f, position, .001f)
            } else {
                compose.onNodeWithTag("dictionary_detail_choose_match").assertTextEquals("3 / 3")
                compose.onNodeWithTag(anchorTag).assertTextEquals(anchor.text)
                val after = compose.onNodeWithTag("dictionary_detail_list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                assertEquals(before, after, .001f)
            }
            assertEquals(0, restoredRepository.analysis.analysisCalls)
        } finally { statusReady.complete(Unit) }
    }

    @Test fun cancelledAndCompletedBackEventsPopOnlyTheDictionaryBeforeClosingTheCapture() {
        val repo = NavigationRepository(); val vm = model(repo)
        var visible by mutableStateOf(true)
        compose.setContent { LexiconTheme(AppSettings()) { if (visible) CapturePanel(capture, emptyList(), emptyList(), vm, { visible = false }, {}, {}) } }
        settle(vm); openDictionary()
        var dialog: ComponentDialog? = null
        compose.runOnIdle { dialog = WindowInspector.getGlobalWindowViews().mapNotNull { it.findViewTreeOnBackPressedDispatcherOwner() as? ComponentDialog }.firstOrNull() }
        val dispatcher = requireNotNull(dialog).onBackPressedDispatcher
        val before = repo.lookups.size
        fun begin() {
            compose.runOnIdle {
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
                dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 300f, .6f, BackEventCompat.EDGE_LEFT))
            }
            compose.mainClock.advanceTimeBy(150); compose.waitForIdle()
        }
        begin(); compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.mainClock.advanceTimeBy(250); compose.waitForIdle()
        compose.onNodeWithTag("dictionary_detail").assertExists(); assertTrue(visible)
        begin(); compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitUntil(5_000) { exists("capture_target") && !exists("dictionary_detail") }
        assertTrue(visible); assertEquals(before, repo.lookups.size)
        compose.onNodeWithTag("capture_close").assertIsDisplayed()
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(600)
        compose.waitUntil(5_000) { !visible }
    }

    @Test fun aNewDictionaryAndAChangedQueryNeverReuseThePreviousDetailRows() {
        val response = CompletableDeferred<List<DictionaryEntry>>()
        val repo = NavigationRepository(); val vm = model(repo)
        compose.setContent { LexiconTheme(AppSettings()) { CapturePanel(capture, emptyList(), emptyList(), vm, {}, {}, {}) } }
        settle(vm); openDictionary()
        try {
            compose.runOnIdle {
                repo.currentDictionary = repo.currentDictionary.copy(id = "second", name = "Second local dictionary")
                repo.lookupAction = { response.await() }
                vm.dictionaryImported(repo.currentDictionary)
            }
            compose.onNodeWithTag("dictionary_detail_loading").assertExists()
            compose.onNodeWithTag("dictionary_detail_word").assertDoesNotExist()
            compose.onNodeWithTag("dictionary_detail_source").assertDoesNotExist()
            compose.runOnIdle { response.complete(listOf(DictionaryEntry("robust", "", "A definition from the second dictionary."))) }
            compose.waitUntil(5_000) { exists("dictionary_detail_list") }
            compose.onNodeWithTag("dictionary_detail_source").assertTextEquals("Second local dictionary")
            compose.onNodeWithTag("dictionary_detail_block_0").assertTextEquals("A definition from the second dictionary.")
            compose.onNodeWithTag("dictionary_detail_back").performClick()
            compose.runOnIdle { repo.lookupAction = { word -> listOf(DictionaryEntry(word, "", "Definition for $word")) } }
            compose.onNodeWithTag("capture_target").performScrollTo().performTextReplacement("method")
            settle(vm, "method"); hideKeyboard(); openDictionary()
            compose.onNodeWithTag("dictionary_detail_word").assertTextEquals("method  ")
            compose.onNodeWithTag("dictionary_detail_block_0").assertTextEquals("Definition for method")
        } finally { response.complete(emptyList()) }
    }

    @Test fun readingDictionaryDoesNotChangeTheCachedAnalysisSaveGateOrOriginalOffsets() {
        val repo = NavigationRepository().apply { analysis.availableAction = { CaptureAnalysisTestRepository.ready } }
        val vm = model(repo); var saved: Card? = null
        compose.setContent { LexiconTheme(AppSettings()) { CapturePanel(capture, emptyList(), emptyList(), vm, {}, { saved = it }, {}) } }
        settle(vm)
        compose.onNodeWithTag("generate_card").performScrollTo().assertTextContains("保存已有分析").assertIsEnabled()
        val probes = repo.analysis.probes.size
        openDictionary(); compose.onNodeWithTag("dictionary_detail_back").performClick()
        compose.runOnIdle { assertEquals(probes, repo.analysis.probes.size) }
        compose.onNodeWithTag("generate_card").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { saved != null }
        val input = repo.analysis.savedInputs.single()
        assertEquals(capture.selectedText, input.selectedText); assertEquals(capture.sentence, input.sentence)
        assertEquals(capture.selectionStart, input.selectionStart); assertEquals(capture.selectionEnd, input.selectionEnd)
        assertEquals(capture.bodyHash, input.bodyHash); assertEquals(capture.sourceRef, input.sourceRef)
        assertEquals(listOf(CaptureAnalysisTestRepository.ready.receiptId), repo.analysis.savedReceipts)
        assertEquals(0, repo.analysis.analysisCalls); assertEquals(0, repo.analysis.captureSaves); assertEquals(0, repo.analysis.directCardSaves)
    }

    @Test fun backgroundHeaderAndSystemBackBothReturnToCaptureWithoutStartingResearch() {
        val repo = NavigationRepository(); val vm = model(repo)
        val backgrounds = BackgroundResearchTestRepository(System.currentTimeMillis())
        var visible by mutableStateOf(true)
        compose.setContent { LexiconTheme(AppSettings()) {
            if (visible) CapturePanel(capture, emptyList(), emptyList(), vm, { visible = false }, {}, {},
                backgroundRepository = backgrounds, onAssist = { error("Background must not call the shared AI action") })
        } }
        settle(vm)
        val before = repo.lookups.size
        compose.onNodeWithTag("capture_background").performScrollTo().performClick()
        compose.waitUntil(5_000) { exists("background_query_context") }
        compose.onNodeWithTag("background_continue_reading").assertIsDisplayed()
        compose.onNodeWithTag("background_back_to_capture").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { exists("capture_target") }
        assertTrue(visible); assertEquals(capture.sentence, field("capture_sentence"))
        compose.onNodeWithTag("capture_background").performScrollTo().performClick()
        compose.waitUntil(5_000) { exists("background_query_context") }
        compose.runOnIdle {
            val dialog = WindowInspector.getGlobalWindowViews().mapNotNull { it.findViewTreeOnBackPressedDispatcherOwner() as? ComponentDialog }.first()
            dialog.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitUntil(5_000) { exists("capture_target") && !exists("background_research_panel") }
        assertTrue(visible); assertEquals(before, repo.lookups.size)
        assertTrue(backgrounds.lookupCalls.isEmpty()); assertTrue(backgrounds.explainCalls.isEmpty())
    }

    private class NavigationRepository(val analysis: CaptureAnalysisTestRepository = CaptureAnalysisTestRepository()) : AppRepository by analysis {
        val lookups = mutableListOf<String>()
        var currentDictionary = DictionaryStatus(ready = true, name = "Local navigation fixture", entryCount = 8, id = "first", format = "MDX")
        var statusAction: suspend () -> DictionaryStatus = { currentDictionary }
        var lookupAction: suspend (String) -> List<DictionaryEntry> = { word ->
            listOf(DictionaryEntry(word, "", (1..12).joinToString("\n") { "Meaning $it. A local definition of $word." }))
        }
        override suspend fun lookup(text: String): List<DictionaryEntry> { lookups += text; return lookupAction(text) }
        override suspend fun dictionaryStatus() = statusAction()
    }
}
