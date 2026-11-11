package dev.lexicon.domain

import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Explicit reader controls only; neither difficulty nor reading ability is inferred. */
@Serializable
data class ReadingResumeControls(
    val mode: StudyMode = StudyMode.INTENSIVE,
    val quiet: Boolean = false,
    val budgetMinutes: Int = 5,
    val roleFilter: String = "全部",
    val toolTab: String = "导图",
)

/** A reached original paragraph, not a claim about gaze, completion percentage or comprehension. */
@Serializable
data class ReadingResumeRecord(
    val articleId: String,
    val bodyHash: String,
    val paragraphIndex: Int,
    val start: Int,
    val end: Int,
    val controls: ReadingResumeControls,
    val generation: Long,
    val sequence: Long,
    val updatedAt: Long,
    val completed: Boolean = false,
)

/** Assigned by storage. A new generation invalidates delayed writes from previous entries. */
@Serializable
data class ReadingResumeLease(
    val articleId: String,
    val bodyHash: String,
    val generation: Long,
    val previous: ReadingResumeRecord?,
)

@Serializable
data class ReadingResumeLibrary(val records: List<ReadingResumeRecord> = emptyList(), val errors: Int = 0)

@Serializable
enum class ReadingEntryKind { START, CONTINUE, ANCHOR }

/** These operations use local state only and do not write reading sessions or completion events. */
interface ReadingResumeRepository {
    val library: Flow<ReadingResumeLibrary>

    /** Allocate a monotonic writer generation without discarding an older saved paragraph. */
    suspend fun begin(articleId: String, bodyHash: String): ReadingResumeLease

    /**
     * Storage revalidates the actual original body and current generation, then accepts only a
     * newer sequence within that generation. Completion is sticky within a lease. Returning false
     * means a stale/duplicate write did not change the saved position; it is not a new completion.
     */
    suspend fun checkpoint(
        lease: ReadingResumeLease,
        sequence: Long,
        body: ArticleBody,
        paragraphIndex: Int,
        controls: ReadingResumeControls,
        completed: Boolean = false,
    ): Boolean

    /** Compare the exact record and invalidate previous writers; retain no body text. */
    suspend fun clear(record: ReadingResumeRecord): Boolean
}

/** Pure metadata and exact-body checks. Storage, expiry policy and UI positioning remain separate. */
object ReadingResumes {
    const val MAX_ARTICLE_ID = 200
    val roleFilters: Set<String> = setOf("全部", "论点", "证据", "背景", "结论")
    val toolTabs: Set<String> = setOf("导图", "重点", "略读", "背景", "检测", "分级", "对照")
    private val hash = Regex("[a-f0-9]{64}")

    fun validateIdentity(articleId: String, bodyHash: String) {
        require(articleId.isNotBlank() && articleId.length <= MAX_ARTICLE_ID && articleId.wellFormedUtf16() &&
            articleId.none(Char::isISOControl)) { "续读文章标识无效" }
        require(hash.matches(bodyHash)) { "续读正文摘要无效" }
    }

    fun validateControls(controls: ReadingResumeControls) {
        require(controls.budgetMinutes in setOf(5, 10) && controls.roleFilter in roleFilters && controls.toolTab in toolTabs) {
            "续读设置无效，请重新打开原文"
        }
    }

    /** Supports library display when the original body has expired or is temporarily unavailable. */
    fun validate(record: ReadingResumeRecord) {
        validateIdentity(record.articleId, record.bodyHash)
        validateControls(record.controls)
        require(record.paragraphIndex >= 0 && record.start >= 0 && record.end > record.start) { "续读段落范围无效" }
        require(record.generation > 0 && record.sequence > 0 && record.updatedAt >= 0) { "续读记录顺序或时间无效" }
    }

    fun validateLease(lease: ReadingResumeLease) {
        validateIdentity(lease.articleId, lease.bodyHash)
        require(lease.generation > 0) { "续读写入代次无效" }
        lease.previous?.let { previous ->
            validate(previous)
            require(previous.articleId == lease.articleId && previous.generation < lease.generation) {
                "续读历史不属于当前写入代次"
            }
        }
    }

    /** A valid hash string alone is not proof: recompute it from the exact original UTF-8 bytes. */
    fun validate(record: ReadingResumeRecord, body: ArticleBody) {
        validate(record)
        validateBody(body)
        require(record.articleId == body.articleId && record.bodyHash == body.contentHash) { "续读位置不属于当前正文" }
        val paragraph = ParagraphFocus.paragraphs(body.text).getOrNull(record.paragraphIndex)
        require(paragraph != null && record.start == paragraph.start && record.end == paragraph.endExclusive) {
            "续读段落与原文不一致"
        }
        require(!splitsSurrogate(body.text, record.start) && !splitsSurrogate(body.text, record.end)) {
            "续读位置不能拆分 Unicode 字符"
        }
    }

    fun create(
        lease: ReadingResumeLease,
        sequence: Long,
        body: ArticleBody,
        paragraphIndex: Int,
        controls: ReadingResumeControls,
        now: Long,
        completed: Boolean = false,
    ): ReadingResumeRecord {
        validateLease(lease)
        validateBody(body)
        require(lease.articleId == body.articleId && lease.bodyHash == body.contentHash) { "当前写入代次不属于此正文" }
        val paragraph = requireNotNull(ParagraphFocus.paragraphs(body.text).getOrNull(paragraphIndex)) { "请选择当前原文中的段落" }
        return ReadingResumeRecord(lease.articleId, lease.bodyHash, paragraphIndex, paragraph.start, paragraph.endExclusive,
            controls, lease.generation, sequence, now, completed).also(::validate)
    }

    /** Failed matching never searches similar text or silently relocates an old position. */
    fun restore(record: ReadingResumeRecord, body: ArticleBody): ReadingResumeRecord? =
        runCatching { validate(record, body); record.takeUnless { it.completed } }.getOrNull()

    fun anchor(record: ReadingResumeRecord, body: ArticleBody): ReaderAnchor? =
        restore(record, body)?.let { ReaderAnchor(it.bodyHash, it.start, it.end) }

    private fun validateBody(body: ArticleBody) {
        validateIdentity(body.articleId, body.contentHash)
        require(body.text.isNotBlank() && body.text.wellFormedUtf16()) { "原文为空或包含无效 Unicode 字符" }
        val actual = MessageDigest.getInstance("SHA-256").digest(body.text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        require(actual == body.contentHash) { "原文内容与保存的摘要不一致" }
    }

    private fun String.wellFormedUtf16(): Boolean {
        var index = 0
        while (index < length) {
            val character = this[index]
            if (character.isLowSurrogate()) return false
            if (character.isHighSurrogate()) {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                index++
            }
            index++
        }
        return true
    }

    private fun splitsSurrogate(text: String, offset: Int): Boolean = offset > 0 && offset < text.length &&
        text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate()
}
