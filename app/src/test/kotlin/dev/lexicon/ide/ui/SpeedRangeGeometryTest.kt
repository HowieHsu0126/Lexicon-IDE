package dev.lexicon.ide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Numeric expectations frozen by root before implementation; no Android/font measurement here. */
class SpeedRangeGeometryTest {
    @Test fun alreadyVisibleBlockDoesNotMove() {
        assertNull(speedRangeScrollTarget(SpeedActiveRangeBounds(100f, 200f), 300, 0, 1000))
    }

    @Test fun aTallFittingBlockClampsTheReadingLineToRevealItsTail() {
        assertEquals(450, speedRangeScrollTarget(SpeedActiveRangeBounds(500f, 750f), 300, 0, 1000))
    }

    @Test fun aShortBlockCanKeepTheThirtyPercentReadingLine() {
        assertEquals(410, speedRangeScrollTarget(SpeedActiveRangeBounds(500f, 630f), 300, 0, 1000))
    }

    @Test fun backwardRevealAlsoKeepsTheReadingLineWhenThereIsRoom() {
        assertEquals(10, speedRangeScrollTarget(SpeedActiveRangeBounds(100f, 250f), 300, 300, 1000))
    }

    @Test fun anOversizedBlockStartsAtItsFirstLine() {
        assertEquals(500, speedRangeScrollTarget(SpeedActiveRangeBounds(500f, 900f), 300, 0, 1000))
    }

    @Test fun documentStartClampsToZero() {
        assertEquals(0, speedRangeScrollTarget(SpeedActiveRangeBounds(0f, 300f), 300, 100, 1000))
    }

    @Test fun documentEndClampsToTheMeasuredMaximum() {
        assertEquals(700, speedRangeScrollTarget(SpeedActiveRangeBounds(900f, 1000f), 300, 0, 700))
    }

    @Test fun viewportHeightFractionalBlockHasAtMostOnePixelOfEdgeError() {
        val target = speedRangeScrollTarget(SpeedActiveRangeBounds(100.5f, 400.5f), 300, 0, 1000)
        assertNotNull(target)
        // These edge bounds come directly from the frozen 100.5/400.5/300 example.
        val actual = requireNotNull(target)
        assertTrue(actual >= 100)
        assertTrue(actual <= 101)
        assertTrue(100.5f >= actual - 1f)
        assertTrue(400.5f <= actual + 300f + 1f)
        assertNull(speedRangeScrollTarget(SpeedActiveRangeBounds(100.5f, 400.5f), 300, 100, 1000))
        assertNull(speedRangeScrollTarget(SpeedActiveRangeBounds(100.5f, 400.5f), 300, 101, 1000))
    }

    @Test fun aOnePixelMoveCanStillCorrectClippingAboveTolerance() {
        // At scroll 99 the viewport ends at 399: the range is clipped by 1.5 px.
        // One pixel of movement leaves only 0.5 px, inside the visibility tolerance.
        assertEquals(100, speedRangeScrollTarget(SpeedActiveRangeBounds(100.5f, 400.5f), 300, 99, 1000))
    }

    @Test fun invalidRangesAndUnmeasuredViewportsDoNotRequestScrolling() {
        listOf(
            SpeedActiveRangeBounds(Float.NaN, 200f),
            SpeedActiveRangeBounds(100f, Float.POSITIVE_INFINITY),
            SpeedActiveRangeBounds(Float.NEGATIVE_INFINITY, 200f),
            SpeedActiveRangeBounds(200f, 100f),
            SpeedActiveRangeBounds(100f, 100f),
        ).forEach { assertNull(speedRangeScrollTarget(it, 300, 0, 1000)) }
        val valid = SpeedActiveRangeBounds(500f, 750f)
        assertNull(speedRangeScrollTarget(valid, 0, 0, 1000))
        assertNull(speedRangeScrollTarget(valid, -1, 0, 1000))
        assertNull(speedRangeScrollTarget(valid, 300, 0, Int.MAX_VALUE))
        assertNull(speedRangeScrollTarget(valid, 300, 0, -1))
        assertNull(speedRangeScrollTarget(valid, 300, -1, 1000))
        assertNull(speedRangeScrollTarget(valid, 300, 1001, 1000))
    }
}
