package dev.lexicon.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.AppSettings
import dev.lexicon.domain.Card
import dev.lexicon.domain.Collocation
import dev.lexicon.domain.FsrsState
import dev.lexicon.domain.Rating
import dev.lexicon.domain.SourceRef
import dev.lexicon.domain.ThemeMode
import dev.lexicon.domain.VocabularyContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic UI state tests; no repository, model call or saved learning data is used. */
@RunWith(AndroidJUnit4::class)
class ReviewSourceNavigationUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = 5_000L

    @Test fun sourceNavigationDoesNotScoreOrChangeWhetherTheAnswerIsRevealed() {
        val current = card("first", "robust")
        val opened = mutableListOf<Card>()
        var ratings = 0
        compose.setContent { TestTheme {
            ReviewScreen(listOf(current), false, { _, _, _ -> ratings++ }, {}, now = now,
                onOpenSource = { opened += it })
        } }

        compose.onNodeWithTag("review_open_source").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("review_answer").assertExists()
        compose.onNodeWithText("Good · 记得").assertDoesNotExist()
        compose.onNodeWithTag("review_answer").performScrollTo().performClick()
        compose.onNodeWithText("Good · 记得").assertExists()
        compose.onNodeWithTag("review_open_source").performScrollTo().performClick()
        compose.onNodeWithTag("review_answer").assertDoesNotExist()
        compose.onNodeWithText("Good · 记得").assertExists()
        compose.onNodeWithTag("review_counts").assertTextEquals("0 完成 · 1 待复习")
        compose.runOnIdle {
            assertEquals(listOf(current, current), opened)
            assertEquals(0, ratings)
        }
    }

    @Test fun sourceActionRequiresBothACallbackAndAnAvailableOriginal() {
        val current = card("first", "robust")
        val available = mutableStateOf(false)
        val callbackPresent = mutableStateOf(true)
        var opened = 0
        compose.setContent { TestTheme {
            ReviewScreen(listOf(current), false, { _, _, _ -> error("No rating was requested") }, {}, now = now,
                onOpenSource = if (callbackPresent.value) ({ _: Card -> opened++ }) else null,
                canOpenSource = { available.value })
        } }

        compose.onNodeWithTag("review_open_source").assertDoesNotExist()
        compose.runOnIdle { available.value = true }
        compose.onNodeWithTag("review_open_source").assertExists()
        compose.runOnIdle { callbackPresent.value = false }
        compose.onNodeWithTag("review_open_source").assertDoesNotExist()
        compose.onNodeWithTag("review_answer").assertExists()
        compose.runOnIdle { assertEquals(0, opened) }
    }

    @Test fun successfulRatingStartsTheNextLongCardAtTheTopAtTwoHundredPercent() {
        val first = card("first", "robust")
        val second = card("second", "qualified")
        val cards = mutableStateOf(listOf(first, second))
        val ratings = mutableListOf<Pair<String, Rating>>()
        compose.setContent { TestTheme(fontScale = 2f) {
            ReviewScreen(cards.value, false, { selected, rating, saved ->
                ratings += selected.id to rating
                cards.value = listOf(second)
                saved()
            }, {}, now = now)
        } }

        compose.onNodeWithTag("review_answer").performScrollTo().performClick()
        compose.onNodeWithText("Good · 记得").performScrollTo()
        assertTrue("The first card must be scrolled before a rating", position() > 100f)
        compose.onNodeWithText("Good · 记得").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(second.content.headword).assertIsDisplayed()
        compose.onNodeWithTag("review_counts").assertIsDisplayed().assertTextEquals("1 完成 · 1 待复习")
        assertEquals("The next card must start at its question", 0f, position(), 1f)
        compose.onNodeWithTag("review_answer").assertExists()
        compose.onNodeWithText("Good · 记得").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(first.id to Rating.GOOD), ratings) }
    }

    @Test fun failedRatingKeepsTheSameRevealedAnswerAndScrollPosition() {
        val current = card("first", "robust")
        val busy = mutableStateOf(false)
        var ratings = 0
        compose.setContent { TestTheme {
            ReviewScreen(listOf(current), busy.value, { _, _, _ ->
                ratings++
                busy.value = true
                // No success callback: simulate a rejected save, not an applied rating.
            }, {}, now = now)
        } }

        compose.onNodeWithTag("review_answer").performScrollTo().performClick()
        compose.onNodeWithText("Good · 记得").performScrollTo()
        val before = position()
        assertTrue("The answer must have a nonzero position to test preservation", before > 100f)
        compose.onNodeWithText("Good · 记得").performClick()
        compose.onNodeWithText("Good · 记得").assertIsNotEnabled()
        compose.runOnIdle { busy.value = false }
        compose.waitForIdle()

        compose.onNodeWithText("Good · 记得").assertIsEnabled()
        compose.onNodeWithTag("review_answer").assertDoesNotExist()
        compose.onNodeWithTag("review_counts").assertTextEquals("0 完成 · 1 待复习")
        assertEquals("A failed rating must not move the current answer", before, position(), 1f)
        compose.runOnIdle { assertEquals(1, ratings) }
    }

    @Test fun sourceRoundTripRestoresTheSameCardAnswerAndPositionFromItsSavedState() {
        val current = card("first", "robust")
        val sourceOpen = mutableStateOf(false)
        val opened = mutableListOf<String>()
        var ratings = 0
        compose.setContent { TestTheme {
            val holder = rememberSaveableStateHolder()
            if (sourceOpen.value) {
                Button(onClick = { sourceOpen.value = false }, modifier = Modifier.testTag("return_from_source")) {
                    Text("返回复习")
                }
            } else holder.SaveableStateProvider("review") {
                ReviewScreen(listOf(current), false, { _, _, _ -> ratings++ }, {}, now = now,
                    onOpenSource = { opened += it.id; sourceOpen.value = true })
            }
        } }

        compose.onNodeWithTag("review_answer").performScrollTo().performClick()
        compose.onNodeWithTag("review_open_source").performScrollTo()
        val before = position()
        assertTrue("The original entry must be below a long question", before > 100f)
        compose.onNodeWithTag("review_open_source").performClick()
        compose.onNodeWithTag("review_card_scroll").assertDoesNotExist()
        compose.onNodeWithTag("return_from_source").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("review_answer").assertDoesNotExist()
        compose.onNodeWithText("Good · 记得").assertExists()
        compose.onNodeWithTag("review_counts").assertTextEquals("0 完成 · 1 待复习")
        assertEquals("Restoring the same card must keep its position", before, position(), 1f)
        compose.runOnIdle {
            assertEquals(listOf(current.id), opened)
            assertEquals(0, ratings)
        }
    }

    private fun position(): Float = compose.onNodeWithTag("review_card_scroll").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Composable private fun TestTheme(fontScale: Float = 1f, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            LexiconTheme(AppSettings(theme = ThemeMode.DARK)) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
    }

    private fun card(id: String, word: String): Card = Card(
        id, "capture-$id",
        VocabularyContent(word,
            "Reliable under the conditions discussed in the article. ".repeat(8),
            listOf(
                Collocation("$word method", "The $word method preserves the intended interpretation. ".repeat(3)),
                Collocation("$word evidence", "The author explains how the $word evidence supports the argument. ".repeat(3)),
            ),
            "supported within the stated conditions", "neutral", "high"),
        "The $word method must be considered in the context of the evidence and its limitations. ".repeat(14),
        SourceRef("Synthetic reading context", "Test source", "https://example.invalid/article"),
        state = FsrsState(dueAt = 1_000), createdAt = 1, updatedAt = 2,
    )
}
