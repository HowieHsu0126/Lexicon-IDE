package dev.lexicon.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/** A local calendar day is not always 24 hours. Both boundaries use the selected zone's rules. */
data class ReviewDayRange(val startInclusive: Long, val endExclusive: Long) {
    init { require(startInclusive < endExclusive) }

    companion object {
        fun from(now: Long, zoneId: ZoneId = ZoneId.systemDefault()): ReviewDayRange {
            val date = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
            return ReviewDayRange(
                date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            )
        }
    }
}

/** Identity of a committed rating. Card content is resolved separately from its stable cardId. */
data class ReviewReceipt(
    val logId: String,
    val cardId: String,
    val reviewedAt: Long,
    val rating: Rating,
    val beforeReps: Int,
    val afterReps: Int,
)

data class ReviewDaySummary(val count: Int = 0, val latestReceipt: ReviewReceipt? = null)

/** Reads committed logs only; observing history never changes a card or replays its rating. */
interface ReviewHistoryRepository {
    fun observeDay(range: ReviewDayRange): Flow<ReviewDaySummary>
}

/** Unreadable history must remain unknown instead of being presented as zero completed reviews. */
class ReviewHistoryUnavailableException : IllegalStateException("Review history is unavailable")
