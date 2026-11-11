package dev.lexicon.data

import android.content.Context
import android.content.ContextWrapper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.data.mdict.MdxReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.Adler32
import java.util.zip.Deflater

/** Synthetic fixtures only: no personal dictionary, learning database, network or API. */
@RunWith(AndroidJUnit4::class)
class DictionarySelectionIntegrationTest {
    @Test fun recordCorruptionAfterInstallationDoesNotReplaceTheActiveDictionary() = runBlocking {
        withArea {
            val store = newStore()
            val a = store.import(mdx("A", "Meaning from A.").file)
            val b = store.import(mdx("B", "Meaning from B.").file)
            store.select(a.id)
            store.lookup("shared")
            val activeA = requireNotNull(reader(store))
            val file = installedFile(store, b.id)
            val original = file.readBytes()
            try {
                val bytes = original.copyOf()
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                file.writeBytes(bytes)
                // Header/key indexes still open, but catalog identity is no longer valid.
                MdxReader(file).use { assertEquals(2L, it.metadata.entries) }
                expectFailure { store.select(b.id) }
                assertEquals(a.id, selectionFile.readText())
                assertEquals(a.id, store.status().id)
                assertSame(activeA, reader(store))
                assertEquals("Meaning from A.", store.lookup("shared").single().definition)
            } finally { file.writeBytes(original) }
        }
    }
    @Test
    fun selectingSameMdxKeepsReaderAndSanitizedEntry() = runBlocking {
        withArea {
            val store = newStore()
            val selected = store.import(mdx("A", "Meaning from A.").file)
            assertEquals("Meaning from A.", store.lookup("shared").single().definition)
            val reader = requireNotNull(reader(store))
            val entries = entryCache(reader)
            assertEquals(1, entries.size)
            val cached = entries.values.single()
            val selectionBytes = selectionFile.readBytes()

            assertEquals(selected.id, store.select(selected.id).id)

            assertSame(reader, reader(store))
            assertSame(entries, entryCache(reader))
            assertSame(cached, entryCache(reader).values.single())
            assertArrayEquals(selectionBytes, selectionFile.readBytes())
            assertEquals("Meaning from A.", store.lookup("SHARED").single().definition)
            assertSame(cached, entryCache(reader).values.single())
            assertTrue(input(reader).fd.valid())
        }
    }

    @Test
    fun switchingMdxReusesValidatedCandidateAndNeverMixesDefinitions() = runBlocking {
        withArea {
            val store = newStore()
            val a = store.import(mdx("A", "Meaning from A.").file)
            val b = store.import(mdx("B", "Meaning from B.").file)
            store.select(a.id)
            assertEquals("Meaning from A.", store.lookup("shared").single().definition)
            val oldA = requireNotNull(reader(store))

            store.select(b.id)
            val candidateB = requireNotNull(reader(store))
            assertNotSame(oldA, candidateB)
            assertReaderClosed(oldA)
            assertEquals(b.id, selectionFile.readText())
            assertEquals("Meaning from B.", store.lookup("shared").single().definition)
            assertSame(candidateB, reader(store))

            store.select(a.id)
            val candidateA = requireNotNull(reader(store))
            assertNotSame(candidateB, candidateA)
            assertReaderClosed(candidateB)
            assertEquals("Meaning from A.", store.lookup("shared").single().definition)
            assertSame(candidateA, reader(store))
            assertEquals(a.id, store.status().id)
        }
    }

    @Test
    fun brokenMdxCandidateLeavesCurrentReaderCatalogAndSelectionUsable() = runBlocking {
        withArea {
            val store = newStore()
            val a = store.import(mdx("A", "Meaning from A.").file)
            val b = store.import(mdx("B", "Meaning from B.").file)
            store.select(a.id)
            store.lookup("shared")
            val activeA = requireNotNull(reader(store))
            val cachedA = entryCache(activeA).values.single()
            val catalogBefore = catalogFile.readBytes()
            val candidate = installedFile(store, b.id)
            val original = candidate.readBytes()
            try {
                candidate.writeBytes(original.copyOf(12))
                expectFailure { store.select(b.id) }
                assertEquals(a.id, store.status().id)
                assertSame(activeA, reader(store))
                assertSame(cachedA, entryCache(activeA).values.single())
                assertEquals("Meaning from A.", store.lookup("shared").single().definition)
                assertEquals(a.id, selectionFile.readText())
                assertArrayEquals(catalogBefore, catalogFile.readBytes())
                assertEquals(setOf(a.id, b.id), store.installed().map { it.id }.toSet())
            } finally {
                candidate.writeBytes(original)
            }
        }
    }

