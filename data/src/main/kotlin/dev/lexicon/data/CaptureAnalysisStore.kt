package dev.lexicon.data

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import dev.lexicon.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Validated output survives closing the capture panel and process recreation. */
@Serializable internal data class StoredCaptureAnalysis(
    val version: Int = 1, val receiptId: String, val input: Capture,
    val content: VocabularyContent, val model: String, val deep: Boolean, val createdAt: Long,
)

/** No key or network dependency: both lookup and committing a receipt are entirely local. */
class CaptureAnalysisStore(
    private val dao: CaptureAnalysisDao,
    private val cards: VocabularyDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun available(capture: Capture): CaptureAnalysisAvailability = withContext(Dispatchers.IO) {
        dao.available(capture)
    }

    /** Call after schema validation and before publishing a completed AI job. */
    suspend fun persist(capture: Capture, result: AnalysisResult, deep: Boolean): String = withContext(Dispatchers.IO) {
        dao.persist(capture, result, deep, clock())
    }

    suspend fun save(capture: Capture, receiptId: String): Card = withContext(Dispatchers.IO) {
        dao.commit(capture, receiptId, clock(), cards)
    }
}

/** These methods use the same database as VocabularyDao so nested commits share one transaction. */
@Dao abstract class CaptureAnalysisDao {
    @Query("SELECT * FROM preferences WHERE `key`=:key") abstract suspend fun entry(key: String): PreferenceRow?
    @Query("SELECT * FROM preferences WHERE `key` LIKE 'vocabulary-library:batch:v1:%' ORDER BY `key`")
    abstract suspend fun batches(): List<PreferenceRow>
    @Query("SELECT * FROM captures WHERE id=:id") abstract suspend fun capture(id: String): CaptureRow?
    @Query("SELECT * FROM cards WHERE captureId=:id") abstract suspend fun cardByCapture(id: String): CardRow?
    @Query("SELECT * FROM articles WHERE id=:id") abstract suspend fun article(id: String): ArticleRow?
    @Query("SELECT * FROM sources WHERE id=:id") abstract suspend fun source(id: String): SourceRow?
    @Upsert abstract suspend fun put(row: PreferenceRow)

    @Transaction open suspend fun available(input: Capture): CaptureAnalysisAvailability {
        val current = capture(input.id)?.let { decode<Capture>(it.data) }
        val same = current == null || CaptureAnalysisIdentity.sameInput(current, input)
        cardByCapture(input.id)?.let { row ->
            val saved = decode<Card>(row.data)
            val fullSignature = entry("card-analysis-input:${input.id}")?.data
            val exact = if (fullSignature != null) fullSignature == VocabularyCardInput.analysisSignature(input)
                else if (input.bodyHash == null && input.selectionStart == null && input.selectionEnd == null) true
                else completedQueueProof(saved, input)
            return CaptureAnalysisAvailability.ExistingCard(saved,
                current != null && same && exact && VocabularyCardInput.matches(saved, input, entry("card-input:${input.id}")?.data))
        }
        if (!same) return CaptureAnalysisAvailability.Conflict
        if (current == null || ContentValidator.validateCapture(input.copy(aiAllowed = true)).isNotEmpty())
            return CaptureAnalysisAvailability.Missing
        if (!sourceAllowed(current)) return CaptureAnalysisAvailability.Restricted
        val records = listOf(false, true).mapNotNull { deep -> entry(key(input, deep))?.let(::readRecord) }
        records.maxWithOrNull(compareBy<StoredCaptureAnalysis> { it.createdAt }.thenBy { it.deep })?.let { saved ->
            check(CaptureAnalysisIdentity.sameInput(saved.input, input))
            return CaptureAnalysisAvailability.Ready(AnalysisResult(saved.content, saved.model,
                VocabularyCardInput.analysisSignature(input)), saved.receiptId, saved.deep)
        }
        // The 1.8 queue already persisted exact frozen inputs. Read it without promoting or changing rows.
        return queued(input) ?: CaptureAnalysisAvailability.Missing
    }

