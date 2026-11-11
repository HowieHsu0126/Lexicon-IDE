package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.AppSettings
import dev.lexicon.domain.DictionaryEntry
import dev.lexicon.domain.ReaderPreferences
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated presentation checks. No repository, personal dictionary, network, or learning writes. */
@RunWith(AndroidJUnit4::class)
class DictionaryDetailContentUiTest {
    @get:Rule val compose = createComposeRule()
    private val entries = (0..7).map { index -> DictionaryEntry("word$index", "",
        ("Entry$index keeps every source character, including examples and phonetic text /wɜːd/.\r\n").repeat(190)) }

    @Test fun allEightMatchesAreReachableIncludingTheLastOneAndTheEndOfItsDefinition() {
        compose.setContent { Detail() }
        for (index in entries.indices) {
            if (index > 0) choose(index)
            awaitEntry(index)
            compose.onNodeWithTag("dictionary_detail_word").assertTextEquals("word$index  ")
            val last = DictionaryPresentation.blocks(entries[index].definition).last()
            compose.onNodeWithTag("dictionary_detail_list").performScrollToIndex(DictionaryPresentation.blocks(entries[index].definition).size)
            compose.onNodeWithTag("dictionary_detail_block_${last.start}").assertTextEquals(last.text)
            assertFixedExits()
        }
    }

    @Test fun eachMatchKeepsItsOwnReadingPositionWhenSwitchingAwayAndBack() {
        compose.setContent { Detail() }
        awaitEntry(0)
        compose.onNodeWithTag("dictionary_detail_list").performScrollToIndex(5)
        val first = position()
        choose(7); awaitEntry(7)
        assertEquals(0f, position(), .01f)
        compose.onNodeWithTag("dictionary_detail_list").performScrollToIndex(9)
        val last = position()
        choose(0); awaitEntry(0)
        assertEquals(first, position(), .01f)
        choose(7); awaitEntry(7)
        assertEquals(last, position(), .01f)
    }

