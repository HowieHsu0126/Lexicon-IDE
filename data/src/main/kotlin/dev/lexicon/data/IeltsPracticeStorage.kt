@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.lexicon.data

import androidx.room3.*
import dev.lexicon.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class IeltsCachedPractice(
    val version: Int = 1,
    val request: IeltsExerciseRequest,
    val exercise: IeltsExercisePackage,
    val requestHash: String,
    val packageHash: String,
)

@Serializable
internal data class IeltsStoredAttempt(
    val version: Int = 1,
    val attemptId: String,
    val packageHash: String,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val draftResponses: List<IeltsResponse>,
    val writingText: String,
    val submission: IeltsSubmission? = null,
    val submissionHash: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val writingTask1Text: String? = null,
)

/** Frozen source material and revisioned answers occupy separate v1 preference rows. */
internal object IeltsPracticeStorage {
    const val PACKAGE_PREFIX = "ielts.package."
    const val ATTEMPT_PREFIX = "ielts.attempt."
    private val digest = Regex("[a-f0-9]{64}")
    private val codec = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = false; classDiscriminator = "type" }

    fun packageKey(hash: String): String {
        require(digest.matches(hash)) { "练习题包标识无效" }
        return PACKAGE_PREFIX + hash
    }

    fun attemptKey(id: String): String {
        IeltsExerciseValidator.validId(id)
        return ATTEMPT_PREFIX + id
    }

    fun time(now: Long) { require(now in 0..IeltsContract.MAX_TIMESTAMP) { "练习记录时间无效" } }

    fun material(request: IeltsExerciseRequest, exercise: IeltsExercisePackage): IeltsCachedPractice {
        val receipt = IeltsExerciseValidator.validatePackage(request, exercise)
        return IeltsCachedPractice(request = request, exercise = exercise,
            requestHash = receipt.requestSha256, packageHash = receipt.packageSha256)
    }

    fun packageRow(value: IeltsCachedPractice): PreferenceRow =
        PreferenceRow(packageKey(value.packageHash), codec.encodeToString(value)).also { cached(it) }

    fun cached(row: PreferenceRow): IeltsCachedPractice {
        val value = strict<IeltsCachedPractice>(row.data)
        require(value.version == 1 && row.key == packageKey(value.packageHash))
        val receipt = IeltsExerciseValidator.validatePackage(value.request, value.exercise)
        require(receipt.requestSha256 == value.requestHash && receipt.packageSha256 == value.packageHash)
        return value
    }

    fun attempt(row: PreferenceRow): IeltsStoredAttempt {
        val value = strict<IeltsStoredAttempt>(row.data)
        require(value.version == 1 && row.key == attemptKey(value.attemptId))
        packageKey(value.packageHash)
        require(value.revision in 1..IeltsContract.MAX_TIMESTAMP)
        time(value.createdAt); time(value.updatedAt)
        require(value.updatedAt >= value.createdAt)
        require((value.submission == null) == (value.submissionHash == null))
        if (value.submission != null) {
            require(value.submission.attemptId == value.attemptId && value.submission.packageSha256 == value.packageHash)
            require(value.submissionHash == IeltsExerciseJson.submissionHash(value.submission))
            require(value.submission.submittedAt == value.updatedAt)
            require(value.submission.readingAnswers == value.draftResponses && value.submission.writingTask2Text == value.writingText && value.submission.writingTask1Text == value.writingTask1Text)
        }
        return value
    }

    fun attemptRow(value: IeltsStoredAttempt): PreferenceRow =
        PreferenceRow(attemptKey(value.attemptId), codec.encodeToString(value)).also { attempt(it) }

    fun draft(cached: IeltsCachedPractice, id: String, responses: List<IeltsResponse>, writing: String, writing1: String? = null): IeltsPracticeSession {
        require(responses.size <= cached.exercise.readingQuestions.size && responses.map { it.questionId }.distinct().size == responses.size)
        var result = IeltsPracticeSession.begin(cached.request, cached.exercise, id)
        responses.forEach { result = result.answer(it.questionId, it.answer) }
        result = result.write(writing)
        if (cached.exercise.writingTask1.status == "available") result = result.writeTask1(requireNotNull(writing1))
        else require(writing1 == null)
        return result
    }

    fun saved(cached: IeltsCachedPractice, value: IeltsStoredAttempt): IeltsSavedPractice {
        require(value.packageHash == cached.packageHash)
        val state = draft(cached, value.attemptId, value.draftResponses, value.writingText, value.writingTask1Text)
        require(state.draftResponses() == value.draftResponses) { "练习草稿顺序与题包不一致" }
        value.submission?.let { state.prepareSubmission(it.submittedAt).confirmSaved(it) }
        return IeltsSavedPractice(value.attemptId, value.packageHash, cached.request, cached.exercise,
            value.draftResponses, value.writingText, value.submission, value.revision, value.createdAt, value.updatedAt, value.writingTask1Text)
    }

    fun history(rows: List<PreferenceRow>): List<IeltsPracticeHistoryItem> {
        val materials = rows.filter { it.key.startsWith(PACKAGE_PREFIX) }.associate { row ->
            row.key to runCatching { cached(row) }.getOrNull()
        }
        return rows.filter { it.key.startsWith(ATTEMPT_PREFIX) }.map { row ->
            runCatching {
                val attempt = attempt(row)
                val cached = requireNotNull(materials[packageKey(attempt.packageHash)])
                saved(cached, attempt)
                IeltsPracticeHistoryItem(attempt.attemptId, attempt.attemptId, attempt.packageHash, cached.request.articleId,
                    if (attempt.submission == null) IeltsPracticeHistoryStatus.DRAFT else IeltsPracticeHistoryStatus.SUBMITTED,
                    cached.exercise.readingQuestions.size, attempt.draftResponses.size, attempt.createdAt, attempt.updatedAt,
                    attempt.submission?.submittedAt)
            }.getOrElse {
                IeltsPracticeHistoryItem("unavailable:${sha256(row.key)}", null, null, null,
                    IeltsPracticeHistoryStatus.UNAVAILABLE, null, null, null, null, null)
            }
        }.sortedWith(compareByDescending<IeltsPracticeHistoryItem> { it.updatedAt ?: -1L }.thenBy { it.id })
    }

    private inline fun <reified T> strict(text: String): T {
        // The existing strict parser rejects duplicate keys, primitive coercion and unknown versions.
        val canonical = IeltsExerciseJson.canonicalJson(text)
        val result = codec.decodeFromString<T>(text)
        require(canonical == IeltsExerciseJson.canonicalJson(codec.encodeToString(result)))
        return result
    }
}

