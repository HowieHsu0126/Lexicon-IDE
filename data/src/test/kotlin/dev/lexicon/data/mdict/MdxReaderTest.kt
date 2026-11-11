package dev.lexicon.data.mdict

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.Deflater

class MdxReaderTest {
    @Test fun versionOneUtf16RawSupportsAliasesAndExactEntries() = fixture(1, linkedMapOf(
        "alpha" to "<p>first meaning</p><p>second sense</p>", "alphas" to "@@@LINK=alpha", "beta" to "<div>second word</div>",
    )).useReader { reader ->
        assertEquals(3L, reader.metadata.entries)
        assertEquals("en-zh", reader.metadata.language)
        assertEquals("first meaning\nsecond sense", reader.lookup("ALPHA").single().text)
        assertEquals(reader.lookup("alpha").single().text, reader.lookup("alphas").single().text)
        reader.validateAllRecords()
    }

    @Test fun versionTwoUtf8ZlibSupportsEntryAcrossRecordBlocks() = fixture(2, linkedMapOf(
        "alpha" to "<p>${"cross block text ".repeat(15)}</p>", "beta" to "second word", "gamma" to "third word",
    ), split = 50).useReader { reader ->
        assertTrue(reader.lookup("alpha").single().text.contains("cross block text"))
        assertEquals("second word", reader.lookup("beta").single().text)
        assertEquals("third word", reader.lookup("gamma").single().text)
        reader.validateAllRecords()
    }

    @Test fun aliasCyclesAreBoundedAndHtmlNeverExecutes() = fixture(2, linkedMapOf(
        "a" to "@@@LINK=b", "b" to "@@@LINK=a", "c" to "<script>untrusted()</script><style>hide</style><p>safe</p><img src='https://example.invalid/track'>",
    )).useReader { reader ->
        assertTrue(reader.lookup("a").isEmpty())
        assertEquals("safe", reader.lookup("c").single().text)
    }

    @Test fun kingsoftFontTranscriptionIsScopedAndUnknownSymbolsAreExplicit() {
        assertEquals("rəˈbʌst", KingsoftPhonetics.decode("rE5bQst"))
        assertEquals("həˈləʊ", KingsoftPhonetics.decode("hE5lEJ"))
        assertEquals("wɚld", KingsoftPhonetics.decode("wLld"))
        assertEquals("wɜːld", KingsoftPhonetics.decode("w\\:ld"))
        assertNull(KingsoftPhonetics.decode("unverified%"))
        val text = MdxReader.plainText("<b>robust</b><hr><font face='Kingsoft Phonetic Plain, Tahoma'>rE5bQst</font><br><p>Body E5 is unchanged.</p>", "robust")
        assertEquals("rəˈbʌst\nBody E5 is unchanged.", text)
        assertEquals("E5", MdxReader.plainText("<font face='Other Font'>E5</font>"))
        assertEquals("〔音标未显示〕", MdxReader.plainText("<font face='Kingsoft Phonetic Plain'>%</font>"))
    }

    @Test fun truncationAndHeaderChecksumRejectBeforeUse() {
        val file = fixture(1, linkedMapOf("word" to "meaning"))
        try {
            val bytes = file.readBytes(); file.writeBytes(bytes.copyOf(bytes.size - 2))
            assertThrows(IllegalArgumentException::class.java) { MdxReader(file).close() }
            bytes[6] = (bytes[6].toInt() xor 1).toByte(); file.writeBytes(bytes)
            assertThrows(IllegalArgumentException::class.java) { MdxReader(file).close() }
        } finally { file.delete() }
    }

    @Test fun lzoLiteralAndMalformedBounds() {
        val literal = "hello".toByteArray()
        assertArrayEquals(literal, Lzo1x.decode(byteArrayOf(22) + literal + byteArrayOf(17, 0, 0), 5))
        assertThrows(IllegalArgumentException::class.java) { Lzo1x.decode(byteArrayOf(22, 1, 2), 5) }
        assertThrows(IllegalArgumentException::class.java) { Lzo1x.decode(byteArrayOf(22) + literal + byteArrayOf(17, 0, 0), 3) }
    }

    @Test fun corruptCompressedRecordDoesNotPassValidation() {
        val file = fixture(2, linkedMapOf("word" to "a meaningful entry"))
        try {
            val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            MdxReader(file).use { reader -> assertThrows(Exception::class.java) { reader.validateAllRecords() } }
        } finally { file.delete() }
    }

    @Test fun suppliedMdxCompleteValidationAndBoundedLookup() {
        val corpus = File("dic").listFiles()?.filter { it.extension.equals("mdx", true) }.orEmpty().firstOrNull()
        assumeTrue("Personal dictionary not present in portable source checkout", corpus != null)
        val file = requireNotNull(corpus)
        val start = System.nanoTime()
        MdxReader(file).use { reader ->
            val openMs = (System.nanoTime() - start) / 1_000_000
            val validationStart = System.nanoTime(); reader.validateAllRecords()
            val validationMs = (System.nanoTime() - validationStart) / 1_000_000
            val words = listOf("hello", "world", "book")
            val samples = words.map { word ->
                val entries = reader.lookup(word)
                assertTrue("Expected a dictionary entry for a common word", entries.isNotEmpty())
                buildJsonObject { put("query", word); put("matches", entries.size); put("definition_characters", entries.first().text.length) }
            }
            val nanos = (0 until 150).map { i -> val before = System.nanoTime(); reader.lookup(words[i % words.size]); System.nanoTime() - before }.sorted()
            val report = buildJsonObject {
                put("kind", "local-user-supplied-mdx-validation"); put("status", "passed")
                put("file_name", file.name); put("file_bytes", file.length())
                put("header_title", reader.metadata.title); put("mdx_version", reader.metadata.version); put("encoding", reader.metadata.encoding)
                put("language_from_header_title", reader.metadata.language); put("key_count", reader.metadata.entries)
                put("open_ms", openMs); put("all_record_checksums_validation_ms", validationMs)
                put("host_warm_lookup_samples", nanos.size); put("host_warm_lookup_p95_ms", nanos[(nanos.size * .95).toInt()] / 1_000_000.0)
                put("measurement_environment", "host JVM; not Android/MagicOS device acceptance")
                putJsonArray("samples") { samples.forEach(::add) }
                put("no_dictionary_definition_text_in_report", true)
            }
            // Normal regression runs must not overwrite immutable release evidence.
            System.getenv("LEXICON_MDX_VALIDATION_OUTPUT")?.let { path ->
                val output = File(path)
                check(!output.exists()) { "Preserve existing dictionary validation report" }
                output.parentFile?.mkdirs(); output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
            }
        }
    }

