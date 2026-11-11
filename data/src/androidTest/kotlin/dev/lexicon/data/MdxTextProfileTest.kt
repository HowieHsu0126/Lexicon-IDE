package dev.lexicon.data

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.data.mdict.MdxReadObserver
import dev.lexicon.data.mdict.MdxReadStage
import dev.lexicon.data.mdict.MdxReader
import dev.lexicon.data.mdict.MdxTextObserver
import dev.lexicon.data.mdict.MdxTextStage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opt-in hotspot diagnosis, not a general lookup benchmark. One invocation in a fresh
 * process: dictionaryTextProfile=1, class=<this class>#fixedThreeWordsExposeSanitizerStages,
 * phase=<unique label>, rep=<unique label>, sourceManifestSha256=<frozen input manifest>.
 * Constructor/header and first-use text processing are separate. Repeat calls exercise
 * the three already sanitized entries. Use the unchanged 1.20 fixed128 test for comparison.
 */
@RunWith(AndroidJUnit4::class)
class MdxTextProfileTest {
    @Test fun fixedThreeWordsExposeSanitizerStages() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with dictionaryTextProfile=1", args.getString("dictionaryTextProfile") == "1")
        check(invoked.compareAndSet(false, true)) { "Use a fresh instrumentation process" }
        check(args.getString("class") in setOf(TEST_CLASS, "$TEST_CLASS#fixedThreeWordsExposeSanitizerStages"))
        fun label(name: String) = args.getString(name).orEmpty().also {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) { "Missing or invalid $name" }
        }
        val phase = label("phase")
        val rep = label("rep")
        val manifestSha = args.getString("sourceManifestSha256").orEmpty().also {
            require(it.matches(Regex("[a-f0-9]{64}"))) { "Missing sourceManifestSha256" }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File(context.filesDir, "validation-1.21.0/mdx-text-profile/$phase").apply {
            check(isDirectory || mkdirs())
        }.resolve("rep-$rep.json")
        check(output.createNewFile()) { "Preserve previous evidence; use a new phase/rep" }
        val owned = File(context.cacheDir, "mdx-text-profile-${UUID.randomUUID()}")
        val startedAt = Instant.now().toString()
        val started = System.nanoTime()
        val processStart = Process.getStartElapsedRealtime()
        val processAge = SystemClock.elapsedRealtime() - processStart
        val recorder = Recorder()
        var reader: MdxReader? = null
        var constructorNanos: Long? = null
        var constructorStages: JsonObject? = null
        var targetApk: JsonObject? = null
        var testApk: JsonObject? = null
        var setupNanos: Long? = null
        var corpusVerified = false
        var failedAt = "setup"
        var failure: Throwable? = null
        var closeFailure: Throwable? = null
        var removed = false
        val samples = mutableListOf<Sample>()
        try {
            check(owned.mkdir())
            val setupStart = System.nanoTime()
            val rows = context.assets.open("dictionaries/catalog.tsv").bufferedReader().use { it.readLines() }
            val asset = rows.map { it.split('\t', limit = 2) }.single { it.size == 2 && it[0] == CORPUS_SHA }[1]
            require(asset.isNotBlank() && asset.none { it in "/\\\r\n\t" })
            val dictionary = File(owned, "corpus.mdx")
            context.assets.open("dictionaries/$asset").use { input -> dictionary.outputStream().use { sink ->
                val bytes = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    total += count
                    check(total <= CORPUS_BYTES)
                    sink.write(bytes, 0, count)
                }
                sink.fd.sync()
            } }
            check(dictionary.length() == CORPUS_BYTES && hash(dictionary) == CORPUS_SHA)
            corpusVerified = true
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            targetApk = apk(instrumentation.targetContext)
            testApk = apk(instrumentation.context)
            setupNanos = System.nanoTime() - setupStart
            // No MdxReader installation, validation, normalization or query precedes this point.
            failedAt = "constructor"
            recorder.clear()
            val openStart = System.nanoTime()
            try { reader = MdxReader(dictionary, observer = recorder, textObserver = recorder) } finally {
                constructorNanos = System.nanoTime() - openStart
                constructorStages = recorder.snapshot()
            }
            val opened = checkNotNull(reader)
            check(opened.metadata.entries == 37_375L)
            check(recorder.textCalls.all { it == 0L }) { "Text cleanup occurred during constructor" }
            listOf("first_three", "immediate_repeat_three").forEach { pass ->
                words.forEachIndexed { index, word ->
                    failedAt = "$pass:$index"
                    check(System.nanoTime() - started < 60_000_000_000L) { "Profile cooperative budget exceeded" }
                    recorder.clear()
                    var result: List<MdxReader.Entry> = emptyList()
                    var complete = false
                    val before = System.nanoTime()
                    try { result = opened.lookup(word); complete = true } finally {
                        val elapsed = System.nanoTime() - before
                        samples += Sample(pass, index, elapsed, recorder.snapshot(), complete, result)
                    }
                }
            }
            failedAt = "result_and_stage_checks"
            check(samples.size == 6)
            (0..2).forEach { index ->
                val first = samples[index]
                val second = samples[index + 3]
                check(first.result.isNotEmpty() && first.result == second.result) { "Result changed between passes" }
                val textFirst = first.stages.getValue("text") as JsonObject
                val textSecond = second.stages.getValue("text") as JsonObject
                fun calls(stage: MdxTextStage) =
                    ((textFirst.getValue(stage.name) as JsonObject).getValue("calls") as JsonPrimitive).content.toLong()
                val expected = first.result.size.toLong()
                val required = listOf(MdxTextStage.PARSE, MdxTextStage.EXTRACT_TEXT,
                    MdxTextStage.NORMALIZE_LINES, MdxTextStage.FINALIZE)
                check(required.all { calls(it) == expected }) { "Required cleanup stage coverage differs" }
                val optional = listOf(MdxTextStage.PHONETICS, MdxTextStage.REMOVE_ELEMENTS, MdxTextStage.INSERT_BREAKS)
                val layout = when {
                    optional.all { calls(it) == expected } -> "separate"
                    optional.all { calls(it) == 0L } -> "fused_into_extract"
                    else -> error("Unrecognized cleanup stage layout")
                }
                first.cleanupLayout = layout
                check(MdxTextStage.entries.all { stage ->
                    ((textSecond.getValue(stage.name) as JsonObject).getValue("calls") as JsonPrimitive).content == "0"
                }) { "Immediate repeat must reuse sanitized entries" }
                // A cache hit executes no cleanup. Retain the corresponding uncached
                // layout explicitly, rather than inferring a new layout from zero calls.
                second.cleanupLayout = layout
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            runCatching { reader?.close() }.onFailure { closeFailure = it }
            removed = !owned.exists() || owned.deleteRecursively()
            val report = buildJsonObject {
                put("schema", "lexicon.mdx.text-profile.v1")
                put("protocol", "1.21-text-stages-fixed3-v1")
                put("status", if (failure == null && closeFailure == null && removed) "complete" else "failed")
                put("phase", phase); put("repetition", rep); put("started_at", startedAt)
                put("source_manifest_sha256", manifestSha)
                put("pid", Process.myPid()); put("process_start_elapsed_ms", processStart)
                put("process_age_at_test_start_ms", processAge)
                put("device", buildJsonObject {
                    put("model", Build.MODEL); put("fingerprint", Build.FINGERPRINT); put("sdk", Build.VERSION.SDK_INT)
                    put("supported_abis", JsonArray(Build.SUPPORTED_ABIS.map(::JsonPrimitive)))
                })
                put("corpus_verified", corpusVerified); put("corpus_sha256", CORPUS_SHA); put("corpus_bytes", CORPUS_BYTES)
                put("queries", JsonArray(words.map(::JsonPrimitive)))
                put("query_list_sha256", hash((words.joinToString("\n") + "\n").toByteArray()))
                targetApk?.let { put("target_apk", it) }; testApk?.let { put("instrumentation_apk", it) }
                setupNanos?.let { put("setup_copy_and_hash_ns", it) }
                constructorNanos?.let { put("constructor_elapsed_ns", it) }
                constructorStages?.let { put("constructor_stages", it) }
                put("samples", JsonArray(samples.map { sample -> buildJsonObject {
                    put("pass", sample.pass); put("query_index", sample.index); put("query", words[sample.index])
                    put("complete", sample.complete); put("lookup_elapsed_ns", sample.elapsedNanos)
                    put("cleanup_stage_layout", sample.cleanupLayout ?: "unclassified_failed_sample")
                    put("cleanup_stage_layout_basis", if (sample.pass == "first_three")
                        "observed_stage_callbacks" else "inherited_from_matching_first_pass; no cleanup on cache hit")
                    put("stages", sample.stages); put("result", signature(sample.result))
                } }))
                put("scope", "Three-word hotspot diagnosis only; use the unchanged fixed128 protocol for before/after performance comparison")
                put("constructor_scope", "Separate first Reader construction; asset copy/hash warms filesystem pages and header parsing initializes Jsoup")
                put("stage_scope", "Text stages are exclusive children of LOOKUP_SANITIZE. In fused_into_extract, EXTRACT_TEXT includes element filtering, phonetic conversion and newline construction; absent separate stages do not mean those costs disappear. RECORD_DECOMPRESS is a child of LOOKUP_RESOLVE. Never add parents and children")
                put("timing_excludes", "Setup, hashes, stage JSON creation, result consistency checks, cleanup and reporting")
                put("observer_callback_cost_in_outer_lookup", true)
                put("filesystem_cache_flushed", false); put("forced_gc", false)
                put("test_invokes_network", false); put("touches_application_database", false)
                put("no_dictionary_definition_text_in_report", true)
                put("reader_released", closeFailure == null); put("owned_directory_removed", removed)
                put("wall_elapsed_ms", (System.nanoTime() - started) / 1_000_000.0)
                failure?.let { put("failed_at", failedAt); put("failure_class", it.javaClass.name) }
                closeFailure?.let { put("close_failure_class", it.javaClass.name) }
            }
            output.outputStream().use {
                it.write(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report).toByteArray())
                it.fd.sync()
            }
        }
        check(failure == null && closeFailure == null && removed) { "MDX text profile incomplete at $failedAt" }
    }

    private class Recorder : MdxReadObserver, MdxTextObserver {
        private val readCalls = LongArray(MdxReadStage.entries.size)
        private val readNanos = LongArray(MdxReadStage.entries.size)
        val textCalls = LongArray(MdxTextStage.entries.size)
        private val textNanos = LongArray(MdxTextStage.entries.size)
        override fun onStage(stage: MdxReadStage, elapsedNanos: Long) { readCalls[stage.ordinal]++; readNanos[stage.ordinal] += elapsedNanos }
        override fun onStage(stage: MdxTextStage, elapsedNanos: Long) { textCalls[stage.ordinal]++; textNanos[stage.ordinal] += elapsedNanos }
        fun clear() { readCalls.fill(0); readNanos.fill(0); textCalls.fill(0); textNanos.fill(0) }
        fun snapshot() = buildJsonObject {
            put("reader", stages(MdxReadStage.entries.map { it.name }, readCalls, readNanos))
            put("text", stages(MdxTextStage.entries.map { it.name }, textCalls, textNanos))
        }
        private fun stages(names: List<String>, calls: LongArray, nanos: LongArray) = buildJsonObject {
            names.forEachIndexed { index, name -> put(name, buildJsonObject {
                put("calls", calls[index]); put("elapsed_ns", nanos[index])
            }) }
        }
    }
    private data class Sample(val pass: String, val index: Int, val elapsedNanos: Long,
        val stages: JsonObject, val complete: Boolean, val result: List<MdxReader.Entry>,
        var cleanupLayout: String? = null)
    private fun signature(result: List<MdxReader.Entry>): JsonObject {
        val digest = MessageDigest.getInstance("SHA-256")
        fun append(value: String): ByteArray = value.toByteArray(Charsets.UTF_8).also { bytes ->
            digest.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            digest.update(bytes)
        }
        val entries = result.map { entry ->
            val word = append(entry.word); val text = append(entry.text)
            buildJsonObject {
                put("headword_characters", entry.word.length); put("headword_sha256", hash(word))
                put("definition_characters", entry.text.length); put("definition_utf8_bytes", text.size)
                put("definition_sha256", hash(text))
            }
        }
        return buildJsonObject {
            put("match_count", result.size); put("ordered_result_sha256", digest.digest().hex())
            put("entries", JsonArray(entries))
        }
    }
    private fun apk(context: Context) = buildJsonObject {
        val file = File(context.applicationInfo.sourceDir)
        put("package", context.packageName); put("bytes", file.length()); put("sha256", hash(file))
        put("splits", JsonArray(context.applicationInfo.splitSourceDirs.orEmpty().map { path ->
            val split = File(path)
            buildJsonObject { put("name", split.name); put("bytes", split.length()); put("sha256", hash(split)) }
        }))
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        }
        return digest.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private companion object {
        const val TEST_CLASS = "dev.lexicon.data.MdxTextProfileTest"
        const val CORPUS_SHA = "898cf9f1c4bf7a7c52669f3e25a5aa8396092b9b77287ba4b7cdc9a16fc77e73"
        const val CORPUS_BYTES = 15_895_150L
        val words = listOf("book", "world", "robust")
        val invoked = AtomicBoolean(false)
    }
}
