package dev.lexicon.ide.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in actual SETUP page only; all navigation and edits use touch input, never prepare/play. */
@RunWith(AndroidJUnit4::class)
class SpeedSetupDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun repeatedGranularityAndRoundTripKeepTheChosenSetupRates() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Speed setup device trial is opt-in", args.getString("speedSetupTrial") == "1")
        val phase = requireNotNull(args.getString("phase")).also { require(it in setOf("before", "after")) }
        val variant = (args.getString("variant") ?: "day-font100").also { require(it in setOf("day-font100", "night-font200")) }
        val run = requireNotNull(args.getString("uniqueRun")).also { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) }
        require(args.getString("expectedUserId") == "10")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as LexiconApplication
        val user = Regex("^/data/user/(\\d+)/").find(app.applicationInfo.dataDir)?.groupValues?.get(1)?.toInt()
        check(user == 10) { "This trial is restricted to dedicated Android user 10" }
        val output = File(app.filesDir, "validation-speed-setup-2026-11-06/$run")
        check(!output.exists() && output.mkdirs()) { "Use a new uniqueRun; keep all earlier evidence" }
        val version = app.packageManager.getPackageInfo(app.packageName, 0)
        val report = JSONObject().put("schema", "lexicon.speed.setup.trial.v1").put("status", "started")
            .put("uniqueRun", run).put("phase", phase).put("variant", variant).put("androidUserId", user)
            .put("version", version.versionName).put("versionCode", version.longVersionCode)
            .put("apkSha256", SpeedSetupTrialStore.sha(File(app.applicationInfo.sourceDir)))
            .put("actualMainActivity", true).put("automatedJourney", true).put("jsonContainsLearningText", false)
            .put("materialOrigin", "Four self-authored fictional paragraphs; not publisher or comprehension evidence")
            .put("databaseDriver", "androidx.sqlite.driver.bundled.BundledSQLiteDriver")
            .put("aiActions", 0).put("prepareActions", 0).put("playActions", 0).put("ratingsRequested", 0)
            .put("originalRowsRestoredOrReplaced", 0).put("touches", JSONArray()).put("observations", JSONArray())
            .put("profileStates", JSONArray()).put("screenshots", JSONArray())
            .put("protocolFrozenBeforeUi", JSONObject().put("defaultProfileEnabled", true).put("defaultRates", 60)
                .put("l3RequestedRate", 130).put("l1RequestedRate", 95).put("l2RequestedRate", 135)
                .put("sliderTargetTolerance", 8).put("stableMs", STABLE_MS)
                .put("afterContract", "Repeated selected L3 preserves the visible edited value; L1→L2→L1 restores the earlier L1 value exactly")
                .put("profileOwnership", "Initially absent; only canonical rows matching observed real UI choices, deleted by exact key and data")
                .put("noTraining", "Do not touch prepare/play/AI; no new speed plan, attempt, correction or run is allowed"))
        val file = File(output, "device-trial.json")
        fun save() { file.writeText(report.toString(2)) }
        save()
        var trial: Trial? = null
        var observed = false; var desired = false; var preserved = false
        try {
            SpeedSetupTrialStore.offlineNoKey(app)
            runBlocking { app.repository.initialize(); app.repository.installedDictionaries(); app.repository.dictionaryStatus() }
            val expectedDark = variant == "night-font200"
            val configuration = app.resources.configuration
            val night = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            check(abs(configuration.fontScale - if (expectedDark) 2f else 1f) < .02f)
            check((night == Configuration.UI_MODE_NIGHT_YES) == expectedDark)
            report.put("actualFontScale", configuration.fontScale).put("actualNightMode", night).put("effectiveDark", expectedDark)
            val store = SpeedSetupTrialStore(app, run, System.currentTimeMillis(), report)
            val t = Trial(store, output, report, phase); trial = t
            store.preflight(); save(); store.insertFixture()
            t.stage = "history_to_original_reader"
            t.activity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(20_000) { exists(hasTestTag("daily_reading")) }
            touchVisible(t, hasText("读过的文章"), "open_history", hasTestTag("daily_reading"))
            compose.waitUntil(15_000) { exists(hasTestTag("article_history_list")) }
            store.readerOpened = true
            touchVisible(t, hasText(store.fixture.article.title), "open_owned_article", hasTestTag("article_history_list"))
            compose.waitUntil(20_000) { exists(hasTestTag("zen_article_text")) && runCatching {
                compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().config[ReaderPositionReadyKey]
            }.getOrDefault(false) }
            touchVisible(t, hasText("精读 · 工具"), "open_reading_tools")
            compose.waitUntil(10_000) { exists(hasTestTag("reader_tools_sheet")) }
            touchVisible(t, hasText("意群速读 →"), "open_speed_setup")
            compose.waitUntil(15_000) { exists(hasTestTag("speed_reading_screen")) && exists(hasTestTag("speed_prepare_offline")) }
            compose.waitUntil(15_000) { readState(t).let { it.personalizationAvailable && !it.personalizationBusy && it.profile == PersonalizationProfile() } }
            val initial = observe(t, "01-initial-setup", SpeedGranularity.L2)
            check(initial == 60)

            select(t, SpeedGranularity.L3, "select_l3")
            check(observe(t, "02-l3-default", SpeedGranularity.L3) == 60)
            val l3 = editRate(t, SpeedGranularity.L3, 130, "03-l3-edited")
            select(t, SpeedGranularity.L3, "tap_already_selected_l3")
            val l3Repeated = observe(t, "04-l3-reselected", SpeedGranularity.L3)
            report.put("idempotentSelection", JSONObject().put("before", l3).put("after", l3Repeated)
                .put("resetToDefaultObserved", l3Repeated == 60 && l3 != 60).put("preserved", l3Repeated == l3))

            select(t, SpeedGranularity.L1, "select_l1")
            observe(t, "05-l1-before-edit", SpeedGranularity.L1)
            val l1 = editRate(t, SpeedGranularity.L1, 95, "06-l1-edited")
            select(t, SpeedGranularity.L2, "select_l2")
            observe(t, "07-l2-before-edit", SpeedGranularity.L2)
            val l2 = editRate(t, SpeedGranularity.L2, 135, "08-l2-edited")
            select(t, SpeedGranularity.L1, "return_to_l1")
            val l1Returned = observe(t, "09-l1-returned", SpeedGranularity.L1)
            report.put("granularityRoundTrip", JSONObject().put("l1Edited", l1).put("l2Edited", l2)
                .put("l1Returned", l1Returned).put("resetToDefaultObserved", l1Returned == 60 && l1 != 60)
                .put("preserved", l1Returned == l1))
            observed = true; desired = l3Repeated == l3 && l1Returned == l1
            report.put("desiredBehaviorVerified", desired)
            t.stage = "leave_setup_without_training"
            touchVisible(t, hasContentDescription("返回阅读"), "return_to_original_reader")
            compose.waitUntil(15_000) { exists(hasTestTag("zen_article_text")) && !exists(hasTestTag("speed_reading_screen")) }
            store.quietRows()
            store.observeProfile(SpeedGranularity.L1, l1Returned, "returned_to_reader", phase, afterExit = true)
            screenshot(t, "10-returned-to-reader.png")
            check(!keyboardVisible())
            SpeedSetupTrialStore.offlineNoKey(app)
            report.put("returnedToOriginalReader", true).put("allNavigationUsedTouch", true)
        } catch (e: Throwable) {
            report.put("failedStage", trial?.stage ?: "preflight").put("failureClass", e.javaClass.simpleName)
                .put("failureLocations", JSONArray(e.stackTrace.take(10).map { "${it.fileName}:${it.lineNumber}" }))
            trial?.let { runCatching { screenshot(it, "failure.png") } }
        } finally {
            trial?.let { t ->
                val jobs = existingJobs(t)
                runCatching { t.activity?.close() }.onFailure { observed = false; report.put("closeFailureClass", it.javaClass.simpleName) }
                t.activity = null
                runCatching { runBlocking { withTimeout(15_000) { jobs.joinAll() } }; t.store.preserve(); preserved = true }
                    .onFailure { e -> report.put("preservationFailureClass", e.javaClass.simpleName)
                        .put("preservationFailureLocations", JSONArray(e.stackTrace.take(10).map { "${it.fileName}:${it.lineNumber}" })) }
            }
            val journey = observed && report.optBoolean("returnedToOriginalReader")
            report.put("observed", observed).put("journeyPassed", journey).put("originalDataPreserved", preserved)
                .put("status", when { !journey || !preserved || (phase == "after" && !desired) -> "failed"
                    phase == "before" -> "baseline_observed"; else -> "passed" })
            save()
        }
        check(report.getString("status") in setOf("passed", "baseline_observed")) { "Setup trial failed; inspect the retained report" }
    }

    private class Trial(val store: SpeedSetupTrialStore, val output: File, val report: JSONObject, val phase: String) {
        var activity: ActivityScenario<MainActivity>? = null
        var stage = "preflight"
    }
    private fun select(t: Trial, level: SpeedGranularity, label: String) {
        t.stage = label; t.store.setupActions++
        touchVisible(t, hasText(levelLabel(level)), label)
    }
    private fun editRate(t: Trial, level: SpeedGranularity, target: Int, label: String): Int {
        t.stage = label
        val matcher = hasTestTag("speed_slider")
        ensureVisible(t, matcher)
        val node = compose.onNode(matcher); val bounds = node.fetchSemanticsNode().boundsInRoot
        val current = node.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        val fraction = (target - 30f) / 150f
        val currentFraction = (current - 30f) / 150f
        val receipt = JSONObject().put("action", label).put("kind", "slider_touch_drag").put("requestedRate", target)
        t.report.getJSONArray("touches").put(receipt); t.store.setupActions++
        node.performTouchInput {
            val inset = 10f * t.store.app.resources.displayMetrics.density
            val usable = (width - 2 * inset).coerceAtLeast(1f)
            val start = Offset(inset + usable * currentFraction, centerY)
            val end = Offset(inset + usable * fraction, centerY)
            receipt.put("startLocal", pair(start)).put("endLocal", pair(end))
                .put("startRoot", pair(start + bounds.topLeft)).put("endRoot", pair(end + bounds.topLeft))
            advanceEventTime(600); swipe(start, end, 500)
        }
        val rate = observe(t, label, level, introducedSliderRate = true)
        check(abs(rate - target) <= 8 && rate != 60) { "Real slider did not reach the requested approximate rate" }
        return rate
    }
    private fun observe(t: Trial, label: String, level: SpeedGranularity, introducedSliderRate: Boolean = false): Int {
        t.stage = label
        ensureVisible(t, hasTestTag("speed_rate_label"))
        var last = ""; var since = SystemClock.elapsedRealtime(); var value = -1
        compose.waitUntil(15_000) {
            val state = readState(t)
            val text = compose.onNodeWithTag("speed_rate_label").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
            val parsed = Regex("^(\\d+) 意群/分钟$").matchEntire(text)?.groupValues?.get(1)?.toInt()
            val progress = compose.onNodeWithTag("speed_slider").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current.roundToInt()
            val signature = "$text:${state.selectedGranularity}:${state.groupsPerMinute}:${state.profile.revision}:${state.personalizationBusy}"
            if (signature != last) { last = signature; since = SystemClock.elapsedRealtime() }
            if (parsed != null) value = parsed
            state.stage == SpeedStage.SETUP && state.plan == null && !state.playing && !state.loading && !state.personalizationBusy &&
                state.selectedGranularity == level && parsed == state.groupsPerMinute && parsed == progress &&
                SystemClock.elapsedRealtime() - since >= STABLE_MS
        }
        check(value in 30..180 && !keyboardVisible())
        check(compose.onNodeWithText(levelLabel(level)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true)
        if (introducedSliderRate) t.store.allowedRates.getValue(level).add(value)
        t.store.observeProfile(level, value, label, t.phase)
        t.report.getJSONArray("observations").put(JSONObject().put("phase", label).put("selectedGranularity", level.name)
            .put("visibleRate", value).put("sliderAndViewModelAgree", true).put("stage", "SETUP").put("planAbsent", true)
            .put("playing", false).put("imeHidden", true).put("stableForMs", SystemClock.elapsedRealtime() - since))
        screenshot(t, "$label.png")
        return value
    }

    /** Exposes only the current state of an already-created real ViewModel; no state injection. */
    private fun readState(t: Trial): SpeedUiState {
        var result: SpeedUiState? = null
        requireNotNull(t.activity).onActivity { result = provider(it)[SpeedReadingViewModel::class.java].state.value }
        return requireNotNull(result)
    }
    private fun provider(activity: MainActivity) = ViewModelProvider(activity, object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = error("Existing ViewModels only")
    })
    private fun existingJobs(t: Trial): List<Job> {
        val jobs = linkedSetOf<Job>()
        t.activity?.onActivity { activity ->
            val provider = provider(activity)
            fun retain(find: () -> ViewModel) { runCatching { find().viewModelScope.coroutineContext[Job] }.getOrNull()?.let(jobs::add) }
            retain { provider[LexiconViewModel::class.java] }; retain { provider[ReadingViewModel::class.java] }
            retain { provider[SpeedReadingViewModel::class.java] }
            if (t.store.readerOpened) retain { provider["reading-resume:${SpeedSetupTrialStore.sha(t.store.fixture.article.id)}", ReadingResumeViewModel::class.java] }
        }
        t.report.put("joinedExistingViewModelJobs", jobs.size)
        return jobs.toList()
    }

    private fun touchVisible(t: Trial, matcher: SemanticsMatcher, label: String, fallback: SemanticsMatcher? = null) {
        ensureVisible(t, matcher, fallback)
        val node = compose.onNode(matcher); node.assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val receipt = JSONObject().put("action", label).put("kind", "touch")
        t.report.getJSONArray("touches").put(receipt)
        node.performTouchInput { receipt.put("local", pair(center)).put("root", pair(center + bounds.topLeft)); advanceEventTime(600); click(center) }
        compose.waitForIdle()
    }
    private fun ensureVisible(t: Trial, matcher: SemanticsMatcher, fallback: SemanticsMatcher? = null) {
        repeat(35) { attempt ->
            val target = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()
            val scrollers = compose.onAllNodes(hasScrollAction() and hasAnyDescendant(matcher)).fetchSemanticsNodes()
                .filter { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            val scroller = scrollers.minByOrNull { it.boundsInRoot.height }
                ?: fallback?.let { compose.onAllNodes(it).fetchSemanticsNodes().singleOrNull() }
            if (target != null) {
                val full = fullBounds(target); val clipped = target.boundsInRoot
                if (clipped.width > 0f && clipped.height > 0f && contains(clipped, full) &&
                    (scroller == null || contains(scroller.boundsInRoot, full))) return
            }
            check(scroller != null) { "No visible scroll container for the requested control" }
            val viewport = scroller.boundsInRoot
            check(viewport.width > 50 && viewport.height > 80)
            val upward = target == null || fullBounds(target).bottom > viewport.bottom
            val receipt = JSONObject().put("action", "reveal_control_${t.report.getJSONArray("touches").length()}_$attempt")
                .put("kind", "scroll_touch").put("durationMs", 500)
            t.report.getJSONArray("touches").put(receipt)
            compose.onNode(SemanticsMatcher("current scroll container") { it.id == scroller.id }).performTouchInput {
                val start = Offset(centerX, height * if (upward) .73f else .27f)
                val end = Offset(centerX, height * if (upward) .27f else .73f)
                receipt.put("startLocal", pair(start)).put("endLocal", pair(end))
                    .put("startRoot", pair(start + viewport.topLeft)).put("endRoot", pair(end + viewport.topLeft))
                swipe(start, end, 500)
            }
            compose.waitForIdle()
        }
        error("Real scrolling did not reveal the requested control")
    }
    private fun screenshot(t: Trial, name: String) {
        compose.waitForIdle()
        val file = File(t.output, name); check(!file.exists())
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() }
        t.report.getJSONArray("screenshots").put(name)
        File(t.output, "device-trial.json").writeText(t.report.toString(2))
    }
    private fun keyboardVisible(): Boolean {
        var visible = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            visible = WindowInspector.getGlobalWindowViews().any { it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        }
        return visible
    }
    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    private fun fullBounds(node: SemanticsNode) = Rect(node.positionInRoot.x, node.positionInRoot.y,
        node.positionInRoot.x + node.size.width, node.positionInRoot.y + node.size.height)
    private fun contains(outer: Rect, inner: Rect) = inner.left >= outer.left - 1 && inner.top >= outer.top - 1 &&
        inner.right <= outer.right + 1 && inner.bottom <= outer.bottom + 1
    private fun pair(point: Offset) = JSONArray(listOf(point.x, point.y))
    private fun levelLabel(level: SpeedGranularity) = when (level) {
        SpeedGranularity.L1 -> "L1 · 舒适"; SpeedGranularity.L2 -> "L2 · 标准"; SpeedGranularity.L3 -> "L3 · 挑战"
    }
    private companion object { const val STABLE_MS = 1_200L }
}
