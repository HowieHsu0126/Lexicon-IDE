package dev.lexicon.data

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.data.mdict.MdxReader
import dev.lexicon.data.mdict.PreparedDictionaryFormat
import dev.lexicon.data.mdict.PreparedMdxIndex
import dev.lexicon.domain.DictionaryEntry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/** Production Store experiment, isolated entirely inside the instrumentation application's filesDir. */
@RunWith(AndroidJUnit4::class)
class PreparedDictionaryStoreBenchmarkTest {
    @Test fun productionStoreDictionaryInOneFreshProcess(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with dictionaryStoreBenchmark=1", args.getString("dictionaryStoreBenchmark") == "1")
        check(invoked.compareAndSet(false, true)) { "One phase requires a fresh instrumentation process" }
        check(args.getString("class") in setOf(TEST_CLASS, "$TEST_CLASS#$TEST_METHOD"))
        fun label(key: String, optional: Boolean = false): String? = args.getString(key)?.also {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) { "Invalid $key" }
        } ?: if (optional) null else error("Missing $key")
        fun fingerprint(key: String) = args.getString(key).orEmpty().also {
            require(it.matches(Regex("[a-f0-9]{64}"))) { "Missing or invalid $key" }
        }
        val phase = checkNotNull(label("phase")).also { require(it in setOf("setup", "verify", "raw", "prepared")) }
        val run = checkNotNull(label("run")); val setupRun = label("setupRun", optional = true)
        require((phase != "setup") == (setupRun != null))
        val expectedUser = args.getString("expectedUserId")?.toIntOrNull() ?: error("Missing expectedUserId")
        require(expectedUser >= 0 && Process.myUid() / 100_000 == expectedUser) { "Wrong Android user" }
        val fingerprints = linkedMapOf(
            "source_manifest_sha256" to fingerprint("sourceManifestSha256"),
            "observer_sha256" to fingerprint("observerSha256"),
            "contract_sha256" to fingerprint("contractSha256"),
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val owner = instrumentation.context
        val runs = File(owner.filesDir, "validation-mdx-store-production/runs")
        check(runs.isDirectory || runs.mkdirs()); check(!Files.isSymbolicLink(runs.toPath()))
        val directory = File(runs, run)
        check(!directory.exists() && directory.mkdir()) { "Preserve earlier run output" }
        check(directory.canonicalFile.parentFile == runs.canonicalFile)
        Session(owner, instrumentation.targetContext, runs, directory, phase, run, setupRun, fingerprints).execute()
    }

