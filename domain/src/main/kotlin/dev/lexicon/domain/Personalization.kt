package dev.lexicon.domain

import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** User-confirmed training defaults. This profile never changes an active reading session. */
@Serializable data class PersonalizationProfile(
    val revision: Long = 0,
    val enabled: Boolean = true,
    val granularity: SpeedGranularity = SpeedGranularity.L2,
    val rates: Map<SpeedGranularity, Int> = SpeedGranularity.entries.associateWith { 60 },
    val sentenceOnly: Boolean = false,
    val decisions: List<PersonalizationDecision> = emptyList(),
    val holds: List<PaceHold> = emptyList(),
    /** Persisted only by an explicit apply; manual pace/granularity edits invalidate this token. */
    val activePaceBinding: AppliedPaceBinding? = null,
    /** Independent undo provenance per granularity; never inferred from a matching numeric rate. */
    val undoPaceBindings: Map<SpeedGranularity, AppliedPaceBinding> = emptyMap(),
) {
    fun rateFor(granularity: SpeedGranularity = this.granularity): Int = (rates[granularity] ?: 60)
        .coerceIn(SpeedPacing.MIN_GROUPS_PER_MINUTE, SpeedPacing.MAX_GROUPS_PER_MINUTE)
}

/** A deliberate choice persists across additional evidence until the user releases this hold. */
@Serializable data class PaceHold(
    val granularity: SpeedGranularity, val rate: Int, val direction: Int, val createdAt: Long,
) {
    init {
        require(direction == -1 || direction == 1)
        require(rate in SpeedPacing.MIN_GROUPS_PER_MINUTE..SpeedPacing.MAX_GROUPS_PER_MINUTE && createdAt >= 0)
    }
}

/** Evidence remains local; correctness describes these exercises, not general reading ability. */
@Serializable data class PaceSuggestion(
    val id: String,
    val granularity: SpeedGranularity,
    val fromRate: Int,
    val toRate: Int,
    val evidenceRunIds: List<String>,
    val correct: Int,
    val total: Int,
    val sampleCount: Int,
    val evidenceHash: String,
    val ruleVersion: String,
)

@Serializable enum class PersonalizationDecisionStatus { APPLIED, DISMISSED, UNDONE }

@Serializable data class PersonalizationDecision(
    val id: String,
    val suggestion: PaceSuggestion,
    val status: PersonalizationDecisionStatus,
    val decidedAt: Long,
    val updatedAt: Long = decidedAt,
    val appliedRevision: Long? = null,
)

/** A stale screen or a later manual choice must not be silently overwritten. */
class PersonalizationConflict(message: String) : IllegalStateException(message)

interface PersonalizationRepository {
    val state: Flow<PersonalizationProfile>
    val health: Flow<PersonalizationHealth>
        get() = kotlinx.coroutines.flow.flowOf(PersonalizationHealth(PersonalizationHealthStatus.OK))
    /** Preserve the original local bytes before resetting only a damaged profile to disabled. */
    suspend fun recoverCorrupt(expectedRowDigest: String): PersonalizationRecoveryReceipt =
        throw UnsupportedOperationException("Personalization recovery is unavailable")
    suspend fun remember(
        granularity: SpeedGranularity, rate: Int, sentenceOnly: Boolean, expectedRevision: Long,
        paceManuallyAdjusted: Boolean = false,
    ): PersonalizationProfile
    suspend fun setEnabled(enabled: Boolean, expectedRevision: Long): PersonalizationProfile
    /** A manual granularity choice ends run attribution without removing any saved rate's undo origin. */
    suspend fun invalidatePaceBinding(expectedRevision: Long): PersonalizationProfile =
        throw UnsupportedOperationException("Invalidating a training association is unavailable")
    /** Invalidate only this granularity's pace origin after an explicit local edit, without saving its draft rate. */
    suspend fun invalidatePaceUndo(granularity: SpeedGranularity, expectedRevision: Long): PersonalizationProfile =
        throw UnsupportedOperationException("Invalidating a pace origin is unavailable")
    /** Recompute the candidate from current local evidence before the atomic revision check/write. */
    suspend fun apply(suggestion: PaceSuggestion, expectedRevision: Long): PersonalizationProfile
    suspend fun dismiss(suggestion: PaceSuggestion, expectedRevision: Long): PersonalizationProfile
    /** Restore only this decision's rate; preserve later changes to every unrelated field. */
    suspend fun undo(decisionId: String, expectedRevision: Long): PersonalizationProfile
    suspend fun releaseHold(granularity: SpeedGranularity, expectedRevision: Long): PersonalizationProfile =
        throw UnsupportedOperationException("Releasing a pace hold is unavailable")
}