    @Transaction open suspend fun persist(input: Capture, result: AnalysisResult, deep: Boolean, now: Long): String {
        validateInput(input)
        ContentValidator.requireValid(result.content)
        require(result.inputSignature == null || result.inputSignature == VocabularyCardInput.analysisSignature(input))
        require(validModel(result.model) && now >= 0)
        val key = key(input, deep)
        // Do not silently replace unreadable user data with a later model result.
        entry(key)?.let(::readRecord)
        val saved = StoredCaptureAnalysis(receiptId = receiptId(input, result.content, result.model, deep), input = input,
            content = result.content, model = result.model, deep = deep, createdAt = now)
        val row = PreferenceRow(key, codec.encodeToString(saved))
        readRecord(row)
        put(row)
        return saved.receiptId
    }

    /** Re-probe and commit on the writer transaction; never upsert a stale capture before comparing. */
    @Transaction open suspend fun commit(input: Capture, expectedReceipt: String, now: Long, cards: VocabularyDao): Card {
        require(expectedReceipt.matches(HEX) && now >= 0) { "分析记录无效，请重新打开捕获" }
        val result = when (val value = available(input)) {
            is CaptureAnalysisAvailability.ExistingCard -> {
                if (value.matchesInput) return value.card
                throw changed()
            }
            is CaptureAnalysisAvailability.Ready -> {
                if (value.receiptId != expectedReceipt) throw ApiFailure("analysis_changed", "已有分析已更新，请查看后再保存")
                value.analysis
            }
            CaptureAnalysisAvailability.Restricted -> throw ApiFailure("source_restricted", "此来源已关闭 AI，分析结果已保留")
            CaptureAnalysisAvailability.Conflict -> throw changed()
            CaptureAnalysisAvailability.Missing -> throw ApiFailure("analysis_missing", "没有可保存的本地分析，请先核对原句")
        }
        val id = UUID.nameUUIDFromBytes("vocab:${input.id}".toByteArray()).toString()
        val proposed = Card(id, input.id, result.content, input.sentence, input.sourceRef,
            state = FsrsState(dueAt = now), createdAt = now, updatedAt = now, contextOrigin = input.contextOrigin)
        return cards.commitCard(input, proposed)
    }

    private suspend fun sourceAllowed(input: Capture): Boolean {
        val id = input.articleId ?: return input.aiAllowed
        val current = article(id)?.let { decode<Article>(it.data) } ?: return false
        return current.sourceIds.distinct().mapNotNull { source(it) }.any { decode<Source>(it.data).aiAllowed }
    }

    private suspend fun queued(input: Capture): CaptureAnalysisAvailability.Ready? {
        if (input.contextOrigin != ContextOrigin.ORIGINAL || input.articleId == null || input.bodyHash == null ||
            input.selectionStart == null || input.selectionEnd == null) return null
        val matches = batches().map(::readBatch).flatMap { batch ->
            batch.items.mapNotNull { item ->
                val content = item.content
                val model = item.model
                if (content == null || model == null || !CaptureAnalysisIdentity.sameInput(item.candidate.capture, input)) null
                else Triple(batch, item, AnalysisResult(content, model, VocabularyCardInput.analysisSignature(input)))
            }
        }
        val (batch, item, result) = matches.maxWithOrNull(compareBy<Triple<VocabularyBatch, VocabularyQueueItem, AnalysisResult>> { it.first.updatedAt }
            .thenBy { it.first.id }) ?: return null
        val id = sha256("queue\u0000${batch.id}\u0000${item.candidate.id}\u0000" +
            receiptId(input, result.content, result.model, false))
        return CaptureAnalysisAvailability.Ready(result, id)
    }

