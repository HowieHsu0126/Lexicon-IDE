package dev.lexicon.domain

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.TimeZone
import kotlin.math.max

class Fsrs6SchedulerTest {
    private val scheduler = Fsrs6Scheduler()
    private val json = Json { ignoreUnknownKeys = false }
    private val start = Instant.parse("2026-10-28T00:00:00Z").toEpochMilli()

    @Test fun `matches independently frozen py-fsrs transitions and recall probes`() {
        val text = requireNotNull(javaClass.getResourceAsStream("/fsrs6_reference.json")).bufferedReader().use { it.readText() }
        val fixtures = json.parseToJsonElement(text).jsonObject["cases"]!!.jsonArray
        assertTrue("The fixture should cover at least 600 transitions", fixtures.size >= 600)
        for (entry in fixtures) {
            val fixture = entry.jsonObject
            val label = fixture["case"]!!.jsonPrimitive.content
            val before = json.decodeFromJsonElement<FsrsState>(fixture["before"]!!)
            val expected = json.decodeFromJsonElement<FsrsState>(fixture["after"]!!)
            val rating = Rating.valueOf(fixture["rating"]!!.jsonPrimitive.content)
            val actual = scheduler.review(before, rating, fixture["now"]!!.jsonPrimitive.long)
            assertEquals("$label phase", expected.phase, actual.phase)
            assertEquals("$label step", expected.step, actual.step)
            assertEquals("$label due", expected.dueAt, actual.dueAt)
            assertEquals("$label last review", expected.lastReviewAt, actual.lastReviewAt)
            assertEquals("$label repetitions", expected.reps, actual.reps)
            assertEquals("$label lapses", expected.lapses, actual.lapses)
            assertEquals("$label stability", expected.stability!!, actual.stability!!, max(1.0, expected.stability) * 1e-10)
            assertEquals("$label difficulty", expected.difficulty!!, actual.difficulty!!, 1e-10)
            for (probe in fixture["probes"]!!.jsonArray) {
                val fields = probe.jsonObject
                assertEquals("$label recall", fields["r"]!!.jsonPrimitive.double,
                    scheduler.retrievability(actual, fields["at"]!!.jsonPrimitive.long)!!, 1e-10)
            }
        }
    }

    @Test fun `new card has unknown recall and expected learning intervals`() {
        assertNull(scheduler.retrievability(FsrsState(), start))
        val intervals = listOf(60_000L, 330_000L, 600_000L, 8 * Fsrs6Scheduler.DAY_MS)
        Rating.entries.forEachIndexed { i, rating ->
            val result = scheduler.review(FsrsState(), rating, start)
            assertEquals(start + intervals[i], result.dueAt)
            assertEquals(1, result.reps)
            assertEquals(0, result.lapses)
        }
    }

    @Test fun `same day hard never shrinks stability`() {
        val learned = scheduler.review(FsrsState(), Rating.EASY, start)
        val hard = scheduler.review(learned, Rating.HARD, start + 60_000)
        assertTrue(hard.stability!! >= learned.stability!!)
    }

    @Test fun `again in review counts one lapse while relearning again does not`() {
        val learned = scheduler.review(FsrsState(), Rating.EASY, start)
        val failed = scheduler.review(learned, Rating.AGAIN, learned.dueAt)
        val repeatedFailure = scheduler.review(failed, Rating.AGAIN, failed.dueAt)
        assertEquals(CardPhase.RELEARNING, failed.phase)
        assertEquals(1, failed.lapses)
        assertEquals(1, repeatedFailure.lapses)
        assertEquals(3, repeatedFailure.reps)
    }

    @Test fun `timezone and DST do not change scheduling for the same instants`() {
        val original = TimeZone.getDefault()
        try {
            val reviewAt = Instant.parse("2026-11-01T08:30:00Z").toEpochMilli()
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val west = scheduler.review(FsrsState(), Rating.GOOD, reviewAt)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            assertEquals(west, scheduler.review(FsrsState(), Rating.GOOD, reviewAt))
        } finally { TimeZone.setDefault(original) }
    }

    @Test(expected = IllegalArgumentException::class) fun `time reversal requires clock correction`() {
        val learned = scheduler.review(FsrsState(), Rating.GOOD, start)
        scheduler.review(learned, Rating.GOOD, start - 1)
    }

    @Test(expected = IllegalArgumentException::class) fun `corrupt state is rejected`() {
        scheduler.review(FsrsState(phase = CardPhase.REVIEW, stability = Double.NaN, difficulty = 4.0, lastReviewAt = start), Rating.GOOD, start)
    }
}
