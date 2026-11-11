package dev.lexicon.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.lexicon.data.mdict.PreparedDictionaryFormat
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.Adler32
import java.util.zip.Deflater

/** Every input and writable directory belongs to this synthetic test, never the user's store. */
internal class PreparedDictionaryFixture : Closeable {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    val root = File(base.cacheDir, "prepared-store-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
    val context = object : ContextWrapper(base) {
        override fun getNoBackupFilesDir() = File(root, "no_backup").apply { check(isDirectory || mkdirs()) }
        override fun getCacheDir() = File(root, "cache").apply { check(isDirectory || mkdirs()) }
    }
    val folder get() = File(context.noBackupFilesDir, "dictionary").apply { check(isDirectory || mkdirs()) }
    val preparedFolder get() = File(folder, "prepared")
    val selectedFile get() = File(folder, "selected.txt")
    val catalogFile get() = File(folder, "catalog.json")
    private val stores = mutableListOf<DictionaryStore>()

    fun store() = DictionaryStore(context, installPackaged = false).also(stores::add)
    fun cache() = PreparedDictionaryCache(preparedFolder)
    fun area(sha: String) = File(File(preparedFolder, sha), PreparedDictionaryFormat.PARSER_VERSION)
    fun receipt(sha: String) = File(area(sha), PreparedDictionaryCache.RECEIPT_FILE)
    suspend fun installed(store: DictionaryStore, sha: String) = File(folder, store.installed().single { it.id == sha }.fileName)
    fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
    fun closeStore(store: DictionaryStore) {
        DictionaryStore::class.java.getDeclaredMethod("closeOpen").apply { isAccessible = true }.invoke(store)
    }

    fun sqlite(definition: String): File {
        val file = File(root, "synthetic-${UUID.randomUUID()}.sqlite")
        BundledSQLiteDriver().open(file.absolutePath).use { db ->
            fun sql(text: String) { db.prepare(text).use { it.step() } }
            sql("CREATE TABLE metadata (key TEXT PRIMARY KEY,value TEXT)")
            sql("INSERT INTO metadata VALUES ('dictionary_id','longman-6'),('format_version','1'),('language','en-en'),('source_name','Synthetic SQLite')")
            sql("CREATE TABLE entries (word TEXT PRIMARY KEY,normalized TEXT UNIQUE,translation TEXT,definition TEXT,phonetic TEXT,tags TEXT,frequency INTEGER)")
            db.prepare("INSERT INTO entries VALUES ('shared','shared','',?,'','',NULL)").use { it.bindText(1, definition); it.step() }
            sql("CREATE TABLE forms (form TEXT,lemma TEXT,PRIMARY KEY(form,lemma))")
            sql("INSERT INTO forms VALUES ('shareds','shared')")
            sql("CREATE VIRTUAL TABLE entries_fts USING fts5(word,definition,translation,content='entries',content_rowid='rowid')")
            sql("INSERT INTO entries_fts(entries_fts) VALUES ('rebuild')")
        }
        return file
    }

    /** MDX 2.2, one zlib key block and one record block, matching the existing integration fixture. */
    fun mdx(label: String, definition: String): File {
        val rows = linkedMapOf("shared" to "<p>$definition</p>", "shadow" to "Another synthetic meaning from $label.")
        fun bytes(block: (DataOutputStream) -> Unit) = ByteArrayOutputStream().also { output -> DataOutputStream(output).use(block) }.toByteArray()
        fun packed(data: ByteArray) = bytes { output ->
            output.write(byteArrayOf(2, 0, 0, 0))
            output.writeInt(Adler32().apply { update(data) }.value.toInt())
            val compressor = Deflater()
            try {
                compressor.setInput(data); compressor.finish()
                val buffer = ByteArray(1024)
                while (!compressor.finished()) output.write(buffer, 0, compressor.deflate(buffer))
            } finally { compressor.end() }
        }
        val record = ByteArrayOutputStream()
        val keys = bytes { output -> rows.forEach { (word, text) ->
            output.writeLong(record.size().toLong()); output.write(word.toByteArray()); output.writeByte(0)
            record.write((text + "\u0000").toByteArray())
        } }
        val keyBlock = packed(keys)
        val info = bytes { output ->
            output.writeLong(rows.size.toLong())
            listOf(rows.keys.first(), rows.keys.last()).forEach { word ->
                output.writeShort(word.toByteArray().size); output.write(word.toByteArray()); output.writeByte(0)
            }
            output.writeLong(keyBlock.size.toLong()); output.writeLong(keys.size.toLong())
        }
        val compressedInfo = packed(info)
        val keyHeader = bytes { output ->
            listOf(1L, rows.size.toLong(), info.size.toLong(), compressedInfo.size.toLong(), keyBlock.size.toLong()).forEach(output::writeLong)
        }
        val recordBlock = packed(record.toByteArray())
        val header = ("<Dictionary GeneratedByEngineVersion=\"2.2\" Encrypted=\"No\" Encoding=\"UTF-8\" Title=\"Synthetic $label 英英\"/>\u0000").toByteArray(Charsets.UTF_16LE)
        val contents = bytes { output ->
            output.writeInt(header.size); output.write(header)
            output.writeInt(Integer.reverseBytes(Adler32().apply { update(header) }.value.toInt()))
            output.write(keyHeader); output.writeInt(Adler32().apply { update(keyHeader) }.value.toInt())
            output.write(compressedInfo); output.write(keyBlock)
            listOf(1L, rows.size.toLong(), 16L, recordBlock.size.toLong(), recordBlock.size.toLong(), record.size().toLong()).forEach(output::writeLong)
            output.write(recordBlock)
        }
        return File(root, "synthetic-${UUID.randomUUID()}.mdx").apply { writeBytes(contents) }
    }

    override fun close() {
        try { stores.forEach(::closeStore) }
        finally { check(root.deleteRecursively()) { "Owned prepared dictionary test cleanup failed" } }
    }
}
