package dev.lexicon.data.mdict

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.Collections
import java.util.Random
import java.util.zip.Adler32
import java.util.zip.Deflater

/** Independent MDX fixtures share and reorder physical record offsets; keys stay in file order. */
class MdxEndOffsetCompatibilityTest {
    @Test fun nondecreasingOffsetsKeepBothDuplicateGroupsAndTheFinalRecordBoundary() {
        for (version in 1..2) checkFixture(version,
            listOf("first record", "middle record", "last record"), listOf(0, 0, 1, 2, 2, 2))
    }

    @Test fun descendingAndUnsortedOffsetsUseTheNextPhysicalRecordRatherThanTheNextKey() {
        val records = listOf("record zero", "record one", "record two", "record three")
        for (version in 1..2) {
            checkFixture(version, records, listOf(3, 2, 1, 0))
            checkFixture(version, records, listOf(2, 0, 3, 2, 1, 3, 0, 1))
        }
    }

    @Test fun aSinglePhysicalRecordCanBackEveryKeyIncludingTheLastDuplicateGroup() {
        for (version in 1..2) {
            checkFixture(version, listOf("only physical record"), listOf(0))
            checkFixture(version, listOf("shared terminal record"), List(12) { 0 })
        }
    }

    @Test fun sharedUnsortedEntriesRemainExactAcrossRecordBlocksAndUnicodeByteBoundaries() {
        val records = listOf(
            "first record includes rəˈbʌst and 中文 and 😀, repeated. ".repeat(14).trimEnd(),
            "second record crosses physical blocks independently of key order",
            "last record includes world and 𐐀 and e\u0301. ".repeat(11).trimEnd(),
        )
        // Seven-byte chunks split UTF-16 code units and UTF-8 sequences; record concatenation restores them.
        for (version in 1..2) checkFixture(version, records, listOf(2, 0, 2, 1, 0, 2), splitBytes = 7)
    }

    @Test fun seededOffsetPermutationsAndDuplicatesMatchTheFrozenEndMap() {
        val random = Random(0x4D4458454E44L)
        repeat(64) { sample ->
            val count = 1 + random.nextInt(12)
            val records = (0 until count).map { index -> "record $index " + "x".repeat(1 + random.nextInt(90)) }
            val keyRecords = (0 until count).toMutableList()
            repeat(random.nextInt(25)) { keyRecords.add(random.nextInt(count)) }
            if (sample % 3 == 0) keyRecords.sort() else Collections.shuffle(keyRecords, random)
            checkFixture(if (sample % 2 == 0) 1 else 2, records, keyRecords, splitBytes = 11 + random.nextInt(53))
        }
    }

    private fun checkFixture(version: Int, definitions: List<String>, keyRecords: List<Int>, splitBytes: Int = Int.MAX_VALUE) {
        require(definitions.isNotEmpty() && keyRecords.toSet() == definitions.indices.toSet())
        val charset = if (version == 1) Charsets.UTF_16LE else Charsets.UTF_8
        val encodedRecords = definitions.map { (it + '\u0000').toByteArray(charset) }
        val physicalStarts = LongArray(definitions.size)
        var total = 0L
        encodedRecords.forEachIndexed { index, bytes -> physicalStarts[index] = total; total += bytes.size }
        val offsets = keyRecords.map { physicalStarts[it] }
        val keys = keyRecords.indices.map { "key${it.toString().padStart(3, '0')}" }
        val file = writeFixture(version, charset, keys, offsets, encodedRecords, splitBytes)
        try {
            MdxReader(file.toFile()).use { reader ->
                val field = MdxReader::class.java.getDeclaredField("keyEndOffsets").apply { isAccessible = true }
                val actualEnds = field.get(reader) as LongArray
                assertArrayEquals("v$version complete private offset array", previousEnds(offsets, total), actualEnds)
                // Expected visible content is independently supplied, not derived from a production parser/helper.
                keyRecords.forEachIndexed { index, record ->
                    val entry = reader.lookup(keys[index]).single()
                    assertEquals("key identity $index", keys[index], entry.word)
                    assertEquals("v$version key $index physical record $record", definitions[record], entry.text)
                    val physicalEnd = physicalStarts.getOrElse(record + 1) { total }
                    assertEquals("v$version physical boundary $index", physicalEnd, actualEnds[index])
                }
                reader.validateAllRecords()
            }
        } finally { Files.deleteIfExists(file) }
    }

