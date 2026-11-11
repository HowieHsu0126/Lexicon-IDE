package dev.lexicon.domain

import org.junit.Assert.*
import org.junit.Test

class PersonalizationAssessmentTest {
    private val profile = PersonalizationProfile()

    @Test fun disabledAndInvalidProfilesDoNotEvaluateHistoryOrProduceAdvice() {
        val runs = listOf(run(1), run(2))
        listOf(profile.copy(enabled = false) to PaceAssessmentStatus.DISABLED,
            profile.copy(revision = -1) to PaceAssessmentStatus.INVALID_PROFILE).forEach { (state, expected) ->
            val result = PersonalizationPolicy.assess(state, runs)
            assertEquals(expected, result.status)
            assertTrue(result.evidence.isEmpty())
            assertTrue(result.excludedCounts.isEmpty())
            assertEquals(0, result.total)
            assertNull(result.suggestion)
        }
        assertEquals(PaceAssessmentStatus.DISABLED,
            PersonalizationPolicy.assess(profile.copy(enabled = false, revision = -1), runs).status)
    }

    @Test fun emptyAndInsufficientEvidenceExposeActualCountsWithoutTurningUnknownIntoFailure() {
        val empty = PersonalizationPolicy.assess(profile, emptyList())
        assertEquals(PaceAssessmentStatus.INSUFFICIENT_EVIDENCE, empty.status)
        assertEquals(0, empty.correct)
        assertEquals(0, empty.total)
        assertEquals(0, empty.materialCount)
        val single = PersonalizationPolicy.assess(profile, listOf(run(1, correct = 2)))
        assertEquals(PaceAssessmentStatus.INSUFFICIENT_EVIDENCE, single.status)
        assertEquals(listOf("run-1"), single.evidence.map { it.id })
        assertEquals(2, single.correct)
        assertEquals(3, single.total)
        assertEquals(1, single.materialCount)
        assertNull(single.suggestion)
    }

    @Test fun enoughQuestionsFromTheSameSourceBodyStillExplainInsufficientMaterialDiversity() {
        val first = run(1)
        val second = run(2).copy(bodyHash = first.bodyHash)
        val result = PersonalizationPolicy.assess(profile, listOf(first, second))
        assertEquals(PaceAssessmentStatus.INSUFFICIENT_EVIDENCE, result.status)
        assertEquals(6, result.total)
        assertEquals(2, result.evidence.size)
        assertEquals(1, result.materialCount)
        assertTrue(result.excludedCounts.isEmpty())
    }

    @Test fun suggestionKeepsFrozenPaceV2IdentityHashOrderAndExactOriginalEvidence() {
        val runs = listOf(run(1), run(2))
        val before = profile.copy()
        val result = PersonalizationPolicy.assess(profile, runs)
        val suggestion = requireNotNull(result.suggestion)
        assertEquals(PaceAssessmentStatus.SUGGESTED, result.status)
        assertEquals("pace:183cdfadade09dced0543746f1dfcaa972114ca4fb8ec6c4e95c3f2b6c3bacde", suggestion.id)
        assertEquals("9cbf48ed8603d0aeffd9864608106df4f191f4896a4d6902b406768ab3ecf1fa", suggestion.evidenceHash)
        assertEquals("pace-v2", suggestion.ruleVersion)
        assertEquals(listOf(runs[1], runs[0]), result.evidence)
        assertEquals(result.evidence.map { it.id }, suggestion.evidenceRunIds)
        assertEquals(result.correct, suggestion.correct)
        assertEquals(result.total, suggestion.total)
        assertEquals(2, result.materialCount)
        assertEquals(60, result.rate)
        assertEquals(63, suggestion.toRate)
        assertEquals(suggestion, PersonalizationPolicy.suggest(profile, runs))
        assertEquals(before, profile)
        assertEquals(listOf(run(1), run(2)), runs)
    }

    @Test fun neutralEvidenceHasAMaintainStatusInsteadOfASyntheticSuggestion() {
        val result = PersonalizationPolicy.assess(profile, listOf(run(1, correct = 2), run(2)))
        assertEquals(PaceAssessmentStatus.MAINTAIN, result.status)
        assertEquals(5, result.correct)
        assertEquals(6, result.total)
        assertEquals(2, result.materialCount)
        assertNull(result.suggestion)
    }

