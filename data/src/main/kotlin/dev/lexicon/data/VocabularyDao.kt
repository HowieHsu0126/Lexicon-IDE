package dev.lexicon.data

import androidx.room3.*
import dev.lexicon.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Shared identity rules keep saved-card reuse and atomic card commits in agreement. */
internal object VocabularyCardInput {
    private val codec = Json { encodeDefaults = true }
    fun signature(capture: Capture) = sha256(capture.id + "\u0000" + capture.target + "\u0000" + capture.selectedText + "\u0000" + capture.sentence +
        "\u0000" + capture.articleId + "\u0000" + codec.encodeToString(capture.sourceRef) + "\u0000" + capture.contextOrigin)
    /** Analysis caches include precise source identity; the persisted legacy card signature is unchanged. */
    fun analysisSignature(capture: Capture) = sha256(signature(capture) + "\u0000" + capture.bodyHash + "\u0000" +
        capture.selectionStart + "\u0000" + capture.selectionEnd)
    fun sameInput(left: Capture, right: Capture) = signature(left) == signature(right) && left.bodyHash == right.bodyHash &&
        left.selectionStart == right.selectionStart && left.selectionEnd == right.selectionEnd
    fun matches(card: Card, capture: Capture, recordedSignature: String?, recordedAnalysisSignature: String? = null): Boolean {
        if (recordedAnalysisSignature != null && recordedAnalysisSignature != analysisSignature(capture)) return false
        if (card.captureId != capture.id || card.sourceSentence != capture.sentence || card.sourceRef != capture.sourceRef || card.contextOrigin != capture.contextOrigin) return false
        return if (recordedSignature != null) recordedSignature == signature(capture)
        else VocabularyLearning.termKey(card.content.headword) == VocabularyLearning.termKey(capture.target)
    }
}

/** Queue rows reuse the preferences table; a queue preparation never holds a transaction over a model call. */
@Dao abstract class VocabularyDao {
    @Query("SELECT * FROM preferences WHERE `key` LIKE 'vocabulary-library:%' ORDER BY `key`")
    abstract fun observeRows(): Flow<List<PreferenceRow>>
    @Query("SELECT * FROM preferences WHERE `key` LIKE 'vocabulary-library:%' ORDER BY `key`")
    abstract suspend fun rows(): List<PreferenceRow>
    @Query("SELECT * FROM preferences WHERE `key`=:key") abstract suspend fun entry(key: String): PreferenceRow?
    @Upsert abstract suspend fun put(row: PreferenceRow)
    @Query("DELETE FROM preferences WHERE `key`=:key") abstract suspend fun delete(key: String)
    @Query("SELECT * FROM articles WHERE id=:id") abstract suspend fun article(id: String): ArticleRow?
    @Query("SELECT * FROM article_bodies WHERE articleId=:id") abstract suspend fun body(id: String): BodyRow?
    @Query("SELECT * FROM sources WHERE id=:id") abstract suspend fun source(id: String): SourceRow?
    @Query("SELECT * FROM captures ORDER BY createdAt, id") abstract suspend fun captures(): List<CaptureRow>
    @Query("SELECT * FROM captures WHERE id=:id") abstract suspend fun capture(id: String): CaptureRow?
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertCapture(row: CaptureRow): Long
    @Query("SELECT * FROM cards WHERE captureId=:id") abstract suspend fun cardByCapture(id: String): CardRow?
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertCard(row: CardRow): Long
    @Insert abstract suspend fun insertEvent(row: EventRow)
    @Upsert abstract suspend fun putArticle(row: ArticleRow)

