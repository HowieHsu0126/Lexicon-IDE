@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.lexicon.domain

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Original practice only. This module does not authenticate a gateway or predict an IELTS band. */
object IeltsContract {
    const val REQUEST = "lexicon.ielts.request.v1"
    const val PACKAGE = "lexicon.ielts.package.v1"
    const val SUBMISSION = "lexicon.ielts.submission.v1"
    const val CANDIDATE = "lexicon.ielts.candidate-view.v1"
    const val WORD_RULE = "lexicon-extractive-v1"
    const val LABEL = "AI 模拟练习"
    const val MAX_TEXT = 24_000
    const val MAX_JSON_BYTES = 1_000_000
    const val MAX_TIMESTAMP = 9_007_199_254_740_991L
}

@Serializable data class IeltsParagraph(
    @SerialName("paragraph_id") val paragraphId: String, val start: Int, val end: Int, val text: String,
)
@Serializable data class IeltsEvidence(
    @SerialName("paragraph_id") val paragraphId: String, val start: Int, val end: Int, val quote: String,
)
@Serializable enum class IeltsStyleScope {
    @SerialName("official_public_sample_format") PUBLIC_SAMPLE_FORMAT,
    @SerialName("official_test_format") TEST_FORMAT,
}
@Serializable data class IeltsStyleSource(
    @SerialName("source_id") val sourceId: String, val sha256: String, val summary: String, val scope: IeltsStyleScope,
)
@Serializable enum class IeltsWritingType { @SerialName("extent_of_agreement") EXTENT_OF_AGREEMENT }
@Serializable enum class IeltsChoice { A, B, C, D }
@Serializable enum class IeltsInformationAnswer(val wire: String) {
    @SerialName("true") TRUE("true"), @SerialName("false") FALSE("false"), @SerialName("not_given") NOT_GIVEN("not_given"),
}
@Serializable enum class IeltsViewAnswer(val wire: String) {
    @SerialName("yes") YES("yes"), @SerialName("no") NO("no"), @SerialName("not_given") NOT_GIVEN("not_given"),
}
enum class IeltsReadingType { MULTIPLE_CHOICE, IDENTIFYING_INFORMATION, IDENTIFYING_WRITERS_VIEWS, SHORT_TEXT_COMPLETION }

@Serializable sealed class IeltsReadingRequest { abstract val questionId: String }
@Serializable @SerialName("multiple_choice") data class IeltsChoiceRequest(
    @SerialName("question_id") override val questionId: String,
) : IeltsReadingRequest()
@Serializable @SerialName("identifying_information") data class IeltsInformationRequest(
    @SerialName("question_id") override val questionId: String,
) : IeltsReadingRequest()
@Serializable @SerialName("identifying_writers_views") data class IeltsViewRequest(
    @SerialName("question_id") override val questionId: String,
) : IeltsReadingRequest()
@Serializable @SerialName("short_text_completion") data class IeltsCompletionRequest(
    @SerialName("question_id") override val questionId: String,
    @SerialName("max_words") val maxWords: Int, @SerialName("allow_numbers") val allowNumbers: Boolean,
) : IeltsReadingRequest()
@Serializable data class IeltsWritingRequest(
    @SerialName("task_id") val taskId: String, @SerialName("task_type") val taskType: IeltsWritingType,
)
@Serializable data class IeltsVisualRequest(@SerialName("task_id") val taskId: String,
    val policy: String = "source_numeric_visual_v1")
@Serializable data class IeltsExerciseRequest(
    val schema: String, @SerialName("request_id") val requestId: String, @SerialName("article_id") val articleId: String,
    @SerialName("body_hash") val bodyHash: String, val paragraphs: List<IeltsParagraph>,
    @SerialName("style_blueprint_sha256") val styleBlueprintSha256: String,
    @SerialName("style_sources") val styleSources: List<IeltsStyleSource>,
    @SerialName("reading_requests") val readingRequests: List<IeltsReadingRequest>,
    @SerialName("writing_task2_request") val writingTask2Request: IeltsWritingRequest,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("writing_task1_request") val writingTask1Request: IeltsVisualRequest? = null,
)

@Serializable data class IeltsOption(@SerialName("option_id") val optionId: IeltsChoice, val text: String)
@Serializable sealed class IeltsReadingQuestion {
    abstract val questionId: String
    abstract val prompt: String
    abstract val explanation: String
    abstract val sourceRefs: List<IeltsEvidence>
}
@Serializable @SerialName("multiple_choice") data class IeltsChoiceQuestion(
    @SerialName("question_id") override val questionId: String, override val prompt: String,
    val options: List<IeltsOption>, @SerialName("number_of_answers") val numberOfAnswers: Int,
    val answer: IeltsChoice, override val explanation: String,
    @SerialName("source_refs") override val sourceRefs: List<IeltsEvidence>,
) : IeltsReadingQuestion()
@Serializable @SerialName("identifying_information") data class IeltsInformationQuestion(
    @SerialName("question_id") override val questionId: String, override val prompt: String,
    val answer: IeltsInformationAnswer, override val explanation: String,
    @SerialName("source_refs") override val sourceRefs: List<IeltsEvidence>,
    @SerialName("reviewed_paragraph_ids") val reviewedParagraphIds: List<String>? = null,
    @SerialName("absence_justification") val absenceJustification: String? = null,
) : IeltsReadingQuestion()
@Serializable @SerialName("identifying_writers_views") data class IeltsViewQuestion(
    @SerialName("question_id") override val questionId: String, override val prompt: String,
    val answer: IeltsViewAnswer, override val explanation: String,
    @SerialName("source_refs") override val sourceRefs: List<IeltsEvidence>,
    @SerialName("reviewed_paragraph_ids") val reviewedParagraphIds: List<String>? = null,
    @SerialName("absence_justification") val absenceJustification: String? = null,
) : IeltsReadingQuestion()
@Serializable @SerialName("short_text_completion") data class IeltsCompletionQuestion(
    @SerialName("question_id") override val questionId: String, override val prompt: String,
    @SerialName("max_words") val maxWords: Int, @SerialName("allow_numbers") val allowNumbers: Boolean,
    @SerialName("normalization_rule") val normalizationRule: String, val answer: String,
    override val explanation: String, @SerialName("source_refs") override val sourceRefs: List<IeltsEvidence>,
) : IeltsReadingQuestion()
@Serializable data class IeltsWritingTask2(
    @SerialName("task_id") val taskId: String, @SerialName("task_type") val taskType: IeltsWritingType,
    val prompt: String, @SerialName("minimum_words") val minimumWords: Int,
    @SerialName("suggested_minutes") val suggestedMinutes: Int, @SerialName("source_refs") val sourceRefs: List<IeltsEvidence>,
)
@Serializable data class IeltsWritingTask1(val status: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val reason: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val task: IeltsVisualTask? = null)
typealias IeltsUnavailableTask1 = IeltsWritingTask1
@Serializable data class IeltsExercisePackage(
    val schema: String, @SerialName("request_id") val requestId: String, @SerialName("article_id") val articleId: String,
    @SerialName("body_hash") val bodyHash: String, @SerialName("request_sha256") val requestSha256: String,
    val label: String, @SerialName("style_source_ids") val styleSourceIds: List<String>,
    @SerialName("reading_questions") val readingQuestions: List<IeltsReadingQuestion>,
    @SerialName("writing_task2") val writingTask2: IeltsWritingTask2,
    @SerialName("writing_task1") val writingTask1: IeltsUnavailableTask1,
)

