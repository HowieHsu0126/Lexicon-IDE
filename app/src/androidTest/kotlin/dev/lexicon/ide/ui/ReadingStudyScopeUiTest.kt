package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Uses synthetic source text and local repositories. A UI test is not an online AI success sample. */
@RunWith(AndroidJUnit4::class)
class ReadingStudyScopeUiTest {
    @get:Rule val compose = createComposeRule()
    private val article = Article("scope-ui", "scope-guid", listOf("scope-source"), "A long reading example",
        "https://example.org/scope", "", topic = "Science & Technology", sourceName = "Synthetic source", readingMode = ReadingMode.NATIVE)
    private val text = (1..90).joinToString("\n\n") { n ->
        "Paragraph $n describes a robust method and its supporting evidence. Researchers compare observations before drawing a conclusion."
    }
    private val body = ArticleBody(article.id, text, fingerprint(text), 1, Long.MAX_VALUE, "Synthetic test material")

    @Test fun currentRangeAfterParagraphEightyHasExactPreviewAndRequiresExplicitAction() {
        val calls = mutableListOf<StudyScope>()
        val expected = ReadingStudyScopes.create(body, 83, 86)
        compose.setContent { Controls(onAnalyze = { calls += it }) }
        compose.onNodeWithTag("study_scope_toggle").performClick()
        compose.onNodeWithTag("study_scope_first").assertTextContains("84")
        compose.onNodeWithTag("study_scope_last").assertTextContains("86")
        compose.onNodeWithTag("study_scope_count").performScrollTo()
            .assertTextEquals("第 84–86 段 · 3 段 · ${expected.endExclusive - expected.start} 字符")
        val spans = ParagraphFocus.paragraphs(text)
        compose.onNodeWithTag("study_scope_preview_first").performScrollTo()
            .assertTextEquals(text.substring(spans[83].start, spans[83].endExclusive))
        compose.onNodeWithTag("study_scope_preview_last").performScrollTo()
            .assertTextEquals(text.substring(spans[85].start, spans[85].endExclusive))
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithTag("study_scope_analyze").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(expected), calls) }
        compose.onNodeWithTag("study_scope_full").assertDoesNotExist()
    }

    @Test fun invalidRangeNeverClampsUserInputOrCallsAnalysis() {
        var calls = 0
        compose.setContent { Controls(onAnalyze = { calls++ }) }
        compose.onNodeWithTag("study_scope_toggle").performClick()
        setRange("86", "84")
        compose.onNodeWithTag("study_scope_error").performScrollTo().assertTextEquals("结束段落不能早于开始段落。")
        compose.onNodeWithTag("study_scope_analyze").performScrollTo().assertIsNotEnabled()
        setRange("91", "91")
        compose.onNodeWithTag("study_scope_first").assertTextContains("91")
        compose.onNodeWithTag("study_scope_error").performScrollTo().assertTextEquals("段落范围为 1–90。")
        compose.onNodeWithTag("study_scope_analyze").performScrollTo().assertIsNotEnabled()
        setRange("notint", "86")
        compose.onNodeWithTag("study_scope_error").performScrollTo().assertTextEquals("段落编号需为整数。")
        compose.onNodeWithTag("study_scope_analyze").performScrollTo().assertIsNotEnabled()
        setRange("1", "90")
        compose.onNodeWithTag("study_scope_error").performScrollTo().assertTextContains("超过单次范围", substring = true)
        compose.onNodeWithTag("study_scope_analyze").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun rangeDraftAndExpandedPanelSurviveSavedStateRestore() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Controls() }
        compose.onNodeWithTag("study_scope_toggle").performClick()
        setRange("82", "85")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("study_scope_first").assertTextContains("82")
        compose.onNodeWithTag("study_scope_last").assertTextContains("85")
        compose.onNodeWithTag("study_scope_count").performScrollTo().assertTextContains("第 82–85 段", substring = true)
    }

    @Test fun savedScopeCanSwitchDuringRequestAndDoesNotNeedAiPermission() {
        val cached = scopeStudy(83, 86)
        val chosen = mutableListOf<ArticleStudy>()
        var requests = 0
        compose.setContent { Controls(saved = listOf(cached), busy = true, allowed = false,
            onChoose = { chosen += it }, onAnalyze = { requests++ }) }
        compose.onNodeWithTag("study_scope_toggle").performClick()
        compose.onNodeWithTag("study_saved_${ReadingStudyScopes.identity(cached)}").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("study_scope_analyze").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf(cached), chosen); assertEquals(0, requests) }
    }

    @Test fun matchingRangeOpensSavedAnalysisWithoutRepeatingRequest() {
        val cached = scopeStudy(83, 86)
        val chosen = mutableListOf<ArticleStudy>()
        var requests = 0
        compose.setContent { Controls(saved = listOf(cached), allowed = false,
            onChoose = { chosen += it }, onAnalyze = { requests++ }) }
        compose.onNodeWithTag("study_scope_toggle").performClick()
        compose.onNodeWithTag("study_scope_analyze").performScrollTo().assertTextContains("查看已有分析").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(cached), chosen); assertEquals(0, requests) }
    }

    @Test fun longOriginalDoesNotStartAutomaticFullStudyOrEnableFullRewrite() {
        val reading = ScopeUiReadingRepository()
        val vm = ReadingViewModel(ScopeUiAppRepository(reading))
        compose.setContent { Workspace(vm, automatic = true) }
        compose.waitUntil(3_000) { vm.currentArticle != null && !vm.loadingStudies }
        assertEquals(text, editorText())
        openTools()
        compose.onNodeWithTag("study_long_article_hint").assertExists()
        compose.onNodeWithTag("reader_generate_study").assertDoesNotExist()
        compose.onNodeWithTag("reader_tab_分级").reachReadingTool().performClick()
        compose.onNodeWithTag("study_rewrite_limit").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("B1").assertIsNotEnabled()
        compose.onNodeWithText("B2").assertIsNotEnabled()
        compose.runOnIdle {
            assertTrue(reading.fullRequests.isEmpty())
            assertTrue(reading.rangeRequests.isEmpty())
            assertTrue(reading.assistRequests.isEmpty())
        }
    }

    @Test fun scopedHighlightAfterParagraphEightyCapturesGlobalOriginalOffsets() {
        val cached = scopeStudy(83, 86)
        val reading = ScopeUiReadingRepository(listOf(cached))
        val vm = ReadingViewModel(ScopeUiAppRepository(reading))
        var captured: Capture? = null
        compose.setContent { Workspace(vm, onCapture = { captured = it }) }
        compose.waitUntil(3_000) { vm.study == cached }
        assertEquals(text, editorText())
        openTools()
        compose.onNodeWithTag("study_scope_notice").assertTextContains("第 84–86 段", substring = true)
        compose.onNodeWithTag("reader_tab_重点").reachReadingTool().performClick()
        val highlighted = cached.highlights.single()
        compose.onNodeWithText(highlighted.quote).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(highlighted.quote, captured?.selectedText)
            assertEquals(highlighted.start, captured?.selectionStart)
            assertEquals(highlighted.end, captured?.selectionEnd)
            assertEquals(body.contentHash, captured?.bodyHash)
            assertEquals(ContextOrigin.ORIGINAL, captured?.contextOrigin)
            assertEquals(article.id, captured?.articleId)
            assertTrue(captured!!.selectionStart!! > text.indexOf("Paragraph 80"))
            assertTrue(reading.fullRequests.isEmpty())
            assertTrue(reading.rangeRequests.isEmpty())
        }
        assertEquals(text, editorText())
    }

    @Test fun switchingSavedScopesUpdatesQuestionsAndCefrWithoutReplacingOriginal() {
        val newer = scopeStudy(83, 86, cefr = "C1", created = 2)
        val earlier = scopeStudy(1, 4, cefr = "B1", created = 1)
        val reading = ScopeUiReadingRepository(listOf(newer, earlier))
        val vm = ReadingViewModel(ScopeUiAppRepository(reading))
        compose.setContent { Workspace(vm) }
        compose.waitUntil(3_000) { vm.study == newer }
        openTools()
        compose.onNodeWithText(newer.summary).assertExists()
        compose.onNodeWithTag("reader_tab_检测").reachReadingTool().performClick()
        compose.onNodeWithText("用自己的话回答").performScrollTo().performTextInput("An answer belonging to the previous scope.")
        compose.onNodeWithText("参考思路").performScrollTo().performClick()
        compose.onNodeWithText(newer.questions.single().answer).assertExists()
        compose.onNodeWithTag("study_scope_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("study_saved_${ReadingStudyScopes.identity(earlier)}").performScrollTo().performClick()
        compose.onNodeWithTag("study_scope_toggle").performScrollTo().performClick()
        compose.onNodeWithText(earlier.questions.single().question).performScrollTo().assertIsDisplayed()
        assertEquals("", compose.onNodeWithText("用自己的话回答").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithText("An answer belonging to the previous scope.").assertDoesNotExist()
        compose.onNodeWithText(earlier.questions.single().answer).assertDoesNotExist()
        compose.onNodeWithTag("reader_tab_分级").reachReadingTool().performClick()
        compose.onNodeWithTag("study_cefr_scope").performScrollTo().assertTextEquals("当前：原文 · 所选段落估计 B1")
        compose.onNodeWithTag("reader_tab_略读").reachReadingTool().performClick()
        compose.onNodeWithTag("skim_scope_notice").performScrollTo().assertTextContains("第 2–4 段", substring = true)
        compose.onNodeWithTag("skim_role_论点").performScrollTo().performClick()
        compose.onNodeWithText("开始略读").reachReadingTool().performClick()
        assertEquals(text, editorText())
        compose.runOnIdle { assertTrue(reading.fullRequests.isEmpty()); assertTrue(reading.rangeRequests.isEmpty()); assertTrue(reading.assistRequests.isEmpty()) }
    }

    @Test fun selectedCachedScopeRestoresWithANewViewModelWithoutAnyAiRequest() {
        val newer = scopeStudy(83, 86, created = 2)
        val earlier = scopeStudy(1, 4, created = 1)
        val reading = ScopeUiReadingRepository(listOf(newer, earlier))
        val repository = ScopeUiAppRepository(reading)
        var visibleVm: ReadingViewModel? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val vm = remember { ReadingViewModel(repository) }
            visibleVm = vm
            Workspace(vm)
        }
        compose.waitUntil(3_000) { visibleVm?.study == newer }
        val previousVm = visibleVm
        openTools()
        compose.onNodeWithTag("study_scope_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("study_saved_${ReadingStudyScopes.identity(earlier)}").performScrollTo().performClick()
        compose.waitUntil(3_000) { visibleVm?.study == earlier }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(3_000) { visibleVm !== previousVm && visibleVm?.study == earlier && visibleVm?.loadingStudies == false }
        // The selected assistance restores independently of whether the transient sheet is open.
        if (compose.onAllNodesWithTag("study_scope_toggle").fetchSemanticsNodes().isEmpty()) openTools()
        if (compose.onAllNodesWithTag("study_saved_${ReadingStudyScopes.identity(earlier)}").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("study_scope_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("study_saved_${ReadingStudyScopes.identity(earlier)}").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("study_scope_notice").performScrollTo().assertTextContains("第 2–4 段", substring = true)
        compose.runOnIdle { assertTrue(reading.fullRequests.isEmpty()); assertTrue(reading.rangeRequests.isEmpty()) }
    }

    @Test fun scopeControlsKeepRangeAndActionReachableAtTwoHundredPercentFont() {
        compose.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(currentDensity.density, 2f)) { Controls() }
        }
        compose.onNodeWithTag("study_scope_toggle").performClick()
        compose.onNodeWithTag("study_scope_first").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("study_scope_last").assertIsDisplayed()
        compose.onNodeWithTag("study_scope_analyze").performScrollTo().assertIsDisplayed().assertIsEnabled()
        val action = compose.onNodeWithTag("study_scope_analyze").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(action.left >= root.left && action.right <= root.right)
    }

    @Composable
    private fun Controls(saved: List<ArticleStudy> = emptyList(), busy: Boolean = false, allowed: Boolean = true,
        onChoose: (ArticleStudy) -> Unit = {}, onAnalyze: (StudyScope) -> Unit = {}) {
        LexiconTheme(AppSettings(theme = ThemeMode.LIGHT)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                ReadingStudyScopeControls(body, 83, null, saved, loading = false, busy = busy, allowed = allowed,
                    onChoose = onChoose, onAnalyze = onAnalyze, onAnalyzeFull = { error("Unexpected full request") })
            }
        }
    }

    @Composable
    private fun Workspace(vm: ReadingViewModel, automatic: Boolean = false, onCapture: (Capture) -> Unit = {}) {
        val settings = AppSettings(theme = ThemeMode.LIGHT, reader = ReaderPreferences(automaticStudy = automatic))
        LexiconTheme(settings) {
            ReadingWorkspace(article, body, false, settings, StudyMode.INTENSIVE, true, false, emptyList(), vm,
                onBack = {}, onCapture = onCapture, onRetry = {}, onOpenPage = {}, onSettings = {}, onEvent = { _, _ -> })
        }
    }

    private fun setRange(first: String, last: String) {
        compose.onNodeWithTag("study_scope_first").performScrollTo().performTextReplacement(first)
        compose.onNodeWithTag("study_scope_last").performScrollTo().performTextReplacement(last)
    }

    private fun openTools() {
        compose.mainClock.advanceTimeBy(3_300)
        compose.waitForIdle()
        compose.onNodeWithText("精读 · 工具").performScrollTo().performClick()
    }

    private fun editorText() = compose.onNodeWithTag("zen_article_text")
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun scopeStudy(first: Int, end: Int, cefr: String = "B2", created: Long = 1): ArticleStudy {
        val scope = ReadingStudyScopes.create(body, first, end)
        val spans = ParagraphFocus.paragraphs(body.text)
        val sentence = ReadingSkimPlanner.sentences(text).first { it.paragraphIndex == first }
        val quote = text.substring(sentence.start, sentence.endExclusive)
        return ArticleStudy(article.id, body.contentHash, "A guide to paragraph ${first + 1} through $end.", cefr,
            nodes = listOf(MindMapNode("root", null, "Selected evidence", first)),
            highlights = listOf(StudyHighlight("sentence", quote, sentence.start, sentence.endExclusive, "sentence", "An original sentence from the selected range.")),
            paragraphs = (first until end).map { StudyParagraph(it, "claim", "Evidence reported in paragraph ${it + 1}.") },
            questions = listOf(ReadingQuestion("shared-question-id", "What does paragraph ${first + 1} explain?",
                "It explains the method discussed in paragraph ${first + 1}.", listOf(first), listOf(sentence.evidenceRange()))),
            backgrounds = emptyList(), model = "test-fixture", createdAt = created, schemaVersion = 2, scope = scope)
            .also { ReadingStudyScopes.validateStudy(it, body); assertEquals(spans[first].start, scope.start) }
    }
}