/**
 * A deterministic, local proposal rule. Playback speed is a setting that a user may try next time;
 * it is never a learned ability score. No proposal applies itself or invokes an AI service.
 */
object PersonalizationPolicy {
    const val RULE_VERSION = "pace-v2"
    const val MIN_QUESTIONS = 6
    const val MIN_MATERIALS = 2
    const val MAX_EVIDENCE_RUNS = 6
    private const val MAX_ACTIVE_MILLIS = 86_400_000L
    private val codec = Json { encodeDefaults = true }

    fun suggest(profile: PersonalizationProfile, runs: List<SpeedRun>): PaceSuggestion? = assess(profile, runs).suggestion

    /** One decision path supplies both the proposal and the evidence explanation shown by the UI. */
    fun assess(profile: PersonalizationProfile, runs: List<SpeedRun>): PaceAssessment {
        val rate = profile.rateFor()
        fun unevaluated(status: PaceAssessmentStatus) = PaceAssessment(profile.granularity, rate, status,
            emptyList(), 0, 0, 0, null, emptyMap())
        if (!profile.enabled) return unevaluated(PaceAssessmentStatus.DISABLED)
        if (profile.revision < 0) return unevaluated(PaceAssessmentStatus.INVALID_PROFILE)
        val excluded = mutableMapOf<PaceExclusionReason, Int>()
        fun exclude(reason: PaceExclusionReason, count: Int = 1) {
            if (count > 0) excluded[reason] = (excluded[reason] ?: 0) + count
        }
        exclude(PaceExclusionReason.INVALID_RECORD, runs.filter { it.id.isBlank() }.distinct().size)
        // The repository normally emits one canonical row per run. Reject conflicting copies
        // rather than selecting whichever snapshot happens to produce the more favorable score.
        val candidates = runs.filter { it.id.isNotBlank() }.groupBy { it.id }.values.mapNotNull { copies ->
            copies.distinct().singleOrNull().also { if (it == null) exclude(PaceExclusionReason.CONFLICTING_DUPLICATE) }
        }.filter { run ->
            val reason = exclusionReason(run, profile.granularity, rate)
            if (reason != null) exclude(reason)
            reason == null
        }
            .sortedWith(compareByDescending<SpeedRun> { it.finishedAt }.thenByDescending { it.startedAt }.thenBy { it.id })
        val evidence = mutableListOf<SpeedRun>()
        var questionCount = 0
        for (run in candidates.take(MAX_EVIDENCE_RUNS)) {
            evidence += run
            questionCount += run.questionCount
            if (questionCount >= MIN_QUESTIONS && materials(evidence) >= MIN_MATERIALS) break
        }
        exclude(PaceExclusionReason.OUTSIDE_SELECTED_EVIDENCE, candidates.size - evidence.size)
        val correct = evidence.sumOf { run -> run.answers.count { it.correct } }
        fun result(status: PaceAssessmentStatus, suggestion: PaceSuggestion? = null) = PaceAssessment(
            profile.granularity, rate, status, evidence.toList(), correct, questionCount, materials(evidence), suggestion, excluded.toMap())
        if (questionCount < MIN_QUESTIONS || evidence.size < 2 || materials(evidence) < MIN_MATERIALS)
            return result(PaceAssessmentStatus.INSUFFICIENT_EVIDENCE)

        val target = paceV2TargetRate(evidence, rate) ?: return result(PaceAssessmentStatus.MAINTAIN)
        if (target == rate) return result(PaceAssessmentStatus.RATE_LIMIT)
        val direction = target.compareTo(rate)
        if (profile.holds.any { it.granularity == profile.granularity && it.rate == rate && it.direction == direction })
            return result(PaceAssessmentStatus.HELD)

        val hash = canonicalEvidenceHash(evidence)
        val id = suggestionId(profile.granularity, rate, target, hash)
        if (profile.decisions.any { it.suggestion.id == id }) return result(PaceAssessmentStatus.ALREADY_DECIDED)
        return result(PaceAssessmentStatus.SUGGESTED, PaceSuggestion(id, profile.granularity, rate, target, evidence.map { it.id }, correct,
            questionCount, evidence.size, hash, RULE_VERSION))
    }

