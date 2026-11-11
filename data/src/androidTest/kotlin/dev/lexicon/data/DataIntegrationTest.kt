package dev.lexicon.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class DataIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun reviewCompareAndSetAndBodyExpiryPreserveLearningRecords() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO).build()
        try {
            val dao = db.dao()
            dao.putCapture(CaptureRow("capture", 1, "{}"))
            dao.putCard(CardRow("card", "capture", 100, 0, 1, "before"))
            dao.putBody(BodyRow("article", 1, "{}"))
            dao.expireBodies(2)
            assertNull(dao.body("article", 0))
            assertNotNull(dao.capture("capture")); assertNotNull(dao.card("card"))
            val next = CardRow("card", "capture", 1000, 1, 1, "after")
            assertTrue(dao.applyReview(next, 0, ReviewRow("log1", "card", 50, "{}")))
            assertFalse(dao.applyReview(next, 0, ReviewRow("log2", "card", 50, "{}")))
            assertEquals(1, dao.card("card")!!.reps)
        } finally { db.close() }
    }

    @Test fun encryptedKeyDoesNotContainPlaintext() {
        val folder = File(context.cacheDir, "key-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }
        val store = ApiKeyStore(isolated)
        val value = "instrumentation-key-${UUID.randomUUID()}"
        try {
            store.save(value)
            assertEquals(value, store.read())
            val bytes = File(folder, "deepseek-key.aesgcm").readBytes()
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(value))
            store.save("replacement-${UUID.randomUUID()}")
            assertNotEquals(value, store.read())
        } finally { store.clear(); folder.deleteRecursively() }
        assertNull(store.read())
    }

    @Test fun longmanImportValidatesBeforeReplacingAndSupportsExactFormsAndFts() = runBlocking {
        // Isolated synthetic SQLite fixture; packaged personal dictionaries are tested separately.
        val folder = File(context.cacheDir, "dictionary-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }
        val store = DictionaryStore(isolated, installPackaged = false)
        try {
            assertFalse(store.status().ready)
            assertTrue(store.lookup("testword").isEmpty())
            val fixture = File(folder, "synthetic.sqlite")
            BundledSQLiteDriver().open(fixture.absolutePath).use { db ->
                db.exec("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT)")
                db.exec("INSERT INTO metadata VALUES ('dictionary_id','longman-6'),('format_version','1'),('language','en-en'),('test_fixture','synthetic—not real Longman content')")
                db.exec("CREATE TABLE entries (word TEXT PRIMARY KEY, normalized TEXT UNIQUE, translation TEXT, definition TEXT, phonetic TEXT, tags TEXT, frequency INTEGER)")
                db.exec("INSERT INTO entries VALUES ('testword','testword','','A synthetic test definition.','','',NULL),('testphrase','testphrase','','Another synthetic definition.','','',NULL)")
                db.exec("CREATE TABLE forms (form TEXT, lemma TEXT, PRIMARY KEY(form,lemma))")
                db.exec("INSERT INTO forms VALUES ('testwords','testword')")
                db.exec("CREATE VIRTUAL TABLE entries_fts USING fts5(word,definition,translation,content='entries',content_rowid='rowid')")
                db.exec("INSERT INTO entries_fts(entries_fts) VALUES ('rebuild')")
            }
            val initial = store.import(fixture)
            assertEquals(2, initial.entryCount)
            assertEquals(initial.id, store.import(fixture).id)
            assertEquals(1, store.installed().size)
            assertEquals("testword", store.lookup("TESTWORD").single().word)
            assertEquals("testword", store.lookup("testwords").single().word)
            assertEquals(2, store.lookup("test").size)
            assertEquals("", store.lookup("testword").single().translation)
            val broken = File(folder, "wrong.sqlite")
            fixture.copyTo(broken)
            BundledSQLiteDriver().open(broken.absolutePath).use { db -> db.exec("UPDATE metadata SET value='bilingual-other' WHERE key='dictionary_id'") }
            try { store.import(broken); fail("Expected invalid package rejection") } catch (_: IllegalArgumentException) { }
            assertEquals("testword", store.lookup("testword").single().word)
            val alternate = File(folder, "alternate.sqlite")
            fixture.copyTo(alternate)
            BundledSQLiteDriver().open(alternate.absolutePath).use { db ->
                db.exec("UPDATE entries SET definition='Updated synthetic definition.' WHERE word='testword'")
                db.exec("INSERT INTO entries_fts(entries_fts) VALUES ('rebuild')")
            }
            val second = store.import(alternate)
            assertNotEquals(initial.id, second.id)
            assertEquals(2, store.installed().size)
            assertEquals("Updated synthetic definition.", store.lookup("testword").single().definition)
            store.select(initial.id)
            assertEquals("A synthetic test definition.", store.lookup("testword").single().definition)
            val reopened = DictionaryStore(isolated, installPackaged = false)
            assertEquals(initial.id, reopened.status().id)
            assertEquals(2, reopened.installed().size)
            assertEquals("A synthetic test definition.", reopened.lookup("testword").single().definition)
        } finally { folder.deleteRecursively() }
    }

    @Test fun export500CardsHasCorrectLegacySchemaAndStableGuids() = runBlocking {
        val folder = File(context.cacheDir, "export-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val cards = (1..500).map { i ->
                Card("test-$i", "capture-$i", VocabularyContent("word$i", "A synthetic definition for testing.",
                    listOf(Collocation("first collocation", "A synthetic example."), Collocation("second collocation", "Another synthetic example.")),
                    "A synthetic paraphrase", "formal", "medium"), "A synthetic original sentence $i.", SourceRef("Test fixture", "Synthetic"),
                    topic = "Education", createdAt = 1793145600000, updatedAt = 1793145600000)
            }
            val target = File(folder, "test.apkg")
            val started = android.os.SystemClock.elapsedRealtime()
            LegacyAnkiExporter().export(cards, target)
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            val metric = android.os.Bundle().apply { putString("lexicon_apkg_500_ms", elapsed.toString()) }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0, metric)
            ZipFile(target).use { zip ->
                assertEquals(2, zip.size())
                assertEquals("{}", zip.getInputStream(zip.getEntry("media")).bufferedReader().readText())
                val database = File(folder, "collection.anki2")
                zip.getInputStream(zip.getEntry("collection.anki2")).use { input -> database.outputStream().use { input.copyTo(it) } }
                BundledSQLiteDriver().open(database.absolutePath).use { db ->
                    db.prepare("SELECT COUNT(*),MIN(type),MAX(reps) FROM cards").use { s ->
                        assertTrue(s.step()); assertEquals(500, s.getLong(0)); assertEquals(0, s.getLong(1)); assertEquals(0, s.getLong(2))
                    }
                    db.prepare("SELECT guid,flds,tags FROM notes WHERE guid='test-1'").use { s ->
                        assertTrue(s.step()); assertEquals("test-1", s.getText(0)); assertEquals(4, s.getText(1).split('\u001f').size)
                        assertTrue(s.getText(2).contains("exam::medium"))
                    }
                    db.prepare("PRAGMA integrity_check").use { s -> assertTrue(s.step()); assertEquals("ok", s.getText(0)) }
                }
            }
        } finally { folder.deleteRecursively() }
    }
}
