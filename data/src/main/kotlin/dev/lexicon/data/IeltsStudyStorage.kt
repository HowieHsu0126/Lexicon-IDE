package dev.lexicon.data

import androidx.room3.*
import dev.lexicon.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable internal data class StudyProfileRow(val version: Int = 1, val profile: IeltsStudyProfile)
@Serializable internal data class StudyFeedbackRow(val version: Int = 1, val feedback: IeltsStudyFeedback)
@Serializable internal data class StudyRequestRow(val version: Int = 1, val request: IeltsStudyRequest)

/** Dedicated namespace; source packages, submissions and other learning settings are read-only. */
internal object IeltsStudyStorage {
    const val PROFILE = "ielts.study.profile"
    private val codec = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
    fun feedbackKey(attemptId: String, questionId: String): String {
        IeltsExerciseValidator.validId(attemptId); IeltsExerciseValidator.validId(questionId)
        return "ielts.study.feedback." + sha256("${attemptId.length}:$attemptId$questionId")
    }
    fun requestKey(id: String): String { IeltsExerciseValidator.validId(id); return "ielts.study.request.$id" }
    fun eventKey(value: IeltsStudyFeedback): String = "ielts.study.event." + sha256("${value.attemptId.length}:${value.attemptId}${value.questionId}") + ".${value.revision}"
    fun event(row: PreferenceRow): IeltsStudyFeedback = decode<StudyFeedbackRow>(row.data)
        .also { require(it.version == 1) }.feedback.also { IeltsStudyPolicy.validate(it); require(row.key == eventKey(it)) }
    private inline fun <reified T> decode(text: String): T {
        require(text.length <= 16_384)
        val canonical = IeltsExerciseJson.canonicalJson(text)
        val value = codec.decodeFromString<T>(text)
        require(canonical == IeltsExerciseJson.canonicalJson(codec.encodeToString(value)))
        return value
    }
    fun profile(row: PreferenceRow?): IeltsStudyProfile = if (row == null) IeltsStudyProfile() else {
        require(row.key == PROFILE)
        decode<StudyProfileRow>(row.data).also { require(it.version == 1) }.profile.also(IeltsStudyPolicy::validate)
    }
    fun feedback(row: PreferenceRow): IeltsStudyFeedback = decode<StudyFeedbackRow>(row.data)
        .also { require(it.version == 1) }.feedback.also {
            IeltsStudyPolicy.validate(it); require(row.key == feedbackKey(it.attemptId, it.questionId))
        }
    fun request(row: PreferenceRow): IeltsStudyRequest = decode<StudyRequestRow>(row.data)
        .also { require(it.version == 1) }.request.also {
            IeltsStudyPolicy.validate(it); require(row.key == requestKey(it.requestId))
        }
    fun row(value: IeltsStudyProfile) = PreferenceRow(PROFILE, codec.encodeToString(StudyProfileRow(profile = value)))
    fun row(value: IeltsStudyFeedback) = PreferenceRow(feedbackKey(value.attemptId, value.questionId), codec.encodeToString(StudyFeedbackRow(feedback = value)))
    fun row(value: IeltsStudyRequest) = PreferenceRow(requestKey(value.requestId), codec.encodeToString(StudyRequestRow(request = value)))
}

@Dao abstract class IeltsStudyDao {
    @Query("SELECT * FROM preferences WHERE `key`=:key") protected abstract suspend fun row(key: String): PreferenceRow?
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insert(row: PreferenceRow): Long
    @Query("UPDATE preferences SET data=:next WHERE `key`=:key AND data=:previous")
    protected abstract suspend fun replace(key: String, previous: String, next: String): Int

    private suspend fun committed(id: String): IeltsSavedPractice {
        val attempt = IeltsPracticeStorage.attempt(requireNotNull(row(IeltsPracticeStorage.attemptKey(id))))
        val cached = IeltsPracticeStorage.cached(requireNotNull(row(IeltsPracticeStorage.packageKey(attempt.packageHash))))
        val saved = IeltsPracticeStorage.saved(cached, attempt)
        if (!saved.submitted) throw IeltsStudyFailure(IeltsStudyFailureCode.NOT_SUBMITTED)
        return saved
    }

