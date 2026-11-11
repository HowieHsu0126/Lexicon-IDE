package dev.lexicon.ide.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.AppSettings
import dev.lexicon.domain.CardPhase
import dev.lexicon.domain.Collocation
import dev.lexicon.domain.FsrsState
import dev.lexicon.domain.Rating
import dev.lexicon.domain.ReviewDayRange
import dev.lexicon.domain.ReviewDaySummary
import dev.lexicon.domain.ReviewReceipt
import dev.lexicon.domain.SourceRef
import dev.lexicon.domain.ThemeMode
import dev.lexicon.domain.VocabularyContent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import dev.lexicon.domain.Card as LearningCard

/** Synthetic component state tests. No repository, stored review log or model is modified. */
@RunWith(AndroidJUnit4::class)
class ReviewCompletionUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = 5_000L
    private val day = ReviewDayRange(0, 86_400_000)
    private val original = card("first", "robust evidence")

    @Test fun successCallbackBeforeFlowsHidesTheAppliedRevisionUntilCommittedHistoryArrives() {
        val cards = mutableStateOf(listOf(original))
        val progress = mutableStateOf(stateFor())
        val busy = mutableStateOf(false)
        var saved: (() -> Unit)? = null
        var submitted = 0
        compose.setContent { TestTheme {
            ReviewScreen(cards.value, busy.value, { _, _, done -> submitted++; busy.value = true; saved = done }, {},
                now = now, progress = progress.value, onOpenCard = {})
        } }
        rateGood()
        compose.runOnIdle { requireNotNull(saved).invoke(); busy.value = false }
        settle()
        compose.onNodeWithTag("review_completion").assertExists()
        compose.onNodeWithTag("review_completion_count").assertDoesNotExist()
        compose.onNodeWithTag("review_history_status").assertTextEquals("正在更新复习记录…")
        compose.onNodeWithTag("review_answer").assertDoesNotExist()
        compose.onNodeWithTag("review_rate_good").assertDoesNotExist()
        compose.runOnIdle { cards.value = listOf(reviewed(original)) }
        settle()
        compose.onNodeWithTag("review_completion_count").assertDoesNotExist()
        compose.runOnIdle { progress.value = stateFor(receipt(original)); requireNotNull(saved).invoke() }
        settle()
        compose.onNodeWithTag("review_completion_count").assertTextEquals("今日完成 1 次复习")
        compose.onNodeWithTag("review_history_status").assertDoesNotExist()
        compose.onNodeWithTag("review_last_headword").assertTextEquals(original.content.headword)
        compose.onNodeWithTag("review_last_card").assertIsEnabled()
        assertEquals(1, submitted)
    }

    @Test fun committedHistoryBeforeCallbackHidesLaggingCardsAndALateCallbackKeepsTheNextAnswer() {
        val next = card("next", "qualified conclusion").copy(state = FsrsState(dueAt = 2_000))
        val cards = mutableStateOf(listOf(next, original))
        val progress = mutableStateOf(stateFor())
        var saved: (() -> Unit)? = null
        var submitted = 0
        val opened = mutableListOf<String>()
        compose.setContent { TestTheme {
            ReviewScreen(cards.value, false, { selected, _, done ->
                assertEquals(original.id, selected.id); submitted++; saved = done
            }, {}, now = now, progress = progress.value, onOpenCard = { opened += it })
        } }
        compose.onNodeWithText(original.content.headword).assertExists()
        rateGood()
        compose.runOnIdle { progress.value = stateFor(receipt(original)) }
        settle()
        compose.onNodeWithText(next.content.headword).assertIsDisplayed()
        compose.onNodeWithTag("review_counts").assertTextEquals("今日完成 1 次复习 · 1 待复习")
        compose.onNodeWithTag("review_answer").assertExists()
        compose.onNodeWithTag("review_last_headword").assertDoesNotExist()
        compose.onNodeWithTag("review_last_source").assertDoesNotExist()
        compose.onNodeWithTag("review_last_card").assertTextContains("回看刚复习的卡片").performScrollTo().performClick()
        compose.onNodeWithTag("review_answer").performScrollTo().performClick()
        compose.onNodeWithTag("review_rate_good").performScrollTo()
        val before = position()
        compose.runOnIdle { requireNotNull(saved).invoke(); requireNotNull(saved).invoke() }
        settle()
        compose.onNodeWithTag("review_answer").assertDoesNotExist()
        compose.onNodeWithTag("review_rate_good").assertIsEnabled()
        assertEquals(before, position(), 1f)
        compose.runOnIdle { cards.value = listOf(reviewed(original), next) }
        settle()
        compose.onNodeWithTag("review_counts").assertTextEquals("今日完成 1 次复习 · 1 待复习")
        assertEquals(listOf(original.id), opened)
        assertEquals(1, submitted)
    }

    @Test fun unknownAndFailedHistoryNeverBecomeZeroAndAMissingCardStillUsesItsReceiptId() {
        val progress = mutableStateOf(ReviewProgressUiState(day, loading = true))
        val cards = mutableStateOf(emptyList<LearningCard>())
        val sourceAvailable = mutableStateOf(true)
        val opened = mutableListOf<String>()
        val sources = mutableListOf<LearningCard>()
        var retries = 0
        var reads = 0
        compose.setContent { TestTheme {
            ReviewScreen(cards.value, false, { _, _, _ -> error("A completed card must not be rated") }, { reads++ },
                now = now, progress = progress.value, onOpenCard = { opened += it },
                onOpenSource = { sources += it }, canOpenSource = { sourceAvailable.value }, onRetryHistory = { retries++ })
        } }
        compose.onNodeWithTag("review_completion_count").assertDoesNotExist()
        compose.onNodeWithTag("review_history_status").assertTextEquals("正在读取复习记录…")
        compose.runOnIdle { progress.value = ReviewProgressUiState(day, failed = true) }
        settle()
        compose.onNodeWithTag("review_completion_count").assertDoesNotExist()
        compose.onNodeWithTag("review_history_retry").performScrollTo().performClick()
        assertEquals(1, retries)
        compose.runOnIdle { progress.value = stateFor(receipt(original)) }
        settle()
        compose.onNodeWithTag("review_last_card").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("review_last_source").assertDoesNotExist()
        assertEquals(listOf(original.id), opened)
        compose.runOnIdle { cards.value = listOf(reviewed(original)) }
        settle()
        compose.onNodeWithTag("review_last_headword").assertTextEquals(original.content.headword)
        compose.onNodeWithTag("review_last_source").performScrollTo().performClick()
        assertEquals(listOf(reviewed(original)), sources)
        compose.runOnIdle { sourceAvailable.value = false }
        settle()
        compose.onNodeWithTag("review_last_source").assertDoesNotExist()
        compose.onNodeWithTag("review_return_reading").performScrollTo().performClick()
        assertEquals(1, reads)
    }

    @Test fun anOlderSuccessCallbackCannotReplaceALaterCommittedCompletion() {
        val next = card("next", "qualified conclusion").copy(state = FsrsState(dueAt = 2_000))
        val cards = mutableStateOf(listOf(original, next))
        val progress = mutableStateOf(stateFor())
        val callbacks = mutableMapOf<String, () -> Unit>()
        compose.setContent { TestTheme {
            ReviewScreen(cards.value, false, { selected, _, done -> callbacks[selected.id] = done }, {}, now = now,
                progress = progress.value, onOpenCard = {})
        } }
        rateGood()
        compose.runOnIdle { progress.value = stateFor(receipt(original)) }
        settle()
        rateGood()
        compose.runOnIdle {
            cards.value = listOf(reviewed(original), reviewed(next))
            progress.value = stateFor(receipt(next), count = 2)
            callbacks.getValue(next.id).invoke()
        }
        settle()
        compose.onNodeWithTag("review_completion_count").assertTextEquals("今日完成 2 次复习")
        compose.runOnIdle { callbacks.getValue(original.id).invoke(); callbacks.getValue(next.id).invoke() }
        settle()
        compose.onNodeWithTag("review_completion_count").assertTextEquals("今日完成 2 次复习")
        compose.onNodeWithTag("review_last_headword").assertTextEquals(next.content.headword)
        compose.onNodeWithTag("review_history_status").assertDoesNotExist()
        compose.onNodeWithTag("review_answer").assertDoesNotExist()
    }

    @Test fun aConflatedHistorySnapshotCanFinishRefreshingWithAnotherCardsLatestReceipt() {
        val other = card("other", "independent evidence").copy(state = FsrsState(dueAt = now + 600_000))
        val cards = mutableStateOf(listOf(original, other))
        val progress = mutableStateOf(stateFor())
        val opened = mutableListOf<String>()
        var saved: (() -> Unit)? = null
        compose.setContent { TestTheme {
            ReviewScreen(cards.value, false, { selected, _, done ->
                assertEquals(original.id, selected.id); saved = done
            }, {}, now = now, progress = progress.value, onOpenCard = { opened += it })
        } }
        rateGood()
        compose.runOnIdle { requireNotNull(saved).invoke() }
        settle()
        compose.onNodeWithTag("review_history_status").assertTextEquals("正在更新复习记录…")
        compose.onNodeWithTag("review_completion_count").assertDoesNotExist()
        // Two committed writes are delivered as one observable snapshot. A's own
        // receipt never appears as latest; B is now the actual latest review.
        val latest = ReviewReceipt("log-other", other.id, now + 1, Rating.HARD, 0, 1)
        compose.runOnIdle { cards.value = listOf(original, reviewed(other)); progress.value = stateFor(latest, count = 2) }
        settle()
        compose.onNodeWithTag("review_history_status").assertDoesNotExist()
        compose.onNodeWithTag("review_completion_count").assertTextEquals("今日完成 2 次复习")
        compose.onNodeWithTag("review_last_headword").assertTextEquals(other.content.headword)
        compose.onNodeWithTag("review_last_rating").assertTextEquals("Hard · 困难")
        compose.onNodeWithTag("review_last_card").performScrollTo().performClick()
        assertEquals(listOf(other.id), opened)
        compose.onNodeWithTag("review_answer").assertDoesNotExist()
        compose.onNodeWithTag("review_rate_good").assertDoesNotExist()
    }

    @Test fun anAgainCardCanBecomeDueAtItsNewRepetitionWithoutRevealingThePreviousAnswer() {
        val clock = mutableStateOf(now)
        val waiting = reviewed(original).copy(state = reviewed(original).state.copy(dueAt = now + 60_000))
        val opened = mutableListOf<String>()
        compose.setContent { TestTheme {
            ReviewScreen(listOf(waiting), false, { _, _, _ -> error("Viewing is not a rating") }, {}, now = clock.value,
                progress = stateFor(receipt(original, Rating.AGAIN)), onOpenCard = { opened += it })
        } }
        compose.onNodeWithTag("review_completion").assertExists()
        compose.onNodeWithTag("review_last_rating").assertTextEquals("Again · 忘记")
        compose.runOnIdle { clock.value = waiting.state.dueAt }
        settle()
        compose.onNodeWithTag("review_completion").assertDoesNotExist()
        compose.onNodeWithTag("review_counts").assertTextEquals("今日完成 1 次复习 · 1 待复习")
        compose.onNodeWithTag("review_answer").assertExists()
        compose.onNodeWithTag("review_rate_again").assertDoesNotExist()
        assertEquals(0f, position(), 1f)
        compose.onNodeWithTag("review_last_card").performScrollTo().performClick()
        assertEquals(listOf(original.id), opened)
    }

    @Test fun completionRestorationKeepsCommittedProgressWhileCardsReloadWithoutWriting() {
        val cards = mutableStateOf(listOf(reviewed(original)))
        val restoration = StateRestorationTester(compose)
        val opened = mutableListOf<String>()
        restoration.setContent { TestTheme {
            ReviewScreen(cards.value, false, { _, _, _ -> error("Restoration must not replay a rating") }, {}, now = now,
                progress = stateFor(receipt(original), count = 3), onOpenCard = { opened += it })
        } }
        compose.onNodeWithTag("review_completion_count").assertTextEquals("今日完成 3 次复习")
        compose.runOnIdle { cards.value = emptyList() }
        restoration.emulateSavedInstanceStateRestore()
        settle()
        compose.onNodeWithTag("review_completion_count").assertTextEquals("今日完成 3 次复习")
        compose.onNodeWithTag("review_last_card").performScrollTo().performClick()
        compose.onNodeWithTag("review_answer").assertDoesNotExist()
        compose.runOnIdle { cards.value = listOf(reviewed(original)) }
        settle()
        compose.onNodeWithTag("review_last_headword").assertTextEquals(original.content.headword)
        assertEquals(listOf(original.id), opened)
    }

    @Test fun aNewDayDropsYesterdaysReceiptAndKeepsUnknownProgressDistinctFromCommittedZero() {
        val progress = mutableStateOf(stateFor(receipt(original)))
        compose.setContent { TestTheme {
            ReviewScreen(emptyList(), false, { _, _, _ -> error("Changing days must not rate a card") }, {}, now = now,
                progress = progress.value, onOpenCard = {})
        } }
        compose.onNodeWithTag("review_last_card").assertExists()
        val nextDay = ReviewDayRange(day.endExclusive, day.endExclusive + 86_400_000)
        compose.runOnIdle { progress.value = ReviewProgressUiState(nextDay, loading = true) }
        settle()
        compose.onNodeWithTag("review_completion_count").assertDoesNotExist()
        compose.onNodeWithTag("review_last_card").assertDoesNotExist()
        compose.onNodeWithTag("review_last_rating").assertDoesNotExist()
        compose.runOnIdle { progress.value = ReviewProgressUiState(nextDay, ReviewDaySummary()) }
        settle()
        compose.onNodeWithTag("review_completion_count").assertTextEquals("今日完成 0 次复习")
        compose.onNodeWithTag("review_history_status").assertDoesNotExist()
    }

    @Test fun aRejectedSaveKeepsTheAnswerAndCanBeRetriedWithoutInventingProgress() {
        val cards = mutableStateOf(listOf(original))
        val progress = mutableStateOf(stateFor())
        val busy = mutableStateOf(false)
        var submitted = 0
        var saved: (() -> Unit)? = null
        compose.setContent { TestTheme {
            ReviewScreen(cards.value, busy.value, { _, _, done -> submitted++; busy.value = true; saved = done }, {},
                now = now, progress = progress.value, onOpenCard = {})
        } }
        compose.onNodeWithTag("review_answer").performScrollTo().performClick()
        compose.onNodeWithTag("review_rate_good").performScrollTo()
        val before = position()
        compose.onNodeWithTag("review_rate_good").performClick()
        compose.onNodeWithTag("review_rate_good").assertIsNotEnabled()
        compose.runOnIdle { busy.value = false }
        settle()
        compose.onNodeWithTag("review_answer").assertDoesNotExist()
        compose.onNodeWithTag("review_rate_good").assertIsEnabled()
        compose.onNodeWithTag("review_counts").assertTextEquals("今日完成 0 次复习 · 1 待复习")
        compose.onNodeWithTag("review_last_card").assertDoesNotExist()
        assertEquals(before, position(), 1f)
        compose.onNodeWithTag("review_rate_good").performClick()
        compose.runOnIdle {
            cards.value = listOf(reviewed(original)); progress.value = stateFor(receipt(original))
            requireNotNull(saved).invoke(); busy.value = false
        }
        settle()
        compose.onNodeWithTag("review_completion_count").assertTextEquals("今日完成 1 次复习")
        assertEquals(2, submitted)
    }

    @Test fun yesterdaysLateCallbackCannotWaitForItsReceiptInsideTheNewDay() {
        val progress = mutableStateOf(stateFor())
        val clock = mutableStateOf(now)
        var saved: (() -> Unit)? = null
        compose.setContent { TestTheme {
            ReviewScreen(listOf(original), false, { _, _, done -> saved = done }, {}, now = clock.value,
                progress = progress.value, onOpenCard = {})
        } }
        rateGood()
        val nextDay = ReviewDayRange(day.endExclusive, day.endExclusive + 86_400_000)
        compose.runOnIdle {
            progress.value = ReviewProgressUiState(nextDay, ReviewDaySummary())
            clock.value = nextDay.startInclusive + now
        }
        settle()
        compose.runOnIdle { requireNotNull(saved).invoke() }
        settle()
        compose.onNodeWithTag("review_completion_count").assertTextEquals("今日完成 0 次复习")
        compose.onNodeWithTag("review_history_status").assertDoesNotExist()
        compose.onNodeWithTag("review_last_card").assertDoesNotExist()
        compose.onNodeWithTag("review_answer").assertDoesNotExist()
        compose.onNodeWithTag("review_rate_good").assertDoesNotExist()
    }

    @Test fun returningAfterDayChangeDiscardsThePreviousDaysSavedHistoryWait() = pendingHistoryRoundTrip(changeDay = true)

    @Test fun sameDayWorkspaceReturnKeepsPendingHistoryProtectionUntilTheCommittedUpdate() = pendingHistoryRoundTrip(changeDay = false)

    private fun pendingHistoryRoundTrip(changeDay: Boolean) {
        val previousCard = card("previous", "qualified conclusion")
        val progress = mutableStateOf(stateFor(receipt(previousCard), count = 3))
        val cards = mutableStateOf(listOf(original))
        val clock = mutableStateOf(now)
        val showReview = mutableStateOf(true)
        var saved: (() -> Unit)? = null
        var ratings = 0
        compose.setContent { TestTheme {
            val holder = rememberSaveableStateHolder()
            if (showReview.value) holder.SaveableStateProvider("review") {
                ReviewScreen(cards.value, false, { _, _, done -> ratings++; saved = done }, { showReview.value = false },
                    now = clock.value, progress = progress.value, onOpenCard = {})
            } else Button(onClick = { showReview.value = true }, modifier = Modifier.testTag("review_day_reentry")) {
                Text("返回复习")
            }
        } }
        rateGood()
        compose.runOnIdle {
            cards.value = listOf(reviewed(original).let { it.copy(state = it.state.copy(dueAt = Long.MAX_VALUE)) })
            progress.value = progress.value.copy(failed = true)
            requireNotNull(saved).invoke()
        }
        settle()
        compose.onNodeWithTag("review_completion_count").assertDoesNotExist()
        compose.onNodeWithTag("review_history_status").assertTextEquals("复习记录暂不可用")
        compose.onNodeWithTag("review_return_reading").performScrollTo().performClick()
        settle()
        compose.onNodeWithTag("review_completion").assertDoesNotExist()
        compose.onNodeWithTag("review_day_reentry").assertExists()

        if (changeDay) {
            val nextDay = ReviewDayRange(day.endExclusive, day.endExclusive + 86_400_000)
            // The app's root observes the new local day while this screen is absent.
            compose.runOnIdle {
                progress.value = ReviewProgressUiState(nextDay, ReviewDaySummary())
                clock.value = nextDay.startInclusive + now
            }
            settle()
        }
        compose.onNodeWithTag("review_day_reentry").performClick()
        settle()
        if (changeDay) {
            compose.onNodeWithTag("review_completion_count").assertTextEquals("今日完成 0 次复习")
            compose.onNodeWithTag("review_last_card").assertDoesNotExist()
        } else {
            compose.onNodeWithTag("review_completion_count").assertDoesNotExist()
            compose.onNodeWithTag("review_history_status").assertTextEquals("复习记录暂不可用")
            compose.runOnIdle { progress.value = stateFor(receipt(original), count = 4) }
            settle()
            compose.onNodeWithTag("review_completion_count").assertTextEquals("今日完成 4 次复习")
            compose.onNodeWithTag("review_last_headword").assertTextEquals(original.content.headword)
        }
        compose.onNodeWithTag("review_history_status").assertDoesNotExist()
        compose.onNodeWithTag("review_answer").assertDoesNotExist()
        compose.onNodeWithTag("review_rate_good").assertDoesNotExist()
        assertEquals(1, ratings)
    }

    @Test fun legacyCompletionCallbackCanOnlyCountItsSubmissionOnce() {
        val cards = mutableStateOf(listOf(original))
        compose.setContent { TestTheme {
            ReviewScreen(cards.value, false, { _, _, done -> cards.value = emptyList(); done(); done() }, {}, now = now)
        } }
        rateGood()
        settle()
        compose.onNodeWithTag("review_completion_count").assertTextEquals("本轮完成 1 次复习。")
    }

    @Test fun daylightCompletionRemainsReadableAndReachableAtTwoHundredPercent() = layoutTrial(ThemeMode.LIGHT)

    @Test fun draculaCompletionRemainsReadableAndReachableAtTwoHundredPercent() = layoutTrial(ThemeMode.DARK)

    private fun layoutTrial(theme: ThemeMode) {
        val run = (InstrumentationRegistry.getArguments().getString("reviewCompletionRun") ?: "local-${UUID.randomUUID()}").also {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")))
        }
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "validation-1.24.0/review-completion/${theme.name.lowercase()}/$run")
        check(!output.exists() && output.mkdirs()) { "Use a new reviewCompletionRun to preserve earlier evidence" }
        val report = JSONObject().put("schema", "lexicon.review.completion.layout.v1").put("theme", theme.name)
            .put("fontScaleOverride", 2.0).put("scope", "Synthetic component; no stored reviews or device font setting changes")
            .put("textLayouts", JSONObject()).put("screenshots", JSONArray())
        var passed = false
        try {
            val longCard = reviewed(original.copy(content = original.content.copy(headword = "context-sensitive analytical judgment")))
            var cardOpens = 0; var sourceOpens = 0; var reads = 0
            compose.setContent { TestTheme(theme, 2f) {
                ReviewScreen(listOf(longCard), false, { _, _, _ -> error("Completion has no rating action") }, { reads++ }, now = now,
                    progress = stateFor(receipt(original), count = 127), onOpenCard = { cardOpens++ }, onOpenSource = { sourceOpens++ })
            } }
            settle()
            listOf("review_completion_title", "review_completion_count", "review_last_headword", "review_last_rating").forEach { tag ->
                val node = compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                inspectText(tag, node, report)
            }
            screenshot(output, report, "01-completion-large-type.png")
            listOf("review_last_card" to "回看这张卡", "review_last_source" to "回原文", "review_return_reading" to "回到阅读")
                .forEach { (tag, text) ->
                    val action = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
                    val node = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
                    inspectText(tag, node, report)
                    assertInside(node.fetchSemanticsNode().boundsInRoot, action.fetchSemanticsNode().boundsInRoot, "$tag label")
                    action.performClick()
                }
            assertEquals(1, cardOpens); assertEquals(1, sourceOpens); assertEquals(1, reads)
            compose.onNodeWithTag("review_rate_good").assertDoesNotExist()
            screenshot(output, report, "02-actions-large-type.png")
            passed = true
        } catch (error: Throwable) {
            report.put("failureClass", error.javaClass.simpleName)
                .put("failureLocations", JSONArray(error.stackTrace.take(5).map { "${it.fileName}:${it.lineNumber}" }))
            runCatching { screenshot(output, report, "failure.png") }
            throw error
        } finally {
            report.put("status", if (passed) "passed" else "failed")
            File(output, "layout-report.json").writeText(report.toString(2))
        }
    }

    private fun inspectText(name: String, node: SemanticsNodeInteraction, report: JSONObject) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val diagnostics = JSONObject().put("bounds", coordinates(bounds)).put("count", layouts.size)
            .put("results", JSONArray(layouts.map { layout ->
                JSONObject().put("width", layout.size.width).put("height", layout.size.height)
                    .put("didOverflowWidth", layout.didOverflowWidth).put("didOverflowHeight", layout.didOverflowHeight)
                    .put("hasVisualOverflow", layout.hasVisualOverflow).put("paragraphWidth", layout.multiParagraph.width)
                    .put("textLength", layout.layoutInput.text.length).put("lineCount", layout.lineCount)
                    .put("lines", JSONArray((0 until layout.lineCount).map { line ->
                        JSONObject().put("left", layout.getLineLeft(line)).put("right", layout.getLineRight(line))
                            .put("top", layout.getLineTop(line)).put("bottom", layout.getLineBottom(line))
                            .put("start", layout.getLineStart(line)).put("end", layout.getLineEnd(line))
                            .put("isEllipsized", layout.isLineEllipsized(line))
                    }))
            }))
        report.getJSONObject("textLayouts").put(name, diagnostics)
        assertInside(bounds, compose.onNodeWithTag("review_completion_area").fetchSemanticsNode().boundsInRoot, name)
        assertTrue("$name must expose text layouts", layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertTrue("$name must have nonempty text dimensions", layout.size.width > 0 && layout.size.height > 0 && layout.lineCount > 0)
            assertTrue("$name must not overflow vertically", !layout.didOverflowHeight)
            val horizontal = -1f..(layout.size.width + 1f)
            val vertical = -1f..(layout.size.height + 1f)
            repeat(layout.lineCount) { line ->
                assertTrue("$name line $line must not be ellipsized", !layout.isLineEllipsized(line))
                assertTrue("$name line $line must fit its actual text layout",
                    layout.getLineLeft(line) in horizontal && layout.getLineRight(line) in horizontal &&
                        layout.getLineTop(line) in vertical && layout.getLineBottom(line) in vertical)
            }
            assertEquals("$name must retain every input character", layout.layoutInput.text.length,
                layout.getLineEnd(layout.lineCount - 1, visibleEnd = false))
        }
    }

    private fun assertInside(inner: Rect, outer: Rect, name: String) {
        assertTrue("$name must have nonempty visible bounds", inner.width > 0f && inner.height > 0f)
        assertTrue("$name must remain within its parent", inner.left >= outer.left - 1f && inner.right <= outer.right + 1f &&
            inner.top >= outer.top - 1f && inner.bottom <= outer.bottom + 1f)
    }

    private fun screenshot(output: File, report: JSONObject, name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val target = File(output, name)
        check(!target.exists())
        try { target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        report.getJSONArray("screenshots").put(name)
    }

    private fun coordinates(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
    private fun rateGood() {
        compose.onNodeWithTag("review_answer").performScrollTo().performClick()
        compose.onNodeWithTag("review_rate_good").performScrollTo().performClick()
    }
    private fun settle() { compose.mainClock.advanceTimeBy(600); compose.waitForIdle() }
    private fun position() = compose.onNodeWithTag("review_card_scroll").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun stateFor(receipt: ReviewReceipt? = null, count: Int = if (receipt == null) 0 else 1) =
        ReviewProgressUiState(day, ReviewDaySummary(count, receipt))
    private fun receipt(card: LearningCard, rating: Rating = Rating.GOOD) =
        ReviewReceipt("log-${card.id}", card.id, now, rating, card.state.reps, card.state.reps + 1)
    private fun reviewed(card: LearningCard) = card.copy(state = card.state.copy(phase = CardPhase.REVIEW, reps = card.state.reps + 1, dueAt = now + 600_000))
    private fun card(id: String, word: String) = LearningCard(id, "capture-$id", VocabularyContent(word,
        "Reliable under the conditions described by the author. ".repeat(5),
        listOf(Collocation("robust evidence", "The evidence remains robust under the stated conditions."),
            Collocation("robust method", "A robust method handles variation.")),
        "well-supported in the stated context", "neutral", "high"),
        "The $word supports a careful interpretation of the author's argument. ".repeat(5),
        SourceRef("A synthetic article for review-state testing", "Synthetic source", "https://example.invalid/review"),
        state = FsrsState(dueAt = 1_000), createdAt = 1, updatedAt = 2)

    @Composable private fun TestTheme(theme: ThemeMode = ThemeMode.DARK, fontScale: Float = 1f, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            LexiconTheme(AppSettings(theme = theme)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().testTag("review_completion_area")) { content() }
                }
            }
        }
    }
}
