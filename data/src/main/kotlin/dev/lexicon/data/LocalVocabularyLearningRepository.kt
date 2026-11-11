package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** Explicit, durable vocabulary work. Observation and restart never start a model request. */
class LocalVocabularyLearningRepository(
    private val db: LexiconDatabase,
    private val analyze: suspend (Capture) -> AnalysisResult,
    private val saveCard: suspend (Capture, AnalysisResult) -> Card,
    private val hasKey: suspend () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : VocabularyLearningRepository {
    private val dao = db.vocabularyDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override val library: Flow<VocabularyLibrary> = dao.observeRows().distinctUntilChanged().map { rows ->
        val familiar = rows.filter { it.key.startsWith(FAMILIAR_PREFIX) }.map(::decodeFamiliar)
        val batches = rows.filter { it.key.startsWith(BATCH_PREFIX) }.map { row ->
            val batch = decodeBatch(row)
            if (ActiveRun(db, batch.id) in activeRuns) batch else batch.copy(items = batch.items.map { item ->
                if (item.status == VocabularyItemStatus.RUNNING) item.copy(status = VocabularyItemStatus.INTERRUPTED, errorCode = "process_interrupted") else item
            })
        }.sortedByDescending { it.createdAt }
        if (familiar.size + batches.size != rows.size) throw damaged()
        VocabularyLibrary(familiar.sortedBy { it.key }, batches)
    }.flowOn(Dispatchers.IO)

    override suspend fun prepareBatch(article: Article, body: ArticleBody, study: ArticleStudy, candidateIds: List<String>): VocabularyBatch = withContext(Dispatchers.IO) {
        val articleRow = dao.article(article.id) ?: throw ApiFailure("snapshot_mismatch", "文章记录已变化，请重新打开")
        val storedArticle = decode<Article>(articleRow.data)
        val bodyRow = dao.body(article.id) ?: throw ApiFailure("snapshot_mismatch", "正文缓存已过期，请重新打开文章")
        val storedBody = decode<ArticleBody>(bodyRow.data)
        require(bodyRow.expiresAt > clock() && storedBody.expiresAt > clock() && storedBody == body &&
            storedArticle.id == article.id && SourceRef(storedArticle.title, storedArticle.sourceName, storedArticle.url, storedArticle.publishedAt) ==
            SourceRef(article.title, article.sourceName, article.url, article.publishedAt)) { "正文或来源已变化，请重新打开重点列表" }
        val sources = storedArticle.sourceIds.distinct().mapNotNull { dao.source(it) }
        if (sources.none { decode<Source>(it.data).aiAllowed }) throw ApiFailure("source_restricted", "此来源未开启 AI 制卡")
        val studyRow = dao.entry(ReadingStudyScopes.cacheKey(article.id, body.contentHash, study.schemaVersion, study.scope))
            ?: throw ApiFailure("snapshot_mismatch", "阅读辅助已变化，请重新打开重点列表")
        require(decode<ArticleStudy>(studyRow.data) == study && study.schemaVersion in setOf(1, 2)) { "阅读辅助已变化，请重新打开重点列表" }
        study.scope?.let { ReadingStudyScopes.validateStudy(study, storedBody) }
        val selected = VocabularyLearning.selected(storedArticle, storedBody, study, candidateIds, true)
        val id = VocabularyLearning.batchId(selected)
        val row = dao.prepare(batchKey(id), articleRow, bodyRow, studyRow, sources, selected, clock())
        decodeBatch(row)
    }

    override suspend fun setFamiliar(term: String, familiar: Boolean) = withContext(Dispatchers.IO) {
        val label = term.trim()
        val key = VocabularyLearning.termKey(label)
        require(label.isNotBlank() && label.length <= 120 && key.length <= 160 && label.none { it.isISOControl() }) { "词或短语无效" }
        val rowKey = FAMILIAR_PREFIX + sha256(key)
        // Refuse to silently overwrite a corrupt decision. The original bytes remain available for repair.
        dao.entry(rowKey)?.let(::decodeFamiliar)
        if (familiar) dao.put(PreferenceRow(rowKey, json.encodeToString(FamiliarTerm(key, label, clock())))) else dao.delete(rowKey)
    }

    override suspend fun runBatch(id: String) = withContext(Dispatchers.IO) {
        if (!runLock.tryLock()) throw ApiFailure("queue_running", "已有制卡队列正在运行")
        try {
            var batch = readBatch(id)
            val run = ActiveRun(db, id)
            activeRuns.add(run)
            try {
                for (index in batch.items.indices) {
                    ensureActive()
                    batch = readBatch(id)
                    var item = batch.items[index]
                    if (item.status == VocabularyItemStatus.COMPLETED) continue
                    try {
                        val capture = item.candidate.capture
                        val current = dao.capture(capture.id)?.let { decode<Capture>(it.data) }
                            ?: throw ApiFailure("capture_changed", "捕获记录不可用，请先核对原句")
                        if (!VocabularyLearning.sameCapture(current, capture)) throw ApiFailure("capture_changed", "捕获已被编辑，请在捕获面板确认后制卡")
                        // A card committed just before interruption is authoritative, even without a queue completion write.
                        val existing = dao.cardByCapture(capture.id)
                        if (existing != null) {
                            val card = decode<Card>(existing.data)
                            if (!VocabularyCardInput.matches(card, capture, dao.entry("card-input:${capture.id}")?.data, dao.entry("card-analysis-input:${capture.id}")?.data))
                                throw ApiFailure("capture_changed", "已有卡片语境与队列材料不同，请先核对")
                            batch = replace(batch, index, item.copy(status = VocabularyItemStatus.COMPLETED, cardId = existing.id, errorCode = null))
                            continue
                        }
                        if (dao.entry(FAMILIAR_PREFIX + sha256(item.candidate.termKey))?.let(::decodeFamiliar) != null) {
                            batch = replace(batch, index, item.copy(status = VocabularyItemStatus.SKIPPED_FAMILIAR, errorCode = null))
                            continue
                        }
                        if (item.content == null) {
                            // Overlapping explicit selections can share one exact captured occurrence.
                            // Reuse its already validated result before considering another model call.
                            val prior = cachedAnalysis(capture)
                            if (prior != null) {
                                item = item.copy(content = prior.content, model = prior.model)
                                batch = replace(batch, index, item)
                            }
                        }
                        if (!sourceAllowed(batch.articleId)) throw ApiFailure("source_restricted", "此来源已关闭 AI，材料已保留")
                        if (item.content == null && !hasKey()) throw ApiFailure("missing_key", "请先配置 DeepSeek API Key，材料已保留")
                        item = item.copy(status = VocabularyItemStatus.RUNNING, errorCode = null)
                        batch = replace(batch, index, item)
                        val existingContent = item.content
                        val result = if (existingContent != null) AnalysisResult(existingContent, requireNotNull(item.model)) else {
                            val generated = analyze(capture)
                            ContentValidator.requireValid(generated.content)
                            require(validModel(generated.model))
                            item = item.copy(content = generated.content, model = generated.model)
                            // Persist validated analysis before local save so a failed save does not require another paid request.
                            batch = replace(batch, index, item)
                            generated
                        }
                        ensureActive()
                        val latest = dao.capture(capture.id)?.let { decode<Capture>(it.data) }
                        if (latest == null || !VocabularyLearning.sameCapture(latest, capture))
                            throw ApiFailure("capture_changed", "捕获已被编辑，请在捕获面板确认后制卡")
                        if (!sourceAllowed(batch.articleId)) throw ApiFailure("source_restricted", "此来源已关闭 AI，分析结果已保留")
                        if (dao.entry(FAMILIAR_PREFIX + sha256(item.candidate.termKey))?.let(::decodeFamiliar) != null) {
                            batch = replace(batch, index, item.copy(status = VocabularyItemStatus.SKIPPED_FAMILIAR, errorCode = null))
                            continue
                        }
                        val card = saveCard(capture, result)
                        require(card.captureId == capture.id && card.sourceSentence == capture.sentence && card.sourceRef == capture.sourceRef)
                        batch = replace(batch, index, item.copy(status = VocabularyItemStatus.COMPLETED, cardId = card.id, errorCode = null))
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) {
                            val latest = readBatch(id)
                            if (latest.items[index].status != VocabularyItemStatus.COMPLETED)
                                replace(latest, index, latest.items[index].copy(status = VocabularyItemStatus.INTERRUPTED, cardId = null, errorCode = "interrupted"))
                        }
                        throw cancelled
                    } catch (failure: Exception) {
                        val code = (failure as? ApiFailure)?.code?.takeIf { it in ERROR_CODES } ?: "queue_failed"
                        val latest = readBatch(id)
                        replace(latest, index, latest.items[index].copy(status = VocabularyItemStatus.FAILED, errorCode = code))
                        throw ApiFailure(code, message(code))
                    }
                }
            } finally { activeRuns.remove(run) }
        } finally { runLock.unlock() }
    }

    /** Ordinary and queued captures share the same local-only receipt lookup before any key check. */
    internal suspend fun cachedAnalysis(capture: Capture): AnalysisResult? =
        (CaptureAnalysisStore(db.captureAnalysisDao(), dao).available(capture) as? CaptureAnalysisAvailability.Ready)?.analysis

    private suspend fun sourceAllowed(articleId: String): Boolean {
        val article = dao.article(articleId)?.let { decode<Article>(it.data) } ?: return false
        return article.sourceIds.distinct().mapNotNull { dao.source(it) }.any { decode<Source>(it.data).aiAllowed }
    }

    private suspend fun readBatch(id: String): VocabularyBatch = dao.entry(batchKey(id))?.let(::decodeBatch)
        ?: throw ApiFailure("queue_missing", "制卡队列不可用，请重新选择重点词")
    private suspend fun replace(batch: VocabularyBatch, index: Int, item: VocabularyQueueItem): VocabularyBatch {
        val updated = batch.copy(items = batch.items.toMutableList().also { it[index] = item }, updatedAt = clock())
        val row = PreferenceRow(batchKey(batch.id), json.encodeToString(updated))
        decodeBatch(row)
        dao.put(row)
        return updated
    }

    private fun decodeFamiliar(row: PreferenceRow): FamiliarTerm = guard {
        decode<FamiliarTerm>(row.data).also {
            require(it.key == VocabularyLearning.termKey(it.label) && it.key.isNotBlank() && it.key.length <= 160 &&
                it.label.length <= 120 && it.label.none(Char::isISOControl) && it.updatedAt >= 0 && row.key == FAMILIAR_PREFIX + sha256(it.key))
        }
    }
    private fun decodeBatch(row: PreferenceRow): VocabularyBatch = guard {
        val batch = decode<VocabularyBatch>(row.data)
        require(batch.items.size in 1..18 && batch.items.map { it.candidate.id }.distinct().size == batch.items.size &&
            batch.id == VocabularyLearning.batchId(batch.items.map { it.candidate }) && row.key == batchKey(batch.id) &&
            batch.articleId.isNotBlank() && batch.articleId.length <= 256 && HEX.matches(batch.bodyHash) && batch.createdAt >= 0 && batch.updatedAt >= batch.createdAt)
        batch.items.forEach { item ->
            val candidate = item.candidate
            VocabularyLearning.requireValid(candidate)
            val capture = candidate.capture
            val start = capture.selectionStart
            val end = capture.selectionEnd
            require(HEX.matches(candidate.id) && candidate.highlightId.isNotBlank() && candidate.highlightId.length <= 200 &&
                candidate.termKey == VocabularyLearning.termKey(capture.target) && candidate.explanation.length <= 700 && candidate.exam in setOf("ielts", "toefl", "both"))
            require(capture.id.isNotBlank() && capture.id.length <= 256 && capture.articleId == batch.articleId && capture.bodyHash == batch.bodyHash &&
                capture.sourceRef == batch.source && capture.contextOrigin == ContextOrigin.ORIGINAL &&
                start != null && end != null && start >= 0 && end > start &&
                end - start == capture.selectedText.length && capture.sentence.contains(capture.selectedText) &&
                ContentValidator.validateCapture(capture.copy(aiAllowed = true)).isEmpty())
            val identity = sha256(listOf(batch.articleId, batch.bodyHash, capture.selectionStart.toString(), capture.selectionEnd.toString(), candidate.termKey).joinToString("\u0000"))
            require(candidate.id == identity)
            require((item.content == null) == (item.model == null))
            item.content?.let(ContentValidator::requireValid)
            item.model?.let { require(validModel(it)) }
            item.errorCode?.let { require(it in ERROR_CODES) }
            require(item.status != VocabularyItemStatus.COMPLETED || !item.cardId.isNullOrBlank())
            require(item.status == VocabularyItemStatus.COMPLETED || item.cardId == null)
            item.cardId?.let { require(it.length <= 256 && it.none(Char::isISOControl)) }
        }
        batch
    }
    private inline fun <reified T> decode(raw: String): T = json.decodeFromString(raw)
    private inline fun <T> guard(block: () -> T): T = try { block() } catch (_: Exception) { throw damaged() }
    private fun validModel(model: String) = model.isNotBlank() && model.length <= 200 && model.none(Char::isISOControl)
    private fun damaged() = ApiFailure("queue_damaged", "制卡记录无法读取，原始数据已保留")
    private fun message(code: String) = when (code) {
        "missing_key" -> "请先配置 DeepSeek API Key，再继续队列"
        "authentication", "quota", "ai_paused" -> "请检查 API Key 或额度，再继续队列"
        "source_restricted" -> "此来源已关闭 AI，队列材料已保留"
        "capture_changed" -> "捕获已被编辑，请在捕获面板核对后制卡"
        "invalid_schema" -> "本项结果未通过校验，成功项已保留"
        "rate_limit" -> "请求受限，请稍后继续队列"
        else -> "本项未完成，已生成的卡片和分析已保留"
    }
    private fun batchKey(id: String) = BATCH_PREFIX + id
    private data class ActiveRun(val db: LexiconDatabase, val id: String)
    companion object {
        internal const val BATCH_PREFIX = "vocabulary-library:batch:v1:"
        internal const val FAMILIAR_PREFIX = "vocabulary-library:familiar:v1:"
        private val runLock = Mutex()
        private val activeRuns = ConcurrentHashMap.newKeySet<ActiveRun>()
        private val HEX = Regex("[a-f0-9]{64}")
        private val ERROR_CODES = setOf("queue_failed", "queue_damaged", "queue_missing", "queue_running", "snapshot_mismatch", "capture_changed", "source_restricted", "missing_key", "authentication", "quota", "ai_paused", "rate_limit", "network", "invalid_schema", "incomplete", "empty_response", "invalid_response", "interrupted", "process_interrupted")
    }
}
