package dev.lexicon.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Character offsets remain in the original UTF-16 text; focus never rewrites the article. */
data class ReadingParagraph(val start: Int, val endExclusive: Int)

/** Bounds use the text field's local pixel coordinate space. */
data class ReadingParagraphBounds(val index: Int, val top: Float, val bottom: Float)

data class ReadingViewport(val top: Float, val bottom: Float) {
    val valid: Boolean get() = top.isFinite() && bottom.isFinite() && bottom > top
}

object ParagraphFocus {
    private val separator = Regex("(?:\\r?\\n[ \\t]*){2,}|\\u2029+")

    fun paragraphs(text: CharSequence): List<ReadingParagraph> = buildList {
        fun addRange(from: Int, to: Int) {
            var start = from
            var end = to
            while (start < end && text[start].isWhitespace()) start++
            while (end > start && text[end - 1].isWhitespace()) end--
            if (start < end) add(ReadingParagraph(start, end))
        }
        var start = 0
        for (match in separator.findAll(text)) {
            addRange(start, match.range.first)
            start = match.range.last + 1
        }
        addRange(start, text.length)
    }

    /**
     * Prefer the paragraph at a stable physical reading line, 42% down the viewport.
     * Keep the current paragraph near a boundary, including the whitespace between paragraphs.
     * Tall paragraphs remain focused while the reading line is anywhere inside them.
     */
    fun choose(
        bounds: List<ReadingParagraphBounds>,
        viewport: ReadingViewport,
        previous: Int?,
        anchorFraction: Float = 0.42f,
        hysteresis: Float = 24f,
    ): Int? {
        if (!viewport.valid || bounds.isEmpty()) return previous
        val visible = bounds.filter { it.top.isFinite() && it.bottom.isFinite() &&
            it.bottom > it.top && it.bottom > viewport.top && it.top < viewport.bottom }
        if (visible.isEmpty()) return previous
        val anchor = viewport.top + (viewport.bottom - viewport.top) * anchorFraction.coerceIn(0f, 1f)
        val previousBounds = visible.find { it.index == previous }
        val margin = hysteresis.coerceAtLeast(0f)
        if (previousBounds != null && anchor >= previousBounds.top - margin && anchor <= previousBounds.bottom + margin) {
            return previous
        }
        fun distance(item: ReadingParagraphBounds): Float = when {
            anchor < item.top -> item.top - anchor
            anchor > item.bottom -> anchor - item.bottom
            else -> 0f
        }
        return visible.minWithOrNull(compareBy<ReadingParagraphBounds> { distance(it) }
            .thenBy { abs((max(it.top, viewport.top) + min(it.bottom, viewport.bottom)) / 2f - anchor) }
            .thenBy { it.index })?.index
    }

    fun atOffset(paragraphs: List<ReadingParagraph>, offset: Int): Int? {
        if (paragraphs.isEmpty()) return null
        val inside = paragraphs.indexOfFirst { offset >= it.start && offset < it.endExclusive }
        if (inside >= 0) return inside
        // Taps in an inter-paragraph gap choose the closest paragraph, without changing selection.
        return paragraphs.indices.minByOrNull { index ->
            val paragraph = paragraphs[index]
            min(abs(offset.toLong() - paragraph.start), abs(offset.toLong() - paragraph.endExclusive))
        }
    }
}
