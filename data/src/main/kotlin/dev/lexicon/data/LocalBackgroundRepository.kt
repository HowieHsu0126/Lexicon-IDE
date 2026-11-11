package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** Evidence is saved before any optional generation; browsing never starts or replays paid work. */
class LocalBackgroundRepository(
    private val db: LexiconDatabase,
    private val ai: BackgroundResearchAi,
    private val hasKey: suspend () -> Boolean,
    private val sourceAllowed: suspend (Article) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : BackgroundRepository {
    private val dao = db.readingDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val locks = synchronized(databaseLocks) { databaseLocks.getOrPut(db) { Locks() } }

    override suspend fun cached(query: BackgroundQuery): BackgroundLookup? = withContext(Dispatchers.IO) {
        val normalized = BackgroundResearch.normalize(query)
        val index = dao.entry(indexKey(normalized)) ?: return@withContext null
        runCatching { requireLookup(index.data).also { require(it.query == normalized) } }.getOrNull()
    }

    override suspend fun lookup(query: BackgroundQuery, refresh: Boolean): BackgroundLookup = withContext(Dispatchers.IO) {
        val normalized = BackgroundResearch.normalize(query)
        val indexKey = indexKey(normalized)
        if (!refresh) cached(normalized)?.let { return@withContext it }
        val lock = locks.lookups.getOrPut(indexKey) { LookupLock() }
        val observedGeneration = lock.generation
        lock.mutex.withLock {
            val priorIndex = dao.entry(indexKey)
            val prior = cached(normalized)
            if (lock.generation != observedGeneration) lock.last?.let { return@withLock it.getOrThrow() }
            if (prior != null && !refresh) return@withLock prior
            if (priorIndex != null && prior == null && !refresh)
                throw ApiFailure("background_cache_invalid", "背景资料记录不可用；可明确刷新，已有记录保留")
            try {
                val fetched = ai.lookup(normalized).also { result ->
                    validateLookup(result)
                    require(result.query == normalized) { "Background lookup changed the supplied query" }
                }
                if (prior != null && fetched.sources.isEmpty()) {
                    // A failed refresh must not replace the last useful receipt or move its index.
                    throw ApiFailure(if (fetched.status == "no_results") "background_no_results" else "background_unavailable",
                        "本次刷新未取得可用资料，已保留上次检索结果")
                }
                val row = PreferenceRow(lookupKey(fetched.id), json.encodeToString(fetched))
                // Insert the immutable receipt first. A process death may leave an unreferenced receipt,
                // but can never expose an index whose receipt was not completely committed.
                dao.insert(row)
                require(dao.entry(row.key)?.data == row.data) { "Background receipt identity collision" }
                dao.put(PreferenceRow(indexKey, fetched.id))
                lock.last = Result.success(fetched)
                fetched
            } catch (cancelled: CancellationException) {
                lock.last = Result.failure(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                val safe = lookupFailure(failure)
                lock.last = Result.failure(safe)
                throw safe
            } finally {
                // Waiting callers share this attempt's outcome, including failures; a later user
                // request observes the new generation and may explicitly refresh again.
                lock.generation++
            }
        }
    }

    override suspend fun cachedExplanation(lookupId: String, candidateId: String): StudyAssist? = withContext(Dispatchers.IO) {
        runCatching {
            val lookup = requireLookup(lookupId)
            val sources = BackgroundResearch.selectedSources(lookup, candidateId)
            val row = dao.entry(explanationKey(lookupId, candidateId)) ?: return@withContext null
            decodeExplanation(row.data, lookup, candidateId, sources)
        }.getOrNull()
    }

    override suspend fun explain(lookupId: String, candidateId: String, explicitRetry: Boolean): StudyAssist = withContext(Dispatchers.IO) {
        // A valid saved explanation stays readable when the key is removed, the source is disabled,
        // or the receipt expires. These conditions only restrict new generation.
        cachedExplanation(lookupId, candidateId)?.let { return@withContext it }
        val key = explanationKey(lookupId, candidateId)
        val attempt = locks.explanations.getOrPut(key) { ExplanationAttempt() }
        val observedGeneration = attempt.generation
        locks.generation.withLock {
            cachedExplanation(lookupId, candidateId)?.let { return@withLock it }
            if (attempt.generation != observedGeneration) attempt.last?.let { return@withLock it.getOrThrow() }
            val lookup = requireLookup(lookupId)
            val sources = BackgroundResearch.selectedSources(lookup, candidateId)
            BackgroundEvidence.validateSources(sources)
            val jobId = "background-ai:${sha256(key)}"
            val oldRow = dao.job(jobId)
            val oldJob = oldRow?.let { row -> runCatching { json.decodeFromString<AiJob>(row.data) }.getOrNull() }
            if (!explicitRetry && (dao.entry(key) != null || oldRow != null))
                throw ApiFailure("retry_required", "上次背景解释未完成，请明确重试；已检索资料仍可阅读")
            val beforeRequest: suspend () -> Unit = {
                currentCoroutineContext().ensureActive()
                requireFresh(lookup)
                requireGenerationPermission(lookup.query)
                if (!hasKey()) throw ApiFailure("missing_key", "请先配置 DeepSeek API Key；检索资料已保留")
                if (dao.entry("ai_paused")?.data == "true")
                    throw ApiFailure("paused", "AI 解释已暂停；检索资料仍可阅读")
                require(requireLookup(lookup.id) == lookup) { "Background receipt changed before generation" }
            }
            beforeRequest()
            val now = clock()
            val job = AiJob(jobId, "background_explanation", lookup.id, "running", oldJob?.createdAt ?: now, now)
            dao.putJob(job.row())
            try {
                // The service invokes this guard after its shared model gate and again before repair.
                val result = ai.explain(lookup.query, sources, beforeRequest)
                validateExplanation(result, sources)
                requireFresh(lookup)
                requireGenerationPermission(lookup.query)
                require(requireLookup(lookup.id) == lookup) { "Background receipt changed during generation" }
                val saved = SavedExplanation(lookup.id, candidateId, result)
                dao.finishJob(PreferenceRow(key, json.encodeToString(saved)), job.copy(status = "succeeded", updatedAt = clock()).row())
                attempt.last = Result.success(result)
                result
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { dao.putJob(job.copy(status = "interrupted", updatedAt = clock(), errorCode = "cancelled").row()) }
                attempt.last = Result.failure(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                val code = (failure as? ApiFailure)?.code ?: "background_explanation_failed"
                dao.failJob(job.copy(status = "failed", updatedAt = clock(), errorCode = code).row(), code in setOf("authentication", "quota"))
                val safe = if (failure is ApiFailure) failure else ApiFailure(code, "背景解释未完成，已检索资料保留，可稍后明确重试")
                attempt.last = Result.failure(safe)
                throw safe
            } finally {
                attempt.generation++
            }
        }
    }

    override suspend fun readLookup(id: String): BackgroundLookup? = withContext(Dispatchers.IO) {
        if (!id.matches(Regex("[a-f0-9]{64}"))) throw ApiFailure("background_missing", "背景资料标识无效，请重新检索")
        dao.entry(lookupKey(id))?.let { decodeLookup(it, id) }
    }

    override suspend fun recentLookups(): List<BackgroundLookup> = withContext(Dispatchers.IO) {
        dao.studyRows("background:index:1:").map { index ->
            requireLookup(index.data).also { lookup ->
                if (index.key != indexKey(lookup.query)) throw ApiFailure("background_cache_invalid", "部分背景资料索引不可用，原记录保留")
            }
        }.distinctBy { BackgroundResearch.queryKey(it.query) }
            .sortedWith(compareByDescending<BackgroundLookup> { it.createdAt }.thenBy { it.id }).take(100)
    }

    override suspend fun savedRecord(lookupId: String, candidateId: String): BackgroundRecord? = withContext(Dispatchers.IO) {
        dao.entry(BackgroundRecordStorage.key(BackgroundRecords.id(lookupId, candidateId)))?.let(::decodeRecord)
    }

    override suspend fun saveRecord(lookupId: String, candidateId: String, displayedExplanation: StudyAssist?,
        expectedRevision: Int?): BackgroundRecord = withContext(Dispatchers.IO) {
        val key = BackgroundRecordStorage.key(BackgroundRecords.id(lookupId, candidateId))
        val previousRow = dao.entry(key)
        val previous = previousRow?.let(::decodeRecord)
        // Reopening an already saved item must not overwrite its comments or add a later AI result.
        if (previous != null && expectedRevision == null) return@withContext previous
        if ((previous == null && expectedRevision != null) ||
            (previous != null && previous.revision != expectedRevision)) throw recordConflict()
        if (previous != null && displayedExplanation == null) return@withContext previous

        val lookupRow = dao.entry(lookupKey(lookupId)) ?: throw ApiFailure("background_missing", "找不到背景资料，尚未保存知识项")
        val lookup = decodeLookup(lookupRow, lookupId)
        val sources = BackgroundResearch.selectedSources(lookup, candidateId)
        val evidence = mutableListOf(lookupRow)
        if (displayedExplanation != null) {
            val explanationRow = dao.entry(explanationKey(lookupId, candidateId))
                ?: throw ApiFailure("background_explanation_missing", "找不到刚才显示的解释，请重新打开后保存")
            val cached = try { decodeExplanation(explanationRow.data, lookup, candidateId, sources) }
            catch (_: Exception) { throw ApiFailure("background_cache_invalid", "背景解释记录不可用，原记录保留") }
            if (cached != displayedExplanation) throw ApiFailure("background_explanation_changed", "显示的解释与保存记录不同，请重新打开后保存")
            evidence += explanationRow
        }
        val proposed = if (previous == null) BackgroundRecords.create(lookup, candidateId, clock(), displayedExplanation)
            else BackgroundRecords.withExplanation(previous, requireNotNull(expectedRevision), requireNotNull(displayedExplanation), clock())
        if (proposed == previous) return@withContext proposed
        val revisionData = revisionData(proposed.id)
        val next = proposed.copy(revision = nextRevision(previous, revisionData))
        if (!dao.changeBackgroundRecord(key, previousRow?.data, BackgroundRecordStorage.row(next), evidence, revisionData)) {
            // Two first saves converge on the same item without replacing either user's edits.
            if (previous == null && expectedRevision == null) dao.entry(key)?.let { return@withContext decodeRecord(it) }
            throw recordConflict()
        }
        next
    }

    override suspend fun updateRecord(id: String, expectedRevision: Int, comment: String, tags: List<String>): BackgroundRecord = withContext(Dispatchers.IO) {
        val row = recordRow(id)
        val previous = decodeRecord(row)
        if (previous.revision != expectedRevision) throw recordConflict()
        val proposed = BackgroundRecords.updateMetadata(previous, expectedRevision, comment, tags, clock())
        if (proposed == previous) return@withContext previous
        val revisionData = revisionData(id)
        val next = proposed.copy(revision = nextRevision(previous, revisionData))
        if (!dao.changeBackgroundRecord(row.key, row.data, BackgroundRecordStorage.row(next), expectedRevisionData = revisionData)) throw recordConflict()
        next
    }

    override suspend fun deleteRecord(id: String, expectedRevision: Int) = withContext(Dispatchers.IO) {
        val row = recordRow(id)
        if (decodeRecord(row).revision != expectedRevision ||
            !dao.changeBackgroundRecord(row.key, row.data, null, expectedRevisionData = revisionData(id))) throw recordConflict()
    }

    private suspend fun revisionData(id: String): String? = dao.entry(BackgroundRecordStorage.revisionKey(id))?.data.also {
        try { BackgroundRecordStorage.revision(it) }
        catch (_: Exception) { throw ApiFailure("background_record_invalid", "背景知识版本记录不可用，原记录保留") }
    }

    private fun nextRevision(previous: BackgroundRecord?, data: String?): Int {
        val last = maxOf(previous?.revision ?: 0, BackgroundRecordStorage.revision(data))
        if (last == Int.MAX_VALUE) throw ApiFailure("background_record_invalid", "这条背景已达到修订上限，原记录保留")
        return last + 1
    }

    private suspend fun recordRow(id: String): PreferenceRow {
        if (!id.matches(Regex("[a-f0-9]{64}:[a-f0-9]{64}"))) throw ApiFailure("background_record_missing", "知识项标识无效")
        return dao.entry(BackgroundRecordStorage.key(id)) ?: throw ApiFailure("background_record_missing", "知识项已删除，请返回知识库")
    }

    private fun decodeRecord(row: PreferenceRow): BackgroundRecord = try { BackgroundRecordStorage.decode(row) }
        catch (_: Exception) { throw ApiFailure("background_record_invalid", "背景知识项不可用，原记录保留") }

    private fun recordConflict() = ApiFailure("background_record_conflict", "知识项或资料已更新，请重新打开后再保存")

    private suspend fun requireLookup(id: String): BackgroundLookup = readLookup(id)
        ?: throw ApiFailure("background_missing", "找不到背景资料，请重新检索")

    private fun decodeLookup(row: PreferenceRow, id: String): BackgroundLookup = try {
            require(row.key == lookupKey(id))
            require(row.data.length <= 100_000)
            json.decodeFromString<BackgroundLookup>(row.data).also { require(it.id == id); validateLookup(it) }
        } catch (failure: Exception) {
            throw ApiFailure("background_cache_invalid", "背景资料记录不可用，原记录保留；请明确刷新")
        }

    private fun validateLookup(value: BackgroundLookup) {
        BackgroundResearch.validateLookup(value)
        if (value.sources.isNotEmpty()) BackgroundEvidence.validateSources(value.sources)
    }

    private fun requireFresh(lookup: BackgroundLookup) {
        if (BackgroundResearch.expired(lookup, clock()))
            throw ApiFailure("refresh_required", "资料已过期，请先刷新再生成解释；原资料仍可阅读")
    }

    private suspend fun requireGenerationPermission(query: BackgroundQuery) {
        if (!query.aiAllowed) throw ApiFailure("source_restricted", "此来源未开启 AI 解释，检索资料仍可阅读")
        // A missing body cache does not invalidate an existing source-bound evidence receipt.
        val articleId = query.articleId
        val sourceUrl = query.source.url
        val associated = when {
            articleId != null -> db.dao().article(articleId)
                ?: throw ApiFailure("source_restricted", "关联文章不可用，检索资料保留，未生成解释")
            !sourceUrl.isNullOrBlank() -> db.dao().articleByUrl(sourceUrl)
            else -> null
        }
        if (associated != null) {
            val article = runCatching { json.decodeFromString<Article>(associated.data) }.getOrNull()
                ?: throw ApiFailure("source_restricted", "关联文章来源不可用，未生成解释")
            val requested = query.source
            val matches = (requested.title == null || requested.title == article.title) &&
                (requested.source == null || requested.source == article.sourceName) &&
                (requested.url == null || requested.url == article.url) &&
                (requested.publishedAt == null || requested.publishedAt == article.publishedAt) &&
                (query.articleId == null || query.articleId == article.id)
            if (!matches || !sourceAllowed(article))
                throw ApiFailure("source_restricted", "文章来源或 AI 权限已变化；检索资料保留，未生成解释")
        }
    }

    private fun decodeExplanation(data: String, lookup: BackgroundLookup, candidateId: String, sources: List<BackgroundSource>): StudyAssist {
        require(data.length <= 100_000)
        return json.decodeFromString<SavedExplanation>(data).also {
            require(it.lookupId == lookup.id && it.candidateId == candidateId && it.version == 1)
            validateExplanation(it.result, sources)
        }.result
    }

    private fun validateExplanation(result: StudyAssist, sources: List<BackgroundSource>) {
        BackgroundRecords.validateExplanation(result, sources)
    }

    private fun lookupFailure(failure: Exception) = if (failure is ApiFailure) failure else
        ApiFailure("background_unavailable", "检索未完成，已有资料保留，请稍后手动刷新")
    private fun indexKey(query: BackgroundQuery) = "background:index:1:${BackgroundResearch.queryKey(query)}"
    private fun lookupKey(id: String) = "background:lookup:1:$id"
    private fun explanationKey(lookupId: String, candidateId: String) = "background:explanation:1:$lookupId:${sha256(candidateId)}"
    private fun AiJob.row() = JobRow(id, status, updatedAt, json.encodeToString(this))
    @Serializable private data class SavedExplanation(val lookupId: String, val candidateId: String, val result: StudyAssist, val version: Int = 1)
    private class LookupLock { val mutex = Mutex(); @Volatile var generation = 0L; var last: Result<BackgroundLookup>? = null }
    private class ExplanationAttempt { @Volatile var generation = 0L; var last: Result<StudyAssist>? = null }
    private class Locks {
        val lookups = ConcurrentHashMap<String, LookupLock>()
        val explanations = ConcurrentHashMap<String, ExplanationAttempt>()
        val generation = Mutex()
    }
    private companion object { val databaseLocks = WeakHashMap<LexiconDatabase, Locks>() }
}
