@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.lexicon.domain

import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** One paired host origin. Path prefixes, credentials and URL parameters are not gateway configuration. */
class IeltsGatewayEndpoint private constructor(val url: String) {
    override fun equals(other: Any?) = other is IeltsGatewayEndpoint && url == other.url
    override fun hashCode() = url.hashCode()
    override fun toString() = url
    companion object {
        fun parse(value: String): IeltsGatewayEndpoint {
            try {
                require(value.length in 1..2048 && value.none { it.isWhitespace() || it.code < 32 })
                val uri = URI(value)
                require(uri.scheme.equals("https", ignoreCase = true) && !uri.isOpaque)
                require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
                require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
                require(!uri.host.isNullOrBlank() && uri.rawAuthority?.contains('%') != true)
                require(uri.port == -1 || uri.port in 1..65535)
                val port = if (uri.port == 443) -1 else uri.port
                return IeltsGatewayEndpoint(URI("https", null, uri.host.lowercase(Locale.ROOT), port, "/", null, null).toASCIIString())
            } catch (_: Exception) { throw IeltsGatewayFailure(IeltsGatewayFailureCode.HTTPS_REQUIRED) }
        }
    }
}

@Serializable data class IeltsGatewaySubmit(
    val schema: String = "lexicon.ielts.submit.v1",
    @SerialName("request_id") val requestId: String,
    @SerialName("article_id") val articleId: String,
    val body: String,
    val paragraphs: List<IeltsParagraph>,
    @SerialName("reading_requests") val readingRequests: List<IeltsReadingRequest>,
    @SerialName("writing_task2_request") val writingTask2Request: IeltsWritingRequest,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("writing_task1_request") val writingTask1Request: IeltsVisualRequest? = null,
)
@Serializable data class IeltsGatewayCapabilities(
    @SerialName("runtime_version") val runtimeVersion: String,
    @SerialName("corpus_id") val corpusId: String,
    @SerialName("corpus_sha256") val corpusSha256: String,
    @SerialName("supported_reading_types") val supportedReadingTypes: List<String>,
    @SerialName("supported_writing_task2_types") val supportedWritingTask2Types: List<String>,
    @SerialName("corpus_scope") val corpusScope: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("supported_writing_task1_policies") val supportedWritingTask1Policies: List<String>? = null,
)
@Serializable data class IeltsGatewayHealth(val schema: String, val status: String, val capabilities: IeltsGatewayCapabilities)
@Serializable enum class IeltsGatewayJobState {
    @SerialName("queued") QUEUED, @SerialName("generating") GENERATING,
    @SerialName("reviewing") REVIEWING, @SerialName("ready") READY,
    @SerialName("cancelling") CANCELLING, @SerialName("cancelled") CANCELLED,
    @SerialName("failed") FAILED, @SerialName("interrupted") INTERRUPTED,
}
@Serializable data class IeltsGatewayJob(
    val id: String, @SerialName("request_id") val requestId: String,
    @SerialName("input_hash") val inputHash: String, val state: IeltsGatewayJobState,
    val revision: Long, @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long, val error: String?,
    @SerialName("process_reaped") val processReaped: Int,
)
@Serializable private data class IeltsGatewayJobEnvelope(val schema: String, val job: IeltsGatewayJob)
@Serializable data class IeltsGatewayProvenance(
    val kind: String, @SerialName("runtime_version") val runtimeVersion: String,
    @SerialName("generation_session_id") val generationSessionId: String,
    @SerialName("review_session_id") val reviewSessionId: String,
    @SerialName("request_sha256") val requestSha256: String,
    @SerialName("package_sha256") val packageSha256: String,
    @SerialName("review_sha256") val reviewSha256: String,
    @SerialName("corpus_sha256") val corpusSha256: String,
    @SerialName("semantic_review") val semanticReview: String,
    @SerialName("recent_three_year_coverage") val recentThreeYearCoverage: Boolean,
    @SerialName("real_provider_request_count") val realProviderRequestCount: Long,
    @SerialName("created_at") val createdAt: Long,
    // Missing fields identify a legacy stored artifact. New network imports
    // require all three; absence never attests that explanations were checked.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("explanation_review_session_id") val explanationReviewSessionId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("explanation_review_sha256") val explanationReviewSha256: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("explanation_review") val explanationReview: String? = null,
)
/** Full private artifact. Its paired-host receipt is not evidence of execution on this Android client. */
data class IeltsGatewayVerifiedPackage(
    val schema: String, val request: IeltsExerciseRequest,
    val exercisePackage: IeltsExercisePackage, val provenance: IeltsGatewayProvenance,
) {
    val provenanceScope: String get() = "paired_host_receipt"
    val runtimeExecutedOnClient: Boolean get() = false
}

