package dev.lexicon.ide.ui

import dev.lexicon.domain.ReviewDayRange
import dev.lexicon.domain.ReviewDaySummary

/** A read model of committed local reviews, independent of the currently visible card. */
internal data class ReviewProgressUiState(
    val range: ReviewDayRange? = null,
    val summary: ReviewDaySummary? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
)