    private inner class Session(
        val owner: Context, val assets: Context, val runs: File, val directory: File,
        val phase: String, val run: String, val setupRun: String?, val fingerprints: Map<String, String>,
    ) {
        val startAt = Instant.now().toString(); val started = System.nanoTime()
        val processStart = Process.getStartElapsedRealtime()
        val facts = linkedMapOf<String, JsonElement>(); val setup = mutableListOf<JsonObject>()
        val samples = mutableListOf<QueryMeasurement>(); val stores = mutableListOf<DictionaryStore>()
        val immutable = linkedMapOf<File, String>(); val closeFailures = mutableListOf<String>()
        var targetApk: JsonObject? = null; var testApk: JsonObject? = null
        var failedAt = "initialization"; var status = "failed"
        fun record(key: String, value: String) { facts[key] = JsonPrimitive(value) }
        fun record(key: String, value: Number) { facts[key] = JsonPrimitive(value) }
        fun record(key: String, value: Boolean) { facts[key] = JsonPrimitive(value) }
        fun record(key: String, value: JsonElement) { facts[key] = value }
        fun checkBudget() { if (System.nanoTime() - started >= BUDGET_NANOS) throw BudgetExhausted() }
        fun <T> setupStep(name: String, action: () -> T): T {
            val before = System.nanoTime()
            try { return action() } finally { setup += buildJsonObject { put("phase", name); put("elapsed_ns", System.nanoTime() - before) } }
        }
        suspend fun <T> setupSuspend(name: String, action: suspend () -> T): T {
            val before = System.nanoTime()
            try { return action() } finally { setup += buildJsonObject { put("phase", name); put("elapsed_ns", System.nanoTime() - before) } }
        }
        suspend fun execute() {
            val reportFile = File(directory, "report.json")
            check(!reportFile.exists())
            try {
                check(queries.size == 128 && queries.toSet().size == 128 && queryHash() == QUERY_SHA256)
                setupStep("hash_installed_apks") { targetApk = apkIdentity(assets); testApk = apkIdentity(owner) }
                when (phase) {
                    "setup" -> prepareInstallations()
                    "verify" -> verifyAllKeys()
                    else -> measureStore()
                }
                failedAt = "preserve_inputs"
                setupStep("rehash_inputs_after_work") {
                    immutable.forEach { (file, expected) -> check(sha256(file) == expected) { "Owned input changed" } }
                }
                record("immutable_inputs_unchanged", true); checkBudget(); status = "complete"
            } catch (_: BudgetExhausted) {
                status = "incomplete_budget"; record("budget_exhausted", true)
            } catch (error: Throwable) {
                record("failure_class", error.javaClass.name); status = "failed"
            } finally {
                // Reflection only reads handles belonging to these isolated Stores. Closing their
                // public handles does not write Store state or affect the application's Store.
                stores.forEach { store ->
                    for (field in listOf("reader", "prepared", "connection")) {
                        val handle = runCatching { readField(store, field) }.getOrElse {
                            closeFailures += it.javaClass.name; null
                        }
                        if (handle is AutoCloseable) try { handle.close() } catch (error: Throwable) { closeFailures += error.javaClass.name }
                    }
                }
                if (closeFailures.isNotEmpty()) status = "failed"
                val resultHashStart = System.nanoTime()
                val sampleJson = samples.map { measured -> buildJsonObject {
                    put("pass", measured.pass); put("query_index", measured.index); put("query", queries[measured.index])
                    put("complete", measured.complete); put("elapsed_ns", measured.nanos); put("result", resultSignature(measured.result))
                } }
                record("result_hashing_elapsed_ns", System.nanoTime() - resultHashStart)
                val first = samples.firstOrNull { it.pass == PASSES[0] && it.index == 0 && it.complete }
                val construction = (facts["store_construction_elapsed_ns"] as? JsonPrimitive)?.content?.toLongOrNull()
                if (first != null) {
                    record("first_store_lookup_elapsed_ns", first.nanos)
                    if (construction != null) record("construction_plus_first_lookup_elapsed_ns", construction + first.nanos)
                }
                val report = buildJsonObject {
                    put("schema", "lexicon.mdx.production-store-benchmark.v1"); put("protocol", PROTOCOL)
                    put("status", status); put("phase", phase); put("run", run); setupRun?.let { put("setup_run", it) }
                    put("started_at", startAt); put("finished_at", Instant.now().toString())
                    put("pid", Process.myPid()); put("android_user_id", Process.myUid() / 100_000)
                    put("process_start_elapsed_ms", processStart); put("process_age_now_ms", SystemClock.elapsedRealtime() - processStart)
                    put("wall_elapsed_ms_before_report_write", (System.nanoTime() - started) / 1_000_000.0)
                    put("parser_version", PreparedDictionaryFormat.PARSER_VERSION)
                    fingerprints.forEach { (key, value) -> put(key, value) }
                    put("source_fingerprints_runner_supplied", true)
                    put("dictionary_sha256", DICTIONARY_SHA256); put("dictionary_bytes", DICTIONARY_BYTES)
                    put("expected_original_keys", EXPECTED_KEYS); put("dictionary_edition_verified", false)
                    targetApk?.let { put("target_apk", it) }; testApk?.let { put("instrumentation_apk", it) }
                    put("device", buildJsonObject { put("model", Build.MODEL); put("fingerprint", Build.FINGERPRINT); put("sdk", Build.VERSION.SDK_INT) })
                    put("query_list_sha256", QUERY_SHA256); put("query_order", JsonArray(queries.map(::JsonPrimitive)))
                    put("query_samples", JsonArray(sampleJson))
                    put("query_pass_summaries", JsonArray(PASSES.map { pass ->
                        val selected = samples.filter { it.pass == pass && it.complete }
                        buildJsonObject { put("pass", pass); put("complete_query_count", selected.size); put("miss_count", selected.count { it.result.isEmpty() }); put("timing", percentiles(selected.map { it.nanos })) }
                    }))
                    put("setup", JsonArray(setup)); put("cooperative_budget_seconds", BUDGET_NANOS / 1_000_000_000L)
                    put("lookup_timing_scope", "Actual suspend DictionaryStore.lookup including dispatch, first initialization, source verification when prepared, backend opening and query; excludes observer conversion, signatures and reflection")
                    put("prelookup_scope", "Installed directory copied and fingerprinted, then a fresh Store is constructed without status/installed/lookup; no preparation scope")
                    put("filesystem_cache_flushed", false); put("forced_gc", false)
                    put("prepared_scope", "First lookup includes production MDX source fingerprint and openBound full artifact fingerprint; query latency alone is not total App startup")
                    put("verification_scope", "All original key words from production forEachPreparedEntry; raw lookup and Store full DictionaryEntry lists compared; arbitrary prefix/Unicode/limit cases remain separate synthetic tests")
                    put("no_application_user_data_access", true); put("all_writes_in_instrumentation_owned_directory", true)
                    put("network_requests", 0); put("reads_api_key", false); put("definition_text_in_report", false)
                    put("reflection_scope", "Read-only inspection of owned Store backend fields, then public close on owned handles; no reflected writes or parser access")
                    put("close_failure_classes", JsonArray(closeFailures.map(::JsonPrimitive)))
                    facts.forEach { (key, value) -> put(key, value) }
                    if (status != "complete") put("failed_at", failedAt)
                }
                val bytes = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report).toByteArray(Charsets.UTF_8)
                check(bytes.size <= MAX_REPORT_BYTES); writeNew(reportFile, bytes)
            }
            check(status == "complete") { "Production Store observer did not complete; inspect owned report" }
        }

