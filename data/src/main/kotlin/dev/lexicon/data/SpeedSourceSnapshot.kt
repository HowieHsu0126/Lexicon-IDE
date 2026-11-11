package dev.lexicon.data

import dev.lexicon.domain.*

/** Maps retained paragraphs to compact text without allocating an unknown full-article prefix. */
internal class SpeedSourceSnapshot(private val source: List<SpeedSourcePart>) {
    val text = source.joinToString("\n\n") { it.text }
    private val compactParagraphs = ParagraphFocus.paragraphs(text)
    private val indices = source.withIndex().associate { it.value.paragraph to it.index }
    init { require(compactParagraphs.size == source.size) }

    fun paragraph(original: Int): Int = indices.getValue(original)
    fun compactOffset(original: Int, paragraph: Int): Int {
        val index = indices.getValue(paragraph)
        return original - source[index].start + compactParagraphs[index].start
    }
    fun originalOffset(compact: Int, paragraph: Int): Int =
        compact - compactParagraphs[paragraph].start + source[paragraph].start

    fun compact(plan: SpeedPlan): SpeedPlan = plan.copy(
        paragraphIndices = source.indices.toList(),
        chunks = plan.chunks.map { chunk -> chunk.copy(start = compactOffset(chunk.start, chunk.paragraph),
            end = compactOffset(chunk.end, chunk.paragraph), paragraph = paragraph(chunk.paragraph)) },
        questions = plan.questions.map { question -> question.copy(evidenceStart = compactOffset(question.evidenceStart, question.paragraph),
            evidenceEnd = compactOffset(question.evidenceEnd, question.paragraph), paragraph = paragraph(question.paragraph)) },
    )

    fun original(plan: SpeedPlan): SpeedPlan = plan.copy(
        paragraphIndices = source.map { it.paragraph },
        chunks = plan.chunks.map { chunk -> chunk.copy(start = originalOffset(chunk.start, chunk.paragraph),
            end = originalOffset(chunk.end, chunk.paragraph), paragraph = source[chunk.paragraph].paragraph) },
        questions = plan.questions.map { question -> question.copy(evidenceStart = originalOffset(question.evidenceStart, question.paragraph),
            evidenceEnd = originalOffset(question.evidenceEnd, question.paragraph), paragraph = source[question.paragraph].paragraph) },
    )

    fun original(value: SpeedSegmentation): SpeedSegmentation = value.copy(
        paragraph = source[value.paragraph].paragraph,
        start = originalOffset(value.start, value.paragraph), end = originalOffset(value.end, value.paragraph),
        suggestedBreaks = value.suggestedBreaks.map { originalOffset(it, value.paragraph) },
        breaks = value.breaks.map { originalOffset(it, value.paragraph) },
        legalBreaks = value.legalBreaks.map { originalOffset(it, value.paragraph) },
    )
}
