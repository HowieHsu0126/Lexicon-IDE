package dev.lexicon.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SpeedInteractionRecapTest {
    private val chunks = buildList {
        var start = 0
        for (phrase in listOf("Clear evidence", "supports a cautious conclusion", "while additional measurements test the remaining alternative explanations")) {
            add(SenseChunk(start, start + phrase.length, 0, 0, SpeedChunker.wordCount(phrase), "phrase"))
            start += phrase.length + 1
        }
    }
    private val plan = SpeedPlan("article", "body-hash", SpeedGranularity.L2, listOf(0), chunks,
        origin = "rules", planKey = "plan")
    private val expected = chunks.map { SpeedPacing.durationMillis(it, 60, chunks.map { chunk -> chunk.wordCount }.average()) }
    private val once = List(chunks.size) { SpeedChunkInteraction(1, 0, 0) }

    @Test fun naturallyLongerAutoplayChunksNeverBecomeReviewCandidates() {
        assertTrue(expected.last() > expected.first())
        val summary = SpeedInteractionRecap.summarize(run(), plan)
        assertEquals(3, summary.observedChunks)
        assertTrue(summary.items.isEmpty())
        assertEquals(setOf(SpeedInteractionLimit.PLAYBACK_ONLY), summary.limitations)
        // A delayed frame or timer alone is also not evidence that the reader struggled.
        val delayed = SpeedInteractionRecap.summarize(run(durations = expected.map { it * 5 }), plan)
        assertTrue(delayed.items.isEmpty())
    }

    @Test fun eachChunkUsesItsOwnPlanDurationRatherThanRawCumulativeTime() {
        val summary = SpeedInteractionRecap.summarize(run(expected.map { it * 2 },
            List(3) { SpeedChunkInteraction(1, 1, 0) }), plan)
        assertEquals(listOf(0, 1, 2), summary.items.map { it.index })
        summary.items.forEach { item ->
            assertEquals(expected[item.index], item.plannedMillisPerVisit)
            assertEquals(2.0, requireNotNull(item.normalizedMeanDwell), .00001)
            assertEquals(expected[item.index] * 2.0, item.meanActiveMillis, .00001)
        }
        assertTrue(summary.items.last().activeMillis > summary.items.first().activeMillis)
    }

    @Test fun repeatedVisitsAreShownSeparatelyFromSingleVisitDwell() {
        val observations = listOf(SpeedChunkInteraction(3, 0, 2), SpeedChunkInteraction(1, 0, 0), SpeedChunkInteraction(1, 1, 0))
        val summary = SpeedInteractionRecap.summarize(run(listOf(expected[0] * 3, expected[1], expected[2] * 2), observations), plan)
        assertEquals(listOf(0, 2), summary.items.map { it.index })
        val repeated = summary.items.first()
        assertEquals(3, repeated.visits)
        assertEquals(2, repeated.revisits)
        assertEquals(expected[0] * 3, repeated.activeMillis)
        assertEquals(expected[0].toDouble(), repeated.meanActiveMillis, .00001)
        assertEquals(1.0, requireNotNull(repeated.normalizedMeanDwell), .00001)
        assertEquals(2.0, requireNotNull(summary.items.last().normalizedMeanDwell), .00001)
    }

    @Test fun globalLifecyclePausesDoNotBecomeUserPauseCandidates() {
        val summary = SpeedInteractionRecap.summarize(run().copy(pauses = 20), plan)
        assertTrue(summary.items.isEmpty())
        val paused = run(interactions = listOf(SpeedChunkInteraction(1, 1, 0), once[1], once[2])).copy(pauses = 20)
        assertEquals(1, SpeedInteractionRecap.summarize(paused, plan).items.single().pauses)
    }

    @Test fun changedOrUnknownPaceKeepsInteractionsButOmitsUnverifiableRatios() {
        val base = run(interactions = listOf(SpeedChunkInteraction(1, 1, 0), once[1], once[2]))
        for (known in listOf<Boolean?>(false, null)) {
            val summary = SpeedInteractionRecap.summarize(base.copy(pacingStable = known), plan)
            assertTrue(SpeedInteractionLimit.VARIABLE_PACE in summary.limitations)
            val item = summary.items.single()
            assertEquals(1, item.pauses)
            assertNull(item.plannedMillisPerVisit)
            assertNull(item.normalizedMeanDwell)
            assertEquals(expected[0].toDouble(), item.meanActiveMillis, .00001)
        }
    }

    @Test fun legacyRecordsDoNotInventOneVisitPerMeasuredChunk() {
        val codec = Json { encodeDefaults = true }
        val legacy = codec.encodeToString(run().copy(chunkInteractions = null)).replace(",\"chunkInteractions\":null", "")
        val restored = codec.decodeFromString<SpeedRun>(legacy)
        assertNull(restored.chunkInteractions)
        assertTrue(restored.groupMillis.all { it > 0 })
        val summary = SpeedInteractionRecap.summarize(restored, plan)
        assertTrue(summary.items.isEmpty())
        assertEquals(0, summary.observedChunks)
        assertTrue(SpeedInteractionLimit.MISSING_VISIT_DATA in summary.limitations)
    }

    @Test fun invalidOrMismatchedEvidenceProducesNoRankedClaims() {
        val base = run()
        val invalid = listOf(
            base.copy(chunkInteractions = once.dropLast(1)),
            base.copy(chunkInteractions = listOf(SpeedChunkInteraction(0, 0, 0), once[1], once[2])),
            base.copy(chunkInteractions = listOf(SpeedChunkInteraction(2, 0, 0), once[1], once[2])),
            base.copy(chunkInteractions = listOf(SpeedChunkInteraction(1, -1, 0), once[1], once[2])),
            base.copy(chunkInteractions = listOf(SpeedChunkInteraction(1, 1, 0), once[1], once[2]), pauses = 0),
            base.copy(groupMillis = base.groupMillis.dropLast(1)),
            base.copy(activeMillis = base.activeMillis + 1),
            base.copy(groupsRead = 1), base.copy(wordsRead = 999), base.copy(groupsPerMinute = 29),
        )
        invalid.forEach { candidate ->
            val summary = SpeedInteractionRecap.summarize(candidate, plan)
            assertTrue(summary.items.isEmpty())
            assertTrue(SpeedInteractionLimit.INVALID_TRACE in summary.limitations)
        }
        for (candidate in listOf(base.copy(articleId = "other"), base.copy(bodyHash = "changed"), base.copy(planKey = "other"))) {
            val summary = SpeedInteractionRecap.summarize(candidate, plan)
            assertTrue(summary.items.isEmpty())
            assertTrue(SpeedInteractionLimit.PLAN_MISMATCH in summary.limitations)
        }
    }

    @Test fun partialRunsReportOnlyActualStartedVisitsAndRetainThePartialBoundary() {
        val observations = listOf(SpeedChunkInteraction(1, 1, 0), SpeedChunkInteraction(0, 0, 0), SpeedChunkInteraction(0, 0, 0))
        val summary = SpeedInteractionRecap.summarize(run(listOf(expected.first(), 0, 0), observations).copy(completed = false), plan)
        assertEquals(1, summary.observedChunks)
        assertEquals(0, summary.items.single().index)
        assertTrue(SpeedInteractionLimit.PARTIAL_RUN in summary.limitations)
        assertTrue(SpeedInteractionLimit.PLAYBACK_ONLY in summary.limitations)
    }

    @Test fun instantPlayWithoutMeasuredTimeDoesNotBecomeDwellEvidence() {
        val summary = SpeedInteractionRecap.summarize(run(listOf(0, 0, 0),
            listOf(SpeedChunkInteraction(1, 1, 0), SpeedChunkInteraction(0, 0, 0), SpeedChunkInteraction(0, 0, 0))).copy(completed = false), plan)
        assertEquals(1, summary.observedChunks)
        assertTrue(summary.items.isEmpty())
        assertFalse(SpeedInteractionLimit.INVALID_TRACE in summary.limitations)
    }

    @Test fun recapLimitChangesPresentationWithoutChangingEvidenceOrCounts() {
        val candidate = run(interactions = List(3) { SpeedChunkInteraction(1, 1, 0) })
        assertEquals(3, SpeedInteractionRecap.summarize(candidate, plan).items.size)
        assertEquals(1, SpeedInteractionRecap.summarize(candidate, plan, 1).items.size)
        assertEquals(3, SpeedInteractionRecap.summarize(candidate, plan, 1).observedChunks)
        assertTrue(SpeedInteractionRecap.summarize(candidate, plan, 0).items.isEmpty())
    }

    private fun run(durations: List<Long> = expected, interactions: List<SpeedChunkInteraction> = once): SpeedRun {
        val active = durations.sum()
        return SpeedRun("run", plan.articleId, plan.bodyHash, plan.granularity, plan.planKey, 1_000, 1_000 + active,
            active, chunks.indices.filter { durations[it] > 0 }.sumOf { chunks[it].wordCount }, durations.count { it > 0 },
            durations, interactions.sumOf { it.revisits }, interactions.sumOf { it.pauses }, emptyList(), 0, true, 60, plan.origin,
            pacingStable = true, pacedCompletion = true, chunkInteractions = interactions)
    }
}
