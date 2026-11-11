package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID

/** Unassisted assessments reuse immutable local plans and never initiate an AI request. */
class LocalReadingBaselineRepository(
    private val db: LexiconDatabase,
    private val plans: SpeedReadingRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ReadingBaselineRepository {
    private val dao = db.readingBaselineDao()
    private val codec = Json { ignoreUnknownKeys = true }
    override val library: Flow<List<ReadingBaselineAttempt>> = dao.observeAttempts().map { rows ->
        rows.mapNotNull { runCatching { codec.decodeFromString<ReadingBaselineAttempt>(it.data) }.getOrNull() }
            .sortedByDescending { it.startedAt }
    }

    override suspend fun begin(planKey: String, userConfirmedUnseen: Boolean): ReadingBaselineAttempt = withContext(Dispatchers.IO) {
        val plan = plans.cachedPlan(planKey) ?: throw ApiFailure("missing_plan", "自测材料无法读取，请重新准备")
        val now = clock().also { require(it >= 0) }
        dao.begin(UUID.randomUUID().toString(), plan, userConfirmedUnseen, now)
    }

    override suspend fun save(attempt: ReadingBaselineAttempt): ReadingBaselineAttempt = withContext(Dispatchers.IO) {
        validId(attempt.id)
        val plan = plans.cachedPlan(attempt.planKey) ?: throw ApiFailure("missing_plan", "自测原文缓存无法读取，原记录已保留")
        dao.save(attempt, plan, clock().also { require(it >= 0) })
    }

    override suspend fun get(id: String): ReadingBaselineAttempt? = withContext(Dispatchers.IO) {
        validId(id)
        dao.entry("baseline-library:attempt:$id")?.let { row ->
            runCatching { codec.decodeFromString<ReadingBaselineAttempt>(row.data).also { require(it.id == id) } }.getOrNull()
        }
    }

    override suspend fun material(id: String): ReadingBaselineMaterial? = withContext(Dispatchers.IO) {
        validId(id)
        val attempt = get(id) ?: return@withContext null
        val plan = plans.cachedPlan(attempt.planKey) ?: return@withContext null
        try { dao.material(id, plan) }
        catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
        catch (_: Exception) { null }
    }

    override suspend fun resume(id: String): ReadingBaselineAttempt = withContext(Dispatchers.IO) {
        validId(id)
        val attempt = get(id) ?: throw ApiFailure("missing_attempt", "找不到这次自测，原记录已保留")
        val plan = if (attempt.completed) null else (plans.cachedPlan(attempt.planKey)
            ?: throw ApiFailure("missing_plan", "自测原文缓存无法读取，原记录已保留"))
        dao.resume(id, plan)
    }

    private fun validId(id: String) { require(id.isNotBlank() && id.length <= 200 && id.none { it.isISOControl() }) }
}