    private fun validateBinding(value: IeltsStudyFeedback, saved: IeltsSavedPractice) {
        IeltsStudyPolicy.validate(value)
        val submission = requireNotNull(saved.submission)
        require(value.attemptId == saved.attemptId && value.packageHash == saved.packageHash &&
            value.submissionHash == IeltsExerciseJson.submissionHash(submission))
        require(value.kind == IeltsStudyPolicy.kind(requireNotNull(saved.exercise.readingQuestions.singleOrNull { it.questionId == value.questionId })))
        require(value.updatedAt >= submission.submittedAt)
    }

    private suspend fun profile(): IeltsStudyProfile = IeltsStudyStorage.profile(row(IeltsStudyStorage.PROFILE)).also { profile ->
        profile.focus?.let { focus ->
            val current = IeltsStudyStorage.feedback(requireNotNull(row(IeltsStudyStorage.feedbackKey(focus.attemptId, focus.questionId))))
            require(current == focus)
            validateEvent(focus)
        }
    }

    private suspend fun validateEvent(value: IeltsStudyFeedback) {
        require(IeltsStudyStorage.event(requireNotNull(row(IeltsStudyStorage.eventKey(value)))) == value)
        validateBinding(value, committed(value.attemptId))
    }

    private suspend fun feedback(saved: IeltsSavedPractice): List<IeltsStudyFeedback> = saved.exercise.readingQuestions.mapNotNull { question ->
        row(IeltsStudyStorage.feedbackKey(saved.attemptId, question.questionId))?.let(IeltsStudyStorage::feedback)
            ?.also { validateBinding(it, saved); validateEvent(it) }
    }

    @Transaction open suspend fun load(id: String?): IeltsStudySnapshot =
        IeltsStudySnapshot(profile(), id?.let { feedback(committed(it)) }.orEmpty())

    private fun next(revision: Long): Long { require(revision in 0 until IeltsContract.MAX_TIMESTAMP); return revision + 1 }
    private fun time(now: Long, previous: Long) = maxOf(now.also(IeltsPracticeStorage::time), previous)
    private fun checkRevision(expected: Long, actual: Long) {
        if (expected != actual) throw IeltsStudyFailure(IeltsStudyFailureCode.CONFLICT)
    }
    private suspend fun store(previous: PreferenceRow?, replacement: PreferenceRow) {
        val changed = if (previous == null) insert(replacement) != -1L else {
            require(previous.key == replacement.key); replace(previous.key, previous.data, replacement.data) == 1
        }
        if (!changed) throw IeltsStudyFailure(IeltsStudyFailureCode.CONFLICT)
        check(row(replacement.key) == replacement)
    }

    @Transaction open suspend fun reflect(id: String, questionId: String, intent: IeltsStudyIntent?,
        expectedFeedbackRevision: Long, expectedProfileRevision: Long, now: Long): IeltsStudySnapshot {
        val saved = committed(id)
        val kind = IeltsStudyPolicy.kind(requireNotNull(saved.exercise.readingQuestions.singleOrNull { it.questionId == questionId }))
        val previousProfile = row(IeltsStudyStorage.PROFILE)
        val current = profile(); checkRevision(expectedProfileRevision, current.revision)
        val key = IeltsStudyStorage.feedbackKey(id, questionId)
        val previous = row(key)
        val before = previous?.let(IeltsStudyStorage::feedback)?.also { validateBinding(it, saved) }
        checkRevision(expectedFeedbackRevision, before?.revision ?: 0)
        IeltsPracticeStorage.time(now)
        val submission = requireNotNull(saved.submission)
        val updated = time(now, maxOf(current.updatedAt, before?.updatedAt ?: 0, submission.submittedAt))
        val value = IeltsStudyFeedback(id, questionId, saved.packageHash, IeltsExerciseJson.submissionHash(submission),
            kind, intent, next(before?.revision ?: 0), updated)
        val originMatches = current.focus?.let { it.packageHash == saved.packageHash && it.questionId == questionId } == true
        val focus = when {
            intent == IeltsStudyIntent.PRACTICE_AGAIN -> value
            originMatches -> null
            else -> current.focus
        }
        val newProfile = current.copy(revision = next(current.revision), updatedAt = updated, focus = focus)
        IeltsStudyPolicy.validate(value); IeltsStudyPolicy.validate(newProfile)
        val encoded = IeltsStudyStorage.row(value)
        store(null, encoded.copy(key = IeltsStudyStorage.eventKey(value)))
        store(previous, encoded); store(previousProfile, IeltsStudyStorage.row(newProfile))
        return load(id)
    }

