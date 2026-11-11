package dev.lexicon.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PersonalizationOutcomeTest {
    private val suggestion = PaceSuggestion("decision", SpeedGranularity.L2, 60, 63,
        listOf("before-1", "before-2"), 6, 6, 2, "evidence", PersonalizationPolicy.RULE_VERSION)
    private val decision = PersonalizationDecision(suggestion.id, suggestion, PersonalizationDecisionStatus.APPLIED,
        decidedAt = 1_000, appliedRevision = 2)
    private val binding = AppliedPaceBinding(decision.id, 2, SpeedGranularity.L2, 63)
    private val profile = PersonalizationProfile(revision = 2, rates = mapOf(SpeedGranularity.L2 to 63), decisions = listOf(decision), activePaceBinding = binding)

    @Test fun onlyAnExplicitCurrentApplicationCanBindAtRunStart() {
        assertEquals(binding, PersonalizationOutcomes.bindingForStart(profile, SpeedGranularity.L2, 63, false))
        assertNull(PersonalizationOutcomes.bindingForStart(profile, SpeedGranularity.L2, 63, true))
        assertEquals(binding, PersonalizationOutcomes.bindingForStart(profile.copy(revision = 4, sentenceOnly = true), SpeedGranularity.L2, 63, false))
        assertNull(PersonalizationOutcomes.bindingForStart(profile.copy(revision = 4, activePaceBinding = null), SpeedGranularity.L2, 63, false))
        assertNull(PersonalizationOutcomes.bindingForStart(profile.copy(activePaceBinding = binding.copy(profileRevision = 3)), SpeedGranularity.L2, 63, false))
        assertNull(PersonalizationOutcomes.bindingForStart(profile, SpeedGranularity.L2, 60, false))
        assertNull(PersonalizationOutcomes.bindingForStart(profile, SpeedGranularity.L1, 63, false))
        assertNull(PersonalizationOutcomes.bindingForStart(profile.copy(enabled = false), SpeedGranularity.L2, 63, false))
        assertNull(PersonalizationOutcomes.bindingForStart(profile.copy(decisions = emptyList()), SpeedGranularity.L2, 63, false))
        assertNull(PersonalizationOutcomes.bindingForStart(profile.copy(decisions = listOf(decision.copy(status = PersonalizationDecisionStatus.UNDONE))),
            SpeedGranularity.L2, 63, false))
    }

    @Test fun sufficientBoundEvidenceReportsCountsAndObservedAccuracy() {
        val first = run(1).copy(pauses = 1, revisits = 2)
        val second = run(2, correct = 2).copy(pauses = 3, revisits = 4)
        val summary = PersonalizationOutcomes.summarize(decision, listOf(second, first))
        assertEquals(2, summary.sampleCount)
        assertEquals(2, summary.materialCount)
        assertEquals(5, summary.correct)
        assertEquals(6, summary.total)
        assertEquals(4, summary.pauses)
        assertEquals(6, summary.revisits)
        assertEquals(5.0 / 6, requireNotNull(summary.accuracy), .0001)
        assertTrue(summary.evidenceEnough)
        assertEquals(listOf(first.id, second.id), summary.evidenceRunIds)
        assertEquals(63, summary.rate)
        assertEquals(SpeedGranularity.L2, summary.granularity)
    }

    @Test fun matchingRateWithoutRunStartBindingIsNotAttributed() {
        val original = run(2)
        val invalid = listOf(
            original.copy(paceBinding = null),
            original.copy(paceBinding = binding.copy(decisionId = "other")),
            original.copy(paceBinding = binding.copy(profileRevision = 3)),
            original.copy(paceBinding = binding.copy(granularity = SpeedGranularity.L1)),
            original.copy(paceBinding = binding.copy(rate = 60)),
            original.copy(groupsPerMinute = 60), original.copy(granularity = SpeedGranularity.L1),
            // The controller retains this false flag even if a later edit returns to 63.
            original.copy(pacingStable = false), original.copy(pacedCompletion = false),
        )
        invalid.forEach { candidate ->
            val summary = PersonalizationOutcomes.summarize(decision, listOf(run(1), candidate))
            assertEquals(1, summary.sampleCount)
            assertFalse(summary.evidenceEnough)
        }
    }

    @Test fun onlyCompleteFirstGuidedChecksBeforeUndoEnterTheOutcome() {
        val original = run(2)
        val invalid = listOf(
            original.copy(startedAt = 999), original.copy(round = SpeedPacedRound.FADED, parentRunId = "parent"),
            original.copy(parentRunId = "parent"), original.copy(firstAttempt = false), original.copy(completed = false),
            original.copy(planOrigin = "rules"), original.copy(answers = original.answers.dropLast(1)),
            original.copy(answers = List(3) { original.answers.first() }), original.copy(pacingStable = null),
            original.copy(activeMillis = 0), original.copy(groupMillis = listOf(1, 1, 1)),
        )
        invalid.forEach { assertEquals(0, PersonalizationOutcomes.summarize(decision, listOf(it)).sampleCount) }
        val undone = decision.copy(status = PersonalizationDecisionStatus.UNDONE, updatedAt = 23_000)
        assertEquals(listOf("run-1"), PersonalizationOutcomes.summarize(undone, listOf(run(1), run(2), run(3))).evidenceRunIds)
        assertEquals(0, PersonalizationOutcomes.summarize(decision.copy(status = PersonalizationDecisionStatus.DISMISSED), listOf(run(1))).sampleCount)
        assertEquals(0, PersonalizationOutcomes.summarize(decision.copy(appliedRevision = null), listOf(run(1))).sampleCount)
    }

    @Test fun sameBodyCountsOnceAndKeepsItsEarliestEligibleScore() {
        val first = run(1, correct = 0)
        val repeated = run(2).copy(bodyHash = first.bodyHash)
        val summary = PersonalizationOutcomes.summarize(decision, listOf(repeated, first, run(3)))
        assertEquals(listOf("run-1", "run-3"), summary.evidenceRunIds)
        assertEquals(3, summary.correct)
        assertEquals(6, summary.total)
        assertEquals(2, summary.materialCount)
        assertTrue(summary.evidenceEnough)
        val repeatedOnly = PersonalizationOutcomes.summarize(decision, listOf(repeated, first))
        assertEquals(1, repeatedOnly.sampleCount)
        assertEquals(0, repeatedOnly.correct)
        assertFalse(repeatedOnly.evidenceEnough)
    }

    @Test fun identicalSnapshotsDeduplicateButConflictingCopiesAreRejected() {
        val original = run(1)
        val single = PersonalizationOutcomes.summarize(decision, listOf(original))
        assertEquals(single, PersonalizationOutcomes.summarize(decision, listOf(original, original)))
        val conflict = original.copy(paceBinding = null)
        val summary = PersonalizationOutcomes.summarize(decision, listOf(original, conflict, run(2)))
        assertEquals(listOf("run-2"), summary.evidenceRunIds)
        assertFalse(summary.evidenceEnough)
    }

    @Test fun noSamplesOrTooFewQuestionsNeverClaimsSufficientEvidence() {
        val empty = PersonalizationOutcomes.summarize(decision, emptyList())
        assertEquals(0, empty.sampleCount)
        assertEquals(0, empty.total)
        assertNull(empty.accuracy)
        assertFalse(empty.evidenceEnough)
        val shortRuns = listOf(run(1), run(2)).map { it.copy(answers = it.answers.take(1), questionCount = 1) }
        val short = PersonalizationOutcomes.summarize(decision, shortRuns)
        assertEquals(2, short.materialCount)
        assertEquals(2, short.total)
        assertNotNull(short.accuracy)
        assertFalse(short.evidenceEnough)
    }

    @Test fun legacyRunsDecodeWithNoInventedPaceBinding() {
        val codec = Json { encodeDefaults = true }
        val legacy = codec.encodeToString(run(1).copy(paceBinding = null)).replace(",\"paceBinding\":null", "")
        val restored = codec.decodeFromString<SpeedRun>(legacy)
        assertNull(restored.paceBinding)
        assertEquals(0, PersonalizationOutcomes.summarize(decision, listOf(restored)).sampleCount)
    }

    @Test fun legacyProfilesCannotInferAnActiveTokenFromMatchingNumbers() {
        val codec = Json { encodeDefaults = true }
        val legacy = codec.encodeToString(profile.copy(activePaceBinding = null)).replace(",\"activePaceBinding\":null", "")
        val restored = codec.decodeFromString<PersonalizationProfile>(legacy)
        assertEquals(63, restored.rateFor())
        assertNull(restored.activePaceBinding)
        assertNull(PersonalizationOutcomes.bindingForStart(restored, SpeedGranularity.L2, 63, false))
    }

    private fun run(number: Int, correct: Int = 3): SpeedRun {
        val start = number * 10_000L
        return SpeedRun("run-$number", "article-$number", "body-$number", SpeedGranularity.L2, "plan-$number",
            start, start + 3_000, 3_000, 12, 3, listOf(1_000, 1_000, 1_000), 0, 0,
            (0..2).map { SpeedAnswer("q-$number-$it", if (it < correct) 0 else 1, it < correct) },
            3, true, 63, "ai", firstAttempt = true, pacingStable = true, pacedCompletion = true, paceBinding = binding)
    }
}
