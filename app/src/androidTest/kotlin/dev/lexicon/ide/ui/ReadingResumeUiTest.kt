package dev.lexicon.ide.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
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
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class ReadingResumeUiTest {
    @get:Rule val compose = createComposeRule()
    private val body = resumeFixtureBody()
    private val article = Article(body.articleId, "resume-guid", listOf("synthetic"), "Reading continuation test",
        "https://example.org/continuation", "", sourceName = "Synthetic local source", readingMode = ReadingMode.NATIVE)
    private val controls = ReadingResumeControls(StudyMode.SKIM, true, 10, "证据", "略读")
    private val models = mutableListOf<ReadingViewModel>()
    private fun previous() = ReadingResumes.create(ReadingResumeLease(body.articleId, body.contentHash, 1, null), 1, body, 7, controls, 100)
    private fun model(repository: ReadingRepository): ReadingViewModel {
        val app = object : AppRepository by VocabularyWorkspaceApp(repository) { override suspend fun hasApiKey() = true }
        return ReadingViewModel(app).also { models += it }
    }
    @After fun dispose() { InstrumentationRegistry.getInstrumentation().runOnMainSync { models.forEach { it.viewModelScope.cancel() } } }

    @Test fun delayedResumeDoesNotCheckpointTheTopAndRestoresControlsWithToolsClosed() {
        val gate = CompletableDeferred<Unit>(); val resumes = ResumeTestRepository(previous())
        resumes.beginAction = { id, hash -> gate.await(); resumes.acquire(id, hash) }
        val reading = ResumeUiReadingRepository(resumes); val vm = model(reading)
        compose.setContent { Workspace(vm) }
        compose.waitUntil { resumes.beginCalls.isNotEmpty() }
        assertFalse(positionReady()); compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertTrue(resumes.checkpoints.isEmpty()); assertEquals(7, resumes.current?.paragraphIndex) }
        gate.complete(Unit)
        compose.waitUntil(5000) { positionReady() }
        compose.mainClock.advanceTimeBy(700)
        compose.waitUntil(5000) { resumes.checkpoints.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(7, resumes.current?.paragraphIndex); assertEquals(controls, resumes.current?.controls)
            assertEquals(0, reading.automaticCalls)
        }
        compose.onNodeWithTag("reader_tools_sheet").assertDoesNotExist()
        openTools("略读 · 工具")
        compose.onNodeWithTag("skim_budget_10").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("reader_quiet").reachReadingTool().assertTextContains("显示辅助")
    }

    @Test fun savedStateRecreationKeepsManualScrollInsteadOfRepeatingTheOldResumeJump() {
        val resumes = ResumeTestRepository(previous()); val vm = model(ResumeUiReadingRepository(resumes))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Workspace(vm) }
        compose.waitUntil(5000) { positionReady() }
        compose.onNodeWithTag("zen_reader_viewport").performTouchInput { swipeUp(durationMillis = 400) }
        compose.waitUntil(5000) { positionReady() && paragraph() > 7 }
        val before = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().positionInRoot.y
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5000) { positionReady() }
        val after = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().positionInRoot.y
        assertEquals(before, after, 8f)
        compose.runOnIdle { assertEquals(1, resumes.beginCalls.size) }
    }

    @Test fun anExplicitFiveMinuteStartUsesTheTopAndResetsPriorSkimControls() {
        val resumes = ResumeTestRepository(previous()); val vm = model(ResumeUiReadingRepository(resumes))
        var entry by mutableIntStateOf(1); var kind by mutableStateOf(ReadingEntryKind.CONTINUE)
        compose.setContent { Workspace(vm, entry = entry, kind = kind, initialMode = StudyMode.SKIM) }
        compose.waitUntil(5000) { positionReady() && paragraph() == 7 }
        compose.runOnIdle { entry++; kind = ReadingEntryKind.START }
        compose.waitUntil(5000) { positionReady() && paragraph() == 0 }
        compose.mainClock.advanceTimeBy(700)
        compose.waitUntil(5000) { resumes.current?.generation == 3L }
        compose.runOnIdle {
            assertEquals(StudyMode.SKIM, resumes.current?.controls?.mode); assertEquals(5, resumes.current?.controls?.budgetMinutes)
            assertEquals(false, resumes.current?.controls?.quiet); assertEquals("全部", resumes.current?.controls?.roleFilter)
        }
    }

    @Test fun anOriginalNoteAnchorTakesPriorityOverTheSavedParagraphAndMode() {
        val resumes = ResumeTestRepository(previous()); val vm = model(ResumeUiReadingRepository(resumes))
        val target = ParagraphFocus.paragraphs(body.text)[3]
        compose.setContent { Workspace(vm, kind = ReadingEntryKind.ANCHOR, initialMode = StudyMode.SKIM,
            anchor = ReaderAnchor(body.contentHash, target.start, target.endExclusive)) }
        compose.waitUntil(5000) { positionReady() }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("zen_article_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val text = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode()
        val viewport = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().boundsInRoot
        val y = text.positionInRoot.y + layouts.single().getLineTop(layouts.single().getLineForOffset(target.start))
        assertTrue("The exact original anchor must be in the viewport", y >= viewport.top && y < viewport.bottom)
        compose.onNodeWithText("精读 · 工具").assertExists()
        compose.mainClock.advanceTimeBy(700)
        compose.waitUntil(5000) { resumes.checkpoints.any { it.paragraphIndex == 3 } }
        compose.runOnIdle {
            assertEquals(StudyMode.INTENSIVE, resumes.current?.controls?.mode)
            assertEquals("The explicit original target must become the persisted checkpoint", 3, resumes.current?.paragraphIndex)
            assertEquals("The checkpoint writer must receive the explicit paragraph", 3, resumes.checkpoints.last().paragraphIndex)
        }
        assertEquals("Reading tools and the visible paragraph must share the explicit target", 3,
            compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().config[FocusedParagraphIndex])
    }

    @Test fun optionalRepositoryAndSameBodyLoadingGapKeepTheExistingReaderPosition() {
        val vm = model(UiTestReadingRepository()); var loaded by mutableStateOf<ArticleBody?>(null)
        compose.setContent { Workspace(vm, current = loaded, kind = ReadingEntryKind.START) }
        compose.onNodeWithTag("zen_article_text").assertDoesNotExist()
        compose.runOnIdle { loaded = body }; compose.waitUntil(5000) { positionReady() }
        compose.onNodeWithTag("zen_reader_viewport").performTouchInput { swipeUp(durationMillis = 400) }
        compose.waitUntil(5000) { positionReady() && paragraph() > 0 }
        val before = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().positionInRoot.y
        compose.runOnIdle { loaded = null }; compose.onNodeWithTag("zen_article_text").assertDoesNotExist()
        compose.runOnIdle { loaded = body }; compose.waitUntil(5000) { positionReady() }
        assertEquals(before, compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().positionInRoot.y, 8f)
    }

    @Test fun anAiVariantNeverReportsAnOrdinaryOriginalViewport() {
        val seen = mutableListOf<ReaderViewportPosition>()
        compose.setContent { LexiconTheme(AppSettings()) {
            ZenReader(article, body, false, AppSettings(), false, false, {}, {}, {}, {}, {}, { _, _ -> },
                display = ReaderDisplay(aiLevel = "B1", checkpointKey = "variant"), onViewportPosition = { seen += it })
        } }
        compose.onNodeWithTag("zen_article_text").assertExists()
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle { assertTrue(seen.isNotEmpty()); assertTrue(seen.all { it.paragraphIndex == null }) }
        assertFalse(positionReady())
    }

    @Test fun aResolvedLeaseDoesNotCountReadingTimeBeforeTheViewportCanBePositioned() {
        val resumes = ResumeTestRepository(previous()); val reading = ResumeUiReadingRepository(resumes); val vm = model(reading)
        var visible by mutableStateOf(true)
        compose.setContent {
            if (visible) Box(Modifier.fillMaxWidth().height(0.dp)) { Workspace(vm) }
            else Text("Reader closed")
        }
        // The stored SKIM label proves controls have applied; the zero-height viewport still prevents positioning.
        compose.waitUntil(5000) { compose.onAllNodesWithText("略读 · 工具").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(positionReady())
        val started = SystemClock.uptimeMillis()
        compose.waitUntil(3000) { SystemClock.uptimeMillis() - started >= 1100 }
        compose.mainClock.advanceTimeBy(1200)
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(reading.sessions.isEmpty()); assertTrue(resumes.checkpoints.isEmpty()) }
    }

    @Composable private fun Workspace(vm: ReadingViewModel, current: ArticleBody? = body, entry: Int = 1,
        kind: ReadingEntryKind = ReadingEntryKind.CONTINUE, initialMode: StudyMode = StudyMode.INTENSIVE, anchor: ReaderAnchor? = null) {
        val settings = AppSettings(theme = ThemeMode.LIGHT, reader = ReaderPreferences(automaticStudy = true))
        LexiconTheme(settings) {
            ReadingWorkspace(article, current, current == null, settings, initialMode, true, false, emptyList(), vm,
                {}, {}, {}, {}, {}, { _, _ -> }, entryToken = entry, initialBudgetMinutes = 5, initialAnchor = anchor, entryKind = kind)
        }
    }
    private fun positionReady() = runCatching { compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().config[ReaderPositionReadyKey] }.getOrDefault(false)
    private fun paragraph() = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().config[ReaderViewportParagraphKey]
    private fun openTools(label: String) { compose.onNodeWithText(label).performScrollTo().performClick() }
}

private class ResumeUiReadingRepository(override val resumes: ReadingResumeRepository) : ReadingRepository by UiTestReadingRepository() {
    var automaticCalls = 0
    val sessions = CopyOnWriteArrayList<ReadingSession>()
    override suspend fun study(article: Article, body: ArticleBody, aiAllowed: Boolean): ArticleStudy { automaticCalls++; error("Unexpected automatic model work") }
    override suspend fun recordSession(session: ReadingSession) { sessions += session }
}
