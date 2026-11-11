package dev.lexicon.domain

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A client-owned, continuous part of the original article. Offsets are half-open UTF-16 indices. */
@Serializable
data class StudyScope(
    val firstParagraph: Int,
    val endParagraphExclusive: Int,
    val start: Int,
    val endExclusive: Int,
    val totalParagraphs: Int,
    /** SHA-256 of the exact selected substring, including the separators between its paragraphs. */
    val contentHash: String,
)

/** Scope construction never rewrites text or turns a partial result into a whole-article study. */
object ReadingStudyScopes {
    const val MAX_CHARACTERS = 24_000
    const val MAX_PARAGRAPHS = 80
    private val hashPattern = Regex("[a-f0-9]{64}")
    private val identifier = Regex("[A-Za-z0-9_-]+")
    private val cefr = setOf("A1", "A2", "B1", "B2", "C1", "C2")
    private val roles = setOf("introduction", "background", "claim", "evidence", "counterargument", "analysis", "conclusion", "example", "transition", "other")
    private val highlightKinds = setOf("vocabulary", "collocation", "sentence", "argument")
    private val codec = Json { encodeDefaults = true }

    fun supportsFull(body: ArticleBody): Boolean = body.text.isNotBlank() &&
        body.text.length <= MAX_CHARACTERS && body.contentHash == digest(body.text) &&
        ParagraphFocus.paragraphs(body.text).size in 1..MAX_PARAGRAPHS

    fun create(body: ArticleBody, firstParagraph: Int, endParagraphExclusive: Int): StudyScope {
        requireSnapshot(body)
        val paragraphs = ParagraphFocus.paragraphs(body.text)
        require(firstParagraph >= 0 && endParagraphExclusive > firstParagraph && endParagraphExclusive <= paragraphs.size) {
            "请选择正文中连续的完整段落"
        }
        require(endParagraphExclusive - firstParagraph <= MAX_PARAGRAPHS) { "一次最多研读 80 段" }
        val start = paragraphs[firstParagraph].start
        val end = paragraphs[endParagraphExclusive - 1].endExclusive
        require(end - start <= MAX_CHARACTERS) { "所选段落超过 24,000 字符，请缩小范围" }
        return StudyScope(firstParagraph, endParagraphExclusive, start, end, paragraphs.size,
            digest(body.text.substring(start, end)))
    }

    /** Suggest a few paragraphs from the current position, without silently cutting a large paragraph. */
    fun defaultScope(body: ArticleBody, first: Int, preferredCount: Int = 3): StudyScope? {
        requireSnapshot(body)
        val paragraphs = ParagraphFocus.paragraphs(body.text)
        if (paragraphs.isEmpty()) return null
        val start = first.coerceIn(paragraphs.indices)
        val count = preferredCount.coerceIn(1, MAX_PARAGRAPHS).coerceAtMost(paragraphs.size - start)
        var end = start
        for (index in start until start + count) {
            if (paragraphs[index].endExclusive - paragraphs[start].start > MAX_CHARACTERS) break
            end = index + 1
        }
        return if (end == start) null else create(body, start, end)
    }

    /** Validate stored metadata without needing the full body. Exact binding is checked by validate. */
    fun validateMetadata(scope: StudyScope) {
        require(scope.firstParagraph >= 0 && scope.endParagraphExclusive > scope.firstParagraph &&
            scope.totalParagraphs >= scope.endParagraphExclusive &&
            scope.endParagraphExclusive - scope.firstParagraph <= MAX_PARAGRAPHS &&
            scope.start >= 0 && scope.endExclusive > scope.start &&
            scope.endExclusive.toLong() - scope.start <= MAX_CHARACTERS && hashPattern.matches(scope.contentHash)) {
            "研读范围记录无效"
        }
    }

    fun validate(body: ArticleBody, scope: StudyScope) {
        validateMetadata(scope)
        require(create(body, scope.firstParagraph, scope.endParagraphExclusive) == scope) { "研读范围与当前原文不一致" }
    }

    /** Whole-article keys are byte-for-byte compatible with the existing cache namespace. */
    fun cacheKey(articleId: String, bodyHash: String, version: Int, scope: StudyScope? = null): String {
        require(articleId.isNotBlank() && hashPattern.matches(bodyHash) && version > 0)
        scope?.let(::validateMetadata)
        val base = "reading-cache:study:$version:${digest(articleId)}:$bodyHash"
        return if (scope == null) base else "$base:range:${scope.firstParagraph}:${scope.endParagraphExclusive}"
    }

    /** Result identity also changes when a saved scope is refreshed with different questions or text. */
    fun identity(study: ArticleStudy): String = digest(codec.encodeToString(study))

