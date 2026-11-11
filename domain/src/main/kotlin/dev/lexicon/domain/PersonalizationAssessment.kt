package dev.lexicon.domain

/** A read-only explanation of the existing pace-v2 decision, not a reading ability estimate. */
enum class PaceAssessmentStatus {
    DISABLED, INVALID_PROFILE, INSUFFICIENT_EVIDENCE, MAINTAIN, RATE_LIMIT, HELD, ALREADY_DECIDED, SUGGESTED,
}

/**
 * Each excluded canonical record has one reason. Conflict groups are counted once per ID;
 * identical duplicates are one record; distinct blank-ID snapshots count as invalid records.
 */
enum class PaceExclusionReason {
    /** Different granularity or target rate from the assessed setting. */
    OTHER_CONFIGURATION,
    /** A repeated attempt, faded round, or a round with a parent run. */
    REPEAT_OR_FADED,
    /** Missing full completion or explicitly verified constant, fully delivered pacing. */
    INCOMPLETE_OR_UNSTABLE_PACING,
    /** Non-AI material or no available comprehension question/answer. */
    NO_AI_QUESTIONS,
    /** Remaining identity, timing, measurement, or question contract failures. */
    INVALID_RECORD,
    /** Multiple different snapshots claim the same nonblank run ID. */
    CONFLICTING_DUPLICATE,
    /** Eligible, but outside the existing latest-six/minimum-evidence selection. Not invalid. */
    OUTSIDE_SELECTED_EVIDENCE,
}

data class PaceAssessment(
    val granularity: SpeedGranularity,
    val rate: Int,
    val status: PaceAssessmentStatus,
    /** Exact selected original records in policy order, newest first; at most six. */
    val evidence: List<SpeedRun>,
    val correct: Int,
    val total: Int,
    val materialCount: Int,
    val suggestion: PaceSuggestion?,
    /** Disabled/invalid assessments do not evaluate history and leave this map empty. */
    val excludedCounts: Map<PaceExclusionReason, Int>,
)
