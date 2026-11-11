package dev.lexicon.domain

import java.text.BreakIterator
import java.util.Locale

data class SelectionSnapshot(
    val selectedText: String, val sentence: String,
    val start: Int, val end: Int, val sentenceStart: Int, val sentenceEnd: Int,
)

object TextCapture {
    /** Positions use UTF-16, matching Compose TextRange; never locate a selection with indexOf. */
    fun extract(text: String, start: Int, end: Int): SelectionSnapshot {
        val from = minOf(start, end)
        val to = maxOf(start, end)
        require(from >= 0 && to <= text.length && from < to) { "请选择正文中的词或短语" }
        require(!splitsSurrogate(text, from) && !splitsSurrogate(text, to)) { "选区不能拆分 Unicode 字符" }
        val selected = text.substring(from, to)
        require(selected.isNotBlank()) { "选区不能为空" }
        val iterator = BreakIterator.getSentenceInstance(Locale.ENGLISH)
        iterator.setText(text)
        var sentenceStart = if (iterator.isBoundary(from)) from else iterator.preceding(from)
        if (sentenceStart == BreakIterator.DONE) sentenceStart = 0
        var sentenceEnd = if (iterator.isBoundary(to)) to else iterator.following(to)
        if (sentenceEnd == BreakIterator.DONE) sentenceEnd = text.length

        // Bound punctuation-free paragraphs without losing a legitimate cross-paragraph selection.
        val previousParagraph = if (from >= 2) text.lastIndexOf("\n\n", from - 2) else -1
        if (previousParagraph >= 0) sentenceStart = maxOf(sentenceStart, previousParagraph + 2)
        val nextParagraph = text.indexOf("\n\n", to)
        if (nextParagraph >= 0) sentenceEnd = minOf(sentenceEnd, nextParagraph)

        // Common English honorifics are not separate sentences. This remains a reviewable heuristic.
        if (sentenceStart > 0) {
            val previous = iterator.preceding(sentenceStart).takeIf { it != BreakIterator.DONE } ?: 0
            if (ABBREVIATION.containsMatchIn(text.substring(previous, sentenceStart).trimEnd())) sentenceStart = previous
        }
        while (sentenceStart < from && text[sentenceStart].isWhitespace()) sentenceStart++
        while (sentenceEnd > to && text[sentenceEnd - 1].isWhitespace()) sentenceEnd--
        return SelectionSnapshot(selected, text.substring(sentenceStart, sentenceEnd), from, to, sentenceStart, sentenceEnd)
    }

    private fun splitsSurrogate(text: String, position: Int): Boolean =
        position > 0 && position < text.length && text[position - 1].isHighSurrogate() && text[position].isLowSurrogate()

    private val ABBREVIATION = Regex("\\b(?:Mr|Mrs|Ms|Dr|Prof|St|vs|etc|e\\.g|i\\.e)\\.$", RegexOption.IGNORE_CASE)
}
