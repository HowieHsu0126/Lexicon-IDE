package dev.lexicon.domain

/** Validated frozen source and plan are supplied by the repository, never by a chart or model. */
data class BaselineProgressRecord(
    val attempt: ReadingBaselineAttempt,
    val plan: SpeedPlan?,
    val material: ReadingBaselineMaterial?,
)

enum class BaselineExclusion { INCOMPLETE, CONTINUATION, FAMILIAR, UNMEASURED, UNAVAILABLE, DUPLICATE }

data class BaselineProgressPoint(
    val attemptId: String,
    val articleId: String,
    val bodyHash: String,
    val measuredAt: Long,
    val sourceRef: SourceRef,
    val topic: String?,
    val wordsRead: Int,
    val activeMillis: Long,
    val correct: Int,
    val questions: Int,
    val wordsPerMinute: Double,
    val effectiveWordsPerMinute: Double,
)

data class BaselineProgressSummary(
    /** Ascending measurement time; different measurements on one date remain separate. */
    val points: List<BaselineProgressPoint> = emptyList(),
    val exclusions: Map<BaselineExclusion, Int> = emptyMap(),
    val materialCount: Int = 0,
    val correct: Int = 0,
    val questions: Int = 0,
    val weightedWordsPerMinute: Double? = null,
)

/** Descriptive exercise history, without attributing change to training or estimating ability. */
object BaselineProgressCalculator {
    fun summarize(records: List<BaselineProgressRecord>): BaselineProgressSummary {
        val excluded = mutableMapOf<BaselineExclusion, Int>()
        fun exclude(reason: BaselineExclusion) { excluded[reason] = (excluded[reason] ?: 0) + 1 }
        val candidates = mutableListOf<BaselineProgressPoint>()
        records.groupBy { it.attempt.id }.values.forEach { copies ->
            val unique = copies.distinct()
            if (unique.size != 1 || unique.single().attempt.id.isBlank()) {
                exclude(BaselineExclusion.UNAVAILABLE); return@forEach
            }
            val record = unique.single()
            val attempt = record.attempt
            val plan = record.plan
            val material = record.material
            val metrics = runCatching {
                requireNotNull(plan); requireNotNull(material)
                validateFrozenMaterial(plan, material)
                ReadingBaselineMetrics.summarize(attempt, plan)
            }.getOrNull()
            val reason = when {
                metrics == null -> BaselineExclusion.UNAVAILABLE
                !attempt.completed -> BaselineExclusion.INCOMPLETE
                attempt.resumedFromHistory -> BaselineExclusion.CONTINUATION
                !attempt.localUnseen || !attempt.userConfirmedUnseen -> BaselineExclusion.FAMILIAR
                !metrics.qualifiesAsBaseline -> BaselineExclusion.UNMEASURED
                else -> null
            }
            if (reason != null) { exclude(reason); return@forEach }
            requireNotNull(plan); requireNotNull(material); requireNotNull(metrics)
            val answers = plan.questions.associateBy { it.id }
            candidates += BaselineProgressPoint(attempt.id, attempt.articleId, attempt.bodyHash,
                requireNotNull(attempt.readingFinishedAt), material.sourceRef, material.topic?.takeIf { it in Topics.children },
                metrics.wordsRead, metrics.activeMillis,
                attempt.answers.count { it.selectedIndex == answers.getValue(it.questionId).answerIndex },
                plan.questions.size, requireNotNull(metrics.wordsPerMinute), requireNotNull(metrics.effectiveWordsPerMinute))
        }
        val seen = mutableSetOf<String>()
        val points = candidates.sortedWith(compareBy<BaselineProgressPoint> { it.measuredAt }.thenBy { it.attemptId })
            .filter { if (seen.add(it.bodyHash)) true else { exclude(BaselineExclusion.DUPLICATE); false } }
        return BaselineProgressSummary(points, excluded.toMap(), seen.size, points.sumOf { it.correct },
            points.sumOf { it.questions }, points.takeIf { it.isNotEmpty() }?.let {
                it.sumOf { p -> p.wordsRead.toDouble() } * 60_000.0 / it.sumOf { p -> p.activeMillis.toDouble() }
            })
    }

    /** Recheck measurable text against the frozen plan without reconstructing the full article. */
    private fun validateFrozenMaterial(plan: SpeedPlan, material: ReadingBaselineMaterial) {
        require(plan.planKey == SpeedChunker.planKey(plan))
        require(plan.origin in setOf("rules", "ai", "manual"))
        require(material.planKey == plan.planKey && material.bodyHash == plan.bodyHash)
        val source = material.paragraphs
        require(source.size in 1..SpeedChunker.MAX_UNIT_PARAGRAPHS && source.map { it.paragraph } == plan.paragraphIndices)
        require(plan.chunks.size in 1..SpeedChunker.MAX_UNIT_CHARACTERS && plan.questions.size <= source.size)
        require(source.all { it.paragraph >= 0 && it.start >= 0 && it.text.isNotBlank() && it.start <= Int.MAX_VALUE - it.text.length })
        require(source.sumOf { it.text.length.toLong() } <= SpeedChunker.MAX_UNIT_CHARACTERS)
        require(source.zipWithNext().all { (a, b) -> a.paragraph.toLong() + 1 == b.paragraph.toLong() && a.end < b.start })
        val text = source.joinToString("\n\n") { it.text }
        val compactParagraphs = ParagraphFocus.paragraphs(text)
        require(compactParagraphs.size == source.size)
        val positions = source.withIndex().associate { it.value.paragraph to it.index }
        fun paragraph(original: Int) = positions.getValue(original)
        fun offset(original: Int, originalParagraph: Int): Int {
            val index = paragraph(originalParagraph)
            val part = source[index]
            require(original in part.start..part.end)
            return original - part.start + compactParagraphs[index].start
        }
        val compact = plan.copy(paragraphIndices = source.indices.toList(),
            chunks = plan.chunks.map { chunk -> chunk.copy(
                start = offset(chunk.start, chunk.paragraph), end = offset(chunk.end, chunk.paragraph),
                paragraph = paragraph(chunk.paragraph)) },
            questions = plan.questions.map { question -> question.copy(
                evidenceStart = offset(question.evidenceStart, question.paragraph),
                evidenceEnd = offset(question.evidenceEnd, question.paragraph), paragraph = paragraph(question.paragraph)) })
        SpeedChunker.validate(compact, text)
    }
}
