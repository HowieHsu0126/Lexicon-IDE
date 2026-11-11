package dev.lexicon.ide.ui

import androidx.compose.ui.text.TextLayoutResult
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Content coordinates for every line touched by a half-open active character range. */
internal data class SpeedActiveRangeBounds(val topPx: Float, val bottomPx: Float)

internal fun speedActiveRangeBounds(layout: TextLayoutResult, start: Int, endExclusive: Int): SpeedActiveRangeBounds? {
    if (layout.lineCount <= 0 || start < 0 || endExclusive <= start || endExclusive > layout.layoutInput.text.length) return null
    val first = layout.getLineForOffset(start)
    // Using the exclusive end itself would incorrectly include a following line at a boundary.
    val last = layout.getLineForOffset(endExclusive - 1)
    val top = layout.getLineTop(first)
    val bottom = layout.getLineBottom(last)
    return if (top.isFinite() && bottom.isFinite() && bottom > top) SpeedActiveRangeBounds(top, bottom) else null
}

/** A one-shot reveal target. Calling code owns when a new block/layout may request a reveal. */
internal fun speedRangeScrollTarget(
    bounds: SpeedActiveRangeBounds,
    viewportHeight: Int,
    currentScroll: Int,
    maxScroll: Int,
): Int? {
    if (!bounds.topPx.isFinite() || !bounds.bottomPx.isFinite() || bounds.bottomPx <= bounds.topPx ||
        viewportHeight <= 0 || maxScroll < 0 || maxScroll == Int.MAX_VALUE || currentScroll !in 0..maxScroll) return null
    val top = bounds.topPx.toDouble()
    val bottom = bounds.bottomPx.toDouble()
    val height = viewportHeight.toDouble()
    val current = currentScroll.toDouble()
    val tolerance = 1.0 // Text lines use floats; ScrollState positions use integer pixels.
    if (top >= current - tolerance && bottom <= current + height + tolerance) return null

    val target = if (bottom - top > height + tolerance) {
        // An oversized block cannot fit. Show its beginning once, then let the reader scroll.
        floor(top).coerceIn(0.0, maxScroll.toDouble()).toInt()
    } else {
        val preferred = (top - height * 0.30).roundToInt()
        var lower = ceil(bottom - height).coerceAtLeast(0.0)
        var upper = floor(top).coerceAtMost(maxScroll.toDouble())
        if (lower > upper) {
            // A viewport-height block at a fractional coordinate may have no exact integer fit.
            // Relax only that rounding case, never a normal bottom constraint by a whole pixel.
            lower = ceil(bottom - height - tolerance).coerceAtLeast(0.0)
            upper = floor(top + tolerance).coerceAtMost(maxScroll.toDouble())
        }
        if (lower > upper) return null
        preferred.toDouble().coerceIn(lower, upper).toInt()
    }
    return target.takeIf { it != currentScroll }
}
