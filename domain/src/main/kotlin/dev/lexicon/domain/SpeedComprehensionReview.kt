package dev.lexicon.domain

/** A read-only review of the recorded exercise, not a new score or an assessment of ability. */
enum class SpeedComprehensionReviewStatus { AVAILABLE, NO_QUESTIONS, INCOMPLETE, UNAVAILABLE }

data class SpeedComprehensionReviewItem(
    val question: SpeedQuestion,
    val answer: SpeedAnswer,
    /** Exact substring of the validated source snapshot, including cross-sentence evidence. */
    val evidenceText: String,
)

data class SpeedComprehensionReview(
    val status: SpeedComprehensionReviewStatus,
    val items: List<SpeedComprehensionReviewItem> = emptyList(),
)

object SpeedComprehensionReviews {
    /** Source provenance is checked by the repository before it supplies the exact quotes. */
    fun inspect(run: SpeedRun, plan: SpeedPlan, evidence: Map<String, String>): SpeedComprehensionReview {
        fun status(value: SpeedComprehensionReviewStatus) = SpeedComprehensionReview(value)
        if (run.id.isBlank() || run.articleId != plan.articleId || run.bodyHash != plan.bodyHash ||
            run.planKey != plan.planKey || run.granularity != plan.granularity || run.planOrigin != plan.origin)
            return status(SpeedComprehensionReviewStatus.UNAVAILABLE)
        if (run.round != SpeedPacedRound.GUIDED) return status(SpeedComprehensionReviewStatus.NO_QUESTIONS)
        val questions = plan.questions
        if (run.questionCount != questions.size || questions.size > SpeedChunker.MAX_UNIT_PARAGRAPHS ||
            questions.any { it.id.isBlank() } || questions.map { it.id }.distinct().size != questions.size ||
            run.answers.map { it.questionId }.distinct().size != run.answers.size)
            return status(SpeedComprehensionReviewStatus.UNAVAILABLE)
        if (run.answers.any { answer ->
                val question = questions.singleOrNull { it.id == answer.questionId }
                question == null || answer.selectedIndex !in question.options.indices ||
                    answer.correct != (answer.selectedIndex == question.answerIndex)
            }) return status(SpeedComprehensionReviewStatus.UNAVAILABLE)
        if (questions.isEmpty()) return status(SpeedComprehensionReviewStatus.NO_QUESTIONS)
        // Do not reveal the remaining solutions from a partially answered or interrupted run.
        if (!run.completed || run.answers.size != questions.size)
            return status(SpeedComprehensionReviewStatus.INCOMPLETE)
        if (questions.any { question ->
                question.options.size != 3 || question.options.distinct().size != 3 ||
                    question.options.any { it.isBlank() } || question.answerIndex !in question.options.indices ||
                    question.prompt.isBlank() || question.explanation.isBlank() ||
                    question.evidenceStart < 0 || question.evidenceEnd <= question.evidenceStart ||
                    evidence[question.id]?.let { it.isNotBlank() && it.length == question.evidenceEnd - question.evidenceStart } != true
            }) return status(SpeedComprehensionReviewStatus.UNAVAILABLE)
        return SpeedComprehensionReview(SpeedComprehensionReviewStatus.AVAILABLE, questions.map { question ->
            SpeedComprehensionReviewItem(question, run.answers.single { it.questionId == question.id }, evidence.getValue(question.id))
        })
    }
}
