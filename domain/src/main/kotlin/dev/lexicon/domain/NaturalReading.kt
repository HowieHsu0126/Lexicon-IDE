package dev.lexicon.domain

import kotlinx.serialization.Serializable

/** A paragraph's active foreground time and the reader's explicit completion declaration. */
@Serializable data class NaturalParagraphTiming(
    val paragraph: Int,
    val activeMillis: Long,
    val completed: Boolean,
)

/** Independent from paced runs: no inferred block dwell or repeated comprehension score. */
@Serializable data class NaturalReadingAttempt(
    val id: String,
    val articleId: String,
    val bodyHash: String,
    val planKey: String,
    val parentRunId: String,
    val startedAt: Long,
    val finishedAt: Long,
    val paragraphs: List<NaturalParagraphTiming>,
    val completed: Boolean,
    val pauses: Int,
    val revisits: Int,
)

data class NaturalReadingSummary(
    val activeMillis: Long,
    val completedParagraphs: Int,
    val totalParagraphs: Int,
    /** Frozen text words in paragraphs the user declared finished; not measured eye movement. */
    val wordsRead: Int,
    /** Only a complete self-timed exercise receives a text-words/time estimate. */
    val wordsPerMinute: Double?,
)

object NaturalReadingMetrics {
    const val MAX_ACTIVE_MILLIS = 86_400_000L

    /** Reject mismatched source or impossible timing before deriving an estimate. */
    fun summarize(attempt: NaturalReadingAttempt, plan: SpeedPlan): NaturalReadingSummary {
        require(attempt.articleId == plan.articleId && attempt.bodyHash == plan.bodyHash && attempt.planKey == plan.planKey)
        require(plan.paragraphIndices.size in 1..SpeedChunker.MAX_UNIT_PARAGRAPHS)
        require(attempt.paragraphs.map { it.paragraph } == plan.paragraphIndices)
        require(attempt.startedAt >= 0 && attempt.finishedAt >= attempt.startedAt)
        require(attempt.pauses in 0..10_000 && attempt.revisits in 0..10_000)
        require(attempt.paragraphs.all { it.activeMillis in 0..MAX_ACTIVE_MILLIS && (!it.completed || it.activeMillis > 0) })
        val active = attempt.paragraphs.sumOf { it.activeMillis }
        require(active <= MAX_ACTIVE_MILLIS && active <= attempt.finishedAt - attempt.startedAt)
        val completed = attempt.paragraphs.filter { it.completed }.map { it.paragraph }.toSet()
        require(!attempt.completed || completed.size == plan.paragraphIndices.size)
        val words = plan.chunks.filter { it.paragraph in completed }.sumOf { it.wordCount }
        val rate = if (attempt.completed && active > 0 && words > 0) words * 60_000.0 / active else null
        return NaturalReadingSummary(active, completed.size, plan.paragraphIndices.size, words, rate)
    }
}