/** These presentation types cannot contain reference keys, explanations or answer evidence. */
@Serializable sealed class IeltsCandidateQuestion { abstract val questionId: String; abstract val prompt: String }
@Serializable @SerialName("multiple_choice") data class IeltsCandidateChoice(
    @SerialName("question_id") override val questionId: String, override val prompt: String,
    val options: List<IeltsOption>, @SerialName("number_of_answers") val numberOfAnswers: Int,
) : IeltsCandidateQuestion()
@Serializable @SerialName("identifying_information") data class IeltsCandidateInformation(
    @SerialName("question_id") override val questionId: String, override val prompt: String,
    @SerialName("response_values") val responseValues: List<IeltsInformationAnswer>,
) : IeltsCandidateQuestion()
@Serializable @SerialName("identifying_writers_views") data class IeltsCandidateViewQuestion(
    @SerialName("question_id") override val questionId: String, override val prompt: String,
    @SerialName("response_values") val responseValues: List<IeltsViewAnswer>,
) : IeltsCandidateQuestion()
@Serializable @SerialName("short_text_completion") data class IeltsCandidateCompletion(
    @SerialName("question_id") override val questionId: String, override val prompt: String,
    @SerialName("max_words") val maxWords: Int, @SerialName("allow_numbers") val allowNumbers: Boolean,
    @SerialName("normalization_rule") val normalizationRule: String,
) : IeltsCandidateQuestion()
@Serializable data class IeltsCandidateWriting(
    @SerialName("task_id") val taskId: String, @SerialName("task_type") val taskType: IeltsWritingType,
    val prompt: String, @SerialName("minimum_words") val minimumWords: Int,
    @SerialName("suggested_minutes") val suggestedMinutes: Int,
)
@Serializable data class IeltsCandidateView(
    val schema: String, @SerialName("request_id") val requestId: String, @SerialName("article_id") val articleId: String,
    @SerialName("body_hash") val bodyHash: String, @SerialName("package_sha256") val packageSha256: String,
    val label: String, val scope: String, @SerialName("question_count") val questionCount: Int,
    val paragraphs: List<IeltsParagraph>, @SerialName("reading_questions") val readingQuestions: List<IeltsCandidateQuestion>,
    @SerialName("writing_task2") val writingTask2: IeltsCandidateWriting,
    @SerialName("writing_task1") val writingTask1: IeltsUnavailableTask1,
    @SerialName("style_source_ids") val styleSourceIds: List<String>, @SerialName("reference_scope") val referenceScope: String,
)
@Serializable data class IeltsResponse(@SerialName("question_id") val questionId: String, val answer: String)
@Serializable data class IeltsSubmission(
    val schema: String, @SerialName("attempt_id") val attemptId: String, @SerialName("request_id") val requestId: String,
    @SerialName("package_sha256") val packageSha256: String, val status: String,
    @SerialName("submitted_at") val submittedAt: Long, @SerialName("reading_answers") val readingAnswers: List<IeltsResponse>,
    @SerialName("writing_task2_text") val writingTask2Text: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("writing_task1_text") val writingTask1Text: String? = null,
)

data class IeltsValidationReceipt(val requestSha256: String, val packageSha256: String, val questionCount: Int,
    val semanticReviewRequired: Boolean = true, val sourceScope: String = "supplied_paragraphs_only",
    val fullBodyHashIndependentlyVerified: Boolean = false, val officialSourceProvenanceIndependentlyVerified: Boolean = false,
    val recentThreeYearStyleCoverage: Boolean = false)
data class IeltsAnswerItem(val questionId: String, val kind: IeltsReadingType, val submittedAnswer: String,
    val referenceAnswer: String, val explanation: String, val sourceRefs: List<IeltsEvidence>,
    val reviewedParagraphIds: List<String>? = null, val absenceJustification: String? = null,
    val wordCount: Int? = null, val withinWordLimit: Boolean? = null, val numberRuleSatisfied: Boolean? = null)
data class IeltsAnswerView(val attemptId: String, val packageSha256: String, val submissionSha256: String,
    val readingAnswers: List<IeltsAnswerItem>, val writingWords: Int, val writingMeetsMinimum: Boolean,
    val semanticReviewRequired: Boolean = true, val officialBandScore: Double? = null,
    val writingTask1Words: Int? = null, val writingTask1MeetsMinimum: Boolean? = null)

