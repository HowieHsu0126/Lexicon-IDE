package dev.lexicon.data.mdict

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.Adler32
import java.util.zip.Deflater

/** Prepared-stream contracts use independent small MDX bytes; no personal corpus or reflection. */
class MdxPreparedEntryStreamTest {
    private data class PreparedRow(val ordinal: Int, val word: String, val text: String?)

    @Test fun originalOrdinalsAndAliasHeadwordsArePreservedForBothMdxVersions() {
        val source = linkedMapOf(
            "colour" to "@@@LINK=color",
            "zeta" to "<p>independent meaning</p>",
            "color" to "<b>color</b><p>first meaning</p><font face='Kingsoft Phonetic Plain'>rE5bQst</font>",
        )
        val expected = listOf(
            PreparedRow(0, "colour", "color\nfirst meaning\nrəˈbʌst"),
            PreparedRow(1, "zeta", "independent meaning"),
            PreparedRow(2, "color", "first meaning\nrəˈbʌst"),
        )
        for (version in 1..2) withReader(version, source) { reader ->
            val actual = mutableListOf<PreparedRow>()
            reader.forEachPreparedEntry { ordinal, word, text -> actual += PreparedRow(ordinal, word, text) }
            assertEquals("MDX $version preserves file order and per-key cleanup", expected, actual)
        }
    }

    @Test fun normalizedDuplicatesRemainSeparateRowsInFileOrder() {
        val source = linkedMapOf(
            "beta" to "unrelated meaning",
            "ALPHA" to "first normalized key",
            "alpha" to "second normalized key",
            "ＡＬＰＨＡ" to "third normalized key",
            "alphabet" to "prefix result",
        )
        for (version in 1..2) withReader(version, source) { reader ->
            val actual = mutableListOf<PreparedRow>()
            reader.forEachPreparedEntry { ordinal, word, text -> actual += PreparedRow(ordinal, word, text) }
            assertEquals(source.entries.mapIndexed { ordinal, entry -> PreparedRow(ordinal, entry.key, entry.value) }, actual)
            assertEquals(listOf("ALPHA", "alpha", "ＡＬＰＨＡ"), reader.lookup("alpha").map { it.word })
        }
    }

    @Test fun blankTextMissingAliasesAndCyclesStillProduceTheirOriginalOrdinals() {
        val source = linkedMapOf(
            "empty" to "",
            "blank" to " \r\n\t ",
            "hidden" to "<script>hidden()</script><style>hidden</style><img src='unused'>",
            "missing" to "@@@LINK=not-present",
            "cycle-a" to "@@@LINK=cycle-b",
            "cycle-b" to "@@@LINK=cycle-a",
            "visible" to "<p>visible meaning</p>",
        )
        for (version in 1..2) withReader(version, source) { reader ->
            val actual = mutableListOf<PreparedRow>()
            reader.forEachPreparedEntry { ordinal, word, text -> actual += PreparedRow(ordinal, word, text) }
            val expected = source.keys.mapIndexed { ordinal, word ->
                PreparedRow(ordinal, word, if (word == "visible") "visible meaning" else null)
            }
            assertEquals(expected, actual)
        }
    }

    @Test fun consumerFailurePropagatesTheSameExceptionAndStopsTheStream() = withReader(2,
        linkedMapOf("alpha" to "one", "beta" to "two", "gamma" to "three")) { reader ->
        val failure = IllegalStateException("synthetic consumer failure")
        val seen = mutableListOf<Int>()
        val actual = assertThrows(IllegalStateException::class.java) {
            reader.forEachPreparedEntry { ordinal, _, _ ->
                seen += ordinal
                if (ordinal == 1) throw failure
            }
        }
        assertSame(failure, actual)
        assertEquals(listOf(0, 1), seen)
        val restarted = mutableListOf<Int>()
        reader.forEachPreparedEntry { ordinal, _, _ -> restarted += ordinal }
        assertEquals(listOf(0, 1, 2), restarted)
    }

    @Test fun cancellationBeforeTheStreamDoesNotResolveOrDeliverAnyRecord() {
        val stages = ReadStages()
        withReader(2, linkedMapOf("alpha" to "one"), observer = stages) { reader ->
            stages.clear()
            val failure = CancellationException("cancel before first record")
            var delivered = false
            val actual = assertThrows(CancellationException::class.java) {
                reader.forEachPreparedEntry(checkCancelled = { throw failure }) { _, _, _ -> delivered = true }
            }
            assertSame(failure, actual)
            assertFalse(delivered)
            assertEquals(0, stages.count(MdxReadStage.RECORD_DECOMPRESS))
        }
    }

