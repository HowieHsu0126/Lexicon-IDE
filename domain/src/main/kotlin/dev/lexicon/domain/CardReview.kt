package dev.lexicon.domain

/** Only APPLIED records a completed review; all other outcomes leave scheduling unchanged. */
enum class ReviewOutcome { APPLIED, PAUSED, STALE, MISSING }

/** Pausing controls queue membership without changing the stored FSRS state or original due time. */
fun Card.isDueForReview(now: Long): Boolean =
    !reviewPaused && (state.phase == CardPhase.NEW || state.dueAt <= now)