/** Integer-only contract canonicalization, not arbitrary JSON/JCS canonicalization. */
object IeltsExerciseJson {
    private val codec = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = false; classDiscriminator = "type" }
    fun decodeRequest(text: String): IeltsExerciseRequest = decode<IeltsExerciseRequest>(text).also(IeltsExerciseValidator::validateRequest)
    fun decodePackage(text: String): IeltsExercisePackage = decode(text)
    fun decodeSubmission(text: String): IeltsSubmission = decode(text)
    fun decodeCandidate(text: String): IeltsCandidateView = decode(text)
    fun encodeRequest(value: IeltsExerciseRequest): String = codec.encodeToString(value)
    fun encodePackage(value: IeltsExercisePackage): String = codec.encodeToString(value)
    fun encodeSubmission(value: IeltsSubmission): String = codec.encodeToString(value)
    fun encodeCandidate(value: IeltsCandidateView): String = codec.encodeToString(value)
    fun requestHash(value: IeltsExerciseRequest): String = canonicalSha256(codec.encodeToJsonElement(value))
    fun packageHash(value: IeltsExercisePackage): String = canonicalSha256(codec.encodeToJsonElement(value))
    fun submissionHash(value: IeltsSubmission): String = canonicalSha256(codec.encodeToJsonElement(value))
    fun canonicalJson(text: String): String = canonical(parseStrict(text))
    fun canonicalSha256(text: String): String = sha(canonicalJson(text))
    fun canonicalSha256(value: JsonElement): String = sha(canonical(value))

    private inline fun <reified T> decode(text: String): T {
        val input = parseStrict(text)
        val result = codec.decodeFromJsonElement<T>(input)
        // Reject primitive coercion, undeclared explicit nulls and default-filled missing fields.
        require(canonical(input) == canonical(codec.encodeToJsonElement(result))) { "IELTS JSON fields or primitive types changed while decoding" }
        return result
    }

    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun parseStrict(text: String): JsonElement {
        require(text.toByteArray(Charsets.UTF_8).size <= IeltsContract.MAX_JSON_BYTES) { "IELTS JSON exceeds byte limit" }
        return StrictJson(text).read()
    }
    private fun canonical(value: JsonElement): String = buildString { appendCanonical(value, 0) }
    private fun StringBuilder.appendCanonical(value: JsonElement, depth: Int) {
        require(depth <= 32) { "IELTS JSON nesting exceeds limit" }
        when (value) {
            JsonNull -> append("null")
            is JsonObject -> {
                append('{')
                value.keys.sortedWith(Comparator { left, right -> compareCodePoints(left, right) }).forEachIndexed { index, key ->
                    if (index > 0) append(',')
                    appendQuoted(key); append(':'); appendCanonical(value.getValue(key), depth + 1)
                }
                append('}')
            }
            is JsonArray -> { append('['); value.forEachIndexed { index, item ->
                if (index > 0) append(','); appendCanonical(item, depth + 1)
            }; append(']') }
            is JsonPrimitive -> if (value.isString) appendQuoted(value.content) else {
                if (value.content == "true" || value.content == "false") append(value.content)
                else append(integerToken(value.content))
            }
        }
    }
    private fun integerToken(value: String): String {
        require(value.length <= 17 && Regex("-?(0|[1-9][0-9]*)").matches(value)) { "IELTS contract supports integer JSON numbers only" }
        val integer = BigInteger(value)
        require(integer.abs() <= BigInteger.valueOf(IeltsContract.MAX_TIMESTAMP)) { "IELTS integer exceeds contract range" }
        return integer.toString()
    }
    private fun StringBuilder.appendQuoted(value: String) {
        IeltsExerciseValidator.validUnicode(value)
        append('"')
        value.forEach { char -> when (char) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\b' -> append("\\b"); '\u000C' -> append("\\f")
            '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (char.code < 32) append("\\u%04x".format(char.code)) else append(char)
        } }
        append('"')
    }
    private fun compareCodePoints(left: String, right: String): Int {
        var a = 0; var b = 0
        while (a < left.length && b < right.length) {
            val x = left.codePointAt(a); val y = right.codePointAt(b)
            if (x != y) return x.compareTo(y)
            a += Character.charCount(x); b += Character.charCount(y)
        }
        return (left.length - a).compareTo(right.length - b)
    }

    /** Small strict reader preserves duplicate-field checks before serialization can discard them. */
    private class StrictJson(private val input: String) {
        private var position = 0
        fun read(): JsonElement { val value = value(0); whitespace(); require(position == input.length); return value }
        private fun whitespace() { while (position < input.length && input[position] in " \n\r\t") position++ }
        private fun take(char: Char): Boolean { whitespace(); return if (position < input.length && input[position] == char) { position++; true } else false }
        private fun value(depth: Int): JsonElement {
            require(depth <= 32); whitespace(); require(position < input.length)
            return when (input[position]) {
                '{' -> {
                    position++; val fields = linkedMapOf<String, JsonElement>()
                    if (!take('}')) while (true) {
                        whitespace(); require(position < input.length && input[position] == '"')
                        val key = string(); require(!fields.containsKey(key)) { "Duplicate IELTS JSON field" }
                        require(take(':')); fields[key] = value(depth + 1)
                        if (take('}')) break
                        require(take(','))
                    }
                    JsonObject(fields)
                }
                '[' -> {
                    position++; val items = mutableListOf<JsonElement>()
                    if (!take(']')) while (true) {
                        require(items.size < 10_000); items += value(depth + 1)
                        if (take(']')) break
                        require(take(','))
                    }
                    JsonArray(items)
                }
                '"' -> JsonPrimitive(string())
                't' -> literal("true", JsonPrimitive(true))
                'f' -> literal("false", JsonPrimitive(false))
                'n' -> literal("null", JsonNull)
                else -> {
                    val start = position
                    if (input[position] == '-') position++
                    while (position < input.length && input[position] in '0'..'9') position++
                    require(position > start)
                    val token = integerToken(input.substring(start, position))
                    require(position == input.length || input[position] in ",]} \n\r\t") { "Non-integer IELTS JSON number" }
                    JsonPrimitive(token.toLong())
                }
            }
        }
        private fun literal(token: String, result: JsonElement): JsonElement {
            require(input.startsWith(token, position)); position += token.length; return result
        }
        private fun string(): String {
            require(input[position++] == '"')
            val result = StringBuilder()
            while (position < input.length) {
                val char = input[position++]
                if (char == '"') return result.toString().also(IeltsExerciseValidator::validUnicode)
                if (char == '\\') {
                    require(position < input.length)
                    result.append(when (val escape = input[position++]) {
                        '"', '\\', '/' -> escape; 'b' -> '\b'; 'f' -> '\u000C'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> { require(position + 4 <= input.length)
                            val digits = input.substring(position, position + 4); require(digits.all { it in "0123456789abcdefABCDEF" })
                            position += 4; digits.toInt(16).toChar() }
                        else -> error("Invalid IELTS JSON escape")
                    })
                } else { require(char.code >= 32); result.append(char) }
            }
            error("Unterminated IELTS JSON string")
        }
    }
}