        suspend fun prepareInstallations() {
            val work = newWork(); val corpus = setupStep("copy_verify_packaged_mdx") { copyCorpus(work) }
            val rawRoot = File(work, "raw-store").apply { check(mkdir()) }
            val preparedRoot = File(work, "prepared-store").apply { check(mkdir()) }
            val raw = newStore(rawRoot); val prepared = newStore(preparedRoot)
            for ((name, store) in listOf("raw" to raw, "prepared" to prepared)) {
                failedAt = "install_$name"
                val installed = setupSuspend("production_store_install_$name") { store.import(corpus) }
                check(installed.id == DICTIONARY_SHA256 && installed.entryCount == EXPECTED_KEYS.toLong())
                val list = store.installed(); check(list.size == 1 && list.single().selected)
                checkBackend(store, "unopened")
            }
            val rawBefore = inventory(rawRoot); val preparedBefore = inventory(preparedRoot)
            check(rawBefore == preparedBefore) { "Installed baselines differ" }
            check(rawBefore.keys == BASE_FILES)
            failedAt = "prepare_production_cache"
            val cache = PreparedDictionaryCache(File(preparedRoot, "no_backup/dictionary/prepared"), preparationScope = null)
            val installedSource = File(preparedRoot, SOURCE_PATH)
            val prepareStart = System.nanoTime()
            try { cache.prepare(installedSource, DICTIONARY_SHA256, ::checkBudget) }
            finally { record("production_cache_prepare_elapsed_ns", System.nanoTime() - prepareStart) }
            val receiptFile = File(preparedRoot, receiptPath())
            val receipt = readJson(receiptFile)
            check(receipt.string("sourceSha256") == DICTIONARY_SHA256 && receipt.string("parserVersion") == PreparedDictionaryFormat.PARSER_VERSION)
            check(receipt.long("rowCount") == EXPECTED_KEYS.toLong())
            val artifactName = receipt.string("artifactFile").also { check(it.matches(ARTIFACT_NAME)) }
            val artifactPath = receiptPath().substringBeforeLast('/') + "/" + artifactName
            val artifact = File(preparedRoot, artifactPath)
            check(sha256(artifact) == receipt.string("artifactSha256"))
            val rawAfter = inventory(rawRoot); val preparedAfter = inventory(preparedRoot)
            check(rawBefore == rawAfter)
            check(preparedAfter.keys == BASE_FILES + setOf(receiptPath(), artifactPath)) { "Unexpected prepared files" }
            BASE_FILES.forEach { check(rawAfter[it] == preparedAfter[it]) { "Preparation changed source or installation metadata" } }
            record("raw_installation", inventoryJson(rawAfter)); record("prepared_installation", inventoryJson(preparedAfter))
            record("raw_installation_bytes", rawAfter.values.sumOf { it.bytes })
            record("prepared_installation_bytes", preparedAfter.values.sumOf { it.bytes })
            record("derived_artifact_bytes", artifact.length()); record("derived_artifact_sha256", sha256(artifact))
            record("prepared_receipt_sha256", sha256(receiptFile)); record("prepared_artifact_path", artifactPath)
            record("setup_complete", true); record("corpus_lookup_fidelity_verified", false)
            (rawAfter.keys.map { File(rawRoot, it) to rawAfter.getValue(it).sha } +
                preparedAfter.keys.map { File(preparedRoot, it) to preparedAfter.getValue(it).sha }).forEach { immutable[it.first] = it.second }
        }

