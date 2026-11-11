package dev.lexicon.ide.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReadingFeatureUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun dailyScreenOffersThreeOrFewerArticlesAndDistinctModeActions() {
        val articles = (1..5).map { Article("a$it", "g$it", listOf("s$it"), "Article $it", "https://example.org/$it", "Summary", sourceName = "Source $it") }
        var selected: StudyMode? = null
        compose.setContent { LexiconTheme(AppSettings()) {
            DailyReadingHome(articles, DailyEdition("2026-10-30", listOf("a1", "a2", "a3"), listOf("s1", "s2", "s3")), false, {}, { _, mode -> selected = mode }, {}, {})
        } }
        compose.onNodeWithText("3 / 3 篇").assertIsDisplayed()
        compose.onNodeWithText("Article 4").assertDoesNotExist()
        compose.onAllNodesWithText("略读 · 5 分钟").onFirst().performClick()
        compose.runOnIdle { assertEquals(StudyMode.SKIM, selected) }
    }
    @Test fun fullZenReaderTapCapturesExactWordWithoutKeyboardOrRouteChange() {
        val text = "A robust method stays useful. A robust result has supporting evidence."
        val article = Article("tap-reader", "g", listOf("s"), "A reading example", "https://example.org/tap", "", sourceName = "Example")
        val body = ArticleBody(article.id, text, fingerprint(text), 1, Long.MAX_VALUE, "Test")
        var captured: Capture? = null
        compose.setContent { LexiconTheme(AppSettings()) {
            ZenReader(article, body, false, AppSettings(), true, false, {}, { captured = it }, {}, {}, {}, { _, _ -> })
        } }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("zen_article_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val from = text.indexOf("robust")
        compose.onNodeWithTag("zen_article_text").performTouchInput { click(layouts.single().getBoundingBox(from + 2).center) }
        compose.runOnIdle { assertEquals("robust", captured?.selectedText); assertEquals(from, captured?.selectionStart) }
    }
    @Test fun progressDoesNotInventScoresWithoutData() {
        compose.setContent { LexiconTheme(AppSettings()) { ProgressScreen(ReadingLibrary(), emptyList(), {}) } }
        compose.onRoot().assertExists()
        // No fabricated 100% comprehension or upward trend should appear in an empty profile.
        compose.onNodeWithText("100%", substring = true).assertDoesNotExist()
    }
    @Test fun aiGeneratedCapturePreservesItsOrigin() {
        val body = ArticleBody("a", "A revised explanation makes the claim clearer.", "hash", 1, 2, "AI", "")
        val article = Article("a", "g", listOf("s"), "Article", "https://example.org/a", "")
        val capture = captureAt(article, body, 2, 9, true, true)
        assertEquals(ContextOrigin.AI_GENERATED, capture.contextOrigin)
        assertEquals("revised", capture.selectedText)
    }
}

internal class UiTestReadingRepository : ReadingRepository {
    override val library = MutableStateFlow(ReadingLibrary())
    var analysisCount = 0
    override suspend fun dailyEdition(articles: List<Article>, sources: List<Source>, now: Long, zoneId: String) =
        DailyEdition("test", articles.take(3).map { it.id }, articles.take(3).map { it.sourceName })
    override suspend fun cachedStudy(articleId: String, bodyHash: String): ArticleStudy? = null
    override suspend fun study(article: Article, body: ArticleBody, aiAllowed: Boolean): ArticleStudy {
        analysisCount++
        return ArticleStudy(article.id, body.contentHash, "A synthetic overview.", "B2",
            listOf(MindMapNode("root", null, article.title), MindMapNode("evidence", "root", "Evidence", 0)),
            emptyList(), listOf(StudyParagraph(0, "evidence", "Evidence and reasoning")),
            listOf(ReadingQuestion("q", "What supports the claim?", "The reported evidence.", listOf(0))),
            emptyList(), "test", 1)
    }
    override suspend fun assist(request: StudyRequest) = StudyAssist(request.kind, "Test", "A synthetic response.", "test", 1)
    override suspend fun saveNote(note: ReadingNote) { library.value = library.value.copy(notes = library.value.notes + note) }
    override suspend fun deleteNote(id: String) { library.value = library.value.copy(notes = library.value.notes.filter { it.id != id }) }
    override suspend fun recordSession(session: ReadingSession) { library.value = library.value.copy(sessions = library.value.sessions + session) }
    override suspend fun saveCheck(check: ComprehensionCheck) { library.value = library.value.copy(checks = library.value.checks + check) }
    override suspend fun expressionReview() = StudyAssist("review", "Review", "Synthetic", "test", 1)
    override suspend fun exportKnowledge(destination: File) = destination
}
