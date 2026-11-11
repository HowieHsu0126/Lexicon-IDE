package dev.lexicon.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Explicit reader feedback. Familiarity is not inferred from FSRS repetitions or exam scores. */
@Serializable data class FamiliarTerm(val key: String, val label: String, val updatedAt: Long)
@Serializable enum class VocabularyItemStatus { PENDING, RUNNING, COMPLETED, FAILED, INTERRUPTED, SKIPPED_FAMILIAR }
@Serializable data class VocabularyCandidate(
    val id: String, val highlightId: String, val termKey: String, val capture: Capture,
    val explanation: String, val exam: String,
)
@Serializable data class VocabularyQueueItem(
    val candidate: VocabularyCandidate, val status: VocabularyItemStatus = VocabularyItemStatus.PENDING,
    val cardId: String? = null, val errorCode: String? = null,
    /** A validated result survives an interrupted local card save; it is bound to this immutable capture. */
    val content: VocabularyContent? = null, val model: String? = null,
)
@Serializable data class VocabularyBatch(
    val id: String, val articleId: String, val bodyHash: String, val source: SourceRef,
    val items: List<VocabularyQueueItem>, val createdAt: Long, val updatedAt: Long = createdAt,
)
@Serializable data class VocabularyLibrary(
    val familiar: List<FamiliarTerm> = emptyList(), val batches: List<VocabularyBatch> = emptyList(),
)

interface VocabularyLearningRepository {
    val library: Flow<VocabularyLibrary>
    /** Stores frozen candidates/captures only. No model call occurs until runBatch is explicitly invoked. */
    suspend fun prepareBatch(article: Article, body: ArticleBody, study: ArticleStudy, candidateIds: List<String>): VocabularyBatch
    /** Serial, bounded, explicit run. Stops on a failure; completed cards are never regenerated. */
    suspend fun runBatch(id: String)
    /** Only display familiarity changes. Existing FSRS cards and their due times stay intact. */
    suspend fun setFamiliar(term: String, familiar: Boolean)
}

object VocabularyLearning {
    fun termKey(term: String): String = Normalizer.normalize(term, Normalizer.Form.NFKC)
        .replace('’', '\'').replace('‘', '\'').trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    fun captureId(articleId: String, bodyHash: String, start: Int, end: Int, target: String): String = "highlight:" +
        digest(listOf(articleId, bodyHash, minOf(start, end).toString(), maxOf(start, end).toString(), termKey(target)).joinToString("\u0000"))

    fun candidates(article: Article, body: ArticleBody, study: ArticleStudy, aiAllowed: Boolean): List<VocabularyCandidate> {
        require(body.articleId == article.id && study.articleId == article.id && study.bodyHash == body.contentHash &&
            digest(body.text) == body.contentHash) { "正文已变化，请重新打开重点列表" }
        require(study.highlights.size <= 18 && study.highlights.map { it.id }.distinct().size == study.highlights.size)
        return study.highlights.filter { it.kind in setOf("vocabulary", "collocation") }.mapNotNull { h ->
            if (h.start < 0 || h.end <= h.start || h.end > body.text.length || body.text.substring(h.start, h.end) != h.quote) return@mapNotNull null
            val selected = runCatching { TextCapture.extract(body.text, h.start, h.end) }.getOrNull() ?: return@mapNotNull null
            val target = selected.selectedText.trim()
            // Longer quotations remain individually selectable in the reader, not silently truncated into words.
            if (target.isBlank() || target.length > 120 || selected.sentence.length > 4_000) return@mapNotNull null
            val key = termKey(target)
            val captureId = captureId(article.id, body.contentHash, selected.start, selected.end, target)
            val identity = captureId.removePrefix("highlight:")
            val capture = Capture(captureId, article.id, target, selected.selectedText, selected.sentence,
                ContextOrigin.ORIGINAL, SourceRef(article.title, article.sourceName, article.url, article.publishedAt),
                selected.start, selected.end, body.contentHash, study.createdAt, aiAllowed = aiAllowed)
            VocabularyCandidate(identity, h.id, key, capture, h.explanation, h.exam)
        }.distinctBy { it.id }
    }

    fun selected(article: Article, body: ArticleBody, study: ArticleStudy, ids: List<String>, aiAllowed: Boolean): List<VocabularyCandidate> {
        require(ids.size in 1..18 && ids.distinct().size == ids.size) { "请选择 1–18 个不同的词或短语" }
        val candidates = candidates(article, body, study, aiAllowed).associateBy { it.id }
        return ids.map { requireNotNull(candidates[it]) { "重点选区已变化，请重新选择" } }.also { values ->
            values.forEach { ContentValidator.validateCapture(it.capture).also { errors -> require(errors.isEmpty()) { errors.first() } } }
        }
    }

    fun batchId(candidates: List<VocabularyCandidate>): String {
        require(candidates.isNotEmpty())
        return "vocab-batch:" + digest(candidates.map { it.id }.sorted().joinToString("\u0000"))
    }

    fun requireValid(candidate: VocabularyCandidate) {
        val c = candidate.capture
        require(!c.articleId.isNullOrBlank() && c.bodyHash?.matches(Regex("[a-f0-9]{64}")) == true)
        val start = requireNotNull(c.selectionStart)
        val end = requireNotNull(c.selectionEnd)
        require(start >= 0 && end > start && end - start == c.selectedText.length)
        require(c.contextOrigin == ContextOrigin.ORIGINAL && termKey(c.target) == termKey(c.selectedText) && c.sentence.contains(c.selectedText))
        require(ContentValidator.validateCapture(c.copy(aiAllowed = true)).isEmpty())
        require(candidate.termKey == termKey(c.target))
        require(candidate.id == digest(listOf(c.articleId, c.bodyHash, start.toString(), end.toString(), candidate.termKey).joinToString("\u0000")))
        require(candidate.highlightId.isNotBlank() && candidate.explanation.length <= 700 && candidate.exam in setOf("ielts", "toefl", "both"))
    }

    fun isFamiliar(term: String, familiar: List<FamiliarTerm>): Boolean = termKey(term).let { key -> familiar.any { it.key == key } }
    /** Same exact occurrence and confirmed context only; same word in another sentence remains distinct. */
    fun sameCapture(left: Capture, right: Capture): Boolean = left.articleId == right.articleId && left.bodyHash == right.bodyHash &&
        left.selectionStart == right.selectionStart && left.selectionEnd == right.selectionEnd &&
        left.contextOrigin == ContextOrigin.ORIGINAL && right.contextOrigin == ContextOrigin.ORIGINAL &&
        left.selectedText == right.selectedText && left.sentence == right.sentence && termKey(left.target) == termKey(right.target) && left.sourceRef == right.sourceRef

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