/** Every mutation is one SQLite transaction; the byte CAS also protects independent instances. */
@Dao
abstract class IeltsPracticeDao {
    @Query("SELECT * FROM preferences WHERE `key` LIKE 'ielts.generation.%' ORDER BY `key`")
    abstract fun observeGenerationRows(): Flow<List<PreferenceRow>>

    @Query("SELECT * FROM preferences WHERE `key`=:key AND `key` LIKE 'ielts.generation.%'")
    abstract suspend fun generationRow(key: String): PreferenceRow?

    @Transaction
    open suspend fun createGeneration(incoming: PreferenceRow): PreferenceRow {
        val value = IeltsGenerationStorage.decode(incoming)
        require(value.revision == 1L && !value.dispatchClaimed && value.stage == IeltsGenerationStage.PREPARED)
        row(incoming.key)?.let { original ->
            val previous = IeltsGenerationStorage.decode(original)
            require(previous.submitHash == value.submitHash && previous.submit == value.submit) { "IELTS request identity conflict" }
            return original
        }
        check(insert(incoming) != -1L)
        return requireNotNull(row(incoming.key)).also { check(it == incoming) }
    }

    @Transaction
    open suspend fun claimGeneration(requestId: String, now: Long): Boolean {
        IeltsPracticeStorage.time(now)
        val previous = row(IeltsGenerationStorage.key(requestId)) ?: return false
        val value = IeltsGenerationStorage.decode(previous)
        if (value.dispatchClaimed) return false
        require(value.revision < IeltsContract.MAX_TIMESTAMP)
        val next = value.copy(revision = value.revision + 1, updatedAt = maxOf(now, value.updatedAt),
            dispatchClaimed = true, stage = IeltsGenerationStage.RECONCILING, issue = null)
        return changeGeneration(previous, IeltsGenerationStorage.row(next))
    }

