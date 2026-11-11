package dev.lexicon.ide.ui

import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/** Opt-in, real Choreographer clock. No Compose test clock, API, database, screenshots or recorder. */
@RunWith(AndroidJUnit4::class)
class ReadingFrameBenchmarkTest {
    @Test fun fixedNativeReadingScroll() {
        val arguments = InstrumentationRegistry.getArguments()
        val label = arguments.getString("lexiconBenchmarkLabel").orEmpty()
        assumeTrue("Run explicitly with lexiconBenchmarkLabel", label.matches(Regex("[a-z0-9-]+")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val text = (1..32).joinToString("\n\n") { n ->
            "Paragraph $n. Public institutions shape how a city responds to change. " +
                "Researchers compare the evidence before drawing a conclusion. A robust argument distinguishes " +
                "an observed association from a causal explanation. Readers can examine each claim in its original " +
                "context and connect the supporting details to the wider discussion. Clear information helps a " +
                "community understand the choices available and the trade-offs involved."
        }
        val article = Article("benchmark", "benchmark", listOf("benchmark"), "Reading evidence in context", "https://example.org/benchmark", "", sourceName = "Local performance fixture")
        val body = ArticleBody(article.id, text, fingerprint(text), 1, Long.MAX_VALUE, "Self-authored test fixture")
        val metricsThread = HandlerThread("lexicon-frame-metrics").apply { start() }
        val samples = Collections.synchronizedList(mutableListOf<FrameMetrics>())
        var dropped = 0
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropCount ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                // The framework reuses its callback object. Keep a snapshot for every frame.
                samples.add(FrameMetrics(metrics))
                dropped += dropCount
            }
        }
        val probe = ReaderRenderProbe()
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.onActivity { activity -> activity.setContent { CompositionLocalProvider(LocalReaderRenderProbe provides probe) { LexiconTheme(AppSettings()) {
                ZenReader(article, body, false, AppSettings(), false, false, {}, {}, {}, {}, {}, { _, _ -> })
            } } } }
            SystemClock.sleep(4500) // arrival, toolbar and font warm-up, outside measured window
            val bounds = context.resources.displayMetrics
            val x = bounds.widthPixels / 2
            val top = (bounds.heightPixels * .26).toInt()
            val bottom = (bounds.heightPixels * .76).toInt()
            fun swipe(up: Boolean) {
                val command = "input swipe $x ${if (up) bottom else top} $x ${if (up) top else bottom} 360"
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
                SystemClock.sleep(200)
            }
            swipe(true); swipe(false)
            val runs = JSONArray()
            repeat(3) { run ->
                samples.clear(); dropped = 0
                val countsBefore = probe.snapshot()
                scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, Handler(metricsThread.looper)) }
                val started = SystemClock.elapsedRealtime()
                repeat(4) { swipe(true) }; repeat(4) { swipe(false) }
                SystemClock.sleep(500)
                scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
                val copy = synchronized(samples) { samples.toList() }
                val durations = copy.map { it.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0 }.sorted()
                fun percentile(p: Double) = durations.getOrElse((durations.size * p).toInt().coerceAtMost(durations.lastIndex.coerceAtLeast(0))) { 0.0 }
                val components = JSONObject()
                mapOf(
                    "unknownDelay" to FrameMetrics.UNKNOWN_DELAY_DURATION,
                    "input" to FrameMetrics.INPUT_HANDLING_DURATION,
                    "animation" to FrameMetrics.ANIMATION_DURATION,
                    "layout" to FrameMetrics.LAYOUT_MEASURE_DURATION,
                    "draw" to FrameMetrics.DRAW_DURATION,
                    "sync" to FrameMetrics.SYNC_DURATION,
                    "commandIssue" to FrameMetrics.COMMAND_ISSUE_DURATION,
                    "swapBuffers" to FrameMetrics.SWAP_BUFFERS_DURATION,
                    "gpu" to FrameMetrics.GPU_DURATION,
                ).forEach { (name, metric) ->
                    val raw = copy.map { it.getMetric(metric) }
                    val available = raw.filter { it >= 0 }.sorted()
                    components.put(name, JSONObject().put("rawNs", JSONArray(raw))
                        .put("availableFrames", available.size)
                        .put("p95Ms", available.getOrNull((available.size * .95).toInt())?.div(1_000_000.0) ?: JSONObject.NULL))
                }
                runs.put(JSONObject().put("run", run + 1).put("frames", copy.size).put("wallMs", SystemClock.elapsedRealtime() - started)
                    .put("p50Ms", percentile(.5)).put("p95Ms", percentile(.95)).put("p99Ms", percentile(.99))
                    .put("missedDeadline", copy.count { it.getMetric(FrameMetrics.DEADLINE) > 0 && it.getMetric(FrameMetrics.TOTAL_DURATION) > it.getMetric(FrameMetrics.DEADLINE) }).put("callbackDrops", dropped)
                    .put("components", components)
                    .put("renderEvents", JSONObject(probe.snapshot().mapValues { (key, value) -> if (key == "MAX_GRADIENT_STOPS") value else value - countsBefore.getOrDefault(key, 0) }))
                    .put("rawTotalNs", JSONArray(copy.map { it.getMetric(FrameMetrics.TOTAL_DURATION) })).put("rawDeadlineNs", JSONArray(copy.map { it.getMetric(FrameMetrics.DEADLINE) })))
            }
            val output = context.filesDir.resolve("reading-benchmark/$label.json")
            output.parentFile!!.mkdirs()
            check(output.createNewFile()) { "Preserve existing frame benchmark evidence" }
            output.writeText(JSONObject().put("label", label).put("fixtureSha256", body.contentHash).put("paragraphs", 32)
                .put("display", "${bounds.widthPixels}x${bounds.heightPixels}").put("density", bounds.density)
                .put("fontScale", context.resources.configuration.fontScale)
                .put("animatorScale", Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
                .put("device", android.os.Build.MODEL).put("hardware", android.os.Build.HARDWARE)
                .put("buildType", dev.lexicon.ide.BuildConfig.BUILD_TYPE).put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("protocol", "Warm-up then 3 runs; each 4 up + 4 down shell swipes, 360ms + 200ms settle; no recorder")
                .put("runs", runs).toString(2))
            check(runs.length() == 3 && (0..2).all { runs.getJSONObject(it).getInt("frames") > 0 })
        } finally { scenario.close(); metricsThread.quitSafely() }
    }
}
