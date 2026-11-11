package dev.lexicon.ide.ui

import android.view.KeyEvent
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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

/** Production reader and local synthetic content only; these checks never call a model or the network. */
@RunWith(AndroidJUnit4::class)
class ReadingToolsLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val fixture = ReadingToolsTestFixture()

    @After fun dispose() = fixture.dispose()

    @Test fun headerTabsAndCommonActionsStayVisibleAtBothEndsOfLongContent() {
        compose.setContent { ReadingToolsTestWorkspace(fixture) }
        compose.awaitToolsStudy(fixture)
        compose.openReadingTools()
        compose.assertCommonToolsVisible()
        val header = compose.onNodeWithTag("reader_tools_header").fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNodeWithTag("reader_tools_tabs").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("reader_tools_footer").fetchSemanticsNode().boundsInRoot
        assertTrue("The fixture must actually require vertical scrolling", compose.toolsMaximum("导图") > 500f)

        compose.scrollTools("导图", 100_000f)
        assertTrue(compose.toolsPosition("导图") > 500f)
        compose.assertCommonToolsVisible()
        assertEquals(header, compose.onNodeWithTag("reader_tools_header").fetchSemanticsNode().boundsInRoot)
        assertEquals(tabs, compose.onNodeWithTag("reader_tools_tabs").fetchSemanticsNode().boundsInRoot)
        assertEquals(footer, compose.onNodeWithTag("reader_tools_footer").fetchSemanticsNode().boundsInRoot)
        val scrolled = compose.toolsPosition("导图")
        compose.onNodeWithTag("reader_quiet").performClick()
        compose.onNodeWithTag("reader_tools_sheet").assertDoesNotExist()
        compose.openReadingTools()
        compose.onNodeWithTag("reader_quiet").assertTextContains("显示辅助")
        assertEquals(scrolled, compose.toolsPosition("导图"), 1f)
        fixture.assertNoModelWork()
    }

    @Test fun font200KeepsPrimaryCloseQuietAndCompletionVisibleWithoutContentScroll() {
        compose.setContent { ReadingToolsTestWorkspace(fixture, fontScale = 2f) }
        compose.awaitToolsStudy(fixture)
        compose.openReadingTools()
        assertEquals(0f, compose.toolsPosition("导图"), 1f)
        compose.assertCommonToolsVisible()
        assertFullTouchAreas()
        compose.onNodeWithTag("reader_complete").assertIsNotEnabled()
        compose.scrollTools("导图", 100_000f)
        compose.assertCommonToolsVisible()
        assertFullTouchAreas()
        compose.onNodeWithTag("reader_tools_primary").performClick()
        compose.onNodeWithTag("reader_tools_sheet").assertDoesNotExist()
        assertEquals(fixture.body.text, compose.readingToolsOriginal())
        fixture.assertNoModelWork()
    }

    @Test fun skimHasOneFixedStartActionAndReturnsToTheSameOriginal() {
        compose.setContent { ReadingToolsTestWorkspace(fixture) }
        compose.awaitToolsStudy(fixture)
        compose.openReadingTools()
        compose.chooseToolsTab("略读")
        compose.onAllNodesWithText("开始略读").assertCountEquals(1)
        compose.onNodeWithTag("reader_tools_primary").assertTextContains("开始略读").assertIsDisplayed()
        compose.onNodeWithTag("skim_directory_toggle").performScrollTo().performClick()
        compose.scrollTools("略读", 100_000f)
        compose.assertCommonToolsVisible()
        compose.onNodeWithTag("reader_tools_primary").performClick()
        compose.onNodeWithTag("reader_tools_sheet").assertDoesNotExist()
        compose.onNodeWithText("略读 · 工具").assertExists()
        assertEquals(fixture.body.text, compose.readingToolsOriginal())
        fixture.assertNoModelWork()
    }

    @Test fun closedZenContainsNoToolChromeAndCloseDoesNotCompleteReading() {
        compose.setContent { ReadingToolsTestWorkspace(fixture) }
        compose.awaitToolsStudy(fixture)
        listOf("reader_tools_header", "reader_tools_tabs", "reader_tools_footer", "reader_tools_primary")
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.openReadingTools()
        compose.onNodeWithTag("reader_complete").assertIsNotEnabled()
        compose.onNodeWithTag("reader_tools_close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reader_tools_sheet").assertDoesNotExist()
        assertEquals(fixture.body.text, compose.readingToolsOriginal())
        compose.openReadingTools()
        compose.onNodeWithTag("reader_complete").assertIsNotEnabled()
        fixture.assertNoModelWork()
    }

    @Test fun typingWithTheRealImeKeepsAnEditableViewportAndBackOnlyDismissesTheKeyboard() {
        compose.setContent { ReadingToolsTestWorkspace(fixture) }
        compose.awaitToolsStudy(fixture)
        compose.openReadingTools()
        compose.chooseToolsTab("对照")
        val input = compose.onNodeWithText("另一家媒体的真实标题")
        input.performScrollTo().performClick().performTextInput("A title typed with the keyboard")
        compose.waitUntil(5_000) {
            keyboardVisible() && runCatching {
                input.assertIsDisplayed()
                compose.onNodeWithTag("reader_tools_primary").assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
        input.assertIsFocused().assertIsDisplayed()
        assertEquals("A title typed with the keyboard",
            input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("reader_tools_header").assertIsDisplayed()
        compose.onNodeWithTag("reader_tools_close").assertIsDisplayed()
        compose.onNodeWithTag("reader_tools_primary").assertIsDisplayed()
        val content = compose.onNodeWithTag("reader_tools_content_对照").getUnclippedBoundsInRoot()
        assertTrue("The keyboard must leave a usable content viewport", content.bottom - content.top >= 48.dp)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { !keyboardVisible() }
        compose.onNodeWithTag("reader_tools_sheet").assertIsDisplayed()
        assertEquals("A title typed with the keyboard",
            input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("reader_tools_close").performClick()
        compose.onNodeWithTag("reader_tools_sheet").assertDoesNotExist()
        assertEquals(fixture.body.text, compose.readingToolsOriginal())
        fixture.assertNoModelWork()
    }

    @Test fun shortFont200LayoutKeepsContentUsableAndExposesSecondaryActionsThroughOverflow() {
        var quietActions = 0
        var completeActions = 0
        var primaryActions = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                LexiconTheme(AppSettings(theme = ThemeMode.LIGHT)) {
                    Box(Modifier.width(360.dp).height(360.dp)) {
                        ReadingToolsLayout(
                            stateHolder = rememberSaveableStateHolder(), modifier = Modifier.fillMaxSize(),
                            mode = StudyMode.INTENSIVE, tab = "导图", quiet = false, completed = false, canComplete = true,
                            onTab = {}, onClose = {}, onAppearance = {}, onPrimary = { primaryActions++ },
                            onQuiet = { quietActions++ }, onComplete = { completeActions++ },
                        ) {
                            repeat(40) { Text("Synthetic paragraph $it keeps this content scrollable.") }
                        }
                    }
                }
            }
        }
        listOf("reader_tools_header", "reader_tools_tabs", "reader_tools_close", "reader_tools_primary", "reader_tools_more")
            .forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        val content = compose.onNodeWithTag("reader_tools_content_导图").getUnclippedBoundsInRoot()
        assertTrue("A compact tool layout must retain a 48 dp content viewport", content.bottom - content.top >= 48.dp)
        assertTrue(compose.toolsMaximum("导图") > 0f)
        compose.scrollTools("导图", 100_000f)
        compose.onNodeWithTag("reader_tools_close").assertIsDisplayed()
        compose.onNodeWithTag("reader_tools_primary").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reader_tools_more").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reader_quiet").assertIsDisplayed().assertIsEnabled()
        assertTrue(compose.onNodeWithTag("reader_quiet").getUnclippedBoundsInRoot().let { it.bottom - it.top } >= 48.dp)
        compose.onNodeWithTag("reader_quiet").performClick()
        compose.onNodeWithTag("reader_tools_more").performClick()
        compose.onNodeWithTag("reader_complete").assertIsDisplayed().assertIsEnabled()
        assertTrue(compose.onNodeWithTag("reader_complete").getUnclippedBoundsInRoot().let { it.bottom - it.top } >= 48.dp)
        compose.onNodeWithTag("reader_complete").performClick()
        compose.runOnIdle {
            assertEquals(1, primaryActions)
            assertEquals(1, quietActions)
            assertEquals(1, completeActions)
        }
    }

    @Test fun anExtremelyShortWindowKeepsActionsReachableAndRestoresItsDraftAcrossLayoutChanges() {
        var height by mutableStateOf(360.dp)
        compose.setContent {
            LexiconTheme(AppSettings()) {
                Box(Modifier.width(360.dp).height(height)) {
                    ReadingToolsLayout(
                        stateHolder = rememberSaveableStateHolder(), modifier = Modifier.fillMaxSize(),
                        mode = StudyMode.INTENSIVE, tab = "对照", quiet = false, completed = false, canComplete = true,
                        onTab = {}, onClose = {}, onAppearance = {}, onPrimary = {}, onQuiet = {}, onComplete = {},
                    ) {
                        var draft by rememberSaveable { mutableStateOf("") }
                        OutlinedTextField(draft, { draft = it }, label = { Text("布局草稿") })
                        repeat(12) { Text("Additional synthetic content $it.") }
                    }
                }
            }
        }
        val input = compose.onNodeWithText("布局草稿")
        input.performScrollTo().performTextReplacement("Preserve this draft across a short window.")
        compose.runOnIdle { height = 180.dp }
        assertEquals("Preserve this draft across a short window.",
            input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        // This exceptional height intentionally uses the documented whole-sheet scroll fallback.
        compose.onNodeWithTag("reader_tools_primary").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reader_quiet").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reader_complete").performScrollTo().assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("reader_tools_content_对照").getUnclippedBoundsInRoot().let { it.bottom - it.top } >= 48.dp)
        compose.runOnIdle { height = 360.dp }
        assertEquals("Preserve this draft across a short window.",
            input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("reader_tools_close").assertIsDisplayed()
        compose.onNodeWithTag("reader_tools_primary").assertIsDisplayed()
    }

    private fun assertFullTouchAreas() {
        val sheet = compose.onNodeWithTag("reader_tools_sheet").getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag("reader_tools_footer").getUnclippedBoundsInRoot()
        listOf("reader_tools_primary", "reader_quiet", "reader_complete", "reader_tools_close").forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$tag needs a 48 dp vertical touch area: $bounds", bounds.bottom - bounds.top >= 48.dp)
            assertTrue("$tag needs a 48 dp horizontal touch area: $bounds", bounds.right - bounds.left >= 48.dp)
            assertTrue("$tag is clipped by the sheet: $bounds outside $sheet",
                bounds.left >= sheet.left && bounds.top >= sheet.top && bounds.right <= sheet.right && bounds.bottom <= sheet.bottom)
            if (tag != "reader_tools_close") assertTrue("$tag must stay inside the fixed footer",
                bounds.top >= footer.top && bounds.bottom <= footer.bottom)
        }
    }

    private fun keyboardVisible(): Boolean {
        var visible = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            visible = WindowInspector.getGlobalWindowViews().any {
                it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            }
        }
        return visible
    }
}