enum class IeltsGatewayFailureCode {
    HTTPS_REQUIRED, NOT_PAIRED, TOKEN_INVALID, STORAGE_UNAVAILABLE, UNAUTHORIZED,
    NOT_FOUND, CONFLICT, PACKAGE_UNAVAILABLE, TRANSPORT, INVALID_RESPONSE,
    CONFIGURATION_CHANGED, REMOTE_FAILURE, SUBMIT_REJECTED,
    PAIRING_DOCUMENT_INVALID, CERTIFICATE_INVALID, TLS_REJECTED,
}
class IeltsGatewayFailure(val code: IeltsGatewayFailureCode, val httpStatus: Int? = null) : Exception(code.name)
/** Reconcile this exact ID with byRequest; transport uncertainty never authorizes another POST. */
class IeltsGatewaySubmissionUncertain(val requestId: String) : Exception("submit_outcome_unknown")

interface IeltsGateway {
    suspend fun health(): IeltsGatewayHealth
    suspend fun submit(payload: IeltsGatewaySubmit): IeltsGatewayJob
    suspend fun get(jobId: String): IeltsGatewayJob
    suspend fun byRequest(requestId: String): IeltsGatewayJob?
    suspend fun cancel(jobId: String): IeltsGatewayJob
    suspend fun fetchPackage(job: IeltsGatewayJob, originalSubmit: IeltsGatewaySubmit, expectedHealth: IeltsGatewayHealth): IeltsGatewayVerifiedPackage
}

/** Repository/UI connection state contains no token or authentication header. */
data class IeltsGatewayConnection(val endpoint: String?, val paired: Boolean, val health: IeltsGatewayHealth?,
    val certificateSha256: String? = null)
/** Public confirmation metadata. The connection document and its token never enter UI state. */
data class IeltsPairingPreview(val endpoint: String, val certificateSha256: String, val expiresAt: Long) {
    companion object { const val MAX_DOCUMENT_BYTES = 16_384 }
}
interface IeltsGatewayAccess {
    suspend fun snapshot(): IeltsGatewayConnection
    suspend fun pair(endpoint: String, token: String): IeltsGatewayHealth
    suspend fun inspectPairing(document: ByteArray): IeltsPairingPreview =
        throw IeltsGatewayFailure(IeltsGatewayFailureCode.PAIRING_DOCUMENT_INVALID)
    suspend fun pairImported(document: ByteArray): IeltsGatewayHealth =
        throw IeltsGatewayFailure(IeltsGatewayFailureCode.PAIRING_DOCUMENT_INVALID)
    suspend fun clearPairing()
    suspend fun createClient(): IeltsGateway
}