    /** Input comparison, card insertion, identity metadata and capture creation are one writer transaction. */
    @Transaction open suspend fun commitCard(capture: Capture, proposed: Card): Card {
        val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        require(capture.target.length <= CaptureLimits.TARGET && capture.selectedText.length <= CaptureLimits.SELECTED_TEXT &&
            capture.sentence.length <= CaptureLimits.SENTENCE && capture.sentence.isNotBlank() &&
            (capture.target.isNotBlank() || capture.selectedText.isNotBlank())) { "请核对目标词和原句" }
        require(proposed.captureId == capture.id && proposed.sourceSentence == capture.sentence && proposed.sourceRef == capture.sourceRef && proposed.contextOrigin == capture.contextOrigin)
        ContentValidator.requireValid(proposed.content)
        val existingCapture = this.capture(capture.id)
        if (existingCapture != null && !VocabularyCardInput.sameInput(codec.decodeFromString<Capture>(existingCapture.data), capture))
            throw ApiFailure("capture_changed", "捕获已被编辑，原分析已保留，请先核对")
        cardByCapture(capture.id)?.let { row ->
            val existing = codec.decodeFromString<Card>(row.data)
            if (!VocabularyCardInput.matches(existing, capture, entry("card-input:${capture.id}")?.data, entry("card-analysis-input:${capture.id}")?.data))
                throw ApiFailure("capture_changed", "此捕获已有不同内容的卡片，请编辑卡片或新建捕获")
            return existing
        }
        val articleRow = capture.articleId?.let { article(it) }
        val associated = articleRow?.let { codec.decodeFromString<Article>(it.data) }
        val allowed = associated?.sourceIds?.distinct()?.mapNotNull { source(it) }?.any { codec.decodeFromString<Source>(it.data).aiAllowed }
            ?: capture.aiAllowed
        if (!allowed) throw ApiFailure("source_restricted", "此来源已关闭 AI，分析结果已保留")
        // Existing raw capture metadata remains untouched. A later user edit is a separate transaction.
        if (existingCapture == null) {
            val savedCapture = capture.copy(aiAllowed = allowed)
            check(insertCapture(CaptureRow(savedCapture.id, savedCapture.createdAt, codec.encodeToString(savedCapture))) != -1L)
        }
        val card = proposed.copy(topic = associated?.topic)
        check(insertCard(CardRow(card.id, card.captureId, card.state.dueAt, card.state.reps, card.updatedAt, codec.encodeToString(card))) != -1L)
        put(PreferenceRow("card-input:${capture.id}", VocabularyCardInput.signature(capture)))
        put(PreferenceRow("card-analysis-input:${capture.id}", VocabularyCardInput.analysisSignature(capture)))
        if (articleRow != null && associated != null && !associated.captured)
            putArticle(articleRow.copy(data = codec.encodeToString(associated.copy(captured = true))))
        if (existingCapture == null) {
            val event = LocalEvent(UUID.randomUUID().toString(), "capture_saved", card.createdAt)
            insertEvent(EventRow(event.id, event.createdAt, codec.encodeToString(event)))
        }
        val event = LocalEvent(UUID.randomUUID().toString(), "card_saved", card.createdAt)
        insertEvent(EventRow(event.id, event.createdAt, codec.encodeToString(event)))
        return card
    }

    /** Recheck the read snapshot inside the committing transaction; never overwrite an edited capture. */
    @Transaction open suspend fun prepare(
        key: String, expectedArticle: ArticleRow, expectedBody: BodyRow, expectedStudy: PreferenceRow,
        expectedSources: List<SourceRow>, candidates: List<VocabularyCandidate>, now: Long,
    ): PreferenceRow {
        // Another identical preparation may have marked the article captured already. Reusing its
        // immutable row is side-effect free; the repository still validates its full frozen contract.
        entry(key)?.let { return it }
        require(article(expectedArticle.id) == expectedArticle && body(expectedBody.articleId) == expectedBody &&
            entry(expectedStudy.key) == expectedStudy && expectedSources.all { source(it.id) == it }) { "正文或来源已变化，请重新打开重点列表" }
        val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val all = captures().map { row -> codec.decodeFromString<Capture>(row.data) }
        val items = candidates.map { candidate ->
            val intended = candidate.capture
            val occupied = all.firstOrNull { it.id == intended.id }
            if (occupied != null && !VocabularyLearning.sameCapture(occupied, intended))
                throw ApiFailure("capture_changed", "这个捕获已被编辑，请在捕获面板确认后制卡")
            val matches = all.filter { VocabularyLearning.sameCapture(it, intended) }
            var previous: Capture? = occupied
            for (match in matches) {
                if (cardByCapture(match.id) != null) { previous = match; break }
                if (previous == null) previous = match
            }
            val frozen = candidate.copy(capture = previous ?: intended)
            if (previous == null) {
                check(insertCapture(CaptureRow(intended.id, intended.createdAt, codec.encodeToString(intended))) != -1L)
            }
            val card = cardByCapture(frozen.capture.id)
            card?.let { row ->
                val existing = codec.decodeFromString<Card>(row.data)
                if (!VocabularyCardInput.matches(existing, frozen.capture, entry("card-input:${frozen.capture.id}")?.data, entry("card-analysis-input:${frozen.capture.id}")?.data))
                    throw ApiFailure("capture_changed", "已有卡片语境与重点材料不同，请先核对")
            }
            VocabularyQueueItem(frozen, if (card == null) VocabularyItemStatus.PENDING else VocabularyItemStatus.COMPLETED,
                cardId = card?.id)
        }
        val article = codec.decodeFromString<Article>(expectedArticle.data)
        val batch = VocabularyBatch(VocabularyLearning.batchId(candidates), article.id, candidates.first().capture.bodyHash!!,
            candidates.first().capture.sourceRef, items, now)
        return PreferenceRow(key, codec.encodeToString(batch)).also {
            put(it)
            putArticle(expectedArticle.copy(data = codec.encodeToString(article.copy(captured = true))))
        }
    }
}
