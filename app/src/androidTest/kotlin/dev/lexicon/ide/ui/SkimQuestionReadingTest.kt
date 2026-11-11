package dev.lexicon.ide.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Production reader/state behavior with synthetic local studies and no model or publisher calls. */
@RunWith(AndroidJUnit4::class)
class SkimQuestionReadingTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<ReadingViewModel>()
    private val article = Article("skim-questions", "fixture", listOf("test-source"), "Planning a reading room",
        "https://example.org/skim-questions", "", topic = "Education", sourceName = "Self-authored fixture", readingMode = ReadingMode.NATIVE)
    private val repeated = "Families need longer opening hours."
    private val text = repeated + " " +
        "Residents compare the available books and describe the limits of their shared reading space. ".repeat(8) +
        repeated + " Readers compare recent suggestions.\n\nThe committee will review the proposal. Volunteers explain the available choices."
    private val body = ArticleBody(article.id, text, fingerprint(text), 1, Long.MAX_VALUE, "Self-authored fixture")
    private val shared by lazy { ReadingSkimPlanner.sentences(text).last { text.substring(it.start, it.endExclusive) == repeated } }
    private fun study(legacy: Boolean = false): ArticleStudy {
        val evidence = if (legacy) emptyList() else listOf(shared.evidenceRange())
        val other = ReadingSkimPlanner.sentences(text).last()
        return ArticleStudy(article.id, body.contentHash, "A synthetic reading guide", "B2",
            listOf(MindMapNode("root", null, "Reading room")), emptyList(),
            listOf(StudyParagraph(0, "claim", "Families describe the need"), StudyParagraph(1, "evidence", "The committee considers choices")),
            listOf(ReadingQuestion("hours", "What do families need?", "Longer opening hours.", listOf(0), evidence),
                ReadingQuestion("need", "Whose need supports the proposal?", "The families' need.", listOf(0), evidence),
                ReadingQuestion("choices", "What do volunteers explain?", "The available choices.", listOf(1),
                    if (legacy) emptyList() else listOf(other.evidenceRange()))),
            emptyList(), "synthetic/no-network", 1, schemaVersion = if (legacy) 1 else 2)
    }
    private fun reading(legacy: Boolean = false) = SkimQuestionRepository(study(legacy), study())
    private fun model(reading: ReadingRepository) = ReadingViewModel(SkimQuestionAppRepository(reading)).also { models += it }
    @After fun release() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { models.forEach { it.viewModelScope.cancel() } }
    }

    @Composable private fun Workspace(vm: ReadingViewModel, onCapture: (Capture) -> Unit = {}, entryToken: Int = 0) {
        val settings = AppSettings(theme = ThemeMode.LIGHT, reader = ReaderPreferences(automaticStudy = true))
        LexiconTheme(settings) {
            ReadingWorkspace(article, body, false, settings, StudyMode.SKIM, true, false, emptyList(), vm,
                {}, onCapture, {}, {}, {}, { _, _ -> }, entryToken = entryToken, initialBudgetMinutes = 5)
        }
    }
    private fun openTools(label: String = "略读 · 工具") {
        compose.mainClock.advanceTimeBy(3_300)
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.onNodeWithTag("reader_tab_略读").reachReadingTool().performClick()
    }
    private fun chooseBoth() {
        compose.onNodeWithTag("skim_question_hours").performScrollTo().performClick()
        compose.onNodeWithTag("skim_question_need").performScrollTo().performClick()
        compose.onNodeWithTag("skim_question_hours").assertIsSelected()
        compose.onNodeWithTag("skim_question_need").assertIsSelected()
    }
    private fun editorText() = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun tapWord(start: Int) {
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("zen_article_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        val field = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode()
        val tap = layout.single().getBoundingBox(start + 2).center + field.positionInRoot - field.boundsInRoot.topLeft
        compose.onNodeWithTag("zen_article_text").performTouchInput {
            advanceEventTime(600); click(tap)
        }
    }
    private fun assertOffsetVisible(offset: Int) {
        compose.waitForIdle()
        val field = compose.onNodeWithTag("zen_article_text")
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val box = layouts.single().getBoundingBox(offset)
        val origin = field.fetchSemanticsNode().positionInRoot
        val viewport = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("The exact evidence sentence must be inside the viewport", origin.y + box.top >= viewport.top && origin.y + box.bottom <= viewport.bottom)
    }

    @Test fun sharedEvidenceJumpsToTheSecondOccurrenceAndLookupKeepsExactCaptureOffsets() {
        val reading = reading(); val vm = model(reading)
        var captured: Capture? = null
        compose.setContent { Workspace(vm, onCapture = { captured = it }) }
        compose.waitUntil(3_000) { vm.study != null }
        openTools(); chooseBoth()
        compose.onNodeWithTag("reader_quiet").reachReadingTool().performClick()
        openTools()
        compose.onNodeWithTag("reader_quiet").reachReadingTool().assertTextContains("显示辅助")
        compose.onNodeWithTag("skim_question_coverage").performScrollTo().assertTextEquals("已纳入证据 2 / 2 个问题")
        compose.onNodeWithTag("skim_answer_hours_0").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertOffsetVisible(shared.start)
        assertTrue(shared.start > text.indexOf(repeated))
        tapWord(shared.start)
        compose.waitForIdle()
        compose.onNodeWithTag("skim_preview_question_hours").assertIsDisplayed()
        compose.onNodeWithTag("skim_preview_question_need").assertIsDisplayed()
        compose.onNodeWithTag("skim_preview_question_choices").assertDoesNotExist()
        assertNull(captured)
        compose.onNodeWithTag("skim_preview_lookup").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("Families", captured?.selectedText)
            assertEquals(shared.start, captured?.selectionStart)
            assertEquals(shared.start + "Families".length, captured?.selectionEnd)
            assertEquals(body.contentHash, captured?.bodyHash)
            assertEquals(ContextOrigin.ORIGINAL, captured?.contextOrigin)
            captured = null
        }
        compose.onNodeWithTag("skim_preview_question_hours").assertDoesNotExist()
        val direct = text.indexOf("Readers compare recent")
        assertOffsetVisible(direct); tapWord(direct)
        compose.runOnIdle { assertEquals("Readers", captured?.selectedText); assertEquals(direct, captured?.selectionStart) }
        assertEquals(text, editorText())
        assertEquals(0, reading.automaticCalls); assertEquals(0, reading.explicitCalls)
    }

    @Test fun modeSwitchAndSavedStateRestorePreserveMultipleQuestionsAndFilters() {
        val reading = reading(); val vm = model(reading)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Workspace(vm) }
        compose.waitUntil(3_000) { vm.study != null }
        openTools(); chooseBoth()
        compose.onNodeWithTag("skim_budget_10").performScrollTo().performClick()
        compose.onNodeWithTag("skim_role_论点").performScrollTo().performClick()
        compose.onNodeWithTag("skim_answer_need_0").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertOffsetVisible(shared.start)
        // The reader's visible top-edge affordance reveals its normal mode command.
        compose.onNodeWithTag("zen_reader_viewport").performTouchInput { click(Offset(center.x, 12f)) }
        compose.onNodeWithText("略读", substring = false).performClick()
        openTools("精读 · 工具")
        compose.onNodeWithTag("skim_question_hours").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("skim_question_need").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("skim_budget_10").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("skim_role_论点").performScrollTo().assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("skim_question_hours").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("skim_question_need").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("skim_budget_10").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("skim_role_论点").performScrollTo().assertIsSelected()
        compose.onNodeWithText("开始略读").reachReadingTool().performClick()
        openTools()
        compose.onNodeWithTag("skim_question_coverage").performScrollTo().assertTextEquals("已纳入证据 2 / 2 个问题")
        assertEquals(0, reading.automaticCalls); assertEquals(0, reading.explicitCalls)
    }

    @Test fun explicitDailyEntryResetsMultipleChoicesWhileToolDismissalKeepsThem() {
        val reading = reading()
        val app = SkimQuestionAppRepository(reading).apply {
            openedBody = body; articles.value = listOf(article)
            settings.value = AppSettings(reader = ReaderPreferences(automaticStudy = false))
        }
        compose.setContent { LexiconApp(app) }
        compose.onNodeWithText("略读 · 5 分钟").performClick()
        openTools(); chooseBoth()
        compose.onNodeWithTag("skim_budget_10").performScrollTo().performClick()
        compose.onNodeWithTag("skim_role_论点").performScrollTo().performClick()
        compose.onNodeWithText("开始略读").reachReadingTool().performClick()
        openTools()
        compose.onNodeWithTag("skim_question_hours").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("skim_question_need").performScrollTo().assertIsSelected()
        compose.onNodeWithText("开始略读").reachReadingTool().performClick()
        compose.onNodeWithText("← 退出 Zen").performScrollTo().performClick()
        compose.onNodeWithText("略读 · 5 分钟").performClick()
        openTools()
        compose.onNodeWithTag("skim_budget_5").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("skim_budget_10").assertIsNotSelected()
        compose.onNodeWithTag("skim_role_全部").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("skim_question_hours").performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag("skim_question_need").performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag("skim_question_coverage").assertDoesNotExist()
        assertEquals(0, reading.explicitCalls)
    }

    @Test fun legacyQuestionsRemainParagraphOnlyUntilTheUserExplicitlyUpdatesThem() {
        val reading = reading(legacy = true); val vm = model(reading)
        compose.setContent { Workspace(vm) }
        compose.waitUntil(3_000) { vm.study != null }
        openTools()
        compose.onNodeWithTag("skim_question_hours").performScrollTo().performClick()
        compose.onNodeWithTag("skim_coverage_hours").performScrollTo().assertTextEquals("仅有相关段落，尚无精确证据句")
        compose.onNodeWithTag("skim_answer_hours_0").assertDoesNotExist()
        compose.onNodeWithTag("skim_question_coverage").assertTextEquals("已纳入证据 0 / 1 个问题")
        compose.runOnIdle { assertEquals(0, reading.automaticCalls); assertEquals(0, reading.explicitCalls) }
        compose.onNodeWithTag("skim_upgrade_evidence").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(3_000) { vm.study?.schemaVersion == 2 && vm.busy == null }
        compose.onNodeWithTag("skim_question_hours").performScrollTo().assertIsNotSelected().performClick()
        compose.onNodeWithTag("skim_coverage_hours").performScrollTo().assertTextEquals("全部证据句已纳入")
        compose.onNodeWithTag("skim_answer_hours_0").assertExists()
        compose.onNodeWithTag("skim_upgrade_evidence").assertDoesNotExist()
        assertEquals(0, reading.automaticCalls); assertEquals(1, reading.explicitCalls)
    }

    @Test fun rewrittenTextNeverUsesOriginalEvidenceRangesAndKeepsAiCaptureProvenance() {
        val reading = reading(); val vm = model(reading)
        var captured: Capture? = null
        compose.setContent { Workspace(vm, onCapture = { captured = it }) }
        compose.waitUntil(3_000) { vm.study != null }
        openTools(); chooseBoth()
        compose.onNodeWithTag("reader_tab_分级").reachReadingTool().performClick()
        compose.onNodeWithText("B1", substring = false).performScrollTo().performClick()
        compose.waitUntil(3_000) { editorText() == SkimQuestionRepository.REWRITE }
        openTools("AI · B1")
        compose.onNodeWithText("原文问题定位在原文中使用。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("skim_question_hours").assertDoesNotExist()
        compose.onNodeWithTag("skim_answer_hours_0").assertDoesNotExist()
        compose.onNodeWithTag("skim_question_coverage").assertDoesNotExist()
        compose.onNodeWithText("开始略读").reachReadingTool().performClick()
        tapWord(SkimQuestionRepository.REWRITE.indexOf("Families"))
        compose.runOnIdle {
            assertEquals("Families", captured?.selectedText)
            assertEquals(ContextOrigin.AI_GENERATED, captured?.contextOrigin)
            assertEquals(fingerprint(SkimQuestionRepository.REWRITE), captured?.bodyHash)
            assertNotEquals(body.contentHash, captured?.bodyHash)
        }
        compose.onNodeWithTag("skim_preview_question_hours").assertDoesNotExist()
        openTools("AI · B1")
        compose.onNodeWithTag("reader_tab_分级").reachReadingTool().performClick()
        compose.onNodeWithText("原文", substring = false).performScrollTo().performClick()
        compose.waitUntil(3_000) { editorText() == text }
        openTools()
        compose.onNodeWithTag("skim_question_hours").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("skim_question_need").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("skim_answer_hours_0").assertExists()
        assertEquals(1, reading.requests.size); assertEquals("rewrite", reading.requests.single().kind)
    }
    @Test fun changedQuestionContentUnderTheSameIdsClearsSelectionAndAnOpenPreview() {
        val originalStudy = study()
        val newEvidence = ReadingSkimPlanner.sentences(text).last().evidenceRange()
        val updated = originalStudy.copy(questions = originalStudy.questions.map { question ->
            if (question.id == "hours") question.copy(question = "Which choices are explained?", answer = "The available choices.",
                paragraphs = listOf(newEvidence.paragraphIndex), evidence = listOf(newEvidence)) else question
        })
        assertEquals(originalStudy.schemaVersion, updated.schemaVersion)
        assertEquals(originalStudy.questions.map { it.id }, updated.questions.map { it.id })
        val reading = SkimQuestionRepository(originalStudy, updated); val vm = model(reading)
        compose.setContent { Workspace(vm) }
        compose.waitUntil(3_000) { vm.study == originalStudy }
        openTools(); chooseBoth()
        compose.onNodeWithTag("skim_answer_hours_0").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1_000)
        tapWord(shared.start)
        compose.onNodeWithTag("skim_preview_question_hours").assertIsDisplayed()
        compose.runOnIdle { vm.generate(article, body, allowed = true) }
        compose.waitUntil(3_000) { vm.study == updated && vm.busy == null }
        compose.onNodeWithTag("skim_preview_question_hours").assertDoesNotExist()
        compose.onNodeWithTag("skim_preview_question_need").assertDoesNotExist()
        openTools()
        compose.onNodeWithTag("skim_question_hours").performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag("skim_question_need").performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag("skim_question_coverage").assertDoesNotExist()
        assertEquals(1, reading.explicitCalls)
    }
}

