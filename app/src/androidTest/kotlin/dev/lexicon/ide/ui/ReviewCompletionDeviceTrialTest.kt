package dev.lexicon.ide.ui

import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import dev.lexicon.ide.LexiconApplication
import dev.lexicon.ide.MainActivity
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in, actual MainActivity and application Repository in a dedicated secondary Android user.
 * The user must contain no learning materials. One self-authored article/capture/card is inserted
 * as an explicit fixture; rating follows the real UI -> ViewModel -> Room -> FSRS path. No original
 * row is paused, rated, replaced, or restored. Host-side protection of user 0 is a separate check.
 *
 * Arguments: reviewCompletionTrial=1, expectedUserId=<nonzero>, phase=before|after,
 * evidenceRun=<unique run>. No API key and no active network are allowed.
 */
@RunWith(AndroidJUnit4::class)
class ReviewCompletionDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Independent 1.25 observer. It never rates the fixture or changes the 1.24 completion trial.
     * Before records whichever paragraph actually settles; only after requires the target focus.
     */
    @Test fun ownedCardSourceReportsStableAnchorFocusBeforeAndAfterActivityRecreation() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Reader anchor observation is opt-in", args.getString("readerAnchorFocusTrial") == "1")
        val phase = requireNotNull(args.getString("phase")).also { require(it in setOf("before", "after")) }
        val run = requireNotNull(args.getString("evidenceRun") ?: args.getString("uniqueRun")).also {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")))
        }
        val expectedUser = (args.getString("expectedUserId") ?: "10").toInt()
        require(expectedUser == 10) { "Reader observation is restricted to dedicated Android user 10" }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiconApplication
        val actualUser = Regex("^/data/user/(\\d+)/").find(app.applicationInfo.dataDir)?.groupValues?.get(1)?.toInt()
        check(actualUser == 10) { "Reader observation may not run against the original user" }
        val output = File(app.filesDir, "validation-1.25.0/reader-anchor/$phase/$run")
        check(!output.exists() && output.mkdirs()) { "Use a new evidenceRun; preserve previous observations" }
        val report = JSONObject().put("schema", "lexicon.reader.anchor.focus.trial.v1")
            .put("status", "started").put("phase", phase).put("evidenceRun", run)
            .put("androidUserId", actualUser).put("actualMainActivity", true)
            .put("repositoryScope", "Unfiltered application Repository in dedicated Android user 10")
            .put("materialOrigin", "Self-authored synthetic article and card; not publisher or user learning evidence")
            .put("jsonContainsLearningText", false).put("aiActions", 0).put("ratingsRequested", 0)
            .put("fixtureInsertSucceeded", false).put("originalRowsRestoredOrReplaced", 0)
            .put("screenshots", JSONArray()).put("observations", JSONArray())
        val reportFile = File(output, "device-trial.json")
        reportFile.writeText(report.toString(2))
        var trial: Trial? = null
        var observed = false
        var preserved = false
        var desiredBehavior = false
        try {
            requireOfflineNoKey(app)
            val repository = app.repository
            runBlocking { repository.initialize() }
            val database = app.getDatabasePath("lexicon.sqlite")
            val original = waitForQuietRows(database)
            listOf("articles", "article_sources", "article_bodies", "captures", "cards", "review_logs", "ai_jobs").forEach {
                check(original.getValue(it).isEmpty()) { "Dedicated reader trial contains existing learning material" }
            }
            val settings = runBlocking { repository.settings.first() }
            check(settings.reader.comfort != ReadingComfort.HIGH_CONTRAST) { "Paragraph focus is disabled in high-contrast reading" }
            val started = System.currentTimeMillis()
            val fixture = Fixture(run, started)
            val t = Trial(app, database, original, output, report, started, fixture)
            trial = t
            val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
            report.put("version", packageInfo.versionName).put("versionCode", packageInfo.longVersionCode)
                .put("apkSha256", hash(File(InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.sourceDir)))
                .put("startedAt", started).put("actualFontScale", app.resources.configuration.fontScale)
                .put("theme", settings.theme.name).put("readerComfort", settings.reader.comfort.name)
                .put("actualNightMode", app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                .put("fixtureBodyHash", fixture.body.contentHash)
                .put("databasePath", database.absolutePath)
                .put("databaseDriver", "androidx.sqlite.driver.bundled.BundledSQLiteDriver")
                .put("baselineQuietIntervalMs", 2_000).put("focusStableIntervalRequiredMs", 1_200)
                .put("originalTableHashes", tableHashes(original)).put("originalSnapshotKeptOnlyInMemory", true)

            t.stage = "insert_unrated_owned_fixture"
            insertFixture(t)
            report.put("fixtureInsertion", "Six explicitly owned synthetic rows before launch; this observation never requests a rating")
            verifyEditionWillNotRewriteOriginal(t)
            verifyUnratedFixture(t)
            t.stage = "open_owned_card_without_reviewing"
            t.activity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(20_000) { exists("daily_reading") }
            compose.onNodeWithText(">_  命令").performClick()
            compose.onNodeWithText("搜索命令…").performTextInput("cards")
            compose.onNodeWithText("浏览生词卡").performClick()
            compose.waitUntil(15_000) { exists("card_library_${fixture.card.id}") }
            compose.onNodeWithTag("card_library_${fixture.card.id}").performScrollTo().performClick()
            awaitOwnedCard(t)
            screenshot(t, "01-owned-card-before-source.png")

            t.stage = "open_exact_owned_source"
            t.sourceOpened = true
            t.sourceOpenActions = 1
            compose.onNodeWithTag("card_open_source").performClick()
            awaitOriginal(t)
            val initial = observeStableAnchor(t, "source_arrival", "02-anchor-after-settling.png")
            verifyUnratedFixture(t)

            t.stage = "recreate_source_then_wait_for_stable_focus"
            requireNotNull(t.activity).recreate()
            awaitOriginal(t)
            val restored = observeStableAnchor(t, "source_after_activity_recreation", "03-anchor-after-recreation-and-settling.png")
            verifyUnratedFixture(t)
            desiredBehavior = initial.targetFocused && restored.targetFocused
            report.put("targetFocusedAtBothStableObservations", desiredBehavior)
                .put("activityRecreationObserved", true)
                .put("recoveryScope", "Activity recreation; not OS process termination")
            if (phase == "after") {
                check(desiredBehavior) { "Verify both explicit-anchor observations before exercising user takeover" }
                exerciseNaturalFocusTakeover(t, restored)
                verifyUnratedFixture(t)
            }

            t.stage = "return_to_same_unrated_card"
            back(t)
            awaitOwnedCard(t)
            verifyUnratedFixture(t)
            report.put("returnedToSameOwnedCard", true).put("sourceOpenActions", t.sourceOpenActions)
            observed = true
        } catch (error: Throwable) {
            report.put("failureClass", error.javaClass.simpleName).put("failedStage", trial?.stage ?: "preflight")
                .put("failureLocations", JSONArray(error.stackTrace.take(10).map { "${it.fileName}:${it.lineNumber}" }))
            trial?.let { runCatching { screenshot(it, "failure.png") } }
        } finally {
            trial?.let { t ->
                val jobs = existingWriterJobs(t)
                runCatching { t.activity?.close() }.onFailure { observed = false; report.put("activityCloseFailureClass", it.javaClass.simpleName) }
                t.activity = null
                runCatching {
                    runBlocking { withTimeout(15_000) { jobs.joinAll() } }
                    requireOfflineNoKey(app)
                    val settled = waitForQuietRows(t.database)
                    if (t.fixtureInsertSucceeded) {
                        verifyUnratedFixture(t)
                        cleanupOwnedRows(t, settled)
                    } else report.put("ownedCleanupSkipped", "Fixture insertion did not commit; no rows may be deleted")
                    val after = waitForQuietRows(t.database)
                    report.put("finalTableHashes", tableHashes(after))
                    check(after == t.original)
                    preserved = true
                }.onFailure { report.put("preservationFailureClass", it.javaClass.simpleName) }
            }
            report.put("fixtureInsertSucceeded", trial?.fixtureInsertSucceeded == true)
                .put("originalDataPreserved", preserved).put("allOriginalRowsCompared", preserved)
                .put("status", when {
                    !observed || !preserved -> "failed"
                    phase == "before" -> "baseline_observed"
                    desiredBehavior -> "passed"
                    else -> "failed"
                })
            reportFile.writeText(report.toString(2))
        }
        check(observed && preserved && (phase == "before" || desiredBehavior)) {
            "Reader anchor observation failed its phase contract; inspect the sanitized report"
        }
    }

    private data class AnchorObservation(val targetFocused: Boolean, val stableKey: String, val data: JSONObject)

    private fun exerciseNaturalFocusTakeover(t: Trial, initial: AnchorObservation) {
        val attempts = JSONArray()
        t.report.put("userSwipeAttempts", attempts)
        var previous = initial
        var natural: AnchorObservation? = null
        repeat(3) { index ->
            if (natural != null) return@repeat
            val scroll = readerScrollNode().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            val beforePosition = scroll.value()
            val maximum = scroll.maxValue()
            // Returning toward the start is useful when the short source is already near its end.
            // Read the range to choose a real available gesture; never write a scroll offset.
            val direction = when {
                beforePosition > 48f -> "toward_start"
                maximum - beforePosition > 48f -> "toward_end"
                else -> null
            }
            if (direction == null) {
                attempts.put(JSONObject().put("attempt", index + 1).put("gesturePerformed", false)
                    .put("scrollPosition", beforePosition).put("scrollMaximum", maximum)
                    .put("reason", "No useful scroll space at the current position"))
                return@repeat
            }
            t.stage = "natural_user_swipe_${index + 1}"
            val priorOrigin = previous.data.getJSONArray("textOrigin").getDouble(1)
            val viewport = compose.onNodeWithTag("zen_reader_viewport")
            val viewportBounds = viewport.fetchSemanticsNode().boundsInRoot
            val attempt = JSONObject().put("attempt", index + 1).put("gesturePerformed", true)
                .put("direction", direction).put("gesture", "Interior rendered-viewport touch swipe, duration 650ms")
                .put("scrollBefore", beforePosition).put("scrollMaximum", maximum)
            attempts.put(attempt)
            viewport.performTouchInput {
                // The top edge is a separate toolbar handle. Start inside the scrollable reader.
                val start = Offset(centerX, height * if (direction == "toward_start") .20f else .75f)
                val end = Offset(centerX, height * if (direction == "toward_start") .75f else .20f)
                attempt.put("gestureStartLocalPx", JSONArray(listOf(start.x, start.y)))
                    .put("gestureEndLocalPx", JSONArray(listOf(end.x, end.y)))
                    .put("gestureStartRootPx", JSONArray(listOf(viewportBounds.left + start.x, viewportBounds.top + start.y)))
                    .put("gestureEndRootPx", JSONArray(listOf(viewportBounds.left + end.x, viewportBounds.top + end.y)))
                swipe(start, end, durationMillis = 650)
            }
            val current = observeStableAnchor(t, "after_user_swipe_${index + 1}", "04-user-swipe-${index + 1}-settled.png")
            val afterPosition = readerScrollPosition()
            val originDelta = current.data.getJSONArray("textOrigin").getDouble(1) - priorOrigin
            val scrollDelta = afterPosition - beforePosition
            val moved = abs(originDelta) > 8.0 && abs(scrollDelta) > 8f
            val focused = current.data.getInt("focusedParagraph")
            val viewportParagraph = current.data.getInt("viewportParagraph")
            val target = current.data.getInt("targetParagraph")
            val tookOver = moved && focused == viewportParagraph && focused != target
            attempt.put("scrollAfter", afterPosition)
                .put("actualScrollDeltaPx", scrollDelta).put("actualTextOriginDeltaPx", originDelta)
                .put("actualMovementObserved", moved).put("focusedParagraph", focused)
                .put("viewportParagraph", viewportParagraph).put("userFocusTookOver", tookOver)
            previous = current
            if (tookOver) natural = current
        }
        val settled = requireNotNull(natural) { "No observed user movement established a stable neighbouring paragraph; do not claim takeover" }
        val beforeRecreationPosition = readerScrollPosition()
        val beforeRecreationOrigin = settled.data.getJSONArray("textOrigin").getDouble(1)
        t.report.put("userScrollTookOverFocus", true)
            .put("userSelectedParagraph", settled.data.getInt("focusedParagraph"))
            .put("userScrollUsedOnlyTouchInput", true)

        t.stage = "recreate_after_natural_user_scroll"
        requireNotNull(t.activity).recreate()
        // The user has intentionally left the original anchor. Do not demand that it is still visible.
        compose.waitUntil(20_000) {
            exists("zen_article_text") && runCatching {
                compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().config[SemanticsProperties.EditableText].text == t.fixture.body.text &&
                    compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().config[ReaderPositionReadyKey]
            }.getOrDefault(false)
        }
        val restored = observeStableAnchor(t, "user_position_after_activity_recreation", "05-user-position-after-recreation.png")
        val scrollDelta = readerScrollPosition() - beforeRecreationPosition
        val originDelta = restored.data.getJSONArray("textOrigin").getDouble(1) - beforeRecreationOrigin
        val sameFocus = restored.data.getInt("focusedParagraph") == settled.data.getInt("focusedParagraph") &&
            restored.data.getInt("viewportParagraph") == settled.data.getInt("viewportParagraph")
        val positionPreserved = abs(scrollDelta) <= 2f && abs(originDelta) <= 2.0
        t.report.put("userScrollRestorationDeltaPx", scrollDelta).put("userTextOriginRestorationDeltaPx", originDelta)
            .put("userFocusRetainedAfterRecreation", sameFocus).put("userScrollPositionRetainedAfterRecreation", positionPreserved)
            .put("oldAnchorNotReplayedAfterUserScroll", sameFocus && positionPreserved && !restored.targetFocused)
        check(sameFocus && positionPreserved && !restored.targetFocused) { "Source recreation replayed the old anchor or replaced the user's settled reading position" }
    }

    private fun readerScrollNode() = compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("zen_article_text")))
    private fun readerScrollPosition() = readerScrollNode().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun observeStableAnchor(t: Trial, name: String, screenshotName: String): AnchorObservation {
        val began = SystemClock.elapsedRealtime()
        var previous: AnchorObservation? = null
        var quietSince = began
        var result: AnchorObservation? = null
        compose.waitUntil(15_000) {
            val current = runCatching { measureAnchor(t) }.getOrNull()
            val now = SystemClock.elapsedRealtime()
            if (current == null) {
                previous = null; quietSince = now
                false
            } else {
                if (current.stableKey != previous?.stableKey) quietSince = now
                previous = current
                if (now - quietSince >= 1_200) {
                    current.data.put("observation", name).put("stableForMs", now - quietSince)
                        .put("elapsedAfterReaderReadyMs", now - began).put("screenshot", screenshotName)
                    result = current
                    true
                } else false
            }
        }
        val measured = requireNotNull(result)
        t.report.getJSONArray("observations").put(measured.data)
        screenshot(t, screenshotName)
        return measured
    }

    private fun measureAnchor(t: Trial): AnchorObservation {
        val field = compose.onNodeWithTag("zen_article_text")
        val fieldNode = field.fetchSemanticsNode()
        val viewportNode = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode()
        check(viewportNode.config[ReaderPositionReadyKey])
        check(fieldNode.config[SemanticsProperties.EditableText].text == t.fixture.body.text)
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val origin = fieldNode.positionInRoot
        val viewport = viewportNode.boundsInRoot
        val paragraphs = ParagraphFocus.paragraphs(t.fixture.body.text)
        val start = requireNotNull(t.fixture.capture.selectionStart)
        val end = requireNotNull(t.fixture.capture.selectionEnd)
        val sentenceStart = t.fixture.body.text.indexOf(t.fixture.capture.sentence)
        check(sentenceStart >= 0)
        val sentenceEnd = sentenceStart + t.fixture.capture.sentence.length
        val target = requireNotNull(ParagraphFocus.atOffset(paragraphs, start))
        val focused = fieldNode.config[FocusedParagraphIndex]
        val viewportParagraph = viewportNode.config[ReaderViewportParagraphKey]
        check(focused in paragraphs.indices && viewportParagraph in paragraphs.indices)
        val selection = fieldNode.config[SemanticsProperties.TextSelectionRange]
        fun geometry(from: Int, to: Int): JSONObject {
            val firstLine = layout.getLineForOffset(from)
            val lastLine = layout.getLineForOffset(to - 1)
            val top = origin.y + layout.getLineTop(firstLine)
            val bottom = origin.y + layout.getLineBottom(lastLine)
            return JSONObject().put("start", from).put("endExclusive", to)
                .put("firstLine", firstLine).put("lastLine", lastLine)
                .put("screenTop", top).put("screenBottom", bottom)
                .put("fullyVisible", top >= viewport.top && bottom <= viewport.bottom)
        }
        val data = JSONObject().put("bodyHash", t.fixture.body.contentHash)
            .put("targetParagraph", target).put("focusedParagraph", focused)
            .put("viewportParagraph", viewportParagraph).put("targetParagraphFocused", focused == target)
            .put("focusSemantics", "FocusedParagraphIndex on zen_article_text; the index used by animated focus paint")
            .put("viewportSemantics", "ReaderViewportParagraph on zen_reader_viewport; recorded separately from paint focus")
            .put("sourceSelection", geometry(start, end)).put("sourceSentence", geometry(sentenceStart, sentenceEnd))
            .put("selectedTextSha256", hash(t.fixture.capture.selectedText))
            .put("targetParagraphRange", geometry(paragraphs[target].start, paragraphs[target].endExclusive))
            .put("focusedParagraphRange", geometry(paragraphs[focused].start, paragraphs[focused].endExclusive))
            .put("paragraphs", JSONArray().apply { paragraphs.forEachIndexed { index, paragraph ->
                put(geometry(paragraph.start, paragraph.endExclusive).put("index", index))
            } })
            .put("viewportBounds", JSONArray(listOf(viewport.left, viewport.top, viewport.right, viewport.bottom)))
            .put("textOrigin", JSONArray(listOf(origin.x, origin.y)))
            .put("layoutWidth", layout.size.width).put("layoutHeight", layout.size.height)
            .put("textSelectionStart", selection.start).put("textSelectionEnd", selection.end)
            .put("textSelectionCollapsed", selection.collapsed)
            .put("positionReady", true)
        val stableKey = listOf(focused, viewportParagraph, origin.x.toInt(), origin.y.toInt(),
            viewport.left.toInt(), viewport.top.toInt(), viewport.right.toInt(), viewport.bottom.toInt(),
            layout.size.width, layout.size.height, selection.start, selection.end).joinToString(":")
        return AnchorObservation(focused == target, stableKey, data)
    }

    private fun verifyUnratedFixture(t: Trial) {
        val rows = snapshot(t.database)
        check(codec.decodeFromString<Card>(requireNotNull(rows.getValue("cards").single()[5])) == t.fixture.card)
        check(codec.decodeFromString<Capture>(requireNotNull(rows.getValue("captures").single()[2])) == t.fixture.capture)
        check(rows.getValue("review_logs") == t.original.getValue("review_logs"))
        check(rows.getValue("ai_jobs") == t.original.getValue("ai_jobs"))
        check(rows.getValue("local_events").none { codec.decodeFromString<LocalEvent>(requireNotNull(it[2])).type == "review_completed" })
        t.report.put("ownedCardAndScheduleUnchanged", true).put("ownedCaptureUnchanged", true)
            .put("ownedReviewLogCount", 0).put("ownedReviewEventCount", 0).put("aiJobsUnchanged", true)
    }

    @Test fun lastOwnedCardRatingPreservesItsCompletionAndSourceNavigation() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Dedicated-user completion trial is opt-in", args.getString("reviewCompletionTrial") == "1")
        val phase = requireNotNull(args.getString("phase")).also { require(it in setOf("before", "after")) }
        val run = requireNotNull(args.getString("evidenceRun") ?: args.getString("uniqueRun")).also {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")))
        }
        val expectedUser = requireNotNull(args.getString("expectedUserId")).toInt().also { require(it > 0) }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiconApplication
        val actualUser = Regex("^/data/user/(\\d+)/").find(app.applicationInfo.dataDir)?.groupValues?.get(1)?.toInt()
        check(actualUser == expectedUser) { "Instrumentation is not running in the declared dedicated secondary user" }
        val output = File(app.filesDir, "validation-1.24.0/review-completion/$phase/$run")
        check(!output.exists() && output.mkdirs()) { "Use a new evidenceRun; preserve earlier evidence" }
        val report = JSONObject().put("schema", "lexicon.review.completion.trial.v1")
            .put("status", "started").put("phase", phase).put("evidenceRun", run)
            .put("androidUserId", actualUser).put("actualMainActivity", true)
            .put("repositoryScope", "Unfiltered application Repository in a dedicated secondary Android user")
            .put("materialOrigin", "Self-authored synthetic fixture, not publisher content or real learning evidence")
            .put("jsonContainsLearningText", false).put("aiActions", 0).put("ratingsRequested", 0)
            .put("fixtureInsertSucceeded", false)
            .put("originalRowsRestoredOrReplaced", 0).put("screenshots", JSONArray())
        val reportFile = File(output, "device-trial.json")
        reportFile.writeText(report.toString(2))
        var trial: Trial? = null
        var observed = false
        var preserved = false
        try {
            requireOfflineNoKey(app)
            val repository = app.repository
            runBlocking { repository.initialize() }
            val database = app.getDatabasePath("lexicon.sqlite")
            val original = waitForQuietRows(database)
            listOf("articles", "article_sources", "article_bodies", "captures", "cards", "review_logs", "ai_jobs").forEach {
                check(original.getValue(it).isEmpty()) { "Dedicated trial user contains existing learning material; stop without editing it" }
            }
            val started = System.currentTimeMillis()
            val fixture = Fixture(run, started)
            val t = Trial(app, database, original, output, report, started, fixture)
            trial = t
            val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
            report.put("version", packageInfo.versionName).put("versionCode", packageInfo.longVersionCode)
                .put("startedAt", started).put("actualFontScale", app.resources.configuration.fontScale)
                .put("theme", runBlocking { repository.settings.first() }.theme.name)
                .put("fixtureBodyHash", fixture.body.contentHash)
                .put("databasePath", database.absolutePath)
                .put("databaseDriver", "androidx.sqlite.driver.bundled.BundledSQLiteDriver")
                .put("baselineQuietIntervalMs", 2_000)
                .put("baselineScope", "After repository initialization and two seconds of unchanged database rows")
                .put("originalTableHashes", tableHashes(original)).put("originalSnapshotKeptOnlyInMemory", true)
            t.stage = "insert_only_new_owned_fixture"
            insertFixture(t)
            verifyEditionWillNotRewriteOriginal(t)
            val cards = runBlocking { repository.cards.first() }
            check(cards.single() == fixture.card && cards.single().isDueForReview(System.currentTimeMillis()))
            report.put("initialDueCount", 1).put("fixtureCardIdSha256", hash(fixture.card.id))

            t.stage = "launch_original_main_activity"
            t.activity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(20_000) { exists("daily_reading") }
            compose.onNodeWithText(">_  命令").performClick()
            compose.onNodeWithText("搜索命令…").performTextInput("review")
            compose.onNodeWithText("开始复习", substring = true).performClick()
            compose.waitUntil(15_000) { exists("review_answer") }
            compose.onNodeWithText(fixture.card.content.headword, substring = false).assertExists()
            val initialCounts = if (phase == "before") "0 完成 · 1 待复习" else "今日完成 0 次复习 · 1 待复习"
            compose.waitUntil(15_000) {
                exists("review_counts") && exists("review_open_source") &&
                    runCatching { text("review_counts") == initialCounts }.getOrDefault(false)
            }
            compose.onNodeWithTag("review_counts").assertTextEquals(initialCounts)
            compose.onNodeWithTag("review_answer").performScrollTo().performClick()
            val good = if (phase == "before") compose.onNodeWithText("Good · 记得") else compose.onNodeWithTag("review_rate_good")
            good.performScrollTo().assertIsEnabled()
            screenshot(t, "01-last-card-before-rating.png")

            t.stage = "rate_owned_card_through_real_ui"
            report.put("ratingsRequested", 1)
            good.performClick()
            compose.waitUntil(15_000) {
                runBlocking { repository.cards.first() }.singleOrNull()?.let { it.id == fixture.card.id && it.state.reps == 1 } == true
            }
            verifySingleRating(t)
            if (phase == "before") observeBaselineCompletion(t) else exerciseCompletionNavigation(t)
            observed = true
        } catch (error: Throwable) {
            report.put("failureClass", error.javaClass.simpleName).put("failedStage", trial?.stage ?: "preflight")
                .put("failureLocations", JSONArray(error.stackTrace.take(10).map { "${it.fileName}:${it.lineNumber}" }))
            trial?.let { runCatching { screenshot(it, "failure.png") } }
        } finally {
            trial?.let { t ->
                val jobs = existingWriterJobs(t)
                runCatching { t.activity?.close() }.onFailure { observed = false; report.put("activityCloseFailureClass", it.javaClass.simpleName) }
                t.activity = null
                runCatching {
                    runBlocking { withTimeout(15_000) { jobs.joinAll() } }
                    requireOfflineNoKey(app)
                    val settled = waitForQuietRows(t.database)
                    if (t.fixtureInsertSucceeded) {
                        cleanupOwnedRows(t, settled)
                    } else {
                        report.put("ownedCleanupSkipped", "Fixture insertion did not commit; no rows may be deleted")
                    }
                    val after = waitForQuietRows(t.database)
                    report.put("finalTableHashes", tableHashes(after))
                    check(after == t.original)
                    preserved = true
                }.onFailure { report.put("preservationFailureClass", it.javaClass.simpleName) }
            }
            report.put("fixtureInsertSucceeded", trial?.fixtureInsertSucceeded == true)
                .put("originalDataPreserved", preserved).put("allOriginalRowsCompared", preserved)
                .put("status", if (!observed || !preserved) "failed" else if (phase == "before") "baseline_observed" else "passed")
            reportFile.writeText(report.toString(2))
        }
        check(observed && preserved) { "Completion trial failed; inspect its sanitized report and synthetic screenshots" }
    }

    private fun observeBaselineCompletion(t: Trial) {
        t.stage = "observe_before_completion_actions"
        compose.waitUntil(15_000) { existsText("暂无到期卡片") && existsText("本轮完成 1 次复习。") }
        compose.onNodeWithText("回到阅读").assertIsDisplayed()
        check(!exists("review_card_scroll") && !exists("review_open_source") && !exists("card_open_source"))
        check(!existsText(t.fixture.card.content.headword))
        val panel = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("暂无到期卡片"))).fetchSemanticsNode()
        val actions = descendants(panel).filter { it.config.contains(SemanticsActions.OnClick) }
            .map { node -> if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].joinToString(" ") { it.text } else "" }
        check(actions == listOf("回到阅读")) { "Baseline completion must expose only its original read action" }
        t.report.put("completionCount", 1).put("completionPanelActions", JSONArray(actions))
            .put("completionLastCardActionAvailable", false).put("completionSourceActionAvailable", false)
            .put("lastCardHiddenAfterRating", true).put("queueAfterRating", 0)
        screenshot(t, "02-before-completion-only-return-to-reading.png")
        requireNotNull(t.activity).recreate()
        compose.waitUntil(15_000) { existsText("本轮完成 1 次复习。") }
        t.report.put("completionCountSurvivedActivityRecreation", true)
        screenshot(t, "03-before-completion-after-recreation.png")
        compose.onNodeWithText("回到阅读").performClick()
        compose.waitUntil(15_000) { exists("daily_reading") }
        t.report.put("returnToReadingWorked", true)
        verifySingleRating(t)
    }

    private fun exerciseCompletionNavigation(t: Trial) {
        t.stage = "after_completion_from_persisted_rating"
        awaitCompletion(t)
        screenshot(t, "02-after-completion-with-last-card.png")
        val cardId = t.fixture.card.id

        t.stage = "completion_to_same_card_detail"
        compose.onNodeWithTag("review_last_card").performScrollTo().assertIsEnabled().performClick()
        awaitOwnedCard(t)
        compose.onNodeWithTag("card_back").assertTextEquals("← 返回复习")
        screenshot(t, "03-completion-last-card-detail.png")

        t.stage = "last_card_to_exact_synthetic_source"
        compose.onNodeWithTag("card_open_source").performScrollTo().assertIsEnabled()
        t.sourceOpened = true
        t.sourceOpenActions++
        compose.onNodeWithTag("card_open_source").performClick()
        awaitOriginal(t)
        screenshot(t, "04-card-exact-synthetic-source.png")

        t.stage = "source_activity_recreation"
        requireNotNull(t.activity).recreate()
        awaitOriginal(t)
        verifySingleRating(t)
        t.report.put("sourceRangeSurvivedActivityRecreation", true)
        screenshot(t, "05-source-after-recreation.png")
        back(t)
        awaitOwnedCard(t)
        verifySingleRating(t)
        screenshot(t, "06-returned-same-card-detail.png")
        compose.onNodeWithTag("card_back").performScrollTo().assertTextEquals("← 返回复习").performClick()
        awaitCompletion(t)
        t.report.put("completionCardSourceCardCompletionRoundTrip", true)
        screenshot(t, "07-returned-completion.png")

        t.stage = "completion_direct_source_round_trip"
        compose.onNodeWithTag("review_last_source").performScrollTo().assertIsEnabled()
        t.sourceOpenActions++
        compose.onNodeWithTag("review_last_source").performClick()
        awaitOriginal(t)
        screenshot(t, "08-completion-direct-synthetic-source.png")
        back(t)
        awaitCompletion(t)
        check(!exists("card_detail_$cardId"))
        t.report.put("completionDirectSourceRoundTrip", true)

        t.stage = "completion_recreation_reads_same_today_log"
        requireNotNull(t.activity).recreate()
        awaitCompletion(t)
        verifySingleRating(t)
        t.report.put("completionCountSurvivedActivityRecreation", true)
            .put("completionReceiptAndCardIdentityPreserved", true)
            .put("completionCountVerifiedAgainstDatabaseAfterRecreation", true)
            .put("recoveryScope", "Activity recreation and persisted ReviewLog identity; not OS process termination")
        screenshot(t, "09-completion-after-recreation.png")
        compose.onNodeWithTag("review_return_reading").performScrollTo().performClick()
        compose.waitUntil(15_000) { exists("daily_reading") }
        t.report.put("returnToReadingWorked", true).put("sourceOpenActions", t.sourceOpenActions)
        verifySingleRating(t)
    }

    private fun awaitCompletion(t: Trial) {
        compose.waitUntil(20_000) {
            exists("review_completion") && exists("review_last_headword") && exists("review_last_card") && exists("review_last_source") &&
                runCatching { text("review_completion_count") == "今日完成 1 次复习" &&
                    text("review_last_headword") == t.fixture.card.content.headword }.getOrDefault(false)
        }
        compose.onNodeWithTag("review_completion_count").assertTextEquals("今日完成 1 次复习")
        compose.onNodeWithTag("review_last_headword").assertTextEquals(t.fixture.card.content.headword)
        compose.onNodeWithTag("review_last_rating").assertTextEquals("Good · 记得")
        compose.onNodeWithTag("review_last_card").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("review_last_source").performScrollTo().assertIsDisplayed().assertIsEnabled()
        check(!exists("review_card_scroll") && !exists("review_answer"))
        verifySingleRating(t)
        t.report.put("completionCount", 1).put("completionLastCardActionAvailable", true)
            .put("completionSourceActionAvailable", true).put("completionHeadwordMatchesOwnedCard", true)
            .put("queueAfterRating", 0)
    }

    private fun awaitOwnedCard(t: Trial) {
        compose.waitUntil(15_000) { exists("card_detail_${t.fixture.card.id}") }
        check(!exists("card_definition_edit")) { "Returning to the card must not enter content editing" }
        compose.onNodeWithTag("card_open_source").performScrollTo().assertIsDisplayed()
    }

    private fun awaitOriginal(t: Trial) {
        val body = t.fixture.body
        val start = requireNotNull(t.fixture.capture.selectionStart)
        val end = requireNotNull(t.fixture.capture.selectionEnd)
        compose.waitUntil(20_000) {
            exists("zen_article_text") && runCatching {
                val field = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode()
                val viewport = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode()
                field.config[SemanticsProperties.EditableText].text == body.text &&
                    viewport.config[ReaderPositionReadyKey] && offsetVisible(start) && offsetVisible(end - 1)
            }.getOrDefault(false)
        }
        check(body.text.substring(start, end) == t.fixture.capture.selectedText)
        t.report.put("sourceTextIsExactSyntheticBody", true).put("sourceRangeStart", start)
            .put("sourceRangeEnd", end).put("sourceRangeVisible", true)
    }

    private fun offsetVisible(offset: Int): Boolean {
        val field = compose.onNodeWithTag("zen_article_text")
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val box = layouts.single().getBoundingBox(offset)
        val origin = field.fetchSemanticsNode().positionInRoot
        val viewport = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().boundsInRoot
        return origin.y + box.top >= viewport.top && origin.y + box.bottom <= viewport.bottom
    }

    private fun back(t: Trial) {
        requireNotNull(t.activity).onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private class Fixture(run: String, now: Long) {
        val prefix = "trial-review-completion:$run"
        val source = Source("$prefix:source", "合成测试材料（非外刊）", "https://example.invalid/review-fixture/$run/feed",
            readingMode = ReadingMode.NATIVE, enabled = false, aiAllowed = false,
            license = "Self-authored instrumentation fixture; not publisher evidence")
        val first = "This self-authored synthetic article tests review navigation. It is not an external publication and does not describe a real event."
        val sentence = "In this synthetic test, the committee chose a resilient plan for a reading room."
        val text = "$first\n\n$sentence\n\nThe synthetic committee compared two layouts and kept a quiet area beside the entrance."
        val article = Article("$prefix:article", "$prefix:guid", listOf(source.id), "合成复习材料：社区阅读室（测试）",
            "https://example.invalid/review-fixture/$run/article", first, author = "Lexicon instrumentation fixture",
            publishedAt = now, topic = "Culture", topicOrigin = "synthetic_fixture", read = true, captured = true,
            cachedAt = now, readingMode = ReadingMode.NATIVE, sourceName = source.name, license = source.license,
            wordCount = text.split(Regex("\\s+")).size)
        private val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val body = ArticleBody(article.id, text, digest, now, now + 3_600_000, source.license, article.author)
        val reference = SourceRef(article.title, source.name, article.url, article.publishedAt)
        private val start = text.indexOf("resilient")
        val capture = Capture("$prefix:capture", article.id, "resilient", "resilient", sentence,
            contextOrigin = ContextOrigin.ORIGINAL, sourceRef = reference, selectionStart = start,
            selectionEnd = start + "resilient".length, bodyHash = body.contentHash, createdAt = now, aiAllowed = false)
        val content = VocabularyContent("resilient", "Able to remain useful when conditions change in this synthetic plan.",
            listOf(Collocation("a resilient plan", "The synthetic committee chose a resilient plan."),
                Collocation("a resilient layout", "The synthetic example describes a resilient layout.")),
            "a plan that can adapt", "neutral", "medium")
        val card = Card("$prefix:card", capture.id, content, sentence, reference, topic = "Culture",
            state = FsrsState(dueAt = now - 60_000), createdAt = now, contextOrigin = ContextOrigin.ORIGINAL)
    }

    private class Trial(val app: LexiconApplication, val database: File, val original: Map<String, List<List<String?>>>,
        val output: File, val report: JSONObject, val startedAt: Long, val fixture: Fixture) {
        var activity: ActivityScenario<MainActivity>? = null
        var stage = "preflight"
        var fixtureInsertSucceeded = false
        var sourceOpened = false
        var sourceOpenActions = 0
        var appliedCardRows: List<List<String?>>? = null
        var appliedReviewRows: List<List<String?>>? = null
        val day = Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        var expectedEdition: DailyEdition? = null
    }

    private fun insertFixture(t: Trial) {
        val f = t.fixture
        check(f.body.text.substring(requireNotNull(f.capture.selectionStart), requireNotNull(f.capture.selectionEnd)) == f.capture.selectedText)
        ContentValidator.requireValid(f.content)
        openDatabase(t.database).use { db ->
            transaction(db) {
                check(snapshot(db) == t.original) { "Database changed before fixture insertion" }
                insert(db, "sources", "id" to f.source.id, "data" to codec.encodeToString(f.source))
                insert(db, "articles", "id" to f.article.id, "url" to f.article.url, "publishedAt" to requireNotNull(f.article.publishedAt), "data" to codec.encodeToString(f.article))
                insert(db, "article_sources", "sourceId" to f.source.id, "guid" to f.article.guid, "articleId" to f.article.id)
                insert(db, "article_bodies", "articleId" to f.body.articleId, "expiresAt" to f.body.expiresAt, "data" to codec.encodeToString(f.body))
                insert(db, "captures", "id" to f.capture.id, "createdAt" to f.capture.createdAt, "data" to codec.encodeToString(f.capture))
                insert(db, "cards", "id" to f.card.id, "captureId" to f.card.captureId, "dueAt" to f.card.state.dueAt,
                    "reps" to f.card.state.reps, "updatedAt" to f.card.updatedAt, "data" to codec.encodeToString(f.card))
            }
        }
        t.fixtureInsertSucceeded = true
        t.report.put("fixtureInsertion", "Test-owned SQLite rows before launch; application uses its unchanged Room Repository for rating")
            .put("fixtureInsertSucceeded", true).put("fixtureRowsInserted", 6).put("existingCardPauseOrScheduleChanges", 0)
    }

    private fun insert(db: SQLiteConnection, table: String, vararg entries: Pair<String, Any>) {
        require(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
        entries.forEach { require(it.first.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) }
        val columns = entries.joinToString(",") { "\"${it.first}\"" }
        val placeholders = entries.joinToString(",") { "?" }
        db.prepare("INSERT INTO \"$table\" ($columns) VALUES ($placeholders)").use { statement ->
            entries.forEachIndexed { index, (_, value) -> when (value) {
                is String -> statement.bindText(index + 1, value)
                is Long -> statement.bindLong(index + 1, value)
                is Int -> statement.bindLong(index + 1, value.toLong())
                else -> error("Unsupported fixture type")
            } }
            statement.step()
        }
        check(changes(db) == 1L)
    }

    private fun verifyEditionWillNotRewriteOriginal(t: Trial) {
        val rows = t.original.getValue("preferences").filter { it[0]?.startsWith("reading-library:edition:") == true }
        val existing = rows.singleOrNull { it[0] == "reading-library:edition:${t.day}" }
            ?.let { codec.decodeFromString<DailyEdition>(requireNotNull(it[1])) }
        val history = rows.sortedByDescending { it[0] }.take(8).map { codec.decodeFromString<DailyEdition>(requireNotNull(it[1])) }
        val sources = runBlocking { t.app.repository.sources.first() }
        val expected = ReadingDailyPlanner.extend(existing, listOf(t.fixture.article), sources, t.startedAt, ZoneId.systemDefault().id, history)
        check(existing == null || existing == expected) { "An existing daily edition would change" }
        t.expectedEdition = expected
    }

    private fun verifySingleRating(t: Trial) {
        val rows = snapshot(t.database)
        val saved = codec.decodeFromString<Card>(requireNotNull(rows.getValue("cards").single()[5]))
        val logRow = rows.getValue("review_logs").single()
        val log = codec.decodeFromString<ReviewLog>(requireNotNull(logRow[3]))
        check(saved.id == t.fixture.card.id && saved.state.reps == 1)
        check(saved == t.fixture.card.copy(state = saved.state))
        check(log.cardId == saved.id && log.rating == Rating.GOOD && log.before == t.fixture.card.state && log.after == saved.state)
        check(log.reviewedAt in t.startedAt..System.currentTimeMillis())
        check(log.after == Fsrs6Scheduler().review(log.before, log.rating, log.reviewedAt))
        check(!saved.isDueForReview(System.currentTimeMillis()))
        val events = rows.getValue("local_events").map { codec.decodeFromString<LocalEvent>(requireNotNull(it[2])) }
            .filter { it.type == "review_completed" }
        check(events.size == 1 && events.single().createdAt == log.reviewedAt)
        t.appliedCardRows?.let { check(it == rows.getValue("cards")) { "The applied card changed during navigation" } }
        t.appliedReviewRows?.let { check(it == rows.getValue("review_logs")) { "The persisted rating receipt changed during navigation" } }
        if (t.appliedCardRows == null) t.appliedCardRows = rows.getValue("cards")
        if (t.appliedReviewRows == null) t.appliedReviewRows = rows.getValue("review_logs")
        val today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault()).toLocalDate()
        check(today.toString() == t.day) { "Trial crossed midnight; do not claim a same-day count" }
        val dayStart = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val dayEnd = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val todayLogs = rows.getValue("review_logs").map { codec.decodeFromString<ReviewLog>(requireNotNull(it[3])) }
            .filter { it.reviewedAt >= dayStart && it.reviewedAt < dayEnd }
        check(todayLogs.size == 1 && todayLogs.single() == log)
        t.report.put("appliedRatingCount", 1).put("ownedReviewLogCount", 1).put("ownedReviewEventCount", 1)
            .put("actualFsrsStateMatchesRecordedReviewTime", true)
            .put("ownedReviewReceiptIdSha256", hash(log.id)).put("todayDatabaseReviewLogCount", todayLogs.size)
            .put("reviewDay", today.toString()).put("appliedCardAndReceiptRowsUnchanged", true)
            .put("afterReps", saved.state.reps).put("afterPhase", saved.state.phase.name)
            .put("afterDueAt", saved.state.dueAt)
    }

    private fun cleanupOwnedRows(t: Trial, settled: Map<String, List<List<String?>>>) {
        check(t.fixtureInsertSucceeded) { "Do not clean a fixture that did not commit" }
        t.report.put("preCleanupTableHashes", tableHashes(settled))
        check(settled.keys == t.original.keys)
        val added = settled.mapValues { (table, rows) ->
            val previous = t.original.getValue(table)
            val ids = previous.map { rowKey(table, it) }.toSet()
            check(rows.filter { rowKey(table, it) in ids } == previous) { "An original row changed; never restore it from a snapshot" }
            rows.filter { rowKey(table, it) !in ids }
        }
        val f = t.fixture
        val ended = System.currentTimeMillis()
        val sessions = added.getValue("preferences").filter { it[0]?.startsWith("reading-library:session:") == true }
            .associate { requireNotNull(it[0]).removePrefix("reading-library:session:") to codec.decodeFromString<ReadingSession>(requireNotNull(it[1])) }
        val reviews = added.getValue("review_logs").map { codec.decodeFromString<ReviewLog>(requireNotNull(it[3])) }
        check(reviews.size <= 1)
        fun owned(table: String, row: List<String?>): Boolean {
            val id = requireNotNull(row[0])
            return when (table) {
                "sources" -> id == f.source.id && codec.decodeFromString<Source>(requireNotNull(row[1])) == f.source
                "articles" -> id == f.article.id && codec.decodeFromString<Article>(requireNotNull(row[3])) == f.article
                "article_sources" -> row == listOf(f.source.id, f.article.guid, f.article.id)
                "article_bodies" -> id == f.article.id && codec.decodeFromString<ArticleBody>(requireNotNull(row[2])) == f.body
                "captures" -> id == f.capture.id && codec.decodeFromString<Capture>(requireNotNull(row[2])) == f.capture
                "cards" -> id == f.card.id && codec.decodeFromString<Card>(requireNotNull(row[5])).let { saved ->
                    saved == f.card.copy(state = saved.state) && if (reviews.isEmpty()) saved == f.card else
                        reviews.single().let { it.cardId == saved.id && it.after == saved.state }
                }
                "review_logs" -> codec.decodeFromString<ReviewLog>(requireNotNull(row[3])).let {
                    it.id == id && it.cardId == f.card.id && it.rating == Rating.GOOD && it.before == f.card.state &&
                        it.reviewedAt in t.startedAt..ended && it.after == Fsrs6Scheduler().review(it.before, it.rating, it.reviewedAt)
                }
                "local_events" -> codec.decodeFromString<LocalEvent>(requireNotNull(row[2])).let { event ->
                    event.id == id && event.createdAt in t.startedAt..ended && when (event.type) {
                        "review_completed" -> reviews.singleOrNull()?.reviewedAt == event.createdAt && event.value == 1
                        "zen_opened", "zen_duration_seconds" -> t.sourceOpened
                        "reading_session" -> t.sourceOpened && sessions[id.removePrefix("reading-session:")]?.let {
                            it.articleId == f.article.id && it.startedAt in t.startedAt..ended
                        } == true
                        else -> false
                    }
                }
                "preferences" -> {
                    val data = requireNotNull(row[1])
                    when {
                        id == "reading-library:edition:${t.day}" -> codec.decodeFromString<DailyEdition>(data) == t.expectedEdition
                        !t.sourceOpened -> false
                        id.startsWith("reading-library:session:") -> sessions[id.removePrefix("reading-library:session:")]?.let {
                            it.articleId == f.article.id && it.startedAt in t.startedAt..ended && it.finishedAt <= ended
                        } == true
                        id == "reading-exposure:article:${f.article.id}" || id == "reading-exposure:body:${f.body.contentHash}" -> data.toLongOrNull()?.let { it in t.startedAt..ended } == true
                        id == "reading-resume:record:${hash(f.article.id)}" -> codec.decodeFromString<ReadingResumeRecord>(data).let {
                            it.articleId == f.article.id && it.bodyHash == f.body.contentHash && it.updatedAt in t.startedAt..ended
                        }
                        id == "reading-resume:generation:${hash(f.article.id)}" -> JSONObject(data).let {
                            it.getInt("version") == 1 && it.getLong("generation") > 0 && it.getString("bodyHash") == f.body.contentHash
                        }
                        else -> false
                    }
                }
                else -> false
            }
        }
        added.forEach { (table, rows) -> rows.forEach { check(owned(table, it)) { "Unexpected added row; retain all rows for inspection" } } }
        val reviewEvents = added.getValue("local_events").map { codec.decodeFromString<LocalEvent>(requireNotNull(it[2])) }.count { it.type == "review_completed" }
        check(reviewEvents == reviews.size)
        openDatabase(t.database).use { db ->
            transaction(db) {
                check(snapshot(db) == settled) { "Database writes continued after the quiet interval" }
                listOf("review_logs", "local_events", "preferences", "cards", "captures", "article_bodies", "article_sources", "articles", "sources").forEach { table ->
                    added.getValue(table).forEach { row ->
                        val where = when (table) { "preferences" -> "`key`=?"; "article_bodies" -> "articleId=?"; "article_sources" -> "sourceId=? AND guid=?"; else -> "id=?" }
                        val args = if (table == "article_sources") arrayOf(requireNotNull(row[0]), requireNotNull(row[1])) else arrayOf(requireNotNull(row[0]))
                        db.prepare("DELETE FROM \"$table\" WHERE $where").use { statement ->
                            args.forEachIndexed { index, value -> statement.bindText(index + 1, value) }
                            statement.step()
                        }
                        check(changes(db) == 1L)
                    }
                }
            }
        }
        t.report.put("removedOwnedRows", JSONObject().apply { added.forEach { (table, rows) -> if (rows.isNotEmpty()) put(table, rows.size) } })
            .put("quietIntervalMs", 2_000)
    }

    private fun rowKey(table: String, row: List<String?>) = JSONArray(row.take(if (table == "article_sources") 2 else 1)).toString()
    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    private fun existsText(value: String) = compose.onAllNodesWithText(value, substring = false).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    private fun text(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("\n") { it.text }
    private fun requireOfflineNoKey(app: LexiconApplication) {
        check(app.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
        check(!runBlocking { app.repository.hasApiKey() })
    }
    private fun snapshot(file: File): Map<String, List<List<String?>>> = openDatabase(file, queryOnly = true).use { db ->
        db.exec("BEGIN DEFERRED TRANSACTION")
        try { snapshot(db).also { db.exec("COMMIT") } }
        catch (error: Throwable) { runCatching { db.exec("ROLLBACK") }; throw error }
    }
    private fun snapshot(db: SQLiteConnection): Map<String, List<List<String?>>> {
        val tables = db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { statement ->
            buildList { while (statement.step()) add(statement.getText(0)) }
        }
        return tables.associateWith { table ->
            require(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            db.prepare("SELECT * FROM \"$table\"").use { statement ->
                buildList { while (statement.step()) add((0 until statement.getColumnCount()).map { if (statement.isNull(it)) null else statement.getText(it) }) }
                    .sortedBy { JSONArray(it).toString() }
            }
        }
    }
    private fun openDatabase(file: File, queryOnly: Boolean = false): SQLiteConnection {
        check(file.isFile) { "The initialized application database must already exist" }
        val db = BundledSQLiteDriver().open(file.absolutePath)
        try {
            db.exec("PRAGMA busy_timeout=5000")
            if (queryOnly) db.exec("PRAGMA query_only=ON")
            return db
        } catch (error: Throwable) { db.close(); throw error }
    }
    private fun transaction(db: SQLiteConnection, block: () -> Unit) {
        db.exec("BEGIN IMMEDIATE TRANSACTION")
        try { block(); db.exec("COMMIT") }
        catch (error: Throwable) { runCatching { db.exec("ROLLBACK") }; throw error }
    }
    private fun changes(db: SQLiteConnection): Long = db.prepare("SELECT changes()").use {
        check(it.step()); it.getLong(0)
    }
    private fun SQLiteConnection.exec(sql: String) { prepare(sql).use { it.step() } }
    private fun waitForQuietRows(file: File): Map<String, List<List<String?>>> {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var previous = snapshot(file)
        var quietSince = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(200)
            val next = snapshot(file)
            if (next != previous) { previous = next; quietSince = SystemClock.elapsedRealtime() }
            else if (SystemClock.elapsedRealtime() - quietSince >= 2_000) return next
        }
        error("Writes did not become quiet within the bounded observation")
    }
    private fun existingWriterJobs(t: Trial): List<Job> {
        val jobs = linkedSetOf<Job>()
        t.activity?.onActivity { activity ->
            val provider = ViewModelProvider(activity, object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = error("Existing ViewModels only")
            })
            fun retain(find: () -> ViewModel) { runCatching { find().viewModelScope.coroutineContext[Job] }.getOrNull()?.let(jobs::add) }
            retain { provider[LexiconViewModel::class.java] }
            retain { provider[ReadingViewModel::class.java] }
            if (t.sourceOpened) retain { provider["reading-resume:${hash(t.fixture.article.id)}", ReadingResumeViewModel::class.java] }
        }
        t.report.put("joinedExistingViewModelJobs", jobs.size)
        return jobs.toList()
    }
    private fun tableHashes(rows: Map<String, List<List<String?>>>) = JSONObject().apply {
        rows.forEach { (table, values) -> put(table, JSONObject().put("rows", values.size).put("sha256", hash(JSONArray(values.map { JSONArray(it) }).toString()))) }
    }
    private fun screenshot(t: Trial, name: String) {
        compose.waitForIdle()
        val file = File(t.output, name)
        check(!file.exists())
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() }
        t.report.getJSONArray("screenshots").put(name)
        File(t.output, "device-trial.json").writeText(t.report.toString(2))
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