/** Wire validation follows tools/ielts_http.py; no host claims enter client execution evidence. */
object IeltsGatewayProtocol {
    const val MAX_REQUEST_BYTES = 500_000
    const val MAX_BODY_BYTES = 200_000
    const val MAX_RESPONSE_BYTES = 2_000_000
    private val codec = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false; classDiscriminator = "type" }
    private val digest = Regex("[a-f0-9]{64}")
    private val jobId = Regex("job-[a-f0-9]{32}")
    private val version = Regex("[A-Za-z0-9][A-Za-z0-9._+:-]{0,63}")
    private val readingTypes = setOf("multiple_choice", "identifying_information", "identifying_writers_views", "short_text_completion")
    private val writingTypes = setOf("extent_of_agreement")
    private val explanationFields = setOf("explanation_review_session_id", "explanation_review_sha256", "explanation_review")
    fun validateJobId(value: String) { require(jobId.matches(value)) { "Invalid gateway job ID" } }
    fun validateRequestId(value: String) = IeltsExerciseValidator.validId(value)
    fun bodyHash(body: String): String = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun timestamp(value: Long) { require(value in 0..IeltsContract.MAX_TIMESTAMP) }
    private fun type(request: IeltsReadingRequest): String = when (request) {
        is IeltsChoiceRequest -> "multiple_choice"
        is IeltsInformationRequest -> "identifying_information"
        is IeltsViewRequest -> "identifying_writers_views"
        is IeltsCompletionRequest -> "short_text_completion"
    }
    fun validateSubmit(value: IeltsGatewaySubmit) {
        require(value.schema == "lexicon.ielts.submit.v1")
        validateRequestId(value.requestId); IeltsExerciseValidator.validId(value.articleId)
        IeltsExerciseValidator.validUnicode(value.body)
        require(value.body.isNotBlank() && value.body.length <= MAX_BODY_BYTES && value.body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES)
        require(value.paragraphs.size in 1..80 && value.paragraphs.map { it.paragraphId }.distinct().size == value.paragraphs.size)
        var previousEnd = -1
        value.paragraphs.forEach { paragraph ->
            IeltsExerciseValidator.validId(paragraph.paragraphId); IeltsExerciseValidator.validUnicode(paragraph.text)
            require(paragraph.text.isNotBlank() && paragraph.text.length <= IeltsContract.MAX_TEXT)
            require(paragraph.start >= 0 && paragraph.end > paragraph.start && paragraph.end <= value.body.length)
            require(paragraph.start >= previousEnd && paragraph.end - paragraph.start == paragraph.text.length)
            require(value.body.substring(paragraph.start, paragraph.end) == paragraph.text)
            previousEnd = paragraph.end
        }
        require(value.paragraphs.sumOf { it.text.length.toLong() } <= IeltsContract.MAX_TEXT)
        require(value.readingRequests.size in 1..20 && value.readingRequests.map { it.questionId }.distinct().size == value.readingRequests.size)
        value.readingRequests.forEach { request ->
            IeltsExerciseValidator.validId(request.questionId)
            if (request is IeltsCompletionRequest) require(request.maxWords in 1..8)
        }
        IeltsExerciseValidator.validId(value.writingTask2Request.taskId)
        require(value.readingRequests.none { it.questionId == value.writingTask2Request.taskId })
        value.writingTask1Request?.let {
            IeltsExerciseValidator.validId(it.taskId)
            require(it.policy == "source_numeric_visual_v1" && it.taskId != value.writingTask2Request.taskId &&
                value.readingRequests.none { q -> q.questionId == it.taskId })
        }
        require(codec.encodeToString(value).toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES)
    }
    fun requireSupported(value: IeltsGatewaySubmit, health: IeltsGatewayHealth) {
        validateSubmit(value); validateHealth(health)
        require(value.readingRequests.all { type(it) in health.capabilities.supportedReadingTypes })
        require("extent_of_agreement" in health.capabilities.supportedWritingTask2Types)
        value.writingTask1Request?.let { require(it.policy in health.capabilities.supportedWritingTask1Policies.orEmpty()) }
    }
    fun validateHealth(value: IeltsGatewayHealth) {
        require(value.schema == "lexicon.ielts.health.v1" && value.status == "ok")
        val c = value.capabilities
        require(version.matches(c.runtimeVersion)); IeltsExerciseValidator.validId(c.corpusId); require(digest.matches(c.corpusSha256))
        require(c.supportedReadingTypes.size <= readingTypes.size && c.supportedReadingTypes.distinct().size == c.supportedReadingTypes.size && c.supportedReadingTypes.all { it in readingTypes })
        require(c.supportedWritingTask2Types.size <= writingTypes.size && c.supportedWritingTask2Types.distinct().size == c.supportedWritingTask2Types.size && c.supportedWritingTask2Types.all { it in writingTypes })
        require(c.corpusScope == "official_public_samples_and_format_metadata")
        require(c.supportedWritingTask1Policies == null || c.supportedWritingTask1Policies == listOf("source_numeric_visual_v1"))
    }
    fun validateJob(value: IeltsGatewayJob) {
        validateJobId(value.id); validateRequestId(value.requestId); require(digest.matches(value.inputHash))
        timestamp(value.revision); timestamp(value.createdAt); timestamp(value.updatedAt)
        require(value.processReaped in 0..1)
        require(value.error == null || Regex("[a-z0-9_]{1,80}").matches(value.error))
    }
    fun encodeSubmit(value: IeltsGatewaySubmit): String { validateSubmit(value); return codec.encodeToString(value) }
    fun decodeSubmit(text: String): IeltsGatewaySubmit = decode<IeltsGatewaySubmit>(text, MAX_REQUEST_BYTES).also(::validateSubmit)
    fun encodeHealth(value: IeltsGatewayHealth): String { validateHealth(value); return codec.encodeToString(value) }
    fun decodeHealth(text: String): IeltsGatewayHealth = decode<IeltsGatewayHealth>(text).also(::validateHealth)
    fun encodeJob(value: IeltsGatewayJob): String { validateJob(value); return codec.encodeToString(IeltsGatewayJobEnvelope("lexicon.ielts.job.v1", value)) }
    fun decodeJob(text: String): IeltsGatewayJob = decode<IeltsGatewayJobEnvelope>(text).let {
        require(it.schema == "lexicon.ielts.job.v1"); it.job.also(::validateJob)
    }
    fun encodeVerifiedPackage(value: IeltsGatewayVerifiedPackage): String = buildJsonObject {
        put("schema", value.schema)
        put("request", Json.parseToJsonElement(IeltsExerciseJson.encodeRequest(value.request)))
        put("package", Json.parseToJsonElement(IeltsExerciseJson.encodePackage(value.exercisePackage)))
        put("provenance", codec.encodeToJsonElement(value.provenance))
    }.toString()
    fun decodeVerifiedPackage(text: String): IeltsGatewayVerifiedPackage {
        val root = parse(text, MAX_RESPONSE_BYTES).jsonObject
        require(root.keys == setOf("schema", "request", "package", "provenance"))
        val schema = root.getValue("schema").jsonPrimitive
        require(schema.isString && schema.content == "lexicon.ielts.verified-package.v1")
        val provenance = root.getValue("provenance").jsonObject
        val explanationKeys = provenance.keys.intersect(explanationFields)
        require(explanationKeys.isEmpty() || explanationKeys == explanationFields) { "Incomplete explanation review provenance" }
        require(explanationKeys.all { provenance.getValue(it).let { value -> value is JsonPrimitive && value.isString } })
        return IeltsGatewayVerifiedPackage(schema.content,
            IeltsExerciseJson.decodeRequest(root.getValue("request").toString()),
            IeltsExerciseJson.decodePackage(root.getValue("package").toString()),
            decode<IeltsGatewayProvenance>(provenance.toString()))
    }
    fun jobInputHash(request: IeltsExerciseRequest, body: String, corpusSha256: String): String = IeltsExerciseJson.canonicalSha256(buildJsonObject {
        put("request", Json.parseToJsonElement(IeltsExerciseJson.encodeRequest(request)))
        put("body", body); put("corpus_hash", corpusSha256)
    })
    fun validateVerifiedPackage(value: IeltsGatewayVerifiedPackage, job: IeltsGatewayJob, original: IeltsGatewaySubmit, health: IeltsGatewayHealth): IeltsGatewayVerifiedPackage {
        requireSupported(original, health); validateJob(job)
        require(job.state == IeltsGatewayJobState.READY && job.processReaped == 1 && job.requestId == original.requestId)
        require(value.schema == "lexicon.ielts.verified-package.v1")
        val request = value.request
        require(request.requestId == original.requestId && request.articleId == original.articleId && request.bodyHash == bodyHash(original.body))
        require(request.paragraphs == original.paragraphs && request.readingRequests == original.readingRequests && request.writingTask2Request == original.writingTask2Request && request.writingTask1Request == original.writingTask1Request)
        val receipt = IeltsExerciseValidator.validatePackage(request, value.exercisePackage)
        IeltsStudyPolicy.validateFocusedSequence(request, value.exercisePackage)
        val p = value.provenance
        require(p.kind == "deepseek_harness" && p.runtimeVersion == health.capabilities.runtimeVersion)
        IeltsExerciseValidator.validId(p.generationSessionId); IeltsExerciseValidator.validId(p.reviewSessionId)
        val explanationSession = requireNotNull(p.explanationReviewSessionId) { "Explanation review is required for a new import" }
        val explanationSha256 = requireNotNull(p.explanationReviewSha256)
        IeltsExerciseValidator.validId(explanationSession)
        require(setOf(p.generationSessionId, p.reviewSessionId, explanationSession).size == 3)
        require(listOf(p.requestSha256, p.packageSha256, p.reviewSha256, p.corpusSha256, explanationSha256).all { digest.matches(it) })
        require(p.requestSha256 == receipt.requestSha256 && p.packageSha256 == receipt.packageSha256)
        require(p.corpusSha256 == health.capabilities.corpusSha256)
        require(p.semanticReview == "independent_answers_agree" && !p.recentThreeYearCoverage)
        require(p.explanationReview == "source_faithfulness_checked")
        require(p.realProviderRequestCount in 3..16); timestamp(p.createdAt)
        require(job.inputHash == jobInputHash(request, original.body, p.corpusSha256))
        require(encodeVerifiedPackage(value).toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES)
        return value
    }
    /** Only fixed caller-side error categories leave transport; provider text is never shown or logged. */
    fun errorCode(text: String): String? = try {
        val root = parse(text, 16_384).jsonObject
        require(root.keys == setOf("schema", "error") && root["schema"] == JsonPrimitive("lexicon.ielts.http-error.v1"))
        val error = root.getValue("error").jsonObject
        require(error.keys == setOf("code") || error.keys == setOf("code", "request_id"))
        val code = error.getValue("code").jsonPrimitive
        require(code.isString && Regex("[a-z0-9_]{1,80}").matches(code.content))
        code.content
    } catch (_: Exception) { null }
    private inline fun <reified T> decode(text: String, bound: Int = MAX_RESPONSE_BYTES): T {
        val input = parse(text, bound)
        val decoded = codec.decodeFromJsonElement<T>(input)
        require(IeltsExerciseJson.canonicalSha256(input) == IeltsExerciseJson.canonicalSha256(codec.encodeToJsonElement(decoded))) { "Gateway JSON changed while decoding" }
        return decoded
    }
    private fun parse(text: String, bound: Int): JsonElement {
        require(text.toByteArray(Charsets.UTF_8).size <= bound)
        // Validate duplicates/Unicode/integers before kotlinx.serialization can
        // collapse fields. The wrapper may contain two individually bounded contracts.
        return StrictJson(text).read()
    }
    private class StrictJson(private val input: String) {
        private var at = 0
        fun read(): JsonElement { val result = value(0); space(); require(at == input.length); return result }
        private fun space() { while (at < input.length && input[at] in " \t\r\n") at++ }
        private fun value(depth: Int): JsonElement {
            require(depth <= 32); space(); require(at < input.length)
            return when (input[at]) {
                '{' -> { at++; space(); val entries = linkedMapOf<String, JsonElement>()
                    if (take('}')) JsonObject(entries) else {
                        while (true) { space(); require(input.getOrNull(at) == '"'); val key = string(); require(key !in entries); space(); require(take(':'))
                            entries[key] = value(depth + 1); space(); if (take('}')) break; require(take(',')) }
                        JsonObject(entries)
                    }
                }
                '[' -> { at++; space(); val items = mutableListOf<JsonElement>()
                    if (take(']')) JsonArray(items) else { while (true) { items += value(depth + 1); space(); if (take(']')) break; require(take(',')) }; JsonArray(items) }
                }
                '"' -> JsonPrimitive(string())
                't' -> { literal("true"); JsonPrimitive(true) }
                'f' -> { literal("false"); JsonPrimitive(false) }
                'n' -> { literal("null"); JsonNull }
                else -> { val start = at; if (input[at] == '-') at++; require(at < input.length && input[at].isDigit())
                    if (input[at] == '0') at++ else while (at < input.length && input[at] in '0'..'9') at++
                    val token = input.substring(start, at); require(Regex("-?(0|[1-9][0-9]*)").matches(token))
                    val number = token.toLong(); require(number in -IeltsContract.MAX_TIMESTAMP..IeltsContract.MAX_TIMESTAMP); JsonPrimitive(number)
                }
            }
        }
        private fun take(char: Char): Boolean = (input.getOrNull(at) == char).also { if (it) at++ }
        private fun literal(value: String) { require(input.startsWith(value, at)); at += value.length }
        private fun string(): String {
            require(take('"')); val result = StringBuilder()
            while (at < input.length) {
                val char = input[at++]
                if (char == '"') return result.toString().also(IeltsExerciseValidator::validUnicode)
                require(char.code >= 32)
                if (char != '\\') result.append(char) else {
                    require(at < input.length)
                    when (val escaped = input[at++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b'); 'f' -> result.append('\u000C'); 'n' -> result.append('\n'); 'r' -> result.append('\r'); 't' -> result.append('\t')
                        'u' -> { require(at + 4 <= input.length); val hex = input.substring(at, at + 4); require(hex.all { it in "0123456789abcdefABCDEF" }); result.append(hex.toInt(16).toChar()); at += 4 }
                        else -> error("Invalid gateway JSON escape")
                    }
                }
            }
            error("Unterminated gateway JSON string")
        }
    }
}
