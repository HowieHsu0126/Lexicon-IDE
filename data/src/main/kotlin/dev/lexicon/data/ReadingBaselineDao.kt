package dev.lexicon.data

import androidx.room3.*
import dev.lexicon.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Assessment reservation, exposure and answers share the existing database transaction. */
@Dao abstract class ReadingBaselineDao {
    @Query("SELECT * FROM preferences WHERE `key` LIKE 'baseline-library:attempt:%' ORDER BY `key`")
    abstract fun observeAttempts(): Flow<List<PreferenceRow>>
    @Query("SELECT * FROM preferences WHERE `key` LIKE 'baseline-library:attempt:%' OR `key` LIKE 'reading-library:session:%' OR `key` LIKE 'reading-library:note:%' OR `key` LIKE 'speed-library:%'")
    abstract suspend fun exposureRows(): List<PreferenceRow>
    @Query("SELECT * FROM preferences WHERE `key`=:key") abstract suspend fun entry(key: String): PreferenceRow?
    @Upsert abstract suspend fun put(row: PreferenceRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insert(row: PreferenceRow): Long
    @Query("SELECT * FROM articles") abstract suspend fun articles(): List<ArticleRow>
    @Query("SELECT * FROM article_bodies") abstract suspend fun bodies(): List<BodyRow>
    @Query("SELECT * FROM captures") abstract suspend fun captures(): List<CaptureRow>
    @Upsert abstract suspend fun putArticle(row: ArticleRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertEvent(row: EventRow): Long

    @Transaction open suspend fun begin(id: String, plan: SpeedPlan, declaredUnseen: Boolean, now: Long): ReadingBaselineAttempt {
        require(id.isNotBlank() && now >= 0 && entry("baseline-library:attempt:$id") == null)
        val codec = codec()
        checkedPlan(plan)
        val articleRows = articles()
        val row = requireNotNull(articleRows.find { it.id == plan.articleId }) { "找不到自测文章" }
        val article = codec.decodeFromString<Article>(row.data)
        require(article.id == plan.articleId)
        val unseen = !wasExposed(plan, article, articleRows)
        val attempt = ReadingBaselineAttempt(id, plan.articleId, plan.bodyHash, plan.planKey, now, now,
            plan.paragraphIndices.map { NaturalParagraphTiming(it, 0, false) }, emptyList(), plan.questions.size,
            localUnseen = unseen, userConfirmedUnseen = declaredUnseen)
        ReadingBaselineMetrics.summarize(attempt, plan)
        insert(PreferenceRow("baseline-library:attempt:$id", codec.encodeToString(attempt)))
        // Attribution is retained independently of future feed title/source changes.
        insert(PreferenceRow("baseline-source:$id", codec.encodeToString(SourceRef(article.title, article.sourceName, article.url, article.publishedAt))))
        article.topic?.takeIf { it in Topics.children }?.let { insert(PreferenceRow("baseline-topic:$id", it)) }
        plan.paragraphIndices.forEach { paragraph ->
            insert(PreferenceRow("baseline-exposure:${plan.bodyHash}:$paragraph", now.toString()))
            insert(PreferenceRow("speed-exposure:${plan.bodyHash}:$paragraph", now.toString()))
        }
        putArticle(row.copy(data = codec.encodeToString(article.copy(read = true))))
        insert(PreferenceRow("reading-exposure:article:${article.id}", now.toString()))
        return attempt
    }

    @Transaction open suspend fun save(incoming: ReadingBaselineAttempt, plan: SpeedPlan, now: Long): ReadingBaselineAttempt {
        require(now >= 0)
        val codec = codec()
        checkedPlan(plan)
        val key = "baseline-library:attempt:${incoming.id}"
        val old = entry(key)?.let { codec.decodeFromString<ReadingBaselineAttempt>(it.data) }
            ?: throw IllegalArgumentException("请先开始这次自测")
        require(old.id == incoming.id && old.articleId == incoming.articleId && old.bodyHash == incoming.bodyHash &&
            old.planKey == incoming.planKey && old.startedAt == incoming.startedAt) { "自测记录标识不一致" }
        ReadingBaselineMetrics.summarize(old, plan)
        if (old.completed) return old
        if (incoming.finishedAt < old.finishedAt) return old
        require(incoming.paragraphs.map { it.paragraph } == plan.paragraphIndices)
        require(incoming.pauses in old.pauses..10_000)
        require(old.paragraphs.zip(incoming.paragraphs).all { (previous, next) ->
            next.activeMillis >= previous.activeMillis && next.activeMillis <= ReadingBaselineMetrics.MAX_ACTIVE_MILLIS &&
                (!previous.completed || next.completed) && (!next.completed || next.activeMillis > 0)
        }) { "已记录的阅读时间与完成状态不能回退" }
        if (old.readingFinishedAt != null) require(incoming.paragraphs == old.paragraphs) { "答题阶段不能改写阅读时间" }
        else require(incoming.answers.isEmpty()) { "请先完成并保存原文阅读，再提交答案" }
        val active = incoming.paragraphs.sumOf { it.activeMillis }
        val finished = maxOf(now, old.finishedAt)
        require(active in 0..ReadingBaselineMetrics.MAX_ACTIVE_MILLIS && active <= finished - old.startedAt) {
            "阅读计时超出实际会话时长，请检查设备时间后重试"
        }
        val allRead = incoming.paragraphs.all { it.completed }
        val readingFinished = old.readingFinishedAt ?: if (allRead) finished else null
        val questions = plan.questions.associateBy { it.id }
        require(incoming.answers.map { it.questionId }.distinct().size == incoming.answers.size)
        require(incoming.answers.all { answer -> questions[answer.questionId]?.let { answer.selectedIndex in it.options.indices } == true })
        require(old.answers.all { previous -> incoming.answers.any { it.questionId == previous.questionId && it.selectedIndex == previous.selectedIndex } }) {
            "已提交的答案不能改写"
        }
        val complete = readingFinished != null && incoming.answers.size == plan.questions.size
        val revealed = if (complete && plan.questions.isNotEmpty()) maxOf(now, requireNotNull(readingFinished)) else null
        val answers = incoming.answers.sortedBy { answer -> plan.questions.indexOfFirst { it.id == answer.questionId } }
            .map { answer -> answer.copy(correct = revealed != null && answer.selectedIndex == questions.getValue(answer.questionId).answerIndex) }
        val canonical = old.copy(finishedAt = maxOf(finished, revealed ?: finished), paragraphs = incoming.paragraphs,
            answers = answers, readingFinishedAt = readingFinished, revealedAt = revealed, completed = complete,
            pauses = if (old.readingFinishedAt == null) incoming.pauses else old.pauses)
        ReadingBaselineMetrics.summarize(canonical, plan)
        put(PreferenceRow(key, codec.encodeToString(canonical)))
        if (canonical.completed) {
            val event = LocalEvent("baseline:${canonical.id}", "reading_baseline_completed", canonical.finishedAt)
            insertEvent(EventRow(event.id, event.createdAt, codec.encodeToString(event)))
        }
        return canonical
    }

    /** Inspecting retained fragments never begins another attempt or downloads an article. */
    @Transaction open suspend fun material(id: String, plan: SpeedPlan): ReadingBaselineMaterial? {
        val attempt = entry("baseline-library:attempt:$id")?.let { codec().decodeFromString<ReadingBaselineAttempt>(it.data) }
            ?: return null
        require(attempt.id == id)
        ReadingBaselineMetrics.summarize(attempt, plan)
        val record = checkedPlan(plan)
        val source = entry("baseline-source:$id")?.let { codec().decodeFromString<SourceRef>(it.data) } ?: SourceRef()
        val topic = entry("baseline-topic:$id")?.data?.takeIf { it in Topics.children }
        return ReadingBaselineMaterial(plan.planKey, plan.bodyHash, source,
            record.source.map { BaselineSourceParagraph(it.paragraph, it.start, it.text) }, topic)
    }

    /** Resume is monotonic and preserves the recorded clock, answers, and exposure declaration. */
    @Transaction open suspend fun resume(id: String, plan: SpeedPlan?): ReadingBaselineAttempt {
        val key = "baseline-library:attempt:$id"
        val old = entry(key)?.let { codec().decodeFromString<ReadingBaselineAttempt>(it.data) }
            ?: throw IllegalArgumentException("找不到这次自测")
        require(old.id == id)
        if (old.completed) return old
        checkedPlan(requireNotNull(plan))
        ReadingBaselineMetrics.summarize(old, plan)
        if (old.resumedFromHistory) return old
        return old.copy(resumedFromHistory = true).also { put(PreferenceRow(key, codec().encodeToString(it))) }
    }

    private suspend fun checkedPlan(plan: SpeedPlan): SpeedPlanRecord {
        val record = entry("speed-plan:${plan.planKey}")?.let { codec().decodeFromString<SpeedPlanRecord>(it.data) }
            ?: throw IllegalArgumentException("自测原文缓存无法读取")
        require(record.plan == plan && plan.planKey == SpeedChunker.planKey(plan)) { "自测材料已变化" }
        SpeedPlanStorage.validate(record)
        return record
    }

    private suspend fun wasExposed(plan: SpeedPlan, article: Article, articleRows: List<ArticleRow>): Boolean {
        val codec = codec()
        if (article.read || article.captured || entry("reading-exposure:article:${article.id}") != null ||
            entry("reading-exposure:body:${plan.bodyHash}") != null) return true
        if (plan.paragraphIndices.any { entry("speed-exposure:${plan.bodyHash}:$it") != null ||
            entry("baseline-exposure:${plan.bodyHash}:$it") != null }) return true
        val aliases = mutableSetOf(plan.articleId)
        for (bodyRow in bodies()) {
            val body = runCatching { codec.decodeFromString<ArticleBody>(bodyRow.data) }.getOrNull() ?: return true
            if (body.contentHash == plan.bodyHash) aliases += body.articleId
        }
        for (row in articleRows.filter { it.id in aliases }) {
            val alias = runCatching { codec.decodeFromString<Article>(row.data) }.getOrNull() ?: return true
            if (alias.read || alias.captured || entry("reading-exposure:article:${alias.id}") != null) return true
        }
        for (row in captures()) {
            val capture = runCatching { codec.decodeFromString<Capture>(row.data) }.getOrNull() ?: return true
            if (capture.articleId in aliases || capture.bodyHash == plan.bodyHash) return true
        }
        for (row in exposureRows()) {
            val exposed = runCatching {
                when {
                    row.key.startsWith("reading-library:session:") -> codec.decodeFromString<ReadingSession>(row.data).articleId in aliases
                    row.key.startsWith("reading-library:note:") -> codec.decodeFromString<ReadingNote>(row.data).let { it.articleId in aliases || it.bodyHash == plan.bodyHash }
                    row.key.startsWith("baseline-library:attempt:") -> codec.decodeFromString<ReadingBaselineAttempt>(row.data).let {
                        it.bodyHash == plan.bodyHash && it.paragraphs.any { paragraph -> paragraph.paragraph in plan.paragraphIndices }
                    }
                    row.key.startsWith("speed-library:run:") -> codec.decodeFromString<SpeedRun>(row.data).let {
                        it.bodyHash == plan.bodyHash && overlaps(it.planKey, plan)
                    }
                    row.key.startsWith("speed-library:natural:") -> codec.decodeFromString<NaturalReadingAttempt>(row.data).let {
                        it.bodyHash == plan.bodyHash && it.paragraphs.any { paragraph -> paragraph.paragraph in plan.paragraphIndices }
                    }
                    row.key.startsWith("speed-library:correction:") -> codec.decodeFromString<SpeedCorrection>(row.data).let {
                        it.bodyHash == plan.bodyHash && overlaps(it.planKey, plan)
                    }
                    else -> false
                }
            }.getOrDefault(true)
            if (exposed) return true
        }
        return false
    }

    private suspend fun overlaps(planKey: String, current: SpeedPlan): Boolean {
        val previous = entry("speed-plan:$planKey")?.let { codec().decodeFromString<SpeedPlanRecord>(it.data).plan } ?: return true
        return previous.paragraphIndices.any { it in current.paragraphIndices }
    }
    private fun codec() = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}