/** Shared test harness, while every repository implementation remains private to these synthetic tests. */
internal class ReadingToolsTestFixture {
    val article = Article("tools-synthetic", "tools-guid", listOf("tools-source"), "Evidence and interpretation",
        "https://example.org/tools", "", topic = "Science & Technology", sourceName = "Synthetic local source",
        readingMode = ReadingMode.NATIVE)
    private val text = (1..35).joinToString("\n\n") { number ->
        "Paragraph $number presents evidence from a local observation. Researchers compare the result with another explanation before drawing a conclusion."
    }
    val body = ArticleBody(article.id, text, fingerprint(text), 1, Long.MAX_VALUE, "Synthetic test material")
    private val repository = ReadingToolsSyntheticReadingRepository()
    val vm = ReadingViewModel(ReadingToolsSyntheticAppRepository(repository))

    init { addStudy(body) }

    fun addStudy(current: ArticleBody) {
        val paragraphs = ParagraphFocus.paragraphs(current.text)
        repository.studies += ArticleStudy(article.id, current.contentHash,
            "The overview distinguishes an observation from an interpretation. ".repeat(14).trim(), "B2",
            listOf(MindMapNode("root", null, "Evidence and interpretation", 0)) + (1..17).map {
                MindMapNode("node$it", "root", "Evidence branch $it and the limits of this conclusion", it)
            }, emptyList(), paragraphs.indices.map { StudyParagraph(it, "evidence", "Evidence in paragraph ${it + 1}.") },
            listOf(ReadingQuestion("question", "What supports the article's main conclusion?",
                "The conclusion follows from the reported observations and their stated limits.", listOf(0))),
            emptyList(), "synthetic", 1)
    }

