package dev.lexicon.data

import androidx.room3.*
import dev.lexicon.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Separate rows reuse the v1 preferences table; existing installations need no schema migration. */
@Dao abstract class ReadingDao {
    private val resumeCodec = Json { ignoreUnknownKeys = true }
    @Query("SELECT * FROM preferences WHERE `key` LIKE 'reading-library:%' ORDER BY `key`")
    abstract fun observeLibrary(): Flow<List<PreferenceRow>>
    @Query("SELECT * FROM preferences WHERE `key` LIKE 'reading-library:%' ORDER BY `key`")
    abstract suspend fun libraryRows(): List<PreferenceRow>
    @Query("SELECT * FROM preferences WHERE `key` LIKE 'reading-library:edition:%' ORDER BY `key` DESC LIMIT 8")
    abstract suspend fun editionRows(): List<PreferenceRow>
    @Query("SELECT * FROM preferences WHERE `key`=:key") abstract suspend fun entry(key: String): PreferenceRow?
    @Query("SELECT * FROM preferences WHERE `key` LIKE :prefix || '%' ORDER BY `key`")
    abstract suspend fun studyRows(prefix: String): List<PreferenceRow>
    @Query("SELECT `key`, CAST(data AS BLOB) AS bytes FROM preferences WHERE `key`=:key")
    abstract suspend fun rawEntry(key: String): RawPreferenceValue?
    @Upsert abstract suspend fun put(row: PreferenceRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insert(row: PreferenceRow): Long
    @Query("DELETE FROM preferences WHERE `key`=:key") abstract suspend fun delete(key: String)

    @Query("SELECT * FROM preferences WHERE `key` LIKE 'reading-resume:record:%' OR `key` LIKE 'reading-resume:generation:%' ORDER BY `key`")
    abstract fun observeReadingResumes(): Flow<List<PreferenceRow>>

    @Transaction open suspend fun beginReadingResume(articleId: String, bodyHash: String): ReadingResumeLease {
        ReadingResumes.validateIdentity(articleId, bodyHash)
        val previous = readResumeRecord(articleId)
        val watermark = readResumeGeneration(articleId)
        val high = maxOf(previous?.generation ?: 0, watermark?.generation ?: 0)
        if (high == Long.MAX_VALUE) throw ApiFailure("reading_resume_limit", "续读记录已达到版本上限，原位置保留")
        val generation = high + 1
        put(ReadingResumeStorage.generationRow(articleId, generation, bodyHash, previous))
        return ReadingResumeLease(articleId, bodyHash, generation, previous)
    }

    @Transaction open suspend fun checkpointReadingResume(lease: ReadingResumeLease, incoming: ReadingResumeRecord): Boolean {
        ReadingResumes.validateLease(lease)
        ReadingResumes.validate(incoming)
        require(incoming.articleId == lease.articleId && incoming.bodyHash == lease.bodyHash && incoming.generation == lease.generation)
        val watermark = readResumeGeneration(lease.articleId) ?: return false
        if (watermark.generation != lease.generation || watermark.bodyHash != lease.bodyHash ||
            watermark.previousHash != ReadingResumeStorage.previousHash(lease.previous)) return false
        val current = readResumeRecord(lease.articleId)
        if (current != null && (current.generation > incoming.generation ||
                (current.generation == incoming.generation && current.sequence >= incoming.sequence))) return false
        val known = articleRow(lease.articleId) ?: return false
        val article = try { resumeCodec.decodeFromString<Article>(known.data) }
        catch (_: Exception) { throw ApiFailure("reading_resume_invalid", "文章来源无法验证，原续读位置保留") }
        if (article.id != lease.articleId) return false
        val stored = bodyRow(lease.articleId)
        if (stored != null) {
            val body = try { resumeCodec.decodeFromString<ArticleBody>(stored.data) }
            catch (_: Exception) { throw ApiFailure("reading_resume_invalid", "正文缓存无法验证，原续读位置保留") }
            if (body.articleId != lease.articleId || body.contentHash != incoming.bodyHash) return false
            try { ReadingResumes.validate(incoming, body) }
            catch (_: Exception) { throw ApiFailure("reading_resume_invalid", "正文快照与续读位置不一致，原位置保留") }
        } else {
            val exposure = entry("reading-exposure:body:${incoming.bodyHash}")?.data?.toLongOrNull()
            if (current?.bodyHash != incoming.bodyHash && (exposure == null || exposure < 0)) return false
        }
        val next = incoming.copy(completed = incoming.completed ||
            (current != null && current.generation == incoming.generation && current.completed))
        put(ReadingResumeStorage.row(next))
        return true
    }

    @Transaction open suspend fun clearReadingResume(expected: ReadingResumeRecord): Boolean {
        ReadingResumes.validate(expected)
        val current = readResumeRecord(expected.articleId) ?: return false
        if (current != expected) return false
        val watermark = readResumeGeneration(expected.articleId)
        val high = maxOf(current.generation, watermark?.generation ?: 0)
        if (high == Long.MAX_VALUE) throw ApiFailure("reading_resume_limit", "续读记录已达到版本上限，原位置保留")
        put(ReadingResumeStorage.generationRow(expected.articleId, high + 1, null))
        delete(ReadingResumeStorage.recordKey(expected.articleId))
        return true
    }

    private suspend fun readResumeRecord(articleId: String): ReadingResumeRecord? =
        entry(ReadingResumeStorage.recordKey(articleId))?.let { row ->
            try { ReadingResumeStorage.record(row) }
            catch (_: Exception) { throw ApiFailure("reading_resume_invalid", "续读位置无法读取，原记录保留") }
        }

    private suspend fun readResumeGeneration(articleId: String): ReadingResumeGeneration? {
        val row = entry(ReadingResumeStorage.generationKey(articleId))
        return try { ReadingResumeStorage.generation(row, articleId) }
        catch (_: Exception) { throw ApiFailure("reading_resume_invalid", "续读版本记录无法读取，原记录保留") }
    }

    /** Database CAS protects independent repository instances without locking network work. */
    @Transaction open suspend fun changeBackgroundRecord(key: String, expectedData: String?,
        replacement: PreferenceRow?, expectedEvidence: List<PreferenceRow> = emptyList(),
        expectedRevisionData: String? = null): Boolean {
        require(key.startsWith(BackgroundRecordStorage.PREFIX))
        require(replacement == null || replacement.key == key)
        require(replacement != null || expectedData != null)
        require(expectedEvidence.all { it.key.startsWith("background:lookup:1:") || it.key.startsWith("background:explanation:1:") })
        if (entry(key)?.data != expectedData || expectedEvidence.any { entry(it.key) != it }) return false
        val revisionKey = BackgroundRecordStorage.revisionKey(key.removePrefix(BackgroundRecordStorage.PREFIX))
        if (entry(revisionKey)?.data != expectedRevisionData) return false
        val previous = expectedData?.let { BackgroundRecordStorage.decode(PreferenceRow(key, it)) }
        val highRevision = maxOf(BackgroundRecordStorage.revision(expectedRevisionData), previous?.revision ?: 0)
        if (replacement == null) {
            require(highRevision > 0)
            delete(key)
            // A content-free watermark prevents old editors from matching a later re-created item.
            put(PreferenceRow(revisionKey, highRevision.toString()))
        } else {
            val next = BackgroundRecordStorage.decode(replacement)
            require(highRevision < Int.MAX_VALUE && next.revision == highRevision + 1)
            put(replacement)
            put(PreferenceRow(revisionKey, next.revision.toString()))
        }
        return true
    }
    @Query("SELECT * FROM ai_jobs WHERE id=:id") abstract suspend fun job(id: String): JobRow?
    @Query("SELECT * FROM ai_jobs WHERE id LIKE 'reading-ai:%' AND status='running'") abstract suspend fun runningJobs(): List<JobRow>
    @Query("SELECT * FROM preferences WHERE `key` LIKE 'speed-library:%' ORDER BY `key`")
    abstract fun observeSpeedLibrary(): Flow<List<PreferenceRow>>
    @Query("SELECT * FROM preferences WHERE `key` LIKE 'speed-library:%' ORDER BY `key`")
    abstract suspend fun speedLibraryRows(): List<PreferenceRow>
    @Query("SELECT * FROM preferences WHERE `key` LIKE 'speed-library:run:%' ORDER BY `key`")
    abstract suspend fun personalizationRunRows(): List<PreferenceRow>
    @Query("SELECT * FROM ai_jobs WHERE id LIKE 'speed-ai:%' AND status='running'")
    abstract suspend fun speedRunningJobs(): List<JobRow>
    @Upsert abstract suspend fun putJob(row: JobRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertEvent(row: EventRow): Long
    @Upsert abstract suspend fun putEvent(row: EventRow)
    @Query("SELECT * FROM local_events WHERE id=:id") abstract suspend fun event(id: String): EventRow?
    @Query("SELECT * FROM articles WHERE id=:id") abstract suspend fun articleRow(id: String): ArticleRow?
    @Query("SELECT * FROM article_bodies WHERE articleId=:id") abstract suspend fun bodyRow(id: String): BodyRow?
    @Query("SELECT * FROM article_bodies WHERE expiresAt<=:now") abstract suspend fun expiredBodyRows(now: Long): List<BodyRow>
    @Query("DELETE FROM article_bodies WHERE expiresAt<=:now") abstract suspend fun deleteExpiredBodies(now: Long)
    @Upsert abstract suspend fun putArticleRow(row: ArticleRow)
    @Upsert abstract suspend fun putBodyRow(row: BodyRow)

    /** Persistent exposure survives body expiry and a later feed metadata refresh. */
    @Transaction open suspend fun recordArticleOpen(id: String, now: Long) {
        val row = articleRow(id) ?: return
        val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val article = codec.decodeFromString<Article>(row.data)
        putArticleRow(row.copy(data = codec.encodeToString(article.copy(read = true))))
        insert(PreferenceRow("reading-exposure:article:$id", now.toString()))
        bodyRow(id)?.let { cached ->
            val body = runCatching { codec.decodeFromString<ArticleBody>(cached.data) }.getOrNull()
            if (body?.articleId == id) insert(PreferenceRow("reading-exposure:body:${body.contentHash}", now.toString()))
        }
    }

    /** Body prefetch updates size but preserves the current article-open state. */
    @Transaction open suspend fun cacheNativeBody(body: ArticleBody, words: Int, markRead: Boolean, now: Long) {
        val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val row = requireNotNull(articleRow(body.articleId))
        val latest = codec.decodeFromString<Article>(row.data)
        putBodyRow(BodyRow(body.articleId, body.expiresAt, codec.encodeToString(body)))
        val updated = latest.copy(read = latest.read || markRead, wordCount = words)
        putArticleRow(row.copy(data = codec.encodeToString(updated)))
        if (updated.read) {
            insert(PreferenceRow("reading-exposure:article:${body.articleId}", now.toString()))
            insert(PreferenceRow("reading-exposure:body:${body.contentHash}", now.toString()))
        }
    }

    /** Retain known body identity before removing old caches, including installations predating exposure markers. */
    @Transaction open suspend fun expireBodiesPreservingExposure(now: Long) {
        val codec = Json { ignoreUnknownKeys = true }
        expiredBodyRows(now).forEach { row ->
            val body = runCatching { codec.decodeFromString<ArticleBody>(row.data) }.getOrNull()
            val article = articleRow(row.articleId)?.let { runCatching { codec.decodeFromString<Article>(it.data) }.getOrNull() }
            if (body?.articleId == row.articleId && (article?.read == true || article?.captured == true ||
                    entry("reading-exposure:article:${row.articleId}") != null)) {
                insert(PreferenceRow("reading-exposure:body:${body.contentHash}", now.toString()))
            }
        }
        deleteExpiredBodies(now)
    }

    @Transaction open suspend fun dailyEdition(key: String, articles: List<Article>, sources: List<Source>, now: Long, zoneId: String): DailyEdition {
        val existing = entry(key)?.let { Json.decodeFromString<DailyEdition>(it.data) }
        val history = editionRows().map { Json.decodeFromString<DailyEdition>(it.data) }
        val result = ReadingDailyPlanner.extend(existing, articles, sources, now, zoneId, history)
        // Do not freeze an empty result. A later sync can supply today's first set.
        if (result.articleIds.isNotEmpty() && result != existing) put(PreferenceRow(key, Json.encodeToString(result)))
        return result
    }

    @Transaction open suspend fun recordOnce(row: PreferenceRow, event: EventRow): Boolean {
        if (insert(row) == -1L) return false
        insertEvent(event)
        return true
    }

    /** Partial mode-exit snapshots and final completion share a logical session ID. */
    @Transaction open suspend fun recordSession(incoming: ReadingSession) {
        val key = "reading-library:session:${incoming.id}"
        val previous = entry(key)?.let { Json.decodeFromString<ReadingSession>(it.data) }
        if (previous != null) require(previous.articleId == incoming.articleId && previous.startedAt == incoming.startedAt) { "阅读会话标识与起始记录不一致" }
        val latest = if (previous != null && previous.finishedAt > incoming.finishedAt) previous else incoming
        val merged = if (previous == null) incoming else latest.copy(
            finishedAt = maxOf(previous.finishedAt, incoming.finishedAt),
            activeMillis = maxOf(previous.activeMillis, incoming.activeMillis),
            wordsRead = maxOf(previous.wordsRead, incoming.wordsRead),
            paragraphsRead = maxOf(previous.paragraphsRead, incoming.paragraphsRead),
            totalParagraphs = maxOf(previous.totalParagraphs, incoming.totalParagraphs),
            assistanceCount = maxOf(previous.assistanceCount, incoming.assistanceCount),
            completed = previous.completed || incoming.completed,
        )
        if (merged == previous) return
        put(PreferenceRow(key, Json.encodeToString(merged)))
        val eventId = "reading-session:${incoming.id}"
        // Once completion has been recorded, retain its first timestamp and never add XP events.
        val existingEvent = event(eventId)
        if (previous?.completed == true && existingEvent != null) return
        val event = LocalEvent(eventId, if (merged.completed) "reading_completed" else "reading_session", merged.finishedAt)
        putEvent(EventRow(event.id, event.createdAt, Json.encodeToString(event)))
    }

    @Transaction open suspend fun finishJob(cache: PreferenceRow, job: JobRow, libraryEntry: PreferenceRow? = null) {
        put(cache)
        libraryEntry?.let { put(it) }
        putJob(job)
    }

    /** Plan identities are immutable even when another request alias reaches the same content. */
    @Transaction open suspend fun storeSpeedPlan(plan: PreferenceRow, request: PreferenceRow, job: JobRow?) {
        require(plan.key.startsWith("speed-plan:") && request.key.startsWith("speed-request:"))
        insert(plan)
        put(request)
        job?.let { putJob(it) }
    }

    /** Changing a personal override never rewrites the source or historical training plans. */
    @Transaction open suspend fun changeSpeedOverride(key: String, expected: String?, plan: PreferenceRow?,
        nextPlanKey: String?, correction: PreferenceRow?, exposure: PreferenceRow?): Boolean {
        require(key.startsWith("speed-override:"))
        if (entry(key)?.data != expected) return false
        if (nextPlanKey == null) {
            require(plan == null && correction == null && exposure == null)
            delete(key)
        } else {
            require(plan?.key == "speed-plan:$nextPlanKey" && correction?.key?.startsWith("speed-library:correction:") == true &&
                exposure?.key?.startsWith("speed-exposure:") == true)
            insert(requireNotNull(plan)); put(PreferenceRow(key, nextPlanKey))
            insert(requireNotNull(correction)); put(requireNotNull(exposure))
        }
        return true
    }

    @Transaction open suspend fun recordSpeedSegmentationExposure(key: String, expected: String?,
        correction: PreferenceRow, exposure: PreferenceRow): Boolean {
        require(key.startsWith("speed-override:") && correction.key.startsWith("speed-library:correction:") && exposure.key.startsWith("speed-exposure:"))
        if (entry(key)?.data != expected) return false
        insert(correction); put(exposure)
        return true
    }

    /** Shares the run writer's transaction boundary; preview does not modify the cached plan. */
    @Transaction open suspend fun recordSpeedPreview(planKey: String, expectedRecordData: String, paragraph: Int, now: Long) {
        val stored = requireNotNull(entry("speed-plan:$planKey")) { "找不到可预览的训练材料" }
        val codec = Json { ignoreUnknownKeys = true }
        val record = codec.decodeFromString<SpeedPlanRecord>(stored.data)
        require(record == codec.decodeFromString<SpeedPlanRecord>(expectedRecordData)) { "预览材料已改变" }
        require(record.plan.planKey == planKey && paragraph in record.plan.paragraphIndices)
        require(now >= 0)
        insert(PreferenceRow("speed-exposure:${record.plan.bodyHash}:$paragraph", now.toString()))
    }

    @Transaction open suspend fun failJob(job: JobRow, pause: Boolean) {
        putJob(job)
        if (pause) put(PreferenceRow("ai_paused", "true"))
    }

    /** CAS also freezes suggestion evidence: new or edited runs invalidate an in-flight decision. */
    @Transaction open suspend fun compareAndSetPersonalization(expectedData: String?, replacement: PreferenceRow,
        expectedRuns: List<PreferenceRow>?): Boolean {
        require(replacement.key == "personalization:profile")
        val next = PersonalizationStorage.decode(replacement.data)
        val currentBytes = rawEntry(replacement.key)?.bytes
        val currentData = currentBytes?.let(PersonalizationStorage::text)
        if (currentData != expectedData) return false
        if (expectedRuns != null && personalizationRunRows() != expectedRuns) return false
        val previous = PersonalizationStorage.decodeRaw(currentBytes)
        require(previous.revision < Long.MAX_VALUE && next.revision == previous.revision + 1) { "个性化设置版本必须逐次增加" }
        put(replacement)
        return true
    }

    /** Completed speed attempts are immutable. Partial checkpoints can only accumulate evidence. */
    @Transaction open suspend fun recordSpeedRun(incoming: SpeedRun, plan: SpeedPlan) {
        val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        if (incoming.round == SpeedPacedRound.GUIDED) require(incoming.parentRunId == null)
        else checkedPacedParent(requireNotNull(incoming.parentRunId), incoming.id, SpeedPacedRound.GUIDED, plan, incoming.startedAt)
        val key = "speed-library:run:${incoming.id}"
        val previous = entry(key)?.let { codec.decodeFromString<SpeedRun>(it.data) }
        if (previous != null) {
            require(previous.articleId == incoming.articleId && previous.bodyHash == incoming.bodyHash &&
                previous.planKey == incoming.planKey && previous.startedAt == incoming.startedAt &&
                previous.round == incoming.round && previous.parentRunId == incoming.parentRunId) { "训练记录标识不一致" }
            if (previous.completed) return
            if (incoming.finishedAt < previous.finishedAt) return
            require(incoming.activeMillis >= previous.activeMillis && incoming.revisits >= previous.revisits && incoming.pauses >= previous.pauses)
            require(previous.groupMillis.indices.all { incoming.groupMillis[it] >= previous.groupMillis[it] })
            require(previous.answers.all { old -> incoming.answers.any { it == old } }) { "已提交的理解答案不能改写" }
        }
        SpeedInteractionStorage.validate(incoming, plan)
        val chunkInteractions = SpeedInteractionStorage.merged(previous, incoming)
        val earlierEvidence = speedLibraryRows().asSequence().filter { it.key.startsWith("speed-library:run:") }.mapNotNull {
            runCatching { codec.decodeFromString<SpeedRun>(it.data) }.getOrNull()
        }.filter { it.id != incoming.id && (it.completed || it.answers.isNotEmpty()) &&
            it.bodyHash == incoming.bodyHash }.toList()
        val editedExposure = plan.paragraphIndices.any { entry("speed-exposure:${incoming.bodyHash}:$it") != null }
        val repeatedMaterial = editedExposure || earlierEvidence.any { run ->
            entry("speed-plan:${run.planKey}")?.let { row ->
                runCatching {
                    val previousPlan = codec.decodeFromString<SpeedPlanRecord>(row.data).plan
                    if (run.completed) previousPlan.paragraphIndices.any { it in plan.paragraphIndices }
                    else run.answers.any { answer ->
                        // Revealing an answer is exposure even when playback was never completed.
                        previousPlan.questions.find { it.id == answer.questionId }?.paragraph?.let { it in plan.paragraphIndices } ?: true
                    }
                }.getOrDefault(true)
            } ?: true
        }
        val stablePacing = if (previous == null) incoming.pacingStable
            else previous.pacingStable?.let { old -> incoming.pacingStable?.let { old && it } }
        // Known partial=false may finish true; legacy unknown must not gain a success label on restore.
        val pacedCompletion = if (previous != null && previous.pacedCompletion == null) null else incoming.pacedCompletion
        val checkedBinding = checkedRunBinding(incoming)
        val paceBinding = if (previous == null) checkedBinding
            else previous.paceBinding?.takeIf { it == checkedBinding }
        val canonical = incoming.copy(firstAttempt = incoming.round == SpeedPacedRound.GUIDED && incoming.firstAttempt &&
            (previous?.firstAttempt ?: true) && !repeatedMaterial, pacingStable = stablePacing, pacedCompletion = pacedCompletion,
            paceBinding = paceBinding, chunkInteractions = chunkInteractions,
            answers = if (incoming.round == SpeedPacedRound.GUIDED) incoming.answers else emptyList(),
            questionCount = if (incoming.round == SpeedPacedRound.GUIDED) incoming.questionCount else 0)
        put(PreferenceRow(key, codec.encodeToString(canonical)))
        if (canonical.completed) {
            val eventId = if (canonical.round == SpeedPacedRound.GUIDED) "speed-run:${canonical.id}" else "speed-faded-run:${canonical.id}"
            val eventType = if (canonical.round == SpeedPacedRound.GUIDED) "speed_training_completed" else "speed_faded_completed"
            insertEvent(EventRow(eventId, canonical.finishedAt, codec.encodeToString(LocalEvent(eventId, eventType, canonical.finishedAt))))
        }
    }

    /** Natural timing stays in paragraph records; it never becomes block or first-answer evidence. */
    @Transaction open suspend fun recordNaturalAttempt(incoming: NaturalReadingAttempt, plan: SpeedPlan) {
        NaturalReadingMetrics.summarize(incoming, plan)
        checkedPacedParent(incoming.parentRunId, incoming.id, SpeedPacedRound.FADED, plan, incoming.startedAt)
        val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val key = "speed-library:natural:${incoming.id}"
        val previous = entry(key)?.let { codec.decodeFromString<NaturalReadingAttempt>(it.data) }
        if (previous != null) {
            require(previous.articleId == incoming.articleId && previous.bodyHash == incoming.bodyHash &&
                previous.planKey == incoming.planKey && previous.parentRunId == incoming.parentRunId &&
                previous.startedAt == incoming.startedAt && previous.paragraphs.map { it.paragraph } == incoming.paragraphs.map { it.paragraph }) {
                "自主阅读记录标识不一致"
            }
            if (previous.completed) return
            if (incoming.finishedAt < previous.finishedAt) return
            require(incoming.pauses >= previous.pauses && incoming.revisits >= previous.revisits)
            require(previous.paragraphs.zip(incoming.paragraphs).all { (old, next) ->
                next.activeMillis >= old.activeMillis && (!old.completed || next.completed)
            }) { "已记录的段落时间与完成状态不能回退" }
        }
        put(PreferenceRow(key, codec.encodeToString(incoming)))
        if (incoming.completed) {
            val eventId = "natural-run:${incoming.id}"
            insertEvent(EventRow(eventId, incoming.finishedAt,
                codec.encodeToString(LocalEvent(eventId, "natural_reading_completed", incoming.finishedAt))))
        }
    }

    /** Called inside the child writer's transaction so the parent and new round commit agree. */
    private suspend fun checkedPacedParent(parentId: String, childId: String, requiredRound: SpeedPacedRound,
        plan: SpeedPlan, childStartedAt: Long): SpeedRun {
        require(parentId.isNotBlank() && parentId.length <= 200 && parentId.none { it.isISOControl() } && parentId != childId)
        val codec = Json { ignoreUnknownKeys = true }
        val parent = entry("speed-library:run:$parentId")?.let { codec.decodeFromString<SpeedRun>(it.data) }
            ?: throw IllegalArgumentException("请先完成上一轮训练")
        require(parent.id == parentId && parent.completed && parent.round == requiredRound &&
            parent.articleId == plan.articleId && parent.bodyHash == plan.bodyHash && parent.planKey == plan.planKey &&
            parent.granularity == plan.granularity && parent.startedAt >= 0 && parent.finishedAt >= parent.startedAt &&
            parent.finishedAt <= childStartedAt) { "上一轮训练与当前材料或时间不匹配" }
        if (requiredRound == SpeedPacedRound.GUIDED) require(parent.parentRunId == null)
        else checkedPacedParent(requireNotNull(parent.parentRunId), parent.id, SpeedPacedRound.GUIDED, plan, parent.startedAt)
        return parent
    }

    /** Unverifiable attribution loses its binding, never the user's reading record. */
    private suspend fun checkedRunBinding(run: SpeedRun): AppliedPaceBinding? {
        val binding = run.paceBinding ?: return null
        if (run.round != SpeedPacedRound.GUIDED || binding.granularity != run.granularity || binding.rate != run.groupsPerMinute) return null
        return runCatching {
            val profile = PersonalizationStorage.decodeRaw(rawEntry("personalization:profile")?.bytes)
            val decision = profile.decisions.singleOrNull { it.id == binding.decisionId }
            binding.takeIf { profile.activePaceBinding == it && decision != null &&
                decision.status == PersonalizationDecisionStatus.APPLIED && decision.appliedRevision == it.profileRevision &&
                decision.suggestion.granularity == it.granularity && decision.suggestion.toRate == it.rate &&
                run.startedAt >= decision.decidedAt }
        }.getOrNull()
    }
}