    /** Legacy native cards need a completed, generated queue receipt to establish their original range. */
    private suspend fun completedQueueProof(card: Card, input: Capture): Boolean = batches().any { row ->
        val batch = try { readBatch(row) } catch (_: ApiFailure) { return@any false }
        batch.items.any { item -> item.status == VocabularyItemStatus.COMPLETED && item.cardId == card.id &&
            item.content != null && item.model != null && CaptureAnalysisIdentity.sameInput(item.candidate.capture, input) }
    }

    private fun readRecord(row: PreferenceRow): StoredCaptureAnalysis = guarded {
        require(row.data.length <= 100_000)
        decode<StoredCaptureAnalysis>(row.data).also {
            validateInput(it.input)
            ContentValidator.requireValid(it.content)
            require(it.version == 1 && it.createdAt >= 0 && validModel(it.model) &&
                row.key == key(it.input, it.deep) && it.receiptId == receiptId(it.input, it.content, it.model, it.deep))
        }
    }

    private fun readBatch(row: PreferenceRow): VocabularyBatch = guarded("queue_damaged") {
        val batch = decode<VocabularyBatch>(row.data)
        require(batch.items.size in 1..18 && batch.items.map { it.candidate.id }.distinct().size == batch.items.size &&
            batch.id == VocabularyLearning.batchId(batch.items.map { it.candidate }) &&
            row.key == LocalVocabularyLearningRepository.BATCH_PREFIX + batch.id &&
            batch.articleId.isNotBlank() && batch.articleId.length <= 256 && HEX.matches(batch.bodyHash) &&
            batch.createdAt >= 0 && batch.updatedAt >= batch.createdAt)
        batch.items.forEach { item ->
            val candidate = item.candidate
            VocabularyLearning.requireValid(candidate)
            require(candidate.capture.articleId == batch.articleId && candidate.capture.bodyHash == batch.bodyHash &&
                candidate.capture.sourceRef == batch.source && candidate.highlightId.length <= 200 &&
                candidate.capture.id.length <= 256 && HEX.matches(candidate.id))
            require((item.content == null) == (item.model == null))
            item.content?.let(ContentValidator::requireValid)
            item.model?.let { require(validModel(it)) }
            require(item.status != VocabularyItemStatus.COMPLETED || !item.cardId.isNullOrBlank())
            require(item.status == VocabularyItemStatus.COMPLETED || item.cardId == null)
            item.cardId?.let { require(it.length <= 256 && it.none(Char::isISOControl)) }
        }
        batch
    }

    private inline fun <reified T> decode(raw: String): T = codec.decodeFromString(raw)
    private inline fun <T> guarded(code: String = "analysis_damaged", block: () -> T): T = try { block() } catch (_: Exception) {
        throw ApiFailure(code, "本地分析无法读取，原始数据已保留")
    }
    private fun changed() = ApiFailure("capture_changed", "捕获已被编辑，原分析已保留，请先核对")

    companion object {
        internal const val PREFIX = "capture-analysis:v1:"
        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val HEX = Regex("[a-f0-9]{64}")
        internal fun key(input: Capture, deep: Boolean): String = PREFIX + VocabularyCardInput.analysisSignature(input) + if (deep) ":deep" else ":flash"
        private fun receiptId(input: Capture, content: VocabularyContent, model: String, deep: Boolean) =
            sha256(VocabularyCardInput.analysisSignature(input) + "\u0000" + deep + "\u0000" + model + "\u0000" + codec.encodeToString(content))
        private fun validModel(model: String) = model.isNotBlank() && model.length <= 200 && model.none(Char::isISOControl)
        private fun validateInput(input: Capture) {
            require(input.id.isNotBlank() && input.id.length <= 256 && input.id.none(Char::isISOControl))
            require(ContentValidator.validateCapture(input.copy(aiAllowed = true)).isEmpty())
            input.articleId?.let { require(it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl)) }
            input.bodyHash?.let { require(HEX.matches(it)) }
            require((input.selectionStart == null) == (input.selectionEnd == null))
            input.selectionStart?.let { require(it >= 0 && requireNotNull(input.selectionEnd) > it) }
        }
    }
}
