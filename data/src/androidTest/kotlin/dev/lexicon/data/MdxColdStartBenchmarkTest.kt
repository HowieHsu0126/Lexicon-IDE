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
import kotlin.math.ceil

/**
 * Reader-only profile, one test in each fresh instrumentation process. Setup copies and
 * hashes the asset; it never installs, validates, normalizes or opens it with MdxReader.
 * Filesystem pages are consequently warm. Constructor class initialization is included
 * in the outer timer. This is not a device-boot, filesystem-cold or Store benchmark.
 *
 * Opt in with dictionaryColdStart=1, phase=before|after, rep=1, and the SHA-256 of the
 * host's frozen source manifest in sourceManifestSha256. Use a new phase/rep for retries.
 * The manifest binds exact source files and built APKs; device APK hashes are also saved.
 */
@RunWith(AndroidJUnit4::class)
class MdxColdStartBenchmarkTest {
    @Test
    fun fixedDictionaryFreshProcessConstructorAndQueries() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with -e dictionaryColdStart 1", args.getString("dictionaryColdStart") == "1")
        check(invoked.compareAndSet(false, true)) { "Use a fresh instrumentation process" }
        check(args.getString("class") in setOf(TEST_CLASS, "$TEST_CLASS#$TEST_METHOD")) {
            "Run only this benchmark in one instrumentation invocation"
        }
        fun label(name: String) = args.getString(name).orEmpty().also {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) { "Missing or invalid $name" }
        }
        val phase = label("phase")
        val repetition = label("rep")
        val sourceManifest = args.getString("sourceManifestSha256").orEmpty().also {
            require(it.matches(Regex("[a-f0-9]{64}"))) { "Missing or invalid sourceManifestSha256" }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "validation-1.20.0/mdx-cold-start/$phase").apply {
            check(isDirectory || mkdirs()) { "Cannot create benchmark report directory" }
        }
        val reportFile = File(directory, "rep-$repetition.json")
        check(reportFile.createNewFile()) { "Existing evidence must not be overwritten; use a new phase/rep" }
        val owned = File(context.cacheDir, "mdx-cold-start-${UUID.randomUUID()}")
        val startedAt = Instant.now().toString()
        val started = System.nanoTime()
        val processStarted = Process.getStartElapsedRealtime()
        val processAge = SystemClock.elapsedRealtime() - processStarted
        val setup = mutableListOf<JsonObject>()
        val measurements = mutableListOf<QueryMeasurement>()
        val recorder = StageRecorder()
        var reader: MdxReader? = null
        var metadata: MdxReader.Metadata? = null
        var openingNanos: Long? = null
        var openingStages: StageSnapshot? = null
        var dictionaryIdentity: JsonObject? = null
        var structureIdentity: JsonObject? = null
        var targetApkIdentity: JsonObject? = null
        var instrumentationApkIdentity: JsonObject? = null
        var failedAt = "setup"
        var failure: Throwable? = null
        var closeFailure: Throwable? = null
        var cleaned = false
        fun checkBudget() = check(System.nanoTime() - started < 180_000_000_000L) {
            "Benchmark exceeded its cooperative 180-second budget"
        }
        fun setupStep(name: String, block: () -> Unit) {
            val before = System.nanoTime()
            try { block() } finally {
                setup += buildJsonObject { put("phase", name); put("elapsed_ns", System.nanoTime() - before) }
            }
        }
        try {
            check(owned.mkdir()) { "Cannot create owned benchmark directory" }
            // These checks deliberately do not call MdxReader.normalize().
            check(queries.size == 128 && queries.toSet().size == 128)
            check(queries.all { it.matches(Regex("[a-z]+(?:[ -][a-z]+)*")) })
            check(queryHash() == QUERY_SHA256) { "The fixed query order has changed" }
            val dictionary = File(owned, "corpus.mdx")
            setupStep("copy_and_hash_asset") {
                val rows = context.assets.open("dictionaries/catalog.tsv").bufferedReader().use { it.readLines() }
                val asset = rows.map { it.split('\t', limit = 2) }
                    .single { it.size == 2 && it[0] == DICTIONARY_SHA256 }[1]
                require(asset.isNotBlank() && asset.none { it in "/\\\r\n\t" })
                context.assets.open("dictionaries/$asset").use { input ->
                    dictionary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            check(copied <= DICTIONARY_BYTES) { "Unexpected corpus length" }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                check(dictionary.length() == DICTIONARY_BYTES)
                val actual = sha256(dictionary)
                check(actual == DICTIONARY_SHA256) { "Unexpected corpus identity" }
                dictionaryIdentity = buildJsonObject {
                    put("sha256", actual); put("bytes", dictionary.length())
                    put("edition_independently_verified", false)
                }
            }
            setupStep("hash_actual_installed_apks") {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                targetApkIdentity = apkIdentity(instrumentation.targetContext)
                instrumentationApkIdentity = apkIdentity(instrumentation.context)
            }
            failedAt = "constructor"
            checkBudget()
            recorder.clear()
            val openingStarted = System.nanoTime()
            try { reader = MdxReader(dictionary, recorder) } finally {
                openingNanos = System.nanoTime() - openingStarted
                openingStages = recorder.snapshot()
            }
            val opened = checkNotNull(reader)
            metadata = opened.metadata
            check(opened.metadata.entries == 37_375L) { "Unexpected corpus key count" }
            val stages = checkNotNull(openingStages)
            check(constructorStages.all { stages.calls[it.ordinal] == 1L }) { "Constructor stage coverage differs" }
            check(lookupStages.all { stages.calls[it.ordinal] == 0L }) { "Lookup occurred before query phase" }
            listOf("first_128", "second_128_same_order").forEach { pass ->
                queries.forEachIndexed { index, query ->
                    failedAt = "$pass:$index"
                    checkBudget()
                    recorder.clear()
                    var result: List<MdxReader.Entry> = emptyList()
                    var succeeded = false
                    val queryStarted = System.nanoTime()
                    try { result = opened.lookup(query); succeeded = true } finally {
                        val elapsed = System.nanoTime() - queryStarted
                        measurements += QueryMeasurement(pass, index, elapsed, recorder.snapshot(), succeeded, result)
                    }
                }
            }
            // Hashing and full result equality checks run only after all measured queries.
            failedAt = "result_consistency"
            val first = measurements.filter { it.pass == "first_128" }
            val second = measurements.filter { it.pass == "second_128_same_order" }
            check(first.size == 128 && second.size == 128)
            first.zip(second).forEach { (a, b) ->
                check(a.result == b.result) { "First and second pass results differ" }
                check(a.stages.calls[MdxReadStage.LOOKUP_INDEX.ordinal] == 1L)
                check(b.stages.calls[MdxReadStage.LOOKUP_INDEX.ordinal] == 1L)
            }
            checkBudget()
            failedAt = "whole_index_fidelity"
            structureIdentity = structureSignature(opened)
            checkBudget()
        } catch (error: Throwable) {
            failure = error
        } finally {
            runCatching { reader?.close() }.onFailure { closeFailure = it }
            cleaned = !owned.exists() || owned.deleteRecursively()
            val complete = failure == null && closeFailure == null && cleaned
            val resultSamples = measurements.map { measured ->
                buildJsonObject {
                    put("pass", measured.pass); put("query_index", measured.queryIndex)
                    put("query", queries[measured.queryIndex]); put("complete", measured.succeeded)
                    put("elapsed_ns", measured.elapsedNanos)
                    put("stages", measured.stages.json())
                    put("result", resultSignature(measured.result))
                }
            }
            val report = buildJsonObject {
                put("schema", "lexicon.mdx.cold-start.v1")
                put("protocol", PROTOCOL)
                put("status", if (complete) "complete" else "failed")
                put("phase", phase); put("repetition", repetition)
                put("source_manifest_sha256", sourceManifest)
                put("started_at", startedAt)
                put("pid", Process.myPid()); put("process_start_elapsed_ms", processStarted)
                put("process_age_at_test_start_ms", processAge)
                put("wall_elapsed_ms_including_setup_hashes_and_cleanup", (System.nanoTime() - started) / 1_000_000.0)
                put("device", buildJsonObject {
                    put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL)
                    put("hardware", Build.HARDWARE); put("fingerprint", Build.FINGERPRINT)
                    put("sdk", Build.VERSION.SDK_INT)
                    put("supported_abis", JsonArray(Build.SUPPORTED_ABIS.map(::JsonPrimitive)))
                    put("process_is_64_bit", Process.is64Bit())
                })
                dictionaryIdentity?.let { put("dictionary", it) }
                structureIdentity?.let { put("whole_index", it) }
                targetApkIdentity?.let { put("target_apk", it) }
                instrumentationApkIdentity?.let { put("instrumentation_apk", it) }
                metadata?.let { value -> put("metadata", buildJsonObject {
                    put("key_count", value.entries); put("version", value.version)
                    put("encoding", value.encoding); put("language", value.language)
                    put("title_sha256", sha256(value.title.toByteArray(Charsets.UTF_8)))
                }) }
                put("query_list_sha256", QUERY_SHA256)
                put("query_hash_encoding", "UTF-8, one query per line, final LF included")
                put("query_order", JsonArray(queries.map(::JsonPrimitive)))
                put("query_count_per_pass", 128)
                put("retains_results_until_measurement_ends", true)
                put("setup", JsonArray(setup))
                openingNanos?.let { put("constructor_elapsed_ns", it) }
                openingStages?.let { value ->
                    put("constructor_stages", value.json())
                    openingNanos?.let { elapsed ->
                        put("constructor_unattributed_ns", elapsed - constructorStages.sumOf { value.nanos[it.ordinal] })
                    }
                }
                put("query_samples", JsonArray(resultSamples))
                put("query_pass_summaries", JsonArray(listOf("first_128", "second_128_same_order").map { pass ->
                    val selected = measurements.filter { it.pass == pass && it.succeeded }
                    buildJsonObject {
                        put("pass", pass); put("complete_query_count", selected.size)
                        put("miss_count", selected.count { it.result.isEmpty() })
                        put("timing", percentiles(selected.map { it.elapsedNanos }))
                    }
                }))
                put("timing_scope", "Constructor/lookup invocation and observer callbacks; excludes setup, hashes, result checks and JSON")
                put("constructor_scope", "First MdxReader use by this sole test; filesystem warmed by asset copy/hash")
                put("stage_scope", "Observer durations exclude callback work; RECORD_DECOMPRESS is an inclusive child of LOOKUP_RESOLVE and must not be added to it")
                put("first_pass_scope", "128 distinct fixed queries on the same new reader; shared record blocks may already be cached")
                put("second_pass_scope", "Same reader and order; entry LRU96 and record LRU4 do not guarantee cache residency")
                put("percentile_scope", "Nearest-rank percentiles of the fixed word distribution, not cold-open population estimates")
                put("filesystem_cache_flushed", false); put("forced_gc", false)
                put("test_invokes_network", false); put("touches_application_database", false)
                put("no_dictionary_definition_text_in_report", true)
                put("reader_released", closeFailure == null); put("owned_directory_removed", cleaned)
                failure?.let { put("failed_at", failedAt); put("failure_class", it.javaClass.name) }
                closeFailure?.let { put("close_failure_class", it.javaClass.name) }
            }
            reportFile.outputStream().use {
                it.write(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report).toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
        }
        check(failure == null && closeFailure == null && cleaned) {
            "MDX benchmark incomplete at $failedAt; inspect ${reportFile.name}"
        }
    }

    private class StageRecorder : MdxReadObserver {
        private val calls = LongArray(MdxReadStage.entries.size)
        private val nanos = LongArray(MdxReadStage.entries.size)
        override fun onStage(stage: MdxReadStage, elapsedNanos: Long) {
            calls[stage.ordinal]++
            nanos[stage.ordinal] += elapsedNanos
        }
        fun clear() { calls.fill(0); nanos.fill(0) }
        fun snapshot() = StageSnapshot(calls.copyOf(), nanos.copyOf())
    }
    private data class StageSnapshot(val calls: LongArray, val nanos: LongArray) {
        fun json() = buildJsonObject {
            MdxReadStage.entries.forEach { stage -> put(stage.name, buildJsonObject {
                put("calls", calls[stage.ordinal]); put("elapsed_ns", nanos[stage.ordinal])
            }) }
        }
    }
    private data class QueryMeasurement(
        val pass: String, val queryIndex: Int, val elapsedNanos: Long,
        val stages: StageSnapshot, val succeeded: Boolean, val result: List<MdxReader.Entry>,
    )
    private fun resultSignature(result: List<MdxReader.Entry>): JsonObject {
        val digest = MessageDigest.getInstance("SHA-256")
        fun append(value: String): ByteArray = value.toByteArray(Charsets.UTF_8).also { bytes ->
            digest.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            digest.update(bytes)
        }
        val entries = result.map { value ->
            val word = append(value.word)
            val definition = append(value.text)
            buildJsonObject {
                put("headword_characters", value.word.length); put("headword_sha256", sha256(word))
                put("definition_characters", value.text.length); put("definition_utf8_bytes", definition.size)
                put("definition_sha256", sha256(definition))
            }
        }
        return buildJsonObject {
            put("match_count", result.size); put("definition_characters", result.sumOf { it.text.length })
            put("ordered_result_sha256", digest.digest().hex()); put("entries", JsonArray(entries))
        }
    }
    /** Read only this owned reader, after every timed query; no key or definition is emitted. */
    @Suppress("UNCHECKED_CAST")
    private fun structureSignature(reader: MdxReader): JsonObject {
        fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val keys = field(reader, "keys").get(reader) as List<Any>
        val ends = field(reader, "keyEndOffsets").get(reader) as LongArray
        val index = field(reader, "index").get(reader) as java.util.TreeMap<String, List<Int>>
        val records = field(reader, "records").get(reader) as List<Any>
        check(keys.isNotEmpty() && records.isNotEmpty() && ends.size == keys.size)
        val word = field(keys.first(), "word")
        val offset = field(keys.first(), "offset")
        val keyHash = CanonicalHash().text("keys-v1").long(keys.size.toLong())
        var previousOffset = Long.MIN_VALUE
        var nondecreasing = true
        keys.forEach { key ->
            val at = offset.getLong(key)
            keyHash.text(word.get(key) as String).long(at)
            if (at < previousOffset) nondecreasing = false
            previousOffset = at
        }
        val endHash = CanonicalHash().text("end-offsets-v1").long(ends.size.toLong())
        ends.forEach { endHash.long(it) }
        val indexHash = CanonicalHash().text("normalized-index-v1").long(index.size.toLong())
        var indexReferences = 0L
        index.forEach { (normalized, positions) ->
            indexHash.text(normalized).long(positions.size.toLong())
            positions.forEach { indexHash.long(it.toLong()) }
            indexReferences += positions.size
        }
        check(indexReferences == keys.size.toLong()) { "Normalized index reference count differs" }
        val recordFields = listOf("fileOffset", "compressedSize", "decompressedSize", "recordOffset")
            .map { field(records.first(), it) }
        val recordHash = CanonicalHash().text("record-blocks-v1").long(records.size.toLong())
        records.forEach { record -> recordFields.forEach { member -> recordHash.long((member.get(record) as Number).toLong()) } }
        val hashes = linkedMapOf("keys_sha256" to keyHash.finish(), "end_offsets_sha256" to endHash.finish(),
            "normalized_index_sha256" to indexHash.finish(), "record_blocks_sha256" to recordHash.finish())
        val full = CanonicalHash().text("mdx-whole-index-v1")
        hashes.forEach { (name, hash) -> full.text(name).text(hash) }
        return buildJsonObject {
            put("schema", "mdx-whole-index-v1")
            put("canonical_encoding", "UTF-8 strings with 4-byte big-endian byte length; 8-byte big-endian integer counts/values; ordered component digest labels and hex strings")
            put("full_structure_sha256", full.finish())
            hashes.forEach { (name, hash) -> put(name, hash) }
            put("key_count", keys.size); put("unique_normalized_keys", index.size)
            put("index_reference_count", indexReferences); put("record_block_count", records.size)
            put("key_offsets_nondecreasing", nondecreasing)
            put("observed_after_all_timing", true)
        }
    }
    private class CanonicalHash {
        private val digest = MessageDigest.getInstance("SHA-256")
        fun text(value: String) = apply {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            digest.update(bytes)
        }
        fun long(value: Long) = apply { for (shift in 56 downTo 0 step 8) digest.update((value ushr shift).toByte()) }
        fun finish() = digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun percentiles(nanos: List<Long>) = buildJsonObject {
        put("n", nanos.size)
        if (nanos.isNotEmpty()) {
            val sorted = nanos.sorted()
            fun rank(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceAtLeast(0)] / 1_000_000.0
            put("p50_ms", rank(.5)); put("p95_ms", rank(.95)); put("max_ms", sorted.last() / 1_000_000.0)
        }
    }
    private fun apkIdentity(context: Context) = buildJsonObject {
        val apk = File(context.applicationInfo.sourceDir)
        put("package", context.packageName); put("bytes", apk.length()); put("sha256", sha256(apk))
        put("splits", JsonArray(context.applicationInfo.splitSourceDirs.orEmpty().map { path ->
            val file = File(path)
            buildJsonObject { put("name", file.name); put("bytes", file.length()); put("sha256", sha256(file)) }
        }))
    }
    private fun queryHash() = sha256((queries.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        }
        return digest.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val TEST_CLASS = "dev.lexicon.data.MdxColdStartBenchmarkTest"
        const val TEST_METHOD = "fixedDictionaryFreshProcessConstructorAndQueries"
        const val PROTOCOL = "1.20-reader-fresh-process-fixed128-v1"
        const val DICTIONARY_SHA256 = "898cf9f1c4bf7a7c52669f3e25a5aa8396092b9b77287ba4b7cdc9a16fc77e73"
        const val DICTIONARY_BYTES = 15_895_150L
        const val QUERY_SHA256 = "c45a27b3cd58eee102151017b9e4c2d810757ddfe3dbc776c3ae2d24cee8156c"
        val invoked = AtomicBoolean(false)
        val constructorStages = listOf(MdxReadStage.HEADER, MdxReadStage.KEY_METADATA, MdxReadStage.KEY_DECODE,
            MdxReadStage.RECORD_METADATA, MdxReadStage.END_OFFSETS, MdxReadStage.NORMALIZED_INDEX)
        val lookupStages = listOf(MdxReadStage.LOOKUP_INDEX, MdxReadStage.LOOKUP_RESOLVE,
            MdxReadStage.LOOKUP_SANITIZE, MdxReadStage.RECORD_DECOMPRESS)
        // Book/world come first. The other 126 retain the frozen 1.18 interleaved order.
        val queries = listOf(
            "book", "world", "a", "call", "economic", "gain", "idea", "make",
            "qualitative", "under", "about", "capacity", "economy", "game", "identify", "maintain",
            "question", "understand", "account", "change", "effect", "general", "implication", "mean",
            "rather than", "unemployment", "achieve", "climate", "efficient", "get", "in accordance with", "measure",
            "read", "universal", "acknowledge", "come", "empirical", "give", "in terms of", "model",
            "relationship", "up", "across", "committee", "environment", "globalization", "independent", "national",
            "research", "urban", "administration", "comprehensive", "establish", "go", "infrastructure", "nature",
            "resilience", "value", "advocate", "consequence", "evidence", "good", "insight", "nevertheless",
            "run", "variable", "analysis", "constitute", "evolution", "government", "institution", "on behalf of",
            "significant", "view", "approach", "contemporary", "face", "grow", "interpretation", "outcome",
            "society", "vulnerability", "available", "context", "factor", "hand", "investment", "policy",
            "sustainable", "water", "benefit", "contribute", "fall", "have", "issue", "population",
            "take", "way", "bias", "data", "figure", "hello", "knowledge", "power",
            "take into account", "while", "democratic", "for instance", "hold", "language", "principle", "theory",
            "by contrast", "development", "framework", "how", "light", "put", "think", "yield",
            "by no means", "draw a distinction", "fundamental", "hypothesis", "long", "proportion", "trade-off", "zero",
        )
    }
}