object IeltsExerciseValidator {
    private val id = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    private val hash = Regex("[a-f0-9]{64}")
    private val urls = Regex("https?://|www\\.|(?:mailto|file|javascript):", RegexOption.IGNORE_CASE)
    private val spaces = Regex("[\\p{Z}\\t\\n\\r\\u0085]+")
    private val words = Regex("\\p{Nd}+(?:[.,]\\p{Nd}+)*|[\\p{L}\\p{N}]+(?:['’\\-‑][\\p{L}\\p{N}]+)*")

    fun validUnicode(value: String) {
        var i = 0
        while (i < value.length) {
            val char = value[i]
            if (Character.isHighSurrogate(char)) { require(i + 1 < value.length && Character.isLowSurrogate(value[i + 1])); i += 2 }
            else { require(!Character.isLowSurrogate(char)) { "Unpaired IELTS Unicode surrogate" }; i++ }
        }
    }
    private fun text(value: String, max: Int, empty: Boolean = false, source: Boolean = false) {
        validUnicode(value)
        require(value.length <= max && (empty || value.isNotBlank())) { "IELTS text is empty or exceeds its bound" }
        require(value.none { it.code < 32 && it !in "\n\r\t" }) { "IELTS text contains control characters" }
        require(source || !urls.containsMatchIn(value)) { "Generated IELTS text must use source IDs instead of URLs" }
    }
    fun validId(value: String) { require(id.matches(value)) { "Invalid IELTS identifier" } }
    fun wordCount(value: String): Int { text(value, 30_000, empty = true, source = true); return words.findAll(value).count() }
    /** Local UTF-16 range; punctuation may surround complete WORD_RULE tokens. */
    fun completeSourceTokens(value: String, start: Int, end: Int): Boolean {
        text(value, IeltsContract.MAX_TEXT, source = true)
        require(start >= 0 && end > start && end <= value.length)
        require(boundary(value, start) && boundary(value, end)) { "IELTS range splits a surrogate pair" }
        var found = false
        for (token in words.findAll(value)) {
            val first = token.range.first
            val last = token.range.last + 1
            if (first < end && last > start) {
                if (first < start || last > end) return false
                found = true
            }
        }
        return found
    }
    private fun hasNumber(value: String): Boolean = value.codePoints().anyMatch { Character.isDigit(it) }
    // Unicode 13.0 casefold expansions/differences, frozen from Python 3.9.6.
    // Java 17 lowercase supplies the unchanged simple mappings; this table also
    // preserves dotless-i, final-sigma and Cherokee equivalence correctly.
    private val caseFoldOverrides by lazy {
        (
        "b5:3bc|df:73,73|149:2bc,6e|17f:73|1f0:6a,30c|345:3b9|390:3b9,308,301|3b0:3c5,308,301|3c2:3c3|3d0:3b2|3d1:3b8|3d5:3c6|3d6:3c0|3f0:3ba|3f1:3c1|" +
        "3f5:3b5|587:565,582|13a0:13a0|13a1:13a1|13a2:13a2|13a3:13a3|13a4:13a4|13a5:13a5|13a6:13a6|13a7:13a7|13a8:13a8|13a9:13a9|13aa:13aa|13ab:13ab|13ac:13ac|" +
        "13ad:13ad|13ae:13ae|13af:13af|13b0:13b0|13b1:13b1|13b2:13b2|13b3:13b3|13b4:13b4|13b5:13b5|13b6:13b6|13b7:13b7|13b8:13b8|13b9:13b9|13ba:13ba|13bb:13bb|" +
        "13bc:13bc|13bd:13bd|13be:13be|13bf:13bf|13c0:13c0|13c1:13c1|13c2:13c2|13c3:13c3|13c4:13c4|13c5:13c5|13c6:13c6|13c7:13c7|13c8:13c8|13c9:13c9|13ca:13ca|" +
        "13cb:13cb|13cc:13cc|13cd:13cd|13ce:13ce|13cf:13cf|13d0:13d0|13d1:13d1|13d2:13d2|13d3:13d3|13d4:13d4|13d5:13d5|13d6:13d6|13d7:13d7|13d8:13d8|13d9:13d9|" +
        "13da:13da|13db:13db|13dc:13dc|13dd:13dd|13de:13de|13df:13df|13e0:13e0|13e1:13e1|13e2:13e2|13e3:13e3|13e4:13e4|13e5:13e5|13e6:13e6|13e7:13e7|13e8:13e8|" +
        "13e9:13e9|13ea:13ea|13eb:13eb|13ec:13ec|13ed:13ed|13ee:13ee|13ef:13ef|13f0:13f0|13f1:13f1|13f2:13f2|13f3:13f3|13f4:13f4|13f5:13f5|13f8:13f0|13f9:13f1|" +
        "13fa:13f2|13fb:13f3|13fc:13f4|13fd:13f5|1c80:432|1c81:434|1c82:43e|1c83:441|1c84:442|1c85:442|1c86:44a|1c87:463|1c88:a64b|1e96:68,331|1e97:74,308|" +
        "1e98:77,30a|1e99:79,30a|1e9a:61,2be|1e9b:1e61|1e9e:73,73|1f50:3c5,313|1f52:3c5,313,300|1f54:3c5,313,301|1f56:3c5,313,342|1f80:1f00,3b9|1f81:1f01,3b9|1f82:1f02,3b9|1f83:1f03,3b9|1f84:1f04,3b9|1f85:1f05,3b9|" +
        "1f86:1f06,3b9|1f87:1f07,3b9|1f88:1f00,3b9|1f89:1f01,3b9|1f8a:1f02,3b9|1f8b:1f03,3b9|1f8c:1f04,3b9|1f8d:1f05,3b9|1f8e:1f06,3b9|1f8f:1f07,3b9|1f90:1f20,3b9|1f91:1f21,3b9|1f92:1f22,3b9|1f93:1f23,3b9|1f94:1f24,3b9|" +
        "1f95:1f25,3b9|1f96:1f26,3b9|1f97:1f27,3b9|1f98:1f20,3b9|1f99:1f21,3b9|1f9a:1f22,3b9|1f9b:1f23,3b9|1f9c:1f24,3b9|1f9d:1f25,3b9|1f9e:1f26,3b9|1f9f:1f27,3b9|1fa0:1f60,3b9|1fa1:1f61,3b9|1fa2:1f62,3b9|1fa3:1f63,3b9|" +
        "1fa4:1f64,3b9|1fa5:1f65,3b9|1fa6:1f66,3b9|1fa7:1f67,3b9|1fa8:1f60,3b9|1fa9:1f61,3b9|1faa:1f62,3b9|1fab:1f63,3b9|1fac:1f64,3b9|1fad:1f65,3b9|1fae:1f66,3b9|1faf:1f67,3b9|1fb2:1f70,3b9|1fb3:3b1,3b9|1fb4:3ac,3b9|" +
        "1fb6:3b1,342|1fb7:3b1,342,3b9|1fbc:3b1,3b9|1fbe:3b9|1fc2:1f74,3b9|1fc3:3b7,3b9|1fc4:3ae,3b9|1fc6:3b7,342|1fc7:3b7,342,3b9|1fcc:3b7,3b9|1fd2:3b9,308,300|1fd3:3b9,308,301|1fd6:3b9,342|1fd7:3b9,308,342|1fe2:3c5,308,300|" +
        "1fe3:3c5,308,301|1fe4:3c1,313|1fe6:3c5,342|1fe7:3c5,308,342|1ff2:1f7c,3b9|1ff3:3c9,3b9|1ff4:3ce,3b9|1ff6:3c9,342|1ff7:3c9,342,3b9|1ffc:3c9,3b9|ab70:13a0|ab71:13a1|ab72:13a2|ab73:13a3|ab74:13a4|" +
        "ab75:13a5|ab76:13a6|ab77:13a7|ab78:13a8|ab79:13a9|ab7a:13aa|ab7b:13ab|ab7c:13ac|ab7d:13ad|ab7e:13ae|ab7f:13af|ab80:13b0|ab81:13b1|ab82:13b2|ab83:13b3|" +
        "ab84:13b4|ab85:13b5|ab86:13b6|ab87:13b7|ab88:13b8|ab89:13b9|ab8a:13ba|ab8b:13bb|ab8c:13bc|ab8d:13bd|ab8e:13be|ab8f:13bf|ab90:13c0|ab91:13c1|ab92:13c2|" +
        "ab93:13c3|ab94:13c4|ab95:13c5|ab96:13c6|ab97:13c7|ab98:13c8|ab99:13c9|ab9a:13ca|ab9b:13cb|ab9c:13cc|ab9d:13cd|ab9e:13ce|ab9f:13cf|aba0:13d0|aba1:13d1|" +
        "aba2:13d2|aba3:13d3|aba4:13d4|aba5:13d5|aba6:13d6|aba7:13d7|aba8:13d8|aba9:13d9|abaa:13da|abab:13db|abac:13dc|abad:13dd|abae:13de|abaf:13df|abb0:13e0|" +
        "abb1:13e1|abb2:13e2|abb3:13e3|abb4:13e4|abb5:13e5|abb6:13e6|abb7:13e7|abb8:13e8|abb9:13e9|abba:13ea|abbb:13eb|abbc:13ec|abbd:13ed|abbe:13ee|abbf:13ef|" +
        "fb00:66,66|fb01:66,69|fb02:66,6c|fb03:66,66,69|fb04:66,66,6c|fb05:73,74|fb06:73,74|fb13:574,576|fb14:574,565|fb15:574,56b|fb16:57e,576|fb17:574,56d"
        ).split('|').associate { entry ->
            val pair = entry.split(':')
            pair[0].toInt(16) to pair[1].split(',').joinToString("") { String(Character.toChars(it.toInt(16))) }
        }
    }
    private fun normalizedOption(value: String): String = spaces.split(value).filter { it.isNotEmpty() }.joinToString(" ")
        .codePoints().toArray().joinToString("") { code ->
            caseFoldOverrides[code] ?: String(Character.toChars(code)).lowercase(Locale.ROOT)
        }
    fun validateRequest(request: IeltsExerciseRequest) {
        require(request.schema == IeltsContract.REQUEST)
        validId(request.requestId); validId(request.articleId)
        require(hash.matches(request.bodyHash) && hash.matches(request.styleBlueprintSha256))
        require(request.paragraphs.size in 1..80 && request.paragraphs.map { it.paragraphId }.distinct().size == request.paragraphs.size)
        var previousEnd = -1
        request.paragraphs.forEach { part ->
            validId(part.paragraphId); text(part.text, IeltsContract.MAX_TEXT, source = true)
            require(part.start in 0..10_000_000 && part.end > part.start && part.end <= 10_000_000 && part.end - part.start == part.text.length)
            require(part.start >= previousEnd); previousEnd = part.end
        }
        require(request.paragraphs.sumOf { it.text.length.toLong() } <= IeltsContract.MAX_TEXT)
        require(request.styleSources.size in 1..12 && request.styleSources.map { it.sourceId }.distinct().size == request.styleSources.size)
        request.styleSources.forEach { source -> validId(source.sourceId); require(hash.matches(source.sha256)); text(source.summary, 4000, source = true) }
        require(request.styleSources.sumOf { it.summary.length } <= 16_000)
        require(request.readingRequests.size in 1..20 && request.readingRequests.map { it.questionId }.distinct().size == request.readingRequests.size)
        request.readingRequests.forEach { question -> validId(question.questionId)
            if (question is IeltsCompletionRequest) require(question.maxWords in 1..8)
        }
        validId(request.writingTask2Request.taskId)
        require(request.readingRequests.none { it.questionId == request.writingTask2Request.taskId })
        request.writingTask1Request?.let {
            validId(it.taskId)
            require(it.policy == "source_numeric_visual_v1" && it.taskId != request.writingTask2Request.taskId &&
                request.readingRequests.none { q -> q.questionId == it.taskId })
        }
    }

