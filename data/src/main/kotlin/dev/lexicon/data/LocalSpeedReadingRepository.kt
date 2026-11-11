package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Only the chosen training paragraphs are retained; no full article copy is added to this cache. */
@Serializable internal data class SpeedSourcePart(val paragraph: Int, val start: Int, val text: String)
@Serializable internal data class SpeedPlanRecord(val plan: SpeedPlan, val source: List<SpeedSourcePart>, val sourceDigest: String)

class LocalSpeedReadingRepository(
    private val db: LexiconDatabase,
    private val ai: SpeedPlanGenerator,
    private val hasKey: suspend () -> Boolean,
    private val sourceAllowed: suspend (Article) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : SpeedReadingRepository {
    private val dao = db.readingDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()
    private var initialized = false
    override val library: Flow<SpeedLibrary> = dao.observeSpeedLibrary().map { rows ->
        SpeedLibrary(rows.filter { it.key.startsWith("speed-library:run:") }.mapNotNull { row ->
            runCatching { json.decodeFromString<SpeedRun>(row.data) }.getOrNull()
        }.sortedByDescending { it.finishedAt }, rows.filter { it.key.startsWith("speed-library:correction:") }.mapNotNull { row ->
            runCatching { json.decodeFromString<SpeedCorrection>(row.data) }.getOrNull()
        }.sortedByDescending { it.createdAt }, rows.filter { it.key.startsWith("speed-library:natural:") }.mapNotNull { row ->
            runCatching { json.decodeFromString<NaturalReadingAttempt>(row.data) }.getOrNull()
        }.sortedByDescending { it.finishedAt })
    }

    override suspend fun prepare(article: Article, body: ArticleBody, granularity: SpeedGranularity,
        startParagraph: Int, paragraphCount: Int, aiAllowed: Boolean, useAi: Boolean): SpeedPlan =
        prepareInternal(article, body, granularity, startParagraph, paragraphCount, aiAllowed, useAi, false)

    override suspend fun retryPrepare(article: Article, body: ArticleBody, granularity: SpeedGranularity,
        startParagraph: Int, paragraphCount: Int, aiAllowed: Boolean, useAi: Boolean): SpeedPlan =
        prepareInternal(article, body, granularity, startParagraph, paragraphCount, aiAllowed, useAi, true)

    private suspend fun initializeLocked() {
        if (initialized) return
        dao.speedRunningJobs().forEach { row ->
            val previous = runCatching { json.decodeFromString<AiJob>(row.data) }.getOrNull()
            if (previous != null) dao.putJob(previous.copy(status = "interrupted", updatedAt = clock(), errorCode = "process_interrupted").row())
        }
        initialized = true
    }

    private suspend fun prepareInternal(article: Article, body: ArticleBody, granularity: SpeedGranularity,
        start: Int, count: Int, aiAllowed: Boolean, useAi: Boolean, explicitRetry: Boolean): SpeedPlan = withContext(Dispatchers.IO) {
        lock.withLock {
            initializeLocked()
            if (article.id != body.articleId || sha256(body.text) != body.contentHash) throw ApiFailure("invalid_input", "正文快照已变化，请重新打开文章")
            val local = try { SpeedChunker.plan(article.id, body.contentHash, body.text, granularity, start, count, clock()) }
            catch (_: Exception) { throw ApiFailure("invalid_input", "请选择 1–3 段、最多 9,000 字符的原文") }
            dao.entry(overrideKey(local))?.data?.let { personalKey ->
                val personal = cachedRecordUnlocked(personalKey)
                    ?: throw ApiFailure("invalid_override", "个人切分缓存无法读取，原记录已保留")
                requireSameMaterial(local, personal.plan)
                require(personal.plan.origin == "manual")
                SpeedChunker.validate(personal.plan, body.text)
                return@withLock personal.plan
            }
            if (useAi) {
                if (!aiAllowed || !sourceAllowed(article)) throw ApiFailure("source_restricted", "此来源未开启 AI，可使用离线意群训练")
                if (!hasKey()) throw ApiFailure("missing_key", "配置 DeepSeek Key 后可生成理解题，也可直接开始离线分块")
                if (dao.entry("ai_paused")?.data == "true") throw ApiFailure("paused", "AI 任务已暂停，可检查 Key 或使用离线分块")
            }
            val requestKey = "speed-request:${SpeedReadingProtocol.VERSION}:${sha256(listOf(article.id, body.contentHash, granularity.name, start, count, useAi).joinToString("|"))}"
            dao.entry(requestKey)?.data?.let { storedKey -> cachedPlanUnlocked(storedKey)?.let { return@withLock it } }
            if (!useAi) return@withLock storePlan(local, body, requestKey)
            val jobId = "speed-ai:${sha256(requestKey)}"
            val previous = dao.job(jobId)?.let { runCatching { json.decodeFromString<AiJob>(it.data) }.getOrNull() }
            if (!explicitRetry && previous != null && previous.errorCode !in setOf("missing_key", "paused")) {
                throw ApiFailure("retry_required", "上次训练材料未完成，请明确重试；不会自动重复可能已收费的请求")
            }
            val now = clock()
            val job = AiJob(jobId, "speed_plan", article.id, "running", previous?.createdAt ?: now, now)
            dao.putJob(job.row())
            try {
                val refined = ai.refine(article, body, local)
                require(refined.articleId == local.articleId && refined.bodyHash == local.bodyHash && refined.granularity == granularity &&
                    refined.paragraphIndices == local.paragraphIndices && refined.origin == "ai" && refined.version == local.version)
                SpeedChunker.validate(refined, body.text)
                require(refined.questions.size == local.paragraphIndices.size && refined.questions.map { it.paragraph }.sorted() == local.paragraphIndices)
                storePlan(refined, body, requestKey, job.copy(status = "succeeded", updatedAt = clock()))
            } catch (e: CancellationException) {
                withContext(NonCancellable) { dao.putJob(job.copy(status = "interrupted", updatedAt = clock(), errorCode = "cancelled").row()) }
                throw e
            } catch (e: Exception) {
                val code = (e as? ApiFailure)?.code ?: "invalid_schema"
                dao.failJob(job.copy(status = "failed", updatedAt = clock(), errorCode = code).row(), code in setOf("authentication", "quota"))
                throw if (e is ApiFailure) e else ApiFailure(code, "训练材料未通过校验，可明确重试或使用离线分块")
            }
        }
    }

    private suspend fun storePlan(proposal: SpeedPlan, body: ArticleBody, requestKey: String, job: AiJob? = null): SpeedPlan {
        val paragraphs = ParagraphFocus.paragraphs(body.text)
        val parts = proposal.paragraphIndices.map { i -> paragraphs[i].let { SpeedSourcePart(i, it.start, body.text.substring(it.start, it.endExclusive)) } }
        val record = record(proposal, parts)
        dao.storeSpeedPlan(record.row(), PreferenceRow(requestKey, record.plan.planKey), job?.row())
        return requireNotNull(cachedRecordUnlocked(record.plan.planKey)).plan
    }

    override suspend fun cachedPlan(planKey: String): SpeedPlan? = withContext(Dispatchers.IO) {
        lock.withLock { cachedPlanUnlocked(planKey) }
    }

    override suspend fun comprehensionReview(run: SpeedRun): SpeedComprehensionReview = withContext(Dispatchers.IO) {
        lock.withLock {
            val unavailable = SpeedComprehensionReview(SpeedComprehensionReviewStatus.UNAVAILABLE)
            val key = "speed-library:run:${run.id}"
            val original = dao.entry(key) ?: return@withLock unavailable
            val saved = runCatching { json.decodeFromString<SpeedRun>(original.data) }.getOrNull()
            if (saved != run) return@withLock unavailable
            val record = cachedRecordUnlocked(run.planKey) ?: return@withLock unavailable
            val evidence = record.plan.questions.associate { question ->
                val part = record.source.single { it.paragraph == question.paragraph }
                question.id to part.text.substring(question.evidenceStart - part.start, question.evidenceEnd - part.start)
            }
            val review = SpeedComprehensionReviews.inspect(run, record.plan, evidence)
            // Another repository instance may have saved a newer answer snapshot during this read.
            if (dao.entry(key)?.data != original.data) unavailable else review
        }
    }
    private suspend fun cachedPlanUnlocked(planKey: String): SpeedPlan? {
        return cachedRecordUnlocked(planKey)?.plan
    }
    private suspend fun cachedRecordUnlocked(planKey: String): SpeedPlanRecord? {
        if (!KEY.matches(planKey)) return null
        return dao.entry("speed-plan:$planKey")?.let { row -> runCatching {
            val record = json.decodeFromString<SpeedPlanRecord>(row.data)
            SpeedPlanStorage.validate(record)
            require(record.plan.planKey == planKey)
            record
        }.getOrNull() }
    }

    override suspend fun sentenceForEditing(planKey: String, chunkIndex: Int): SpeedSegmentation? = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = cachedRecordUnlocked(planKey) ?: return@withLock null
            val base = baseRecord(current)
            val snapshot = SpeedSourceSnapshot(current.source)
            SpeedSegmentationEditor.sentence(snapshot.compact(current.plan), snapshot.text, chunkIndex, snapshot.compact(base.plan))
                ?.let(snapshot::original)
        }
    }

    override suspend fun markSentencePreviewed(planKey: String, paragraph: Int) = withContext(Dispatchers.IO) {
        lock.withLock {
            val record = cachedRecordUnlocked(planKey) ?: throw ApiFailure("missing_plan", "找不到可预览的训练材料")
            require(paragraph in record.plan.paragraphIndices) { "预览段落不属于此训练方案" }
            val now = clock().also { require(it >= 0) }
            dao.recordSpeedPreview(planKey, json.encodeToString(record), paragraph, now)
        }
    }

    override suspend fun editSentence(planKey: String, paragraph: Int, sentence: Int, breakOffsets: List<Int>): SpeedPlan = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = cachedRecordUnlocked(planKey) ?: throw ApiFailure("missing_plan", "找不到可编辑的训练材料")
            val key = overrideKey(current.plan)
            val previous = dao.entry(key)?.data
            requireCurrentOverride(current.plan, previous)
            val base = baseRecord(current)
            val snapshot = SpeedSourceSnapshot(current.source)
            val compact = snapshot.compact(current.plan)
            val compactBase = snapshot.compact(base.plan)
            require(breakOffsets.size <= SpeedChunker.MAX_UNIT_CHARACTERS)
            val edited = SpeedSegmentationEditor.edit(compact, snapshot.text, snapshot.paragraph(paragraph), sentence,
                breakOffsets.map { snapshot.compactOffset(it, paragraph) }, compactBase)
            val unchanged = edited == compact
            val next = if (unchanged) current else record(snapshot.original(edited).copy(basePlanKey = base.plan.planKey, createdAt = clock()), current.source)
            val sentenceChunks = current.plan.chunks.filter { it.paragraph == paragraph && it.sentence == sentence }
            require(sentenceChunks.isNotEmpty())
            val correction = SpeedCorrection(java.util.UUID.randomUUID().toString(), current.plan.articleId, current.plan.bodyHash,
                current.plan.granularity, sentenceChunks.first().start, sentenceChunks.last().end, "boundary_edit", "用户采用个人意群切分", clock(), next.plan.planKey)
            val correctionRow = PreferenceRow("speed-library:correction:${correction.id}", json.encodeToString(correction))
            val exposure = PreferenceRow("speed-exposure:${current.plan.bodyHash}:$paragraph", clock().toString())
            val saved = if (unchanged) dao.recordSpeedSegmentationExposure(key, previous, correctionRow, exposure)
                else dao.changeSpeedOverride(key, previous, next.row(), next.plan.planKey, correctionRow, exposure)
            if (!saved)
                throw ApiFailure("stale_segmentation", "切分已在另一处改变，请重新打开后编辑")
            requireNotNull(cachedRecordUnlocked(next.plan.planKey)).plan
        }
    }

    override suspend fun restoreSegmentation(planKey: String): SpeedPlan = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = cachedRecordUnlocked(planKey) ?: throw ApiFailure("missing_plan", "找不到原训练材料")
            val key = overrideKey(current.plan)
            val previous = dao.entry(key)?.data
            if (current.plan.basePlanKey == null && previous == null) return@withLock current.plan
            requireCurrentOverride(current.plan, previous)
            val base = baseRecord(current)
            if (!dao.changeSpeedOverride(key, previous, null, null, null, null))
                throw ApiFailure("stale_segmentation", "切分已在另一处改变，请重新打开后还原")
            base.plan
        }
    }

    private suspend fun baseRecord(current: SpeedPlanRecord): SpeedPlanRecord {
        val key = current.plan.basePlanKey ?: return current
        val base = cachedRecordUnlocked(key) ?: throw ApiFailure("missing_base_plan", "基础切分缓存无法读取，个人方案已保留")
        require(base.plan.basePlanKey == null && base.plan.origin in setOf("rules", "ai"))
        requireSameMaterial(current.plan, base.plan)
        require(base.sourceDigest == current.sourceDigest)
        return base
    }

    private fun requireCurrentOverride(plan: SpeedPlan, currentKey: String?) {
        if (currentKey != null && currentKey != plan.planKey || currentKey == null && plan.basePlanKey != null)
            throw ApiFailure("stale_segmentation", "切分已改变，请重新打开后编辑")
    }
    private fun requireSameMaterial(first: SpeedPlan, second: SpeedPlan) {
        require(first.articleId == second.articleId && first.bodyHash == second.bodyHash && first.granularity == second.granularity &&
            first.paragraphIndices == second.paragraphIndices)
    }
    private fun overrideKey(plan: SpeedPlan) = "speed-override:${sha256(listOf(plan.articleId, plan.bodyHash, plan.granularity.name, plan.paragraphIndices.joinToString(",")).joinToString("|"))}"
    private fun record(plan: SpeedPlan, source: List<SpeedSourcePart>): SpeedPlanRecord =
        SpeedPlanRecord(plan.copy(planKey = SpeedChunker.planKey(plan)), source, sha256(json.encodeToString(source))).also(SpeedPlanStorage::validate)
    private fun SpeedPlanRecord.row() = PreferenceRow("speed-plan:${plan.planKey}", json.encodeToString(this))

    override suspend fun saveRun(run: SpeedRun) = withContext(Dispatchers.IO) {
        lock.withLock {
            validId(run.id)
            val plan = cachedPlanUnlocked(run.planKey) ?: throw ApiFailure("missing_plan", "找不到原训练材料，记录未写入")
            require(run.articleId == plan.articleId && run.bodyHash == plan.bodyHash && run.granularity == plan.granularity)
            require(run.startedAt >= 0 && run.finishedAt >= run.startedAt && run.activeMillis in 0..86_400_000 && run.activeMillis <= run.finishedAt - run.startedAt)
            require(run.groupMillis.size == plan.chunks.size && run.groupMillis.all { it in 0..86_400_000 })
            require(run.groupMillis.sum() <= run.activeMillis && run.revisits in 0..10000 && run.pauses in 0..10000)
            require(run.groupsPerMinute in SpeedPacing.MIN_GROUPS_PER_MINUTE..SpeedPacing.MAX_GROUPS_PER_MINUTE)
            SpeedInteractionStorage.validate(run, plan)
            require(run.pacedCompletion != true || run.completed)
            if (run.round == SpeedPacedRound.GUIDED) require(run.parentRunId == null)
            else { validId(requireNotNull(run.parentRunId)); require(run.parentRunId != run.id) }
            val submittedAnswers = if (run.round == SpeedPacedRound.GUIDED) run.answers else emptyList()
            require(submittedAnswers.map { it.questionId }.distinct().size == submittedAnswers.size)
            val answers = submittedAnswers.map { answer ->
                val question = plan.questions.single { it.id == answer.questionId }
                require(answer.selectedIndex in question.options.indices)
                answer.copy(correct = answer.selectedIndex == question.answerIndex)
            }
            val seen = plan.chunks.indices.filter { run.groupMillis[it] > 0 }
            if (run.completed) require(seen.size == plan.chunks.size) { "尚有未阅读意群，不能标为完成" }
            val canonical = run.copy(wordsRead = seen.sumOf { plan.chunks[it].wordCount }, groupsRead = seen.size,
                answers = answers, questionCount = if (run.round == SpeedPacedRound.GUIDED) plan.questions.size else 0,
                planOrigin = plan.origin, firstAttempt = run.round == SpeedPacedRound.GUIDED && run.firstAttempt)
            dao.recordSpeedRun(canonical, plan)
        }
    }

    override suspend fun saveNaturalAttempt(attempt: NaturalReadingAttempt) = withContext(Dispatchers.IO) {
        lock.withLock {
            validId(attempt.id); validId(attempt.parentRunId)
            require(attempt.id != attempt.parentRunId)
            val plan = cachedPlanUnlocked(attempt.planKey) ?: throw ApiFailure("missing_plan", "找不到原训练材料，记录未写入")
            NaturalReadingMetrics.summarize(attempt, plan)
            dao.recordNaturalAttempt(attempt, plan)
        }
    }

    override suspend fun saveCorrection(correction: SpeedCorrection) = withContext(Dispatchers.IO) {
        lock.withLock {
            validId(correction.id)
            val plan = cachedPlanUnlocked(correction.planKey) ?: throw ApiFailure("missing_plan", "找不到原切分方案，请重新进入训练")
            require(correction.articleId == plan.articleId && correction.bodyHash == plan.bodyHash && correction.granularity == plan.granularity)
            require(correction.createdAt >= 0 && correction.kind in setOf("merge_next", "too_long", "other") && correction.comment.length <= 1000)
            val index = plan.chunks.indexOfFirst { it.start == correction.start && it.end == correction.end }
            require(index >= 0) { "纠错必须对应当前方案的完整意群" }
            if (correction.kind == "merge_next") {
                val current = plan.chunks[index]
                val next = plan.chunks.getOrNull(index + 1)
                require(next != null && next.paragraph == current.paragraph && next.sentence == current.sentence) {
                    "句末没有可合并的下一意群，请选择其他切分问题"
                }
            }
            dao.insert(PreferenceRow("speed-library:correction:${correction.id}", json.encodeToString(correction)))
            Unit
        }
    }
    private fun validId(id: String) { require(id.isNotBlank() && id.length <= 200 && id.none { it.isISOControl() }) }
    private fun AiJob.row() = JobRow(id, status, updatedAt, json.encodeToString(this))
    companion object { private val KEY = Regex("[a-f0-9]{64}") }
}