    @Test fun overallAccuracyAloneCannotReplaceTheExistingLatestTwoRunCondition() {
        val result = PersonalizationPolicy.assess(profile, listOf(run(1, total = 2), run(2), run(3, correct = 1, total = 2)))
        assertEquals(PaceAssessmentStatus.MAINTAIN, result.status)
        assertEquals(6, result.correct)
        assertEquals(7, result.total)
        assertNull(result.suggestion)
    }

    @Test fun lowAccuracyKeepsTheExistingTenPercentReduction() {
        val result = PersonalizationPolicy.assess(profile, listOf(run(1, correct = 2), run(2, correct = 2)))
        assertEquals(PaceAssessmentStatus.SUGGESTED, result.status)
        assertEquals(54, result.suggestion?.toRate)
        assertEquals(4, result.correct)
        assertEquals(6, result.total)
    }

    @Test fun clampedEndpointsExplainRateLimitsWithoutAnIdenticalTarget() {
        listOf(180 to 3, 30 to 0).forEach { (rate, correct) ->
            val result = PersonalizationPolicy.assess(profile.copy(rates = mapOf(SpeedGranularity.L2 to rate)),
                listOf(run(1, correct = correct, rate = rate), run(2, correct = correct, rate = rate)))
            assertEquals(PaceAssessmentStatus.RATE_LIMIT, result.status)
            assertEquals(6, result.total)
            assertNull(result.suggestion)
        }
    }

    @Test fun explicitHoldRemainsDistinctFromInsufficientOrAlreadyDecidedEvidence() {
        val runs = listOf(run(1), run(2))
        val original = requireNotNull(PersonalizationPolicy.suggest(profile, runs))
        val held = profile.copy(holds = listOf(PaceHold(SpeedGranularity.L2, 60, 1, 50)))
        val result = PersonalizationPolicy.assess(held, runs)
        assertEquals(PaceAssessmentStatus.HELD, result.status)
        assertEquals(6, result.total)
        assertEquals(2, result.evidence.size)
        assertNull(result.suggestion)
        val decidedAndHeld = held.copy(decisions = listOf(PersonalizationDecision(original.id, original,
            PersonalizationDecisionStatus.DISMISSED, 50)))
        assertEquals(PaceAssessmentStatus.HELD, PersonalizationPolicy.assess(decidedAndHeld, runs).status)
        assertEquals(PaceAssessmentStatus.ALREADY_DECIDED,
            PersonalizationPolicy.assess(decidedAndHeld.copy(holds = emptyList()), runs).status)
    }

    @Test fun allPreviouslyDecidedStatusesKeepTheSameEvidenceWithoutReofferingIt() {
        val runs = listOf(run(1), run(2))
        val suggestion = requireNotNull(PersonalizationPolicy.suggest(profile, runs))
        PersonalizationDecisionStatus.entries.forEach { status ->
            val decided = profile.copy(decisions = listOf(PersonalizationDecision(suggestion.id, suggestion, status, 40_000)))
            val result = PersonalizationPolicy.assess(decided, runs)
            assertEquals(PaceAssessmentStatus.ALREADY_DECIDED, result.status)
            assertEquals(listOf("run-2", "run-1"), result.evidence.map { it.id })
            assertNull(result.suggestion)
        }
    }

    @Test fun onlyTheExistingLatestSixAndMinimumEvidenceSelectionAreDisplayed() {
        val six = PersonalizationPolicy.assess(profile, (1..8).map { run(it, total = 1) })
        assertEquals(listOf("run-8", "run-7", "run-6", "run-5", "run-4", "run-3"), six.evidence.map { it.id })
        assertEquals(6, six.total)
        assertEquals(mapOf(PaceExclusionReason.OUTSIDE_SELECTED_EVIDENCE to 2), six.excludedCounts)
        val minimum = PersonalizationPolicy.assess(profile, (1..5).map { run(it) })
        assertEquals(listOf("run-5", "run-4"), minimum.evidence.map { it.id })
        assertEquals(mapOf(PaceExclusionReason.OUTSIDE_SELECTED_EVIDENCE to 3), minimum.excludedCounts)
    }