    @Test fun normalizedDuplicateKeysKeepTheirOrderAndAliasesAcrossCacheEviction() = fixture(2, linkedMapOf(
        "ＡＬＰＨＡ" to "<p>first sense</p>", "alpha" to "<div>second entry</div>", "alphas" to "@@@LINK=alpha",
    ).apply { repeat(130) { put("word${it.toString().padStart(3, '0')}", "<p>Meaning $it</p>") } }, split = 128).useReader { reader ->
        val expected = listOf("first sense", "second entry")
        assertEquals(expected, reader.lookup("(ALPHA)").map { it.text })
        assertEquals("first sense", reader.lookup("alphas").single().text)
        repeat(130) { assertEquals("Meaning $it", reader.lookup("word${it.toString().padStart(3, '0')}").single().text) }
        assertEquals(expected, reader.lookup("alpha").map { it.text })
        assertEquals("first sense", reader.lookup("alphas").single().text)
        reader.validateAllRecords()
    }

    private fun File.useReader(block: (MdxReader) -> Unit) { try { MdxReader(this).use(block) } finally { delete() } }
    private fun fixture(version: Int, entries: LinkedHashMap<String, String>, split: Int = Int.MAX_VALUE): File {
        val charset = if (version == 1) Charsets.UTF_16LE else Charsets.UTF_8
        val header = ("<Dictionary GeneratedByEngineVersion=\"$version.2\" RequiredEngineVersion=\"$version.2\" Encrypted=\"No\" Encoding=\"${if (version == 1) "UTF-16" else "UTF-8"}\" Title=\"Synthetic 英汉 test dictionary\"/>\u0000").toByteArray(Charsets.UTF_16LE)
        fun number(out: DataOutputStream, n: Long) { if (version == 1) out.writeInt(n.toInt()) else out.writeLong(n) }
        fun bytes(block: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { sink -> DataOutputStream(sink).use(block) }.toByteArray()
        fun packed(data: ByteArray): ByteArray = bytes { out ->
            out.write(byteArrayOf(if (version == 1) 0 else 2, 0, 0, 0)); out.writeInt(Adler32().apply { update(data) }.value.toInt())
            if (version == 1) out.write(data) else {
                val compressor = Deflater(); compressor.setInput(data); compressor.finish()
                val buffer = ByteArray(data.size + 100); val n = compressor.deflate(buffer); compressor.end(); out.write(buffer, 0, n)
            }
        }
        val text = ByteArrayOutputStream(); val keyData = bytes { out -> entries.forEach { (word, meaning) ->
            number(out, text.size().toLong()); out.write(word.toByteArray(charset)); out.write(ByteArray(if (version == 1) 2 else 1))
            text.write((meaning + "\u0000").toByteArray(charset))
        } }
        val keyBlock = packed(keyData)
        val keyInfo = bytes { out ->
            number(out, entries.size.toLong())
            for (word in listOf(entries.keys.first(), entries.keys.last())) {
                if (version == 1) out.writeByte(word.length) else out.writeShort(word.toByteArray(charset).size)
                out.write(word.toByteArray(charset)); if (version == 2) out.writeByte(0)
            }
            number(out, keyBlock.size.toLong()); number(out, keyData.size.toLong())
        }
        val storedInfo = if (version == 1) keyInfo else packed(keyInfo)
        val keyHeader = bytes { out ->
            number(out, 1); number(out, entries.size.toLong()); if (version == 2) number(out, keyInfo.size.toLong())
            number(out, storedInfo.size.toLong()); number(out, keyBlock.size.toLong())
        }
        val chunks = text.toByteArray().asList().chunked(minOf(split, text.size())).map { it.toByteArray() }
        val blocks = chunks.map(::packed)
        val result = bytes { out ->
            out.writeInt(header.size); out.write(header); out.writeInt(Integer.reverseBytes(Adler32().apply { update(header) }.value.toInt()))
            out.write(keyHeader); if (version == 2) out.writeInt(Adler32().apply { update(keyHeader) }.value.toInt())
            out.write(storedInfo); out.write(keyBlock)
            number(out, chunks.size.toLong()); number(out, entries.size.toLong()); number(out, (chunks.size * 2 * if (version == 1) 4 else 8).toLong())
            number(out, blocks.sumOf { it.size.toLong() })
            blocks.forEachIndexed { i, block -> number(out, block.size.toLong()); number(out, chunks[i].size.toLong()) }
            blocks.forEach(out::write)
        }
        return Files.createTempFile("lexicon-synthetic-", ".mdx").toFile().apply { writeBytes(result) }
    }
}
