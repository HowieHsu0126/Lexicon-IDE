package dev.lexicon.ide.ui

import android.os.SystemClock
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises production state ownership with local synthetic repositories; never invokes DeepSeek. */
@RunWith(AndroidJUnit4::class)
class ReadingStateRegressionTest {
    @get:Rule val compose = createComposeRule()

    private val article = Article("state-article", "state-guid", listOf("test-source"), "Reading state example",
        "https://example.org/state", "", topic = "Science & Technology", sourceName = "Test source", readingMode = ReadingMode.NATIVE)
    // One paragraph keeps focus at index zero when delayed content appears. A focus change must not
    // accidentally repair the missing-body effect dependency that this test guards.
    private val original = "A careful explanation separates observations from interpretations. " +
        "Researchers compare the evidence, consider competing explanations, and describe the limits of their measurements. " +
        "A strong conclusion follows from the available observations rather than confidence alone."
    private val body = ArticleBody(article.id, original, fingerprint(original), 1, Long.MAX_VALUE, "Synthetic test material")

    @Test fun delayedBodyStartsDwellWithoutAnotherInteractionOrFocusChange() {
        val reading = StateRegressionReadingRepository()
        val vm = ReadingViewModel(StateRegressionAppRepository(reading))
        var displayedBody by mutableStateOf<ArticleBody?>(null)
        var visible by mutableStateOf(true)
        compose.setContent {
            if (visible) Workspace(vm, displayedBody) else Text("Reader closed")
        }
        compose.onNodeWithTag("zen_article_text").assertDoesNotExist()
        compose.runOnIdle { displayedBody = body }
        compose.onNodeWithTag("zen_article_text").assertExists()
        // Production snapshots cap active time to wall time. Let that real bound elapse, while the
        // Compose clock advances the actual delay loop; do not fake a measured speed with a clock jump.
        val arrived = SystemClock.uptimeMillis()
        compose.waitUntil(3_000) { SystemClock.uptimeMillis() - arrived >= 1_100 }
        compose.mainClock.advanceTimeBy(1_200)
        compose.runOnIdle { visible = false }
        compose.waitUntil(3_000) { reading.sessions.isNotEmpty() }
        compose.runOnIdle {
            val recorded = reading.sessions.last()
            assertTrue(recorded.activeMillis >= 1_000)
            assertTrue(recorded.wordsRead > 0)
            assertEquals(1, recorded.totalParagraphs)
            assertFalse(recorded.completed)
        }
    }

