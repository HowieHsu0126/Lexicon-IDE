package dev.lexicon.data.mdict

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.security.MessageDigest

/**
 * Read-only prepared dictionary text bound to an immutable, verified MDX artifact.
 * Never edits an MDX or chooses a dictionary. Cancellation is cooperative at file/row/stage
 * boundaries; a single SQLite operation is allowed to finish before the next cancellation check.
 * The producer owns MDX identity verification, alias resolution and existing plainText semantics.
 * All rows, including suppressed results, are retained in original key order.
 */
internal class PreparedMdxIndex private constructor(
    private val connection: SQLiteConnection,
    val rowCount: Int,
    val rowsSha256: String,
) : Closeable {
    private var closed = false

    data class BuildResult(
        val fileBytes: Long,
        val rowCount: Int,
        val textCharacters: Long,
        val nullTextCount: Int,
        val blankTextCount: Int,
        val normalizedKeyCount: Int,
        val rowsSha256: String,
        val partialCleanupSucceeded: Boolean,
        val artifactSha256: String,
    )

    @Synchronized
    fun lookup(raw: String, limit: Int = 8): List<MdxReader.Entry> {
        check(!closed) { "Prepared dictionary is closed" }
        val query = MdxReader.normalize(raw)
        if (query.isBlank() || query.length > 200) return emptyList()
        require(limit >= 0) { "Requested element count is less than zero" }
        if (limit == 0) return emptyList()
        val lower = utf16Units(query)
        val exact = readMatches(
            "SELECT word_units,text_units FROM prepared_entries WHERE sort_key=? ORDER BY ordinal LIMIT ?",
            lower, null, limit,
        )
        // Existing exact keys suppress prefix fallback even if every selected result is blank/null.
        if (exact.first) return exact.second
        val upper = prefixSuccessor(lower)
        return readMatches(
            if (upper == null) "SELECT word_units,text_units FROM prepared_entries WHERE sort_key>=? ORDER BY sort_key,ordinal LIMIT ?"
            else "SELECT word_units,text_units FROM prepared_entries WHERE sort_key>=? AND sort_key<? ORDER BY sort_key,ordinal LIMIT ?",
            lower, upper, limit,
        ).second
    }

    private fun readMatches(sql: String, lower: ByteArray, upper: ByteArray?, limit: Int): Pair<Boolean, List<MdxReader.Entry>> =
        connection.prepare(sql).use { statement ->
            statement.bindBlob(1, lower)
            if (upper != null) statement.bindBlob(2, upper)
            statement.bindLong(if (upper == null) 2 else 3, limit.toLong())
            var sawRow = false
            val result = ArrayList<MdxReader.Entry>()
            while (statement.step()) {
                sawRow = true
                if (!statement.isNull(1)) {
                    val text = fromUtf16Units(statement.getBlob(1))
                    if (text.isNotBlank()) result += MdxReader.Entry(fromUtf16Units(statement.getBlob(0)), text)
                }
            }
            sawRow to result
        }

    /** Full original ordinal stream; not a lookup and not part of lookup timing. */
    @Synchronized
    fun forEachRow(consumer: (ordinal: Int, word: String, text: String?) -> Unit) {
        check(!closed) { "Prepared dictionary is closed" }
        connection.prepare("SELECT ordinal,word_units,text_units FROM prepared_entries ORDER BY ordinal").use { s ->
            while (s.step()) consumer(s.getLong(0).toInt(), fromUtf16Units(s.getBlob(1)),
                if (s.isNull(2)) null else fromUtf16Units(s.getBlob(2)))
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) { closed = true; connection.close() }
    }

    companion object {
        const val FORMAT = "lexicon-prepared-mdx/v1"
        const val MAX_ROWS = 500_000
        const val MAX_TEXT_CHARACTERS = 60_000
        const val MAX_FILE_BYTES = 512L * 1024 * 1024
        private const val APPLICATION_ID = 0x4c4d4458
        private const val MAX_WORD_CHARACTERS = 4096

        /**
         * Creates only a uniquely-owned same-directory partial until validation completes.
         * Cooperative writers reserve the absent target with CREATE_NEW and a unique non-SQLite
         * marker. ATOMIC_MOVE replaces only that verified reservation after full validation; it is
         * not an atomic no-replace primitive. No copy fallback or unrelated-file overwrite exists.
         * An unfinished reservation is rejected by open(). Writers must not modify another
         * writer's reservation; this protocol does not claim protection against hostile races.
         * The producer must stream every ordinal 0 until expectedCount exactly once.
         * This compatibility entry point defaults cancellation to no operation.
         */
        fun build(
            target: File,
            sourceSha: String,
            parserVersion: String,
            expectedCount: Int,
            producer: (consumer: (ordinal: Int, word: String, text: String?) -> Unit) -> Unit,
        ): BuildResult = build(target, sourceSha, parserVersion, expectedCount, {}, producer)

        fun build(
            target: File,
            sourceSha: String,
            parserVersion: String,
            expectedCount: Int,
            checkCancelled: () -> Unit,
            producer: (consumer: (ordinal: Int, word: String, text: String?) -> Unit) -> Unit,
        ): BuildResult {
            checkCancelled()
            validateIdentity(sourceSha, parserVersion)
            require(expectedCount in 1..MAX_ROWS) { "Invalid prepared key count" }
            val parent = requireNotNull(target.parentFile) { "Prepared target parent is absent" }
            require(parent.isDirectory) { "Prepared target parent is absent" }
            require(!target.exists() && !Files.isSymbolicLink(target.toPath())) { "Prepared target already exists" }
            val temporary = Files.createTempFile(parent.toPath(), ".mdx-prepared-", ".partial.sqlite").toFile()
            var db: SQLiteConnection? = null
            var published = false
            var reservation: ByteArray? = null
            var result: BuildResult? = null
            var cleanupSucceeded = false
            var primaryFailure: Throwable? = null
            try {
                checkCancelled()
                val opened = BundledSQLiteDriver().open(temporary.absolutePath)
                db = opened
                opened.execute("PRAGMA page_size=4096", checkCancelled)
                opened.execute("PRAGMA max_page_count=${MAX_FILE_BYTES / 4096}", checkCancelled)
                opened.execute("PRAGMA journal_mode=DELETE", checkCancelled)
                opened.execute("PRAGMA synchronous=FULL", checkCancelled)
                opened.execute("PRAGMA trusted_schema=OFF", checkCancelled)
                opened.execute("PRAGMA application_id=$APPLICATION_ID", checkCancelled)
                opened.execute("PRAGMA user_version=1", checkCancelled)
                opened.execute("CREATE TABLE prepared_metadata(k TEXT NOT NULL PRIMARY KEY,v TEXT NOT NULL) WITHOUT ROWID", checkCancelled)
                opened.execute("CREATE TABLE prepared_entries(ordinal INTEGER PRIMARY KEY,sort_key BLOB NOT NULL,word_units BLOB NOT NULL,text_units BLOB)", checkCancelled)
                opened.execute("BEGIN IMMEDIATE", checkCancelled)
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0
                var textCharacters = 0L
                var nulls = 0
                var blanks = 0
                var accepting = true
                try {
                    opened.prepare("INSERT INTO prepared_entries(ordinal,sort_key,word_units,text_units) VALUES(?,?,?,?)").use { insert ->
                        try {
                            producer { ordinal, word, text ->
                                checkCancelled()
                                check(accepting) { "Prepared producer callback escaped its build" }
                                require(count < expectedCount && ordinal == count) { "Prepared ordinal missing, repeated or out of order" }
                                require(word.length <= MAX_WORD_CHARACTERS) { "Prepared key is oversized" }
                                require(text == null || text.length <= MAX_TEXT_CHARACTERS) { "Prepared text is oversized" }
                                insert.bindLong(1, ordinal.toLong())
                                insert.bindBlob(2, utf16Units(MdxReader.normalize(word)))
                                insert.bindBlob(3, utf16Units(word))
                                if (text == null) insert.bindNull(4) else insert.bindBlob(4, utf16Units(text))
                                insert.step()
                                insert.reset()
                                insert.clearBindings()
                                addDigest(digest, ordinal, word, text, checkCancelled)
                                textCharacters += text?.length ?: 0
                                if (text == null) nulls++ else if (text.isBlank()) blanks++
                                count++
                                checkCancelled()
                            }
                        } finally { accepting = false }
                    }
                    checkCancelled()
                    require(count == expectedCount) { "Prepared producer ended before all keys" }
                    opened.execute("CREATE INDEX prepared_lookup ON prepared_entries(sort_key,ordinal)", checkCancelled)
                    val normalizedKeys = opened.scalar("SELECT COUNT(DISTINCT sort_key) FROM prepared_entries", checkCancelled).toInt()
                    val rowsHash = hex(digest.digest())
                    val metadata = linkedMapOf(
                        "format" to FORMAT,
                        "source_sha256" to sourceSha,
                        "parser_version" to parserVersion,
                        "key_count" to count.toString(),
                        "text_characters" to textCharacters.toString(),
                        "null_text_count" to nulls.toString(),
                        "blank_text_count" to blanks.toString(),
                        "normalized_key_count" to normalizedKeys.toString(),
                        "rows_sha256" to rowsHash,
                        "complete" to "1",
                    )
                    opened.prepare("INSERT INTO prepared_metadata(k,v) VALUES(?,?)").use { insert ->
                        for ((key, value) in metadata) {
                            checkCancelled()
                            insert.bindText(1, key); insert.bindText(2, value); insert.step()
                            insert.reset(); insert.clearBindings()
                        }
                    }
                    opened.execute("COMMIT", checkCancelled)
                    result = BuildResult(0, count, textCharacters, nulls, blanks, normalizedKeys, rowsHash, false, "")
                } catch (error: Throwable) {
                    runCatching { opened.execute("ROLLBACK") }
                    throw error
                }
                checkCancelled()
                opened.close(); db = null
                checkCancelled()
                require(temporary.length() in 1..MAX_FILE_BYTES) { "Prepared file size exceeded bound" }
                // Read back all metadata, ordinal rows, sort keys and hashes before publication.
                open(temporary, sourceSha, parserVersion, checkCancelled).use { validated ->
                    require(validated.rowCount == expectedCount && validated.rowsSha256 == result.rowsSha256)
                }
                checkCancelled()
                RandomAccessFile(temporary, "rw").use { it.fd.sync() }
                checkCancelled()
                val marker = ("lexicon-prepared-mdx/pending-v1\n" + UUID.randomUUID() + "\n").toByteArray(Charsets.US_ASCII)
                FileChannel.open(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                    // Assignment follows successful exclusive creation; a pre-existing or racing
                    // target leaves reservation null and is never part of our cleanup.
                    reservation = marker
                    val bytes = ByteBuffer.wrap(marker)
                    while (bytes.hasRemaining()) {
                        checkCancelled()
                        check(channel.write(bytes) > 0) { "Prepared reservation write stalled" }
                    }
                    channel.force(true)
                }
                checkCancelled()
                check(ownsReservation(target, marker, allowPartial = false)) { "Prepared reservation changed before publication" }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                published = true
                reservation = null
                checkCancelled()
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                var cleanupFailure: Throwable? = null
                fun cleanup(action: () -> Boolean): Boolean = try { action() } catch (failure: Throwable) {
                    val primary = primaryFailure
                    if (primary != null) {
                        if (failure !== primary) primary.addSuppressed(failure)
                    } else {
                        val previous = cleanupFailure
                        if (previous == null) cleanupFailure = failure
                        else if (failure !== previous) previous.addSuppressed(failure)
                    }
                    false
                }
                val databaseClosed = cleanup { db?.close(); true }
                // Cleanup has no cancellation checks: it finishes while the original cancellation
                // is preserved and rethrown. It never turns cancellation into a successful result.
                // These names are derived only from this call's unique temporary file.
                val ownedPartials = listOf(temporary, File(temporary.path + "-journal"), File(temporary.path + "-wal"), File(temporary.path + "-shm"))
                val partialsRemoved = ownedPartials.map { file -> cleanup { !file.exists() || file.delete() } }.all { it }
                val marker = reservation
                val reservationRemoved = cleanup {
                    when {
                        published || marker == null -> true
                        ownsReservation(target, marker, allowPartial = true) -> target.delete()
                        !target.exists() && !Files.isSymbolicLink(target.toPath()) -> true
                        else -> false // Preserve a changed path whose current owner is uncertain.
                    }
                }
                cleanupSucceeded = databaseClosed && partialsRemoved && reservationRemoved
                // A committed SQLite file never matches the reservation marker and is not removed,
                // even if move returned an uncertain failure. Only our unpublished marker is ours.
                if (primaryFailure == null) cleanupFailure?.let { throw it }
            }
            check(published)
            return checkNotNull(result).copy(fileBytes = target.length(), partialCleanupSucceeded = cleanupSucceeded,
                artifactSha256 = artifactSha256(target, checkCancelled))
        }

        /**
         * Includes physical integrity plus a complete ordinal/content/sort-key validation.
         * Benchmark this entire open separately; it deliberately reads all prepared rows and
         * therefore warms SQLite pages. No opening cost may be called a lookup saving.
         */
        fun open(file: File, sourceSha: String, parserVersion: String, checkCancelled: () -> Unit = {}): PreparedMdxIndex {
            checkCancelled()
            validateIdentity(sourceSha, parserVersion)
            require(file.isFile && file.length() in 1..MAX_FILE_BYTES && !Files.isSymbolicLink(file.toPath())) { "Prepared file is absent or invalid" }
            checkCancelled()
            val db = BundledSQLiteDriver().open(file.absolutePath, SQLITE_OPEN_READONLY)
            try {
                db.execute("PRAGMA query_only=ON", checkCancelled)
                db.execute("PRAGMA trusted_schema=OFF", checkCancelled)
                require(db.scalar("PRAGMA application_id", checkCancelled) == APPLICATION_ID.toLong() && db.scalar("PRAGMA user_version", checkCancelled) == 1L) { "Prepared format version mismatch" }
                checkCancelled()
                db.prepare("PRAGMA integrity_check").use { s ->
                    require(s.step() && s.getText(0) == "ok" && !s.step()) { "Prepared physical integrity failed" }
                }
                checkCancelled()
                val names = mutableMapOf<String, String>()
                db.prepare("SELECT name,type FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%'").use { s ->
                    while (s.step()) { checkCancelled(); names[s.getText(0)] = s.getText(1) }
                }
                require(names == mapOf("prepared_metadata" to "table", "prepared_entries" to "table", "prepared_lookup" to "index")) { "Unexpected prepared schema objects" }
                fun columns(table: String): List<String> = db.prepare("PRAGMA table_info($table)").use { s ->
                    buildList { while (s.step()) { checkCancelled(); add(s.getText(1)) } }
                }
                require(columns("prepared_metadata") == listOf("k", "v") &&
                    columns("prepared_entries") == listOf("ordinal", "sort_key", "word_units", "text_units")) { "Unexpected prepared columns" }
                val meta = mutableMapOf<String, String>()
                db.prepare("SELECT k,v FROM prepared_metadata").use { s -> while (s.step()) { checkCancelled(); meta[s.getText(0)] = s.getText(1) } }
                require(meta.keys == setOf("format", "source_sha256", "parser_version", "key_count", "text_characters", "null_text_count", "blank_text_count", "normalized_key_count", "rows_sha256", "complete")) { "Incomplete prepared metadata" }
                require(meta["format"] == FORMAT && meta["source_sha256"] == sourceSha && meta["parser_version"] == parserVersion && meta["complete"] == "1") { "Prepared identity mismatch or incomplete build" }
                val expectedCount = meta.getValue("key_count").toIntOrNull()
                require(expectedCount != null && expectedCount in 1..MAX_ROWS)
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0; var chars = 0L; var nulls = 0; var blanks = 0
                db.prepare("SELECT ordinal,sort_key,word_units,text_units,typeof(sort_key),typeof(word_units),typeof(text_units) FROM prepared_entries ORDER BY ordinal").use { s ->
                    while (s.step()) {
                        checkCancelled()
                        require(count < expectedCount && s.getLong(0) == count.toLong()) { "Prepared key order differs" }
                        require(s.getText(4) == "blob" && s.getText(5) == "blob" && s.getText(6) in setOf("blob", "null")) { "Prepared string storage differs" }
                        val wordBytes = s.getBlob(2)
                        require(wordBytes.size <= MAX_WORD_CHARACTERS * 2)
                        val word = fromUtf16Units(wordBytes)
                        val text = if (s.isNull(3)) null else {
                            val bytes = s.getBlob(3)
                            require(bytes.size <= MAX_TEXT_CHARACTERS * 2)
                            fromUtf16Units(bytes)
                        }
                        require(s.getBlob(1).contentEquals(utf16Units(MdxReader.normalize(word)))) { "Prepared normalized order differs" }
                        addDigest(digest, count, word, text, checkCancelled)
                        chars += text?.length ?: 0
                        if (text == null) nulls++ else if (text.isBlank()) blanks++
                        count++
                    }
                }
                val rowsHash = hex(digest.digest())
                val normalizedKeys = db.scalar("SELECT COUNT(DISTINCT sort_key) FROM prepared_entries", checkCancelled)
                require(count == expectedCount && meta["text_characters"] == chars.toString() &&
                    meta["null_text_count"] == nulls.toString() && meta["blank_text_count"] == blanks.toString() &&
                    meta["normalized_key_count"] == normalizedKeys.toString() && meta["rows_sha256"] == rowsHash) { "Prepared content integrity failed" }
                checkCancelled()
                return PreparedMdxIndex(db, count, rowsHash)
            } catch (error: Throwable) {
                try { db.close() } catch (closing: Throwable) { if (closing !== error) error.addSuppressed(closing) }
                throw error
            }
        }

        /**
         * Opens a previously fully validated build whose immutable artifact digest was retained
         * by the caller. expectedArtifactSha must come from BuildResult or a trusted local
         * manifest bound to that build, never from hashing an arbitrary input immediately here.
         *
         * The full file fingerprint and lightweight SQLite checks are inside this call's cost.
         * Fingerprinting warms filesystem pages. Unlike open(), this path does not re-normalize,
         * decode or semantically hash every stored row. Cooperative writers keep published
         * artifacts immutable; this is not a protocol for hostile concurrent file replacement.
         */
        fun openBound(file: File, sourceSha: String, parserVersion: String, expectedArtifactSha: String, checkCancelled: () -> Unit = {}): PreparedMdxIndex {
            checkCancelled()
            validateIdentity(sourceSha, parserVersion)
            require(expectedArtifactSha.matches(Regex("[a-f0-9]{64}"))) { "Invalid trusted artifact SHA" }
            require(file.isFile && file.length() in 1..MAX_FILE_BYTES && !Files.isSymbolicLink(file.toPath())) { "Prepared file is absent or invalid" }
            require(artifactSha256(file, checkCancelled) == expectedArtifactSha) { "Prepared artifact fingerprint mismatch" }
            checkCancelled()
            val db = BundledSQLiteDriver().open(file.absolutePath, SQLITE_OPEN_READONLY)
            try {
                db.execute("PRAGMA query_only=ON", checkCancelled)
                db.execute("PRAGMA trusted_schema=OFF", checkCancelled)
                require(db.scalar("PRAGMA application_id", checkCancelled) == APPLICATION_ID.toLong() && db.scalar("PRAGMA user_version", checkCancelled) == 1L) { "Prepared format version mismatch" }
                checkCancelled()
                val names = mutableMapOf<String, String>()
                db.prepare("SELECT name,type FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%'").use { s ->
                    while (s.step()) { checkCancelled(); names[s.getText(0)] = s.getText(1) }
                }
                require(names == mapOf("prepared_metadata" to "table", "prepared_entries" to "table", "prepared_lookup" to "index")) { "Unexpected prepared schema objects" }
                fun columns(table: String): List<String> = db.prepare("PRAGMA table_info($table)").use { s ->
                    buildList { while (s.step()) { checkCancelled(); add(s.getText(1)) } }
                }
                require(columns("prepared_metadata") == listOf("k", "v") &&
                    columns("prepared_entries") == listOf("ordinal", "sort_key", "word_units", "text_units")) { "Unexpected prepared columns" }
                val meta = mutableMapOf<String, String>()
                db.prepare("SELECT k,v FROM prepared_metadata").use { s -> while (s.step()) { checkCancelled(); meta[s.getText(0)] = s.getText(1) } }
                require(meta.keys == setOf("format", "source_sha256", "parser_version", "key_count", "text_characters", "null_text_count", "blank_text_count", "normalized_key_count", "rows_sha256", "complete")) { "Incomplete prepared metadata" }
                require(meta["format"] == FORMAT && meta["source_sha256"] == sourceSha && meta["parser_version"] == parserVersion && meta["complete"] == "1") { "Prepared identity mismatch or incomplete build" }
                val count = meta.getValue("key_count").toIntOrNull()
                require(count != null && count in 1..MAX_ROWS) { "Invalid prepared key count" }
                val nulls = meta.getValue("null_text_count").toIntOrNull()
                val blanks = meta.getValue("blank_text_count").toIntOrNull()
                val normalizedKeys = meta.getValue("normalized_key_count").toIntOrNull()
                val characters = meta.getValue("text_characters").toLongOrNull()
                require(nulls != null && nulls in 0..count && blanks != null && blanks in 0..count &&
                    nulls.toLong() + blanks <= count && normalizedKeys != null && normalizedKeys in 1..count &&
                    characters != null && characters in 0..count.toLong() * MAX_TEXT_CHARACTERS) { "Invalid prepared metadata counts" }
                val rowsHash = meta.getValue("rows_sha256")
                require(rowsHash.matches(Regex("[a-f0-9]{64}"))) { "Invalid prepared row digest" }
                // Count using the compact lookup index; no definition blobs are decoded here.
                require(db.scalar("SELECT COUNT(*) FROM prepared_entries INDEXED BY prepared_lookup", checkCancelled) == count.toLong()) { "Prepared row count mismatch" }
                checkCancelled()
                return PreparedMdxIndex(db, count, rowsHash)
            } catch (error: Throwable) {
                try { db.close() } catch (closing: Throwable) { if (closing !== error) error.addSuppressed(closing) }
                throw error
            }
        }

        private fun artifactSha256(file: File, checkCancelled: () -> Unit = {}): String {
            checkCancelled()
            val expectedBytes = file.length()
            require(expectedBytes in 1..MAX_FILE_BYTES) { "Invalid prepared fingerprint size" }
            val digest = MessageDigest.getInstance("SHA-256")
            var readBytes = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    readBytes += count
                    require(readBytes <= expectedBytes) { "Prepared artifact changed during fingerprinting" }
                    digest.update(buffer, 0, count)
                    checkCancelled()
                }
            }
            require(readBytes == expectedBytes && file.length() == expectedBytes) { "Prepared artifact changed during fingerprinting" }
            checkCancelled()
            return hex(digest.digest())
        }

        private fun ownsReservation(file: File, marker: ByteArray, allowPartial: Boolean): Boolean = runCatching {
            if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() > marker.size) return@runCatching false
            val actual = file.inputStream().use { it.readNBytes(marker.size + 1) }
            if (allowPartial) actual.size <= marker.size && actual.contentEquals(marker.copyOf(actual.size))
            else actual.contentEquals(marker)
        }.getOrDefault(false)

        private fun validateIdentity(sourceSha: String, parserVersion: String) {
            require(sourceSha.matches(Regex("[a-f0-9]{64}"))) { "Invalid prepared source SHA" }
            require(parserVersion.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}"))) { "Invalid parser version" }
        }

        /** Explicit code-unit encoding preserves even a final unpaired surrogate after take(60000). */
        internal fun utf16Units(value: String): ByteArray = ByteArray(value.length * 2).also { result ->
            value.forEachIndexed { i, char -> result[2 * i] = (char.code ushr 8).toByte(); result[2 * i + 1] = char.code.toByte() }
        }
        private fun fromUtf16Units(bytes: ByteArray): String {
            require(bytes.size % 2 == 0) { "Odd prepared UTF-16 byte count" }
            return CharArray(bytes.size / 2) { i -> (((bytes[2 * i].toInt() and 255) shl 8) or (bytes[2 * i + 1].toInt() and 255)).toChar() }.concatToString()
        }
        internal fun prefixSuccessor(bytes: ByteArray): ByteArray? {
            for (i in bytes.lastIndex downTo 0) if ((bytes[i].toInt() and 255) != 255) {
                return bytes.copyOf(i + 1).also { it[i] = ((it[i].toInt() and 255) + 1).toByte() }
            }
            return null
        }
        private fun addDigest(digest: MessageDigest, ordinal: Int, word: String, text: String?, checkCancelled: () -> Unit = {}) {
            checkCancelled()
            fun number(value: Int) { digest.update(byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())) }
            number(ordinal); number(word.length); digest.update(utf16Units(word))
            checkCancelled()
            number(text?.length ?: -1); if (text != null) digest.update(utf16Units(text))
            checkCancelled()
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun SQLiteConnection.execute(sql: String, checkCancelled: () -> Unit = {}) {
            checkCancelled(); prepare(sql).use { it.step() }; checkCancelled()
        }
        private fun SQLiteConnection.scalar(sql: String, checkCancelled: () -> Unit = {}): Long {
            checkCancelled()
            val result = prepare(sql).use { s -> check(s.step()); s.getLong(0) }
            checkCancelled(); return result
        }
    }
}
