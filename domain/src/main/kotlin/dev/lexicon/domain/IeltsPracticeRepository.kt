package dev.lexicon.domain

import kotlinx.coroutines.flow.Flow

/** Local original practice only; neither storage nor these DTOs attest a model runtime. */
interface IeltsPracticeRepository {
    /** The calling service must verify generation and independent review before caching. */
    suspend fun cache(request: IeltsExerciseRequest, exercise: IeltsExercisePackage): String
    suspend fun begin(packageHash: String, attemptId: String, now: Long): IeltsSavedPractice
    suspend fun get(attemptId: String): IeltsSavedPractice?
    fun observeHistory(): Flow<List<IeltsPracticeHistoryItem>>

    /** Whole-draft CAS. Omit a response to clear it; duplicate or unknown question IDs fail. */
    suspend fun saveDraft(id: String, expectedRevision: Long, responses: List<IeltsResponse>,
        writingText: String, now: Long): IeltsSavedPractice

    /** Saves both writing drafts in the same revision. Legacy implementations reject Task 1. */
    suspend fun saveWritingDrafts(id: String, expectedRevision: Long, responses: List<IeltsResponse>,
        writingText: String, writingTask1Text: String?, now: Long): IeltsSavedPractice {
        require(writingTask1Text == null)
        return saveDraft(id, expectedRevision, responses, writingText, now)
    }

    /** Commits before returning answers. Repeating a committed ID returns its original submission. */
    suspend fun submit(id: String, expectedRevision: Long, now: Long): IeltsSavedPractice
}

/** A detached snapshot returned only after the repository has read its durable record. */
data class IeltsSavedPractice(
    val attemptId: String,
    val packageHash: String,
    val request: IeltsExerciseRequest,
    val exercise: IeltsExercisePackage,
    val draftResponses: List<IeltsResponse>,
    val writingText: String,
    val submission: IeltsSubmission?,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val writingTask1Text: String? = null,
) {
    val submitted: Boolean get() = submission != null
    fun candidate(): IeltsCandidateView {
        require(IeltsExerciseJson.packageHash(exercise) == packageHash)
        return IeltsExerciseValidator.candidateView(request, exercise)
    }

    /** A draft has no reveal view; restoring a commit must match its exact saved input. */
    fun answerView(): IeltsAnswerView? {
        val saved = submission ?: return null
        require(saved.attemptId == attemptId && saved.packageSha256 == packageHash)
        var state = IeltsPracticeSession.begin(request, exercise, attemptId)
        draftResponses.forEach { state = state.answer(it.questionId, it.answer) }
        state = state.write(writingText)
        writingTask1Text?.let { state = state.writeTask1(it) }
        return state.prepareSubmission(saved.submittedAt).confirmSaved(saved).answerView()
    }
}

enum class IeltsPracticeHistoryStatus { DRAFT, SUBMITTED, UNAVAILABLE }

/** No source, student writing, reference key, score, or raw storage error is exposed in the index. */
data class IeltsPracticeHistoryItem(
    val id: String,
    val attemptId: String?,
    val packageHash: String?,
    val articleId: String?,
    val status: IeltsPracticeHistoryStatus,
    val questionCount: Int?,
    val answeredCount: Int?,
    val createdAt: Long?,
    val updatedAt: Long?,
    val submittedAt: Long?,
)
