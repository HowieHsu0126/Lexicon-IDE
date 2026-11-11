package dev.lexicon.data

import dev.lexicon.data.mdict.MdxReader
import dev.lexicon.data.mdict.PreparedDictionaryFormat
import dev.lexicon.data.mdict.PreparedMdxIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Disposable derived files. The original dictionary and the user's selection are never edited. */
internal class PreparedDictionaryCache(
    private val directory: File,
    private val preparationScope: CoroutineScope? = null,
    private val parserVersion: String = PreparedDictionaryFormat.PARSER_VERSION,
) {
    @Serializable
    internal data class Receipt(
        val format: Int = 1,
        val sourceSha256: String,
        val parserVersion: String,
        val artifactFile: String,
        val artifactSha256: String,
        val rowCount: Int,
    )

    private val json = Json { encodeDefaults = true }
    private val writer = Mutex()
    private val scheduling = Any()
    private var selectionGeneration = -1L
    private var taskGeneration = 0L
    private var requestedKey: String? = null
    private var requestedRepair: String? = null
    private var scheduled: Job? = null
    private val rejectedArtifacts = mutableMapOf<String, String>()

    /**
     * Reads only a completed locally-built receipt. Missing/invalid acceleration is a raw-MDX
     * fallback, not an empty dictionary. A caller may reuse a source verification performed in
     * the same serialized selection operation; ordinary cold opens verify the MDX here.
     */
    fun tryOpen(
        source: File,
        sourceSha: String,
        checkCancelled: () -> Unit = {},
        sourceAlreadyVerified: Boolean = false,
    ): PreparedMdxIndex? {
        var checkedReceipt: Receipt? = null
        return try {
            checkCancelled()
            val area = area(sourceSha, create = false) ?: return null
            val receipt = readReceipt(area, sourceSha) ?: return null
            checkedReceipt = receipt
            if (synchronized(scheduling) { rejectedArtifacts[sourceSha] == receipt.artifactSha256 }) return null
            if (!sourceAlreadyVerified) require(sourceFingerprint(source, checkCancelled) == sourceSha)
            checkCancelled()
            PreparedMdxIndex.openBound(File(area, receipt.artifactFile), sourceSha, parserVersion,
                receipt.artifactSha256, checkCancelled).also { opened ->
                try {
                    require(opened.rowCount == receipt.rowCount) { "Prepared receipt count mismatch" }
                    checkCancelled()
                } catch (failure: Exception) {
                    runCatching { opened.close() }
                    throw failure
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            checkedReceipt?.let { receipt -> synchronized(scheduling) { rejectedArtifacts[sourceSha] = receipt.artifactSha256 } }
            null
        }
    }

    /** Explicit preparation shares the same single-writer lane as scheduled preparation. */
    suspend fun prepare(source: File, sourceSha: String, checkCancelled: () -> Unit = {}) {
        prepareWithCommit(source, sourceSha, checkCancelled, verifyExisting = true) { publish -> checkCancelled(); publish() }
    }

    /**
     * Called after DictionaryStore releases its lock. Its monotonic selection generation prevents
     * a delayed caller from scheduling an older choice. Completion never selects a dictionary or
     * replaces an open reader. A failed key is retried only after another selection or process.
     */
    fun schedule(
        source: File?,
        sourceSha: String?,
        selectedGeneration: Long,
        checkCancelled: () -> Unit = {},
    ): Job? {
        val scope = preparationScope ?: return null
        require((source == null) == (sourceSha == null))
        require(selectedGeneration >= 0)
        val key = sourceSha?.let { validateIdentity(it); "$it:$parserVersion" }
        var start: Job? = null
        val answer = synchronized(scheduling) {
            if (selectedGeneration < selectionGeneration) return@synchronized scheduled
            selectionGeneration = selectedGeneration
            val repair = sourceSha?.let { rejectedArtifacts[it] }
            if (requestedKey == key && requestedRepair == repair) return@synchronized scheduled
            scheduled?.cancel()
            requestedKey = key
            requestedRepair = repair
            val token = ++taskGeneration
            if (key == null) {
                scheduled = null
                return@synchronized null
            }
            val input = requireNotNull(source)
            val identity = requireNotNull(sourceSha)
            val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                val owningJob = coroutineContext[Job]
                fun checkOwner() {
                    owningJob?.ensureActive()
                    synchronized(scheduling) {
                        if (taskGeneration != token || requestedKey != key) throw CancellationException("Dictionary preparation superseded")
                    }
                    checkCancelled()
                    owningJob?.ensureActive()
                }
                try {
                    prepareWithCommit(input, identity, ::checkOwner, verifyExisting = false) { publish ->
                        // Linearize receipt publication with a newer selection. The file write and
                        // full validation happen before this short commit gate, outside the gate.
                        synchronized(scheduling) {
                            checkOwner()
                            publish()
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Optional acceleration failed. The installed MDX remains usable and a normal
                    // lookup will keep its raw reader. Do not spam the UI or retry on every word.
                } finally {
                    synchronized(scheduling) {
                        if (taskGeneration == token) scheduled = null
                    }
                }
            }
            scheduled = job
            start = job
            job
        }
        start?.start()
        return answer
    }

    private suspend fun prepareWithCommit(
        source: File,
        sourceSha: String,
        checkCancelled: () -> Unit,
        verifyExisting: Boolean,
        commit: ((() -> Unit) -> Unit),
    ) = withContext(Dispatchers.IO) {
        val owningJob = coroutineContext[Job]
        fun check() { owningJob?.ensureActive(); checkCancelled(); owningJob?.ensureActive() }
        writer.withLock {
            check()
            if (verifyExisting) {
                tryOpen(source, sourceSha, ::check)?.let { it.close(); return@withLock }
            } else if (hasReusableReceipt(sourceSha)) {
                // The foreground opener owns complete source/artifact verification. A new app
                // process must not compete with it by hashing the same prepared file in parallel.
                return@withLock
            }
            val area = requireNotNull(area(sourceSha, create = true))
            require(sourceFingerprint(source, ::check) == sourceSha) { "Dictionary source changed" }
            val target = File(area, "${UUID.randomUUID()}.sqlite")
            var built: PreparedMdxIndex.BuildResult? = null
            var receiptPublished = false
            try {
                MdxReader(source).use { reader ->
                    check()
                    built = PreparedMdxIndex.build(target, sourceSha, parserVersion, reader.metadata.entries.toInt(),
                        checkCancelled = ::check) { consume ->
                        reader.forEachPreparedEntry(checkCancelled = ::check, consumer = consume)
                    }
                }
                val completed = requireNotNull(built)
                check()
                require(sourceFingerprint(source, ::check) == sourceSha) { "Dictionary source changed during preparation" }
                val receipt = Receipt(sourceSha256 = sourceSha, parserVersion = parserVersion,
                    artifactFile = target.name, artifactSha256 = completed.artifactSha256, rowCount = completed.rowCount)
                writeReceipt(area, receipt, ::check) { publish ->
                    commit {
                        check()
                        publish()
                        receiptPublished = true
                        synchronized(scheduling) {
                            rejectedArtifacts.remove(sourceSha)
                            if (requestedKey == "$sourceSha:$parserVersion") requestedRepair = null
                        }
                    }
                }
            } finally {
                // Only a successful builder result establishes ownership of the immutable file.
                // A failed builder already cleans its own partials; an uncertain full publication
                // is left unreferenced rather than deleting a path whose owner we cannot prove.
                if (!receiptPublished) built?.let { completed ->
                    try {
                        if (target.isFile && !Files.isSymbolicLink(target.toPath()) && target.length() == completed.fileBytes &&
                            fingerprint(target, PreparedMdxIndex.MAX_FILE_BYTES) {} == completed.artifactSha256) target.delete()
                    } catch (_: Exception) { /* Preserve an unverified path. It has no ready receipt. */ }
                }
            }
        }
    }

    /** A scheduling hint only; this never authorizes a query or skips openBound verification. */
    private fun hasReusableReceipt(sourceSha: String): Boolean = try {
        val area = area(sourceSha, create = false)
        val receipt = area?.let { readReceipt(it, sourceSha) }
        if (area == null || receipt == null) false
        else {
            val artifact = File(area, receipt.artifactFile)
            synchronized(scheduling) { rejectedArtifacts[sourceSha] != receipt.artifactSha256 } &&
                artifact.isFile && !Files.isSymbolicLink(artifact.toPath()) && artifact.length() in 1..PreparedMdxIndex.MAX_FILE_BYTES
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    private fun readReceipt(area: File, sourceSha: String): Receipt? {
        val file = File(area, RECEIPT_FILE)
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() !in 1..MAX_RECEIPT_BYTES) return null
        val bytes = file.inputStream().use { it.readNBytes(MAX_RECEIPT_BYTES.toInt() + 1) }
        if (bytes.size.toLong() > MAX_RECEIPT_BYTES) return null
        val receipt = json.decodeFromString<Receipt>(bytes.toString(Charsets.UTF_8))
        require(receipt.format == 1 && receipt.sourceSha256 == sourceSha && receipt.parserVersion == parserVersion)
        require(ARTIFACT_NAME.matches(receipt.artifactFile) && SHA.matches(receipt.artifactSha256))
        require(receipt.rowCount in 1..PreparedMdxIndex.MAX_ROWS)
        return receipt
    }

    private fun writeReceipt(area: File, receipt: Receipt, checkCancelled: () -> Unit, commit: ((() -> Unit) -> Unit)) {
        val bytes = json.encodeToString(receipt).toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= MAX_RECEIPT_BYTES)
        val temporary = Files.createTempFile(area.toPath(), ".receipt-", ".partial").toFile()
        try {
            checkCancelled()
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            checkCancelled()
            commit {
                Files.move(temporary.toPath(), File(area, RECEIPT_FILE).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }

    private fun area(sourceSha: String, create: Boolean): File? {
        validateIdentity(sourceSha)
        val sourceDirectory = File(directory, sourceSha)
        val result = File(sourceDirectory, parserVersion)
        for (part in listOf(directory, sourceDirectory, result)) {
            if (Files.isSymbolicLink(part.toPath())) return null
            if (!part.exists()) {
                if (!create) return null
                check(part.mkdir() || part.isDirectory) { "Prepared dictionary directory unavailable" }
            }
            require(part.isDirectory && !Files.isSymbolicLink(part.toPath())) { "Prepared dictionary directory invalid" }
        }
        return result
    }

    private fun validateIdentity(sourceSha: String) {
        require(SHA.matches(sourceSha)) { "Invalid dictionary fingerprint" }
        require(parserVersion.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}"))) { "Invalid dictionary parser version" }
    }

    private fun sourceFingerprint(source: File, checkCancelled: () -> Unit) =
        fingerprint(source, MdxReader.MAX_FILE_SIZE, checkCancelled)

    private fun fingerprint(file: File, maximum: Long, checkCancelled: () -> Unit): String {
        require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "Dictionary fingerprint input unavailable" }
        val expected = file.length()
        require(expected in 1..maximum) { "Dictionary fingerprint size invalid" }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(bytes)
                if (count < 0) break
                total += count
                require(total <= expected) { "Dictionary changed during fingerprinting" }
                digest.update(bytes, 0, count)
            }
        }
        checkCancelled()
        require(total == expected && file.length() == expected) { "Dictionary changed during fingerprinting" }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        internal const val RECEIPT_FILE = "receipt.json"
        private const val MAX_RECEIPT_BYTES = 4096L
        private val SHA = Regex("[a-f0-9]{64}")
        private val ARTIFACT_NAME = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.sqlite")
    }
}
