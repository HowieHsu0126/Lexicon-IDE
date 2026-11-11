package dev.lexicon.data

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.data.mdict.MdxReader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
 * Opt-in, offline benchmark. Run one test per fresh `am instrument` invocation with
 * `-e dictionaryLatency 1 -e phase baseline -e baseline <build-label> -e rep 1`.
 *
 * First/second pass refer to calls on a new reader, not a cold filesystem or guaranteed
 * cache hits. Installation has already read the dictionary. No query is used to select
 * the corpus; misses remain in both passes. Timing excludes result hashing/reporting.
 */
@RunWith(AndroidJUnit4::class)
class DictionaryLatencyBenchmarkTest {
    @Test
    fun packagedMdxFirstAndRepeatedQueryLatency() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with -e dictionaryLatency 1", args.getString("dictionaryLatency") == "1")
        check(invoked.compareAndSet(false, true)) { "Run the benchmark in a fresh instrumentation process" }
        fun argument(name: String): String = args.getString(name).orEmpty().also {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) { "Missing or invalid $name argument" }
        }
        val phase = argument("phase")
        val baseline = argument("baseline")
        val repetition = argument("rep")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packagedNames = context.assets.list("dictionaries").orEmpty().filter { it.endsWith(".mdx", true) }.sorted()
        assumeTrue("No personal MDX bundled in this build", packagedNames.isNotEmpty())
        check(queries.size == 128 && queries.map { MdxReader.normalize(it) }.toSet().size == 128)

        val outputDirectory = File(context.filesDir, "validation-1.18.0/dictionary-latency").apply {
            check(isDirectory || mkdirs()) { "Cannot create report directory" }
        }
        val output = File(outputDirectory, "dictionary-$baseline-$phase-rep$repetition.json")
        check(output.createNewFile()) { "Report already exists; use a new phase/baseline/rep tuple" }
        val ownedRoot = File(context.cacheDir, "dictionary-latency-${UUID.randomUUID()}").apply {
            check(mkdirs()) { "Cannot create isolated benchmark directory" }
        }
        val isolated = object : ContextWrapper(context) {
            private fun directory(name: String) = File(ownedRoot, name).apply { check(isDirectory || mkdirs()) }
            override fun getNoBackupFilesDir(): File = directory("no_backup")
            override fun getFilesDir(): File = directory("files")
            override fun getCacheDir(): File = directory("cache")
            override fun getDatabasePath(name: String): File = File(directory("databases"), name)
        }
        val startedAt = Instant.now().toString()
        val started = System.nanoTime()
        val processAgeAtStartMs = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        val observations = mutableListOf<JsonObject>()
        val ownedStores = mutableListOf<DictionaryStore>()
        var ownedReader: MdxReader? = null
        var corpus: JsonObject? = null
        var failedAt = "installation"
        var failure: Throwable? = null
        val cleanupFailures = mutableListOf<String>()
        var removedOwnedDirectory = false
        fun checkDeadline() {
            check((System.nanoTime() - started) / 1_000_000L < 115_000L) {
                "Benchmark exceeded its 115-second cooperative budget"
            }
        }
        try {
            val installer = DictionaryStore(isolated).also(ownedStores::add)
            val installation = timedSuspend { installer.installed() }
            observations += buildJsonObject {
                put("phase", "packaged_installation")
                put("elapsed_ms", installation.ms)
                put("installed_dictionary_count", installation.value.size)
                put("packaged_mdx_asset_count", packagedNames.size)
                put("includes", "asset copy, hashes, MDX index and all-record validation, catalog persistence")
            }
            checkDeadline()
            val selected = installation.value.firstOrNull { it.selected && it.format == "MDX" }
                ?: installation.value.firstOrNull { it.format == "MDX" }
                ?: error("Packaged MDX installation did not yield an MDX dictionary")
            if (!selected.selected) {
                val selection = timedSuspend { installer.select(selected.id) }
                observations += buildJsonObject {
                    put("phase", "setup_selection")
                    put("elapsed_ms", selection.ms)
                }
            }
            val dictionaryFile = File(isolated.noBackupFilesDir, "dictionary/${selected.fileName}")
            check(dictionaryFile.isFile)
            val actualHash = sha256(dictionaryFile)
            check(actualHash == selected.id) { "Installed dictionary identity mismatch" }
            corpus = buildJsonObject {
                put("sha256", actualHash)
                put("file_bytes", dictionaryFile.length())
                put("catalog_name", selected.name)
                put("format", selected.format)
                put("catalog_language", selected.language)
                put("catalog_key_count", selected.entryCount)
                put("name_is_dictionary_metadata_not_verified_edition", true)
            }

            failedAt = "new_reader_open"
            checkDeadline()
            val opening = timed { MdxReader(dictionaryFile) }
            val reader = opening.value.also { ownedReader = it }
            observations += buildJsonObject {
                put("phase", "new_reader_open")
                put("elapsed_ms", opening.ms)
                put("metadata_title", reader.metadata.title)
                put("metadata_version", reader.metadata.version)
                put("metadata_encoding", reader.metadata.encoding)
                put("metadata_language", reader.metadata.language)
                put("metadata_key_count", reader.metadata.entries)
                put("scope", "constructor and complete key index; no lookup beforehand")
            }
            val expected = linkedMapOf<String, Signature>()
            fun readerPass(name: String, sequence: List<String>, compare: Boolean) {
                failedAt = name
                val samples = mutableListOf<JsonObject>()
                val timings = mutableListOf<Double>()
                var misses = 0
                var complete = false
                try {
                    sequence.forEachIndexed { index, query ->
                        checkDeadline()
                        val measured = timed { reader.lookup(query) }
                        val signature = signature(measured.value.map { it.word to it.text })
                        timings += measured.ms
                        if (signature.matches == 0) misses++
                        samples += sample(index, query, measured.ms, signature)
                        if (compare) check(expected[query]?.hash == signature.hash) {
                            "Result signature differs between passes"
                        } else expected[query] = signature
                    }
                    complete = true
                } finally {
                    observations += buildJsonObject {
                        put("phase", name)
                        put("complete", complete)
                        put("requested_query_count", sequence.size)
                        put("completed_query_count", samples.size)
                        put("distinct_query_count", sequence.toSet().size)
                        put("miss_count", misses)
                        put("timing", percentiles(timings))
                        put("samples", JsonArray(samples))
                    }
                }
            }
            readerPass("first_128_queries", queries, compare = false)
            readerPass("second_128_queries_same_order", queries, compare = true)
            readerPass("three_word_repeats_after_second_pass", List(150) { warmWords[it % warmWords.size] }, compare = true)
            ownedReader?.close()
            ownedReader = null

            failedAt = "store_status"
            checkDeadline()
            val store = DictionaryStore(isolated).also(ownedStores::add)
            val status = timedSuspend { store.status() }
            check(status.value.ready && status.value.id == selected.id)
            observations += buildJsonObject {
                put("phase", "store_status")
                put("elapsed_ms", status.ms)
                put("scope", "new store, existing isolated installation; no prior store lookup")
            }
            suspend fun storeLookup(name: String) {
                failedAt = name
                checkDeadline()
                val measured = timedSuspend { store.lookup(storeQuery) }
                val signature = signature(measured.value.map { it.word to it.definition })
                observations += buildJsonObject {
                    put("phase", name)
                    put("sample", sample(0, storeQuery, measured.ms, signature))
                }
                check(expected.getValue(storeQuery).hash == signature.hash) { "Store result differs from direct reader" }
            }
            storeLookup("store_first_lookup")
            failedAt = "store_select_same_id"
            checkDeadline()
            val reselection = timedSuspend { store.select(selected.id) }
            check(reselection.value.id == selected.id)
            observations += buildJsonObject {
                put("phase", "store_select_same_id")
                put("elapsed_ms", reselection.ms)
            }
            storeLookup("store_lookup_after_same_id_selection")
            checkDeadline()
        } catch (error: Throwable) {
            failure = error
        } finally {
            runCatching { ownedReader?.close() }.onFailure { cleanupFailures += it.javaClass.name }
            // Baseline DictionaryStore has no public close. Reflection only releases these
            // owned, idle test instances. No production/global store is inspected or changed.
            ownedStores.forEach { store ->
                runCatching {
                    DictionaryStore::class.java.getDeclaredMethod("closeOpen").apply { isAccessible = true }.invoke(store)
                }.onFailure { cleanupFailures += it.javaClass.name }
            }
            removedOwnedDirectory = ownedRoot.deleteRecursively()
            val report = buildJsonObject {
                put("schema", "lexicon.dictionary.latency.v1")
                put("benchmark_revision", "1.18-fixed128-1")
                put("status", if (failure == null && cleanupFailures.isEmpty() && removedOwnedDirectory) "complete" else "failed")
                put("phase_label", phase)
                put("baseline_label", baseline)
                put("repetition", repetition)
                put("started_at", startedAt)
                put("wall_elapsed_ms_including_setup_hashes_and_cleanup", (System.nanoTime() - started) / 1_000_000.0)
                put("cooperative_budget_ms", 115_000)
                put("pid", Process.myPid())
                put("process_age_at_benchmark_start_ms", processAgeAtStartMs)
                put("device_manufacturer", android.os.Build.MANUFACTURER)
                put("device_model", android.os.Build.MODEL)
                put("sdk_int", android.os.Build.VERSION.SDK_INT)
                put("query_list_sha256", signature(queries.map { it to "" }).hash)
                put("query_count", queries.size)
                put("query_order", "fixed interleave of eight alphabetic bands; unchanged for both 128-query passes")
                put("percentile_method", "nearest rank: sorted[ceil(p*n)-1]")
                put("timing_scope", "lookup invocation only; excludes result hashing, assertions and JSON assembly")
                put("first_pass_scope", "first invocation of each fixed query on a new reader; installation already read the file")
                put("second_pass_scope", "same sequence on the same reader; no guaranteed entry or record cache residency")
                put("repeat_scope", "150 calls alternating hello/world/book after second pass; no separate unreported warmup")
                put("filesystem_cache_flushed", false)
                put("test_invokes_network", false)
                put("touches_application_database", false)
                put("no_dictionary_definition_text_in_report", true)
                put("owned_readers_released", cleanupFailures.isEmpty())
                put("owned_directory_removed", removedOwnedDirectory)
                putJsonArray("cleanup_error_classes") { cleanupFailures.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                corpus?.let { put("dictionary", it) }
                put("observations", JsonArray(observations))
                failure?.let {
                    put("failed_at_phase", failedAt)
                    put("failure_class", it.javaClass.name)
                    // Exception messages/causes may contain corpus content and are omitted.
                }
            }
            output.outputStream().use {
                it.write(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report).toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
        }
        check(failure == null && cleanupFailures.isEmpty() && removedOwnedDirectory) {
            "Dictionary benchmark incomplete at $failedAt; inspect ${output.name}"
        }
    }

    private data class Timed<T>(val value: T, val ms: Double)
    private inline fun <T> timed(block: () -> T): Timed<T> {
        val before = System.nanoTime()
        val value = block()
        return Timed(value, (System.nanoTime() - before) / 1_000_000.0)
    }
    private suspend fun <T> timedSuspend(block: suspend () -> T): Timed<T> {
        val before = System.nanoTime()
        val value = block()
        return Timed(value, (System.nanoTime() - before) / 1_000_000.0)
    }
    private data class Signature(val matches: Int, val characters: Int, val hash: String, val entries: JsonArray)
    private fun signature(entries: List<Pair<String, String>>): Signature {
        val digest = MessageDigest.getInstance("SHA-256")
        fun append(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            digest.update(bytes)
        }
        val records = buildJsonArray {
            entries.forEach { (word, definition) ->
                append(word)
                append(definition)
                add(buildJsonObject {
                    put("headword_characters", word.length)
                    put("headword_sha256", sha256(word.toByteArray(Charsets.UTF_8)))
                    put("definition_characters", definition.length)
                    put("definition_utf8_bytes", definition.toByteArray(Charsets.UTF_8).size)
                    put("definition_sha256", sha256(definition.toByteArray(Charsets.UTF_8)))
                })
            }
        }
        return Signature(entries.size, entries.sumOf { it.second.length }, digest.digest().hex(), records)
    }
    private fun sample(index: Int, query: String, milliseconds: Double, result: Signature): JsonObject = buildJsonObject {
        put("index", index)
        put("query", query)
        put("query_sha256", sha256(query.toByteArray(Charsets.UTF_8)))
        put("query_characters", query.length)
        put("query_word_count", query.split(' ').size)
        put("elapsed_ms", milliseconds)
        put("matches", result.matches)
        put("definition_characters_total", result.characters)
        put("ordered_result_sha256", result.hash)
        put("entries", result.entries)
    }
    private fun percentiles(milliseconds: List<Double>): JsonObject = buildJsonObject {
        put("n", milliseconds.size)
        if (milliseconds.isNotEmpty()) {
            val sorted = milliseconds.sorted()
            fun rank(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceAtLeast(0)]
            put("p50_ms", rank(.50))
            put("p95_ms", rank(.95))
            put("max_ms", sorted.last())
        }
    }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().hex()
    }
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        val invoked = AtomicBoolean(false)
        val warmWords = listOf("hello", "world", "book")
        const val storeQuery = "hello"
        // Freeze inputs, including phrases that may miss. Interleaving avoids traversing
        // only adjacent dictionary blocks. Each band has sixteen distinct English queries.
        val bands = listOf(
            listOf("a", "about", "account", "achieve", "acknowledge", "across", "administration", "advocate", "analysis", "approach", "available", "benefit", "bias", "book", "by contrast", "by no means"),
            listOf("call", "capacity", "change", "climate", "come", "committee", "comprehensive", "consequence", "constitute", "contemporary", "context", "contribute", "data", "democratic", "development", "draw a distinction"),
            listOf("economic", "economy", "effect", "efficient", "empirical", "environment", "establish", "evidence", "evolution", "face", "factor", "fall", "figure", "for instance", "framework", "fundamental"),
            listOf("gain", "game", "general", "get", "give", "globalization", "go", "good", "government", "grow", "hand", "have", "hello", "hold", "how", "hypothesis"),
            listOf("idea", "identify", "implication", "in accordance with", "in terms of", "independent", "infrastructure", "insight", "institution", "interpretation", "investment", "issue", "knowledge", "language", "light", "long"),
            listOf("make", "maintain", "mean", "measure", "model", "national", "nature", "nevertheless", "on behalf of", "outcome", "policy", "population", "power", "principle", "put", "proportion"),
            listOf("qualitative", "question", "rather than", "read", "relationship", "research", "resilience", "run", "significant", "society", "sustainable", "take", "take into account", "theory", "think", "trade-off"),
            listOf("under", "understand", "unemployment", "universal", "up", "urban", "value", "variable", "view", "vulnerability", "water", "way", "while", "world", "yield", "zero"),
        )
        val queries = (0 until 16).flatMap { index -> bands.map { it[index] } }
    }
}
