package dev.lexicon.domain

import java.security.MessageDigest
import kotlin.math.abs

/** A directory row points into the complete rendered snapshot, never its displayed excerpt. */
data class SkimParagraphEntry(
    val key: String,
    val paragraphIndex: Int,
    val start: Int,
    val endExclusive: Int,
    val sentenceRanges: List<SkimSentenceRange>,
    /** Null means no matching original-study annotation, not an AI classification as "other". */
    val annotation: StudyParagraph? = null,
)

data class SkimDirectory(
    val identity: String,
    val articleId: String,
    val bodyHash: String,
    val totalParagraphs: Int,
    val entries: List<SkimParagraphEntry>,
)

data class SkimDirectoryPage(
    /** Zero-based; an empty directory has index 0 and pageCount 0. */
    val index: Int,
    val pageCount: Int,
    val entries: List<SkimParagraphEntry>,
    val totalEntries: Int,
) {
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index + 1 < pageCount
}

/** Projects the existing sentence plan into bounded navigation; it never selects or rewrites text. */
object SkimNavigation {
    const val PAGE_SIZE = 8

    fun build(
        body: ArticleBody,
        plan: SkimPlan,
        study: ArticleStudy? = null,
        original: Boolean = true,
    ): SkimDirectory {
        require(body.articleId.isNotBlank() && body.contentHash == digest(body.text)) {
            "正文快照已变化，请重新打开文章"
        }
        val paragraphs = ParagraphFocus.paragraphs(body.text)
        val planned = plan.paragraphIndices.toSet()
        val units = ReadingSkimPlanner.sentences(body.text).associateBy {
            Triple(it.paragraphIndex, it.start, it.endExclusive)
        }
        val selected = plan.sentenceRanges.filter { range ->
            range.paragraphIndex in planned && units[Triple(range.paragraphIndex, range.start, range.endExclusive)]
                ?.let { it.wordCount == range.wordCount } == true
        }.groupBy { Triple(it.paragraphIndex, it.start, it.endExclusive) }
            // Identical duplicates are harmless. Conflicting question metadata is not resolved by this view.
            .mapNotNull { (_, versions) -> versions.distinct().singleOrNull() }
            .sortedBy { it.start }
            .groupBy { it.paragraphIndex }
        val matchingStudy = study?.takeIf { original && runCatching { ReadingStudyScopes.validateStudy(it, body) }.isSuccess }
        val annotations = matchingStudy?.paragraphs.orEmpty().associateBy { it.index }
        val entries = selected.entries.sortedBy { it.key }.map { (index, ranges) ->
            val paragraph = paragraphs[index]
            SkimParagraphEntry(
                key = "skim-paragraph:${body.articleId}:${body.contentHash}:${paragraph.start}:${paragraph.endExclusive}",
                paragraphIndex = index,
                start = paragraph.start,
                endExclusive = paragraph.endExclusive,
                sentenceRanges = ranges,
                annotation = annotations[index],
            )
        }
        val identity = digest(buildString {
            fun token(value: Any?) { val text = value?.toString().orEmpty(); append(text.length).append(':').append(text) }
            token(body.articleId); token(body.contentHash); token(original)
            token(matchingStudy?.let(ReadingStudyScopes::identity))
            token(plan.targetMinutes); token(plan.wordBudget); token(plan.planningWpm)
            token(entries.size)
            entries.forEach { entry ->
                token(entry.key)
                token(entry.sentenceRanges.size)
                entry.sentenceRanges.forEach { range ->
                    token(range.start); token(range.endExclusive); token(range.wordCount)
                    token(range.questionIds.size); range.questionIds.forEach(::token)
                }
            }
            // Unresolved question choices also remain part of the plan's presentation identity.
            token(plan.questionCoverage.size)
            plan.questionCoverage.forEach { coverage ->
                token(coverage.questionId); token(coverage.status)
                token(coverage.evidenceSentenceIds.size); coverage.evidenceSentenceIds.forEach(::token)
                token(coverage.selectedSentenceIds.size); coverage.selectedSentenceIds.forEach(::token)
                token(coverage.relatedParagraphIndices.size); coverage.relatedParagraphIndices.forEach(::token)
            }
        })
        return SkimDirectory(identity, body.articleId, body.contentHash, paragraphs.size, entries)
    }

    /** Start with the selected paragraph nearest the physical viewport; ties prefer the earlier one. */
    fun initialPage(directory: SkimDirectory, focusedParagraph: Int): Int {
        val nearest = directory.entries.indices.minWithOrNull(compareBy<Int> {
            abs(directory.entries[it].paragraphIndex.toLong() - focusedParagraph.toLong())
        }.thenBy { directory.entries[it].paragraphIndex }) ?: return 0
        return nearest / PAGE_SIZE
    }

    fun page(directory: SkimDirectory, pageIndex: Int): SkimDirectoryPage {
        val total = directory.entries.size
        if (total == 0) return SkimDirectoryPage(0, 0, emptyList(), 0)
        val count = (total - 1) / PAGE_SIZE + 1
        val index = pageIndex.coerceIn(0, count - 1)
        val start = index * PAGE_SIZE
        return SkimDirectoryPage(index, count, directory.entries.subList(start, minOf(start + PAGE_SIZE, total)), total)
    }

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
