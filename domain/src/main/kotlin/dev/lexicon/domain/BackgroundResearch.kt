package dev.lexicon.domain

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BackgroundQuery(
    val term: String,
    val context: String = "",
    val articleId: String? = null,
    val bodyHash: String? = null,
    val source: SourceRef = SourceRef(),
    val aiAllowed: Boolean = true,
)

@Serializable
data class BackgroundCandidate(val id: String, val title: String, val description: String, val sourceIds: List<String>)

@Serializable
data class BackgroundProviderOutcome(val provider: String, val status: String, val detail: String = "")

/** A frozen retrieval result. Explanation and its failures do not replace this evidence. */
@Serializable
data class BackgroundLookup(
    val id: String,
    val query: BackgroundQuery,
    val candidates: List<BackgroundCandidate>,
    val sources: List<BackgroundSource>,
    val providerOutcomes: List<BackgroundProviderOutcome>,
    val status: String,
    val createdAt: Long,
    val version: Int = 1,
)

interface BackgroundResearchAi {
    suspend fun lookup(query: BackgroundQuery): BackgroundLookup
    /**
     * Invoke beforeRequest after waiting for the model gate, immediately before every transport
     * attempt, including schema repair. A failed check must stop that attempt without sending it.
     * The callback checks current local policy; it cannot recall a previously dispatched request.
     */
    suspend fun explain(
        query: BackgroundQuery,
        sources: List<BackgroundSource>,
        beforeRequest: suspend () -> Unit = {},
    ): StudyAssist
}

/** Callers select a saved concept; they cannot supply replacement evidence when requesting prose. */
interface BackgroundRepository {
    suspend fun cached(query: BackgroundQuery): BackgroundLookup?
    suspend fun lookup(query: BackgroundQuery, refresh: Boolean = false): BackgroundLookup
    suspend fun cachedExplanation(lookupId: String, candidateId: String): StudyAssist?
    suspend fun explain(lookupId: String, candidateId: String, explicitRetry: Boolean = false): StudyAssist

    /** Local history only. Missing IDs return null; invalid stored snapshots must not appear missing. */
    suspend fun readLookup(id: String): BackgroundLookup? = null
    suspend fun recentLookups(): List<BackgroundLookup> = emptyList()
    suspend fun savedRecord(lookupId: String, candidateId: String): BackgroundRecord? = null

    /**
     * Promote this exact displayed candidate to an offline record; never retrieve or generate.
     * The implementation reads and validates the stored lookup. A supplied explanation must equal
     * the validated stored explanation. With no expectedRevision, an existing record is returned
     * unchanged. Adding a displayed explanation later requires the current expectedRevision.
     */
    suspend fun saveRecord(
        lookupId: String,
        candidateId: String,
        displayedExplanation: StudyAssist? = null,
        expectedRevision: Int? = null,
    ): BackgroundRecord = throw UnsupportedOperationException("Saving background records is unavailable")

    suspend fun updateRecord(
        id: String,
        expectedRevision: Int,
        comment: String,
        tags: List<String>,
    ): BackgroundRecord = throw UnsupportedOperationException("Editing background records is unavailable")

    /** Delete only this saved record. Retrieval caches, article text and other learning data remain. */
    suspend fun deleteRecord(id: String, expectedRevision: Int): Unit =
        throw UnsupportedOperationException("Deleting background records is unavailable")
}

/** Provider URL and licensing policy belongs to data adapters; domain binds exact local snapshots. */
object BackgroundResearch {
    const val MAX_TERM = 160
    const val MAX_CONTEXT = 1_200
    const val MAX_SOURCES = 3
    const val CACHE_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
    private val codec = Json { encodeDefaults = true }
    private val hash = Regex("[a-f0-9]{64}")
    private val identifier = Regex("[A-Za-z0-9_-]{1,80}")
    private val whitespace = Regex("[\\s\\p{Z}]+")
    private val statuses = setOf("retrieved", "no_results", "unavailable")
    private val providerStatuses = statuses + "skipped"