        suspend fun measureStore() {
            val root = setupStep("copy_and_verify_installed_store") { cloneSetup(phase) }
            val before = System.nanoTime(); val store = newStore(root)
            record("store_construction_elapsed_ns", System.nanoTime() - before)
            checkBackend(store, "unopened"); record("no_backend_open_before_first_lookup", true)
            for (pass in PASSES) for ((index, query) in queries.withIndex()) {
                failedAt = "$pass:$index"; checkBudget()
                var result: List<DictionaryEntry> = emptyList(); var complete = false
                val lookupStart = System.nanoTime()
                try { result = store.lookup(query); complete = true }
                finally {
                    val nanos = System.nanoTime() - lookupStart
                    samples += QueryMeasurement(pass, index, nanos, complete, asMdx(result))
                }
                if (pass == PASSES[0] && index == 0) { checkBackend(store, phase); record("backend_after_first_lookup", phase) }
            }
            checkBackend(store, phase); record("backend_after_last_lookup", phase)
            val first = samples.filter { it.pass == PASSES[0] }; val second = samples.filter { it.pass == PASSES[1] }
            check(first.size == 128 && second.size == 128)
            first.zip(second).forEach { (a, b) -> check(a.complete && b.complete && a.result == b.result) }
            record("same_order_results_equal", true); record("cross_backend_fidelity_requires_verify_phase", true)
        }

        suspend fun verifyAllKeys() {
            val root = setupStep("copy_and_verify_installed_store") { cloneSetup("prepared") }
            val store = newStore(root); checkBackend(store, "unopened")
            val source = File(root, SOURCE_PATH)
            MdxReader(source).use { raw ->
                val words = ArrayList<String>(EXPECTED_KEYS)
                val streamStart = System.nanoTime(); failedAt = "original_key_stream"
                try {
                    raw.forEachPreparedEntry(checkCancelled = ::checkBudget) { ordinal, word, _ ->
                        check(ordinal == words.size && ordinal < EXPECTED_KEYS); words += word
                    }
                } finally {
                    record("production_original_key_stream_elapsed_ns", System.nanoTime() - streamStart)
                    record("original_key_stream_count", words.size)
                }
                check(words.size == EXPECTED_KEYS)
                val rowFile = File(directory, "verification-row-hashes.tsv"); check(rowFile.createNewFile())
                var compared = 0; val digest = MessageDigest.getInstance("SHA-256")
                val verifyStart = System.nanoTime()
                try {
                    FileOutputStream(rowFile).use { bytes -> bytes.bufferedWriter(Charsets.UTF_8).use { writer ->
                        for ((ordinal, word) in words.withIndex()) {
                            failedAt = "verify:$ordinal"; checkBudget()
                            val expected = raw.lookup(word)
                            val actual = store.lookup(word)
                            val domainExpected = expected.map { DictionaryEntry(word = it.word, translation = "", definition = it.text) }
                            if (domainExpected != actual) {
                                record("mismatch", buildJsonObject { put("ordinal", ordinal); put("query_sha256", sha256(word.toByteArray())); put("raw", resultSignature(expected)); put("store", resultSignature(asMdx(actual))) })
                                error("Production Store result differs")
                            }
                            if (ordinal == 0) checkBackend(store, "prepared")
                            val signature = resultSignature(expected).string("ordered_result_sha256")
                            val row = "$ordinal\t${sha256(word.toByteArray(Charsets.UTF_8))}\t$signature\n"
                            writer.write(row); digest.update(row.toByteArray(Charsets.UTF_8)); compared++
                            if (compared % 1000 == 0) println("mdx-store verify rows=$compared")
                        }
                        writer.flush(); bytes.fd.sync()
                    } }
                    checkBackend(store, "prepared"); check(compared == EXPECTED_KEYS)
                    record("corpus_lookup_fidelity_verified", true); record("all_dictionary_entry_fields_equal", true)
                } finally {
                    record("verified_original_key_count", compared); record("verification_elapsed_ns", System.nanoTime() - verifyStart)
                    record("verification_rows_file", rowFile.name); record("verification_rows_sha256", sha256(rowFile))
                    record("verification_stream_sha256", digest.digest().hex())
                }
            }
        }

