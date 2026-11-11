package dev.lexicon.ide.ui

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Complete app routes with synthetic repositories; no installed data or model calls. */
@RunWith(AndroidJUnit4::class)
class ReviewCompletionNavigationUiTest {
    @get:Rule val compose = createComposeRule()
    private var dispatcher: OnBackPressedDispatcher? = null

    @Test fun completionCardFeedbackAndOriginalRoundTripNeverApplyAnotherRating() {
        val repo = CompletionRepository()
        compose.setContent {
            val owner = LocalOnBackPressedDispatcherOwner.current
            SideEffect { dispatcher = owner?.onBackPressedDispatcher }
            LexiconApp(repo)
        }
        enterReview()
        score()
        awaitCompletion()
        val afterRating = repo.backing.cards.value.single()
        val logs = repo.logs.value
        val capture = repo.backing.captures.value
        compose.onNodeWithTag("review_last_card").performScrollTo().performClick()
        awaitCard(repo)
        compose.onNodeWithTag("card_back").assertTextEquals("← 返回复习")
        repo.failEdit = true
        compose.onNodeWithText("标记为不准确").performScrollTo().performClick()
        compose.waitUntil(5_000) { repo.editAttempts == 1 }
        assertFalse(repo.backing.cards.value.single().inaccurate)
        compose.onNodeWithText("标记为不准确").performScrollTo().assertIsDisplayed().assertIsEnabled()
        repo.failEdit = false
        compose.onNodeWithText("标记为不准确").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { repo.backing.cards.value.single().inaccurate }
        compose.onNodeWithText("清除标记").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { !repo.backing.cards.value.single().inaccurate }
        compose.onNodeWithTag("card_open_source").performScrollTo().performClick()
        awaitReader()
        assertEquals(repo.body.text, compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        back(cancel = true); awaitReader()
        back(cancel = false); awaitCard(repo)
        back(cancel = true); awaitCard(repo)
        back(cancel = false); awaitCompletion()
        compose.onNodeWithTag("review_last_source").performScrollTo().performClick()
        awaitReader(); back(cancel = false); awaitCompletion()
        assertEquals(afterRating, repo.backing.cards.value.single())
        assertEquals(logs, repo.logs.value)
        assertEquals(capture, repo.backing.captures.value)
        assertEquals(1, repo.ratingAttempts)
        assertEquals(0, repo.backing.analysisCalls)
    }

    @Test fun appliedRatingAfterLeavingThePageIsRecoveredFromLogsAndSurvivesSavedStateRestore() {
        val repo = CompletionRepository()
        val gate = CompletableDeferred<Unit>(); repo.ratingGate = gate
        val restoration = StateRestorationTester(compose)
        restoration.setContent { LexiconApp(repo) }
        enterReview(); score()
        compose.waitUntil(5_000) { repo.ratingAttempts == 1 }
        compose.onNodeWithText("Lexicon", substring = true).performClick()
        compose.waitUntil(5_000) { exists("daily_reading") && !exists("review_card_scroll") }
        compose.mainClock.advanceTimeBy(900); compose.waitForIdle()
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitUntil(5_000) { repo.logs.value.size == 1 }
        commandReview(); awaitCompletion()
        val logs = repo.logs.value
        restoration.emulateSavedInstanceStateRestore()
        awaitCompletion()
        compose.onNodeWithTag("review_last_headword").assertTextEquals("resilient")
        compose.onNodeWithTag("review_last_card").performScrollTo().performClick(); awaitCard(repo)
        compose.onNodeWithTag("card_back").performScrollTo().performClick(); awaitCompletion()
        assertEquals(logs, repo.logs.value)
        assertEquals(1, repo.ratingAttempts)
    }

    @Test fun failedLocalRatingKeepsTheAnswerAndAConfirmedRetryClearsItsError() {
        val repo = CompletionRepository().apply { failRating = true }
        compose.setContent { LexiconApp(repo) }
        enterReview(); score()
        compose.waitUntil(5_000) { repo.ratingAttempts == 1 }
        compose.onNodeWithText("[error] 本地评分未保存，请重试。").assertExists()
        compose.onNodeWithTag("review_rate_good").assertIsEnabled()
        assertTrue(repo.logs.value.isEmpty())
        compose.onNodeWithTag("review_last_card").assertDoesNotExist()
        repo.failRating = false
        compose.onNodeWithTag("review_rate_good").performClick()
        awaitCompletion()
        compose.onNodeWithText("[error] 本地评分未保存，请重试。").assertDoesNotExist()
        assertEquals(1, repo.logs.value.size)
        assertEquals(1, repo.backing.cards.value.single().state.reps)
    }

    private fun enterReview() {
        compose.waitUntil(5_000) { exists("daily_reading") }
        commandReview()
        compose.waitUntil(5_000) { exists("review_answer") }
    }
    private fun commandReview() {
        compose.onNodeWithText(">_  命令").performClick()
        compose.onNodeWithText("搜索命令…").performTextInput("review")
        compose.onNodeWithText("开始复习", substring = true).performClick()
    }
    private fun score() {
        compose.onNodeWithTag("review_answer").performScrollTo().performClick()
        compose.onNodeWithTag("review_rate_good").performScrollTo().performClick()
    }
    private fun awaitCompletion() {
        compose.waitUntil(5_000) { exists("review_last_card") && exists("review_completion_count") }
        compose.onNodeWithTag("review_completion_count").assertTextContains("今日完成 1 次复习")
        compose.onNodeWithTag("review_rate_good").assertDoesNotExist()
    }
    private fun awaitCard(repo: CompletionRepository) {
        compose.waitUntil(5_000) { exists("card_detail_${repo.card.id}") }
        compose.onNodeWithText(repo.card.sourceSentence).assertExists()
    }
    private fun awaitReader() { compose.waitUntil(5_000) { exists("zen_article_text") } }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun back(cancel: Boolean) {
        val current = requireNotNull(dispatcher)
        compose.runOnIdle {
            current.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
            current.dispatchOnBackProgressed(BackEventCompat(80f, 300f, .6f, BackEventCompat.EDGE_LEFT))
        }
        compose.mainClock.advanceTimeBy(150); compose.waitForIdle()
        compose.runOnIdle { if (cancel) current.dispatchOnBackCancelled() else current.onBackPressed() }
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
    }

    private class CompletionRepository(val backing: CaptureAnalysisTestRepository = CaptureAnalysisTestRepository()) : AppRepository by backing {
        override val reading = UiTestReadingRepository()
        val article = Article("completion-article", "completion-guid", listOf("completion-source"), "Synthetic review context",
            "https://example.invalid/completion", "", sourceName = "Synthetic source", readingMode = ReadingMode.NATIVE, read = true, captured = true)
        private val original = "This is synthetic test material.\n\nA resilient plan can adapt when circumstances change.\n\nThe proposed plan keeps a quiet space for reading."
        val body = ArticleBody(article.id, original, fingerprint(original), 0, Long.MAX_VALUE, "Self-authored test material")
        val capture = original.indexOf("resilient").let { captureAt(article, body, it, it + 9, false, false) }
        val card = Card("completion-card", capture.id, VocabularyContent("resilient", "Able to remain useful when conditions change.",
            listOf(Collocation("resilient plan", "The synthetic group chose a resilient plan."), Collocation("resilient layout", "The synthetic layout can adapt.")),
            "able to adapt", "neutral", "medium"), capture.sentence, capture.sourceRef, state = FsrsState(dueAt = 1), createdAt = 1)
        val logs = MutableStateFlow<List<ReviewLog>>(emptyList())
        override val reviewHistory = object : ReviewHistoryRepository {
            override fun observeDay(range: ReviewDayRange) = logs.map { all ->
                val day = all.filter { it.reviewedAt >= range.startInclusive && it.reviewedAt < range.endExclusive }
                val last = day.maxWithOrNull(compareBy<ReviewLog> { it.reviewedAt }.thenBy { it.id })
                ReviewDaySummary(day.size, last?.let { ReviewReceipt(it.id, it.cardId, it.reviewedAt, it.rating, it.before.reps, it.after.reps) })
            }
        }
        var ratingAttempts = 0
        var ratingGate: CompletableDeferred<Unit>? = null
        var failRating = false
        var failEdit = false
        var editAttempts = 0
        init {
            backing.articles.value = listOf(article)
            backing.sources.value = listOf(Source("completion-source", "Synthetic source", "https://example.invalid/feed", ReadingMode.NATIVE, aiAllowed = false))
            backing.cards.value = listOf(card); backing.captures.value = listOf(capture)
            backing.settings.value = AppSettings(theme = ThemeMode.DARK, reader = ReaderPreferences(automaticStudy = false))
        }
        override suspend fun openArticle(id: String) = body.takeIf { it.articleId == id }
        override suspend fun reviewCard(cardId: String, rating: Rating, expectedReps: Int, now: Long): ReviewOutcome {
            ratingAttempts++
            ratingGate?.await()
            if (failRating) throw IOException("Synthetic local storage failure")
            val current = backing.cards.value.single { it.id == cardId }
            if (current.state.reps != expectedReps) return ReviewOutcome.STALE
            val next = Fsrs6Scheduler().review(current.state, rating, now)
            backing.cards.value = listOf(current.copy(state = next))
            logs.value = logs.value + ReviewLog("synthetic-log-${logs.value.size}", cardId, now, rating, current.state, next)
            return ReviewOutcome.APPLIED
        }
        override suspend fun updateCard(card: Card) {
            editAttempts++
            if (failEdit) throw IOException("Synthetic local edit failure")
            val current = backing.cards.value.single { it.id == card.id }
            backing.cards.value = listOf(current.copy(content = card.content, inaccurate = card.inaccurate))
        }
    }
}