    @Test fun cancellationAfterResolutionIsObservedBeforeTextConversionOrDelivery() {
        var cancelled = false
        var textStages = 0
        val readObserver = MdxReadObserver { stage, _ ->
            if (stage == MdxReadStage.RECORD_DECOMPRESS) cancelled = true
        }
        val textObserver = MdxTextObserver { _, _ -> textStages++ }
        withReader(2, linkedMapOf("alpha" to "<p>one</p>"), readObserver, textObserver) { reader ->
            val failure = CancellationException("cancel during resolution")
            var delivered = false
            val actual = assertThrows(CancellationException::class.java) {
                reader.forEachPreparedEntry(checkCancelled = { if (cancelled) throw failure }) { _, _, _ -> delivered = true }
            }
            assertSame(failure, actual)
            assertFalse(delivered)
            assertEquals(0, textStages)
        }
    }

    @Test fun cancellationAfterTextConversionIsObservedBeforeDelivery() {
        var cancelled = false
        var conversions = 0
        val textObserver = MdxTextObserver { stage, _ ->
            if (stage == MdxTextStage.FINALIZE) { conversions++; cancelled = true }
        }
        withReader(2, linkedMapOf("alpha" to "<p>one</p>"), textObserver = textObserver) { reader ->
            val failure = CancellationException("cancel during text conversion")
            var delivered = false
            val actual = assertThrows(CancellationException::class.java) {
                reader.forEachPreparedEntry(checkCancelled = { if (cancelled) throw failure }) { _, _, _ -> delivered = true }
            }
            assertSame(failure, actual)
            assertEquals(1, conversions)
            assertFalse(delivered)
        }
    }

    @Test fun cancellationSetByConsumerIsObservedEvenAfterTheLastRow() = withReader(1,
        linkedMapOf("alpha" to "one")) { reader ->
        val failure = CancellationException("cancel after delivery")
        var cancelled = false
        val seen = mutableListOf<Int>()
        val actual = assertThrows(CancellationException::class.java) {
            reader.forEachPreparedEntry(checkCancelled = { if (cancelled) throw failure }) { ordinal, _, _ ->
                seen += ordinal
                cancelled = true
            }
        }
        assertSame(failure, actual)
        assertEquals(listOf(0), seen)
    }

    @Test fun exportingDoesNotPopulateOrClearTheSanitizedLookupCache() {
        val stages = ReadStages()
        withReader(2, linkedMapOf("cached" to "already queried", "fresh" to "not queried before export"),
            observer = stages) { reader ->
            reader.lookup("cached")
            stages.clear()
            val actual = mutableListOf<PreparedRow>()
            reader.forEachPreparedEntry { ordinal, word, text -> actual += PreparedRow(ordinal, word, text) }
            assertEquals(listOf(PreparedRow(0, "cached", "already queried"),
                PreparedRow(1, "fresh", "not queried before export")), actual)
            assertEquals(0, stages.count(MdxReadStage.LOOKUP_INDEX))
            assertEquals(0, stages.count(MdxReadStage.LOOKUP_RESOLVE))
            assertEquals(0, stages.count(MdxReadStage.LOOKUP_SANITIZE))

            reader.lookup("cached")
            assertEquals("Export must not clear the existing entry", 0, stages.count(MdxReadStage.LOOKUP_SANITIZE))
            reader.lookup("fresh")
            assertEquals("The first uncached lookup must still sanitize after export", 1, stages.count(MdxReadStage.LOOKUP_SANITIZE))
            reader.lookup("fresh")
            assertEquals("Normal repeat lookup still uses the bounded cache", 1, stages.count(MdxReadStage.LOOKUP_SANITIZE))
        }
    }

    private class ReadStages : MdxReadObserver {
        private val calls = IntArray(MdxReadStage.entries.size)
        override fun onStage(stage: MdxReadStage, elapsedNanos: Long) { calls[stage.ordinal]++ }
        fun count(stage: MdxReadStage) = calls[stage.ordinal]
        fun clear() { calls.fill(0) }
    }

    private fun withReader(
        version: Int,
        rows: LinkedHashMap<String, String>,
        observer: MdxReadObserver? = null,
        textObserver: MdxTextObserver? = null,
        action: (MdxReader) -> Unit,
    ) {
        val file = fixture(version, rows, split = 11)
        try { MdxReader(file, observer, textObserver).use(action) } finally { check(file.delete()) }
    }

    // Copied independently from the existing MdxReaderTest fixture writer; old tests are unchanged.
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
