package dev.lexicon.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NaturalReadingTest {
    private val text = "The committee considers a library. Residents compare the plans.\n\nVolunteers explain the opening hours. A report records their comments."
    private val plan = SpeedChunker.plan("article", "body", text, SpeedGranularity.L2, 0, 2).let {
        it.copy(planKey = SpeedChunker.planKey(it))
    }
    private fun attempt() = NaturalReadingAttempt("natural", plan.articleId, plan.bodyHash, plan.planKey, "faded", 1000, 31_000,
        listOf(NaturalParagraphTiming(0, 4000, true), NaturalParagraphTiming(1, 6000, true)), true, 1, 0)

    @Test fun completeNaturalReadingUsesActualActiveTimeAndFrozenTextWords() {
        val summary = NaturalReadingMetrics.summarize(attempt(), plan)
        val words = SpeedChunker.wordCount(text)
        assertEquals(10_000L, summary.activeMillis)
        assertEquals(2, summary.completedParagraphs); assertEquals(2, summary.totalParagraphs)
        assertEquals(words, summary.wordsRead)
        assertEquals(words * 6.0, summary.wordsPerMinute!!, 0.0)
        // The 20-second gap in wall-clock time is not assigned to a paragraph or a block.
        assertEquals(summary, NaturalReadingMetrics.summarize(attempt().copy(finishedAt = 61_000), plan))
    }

    @Test fun partialOrNotConfirmedReadingHasNoSpeedEstimate() {
        val partial = attempt().copy(completed = false,
            paragraphs = listOf(NaturalParagraphTiming(0, 4000, true), NaturalParagraphTiming(1, 1000, false)))
        val summary = NaturalReadingMetrics.summarize(partial, plan)
        assertEquals(5000L, summary.activeMillis); assertEquals(1, summary.completedParagraphs)
        assertEquals(plan.chunks.filter { it.paragraph == 0 }.sumOf { it.wordCount }, summary.wordsRead)
        assertNull(summary.wordsPerMinute)
        assertNull(NaturalReadingMetrics.summarize(attempt().copy(completed = false), plan).wordsPerMinute)
        val unopened = attempt().copy(completed = false, paragraphs = plan.paragraphIndices.map { NaturalParagraphTiming(it, 0, false) })
        assertEquals(0, NaturalReadingMetrics.summarize(unopened, plan).wordsRead)
        assertNull(NaturalReadingMetrics.summarize(unopened, plan).wordsPerMinute)
    }

    @Test fun impossibleAccountingAndWrongSourceCannotProduceNaturalMetrics() {
        val original = attempt()
        val invalid = listOf(original.copy(articleId = "other"), original.copy(bodyHash = "other"), original.copy(planKey = "other"),
            original.copy(paragraphs = original.paragraphs.reversed()), original.copy(paragraphs = original.paragraphs.take(1)),
            original.copy(paragraphs = List(2) { original.paragraphs.first() }), original.copy(startedAt = -1),
            original.copy(finishedAt = 999), original.copy(finishedAt = 1001), original.copy(pauses = -1),
            original.copy(paragraphs = original.paragraphs.map { it.copy(activeMillis = 0) }),
            original.copy(paragraphs = original.paragraphs.map { it.copy(completed = false) }),
            original.copy(paragraphs = original.paragraphs.map { it.copy(activeMillis = Long.MAX_VALUE) }))
        invalid.forEach { candidate ->
            try { NaturalReadingMetrics.summarize(candidate, plan); fail("Invalid natural attempt") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun naturalSerializationDoesNotInventPacedGroupsAnswersOrFirstAttemptStatus() {
        val payload = Json.encodeToString(attempt())
        assertEquals(attempt(), Json.decodeFromString<NaturalReadingAttempt>(payload))
        listOf("groupMillis", "groupsPerMinute", "firstAttempt", "answers", "pacedCompletion", "wordsRead").forEach {
            assertFalse(payload.contains("\"$it\""))
        }
        val library = SpeedLibrary(naturalAttempts = listOf(attempt()))
        assertTrue(library.runs.isEmpty()); assertTrue(library.corrections.isEmpty())
    }

    @Test fun legacySpeedRunsRemainGuidedAndLegacyLibraryHasNoNaturalAttempts() {
        val guided = SpeedRun("guided", plan.articleId, plan.bodyHash, plan.granularity, plan.planKey, 0, 1000, 1000,
            4, 1, listOf(1000), 0, 0, emptyList(), 0, true, 60, "rules")
        val codec = Json { encodeDefaults = true }
        val legacy = JsonObject(codec.encodeToJsonElement(guided).jsonObject - setOf("round", "parentRunId"))
        val restored = codec.decodeFromString<SpeedRun>(legacy.toString())
        assertEquals(SpeedPacedRound.GUIDED, restored.round); assertNull(restored.parentRunId)
        val oldLibrary = codec.decodeFromString<SpeedLibrary>("{\"runs\":[],\"corrections\":[]}")
        assertTrue(oldLibrary.naturalAttempts.isEmpty())
    }

    @Test fun fadedRunsCannotCreateFreshComprehensionEvenWithForgedFirstAttemptAndAnswers() {
        val faded = SpeedRun("faded", plan.articleId, plan.bodyHash, plan.granularity, plan.planKey, 0, 1000, 1000,
            4, 1, listOf(1000), 0, 0, listOf(SpeedAnswer("old-question", 0, true)), 1, true, 60, "ai",
            firstAttempt = true, pacingStable = true, pacedCompletion = true, round = SpeedPacedRound.FADED, parentRunId = "guided")
        val summary = SpeedMetrics.summarize(faded)
        assertNotNull(summary.wordsPerMinute)
        assertNull(summary.comprehension); assertNull(summary.effectiveWordsPerMinute)
    }
}
