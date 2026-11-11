package dev.lexicon.ide.ui

import dev.lexicon.domain.DictionaryEntry
import java.security.MessageDigest
import java.util.regex.Pattern

internal data class DictionaryPreview(val text: String, val endExclusive: Int, val truncated: Boolean)
internal data class DictionaryTextBlock(val start: Int, val endExclusive: Int, val text: String)

/** Presentation ranges refer to unchanged UTF-16 source text, never normalized dictionary content. */
internal object DictionaryPresentation {
    private val grapheme = Pattern.compile("\\X")

    fun preview(definition: String, maxCharacters: Int = 480, maxLines: Int = 4): DictionaryPreview {
        require(maxCharacters > 0 && maxLines > 0) { "Preview limits must be positive" }
        var ceiling = minOf(definition.length, maxCharacters)
        var lines = 1
        var at = 0
        while (at < ceiling) {
            val char = definition[at]
            if (char == '\r' || char == '\n' || char == '\u0085' || char == '\u2028' || char == '\u2029') {
                if (lines == maxLines) { ceiling = at; break }
                lines++
                if (char == '\r' && at + 1 < definition.length && definition[at + 1] == '\n') at++
            }
            at++
        }
        val matcher = grapheme.matcher(definition)
        var end = 0
        while (matcher.find() && matcher.end() <= ceiling) end = matcher.end()
        return DictionaryPreview(definition.substring(0, end), end, end < definition.length)
    }

    /**
     * Prefer a newline or whitespace in the latter half of a block. A single indivisible grapheme
     * may exceed maxCharacters; every other block is bounded, and joining text restores every byte.
     */
    fun blocks(definition: String, maxCharacters: Int = 1000): List<DictionaryTextBlock> {
        require(maxCharacters > 0) { "Block limit must be positive" }
        if (definition.isEmpty()) return emptyList()
        val ends = ArrayList<Int>(definition.length.coerceAtMost(60_000) + 1)
        ends.add(0)
        val matcher = grapheme.matcher(definition)
        while (matcher.find()) ends.add(matcher.end())
        val result = ArrayList<DictionaryTextBlock>()
        var startIndex = 0
        while (startIndex < ends.lastIndex) {
            val start = ends[startIndex]
            val ceiling = (start.toLong() + maxCharacters).coerceAtMost(definition.length.toLong()).toInt()
            var endIndex = startIndex + 1
            while (endIndex < ends.lastIndex && ends[endIndex + 1] <= ceiling) endIndex++
            if (ends[endIndex] <= ceiling && ends[endIndex] < definition.length) {
                val preferredStart = start + maxCharacters / 2
                var whitespace = -1
                var lineBreak = -1
                for (candidate in endIndex downTo startIndex + 1) {
                    val end = ends[candidate]
                    if (end < preferredStart) break
                    val cp = definition.codePointBefore(end)
                    if (cp == '\n'.code || cp == '\r'.code || cp == 0x85 || cp == 0x2028 || cp == 0x2029) {
                        lineBreak = candidate; break
                    }
                    if (whitespace < 0 && (Character.isWhitespace(cp) || Character.isSpaceChar(cp))) whitespace = candidate
                }
                if (lineBreak >= 0) endIndex = lineBreak else if (whitespace >= 0) endIndex = whitespace
            }
            val end = ends[endIndex]
            result.add(DictionaryTextBlock(start, end, definition.substring(start, end)))
            startIndex = endIndex
        }
        return result
    }

    /** All matches participate, including duplicates and entries that were outside the old preview. */
    fun identity(dictionaryId: String, query: String, entries: List<DictionaryEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
            digest.update(bytes)
        }
        add("dictionary-presentation-1"); add(dictionaryId); add(query); add(entries.size.toString())
        entries.forEach { entry ->
            add(entry.word); add(entry.translation); add(entry.definition); add(entry.phonetic); add(entry.tags)
            add(entry.frequency?.toString().orEmpty())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
