package dev.lexicon.ide.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.AppSettings
import dev.lexicon.domain.StudyHighlight
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FocusedArticleTextTest {
    @get:Rule val compose = createComposeRule()

    private val article = (1..20).joinToString("\n\n") { index ->
        "Paragraph $index. A careful reader considers the evidence before drawing a conclusion. " +
            "The same repeated expression appears again, while source offsets remain exact."
    }

    @Test fun focusFollowsScrollingAndPausesWhileCaptureIsOpen() {
        val state = TextFieldState(article)
        var captureOpen by mutableStateOf(false)
        lateinit var scroll: ScrollState
        lateinit var scope: kotlinx.coroutines.CoroutineScope
        compose.setContent {
            LexiconTheme(AppSettings()) {
                scroll = rememberScrollState()
                scope = rememberCoroutineScope()
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    FocusedArticleText(state, TextStyle(fontSize = 18.sp, lineHeight = 31.5.sp), Modifier.fillMaxWidth(), captureOpen)
                }
            }
        }
        compose.waitForIdle()
        // The reading-line settle delay is intentional even when no animation is running yet.
        compose.mainClock.advanceTimeBy(450)
        compose.waitForIdle()
        fun focused() = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().config[FocusedParagraphIndex]
        val initial = focused()
        compose.runOnIdle { scope.launch { scroll.scrollTo(1400) } }
        compose.waitUntil(3_000) { focused() > initial }
        val beforeCapture = focused()
        compose.runOnIdle { captureOpen = true }
        compose.waitForIdle()
        compose.runOnIdle { scope.launch { scroll.scrollTo(3000) } }
        compose.waitForIdle()
        assertEquals(beforeCapture, focused())
        compose.runOnIdle { captureOpen = false }
        compose.waitUntil(3_000) { focused() > beforeCapture }
        compose.waitForIdle()
        saveScreenshot("focus-scrolled-day.png")
        assertEquals(article, state.text.toString())
    }

    @Test fun reverseCrossParagraphSelectionSurvivesFocusPaintingAndScrolling() {
        val state = TextFieldState(article)
        lateinit var scroll: ScrollState
        lateinit var scope: kotlinx.coroutines.CoroutineScope
        compose.setContent {
            LexiconTheme(AppSettings()) {
                scroll = rememberScrollState()
                scope = rememberCoroutineScope()
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    FocusedArticleText(state, TextStyle(fontSize = 18.sp, lineHeight = 31.5.sp), Modifier.fillMaxWidth())
                }
            }
        }
        val start = article.indexOf("repeated")
        val end = article.indexOf("Paragraph 3") + "Paragraph 3".length
        compose.onNodeWithTag("zen_article_text").performSemanticsAction(SemanticsActions.SetSelection) { setSelection ->
            setSelection(end, start, false)
        }
        val focused = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().config[FocusedParagraphIndex]
        compose.runOnIdle { scope.launch { scroll.scrollTo(1800) } }
        compose.waitForIdle()
        assertEquals(TextRange(end, start), state.selection)
        assertEquals(article.substring(start, end), state.text.subSequence(state.selection.min, state.selection.max).toString())
        assertEquals(focused, compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().config[FocusedParagraphIndex])
        assertEquals(article, state.text.toString())
    }

    @Test fun tapUsesRealWordOffsetsAndStudyHighlightsKeepSourceText() {
        val state = TextFieldState(article, TextRange.Zero)
        val start = article.indexOf("repeated")
        var tapped: Pair<Int, Int>? = null
        compose.setContent {
            LexiconTheme(AppSettings()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    FocusedArticleText(state, TextStyle(fontSize = 18.sp, lineHeight = 31.5.sp), Modifier.fillMaxWidth(),
                        highlights = listOf(StudyHighlight("real", "repeated", start, start + 8, "vocabulary", "A recurring expression."),
                            StudyHighlight("invalid", "not in the source", 0, 4, "sentence", "Invalid input must not move text.")),
                        focusEnabled = false, emphasizedParagraphs = setOf(0, 2),
                        onTapWord = { from, to -> tapped = from to to })
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("zen_article_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val point = layouts.single().getBoundingBox(start + 3).center
        compose.onNodeWithTag("zen_article_text").performTouchInput { click(point) }
        compose.runOnIdle {
            assertEquals(start to start + 8, tapped)
            assertEquals(article, state.text.toString())
            assertTrue(state.selection.collapsed)
        }
    }

    private fun saveScreenshot(name: String) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("focus-artifacts")
        directory.mkdirs()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        directory.resolve(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
