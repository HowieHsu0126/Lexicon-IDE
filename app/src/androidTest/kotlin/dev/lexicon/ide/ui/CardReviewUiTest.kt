package dev.lexicon.ide.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardReviewUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = 5_000L
    private val due = card("due", "robust")
    private val paused = card("paused", "qualified", paused = true)
    private val future = card("future", "substantial").copy(state = FsrsState(phase = CardPhase.REVIEW, dueAt = 20_000, reps = 2))

    @Test fun reviewExcludesPausedAndFutureCardsAndCountsOnlyEligibleCards() {
        compose.setContent { TestTheme {
            ReviewScreen(listOf(paused, future, due), false, { _, _, _ -> fail("No rating was requested") }, {}, now = now)
        } }
        compose.onNodeWithText(due.content.headword).assertExists()
        compose.onNodeWithText(paused.content.headword).assertDoesNotExist()
        compose.onNodeWithText(future.content.headword).assertDoesNotExist()
        compose.onNodeWithTag("review_counts").assertTextEquals("0 完成 · 1 待复习")
    }

    @Test fun pauseBeforeRevealRemovesTheCardWithoutCountingAReview() {
        val cards = mutableStateOf(listOf(due)); val pauses = mutableListOf<String>()
        var reviews = 0
        compose.setContent { TestTheme {
            ReviewScreen(cards.value, false, { _, _, _ -> reviews++ }, {}, onPauseCard = { selected ->
                pauses += selected.id
                cards.value = cards.value.map { it.copy(reviewPaused = true) }
            }, now = now)
        } }
        compose.onNodeWithText("Good · 记得").assertDoesNotExist()
        compose.onNodeWithTag("review_pause_card").performScrollTo().performClick()
        compose.onNodeWithText("暂无到期卡片").assertExists()
        compose.onNodeWithText("本轮完成 0 次复习。").assertExists()
        compose.runOnIdle { assertEquals(listOf(due.id), pauses); assertEquals(0, reviews) }
    }

    @Test fun rejectedPauseLeavesTheCardVisibleAndAvailableForRetry() {
        var pauses = 0
        compose.setContent { TestTheme {
            ReviewScreen(listOf(due), false, { _, _, _ -> fail("No rating was requested") }, {},
                onPauseCard = { pauses++ }, now = now)
        } }
        compose.onNodeWithTag("review_pause_card").performScrollTo().performClick()
        compose.onNodeWithTag("review_pause_card").assertIsEnabled().performClick()
        compose.onNodeWithText(due.content.headword).assertExists()
        compose.onNodeWithTag("review_counts").assertTextEquals("0 完成 · 1 待复习")
        compose.runOnIdle { assertEquals(2, pauses) }
    }

    @Test fun fastRejectedRatingDoesNotCountAReviewOrLeaveSubmissionLocked() {
        var ratings = 0
        compose.setContent { TestTheme {
            ReviewScreen(listOf(due), false, { _, _, _ -> ratings++ }, {}, now = now)
        } }
        compose.onNodeWithTag("review_answer").performScrollTo().performClick()
        compose.onNodeWithText("Good · 记得").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Good · 记得").assertIsEnabled().performClick()
        compose.onNodeWithTag("review_counts").performScrollTo().assertTextEquals("0 完成 · 1 待复习")
        compose.runOnIdle { assertEquals(2, ratings) }
    }

    @Test fun cardDetailPauseAndResumeUseExplicitStateWithoutChangingContentOrSchedule() {
        val current = mutableStateOf(due); val requested = mutableListOf<Boolean>()
        compose.setContent { TestTheme {
            CardDetail(current.value, false, { _, _ -> fail("Pause is not content editing") }, {}, onSetReviewPaused = {
                requested += it; current.value = current.value.copy(reviewPaused = it)
            })
        } }
        compose.onNodeWithTag("card_review_pause").performScrollTo().assertTextContains("暂停复习").performClick()
        compose.onNodeWithTag("card_review_pause").assertTextContains("恢复复习")
        compose.onNodeWithTag("card_review_pause_note").assertExists()
        compose.onNodeWithTag("card_review_due").performScrollTo().assertTextContains("原到期时间", substring = true)
        compose.runOnIdle { assertEquals(due.copy(reviewPaused = true), current.value) }
        compose.onNodeWithTag("card_review_pause").performScrollTo().performClick()
        compose.onNodeWithTag("card_review_pause_note").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(true, false), requested); assertEquals(due, current.value) }
    }

    @Test fun busyCardCannotPauseOrStartAnEdit() {
        compose.setContent { TestTheme {
            CardDetail(due, true, { _, _ -> fail("Busy content cannot be edited") }, {}, onSetReviewPaused = { fail("Busy card cannot pause") })
        } }
        compose.onNodeWithTag("card_review_pause").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("card_edit_analysis").performScrollTo().assertIsNotEnabled()
    }

    @Test fun editedDraftSurvivesPauseAndFailedSaveUntilSaveCompletionIsConfirmed() {
        val current = mutableStateOf(due); var submitted: Card? = null; var complete: (() -> Unit)? = null
        compose.setContent { TestTheme {
            CardDetail(current.value, false, { updated, done -> submitted = updated; complete = done }, {},
                onSetReviewPaused = { current.value = current.value.copy(reviewPaused = it) })
        } }
        compose.onNodeWithTag("card_edit_analysis").performScrollTo().performClick()
        compose.onNodeWithTag("card_headword_edit").performScrollTo().performTextReplacement("robust method")
        compose.onNodeWithTag("card_review_pause").performScrollTo().performClick()
        compose.onNodeWithTag("card_headword_edit").performScrollTo().assertTextContains("robust method")
        compose.onNodeWithTag("card_save_edit").performScrollTo().performClick()
        compose.onNodeWithTag("card_headword_edit").assertExists()
        compose.runOnIdle {
            assertEquals("robust method", submitted?.content?.headword)
            assertTrue(requireNotNull(submitted).reviewPaused)
            assertEquals(due.state, submitted?.state)
            // The callback is withheld for a failed save. A confirmed retry closes the editor.
        }
        compose.onNodeWithTag("card_save_edit").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { current.value = requireNotNull(submitted); requireNotNull(complete).invoke() }
        compose.onNodeWithTag("card_headword_edit").assertDoesNotExist()
        compose.onAllNodesWithText("robust method").assertCountEquals(2)
    }

    @Test fun libraryFiltersKeepPausedCardsDiscoverableAndEmptySearchCanBeReset() {
        compose.setContent { TestTheme { CardLibrary(listOf(due, paused, future), {}, {}, now = now) } }
        compose.onNodeWithTag("card_filter_due").performClick()
        compose.onNodeWithTag("card_library_due").assertExists()
        compose.onNodeWithTag("card_library_paused").assertDoesNotExist()
        compose.onNodeWithTag("card_library_future").assertDoesNotExist()
        compose.onNodeWithTag("card_filter_paused").performClick()
        compose.onNodeWithTag("card_library_paused").assertExists()
        compose.onNodeWithTag("card_library_due").assertDoesNotExist()
        compose.onNodeWithTag("card_library_search").performTextInput("no-such-word")
        compose.onNodeWithText("没有匹配的卡片").assertExists()
        compose.onNodeWithText("清除筛选").performScrollTo().performClick()
        compose.onNodeWithTag("card_filter_all").assertIsSelected()
        compose.onNodeWithTag("card_library_search").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("card_library_due").assertExists()
        compose.onNodeWithTag("card_library_paused").assertExists()
    }

    @Test fun dueFilterRecomputesWhenTheSharedClockAdvances() {
        val clock = mutableStateOf(now)
        compose.setContent { TestTheme { CardLibrary(listOf(future), {}, {}, now = clock.value) } }
        compose.onNodeWithTag("card_filter_due").performClick()
        compose.onNodeWithText("没有匹配的卡片").assertExists()
        compose.runOnIdle { clock.value = 20_001 }
        compose.onNodeWithTag("card_library_future").assertExists()
    }

    @Test fun pausedCardRemainsReadableAndCanBeRestoredOnDraculaAtTwoHundredPercent() {
        val current = mutableStateOf(paused)
        compose.setContent { TestTheme(fontScale = 2f) {
            CardDetail(current.value, false, { _, _ -> }, {}, onSetReviewPaused = { current.value = current.value.copy(reviewPaused = it) })
        } }
        compose.onNodeWithTag("card_review_pause_note").performScrollTo().assertIsDisplayed()
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("card_review_pause_note").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertFalse("Pause explanation must fit at 200% font scale", results.single().hasVisualOverflow)
        compose.onNodeWithTag("card_review_pause").performScrollTo().assertIsDisplayed().assertIsEnabled()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "validation-1.9.0/card-review-ui").apply { mkdirs() }
        dir.resolve("paused-dracula-font200.png").outputStream().use { output ->
            assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        compose.onNodeWithTag("card_review_pause").performClick()
        compose.onNodeWithTag("card_review_pause").assertTextContains("暂停复习")
    }

    @Composable private fun TestTheme(fontScale: Float = 1f, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            LexiconTheme(AppSettings(theme = ThemeMode.DARK)) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
    }

    private fun card(id: String, word: String, paused: Boolean = false) = Card(
        id, "capture-$id", VocabularyContent(word, "Reliable when conditions change.",
            listOf(Collocation("robust method", "A robust method handles changes."), Collocation("robust evidence", "The study provides robust evidence.")),
            "reliable under varying conditions", "neutral", "high"), "A robust method handles variation.", SourceRef("Methods", "Example"),
        state = FsrsState(dueAt = 1_000), createdAt = 1, updatedAt = 2, ankiExportedAt = 3, reviewPaused = paused,
    )
}