    fun validatePackage(request: IeltsExerciseRequest, exercise: IeltsExercisePackage): IeltsValidationReceipt {
        validateRequest(request)
        require(exercise.schema == IeltsContract.PACKAGE && exercise.label == IeltsContract.LABEL)
        require(exercise.requestId == request.requestId && exercise.articleId == request.articleId && exercise.bodyHash == request.bodyHash)
        val requestHash = IeltsExerciseJson.requestHash(request)
        require(exercise.requestSha256 == requestHash) { "IELTS request digest mismatch" }
        require(exercise.styleSourceIds.isNotEmpty() && exercise.styleSourceIds.distinct().size == exercise.styleSourceIds.size &&
            exercise.styleSourceIds.all { used -> request.styleSources.any { it.sourceId == used } })
        require(exercise.readingQuestions.size == request.readingRequests.size)
        exercise.readingQuestions.zip(request.readingRequests).forEach { (question, wanted) ->
            require(question.questionId == wanted.questionId && kind(question) == kind(wanted)) { "IELTS question ID or type mismatch" }
            text(question.prompt, 1200); text(question.explanation, 1600)
            when (question) {
                is IeltsChoiceQuestion -> {
                    require(question.numberOfAnswers == 1 && question.options.map { it.optionId } == IeltsChoice.entries)
                    question.options.forEach { text(it.text, 600) }
                    require(question.options.map { normalizedOption(it.text) }.distinct().size == 4) { "Duplicate IELTS option text" }
                    references(request, question.sourceRefs)
                }
                is IeltsInformationQuestion -> judgement(request, question.answer == IeltsInformationAnswer.NOT_GIVEN,
                    question.sourceRefs, question.reviewedParagraphIds, question.absenceJustification)
                is IeltsViewQuestion -> judgement(request, question.answer == IeltsViewAnswer.NOT_GIVEN,
                    question.sourceRefs, question.reviewedParagraphIds, question.absenceJustification)
                is IeltsCompletionQuestion -> {
                    require(wanted is IeltsCompletionRequest && question.maxWords == wanted.maxWords && question.allowNumbers == wanted.allowNumbers)
                    require(question.normalizationRule == IeltsContract.WORD_RULE && question.maxWords in 1..8)
                    require(Regex("____").findAll(question.prompt).count() == 1 && !Regex("_{5,}").containsMatchIn(question.prompt))
                    text(question.answer, 200)
                    require(wordCount(question.answer) in 1..question.maxWords && (question.allowNumbers || !hasNumber(question.answer)))
                    references(request, question.sourceRefs)
                    val answerRefs = question.sourceRefs.filter { it.quote == question.answer }
                    require(answerRefs.isNotEmpty()) { "IELTS completion is not an exact source phrase" }
                    require(answerRefs.all { ref ->
                        val paragraph = request.paragraphs.single { it.paragraphId == ref.paragraphId }
                        completeSourceTokens(paragraph.text, ref.start - paragraph.start, ref.end - paragraph.start)
                    }) { "IELTS completion reference cuts a source token" }
                }
            }
        }
        val writing = exercise.writingTask2
        require(writing.taskId == request.writingTask2Request.taskId && writing.taskType == request.writingTask2Request.taskType)
        require(writing.minimumWords == 250 && writing.suggestedMinutes == 40)
        text(writing.prompt, 1800); references(request, writing.sourceRefs)
        val task1 = exercise.writingTask1
        if (request.writingTask1Request == null) {
            require(task1 == IeltsUnavailableTask1("unavailable", "no_verified_visual"))
        } else if (task1.status == "unavailable") {
            require(task1 == IeltsWritingTask1("unavailable", "insufficient_comparable_data"))
        } else {
            require(task1.status == "available" && task1.reason == null)
            val task = requireNotNull(task1.task)
            require(task.taskId == request.writingTask1Request.taskId)
            IeltsVisualContract.validateTask(request, task)
        }
        require(IeltsExerciseJson.encodePackage(exercise).toByteArray(Charsets.UTF_8).size <= IeltsContract.MAX_JSON_BYTES)
        return IeltsValidationReceipt(requestHash, IeltsExerciseJson.packageHash(exercise), exercise.readingQuestions.size)
    }

