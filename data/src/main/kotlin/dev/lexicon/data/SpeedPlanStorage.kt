package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Shared validation for retained source fragments, playback, and history-only restoration. */
internal object SpeedPlanStorage {
    private val KEY = Regex("[a-f0-9]{64}")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun validate(record: SpeedPlanRecord) {
        val plan = record.plan
        require(plan.version == 1 && plan.planKey == SpeedChunker.planKey(plan) && plan.origin in setOf("rules", "ai", "manual"))
        require(if (plan.origin == "manual") plan.basePlanKey?.let { KEY.matches(it) && it != plan.planKey } == true else plan.basePlanKey == null)
        require(record.source.size in 1..3 && record.source.map { it.paragraph } == plan.paragraphIndices)
        require(record.source.sumOf { it.text.length } <= SpeedReadingProtocol.MAX_UNIT_CHARACTERS && sha256(json.encodeToString(record.source)) == record.sourceDigest)
        require(plan.chunks.size in 1..SpeedReadingProtocol.MAX_UNIT_CHARACTERS && plan.chunks.map { it.paragraph }.distinct() == plan.paragraphIndices)
        require(record.source.all { it.start >= 0 && it.text.isNotBlank() })
        require(record.source.zipWithNext().all { (a, b) -> a.paragraph + 1 == b.paragraph && a.start + a.text.length < b.start })
        record.source.forEach { part ->
            val chunks = plan.chunks.filter { it.paragraph == part.paragraph }
            require(chunks.isNotEmpty() && chunks.first().start == part.start && chunks.last().end == part.start + part.text.length)
            require(chunks.zipWithNext().all { (a, b) -> a.end == b.start })
            chunks.forEach { chunk ->
                require(chunk.start >= part.start && chunk.end > chunk.start && chunk.end <= part.start + part.text.length)
                require(chunk.wordCount > 0 && chunk.wordCount == SpeedChunker.wordCount(part.text.substring(chunk.start - part.start, chunk.end - part.start)))
            }
        }
        require(plan.questions.map { it.id }.distinct().size == plan.questions.size)
        require(if (plan.origin == "ai") plan.questions.map { it.paragraph }.sorted() == plan.paragraphIndices
            else if (plan.origin == "manual") plan.questions.isEmpty() || plan.questions.map { it.paragraph }.sorted() == plan.paragraphIndices
            else plan.questions.isEmpty())
        plan.questions.forEach { question ->
            val part = record.source.single { it.paragraph == question.paragraph }
            require(question.prompt.isNotBlank() && question.prompt.length <= 600 && question.options.size == 3 && question.options.distinct().size == 3)
            require(question.options.all { it.isNotBlank() && it.length <= 400 } && question.answerIndex in question.options.indices)
            require(question.evidenceStart >= part.start && question.evidenceEnd > question.evidenceStart && question.evidenceEnd <= part.start + part.text.length)
            require(question.explanation.isNotBlank() && question.explanation.length <= 1000)
        }
        // Revalidate semantic and Unicode boundaries without retaining unselected article text.
        val snapshot = SpeedSourceSnapshot(record.source)
        SpeedChunker.validate(snapshot.compact(plan), snapshot.text)
    }

}
