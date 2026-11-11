package dev.lexicon.domain

import kotlinx.serialization.Serializable

/** An explicit run-start association; profileRevision is the decision's original applied revision. */
@Serializable
data class AppliedPaceBinding(
    val decisionId: String,
    val profileRevision: Long,
    val granularity: SpeedGranularity,
    val rate: Int,
)

data class PersonalizationOutcome(
    val decisionId: String,
    val granularity: SpeedGranularity,
    val rate: Int,
    val sampleCount: Int,
    val materialCount: Int,
    val correct: Int,
    val total: Int,
    val pauses: Int,
    val revisits: Int,
    val evidenceEnough: Boolean,
    val accuracy: Double?,
    val evidenceRunIds: List<String>,
)

/** Describes subsequent observed exercises, without estimating a causal benefit from the change. */
object PersonalizationOutcomes {
    /**
     * The controller must latch any manual rate/granularity edit, including edits that return to
     * the same value. A later matching number is not proof that a suggestion was followed.
     */
    fun bindingForStart(
        profile: PersonalizationProfile,
        granularity: SpeedGranularity,
        rate: Int,
        manuallyAdjusted: Boolean,
    ): AppliedPaceBinding? {
        if (manuallyAdjusted || !profile.enabled || profile.revision <= 0 ||
            profile.granularity != granularity || profile.rateFor(granularity) != rate) return null
        val token = profile.activePaceBinding ?: return null
        if (token.granularity != granularity || token.rate != rate || token.profileRevision > profile.revision) return null
        profile.decisions.groupBy { it.id }.values.mapNotNull { it.distinct().singleOrNull() }.singleOrNull {
            validDecision(it) && it.id == token.decisionId && it.status == PersonalizationDecisionStatus.APPLIED && it.appliedRevision == token.profileRevision &&
                it.suggestion.granularity == granularity && it.suggestion.toRate == rate
        } ?: return null
        return token
    }

    fun summarize(decision: PersonalizationDecision, runs: List<SpeedRun>): PersonalizationOutcome {
        val change = decision.suggestion
        val evidence = if (!validDecision(decision)) emptyList() else runs.filter { it.id.isNotBlank() }
            .groupBy { it.id }.values.mapNotNull { it.distinct().singleOrNull() }
            .filter { run ->
                val binding = run.paceBinding
                binding != null && binding.decisionId == decision.id && binding.profileRevision == decision.appliedRevision &&
                    binding.granularity == change.granularity && binding.rate == change.toRate &&
                    run.startedAt >= decision.decidedAt &&
                    (decision.status != PersonalizationDecisionStatus.UNDONE || run.finishedAt < decision.updatedAt) &&
                    PersonalizationPolicy.eligible(run, change.granularity, change.toRate)
            }
            .sortedWith(compareBy<SpeedRun> { it.startedAt }.thenBy { it.finishedAt }.thenBy { it.id })
            // Re-imported or syndicated copies of the same text count as one material. Keep the
            // earliest eligible record instead of selecting the highest-scoring later practice.
            .distinctBy { it.bodyHash }
        val total = evidence.sumOf { it.questionCount }
        val correct = evidence.sumOf { run -> run.answers.count { it.correct } }
        val materials = evidence.map { it.bodyHash }.distinct().size
        return PersonalizationOutcome(decision.id, change.granularity, change.toRate, evidence.size, materials,
            correct, total, evidence.sumOf { it.pauses }, evidence.sumOf { it.revisits },
            materials >= PersonalizationPolicy.MIN_MATERIALS && total >= PersonalizationPolicy.MIN_QUESTIONS,
            if (total > 0) correct.toDouble() / total else null, evidence.map { it.id })
    }

    private fun validDecision(decision: PersonalizationDecision): Boolean =
        decision.id.isNotBlank() && decision.id == decision.suggestion.id &&
            decision.status != PersonalizationDecisionStatus.DISMISSED &&
            decision.appliedRevision != null && decision.appliedRevision > 0 &&
            decision.decidedAt >= 0 && decision.updatedAt >= decision.decidedAt &&
            decision.suggestion.fromRate in SpeedPacing.MIN_GROUPS_PER_MINUTE..SpeedPacing.MAX_GROUPS_PER_MINUTE &&
            decision.suggestion.toRate in SpeedPacing.MIN_GROUPS_PER_MINUTE..SpeedPacing.MAX_GROUPS_PER_MINUTE &&
            decision.suggestion.fromRate != decision.suggestion.toRate
}
