package dev.lexicon.data.mdict

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/** Candidate only. No Lexicon repository, application database, dictionary installation or network use.
 * Each invocation owns a fresh directory in instrumentation.context.filesDir, retained for inspection.
 * Copy/hash setup warms filesystem pages. Timed phases never run raw and prepared lookups together.
 * prepare completion is not corpus-fidelity completion. A budget checkpoint is never a passed verify.
 */
@RunWith(AndroidJUnit4::class)
class PreparedMdxBenchmarkTest {
    @Test fun preparedDictionaryExperimentInOneFreshProcess() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with dictionaryPreparedBenchmark=1", args.getString("dictionaryPreparedBenchmark") == "1")
        check(invoked.compareAndSet(false, true)) { "Use one fresh instrumentation process per invocation" }
        check(args.getString("class") in setOf(TEST_CLASS, "$TEST_CLASS#$TEST_METHOD")) { "Run only this observer" }
        fun label(key: String, optional: Boolean = false): String? = args.getString(key)?.also {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) { "Invalid $key" }
        } ?: if (optional) null else error("Missing $key")
        fun fingerprint(key: String) = args.getString(key).orEmpty().also {
            require(it.matches(Regex("[a-f0-9]{64}"))) { "Missing or invalid $key" }
        }
        val phase = checkNotNull(label("phase")).also { require(it in setOf("prepare", "raw", "prepared", "verify")) }
        val run = checkNotNull(label("run"))
        val prepareRun = label("prepareRun", optional = true)
        val resumeRun = label("resumeRun", optional = true)
        require((phase in setOf("prepared", "verify")) == (prepareRun != null)) { "prepareRun applies to prepared/verify" }
        require(resumeRun == null || phase == "verify") { "Only verify accepts a resumeRun" }
        val parserVersion = args.getString("parserVersion").orEmpty().also {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))) { "Missing or invalid parserVersion" }
        }
        val fingerprints = linkedMapOf(
            "source_manifest_sha256" to fingerprint("sourceManifestSha256"),
            "observer_sha256" to fingerprint("observerSha256"),
            "prototype_sha256" to fingerprint("prototypeSha256"),
            "contract_sha256" to fingerprint("contractSha256"),
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val owner = instrumentation.context
        val runs = File(owner.filesDir, "mdx-prepared-benchmark/runs")
        check(runs.isDirectory || runs.mkdirs()) { "Cannot create observer-owned root" }
        check(!Files.isSymbolicLink(runs.toPath())) { "Indirect observer root" }
        val runDirectory = File(runs, run)
        check(!runDirectory.exists() && runDirectory.mkdir()) { "Existing run must not be overwritten" }
        check(runDirectory.canonicalFile.parentFile == runs.canonicalFile)
        Session(owner, instrumentation.targetContext, runs, runDirectory, phase, run, prepareRun,
            resumeRun, parserVersion, fingerprints).execute()
    }

    private inner class Session(
        val owner: Context, val assetContext: Context, val runs: File, val directory: File,
        val phase: String, val run: String, val prepareRun: String?, val resumeRun: String?,
        val parserVersion: String, val fingerprints: Map<String, String>,
    ) {
        val startedAt = Instant.now().toString()
        val started = System.nanoTime()
        val processStart = Process.getStartElapsedRealtime()
        val processAge = SystemClock.elapsedRealtime() - processStart
        val facts = linkedMapOf<String, JsonElement>()
        val setup = mutableListOf<JsonObject>()
        val measurements = mutableListOf<QueryMeasurement>()
        val closeFailures = mutableListOf<String>()
        val immutableInputs = linkedMapOf<File, String>()
        var failedAt = "setup"
        var status = "failed"
        var targetApk: JsonObject? = null
        var testApk: JsonObject? = null
        fun record(key: String, value: String) { facts[key] = JsonPrimitive(value) }
        fun record(key: String, value: Number) { facts[key] = JsonPrimitive(value) }
        fun record(key: String, value: Boolean) { facts[key] = JsonPrimitive(value) }
        fun record(key: String, value: JsonElement) { facts[key] = value }
        fun checkBudget() { if (System.nanoTime() - started >= BUDGET_NANOS) throw BudgetExhausted() }
        fun progress(rows: Int) {
            if (rows > 0 && rows % 1000 == 0) {
                println("mdx-prepared phase=$phase rows=$rows elapsed_ms=${(System.nanoTime() - started) / 1_000_000}")
            }
        }
        fun <T> setupStep(name: String, action: () -> T): T {
            val start = System.nanoTime()
            try { return action() } finally {
                setup += buildJsonObject { put("phase", name); put("elapsed_ns", System.nanoTime() - start) }
            }
        }
        fun <R : Closeable, T> using(resource: R, action: (R) -> T): T {
            try { return action(resource) } finally {
                try { resource.close() } catch (error: Throwable) { closeFailures += error.javaClass.name; throw error }
            }
        }
        fun execute() {
            val reportFile = File(directory, "report.json")
            check(!reportFile.exists()) { "Existing report must not be overwritten" }
            try {
                check(queries.size == 128 && queries.toSet().size == 128 && queryHash() == QUERY_SHA256)
                check(queries.all { it.matches(Regex("[a-z]+(?:[ -][a-z]+)*")) })
                setupStep("hash_actual_installed_apks") {
                    targetApk = apkIdentity(assetContext)
                    testApk = apkIdentity(owner)
                }
                when (phase) {
                    "prepare" -> prepare()
                    "raw" -> rawQueries()
                    "prepared" -> preparedQueries()
                    "verify" -> verify()
                }
                failedAt = "input_integrity"
                setupStep("rehash_immutable_inputs_after_measurement") {
                    immutableInputs.forEach { (file, digest) -> check(sha256(file) == digest) { "Input changed during phase" } }
                }
                record("immutable_inputs_unchanged", true)
                checkBudget()
                status = "complete"
            } catch (_: BudgetExhausted) {
                status = "incomplete_budget"
                record("budget_exhausted", true)
            } catch (error: Throwable) {
                val cause = if (error is InvocationTargetException) error.targetException else error
                record("failure_class", cause.javaClass.name)
                status = "failed"
            } finally {
                if (closeFailures.isNotEmpty()) status = "failed"
                // Preserve the existing digest protocol, but perform all result hashing after timing.
                val resultHashStart = System.nanoTime()
                val resultSamples = measurements.map { measured -> buildJsonObject {
                    put("pass", measured.pass); put("query_index", measured.queryIndex); put("query", queries[measured.queryIndex])
                    put("complete", measured.succeeded); put("elapsed_ns", measured.elapsedNanos)
                    put("result", resultSignature(measured.result))
                } }
                record("query_result_hashing_elapsed_ns", System.nanoTime() - resultHashStart)
                if (phase == "raw" || phase == "prepared") {
                    val constructor = (facts["${phase}_constructor_elapsed_ns"] as? JsonPrimitive)?.content?.toLongOrNull()
                    val first = measurements.firstOrNull { it.pass == PASSES[0] && it.queryIndex == 0 && it.succeeded }
                    if (constructor != null && first != null) {
                        record("constructor_plus_first_query_elapsed_ns", constructor + first.elapsedNanos)
                        val firstPass = measurements.filter { it.pass == PASSES[0] && it.succeeded }
                        if (firstPass.size == queries.size) record("constructor_plus_first_pass_elapsed_ns", constructor + firstPass.sumOf { it.elapsedNanos })
                    }
                }
                val report = buildJsonObject {
                    put("schema", "lexicon.mdx.prepared-benchmark.v1"); put("protocol", PROTOCOL)
                    put("status", status); put("phase", phase); put("run", run)
                    prepareRun?.let { put("prepare_run", it) }; resumeRun?.let { put("resume_run", it) }
                    put("parser_version", parserVersion)
                    fingerprints.forEach { (key, value) -> put(key, value) }
                    put("source_fingerprints_are_runner_supplied", true)
                    put("started_at", startedAt); put("finished_at", Instant.now().toString())
                    put("pid", Process.myPid()); put("process_start_elapsed_ms", processStart)
                    put("process_age_at_test_start_ms", processAge)
                    put("wall_elapsed_ms_including_setup_hashes_and_io", (System.nanoTime() - started) / 1_000_000.0)
                    put("wall_scope", "Through all result hashes, before report JSON serialization and final report file write")
                    put("cooperative_budget_seconds", BUDGET_NANOS / 1_000_000_000)
                    put("maximum_source_rows", MAX_ROWS); put("maximum_definition_utf16_units", MAX_TEXT_CHARS)
                    put("budget_check_scope", "Between source rows, queries and phases; current blocking operation and report finalization may finish after deadline")
                    put("dictionary_sha256", DICTIONARY_SHA256); put("dictionary_bytes", DICTIONARY_BYTES)
                    put("edition_independently_verified", false)
                    targetApk?.let { put("target_apk", it) }; testApk?.let { put("instrumentation_apk", it) }
                    put("device", buildJsonObject {
                        put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL)
                        put("hardware", Build.HARDWARE); put("fingerprint", Build.FINGERPRINT); put("sdk", Build.VERSION.SDK_INT)
                        put("supported_abis", JsonArray(Build.SUPPORTED_ABIS.map(::JsonPrimitive))); put("process_is_64_bit", Process.is64Bit())
                    })
                    put("query_list_sha256", QUERY_SHA256)
                    put("query_hash_encoding", "UTF-8, one query per line, final LF included")
                    put("query_order", JsonArray(queries.map(::JsonPrimitive))); put("query_count_per_pass", queries.size)
                    put("ordered_result_hash_encoding", "Each returned word then definition: UTF-8 with 4-byte big-endian byte length; original result order; no list count prefix")
                    put("query_samples", JsonArray(resultSamples))
                    put("query_pass_summaries", JsonArray(PASSES.map { pass ->
                        val selected = measurements.filter { it.pass == pass && it.succeeded }
                        buildJsonObject {
                            put("pass", pass); put("complete_query_count", selected.size)
                            put("miss_count", selected.count { it.result.isEmpty() }); put("timing", percentiles(selected.map { it.elapsedNanos }))
                        }
                    }))
                    put("setup", JsonArray(setup))
                    put("constructor_scope", "First implementation open in the sole test, separately timed; copying and hashing warm filesystem pages")
                    put("prepared_open_strategy", "trusted-builder-artifact-sha-v1")
                    put("prepared_open_scope", "openBound includes complete file SHA matched to the trusted builder artifact plus small schema/metadata/identity/row-count checks; no per-row decode/normalize")
                    put("first_use_cost_scope", "Constructor plus first query/pass excludes setup and result hashes; warm prepared query P95 alone is not total first-use benefit")
                    put("timing_scope", "Constructor/lookup calls exclude external setup/report/result hashes, equality and JSON; openBound complete-file hashing and metadata checks are included")
                    put("prepare_timing_scope", "Build includes reflection resolve, unchanged plainText, per-row hashes, row-log writes and sidecar build; raw constructor separate")
                    put("percentile_scope", "Nearest-rank percentiles of the fixed word distribution, not process-population estimates")
                    put("retains_results_until_measurement_ends", true)
                    put("filesystem_cache_flushed", false); put("forced_gc", false)
                    put("uses_read_stage_observer", false)
                    put("test_invokes_network", false); put("touches_lexicon_database", false)
                    put("all_writes_in_test_app_owned_directory", true); put("private_owned_directory_retained", true)
                    put("sidecar_contains_dictionary_definitions", phase != "raw"); put("no_dictionary_definition_text_in_report", true)
                    put("owned_payload_bytes_excluding_report", directory.walkTopDown().filter { it.isFile && it != reportFile }.sumOf { it.length() })
                    put("close_failure_classes", JsonArray(closeFailures.map(::JsonPrimitive)))
                    facts.forEach { (key, value) -> put(key, value) }
                    if (status != "complete") put("failed_at", failedAt)
                }
                writeNew(reportFile, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report).toByteArray(Charsets.UTF_8))
            }
            check(status == "complete") { "Prepared MDX phase incomplete; inspect the new owned report" }
        }

        fun ownWork(): File = File(directory, "work").also {
            check(!it.exists() && it.mkdir()) { "Existing output work directory" }
        }
        fun copyCorpus(work: File): File = setupStep("copy_and_hash_asset") {
            val rows = assetContext.assets.open("dictionaries/catalog.tsv").bufferedReader().use { it.readLines() }
            val asset = rows.map { it.split('\t', limit = 2) }.single { it.size == 2 && it[0] == DICTIONARY_SHA256 }[1]
            require(asset.isNotBlank() && asset.none { it in "/\\\r\n\t" })
            val file = File(work, "corpus.mdx")
            check(file.createNewFile()) { "Existing corpus must not be overwritten" }
            assetContext.assets.open("dictionaries/$asset").use { input -> FileOutputStream(file).use { output ->
                val buffer = ByteArray(64 * 1024); var copied = 0L
                while (true) {
                    checkBudget(); val count = input.read(buffer); if (count < 0) break
                    copied += count; check(copied <= DICTIONARY_BYTES); output.write(buffer, 0, count)
                }
                output.fd.sync()
            } }
            check(file.length() == DICTIONARY_BYTES && sha256(file) == DICTIONARY_SHA256) { "Unexpected corpus identity" }
            immutableInputs[file] = DICTIONARY_SHA256
            file
        }
        fun openRaw(file: File): MdxReader {
            checkBudget(); failedAt = "raw_constructor"; val start = System.nanoTime()
            val raw = try { MdxReader(file) } finally { record("raw_constructor_elapsed_ns", System.nanoTime() - start) }
            if (raw.metadata.entries != EXPECTED_KEYS.toLong()) {
                raw.close(); error("Unexpected corpus key count")
            }
            return raw
        }
        fun openPrepared(file: File, expectedArtifactSha: String): PreparedMdxIndex {
            // expectedArtifactSha comes only from the bound, completed prepare report.
            // All openBound file hashing and metadata checks remain inside this timer.
            checkBudget(); failedAt = "prepared_constructor"; val start = System.nanoTime()
            return try { PreparedMdxIndex.openBound(file, DICTIONARY_SHA256, parserVersion, expectedArtifactSha) }
            finally { record("prepared_constructor_elapsed_ns", System.nanoTime() - start) }
        }
        fun prepare() {
            val work = ownWork(); val corpus = copyCorpus(work)
            using(openRaw(corpus)) { raw ->
                val accessor = RawRows(raw)
                check(accessor.size == EXPECTED_KEYS && accessor.size <= MAX_ROWS)
                record("source_row_count", accessor.size)
                val target = File(work, "prepared.sqlite"); val rowFile = File(directory, "row-hashes.tsv")
                check(!target.exists() && rowFile.createNewFile()) { "Existing prepare output" }
                var processed = 0; var nullResolve = 0; var blankSanitized = 0; var emptyEntry = 0
                var textCharacters = 0L; var extractionNanos = 0L; var rowHashNanos = 0L
                val rowsHash = CanonicalHash().text("prepared-mdx-observer-rows-v1").long(accessor.size.toLong())
                val buildStart = System.nanoTime()
                try {
                    FileOutputStream(rowFile).use { bytes -> bytes.bufferedWriter(Charsets.UTF_8).use { writer ->
                        val builderStarted = System.nanoTime()
                        val built = PreparedMdxIndex.build(target, DICTIONARY_SHA256, parserVersion, accessor.size) { consume ->
                            for (ordinal in 0 until accessor.size) {
                                failedAt = "prepare:$ordinal"; checkBudget()
                                val word = accessor.word(ordinal); check(word.length <= MAX_WORD_CHARS)
                                val extractStart = System.nanoTime()
                                val content = accessor.resolve(ordinal)
                                val text = content?.let { MdxReader.plainText(it, word) }
                                val entry = text?.takeIf { it.isNotBlank() }?.let { MdxReader.Entry(word, it) }
                                extractionNanos += System.nanoTime() - extractStart
                                if (content == null) nullResolve++
                                if (text != null && text.isBlank()) blankSanitized++
                                if (entry == null) emptyEntry++ else { check(entry.text.length <= MAX_TEXT_CHARS); textCharacters += entry.text.length }
                                val hashStart = System.nanoTime()
                                val signature = resultSignature(if (entry == null) emptyList() else listOf(entry))
                                val digest = CanonicalHash().text("prepared-mdx-observer-row-v1").long(ordinal.toLong())
                                    .text(word).text(signature.string("ordered_result_sha256")).finish()
                                rowsHash.text(digest); rowHashNanos += System.nanoTime() - hashStart
                                writer.append(ordinal.toString()).append('\t').append(digest).append('\n')
                                consume(ordinal, word, entry?.text)
                                processed++; record("prepared_rows", processed); progress(processed)
                            }
                        }
                        record("sidecar_builder_call_elapsed_ns_including_producer", System.nanoTime() - builderStarted)
                        writer.flush(); bytes.fd.sync()
                        record("builder_partial_cleanup_succeeded", built.partialCleanupSucceeded)
                        check(built.partialCleanupSucceeded) { "Owned build partial was not cleaned" }
                        check(built.rowCount == accessor.size && built.textCharacters == textCharacters && built.nullTextCount == emptyEntry)
                        check(built.blankTextCount == 0 && built.fileBytes == target.length())
                        record("builder_rows_sha256", built.rowsSha256); record("builder_normalized_key_count", built.normalizedKeyCount)
                        check(built.artifactSha256.matches(Regex("[a-f0-9]{64}"))) { "Builder artifact identity missing" }
                        val actualArtifact = sha256(target)
                        check(actualArtifact == built.artifactSha256) { "Verified builder artifact changed" }
                        record("builder_artifact_sha256", built.artifactSha256)
                        record("sidecar_bytes", built.fileBytes); record("sidecar_sha256", actualArtifact)
                        immutableInputs[target] = built.artifactSha256
                        record("sidecar_file", "work/prepared.sqlite"); record("source_corpus_file", "work/corpus.mdx")
                        record("prepare_complete", true); record("corpus_lookup_fidelity_verified", false)
                    } }
                } finally {
                    FileOutputStream(rowFile, true).use { it.fd.sync() }
                    record("prepare_build_elapsed_ns_including_extract_hash_write", System.nanoTime() - buildStart)
                    record("extraction_elapsed_ns", extractionNanos); record("row_hash_elapsed_ns", rowHashNanos)
                    record("output_definition_characters", textCharacters); record("null_resolve_rows", nullResolve)
                    record("blank_sanitized_rows", blankSanitized); record("nullable_entry_rows", emptyEntry)
                    record("row_hashes_file", rowFile.name); record("row_hashes_sha256", sha256(rowFile))
                    record("row_hash_encoding", "ordinal TAB row-SHA256 LF; each row hashes UTF-8 length-prefixed domain, 8-byte ordinal, original word, and existing ordered_result_sha256")
                    record("observer_rows_sha256", rowsHash.finish())
                }
            }
        }
        fun rawQueries() {
            val corpus = copyCorpus(ownWork())
            using(openRaw(corpus)) { raw -> queryPasses { raw.lookup(it) } }
        }
        fun preparedQueries() {
            val reference = preparedReference()
            using(openPrepared(reference.sidecar, reference.expectedArtifactSha)) { prepared ->
                check(prepared.rowCount == EXPECTED_KEYS); queryPasses { prepared.lookup(it) }
            }
        }
        fun queryPasses(lookup: (String) -> List<MdxReader.Entry>) {
            for (pass in PASSES) for ((index, query) in queries.withIndex()) {
                failedAt = "$pass:$index"; checkBudget()
                var result: List<MdxReader.Entry> = emptyList(); var succeeded = false
                val start = System.nanoTime()
                try { result = lookup(query); succeeded = true }
                finally { measurements += QueryMeasurement(pass, index, System.nanoTime() - start, succeeded, result) }
            }
            failedAt = "query_result_consistency"
            val first = measurements.filter { it.pass == PASSES[0] }; val second = measurements.filter { it.pass == PASSES[1] }
            check(first.size == queries.size && second.size == queries.size)
            first.zip(second).forEach { (a, b) ->
                check(a.succeeded && b.succeeded && a.result == b.result) { "Two same-order passes differ" }
                check(a.result.size <= LOOKUP_LIMIT && a.result.all { it.word.length <= MAX_WORD_CHARS && it.text.length <= MAX_TEXT_CHARS })
            }
            record("same_order_pass_results_equal", true)
            record("cross_implementation_fidelity_requires_separate_verify", true)
        }
        fun preparedReference(): PreparedReference = setupStep("read_and_hash_completed_prepare") {
            val name = checkNotNull(prepareRun); val source = readRunReport(name)
            check(source.string("phase") == "prepare" && source.string("status") == "complete" && source.boolean("prepare_complete"))
            checkBindings(source)
            check(source.string("source_corpus_file") == "work/corpus.mdx" && source.string("sidecar_file") == "work/prepared.sqlite")
            val sourceDirectory = File(runs, name)
            val corpus = checkedFile(File(sourceDirectory, "work/corpus.mdx"), sourceDirectory)
            val sidecar = checkedFile(File(sourceDirectory, "work/prepared.sqlite"), sourceDirectory)
            check(corpus.length() == DICTIONARY_BYTES && sha256(corpus) == DICTIONARY_SHA256)
            // Do not derive trust by hashing an arbitrary current file. The expected SHA
            // must be the builder result in the already-bound, successful prepare report.
            val expectedArtifactSha = source.string("builder_artifact_sha256")
            check(expectedArtifactSha.matches(Regex("[a-f0-9]{64}")) && expectedArtifactSha == source.string("sidecar_sha256"))
            val digest = sha256(sidecar)
            check(digest == expectedArtifactSha && sidecar.length() == source.long("sidecar_bytes"))
            immutableInputs[corpus] = DICTIONARY_SHA256; immutableInputs[sidecar] = expectedArtifactSha
            record("prepared_expected_artifact_sha256", expectedArtifactSha)
            record("prepared_artifact_binding_source", "Builder artifact SHA in the bound completed prepare report; independently matched to immutable sidecar")
            record("prepare_report_sha256", sha256(File(sourceDirectory, "report.json")))
            record("prepared_sidecar_sha256", digest); record("prepared_sidecar_bytes", sidecar.length())
            PreparedReference(corpus, sidecar, expectedArtifactSha)
        }
        fun verify() {
            val reference = preparedReference()
            using(openRaw(reference.corpus)) { raw -> using(openPrepared(reference.sidecar, reference.expectedArtifactSha)) { prepared ->
                val accessor = RawRows(raw); check(accessor.size == prepared.rowCount && accessor.size == EXPECTED_KEYS && accessor.size <= MAX_ROWS)
                var next = 0
                var chain = sha256("prepared-mdx-verify-prefix-v1".toByteArray(Charsets.UTF_8))
                if (resumeRun != null) {
                    val previous = readRunReport(resumeRun); checkBindings(previous)
                    check(previous.string("phase") == "verify" && previous.string("status") == "incomplete_budget")
                    check(previous.string("prepare_run") == prepareRun && previous.string("prepared_sidecar_sha256") == facts.getValue("prepared_sidecar_sha256").primitive())
                    next = previous.long("verify_next_ordinal").toInt()
                    check(next in 1..accessor.size && previous.long("verification_prefix_count") == next.toLong()
                        && previous.long("verification_total_source_rows") == accessor.size.toLong()
                        && previous.boolean("verification_complete") == (next == accessor.size))
                    val log = checkedFile(File(File(runs, resumeRun), "verification-row-hashes.tsv"), File(runs, resumeRun))
                    check(sha256(log) == previous.string("verification_rows_sha256"))
                    chain = previous.string("verification_chain_sha256").also { check(it.matches(Regex("[a-f0-9]{64}"))) }
                    record("resume_report_sha256", sha256(File(File(runs, resumeRun), "report.json")))
                }
                record("verification_start_ordinal", next); record("verification_total_source_rows", accessor.size)
                record("verify_next_ordinal", next); record("verification_prefix_count", next); record("verification_complete", false)
                val rowFile = File(directory, "verification-row-hashes.tsv")
                check(rowFile.createNewFile()) { "Existing verification output" }
                val verificationStart = System.nanoTime()
                try {
                    FileOutputStream(rowFile).use { bytes -> bytes.bufferedWriter(Charsets.UTF_8).use { writer ->
                        while (next < accessor.size) {
                            failedAt = "verify:$next"; checkBudget()
                            val word = accessor.word(next)
                            val expected = raw.lookup(word, LOOKUP_LIMIT); val actual = prepared.lookup(word, LOOKUP_LIMIT)
                            if (expected != actual) {
                                record("mismatch", buildJsonObject {
                                    put("ordinal", next); put("query_sha256", sha256(word.toByteArray(Charsets.UTF_8)))
                                    put("raw", resultSignature(expected)); put("prepared", resultSignature(actual))
                                })
                                throw FidelityMismatch()
                            }
                            check(expected.size <= LOOKUP_LIMIT && expected.all { it.text.length <= MAX_TEXT_CHARS })
                            val result = resultSignature(expected)
                            val row = CanonicalHash().text("prepared-mdx-verify-row-v1").long(next.toLong()).text(word)
                                .text(result.string("ordered_result_sha256")).finish()
                            chain = CanonicalHash().text("prepared-mdx-verify-chain-v1").text(chain).text(row).finish()
                            writer.append(next.toString()).append('\t').append(row).append('\n')
                            next++; record("verify_next_ordinal", next); record("verification_prefix_count", next); progress(next)
                        }
                        writer.flush(); bytes.fd.sync()
                    } }
                    record("verification_complete", next == accessor.size)
                    record("corpus_lookup_fidelity_verified", next == accessor.size)
                } finally {
                    FileOutputStream(rowFile, true).use { it.fd.sync() }
                    record("verification_elapsed_ns_including_lookup_equality_hash_io", System.nanoTime() - verificationStart)
                    record("verification_chain_sha256", chain); record("verification_rows_sha256", sha256(rowFile))
                    record("verification_query_scope", "Every original key word, including duplicate ordinals, as lookup(query,8); not all arbitrary prefixes/Unicode inputs/limits")
                    record("resume_scope", "New report binds the previous immutable budget checkpoint and row-log hash; host must retain the complete resume-report chain")
                }
            } }
        }
        fun readRunReport(name: String): JsonObject {
            check(name != run)
            val parent = File(runs, name)
            check(parent.isDirectory && !Files.isSymbolicLink(parent.toPath()) && parent.canonicalFile.parentFile == runs.canonicalFile)
            val file = checkedFile(File(parent, "report.json"), parent)
            check(file.length() in 1..MAX_REPORT_BYTES)
            return Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject ?: error("Invalid observer report")
        }
        fun checkBindings(previous: JsonObject) {
            check(previous.string("schema") == "lexicon.mdx.prepared-benchmark.v1" && previous.string("protocol") == PROTOCOL
                && previous.string("prepared_open_strategy") == "trusted-builder-artifact-sha-v1")
            check(previous.string("dictionary_sha256") == DICTIONARY_SHA256 && previous.string("parser_version") == parserVersion)
            fingerprints.forEach { (key, value) -> check(previous.string(key) == value) { "Prior run fingerprint differs" } }
            check((previous["target_apk"] as JsonObject).string("sha256") == checkNotNull(targetApk).string("sha256"))
            check((previous["instrumentation_apk"] as JsonObject).string("sha256") == checkNotNull(testApk).string("sha256"))
        }
    }

    private class BudgetExhausted : RuntimeException()
    private class FidelityMismatch : RuntimeException()
    private data class PreparedReference(val corpus: File, val sidecar: File, val expectedArtifactSha: String)
    private data class QueryMeasurement(val pass: String, val queryIndex: Int, val elapsedNanos: Long,
                                        val succeeded: Boolean, val result: List<MdxReader.Entry>)
    /** Observer-only reflection. No production API or extraction implementation is changed. */
    private class RawRows(private val reader: MdxReader) {
        @Suppress("UNCHECKED_CAST")
        private val keys = MdxReader::class.java.getDeclaredField("keys").apply { isAccessible = true }.get(reader) as List<Any>
        private val wordField = keys.first().javaClass.getDeclaredField("word").apply { isAccessible = true }
        private val resolve = MdxReader::class.java.getDeclaredMethod("resolve", Int::class.javaPrimitiveType,
            MutableSet::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        val size get() = keys.size
        fun word(ordinal: Int): String = wordField.get(keys[ordinal]) as String
        fun resolve(ordinal: Int): String? = resolve.invoke(reader, ordinal, HashSet<Int>(), 0) as String?
    }
    private fun checkedFile(file: File, parent: File): File = file.also {
        check(it.isFile && !Files.isSymbolicLink(it.toPath()) && it.canonicalFile.toPath().startsWith(parent.canonicalFile.toPath()))
        check(!Files.isSymbolicLink(checkNotNull(it.parentFile).toPath()))
    }
    private fun writeNew(file: File, bytes: ByteArray) {
        check(file.createNewFile()) { "Existing report must not be overwritten" }
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
        const val TEST_CLASS = "dev.lexicon.data.mdict.PreparedMdxBenchmarkTest"
        const val TEST_METHOD = "preparedDictionaryExperimentInOneFreshProcess"
        const val PROTOCOL = "mdx-prepared-fresh-process-fixed128-v1"
        const val DICTIONARY_SHA256 = "898cf9f1c4bf7a7c52669f3e25a5aa8396092b9b77287ba4b7cdc9a16fc77e73"
        const val DICTIONARY_BYTES = 15_895_150L
        const val QUERY_SHA256 = "c45a27b3cd58eee102151017b9e4c2d810757ddfe3dbc776c3ae2d24cee8156c"
        const val EXPECTED_KEYS = 37_375
        const val MAX_ROWS = 500_000
        const val MAX_WORD_CHARS = 4096
        const val MAX_TEXT_CHARS = 60_000
        const val MAX_REPORT_BYTES = 8L * 1024 * 1024
        const val LOOKUP_LIMIT = 8
        const val BUDGET_NANOS = 900_000_000_000L
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
