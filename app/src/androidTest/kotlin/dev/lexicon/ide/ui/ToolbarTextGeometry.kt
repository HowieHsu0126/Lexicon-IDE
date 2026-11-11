package dev.lexicon.ide.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult

/**
 * Shared androidTest observation for the fixed, short toolbar labels.
 * Compare every returned line/code-point box with the final allocated Text node. Keep Compose's
 * paragraph-width flags as diagnostics: SimpleText semantics can rebuild a wider MultiParagraph.
 * This does not replace the enclosing button/viewport checks, screenshots or real-touch checks.
 */
internal fun inspectToolbarTextGeometry(
    expectedText: String,
    layout: TextLayoutResult?,
    textRawBounds: Rect,
    textClippedBounds: Rect,
): ToolbarTextGeometry {
    require(expectedText.isNotEmpty() && expectedText.none { it == '\n' || it == '\r' }) {
        "This observer is only for nonempty toolbar labels without explicit line breaks"
    }
    if (layout == null) return ToolbarTextGeometry(layoutAvailable = false)
    val width = layout.size.width.toFloat()
    val height = layout.size.height.toFloat()
    val allocated = Rect(0f, 0f, width, height)
    val nodeWidth = textRawBounds.width
    val nodeHeight = textRawBounds.height
    val allocationNonempty = width > 0f && height > 0f && nodeWidth > 0f && nodeHeight > 0f &&
        finiteRect(textRawBounds) && finiteRect(textClippedBounds)
    val nodeLayoutAgree = allocationNonempty &&
        kotlin.math.abs(nodeWidth - width) <= TOOLBAR_TEXT_TOLERANCE &&
        kotlin.math.abs(nodeHeight - height) <= TOOLBAR_TEXT_TOLERANCE
    val nodeUnclipped = allocationNonempty && within(textRawBounds, textClippedBounds)
    val matches = layout.layoutInput.text.text == expectedText

    var previousEnd = 0
    var coverageComplete = layout.lineCount > 0
    val lines = (0 until layout.lineCount).map { line ->
        val start = layout.getLineStart(line)
        // Include the soft-wrap whitespace in the coverage check, not only visible glyphs.
        val end = layout.getLineEnd(line, visibleEnd = false)
        val contiguous = start == previousEnd && start in 0..expectedText.length && end in start..expectedText.length && end > start
        previousEnd = end
        coverageComplete = coverageComplete && contiguous
        val bounds = Rect(layout.getLineLeft(line), layout.getLineTop(line), layout.getLineRight(line), layout.getLineBottom(line))
        ToolbarLabelLine(line, start, end, bounds, layout.isLineEllipsized(line), contiguous,
            allocationNonempty && within(bounds, allocated))
    }
    coverageComplete = coverageComplete && previousEnd == expectedText.length

    val glyphs = buildList {
        if (matches) {
            var offset = 0
            while (offset < expectedText.length) {
                val codePoint = Character.codePointAt(expectedText, offset)
                val end = offset + Character.charCount(codePoint)
                val box = layout.getBoundingBox(offset)
                val line = layout.getLineForOffset(offset)
                val kind = Character.getType(codePoint)
                val needsArea = !Character.isWhitespace(codePoint) && kind != Character.NON_SPACING_MARK.toInt() &&
                    kind != Character.COMBINING_SPACING_MARK.toInt() && kind != Character.ENCLOSING_MARK.toInt() &&
                    kind != Character.FORMAT.toInt()
                val hasRequiredArea = !needsArea || (box.width > 0f && box.height > 0f)
                val coveredByLine = line in lines.indices && offset >= lines[line].start && end <= lines[line].end
                add(ToolbarLabelGlyph(offset, end, line, box, hasRequiredArea, coveredByLine,
                    allocationNonempty && within(box, allocated)))
                offset = end
            }
        }
    }
    val noEllipsis = lines.isNotEmpty() && lines.none { it.ellipsized }
    val lineBoxesFit = lines.isNotEmpty() && lines.all { it.insideAllocation }
    val glyphBoxesFit = glyphs.isNotEmpty() && glyphs.all { it.hasRequiredArea && it.coveredByLine && it.insideAllocation }
    return ToolbarTextGeometry(
        layoutAvailable = true,
        matchesExpectedText = matches,
        allocationNonempty = allocationNonempty,
        nodeLayoutAgree = nodeLayoutAgree,
        nodeUnclipped = nodeUnclipped,
        completeCharacterCoverage = coverageComplete,
        noEllipsis = noEllipsis,
        lineBoxesFit = lineBoxesFit,
        glyphBoxesFit = glyphBoxesFit,
        lines = lines,
        glyphs = glyphs,
        diagnostics = ToolbarTextDiagnostics(
            width = layout.size.width,
            height = layout.size.height,
            paragraphWidth = layout.multiParagraph.width,
            paragraphHeight = layout.multiParagraph.height,
            paragraphExceededMaxLines = layout.multiParagraph.didExceedMaxLines,
            constraintMinWidth = layout.layoutInput.constraints.minWidth,
            constraintMaxWidth = layout.layoutInput.constraints.maxWidth,
            constraintMinHeight = layout.layoutInput.constraints.minHeight,
            constraintMaxHeight = layout.layoutInput.constraints.maxHeight,
            didOverflowWidth = layout.didOverflowWidth,
            didOverflowHeight = layout.didOverflowHeight,
            hasVisualOverflow = layout.hasVisualOverflow,
        ),
    )
}

