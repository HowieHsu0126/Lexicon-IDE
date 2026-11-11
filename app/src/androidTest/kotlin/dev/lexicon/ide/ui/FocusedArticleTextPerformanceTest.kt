package dev.lexicon.ide.ui

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.AppSettings
import dev.lexicon.domain.Article
import dev.lexicon.domain.ArticleBody
import dev.lexicon.domain.ParagraphFocus
import dev.lexicon.domain.ReadingParagraphBounds
import dev.lexicon.domain.ReadingViewport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Counts source work only. Compose's test clock is not evidence of device frame rate. */
@RunWith(AndroidJUnit4::class)
class FocusedArticleTextPerformanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allocationFreeViewportSelectionMatchesTheReferenceAndReleasesTapPin() {
        var top = 0f
        val bounds = (0 until 80).map { index ->
            val height = 70f + (index % 7) * 43f
            ReadingParagraphBounds(index, top, top + height).also { top += height + 31f }
        }
        val tracker = ParagraphViewportTracker()
        var previous: Int? = 0
        for (position in -200..top.toInt() step 17) {
            val viewport = ReadingViewport(position.toFloat(), position + 620f)
            tracker.update(viewport.top, viewport.bottom)
            val expected = ParagraphFocus.choose(bounds, viewport, previous, hysteresis = 24f)
            assertEquals(expected, tracker.choose(bounds, previous, 24f, 18f))
            previous = expected
        }
        tracker.update(300f, 920f)
        tracker.pin(0)
        tracker.update(315f, 935f)
        assertEquals(0, tracker.choose(bounds, previous, 24f, 18f))
        tracker.update(319f, 939f)
        assertEquals(ParagraphFocus.choose(bounds, ReadingViewport(319f, 939f), previous, hysteresis = 24f),
            tracker.choose(bounds, previous, 24f, 18f))
    }

    @Test fun scrollingInsideOneParagraphDoesNotRecomposeOrReplaceItsLayout() {
        val probe = ReaderRenderProbe()
        val text = List(40) { "A careful reader compares the evidence before reaching a conclusion." }.joinToString(" ") +
            "\n\nThe following paragraph remains outside the first viewport."
        val state = TextFieldState(text, TextRange.Zero)
        lateinit var scroll: ScrollState
        lateinit var scope: CoroutineScope
        compose.setContent {
            CompositionLocalProvider(LocalReaderRenderProbe provides probe) {
                LexiconTheme(AppSettings()) {
                    scroll = rememberScrollState()
                    scope = rememberCoroutineScope()
                    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                        FocusedArticleText(state, TextStyle(fontSize = 18.sp, lineHeight = 31.5.sp), Modifier.fillMaxWidth())
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val before = probe.snapshot()
        repeat(12) { step ->
            compose.runOnIdle { scope.launch { scroll.scrollTo((step + 1) * 6) } }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        val after = probe.snapshot()
        assertTrue(probe.count(ReaderRenderEvent.VIEWPORT_CALLBACK) > before.getValue("VIEWPORT_CALLBACK"))
        assertEquals(before.getValue("TEXT_COMPOSITION"), after.getValue("TEXT_COMPOSITION"))
        assertEquals(before.getValue("TEXT_LAYOUT_UPDATE"), after.getValue("TEXT_LAYOUT_UPDATE"))
        assertEquals(text, state.text.toString())
        Log.i("LexiconRenderProbe", "same-paragraph before=$before after=$after")
    }

    @Test fun paragraphCrossfadeUsesDrawPhaseAndACompactGradient() {
        val probe = ReaderRenderProbe()
        val text = (1..48).joinToString("\n\n") { "Paragraph $it. The evidence supports a careful, limited conclusion." }
        val state = TextFieldState(text, TextRange.Zero)
        var emphasized by mutableStateOf(setOf(0))
        compose.setContent {
            CompositionLocalProvider(LocalReaderRenderProbe provides probe) {
                LexiconTheme(AppSettings()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        FocusedArticleText(state, TextStyle(fontSize = 18.sp, lineHeight = 31.5.sp), Modifier.fillMaxWidth(),
                            focusEnabled = false, emphasizedParagraphs = emphasized)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { emphasized = setOf(1) }
        compose.mainClock.advanceTimeBy(64)
        compose.onRoot().captureToImage()
        val before = probe.snapshot()
        repeat(8) {
            compose.mainClock.advanceTimeByFrame()
            compose.onRoot().captureToImage()
        }
        val after = probe.snapshot()
        assertTrue(after.getValue("TEXT_DRAW") > before.getValue("TEXT_DRAW"))
        assertEquals(before.getValue("TEXT_COMPOSITION"), after.getValue("TEXT_COMPOSITION"))
        assertEquals(before.getValue("TEXT_LAYOUT_UPDATE"), after.getValue("TEXT_LAYOUT_UPDATE"))
        assertTrue("Uniform dim paragraphs should not add gradient stops", probe.maxGradientStops <= 10)
        assertEquals(text, state.text.toString())
        Log.i("LexiconRenderProbe", "crossfade before=$before after=$after")
        compose.mainClock.autoAdvance = true
    }

    @Test fun focusTimerTicksDoNotRecomposeTheArticle() {
        val probe = ReaderRenderProbe()
        val text = "A reader studies the original evidence. A quiet focus timer must not rebuild this article."
        val article = Article("perf-timer", "perf-timer", listOf("synthetic"), "Synthetic reading fixture", "https://example.org/perf", "")
        val body = ArticleBody(article.id, text, fingerprint(text), 1, Long.MAX_VALUE, "Synthetic test material")
        compose.setContent {
            CompositionLocalProvider(LocalReaderRenderProbe provides probe) {
                LexiconTheme(AppSettings()) {
                    ZenReader(article, body, false, AppSettings(focusTimer = true), true, false,
                        {}, {}, {}, {}, {}, { _, _ -> })
                }
            }
        }
        compose.mainClock.advanceTimeBy(3_700)
        compose.waitForIdle()
        val before = probe.snapshot()
        // The displayed clock uses wall time; allow its value to change before advancing delay jobs.
        val start = SystemClock.uptimeMillis()
        compose.waitUntil(3_000) { SystemClock.uptimeMillis() - start >= 1_100 }
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        val after = probe.snapshot()
        assertTrue(after.getValue("TIMER_TICK") - before.getValue("TIMER_TICK") >= 4)
        assertEquals(before.getValue("READER_COMPOSITION"), after.getValue("READER_COMPOSITION"))
        assertEquals(before.getValue("TEXT_COMPOSITION"), after.getValue("TEXT_COMPOSITION"))
        assertEquals(before.getValue("TEXT_LAYOUT_UPDATE"), after.getValue("TEXT_LAYOUT_UPDATE"))
        Log.i("LexiconRenderProbe", "timer before=$before after=$after")
    }
}
