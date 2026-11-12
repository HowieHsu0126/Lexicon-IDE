package dev.lexicon.domain

import kotlinx.serialization.Serializable

/** Explicit learner intentions, never inferred grades or official ability estimates. */
@Serializable enum class IeltsStudyIntent { PRACTICE_AGAIN, QUESTIONABLE }

@Serializable data class IeltsStudyFeedback(
    val attemptId: String, val questionId: String, val packageHash: String, val submissionHash: String,
    val kind: IeltsReadingType, val intent: IeltsStudyIntent?, val revision: Long, val updatedAt: Long,
)

@Serializable data class IeltsStudyProfile(
    val revision: Long = 0, val updatedAt: Long = 0, val focus: IeltsStudyFeedback? = null,
)

data class IeltsStudySnapshot(val profile: IeltsStudyProfile, val feedback: List<IeltsStudyFeedback> = emptyList())

/** Immutable local association. The wire request contains the requested forms, not learning history. */
@Serializable data class IeltsStudyRequest(
    val requestId: String, val submitHash: String, val profile: IeltsStudyProfile,
    val createdAt: Long, val policy: String = "learner-selected-form-v1",
)

enum class IeltsStudyFailureCode { CONFLICT, UNAVAILABLE, INVALID, NOT_SUBMITTED }
class IeltsStudyFailure(val code: IeltsStudyFailureCode) : IllegalStateException(code.name)

interface IeltsStudyRepository {
    suspend fun load(attemptId: String? = null): IeltsStudySnapshot
    /** PRACTICE_AGAIN also explicitly selects the next form. A null intent is a versioned undo. */
    suspend fun reflect(attemptId: String, questionId: String, intent: IeltsStudyIntent?,
        expectedFeedbackRevision: Long, expectedProfileRevision: Long, now: Long): IeltsStudySnapshot
    suspend fun useBalanced(expectedProfileRevision: Long, now: Long): IeltsStudySnapshot
    suspend fun requestBinding(requestId: String): IeltsStudyRequest?
    /** Save the exact choice before dispatch. It cannot be rewritten when preferences change. */
    suspend fun freeze(submit: IeltsGatewaySubmit, expectedProfileRevision: Long, now: Long): IeltsStudyRequest
    /** Terminal retries retain the old recipe, including its original learner choice. */
    suspend fun inherit(original: IeltsGatewaySubmit, replacement: IeltsGatewaySubmit, now: Long): IeltsStudyRequest?
}

object IeltsStudyPolicy {
    /** New network deliveries only. Existing saved practice retains its frozen content. */
    fun validateFocusedSequence(request: IeltsExerciseRequest, exercise: IeltsExercisePackage) {
        if (request.readingRequests.size < 2 || request.readingRequests.any { it !is IeltsInformationRequest }) return
        var previous = -1
        exercise.readingQuestions.forEach { raw ->
            val item = raw as IeltsInformationQuestion
            if (item.answer != IeltsInformationAnswer.NOT_GIVEN) {
                val anchor = item.sourceRefs.minOf { it.start }
                require(anchor >= previous) { "Focused information evidence moves backwards" }
                previous = anchor
            }
        }
    }
    fun requests(focus: IeltsReadingType?): List<IeltsReadingRequest> = if (focus == null)
        listOf(IeltsChoiceRequest("q1"), IeltsInformationRequest("q2"), IeltsViewRequest("q3"), IeltsCompletionRequest("q4", 1, false))
    else (1..4).map { number ->
        val id = "q$number"
        when (focus) {
            IeltsReadingType.MULTIPLE_CHOICE -> IeltsChoiceRequest(id)
            IeltsReadingType.IDENTIFYING_INFORMATION -> IeltsInformationRequest(id)
            IeltsReadingType.IDENTIFYING_WRITERS_VIEWS -> IeltsViewRequest(id)
            IeltsReadingType.SHORT_TEXT_COMPLETION -> IeltsCompletionRequest(id, 1, false)
        }
    }

    fun kind(question: IeltsReadingQuestion): IeltsReadingType = when (question) {
        is IeltsChoiceQuestion -> IeltsReadingType.MULTIPLE_CHOICE
        is IeltsInformationQuestion -> IeltsReadingType.IDENTIFYING_INFORMATION
        is IeltsViewQuestion -> IeltsReadingType.IDENTIFYING_WRITERS_VIEWS
        is IeltsCompletionQuestion -> IeltsReadingType.SHORT_TEXT_COMPLETION
    }

    fun validate(feedback: IeltsStudyFeedback) {
        IeltsExerciseValidator.validId(feedback.attemptId); IeltsExerciseValidator.validId(feedback.questionId)
        require(listOf(feedback.packageHash, feedback.submissionHash).all { Regex("[a-f0-9]{64}").matches(it) })
        require(feedback.revision in 1..IeltsContract.MAX_TIMESTAMP && feedback.updatedAt in 0..IeltsContract.MAX_TIMESTAMP)
    }

    fun validate(profile: IeltsStudyProfile) {
        require(profile.revision in 0..IeltsContract.MAX_TIMESTAMP && profile.updatedAt in 0..IeltsContract.MAX_TIMESTAMP)
        if (profile.revision == 0L) require(profile.focus == null && profile.updatedAt == 0L)
        profile.focus?.let {
            validate(it); require(it.intent == IeltsStudyIntent.PRACTICE_AGAIN && profile.revision > 0 && profile.updatedAt >= it.updatedAt)
        }
    }

    fun validate(request: IeltsStudyRequest) {
        IeltsExerciseValidator.validId(request.requestId); require(Regex("[a-f0-9]{64}").matches(request.submitHash))
        validate(request.profile)
        require(request.policy == "learner-selected-form-v1" && request.createdAt in request.profile.updatedAt..IeltsContract.MAX_TIMESTAMP)
    }
}
