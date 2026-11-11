package dev.lexicon.data.mdict

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.TreeMap
import java.util.UUID

/** Synthetic read/write contract tests for the cache-only prototype; no personal dictionary. */
@RunWith(AndroidJUnit4::class)
class PreparedMdxIndexTest {
    private data class Row(val word: String, val text: String?)
    private val source = "a".repeat(64)
    private val parser = "mdx-dom-1.21-frozen-experiment"

    @Test fun exactDuplicatesAndPrefixRetainOriginalKeyOrdering() = owned { dir ->
        val rows = listOf(Row("ALPHA", "first"), Row("alphabet", "prefix"), Row("alpha", "second"),
            Row("Alpha", "third"), Row("ＢＥＴＡ", "full-width"), Row("beta", "plain"), Row("alpine", "later"))
        val file = File(dir, "prepared.sqlite")
        val result = build(file, rows)
        assertTrue(result.partialCleanupSucceeded)
        assertEquals(rows.size, result.rowCount)
        assertEquals(4, result.normalizedKeyCount)
        PreparedMdxIndex.open(file, source, parser).use { prepared ->
            for (query in listOf("alpha", "al", "ＡＬＰＨＡ", "beta", "b", "absent")) {
                for (limit in listOf(0, 1, 2, 8)) assertEquals("$query/$limit", oracle(rows, query, limit), prepared.lookup(query, limit))
            }
            assertEquals(emptyList<MdxReader.Entry>(), prepared.lookup("   ", -1))
            assertThrows(IllegalArgumentException::class.java) { prepared.lookup("alpha", -1) }
        }
    }

    @Test fun utf16OrderingAndLiteralPercentUnderscoreDoNotUseSqlTextCollationOrLike() = owned { dir ->
        val rows = listOf(Row("x\uE000", "private use"), Row("x\uD800\uDC00", "supplementary"),
            Row("x\uFFFF", "last unit"), Row("x_1", "underscore"), Row("x%1", "percent"),
            Row("xA", "ascii"), Row("x\uD800", "unpaired high"), Row("\uFFFFa", "upperless a"), Row("\uFFFFb", "upperless b"))
        val file = File(dir, "prepared.sqlite"); build(file, rows)
        PreparedMdxIndex.open(file, source, parser).use { prepared ->
            for (query in listOf("x", "x_", "x%", "x\uD800", "\uFFFF")) {
                assertEquals(query, oracle(rows, query, 8), prepared.lookup(query))
            }
            val words = prepared.lookup("x").map { it.word }
            assertTrue(words.indexOf("x\uD800\uDC00") < words.indexOf("x\uE000"))
        }
        assertNull(PreparedMdxIndex.prefixSuccessor(byteArrayOf(-1, -1)))
        assertArrayEquals(byteArrayOf(0x01, 0x03), PreparedMdxIndex.prefixSuccessor(byteArrayOf(0x01, 0x02, -1)))
    }

    @Test fun nullAndBlankRowsConsumeLimitAndExactPresenceNeverFallsBackToPrefix() = owned { dir ->
        val rows = buildList {
            repeat(8) { add(Row("empty", if (it % 2 == 0) null else " \n\t")) }
            add(Row("empty", "ninth exact must not backfill"))
            add(Row("emptyish", "prefix must not replace exact"))
            repeat(8) { add(Row("prefix${it}", if (it < 7) null else "eighth")) }
            add(Row("prefix8", "ninth must not backfill"))
        }
        val file = File(dir, "prepared.sqlite"); build(file, rows)
        PreparedMdxIndex.open(file, source, parser).use { prepared ->
            assertEquals(emptyList<MdxReader.Entry>(), prepared.lookup("empty"))
            assertEquals(listOf(MdxReader.Entry("prefix7", "eighth")), prepared.lookup("prefix"))
            assertEquals(oracle(rows, "empty", 9), prepared.lookup("empty", 9))
            assertEquals(rows.size, prepared.rowCount)
        }
    }

    @Test fun completeRowsPreserveHeadwordDependentTextNullsWhitespaceAndUtf16Units() = owned { dir ->
        val html = "<b>color</b><p>first meaning</p><font face='Kingsoft Phonetic Plain'>rE5bQst</font>"
        val rows = listOf(
            Row("color", MdxReader.plainText(html, "color")),
            // The MDX producer resolves aliases but retains the alias headword for cleanup.
            Row("colour", MdxReader.plainText(html, "colour")),
            Row("blank", " \n\t"), Row("missing", null),
            Row("odd\uD800", "lead\u0000\uDC00tail\uD800"),
            Row("maximum", "x".repeat(59_999) + "\uD800"),
        )
        assertNotEquals(rows[0].text, rows[1].text)
        val file = File(dir, "prepared.sqlite"); val result = build(file, rows)
        assertEquals(1, result.nullTextCount); assertEquals(1, result.blankTextCount)
        PreparedMdxIndex.open(file, source, parser).use { prepared ->
            val roundTrip = mutableListOf<Row>()
            prepared.forEachRow { ordinal, word, text -> assertEquals(roundTrip.size, ordinal); roundTrip += Row(word, text) }
            assertEquals(rows, roundTrip)
            for (row in rows) assertEquals(oracle(rows, row.word, 8), prepared.lookup(row.word))
        }
    }

