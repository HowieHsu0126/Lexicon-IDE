package dev.lexicon.domain

import java.security.MessageDigest
import java.text.BreakIterator
import java.util.Locale

/** Stable, complete source sentences. All offsets are half-open UTF-16 article offsets. */
data class ReadingSentenceUnit(
    val id: String, val paragraphIndex: Int, val start: Int, val endExclusive: Int, val wordCount: Int,
) {
    fun evidenceRange() = ReadingEvidenceRange(id, paragraphIndex, start, endExclusive)
}

data class SkimSentenceRange(
    val paragraphIndex: Int, val start: Int, val endExclusive: Int, val wordCount: Int,
    /** Only validated exact sentence evidence receives question IDs. */
    val questionIds: List<String> = emptyList(),
)

enum class SkimQuestionStatus {
    COVERED, PARTIAL, BUDGET_EXCLUDED, ROLE_EXCLUDED, PARAGRAPH_ONLY, INVALID_EVIDENCE,
}

data class SkimQuestionCoverage(
    val questionId: String,
    val evidenceSentenceIds: List<String>,
    val selectedSentenceIds: List<String>,
    val relatedParagraphIndices: List<Int>,
    val status: SkimQuestionStatus,
)

/** Optional original-source identity; rewritten or unknown text uses the text-only planner. */
data class SkimOriginal(val article: Article, val body: ArticleBody)

data class SkimPlan(
    val paragraphIndices: List<Int>, val targetMinutes: Int, val wordBudget: Int,
    val wordsSelected: Int, val estimatedMinutes: Double, val exceedsBudget: Boolean,
    val note: String = "按每分钟 160 词规划，实际用时因人而异；自动分句只选取原文，不生成摘要。",
    val sentenceRanges: List<SkimSentenceRange> = emptyList(),
    val paragraphWordsSelected: Int = wordsSelected,
    val planningWpm: Int = ReadingSkimPlanner.PLANNING_WORDS_PER_MINUTE,
    val questionCoverage: List<SkimQuestionCoverage> = emptyList(),
    val disclosures: List<ReadingDisclosure> = emptyList(),
    val includesDisclosures: Boolean = false,
) {
    /** Requested choices include unresolved questions, so the UI can retain every selection. */
    val selectedQuestionIds: List<String> get() = questionCoverage.map { it.questionId }
    val coveredQuestionIds: List<String> get() = questionCoverage.filter { it.status == SkimQuestionStatus.COVERED }.map { it.questionId }
    val unresolvedQuestionIds: List<String> get() = questionCoverage.filter { it.status != SkimQuestionStatus.COVERED }.map { it.questionId }
    val budgetOverflowWords: Int get() = (wordsSelected - wordBudget).coerceAtLeast(0)
}

/** Plans a bounded selection of original sentences; the full article itself never changes. */
object ReadingSkimPlanner {
    const val PLANNING_WORDS_PER_MINUTE = 160
    const val MIN_PLANNING_WORDS_PER_MINUTE = 80
    const val MAX_PLANNING_WORDS_PER_MINUTE = 400
    private val word = Regex("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*")
    private val abbreviation = Regex("\\b(?:Mr|Mrs|Ms|Dr|Prof|St|vs|etc|e\\.g|i\\.e)\\.$|(?:\\b[A-Z]\\.){2,}$", RegexOption.IGNORE_CASE)
    private const val HEX = "0123456789abcdef"