    fun assertNoModelWork() = assertEquals("Navigation and drafts must not start model work", 0, repository.modelCalls)

    fun addPartialStudy(): ArticleStudy {
        val full = repository.studies.last { it.bodyHash == body.contentHash }
        return full.copy(scope = ReadingStudyScopes.create(body, 0, 3), schemaVersion = 2,
            nodes = full.nodes.filter { (it.paragraph ?: 0) < 3 }, paragraphs = full.paragraphs.take(3),
            questions = full.questions.map { it.copy(question = "How does the first part support the conclusion?",
                evidence = listOf(ReadingSkimPlanner.sentences(body.text).first().evidenceRange())) }, createdAt = 2)
            .also { ReadingStudyScopes.validateStudy(it, body); repository.studies += it }
    }

    fun dispose() = InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.viewModelScope.cancel() }
}

@Composable
internal fun ReadingToolsTestWorkspace(
    fixture: ReadingToolsTestFixture,
    currentBody: ArticleBody? = fixture.body,
    entryToken: Int = 0,
    fontScale: Float = 1f,
) {
    val density = LocalDensity.current
    val settings = AppSettings(theme = ThemeMode.LIGHT, reader = ReaderPreferences(automaticStudy = false))
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        LexiconTheme(settings) {
            ReadingWorkspace(fixture.article, currentBody, currentBody == null, settings, StudyMode.INTENSIVE,
                true, false, emptyList(), fixture.vm, onBack = {}, onCapture = {}, onRetry = {}, onOpenPage = {},
                onSettings = {}, onEvent = { _, _ -> }, entryToken = entryToken)
        }
    }
}

