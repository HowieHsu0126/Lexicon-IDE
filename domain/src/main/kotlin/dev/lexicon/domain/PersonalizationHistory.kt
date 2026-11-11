package dev.lexicon.domain

enum class PaceHistoricalEvidenceStatus { VERIFIED, MISSING, CONFLICT, CHANGED, UNSUPPORTED_RULE, INVALID_SUMMARY }

data class PaceHistoricalEvidence(
    val status: PaceHistoricalEvidenceStatus,
    /** Populated only when the complete stored evidence verifies; never a best-effort partial replacement. */
    val runs: List<SpeedRun> = emptyList(),
    val missingIds: List<String> = emptyList(),
    val conflictingIds: List<String> = emptyList(),
)

data class PaceDecisionDetail(
    val decision: PersonalizationDecision,
    val originalEvidence: PaceHistoricalEvidence,
    /** Null for a decision never applied; a valid applied decision may have zero subsequent records. */
    val outcome: PersonalizationOutcome?,
    /** Exact selected canonical records behind outcome, independent from the original proposal evidence. */
    val outcomeRuns: List<SpeedRun> = emptyList(),
)

/** Read-only historical replay. Today's settings, holds, or newer proposals cannot rewrite a past decision. */
object PersonalizationHistory {
    private val digest = Regex("[a-f0-9]{64}")
    private val newestFirst = compareByDescending<PersonalizationDecision> { it.decidedAt }.thenByDescending { it.id }
    private val evidenceOrder = compareByDescending<SpeedRun> { it.finishedAt }.thenByDescending { it.startedAt }.thenBy { it.id }

    /** Conflicting same-ID snapshots are not resolved by choosing the most convenient status. */
    fun decisions(profile: PersonalizationProfile): List<PersonalizationDecision> = profile.decisions
        .filter { it.id.isNotBlank() }.groupBy { it.id }.values.mapNotNull { it.distinct().singleOrNull() }.sortedWith(newestFirst)

    fun inspect(decision: PersonalizationDecision, runs: List<SpeedRun>): PaceDecisionDetail {
        val canonical = runs.filter { it.id.isNotBlank() }.groupBy { it.id }
            .mapValues { (_, copies) -> copies.distinct().singleOrNull() }
        val original = verifyEvidence(decision, canonical)
        val outcome = if (decision.status == PersonalizationDecisionStatus.DISMISSED || !validSummary(decision)) null
            else PersonalizationOutcomes.summarize(decision, runs)
        val outcomeRuns = outcome?.evidenceRunIds.orEmpty().mapNotNull { canonical[it] }
        return PaceDecisionDetail(decision, original, outcome, outcomeRuns)
    }

    /** A saved token, not a coincidentally restored number, controls whether a rate can be undone. */
    fun undoableDecisions(profile: PersonalizationProfile): List<PersonalizationDecision> {
        if (profile.revision < 0) return emptyList()
        val canonical = decisions(profile)
        return canonical.filter { decision ->
            if (!validSummary(decision) || decision.status != PersonalizationDecisionStatus.APPLIED) return@filter false
            val granularity = decision.suggestion.granularity
            val token = if (profile.undoPaceBindings.containsKey(granularity)) profile.undoPaceBindings[granularity]
                else profile.activePaceBinding?.takeIf { it.granularity == granularity }
            token != null && token.granularity == granularity && token.decisionId == decision.id &&
                token.profileRevision in 1..profile.revision && token.profileRevision == decision.appliedRevision &&
                token.rate == decision.suggestion.toRate && profile.rateFor(granularity) == token.rate &&
                canonical.none { later ->
                    later.suggestion.granularity == granularity && later.status != PersonalizationDecisionStatus.DISMISSED &&
                        (later.appliedRevision ?: 0) > token.profileRevision
                }
        }
    }

