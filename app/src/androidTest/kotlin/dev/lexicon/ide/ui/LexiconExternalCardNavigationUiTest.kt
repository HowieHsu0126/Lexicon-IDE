package dev.lexicon.ide.ui

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Full LexiconApp routes with synthetic repositories. No device database, API or key store. */
@RunWith(AndroidJUnit4::class)
class LexiconExternalCardNavigationUiTest {
    @get:Rule val compose = createComposeRule()
    private var dispatcher: OnBackPressedDispatcher? = null
    private var finishes = 0

    @Test fun closingACaptureInAnExternalWorkspaceSourceKeepsTheReaderOpen() {
        val repo = NavigationRepository()
        enterExternalWorkspace(repo)
        openCard(repo.cardA)
        openSource()
        tapWord(repo, "novel")
        compose.onNodeWithTag("capture_close").assertIsDisplayed().performClick()
        awaitReader()
        assertEquals(0, finishes)
        assertEquals(0, repo.savedCards)
        assertEquals(0, repo.ratings)
        assertEquals(0, repo.aiCalls)
        back(cancel = false)
        awaitCard(repo.cardA)
        assertEquals(0, finishes)
    }

    @Test fun savingCachedAnalysisInAnExternalWorkspaceSourceDoesNotFinishTheActivity() {
        val repo = NavigationRepository()
        enterExternalWorkspace(repo)
        openCard(repo.cardA)
        openSource()
        tapWord(repo, "novel")
        compose.onNodeWithTag("generate_card").performScrollTo().assertTextContains("保存已有分析").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { repo.savedCards == 1 && !exists("capture_panel") }
        awaitReader()
        assertEquals(0, finishes)
        assertEquals(0, repo.ratings)
        assertEquals(0, repo.aiCalls)
        val saved = repo.backing.cards.value.single { it.id == "new-cached-card" }
        assertEquals("novel", saved.content.headword)
        assertEquals("A novel approach needs testing.", saved.sourceSentence)
        back(cancel = false)
        awaitCard(repo.cardA)
        assertEquals(0, finishes)
    }

    @Test fun nestedSourceCardSourceRestoresEachLayerAndPredictiveCancellationDoesNotPopIt() {
        val repo = NavigationRepository()
        enterExternalWorkspace(repo)
        openCard(repo.cardA)
        back(cancel = true)
        awaitCard(repo.cardA)
        openSource()
        back(cancel = true)
        awaitReader()
        tapWord(repo, "Reliable")
        compose.onNodeWithTag("capture_existing_card").performScrollTo().assertIsEnabled().performClick()
        awaitCard(repo.cardB)
        assertEquals(0, finishes)
        openSource()
        back(cancel = true)
        awaitReader()
        back(cancel = false)
        awaitCard(repo.cardB)
        back(cancel = true)
        awaitCard(repo.cardB)
        back(cancel = false)
        awaitReader()
        assertEquals(repo.body.text, editorText())
        back(cancel = false)
        awaitCard(repo.cardA)
        back(cancel = false)
        compose.waitUntil(5_000) { exists("card_library_search") }
        assertEquals(0, finishes)
        assertEquals(listOf(repo.cardA, repo.cardB), repo.backing.cards.value)
        assertEquals(0, repo.savedCards)
        assertEquals(0, repo.ratings)
        assertEquals(0, repo.aiCalls)
    }