    @Test fun failedOrOutOfOrderPreparationRemovesOnlyOwnedPartials() = owned { dir ->
        val sentinel = File(dir, "unrelated.txt").apply { writeText("keep") }
        for ((label, producer) in listOf<Pair<String, ((Int, String, String?) -> Unit) -> Unit>>(
            "throws" to { consume -> consume(0, "alpha", "one"); error("synthetic producer interruption") },
            "short" to { consume -> consume(0, "alpha", "one") },
            "duplicate" to { consume -> consume(0, "alpha", "one"); consume(0, "beta", "two") },
            "oversized" to { consume -> consume(0, "alpha", "x".repeat(60_001)) },
        )) {
            val target = File(dir, "$label.sqlite")
            assertThrows(Throwable::class.java) { PreparedMdxIndex.build(target, source, parser, 2, producer) }
            assertFalse(target.exists())
            assertEquals(setOf(sentinel.name), dir.listFiles()!!.map { it.name }.toSet())
        }
        assertEquals("keep", sentinel.readText())
    }

    @Test fun existingAndConcurrentlyCreatedTargetAreNeverOverwritten() = owned { dir ->
        val existing = File(dir, "existing.sqlite").apply { writeText("existing bytes") }
        assertThrows(IllegalArgumentException::class.java) { build(existing, listOf(Row("alpha", "one"))) }
        assertEquals("existing bytes", existing.readText())
        val raced = File(dir, "raced.sqlite")
        assertThrows(Throwable::class.java) {
            PreparedMdxIndex.build(raced, source, parser, 1) { consume ->
                consume(0, "alpha", "one")
                raced.writeText("concurrent owner")
            }
        }
        assertEquals("concurrent owner", raced.readText())
        assertEquals(setOf("existing.sqlite", "raced.sqlite"), dir.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun wrongIdentityAndLogicallyCorruptContentAreRejectedWithoutEditingTheCache() = owned { dir ->
        val rows = listOf(Row("alpha", "one"), Row("beta", "two"))
        val file = File(dir, "prepared.sqlite"); build(file, rows)
        val original = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { PreparedMdxIndex.open(file, "b".repeat(64), parser).close() }
        assertThrows(IllegalArgumentException::class.java) { PreparedMdxIndex.open(file, source, "different-parser").close() }
        assertArrayEquals(original, file.readBytes())
        BundledSQLiteDriver().open(file.absolutePath).use { db ->
            db.prepare("UPDATE prepared_entries SET text_units=? WHERE ordinal=0").use { s ->
                s.bindBlob(1, PreparedMdxIndex.utf16Units("different")); s.step()
            }
        }
        val corrupted = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { PreparedMdxIndex.open(file, source, parser).close() }
        assertArrayEquals(corrupted, file.readBytes())
    }

    @Test fun missingOrdinalAndWrongSortKeyAreRejected() = owned { dir ->
        for (change in listOf("DELETE FROM prepared_entries WHERE ordinal=0", "UPDATE prepared_entries SET sort_key=x'0000' WHERE ordinal=0")) {
            val file = File(dir, "${UUID.randomUUID()}.sqlite")
            build(file, listOf(Row("alpha", "one"), Row("beta", "two")))
            BundledSQLiteDriver().open(file.absolutePath).use { db -> db.prepare(change).use { it.step() } }
            assertThrows(IllegalArgumentException::class.java) { PreparedMdxIndex.open(file, source, parser).close() }
        }
    }

    private fun build(file: File, rows: List<Row>) = PreparedMdxIndex.build(file, source, parser, rows.size) { consume ->
        rows.forEachIndexed { ordinal, row -> consume(ordinal, row.word, row.text) }
    }
    private fun oracle(rows: List<Row>, raw: String, limit: Int): List<MdxReader.Entry> {
        val normalized = MdxReader.normalize(raw)
        if (normalized.isBlank() || normalized.length > 200) return emptyList()
        val keys = TreeMap<String, MutableList<Int>>()
        rows.forEachIndexed { ordinal, row -> keys.getOrPut(MdxReader.normalize(row.word)) { mutableListOf() }.add(ordinal) }
        val selected = keys[normalized] ?: keys.tailMap(normalized).entries.asSequence()
            .takeWhile { it.key.startsWith(normalized) }.take(limit).flatMap { it.value.asSequence() }.take(limit).toList()
        return selected.take(limit).mapNotNull { ordinal -> rows[ordinal].text?.takeIf { it.isNotBlank() }?.let { MdxReader.Entry(rows[ordinal].word, it) } }
    }
    private fun owned(block: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "prepared-mdx-test-${UUID.randomUUID()}")
        check(root.mkdir())
        try { block(root) } finally { check(root.deleteRecursively()) }
    }
}