    @Test
    fun selectionPersistenceFailureKeepsCurrentReaderAndCanRecover() = runBlocking {
        withArea {
            val store = newStore()
            val a = store.import(mdx("A", "Meaning from A.").file)
            val b = store.import(mdx("B", "Meaning from B.").file)
            store.select(a.id)
            store.lookup("shared")
            val activeA = requireNotNull(reader(store))
            val cachedA = entryCache(activeA).values.single()
            val catalogBefore = catalogFile.readBytes()
            val selectedBefore = selectionFile.readBytes()
            check(selectionFile.delete())
            check(selectionFile.mkdir())
            // A nonempty directory deterministically rejects file replacement on Android.
            File(selectionFile, "owned-write-failure").writeText("synthetic fixture")
            try {
                expectFailure { store.select(b.id) }
                assertEquals(a.id, store.status().id)
                assertSame(activeA, reader(store))
                assertSame(cachedA, entryCache(activeA).values.single())
                assertEquals("Meaning from A.", store.lookup("shared").single().definition)
                assertArrayEquals(catalogBefore, catalogFile.readBytes())
            } finally {
                check(selectionFile.deleteRecursively())
                selectionFile.writeBytes(selectedBefore)
            }
            val reopened = newStore()
            assertEquals(a.id, reopened.status().id)
            assertEquals("Meaning from A.", reopened.lookup("shared").single().definition)
            store.select(b.id)
            val candidateB = requireNotNull(reader(store))
            assertEquals("Meaning from B.", store.lookup("shared").single().definition)
            assertSame(candidateB, reader(store))
        }
    }

    @Test
    fun importingBadRecordChecksumPreservesCatalogAndCurrentDictionary() = runBlocking {
        withArea {
            val store = newStore()
            val a = store.import(mdx("A", "Meaning from A.").file)
            store.lookup("shared")
            val activeA = requireNotNull(reader(store))
            val catalogBefore = catalogFile.readBytes()
            val selectedBefore = selectionFile.readBytes()
            val filesBefore = dictionaryFolder.list().orEmpty().toSet()
            val damaged = mdx("damaged", "This record must fail its checksum.")
            val bytes = damaged.file.readBytes()
            bytes[damaged.recordChecksumOffset] = (bytes[damaged.recordChecksumOffset].toInt() xor 1).toByte()
            damaged.file.writeBytes(bytes)
            // The header/key index remain valid; failure belongs to record validation.
            MdxReader(damaged.file).use { candidate ->
                assertEquals(2L, candidate.metadata.entries)
                expectFailure { candidate.validateAllRecords() }
            }

            expectFailure { store.import(damaged.file) }

            assertEquals(a.id, store.status().id)
            assertSame(activeA, reader(store))
            assertEquals("Meaning from A.", store.lookup("shared").single().definition)
            assertArrayEquals(catalogBefore, catalogFile.readBytes())
            assertArrayEquals(selectedBefore, selectionFile.readBytes())
            assertEquals(listOf(a.id), store.installed().map { it.id })
            assertEquals(filesBefore, dictionaryFolder.list().orEmpty().toSet())
        }
    }

    @Test
    fun mdxAndSqliteSwitchingReusesHandlesAndRestoresSelectionAfterRestart() = runBlocking {
        withArea {
            val store = newStore()
            val a = store.import(mdx("A", "Meaning from A.").file)
            val sql = store.import(sqlite("SQL", "Meaning from SQLite."))
            assertEquals("Meaning from SQLite.", store.lookup("shared").single().definition)
            val initialSql = requireNotNull(connection(store))
            store.select(sql.id)
            assertSame(initialSql, connection(store))

            store.select(a.id)
            val candidateA = requireNotNull(reader(store))
            assertNull(connection(store))
            expectFailure { initialSql.prepare("SELECT 1").use { it.step() } }
            assertEquals("Meaning from A.", store.lookup("shared").single().definition)
            assertSame(candidateA, reader(store))

            store.select(sql.id)
            val candidateSql = requireNotNull(connection(store))
            assertNull(reader(store))
            assertReaderClosed(candidateA)
            assertEquals("Meaning from SQLite.", store.lookup("shared").single().definition)
            assertEquals("Meaning from SQLite.", store.lookup("shareds").single().definition)
            assertEquals(2, store.lookup("sha").size)
            assertSame(candidateSql, connection(store))
            closeOwnedStore(store)

            val reopened = newStore()
            assertEquals(sql.id, reopened.status().id)
            assertEquals("SQLite", reopened.status().format)
            assertEquals("Meaning from SQLite.", reopened.lookup("shared").single().definition)
            assertEquals(setOf(a.id, sql.id), reopened.installed().map { it.id }.toSet())
            reopened.select(a.id)
            closeOwnedStore(reopened)

            val reopenedAgain = newStore()
            assertEquals(a.id, reopenedAgain.status().id)
            assertEquals("MDX", reopenedAgain.status().format)
            assertEquals("Meaning from A.", reopenedAgain.lookup("shared").single().definition)
        }
    }

