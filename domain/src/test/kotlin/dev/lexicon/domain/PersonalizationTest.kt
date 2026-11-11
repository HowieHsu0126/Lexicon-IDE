package dev.lexicon.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PersonalizationTest {
    private val profile = PersonalizationProfile()

    @Test fun confirmedDefaultsHaveIndependentBoundedRates() {
        assertEquals(60, profile.rateFor())
        assertEquals(60, profile.rateFor(SpeedGranularity.L1))
        val changed = profile.copy(granularity = SpeedGranularity.L1,
            rates = mapOf(SpeedGranularity.L1 to 90, SpeedGranularity.L3 to 999))
        assertEquals(90, changed.rateFor())
        assertEquals(60, changed.rateFor(SpeedGranularity.L2))
        assertEquals(180, changed.rateFor(SpeedGranularity.L3))
        assertEquals(30, profile.copy(rates = mapOf(SpeedGranularity.L2 to -1)).rateFor())
    }

    @Test fun sufficientFirstAttemptEvidenceProposesFivePercentWithoutChangingTheProfile() {
        val runs = listOf(run(1), run(2))
        val suggestion = requireNotNull(PersonalizationPolicy.suggest(profile, runs))
        assertEquals(60, suggestion.fromRate)
        assertEquals(63, suggestion.toRate)
        assertEquals(6, suggestion.correct)
        assertEquals(6, suggestion.total)
        assertEquals(2, suggestion.sampleCount)
        assertEquals(listOf("run-2", "run-1"), suggestion.evidenceRunIds)
        assertEquals(PersonalizationPolicy.RULE_VERSION, suggestion.ruleVersion)
        assertEquals(60, profile.rateFor())
        assertTrue(profile.decisions.isEmpty())
    }

    @Test fun tooLittleEvidenceAndRepeatedMaterialCannotRaisePace() {
        assertNull(PersonalizationPolicy.suggest(profile, emptyList()))
        assertNull(PersonalizationPolicy.suggest(profile, listOf(run(1))))
        assertNull(PersonalizationPolicy.suggest(profile, listOf(run(1, total = 1), run(2, total = 1))))
        assertNull(PersonalizationPolicy.suggest(profile,
            listOf(run(1), run(2).copy(articleId = "article-1", bodyHash = "hash-1"))))
        val singleMaterial = (1..7).map { run(it, total = 1).copy(articleId = "article", bodyHash = "body") }
        assertNull(PersonalizationPolicy.suggest(profile, singleMaterial))
    }

    @Test fun weightedAccuracyControlsHoldAndTenPercentReduction() {
        assertNull(PersonalizationPolicy.suggest(profile, listOf(run(1, correct = 2), run(2))))
        val lower = requireNotNull(PersonalizationPolicy.suggest(profile, listOf(run(1, correct = 2), run(2, correct = 2))))
        assertEquals(54, lower.toRate)
        assertEquals(4, lower.correct)
        assertEquals(6, lower.total)
        // Each question has equal weight even when training units have different question counts.
        val mixed = listOf(run(1, correct = 1, total = 3), run(2, correct = 1, total = 1), run(3, correct = 2, total = 2))
        val mixedLower = requireNotNull(PersonalizationPolicy.suggest(profile, mixed))
        assertEquals(4, mixedLower.correct)
        assertEquals(6, mixedLower.total)
        assertEquals(54, mixedLower.toRate)
    }

    @Test fun differentArticleIdsForTheSameBodyDoNotCountAsDifferentMaterials() {
        val first = run(1)
        val duplicateText = run(2).copy(bodyHash = first.bodyHash)
        assertNotEquals(first.articleId, duplicateText.articleId)
        assertEquals(6, first.questionCount + duplicateText.questionCount)
        assertNull(PersonalizationPolicy.suggest(profile, listOf(first, duplicateText)))
    }

    @Test fun theLatestTwoRunsMustBothMeetTheRaiseThreshold() {
        // Overall 6/7 exceeds 85%, but the newest 1/2 unit is not stable evidence for a raise.
        val runs = listOf(run(1, total = 2), run(2), run(3, correct = 1, total = 2))
        assertNull(PersonalizationPolicy.suggest(profile, runs))
    }

    @Test fun suggestionsNeverMixGranularityRateOrUnknownPacing() {
        val invalid = listOf(
            run(2).copy(round = SpeedPacedRound.FADED, parentRunId = "guided-2"),
            run(2).copy(parentRunId = "invented-parent"),
            run(2).copy(pacingStable = null),
            run(2).copy(pacingStable = false),
            run(2).copy(pacedCompletion = null),
            run(2).copy(pacedCompletion = false),
            run(2).copy(granularity = SpeedGranularity.L1),
            run(2).copy(groupsPerMinute = 61),
            run(2).copy(firstAttempt = false),
            run(2).copy(completed = false),
            run(2).copy(planOrigin = "rules"),
        )
        invalid.forEach { assertNull(PersonalizationPolicy.suggest(profile, listOf(run(1), it))) }
        assertNull(PersonalizationPolicy.suggest(profile.copy(enabled = false), listOf(run(1), run(2))))
    }

    @Test fun additiveRoundMetadataDoesNotReofferPreviouslyDecidedGuidedEvidence() {
        // Frozen v1.3 pace-v2 identity before SpeedRun gained round/parentRunId.
        val originalId = "pace:183cdfadade09dced0543746f1dfcaa972114ca4fb8ec6c4e95c3f2b6c3bacde"
        val offer = requireNotNull(PersonalizationPolicy.suggest(profile, listOf(run(1), run(2))))
        assertEquals("9cbf48ed8603d0aeffd9864608106df4f191f4896a4d6902b406768ab3ecf1fa", offer.evidenceHash)
        assertEquals(originalId, offer.id)
        val decided = profile.copy(decisions = listOf(PersonalizationDecision(originalId, offer,
            PersonalizationDecisionStatus.DISMISSED, 100_000)))
        assertNull(PersonalizationPolicy.suggest(decided, listOf(run(1), run(2))))
    }

    @Test fun runStartPaceBindingDoesNotChangeFrozenSuggestionIdentity() {
        val original = requireNotNull(PersonalizationPolicy.suggest(profile, listOf(run(1), run(2))))
        val binding = AppliedPaceBinding("earlier-decision", 2, SpeedGranularity.L2, 60)
        val boundRuns = listOf(run(1), run(2)).map { it.copy(paceBinding = binding) }
        assertEquals(original, PersonalizationPolicy.suggest(profile, boundRuns))
        assertEquals("pace:183cdfadade09dced0543746f1dfcaa972114ca4fb8ec6c4e95c3f2b6c3bacde", original.id)
    }

    @Test fun chunkInteractionMetadataDoesNotChangePreviouslyDecidedEvidenceHash() {
        val original = requireNotNull(PersonalizationPolicy.suggest(profile, listOf(run(1), run(2))))
        val observed = listOf(run(1), run(2)).map { it.copy(chunkInteractions = List(3) { SpeedChunkInteraction(1, 0, 0) }) }
        assertEquals(original, PersonalizationPolicy.suggest(profile, observed))
        assertEquals("pace:183cdfadade09dced0543746f1dfcaa972114ca4fb8ec6c4e95c3f2b6c3bacde", original.id)
    }

    @Test fun malformedMeasurementsAndIncompleteAnswersCannotBecomeEvidence() {
        val original = run(2)
        val invalid = listOf(
            original.copy(activeMillis = 0),
            original.copy(activeMillis = 4_000),
            original.copy(finishedAt = original.startedAt - 1),
            original.copy(startedAt = -1),
            original.copy(wordsRead = 0),
            original.copy(groupMillis = listOf(1_000, 1_000)),
            original.copy(groupMillis = listOf(0, 1_000, 2_000)),
            original.copy(groupMillis = listOf(1_000, 1_000, 999)),
            original.copy(answers = original.answers.dropLast(1)),
            original.copy(answers = List(3) { original.answers.first() }),
            original.copy(answers = original.answers.map { it.copy(selectedIndex = 99) }),
            original.copy(questionCount = 0),
            original.copy(articleId = ""),
            original.copy(planKey = ""),
        )
        invalid.forEach { assertNull("Accepted $it", PersonalizationPolicy.suggest(profile, listOf(run(1), it))) }
    }

    @Test fun equalDuplicateSnapshotsCountOnceAndConflictingCopiesAreExcluded() {
        val original = run(1)
        assertNull(PersonalizationPolicy.suggest(profile, listOf(original, original)))
        val clean = requireNotNull(PersonalizationPolicy.suggest(profile, listOf(original, run(2))))
        assertEquals(clean, PersonalizationPolicy.suggest(profile, listOf(original, original, run(2))))
        assertNull(PersonalizationPolicy.suggest(profile,
            listOf(original, original.copy(firstAttempt = false), run(2))))
    }

    @Test fun evidenceOrderAndOlderUnselectedRecordsDoNotChangeSuggestionIdentity() {
        val runs = (1..6).map { run(it, total = 1) }
        val suggestion = requireNotNull(PersonalizationPolicy.suggest(profile, runs))
        assertEquals(6, suggestion.sampleCount)
        assertEquals(suggestion, PersonalizationPolicy.suggest(profile, runs.reversed()))
        assertEquals(suggestion, PersonalizationPolicy.suggest(profile, runs + run(0, correct = 0)))
        assertEquals(suggestion, PersonalizationPolicy.suggest(profile.copy(revision = 12), runs))
        val newEvidence = requireNotNull(PersonalizationPolicy.suggest(profile, runs + run(7, total = 1)))
        assertNotEquals(suggestion.id, newEvidence.id)
        assertNotEquals(suggestion.evidenceHash, newEvidence.evidenceHash)
    }

    @Test fun acceptedDismissedAndUndoneEvidenceIsNotProposedAgain() {
        val runs = listOf(run(1), run(2))
        val suggestion = requireNotNull(PersonalizationPolicy.suggest(profile, runs))
        PersonalizationDecisionStatus.entries.forEach { status ->
            val decision = PersonalizationDecision(suggestion.id, suggestion, status, 40_000)
            assertNull(PersonalizationPolicy.suggest(profile.copy(decisions = listOf(decision)), runs))
            assertNotNull(PersonalizationPolicy.suggest(profile.copy(decisions = listOf(decision)), runs + run(3)))
        }
    }

    @Test fun pacingBoundsCannotProduceAnIdenticalOrOutOfRangeSuggestion() {
        val fast = profile.copy(rates = mapOf(SpeedGranularity.L2 to 180))
        assertNull(PersonalizationPolicy.suggest(fast, listOf(run(1, rate = 180), run(2, rate = 180))))
        val slow = profile.copy(rates = mapOf(SpeedGranularity.L2 to 30))
        assertNull(PersonalizationPolicy.suggest(slow, listOf(run(1, correct = 0, rate = 30), run(2, correct = 0, rate = 30))))
        val nearBound = profile.copy(rates = mapOf(SpeedGranularity.L2 to 178))
        val capped = requireNotNull(PersonalizationPolicy.suggest(nearBound, listOf(run(1, rate = 178), run(2, rate = 178))))
        assertEquals(180, capped.toRate)
    }

    @Test fun legacyRunsRemainReadableButHaveUnknownPacingEvidence() {
        val json = Json { encodeDefaults = true }
        val legacyJson = json.encodeToString(run(1)).replace(",\"pacingStable\":true", "")
        val restored = json.decodeFromString<SpeedRun>(legacyJson)
        assertNull(restored.pacingStable)
        assertNotNull(SpeedMetrics.summarize(restored).comprehension)
        assertNull(PersonalizationPolicy.suggest(profile, listOf(restored, run(2))))
    }

    @Test fun briefManualExposureCannotBecomePacedCompletionEvidence() {
        val rapid = run(1).let { it.copy(activeMillis = 3, finishedAt = it.startedAt + 3,
            groupMillis = listOf(1, 1, 1), pacedCompletion = false) }
        assertTrue(rapid.completed)
        assertEquals(true, rapid.pacingStable)
        assertNull(PersonalizationPolicy.suggest(profile, listOf(rapid, run(2))))
        val legacyJson = Json { encodeDefaults = true }.encodeToString(run(1)).replace(",\"pacedCompletion\":true", "")
        val legacy = Json.decodeFromString<SpeedRun>(legacyJson)
        assertNull(legacy.pacedCompletion)
        assertNull(PersonalizationPolicy.suggest(profile, listOf(legacy, run(2))))
    }

    @Test fun explicitPaceHoldSuppressesNewEvidenceUntilReleased() {
        val held = profile.copy(holds = listOf(PaceHold(SpeedGranularity.L2, 60, 1, 0)))
        assertNull(PersonalizationPolicy.suggest(held, listOf(run(1), run(2))))
        assertNull(PersonalizationPolicy.suggest(held, listOf(run(1), run(2), run(3))))
        assertNotNull(PersonalizationPolicy.suggest(held.copy(holds = emptyList()), listOf(run(1), run(2), run(3))))
    }

    @Test fun holdsAreIndependentByGranularityRateAndDirection() {
        val runs = listOf(run(1), run(2))
        listOf(PaceHold(SpeedGranularity.L1, 60, 1, 0), PaceHold(SpeedGranularity.L2, 63, 1, 0),
            PaceHold(SpeedGranularity.L2, 60, -1, 0)).forEach { hold ->
            assertEquals(63, PersonalizationPolicy.suggest(profile.copy(holds = listOf(hold)), runs)?.toRate)
        }
        val low = listOf(run(1, correct = 0), run(2, correct = 0))
        assertNull(PersonalizationPolicy.suggest(profile.copy(holds = listOf(PaceHold(SpeedGranularity.L2, 60, -1, 0))), low))
        assertEquals(54, PersonalizationPolicy.suggest(profile.copy(holds = listOf(PaceHold(SpeedGranularity.L2, 60, 1, 0))), low)?.toRate)
    }

    @Test fun releasingAHoldDoesNotReplayAlreadyDecidedEvidence() {
        val runs = listOf(run(1), run(2))
        val suggestion = requireNotNull(PersonalizationPolicy.suggest(profile, runs))
        val dismissed = profile.copy(decisions = listOf(PersonalizationDecision(suggestion.id, suggestion,
            PersonalizationDecisionStatus.DISMISSED, 0)), holds = listOf(PaceHold(SpeedGranularity.L2, 60, 1, 0)))
        val released = dismissed.copy(holds = emptyList())
        assertNull(PersonalizationPolicy.suggest(released, runs))
        assertNotNull(PersonalizationPolicy.suggest(released, runs + run(3)))
    }

    @Test fun paceHoldRejectsUnknownDirectionsAndOutOfBoundsRates() {
        listOf(0, 2, -2).forEach { direction ->
            try { PaceHold(SpeedGranularity.L2, 60, direction, 0); fail("Invalid direction") } catch (_: IllegalArgumentException) { }
        }
        listOf(29, 181).forEach { rate ->
            try { PaceHold(SpeedGranularity.L2, rate, 1, 0); fail("Invalid rate") } catch (_: IllegalArgumentException) { }
        }
    }

    private fun run(number: Int, correct: Int? = null, total: Int = 3, rate: Int = 60): SpeedRun {
        val correctCount = correct ?: total
        val start = 1_000L + number * 10_000L
        return SpeedRun("run-$number", "article-$number", "hash-$number", SpeedGranularity.L2, "plan-$number",
            start, start + 3_000, 3_000, 12, 3, listOf(1_000, 1_000, 1_000), 0, 0,
            (0 until total).map { index -> SpeedAnswer("q-$number-$index", if (index < correctCount) 0 else 1, index < correctCount) },
            total, true, rate, "ai", firstAttempt = true, pacingStable = true, pacedCompletion = true)
    }
}
