package dev.lexicon.ide.ui

import android.graphics.Bitmap
import android.content.res.Configuration
import android.os.SystemClock
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import dev.lexicon.ide.LexiconApplication
import dev.lexicon.ide.MainActivity
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in MainActivity observation. All navigation, settings and seeking use actual touch input. */
@RunWith(AndroidJUnit4::class)
class SpeedRangeDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun completeMultilineChunkFitsButMustNotDisappearBelowTheReadingViewport() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Speed range device trial is opt-in", args.getString("speedRangeTrial") == "1")
        val phase = requireNotNull(args.getString("phase")).also { require(it in setOf("before", "after")) }
        val frozen = if (phase == "after") FrozenTarget(
            requireNotNull(args.getString("expectedBodyHash")), requireNotNull(args.getString("expectedChunkRangesHash")),
            requireNotNull(args.getString("targetIndex")).toInt(), requireNotNull(args.getString("targetStart")).toInt(),
            requireNotNull(args.getString("targetEnd")).toInt(), requireNotNull(args.getString("expectedReaderSizeSp")).toFloat(),
        ).also { require(it.bodyHash.matches(Regex("[a-f0-9]{64}")) && it.chunkHash.matches(Regex("[a-f0-9]{64}")) &&
            it.index > 0 && it.start >= 0 && it.end > it.start && it.sizeSp in 23f..25f) } else null
        val run = requireNotNull(args.getString("uniqueRun")).also { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) }
        require(args.getString("expectedUserId") == "10")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiconApplication
        val user = Regex("^/data/user/(\\d+)/").find(app.applicationInfo.dataDir)?.groupValues?.get(1)?.toInt()
        check(user == 10)
        val output = File(app.filesDir, "validation-speed-range-2026-11-06/$run")
        check(!output.exists() && output.mkdirs())
        val version = app.packageManager.getPackageInfo(app.packageName, 0)
        val report = JSONObject().put("schema", "lexicon.speed.range.trial.v1").put("status", "started")
            .put("phase", phase).put("uniqueRun", run).put("androidUserId", user)
            .put("version", version.versionName).put("versionCode", version.longVersionCode)
            .put("apkSha256", SpeedRangeTrialStore.sha(File(app.applicationInfo.sourceDir)))
            .put("actualSystemFontScale", app.resources.configuration.fontScale)
            .put("actualUiModeNightMask", app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            .put("actualMainActivity", true).put("automatedJourney", true).put("jsonContainsLearningText", false)
            .put("materialOrigin", "Fixed self-authored fictional paragraph; no comprehension or publisher evidence")
            .put("aiActions", 0).put("playActions", 0).put("ratingsRequested", 0).put("originalRowsRestoredOrReplaced", 0)
            .put("touches", JSONArray()).put("profileStates", JSONArray()).put("screenshots", JSONArray())
            .put("scan", JSONArray()).put("protocol", JSONObject().put("systemFontScale", 2).put("requestedReaderSizeSp", 24)
                .put("readerLineHeight", 1.8).put("granularity", "L3").put("rate", 30).put("sentenceOnly", false)
                .put("minimumLines", 2).put("fitMarginPx", FIT_MARGIN).put("visibilityTolerancePx", TOLERANCE)
                .put("calibrationTolerancePx", CALIBRATION_TOLERANCE).put("stableMs", STABLE_MS)
                .put("selection", "Exact plan UTF-16 range; first visible lexical glyph is touched without text search")
                .put("afterContract", "The complete frozen range fits inside the viewport after automatic positioning"))
        val reportFile = File(output, "device-trial.json")
        fun save() { reportFile.writeText(report.toString(2)) }
        save()
        var trial: Trial? = null
        var observed = false; var preserved = false; var desired = false; var returned = false
        var guard: SpeedRangeWindowGuard? = null
        try {
            SpeedRangeTrialStore.offlineNoKey(app)
            check(abs(app.resources.configuration.fontScale - 2f) < .02f)
            runBlocking { app.repository.initialize(); app.repository.installedDictionaries(); app.repository.dictionaryStatus() }
            val store = SpeedRangeTrialStore(app, run, System.currentTimeMillis(), report, output)
            val screenGuard = SpeedRangeWindowGuard(app.packageName, report).also { guard = it }
            val t = Trial(store, output, report, screenGuard).also { trial = it }
            store.preflight(); save(); store.insertFixture()
            t.activityLaunchAttempted = true
            t.activity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(20_000) { exists(hasTestTag("daily_reading")) }
            t.shellObserved = true; report.put("shellObserved", true)
            requireNotNull(t.activity).onActivity { screenGuard.bindSafeContent(it) }
            screenGuard.check("initial_application_layout")
            touch(t, hasText("读过的文章"), "open_history", hasTestTag("daily_reading"))
            compose.waitUntil(15_000) { exists(hasTestTag("article_history_list")) }
            store.readerOpened = true
            store.persistOwnership("before_open_owned_article")
            touch(t, hasText(store.fixture.article.title), "open_owned_article", hasTestTag("article_history_list"))
            awaitReader()
            t.readerObserved = true; report.put("readerObserved", true)
            touch(t, hasText("精读 · 工具"), "open_tools_for_appearance", hasTestTag("zen_reader_viewport"))
            compose.waitUntil(10_000) { exists(hasTestTag("reader_tools_sheet")) }
            touch(t, hasText("Aa"), "open_appearance")
            compose.waitUntil(10_000) { exists(hasTestTag("reader_appearance_preview")) }
            slider(t, "reader_size", frozen?.sizeSp ?: 24f, 14f, 32f, "set_reader_size")
            compose.waitUntil(10_000) { runBlocking { app.repository.settings.first() }.reader.sizeSp in 23f..25f }
            t.readerSizeSp = store.observeSettings().reader.sizeSp
            if (frozen != null) check(t.readerSizeSp == frozen.sizeSp) { "The paired trial must use the baseline's actual font size" }
            report.put("requestedSizeReachedExactly", t.readerSizeSp == 24f)
            screenshot(t, "01-appearance-actual-size.png")
            touch(t, hasText("完成"), "close_appearance")
            awaitReader()
            touch(t, hasText("精读 · 工具"), "open_tools_for_training", hasTestTag("zen_reader_viewport"))
            compose.waitUntil(10_000) { exists(hasTestTag("reader_tools_sheet")) }
            touch(t, hasText("意群速读 →"), "open_speed_setup")
            compose.waitUntil(15_000) { exists(hasTestTag("speed_prepare_offline")) }
            report.put("speedSetupObserved", true)
            t.store.setupActions++
            store.persistOwnership("before_choose_l3")
            touch(t, hasText("L3 · 挑战"), "choose_l3")
            check(observeSetup(t, "l3") == 60)
            t.store.setupActions++
            store.persistOwnership("before_set_rate")
            slider(t, "speed_slider", 30f, 30f, 180f, "rate_30")
            check(observeSetup(t, "rate_30") == 30)
            touch(t, hasTestTag("speed_prepare_offline"), "prepare_offline")
            report.put("prepareActions", 1)
            compose.waitUntil(15_000) { state(t).let { it.stage == SpeedStage.READING && !it.loading && it.plan != null } }
            val prepared = state(t)
            check(!prepared.sentenceOnly && !prepared.playing && prepared.groupsPerMinute == 30)
            store.observePlan(requireNotNull(prepared.plan))
            check(prepared.plan!!.chunks.size >= 12)
            if (frozen != null) {
                check(prepared.plan!!.bodyHash == frozen.bodyHash && report.getString("chunkRangesSha256") == frozen.chunkHash)
                check(prepared.plan!!.chunks.getOrNull(frozen.index)?.let { it.start == frozen.start && it.end == frozen.end } == true)
            }
            touch(t, hasTestTag("speed_next"), "leave_initial_timing_hint")
            compose.waitUntil(5_000) { state(t).chunkIndex == 1 && !state(t).playing }
            calibrateOrigin(t)
            val candidate = if (frozen == null) findCandidate(t) else arrangeFrozenCandidate(t, frozen)
            report.put("frozenCandidate", candidate.json).put("frozenIndex", candidate.index)
                .put("frozenStart", candidate.start).put("frozenEnd", candidate.end)
            screenshot(t, "02-candidate-before-seek.png")
            val beforeGeometry = stable(t)
            val beforeState = beforeGeometry.state
            val rechecked = range(beforeGeometry, candidate.index)
            check(beforeState.chunkIndex != candidate.index) { "A same-index tap cannot test index-triggered auto-positioning" }
            check(beforeState.plan?.chunks?.get(candidate.index)?.let { it.start == candidate.start && it.end == candidate.end } == true)
            check(rechecked.fits && rechecked.firstLineVisible && rechecked.bottom > beforeGeometry.viewport.bottom + TOLERANCE)
            report.put("beforeSeekGeometry", beforeGeometry.json).put("fromIndex", beforeState.chunkIndex)
                .put("toIndexRequested", candidate.index)
            touchRootInSurface(t, rechecked.touch, "seek_frozen_chunk")
            compose.waitUntil(10_000) { state(t).chunkIndex == candidate.index && !state(t).playing }
            val after = stable(t, candidate.index)
            val afterRange = range(after, candidate.index)
            check(afterRange.start == candidate.start && afterRange.end == candidate.end)
            check(afterRange.fits && afterRange.lines >= 2)
            check(abs(after.viewport.width - beforeGeometry.viewport.width) <= TOLERANCE &&
                abs(after.viewport.height - beforeGeometry.viewport.height) <= TOLERANCE) { "The seek changed the available viewport" }
            desired = afterRange.fullyVisible
            observed = if (phase == "before") afterRange.firstLineVisible && afterRange.bottom > after.viewport.bottom + TOLERANCE
                else true
            report.put("afterSeek", afterRange.json).put("afterGeometry", after.json)
                .put("toIndexObserved", after.state.chunkIndex).put("actualIndexChanged", after.state.chunkIndex != beforeState.chunkIndex)
                .put("desiredBehaviorVerified", desired).put("baselineClippingObserved", phase == "before" && observed)
            screenshot(t, "03-frozen-chunk-after-seek.png")
            touch(t, hasContentDescription("返回阅读"), "return_to_original")
            awaitReader(); returned = true
            store.quietRows(); store.observeProfile(SpeedGranularity.L3, 30, "returned_to_reader", "after", afterExit = true)
            store.observeSettings()
            screenshot(t, "04-returned-to-reader.png")
            check(!keyboardVisible()); SpeedRangeTrialStore.offlineNoKey(app)
        } catch (failure: Throwable) {
            report.put("failedStage", trial?.stage ?: "preflight").put("failureClass", failure.javaClass.simpleName)
                .put("failureLocations", JSONArray(failure.stackTrace.take(10).map { "${it.fileName}:${it.lineNumber}" }))
            if (!report.optBoolean("systemWindowBlocked")) trial?.let { runCatching { screenshot(it, "failure.png") } }
        } finally {
            trial?.let { t ->
                report.put("cleanupAttemptedAfterSystemBlock", report.optBoolean("systemWindowBlocked"))
                runCatching { t.store.persistOwnership("before_activity_cleanup") }
                    .onFailure { report.put("ownershipLedgerFailureClass", it.javaClass.simpleName) }
                var jobsObserved = true
                val jobs = runCatching { existingJobs(t) }.getOrElse {
                    jobsObserved = false
                    report.put("existingJobsFailureClass", it.javaClass.simpleName); t.observedJobs.toList()
                }
                val active = t.activity
                var closeConfirmed = active == null
                report.put("activityCloseAttempted", active != null).put("activityCloseCompleted", false)
                if (active != null) runCatching {
                    active.close(); closeConfirmed = true; t.activity = null
                    report.put("activityCloseCompleted", true)
                }.onFailure { report.put("closeFailureClass", it.javaClass.simpleName) }
                var jobsStopped = false
                if (closeConfirmed && jobsObserved) runCatching {
                    runBlocking { withTimeout(15_000) { jobs.joinAll() } }
                    check(jobs.all { it.isCompleted }); jobsStopped = true
                }.onFailure { report.put("jobsStopFailureClass", it.javaClass.simpleName) }
                report.put("existingViewModelJobsStopped", jobsStopped)
                if (closeConfirmed && jobsStopped) runCatching { t.store.preserve(); preserved = true }
                    .onFailure { report.put("preservationFailureClass", it.javaClass.simpleName)
                        .put("preservationFailureLocations", JSONArray(it.stackTrace.take(10).map { frame -> "${frame.fileName}:${frame.lineNumber}" })) }
                else report.put("cleanupDeferredUntilWritersStop", true)
                runCatching { t.store.persistOwnership("after_cleanup_attempt") }
                    .onFailure { report.put("ownershipLedgerFailureClass", it.javaClass.simpleName) }
            }
            runCatching { guard?.close() }
            val journey = observed && returned && !report.has("failureClass") && !report.has("ownershipLedgerFailureClass") &&
                !report.optBoolean("systemWindowBlocked")
            report.put("observed", observed).put("returnedToOriginalReader", returned).put("journeyPassed", journey)
                .put("originalDataPreserved", preserved).put("status", when {
                    !journey || !preserved || phase == "after" && !desired -> "failed"
                    phase == "before" -> "baseline_observed"
                    else -> "passed"
                })
            save()
        }
        check(report.getString("status") in setOf("baseline_observed", "passed")) { "Inspect the retained range trial evidence" }
    }

    private class Trial(val store: SpeedRangeTrialStore, val output: File, val report: JSONObject, val guard: SpeedRangeWindowGuard) {
        var activity: ActivityScenario<MainActivity>? = null
        var activityLaunchAttempted = false
        var shellObserved = false
        var readerObserved = false
        var stage = "preflight"
        var subtractScrollFromOrigin: Boolean? = null
        var readerSizeSp = 24f
        val observedJobs = linkedSetOf<Job>()
    }
    private data class FrozenTarget(val bodyHash: String, val chunkHash: String, val index: Int,
        val start: Int, val end: Int, val sizeSp: Float)
    private data class Geometry(val state: SpeedUiState, val layout: TextLayoutResult, val position: Offset,
        val viewport: Rect, val scroll: Float, val maxScroll: Float, val origin: Offset, val json: JSONObject)
    private data class Range(val index: Int, val start: Int, val end: Int, val lines: Int, val top: Float, val bottom: Float,
        val fits: Boolean, val firstLineVisible: Boolean, val fullyVisible: Boolean, val touch: Offset, val json: JSONObject)

    private fun calibrateOrigin(t: Trial) {
        t.stage = "calibrate_text_origin"
        val before = stable(t)
        val viewport = before.viewport
        swipeSurface(t, Offset(viewport.center.x, viewport.top + viewport.height * .70f),
            Offset(viewport.center.x, viewport.top + viewport.height * .40f), "calibrate_small_scroll")
        val after = stable(t)
        val delta = after.scroll - before.scroll
        check(delta > 8f && before.state.chunkIndex == after.state.chunkIndex)
        check(before.layout.layoutInput.text == after.layout.layoutInput.text && before.layout.size == after.layout.size)
        check(abs(before.viewport.height - after.viewport.height) <= CALIBRATION_TOLERANCE)
        val moved = after.position.y - before.position.y
        val movingOrigin = abs(moved + delta) <= CALIBRATION_TOLERANCE
        val fixedOrigin = abs(moved) <= CALIBRATION_TOLERANCE
        check(movingOrigin.xor(fixedOrigin)) { "Text origin and scroll do not establish a unique coordinate transform" }
        t.subtractScrollFromOrigin = fixedOrigin
        t.report.put("originCalibration", JSONObject().put("scrollDeltaPx", delta).put("positionDeltaPx", moved)
            .put("subtractScrollFromOrigin", fixedOrigin).put("before", before.json).put("after", after.json))
    }

    private fun findCandidate(t: Trial): Range {
        t.stage = "find_fit_multiline_case"
        repeat(60) {
            val sample = stable(t)
            val plan = requireNotNull(sample.state.plan)
            val eligible = plan.chunks.indices.filter { it > 0 && it != sample.state.chunkIndex }.map { range(sample, it) }
                .firstOrNull { it.lines >= 2 && it.fits && it.firstLineVisible && it.bottom > sample.viewport.bottom + TOLERANCE &&
                    sample.viewport.contains(it.touch) }
            t.report.getJSONArray("scan").put(JSONObject().put("index", sample.state.chunkIndex).put("geometry", sample.json)
                .put("eligibleIndex", eligible?.index ?: JSONObject.NULL))
            if (eligible != null) return eligible
            if (sample.state.chunkIndex >= plan.chunks.lastIndex - 1) {
                t.report.put("noEligibleCase", true)
                error("No eligible case before the final training block")
            }
            val wanted = sample.state.chunkIndex + 1
            touch(t, hasTestTag("speed_next"), "advance_to_$wanted")
            compose.waitUntil(5_000) { state(t).chunkIndex == wanted }
        }
        t.report.put("noEligibleCase", true)
        error("No complete multiline group met the frozen fit-and-clipping conditions")
    }

    /** Arrange the exact baseline target with real scrolling; never substitute an easier target. */
    private fun arrangeFrozenCandidate(t: Trial, target: FrozenTarget): Range {
        t.stage = "arrange_frozen_target"
        if (state(t).chunkIndex == target.index) {
            val plan = requireNotNull(state(t).plan)
            val tag = if (target.index < plan.chunks.lastIndex) "speed_next" else "speed_previous"
            touch(t, hasTestTag(tag), "leave_frozen_index_before_seek")
        }
        repeat(40) {
            val sample = stable(t)
            val value = range(sample, target.index)
            check(sample.state.chunkIndex != target.index)
            check(value.start == target.start && value.end == target.end && value.lines >= 2 && value.fits)
            if (value.firstLineVisible && value.bottom > sample.viewport.bottom + TOLERANCE && sample.viewport.contains(value.touch)) return value
            val first = sample.layout.getLineForOffset(value.start)
            val firstHeight = sample.layout.getLineBottom(first) - sample.layout.getLineTop(first)
            val desiredTop = sample.viewport.bottom - firstHeight - 8f
            val distance = (value.top - desiredTop).coerceIn(-sample.viewport.height * .4f, sample.viewport.height * .4f)
            check(abs(distance) > 2f) { "The exact frozen target cannot satisfy the before-click visibility condition" }
            val from = Offset(sample.viewport.center.x, sample.viewport.center.y + distance * .5f)
            val to = Offset(sample.viewport.center.x, sample.viewport.center.y - distance * .5f)
            swipeSurface(t, from, to, "arrange_exact_target_$it")
        }
        error("Real scrolling could not arrange the exact baseline range")
    }

    private fun range(sample: Geometry, index: Int): Range {
        val chunk = requireNotNull(sample.state.plan).chunks[index]
        val text = sample.layout.layoutInput.text.text
        check(chunk.start >= 0 && chunk.end in (chunk.start + 1)..text.length)
        val first = sample.layout.getLineForOffset(chunk.start)
        val last = sample.layout.getLineForOffset(chunk.end - 1)
        val top = sample.origin.y + sample.layout.getLineTop(first)
        val firstBottom = sample.origin.y + sample.layout.getLineBottom(first)
        val bottom = sample.origin.y + sample.layout.getLineBottom(last)
        var glyph = chunk.start
        while (glyph < chunk.end && !text[glyph].isLetterOrDigit()) glyph++
        check(glyph < chunk.end)
        val glyphBox = sample.layout.getBoundingBox(glyph).translate(sample.origin)
        val fits = bottom - top <= sample.viewport.height - FIT_MARGIN
        val firstVisible = top >= sample.viewport.top - TOLERANCE && firstBottom <= sample.viewport.bottom + TOLERANCE && contains(sample.viewport, glyphBox)
        val visible = top >= sample.viewport.top - TOLERANCE && bottom <= sample.viewport.bottom + TOLERANCE
        return Range(index, chunk.start, chunk.end, last - first + 1, top, bottom, fits, firstVisible, visible, glyphBox.center,
            JSONObject().put("index", index).put("start", chunk.start).put("end", chunk.end).put("wordCount", chunk.wordCount)
                .put("firstLine", first).put("lastLine", last).put("lineTopRootPx", top).put("firstLineBottomRootPx", firstBottom)
                .put("lastLineBottomRootPx", bottom).put("rangeHeightPx", bottom - top).put("viewport", rect(sample.viewport))
                .put("fitsViewport", fits).put("firstLineVisible", firstVisible).put("completeRangeVisible", visible)
                .put("firstGlyphOffset", glyph).put("firstGlyphBoundsRoot", rect(glyphBox)))
    }

    private fun stable(t: Trial, expectedIndex: Int? = null): Geometry {
        var prior = ""; var since = SystemClock.elapsedRealtime(); var accepted: Geometry? = null
        compose.waitUntil(15_000) {
            val sample = geometry(t)
            val signature = "${sample.state.chunkIndex}:${sample.scroll}:${sample.maxScroll}:${sample.position}:${sample.viewport}:${sample.layout.size}"
            if (signature != prior) { prior = signature; since = SystemClock.elapsedRealtime() }
            accepted = sample
            (expectedIndex == null || sample.state.chunkIndex == expectedIndex) && SystemClock.elapsedRealtime() - since >= STABLE_MS
        }
        return requireNotNull(accepted)
    }

    private fun geometry(t: Trial): Geometry {
        t.guard.check("${t.stage}:geometry")
        val before = state(t)
        val plan = requireNotNull(before.plan)
        check(before.stage == SpeedStage.READING && !before.playing && !before.sentenceOnly && before.groupsPerMinute == 30)
        check(plan.granularity == SpeedGranularity.L3 && plan.bodyHash == t.store.fixture.body.contentHash && plan.paragraphIndices == listOf(0))
        val field = compose.onNodeWithTag("speed_original_text")
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        check(layout.layoutInput.text.text == t.store.fixture.text)
        check(abs(layout.layoutInput.style.fontSize.value - t.readerSizeSp) < .02f &&
            abs(layout.layoutInput.style.lineHeight.value - t.readerSizeSp * 1.8f) < .03f)
        check(abs(layout.layoutInput.density.fontScale - 2f) < .02f)
        val node = field.fetchSemanticsNode()
        val scrollers = compose.onAllNodes(hasScrollAction() and hasAnyDescendant(hasTestTag("speed_original_text"))).fetchSemanticsNodes()
        val axis = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            ?: scrollers.minByOrNull { it.boundsInRoot.height }?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            ?: error("The original text has no observable scroll range")
        val surface = compose.onNodeWithTag("speed_chunk_surface").fetchSemanticsNode().boundsInRoot
        val viewport = node.boundsInRoot.intersect(surface)
        val scroll = axis.value(); val maximum = axis.maxValue()
        check(viewport.width > 0 && viewport.height > 0 && maximum.isFinite() && maximum > 0)
        check(abs(layout.size.height - maximum - viewport.height) <= CALIBRATION_TOLERANCE) { "Observed clipping is not the real text viewport" }
        val position = node.positionInRoot
        val origin = if (t.subtractScrollFromOrigin == true) position.copy(y = position.y - scroll) else position
        val counter = compose.onNodeWithTag("speed_group_counter").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        check(counter == "1 / 1 段 · ${before.chunkIndex + 1} / ${plan.chunks.size} 意群")
        val after = state(t)
        check(before.plan == after.plan && before.chunkIndex == after.chunkIndex && !after.playing)
        return Geometry(after, layout, position, viewport, scroll, maximum, origin,
            JSONObject().put("index", after.chunkIndex).put("bodyHash", plan.bodyHash).put("planKey", plan.planKey)
                .put("textSha256", SpeedRangeTrialStore.sha(layout.layoutInput.text.text)).put("layoutWidth", layout.size.width)
                .put("layoutHeight", layout.size.height).put("lineCount", layout.lineCount).put("textPositionRoot", point(position))
                .put("derivedTextOriginRoot", if (t.subtractScrollFromOrigin == null) JSONObject.NULL else point(origin))
                .put("originTransformCalibrated", t.subtractScrollFromOrigin != null)
                .put("viewport", rect(viewport)).put("surface", rect(surface))
                .put("scroll", scroll).put("maxScroll", maximum).put("counter", counter).put("fontScale", layout.layoutInput.density.fontScale)
                .put("fontSizeSp", layout.layoutInput.style.fontSize.value).put("lineHeightSp", layout.layoutInput.style.lineHeight.value))
    }

    /** Freeze the actual committed UI value before checking the requested trial value. */
    private fun observeSetup(t: Trial, action: String): Int {
        compose.waitUntil(15_000) { state(t).let { it.stage == SpeedStage.SETUP && it.selectedGranularity == SpeedGranularity.L3 &&
            !it.personalizationBusy && !it.loading } }
        val actual = state(t).groupsPerMinute
        check(actual in 30..180)
        val sliderValue = compose.onNodeWithTag("speed_slider").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current.roundToInt()
        check(sliderValue == actual && exists(hasText("$actual 意群/分钟")))
        t.store.allowedRates.getValue(SpeedGranularity.L3).add(actual)
        t.store.observeProfile(SpeedGranularity.L3, actual, action, "after")
        return actual
    }
    private fun awaitReader() {
        compose.waitUntil(20_000) { exists(hasTestTag("zen_article_text")) && !exists(hasTestTag("speed_reading_screen")) &&
            runCatching { compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().config[ReaderPositionReadyKey] }.getOrDefault(false) }
    }
    private fun state(t: Trial): SpeedUiState {
        var result: SpeedUiState? = null
        requireNotNull(t.activity).onActivity {
            val model = provider(it)[SpeedReadingViewModel::class.java]
            t.observedJobs += requireNotNull(model.viewModelScope.coroutineContext[Job]) { "The observed speed ViewModel has no scope Job" }
            result = model.state.value
        }
        return requireNotNull(result)
    }
    private fun provider(activity: MainActivity) = ViewModelProvider(activity, object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = error("Existing ViewModels only")
    })
    private fun existingJobs(t: Trial): List<Job> {
        val jobs = linkedSetOf<Job>()
        val expected = buildList {
            if (t.shellObserved) {
                addAll(listOf("LexiconViewModel", "ReadingViewModel", "SpeedReadingViewModel"))
                if (t.readerObserved) add("ReadingResumeViewModel")
            }
        }
        val found = JSONArray()
        val receipt = JSONObject().put("activityLaunchAttempted", t.activityLaunchAttempted)
            .put("shellObserved", t.shellObserved).put("readerNavigationAttempted", t.store.readerOpened)
            .put("readerObserved", t.readerObserved).put("requiredModels", JSONArray(expected))
            .put("observedModels", found).put("complete", false)
        t.report.put("requiredViewModelJobs", receipt)
        val scenario = t.activity
        if (scenario == null) {
            check(!t.activityLaunchAttempted && t.observedJobs.isEmpty()) { "An attempted Activity launch has no confirmed lifecycle handle" }
            receipt.put("complete", true)
            return emptyList()
        }
        check(t.shellObserved) { "The initial application page did not establish its required ViewModels" }
        check(!t.store.readerOpened || t.readerObserved) { "Reader navigation did not finish; writer ownership is not established" }
        scenario.onActivity { activity ->
            val p = provider(activity)
            fun retain(name: String, read: () -> ViewModel) {
                receipt.put("checkingModel", name)
                val job = requireNotNull(read().viewModelScope.coroutineContext[Job]) { "Required $name scope Job is absent" }
                jobs += job; found.put(name)
            }
            retain("LexiconViewModel") { p[LexiconViewModel::class.java] }
            retain("ReadingViewModel") { p[ReadingViewModel::class.java] }
            retain("SpeedReadingViewModel") { p[SpeedReadingViewModel::class.java] }
            if (t.readerObserved) retain("ReadingResumeViewModel") {
                p["reading-resume:${SpeedRangeTrialStore.sha(t.store.fixture.article.id)}", ReadingResumeViewModel::class.java]
            }
        }
        check((0 until found.length()).map { found.getString(it) } == expected)
        receipt.remove("checkingModel"); receipt.put("complete", true)
        t.observedJobs += jobs
        return t.observedJobs.toList()
    }
    private fun touch(t: Trial, matcher: SemanticsMatcher, label: String, fallback: SemanticsMatcher? = null) {
        t.stage = label; ensureVisible(t, matcher, fallback); t.guard.check(label)
        val node = compose.onNode(matcher); val bounds = node.fetchSemanticsNode().boundsInRoot
        t.report.getJSONArray("touches").put(JSONObject().put("action", label).put("kind", "touch").put("root", point(bounds.center)))
        node.performTouchInput { advanceEventTime(600); click(center) }
        compose.waitForIdle()
    }
    private fun slider(t: Trial, tag: String, target: Float, low: Float, high: Float, label: String) {
        t.stage = label; val matcher = hasTestTag(tag); ensureVisible(t, matcher); t.guard.check(label)
        val node = compose.onNode(matcher); val current = node.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        val receipt = JSONObject().put("action", label).put("kind", "slider_touch").put("requested", target)
        t.report.getJSONArray("touches").put(receipt)
        node.performTouchInput {
            val inset = 10f * t.store.app.resources.displayMetrics.density
            val track = width - 2f * inset
            val start = Offset(inset + track * ((current - low) / (high - low)), centerY)
            val end = Offset(when (target) {
                low -> 1f
                high -> width - 1f
                else -> inset + track * ((target - low) / (high - low))
            }, centerY)
            receipt.put("startLocal", point(start)).put("endLocal", point(end)); advanceEventTime(600); swipe(start, end, 500)
        }
        compose.waitForIdle()
    }
    private fun touchRootInSurface(t: Trial, root: Offset, label: String) {
        t.stage = label; t.guard.check(label)
        val node = compose.onNodeWithTag("speed_chunk_surface"); val bounds = node.fetchSemanticsNode().boundsInRoot
        check(bounds.contains(root))
        t.report.getJSONArray("touches").put(JSONObject().put("action", label).put("kind", "exact_glyph_touch").put("root", point(root)))
        node.performTouchInput { advanceEventTime(600); click(root - bounds.topLeft) }
        compose.waitForIdle()
    }
    private fun swipeSurface(t: Trial, from: Offset, to: Offset, label: String) {
        t.stage = label; t.guard.check(label)
        val node = compose.onNodeWithTag("speed_chunk_surface"); val bounds = node.fetchSemanticsNode().boundsInRoot
        check(bounds.contains(from) && bounds.contains(to))
        t.report.getJSONArray("touches").put(JSONObject().put("action", label).put("kind", "text_scroll_touch")
            .put("fromRoot", point(from)).put("toRoot", point(to)))
        node.performTouchInput { advanceEventTime(600); swipe(from - bounds.topLeft, to - bounds.topLeft, 500) }
        compose.waitForIdle()
    }
    private fun ensureVisible(t: Trial, matcher: SemanticsMatcher, fallback: SemanticsMatcher? = null) {
        repeat(35) {
            t.guard.check("${t.stage}:reveal")
            val target = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()
            val scroller = compose.onAllNodes(hasScrollAction() and hasAnyDescendant(matcher)).fetchSemanticsNodes()
                .filter { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }.minByOrNull { it.boundsInRoot.height }
                ?: fallback?.let { find -> compose.onAllNodes(find).fetchSemanticsNodes().singleOrNull() }
            if (target != null && target.boundsInRoot.width > 0 && target.boundsInRoot.height > 0 &&
                contains(target.boundsInRoot, fullBounds(target)) && (scroller == null || contains(scroller.boundsInRoot, fullBounds(target)))) return
            check(scroller != null)
            val viewport = scroller.boundsInRoot
            check(viewport.width > 50 && viewport.height > 80)
            val forward = target == null || fullBounds(target).bottom > viewport.bottom
            compose.onNode(SemanticsMatcher("observed scroll container") { it.id == scroller.id }).performTouchInput {
                val from = Offset(centerX, height * if (forward) .73f else .27f)
                val to = Offset(centerX, height * if (forward) .27f else .73f)
                t.report.getJSONArray("touches").put(JSONObject().put("action", "${t.stage}:reveal_$it")
                    .put("kind", "control_scroll_touch").put("forward", forward).put("viewport", rect(viewport))
                    .put("fromLocal", point(from)).put("toLocal", point(to)))
                advanceEventTime(600)
                swipe(from, to, 500)
            }
            compose.waitForIdle()
        }
        error("The requested control could not be reached by real scrolling")
    }
    private fun screenshot(t: Trial, name: String) {
        compose.waitForIdle(); t.guard.check("${t.stage}:screenshot")
        val file = File(t.output, name); check(!file.exists())
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        t.report.getJSONArray("screenshots").put(name)
        File(t.output, "device-trial.json").writeText(t.report.toString(2))
    }
    private fun keyboardVisible(): Boolean {
        var result = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = WindowInspector.getGlobalWindowViews().any { it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        }
        return result
    }
    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    private fun fullBounds(node: SemanticsNode) = Rect(node.positionInRoot.x, node.positionInRoot.y,
        node.positionInRoot.x + node.size.width, node.positionInRoot.y + node.size.height)
    private fun contains(outer: Rect, inner: Rect) = inner.left >= outer.left - TOLERANCE && inner.top >= outer.top - TOLERANCE &&
        inner.right <= outer.right + TOLERANCE && inner.bottom <= outer.bottom + TOLERANCE
    private fun rect(value: Rect) = JSONArray(listOf(value.left, value.top, value.right, value.bottom))
    private fun point(value: Offset) = JSONArray(listOf(value.x, value.y))
    private companion object {
        const val STABLE_MS = 1_200L
        const val TOLERANCE = 1f
        const val CALIBRATION_TOLERANCE = 2f
        const val FIT_MARGIN = 4f
    }
}