    // Frozen predecessor. Do not optimize it or call the production end-offset helper here.
    private fun previousEnds(offsets: List<Long>, totalRecordBytes: Long): LongArray {
        val sorted = offsets.distinct().sorted()
        val endMap = HashMap<Long, Long>(sorted.size)
        sorted.forEachIndexed { index, offset -> endMap[offset] = sorted.getOrElse(index + 1) { totalRecordBytes } }
        return LongArray(offsets.size) { endMap.getValue(offsets[it]) }
    }

    private fun writeFixture(
        version: Int, charset: Charset, keys: List<String>, offsets: List<Long>, records: List<ByteArray>, splitBytes: Int,
    ): java.nio.file.Path {
        fun bytes(block: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { sink -> DataOutputStream(sink).use(block) }.toByteArray()
        fun number(out: DataOutputStream, value: Long) { if (version == 1) out.writeInt(value.toInt()) else out.writeLong(value) }
        fun packed(data: ByteArray): ByteArray = bytes { out ->
            out.write(byteArrayOf(if (version == 1) 0 else 2, 0, 0, 0))
            out.writeInt(Adler32().apply { update(data) }.value.toInt())
            if (version == 1) out.write(data) else {
                val compressor = Deflater()
                try {
                    compressor.setInput(data); compressor.finish()
                    val buffer = ByteArray(512)
                    while (!compressor.finished()) {
                        val read = compressor.deflate(buffer)
                        require(read > 0)
                        out.write(buffer, 0, read)
                    }
                } finally { compressor.end() }
            }
        }
        val header = ("<Dictionary GeneratedByEngineVersion=\"$version.2\" Encrypted=\"No\" Encoding=\"${if (version == 1) "UTF-16" else "UTF-8"}\" Title=\"Synthetic offset fixture\"/>\u0000").toByteArray(Charsets.UTF_16LE)
        val keyData = bytes { out -> keys.forEachIndexed { index, key ->
            number(out, offsets[index]); out.write(key.toByteArray(charset)); out.write(ByteArray(if (version == 1) 2 else 1))
        } }
        val storedKeys = packed(keyData)
        val keyInfo = bytes { out ->
            number(out, keys.size.toLong())
            for (key in listOf(keys.first(), keys.last())) {
                if (version == 1) out.writeByte(key.length) else out.writeShort(key.length)
                out.write(key.toByteArray(charset))
                if (version == 2) out.writeByte(0)
            }
            number(out, storedKeys.size.toLong()); number(out, keyData.size.toLong())
        }
        val storedInfo = if (version == 1) keyInfo else packed(keyInfo)
        val keyHeader = bytes { out ->
            number(out, 1); number(out, keys.size.toLong())
            if (version == 2) number(out, keyInfo.size.toLong())
            number(out, storedInfo.size.toLong()); number(out, storedKeys.size.toLong())
        }
        val allRecords = ByteArrayOutputStream().apply { records.forEach(::write) }.toByteArray()
        val chunks = buildList {
            var offset = 0
            while (offset < allRecords.size) {
                val end = (offset.toLong() + splitBytes).coerceAtMost(allRecords.size.toLong()).toInt()
                add(allRecords.copyOfRange(offset, end)); offset = end
            }
        }
        val storedRecords = chunks.map(::packed)
        val encoded = bytes { out ->
            out.writeInt(header.size); out.write(header)
            out.writeInt(Integer.reverseBytes(Adler32().apply { update(header) }.value.toInt()))
            out.write(keyHeader)
            if (version == 2) out.writeInt(Adler32().apply { update(keyHeader) }.value.toInt())
            out.write(storedInfo); out.write(storedKeys)
            number(out, chunks.size.toLong()); number(out, keys.size.toLong())
            number(out, chunks.size.toLong() * 2 * if (version == 1) 4 else 8)
            number(out, storedRecords.sumOf { it.size.toLong() })
            storedRecords.forEachIndexed { index, block -> number(out, block.size.toLong()); number(out, chunks[index].size.toLong()) }
            storedRecords.forEach(out::write)
        }
        return Files.createTempFile("lexicon-mdx-offset-", ".mdx").also { Files.write(it, encoded) }
    }
}
