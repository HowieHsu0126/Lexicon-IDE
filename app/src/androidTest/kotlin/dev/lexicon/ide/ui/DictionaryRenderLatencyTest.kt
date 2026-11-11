package dev.lexicon.ide.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.Choreographer
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.data.LexiconDatabase
import dev.lexicon.data.LexiconRepository
import dev.lexicon.domain.AppRepository
import dev.lexicon.domain.AppSettings
import dev.lexicon.domain.Capture
import dev.lexicon.domain.ContextOrigin
import dev.lexicon.domain.DictionaryEntry
import dev.lexicon.domain.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Opt-in, real Choreographer clock, no Compose test rule or keyboard input.
 * Captures exist only in memory; the real packaged MDX is installed in an owned directory.
 * Application startup is outside this fixture's isolation and needs outer preservation.
 */
@RunWith(AndroidJUnit4::class)
class DictionaryRenderLatencyTest {
    @Test
    fun realMdxReturnToMatchingDefinitionDraw() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with dictionaryRender=1", args.getString("dictionaryRender") == "1")
        val label = args.getString("label").orEmpty()
        require(label.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) { "A unique label is required" }
        val measureDetail = args.getString("expanded") == "1"
        val context = instrumentation.targetContext
        assumeTrue("This run requires a packaged personal MDX", context.assets.list("dictionaries").orEmpty().any { it.endsWith(".mdx", true) })
        val output = File(context.filesDir, "validation-1.19.0/dictionary-render/$label.json")
        check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
        check(output.createNewFile()) { "Preserve existing render benchmark evidence" }
        val owned = File(context.cacheDir, "dictionary-render-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(context) {
            private fun dir(name: String) = File(owned, name).apply { check(isDirectory || mkdirs()) }
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = dir("no_backup")
            override fun getFilesDir(): File = dir("files")
            override fun getCacheDir(): File = dir("cache")
            override fun getDatabasePath(name: String): File = File(dir("databases"), name)
        }
        val db = Room.inMemoryDatabaseBuilder<LexiconDatabase>(isolated)
            .setDriver(BundledSQLiteDriver()).build()
        val ownRepository = LexiconRepository(isolated, database = db)
        val registry = RecordingProbe()
        val visibleCapture = mutableStateOf<Capture?>(null)
        var scenario: ActivityScenario<ComponentActivity>? = null
        var model: LexiconViewModel? = null
        var failureClass: String? = null
        var failedAt = "owned_installation"
        var ownedClosed = false
        val report = JSONObject().put("schema", "lexicon.dictionary.render.v1")
            .put("protocol", "1.19-fixed-book-world-robust-4").put("label", label)
            .put("status", "started").put("startedAt", System.currentTimeMillis())
            .put("clock", "System.nanoTime, one process; no virtual Compose clock")
            .put("queries", JSONArray(QUERIES)).put("expandedDetailRequested", measureDetail)
            .put("repository", "Real LexiconRepository.lookup and DictionaryStore over isolated packaged MDX")
            .put("trigger", "Fresh in-memory capture panel for each query; no saved capture, keyboard or text-input driver")
            .put("betweenQueryCleanup", "Set visibleCapture to null on the activity thread, then await two real Choreographer callbacks; navigation correctness is tested separately")
            .put("applicationStartupScope", "Normal target Application may initialize or schedule work; outer before/after preservation is required")
            .put("fixtureWritesInstalledLearningDatabase", false)
            .put("testInvokesAi", false).put("testReadsApiKey", false)
            .put("testStartsScreenshotOrRecorder", false)
            .put("drawMeaning", "CPU drawContent returned for matching word and definition, irrespective of clipped window bounds; not first-visible-frame or display presentation timing")
            .put("visibilityLimitation", "Ancestor layer animation can reveal cached text without another text draw callback; this probe cannot time first on-screen visibility")
            .put("layoutMeaning", "First primary definition layout may contain the whole original, a bounded preview or one detail block; actual layout characters and full result hashes are separate")
            .put("fidelityScope", "List object identity binds the response; exact preview prefixes and lossless detail-block concatenation require independent helper/UI tests")
            .put("firstPassScope", "Each of three fixed words queried once; first reader lookup is book; installation already read the file")
            .put("timingExcludes", "query-result hashing, output serialization, semantic driver idle waits and keyboard input")
            .put("noDefinitionTextInReport", true)
        try {
            val installStarted = System.nanoTime()
            val installed = runBlocking { ownRepository.installedDictionaries() }
            val installReturned = System.nanoTime()
            val dictionary = runBlocking { ownRepository.dictionaryStatus() }
            check(dictionary.ready && dictionary.format == "MDX" && dictionary.id.isNotBlank())
            val selected = installed.single { it.id == dictionary.id }
            val corpus = File(isolated.noBackupFilesDir, "dictionary/${selected.fileName}")
            check(corpus.isFile && sha256(corpus) == dictionary.id)
            report.put("ownedInstallerMs", millis(installReturned - installStarted))
                .put("dictionarySha256", dictionary.id).put("dictionaryBytes", corpus.length())
                .put("dictionaryKeyCount", dictionary.entryCount)
                .put("dictionaryMetadataName", dictionary.name)
                .put("dictionaryEditionIndependentlyVerified", false)
                .put("packagedDictionaryCount", installed.size)
            val fixture = DictionaryLookupTestRepository()
            val settings = AppSettings(theme = if (args.getString("theme") == "night") ThemeMode.DARK else ThemeMode.LIGHT)
            fixture.settings.value = settings
            val repository = object : AppRepository by fixture {
                override suspend fun lookup(text: String): List<DictionaryEntry> {
                    val trace = registry.beginLookup(text)
                    try {
                        val result = ownRepository.lookup(text)
                        val returned = System.nanoTime()
                        registry.returned(trace, result, returned)
                        return result
                    } catch (error: Exception) {
                        trace.failed(error.javaClass.name)
                        throw error
                    }
                }
                override suspend fun initialize() = Unit
                override suspend fun hasApiKey() = false
                override suspend fun dictionaryStatus() = dictionary
                override suspend fun installedDictionaries() = installed
            }
            failedAt = "view_model_initialization"
            val activityScenario = ActivityScenario.launch(ComponentActivity::class.java)
            scenario = activityScenario
            activityScenario.onActivity { activity ->
                val vm = LexiconViewModel(repository).also { model = it }
                activity.setContent {
                    CompositionLocalProvider(LocalDictionaryRenderProbe provides registry) {
                        LexiconTheme(settings) {
                            visibleCapture.value?.let { capture ->
                                CapturePanel(capture, emptyList(), emptyList(), vm, { visibleCapture.value = null }, {}, {})
                            }
                        }
                    }
                }
            }
            // Initialization can restart lookup. Complete it before exposing any target word.
            val initDeadline = SystemClock.uptimeMillis() + 10_000
            var initialized = false
            while (!initialized && SystemClock.uptimeMillis() < initDeadline) {
                instrumentation.runOnMainSync { initialized = model?.dictionaryStatus?.id == dictionary.id }
                if (!initialized) SystemClock.sleep(20)
            }
            check(initialized)
            val metrics = context.resources.displayMetrics
            report.put("fontScale", context.resources.configuration.fontScale)
                .put("displayPixels", "${metrics.widthPixels}x${metrics.heightPixels}")
                .put("density", metrics.density).put("theme", settings.theme.name)
                .put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("device", android.os.Build.MODEL)
                .put("buildType", dev.lexicon.ide.BuildConfig.BUILD_TYPE)
                .put("version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)

            QUERIES.forEachIndexed { index, query ->
                failedAt = "lookup_and_draw_$query"
                val captureId = "render-fixture-$label-${index + 1}"
                val trace = registry.plan(captureId, query, dictionary.id, index + 1L)
                activityScenario.onActivity {
                    registry.activate(trace)
                    visibleCapture.value = Capture(captureId, target = query, selectedText = query,
                        sentence = "", contextOrigin = ContextOrigin.MISSING, createdAt = 1L)
                }
                check(trace.returned.await(30, TimeUnit.SECONDS)) { "Repository did not finish" }
                check(trace.failureClass == null && trace.entries?.isNotEmpty() == true) { "Expected a real MDX result" }
                check(trace.drawn.await(15, TimeUnit.SECONDS)) { "Matching word and definition were not drawn" }
                check(trace.failureClass == null) { "The drawn result did not match its layout identity" }
                check(registry.lookupCount() == index + 1) { "Unexpected repeated or initialization lookup" }
                if (measureDetail && index == 0) {
                    failedAt = "expanded_detail_draw"
                    val button = findClickableText("全部释义", 5_000)
                    trace.armDetail(System.nanoTime())
                    check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Detail action was not dispatched" }
                    check(trace.detailDrawn.await(15, TimeUnit.SECONDS)) { "Expanded definition was not drawn" }
                    check(trace.failureClass == null) { "Expanded layout validation failed" }
                    check(registry.lookupCount() == 1) { "Expanding a definition must not query again" }
                }
                // Measurement is finished. Unmount both old inline and new paged details
                // without scrolling or testing navigation, then mount a fresh next panel.
                failedAt = "panel_unmount"
                activityScenario.onActivity { visibleCapture.value = null }
                awaitTwoFrames()
                check(registry.lookupCount() == index + 1) { "Unmounting must not query again" }
            }
            check(registry.lookupCount() == 3 && registry.rejectedBindings == 0)
            // Result hashes are calculated only after all measured drawing has completed.
            report.put("status", "complete")
        } catch (error: Throwable) {
            failureClass = error.javaClass.name
            report.put("status", "failed").put("failedAt", failedAt).put("failureClass", failureClass)
        } finally {
            runCatching {
                scenario?.onActivity {
                    visibleCapture.value = null
                    model?.viewModelScope?.cancel()
                }
                runBlocking { model?.viewModelScope?.coroutineContext?.get(Job)?.join() }
                scenario?.close()
                // Release only the isolated repository; production has no public close API.
                val ownedStore = ownRepository.javaClass.getDeclaredField("dictionary").apply { isAccessible = true }.get(ownRepository)
                ownedStore.javaClass.getDeclaredMethod("closeOpen").apply { isAccessible = true }.invoke(ownedStore)
                db.close()
                check(owned.deleteRecursively())
                ownedClosed = true
            }.onFailure {
                report.put("cleanupFailureClass", it.javaClass.name).put("status", "failed")
            }
            report.put("lookupInvocationCount", registry.lookupCount())
                .put("rejectedResultBindings", registry.rejectedBindings)
                .put("ownedResourcesClosed", ownedClosed)
                .put("samples", registry.report())
            output.outputStream().use { it.write(report.toString(2).toByteArray()); it.fd.sync() }
        }
        check(failureClass == null && ownedClosed) { "Render benchmark failed at $failedAt; inspect ${output.name}" }
    }

