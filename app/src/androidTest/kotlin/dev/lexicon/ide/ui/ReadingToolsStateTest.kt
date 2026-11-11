package dev.lexicon.ide.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** State is exercised through the real reader, not a duplicate of its tool-sheet state holder. */
@RunWith(AndroidJUnit4::class)
class ReadingToolsStateTest {
    @get:Rule val compose = createComposeRule()
    private val fixture = ReadingToolsTestFixture()

    @After fun dispose() = fixture.dispose()

    @Test fun eachTabHasItsOwnScrollAndAnUnvisitedTabStartsAtTheTop() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { ReadingToolsTestWorkspace(fixture) }
        compose.awaitToolsStudy(fixture)
        compose.openReadingTools()
        compose.scrollTools("导图", 700f)
        val mapScroll = compose.toolsPosition("导图")
        assertTrue(mapScroll > 100f)
        compose.chooseToolsTab("略读")
        assertEquals(0f, compose.toolsPosition("略读"), 1f)
        compose.onNodeWithTag("skim_directory_toggle").performScrollTo().performClick()
        compose.scrollTools("略读", 100_000f)
        val skimScroll = compose.toolsPosition("略读")
        assertTrue(skimScroll > 100f)
        compose.chooseToolsTab("背景")
        assertEquals(0f, compose.toolsPosition("背景"), 1f)
        compose.chooseToolsTab("导图")
        assertEquals(mapScroll, compose.toolsPosition("导图"), 1f)
        compose.chooseToolsTab("略读")
        assertEquals(skimScroll, compose.toolsPosition("略读"), 1f)
        compose.assertCommonToolsVisible()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("reader_tab_略读").assertIsSelected()
        assertEquals(skimScroll, compose.toolsPosition("略读"), 1f)
        compose.chooseToolsTab("导图")
        assertEquals(mapScroll, compose.toolsPosition("导图"), 1f)
        fixture.assertNoModelWork()
    }

    @Test fun answerAndComparisonDraftsSurviveTabsCloseAndSavedStateRestore() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { ReadingToolsTestWorkspace(fixture) }
        compose.awaitToolsStudy(fixture)
        compose.openReadingTools()
        compose.chooseToolsTab("检测")
        replaceField("用自己的话回答", "The observations support a limited conclusion.")
        compose.onNodeWithText("参考思路").performScrollTo().performClick()
        compose.chooseToolsTab("对照")
        replaceField("另一家媒体的真实标题", "A different account of the same evidence")
        replaceField("媒体名 / 原文链接", "A synthetic publication")
        compose.onNodeWithTag("reader_tools_close").assertIsDisplayed().performClick()
        compose.openReadingTools()
        compose.onNodeWithTag("reader_tab_对照").assertIsSelected()
        assertField("另一家媒体的真实标题", "A different account of the same evidence")
        assertField("媒体名 / 原文链接", "A synthetic publication")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("reader_tab_对照").assertIsSelected()
        assertField("另一家媒体的真实标题", "A different account of the same evidence")
        assertField("媒体名 / 原文链接", "A synthetic publication")
        compose.chooseToolsTab("检测")
        assertField("用自己的话回答", "The observations support a limited conclusion.")
        compose.onNodeWithText("收起提示").performScrollTo().assertIsDisplayed()
        fixture.assertNoModelWork()
    }

    @Test fun sharedRangeDraftSurvivesBackgroundTabAndReopening() {
        compose.setContent { ReadingToolsTestWorkspace(fixture) }
        compose.awaitToolsStudy(fixture)
        compose.openReadingTools()
        expandScope()
        replaceRange("7", "9")
        compose.chooseToolsTab("背景")
        compose.onNodeWithTag("study_scope_first").assertDoesNotExist()
        compose.chooseToolsTab("对照")
        assertRange("7", "9")
        compose.onNodeWithTag("reader_tools_close").performClick()
        compose.openReadingTools()
        assertRange("7", "9")
        compose.chooseToolsTab("导图")
        assertRange("7", "9")
        fixture.assertNoModelWork()
    }

    @Test fun aDifferentBodyKeepsThePanelAndCurrentTabButInvalidatesOldDraftsAndRanges() {
        var displayedBody by mutableStateOf(fixture.body)
        compose.setContent { ReadingToolsTestWorkspace(fixture, currentBody = displayedBody) }
        compose.awaitToolsStudy(fixture)
        compose.openReadingTools()
        compose.chooseToolsTab("检测")
        replaceField("用自己的话回答", "This answer belongs to the previous body.")
        compose.chooseToolsTab("对照")
        replaceField("另一家媒体的真实标题", "Previous body comparison")
        replaceField("媒体名 / 原文链接", "Previous source")
        expandScope()
        replaceRange("7", "9")
        val revised = revisedBody()
        compose.runOnIdle { fixture.addStudy(revised); displayedBody = revised }
        compose.awaitToolsStudy(fixture, revised)
        compose.onNodeWithTag("reader_tools_sheet").assertIsDisplayed()
        compose.onNodeWithTag("reader_tab_对照").assertIsSelected()
        assertField("另一家媒体的真实标题", "")
        assertField("媒体名 / 原文链接", "")
        expandScopeIfNeeded()
        assertNotEquals("7", rangeText("study_scope_first"))
        assertNotEquals("9", rangeText("study_scope_last"))
        compose.chooseToolsTab("检测")
        assertField("用自己的话回答", "")
        fixture.assertNoModelWork()
    }

    @Test fun anExplicitNewEntryClearsToolDraftsAndScrollForTheSameBody() {
        var entry by mutableIntStateOf(0)
        compose.setContent { ReadingToolsTestWorkspace(fixture, entryToken = entry) }
        compose.awaitToolsStudy(fixture)
        compose.openReadingTools()
        compose.scrollTools("导图", 700f)
        assertTrue(compose.toolsPosition("导图") > 100f)
        compose.chooseToolsTab("检测")
        replaceField("用自己的话回答", "An answer from the old entry.")
        compose.chooseToolsTab("对照")
        replaceField("另一家媒体的真实标题", "A comparison from the old entry")
        expandScope()
        replaceRange("7", "9")
        compose.runOnIdle { entry++ }
        compose.onNodeWithTag("reader_tools_sheet").assertDoesNotExist()
        compose.openReadingTools()
        compose.onNodeWithTag("reader_tab_导图").assertIsSelected()
        assertEquals(0f, compose.toolsPosition("导图"), 1f)
        compose.chooseToolsTab("对照")
        assertField("另一家媒体的真实标题", "")
        expandScopeIfNeeded()
        assertNotEquals("7", rangeText("study_scope_first"))
        assertNotEquals("9", rangeText("study_scope_last"))
        compose.chooseToolsTab("检测")
        assertField("用自己的话回答", "")
        fixture.assertNoModelWork()
    }

    @Test fun aSameBodyLoadingRefreshRetainsOpenToolsAndComparisonDraft() {
        var displayedBody by mutableStateOf<ArticleBody?>(fixture.body)
        compose.setContent { ReadingToolsTestWorkspace(fixture, currentBody = displayedBody) }
        compose.awaitToolsStudy(fixture)
        compose.openReadingTools()
        compose.chooseToolsTab("对照")
        replaceField("另一家媒体的真实标题", "Keep this draft during a refresh")
        replaceField("媒体名 / 原文链接", "Synthetic source")
        compose.runOnIdle { displayedBody = null }
        compose.onNodeWithTag("reader_tools_sheet").assertIsDisplayed()
        compose.runOnIdle { displayedBody = fixture.body.copy(cachedAt = fixture.body.cachedAt + 1) }
        compose.awaitToolsStudy(fixture)
        compose.onNodeWithTag("reader_tab_对照").assertIsSelected()
        assertField("另一家媒体的真实标题", "Keep this draft during a refresh")
        assertField("媒体名 / 原文链接", "Synthetic source")
        fixture.assertNoModelWork()
    }

    private fun replaceField(label: String, value: String) {
        compose.onNodeWithText(label).performScrollTo().performTextReplacement(value)
    }

    @Test fun sameBodyLoadingGapPreservesTheOpenDirectoryPageAndAnswerDraft() {
        var displayedBody by mutableStateOf<ArticleBody?>(fixture.body)
        compose.setContent { ReadingToolsTestWorkspace(fixture, currentBody = displayedBody) }
        compose.awaitToolsStudy(fixture)
        compose.openReadingTools()
        compose.chooseToolsTab("略读")
        compose.onNodeWithTag("skim_directory_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("skim_directory_next").performScrollTo().performClick()
        compose.onNodeWithTag("skim_study_paragraph_8").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { displayedBody = null }
        compose.onNodeWithTag("skim_directory_page").assertDoesNotExist()
        compose.runOnIdle { displayedBody = fixture.body }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("skim_study_paragraph_8").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("skim_directory_page").assertTextEquals("2 / 5")
        compose.chooseToolsTab("检测")
        replaceField("用自己的话回答", "Keep this answer across the same source refresh.")
        compose.onNodeWithText("参考思路").performScrollTo().performClick()
        compose.runOnIdle { displayedBody = null }
        compose.onNodeWithText("用自己的话回答").assertDoesNotExist()
        compose.runOnIdle { displayedBody = fixture.body }
        assertField("用自己的话回答", "Keep this answer across the same source refresh.")
        compose.onNodeWithText("收起提示").assertExists()
        fixture.assertNoModelWork()
    }

    @Test fun selectingAnotherCachedScopeWhileAwayClearsThePreviousQuestionDraft() {
        val partial = fixture.addPartialStudy()
        compose.setContent { ReadingToolsTestWorkspace(fixture) }
        compose.awaitToolsStudy(fixture)
        compose.openReadingTools()
        compose.chooseToolsTab("检测")
        replaceField("用自己的话回答", "This answer belongs to the whole-article question.")
        compose.onNodeWithText("参考思路").performScrollTo().performClick()
        compose.chooseToolsTab("导图")
        expandScope()
        compose.onNodeWithTag("study_saved_${ReadingStudyScopes.identity(partial)}").performScrollTo().performClick()
        compose.chooseToolsTab("检测")
        compose.onNodeWithText(partial.questions.single().question).assertExists()
        assertField("用自己的话回答", "")
        compose.onNodeWithText("参考思路").assertExists()
        fixture.assertNoModelWork()
    }

    @Test fun partialStudyDirectoryKeepsSelectedOriginalParagraphsOutsideItsAnalysis() {
        val partial = fixture.addPartialStudy()
        compose.setContent { ReadingToolsTestWorkspace(fixture) }
        compose.awaitToolsStudy(fixture)
        compose.openReadingTools()
        expandScope()
        compose.onNodeWithTag("study_saved_${ReadingStudyScopes.identity(partial)}").performScrollTo().performClick()
        compose.chooseToolsTab("略读")
        compose.onNodeWithTag("skim_budget_10").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("skim_directory_toggle").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("skim_directory_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("skim_study_paragraph_0").assertExists()
        compose.onNodeWithTag("skim_rendered_paragraph_3").assertExists()
        compose.onNodeWithTag("skim_directory_next").performScrollTo().performClick()
        compose.onNodeWithTag("skim_rendered_paragraph_8").performScrollTo().performClick()
        compose.onNodeWithTag("reader_tools_sheet").assertDoesNotExist()
        assertEquals(fixture.body.text, compose.readingToolsOriginal())
        fixture.assertNoModelWork()
    }

    private fun assertField(label: String, expected: String) {
        val text = compose.onNodeWithText(label).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals(expected, text)
    }

    private fun expandScope() = compose.onNodeWithTag("study_scope_toggle").performScrollTo().performClick()

    private fun expandScopeIfNeeded() {
        if (compose.onAllNodesWithTag("study_scope_first").fetchSemanticsNodes().isEmpty()) expandScope()
    }

    private fun replaceRange(first: String, last: String) {
        compose.onNodeWithTag("study_scope_first").performScrollTo().performTextReplacement(first)
        compose.onNodeWithTag("study_scope_last").performScrollTo().performTextReplacement(last)
    }

    private fun assertRange(first: String, last: String) {
        assertEquals(first, rangeText("study_scope_first"))
        assertEquals(last, rangeText("study_scope_last"))
    }

    private fun rangeText(tag: String) =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun revisedBody(): ArticleBody {
        val text = fixture.body.text.replace("local observation", "new observation")
        return fixture.body.copy(text = text, contentHash = fingerprint(text))
    }
}