    private fun judgement(request: IeltsExerciseRequest, notGiven: Boolean, refs: List<IeltsEvidence>,
        reviewedIds: List<String>?, absence: String?) {
        if (notGiven) {
            require(refs.isEmpty() && reviewedIds == request.paragraphs.map { it.paragraphId })
            text(requireNotNull(absence), 1600)
        } else { require(reviewedIds == null && absence == null); references(request, refs) }
    }
    fun references(request: IeltsExerciseRequest, refs: List<IeltsEvidence>, maximum: Int = 5) {
        require(refs.size in 1..maximum && refs.map { Triple(it.paragraphId, it.start, it.end) }.distinct().size == refs.size)
        refs.forEach { ref ->
            val paragraph = requireNotNull(request.paragraphs.singleOrNull { it.paragraphId == ref.paragraphId })
            require(ref.start >= paragraph.start && ref.end > ref.start && ref.end <= paragraph.end)
            val start = ref.start - paragraph.start; val end = ref.end - paragraph.start
            require(boundary(paragraph.text, start) && boundary(paragraph.text, end)) { "IELTS range splits a surrogate pair" }
            text(ref.quote, IeltsContract.MAX_TEXT, source = true)
            require(paragraph.text.substring(start, end) == ref.quote) { "IELTS quote differs from the exact source range" }
        }
    }
    private fun boundary(value: String, offset: Int): Boolean = offset in 0..value.length &&
        (offset == value.length || !Character.isLowSurrogate(value[offset]))

