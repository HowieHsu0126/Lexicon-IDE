package dev.lexicon.data.mdict

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.text.Normalizer
import java.util.Locale
import java.util.TreeMap
import java.util.zip.Adler32
import java.util.zip.Inflater
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeFilter
import org.jsoup.select.NodeFilter.FilterResult

/**
 * Read-only MDX 1.x/2.x text reader. Layout follows the published MDict file format.
 * Only indexes are kept in memory; record blocks are read on demand with a bounded cache.
 * Encrypted databases are rejected explicitly. MDD media and executable HTML are not used.
 */
internal class MdxReader(private val file: File, private val observer: MdxReadObserver? = null, private val textObserver: MdxTextObserver? = null) : Closeable {
    data class Metadata(val title: String, val version: String, val encoding: String, val language: String, val entries: Long)
    data class Entry(val word: String, val text: String)
    private data class Key(val word: String, val offset: Long)
    private data class Block(val fileOffset: Long, val compressedSize: Int, val decompressedSize: Int, val recordOffset: Long = 0)
    private val input = RandomAccessFile(file, "r")
    private val keys = ArrayList<Key>()
    private val index = TreeMap<String, MutableList<Int>>()
    private val records = ArrayList<Block>()
    private val cache = object : LinkedHashMap<Int, ByteArray>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?) = size > 4
    }
    // Keep sanitized entries, not just compressed blocks: repeated lookups avoid HTML parsing.
    private val entries = LinkedHashMap<Int, Entry>(96, .75f, true)
    private var entryCharacters = 0
    private lateinit var charset: Charset
    private var utf16 = false
    private var numberWidth = 4
    private var totalRecordBytes = 0L
    private var keyEndOffsets = LongArray(0)
    val metadata: Metadata

    init {
        try { metadata = load() } catch (e: Exception) { input.close(); throw e }
    }

    private fun load(): Metadata {
        var version = ""
        var major = 0
        var encoding = ""
        val attrs = measured(MdxReadStage.HEADER) {
            require(file.length() in 16..MAX_FILE_SIZE) { "MDX 文件大小无效" }
            val headerSize = input.readInt().toLong() and 0xffffffffL
            require(headerSize in 2..MAX_HEADER_SIZE.toLong() && headerSize % 2 == 0L) { "MDX 头部无效" }
            val rawHeader = readBytes(headerSize.toInt())
            val checksum = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
            verifyAdler(rawHeader, checksum)
            val header = rawHeader.toString(Charsets.UTF_16LE).trimEnd('\u0000')
            val attributes = Regex("([A-Za-z][A-Za-z0-9]*)=\"([^\"]*)\"").findAll(header)
                .associate { it.groupValues[1] to Jsoup.parseBodyFragment(it.groupValues[2]).text() }
            version = attributes["GeneratedByEngineVersion"] ?: throw IllegalArgumentException("缺少 MDX 版本")
            major = version.substringBefore('.').toIntOrNull() ?: 0
            require(major in 1..2) { "支持 MDX 1.x / 2.x，当前版本为 $version" }
            val encrypted = attributes["Encrypted"] ?: "No"
            require(encrypted.equals("No", true) || encrypted == "0") { "不支持加密 MDX，请使用未加密的本地词典" }
            numberWidth = if (major >= 2) 8 else 4
            encoding = attributes["Encoding"].orEmpty().ifBlank { "UTF-8" }
            utf16 = encoding.startsWith("UTF-16", true)
            charset = when {
                utf16 -> Charsets.UTF_16LE
                encoding.equals("GBK", true) || encoding.equals("GB2312", true) -> Charset.forName("GB18030")
                else -> runCatching { Charset.forName(encoding) }.getOrElse { throw IllegalArgumentException("不支持 MDX 编码：$encoding") }
            }
            attributes
        }
        var entryCount = 0
        val keyBlocks = measured(MdxReadStage.KEY_METADATA) {
            val keyHeaderRaw = readBytes(numberWidth * if (major >= 2) 5 else 4)
            val kh = Cursor(keyHeaderRaw)
            val blockCount = kh.number().boundedCount("键块数量", MAX_BLOCK_COUNT)
            entryCount = kh.number().boundedCount("词条数量", MAX_ENTRIES)
            val infoDecompressed = if (major >= 2) kh.number().boundedCount("键索引大小", MAX_INDEX_SIZE) else 0
            val infoSize = kh.number().boundedCount("键索引大小", MAX_INDEX_SIZE)
            val keyBytes = kh.number()
            if (major >= 2) verifyAdler(keyHeaderRaw, input.readInt().toLong() and 0xffffffffL)
            val infoRaw = readBytes(infoSize)
            val info = Cursor(if (major >= 2) decompress(infoRaw, infoDecompressed) else infoRaw)
            val blocks = ArrayList<Pair<Int, Int>>(blockCount)
            var describedEntries = 0L
            repeat(blockCount) {
                describedEntries += info.number()
                repeat(2) {
                    val length = if (major >= 2) info.u16() else info.u8()
                    val bytes = (length + if (major >= 2) 1 else 0) * if (utf16) 2 else 1
                    info.skip(bytes)
                }
                blocks += info.number().boundedCount("压缩键块大小", MAX_BLOCK_SIZE) to info.number().boundedCount("键块大小", MAX_BLOCK_SIZE)
            }
            require(info.remaining == 0 && describedEntries == entryCount.toLong()) { "MDX 键索引计数不一致" }
            require(blocks.sumOf { it.first.toLong() } == keyBytes) { "MDX 键块总大小不一致" }
            require(blocks.sumOf { it.second.toLong() } <= MAX_KEY_DATA_SIZE) { "MDX 词条索引展开后超过 64 MB" }
            blocks
        }
        measured(MdxReadStage.KEY_DECODE) {
            keys.ensureCapacity(entryCount)
            keyBlocks.forEach { (compressed, decompressed) ->
                val cursor = Cursor(decompress(readBytes(compressed), decompressed))
                while (cursor.remaining > 0) {
                    val offset = cursor.number()
                    val word = cursor.string(charset, utf16)
                    require(word.length <= MAX_KEY_CHARS) { "MDX 词条名称过长" }
                    keys += Key(word, offset)
                    require(keys.size <= entryCount) { "MDX 词条数量越界" }
                }
            }
            require(keys.size == entryCount && entryCount > 0) { "MDX 词条数不一致或词典为空" }
        }
        measured(MdxReadStage.RECORD_METADATA) {
            val recordCount = number().boundedCount("记录块数量", MAX_BLOCK_COUNT)
            require(number() == entryCount.toLong()) { "MDX 记录数量不一致" }
            val recordInfoSize = number()
            val recordCompressedTotal = number()
            require(recordInfoSize == recordCount.toLong() * numberWidth * 2) { "MDX 记录索引大小无效" }
            val recordDataStart = input.filePointer + recordInfoSize
            var disk = recordDataStart; var uncompressed = 0L
            repeat(recordCount) {
                val compressed = number().boundedCount("压缩记录块大小", MAX_BLOCK_SIZE)
                val decompressed = number().boundedCount("记录块大小", MAX_BLOCK_SIZE)
                require(compressed >= 8 && decompressed > 0) { "MDX 记录块为空" }
                records += Block(disk, compressed, decompressed, uncompressed)
                disk += compressed; uncompressed += decompressed
                require(uncompressed <= MAX_TOTAL_RECORD_SIZE) { "MDX 展开记录过大" }
            }
            require(disk - recordDataStart == recordCompressedTotal && disk == input.length()) { "MDX 记录文件大小不一致" }
            totalRecordBytes = uncompressed
            require(keys.all { it.offset in 0 until totalRecordBytes }) { "MDX 词条偏移越界" }
        }
        measured(MdxReadStage.END_OFFSETS) {
            var ordered = true
            for (i in 1 until keys.size) {
                if (keys[i].offset < keys[i - 1].offset) { ordered = false; break }
            }
            if (ordered) {
                keyEndOffsets = LongArray(keys.size)
                var laterOffset = totalRecordBytes
                var end = totalRecordBytes
                for (i in keys.lastIndex downTo 0) {
                    val offset = keys[i].offset
                    // Equal offsets share the same next strictly greater record offset.
                    if (offset != laterOffset) end = laterOffset
                    keyEndOffsets[i] = end
                    laterOffset = offset
                }
            } else {
                val offsets = keys.map { it.offset }.distinct().sorted()
                val endMap = HashMap<Long, Long>(offsets.size)
                offsets.forEachIndexed { i, offset -> endMap[offset] = offsets.getOrElse(i + 1) { totalRecordBytes } }
                keyEndOffsets = LongArray(keys.size) { endMap.getValue(keys[it].offset) }
            }
        }
        measured(MdxReadStage.NORMALIZED_INDEX) {
            keys.forEachIndexed { i, key -> index.getOrPut(normalize(key.word)) { ArrayList(1) }.add(i) }
        }
        val title = attrs["Title"].orEmpty().ifBlank { file.nameWithoutExtension }.take(300)
        val language = when {
            title.contains("英汉") || title.contains("双解") -> "en-zh"
            title.contains("英英") -> "en-en"
            else -> "未标注"
        }
        return Metadata(title, version, encoding, language, entryCount.toLong())
    }

    /** Used before installing a new file, so broken record blocks never replace working data. */
    fun validateAllRecords() {
        records.indices.forEach { recordBlock(it) }
        keys.indices.forEach { i -> require(keyEndOffsets[i] - keys[i].offset <= MAX_ENTRY_SIZE) { "MDX 单词条过大" } }
    }

    /**
     * Streams every original key ordinal for a prepared dictionary, including suppressed results.
     * Reuses alias and visible-text semantics without reading, populating or clearing lookup entries.
     * Cancellation is cooperative between bounded resolve, text conversion and consumer operations;
     * a call already inside one of those operations is allowed to finish before its next check.
     */
    internal fun forEachPreparedEntry(
        checkCancelled: () -> Unit = {},
        consumer: (ordinal: Int, word: String, text: String?) -> Unit,
    ) {
        checkCancelled()
        for (ordinal in keys.indices) {
            checkCancelled()
            val key = keys[ordinal]
            val raw = resolve(ordinal, HashSet(), 0)
            checkCancelled()
            val text = if (raw == null) null else {
                val visible = plainText(raw, key.word, textObserver)
                checkCancelled()
                visible.takeIf { it.isNotBlank() }
            }
            checkCancelled()
            consumer(ordinal, key.word, text)
            checkCancelled()
        }
        checkCancelled()
    }

    fun lookup(query: String, limit: Int = 8): List<Entry> {
        val matches = measured(MdxReadStage.LOOKUP_INDEX) {
            val normalized = normalize(query)
            if (normalized.isBlank() || normalized.length > 200) return emptyList()
            val exact = index[normalized]
            exact ?: index.tailMap(normalized).entries.asSequence().takeWhile { it.key.startsWith(normalized) }
                .take(limit).flatMap { it.value.asSequence() }.take(limit).toList()
        }
        return matches.take(limit).mapNotNull { i ->
            entries[i]?.let { return@mapNotNull it }
            val raw = measured(MdxReadStage.LOOKUP_RESOLVE) { resolve(i, HashSet(), 0) } ?: return@mapNotNull null
            val text = measured(MdxReadStage.LOOKUP_SANITIZE) { plainText(raw, keys[i].word, textObserver) }
            if (text.isBlank()) null else Entry(keys[i].word, text).also { entry ->
                if (entry.text.length <= 500_000) {
                    entries[i] = entry; entryCharacters += entry.text.length
                    while (entries.size > 96 || entryCharacters > 500_000) {
                        val eldest = entries.entries.iterator(); entryCharacters -= eldest.next().value.text.length; eldest.remove()
                    }
                }
            }
        }
    }

    private fun resolve(i: Int, visited: MutableSet<Int>, depth: Int): String? {
        if (depth >= 12 || !visited.add(i)) return null
        val content = record(keys[i].offset, keyEndOffsets[i]).toString(charset).trimEnd('\u0000').trim()
        if (content.startsWith("@@@LINK=")) {
            val target = normalize(content.removePrefix("@@@LINK=").trim())
            return index[target]?.firstNotNullOfOrNull { resolve(it, visited, depth + 1) }
        }
        return content
    }

    private fun record(start: Long, end: Long): ByteArray {
        require(end >= start && end - start <= MAX_ENTRY_SIZE) { "MDX 单词条大小无效" }
        val result = ByteArray((end - start).toInt())
        var at = start; var outputAt = 0
        var low = 0; var high = records.lastIndex
        while (low < high) { val mid = (low + high + 1) ushr 1; if (records[mid].recordOffset <= start) low = mid else high = mid - 1 }
        var blockIndex = low
        while (at < end) {
            require(blockIndex < records.size) { "MDX 记录跨块越界" }
            val block = records[blockIndex]; val bytes = recordBlock(blockIndex)
            val offset = (at - block.recordOffset).toInt()
            val size = minOf(bytes.size - offset, (end - at).toInt())
            require(offset >= 0 && size > 0) { "MDX 记录偏移无效" }
            bytes.copyInto(result, outputAt, offset, offset + size); at += size; outputAt += size; blockIndex++
        }
        return result
    }

    private fun recordBlock(index: Int): ByteArray = cache.getOrPut(index) {
        measured(MdxReadStage.RECORD_DECOMPRESS) {
            val block = records[index]; input.seek(block.fileOffset)
            decompress(readBytes(block.compressedSize), block.decompressedSize)
        }
    }

    private inline fun <T> measured(stage: MdxReadStage, action: () -> T): T {
        val activeObserver = observer
        val started = if (activeObserver != null) System.nanoTime() else 0L
        var failure: Throwable? = null
        try {
            return action()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (activeObserver != null) {
                val elapsed = System.nanoTime() - started
                if (failure == null) activeObserver.onStage(stage, elapsed)
                else try { activeObserver.onStage(stage, elapsed) }
                catch (observerFailure: Throwable) { if (observerFailure !== failure) failure.addSuppressed(observerFailure) }
            }
        }
    }

    private fun number(): Long = if (numberWidth == 8) input.readLong().also { require(it >= 0) { "MDX 负数长度" } } else input.readInt().toLong() and 0xffffffffL
    private fun readBytes(count: Int): ByteArray {
        require(count >= 0 && count.toLong() <= input.length() - input.filePointer) { "MDX 文件截断" }
        return ByteArray(count).also(input::readFully)
    }
    private fun Long.boundedCount(label: String, max: Int): Int { require(this in 0..max.toLong()) { "MDX $label 超出限制" }; return toInt() }
    override fun close() { cache.clear(); entries.clear(); entryCharacters = 0; input.close() }

    private inner class Cursor(private val bytes: ByteArray) {
        private var at = 0
        val remaining get() = bytes.size - at
        fun u8(): Int { require(remaining >= 1) { "MDX 索引截断" }; return bytes[at++].toInt() and 255 }
        fun u16() = (u8() shl 8) or u8()
        fun number(): Long {
            require(remaining >= numberWidth) { "MDX 索引截断" }
            var result = 0L; repeat(numberWidth) { result = (result shl 8) or u8().toLong() }
            require(result >= 0) { "MDX 索引负数" }; return result
        }
        fun skip(count: Int) { require(count in 0..remaining) { "MDX 索引文本越界" }; at += count }
        fun string(charset: Charset, utf16: Boolean): String {
            val start = at; val unit = if (utf16) 2 else 1
            while (true) {
                require(remaining >= unit && at - start <= MAX_KEY_CHARS * 4) { "MDX 键文本截断或过长" }
                val end = at; var zero = u8() == 0
                if (utf16) zero = (u8() == 0) && zero
                if (zero) return String(bytes, start, end - start, charset)
            }
        }
    }

    companion object {
        const val MAX_FILE_SIZE = 256L * 1024 * 1024
        const val MAX_BLOCK_SIZE = 16 * 1024 * 1024
        private const val MAX_HEADER_SIZE = 2 * 1024 * 1024
        private const val MAX_INDEX_SIZE = 32 * 1024 * 1024
        private const val MAX_ENTRY_SIZE = 2 * 1024 * 1024
        private const val MAX_TOTAL_RECORD_SIZE = 4L * 1024 * 1024 * 1024
        private const val MAX_ENTRIES = 500_000
        private const val MAX_KEY_DATA_SIZE = 64L * 1024 * 1024
        private const val MAX_BLOCK_COUNT = 100_000
        private const val MAX_KEY_CHARS = 4096
        private val whitespace = Regex("\\s+")
        private val inlineWhitespace = Regex("[\\t ]+")
        fun normalize(value: String): String {
            if (value.isEmpty()) return value
            // Printable ASCII needs neither NFKC nor whitespace replacement when already
            // trimmed and single-spaced. Every other input retains the original operation order.
            var canonicalAscii = value.first() !in "\"'.,:;!?() " && value.last() !in "\"'.,:;!?() "
            var uppercase = false
            if (canonicalAscii) for (i in value.indices) {
                val char = value[i]
                if (char !in ' '..'~' || (char == ' ' && i > 0 && value[i - 1] == ' ')) {
                    canonicalAscii = false
                    break
                }
                if (char in 'A'..'Z') uppercase = true
            }
            if (canonicalAscii) return if (uppercase) value.lowercase(Locale.ROOT) else value
            return Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
                .trim().trim('"', '\'', '.', ',', ':', ';', '!', '?', '(', ')').replace(whitespace, " ")
        }
        internal fun plainText(html: String, headword: String? = null, observer: MdxTextObserver? = null): String {
            val doc = textStage(observer, MdxTextStage.PARSE) { Jsoup.parseBodyFragment(html) }
            val text = textStage(observer, MdxTextStage.EXTRACT_TEXT) {
                val output = StringBuilder()
                fun isTextBoundary(node: Node): Boolean = when (node.normalName()) {
                    "p", "div", "li", "section", "article", "blockquote", "h1", "h2", "h3", "h4", "hr", "tr" -> true
                    else -> false
                }
                doc.body().filter(object : NodeFilter {
                    override fun head(node: Node, depth: Int): FilterResult {
                        if (node is TextNode) {
                            output.append(node.wholeText)
                            return FilterResult.CONTINUE
                        }
                        if (node !is Element) return FilterResult.CONTINUE
                        when (node.normalName()) {
                            "script", "style", "iframe", "object", "embed", "img", "audio", "video", "link" ->
                                return FilterResult.SKIP_ENTIRELY
                            "font" -> if (node.hasAttr("face") && node.attr("face").split(',').any {
                                it.trim().equals("Kingsoft Phonetic Plain", ignoreCase = true)
                            }) {
                                // The previous pass replaced an outer font before its descendants.
                                // Decode its original subtree, then omit those descendants entirely.
                                output.append(KingsoftPhonetics.decode(node.wholeText()) ?: "〔音标未显示〕")
                                return FilterResult.SKIP_ENTIRELY
                            }
                            // wholeText supplies one newline; the previous inserted node supplied another.
                            "br" -> output.append("\n\n")
                        }
                        if (isTextBoundary(node)) output.append('\n')
                        return FilterResult.CONTINUE
                    }

                    override fun tail(node: Node, depth: Int): FilterResult {
                        if (node is Element && isTextBoundary(node)) output.append('\n')
                        return FilterResult.CONTINUE
                    }
                })
                output.toString().replace('\u00a0', ' ')
            }
            val lines = textStage(observer, MdxTextStage.NORMALIZE_LINES) {
                text.lineSequence().map { it.trim().replace(inlineWhitespace, " ") }.filter { it.isNotBlank() }.toList()
            }
            return textStage(observer, MdxTextStage.FINALIZE) {
                val visible = if (headword != null && lines.size > 1 && normalize(lines.first()) == normalize(headword)) lines.drop(1) else lines
                visible.joinToString("\n").take(60000)
            }
        }
        private inline fun <T> textStage(observer: MdxTextObserver?, stage: MdxTextStage, action: () -> T): T {
            if (observer == null) return action()
            val started = System.nanoTime()
            var failure: Throwable? = null
            try { return action() }
            catch (error: Throwable) { failure = error; throw error }
            finally {
                val elapsed = System.nanoTime() - started
                if (failure == null) observer.onStage(stage, elapsed)
                else try { observer.onStage(stage, elapsed) }
                catch (observerFailure: Throwable) { if (observerFailure !== failure) failure.addSuppressed(observerFailure) }
            }
        }
        private fun verifyAdler(bytes: ByteArray, expected: Long) { require(Adler32().apply { update(bytes) }.value == expected) { "MDX 校验和不一致" } }
        internal fun decompress(bytes: ByteArray, expected: Int): ByteArray {
            require(bytes.size >= 8 && expected in 0..MAX_BLOCK_SIZE) { "MDX 压缩块格式无效" }
            val type = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val checksum = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
            val payload = bytes.copyOfRange(8, bytes.size)
            val result = when (type) {
                0 -> payload.also { require(it.size == expected) { "MDX 块大小不一致" } }
                1 -> Lzo1x.decode(payload, expected)
                2 -> {
                    val inflater = Inflater()
                    try {
                        inflater.setInput(payload); val output = ByteArray(expected); var at = 0
                        while (!inflater.finished() && at < expected) {
                            val n = inflater.inflate(output, at, expected - at)
                            require(n > 0) { "MDX zlib 数据截断" }; at += n
                        }
                        require(at == expected && inflater.finished() && inflater.remaining == 0) { "MDX zlib 大小不一致" }; output
                    } finally { inflater.end() }
                }
                else -> throw IllegalArgumentException("不支持 MDX 压缩类型 $type")
            }
            verifyAdler(result, checksum); return result
        }
    }
}
