package dev.lexicon.domain

/** Viewport dwell is a reading estimate, never proof that the reader understood the text. */
object ReadingSessionMeter {
    fun estimatedWords(paragraphWords: List<Int>, dwellSeconds: List<Int>): Int = paragraphWords.indices.sumOf { index ->
        minOf(paragraphWords[index], dwellSeconds.getOrElse(index) { 0 } * 8)
    }
    fun visited(paragraphWords: List<Int>, dwellSeconds: List<Int>): Set<Int> = paragraphWords.indices.filter { index ->
        dwellSeconds.getOrElse(index) { 0 } >= (paragraphWords[index] / 6).coerceIn(2, 8)
    }.toSet()
    fun canComplete(paragraphWords: List<Int>, dwellSeconds: List<Int>, activeMillis: Long,
        mode: StudyMode, skimParagraphs: Set<Int>): Boolean {
        if (paragraphWords.isEmpty()) return false
        val expected = if (mode == StudyMode.SKIM) skimParagraphs else paragraphWords.indices.toSet()
        if (expected.isEmpty()) return false
        val minimumMillis = (expected.sumOf { paragraphWords.getOrElse(it) { 0 } }.toLong() * 100).coerceIn(3_000, 30_000)
        val covered = visited(paragraphWords, dwellSeconds).count { it in expected }
        return activeMillis >= minimumMillis && covered.toDouble() / expected.size >= .6
    }
}
