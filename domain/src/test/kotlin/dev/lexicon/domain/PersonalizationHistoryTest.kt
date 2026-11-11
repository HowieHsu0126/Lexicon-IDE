package dev.lexicon.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PersonalizationHistoryTest {
    private val profile = PersonalizationProfile()
    private val originalRuns = listOf(run(1), run(2))
    private fun suggestion(runs: List<SpeedRun> = originalRuns, state: PersonalizationProfile = profile) =
        requireNotNull(PersonalizationPolicy.suggest(state, runs))
    private fun decision(suggestion: PaceSuggestion = suggestion(), status: PersonalizationDecisionStatus = PersonalizationDecisionStatus.APPLIED,
        time: Long = 100_000, revision: Long = 1) = PersonalizationDecision(suggestion.id, suggestion, status, time,
        updatedAt = if (status == PersonalizationDecisionStatus.UNDONE) time + 100_000 else time,
        appliedRevision = if (status == PersonalizationDecisionStatus.DISMISSED) null else revision)
    private fun binding(decision: PersonalizationDecision) = AppliedPaceBinding(decision.id, requireNotNull(decision.appliedRevision),
        decision.suggestion.granularity, decision.suggestion.toRate)
    private fun appliedProfile(decision: PersonalizationDecision = decision()) = profile.copy(revision = requireNotNull(decision.appliedRevision),
        rates = profile.rates + (decision.suggestion.granularity to decision.suggestion.toRate), decisions = listOf(decision),
        activePaceBinding = binding(decision), undoPaceBindings = mapOf(decision.suggestion.granularity to binding(decision)))

    @Test fun aFrozenPaceV2DecisionVerifiesWithItsOriginalEvidenceOrder() {
        val offer = suggestion()
        assertEquals("pace:183cdfadade09dced0543746f1dfcaa972114ca4fb8ec6c4e95c3f2b6c3bacde", offer.id)
        assertEquals("9cbf48ed8603d0aeffd9864608106df4f191f4896a4d6902b406768ab3ecf1fa", offer.evidenceHash)
        val result = PersonalizationHistory.inspect(decision(offer), originalRuns.reversed())
        assertEquals(PaceHistoricalEvidenceStatus.VERIFIED, result.originalEvidence.status)
        assertEquals(listOf(originalRuns[1], originalRuns[0]), result.originalEvidence.runs)
        assertTrue(result.originalEvidence.missingIds.isEmpty())
        assertTrue(result.originalEvidence.conflictingIds.isEmpty())
        assertEquals(0, result.outcome?.sampleCount)
        assertTrue(result.outcomeRuns.isEmpty())
    }

    @Test fun newerRunsAndCurrentSettingsCannotReplaceAnOldDecisionEvidence() {
        val old = decision()
        val changedProfile = appliedProfile(old).copy(enabled = false, rates = mapOf(SpeedGranularity.L2 to 120),
            holds = listOf(PaceHold(SpeedGranularity.L2, 120, 1, 300_000)))
        val selected = PersonalizationHistory.decisions(changedProfile).single()
        val result = PersonalizationHistory.inspect(selected, originalRuns + (3..9).map { run(it, rate = 120) })
        assertEquals(old, selected)
        assertEquals(PaceHistoricalEvidenceStatus.VERIFIED, result.originalEvidence.status)
        assertEquals(old.suggestion.evidenceRunIds, result.originalEvidence.runs.map { it.id })
        assertEquals(60, result.decision.suggestion.fromRate)
    }

    @Test fun allDecisionStatusesCanVerifyAndDismissalHasNoApplicationOutcome() {
        PersonalizationDecisionStatus.entries.forEach { status ->
            val selected = decision(status = status)
            val result = PersonalizationHistory.inspect(selected, originalRuns)
            assertEquals(PaceHistoricalEvidenceStatus.VERIFIED, result.originalEvidence.status)
            assertEquals(selected, result.decision)
            if (status == PersonalizationDecisionStatus.DISMISSED) assertNull(result.outcome)
            else assertNotNull(result.outcome)
        }
    }

    @Test fun decisionsAreStableNewestFirstAndDoNotChooseBetweenConflictingSameIdCopies() {
        val first = decision()
        val second = decision(suggestion(listOf(run(3), run(4))), time = 200_000, revision = 2)
        val third = decision(suggestion(listOf(run(5), run(6))), time = 200_000, revision = 3)
        val corrupt = first.copy(status = PersonalizationDecisionStatus.UNDONE, updatedAt = 300_000)
        val rows = listOf(first, second, third, second)
        val expected = listOf(second, third).sortedByDescending { it.id } + first
        assertEquals(expected, PersonalizationHistory.decisions(profile.copy(decisions = rows)))
        assertEquals(expected, PersonalizationHistory.decisions(profile.copy(decisions = rows.reversed())))
        assertEquals(expected.dropLast(1), PersonalizationHistory.decisions(profile.copy(decisions = rows + corrupt)))
    }

    @Test fun missingEvidenceIsExplicitAndDoesNotPublishTheRemainingPartialSubset() {
        val result = PersonalizationHistory.inspect(decision(), listOf(originalRuns[0], run(9)))
        assertEquals(PaceHistoricalEvidenceStatus.MISSING, result.originalEvidence.status)
        assertEquals(listOf("run-2"), result.originalEvidence.missingIds)
        assertTrue(result.originalEvidence.runs.isEmpty())
        assertTrue(result.originalEvidence.conflictingIds.isEmpty())
    }

    @Test fun identicalRunCopiesAreHarmlessButConflictsHavePriorityOverMissingRows() {
        assertEquals(PaceHistoricalEvidenceStatus.VERIFIED,
            PersonalizationHistory.inspect(decision(), originalRuns + originalRuns).originalEvidence.status)
        val first = originalRuns[0]
        val result = PersonalizationHistory.inspect(decision(), listOf(first, first.copy(pauses = 1)))
        assertEquals(PaceHistoricalEvidenceStatus.CONFLICT, result.originalEvidence.status)
        assertEquals(listOf("run-1"), result.originalEvidence.conflictingIds)
        assertEquals(listOf("run-2"), result.originalEvidence.missingIds)
        assertTrue(result.originalEvidence.runs.isEmpty())
    }

    @Test fun changedSourceMeasurementsFailTheFrozenHashInsteadOfUpdatingTheOldCounters() {
        val changed = originalRuns[0].copy(pauses = 1)
        val result = PersonalizationHistory.inspect(decision(), listOf(changed, originalRuns[1]))
        assertEquals(PaceHistoricalEvidenceStatus.CHANGED, result.originalEvidence.status)
        assertTrue(result.originalEvidence.runs.isEmpty())
        assertEquals(6, result.decision.suggestion.correct)
        assertEquals(6, result.decision.suggestion.total)
    }

    @Test fun unknownRuleDoesNotPretendThatCurrentPaceV2RevalidatesIt() {
        val unknown = decision().let { selected -> selected.copy(suggestion = selected.suggestion.copy(ruleVersion = "pace-v99")) }
        val result = PersonalizationHistory.inspect(unknown, originalRuns)
        assertEquals(PaceHistoricalEvidenceStatus.UNSUPPORTED_RULE, result.originalEvidence.status)
        assertTrue(result.originalEvidence.runs.isEmpty())
    }

    @Test fun modifiedSummaryCountersIdentityAndInvalidMetadataAreNotVerified() {
        val selected = decision()
        val invalid = listOf(
            selected.copy(id = "pace:" + "f".repeat(64)),
            selected.copy(suggestion = selected.suggestion.copy(correct = 5)),
            selected.copy(suggestion = selected.suggestion.copy(total = 7)),
            selected.copy(suggestion = selected.suggestion.copy(sampleCount = 3)),
            selected.copy(suggestion = selected.suggestion.copy(toRate = 80)),
            selected.copy(suggestion = selected.suggestion.copy(evidenceHash = "f".repeat(64))),
            selected.copy(suggestion = selected.suggestion.copy(evidenceRunIds = listOf("run-1", "run-1"))),
            selected.copy(appliedRevision = null), selected.copy(decidedAt = -1),
            selected.copy(updatedAt = selected.decidedAt - 1),
        )
        invalid.forEach {
            val result = PersonalizationHistory.inspect(it, originalRuns)
            assertEquals(it.toString(), PaceHistoricalEvidenceStatus.INVALID_SUMMARY, result.originalEvidence.status)
            assertTrue(result.originalEvidence.runs.isEmpty())
        }
    }

    @Test fun additiveMetadataAndAnswerReorderingDoNotChangeHistoricalIdentity() {
        val altered = originalRuns.map { it.copy(answers = it.answers.reversed(),
            paceBinding = AppliedPaceBinding("earlier", 1, it.granularity, it.groupsPerMinute),
            chunkInteractions = List(it.groupsRead) { SpeedChunkInteraction(1, 0, 0) }) }
        val result = PersonalizationHistory.inspect(decision(), altered)
        assertEquals(PaceHistoricalEvidenceStatus.VERIFIED, result.originalEvidence.status)
        assertEquals(altered.reversed(), result.originalEvidence.runs)
        val faded = altered.map { it.copy(round = SpeedPacedRound.FADED, parentRunId = "parent") }
        assertEquals(PaceHistoricalEvidenceStatus.INVALID_SUMMARY, PersonalizationHistory.inspect(decision(), faded).originalEvidence.status)
    }

    @Test fun reorderedEvidenceIdsAreNotSilentlySortedToMatchTheStoredHash() {
        val selected = decision()
        val reordered = selected.copy(suggestion = selected.suggestion.copy(evidenceRunIds = selected.suggestion.evidenceRunIds.reversed()))
        assertEquals(PaceHistoricalEvidenceStatus.CHANGED, PersonalizationHistory.inspect(reordered, originalRuns).originalEvidence.status)
    }

    @Test fun aSelfConsistentHashCannotAuthorizeWrongOrderWrongTargetOrAnOverlongSelectedPrefix() {
        fun synthetic(runs: List<SpeedRun>, target: Int = 63): PersonalizationDecision {
            val hash = PersonalizationPolicy.canonicalEvidenceHash(runs)
            val id = PersonalizationPolicy.suggestionId(SpeedGranularity.L2, 60, target, hash)
            return decision(PaceSuggestion(id, SpeedGranularity.L2, 60, target, runs.map { it.id },
                runs.sumOf { it.answers.count { answer -> answer.correct } }, runs.sumOf { it.questionCount }, runs.size, hash, "pace-v2"))
        }
        listOf(synthetic(originalRuns), synthetic(originalRuns.reversed(), 80), synthetic((1..3).map { run(it) }.reversed()))
            .forEach { selected ->
                val result = PersonalizationHistory.inspect(selected, (1..3).map { run(it) })
                assertEquals(PaceHistoricalEvidenceStatus.INVALID_SUMMARY, result.originalEvidence.status)
                assertTrue(result.originalEvidence.runs.isEmpty())
            }
    }

    @Test fun aLocalClockEarlierThanEvidenceDoesNotCreateANewHistoricalEligibilityRule() {
        val selected = decision(time = 1)
        assertTrue(originalRuns.all { it.finishedAt > selected.decidedAt })
        assertEquals(PaceHistoricalEvidenceStatus.VERIFIED, PersonalizationHistory.inspect(selected, originalRuns).originalEvidence.status)
    }

    @Test fun oldAppliedOutcomesKeepTheirExactBindingWhenALaterDecisionExists() {
        val selected = decision(time = 100_000)
        val token = binding(selected)
        val later = decision(suggestion(listOf(run(3), run(4))), time = 200_000, revision = 2)
        val own = listOf(run(20, rate = 63), run(21, rate = 63)).map { it.copy(paceBinding = token) }
        val others = listOf(run(22, rate = 63).copy(paceBinding = binding(later)), run(23, rate = 63))
        val chosen = PersonalizationHistory.decisions(appliedProfile(selected).copy(revision = 2, decisions = listOf(selected, later))).last()
        val result = PersonalizationHistory.inspect(chosen, originalRuns + own + others)
        assertEquals(2, result.outcome?.sampleCount)
        assertEquals(own, result.outcomeRuns)
        assertEquals(own.map { it.id }, result.outcome?.evidenceRunIds)
        assertEquals(PaceHistoricalEvidenceStatus.VERIFIED, result.originalEvidence.status)
    }

    @Test fun outcomeRemainsIndependentWhenAnOriginalEvidenceRecordIsMissing() {
        val selected = decision()
        val own = run(20, rate = 63).copy(paceBinding = binding(selected))
        val result = PersonalizationHistory.inspect(selected, listOf(originalRuns.first(), own))
        assertEquals(PaceHistoricalEvidenceStatus.MISSING, result.originalEvidence.status)
        assertEquals(listOf(own), result.outcomeRuns)
        assertEquals(1, result.outcome?.sampleCount)
    }

    @Test fun undoneOutcomeStopsAtUndoAndRepeatedBodiesKeepTheirEarliestEligibleScore() {
        val selected = decision(status = PersonalizationDecisionStatus.UNDONE, time = 100_000)
        val first = run(11, correct = 0, rate = 63).copy(paceBinding = binding(selected))
        val repeated = run(12, rate = 63).copy(bodyHash = first.bodyHash, paceBinding = binding(selected))
        val second = run(13, rate = 63).copy(paceBinding = binding(selected))
        val afterUndo = run(21, rate = 63).copy(paceBinding = binding(selected))
        val result = PersonalizationHistory.inspect(selected, originalRuns + listOf(repeated, afterUndo, first, second))
        assertEquals(listOf(first, second), result.outcomeRuns)
        assertEquals(3, result.outcome?.correct)
        assertEquals(6, result.outcome?.total)
    }

    @Test fun undoTokensWorkPerGranularityWithoutChangingWhichRateIsCurrentlySelected() {
        val l2 = decision()
        val l1Runs = listOf(run(3), run(4)).map { it.copy(granularity = SpeedGranularity.L1) }
        val l1 = decision(suggestion(l1Runs, profile.copy(granularity = SpeedGranularity.L1)), time = 200_000, revision = 2)
        val current = profile.copy(revision = 3, enabled = false, granularity = SpeedGranularity.L3,
            rates = mapOf(SpeedGranularity.L1 to 63, SpeedGranularity.L2 to 63, SpeedGranularity.L3 to 77),
            decisions = listOf(l2, l1), activePaceBinding = null,
            undoPaceBindings = mapOf(SpeedGranularity.L1 to binding(l1), SpeedGranularity.L2 to binding(l2)))
        assertEquals(listOf(l1, l2), PersonalizationHistory.undoableDecisions(current))
        assertEquals(77, current.rateFor())
    }

    @Test fun legacyActiveTokenIsTheOnlyFallbackAndMatchingNumbersCannotRecreateIt() {
        val selected = decision()
        val current = appliedProfile(selected).copy(undoPaceBindings = emptyMap())
        assertEquals(listOf(selected), PersonalizationHistory.undoableDecisions(current))
        assertTrue(PersonalizationHistory.undoableDecisions(current.copy(activePaceBinding = null)).isEmpty())
        val codec = Json { encodeDefaults = true }
        val legacy = codec.encodeToString(current).replace(",\"undoPaceBindings\":{}", "")
        val restored = codec.decodeFromString<PersonalizationProfile>(legacy)
        assertTrue(restored.undoPaceBindings.isEmpty())
        assertEquals(listOf(selected), PersonalizationHistory.undoableDecisions(restored))
    }

    @Test fun invalidOrSupersededMapTokensNeverFallBackToAnOlderActiveToken() {
        val selected = decision()
        val current = appliedProfile(selected)
        val token = binding(selected)
        listOf(token.copy(profileRevision = 99), token.copy(decisionId = "other"), token.copy(rate = 70),
            token.copy(granularity = SpeedGranularity.L1)).forEach { invalid ->
            assertTrue(PersonalizationHistory.undoableDecisions(current.copy(undoPaceBindings = mapOf(SpeedGranularity.L2 to invalid))).isEmpty())
        }
        val later = decision(suggestion(listOf(run(3), run(4))), time = 200_000, revision = 2)
        val superseded = current.copy(revision = 2, decisions = listOf(selected, later))
        assertTrue(PersonalizationHistory.undoableDecisions(superseded).isEmpty())
        assertEquals(listOf(later), PersonalizationHistory.undoableDecisions(superseded.copy(undoPaceBindings = mapOf(SpeedGranularity.L2 to binding(later)))))
        assertTrue(PersonalizationHistory.undoableDecisions(current.copy(rates = current.rates + (SpeedGranularity.L2 to 80))).isEmpty())
    }

    @Test fun dismissedUndoneAndConflictingDecisionSnapshotsCannotBeUndoneAgain() {
        val selected = decision()
        listOf(selected.copy(status = PersonalizationDecisionStatus.UNDONE, updatedAt = 200_000),
            selected.copy(status = PersonalizationDecisionStatus.DISMISSED, appliedRevision = null)).forEach {
            assertTrue(PersonalizationHistory.undoableDecisions(appliedProfile(selected).copy(decisions = listOf(it))).isEmpty())
        }
        val conflict = selected.copy(updatedAt = 200_000)
        assertTrue(PersonalizationHistory.undoableDecisions(appliedProfile(selected).copy(decisions = listOf(selected, conflict))).isEmpty())
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