    fun plan(
        text: String, minutes: Int, study: ArticleStudy? = null, question: ReadingQuestion? = null,
        roles: Set<String> = emptySet(), selectedQuestions: List<ReadingQuestion> = emptyList(),
        planningWpm: Int = PLANNING_WORDS_PER_MINUTE,
        original: SkimOriginal? = null, includeDisclosures: Boolean = false,
    ): SkimPlan {
        require(minutes == 5 || minutes == 10) { "略读时长请选择 5 或 10 分钟" }
        require(planningWpm in MIN_PLANNING_WORDS_PER_MINUTE..MAX_PLANNING_WORDS_PER_MINUTE) { "规划速度请选择每分钟 80–400 词" }
        val paragraphs = ParagraphFocus.paragraphs(text)
        val units = sentences(text)
        val byId = units.associateBy { it.id }
        val budget = minutes * planningWpm
        val eligible = if (roles.isEmpty()) paragraphs.indices.toSet() else study?.paragraphs
            ?.filter { it.role in roles && it.index in paragraphs.indices }?.map { it.index }?.toSet().orEmpty()
        val disclosures = original?.takeIf { it.body.text == text }?.let { ReadingDisclosures.detect(it.article, it.body) }.orEmpty()
        val automaticEligible = if (includeDisclosures) eligible else eligible - disclosures.map { it.paragraphIndex }.toSet()
        val requested = (listOfNotNull(question) + selectedQuestions).groupBy { it.id }
        val snapshotMatches = study == null || study.bodyHash == digest(text)
        data class Choice(val question: ReadingQuestion, val evidence: List<ReadingSentenceUnit>, val invalid: Boolean)
        val choices = requested.map { (id, versions) ->
            val value = versions.first()
            val standaloneLegacy = study == null && selectedQuestions.none { it.id == id } && question == value
            val canonical = study?.questions?.singleOrNull { it.id == id }
            val identityMatches = id.isNotBlank() && versions.distinct().size == 1 && (standaloneLegacy || canonical == value)
            val resolved = value.evidence.mapNotNull { span -> byId[span.sentenceId]?.takeIf { it.evidenceRange() == span } }
            val exact = value.evidence.isNotEmpty() && snapshotMatches &&
                value.evidence.size == resolved.size && value.evidence.map { it.sentenceId }.distinct().size == value.evidence.size &&
                value.paragraphs.distinct().size == value.paragraphs.size &&
                resolved.map { it.paragraphIndex }.toSet() == value.paragraphs.toSet()
            Choice(value, if (identityMatches && exact) resolved.sortedBy { it.start } else emptyList(),
                !identityMatches || (value.evidence.isNotEmpty() && !exact))
        }
        val valid = choices.filterNot { it.invalid }
        val questionParagraphs = valid.filter { it.evidence.isEmpty() }.flatMap { it.question.paragraphs }
            .filter { it in eligible }.toCollection(linkedSetOf())
        val priority = linkedSetOf<Int>().apply {
            addAll(questionParagraphs)
            study?.paragraphs?.filter { it.role in setOf("introduction", "claim", "conclusion", "analysis") && it.index in automaticEligible }
                ?.sortedBy { when (it.role) { "introduction" -> 0; "conclusion" -> 1; "claim" -> 2; else -> 3 } }
                ?.forEach { add(it.index) }
            if (0 in automaticEligible) add(0)
            val lastContent = if (includeDisclosures || disclosures.isEmpty()) paragraphs.lastIndex
                else paragraphs.indices.lastOrNull { index -> disclosures.none { it.paragraphIndex == index } }
            lastContent?.takeIf { it in automaticEligible }?.let(::add)
            addAll(automaticEligible.sorted())
        }
        val byParagraph = units.groupBy { it.paragraphIndex }
        // Give each question its first evidence sentence before considering its second. A single
        // broad question cannot consume the entire budget before the other questions are visited.
        val exactByQuestion = valid.map { it.evidence.filter { sentence -> sentence.paragraphIndex in eligible } }
        val candidates = buildList {
            repeat(exactByQuestion.maxOfOrNull { it.size } ?: 0) { round ->
                exactByQuestion.forEach { evidence -> evidence.getOrNull(round)?.let(::add) }
            }
            questionParagraphs.forEach { addAll(byParagraph[it].orEmpty()) }
            priority.filter { it !in questionParagraphs }.forEach { byParagraph[it]?.firstOrNull()?.let(::add) }
            priority.filter { it !in questionParagraphs }.forEach { addAll(byParagraph[it].orEmpty().drop(1)) }
        }.distinctBy { it.id }
        val selected = mutableListOf<ReadingSentenceUnit>()
        var total = 0
        for (unit in candidates) {
            if (unit.wordCount <= budget - total) { selected += unit; total += unit.wordCount }
        }
        // Never truncate a source sentence to manufacture a fit. This sole fallback retains one
        // shortest complete sentence and reports its exact overrun.
        if (selected.isEmpty()) candidates.minWithOrNull(compareBy<ReadingSentenceUnit> { it.wordCount }.thenBy { it.start })?.let {
            selected += it; total = it.wordCount
        }
        val selectedIds = selected.map { it.id }.toSet()
        val coverage = choices.map { choice ->
            val evidence = choice.evidence
            val picked = evidence.filter { it.id in selectedIds }
            val status = when {
                choice.invalid -> SkimQuestionStatus.INVALID_EVIDENCE
                evidence.isEmpty() -> SkimQuestionStatus.PARAGRAPH_ONLY
                picked.size == evidence.size -> SkimQuestionStatus.COVERED
                evidence.any { it.paragraphIndex !in eligible } -> SkimQuestionStatus.ROLE_EXCLUDED
                picked.isNotEmpty() -> SkimQuestionStatus.PARTIAL
                else -> SkimQuestionStatus.BUDGET_EXCLUDED
            }
            SkimQuestionCoverage(choice.question.id, evidence.map { it.id }, picked.map { it.id },
                if (choice.invalid) emptyList() else choice.question.paragraphs.filter { it in eligible }.distinct(), status)
        }
        val mapping = linkedMapOf<String, MutableList<String>>()
        valid.forEach { choice -> choice.evidence.forEach { mapping.getOrPut(it.id) { mutableListOf() }.add(choice.question.id) } }
        val ranges = selected.sortedBy { it.start }.map { unit ->
            SkimSentenceRange(unit.paragraphIndex, unit.start, unit.endExclusive, unit.wordCount, mapping[unit.id].orEmpty())
        }
        val indices = ranges.map { it.paragraphIndex }.distinct()
        val paragraphWords = indices.sumOf { countWords(text.substring(paragraphs[it].start, paragraphs[it].endExclusive)) }
        val note = when {
            eligible.isEmpty() && roles.isNotEmpty() -> "没有匹配的段落角色；先取得研读结果或调整筛选。"
            eligible.isEmpty() -> "没有可选取的正文。"
            total > budget -> "最短的完整句仍超过规划字数；保留原句，不截断内容，预计用时会超过目标。"
            else -> "按每分钟 $planningWpm 词规划，实际用时因人而异；仅选取原文句子。"
        }
        return SkimPlan(indices, minutes, budget, total, total.toDouble() / planningWpm, total > budget,
            note, ranges, paragraphWords, planningWpm, coverage, disclosures, includeDisclosures)
    }

