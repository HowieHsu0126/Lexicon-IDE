package dev.lexicon.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable enum class SpeedGranularity(val minimumWords: Int, val preferredWords: Int, val maximumWords: Int) {
    L1(2, 3, 4), L2(3, 4, 6), L3(5, 6, 8),
}

/** Half-open offsets always refer to the unchanged full article in UTF-16. */
@Serializable data class SenseChunk(
    val start: Int, val end: Int, val paragraph: Int, val sentence: Int,
    val wordCount: Int, val reason: String,
)
@Serializable data class SpeedQuestion(
    val id: String, val paragraph: Int, val prompt: String, val options: List<String>,
    val answerIndex: Int, val evidenceStart: Int, val evidenceEnd: Int, val explanation: String,
)
@Serializable data class SpeedPlan(
    val articleId: String, val bodyHash: String, val granularity: SpeedGranularity,
    val paragraphIndices: List<Int>, val chunks: List<SenseChunk>,
    val questions: List<SpeedQuestion> = emptyList(), val origin: String = "rules",
    val model: String = "", val createdAt: Long = 0, val version: Int = 1, val planKey: String = "",
    val basePlanKey: String? = null,
)
@Serializable data class SpeedAnswer(val questionId: String, val selectedIndex: Int, val correct: Boolean)
@Serializable enum class SpeedPacedRound { GUIDED, FADED }
@Serializable data class SpeedRun(
    val id: String, val articleId: String, val bodyHash: String, val granularity: SpeedGranularity,
    val planKey: String, val startedAt: Long, val finishedAt: Long, val activeMillis: Long,
    val wordsRead: Int, val groupsRead: Int, val groupMillis: List<Long>, val revisits: Int,
    val pauses: Int, val answers: List<SpeedAnswer>, val questionCount: Int, val completed: Boolean,
    val groupsPerMinute: Int, val planOrigin: String, val firstAttempt: Boolean = true,
    /** Null is legacy/unknown evidence. Only explicitly constant-pace runs can inform pace advice. */
    val pacingStable: Boolean? = null,
    /** Only true when all groups received their scheduled dwell; null is legacy/unknown. */
    val pacedCompletion: Boolean? = null,
    val round: SpeedPacedRound = SpeedPacedRound.GUIDED,
    val parentRunId: String? = null,
    /** Captured once when a guided run starts; never inferred later from a matching numeric rate. */
    val paceBinding: AppliedPaceBinding? = null,
    /** Null means that this run did not record per-chunk interactions; never infer visits. */
    val chunkInteractions: List<SpeedChunkInteraction>? = null,
)
@Serializable data class SpeedCorrection(
    val id: String, val articleId: String, val bodyHash: String, val granularity: SpeedGranularity,
    val start: Int, val end: Int, val kind: String, val comment: String, val createdAt: Long,
    val planKey: String = "",
)
@Serializable data class SpeedLibrary(
    val runs: List<SpeedRun> = emptyList(), val corrections: List<SpeedCorrection> = emptyList(),
    val naturalAttempts: List<NaturalReadingAttempt> = emptyList(),
)

/** Editable boundaries are absolute UTF-16 offsets strictly inside this unchanged sentence. */
@Serializable data class SpeedSegmentation(
    val planKey: String, val basePlanKey: String, val articleId: String, val bodyHash: String,
    val granularity: SpeedGranularity, val paragraph: Int, val sentence: Int,
    val start: Int, val end: Int, val text: String,
    val suggestedBreaks: List<Int>, val breaks: List<Int>, val legalBreaks: List<Int>,
    val hasPersonalEdits: Boolean,
)