internal data class ToolbarTextGeometry(
    val layoutAvailable: Boolean,
    val matchesExpectedText: Boolean = false,
    val allocationNonempty: Boolean = false,
    val nodeLayoutAgree: Boolean = false,
    val nodeUnclipped: Boolean = false,
    val completeCharacterCoverage: Boolean = false,
    val noEllipsis: Boolean = false,
    val lineBoxesFit: Boolean = false,
    val glyphBoxesFit: Boolean = false,
    val lines: List<ToolbarLabelLine> = emptyList(),
    val glyphs: List<ToolbarLabelGlyph> = emptyList(),
    val diagnostics: ToolbarTextDiagnostics? = null,
) {
    val completeInsideAllocatedNode: Boolean get() = layoutAvailable && matchesExpectedText && allocationNonempty &&
        nodeLayoutAgree && nodeUnclipped && completeCharacterCoverage && noEllipsis && lineBoxesFit && glyphBoxesFit
}

internal data class ToolbarLabelLine(
    val index: Int, val start: Int, val end: Int, val bounds: Rect,
    val ellipsized: Boolean, val contiguousCoverage: Boolean, val insideAllocation: Boolean,
)
internal data class ToolbarLabelGlyph(
    val start: Int, val end: Int, val line: Int, val bounds: Rect,
    val hasRequiredArea: Boolean, val coveredByLine: Boolean, val insideAllocation: Boolean,
)
internal data class ToolbarTextDiagnostics(
    val width: Int, val height: Int, val paragraphWidth: Float, val paragraphHeight: Float,
    val paragraphExceededMaxLines: Boolean,
    val constraintMinWidth: Int, val constraintMaxWidth: Int, val constraintMinHeight: Int, val constraintMaxHeight: Int,
    val didOverflowWidth: Boolean, val didOverflowHeight: Boolean, val hasVisualOverflow: Boolean,
)

private const val TOOLBAR_TEXT_TOLERANCE = 1f
private fun finiteRect(rect: Rect) = rect.left.isFinite() && rect.top.isFinite() && rect.right.isFinite() && rect.bottom.isFinite()
private fun within(inner: Rect, outer: Rect) = finiteRect(inner) && finiteRect(outer) &&
    inner.width >= 0f && inner.height >= 0f && outer.width > 0f && outer.height > 0f &&
    inner.left >= outer.left - TOOLBAR_TEXT_TOLERANCE && inner.top >= outer.top - TOOLBAR_TEXT_TOLERANCE &&
    inner.right <= outer.right + TOOLBAR_TEXT_TOLERANCE && inner.bottom <= outer.bottom + TOOLBAR_TEXT_TOLERANCE