    @Test fun rapidParagraphFocusChangesDoNotRestartTheOneSecondSessionTick() {
        val reading = StateRegressionReadingRepository()
        val vm = ReadingViewModel(StateRegressionAppRepository(reading))
        val text = "Evidence supports this claim.\n\nContext explains this interpretation."
        val shortBody = body.copy(text = text, contentHash = fingerprint(text))
        var visible by mutableStateOf(true)
        compose.setContent {
            if (visible) Workspace(vm, shortBody) else Text("Reader closed")
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("zen_article_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val offsets = listOf(text.indexOf("Evidence") + 2, text.indexOf("Context") + 2)
        val points = offsets.map { layouts.single().getBoundingBox(it).center }
        compose.mainClock.autoAdvance = false
        try {
            // Let the production wall-clock cap exceed this test's three seconds of active time.
            // The paused Compose clock prevents ticks during this wait.
            val started = SystemClock.uptimeMillis()
            compose.waitUntil(5_000) { SystemClock.uptimeMillis() - started >= 3_300 }
            val observedFocus = mutableListOf<Int>()
            repeat(12) { step ->
                val next = if (step % 2 == 0) 1 else 0
                // Both paragraphs remain visible. Production taps change the exact focus without
                // a capture overlay; distinct event times prevent native double-click selection.
                compose.onNodeWithTag("zen_article_text").performTouchInput {
                    advanceEventTime(600)
                    click(points[next])
                }
                compose.mainClock.advanceTimeBy(250)
                observedFocus += compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().config[FocusedParagraphIndex]
            }
            assertEquals(setOf(0, 1), observedFocus.toSet())
            assertTrue("The reader must really change paragraph focus between one-second ticks",
                observedFocus.zipWithNext().count { (a, b) -> a != b } >= 10)
            compose.runOnIdle { visible = false }
            compose.mainClock.advanceTimeByFrame()
            compose.waitUntil(3_000) { reading.sessions.isNotEmpty() }
            compose.runOnIdle {
                val recorded = reading.sessions.last()
                assertTrue("Rapid focus changes must not cancel active reading time", recorded.activeMillis >= 3_000)
                assertTrue(recorded.wordsRead > 0)
                assertEquals(2, recorded.totalParagraphs)
                assertFalse(recorded.completed)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun cachedB1RestoresTheVariantInsteadOfLabelingOriginalAsAi() {
        val reading = StateRegressionReadingRepository()
        val vm = ReadingViewModel(StateRegressionAppRepository(reading))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Workspace(vm, body) }
        openTools()
        compose.onNodeWithText("分级").reachReadingTool().performClick()
        compose.onNodeWithText("B1").performScrollTo().performClick()
        compose.waitUntil(3_000) { editorText() == StateRegressionReadingRepository.B1_TEXT }
        val requestsBeforeRestore = reading.requests.size
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(3_000) { editorText() == StateRegressionReadingRepository.B1_TEXT }
        val restoredText = editorText()
        compose.runOnIdle {
            assertTrue(reading.cacheReads > 0)
            assertEquals("Restoration must use the existing response", requestsBeforeRestore, reading.requests.size)
            assertEquals(StateRegressionReadingRepository.B1_TEXT, restoredText)
            assertNotEquals(original, restoredText)
        }
        compose.onNodeWithText("AI · B1").assertExists()
    }

    @Test fun restrictedSourceCannotSubmitAnAnswerEvenWithCachedQuestions() {
        val reading = StateRegressionReadingRepository().apply {
            cachedStudyValue = ArticleStudy(article.id, body.contentHash, "Cached analysis.", "B2",
                listOf(MindMapNode("root", null, "Argument", 0)), emptyList(), listOf(StudyParagraph(0, "analysis", "Evidence and limits.")),
                listOf(ReadingQuestion("question", "What supports a conclusion?", "The observed evidence.", listOf(0))), emptyList(), "test", 1)
        }
        val vm = ReadingViewModel(StateRegressionAppRepository(reading))
        compose.setContent { Workspace(vm, body, allowed = false) }
        compose.waitUntil(3_000) { vm.study != null }
        openTools()
        compose.onNodeWithText("检测").reachReadingTool().performClick()
        compose.onNodeWithText("用自己的话回答").performScrollTo().performTextInput("The available evidence supports the conclusion.")
        compose.onNodeWithText("检查理解").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(reading.requests.isEmpty()) }
    }

    @Test fun lateResultFromPreviousArticleDoesNotPublishOrRunItsCallback() {
        val reading = StateRegressionReadingRepository()
        val vm = ReadingViewModel(StateRegressionAppRepository(reading))
        val oldResponse = CompletableDeferred<StudyAssist>()
        reading.pendingResponse = oldResponse
        var oldCallbackRan = false
        compose.setContent { Text(vm.result?.text ?: "没有辅助结果") }
        val firstRequest = StudyRequest("translation", "First article sentence.")
        compose.runOnIdle {
            vm.prepare(article, body, automatic = false, allowed = true)
            vm.assist(firstRequest, article.id) { oldCallbackRan = true }
        }
        compose.waitUntil(3_000) { reading.requests.size == 1 }
        val otherArticle = article.copy(id = "other-article", title = "Another article")
        compose.runOnIdle { vm.prepare(otherArticle, body.copy(articleId = otherArticle.id), automatic = false, allowed = true) }
        oldResponse.complete(StudyAssist("translation", "Old result", "This belongs to the first article.", "test", 1))
        compose.waitUntil(3_000) { vm.busy == null }
        compose.runOnIdle {
            assertNull(vm.result)
            assertNull(vm.resultRequest)
            assertFalse(oldCallbackRan)
            reading.pendingResponse = null
        }
        compose.onNodeWithText("This belongs to the first article.").assertDoesNotExist()
        val currentRequest = StudyRequest("translation", "Current article sentence.")
        compose.runOnIdle { vm.assist(currentRequest, otherArticle.id) }
        compose.waitUntil(3_000) { vm.resultRequest == currentRequest }
        compose.runOnIdle { assertEquals("Response to: Current article sentence.", vm.result?.text) }
    }

    @Test fun newEntryTokenAppliesRequestedModeForSameArticleAndPreservesCaptureOffsets() {
        val vm = ReadingViewModel(StateRegressionAppRepository(StateRegressionReadingRepository()))
        var requestedMode by mutableStateOf(StudyMode.INTENSIVE)
        var entryToken by mutableStateOf(0)
        var captured: Capture? = null
        compose.setContent {
            Workspace(vm, body, initialMode = requestedMode, entryToken = entryToken, onCapture = { captured = it })
        }
        compose.onNodeWithText("精读 · 工具").assertExists()
        val from = original.indexOf("careful")
        fun tapOriginalWord() {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("zen_article_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            compose.onNodeWithTag("zen_article_text").performTouchInput { advanceEventTime(600); click(layouts.single().getBoundingBox(from + 2).center) }
        }
        tapOriginalWord()
        compose.runOnIdle {
            assertEquals("careful", captured?.selectedText)
            assertEquals(from, captured?.selectionStart)
            captured = null
            requestedMode = StudyMode.SKIM
            entryToken++
        }
        compose.onNodeWithText("略读 · 工具").assertExists()
        compose.onNodeWithText("精读 · 工具").assertDoesNotExist()
        assertEquals(original, editorText())
        tapOriginalWord()
        compose.runOnIdle {
            assertEquals("careful", captured?.selectedText)
            assertEquals(from, captured?.selectionStart)
            assertEquals(from + "careful".length, captured?.selectionEnd)
            assertEquals(body.contentHash, captured?.bodyHash)
            assertEquals(article.id, captured?.articleId)
            assertEquals(ContextOrigin.ORIGINAL, captured?.contextOrigin)
        }
    }

    @Test fun dailyFiveMinuteEntryResetsSkimChoicesButClosingToolsPreservesThem() {
        val reading = StateRegressionReadingRepository().apply {
            cachedStudyValue = ArticleStudy(article.id, body.contentHash, "Synthetic reading guide", "B2", emptyList(), emptyList(),
                listOf(StudyParagraph(0, "claim", "The main claim")),
                listOf(ReadingQuestion("focus-question", "What supports the claim?", "The evidence.", listOf(0))),
                emptyList(), "test", 1)
        }
        val repository = StateRegressionAppRepository(reading).apply {
            openedBody = body
            articles.value = listOf(article)
            settings.value = AppSettings(reader = ReaderPreferences(automaticStudy = false))
        }
        compose.setContent { LexiconApp(repository) }
        compose.onNodeWithText("略读 · 5 分钟").performClick()
        compose.onNodeWithText("略读 · 工具").performScrollTo().performClick()
        compose.onNodeWithTag("reader_tab_略读").performClick()
        compose.onNodeWithTag("skim_budget_10").performScrollTo().performClick()
        compose.onNodeWithTag("skim_role_论点").performScrollTo().performClick()
        compose.onNodeWithTag("skim_question_focus-question").performScrollTo().performClick()
        compose.onNodeWithTag("reader_quiet").reachReadingTool().performClick()

        compose.onNodeWithText("略读 · 工具").performScrollTo().performClick()
        compose.onNodeWithTag("skim_budget_10").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("skim_role_论点").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("skim_question_focus-question").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("reader_quiet").reachReadingTool().assertTextContains("显示辅助")
        compose.onNodeWithText("开始略读").reachReadingTool().performClick()
        compose.onNodeWithText("← 退出 Zen").performScrollTo().performClick()

        compose.onNodeWithText("略读 · 5 分钟").performClick()
        compose.onNodeWithText("略读 · 工具").performScrollTo().performClick()
        compose.onNodeWithTag("skim_budget_5").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("skim_budget_10").assertIsNotSelected()
        compose.onNodeWithTag("skim_role_全部").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("skim_question_focus-question").performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag("reader_quiet").reachReadingTool().assertTextContains("无干扰通读")
    }

    @Test fun delayedTenParagraphBodyLocatesSixthThenEighthParagraph() {
        val longBody = longBody()
        val ranges = ParagraphFocus.paragraphs(longBody.text)
        val vm = ReadingViewModel(StateRegressionAppRepository(StateRegressionReadingRepository()))
        var loaded by mutableStateOf<ArticleBody?>(null)
        var entry by mutableStateOf(1)
        var anchor by mutableStateOf(ReaderAnchor(longBody.contentHash, ranges[5].start, ranges[5].endExclusive))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Workspace(vm, loaded, entryToken = entry, initialAnchor = anchor) }
        compose.onNodeWithTag("zen_article_text").assertDoesNotExist()
        compose.runOnIdle { loaded = longBody }
        compose.mainClock.advanceTimeBy(1_000)
        assertParagraphStartVisible(longBody, 5)
        compose.runOnIdle {
            anchor = ReaderAnchor(longBody.contentHash, ranges[7].start, ranges[7].endExclusive)
            entry++
        }
        compose.mainClock.advanceTimeBy(1_000)
        assertParagraphStartVisible(longBody, 7)
        assertEquals(longBody.text, editorText())
        compose.onNodeWithTag("zen_reader_viewport").performTouchInput { swipeUp(durationMillis = 500) }
        compose.waitForIdle()
        val manualPosition = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().positionInRoot.y
        compose.runOnIdle { loaded = null }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { loaded = longBody }
        compose.mainClock.advanceTimeBy(1_000)
        val restoredPosition = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().positionInRoot.y
        assertEquals("Restoring the same body preserves manual scroll instead of replaying the old anchor", manualPosition, restoredPosition, 8f)
    }

    @Test fun staleAnchorOpensChangedBodyAtStartWithoutMatchingRepeatedText() {
        val oldBody = longBody()
        val changed = oldBody.copy(text = "This edition has been updated. " + oldBody.text,
            contentHash = fingerprint("This edition has been updated. " + oldBody.text))
        val eighth = ParagraphFocus.paragraphs(oldBody.text)[7]
        val vm = ReadingViewModel(StateRegressionAppRepository(StateRegressionReadingRepository()))
        compose.setContent { Workspace(vm, changed, entryToken = 1,
            initialAnchor = ReaderAnchor(oldBody.contentHash, eighth.start, eighth.endExclusive)) }
        compose.mainClock.advanceTimeBy(1_000)
        assertParagraphStartVisible(changed, 0)
        compose.onNodeWithTag("reader_anchor_notice").assertIsDisplayed()
        assertEquals(changed.text, editorText())
    }

    @Test fun knowledgeSourceReturnsToExactEighthParagraphInOriginalReader() {
        val longBody = longBody()
        val eighth = ParagraphFocus.paragraphs(longBody.text)[7]
        val reading = UiTestReadingRepository().apply {
            library.value = ReadingLibrary(notes = listOf(ReadingNote("anchored-note", article.id, longBody.contentHash,
                longBody.text.substring(eighth.start, eighth.endExclusive), "Follow this evidence.", listOf("论证"), 1,
                SourceRef(article.title, article.sourceName), eighth.start, eighth.endExclusive)))
        }
        val repository = StateRegressionAppRepository(reading).apply {
            openedBody = longBody
            articles.value = listOf(article)
            settings.value = AppSettings(reader = ReaderPreferences(automaticStudy = false))
        }
        compose.setContent { LexiconApp(repository) }
        compose.onNodeWithText(">_  命令").performClick()
        compose.onNodeWithText("搜索命令…").performTextInput("knowledge")
        compose.onNodeWithText("笔记与表达回顾").performClick()
        compose.onNodeWithTag("note_source_anchored-note").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertParagraphStartVisible(longBody, 7)
        assertEquals(longBody.text, editorText())
    }

    @Test fun changingTheOriginalSnapshotDropsItsOldRewriteButALoadingGapKeepsTheSameRewrite() {
        val reading = StateRegressionReadingRepository()
        val vm = ReadingViewModel(StateRegressionAppRepository(reading))
        var displayedBody by mutableStateOf<ArticleBody?>(body)
        var captured: Capture? = null
        compose.setContent { Workspace(vm, displayedBody, onCapture = { captured = it }) }
        openTools()
        compose.onNodeWithTag("reader_tab_分级").performClick()
        compose.onNodeWithText("B1").performScrollTo().performClick()
        compose.waitUntil(3_000) { editorText() == StateRegressionReadingRepository.B1_TEXT }
        val requests = reading.requests.size
        compose.runOnIdle { displayedBody = null }
        compose.onNodeWithTag("zen_article_text").assertDoesNotExist()
        compose.runOnIdle { displayedBody = body }
        compose.waitUntil(3_000) { editorText() == StateRegressionReadingRepository.B1_TEXT }
        compose.onNodeWithText("AI · B1").assertExists()
        assertEquals(requests, reading.requests.size)

        val updatedText = "Updated observations support a different conclusion and require a new interpretation."
        val updated = body.copy(text = updatedText, contentHash = fingerprint(updatedText))
        compose.runOnIdle { displayedBody = null }
        compose.onNodeWithTag("zen_article_text").assertDoesNotExist()
        compose.runOnIdle { displayedBody = updated }
        compose.waitUntil(3_000) { editorText() == updatedText }
        compose.onNodeWithText("AI · B1").assertDoesNotExist()
        compose.onNodeWithText("精读 · 工具").assertExists()
        assertEquals("A changed snapshot does not trigger an implicit rewrite request", requests, reading.requests.size)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("zen_article_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        compose.onNodeWithTag("zen_article_text").performTouchInput {
            advanceEventTime(600); click(layouts.single().getBoundingBox(2).center)
        }
        compose.runOnIdle {
            assertEquals("Updated", captured?.selectedText)
            assertEquals(updated.contentHash, captured?.bodyHash)
            assertEquals(ContextOrigin.ORIGINAL, captured?.contextOrigin)
        }
    }

    @Test fun aNewBodyUsesANewTimerAndDisposesTheOldSessionWithItsOwnWordCounts() {
        val reading = StateRegressionReadingRepository()
        val vm = ReadingViewModel(StateRegressionAppRepository(reading))
        val replacementText = "Evidence changes this claim.\n\nContext changes its interpretation."
        val replacement = body.copy(text = replacementText, contentHash = fingerprint(replacementText))
        var displayedBody by mutableStateOf<ArticleBody?>(body)
        var visible by mutableStateOf(true)
        compose.setContent { if (visible) Workspace(vm, displayedBody) else Text("Reader closed") }
        compose.onNodeWithTag("zen_article_text").assertExists()
        compose.mainClock.autoAdvance = false
        try {
            waitForReadingWallTime(3_300)
            compose.mainClock.advanceTimeBy(3_300)
            compose.runOnIdle { displayedBody = replacement }
            compose.mainClock.advanceTimeByFrame()
            compose.waitUntil(3_000) { reading.sessions.isNotEmpty() }
            val previous = reading.sessions.last()
            assertTrue(previous.activeMillis >= 3_000)
            assertEquals(1, previous.totalParagraphs)
            assertTrue("Disposal must retain the old article's word counts", previous.wordsRead >= 24)
            waitForReadingWallTime(1_300)
            compose.mainClock.advanceTimeBy(1_300)
            compose.runOnIdle { visible = false }
            compose.mainClock.advanceTimeByFrame()
            compose.waitUntil(3_000) { reading.sessions.map { it.id }.distinct().size == 2 }
            val current = reading.sessions.last()
            assertNotEquals(previous.id, current.id)
            assertTrue("Only new-body reading belongs to the new timer", current.activeMillis in 1_000L..1_999L)
            assertEquals(2, current.totalParagraphs)
            assertTrue(current.wordsRead in 1..8)
            assertFalse(current.completed)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun aNullLoadingFramePausesTheSameSessionWithoutDiscardingItsTextCounts() {
        val reading = StateRegressionReadingRepository()
        val vm = ReadingViewModel(StateRegressionAppRepository(reading))
        var displayedBody by mutableStateOf<ArticleBody?>(body)
        var visible by mutableStateOf(true)
        compose.setContent { if (visible) Workspace(vm, displayedBody) else Text("Reader closed") }
        compose.onNodeWithTag("zen_article_text").assertExists()
        compose.mainClock.autoAdvance = false
        try {
            waitForReadingWallTime(3_300)
            compose.mainClock.advanceTimeBy(3_300)
            compose.runOnIdle { displayedBody = null }
            compose.mainClock.advanceTimeByFrame()
            // Exercise the periodic save while the body is absent. The clock must be paused, but
            // the existing session must retain its own paragraph word counts for that save.
            compose.mainClock.advanceTimeBy(15_500)
            compose.waitUntil(3_000) { reading.sessions.isNotEmpty() }
            val paused = reading.sessions.last()
            assertTrue(paused.activeMillis in 3_000L..3_999L)
            assertEquals(1, paused.totalParagraphs)
            assertTrue(paused.wordsRead >= 24)
            compose.runOnIdle { displayedBody = body }
            compose.mainClock.advanceTimeByFrame()
            waitForReadingWallTime(1_300)
            compose.mainClock.advanceTimeBy(1_300)
            compose.runOnIdle { visible = false }
            compose.mainClock.advanceTimeByFrame()
            compose.waitUntil(3_000) { reading.sessions.size >= 2 }
            val resumed = reading.sessions.last()
            assertEquals(paused.id, resumed.id)
            assertEquals(1, reading.sessions.map { it.id }.distinct().size)
            assertTrue(resumed.activeMillis >= paused.activeMillis + 1_000)
            assertTrue(resumed.activeMillis < 5_000)
            assertEquals(1, resumed.totalParagraphs)
            assertTrue(resumed.wordsRead >= paused.wordsRead)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun completionSurvivesTheSameSnapshotLoadingGapButDoesNotCompleteANewSnapshot() {
        val reading = StateRegressionReadingRepository()
        val vm = ReadingViewModel(StateRegressionAppRepository(reading))
        val firstText = "Evidence supports this claim."
        val first = body.copy(text = firstText, contentHash = fingerprint(firstText))
        val nextText = "New observations support a different interpretation."
        val next = body.copy(text = nextText, contentHash = fingerprint(nextText))
        var displayedBody by mutableStateOf<ArticleBody?>(first)
        var visible by mutableStateOf(true)
        compose.setContent { if (visible) Workspace(vm, displayedBody) else Text("Reader closed") }
        waitForReadingWallTime(3_300)
        compose.mainClock.advanceTimeBy(3_300)
        openTools()
        compose.onNodeWithText("完成本次阅读").reachReadingTool().assertIsEnabled().performClick()
        compose.waitUntil(3_000) { reading.sessions.any { it.completed } }
        val completedId = reading.sessions.last { it.completed }.id
        compose.runOnIdle { displayedBody = null }
        compose.onNodeWithTag("zen_article_text").assertDoesNotExist()
        compose.runOnIdle { displayedBody = first }
        compose.waitUntil(3_000) { editorText() == firstText }
        openTools()
        compose.onNodeWithText("已完成本次阅读").reachReadingTool().assertIsNotEnabled()
        compose.runOnIdle { displayedBody = next }
        compose.waitUntil(3_000) { editorText() == nextText }
        compose.onNodeWithText("已完成本次阅读").assertDoesNotExist()
        compose.onNodeWithText("完成本次阅读").reachReadingTool().assertIsNotEnabled()
        compose.onNodeWithTag("reader_quiet").reachReadingTool().performClick()
        waitForReadingWallTime(1_300)
        compose.mainClock.advanceTimeBy(1_300)
        compose.runOnIdle { visible = false }
        compose.waitUntil(3_000) { reading.sessions.any { it.id != completedId && it.activeMillis >= 1_000 } }
        val newSession = reading.sessions.last { it.id != completedId }
        assertFalse(newSession.completed)
        assertTrue(newSession.wordsRead > 0)
    }

    @Test fun aRewriteDirectoryJumpStaysInTheDisplayedRewriteAndUsesItsParagraphs() {
        val rewritten = (1..4).joinToString("\n\n") { index ->
            "Simplified paragraph $index explains the evidence. " +
                "People compare different ideas and check what each observation means. ".repeat(4)
        }
        val reading = StateRegressionReadingRepository().apply {
            pendingResponse = CompletableDeferred<StudyAssist>().apply {
                complete(StudyAssist("rewrite", "Simplified article", rewritten, "test", 1))
            }
        }
        val vm = ReadingViewModel(StateRegressionAppRepository(reading))
        compose.setContent { Workspace(vm, body) }
        openTools()
        compose.onNodeWithTag("reader_tab_分级").performClick()
        compose.onNodeWithText("B1").performScrollTo().performClick()
        compose.waitUntil(3_000) { editorText() == rewritten }
        compose.onNodeWithText("AI · B1").performScrollTo().performClick()
        compose.onNodeWithTag("reader_tab_略读").performClick()
        compose.onNodeWithTag("skim_directory_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("skim_rendered_paragraph_2").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(rewritten, editorText())
        compose.onNodeWithText("AI · B1").assertExists()
        assertParagraphStartVisible(body.copy(text = rewritten, contentHash = fingerprint(rewritten)), 2)
        assertEquals(1, reading.requests.size)
    }

    private fun waitForReadingWallTime(milliseconds: Long) {
        val start = SystemClock.uptimeMillis()
        compose.waitUntil(milliseconds + 2_000) { SystemClock.uptimeMillis() - start >= milliseconds }
    }

    private fun longBody(): ArticleBody {
        val paragraphs = (1..10).map { index ->
            "Paragraph $index describes the evidence in context. " +
                "Researchers compare competing explanations and identify the limits of their observations. ".repeat(5)
        }
        val text = paragraphs.joinToString("\n\n")
        return body.copy(text = text, contentHash = fingerprint(text))
    }

    private fun assertParagraphStartVisible(currentBody: ArticleBody, index: Int) {
        compose.waitForIdle()
        val field = compose.onNodeWithTag("zen_article_text")
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val offset = ParagraphFocus.paragraphs(currentBody.text)[index].start
        val character = layouts.single().getBoundingBox(offset)
        val fieldPosition = field.fetchSemanticsNode().positionInRoot
        val viewport = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().boundsInRoot
        val top = fieldPosition.y + character.top
        val bottom = fieldPosition.y + character.bottom
        assertTrue("Paragraph ${index + 1} starts at $top above viewport ${viewport.top}", top >= viewport.top)
        assertTrue("Paragraph ${index + 1} ends at $bottom below viewport ${viewport.bottom}", bottom <= viewport.bottom)
    }

    @Composable
    private fun Workspace(
        vm: ReadingViewModel, currentBody: ArticleBody?, allowed: Boolean = true,
        initialMode: StudyMode = StudyMode.INTENSIVE, entryToken: Int = 0, onCapture: (Capture) -> Unit = {},
        initialAnchor: ReaderAnchor? = null,
    ) {
        val settings = AppSettings(theme = ThemeMode.LIGHT, reader = ReaderPreferences(automaticStudy = false))
        LexiconTheme(settings) {
            ReadingWorkspace(article, currentBody, currentBody == null, settings, initialMode, allowed, false, emptyList(), vm,
                onBack = {}, onCapture = onCapture, onRetry = {}, onOpenPage = {}, onSettings = {}, onEvent = { _, _ -> },
                entryToken = entryToken, initialAnchor = initialAnchor)
        }
    }

    private fun openTools() {
        // Use the persistent article header after the initial floating control strip has faded.
        compose.mainClock.advanceTimeBy(3_300)
        compose.waitForIdle()
        compose.onNodeWithText("精读 · 工具").performScrollTo().performClick()
    }

    private fun editorText(): String? = runCatching {
        compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    }.getOrNull()
}

private class StateRegressionReadingRepository : ReadingRepository by UiTestReadingRepository() {
    val sessions = CopyOnWriteArrayList<ReadingSession>()
    val requests = CopyOnWriteArrayList<StudyRequest>()
    var cachedStudyValue: ArticleStudy? = null
    var pendingResponse: CompletableDeferred<StudyAssist>? = null
    var cacheReads = 0
    private val responseCache = mutableMapOf<StudyRequest, StudyAssist>()
    override suspend fun cachedStudy(articleId: String, bodyHash: String) = cachedStudyValue?.takeIf { it.articleId == articleId && it.bodyHash == bodyHash }
    override suspend fun cachedStudies(articleId: String, bodyHash: String) = listOfNotNull(cachedStudy(articleId, bodyHash))
    override suspend fun cachedAssist(request: StudyRequest): StudyAssist? { cacheReads++; return responseCache[request] }
    override suspend fun assist(request: StudyRequest): StudyAssist {
        check(request.aiAllowed) { "Restricted request reached the test transport" }
        requests += request
        val result = pendingResponse?.await() ?: StudyAssist(request.kind, "Synthetic response",
            if (request.kind == "rewrite") B1_TEXT else "Response to: ${request.text}", "test", 1)
        responseCache[request] = result
        return result
    }
    override suspend fun recordSession(session: ReadingSession) { sessions += session }
    companion object { const val B1_TEXT = "People compare facts before making a claim. A useful explanation also says what we still do not know." }
}

private class StateRegressionAppRepository(override val reading: ReadingRepository) : AppRepository {
    override val articles = MutableStateFlow<List<Article>>(emptyList())
    override val sources = MutableStateFlow<List<Source>>(emptyList())
    override val captures = MutableStateFlow<List<Capture>>(emptyList())
    override val cards = MutableStateFlow<List<Card>>(emptyList())
    override val settings = MutableStateFlow(AppSettings())
    override val status = MutableStateFlow("[ready]")
    override val jobs = MutableStateFlow<List<AiJob>>(emptyList())
    var openedBody: ArticleBody? = null
    override suspend fun initialize() = Unit
    override suspend fun sync() = SyncResult(0, 0, emptyList())
    override suspend fun setSourceEnabled(id: String, enabled: Boolean) = Unit
    override suspend fun markArticleRead(id: String) = Unit
    override suspend fun openArticle(id: String): ArticleBody? = openedBody?.takeIf { it.articleId == id }
    override suspend fun classifyPending() = Unit
    override suspend fun saveCapture(capture: Capture) = Unit
    override suspend fun lookup(text: String) = emptyList<DictionaryEntry>()
    override suspend fun updateSettings(settings: AppSettings) { this.settings.value = settings }
    override suspend fun hasApiKey() = false
    override suspend fun setApiKey(key: String) = Unit
    override suspend fun clearApiKey() = Unit
    override suspend fun dictionaryStatus() = DictionaryStatus()
    override suspend fun importDictionary(file: File) = DictionaryStatus()
    override suspend fun analyzeCapture(capture: Capture, deep: Boolean): AnalysisResult = error("Unexpected vocabulary request")
    override suspend fun saveCard(capture: Capture, analysis: AnalysisResult): Card = error("Unexpected vocabulary write")
    override suspend fun updateCard(card: Card) = Unit
    override suspend fun reviewCard(cardId: String, rating: Rating, expectedReps: Int, now: Long) = ReviewOutcome.APPLIED
    override suspend fun exportAnki(destination: File) = destination
    override suspend fun confirmAnkiExport(exportFile: File) = Unit
    override suspend fun exportEvents(destination: File) = destination
    override suspend fun recordEvent(type: String, value: Int) = Unit
}