    fun label(scope: StudyScope?): String = when {
        scope == null -> "全文"
        scope.endParagraphExclusive == scope.firstParagraph + 1 -> "第 ${scope.firstParagraph + 1} 段"
        else -> "第 ${scope.firstParagraph + 1}–${scope.endParagraphExclusive} 段"
    }

    /** Comprehensive result validation against the original snapshot and its client-owned scope. */
    fun validateStudy(study: ArticleStudy, body: ArticleBody) {
        requireSnapshot(body)
        require(study.articleId == body.articleId && study.bodyHash == body.contentHash) { "研读结果不属于当前正文" }
        require(study.schemaVersion in 1..2 && (study.scope == null || study.schemaVersion == 2))
        val spans = ParagraphFocus.paragraphs(body.text)
        val scope = study.scope
        val selected = if (scope == null) {
            require(supportsFull(body)) { "全文超过单次研读范围" }
            spans.indices
        } else {
            validate(body, scope)
            scope.firstParagraph until scope.endParagraphExclusive
        }
        require(!selected.isEmpty())
        require(study.summary.validText(1800) && study.cefrEstimate in cefr && study.model.validText(200) && study.createdAt >= 0)
        require(study.nodes.size in 1..18 && study.nodes.map { it.id }.distinct().size == study.nodes.size)
        require(study.nodes.count { it.parentId == null } == 1)
        val nodes = study.nodes.associateBy { it.id }
        study.nodes.forEach { node ->
            require(node.id.validIdentifier(48) && node.label.validText(120) && (node.paragraph == null || node.paragraph in selected))
            var current: MindMapNode? = node
            val seen = mutableSetOf<String>()
            while (current != null) {
                require(seen.add(current.id) && seen.size <= 8) { "导图结构无效" }
                current = current.parentId?.let { requireNotNull(nodes[it]) { "导图父节点缺失" } }
            }
        }
        require(study.highlights.size <= 18 && study.highlights.map { it.id }.distinct().size == study.highlights.size &&
            study.highlights.map { it.start to it.end }.distinct().size == study.highlights.size)
        study.highlights.forEach { highlight ->
            require(highlight.id.validIdentifier(48) && highlight.quote.validText(600) && highlight.explanation.validText(700) &&
                highlight.kind in highlightKinds && highlight.exam in setOf("ielts", "toefl", "both"))
            require(highlight.start >= 0 && highlight.end > highlight.start && highlight.end <= body.text.length)
            require(selected.any { index -> highlight.start >= spans[index].start && highlight.end <= spans[index].endExclusive }) {
                "高亮不在所选原文段落内"
            }
            require(body.text.substring(highlight.start, highlight.end) == highlight.quote) { "高亮不匹配原文" }
        }
        require(study.paragraphs.map { it.index }.sorted() == selected.toList()) { "研读段落覆盖不完整" }
        study.paragraphs.forEach { require(it.role in roles && it.summary.validText(200)) }
        require(study.questions.size in 1..5 && study.questions.map { it.id }.distinct().size == study.questions.size)
        val sentenceMap = ReadingSkimPlanner.sentences(body.text).filter { it.paragraphIndex in selected }.associateBy { it.id }
        study.questions.forEach { question ->
            require(question.id.validIdentifier(if (scope == null) 48 else 80) &&
                question.question.validText(700) && question.answer.validText(1400))
            require(question.paragraphs.size in 1..8 && question.paragraphs.distinct().size == question.paragraphs.size &&
                question.paragraphs.all { it in selected })
            if (study.schemaVersion == 1) require(question.evidence.isEmpty())
            else {
                require(question.evidence.size in 1..24 && question.evidence.map { it.sentenceId }.distinct().size == question.evidence.size &&
                    question.evidence.map { it.start to it.endExclusive }.distinct().size == question.evidence.size)
                require(question.evidence.map { it.paragraphIndex }.toSet() == question.paragraphs.toSet())
                question.evidence.forEach { evidence ->
                    require(sentenceMap[evidence.sentenceId]?.evidenceRange() == evidence) { "问题证据不匹配所选原句" }
                }
            }
        }
        require(study.backgrounds.size <= 6)
        study.backgrounds.forEach { background ->
            require(background.paragraph in selected && background.term.validText(160) && background.meaning.validText(1200) &&
                background.whyHere.validText(600) && background.uncertainty.validText(1200))
            val paragraph = spans[background.paragraph]
            require(body.text.substring(paragraph.start, paragraph.endExclusive).contains(background.term, ignoreCase = true))
        }
    }

    private fun requireSnapshot(body: ArticleBody) {
        require(body.articleId.isNotBlank() && body.contentHash == digest(body.text)) { "正文快照已变化，请重新打开文章" }
    }
    private fun String.validText(max: Int) = isNotBlank() && length <= max
    private fun String.validIdentifier(max: Int) = length in 1..max && identifier.matches(this)
    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