    @Test fun everyRejectedCanonicalRecordHasOneReasonAndIdenticalCopiesDoNotInflateCounts() {
        val blank = run(20).copy(id = "")
        val conflict = run(21)
        val runs = listOf(run(1), run(2), run(1),
            run(3).copy(granularity = SpeedGranularity.L1), run(4, rate = 61),
            run(5).copy(firstAttempt = false), run(6).copy(round = SpeedPacedRound.FADED, parentRunId = "parent"),
            run(7).copy(parentRunId = "parent"), run(8).copy(completed = false), run(9).copy(pacingStable = false),
            run(10).copy(pacingStable = null), run(11).copy(pacedCompletion = false),
            run(12).copy(planOrigin = "rules"), run(13, total = 0),
            run(14).copy(activeMillis = 0), blank, blank, conflict, conflict, conflict.copy(wordsRead = 99))
        val result = PersonalizationPolicy.assess(profile, runs)
        assertEquals(listOf("run-2", "run-1"), result.evidence.map { it.id })
        assertEquals(mapOf(
            PaceExclusionReason.OTHER_CONFIGURATION to 2,
            PaceExclusionReason.REPEAT_OR_FADED to 3,
            PaceExclusionReason.INCOMPLETE_OR_UNSTABLE_PACING to 4,
            PaceExclusionReason.NO_AI_QUESTIONS to 2,
            PaceExclusionReason.INVALID_RECORD to 2,
            PaceExclusionReason.CONFLICTING_DUPLICATE to 1,
        ), result.excludedCounts)
        assertEquals(PersonalizationPolicy.suggest(profile, listOf(run(1), run(2))), result.suggestion)
        assertEquals(result, PersonalizationPolicy.assess(profile, runs.reversed()))
    }

    @Test fun aConflictingCopyNeverContributesItsMostFavorableVersion() {
        val first = run(1)
        val result = PersonalizationPolicy.assess(profile, listOf(first, first.copy(firstAttempt = false), run(2)))
        assertEquals(PaceAssessmentStatus.INSUFFICIENT_EVIDENCE, result.status)
        assertEquals(listOf("run-2"), result.evidence.map { it.id })
        assertEquals(mapOf(PaceExclusionReason.CONFLICTING_DUPLICATE to 1), result.excludedCounts)
    }

    @Test fun rejectionReasonPrecedenceDoesNotCallEveryOldOrRepeatedRecordInvalid() {
        val wrong = run(1).copy(granularity = SpeedGranularity.L1, firstAttempt = false, completed = false, planOrigin = "rules")
        val repeated = run(2).copy(firstAttempt = false, completed = false, planOrigin = "rules")
        val unfinished = run(3).copy(completed = false, planOrigin = "rules")
        val result = PersonalizationPolicy.assess(profile, listOf(wrong, repeated, unfinished))
        assertEquals(mapOf(PaceExclusionReason.OTHER_CONFIGURATION to 1, PaceExclusionReason.REPEAT_OR_FADED to 1,
            PaceExclusionReason.INCOMPLETE_OR_UNSTABLE_PACING to 1), result.excludedCounts)
        assertTrue(result.evidence.isEmpty())
    }

    @Test fun assessmentUsesTheExistingTieOrderAndKeepsPerAnswerInputOrderOutsideTheHash() {
        val original = run(1)
        val one = original.copy(id = "a", articleId = "one", bodyHash = "body-one", answers = original.answers.reversed())
        val two = run(2).copy(id = "b", startedAt = one.startedAt, finishedAt = one.finishedAt)
        val result = PersonalizationPolicy.assess(profile, listOf(two, one))
        assertEquals(listOf(one, two), result.evidence)
        assertEquals(listOf("a", "b"), result.suggestion?.evidenceRunIds)
        assertEquals(PersonalizationPolicy.suggest(profile, listOf(one.copy(answers = one.answers.reversed()), two)), result.suggestion)
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