    @Test fun restoredReviewRetainsItsAnswerThroughAnEmptyFlowThenResetsAfterASuccessfulRating() {
        val repo = NavigationRepository()
        val restoration = StateRestorationTester(compose)
        var restoring = false
        val initialCards = repo.backing.cards.value
        restoration.setContent {
            // The registry is saved before disposal. The recreated route then observes a
            // temporary empty repository flow, as can happen while persisted cards load.
            DisposableEffect(Unit) { onDispose { if (restoring) repo.backing.cards.value = emptyList() } }
            LexiconApp(repo)
        }
        compose.waitUntil(5_000) { exists("daily_reading") }
        command("review", "开始复习", substring = true)
        compose.waitUntil(5_000) { exists("review_answer") }
        compose.onNodeWithTag("review_answer").performScrollTo().performClick()
        compose.onNodeWithText("Good · 记得").performScrollTo()
        val position = reviewScroll()
        assertTrue("The restored answer must have a nonzero reading position", position > 100f)
        compose.runOnIdle { restoring = true }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { !exists("review_card_scroll") }
        compose.onNodeWithText("暂无到期卡片").assertExists()
        compose.runOnIdle { repo.backing.cards.value = initialCards }
        compose.waitUntil(5_000) { exists("review_card_scroll") }
        compose.waitForIdle()
        compose.onNodeWithTag("review_answer").assertDoesNotExist()
        compose.onNodeWithText("Good · 记得").assertExists()
        assertEquals(position, reviewScroll(), 1f)
        compose.onNodeWithText("Good · 记得").performScrollTo().performClick()
        compose.waitUntil(5_000) { repo.ratings == 1 && exists("review_answer") }
        compose.waitForIdle()
        compose.onNodeWithText(repo.cardB.content.headword).assertIsDisplayed()
        compose.onNodeWithText("Good · 记得").assertDoesNotExist()
        compose.onNodeWithTag("review_counts").assertTextEquals("1 完成 · 1 待复习")
        assertEquals(0f, reviewScroll(), 1f)
        assertEquals(0, repo.aiCalls)
    }

    private fun enterExternalWorkspace(repo: NavigationRepository) {
        compose.setContent {
            val owner = requireNotNull(LocalOnBackPressedDispatcherOwner.current)
            SideEffect { dispatcher = owner.onBackPressedDispatcher }
            LexiconApp(repo, initialCaptureText = "external term", onExternalCaptureDone = { finishes++ })
        }
        compose.waitUntil(5_000) { exists("capture_panel") }
        compose.onNodeWithText("配置 DeepSeek API Key").performScrollTo().performClick()
        compose.waitUntil(5_000) { !exists("capture_panel") }
        assertEquals("Moving into settings keeps the external workspace available", 0, finishes)
        command("cards", "浏览生词卡")
        compose.waitUntil(5_000) { exists("card_library_search") }
    }