    /** Shared with historical verification; only answers are reordered and the original run order is retained. */
    internal fun canonicalEvidenceHash(evidence: List<SpeedRun>): String {
        val canonicalEvidence = evidence.map { it.copy(answers = it.answers.sortedBy { answer -> answer.questionId }) }
        // These later additions must not change an already-decided pace-v2 evidence identity.
        val frozenEvidence = JsonArray(canonicalEvidence.map {
            JsonObject(codec.encodeToJsonElement(it).jsonObject - setOf("round", "parentRunId", "paceBinding", "chunkInteractions"))
        })
        return digest(codec.encodeToString(frozenEvidence))
    }

    internal fun suggestionId(granularity: SpeedGranularity, fromRate: Int, toRate: Int, evidenceHash: String,
        ruleVersion: String = RULE_VERSION): String = "pace:" + digest("$ruleVersion|${granularity.name}|$fromRate|$toRate|$evidenceHash")

    /** The existing pace-v2 numerical rule, independent of today's profile, holds, and later records. */
    internal fun paceV2TargetRate(evidence: List<SpeedRun>, rate: Int): Int? {
        val questionCount = evidence.sumOf { it.questionCount }
        val correct = evidence.sumOf { run -> run.answers.count { it.correct } }
        val recentStable = evidence.take(2).all { run -> run.answers.count { it.correct } * 100 >= run.questionCount * 85 }
        return when {
            recentStable && correct * 100 >= questionCount * 85 -> (rate * 1.05).roundToInt()
            correct * 100 < questionCount * 70 -> (rate * .90).roundToInt()
            else -> return null
        }.coerceIn(SpeedPacing.MIN_GROUPS_PER_MINUTE, SpeedPacing.MAX_GROUPS_PER_MINUTE)
    }

    /** Eligibility stays authoritative; reasons explain its rejection without adding another selection rule. */
    private fun exclusionReason(run: SpeedRun, granularity: SpeedGranularity, rate: Int): PaceExclusionReason? {
        if (eligible(run, granularity, rate)) return null
        return when {
            run.granularity != granularity || run.groupsPerMinute != rate -> PaceExclusionReason.OTHER_CONFIGURATION
            run.round != SpeedPacedRound.GUIDED || run.parentRunId != null || !run.firstAttempt -> PaceExclusionReason.REPEAT_OR_FADED
            !run.completed || run.pacingStable != true || run.pacedCompletion != true -> PaceExclusionReason.INCOMPLETE_OR_UNSTABLE_PACING
            run.planOrigin != "ai" || run.questionCount == 0 || run.answers.isEmpty() -> PaceExclusionReason.NO_AI_QUESTIONS
            else -> PaceExclusionReason.INVALID_RECORD
        }
    }

    // Syndication or duplicate imports can assign different IDs to the same source text.
    private fun materials(runs: List<SpeedRun>) = runs.map { it.bodyHash }.distinct().size

    internal fun eligible(run: SpeedRun, granularity: SpeedGranularity, rate: Int): Boolean {
        if (run.round != SpeedPacedRound.GUIDED || run.parentRunId != null || !run.completed || !run.firstAttempt || run.pacingStable != true || run.pacedCompletion != true || run.planOrigin != "ai" ||
            run.granularity != granularity || run.groupsPerMinute != rate) return false
        if (run.articleId.isBlank() || run.bodyHash.isBlank() || run.planKey.isBlank() || run.startedAt < 0 ||
            run.finishedAt < run.startedAt || run.activeMillis !in 1..MAX_ACTIVE_MILLIS ||
            run.activeMillis > run.finishedAt - run.startedAt || run.wordsRead <= 0 || run.revisits < 0 || run.pauses < 0) return false
        if (run.questionCount !in 1..SpeedChunker.MAX_UNIT_PARAGRAPHS || run.answers.size != run.questionCount ||
            run.answers.map { it.questionId }.distinct().size != run.questionCount ||
            run.answers.any { it.questionId.isBlank() || it.selectedIndex !in 0..3 }) return false
        if (run.groupsRead != run.groupMillis.size || run.groupsRead !in 1..SpeedChunker.MAX_UNIT_CHARACTERS ||
            run.groupMillis.any { it !in 1..run.activeMillis } || run.groupMillis.sum() != run.activeMillis) return false
        val summary = SpeedMetrics.summarize(run)
        return summary.comprehension != null && summary.wordsPerMinute != null
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