    @Transaction
    open suspend fun changeGeneration(previous: PreferenceRow, replacement: PreferenceRow): Boolean {
        val before = IeltsGenerationStorage.decode(previous)
        val after = IeltsGenerationStorage.decode(replacement)
        require(previous.key == replacement.key)
        IeltsGenerationStorage.requireTransition(before, after)
        return compareAndSet(previous.key, previous.data, replacement.data) == 1
    }

    @Query("SELECT * FROM preferences WHERE `key` LIKE 'ielts.package.%' OR `key` LIKE 'ielts.attempt.%' ORDER BY `key`")
    abstract fun observeRows(): Flow<List<PreferenceRow>>

    @Query("SELECT * FROM preferences WHERE `key`=:key")
    protected abstract suspend fun row(key: String): PreferenceRow?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insert(row: PreferenceRow): Long

    @Query("UPDATE preferences SET data=:replacement WHERE `key`=:key AND data=:expected")
    protected abstract suspend fun compareAndSet(key: String, expected: String, replacement: String): Int

    @Transaction
    open suspend fun cache(request: IeltsExerciseRequest, exercise: IeltsExercisePackage): String {
        val incoming = IeltsPracticeStorage.packageRow(IeltsPracticeStorage.material(request, exercise))
        val existing = row(incoming.key)
        if (existing != null) {
            val saved = readPackage(existing)
            if (saved != readPackage(incoming)) throw ApiFailure("ielts_package_conflict", "题包已存在且内容不同，原题包保留")
            return saved.packageHash
        }
        check(insert(incoming) != -1L) { "IELTS package insert did not commit" }
        val saved = readPackage(requireNotNull(row(incoming.key)))
        check(saved == readPackage(incoming))
        return saved.packageHash
    }

    @Transaction
    open suspend fun begin(packageHash: String, attemptId: String, now: Long): IeltsSavedPractice {
        IeltsPracticeStorage.time(now)
        val key = IeltsPracticeStorage.attemptKey(attemptId)
        val cached = requirePackage(packageHash)
        row(key)?.let { existing ->
            val previous = readAttempt(existing)
            if (previous.packageHash != packageHash) throw ApiFailure("ielts_attempt_conflict", "作答记录已关联其他题包，原记录保留")
            return readSaved(cached, previous)
        }
        val next = IeltsStoredAttempt(attemptId = attemptId, packageHash = packageHash, revision = 1,
            createdAt = now, updatedAt = now, draftResponses = emptyList(), writingText = "", writingTask1Text = if (cached.exercise.writingTask1.status == "available") "" else null)
        check(insert(IeltsPracticeStorage.attemptRow(next)) != -1L)
        return readSaved(cached, readAttempt(requireNotNull(row(key))))
    }

    @Transaction
    open suspend fun get(attemptId: String): IeltsSavedPractice? {
        val existing = row(IeltsPracticeStorage.attemptKey(attemptId)) ?: return null
        val attempt = readAttempt(existing)
        return readSaved(requirePackage(attempt.packageHash), attempt)
    }

    @Transaction
    open suspend fun saveDraft(id: String, expectedRevision: Long, responses: List<IeltsResponse>,
        writingText: String, now: Long): IeltsSavedPractice {
        val current = readAttempt(requireAttemptRow(id))
        return saveWritingDrafts(id, expectedRevision, responses, writingText, current.writingTask1Text, now)
    }

    @Transaction
    open suspend fun saveWritingDrafts(id: String, expectedRevision: Long, responses: List<IeltsResponse>,
        writingText: String, writingTask1Text: String?, now: Long): IeltsSavedPractice {
        IeltsPracticeStorage.time(now)
        val previous = requireAttemptRow(id)
        val attempt = readAttempt(previous)
        val cached = requirePackage(attempt.packageHash)
        readSaved(cached, attempt)
        if (attempt.submission != null) throw ApiFailure("ielts_submitted", "已提交的作答只读，请新建一次练习")
        checkRevision(attempt, expectedRevision)
        val state = IeltsPracticeStorage.draft(cached, id, responses, writingText, writingTask1Text)
        val normalized = state.draftResponses()
        if (attempt.draftResponses == normalized && attempt.writingText == writingText && attempt.writingTask1Text == writingTask1Text) return readSaved(cached, attempt)
        val next = attempt.copy(revision = nextRevision(attempt), updatedAt = maxOf(now, attempt.updatedAt),
            draftResponses = normalized, writingText = writingText, writingTask1Text = writingTask1Text)
        return replaceAndRead(previous, next, cached)
    }

