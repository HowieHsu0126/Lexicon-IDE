package dev.lexicon.ide.ui

import androidx.compose.ui.text.TextLayoutResult
import dev.lexicon.domain.ReadingViewport
import kotlin.math.abs

/** Construct at onTextLayout, retaining the exact body and result; do not rebuild text or select it. */
internal fun readerReflowLayout(source: ReaderReflowSource, exactBody: String, layout: TextLayoutResult): ReaderReflowLayout? {
    if (layout.layoutInput.text.text != exactBody || exactBody.isEmpty() || layout.lineCount == 0) return null
    val input = layout.layoutInput
    if (input.constraints.maxWidth <= 0 || !input.density.density.isFinite() || input.density.density <= 0f ||
        !input.density.fontScale.isFinite() || input.density.fontScale <= 0f) return null
    val geometry = ReaderReflowGeometryKey(input.style, input.density.density, input.density.fontScale,
        input.constraints.maxWidth, input.layoutDirection)
    return object : ReaderReflowLayout {
        override val source = source
        override val key = geometry

        override fun anchorIn(viewport: ReadingViewport): ReaderReflowAnchor? {
            if (!viewport.valid) return null
            val height = viewport.bottom - viewport.top
            val readingY = viewport.top + height * .42f
            val readingLine = layout.getLineForVerticalPosition(readingY)
            val first = layout.getLineForVerticalPosition(viewport.top.coerceAtLeast(0f))
            val last = layout.getLineForVerticalPosition(viewport.bottom.coerceAtMost(layout.size.height.toFloat()))
            if (first > last) return null
            // A blank separator may sit at the reading line. Examine only lines in this viewport,
            // ordered by distance, never the whole article and never by a repeated word's text.
            val candidates = (first..last).filter { line ->
                layout.getLineTop(line) >= viewport.top && layout.getLineBottom(line) <= viewport.bottom
            // On an empty separator prefer the preceding equally near line. Font metrics must
            // not switch the chosen passage from the previous paragraph to the following one.
            }.sortedWith(compareBy<Int> { abs(it - readingLine) }.thenBy { it })
            for (line in candidates) {
                val start = layout.getLineStart(line)
                val end = layout.getLineEnd(line, visibleEnd = true)
                var offset = start
                while (offset < end) {
                    val codePoint = Character.codePointAt(exactBody, offset)
                    if (Character.isLetterOrDigit(codePoint)) {
                        val word = layout.getWordBoundary(offset)
                        if (!word.collapsed && word.min >= start && word.max <= end) {
                            return ReaderReflowAnchor(word.min, (layout.getLineTop(line) - viewport.top) / height)
                        }
                        // The word began on a previous line or continues onto the next one.
                        // Skip the partial word; the anchor must be a complete visible word.
                        offset = maxOf(offset + Character.charCount(codePoint), word.max)
                        continue
                    }
                    offset += Character.charCount(codePoint)
                }
            }
            return null
        }

        override fun lineForOffset(offset: Int): ReaderReflowLine? {
            if (offset !in exactBody.indices || (offset > 0 && exactBody[offset].isLowSurrogate() && exactBody[offset - 1].isHighSurrogate())) return null
            val line = layout.getLineForOffset(offset)
            return ReaderReflowLine(layout.getLineTop(line), layout.getLineBottom(line))
        }
    }
}