    @Test fun closeAndRecreationKeepTheSameSelectedMatchAndLazyPosition() {
        var visible by mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Detail(visible = visible, onBack = { visible = false }) }
        awaitEntry(0); choose(7); awaitEntry(7)
        compose.onNodeWithTag("dictionary_detail_list").performScrollToIndex(6)
        val saved = position()
        compose.onNodeWithTag("dictionary_detail_back").performClick()
        compose.onNodeWithTag("dictionary_detail").assertDoesNotExist()
        compose.runOnIdle { visible = true }
        awaitEntry(7)
        assertEquals(saved, position(), .01f)
        restoration.emulateSavedInstanceStateRestore()
        awaitEntry(7)
        assertEquals(saved, position(), .01f)
        compose.onNodeWithTag("dictionary_detail_choose_match").assertTextContains("8 / 8")
    }

    @Test fun aTransientLocalReloadPreservesTheSelectedMatchAndScrollWithoutShowingOldRows() {
        var loading by mutableStateOf(false)
        compose.setContent { Detail(currentEntries = if (loading) emptyList() else entries, loading = loading) }
        awaitEntry(0); choose(7); awaitEntry(7)
        compose.onNodeWithTag("dictionary_detail_list").performScrollToIndex(5)
        val saved = position()
        compose.runOnIdle { loading = true }
        compose.onNodeWithTag("dictionary_detail_loading").assertIsDisplayed()
        compose.onNodeWithTag("dictionary_detail_list").assertDoesNotExist()
        assertFixedExits()
        compose.runOnIdle { loading = false }
        awaitEntry(7)
        assertEquals(saved, position(), .01f)
    }

    @Test fun aChangedResultResetsOldSelectionAndScrollEvenWithTheSameQueryIdentity() {
        var current by mutableStateOf(entries)
        compose.setContent { Detail(currentEntries = current) }
        awaitEntry(0); choose(7); awaitEntry(7)
        compose.onNodeWithTag("dictionary_detail_list").performScrollToIndex(5)
        compose.runOnIdle { current = entries.toMutableList().apply { this[7] = this[7].copy(definition = "Revised last match") } }
        awaitEntry(0)
        assertEquals(0f, position(), .01f)
        compose.onNodeWithTag("dictionary_detail_choose_match").assertTextContains("1 / 8")
        choose(7)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("dictionary_detail_block_0").fetchSemanticsNodes()
            .any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Revised last match" } == true } }
    }

    @Test fun aDifferentCaptureOrDictionaryContextStartsAtTheFirstMatch() {
        var identity by mutableStateOf("original-context")
        compose.setContent { Detail(identity = identity) }
        awaitEntry(0); choose(7); awaitEntry(7)
        compose.onNodeWithTag("dictionary_detail_list").performScrollToIndex(5)
        compose.runOnIdle { identity = "another-context" }
        awaitEntry(0)
        assertEquals(0f, position(), .01f)
        compose.onNodeWithTag("dictionary_detail_choose_match").assertTextContains("1 / 8")
    }

    @Test fun font200KeepsUnclippedExitTargetsFixedWhileTheDefinitionScrolls() {
        compose.setContent { Detail(fontScale = 2f, width = 360.dp, height = 600.dp) }
        awaitEntry(0)
        val before = compose.onNodeWithTag("dictionary_detail_header").getUnclippedBoundsInRoot()
        assertFixedExits()
        compose.onNodeWithTag("dictionary_detail_list").performScrollToIndex(9)
        assertFixedExits()
        assertEquals(before, compose.onNodeWithTag("dictionary_detail_header").getUnclippedBoundsInRoot())
        val content = compose.onNodeWithTag("dictionary_detail_list").getUnclippedBoundsInRoot()
        assertTrue(content.bottom - content.top >= 48.dp)
    }

    @Test fun shortFont200ViewportKeepsExitsAndUsesTheMenuForOtherActions() {
        var back = 0; var closed = 0; var sentence = 0
        compose.setContent { Detail(fontScale = 2f, width = 240.dp, height = 200.dp,
            onBack = { back++ }, onClose = { closed++ }, onSentence = { sentence++ }) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("dictionary_detail_list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("dictionary_detail_list").performScrollToIndex(1)
        awaitEntry(0)
        assertFixedExits()
        val content = compose.onNodeWithTag("dictionary_detail_list").getUnclippedBoundsInRoot()
        assertTrue(content.bottom - content.top >= 48.dp)
        compose.onNodeWithTag("dictionary_detail_choose_match").performClick()
        compose.onNodeWithTag("dictionary_detail_sentence").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, sentence) }
        compose.onNodeWithTag("dictionary_detail_back").performClick()
        compose.onNodeWithTag("dictionary_detail_close").performClick()
        compose.runOnIdle { assertEquals(1, back); assertEquals(1, closed) }
    }

    @Composable
    private fun Detail(
        visible: Boolean = true, identity: String = "dictionary-capture-context", currentEntries: List<DictionaryEntry> = entries,
        loading: Boolean = false, fontScale: Float = 1f, width: Dp = 360.dp, height: Dp = 620.dp,
        onBack: () -> Unit = {}, onClose: () -> Unit = {}, onSentence: () -> Unit = {},
    ) {
        LexiconTheme(AppSettings()) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.size(width, height)) {
                    DictionaryDetailContent(visible, identity, currentEntries, "Synthetic dictionary", ReaderPreferences(sizeSp = 14f, lineHeight = 1.5f),
                        onBack, onSentence, onClose, loading = loading)
                }
            }
        }
    }

    private fun choose(index: Int) {
        compose.onNodeWithTag("dictionary_detail_choose_match").performClick()
        compose.onNodeWithTag("dictionary_detail_match_$index").performScrollTo().performClick()
    }

    private fun awaitEntry(index: Int) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(SemanticsMatcher("realized definition block") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("dictionary_detail_block_") == true
            }).fetchSemanticsNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains("Entry$index") } == true
            }
        }
    }

    private fun position(): Float = compose.onNodeWithTag("dictionary_detail_list").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun assertFixedExits() {
        val outer = compose.onNodeWithTag("dictionary_detail").getUnclippedBoundsInRoot()
        val header = compose.onNodeWithTag("dictionary_detail_header").getUnclippedBoundsInRoot()
        listOf("dictionary_detail_back", "dictionary_detail_close").forEach { tag ->
            val node = compose.onNodeWithTag(tag).assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("$tag needs a complete 48dp touch area", bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
            assertTrue("$tag must remain fully inside the fixed header", bounds.left >= header.left && bounds.right <= header.right &&
                bounds.top >= header.top && bounds.bottom <= header.bottom)
            assertTrue("$tag must not be clipped by the viewport", bounds.left >= outer.left && bounds.right <= outer.right &&
                bounds.top >= outer.top && bounds.bottom <= outer.bottom)
        }
    }
}