    @Transaction open suspend fun useBalanced(expected: Long, now: Long): IeltsStudySnapshot {
        val previous = row(IeltsStudyStorage.PROFILE); val current = profile(); checkRevision(expected, current.revision)
        IeltsPracticeStorage.time(now)
        if (current.focus == null) return IeltsStudySnapshot(current)
        val replacement = current.copy(revision = next(current.revision), updatedAt = time(now, current.updatedAt), focus = null)
        store(previous, IeltsStudyStorage.row(replacement)); return IeltsStudySnapshot(replacement)
    }

    @Transaction open suspend fun requestBinding(id: String): IeltsStudyRequest? =
        row(IeltsStudyStorage.requestKey(id))?.let(IeltsStudyStorage::request)?.also { request ->
            request.profile.focus?.let { validateEvent(it) }
        }

    @Transaction open suspend fun freeze(submit: IeltsGatewaySubmit, expected: Long, now: Long): IeltsStudyRequest {
        val hash = IeltsExerciseJson.canonicalSha256(IeltsGatewayProtocol.encodeSubmit(submit))
        requestBinding(submit.requestId)?.let {
            require(it.submitHash == hash && submit.readingRequests == IeltsStudyPolicy.requests(it.profile.focus?.kind))
            return it // Reconciliation retains the original intent even after the user changes defaults.
        }
        val current = profile(); checkRevision(expected, current.revision)
        require(submit.readingRequests == IeltsStudyPolicy.requests(current.focus?.kind))
        val value = IeltsStudyRequest(submit.requestId, hash, current, time(now, current.updatedAt))
        IeltsStudyPolicy.validate(value)
        store(null, IeltsStudyStorage.row(value)); return requireNotNull(requestBinding(submit.requestId))
    }

    @Transaction open suspend fun inherit(original: IeltsGatewaySubmit, replacement: IeltsGatewaySubmit, now: Long): IeltsStudyRequest? {
        require(original.requestId != replacement.requestId && replacement == original.copy(requestId = replacement.requestId))
        val source = requestBinding(original.requestId) ?: return null
        require(source.submitHash == IeltsExerciseJson.canonicalSha256(IeltsGatewayProtocol.encodeSubmit(original)))
        val hash = IeltsExerciseJson.canonicalSha256(IeltsGatewayProtocol.encodeSubmit(replacement))
        requestBinding(replacement.requestId)?.let { require(it.profile == source.profile && it.submitHash == hash); return it }
        val value = source.copy(requestId = replacement.requestId, submitHash = hash, createdAt = time(now, source.createdAt))
        store(null, IeltsStudyStorage.row(value)); return requireNotNull(requestBinding(replacement.requestId))
    }
}

class LocalIeltsStudyRepository(private val db: LexiconDatabase) : IeltsStudyRepository {
    private val dao get() = db.ieltsStudyDao()
    private suspend fun <T> access(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: IeltsStudyFailure) { throw failure }
        catch (_: IllegalArgumentException) { throw IeltsStudyFailure(IeltsStudyFailureCode.INVALID) }
        catch (_: IllegalStateException) { throw IeltsStudyFailure(IeltsStudyFailureCode.INVALID) }
        catch (_: Exception) { throw IeltsStudyFailure(IeltsStudyFailureCode.UNAVAILABLE) }
    }
    override suspend fun load(attemptId: String?) = access { dao.load(attemptId) }
    override suspend fun reflect(attemptId: String, questionId: String, intent: IeltsStudyIntent?, expectedFeedbackRevision: Long,
        expectedProfileRevision: Long, now: Long) = access { dao.reflect(attemptId, questionId, intent, expectedFeedbackRevision, expectedProfileRevision, now) }
    override suspend fun useBalanced(expectedProfileRevision: Long, now: Long) = access { dao.useBalanced(expectedProfileRevision, now) }
    override suspend fun requestBinding(requestId: String) = access { dao.requestBinding(requestId) }
    override suspend fun freeze(submit: IeltsGatewaySubmit, expectedProfileRevision: Long, now: Long) = access { dao.freeze(submit, expectedProfileRevision, now) }
    override suspend fun inherit(original: IeltsGatewaySubmit, replacement: IeltsGatewaySubmit, now: Long) = access { dao.inherit(original, replacement, now) }
}