    private fun verifyEvidence(decision: PersonalizationDecision, canonical: Map<String, SpeedRun?>): PaceHistoricalEvidence {
        fun status(value: PaceHistoricalEvidenceStatus) = PaceHistoricalEvidence(value)
        if (!validSummary(decision)) return status(PaceHistoricalEvidenceStatus.INVALID_SUMMARY)
        val suggestion = decision.suggestion
        if (suggestion.ruleVersion != PersonalizationPolicy.RULE_VERSION) return status(PaceHistoricalEvidenceStatus.UNSUPPORTED_RULE)
        if (PersonalizationPolicy.suggestionId(suggestion.granularity, suggestion.fromRate, suggestion.toRate,
                suggestion.evidenceHash, suggestion.ruleVersion) != suggestion.id) return status(PaceHistoricalEvidenceStatus.INVALID_SUMMARY)
        val missing = suggestion.evidenceRunIds.filterNot(canonical::containsKey)
        val conflicts = suggestion.evidenceRunIds.filter { canonical.containsKey(it) && canonical[it] == null }
        if (conflicts.isNotEmpty()) return PaceHistoricalEvidence(PaceHistoricalEvidenceStatus.CONFLICT,
            missingIds = missing, conflictingIds = conflicts)
        if (missing.isNotEmpty()) return PaceHistoricalEvidence(PaceHistoricalEvidenceStatus.MISSING, missingIds = missing)
        val selected = suggestion.evidenceRunIds.map { requireNotNull(canonical[it]) }
        if (PersonalizationPolicy.canonicalEvidenceHash(selected) != suggestion.evidenceHash) return status(PaceHistoricalEvidenceStatus.CHANGED)
        val total = selected.sumOf { it.questionCount }
        val correct = selected.sumOf { run -> run.answers.count { it.correct } }
        if (selected.any { !PersonalizationPolicy.eligible(it, suggestion.granularity, suggestion.fromRate) } ||
            selected != selected.sortedWith(evidenceOrder) || total != suggestion.total || correct != suggestion.correct ||
            selected.map { it.bodyHash }.distinct().size < PersonalizationPolicy.MIN_MATERIALS ||
            PersonalizationPolicy.paceV2TargetRate(selected, suggestion.fromRate) != suggestion.toRate)
            return status(PaceHistoricalEvidenceStatus.INVALID_SUMMARY)
        // A pace-v2 proposal stops at the first sufficient prefix within its selected latest-six records.
        val firstSufficient = (1..selected.size).firstOrNull { count ->
            val prefix = selected.take(count)
            prefix.sumOf { it.questionCount } >= PersonalizationPolicy.MIN_QUESTIONS &&
                prefix.map { it.bodyHash }.distinct().size >= PersonalizationPolicy.MIN_MATERIALS
        }
        if (firstSufficient != selected.size) return status(PaceHistoricalEvidenceStatus.INVALID_SUMMARY)
        return PaceHistoricalEvidence(PaceHistoricalEvidenceStatus.VERIFIED, selected)
    }

    private fun validSummary(decision: PersonalizationDecision): Boolean {
        val suggestion = decision.suggestion
        return decision.id == suggestion.id && suggestion.id.startsWith("pace:") && digest.matches(suggestion.id.removePrefix("pace:")) &&
            decision.decidedAt >= 0 && decision.updatedAt >= decision.decidedAt &&
            (if (decision.status == PersonalizationDecisionStatus.DISMISSED) decision.appliedRevision == null else (decision.appliedRevision ?: 0) > 0) &&
            suggestion.fromRate in SpeedPacing.MIN_GROUPS_PER_MINUTE..SpeedPacing.MAX_GROUPS_PER_MINUTE &&
            suggestion.toRate in SpeedPacing.MIN_GROUPS_PER_MINUTE..SpeedPacing.MAX_GROUPS_PER_MINUTE && suggestion.fromRate != suggestion.toRate &&
            suggestion.sampleCount in 2..PersonalizationPolicy.MAX_EVIDENCE_RUNS && suggestion.evidenceRunIds.size == suggestion.sampleCount &&
            suggestion.evidenceRunIds.distinct().size == suggestion.sampleCount && suggestion.evidenceRunIds.all { id ->
                id.isNotBlank() && id.length <= 200 && id.none { it.isISOControl() }
            } && suggestion.total in PersonalizationPolicy.MIN_QUESTIONS..suggestion.sampleCount * SpeedChunker.MAX_UNIT_PARAGRAPHS &&
            suggestion.correct in 0..suggestion.total && digest.matches(suggestion.evidenceHash) && Regex("[a-zA-Z0-9_-]{1,40}").matches(suggestion.ruleVersion)
    }
}