    private fun findClickableText(text: String, timeoutMs: Long): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            findClickableLabel(visibleNodes(), text)?.let { return it }
            SystemClock.sleep(25)
        }
        error("Dictionary detail action was not accessible")
    }

    private fun awaitTwoFrames() {
        val frames = CountDownLatch(2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val choreographer = Choreographer.getInstance()
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    frames.countDown()
                    if (frames.count > 0) choreographer.postFrameCallback(this)
                }
            }
            choreographer.postFrameCallback(callback)
        }
        check(frames.await(5, TimeUnit.SECONDS)) { "Unmount frame callbacks were not delivered" }
    }

    private fun visibleNodes(): List<AccessibilityNodeInfo> {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(queue::add)
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 800) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser) nodes += node
            repeat(node.childCount) { node.getChild(it)?.let(queue::add) }
        }
        return nodes
    }

    private fun findClickableLabel(nodes: List<AccessibilityNodeInfo>, label: String): AccessibilityNodeInfo? {
        nodes.forEach { node ->
            if (node.text?.toString()?.trim() == label || node.contentDescription?.toString()?.trim() == label) {
                var candidate: AccessibilityNodeInfo? = node
                repeat(4) {
                    val current = candidate
                    if (current != null && current.isClickable && current.isEnabled) return current
                    candidate = current?.parent
                }
            }
        }
        return null
    }

    private class RecordingProbe : DictionaryRenderProbe {
        private val traces = mutableListOf<LookupTrace>()
        private val results = IdentityHashMap<Any, LookupTrace>()
        private var active: LookupTrace? = null
        private var calls = 0
        var rejectedBindings = 0; private set
        @Synchronized fun plan(captureId: String, query: String, dictionaryId: String, generation: Long) =
            LookupTrace(captureId, query, dictionaryId, generation).also(traces::add)
        @Synchronized fun activate(trace: LookupTrace) { active = trace; trace.activatedNs = System.nanoTime() }
        @Synchronized fun beginLookup(query: String): LookupTrace {
            val started = System.nanoTime()
            calls++
            val trace = requireNotNull(active)
            check(trace.query == query.trim() && trace.lookupStartedNs == 0L) { "Unexpected lookup generation" }
            trace.lookupStartedNs = started
            return trace
        }
        @Synchronized fun returned(trace: LookupTrace, entries: List<DictionaryEntry>, at: Long) {
            check(!results.containsKey(entries)) { "Result identity reused by a different invocation" }
            trace.entries = entries
            trace.lookupReturnedNs = at
            results[entries] = trace
            trace.returned.countDown()
        }
        @Synchronized override fun bindResult(captureId: String, query: String, dictionaryId: String, resultIdentity: Any): DictionaryResultRenderProbe? {
            val trace = results[resultIdentity]
            if (trace == null || trace !== active || trace.captureId != captureId || trace.query != query.trim() || trace.dictionaryId != dictionaryId) {
                rejectedBindings++
                return null
            }
            trace.bindings++
            return trace
        }
        @Synchronized fun lookupCount() = calls
        @Synchronized fun report() = JSONArray(traces.map { it.report() })
    }

    private data class LayoutMark(val at: Long, val characters: Int, val lines: Int, val ellipsized: Boolean)
    private class LookupTrace(val captureId: String, val query: String, val dictionaryId: String, val generation: Long) : DictionaryResultRenderProbe {
        val returned = CountDownLatch(1)
        val drawn = CountDownLatch(1)
        val detailDrawn = CountDownLatch(1)
        @Volatile var entries: List<DictionaryEntry>? = null
        @Volatile var failureClass: String? = null
        var activatedNs = 0L
        var lookupStartedNs = 0L
        var lookupReturnedNs = 0L
        var bindings = 0
        private var wordLayout: LayoutMark? = null
        private var definitionLayout: LayoutMark? = null
        private var wordDrawNs = 0L
        private var definitionDrawNs = 0L
        private var wordDrawVisibleBounds: Boolean? = null
        private var definitionDrawVisibleBounds: Boolean? = null
        private var clippedDraws = 0
        private var detailClickNs = 0L
        private var detailLayout: LayoutMark? = null
        private var detailDrawNs = 0L
        private var detailDrawVisibleBounds: Boolean? = null
        @Synchronized fun failed(type: String) { failureClass = type; returned.countDown() }
        @Synchronized fun armDetail(at: Long) { detailClickNs = at }
        @Synchronized override fun onTextLayout(index: Int, part: DictionaryTextPart, expanded: Boolean, characters: Int, lines: Int, ellipsized: Boolean) {
            val at = System.nanoTime()
            if (index != 0) return
            val entry = entries?.firstOrNull() ?: return
            val expectedCharacters = if (part == DictionaryTextPart.WORD) entry.word.length + 2 + entry.phonetic.length else entry.definition.ifBlank { "无释义" }.length
            if (characters <= 0 || characters > expectedCharacters || lines <= 0 || at < lookupReturnedNs) {
                failureClass = "layout_identity_mismatch"
                drawn.countDown(); detailDrawn.countDown()
                return
            }
            val mark = LayoutMark(at, characters, lines, ellipsized)
            if (!expanded) {
                if (part == DictionaryTextPart.WORD && wordLayout == null) wordLayout = mark
                if (part == DictionaryTextPart.DEFINITION && definitionLayout == null) definitionLayout = mark
            } else if (part == DictionaryTextPart.DEFINITION && detailClickNs > 0 && detailLayout == null) {
                if (ellipsized) {
                    failureClass = "expanded_definition_ellipsized"
                    detailDrawn.countDown()
                    return
                }
                detailLayout = mark
            }
        }
        @Synchronized override fun onTextDraw(index: Int, part: DictionaryTextPart, expanded: Boolean, visibleBounds: Boolean) {
            val at = System.nanoTime()
            if (index != 0) return
            if (!visibleBounds) clippedDraws++
            if (!expanded) {
                if (part == DictionaryTextPart.WORD && wordLayout != null && wordDrawNs == 0L) {
                    wordDrawNs = at
                    wordDrawVisibleBounds = visibleBounds
                }
                if (part == DictionaryTextPart.DEFINITION && definitionLayout != null && definitionDrawNs == 0L) {
                    definitionDrawNs = at
                    definitionDrawVisibleBounds = visibleBounds
                }
                if (wordDrawNs > 0 && definitionDrawNs > 0) drawn.countDown()
            } else if (part == DictionaryTextPart.DEFINITION && detailLayout != null && detailDrawNs == 0L) {
                detailDrawNs = at
                detailDrawVisibleBounds = visibleBounds
                detailDrawn.countDown()
            }
        }
        @Synchronized fun report(): JSONObject {
            val result = entries.orEmpty()
            val report = JSONObject().put("probeGeneration", generation).put("query", query)
                .put("captureIdSha256", hash(captureId)).put("dictionarySha256", dictionaryId)
                .put("lookupStartedNs", lookupStartedNs).put("lookupReturnedNs", lookupReturnedNs)
                .put("bindingCount", bindings).put("resultListIdentityBound", bindings > 0)
                .put("matchedEntries", result.size).put("clippedDrawCallbacksObserved", clippedDraws)
                .put("wordFirstDrawSubmissionNs", wordDrawNs).put("definitionFirstDrawSubmissionNs", definitionDrawNs)
                .put("wordFirstDrawHadVisibleBounds", wordDrawVisibleBounds ?: JSONObject.NULL)
                .put("definitionFirstDrawHadVisibleBounds", definitionDrawVisibleBounds ?: JSONObject.NULL)
                .put("resultSignatures", JSONArray(result.map { JSONObject().put("wordSha256", hash(it.word))
                    .put("definitionSha256", hash(it.definition)).put("definitionCharacters", it.definition.length) }))
            if (lookupStartedNs > 0 && lookupReturnedNs > 0) report.put("repositoryCallMs", millis(lookupReturnedNs - lookupStartedNs))
            if (lookupStartedNs > 0) report.put("captureRebindToRepositoryStartMs", millis(lookupStartedNs - activatedNs))
            wordLayout?.let { report.put("wordLayout", layoutJson(it)) }
            definitionLayout?.let {
                report.put("definitionLayout", layoutJson(it))
                report.put("repositoryReturnToDefinitionLayoutMs", millis(it.at - lookupReturnedNs))
            }
            if (wordDrawNs > 0 && definitionDrawNs > 0) {
                val entryDraw = maxOf(wordDrawNs, definitionDrawNs)
                report.put("repositoryReturnToEntryDrawSubmissionMs", millis(entryDraw - lookupReturnedNs))
                    .put("repositoryStartToEntryDrawSubmissionMs", millis(entryDraw - lookupStartedNs))
                    .put("definitionLayoutToDrawSubmissionMs", millis(definitionDrawNs - requireNotNull(definitionLayout).at))
            }
            if (detailClickNs > 0) {
                report.put("detailClickDispatchedNs", detailClickNs)
                detailLayout?.let { report.put("expandedDefinitionLayout", layoutJson(it)) }
                report.put("detailFirstDrawSubmissionNs", detailDrawNs)
                    .put("detailFirstDrawHadVisibleBounds", detailDrawVisibleBounds ?: JSONObject.NULL)
                if (detailDrawNs > 0) report.put("detailClickDispatchToFirstDrawSubmissionMs", millis(detailDrawNs - detailClickNs))
            }
            failureClass?.let { report.put("failureClass", it) }
            return report
        }
        private fun layoutJson(mark: LayoutMark) = JSONObject().put("atNs", mark.at).put("characters", mark.characters)
            .put("lineCount", mark.lines).put("ellipsized", mark.ellipsized)
    }

    private companion object {
        val QUERIES = listOf("book", "world", "robust")
        fun millis(nanos: Long) = nanos / 1_000_000.0
        fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
