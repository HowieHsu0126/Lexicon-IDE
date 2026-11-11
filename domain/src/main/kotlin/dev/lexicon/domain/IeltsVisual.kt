package dev.lexicon.domain

import java.math.BigDecimal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Frozen source numbers; their units and relationships still require independent review. */
@Serializable data class IeltsVisualCell(val value: String, @SerialName("source_ref") val sourceRef: IeltsEvidence)
@Serializable data class IeltsVisualSeries(val label: String, val cells: List<IeltsVisualCell>)
@Serializable data class IeltsVisual(
    val schema: String = "lexicon.ielts.visual.v1", val kind: String, val title: String, val unit: String,
    val categories: List<String>, val series: List<IeltsVisualSeries>,
    @SerialName("context_refs") val contextRefs: List<IeltsEvidence>,
)
@Serializable data class IeltsVisualTask(
    val schema: String = "lexicon.ielts.visual-task.v1",
    @SerialName("task_id") val taskId: String,
    @SerialName("request_sha256") val requestSha256: String,
    val status: String = "available", val visual: IeltsVisual, val prompt: String,
    @SerialName("minimum_words") val minimumWords: Int = 150,
    @SerialName("suggested_minutes") val suggestedMinutes: Int = 20,
)
data class IeltsVisualReceipt(val visualSha256: String, val cells: Int, val maximum: BigDecimal,
    val sourceNumbersVerified: Boolean = true, val semanticReviewRequired: Boolean = true)

object IeltsVisualContract {
    const val INSTRUCTION = "Summarise the information by selecting and reporting the main features, and make comparisons where relevant."
    private val decimal = Regex("(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,4})?")
    private val sourceNumber = Regex("(?:0|[1-9][0-9]*|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")
    private val codec = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    fun number(value: String): BigDecimal {
        require(decimal.matches(value)) { "visual_decimal" }
        val parsed = value.toBigDecimal()
        require(parsed.stripTrailingZeros().toPlainString() == value) { "visual_noncanonical_decimal" }
        return parsed
    }
    private fun plain(value: String, maximum: Int) {
        IeltsExerciseValidator.validUnicode(value)
        require(value.isNotBlank() && value.length <= maximum && value == value.trim() &&
            value.none { it.code < 32 || it in "\u0085\u2028\u2029" } &&
            !Regex("https?://|www\\.|(?:mailto|file|javascript):", RegexOption.IGNORE_CASE).containsMatchIn(value)) { "visual_label" }
    }
    private fun unique(values: List<String>) {
        require(values.map { it.trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT) }.distinct().size == values.size) { "visual_duplicate_label" }
    }
    fun validate(request: IeltsExerciseRequest, visual: IeltsVisual): IeltsVisualReceipt {
        IeltsExerciseValidator.validateRequest(request)
        require(visual.schema == "lexicon.ielts.visual.v1" && visual.kind in setOf("grouped_bar", "table"))
        plain(visual.title, 240); plain(visual.unit, 80)
        require(visual.categories.size in 2..8 && visual.series.size in 1..3)
        require(visual.categories.size * visual.series.size in 4..24) { "visual_cell_count" }
        visual.categories.forEach { plain(it, 80) }; unique(visual.categories)
        IeltsExerciseValidator.references(request, visual.contextRefs, maximum = 32)
        val identities = mutableSetOf<Triple<String, Int, Int>>()
        val numbers = mutableListOf<BigDecimal>()
        visual.series.forEach { series ->
            plain(series.label, 80)
            require(series.cells.size == visual.categories.size)
            series.cells.forEach { cell ->
                val numeric = number(cell.value)
                val ref = cell.sourceRef
                IeltsExerciseValidator.references(request, listOf(ref))
                require(ref.quote.length <= 24 && sourceNumber.matches(ref.quote) &&
                    ref.quote.replace(",", "").toBigDecimal().compareTo(numeric) == 0) { "visual_source_value" }
                require(visual.contextRefs.any { it.paragraphId == ref.paragraphId && it.start <= ref.start && ref.end <= it.end &&
                    (it.start < ref.start || ref.end < it.end) }) { "visual_context_missing" }
                val part = request.paragraphs.single { it.paragraphId == ref.paragraphId }
                val before = part.text.substring(0, ref.start - part.start)
                val after = part.text.substring(ref.end - part.start)
                require(!(before.isNotEmpty() && (before.last().isLetterOrDigit() || before.last() in ".,+−-")) &&
                    !(after.isNotEmpty() && (after.first().isLetterOrDigit() || (after.first() in ".," && after.length > 1 && after[1].isDigit())))) { "visual_numeric_boundary" }
                require(identities.add(Triple(ref.paragraphId, ref.start, ref.end))) { "visual_reused_number" }
                numbers += numeric
            }
        }
        unique(visual.series.map { it.label })
        require(numbers.map { it.stripTrailingZeros() }.distinct().size >= 2) { "visual_no_comparison" }
        return IeltsVisualReceipt(IeltsExerciseJson.canonicalSha256(codec.encodeToString(visual)), numbers.size, numbers.max())
    }
    fun taskPrompt(visual: IeltsVisual) = visual.title + "\n\n" + INSTRUCTION
    fun validateTask(request: IeltsExerciseRequest, task: IeltsVisualTask): IeltsVisualReceipt {
        IeltsExerciseValidator.validId(task.taskId)
        require(task.taskId != request.writingTask2Request.taskId && request.readingRequests.none { it.questionId == task.taskId })
        require(task.schema == "lexicon.ielts.visual-task.v1" && task.status == "available")
        require(task.requestSha256 == IeltsExerciseJson.requestHash(request))
        require(task.minimumWords == 150 && task.suggestedMinutes == 20 && task.prompt == taskPrompt(task.visual))
        return validate(request, task.visual)
    }
    fun encode(value: IeltsVisualTask): String = codec.encodeToString(value)
    fun decode(text: String): IeltsVisualTask {
        // Existing strict parser rejects duplicate keys, coercion and unpaired surrogates.
        val canonical = IeltsExerciseJson.canonicalJson(text)
        val value = codec.decodeFromString<IeltsVisualTask>(canonical)
        require(canonical == IeltsExerciseJson.canonicalJson(encode(value)))
        return value
    }
}