interface SpeedReadingRepository {
    val library: Flow<SpeedLibrary>
    suspend fun cachedPlan(planKey: String): SpeedPlan? = null
    /** Inspect an unchanged saved run against its original local plan and source; never regenerates. */
    suspend fun comprehensionReview(run: SpeedRun): SpeedComprehensionReview? = null
    suspend fun prepare(
        article: Article, body: ArticleBody, granularity: SpeedGranularity,
        startParagraph: Int, paragraphCount: Int, aiAllowed: Boolean, useAi: Boolean,
    ): SpeedPlan
    suspend fun retryPrepare(
        article: Article, body: ArticleBody, granularity: SpeedGranularity,
        startParagraph: Int, paragraphCount: Int, aiAllowed: Boolean, useAi: Boolean,
    ): SpeedPlan = prepare(article, body, granularity, startParagraph, paragraphCount, aiAllowed, useAi)
    suspend fun saveRun(run: SpeedRun)
    suspend fun saveNaturalAttempt(attempt: NaturalReadingAttempt): Unit =
        throw UnsupportedOperationException("Natural reading is unavailable")
    suspend fun saveCorrection(correction: SpeedCorrection)
    suspend fun markSentencePreviewed(planKey: String, paragraph: Int) = Unit
    suspend fun sentenceForEditing(planKey: String, chunkIndex: Int): SpeedSegmentation? = null
    suspend fun editSentence(planKey: String, paragraph: Int, sentence: Int, breakOffsets: List<Int>): SpeedPlan =
        throw UnsupportedOperationException("Personal segmentation is unavailable")
    suspend fun restoreSegmentation(planKey: String): SpeedPlan =
        throw UnsupportedOperationException("Original segmentation is unavailable")
}

/** Playback pacing is a setting, not measured reading ability. Longer blocks receive more time. */
object SpeedPacing {
    const val MIN_GROUPS_PER_MINUTE = 30
    const val MAX_GROUPS_PER_MINUTE = 180

    fun durationMillis(chunk: SenseChunk, groupsPerMinute: Int, averageWords: Double = 4.0): Long {
        require(chunk.wordCount > 0)
        require(averageWords.isFinite() && averageWords > 0)
        val base = 60_000.0 / groupsPerMinute.coerceIn(MIN_GROUPS_PER_MINUTE, MAX_GROUPS_PER_MINUTE)
        return (base * (.45 + .55 * chunk.wordCount / averageWords)).toLong().coerceIn(120, 20_000)
    }

    fun equivalentWordsPerMinute(plan: SpeedPlan, groupsPerMinute: Int): Double? {
        if (plan.chunks.isEmpty()) return null
        val average = plan.chunks.sumOf { it.wordCount }.toDouble() / plan.chunks.size
        val duration = plan.chunks.sumOf { durationMillis(it, groupsPerMinute, average) }
        return plan.chunks.sumOf { it.wordCount }.toDouble() * 60_000 / duration
    }
}

data class SpeedSummary(
    val wordsPerMinute: Double?, val groupsPerMinute: Double?, val comprehension: Double?,
    val effectiveWordsPerMinute: Double?, val meanGroupMillis: Double?, val answeredQuestions: Int,
)

/** First-attempt question results describe this exercise; missing checks never become zero. */
object SpeedMetrics {
    fun summarize(run: SpeedRun): SpeedSummary {
        val timeValid = run.activeMillis > 0 && run.finishedAt >= run.startedAt &&
            run.activeMillis <= run.finishedAt - run.startedAt && run.wordsRead > 0 && run.groupsRead > 0
        val wpm = if (timeValid) run.wordsRead * 60_000.0 / run.activeMillis else null
        val gpm = if (timeValid) run.groupsRead * 60_000.0 / run.activeMillis else null
        val answers = run.answers.distinctBy { it.questionId }
        val completeCheck = run.round == SpeedPacedRound.GUIDED && run.completed && run.firstAttempt && run.questionCount > 0 &&
            answers.size == run.questionCount && answers.size == run.answers.size &&
            answers.all { it.questionId.isNotBlank() && it.selectedIndex >= 0 }
        val comprehension = if (completeCheck) answers.count { it.correct }.toDouble() / answers.size else null
        val durations = run.groupMillis.filter { it > 0 }
        return SpeedSummary(wpm, gpm, comprehension,
            if (wpm != null && comprehension != null) wpm * comprehension else null,
            durations.takeIf { it.isNotEmpty() }?.average(), answers.size)
    }
}
