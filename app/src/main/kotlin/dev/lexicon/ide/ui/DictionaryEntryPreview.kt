package dev.lexicon.ide.ui

import java.util.Locale
import java.util.regex.Pattern

internal enum class DictionaryPreviewBasis { PREFIX, FIRST_SENSE }

/** Offsets refer to the unchanged DictionaryEntry.definition, not to MDX/HTML bytes. */
internal data class DictionaryDefinitionPreview(
    val text: String,
    val start: Int,
    val endExclusive: Int,
    val truncated: Boolean,
    val basis: DictionaryPreviewBasis,
)

/** A small, optional plain-text layout recognizer. Unrecognized layouts keep the old prefix. */
internal object DictionaryEntryPreview {
    private const val SCAN_CHARACTERS = 4096
    private const val SCAN_LINES = 16
    private const val SUPERSCRIPTS = "⁰¹²³⁴⁵⁶⁷⁸⁹"
    private const val SYLLABLE_SEPARATORS = "\u00b7\u2219"
    private val grapheme = Pattern.compile("\\X")
    private val partsOfSpeech = setOf("noun", "verb", "adjective", "adj", "adverb", "pronoun", "preposition",
        "conjunction", "determiner", "interjection", "exclamation", "modal verb", "auxiliary verb")
    private val usageLabels = setOf("formal", "informal", "literary", "technical", "spoken", "written",
        "usually plural", "usually singular", "countable", "uncountable", "transitive", "intransitive")
    private data class Line(val start: Int, val end: Int, val complete: Boolean)

    fun select(word: String, definition: String, maxCharacters: Int = 480, maxLines: Int = 4): DictionaryDefinitionPreview {
        require(maxCharacters > 0 && maxLines > 0) { "Preview limits must be positive" }
        fun prefix(): DictionaryDefinitionPreview {
            val old = DictionaryPresentation.preview(definition, maxCharacters, maxLines)
            return DictionaryDefinitionPreview(old.text, 0, old.endExclusive, old.truncated, DictionaryPreviewBasis.PREFIX)
        }
        val start = firstSenseStart(word, definition) ?: return prefix()
        // The profile currently begins on an ASCII letter; verify the original grapheme boundary too.
        val boundary = grapheme.matcher(definition)
        var startsOnBoundary = start == 0
        while (!startsOnBoundary && boundary.find() && boundary.end() <= start) startsOnBoundary = boundary.end() == start
        if (!startsOnBoundary) return prefix()
        var ceiling = (start.toLong() + maxCharacters).coerceAtMost(definition.length.toLong()).toInt()
        var lines = 1; var at = start
        while (at < ceiling) {
            if (isLineBreak(definition[at])) {
                if (lines == maxLines) { ceiling = at; break }
                lines++
                if (definition[at] == '\r' && at + 1 < definition.length && definition[at + 1] == '\n') at++
            }
            at++
        }
        val matcher = grapheme.matcher(definition).region(start, definition.length)
        var end = start
        while (matcher.find() && matcher.end() <= ceiling) end = matcher.end()
        // A first grapheme larger than the requested budget is not a successful meaning preview.
        if (end == start) return prefix()
        return DictionaryDefinitionPreview(definition.substring(start, end), start, end,
            start > 0 || end < definition.length, DictionaryPreviewBasis.FIRST_SENSE)
    }