    fun validateResponse(question: IeltsReadingQuestion, response: String) {
        when (question) {
            is IeltsChoiceQuestion -> require(response in IeltsChoice.entries.map { it.name })
            is IeltsInformationQuestion -> require(response in IeltsInformationAnswer.entries.map { it.wire })
            is IeltsViewQuestion -> require(response in IeltsViewAnswer.entries.map { it.wire })
            // Retain an over-limit student response for feedback, just as the Python contract does.
            is IeltsCompletionQuestion -> text(response, 200)
        }
    }
    fun validateSubmission(request: IeltsExerciseRequest, exercise: IeltsExercisePackage, submission: IeltsSubmission) {
        val receipt = validatePackage(request, exercise)
        validId(submission.attemptId)
        require(submission.schema == IeltsContract.SUBMISSION && submission.status == "submitted")
        require(submission.requestId == request.requestId && submission.packageSha256 == receipt.packageSha256)
        require(submission.submittedAt in 0..IeltsContract.MAX_TIMESTAMP)
        require(submission.readingAnswers.size == exercise.readingQuestions.size)
        submission.readingAnswers.zip(exercise.readingQuestions).forEach { (answer, question) ->
            require(answer.questionId == question.questionId); validateResponse(question, answer.answer)
        }
        text(submission.writingTask2Text, 30_000, empty = true, source = true)
        if (exercise.writingTask1.status == "available") text(requireNotNull(submission.writingTask1Text), 30_000, empty = true, source = true)
        else require(submission.writingTask1Text == null)
    }
    fun candidateView(request: IeltsExerciseRequest, exercise: IeltsExercisePackage): IeltsCandidateView {
        val receipt = validatePackage(request, exercise)
        val writing = exercise.writingTask2
        return IeltsCandidateView(IeltsContract.CANDIDATE, request.requestId, request.articleId, request.bodyHash, receipt.packageSha256,
            IeltsContract.LABEL, "single_passage_practice", exercise.readingQuestions.size, request.paragraphs.toList(),
            exercise.readingQuestions.map { question -> when (question) {
                is IeltsChoiceQuestion -> IeltsCandidateChoice(question.questionId, question.prompt, question.options.toList(), 1)
                is IeltsInformationQuestion -> IeltsCandidateInformation(question.questionId, question.prompt, IeltsInformationAnswer.entries.toList())
                is IeltsViewQuestion -> IeltsCandidateViewQuestion(question.questionId, question.prompt, IeltsViewAnswer.entries.toList())
                is IeltsCompletionQuestion -> IeltsCandidateCompletion(question.questionId, question.prompt, question.maxWords, question.allowNumbers, IeltsContract.WORD_RULE)
            } }, IeltsCandidateWriting(writing.taskId, writing.taskType, writing.prompt, 250, 40), exercise.writingTask1,
            exercise.styleSourceIds.toList(), "official_public_format_not_recent_three_year_test_corpus")
    }
    fun validateCandidate(request: IeltsExerciseRequest, exercise: IeltsExercisePackage, candidate: IeltsCandidateView) {
        require(candidate == candidateView(request, exercise)) { "IELTS candidate view differs from frozen material" }
    }
    /** Caller must supply a committed submission; IeltsPracticeSession enforces the local save transition. */
    fun answerView(request: IeltsExerciseRequest, exercise: IeltsExercisePackage, submission: IeltsSubmission): IeltsAnswerView {
        validateSubmission(request, exercise, submission)
        val items = exercise.readingQuestions.zip(submission.readingAnswers).map { (q, response) ->
            val reviewed = when (q) { is IeltsInformationQuestion -> q.reviewedParagraphIds; is IeltsViewQuestion -> q.reviewedParagraphIds; else -> null }
            val absence = when (q) { is IeltsInformationQuestion -> q.absenceJustification; is IeltsViewQuestion -> q.absenceJustification; else -> null }
            val count = if (q is IeltsCompletionQuestion) wordCount(response.answer) else null
            IeltsAnswerItem(q.questionId, kind(q), response.answer, answer(q), q.explanation, q.sourceRefs.toList(), reviewed?.toList(), absence,
                count, if (q is IeltsCompletionQuestion) count!! in 1..q.maxWords else null,
                if (q is IeltsCompletionQuestion) q.allowNumbers || !hasNumber(response.answer) else null)
        }
        val writingWords = wordCount(submission.writingTask2Text)
        return IeltsAnswerView(submission.attemptId, submission.packageSha256, IeltsExerciseJson.submissionHash(submission),
            items, writingWords, writingWords >= 250, writingTask1Words = submission.writingTask1Text?.let(::wordCount),
            writingTask1MeetsMinimum = submission.writingTask1Text?.let { wordCount(it) >= 150 })
    }
    fun kind(request: IeltsReadingRequest): IeltsReadingType = when (request) {
        is IeltsChoiceRequest -> IeltsReadingType.MULTIPLE_CHOICE
        is IeltsInformationRequest -> IeltsReadingType.IDENTIFYING_INFORMATION
        is IeltsViewRequest -> IeltsReadingType.IDENTIFYING_WRITERS_VIEWS
        is IeltsCompletionRequest -> IeltsReadingType.SHORT_TEXT_COMPLETION
    }
    fun kind(question: IeltsReadingQuestion): IeltsReadingType = when (question) {
        is IeltsChoiceQuestion -> IeltsReadingType.MULTIPLE_CHOICE
        is IeltsInformationQuestion -> IeltsReadingType.IDENTIFYING_INFORMATION
        is IeltsViewQuestion -> IeltsReadingType.IDENTIFYING_WRITERS_VIEWS
        is IeltsCompletionQuestion -> IeltsReadingType.SHORT_TEXT_COMPLETION
    }
    private fun answer(question: IeltsReadingQuestion): String = when (question) {
        is IeltsChoiceQuestion -> question.answer.name
        is IeltsInformationQuestion -> question.answer.wire
        is IeltsViewQuestion -> question.answer.wire
        is IeltsCompletionQuestion -> question.answer
    }
}