private class SkimQuestionRepository(initial: ArticleStudy, private val updated: ArticleStudy) : ReadingRepository by UiTestReadingRepository() {
    var cached = initial
    var automaticCalls = 0
    var explicitCalls = 0
    val requests = CopyOnWriteArrayList<StudyRequest>()
    private val assists = mutableMapOf<StudyRequest, StudyAssist>()
    override suspend fun cachedStudy(articleId: String, bodyHash: String) = cached.takeIf { it.articleId == articleId && it.bodyHash == bodyHash }
    override suspend fun cachedStudies(articleId: String, bodyHash: String) = listOfNotNull(cachedStudy(articleId, bodyHash))
    override suspend fun study(article: Article, body: ArticleBody, aiAllowed: Boolean): ArticleStudy { automaticCalls++; return cached }
    override suspend fun retryStudy(article: Article, body: ArticleBody, aiAllowed: Boolean): ArticleStudy {
        check(aiAllowed); explicitCalls++; cached = updated; return cached
    }
    override suspend fun assist(request: StudyRequest): StudyAssist {
        check(request.aiAllowed && request.kind == "rewrite")
        requests += request
        return StudyAssist("rewrite", "Synthetic rewrite", REWRITE, "synthetic/no-network", 1).also { assists[request] = it }
    }
    override suspend fun cachedAssist(request: StudyRequest) = assists[request]
    companion object { const val REWRITE = "Families ask for more time to read. A clear plan helps them use the room." }
}

/** Same local fixture boundary as StateRegressionAppRepository, which is file-private. */
private class SkimQuestionAppRepository(override val reading: ReadingRepository) : AppRepository {
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
    override suspend fun hasApiKey() = true // Enables opt-in paths; every service above remains local.
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