    @Transaction
    open suspend fun submit(id: String, expectedRevision: Long, now: Long): IeltsSavedPractice {
        val previous = requireAttemptRow(id)
        val attempt = readAttempt(previous)
        val cached = requirePackage(attempt.packageHash)
        val committed = readSaved(cached, attempt)
        // Process-restoration and repeated buttons return the exact original timestamp and payload.
        if (attempt.submission != null) return committed
        IeltsPracticeStorage.time(now)
        checkRevision(attempt, expectedRevision)
        val pending = IeltsPracticeStorage.draft(cached, id, attempt.draftResponses, attempt.writingText, attempt.writingTask1Text)
            .prepareSubmission(maxOf(now, attempt.updatedAt))
        val submission = requireNotNull(pending.pendingSubmission())
        val next = attempt.copy(revision = nextRevision(attempt), updatedAt = submission.submittedAt,
            submission = submission, submissionHash = IeltsExerciseJson.submissionHash(submission))
        val saved = replaceAndRead(previous, next, cached)
        pending.confirmSaved(requireNotNull(saved.submission))
        return saved
    }

    private suspend fun replaceAndRead(previous: PreferenceRow, next: IeltsStoredAttempt, cached: IeltsCachedPractice): IeltsSavedPractice {
        val replacement = IeltsPracticeStorage.attemptRow(next)
        if (replacement.key != previous.key || compareAndSet(previous.key, previous.data, replacement.data) != 1)
            throw ApiFailure("ielts_revision_conflict", "作答已在另一处更新，请重新打开后继续")
        val reread = requireNotNull(row(previous.key))
        check(reread == replacement) { "IELTS saved content differs from the transaction payload" }
        return readSaved(cached, readAttempt(reread))
    }

    private fun checkRevision(value: IeltsStoredAttempt, expected: Long) {
        if (value.revision != expected) throw ApiFailure("ielts_revision_conflict", "作答已在另一处更新，请重新打开后继续")
    }

    private fun nextRevision(value: IeltsStoredAttempt): Long {
        if (value.revision == IeltsContract.MAX_TIMESTAMP) throw ApiFailure("ielts_revision_limit", "作答版本已达到上限，原记录保留")
        return value.revision + 1
    }

    private suspend fun requireAttemptRow(id: String): PreferenceRow = row(IeltsPracticeStorage.attemptKey(id))
        ?: throw ApiFailure("ielts_attempt_missing", "未找到这次练习")

    private suspend fun requirePackage(hash: String): IeltsCachedPractice =
        row(IeltsPracticeStorage.packageKey(hash))?.let(::readPackage)
            ?: throw ApiFailure("ielts_package_missing", "练习题包暂不可用，已有作答保留")

    private fun readPackage(row: PreferenceRow): IeltsCachedPractice = try { IeltsPracticeStorage.cached(row) }
        catch (_: IllegalArgumentException) { throw ApiFailure("ielts_storage_invalid", "练习题包无法验证，原记录保留") }

    private fun readAttempt(row: PreferenceRow): IeltsStoredAttempt = try { IeltsPracticeStorage.attempt(row) }
        catch (_: IllegalArgumentException) { throw ApiFailure("ielts_storage_invalid", "练习作答无法验证，原记录保留") }

    private fun readSaved(cached: IeltsCachedPractice, value: IeltsStoredAttempt): IeltsSavedPractice =
        try { IeltsPracticeStorage.saved(cached, value) }
        catch (_: IllegalArgumentException) { throw ApiFailure("ielts_storage_invalid", "练习作答与题包不一致，原记录保留") }
        catch (_: IllegalStateException) { throw ApiFailure("ielts_storage_invalid", "练习作答与题包不一致，原记录保留") }
}
