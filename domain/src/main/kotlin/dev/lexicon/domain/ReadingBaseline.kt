package dev.lexicon.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** A first-exposure self-test is separate from guided, faded, and familiar-text natural rounds. */
@Serializable
data class ReadingBaselineAttempt(
    val id: String,
    val articleId: String,
    val bodyHash: String,
    val planKey: String,
    val startedAt: Long,
    val finishedAt: Long,
    val paragraphs: List<NaturalParagraphTiming>,
    val answers: List<SpeedAnswer>,
    val questionCount: Int,
    val readingFinishedAt: Long? = null,
    val revealedAt: Long? = null,
    val completed: Boolean = false,
    val pauses: Int = 0,
    val localUnseen: Boolean = false,
    val userConfirmedUnseen: Boolean = false,
    /** Cross-session continuation remains useful practice, not an uninterrupted baseline. */
    val resumedFromHistory: Boolean = false,
)

data class BaselineSourceParagraph(val paragraph: Int, val start: Int, val text: String) {
    val end: Int get() = start + text.length
}

/** Frozen selected fragments; never a reconstructed or newly downloaded full article. */
data class ReadingBaselineMaterial(
    val planKey: String,
    val bodyHash: String,
    val sourceRef: SourceRef,
    val paragraphs: List<BaselineSourceParagraph>,
    /** Frozen when an attempt begins; legacy or unclassified records keep null. */
    val topic: String? = null,
) {
    fun textAt(start: Int, end: Int): String? = paragraphs.singleOrNull {
        start >= it.start && end > start && end <= it.end
    }?.let { it.text.substring(start - it.start, end - it.start) }
}

data class ReadingBaselineSummary(
    val activeMillis: Long,
    val wordsRead: Int,
    val completeReading: Boolean,
    val answeredQuestions: Int,
    val totalQuestions: Int,
    val accuracy: Double?,
    val wordsPerMinute: Double?,
    val effectiveWordsPerMinute: Double?,
    val qualifiesAsBaseline: Boolean,
)

interface ReadingBaselineRepository {
    val library: Flow<List<ReadingBaselineAttempt>>
    suspend fun begin(planKey: String, userConfirmedUnseen: Boolean): ReadingBaselineAttempt
    suspend fun save(attempt: ReadingBaselineAttempt): ReadingBaselineAttempt
    suspend fun get(id: String): ReadingBaselineAttempt?
    suspend fun material(id: String): ReadingBaselineMaterial? = null
    suspend fun resume(id: String): ReadingBaselineAttempt =
        throw UnsupportedOperationException("Saved practice continuation is unavailable")
}

object ReadingBaselineMetrics {
    const val MIN_READING_MILLIS = 10_000L
    const val MAX_ACTIVE_MILLIS = 86_400_000L

    /** Checks describe this text and these questions; they are not a standardized ability score. */
    fun summarize(attempt: ReadingBaselineAttempt, plan: SpeedPlan): ReadingBaselineSummary {
        require(attempt.id.isNotBlank() && attempt.articleId == plan.articleId && attempt.bodyHash == plan.bodyHash && attempt.planKey == plan.planKey)
        require(plan.paragraphIndices.size in 1..SpeedChunker.MAX_UNIT_PARAGRAPHS && plan.paragraphIndices.distinct().size == plan.paragraphIndices.size)
        require(attempt.paragraphs.map { it.paragraph } == plan.paragraphIndices)
        require(plan.chunks.all { it.wordCount > 0 && it.paragraph in plan.paragraphIndices })
        require(plan.paragraphIndices.all { index -> plan.chunks.any { it.paragraph == index } })
        require(plan.chunks.sumOf { it.wordCount.toLong() } in 1..SpeedChunker.MAX_UNIT_CHARACTERS.toLong())
        require(attempt.startedAt >= 0 && attempt.finishedAt >= attempt.startedAt && attempt.pauses in 0..10_000)
        require(attempt.paragraphs.all { it.activeMillis in 0..MAX_ACTIVE_MILLIS && (!it.completed || it.activeMillis > 0) })
        val active = attempt.paragraphs.sumOf { it.activeMillis }
        val readingEnd = attempt.readingFinishedAt
        require(readingEnd == null || readingEnd in attempt.startedAt..attempt.finishedAt)
        require(active <= MAX_ACTIVE_MILLIS && active <= (readingEnd ?: attempt.finishedAt) - attempt.startedAt)
        val finishedParagraphs = attempt.paragraphs.filter { it.completed }.map { it.paragraph }.toSet()
        val allParagraphs = finishedParagraphs.size == plan.paragraphIndices.size
        require(readingEnd == null || allParagraphs)
        val completeReading = allParagraphs && readingEnd != null

        require(attempt.questionCount == plan.questions.size)
        require(plan.questions.map { it.id }.distinct().size == plan.questions.size)
        require(plan.questions.isEmpty() || plan.questions.map { it.paragraph }.sorted() == plan.paragraphIndices.sorted())
        require(plan.questions.all { it.id.isNotBlank() && it.paragraph in plan.paragraphIndices &&
            it.options.size in 2..4 && it.answerIndex in it.options.indices })
        require(attempt.answers.map { it.questionId }.distinct().size == attempt.answers.size)
        val questions = plan.questions.associateBy { it.id }
        require(attempt.answers.all { answer -> questions[answer.questionId]?.let { answer.selectedIndex in it.options.indices } == true })
        require(attempt.answers.isEmpty() || completeReading)
        val allAnswers = attempt.answers.size == plan.questions.size
        require(!attempt.completed || (completeReading && allAnswers))
        val revealed = attempt.revealedAt
        require(revealed == null || (attempt.completed && completeReading && allAnswers && revealed in requireNotNull(readingEnd)..attempt.finishedAt))

        val words = plan.chunks.filter { it.paragraph in finishedParagraphs }.sumOf { it.wordCount }
        val wpm = if (completeReading && active >= MIN_READING_MILLIS && words > 0) words * 60_000.0 / active else null
        // The frozen plan is authoritative. Pending UI answers may deliberately hide `correct`.
        val accuracy = if (attempt.completed && revealed != null && plan.questions.isNotEmpty() && allAnswers) {
            attempt.answers.count { answer -> answer.selectedIndex == questions.getValue(answer.questionId).answerIndex }.toDouble() / plan.questions.size
        } else null
        val qualifies = !attempt.resumedFromHistory && plan.origin == "ai" && plan.basePlanKey == null && attempt.localUnseen &&
            attempt.userConfirmedUnseen && wpm != null && accuracy != null
        return ReadingBaselineSummary(active, words, completeReading, attempt.answers.size, plan.questions.size,
            accuracy, wpm, if (qualifies) requireNotNull(wpm) * requireNotNull(accuracy) else null, qualifies)
    }
}
