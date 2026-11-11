package dev.lexicon.domain

import java.security.MessageDigest
import kotlinx.serialization.Serializable

/**
 * An explicit user save, independent of retrieval caches and AI jobs. The complete frozen lookup
 * retains the query context and all original evidence identities; selectedCandidateId records the
 * meaning the reader chose. Query context alone does not establish a verified original-text anchor.
 */
@Serializable
data class BackgroundRecord(
    val id: String,
    val lookup: BackgroundLookup,
    val selectedCandidateId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val explanation: StudyAssist? = null,
    val comment: String = "",
    val tags: List<String> = emptyList(),
    val revision: Int = 1,
    val version: Int = 1,
)

/** Pure snapshot and edit rules. Data adapters additionally validate provider URLs and stored identity. */
object BackgroundRecords {
    const val MAX_COMMENT = 12_000
    const val MAX_TAGS = 20
    const val MAX_TAG_LENGTH = 100
    private val hash = Regex("[a-f0-9]{64}")
    private val candidateIdentifier = Regex("[A-Za-z0-9_-]{1,80}")

    /** Stable for this exact lookup and chosen meaning; refreshing a lookup creates a different save. */
    fun id(lookupId: String, candidateId: String): String {
        require(hash.matches(lookupId) && candidateIdentifier.matches(candidateId)) { "保存的背景标识无效" }
        val candidateHash = MessageDigest.getInstance("SHA-256").digest(candidateId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$lookupId:$candidateHash"
    }

    fun validate(record: BackgroundRecord) {
        require(record.version == 1 && record.revision >= 1) { "保存的背景版本无效" }
        require(record.createdAt >= 0 && record.updatedAt >= record.createdAt) { "保存的背景时间无效" }
        val sources = BackgroundResearch.selectedSources(record.lookup, record.selectedCandidateId)
        require(record.id == id(record.lookup.id, record.selectedCandidateId)) { "保存的背景身份不一致" }
        validateMetadata(record.comment, record.tags)
        record.explanation?.let { validateExplanation(it, sources) }
    }

    /** Shared by retrieved explanation caches and saved records so both apply the same contract. */
    fun validateExplanation(result: StudyAssist, sources: List<BackgroundSource>) {
        require(result.kind == "background" && result.retrievalStatus == "retrieved" && result.sources == sources && result.score == null) {
            "背景解释与所选资料不一致"
        }
        require(result.model.isNotBlank() && result.model.length <= 200 && result.model != "retrieval-only" && result.createdAt >= 0) {
            "背景解释生成信息无效"
        }
        require(result.title.isNotBlank() && result.title.length <= 160 && result.text.isNotBlank() && result.text.length <= 1_600) {
            "背景解释标题或正文无效"
        }
        require(result.evidence.isNotBlank() && result.evidence.length <= 2_400) { "背景解释证据说明无效" }
        val ids = result.citedSourceIds
        require(ids.isNotEmpty() && ids.distinct() == ids && ids.all { id -> sources.any { it.id == id } }) { "背景解释引用无效" }
        require(Regex("\\[([^]\\n]+)]").findAll(result.text).map { it.groupValues[1] }.toSet() == ids.toSet()) {
            "背景解释正文与引用编号不一致"
        }
        require(!Regex("(?i)(https?://|www\\.|\\]\\s*\\(|<[^>]+>)").containsMatchIn(result.title + "\n" + result.text)) {
            "背景解释不能自行添加网址或 HTML"
        }
    }

    /** Saving old evidence is allowed. Expiry restricts new generation, not offline preservation. */
    fun create(
        lookup: BackgroundLookup,
        candidateId: String,
        now: Long,
        explanation: StudyAssist? = null,
    ): BackgroundRecord = BackgroundRecord(
        id(lookup.id, candidateId), freeze(lookup), candidateId, now, now, explanation?.let(::freeze),
    ).also(::validate)

    /** Callers edit only their own comment and tags; source, context and evidence remain unchanged. */
    fun updateMetadata(
        record: BackgroundRecord,
        expectedRevision: Int,
        comment: String,
        tags: List<String>,
        now: Long,
    ): BackgroundRecord {
        checkRevision(record, expectedRevision, now)
        validateMetadata(comment, tags)
        if (record.comment == comment && record.tags == tags) return record
        return record.copy(comment = comment, tags = tags.toList(), revision = nextRevision(record),
            updatedAt = maxOf(now, record.updatedAt)).also(::validate)
    }

    /** The repository must separately prove this is the exact explanation displayed and cached. */
    fun withExplanation(
        record: BackgroundRecord,
        expectedRevision: Int,
        explanation: StudyAssist,
        now: Long,
    ): BackgroundRecord {
        checkRevision(record, expectedRevision, now)
        validateExplanation(explanation, BackgroundResearch.selectedSources(record.lookup, record.selectedCandidateId))
        if (record.explanation == explanation) return record
        require(record.explanation == null) { "已保存的解释不能替换；请保留当前记录" }
        return record.copy(explanation = freeze(explanation), revision = nextRevision(record),
            updatedAt = maxOf(now, record.updatedAt)).also(::validate)
    }

    private fun checkRevision(record: BackgroundRecord, expectedRevision: Int, now: Long) {
        validate(record)
        require(expectedRevision == record.revision) { "这条背景已变化，请重新打开后操作" }
        require(now >= 0) { "保存时间无效" }
    }

    private fun nextRevision(record: BackgroundRecord): Int {
        require(record.revision < Int.MAX_VALUE) { "这条背景已达到修订上限" }
        return record.revision + 1
    }

    private fun validateMetadata(comment: String, tags: List<String>) {
        require(comment.length <= MAX_COMMENT && comment.validText(multiline = true)) { "个人评论最多 12,000 字符，且不能包含无效字符" }
        require(tags.size <= MAX_TAGS && tags.all { it.isNotBlank() && it.length <= MAX_TAG_LENGTH && it.validText() }) {
            "背景标签过多、过长或包含无效字符"
        }
    }

    private fun freeze(value: BackgroundLookup): BackgroundLookup = value.copy(
        candidates = value.candidates.map { it.copy(sourceIds = it.sourceIds.toList()) },
        sources = value.sources.toList(), providerOutcomes = value.providerOutcomes.toList(),
    )

    private fun freeze(value: StudyAssist): StudyAssist = value.copy(sources = value.sources.toList(), citedSourceIds = value.citedSourceIds.toList())

    private fun String.validText(multiline: Boolean = false): Boolean {
        var index = 0
        while (index < length) {
            val character = this[index]
            if (character.isISOControl() && !(multiline && character in "\n\r\t")) return false
            if (character.isLowSurrogate()) return false
            if (character.isHighSurrogate()) {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                index++
            }
            index++
        }
        return true
    }
}