    @Test
    fun invalidSqliteCandidateRetainsActiveMdxReader() = runBlocking {
        withArea {
            val store = newStore()
            val a = store.import(mdx("A", "Meaning from A.").file)
            val sql = store.import(sqlite("SQL", "Meaning from SQLite."))
            store.select(a.id)
            store.lookup("shared")
            val activeA = requireNotNull(reader(store))
            val catalogBefore = catalogFile.readBytes()
            BundledSQLiteDriver().open(installedFile(store, sql.id).absolutePath).use {
                it.exec("UPDATE metadata SET value='invalid-fixture' WHERE key='dictionary_id'")
            }

            expectFailure { store.select(sql.id) }

            assertEquals(a.id, store.status().id)
            assertSame(activeA, reader(store))
            assertNull(connection(store))
            assertEquals("Meaning from A.", store.lookup("shared").single().definition)
            assertEquals(a.id, selectionFile.readText())
            assertArrayEquals(catalogBefore, catalogFile.readBytes())
        }
    }

    private suspend fun withArea(block: suspend TestArea.() -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "dictionary-selection-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir(): File = File(root, "no_backup").apply { check(isDirectory || mkdirs()) }
            override fun getFilesDir(): File = File(root, "files").apply { check(isDirectory || mkdirs()) }
            override fun getCacheDir(): File = File(root, "cache").apply { check(isDirectory || mkdirs()) }
            override fun getDatabasePath(name: String): File = File(File(root, "databases").apply { check(isDirectory || mkdirs()) }, name)
        }
        val area = TestArea(root, context)
        try {
            area.block()
        } finally {
            // No handles belonging to the application or another test are accessed.
            val failures = area.stores.mapNotNull { runCatching { closeOwnedStore(it) }.exceptionOrNull() }
            val removed = root.deleteRecursively()
            check(failures.isEmpty() && removed) { "Failed to clean owned dictionary fixtures" }
        }
    }

    private class TestArea(val root: File, val context: Context) {
        val stores = mutableListOf<DictionaryStore>()
        val dictionaryFolder get() = File(context.noBackupFilesDir, "dictionary")
        val selectionFile get() = File(dictionaryFolder, "selected.txt")
        val catalogFile get() = File(dictionaryFolder, "catalog.json")
        fun newStore() = DictionaryStore(context, installPackaged = false).also(stores::add)
        suspend fun installedFile(store: DictionaryStore, id: String) =
            File(dictionaryFolder, store.installed().single { it.id == id }.fileName)

        fun sqlite(label: String, definition: String): File {
            val file = File(root, "synthetic-$label-${UUID.randomUUID()}.sqlite")
            BundledSQLiteDriver().open(file.absolutePath).use { db ->
                db.exec("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT)")
                db.exec("INSERT INTO metadata VALUES ('dictionary_id','longman-6'),('format_version','1'),('language','en-en'),('source_name','Synthetic $label'),('test_fixture','synthetic content only')")
                db.exec("CREATE TABLE entries (word TEXT PRIMARY KEY, normalized TEXT UNIQUE, translation TEXT, definition TEXT, phonetic TEXT, tags TEXT, frequency INTEGER)")
                db.prepare("INSERT INTO entries VALUES ('shared','shared','',?,'','',NULL),('shadow','shadow','','A second synthetic definition.','','',NULL)").use {
                    it.bindText(1, definition)
                    it.step()
                }
                db.exec("CREATE TABLE forms (form TEXT, lemma TEXT, PRIMARY KEY(form,lemma))")
                db.exec("INSERT INTO forms VALUES ('shareds','shared')")
                db.exec("CREATE VIRTUAL TABLE entries_fts USING fts5(word,definition,translation,content='entries',content_rowid='rowid')")
                db.exec("INSERT INTO entries_fts(entries_fts) VALUES ('rebuild')")
            }
            return file
        }

        /** MDX 2.2 UTF-8, one compressed key block and one compressed record block. */
        fun mdx(label: String, definition: String): MdxFixture {
            val entries = linkedMapOf("shared" to "<p>$definition</p>", "zebra" to "Another synthetic meaning from $label.")
            val header = ("<Dictionary GeneratedByEngineVersion=\"2.2\" RequiredEngineVersion=\"2.2\" Encrypted=\"No\" Encoding=\"UTF-8\" Title=\"Synthetic $label 英英 dictionary\"/>\u0000").toByteArray(Charsets.UTF_16LE)
            fun bytes(block: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { sink -> DataOutputStream(sink).use(block) }.toByteArray()
            fun packed(data: ByteArray): ByteArray = bytes { out ->
                out.write(byteArrayOf(2, 0, 0, 0))
                out.writeInt(Adler32().apply { update(data) }.value.toInt())
                val compressor = Deflater()
                try {
                    compressor.setInput(data)
                    compressor.finish()
                    val buffer = ByteArray(1024)
                    while (!compressor.finished()) out.write(buffer, 0, compressor.deflate(buffer))
                } finally {
                    compressor.end()
                }
            }
            val record = ByteArrayOutputStream()
            val keyData = bytes { out ->
                entries.forEach { (word, meaning) ->
                    out.writeLong(record.size().toLong())
                    out.write(word.toByteArray(Charsets.UTF_8))
                    out.writeByte(0)
                    record.write((meaning + "\u0000").toByteArray(Charsets.UTF_8))
                }
            }
            val keyBlock = packed(keyData)
            val keyInfo = bytes { out ->
                out.writeLong(entries.size.toLong())
                listOf(entries.keys.first(), entries.keys.last()).forEach { word ->
                    val wordBytes = word.toByteArray(Charsets.UTF_8)
                    out.writeShort(wordBytes.size)
                    out.write(wordBytes)
                    out.writeByte(0)
                }
                out.writeLong(keyBlock.size.toLong())
                out.writeLong(keyData.size.toLong())
            }
            val storedInfo = packed(keyInfo)
            val keyHeader = bytes { out ->
                out.writeLong(1)
                out.writeLong(entries.size.toLong())
                out.writeLong(keyInfo.size.toLong())
                out.writeLong(storedInfo.size.toLong())
                out.writeLong(keyBlock.size.toLong())
            }
            val recordBlock = packed(record.toByteArray())
            var checksumOffset = -1
            val contents = bytes { out ->
                out.writeInt(header.size)
                out.write(header)
                out.writeInt(Integer.reverseBytes(Adler32().apply { update(header) }.value.toInt()))
                out.write(keyHeader)
                out.writeInt(Adler32().apply { update(keyHeader) }.value.toInt())
                out.write(storedInfo)
                out.write(keyBlock)
                out.writeLong(1)
                out.writeLong(entries.size.toLong())
                out.writeLong(16)
                out.writeLong(recordBlock.size.toLong())
                out.writeLong(recordBlock.size.toLong())
                out.writeLong(record.size().toLong())
                checksumOffset = out.size() + 4
                out.write(recordBlock)
            }
            return MdxFixture(File(root, "synthetic-$label-${UUID.randomUUID()}.mdx").apply { writeBytes(contents) }, checksumOffset)
        }
    }

    private data class MdxFixture(val file: File, val recordChecksumOffset: Int)
    private suspend fun expectFailure(block: suspend () -> Unit) {
        var failure: Throwable? = null
        try { block() } catch (error: Exception) { failure = error }
        assertNotNull("Expected candidate operation to fail", failure)
    }
    private fun reader(store: DictionaryStore) = field(store, "reader") as MdxReader?
    private fun connection(store: DictionaryStore) = field(store, "connection") as SQLiteConnection?
    private fun entryCache(reader: MdxReader) = field(reader, "entries") as Map<*, *>
    private fun input(reader: MdxReader) = field(reader, "input") as RandomAccessFile
    private fun assertReaderClosed(reader: MdxReader) {
        val handle = input(reader)
        // Android can lazily create a channel object after RandomAccessFile.close().
        // Check the descriptor and real I/O instead of the new channel's isOpen flag.
        assertFalse("Old reader file descriptor remains valid", handle.fd.valid())
        assertThrows(IOException::class.java) { handle.seek(0L) }
        assertThrows(IOException::class.java) { handle.read() }
    }
    private fun field(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
    private fun closeOwnedStore(store: DictionaryStore) {
        DictionaryStore::class.java.getDeclaredMethod("closeOpen").apply { isAccessible = true }.invoke(store)
    }
}