    fun normalize(query: BackgroundQuery): BackgroundQuery {
        val value = query.copy(term = query.term.trim().replace(whitespace, " "), context = query.context.trim())
        require(value.term.validText(MAX_TERM)) { "背景概念需为 1–160 个字符" }
        require(value.context.validText(MAX_CONTEXT, blankAllowed = true, multiline = true)) { "背景语境最多 1,200 个字符，且不能包含无效字符" }
        require(value.articleId == null || value.articleId.validText(200)) { "背景文章标识无效" }
        require(value.bodyHash == null || hash.matches(value.bodyHash)) { "背景正文摘要无效" }
        require(value.source.title.validOptionalText(1_000) && value.source.source.validOptionalText(500) &&
            value.source.url.validOptionalText(4_000) && (value.source.publishedAt == null || value.source.publishedAt >= 0)) {
            "背景来源信息无效"
        }
        return value
    }

    fun queryKey(query: BackgroundQuery): String = digest(codec.encodeToString(normalize(query)))

    /** Lists retain their exact order because source numbering and concept order are meaningful. */
    fun lookupId(
        query: BackgroundQuery,
        candidates: List<BackgroundCandidate>,
        sources: List<BackgroundSource>,
        outcomes: List<BackgroundProviderOutcome>,
        status: String,
        createdAt: Long,
    ): String = digest(codec.encodeToString(LookupSnapshot(normalize(query), candidates, sources, outcomes, status, createdAt)))

    fun createLookup(
        query: BackgroundQuery,
        candidates: List<BackgroundCandidate>,
        sources: List<BackgroundSource>,
        outcomes: List<BackgroundProviderOutcome>,
        status: String,
        createdAt: Long,
    ): BackgroundLookup {
        val normalized = normalize(query)
        val frozenCandidates = candidates.map { it.copy(sourceIds = it.sourceIds.toList()) }
        val frozenSources = sources.toList()
        val frozenOutcomes = outcomes.toList()
        return BackgroundLookup(
            lookupId(normalized, frozenCandidates, frozenSources, frozenOutcomes, status, createdAt),
            normalized, frozenCandidates, frozenSources, frozenOutcomes, status, createdAt,
        ).also(::validateLookup)
    }

    fun validateLookup(value: BackgroundLookup) {
        require(value.version == 1 && value.createdAt >= 0 && value.status in statuses) { "背景资料版本或状态无效" }
        require(value.query == normalize(value.query)) { "背景请求未按原规则归一化" }
        require(value.sources.size <= MAX_SOURCES && value.sources.map { it.id }.distinct().size == value.sources.size)
        require(value.sources.map { it.url }.distinct().size == value.sources.size) { "背景资料重复" }
        value.sources.forEach { source ->
            require(identifier.matches(source.id) && source.title.validText(251) && source.url.validText(4_000) &&
                source.excerpt.validText(1_601, multiline = true) && source.provider.validText(100) &&
                source.license.validText(500) && source.retrievedAt >= 0 && source.revisionId.validOptionalText(200)) {
                "背景资料字段无效"
            }
            require(source.contentHash == null || hash.matches(source.contentHash)) { "背景资料内容摘要无效" }
        }
        require(value.candidates.map { it.id }.distinct().size == value.candidates.size) { "背景概念标识重复" }
        val sourceIds = value.sources.map { it.id }.toSet()
        value.candidates.forEach { candidate ->
            require(identifier.matches(candidate.id) && candidate.title.validText(251) && candidate.description.validText(1_200, multiline = true)) {
                "背景概念字段无效"
            }
            require(candidate.sourceIds.isNotEmpty() && candidate.sourceIds.distinct().size == candidate.sourceIds.size &&
                candidate.sourceIds.all { it in sourceIds }) { "背景概念引用无效" }
        }
        require(value.candidates.flatMap { it.sourceIds }.toSet() == sourceIds) { "背景概念未完整关联资料" }
        if (value.status == "retrieved") require(value.sources.isNotEmpty() && value.candidates.isNotEmpty())
        else require(value.sources.isEmpty() && value.candidates.isEmpty()) { "无结果状态不能包含资料或概念" }
        require(value.providerOutcomes.map { it.provider }.distinct().size == value.providerOutcomes.size) { "检索来源状态重复" }
        value.providerOutcomes.forEach { outcome ->
            require(outcome.provider.validText(100) && outcome.status in providerStatuses &&
                outcome.detail.validText(1_200, blankAllowed = true, multiline = true)) { "检索来源状态无效" }
        }
        require(hash.matches(value.id) && value.id == lookupId(value.query, value.candidates, value.sources,
            value.providerOutcomes, value.status, value.createdAt)) { "背景资料快照已变化，请重新读取" }
    }