    private fun command(query: String, label: String, substring: Boolean = false) {
        compose.onNodeWithText(">_  命令").performClick()
        compose.onNodeWithText("搜索命令…").performTextInput(query)
        compose.onNodeWithText(label, substring = substring).performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    private fun openCard(card: Card) {
        compose.onNodeWithTag("card_library_${card.id}").performScrollTo().performClick()
        awaitCard(card)
    }

    private fun awaitCard(card: Card) {
        compose.waitUntil(5_000) { exists("card_detail_${card.id}") && !exists("capture_panel") && !exists("zen_article_text") }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithText(card.content.headword).assertExists()
    }

    private fun openSource() {
        compose.onNodeWithTag("card_open_source").performScrollTo().assertIsEnabled().performClick()
        awaitReader()
    }

    private fun awaitReader() {
        compose.waitUntil(5_000) { exists("zen_article_text") && !exists("capture_panel") }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
    }

    private fun tapWord(repo: NavigationRepository, word: String) {
        compose.onNodeWithTag("zen_article_text").performScrollTo()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("zen_article_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val offset = repo.body.text.indexOf(word) + 1
        assertTrue(offset > 0)
        val point = layouts.single().getBoundingBox(offset).center
        compose.onNodeWithTag("zen_article_text").performTouchInput { advanceEventTime(600); click(point) }
        compose.waitUntil(5_000) { exists("capture_target") }
        compose.onNodeWithTag("capture_target").assertTextContains(word)
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    /** Real Activity dispatcher callbacks, not physical edge gestures or MagicOS evidence. */
    private fun back(cancel: Boolean) {
        val current = requireNotNull(dispatcher)
        compose.runOnIdle {
            current.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
            current.dispatchOnBackProgressed(BackEventCompat(80f, 300f, .6f, BackEventCompat.EDGE_LEFT))
        }
        compose.mainClock.advanceTimeBy(150)
        compose.waitForIdle()
        compose.runOnIdle { if (cancel) current.dispatchOnBackCancelled() else current.onBackPressed() }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun editorText() = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun reviewScroll() = compose.onNodeWithTag("review_card_scroll").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private class NavigationRepository(val backing: CaptureAnalysisTestRepository = CaptureAnalysisTestRepository()) : AppRepository by backing {
        override val reading = UiTestReadingRepository()
        val article = Article("external-source", "external-guid", listOf("source"), "Synthetic reading context",
            "https://example.invalid/source", "", sourceName = "Synthetic source", readingMode = ReadingMode.NATIVE)
        private val original = "A robust method works.\n\nReliable evidence supports this claim.\n\nA novel approach needs testing."
        val body = ArticleBody(article.id, original, fingerprint(original), 0, Long.MAX_VALUE, "Synthetic test material")
        private fun capture(word: String) = original.indexOf(word).let { start -> captureAt(article, body, start, start + word.length, true, false) }
        private val captureA = capture("robust")
        private val captureB = capture("Reliable")
        private fun content(word: String) = VocabularyContent(word,
            "This expression describes evidence that remains useful under the reported conditions. ".repeat(8),
            listOf(Collocation("$word method", "The method remains useful within its stated conditions. ".repeat(4)),
                Collocation("$word evidence", "The evidence supports the interpretation in this context. ".repeat(4))),
            "supported in the reported context", "neutral", "high")
        private fun card(id: String, capture: Capture, due: Long) = Card(id, capture.id, content(capture.target),
            capture.sentence, capture.sourceRef, state = FsrsState(dueAt = due), createdAt = 1, updatedAt = 2)
        val cardA = card("card-a", captureA, 1)
        val cardB = card("card-b", captureB, 2)
        var savedCards = 0
        var ratings = 0
        var aiCalls = 0

        init {
            backing.articles.value = listOf(article)
            backing.sources.value = listOf(Source("source", "Synthetic source", "https://example.invalid/feed", ReadingMode.NATIVE, aiAllowed = true))
            backing.captures.value = listOf(captureA, captureB)
            backing.cards.value = listOf(cardA, cardB)
            backing.settings.value = AppSettings(reader = ReaderPreferences(automaticStudy = false))
        }

        override suspend fun openArticle(id: String) = body.takeIf { it.articleId == id }
        override suspend fun saveCapture(capture: Capture) {
            backing.captures.value = backing.captures.value.filterNot { it.id == capture.id } + capture
        }
        override suspend fun availableCaptureAnalysis(capture: Capture): CaptureAnalysisAvailability {
            backing.cards.value.firstOrNull { it.captureId == capture.id }?.let {
                return CaptureAnalysisAvailability.ExistingCard(it, true)
            }
            return if (capture.target == "novel" && capture.articleId == article.id)
                CaptureAnalysisAvailability.Ready(AnalysisResult(content("novel"), "synthetic-cache"), "cached-novel")
            else CaptureAnalysisAvailability.Missing
        }
        override suspend fun saveAvailableCaptureAnalysis(capture: Capture, expectedAnalysisId: String): Card {
            check(expectedAnalysisId == "cached-novel" && capture.target == "novel")
            savedCards++
            saveCapture(capture)
            return card("new-cached-card", capture, 3).also { backing.cards.value = backing.cards.value + it }
        }
        override suspend fun analyzeCapture(capture: Capture, deep: Boolean): AnalysisResult {
            aiCalls++; error("This navigation test must not request AI")
        }
        override suspend fun saveCard(capture: Capture, analysis: AnalysisResult): Card = error("Use the explicitly cached synthetic result")
        override suspend fun reviewCard(cardId: String, rating: Rating, expectedReps: Int, now: Long): ReviewOutcome {
            val current = backing.cards.value.single { it.id == cardId }
            check(current.state.reps == expectedReps)
            ratings++
            backing.cards.value = backing.cards.value.map {
                if (it.id == cardId) it.copy(state = it.state.copy(phase = CardPhase.LEARNING, reps = it.state.reps + 1, dueAt = now + 60_000)) else it
            }
            return ReviewOutcome.APPLIED
        }
    }
}