internal fun ComposeContentTestRule.awaitToolsStudy(fixture: ReadingToolsTestFixture, body: ArticleBody = fixture.body) {
    waitUntil(5_000) { fixture.vm.study?.bodyHash == body.contentHash && !fixture.vm.loadingStudies }
}

internal fun ComposeContentTestRule.openReadingTools() {
    mainClock.advanceTimeBy(3_300)
    waitForIdle()
    onNodeWithText("精读 · 工具").performScrollTo().performClick()
    onNodeWithTag("reader_tools_sheet").assertIsDisplayed()
}

internal fun ComposeContentTestRule.chooseToolsTab(tab: String) {
    // The tab strip scrolls horizontally; content and fixed actions must not need vertical scrolling.
    onNodeWithTag("reader_tab_$tab").performScrollTo().performClick()
    onNodeWithTag("reader_tools_content_$tab").assertIsDisplayed()
}

internal fun ComposeContentTestRule.assertCommonToolsVisible() {
    listOf("reader_tools_header", "reader_tools_tabs", "reader_tools_footer", "reader_tools_close",
        "reader_tools_primary", "reader_quiet", "reader_complete")
        .forEach { onNodeWithTag(it).assertIsDisplayed() }
}

internal fun ComposeContentTestRule.scrollTools(tab: String, distance: Float) {
    onNodeWithTag("reader_tools_content_$tab").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, distance) }
    waitForIdle()
}

internal fun ComposeContentTestRule.toolsPosition(tab: String): Float =
    onNodeWithTag("reader_tools_content_$tab").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

internal fun ComposeContentTestRule.toolsMaximum(tab: String): Float =
    onNodeWithTag("reader_tools_content_$tab").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()

internal fun ComposeContentTestRule.readingToolsOriginal(): String =
    onNodeWithTag("zen_article_text").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

private class ReadingToolsSyntheticReadingRepository : ReadingRepository by UiTestReadingRepository() {
    val studies = CopyOnWriteArrayList<ArticleStudy>()
    var modelCalls = 0
    override suspend fun cachedStudy(articleId: String, bodyHash: String) = studies.lastOrNull {
        it.articleId == articleId && it.bodyHash == bodyHash
    }
    override suspend fun cachedStudies(articleId: String, bodyHash: String) = studies.filter {
        it.articleId == articleId && it.bodyHash == bodyHash
    }
    override suspend fun study(article: Article, body: ArticleBody, aiAllowed: Boolean): ArticleStudy {
        modelCalls++; error("Unexpected model work in tool navigation test")
    }
    override suspend fun studyRange(article: Article, body: ArticleBody, scope: StudyScope, aiAllowed: Boolean, explicitRetry: Boolean): ArticleStudy {
        modelCalls++; error("Unexpected range model work in tool navigation test")
    }
    override suspend fun assist(request: StudyRequest): StudyAssist {
        modelCalls++; error("Unexpected assistance model work in tool navigation test")
    }
}

private class ReadingToolsSyntheticAppRepository(override val reading: ReadingRepository) : AppRepository {
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
    override suspend fun hasApiKey() = false
    override suspend fun setApiKey(key: String) = Unit
    override suspend fun clearApiKey() = Unit
    override suspend fun dictionaryStatus() = DictionaryStatus()
    override suspend fun importDictionary(file: File) = DictionaryStatus()
    override suspend fun analyzeCapture(capture: Capture, deep: Boolean): AnalysisResult = error("Unexpected vocabulary analysis")
    override suspend fun saveCard(capture: Capture, analysis: AnalysisResult): Card = error("Unexpected vocabulary write")
    override suspend fun updateCard(card: Card) = Unit
    override suspend fun reviewCard(cardId: String, rating: Rating, expectedReps: Int, now: Long) = ReviewOutcome.APPLIED
    override suspend fun exportAnki(destination: File) = destination
    override suspend fun confirmAnkiExport(exportFile: File) = Unit
    override suspend fun exportEvents(destination: File) = destination
    override suspend fun recordEvent(type: String, value: Int) = Unit
}