private class ScopeUiReadingRepository(private val cached: List<ArticleStudy> = emptyList()) : ReadingRepository by UiTestReadingRepository() {
    val fullRequests = CopyOnWriteArrayList<ArticleBody>()
    val rangeRequests = CopyOnWriteArrayList<StudyScope>()
    val assistRequests = CopyOnWriteArrayList<StudyRequest>()
    override suspend fun cachedStudy(articleId: String, bodyHash: String) = cached.firstOrNull { it.articleId == articleId && it.bodyHash == bodyHash && it.scope == null }
    override suspend fun cachedStudies(articleId: String, bodyHash: String) = cached.filter { it.articleId == articleId && it.bodyHash == bodyHash }
    override suspend fun study(article: Article, body: ArticleBody, aiAllowed: Boolean): ArticleStudy {
        fullRequests += body
        error("Unexpected full article request")
    }
    override suspend fun studyRange(article: Article, body: ArticleBody, scope: StudyScope, aiAllowed: Boolean, explicitRetry: Boolean): ArticleStudy {
        rangeRequests += scope
        error("Unexpected range request")
    }
    override suspend fun assist(request: StudyRequest): StudyAssist {
        assistRequests += request
        error("Unexpected assistance request")
    }
}

private class ScopeUiAppRepository(override val reading: ReadingRepository) : AppRepository {
    override val articles = MutableStateFlow<List<Article>>(emptyList())
    override val sources = MutableStateFlow<List<Source>>(emptyList())
    override val captures = MutableStateFlow<List<Capture>>(emptyList())
    override val cards = MutableStateFlow<List<Card>>(emptyList())
    override val settings = MutableStateFlow(AppSettings())
    override val status = MutableStateFlow("[ready]")
    override val jobs = MutableStateFlow<List<AiJob>>(emptyList())
    override suspend fun initialize() = Unit
    override suspend fun sync() = SyncResult(0, 0, emptyList())
    override suspend fun setSourceEnabled(id: String, enabled: Boolean) = Unit
    override suspend fun markArticleRead(id: String) = Unit
    override suspend fun openArticle(id: String): ArticleBody? = null
    override suspend fun classifyPending() = Unit
    override suspend fun saveCapture(capture: Capture) = Unit
    override suspend fun lookup(text: String) = emptyList<DictionaryEntry>()
    override suspend fun updateSettings(settings: AppSettings) { this.settings.value = settings }
    override suspend fun hasApiKey() = true
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