    /** The existing explanation parser receives a fresh, contiguous S1..Sn namespace. */
    fun selectedSources(lookup: BackgroundLookup, candidateId: String): List<BackgroundSource> {
        validateLookup(lookup)
        require(lookup.status == "retrieved") { "尚无可解释的背景资料" }
        val candidate = requireNotNull(lookup.candidates.singleOrNull { it.id == candidateId }) { "请选择已有背景概念" }
        val byId = lookup.sources.associateBy { it.id }
        return candidate.sourceIds.mapIndexed { index, id -> byId.getValue(id).copy(id = "S${index + 1}") }
    }

    /** Expiry is evaluated, never written back as a newer retrieval time. */
    fun expired(lookup: BackgroundLookup, now: Long): Boolean {
        validateLookup(lookup)
        if (now < lookup.createdAt || lookup.sources.any { it.retrievedAt > now }) return true
        val oldest = lookup.sources.minOfOrNull { it.retrievedAt } ?: lookup.createdAt
        return now - oldest >= CACHE_TTL_MILLIS
    }

    /** An exact source substring around a half-open UTF-16 selection, including reverse selections. */
    fun contextAround(text: String, start: Int, end: Int, maxLength: Int = MAX_CONTEXT): String {
        require(maxLength > 0) { "背景语境长度必须为正数" }
        val from = minOf(start, end)
        val to = maxOf(start, end)
        require(from >= 0 && to <= text.length && from < to) { "请选择正文中的词或短语" }
        require(!splitsSurrogate(text, from) && !splitsSurrogate(text, to)) { "选区不能拆分 Unicode 字符" }
        require(to - from <= maxLength) { "选区超过背景语境长度，请缩小选区" }
        require(text.substring(from, to).isNotBlank()) { "背景选区不能为空" }
        val room = maxLength - (to - from)
        var windowStart = (from - room / 2).coerceAtLeast(0)
        val available = text.length - windowStart
        var windowEnd = windowStart + minOf(maxLength, available)
        windowStart = (windowEnd - maxLength).coerceAtLeast(0)
        if (splitsSurrogate(text, windowStart)) windowStart++
        if (splitsSurrogate(text, windowEnd)) windowEnd--
        require(windowStart <= from && windowEnd >= to)
        return text.substring(windowStart, windowEnd).also {
            require(it.wellFormedUtf16()) { "背景语境包含无效 Unicode 字符" }
        }
    }

    @Serializable
    private data class LookupSnapshot(
        val query: BackgroundQuery,
        val candidates: List<BackgroundCandidate>,
        val sources: List<BackgroundSource>,
        val providerOutcomes: List<BackgroundProviderOutcome>,
        val status: String,
        val createdAt: Long,
        val version: Int = 1,
    )

    private fun String?.validOptionalText(max: Int): Boolean = this == null || validText(max, blankAllowed = true)
    private fun String.validText(max: Int, blankAllowed: Boolean = false, multiline: Boolean = false): Boolean =
        (blankAllowed || isNotBlank()) && length <= max && wellFormedUtf16() && none {
            it.isISOControl() && !(multiline && it in "\n\r\t")
        }
    private fun String.wellFormedUtf16(): Boolean {
        var index = 0
        while (index < length) {
            val current = this[index]
            if (current.isLowSurrogate()) return false
            if (current.isHighSurrogate()) {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                index++
            }
            index++
        }
        return true
    }
    private fun splitsSurrogate(text: String, offset: Int): Boolean = offset > 0 && offset < text.length &&
        text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate()
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