enum class IeltsPracticeStage { DRAFT, AWAITING_SAVE, SUBMITTED }

/**
 * Isolated practice state: no speed, baseline, FSRS or recommendation record is mutated.
 * A pending payload is not an answer-reveal permission. The repository must transactionally
 * save it and read it back before confirmSaved. Caller-supplied/model fields do not attest storage.
 */
class IeltsPracticeSession private constructor(
    private val request: IeltsExerciseRequest, private val exercise: IeltsExercisePackage,
    val attemptId: String, val stage: IeltsPracticeStage, private val responses: Map<String, String>,
    val writingText: String, private val pending: IeltsSubmission?, private val pendingHash: String?,
    private val committed: IeltsSubmission?, val writingTask1Text: String?,
) {
    val answeredCount: Int get() = responses.size
    fun candidate(): IeltsCandidateView = IeltsExerciseValidator.candidateView(request, exercise)
    fun draftResponses(): List<IeltsResponse> = exercise.readingQuestions.mapNotNull { question ->
        responses[question.questionId]?.let { IeltsResponse(question.questionId, it) }
    }
    fun answer(questionId: String, response: String): IeltsPracticeSession {
        check(stage == IeltsPracticeStage.DRAFT)
        val question = requireNotNull(exercise.readingQuestions.singleOrNull { it.questionId == questionId })
        IeltsExerciseValidator.validateResponse(question, response)
        return next(responses = responses + (questionId to response))
    }
    fun clearAnswer(questionId: String): IeltsPracticeSession {
        check(stage == IeltsPracticeStage.DRAFT && exercise.readingQuestions.any { it.questionId == questionId })
        return next(responses = responses - questionId)
    }
    fun write(text: String): IeltsPracticeSession {
        check(stage == IeltsPracticeStage.DRAFT)
        IeltsExerciseValidator.wordCount(text) // validates the bounded Unicode writing payload without assigning a score
        return next(writing = text)
    }
    fun writeTask1(text: String): IeltsPracticeSession {
        check(stage == IeltsPracticeStage.DRAFT && exercise.writingTask1.status == "available")
        IeltsExerciseValidator.wordCount(text)
        return next(writing1 = text)
    }
    fun prepareSubmission(submittedAt: Long): IeltsPracticeSession {
        check(stage == IeltsPracticeStage.DRAFT)
        val submission = IeltsSubmission(IeltsContract.SUBMISSION, attemptId, request.requestId, IeltsExerciseJson.packageHash(exercise),
            "submitted", submittedAt, draftResponses(), writingText, writingTask1Text)
        IeltsExerciseValidator.validateSubmission(request, exercise, submission)
        return next(stage = IeltsPracticeStage.AWAITING_SAVE, pending = submission,
            pendingHash = IeltsExerciseJson.submissionHash(submission))
    }
    fun pendingSubmission(): IeltsSubmission? = pending?.let { IeltsExerciseJson.decodeSubmission(IeltsExerciseJson.encodeSubmission(it)) }
    fun confirmSaved(saved: IeltsSubmission): IeltsPracticeSession {
        check(stage == IeltsPracticeStage.AWAITING_SAVE)
        IeltsExerciseValidator.validateSubmission(request, exercise, saved)
        require(saved.attemptId == attemptId && IeltsExerciseJson.submissionHash(saved) == pendingHash) { "Saved IELTS submission differs from pending payload" }
        val frozen = IeltsExerciseJson.decodeSubmission(IeltsExerciseJson.encodeSubmission(saved))
        return next(stage = IeltsPracticeStage.SUBMITTED, pending = null, pendingHash = null, committed = frozen)
    }
    fun saveFailed(): IeltsPracticeSession {
        check(stage == IeltsPracticeStage.AWAITING_SAVE)
        return next(stage = IeltsPracticeStage.DRAFT, pending = null, pendingHash = null)
    }
    fun answerView(): IeltsAnswerView {
        check(stage == IeltsPracticeStage.SUBMITTED) { "Save the full submission before revealing IELTS reference answers" }
        return IeltsExerciseValidator.answerView(request, exercise, requireNotNull(committed))
    }
    private fun next(stage: IeltsPracticeStage = this.stage, responses: Map<String, String> = this.responses,
        writing: String = writingText, pending: IeltsSubmission? = this.pending, pendingHash: String? = this.pendingHash,
        committed: IeltsSubmission? = this.committed, writing1: String? = writingTask1Text) = IeltsPracticeSession(request, exercise, attemptId, stage,
            responses.toMap(), writing, pending, pendingHash, committed, writing1)
    companion object {
        fun begin(request: IeltsExerciseRequest, exercise: IeltsExercisePackage, attemptId: String): IeltsPracticeSession {
            IeltsExerciseValidator.validatePackage(request, exercise); IeltsExerciseValidator.validId(attemptId)
            // Freeze caller collections; returning a candidate must not allow an in-flight question to change.
            val frozenRequest = IeltsExerciseJson.decodeRequest(IeltsExerciseJson.encodeRequest(request))
            val frozenPackage = IeltsExerciseJson.decodePackage(IeltsExerciseJson.encodePackage(exercise))
            return IeltsPracticeSession(frozenRequest, frozenPackage, attemptId, IeltsPracticeStage.DRAFT,
                emptyMap(), "", null, null, null, if (exercise.writingTask1.status == "available") "" else null)
        }
    }
}
