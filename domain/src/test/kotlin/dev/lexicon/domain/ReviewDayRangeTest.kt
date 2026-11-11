package dev.lexicon.domain

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReviewDayRangeTest {
    private fun time(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun `day uses the local date rather than the UTC date`() {
        val range = ReviewDayRange.from(time("2026-11-04T18:30:00Z"), ZoneId.of("Asia/Shanghai"))
        assertEquals(time("2026-11-04T16:00:00Z"), range.startInclusive)
        assertEquals(time("2026-11-05T16:00:00Z"), range.endExclusive)
    }

    @Test fun `spring clock transition produces a 23 hour calendar day`() {
        val range = ReviewDayRange.from(time("2026-03-08T16:00:00Z"), ZoneId.of("America/New_York"))
        assertEquals(time("2026-03-08T05:00:00Z"), range.startInclusive)
        assertEquals(time("2026-03-09T04:00:00Z"), range.endExclusive)
        assertEquals(23 * 3_600_000L, range.endExclusive - range.startInclusive)
    }

    @Test fun `autumn clock transition includes both occurrences of the repeated hour`() {
        val zone = ZoneId.of("America/New_York")
        val first = ReviewDayRange.from(time("2026-11-01T05:30:00Z"), zone)
        val repeated = ReviewDayRange.from(time("2026-11-01T06:30:00Z"), zone)
        assertEquals(first, repeated)
        assertEquals(time("2026-11-01T04:00:00Z"), first.startInclusive)
        assertEquals(time("2026-11-02T05:00:00Z"), first.endExclusive)
        assertEquals(25 * 3_600_000L, first.endExclusive - first.startInclusive)
    }

    @Test fun `adjacent days share one exclusive inclusive midnight boundary`() {
        val zone = ZoneId.of("America/New_York")
        val day = ReviewDayRange.from(time("2026-03-08T16:00:00Z"), zone)
        assertEquals(day, ReviewDayRange.from(day.endExclusive - 1, zone))
        val next = ReviewDayRange.from(day.endExclusive, zone)
        assertEquals(day.endExclusive, next.startInclusive)
        assertEquals(24 * 3_600_000L, next.endExclusive - next.startInclusive)
    }

    @Test fun `timezone change recomputes the day for the same instant`() {
        val now = time("2026-11-04T18:30:00Z")
        val shanghai = ReviewDayRange.from(now, ZoneId.of("Asia/Shanghai"))
        val newYork = ReviewDayRange.from(now, ZoneId.of("America/New_York"))
        assertNotEquals(shanghai, newYork)
        assertEquals(time("2026-11-04T05:00:00Z"), newYork.startInclusive)
        assertEquals(time("2026-11-05T05:00:00Z"), newYork.endExclusive)
        assertTrue(now >= shanghai.startInclusive && now < shanghai.endExclusive)
        assertTrue(now >= newYork.startInclusive && now < newYork.endExclusive)
    }

    @Test fun `empty and reversed windows are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ReviewDayRange(1, 1) }
        assertThrows(IllegalArgumentException::class.java) { ReviewDayRange(2, 1) }
    }
}
