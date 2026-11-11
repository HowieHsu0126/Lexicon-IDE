package dev.lexicon.ide.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
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

/** Actual capture-panel interactions with synthetic local responses; no network, keys or production database. */
@RunWith(AndroidJUnit4::class)
class DictionaryLookupUiTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<LexiconViewModel>()
    private val capture = Capture("dictionary-capture", target = "alpha", selectedText = "alpha",
        sentence = "Alpha is the first letter.", contextOrigin = ContextOrigin.USER_SUPPLIED, createdAt = 1)
    private fun model(repo: DictionaryLookupTestRepository) = LexiconViewModel(repo).also { models += it }
    private fun entry(word: String, definition: String) = listOf(DictionaryEntry(word, "", definition))

    @After fun dispose() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { models.forEach { it.viewModelScope.cancel() } }
    }

    private fun show(repo: DictionaryLookupTestRepository): LexiconViewModel {
        val vm = model(repo)
        compose.setContent { LexiconTheme(AppSettings()) { CapturePanel(capture, emptyList(), emptyList(), vm, {}, {}, {}) } }
        return vm
    }
    private fun settle(vm: LexiconViewModel, target: String = "alpha", id: String = capture.id) {
        compose.waitUntil(5_000) { vm.dictionaryLookupFor(id, target)?.let { !it.loading } == true }
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
    }

    @Test fun editingTheWordHidesItsPreviousDefinitionUntilTheNewLookupCompletes() {
        val response = CompletableDeferred<List<DictionaryEntry>>()
        val repo = DictionaryLookupTestRepository().apply { query = { word, _ ->
            if (word == "alpha") entry(word, "First definition") else response.await()
        } }
        val vm = show(repo); settle(vm)
        try {
            compose.onNodeWithTag("capture_dictionary_definition_0").assertTextEquals("First definition")
            compose.onNodeWithTag("capture_target").performScrollTo().performTextReplacement("beta")
            compose.onNodeWithTag("capture_dictionary_definition_0").assertDoesNotExist()
            compose.onNodeWithTag("capture_dictionary_lookup_loading").assertExists()
            compose.onNodeWithTag("capture_dictionary_lookup_error").assertDoesNotExist()
            compose.onNodeWithTag("capture_dictionary_empty").assertDoesNotExist()
            compose.runOnIdle { response.complete(entry("beta", "Second definition")) }
            settle(vm, "beta")
            compose.onNodeWithTag("capture_dictionary_word_0").assertTextEquals("beta  ")
            compose.onNodeWithTag("capture_dictionary_definition_0").assertTextEquals("Second definition")
            compose.onNodeWithText("First definition").assertDoesNotExist()
        } finally { response.complete(emptyList()) }
    }

    @Test fun aLocalLookupFailureHasAnInlineRetryAndNeverBecomesAGlobalNotice() {
        val response = CompletableDeferred<List<DictionaryEntry>>()
        var failing = true
        val repo = DictionaryLookupTestRepository().apply { query = { word, _ ->
            if (failing) error("Synthetic dictionary failure") else response.await()
        } }
        val vm = show(repo); settle(vm)
        try {
            compose.onNodeWithTag("capture_panel").assertExists()
            compose.onNodeWithTag("capture_dictionary_lookup_error").assertTextEquals("本地查词暂不可用，请重试")
            compose.onNodeWithTag("capture_dictionary_empty").assertDoesNotExist()
            compose.runOnIdle { assertNull(vm.notice); failing = false }
            val before = repo.lookups.size
            compose.onNodeWithTag("capture_dictionary_retry").performScrollTo().performClick()
            compose.onNodeWithTag("capture_dictionary_lookup_error").assertDoesNotExist()
            compose.onNodeWithTag("capture_dictionary_lookup_loading").assertExists()
            compose.waitUntil(5_000) { repo.lookups.size > before }
            compose.runOnIdle { response.complete(entry("alpha", "Recovered definition")) }
            settle(vm)
            compose.onNodeWithTag("capture_dictionary_definition_0").assertTextEquals("Recovered definition")
            compose.onNodeWithTag("capture_dictionary_retry").assertDoesNotExist()
            compose.runOnIdle { assertNull(vm.notice); assertEquals("alpha", repo.lookups.last()) }
        } finally { response.complete(emptyList()) }
    }

    @Test fun aDifferentCaptureAndAnImportedDictionaryEachDiscardThePreviousVisibleDefinition() {
        val secondCapture = CompletableDeferred<List<DictionaryEntry>>()
        val newDictionary = CompletableDeferred<List<DictionaryEntry>>()
        var stage = 0
        val repo = DictionaryLookupTestRepository().apply { query = { _, _ -> when (stage) {
            0 -> entry("alpha", "First capture definition")
            1 -> secondCapture.await()
            else -> newDictionary.await()
        } } }
        val vm = model(repo)
        var visibleCapture by mutableStateOf(capture)
        compose.setContent { LexiconTheme(AppSettings()) { CapturePanel(visibleCapture, emptyList(), emptyList(), vm, {}, {}, {}) } }
        settle(vm)
        try {
            compose.onNodeWithTag("capture_dictionary_definition_0").assertTextEquals("First capture definition")
            compose.runOnIdle { stage = 1; visibleCapture = capture.copy(id = "second-capture") }
            compose.waitUntil(5_000) { vm.dictionaryLookupFor("second-capture", "alpha")?.loading == true }
            compose.onNodeWithTag("capture_dictionary_definition_0").assertDoesNotExist()
            compose.runOnIdle { secondCapture.complete(entry("alpha", "Second capture definition")) }
            settle(vm, id = "second-capture")
            compose.onNodeWithTag("capture_dictionary_definition_0").assertTextEquals("Second capture definition")
            compose.runOnIdle {
                stage = 2; repo.selected = repo.selected.copy(id = "imported", name = "New synthetic dictionary")
                vm.dictionaryImported(repo.selected)
            }
            compose.onNodeWithTag("capture_dictionary_source").assertDoesNotExist()
            compose.onNodeWithTag("capture_dictionary_definition_0").assertDoesNotExist()
            compose.onNodeWithTag("capture_dictionary_lookup_loading").assertExists()
            compose.runOnIdle { newDictionary.complete(entry("alpha", "New dictionary definition")) }
            settle(vm, id = "second-capture")
            compose.onNodeWithTag("capture_dictionary_source").assertTextEquals("New synthetic dictionary")
            compose.onNodeWithTag("capture_dictionary_definition_0").assertTextEquals("New dictionary definition")
        } finally { secondCapture.complete(emptyList()); newDictionary.complete(emptyList()) }
    }

    @Test fun restoringTheEditedDraftRequeriesTheRestoredWord() {
        val repo = DictionaryLookupTestRepository().apply { query = { word, _ -> entry(word, "Definition of $word") } }
        val vm = model(repo); val restoration = StateRestorationTester(compose)
        restoration.setContent { LexiconTheme(AppSettings()) { CapturePanel(capture, emptyList(), emptyList(), vm, {}, {}, {}) } }
        settle(vm)
        compose.onNodeWithTag("capture_target").performScrollTo().performTextReplacement("beta")
        settle(vm, "beta")
        val before = repo.lookups.size
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { repo.lookups.size > before && vm.dictionaryLookupFor(capture.id, "beta")?.loading == false }
        compose.onNodeWithTag("capture_target").assertTextContains("beta")
        compose.onNodeWithTag("capture_dictionary_word_0").assertTextEquals("beta  ")
        compose.onNodeWithTag("capture_dictionary_definition_0").assertTextEquals("Definition of beta")
        assertEquals("beta", repo.lookups.last())
    }

    @Test fun longDefinitionsOpenAsCompleteBlocksAndTheCloseActionRemainsReachable() {
        val definition = (1..24).joinToString("\n") { "Sense $it. A complete local dictionary definition." }
        val repo = DictionaryLookupTestRepository().apply { query = { _, _ -> entry("alpha", definition) } }
        val vm = model(repo)
        var visible by mutableStateOf(true)
        compose.setContent { LexiconTheme(AppSettings(theme = ThemeMode.DARK)) {
            if (visible) CapturePanel(capture, emptyList(), emptyList(), vm, { visible = false }, {}, {})
        } }
        settle(vm)
        compose.onNodeWithTag("capture_dictionary_definition_0").assertTextEquals(DictionaryPresentation.preview(definition).text)
        compose.onNodeWithTag("capture_dictionary_toggle").performScrollTo().assertTextContains("全部释义").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("dictionary_detail_list").fetchSemanticsNodes().isNotEmpty() }
        val observed = mutableListOf<String>()
        DictionaryPresentation.blocks(definition).forEach { block ->
            val tag = "dictionary_detail_block_${block.start}"
            compose.onNodeWithTag("dictionary_detail_list").performScrollToNode(hasTestTag(tag))
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            observed += layout.layoutInput.text.text
            assertFalse(layout.didOverflowHeight)
            assertTrue((0 until layout.lineCount).all { layout.getLineLeft(it) >= -1f && layout.getLineRight(it) <= layout.size.width + 1f })
        }
        assertEquals(definition, observed.joinToString(""))
        compose.onNodeWithTag("dictionary_detail_close").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { !visible }
        compose.onNodeWithTag("capture_panel").assertDoesNotExist()
        compose.runOnIdle { assertNull(vm.dictionaryLookupFor(capture.id, "alpha")); assertFalse(vm.lookingUp); assertTrue(vm.dictionary.isEmpty()) }
    }
}