    fun countWords(text: String): Int = word.findAll(text).count()

    /** Source IDs bind both the exact sentence bytes and its position, including repeated text. */
    fun sentences(text: String): List<ReadingSentenceUnit> = ParagraphFocus.paragraphs(text).flatMapIndexed { index, paragraph ->
        sentenceRanges(text, paragraph, index).map { range ->
            val sentenceHash = digest(text.substring(range.start, range.endExclusive)).take(16)
            ReadingSentenceUnit("s-${range.start}-${range.endExclusive}-$sentenceHash", index, range.start, range.endExclusive, range.wordCount)
        }
    }

    private fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) { bytes.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(HEX[unsigned ushr 4]); append(HEX[unsigned and 0x0f])
        } }
    }

    private fun sentenceRanges(text: String, paragraph: ReadingParagraph, index: Int): List<SkimSentenceRange> {
        val local = text.substring(paragraph.start, paragraph.endExclusive)
        val iterator = BreakIterator.getSentenceInstance(Locale.ENGLISH).apply { setText(local) }
        val boundaries = sortedSetOf(0, local.length)
        var boundary = iterator.first()
        while (boundary != BreakIterator.DONE) { boundaries += boundary; boundary = iterator.next() }
        // Add punctuation followed by an emoji and an uppercase sentence without splitting decimals.
        for (position in local.indices) {
            if (local[position] !in ".!?" || (local[position] == '.' && position > 0 && local[position - 1] == '.')) continue
            var end = position + 1
            while (end < local.length && local[end] in "\"'”’»)]}") end++
            if (end < local.length && !local[end].isWhitespace()) continue
            var next = end; var inspected = 0
            while (next < local.length && inspected < 64) {
                val codePoint = local.codePointAt(next)
                if (Character.isLetterOrDigit(codePoint)) break
                next += Character.charCount(codePoint); inspected++
            }
            if (next == local.length || Character.isUpperCase(local.codePointAt(next)) || Character.isDigit(local.codePointAt(next))) boundaries += end
        }
        val result = mutableListOf<SkimSentenceRange>()
        for ((from, to) in boundaries.zipWithNext()) {
            var start = paragraph.start + from; var end = paragraph.start + to
            while (start < end && text[start].isWhitespace()) start++
            while (end > start && text[end - 1].isWhitespace()) end--
            val count = if (start < end) countWords(text.substring(start, end)) else 0
            if (count > 0) {
                val previous = result.lastOrNull()
                if (previous != null && abbreviation.containsMatchIn(text.substring(previous.start, previous.endExclusive))) {
                    result[result.lastIndex] = previous.copy(endExclusive = end, wordCount = previous.wordCount + count)
                } else result += SkimSentenceRange(index, start, end, count)
            }
        }
        return result
    }
}