    private fun firstSenseStart(word: String, source: String): Int? {
        if (word.isBlank()) return null
        val lines = leadingLines(source).filter { source.substring(it.start, it.end).isNotBlank() }
        if (lines.size < 3 || lines.take(3).any { !it.complete }) return null
        val header = source.substring(lines[0].start, lines[0].end).trim()
        if (!isHeadwordAndPronunciation(header, word.trim())) return null
        // U+25D9 was observed in the bounded production-parser sample. Other unknown markers stay visible.
        val part = source.substring(lines[1].start, lines[1].end).trim().removePrefix("◙").trim().lowercase(Locale.ROOT)
        if (part !in partsOfSpeech) return null
        val line = lines[2]
        var at = line.start
        while (at < line.end && source[at].isWhitespace()) at++
        if (at + 2 >= line.end || source[at] != '1' || source[at + 1] != '.' || !source[at + 2].isWhitespace()) return null
        at += 2
        while (at < line.end && source[at].isWhitespace()) at++
        if (at >= line.end) return null
        // Plain numbered definitions share the same verified header/POS envelope as captioned senses.
        // A usage label is not treated as a definition, even when more text follows its delimiter.
        if (source[at] in 'a'..'z') return at.takeUnless { startsWithUsageLabel(source, it, line.end) }
        val comma = (at until line.end).firstOrNull { source[it] == ',' } ?: return null
        // This profile requires an uppercase English caption, then a Han caption, then a comma.
        // Numbering, uppercase prose, slashes or an arbitrary lowercase word alone are insufficient.
        val caption = source.substring(at, comma).trim()
        val firstHan = caption.indexOfFirst { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        if (firstHan <= 0) return null
        val english = caption.substring(0, firstHan).trimEnd()
        val translated = caption.substring(firstHan)
        if (english.isEmpty() || english.first() !in 'A'..'Z' || english.none { it in 'A'..'Z' } ||
            english.any { it !in 'A'..'Z' && !it.isWhitespace() && it !in "/-&'()" }) return null
        if (translated.none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } ||
            translated.any { Character.UnicodeScript.of(it.code) != Character.UnicodeScript.HAN && !it.isWhitespace() && it !in "/、（）" }) return null
        at = comma + 1
        if (at >= line.end || !source[at].isWhitespace()) return null
        while (at < line.end && source[at].isWhitespace()) at++
        // Keep ambiguous bracketed/qualified, numbered, example-only or uppercase continuations as a prefix.
        if (at >= line.end || source[at] !in 'a'..'z') return null
        if (startsWithUsageLabel(source, at, line.end)) return null
        return at
    }

    private fun startsWithUsageLabel(source: String, start: Int, end: Int): Boolean = usageLabels.any { label ->
        val after = start + label.length
        after <= end && source.regionMatches(start, label, 0, label.length) &&
            (after == end || !Character.isUnicodeIdentifierPart(source[after]))
    }

    private fun isHeadwordAndPronunciation(line: String, word: String): Boolean {
        val slash = line.indexOf('/')
        if (slash <= 0 || slash == line.lastIndex || line.last() != '/') return false
        val head = line.substring(0, slash).trimEnd()
        var end = head.length
        while (end > 0 && head[end - 1] in SUPERSCRIPTS) end--
        val comparableHead = head.substring(0, end).trimEnd()
        if (!comparableHead.equals(word, ignoreCase = true)) {
            // Only comparison removes a supported syllable dot between letters (including observed
            // U+2219). Literal spaced dots and the
            // lookup headword itself remain unchanged; exact spelling was checked first.
            val syllablesJoined = buildString {
                comparableHead.forEachIndexed { index, char ->
                    if (char !in SYLLABLE_SEPARATORS || index == 0 || index == comparableHead.lastIndex ||
                        !comparableHead[index - 1].isLetter() || !comparableHead[index + 1].isLetter()) append(char)
                }
            }
            if (!syllablesJoined.equals(word, ignoreCase = true)) return false
        }
        val pronunciation = line.substring(slash + 1, line.lastIndex)
        return pronunciation.any { it.isLetter() } && pronunciation.all { char ->
            char in 'a'..'z' || char in 'A'..'Z' || char.code in 0x00C0..0x02FF || char in "θβχ" ||
                Character.getType(char) == Character.NON_SPACING_MARK.toInt() || char.isWhitespace() || char in ";:.'-"
        }
    }

    private fun leadingLines(source: String): List<Line> {
        val end = minOf(source.length, SCAN_CHARACTERS)
        val result = ArrayList<Line>(SCAN_LINES)
        var start = 0; var at = 0
        while (at < end && result.size < SCAN_LINES) {
            if (isLineBreak(source[at])) {
                result += Line(start, at, true)
                if (source[at] == '\r' && at + 1 < source.length && source[at + 1] == '\n') at++
                start = at + 1
            }
            at++
        }
        if (result.size < SCAN_LINES && start < end) result += Line(start, end, end == source.length)
        return result
    }

    private fun isLineBreak(char: Char) = char == '\r' || char == '\n' || char == '\u0085' || char == '\u2028' || char == '\u2029'
}
