package dev.lexicon.data

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import dev.lexicon.domain.DictionaryEntry
import dev.lexicon.domain.DictionaryStatus
import dev.lexicon.domain.InstalledDictionary
import dev.lexicon.data.mdict.MdxReader
import dev.lexicon.data.mdict.PreparedMdxIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** All dictionary files and the user's selection stay in noBackupFilesDir. */
internal class DictionaryStore(
    private val context: Context,
    private val installPackaged: Boolean = true,
    preparationScope: CoroutineScope? = null,
) {
    private val lock = Mutex()
    private var connection: SQLiteConnection? = null
    private var reader: MdxReader? = null
    private var prepared: PreparedMdxIndex? = null
    private val rejectedPrepared = mutableSetOf<String>()
    private var openedId = ""
    private val folder = File(context.noBackupFilesDir, "dictionary").apply { mkdirs() }
    private val catalogFile = File(folder, "catalog.json")
    private val selectedFile = File(folder, "selected.txt")
    private val preparedCache = PreparedDictionaryCache(File(folder, "prepared"), preparationScope)
    private val json = Json { ignoreUnknownKeys = true }
    private var catalog = mutableListOf<InstalledDictionary>()
    private var selectedId = ""
    private var initialized = false
    private var selectionGeneration = 0L

    private data class PreparationRequest(val file: File?, val sourceSha: String?, val generation: Long)

    /** Snapshot the selected source while locked, then schedule without holding the query lock. */
    private suspend fun <T> access(action: (checkCancelled: () -> Unit) -> T): T = withContext(Dispatchers.IO) {
        val owningJob = coroutineContext[Job]
        val checkCancelled: () -> Unit = { owningJob?.ensureActive() }
        val (result, request) = lock.withLock {
            checkCancelled()
            initialize()
            val value = action(checkCancelled)
            val selected = current()?.takeIf { it.format == "MDX" }
            value to PreparationRequest(selected?.let { File(folder, it.fileName) }, selected?.id, selectionGeneration)
        }
        try { preparedCache.schedule(request.file, request.sourceSha, request.generation) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Optional preparation never makes an installed dictionary unavailable. */ }
        result
    }

    suspend fun status(): DictionaryStatus = access {
        current()?.let(::statusOf) ?: DictionaryStatus()
    }

    suspend fun installed(): List<InstalledDictionary> = access {
        catalog.map { it.copy(selected = it.id == selectedId) }
    }

    suspend fun select(id: String): DictionaryStatus = access { checkCancelled ->
        val dictionary = catalog.firstOrNull { it.id == id } ?: throw IllegalArgumentException("词典尚未安装")
        require(File(folder, dictionary.fileName).isFile) { "词典文件丢失，请重新导入" }
        if (id == selectedId) return@access statusOf(dictionary)
        var replacementReader: MdxReader? = null
        var replacementPrepared: PreparedMdxIndex? = null
        var replacementConnection: SQLiteConnection? = null
        try {
            // Validate once and retain the candidate. A failed open or durable selection
            // write leaves the current dictionary and its parsed-entry cache untouched.
            val file = File(folder, dictionary.fileName)
            require(sha256(file, checkCancelled) == id) { "词典文件已改变，请重新导入" }
            if (dictionary.format == "MDX") {
                val opened = openMdx(file, id, checkCancelled, sourceAlreadyVerified = true)
                replacementReader = opened.reader; replacementPrepared = opened.prepared
            } else {
                replacementConnection = BundledSQLiteDriver().open(file.absolutePath, SQLITE_OPEN_READONLY)
                replacementConnection.exec("PRAGMA query_only=ON")
                replacementConnection.exec("PRAGMA trusted_schema=OFF")
                validate(replacementConnection)
            }
            checkCancelled()
            atomicWrite(selectedFile, id)
            val previousReader = reader
            val previousPrepared = prepared
            val previousConnection = connection
            reader = replacementReader; prepared = replacementPrepared; connection = replacementConnection
            selectedId = id; openedId = id; selectionGeneration++
            replacementReader = null; replacementPrepared = null; replacementConnection = null
            // Ownership has moved after persistence; a close error must not make the
            // published in-memory selection disagree with the durable selection.
            runCatching { previousReader?.close() }
            runCatching { previousPrepared?.close() }
            runCatching { previousConnection?.close() }
            statusOf(dictionary)
        } finally {
            runCatching { replacementReader?.close() }
            runCatching { replacementPrepared?.close() }
            runCatching { replacementConnection?.close() }
        }
    }

    /** Sniff content, not filename: SAF may deliver an arbitrary temporary extension. */
    suspend fun import(file: File): DictionaryStatus = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() in 100L..MdxReader.MAX_FILE_SIZE) { "请选择不超过 256 MB 的 MDX 或 SQLite 词典" }
        access {
            val dictionary = install(file, "导入", file.name)
            setSelected(dictionary.id)
            statusOf(dictionary)
        }
    }

    private fun initialize() {
        if (initialized) return
        catalog = if (catalogFile.exists()) runCatching { json.decodeFromString<List<InstalledDictionary>>(catalogFile.readText()) }.getOrDefault(emptyList()).toMutableList() else mutableListOf()
        catalog.removeAll { !safeName(it.fileName) || !File(folder, it.fileName).isFile }
        selectedId = selectedFile.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        val old = File(folder, "longman-6.sqlite")
        if (old.isFile && catalog.none { it.fileName == old.name }) {
            runCatching { install(old, "导入", old.name) }
        }
        // Gradle discovers ./data/dic/*.mdx. The generated manifest changes when file bytes change.
        val packaged = if (!installPackaged) emptyList() else runCatching { context.assets.open("dictionaries/catalog.tsv").bufferedReader().use { it.readLines() } }.getOrDefault(emptyList())
        packaged.take(100).forEach { line ->
            val fields = line.split('\t', limit = 2)
            if (fields.size == 2 && Regex("[a-f0-9]{64}").matches(fields[0]) && safeName(fields[1]) && catalog.none { it.id == fields[0] }) {
                val temporary = File(folder, ".asset-${UUID.randomUUID()}.mdx")
                try {
                    context.assets.open("dictionaries/${fields[1]}").use { source -> temporary.outputStream().use { output ->
                        val bytes = ByteArray(64 * 1024); var total = 0L
                        while (true) { val read = source.read(bytes); if (read < 0) break; total += read; require(total <= MdxReader.MAX_FILE_SIZE); output.write(bytes, 0, read) }
                        output.fd.sync()
                    } }
                    require(sha256(temporary) == fields[0]) { "内置词典摘要不一致" }
                    install(temporary, "内置", fields[1])
                } catch (_: Exception) { /* A broken optional asset must not hide existing dictionaries. */ }
                finally { temporary.delete() }
            }
        }
        if (catalog.none { it.id == selectedId }) selectedId = catalog.firstOrNull()?.id.orEmpty()
        if (selectedId.isNotEmpty()) atomicWrite(selectedFile, selectedId)
        initialized = true
        selectionGeneration++
    }

    private fun install(file: File, origin: String, originalName: String): InstalledDictionary {
        val hash = sha256(file)
        catalog.firstOrNull { it.id == hash }?.let { return it }
        val sqlite = file.inputStream().use { it.readNBytes(16).toString(Charsets.ISO_8859_1) } == "SQLite format 3\u0000"
        val temporary = File(folder, ".import-${UUID.randomUUID()}")
        try {
            file.inputStream().use { source -> temporary.outputStream().use { output -> source.copyTo(output); output.fd.sync() } }
            val info = if (sqlite) {
                BundledSQLiteDriver().open(temporary.absolutePath, SQLITE_OPEN_READONLY).use { db ->
                    db.exec("PRAGMA query_only=ON"); db.exec("PRAGMA trusted_schema=OFF"); validate(db)
                    val count = db.prepare("SELECT COUNT(*) FROM entries").use { it.step(); it.getLong(0) }
                    val label = db.prepare("SELECT value FROM metadata WHERE key='source_name' LIMIT 1").use { statement ->
                        if (statement.step()) statement.getText(0).trim().take(300) else ""
                    }.ifBlank { "Longman Dictionary of Contemporary English, 6th Edition" }
                    InstalledDictionary(hash, label, "SQLite", "en-en", count, false, origin, "$hash.sqlite")
                }
            } else {
                MdxReader(temporary).use { mdx ->
                    mdx.validateAllRecords()
                    val meta = mdx.metadata
                    InstalledDictionary(hash, meta.title.ifBlank { originalName.substringBeforeLast('.') }, "MDX", meta.language, meta.entries, false, origin, "$hash.mdx")
                }
            }
            val target = File(folder, info.fileName)
            move(temporary, target)
            catalog.add(info)
            try { atomicWrite(catalogFile, json.encodeToString(catalog)) }
            catch (e: Exception) { catalog.remove(info); target.delete(); throw e }
            return info
        } finally { temporary.delete() }
    }

    suspend fun lookup(raw: String): List<DictionaryEntry> = withContext(Dispatchers.IO) {
        val query = MdxReader.normalize(raw)
        if (query.isBlank() || query.length > 200) return@withContext emptyList()
        access { checkCancelled ->
            val current = current() ?: return@access emptyList()
            if (openedId != current.id) {
                closeOpen()
                val file = File(folder, current.fileName)
                if (current.format == "MDX") {
                    val opened = openMdx(file, current.id, checkCancelled)
                    reader = opened.reader; prepared = opened.prepared
                } else {
                    val opened = BundledSQLiteDriver().open(file.absolutePath, SQLITE_OPEN_READONLY)
                    try {
                        opened.exec("PRAGMA query_only=ON"); opened.exec("PRAGMA trusted_schema=OFF")
                        connection = opened
                    } catch (failure: Exception) {
                        runCatching { opened.close() }; throw failure
                    }
                }
                openedId = current.id
            }
            prepared?.let { index ->
                try {
                    checkCancelled()
                    return@access index.lookup(query).map { DictionaryEntry(word = it.word, translation = "", definition = it.text) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed prepared read gets one same-source raw retry. Never repeatedly
                    // reopen a broken accelerator on subsequent words in this process.
                    rejectedPrepared.add(current.id)
                    closeOpen()
                    checkCancelled()
                    reader = MdxReader(File(folder, current.fileName))
                    openedId = current.id
                }
            }
            reader?.let { mdx -> return@access mdx.lookup(query).map { DictionaryEntry(word = it.word, translation = "", definition = it.text) } }
            val db = connection ?: return@access emptyList()
            fun find(sql: String, input: String): List<DictionaryEntry> = db.prepare(sql).use { stmt ->
                stmt.bindText(1, input); buildList { while (stmt.step()) add(stmt.entry()) }
            }
            val fields = "e.word,e.translation,e.definition,e.phonetic,e.tags,e.frequency"
            val direct = find("SELECT $fields FROM entries e WHERE e.normalized=? LIMIT 8", query)
            if (direct.isNotEmpty()) return@access direct
            val forms = find("SELECT $fields FROM entries e JOIN forms f ON e.normalized=f.lemma WHERE f.form=? LIMIT 8", query)
            if (forms.isNotEmpty()) return@access forms.distinctBy { it.word }
            val tokens = Regex("[a-zA-Z]+(?:[-'][a-zA-Z]+)*").findAll(query).map { it.value }.take(4).toList()
            if (tokens.isEmpty()) return@access emptyList()
            val match = "word : (" + tokens.joinToString(" AND ") { "\"${it.replace("\"", "\"\"")}\"*" } + ")"
            find("SELECT $fields FROM entries_fts f JOIN entries e ON e.rowid=f.rowid WHERE entries_fts MATCH ? ORDER BY rank LIMIT 8", match)
        }
    }

    private data class OpenMdx(val reader: MdxReader? = null, val prepared: PreparedMdxIndex? = null)
    private fun openMdx(file: File, id: String, checkCancelled: () -> Unit, sourceAlreadyVerified: Boolean = false): OpenMdx {
        val index = if (id in rejectedPrepared) null else preparedCache.tryOpen(file, id, checkCancelled, sourceAlreadyVerified)
        if (index != null) return OpenMdx(prepared = index)
        checkCancelled()
        return OpenMdx(reader = MdxReader(file))
    }

    private fun current() = catalog.firstOrNull { it.id == selectedId }
    private fun statusOf(item: InstalledDictionary) = DictionaryStatus(true, item.name, item.entryCount,
        "${item.name} · ${item.entryCount} 个词条 · ${item.format}", item.id, item.format, item.language, item.origin)
    private fun closeOpen() {
        val oldConnection = connection; val oldReader = reader; val oldPrepared = prepared
        connection = null; reader = null; prepared = null; openedId = ""
        runCatching { oldConnection?.close() }; runCatching { oldReader?.close() }; runCatching { oldPrepared?.close() }
    }
    private fun setSelected(id: String) {
        if (id == selectedId) return
        atomicWrite(selectedFile, id); closeOpen(); selectedId = id; selectionGeneration++
    }
    private fun safeName(name: String) = name.isNotBlank() && name.length <= 300 && name != "." && name != ".." && name.none { it in "/\\\t\r\n" }
    private fun sha256(file: File, checkCancelled: () -> Unit = {}): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { checkCancelled(); val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun atomicWrite(file: File, text: String) {
        val temporary = File(folder, ".metadata-${UUID.randomUUID()}")
        try { temporary.outputStream().use { it.write(text.toByteArray()); it.fd.sync() }; move(temporary, file) }
        finally { temporary.delete() }
    }
    private fun move(from: File, to: File) {
        try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun validate(db: SQLiteConnection) {
        val schema = mutableMapOf<String, Pair<String, String>>()
        db.prepare("SELECT name,type,COALESCE(sql,'') FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%'").use { s ->
            while (s.step()) schema[s.getText(0)] = s.getText(1) to s.getText(2)
        }
        require(schema.values.none { it.first in setOf("view", "trigger") }) { "词库不能包含视图或触发器" }
        for (table in listOf("metadata", "entries", "forms", "entries_fts")) require(schema[table]?.first == "table") { "词库缺少 $table 表" }
        val fts = schema.getValue("entries_fts").second
        require(Regex("USING\\s+fts5\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(fts)) { "词库缺少 FTS5 索引" }
        require(schema.filterValues { it.second.contains("VIRTUAL TABLE", true) }.keys == setOf("entries_fts")) { "不支持此词库的虚拟表类型" }
        fun columns(table: String): Set<String> = db.prepare("PRAGMA table_info($table)").use { s -> buildSet { while (s.step()) add(s.getText(1)) } }
        require(columns("metadata").containsAll(setOf("key", "value"))) { "词库元数据格式无效" }
        require(columns("entries").containsAll(setOf("word", "normalized", "translation", "definition", "phonetic", "tags", "frequency"))) { "词条字段不完整" }
        require(columns("forms").containsAll(setOf("form", "lemma"))) { "词形表字段不完整" }
        val metadata = mutableMapOf<String, String>()
        db.prepare("SELECT key,value FROM metadata").use { s -> while (s.step()) metadata[s.getText(0)] = s.getText(1) }
        require(metadata["dictionary_id"] == "longman-6" && metadata["format_version"] == "1" && metadata["language"] == "en-en") {
            "需要 Longman 6 英英词库（dictionary_id=longman-6, language=en-en, format_version=1）"
        }
        db.prepare("PRAGMA integrity_check").use { s -> require(s.step() && s.getText(0) == "ok") { "词库完整性检查未通过" } }
        db.prepare("SELECT COUNT(*),SUM(CASE WHEN definition IS NULL OR length(trim(definition))=0 THEN 1 ELSE 0 END) FROM entries").use { s ->
            require(s.step() && s.getLong(0) > 0 && s.getLong(1) == 0L) { "词库为空或缺少英英释义" }
        }
        db.prepare("SELECT word FROM entries_fts WHERE entries_fts MATCH 'word : \"test\"*' LIMIT 1").use { it.step() }
    }

    private fun SQLiteStatement.entry() = DictionaryEntry(
        // The UI must not mistake a legacy bilingual column for Longman's English definitions.
        word = getText(0), translation = "",
        definition = if (isNull(2)) "" else getText(2), phonetic = if (isNull(3)) "" else getText(3),
        tags = if (isNull(4)) "" else getText(4), frequency = if (isNull(5)) null else getLong(5).toInt(),
    )
}