        fun cloneSetup(backend: String): File {
            val sourceRun = File(runs, checkNotNull(setupRun))
            check(sourceRun.canonicalFile.parentFile == runs.canonicalFile && !Files.isSymbolicLink(sourceRun.toPath()))
            val sourceReportFile = File(sourceRun, "report.json"); val report = readJson(sourceReportFile)
            check(report.string("phase") == "setup" && report.string("status") == "complete" && report.boolean("setup_complete"))
            check(report.string("dictionary_sha256") == DICTIONARY_SHA256 && report.string("parser_version") == PreparedDictionaryFormat.PARSER_VERSION)
            fingerprints.forEach { (key, value) -> check(report.string(key) == value) { "Setup code binding differs" } }
            check(report.getValue("target_apk") == checkNotNull(targetApk))
            check(report.getValue("instrumentation_apk") == checkNotNull(testApk))
            immutable[sourceReportFile] = sha256(sourceReportFile)
            val expected = parseInventory(report.getValue("${backend}_installation") as JsonObject)
            if (backend == "raw") check(expected.keys == BASE_FILES)
            else {
                val artifact = report.string("prepared_artifact_path")
                check(artifact.startsWith(receiptPath().substringBeforeLast('/') + "/"))
                check(expected.keys == BASE_FILES + setOf(receiptPath(), artifact))
            }
            val sourceRoot = File(sourceRun, "work/$backend-store")
            check(inventory(sourceRoot) == expected) { "Setup installation was changed" }
            val target = File(newWork(), "store").apply { check(mkdir()) }
            for ((relative, identity) in expected) {
                checkBudget(); val from = File(sourceRoot, relative); val to = File(target, relative)
                val parent = checkNotNull(to.parentFile); check(parent.isDirectory || parent.mkdirs())
                check(to.canonicalFile.toPath().startsWith(target.canonicalFile.toPath()))
                check(to.createNewFile())
                from.inputStream().use { input -> FileOutputStream(to).use { output ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while (true) { checkBudget(); val n = input.read(buffer); if (n < 0) break; total += n; check(total <= identity.bytes); output.write(buffer, 0, n) }
                    check(total == identity.bytes); output.fd.sync()
                } }
                check(sha256(to) == identity.sha); immutable[to] = identity.sha; immutable[from] = identity.sha
            }
            check(inventory(target) == expected)
            record("setup_report_sha256", sha256(sourceReportFile)); record("installed_input_bytes", expected.values.sumOf { it.bytes })
            record("installed_input_manifest", inventoryJson(expected)); record("installed_directory_cloned_before_timing", true)
            return target
        }
        fun copyCorpus(work: File): File {
            val lines = assets.assets.open("dictionaries/catalog.tsv").bufferedReader().use { it.readLines() }
            val name = lines.map { it.split('\t', limit = 2) }.single { it.size == 2 && it[0] == DICTIONARY_SHA256 }[1]
            check(name.isNotBlank() && name.none { it in "/\\\r\n\t" })
            val file = File(work, "corpus.mdx"); check(file.createNewFile())
            assets.assets.open("dictionaries/$name").use { input -> FileOutputStream(file).use { output ->
                val bytes = ByteArray(64 * 1024); var total = 0L
                while (true) { checkBudget(); val n = input.read(bytes); if (n < 0) break; total += n; check(total <= DICTIONARY_BYTES); output.write(bytes, 0, n) }
                check(total == DICTIONARY_BYTES); output.fd.sync()
            } }
            check(sha256(file) == DICTIONARY_SHA256); immutable[file] = DICTIONARY_SHA256
            return file
        }
        fun newWork() = File(directory, "work").apply { check(!exists() && mkdir()) }
        fun newStore(root: File) = DictionaryStore(ScopedContext(owner, root), installPackaged = false, preparationScope = null).also(stores::add)
        fun inventory(root: File): Map<String, FileIdentity> {
            check(root.isDirectory && !Files.isSymbolicLink(root.toPath()))
            val result = sortedMapOf<String, FileIdentity>(); var total = 0L
            root.walkTopDown().forEach { file ->
                checkBudget(); check(!Files.isSymbolicLink(file.toPath()))
                if (file.isFile) {
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    checkSafeRelative(relative); check(file.length() in 1..MAX_INSTALL_BYTES)
                    total += file.length(); check(total <= MAX_INSTALL_BYTES)
                    result[relative] = FileIdentity(file.length(), sha256(file))
                }
            }
            return result
        }
    }

    private class ScopedContext(context: Context, private val owned: File) : ContextWrapper(context) {
        private fun directory(name: String) = File(owned, name).apply { check(isDirectory || mkdirs()) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory("no_backup")
        override fun getFilesDir(): File = directory("files")
        override fun getCacheDir(): File = directory("cache")
        override fun getDatabasePath(name: String): File = File(directory("databases"), name)
    }
    private data class FileIdentity(val bytes: Long, val sha: String)
    private data class QueryMeasurement(val pass: String, val index: Int, val nanos: Long, val complete: Boolean, val result: List<MdxReader.Entry>)
    private class BudgetExhausted : RuntimeException()
    private fun readField(store: DictionaryStore, name: String): Any? = DictionaryStore::class.java.getDeclaredField(name).apply { isAccessible = true }.get(store)
    private fun checkBackend(store: DictionaryStore, expected: String) {
        val reader = readField(store, "reader"); val prepared = readField(store, "prepared"); val sqlite = readField(store, "connection")
        when (expected) {
            "unopened" -> check(reader == null && prepared == null && sqlite == null)
            "raw" -> check(reader is MdxReader && prepared == null && sqlite == null)
            "prepared" -> check(reader == null && prepared is PreparedMdxIndex && sqlite == null)
            else -> error("Unknown expected backend")
        }
    }
    private fun asMdx(entries: List<DictionaryEntry>) = entries.map { MdxReader.Entry(it.word, it.definition) }
    private fun receiptPath() = "no_backup/dictionary/prepared/$DICTIONARY_SHA256/${PreparedDictionaryFormat.PARSER_VERSION}/receipt.json"
    private fun inventoryJson(value: Map<String, FileIdentity>) = buildJsonObject {
        value.forEach { (path, id) -> put(path, buildJsonObject { put("bytes", id.bytes); put("sha256", id.sha) }) }
    }
    private fun parseInventory(value: JsonObject): Map<String, FileIdentity> = value.entries.associate { (path, raw) ->
        checkSafeRelative(path); val id = raw as JsonObject
        val bytes = id.long("bytes"); val sha = id.string("sha256")
        check(bytes in 1..MAX_INSTALL_BYTES && sha.matches(Regex("[a-f0-9]{64}")))
        path to FileIdentity(bytes, sha)
    }.also { check(it.values.sumOf { id -> id.bytes } <= MAX_INSTALL_BYTES) }
    private fun checkSafeRelative(path: String) {
        check(path.isNotBlank() && path.length <= 1024 && !path.startsWith('/') && '\\' !in path &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
    }
    private fun readJson(file: File): JsonObject {
        check(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..MAX_REPORT_BYTES.toLong())
        return Json.parseToJsonElement(file.readText()).jsonObject
    }
    private fun writeNew(file: File, bytes: ByteArray) {
        check(file.createNewFile()) { "Previous evidence must not be overwritten" }
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }
    private fun JsonElement.primitive() = (this as? JsonPrimitive)?.content ?: error("Invalid scalar")
    private fun JsonObject.string(key: String) = getValue(key).primitive()
    private fun JsonObject.long(key: String) = string(key).toLong()
    private fun JsonObject.boolean(key: String) = string(key).let { require(it == "true" || it == "false"); it == "true" }

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
        const val TEST_CLASS = "dev.lexicon.data.PreparedDictionaryStoreBenchmarkTest"
        const val TEST_METHOD = "productionStoreDictionaryInOneFreshProcess"
        const val PROTOCOL = "production-store-fresh-process-fixed128-v1"
        const val DICTIONARY_SHA256 = "898cf9f1c4bf7a7c52669f3e25a5aa8396092b9b77287ba4b7cdc9a16fc77e73"
        const val DICTIONARY_BYTES = 15_895_150L
        const val QUERY_SHA256 = "c45a27b3cd58eee102151017b9e4c2d810757ddfe3dbc776c3ae2d24cee8156c"
        const val EXPECTED_KEYS = 37_375
        const val MAX_REPORT_BYTES = 8 * 1024 * 1024
        const val MAX_INSTALL_BYTES = 768L * 1024 * 1024
        const val BUDGET_NANOS = 900_000_000_000L
        const val SOURCE_PATH = "no_backup/dictionary/$DICTIONARY_SHA256.mdx"
        val BASE_FILES = setOf(SOURCE_PATH, "no_backup/dictionary/catalog.json", "no_backup/dictionary/selected.txt")
        val ARTIFACT_NAME = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.sqlite")
        val PASSES = listOf("first_128", "second_128_same_order")
        val invoked = AtomicBoolean(false)
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
