package dev.lexicon.domain

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

data class ReadingEstimate(val words: Int, val minutes: Int?, val fleschKincaidGrade: Double?)

object Readability {
    /** A transparent length/phonetic heuristic, not an exam score or a full-article claim for summaries. */
    fun estimate(text: String): ReadingEstimate {
        val words = WORD.findAll(text).map { it.value }.toList()
        if (words.isEmpty()) return ReadingEstimate(0, null, null)
        val iterator = BreakIterator.getSentenceInstance(Locale.ENGLISH)
        iterator.setText(text)
        var start = iterator.first()
        var count = 0
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            if (WORD.containsMatchIn(text.substring(start, end))) count++
            start = end
            end = iterator.next()
        }
        val grade = if (words.size < 50 || count < 2) null else
            0.39 * words.size / max(count, 1) + 11.8 * words.sumOf(::syllables).toDouble() / words.size - 15.59
        return ReadingEstimate(words.size, max(1, ceil(words.size / 200.0).toInt()), grade)
    }

    private fun syllables(input: String): Int {
        var word = input.lowercase(Locale.ROOT).replace(Regex("[^a-z]"), "")
        if (word.length <= 3) return 1
        word = word.replace(Regex("(?:[^laeiouy]es|ed|[^laeiouy]e)$"), "")
        return max(1, Regex("[aeiouy]+").findAll(word).count())
    }
    private val WORD = Regex("[A-Za-z]+(?:['’\\-][A-Za-z]+)*")
}
