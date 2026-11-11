package dev.lexicon.data

import dev.lexicon.domain.Article
import dev.lexicon.domain.ArticleBody
import dev.lexicon.domain.ParagraphFocus
import dev.lexicon.domain.ReadingSkimPlanner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One explicitly enabled request. Reports structure only; never a model-quality success test. */
class ReadingAnalysisLiveAuditTest {
    @Test fun auditOneExplicitArticleResponseWithoutSavingItsContents() = runBlocking {
        assumeTrue("Reading response audit requires explicit opt-in", System.getenv("LEXICON_READING_AUDIT") == "1")
        val report = File(requireNotNull(System.getenv("LEXICON_READING_AUDIT_REPORT")) { "Missing audit report destination" })
        val started = Instant.now()
        val requests = AtomicInteger()
        val codec = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
        var client: OkHttpClient? = null
        var metadata: JsonObject? = null
        var outcome = "setup_failed"
        var failureCode: String? = null
        var validationSection: ReadingValidationSection? = null
        var stage = "input"
        var inputCharacters: Int? = null
        var inputParagraphs: Int? = null
        try {
            val inputFile = File(requireNotNull(System.getenv("LEXICON_READING_AUDIT_INPUT")))
            require(inputFile.length() in 1..500_000)
            val input = codec.parseToJsonElement(inputFile.readText()).jsonObject
            val article = codec.decodeFromJsonElement<Article>(input.getValue("article"))
            val body = codec.decodeFromJsonElement<ArticleBody>(input.getValue("body"))
            inputCharacters = body.text.length
            val paragraphs = ParagraphFocus.paragraphs(body.text).size.also { inputParagraphs = it }
            ReadingAiProtocol.analysisPayload(article, body)
            val sentenceParagraphs = ReadingSkimPlanner.sentences(body.text).associate { it.id to it.paragraphIndex }
            stage = "key"
            val keyFile = File(requireNotNull(System.getenv("LEXICON_API_FILE")))
            require(keyFile.length() in 1..1000)
            val key = keyFile.readText().trim().removePrefix("Bearer ").trim()
            require(Regex("sk-[A-Za-z0-9_-]{8,200}").matches(key))
            stage = "request"
            val transport = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
                .callTimeout(110, TimeUnit.SECONDS).retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false)
                .addInterceptor { chain ->
                    if (requests.incrementAndGet() > 1) throw IOException("Audit request budget exhausted")
                    val request = chain.request()
                    if (request.method != "POST" || request.url.host != "api.deepseek.com" || request.url.encodedPath != "/chat/completions")
                        throw IOException("Unexpected audit endpoint")
                    val before = System.nanoTime()
                    val response = chain.proceed(request)
                    // peekBody does not consume the response used by the production parser. The
                    // content and parsed graph live only in this interceptor's memory.
                    metadata = try {
                        val peek = response.peekBody(2_000_000)
                        val inspected = ReadingAnalysisAuditMetadata.inspect(peek.string(), paragraphs, sentenceParagraphs)
                        JsonObject(inspected + mapOf("http_status" to JsonPrimitive(response.code),
                            "elapsed_ms" to JsonPrimitive((System.nanoTime() - before) / 1_000_000),
                            "peek_limit_reached" to JsonPrimitive(peek.contentLength() >= 2_000_000)))
                    } catch (_: Exception) {
                        buildJsonObject { put("http_status", response.code); put("metadata_unavailable", true) }
                    }
                    response
                }.build().also { client = it }
            DeepSeekReadingAi({ key }, transport, sourceAllowed = { it == article }, maxSchemaRepairs = 0).analyze(article, body)
            outcome = "schema_accepted"
        } catch (failure: ApiFailure) {
            outcome = if (failure.code == "invalid_schema") "schema_rejected" else "request_failed"
            failureCode = auditErrorCode(failure.code)
            validationSection = failure.validationSection
        } catch (_: Exception) {
            outcome = if (stage == "request") "request_failed" else "setup_failed"
            failureCode = when (stage) { "input" -> "invalid_audit_input"; "key" -> "invalid_api_file"; else -> "transport_or_audit_failure" }
        } finally {
            client?.dispatcher?.executorService?.shutdown()
            client?.connectionPool?.evictAll()
            val sanitized = buildJsonObject {
                put("kind", "single-reading-analysis-structure-audit")
                put("started_at_utc", started.toString()); put("completed_at_utc", Instant.now().toString())
                put("outcome", outcome); put("error_code", failureCode)
                put("validation_section", validationSection?.key)
                put("attempted_requests", requests.get()); put("maximum_requests", 1)
                put("automatic_schema_repairs", 0); put("automatic_transport_retries", false)
                put("input_characters", inputCharacters); put("input_paragraphs", inputParagraphs)
                put("semantic_quality", "not_assessed")
                put("report_scope", "fixed structure metadata only; no article, IDs, labels, raw response, key, paths or exception messages")
                put("response_structure", metadata ?: JsonNull)
            }
            try {
                report.parentFile?.mkdirs()
                report.writeText(codec.encodeToString(sanitized) + "\n")
            } catch (_: Exception) { throw AssertionError("Unable to write the sanitized reading audit report") }
        }
        // A rejected parse is reported as a failure, not relabeled as model success because the
        // diagnostic itself worked. A successful parse still makes no semantic-quality claim.
        if (outcome != "schema_accepted" || requests.get() != 1)
            throw AssertionError("Reading analysis audit recorded a failure; inspect the sanitized report")
    }

    private fun auditErrorCode(code: String): String = code.takeIf {
        it in setOf("invalid_schema", "snapshot_mismatch", "invalid_input", "input_too_long", "source_restricted", "missing_key",
            "authentication", "quota", "rate_limit", "network", "incomplete", "empty_response", "invalid_response") ||
            Regex("http_[0-9]{3}").matches(it)
    } ?: "other_api_failure"
}

/** Public values below are types, lengths and counts. Private graph identifiers never escape. */
internal object ReadingAnalysisAuditMetadata {
    private const val MAX_AUDITED_NODES = 512
    private const val MAX_AUDITED_ITEMS = 512
    private val asciiId = Regex("[A-Za-z0-9_-]+")
    private val safeModel = Regex("deepseek-[A-Za-z0-9_.-]{1,100}", RegexOption.IGNORE_CASE)
    private val finishes = setOf("stop", "length", "content_filter", "tool_calls", "insufficient_system_resource")
    private val forbiddenEvidenceFields = setOf("evidence", "evidence_start", "evidence_end", "evidence_quote", "start", "end")
    private fun string(value: JsonElement?) = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun number(value: JsonElement?) = (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    private fun type(value: JsonElement?): String = when {
        value == null -> "missing"
        value == JsonNull -> "null"
        value is JsonObject -> "object"
        value is JsonArray -> "array"
        value is JsonPrimitive && value.isString -> "string"
        value is JsonPrimitive && value.booleanOrNull != null -> "boolean"
        else -> "number"
    }

    fun inspect(response: String, paragraphCount: Int, sentenceParagraphs: Map<String, Int>? = null): JsonObject {
        val outer = runCatching { Json.parseToJsonElement(response) }.getOrNull()
        val objectValue = outer as? JsonObject
        val choice = (objectValue?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
        val message = choice?.get("message") as? JsonObject
        val content = string(message?.get("content"))
        val parsed = content?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
        val analysis = parsed as? JsonObject
        val rawNodes = analysis?.get("nodes") as? JsonArray
        val complete = rawNodes != null && rawNodes.size <= MAX_AUDITED_NODES
        val auditedNodes = rawNodes.orEmpty().take(MAX_AUDITED_NODES)
        val rows = auditedNodes.map { it as? JsonObject }
        val ids = rows.mapNotNull { string(it?.get("id"))?.trim() }
        val idSet = ids.toSet()
        val duplicateCount = ids.size - idSet.size
        val rootCount = rows.count { it?.get("parent_id") == JsonNull }
        val missingParents = rows.count { row -> string(row?.get("parent_id"))?.trim()?.let { it !in idSet } == true }
        val graphReadable = complete && duplicateCount == 0 && rows.all { row ->
            row != null && string(row["id"]) != null && (row["parent_id"] == JsonNull || string(row["parent_id"]) != null)
        } && missingParents == 0
        var cycle: Boolean? = null
        var maxDepth: Int? = null
        if (graphReadable) {
            val parents = rows.associate { row -> string(row?.get("id"))!!.trim() to string(row?.get("parent_id"))?.trim() }
            var cycleFound = false
            var maximum = 0
            for (id in ids) {
                val seen = mutableSetOf<String>()
                var current: String? = id
                while (current != null) {
                    if (!seen.add(current)) { cycleFound = true; break }
                    current = parents[current]
                }
                maximum = maxOf(maximum, seen.size)
            }
            cycle = cycleFound
            if (!cycleFound) maxDepth = maximum
        }
        val rawParagraphs = analysis?.get("paragraphs") as? JsonArray
        val paragraphsComplete = rawParagraphs != null && rawParagraphs.size <= MAX_AUDITED_ITEMS
        val paragraphAudits = rawParagraphs.orEmpty().take(MAX_AUDITED_ITEMS).mapIndexed { index, element ->
            paragraphAudit(element, index, paragraphCount)
        }
        val paragraphIndices = paragraphAudits.mapNotNull { it.declaredIndex }
        val validParagraphIndices = paragraphAudits.filter { it.validIndex }.mapNotNull { it.declaredIndex }.toSet()
        val missingIndices = (0 until paragraphCount).filter { it !in validParagraphIndices }
        val rawQuestions = analysis?.get("questions") as? JsonArray
        val questionsComplete = rawQuestions != null && rawQuestions.size <= MAX_AUDITED_ITEMS
        val questionAudits = rawQuestions.orEmpty().take(MAX_AUDITED_ITEMS).mapIndexed { index, element ->
            questionAudit(element, index, paragraphCount, sentenceParagraphs)
        }
        val questionIds = questionAudits.mapNotNull { it.id }
        return buildJsonObject {
            put("response_json_parseable", outer != null); put("response_type", type(outer))
            val model = string(objectValue?.get("model"))
            put("model", model?.takeIf { safeModel.matches(it) }); put("model_type", type(objectValue?.get("model")))
            val finish = string(choice?.get("finish_reason"))
            put("finish_reason", finish?.takeIf { it in finishes }); put("finish_reason_type", type(choice?.get("finish_reason")))
            put("content_type", type(message?.get("content"))); put("content_characters", content?.length)
            put("analysis_json_parseable", parsed != null); put("analysis_type", type(parsed)); put("nodes_type", type(analysis?.get("nodes")))
            put("nodes_count", rawNodes?.size); put("nodes_audited_count", auditedNodes.size)
            put("nodes_metadata_truncated", rawNodes != null && !complete)
            put("duplicate_ids_count", if (complete) duplicateCount else null)
            put("root_count", if (complete) rootCount else null)
            put("missing_parents_count", if (complete) missingParents else null)
            put("graph_readable", graphReadable); put("has_cycle", cycle); put("max_depth", maxDepth)
            putJsonArray("nodes") { auditedNodes.forEachIndexed { index, element ->
                val row = element as? JsonObject
                val id = string(row?.get("id")); val parent = string(row?.get("parent_id")); val label = string(row?.get("label"))
                val paragraph = number(row?.get("paragraph"))
                add(buildJsonObject {
                    put("index", index); put("node_type", type(element))
                    put("id_type", type(row?.get("id"))); put("id_length", id?.trim()?.length)
                    put("id_ascii_valid", id?.trim()?.let(asciiId::matches))
                    put("parent_id_type", type(row?.get("parent_id"))); put("parent_id_is_null", row?.get("parent_id") == JsonNull)
                    put("parent_id_length", parent?.trim()?.length)
                    put("label_type", type(row?.get("label"))); put("label_length", label?.trim()?.length)
                    put("label_raw_length", label?.length); put("label_nonblank", label?.isNotBlank())
                    put("paragraph_type", type(row?.get("paragraph"))); put("paragraph_value", paragraph)
                    put("paragraph_in_range", paragraph?.let { it in 0 until paragraphCount })
                    put("paragraph_valid", row?.get("paragraph") == JsonNull || paragraph?.let { it in 0 until paragraphCount } == true)
                })
            } }
            put("paragraphs_type", type(analysis?.get("paragraphs"))); put("paragraphs_count", rawParagraphs?.size)
            put("paragraphs_audited_count", paragraphAudits.size)
            put("paragraphs_metadata_truncated", rawParagraphs != null && !paragraphsComplete)
            put("paragraphs_missing_indices", if (paragraphsComplete) JsonArray(missingIndices.map(::JsonPrimitive)) else JsonNull)
            put("paragraphs_missing_count", if (paragraphsComplete) missingIndices.size else null)
            put("paragraphs_duplicate_indices_count", if (paragraphsComplete) paragraphIndices.size - paragraphIndices.toSet().size else null)
            put("paragraphs_invalid_indices_count", if (paragraphsComplete) paragraphAudits.count { !it.validIndex } else null)
            put("paragraphs_invalid_roles_count", if (paragraphsComplete) paragraphAudits.count { !it.validRole } else null)
            put("paragraphs_structure_valid_count", if (paragraphsComplete) paragraphAudits.count { it.structureValid } else null)
            put("paragraphs_complete_coverage", if (paragraphsComplete)
                rawParagraphs?.size == paragraphCount && paragraphAudits.all { it.validIndex } && missingIndices.isEmpty() else null)
            put("paragraphs", JsonArray(paragraphAudits.map { it.metadata }))
            put("questions_type", type(analysis?.get("questions"))); put("questions_count", rawQuestions?.size)
            put("questions_audited_count", questionAudits.size)
            put("questions_metadata_truncated", rawQuestions != null && !questionsComplete)
            put("questions_duplicate_ids_count", if (questionsComplete) questionIds.size - questionIds.toSet().size else null)
            put("questions_structure_valid_count", if (questionsComplete) questionAudits.count { it.structureValid } else null)
            put("sentence_reference_validation_available", sentenceParagraphs != null)
            put("questions_reference_valid_count", if (questionsComplete && sentenceParagraphs != null)
                questionAudits.count { it.referencesValid == true } else null)
            put("questions_valid_count", if (questionsComplete && sentenceParagraphs != null)
                questionAudits.count { it.structureValid && it.referencesValid == true } else null)
            put("questions_contract_valid", if (questionsComplete && sentenceParagraphs != null)
                (rawQuestions?.size ?: 0) in 1..5 && questionIds.size == questionIds.toSet().size &&
                    questionAudits.all { it.structureValid && it.referencesValid == true } else null)
            put("questions", JsonArray(questionAudits.map { it.metadata }))
        }
    }

    private data class ParagraphAudit(val metadata: JsonObject, val declaredIndex: Int?, val validIndex: Boolean,
        val validRole: Boolean, val structureValid: Boolean)

    private fun paragraphAudit(element: JsonElement, position: Int, paragraphCount: Int): ParagraphAudit {
        val row = element as? JsonObject
        val index = number(row?.get("index"))
        val validIndex = index?.let { it in 0 until paragraphCount } == true
        val role = string(row?.get("role"))?.trim()
        val validRole = role?.let { it in ReadingAiProtocol.paragraphRoles } == true
        val summary = string(row?.get("summary"))
        val validSummary = summary?.trim()?.let { it.isNotBlank() && it.length <= 200 } == true
        val valid = row != null && validIndex && validRole && validSummary
        return ParagraphAudit(buildJsonObject {
            put("position", position); put("item_type", type(element))
            put("index_type", type(row?.get("index"))); put("index_value", index); put("index_valid", validIndex)
            put("role_type", type(row?.get("role"))); put("role_length", role?.length); put("role_valid", validRole)
            put("summary_type", type(row?.get("summary"))); put("summary_length", summary?.trim()?.length)
            put("summary_raw_length", summary?.length); put("summary_nonblank", summary?.isNotBlank())
            put("summary_valid", validSummary); put("structure_valid", valid)
        }, index, validIndex, validRole, valid)
    }

    private data class QuestionAudit(val metadata: JsonObject, val id: String?, val structureValid: Boolean,
        val referencesValid: Boolean?)

    private fun questionAudit(element: JsonElement, position: Int, paragraphCount: Int,
        sentenceParagraphs: Map<String, Int>?): QuestionAudit {
        val row = element as? JsonObject
        val id = string(row?.get("id"))?.trim()
        val idValid = id?.let { it.length in 1..48 && asciiId.matches(it) } == true
        val question = string(row?.get("question"))
        val answer = string(row?.get("answer"))
        val questionValid = question?.trim()?.let { it.isNotBlank() && it.length <= 700 } == true
        val answerValid = answer?.trim()?.let { it.isNotBlank() && it.length <= 1400 } == true
        val paragraphs = row?.get("paragraphs") as? JsonArray
        val paragraphIndices = paragraphs.orEmpty().mapNotNull(::number)
        val duplicateParagraphs = paragraphIndices.size - paragraphIndices.toSet().size
        val invalidParagraphs = paragraphs?.count { number(it)?.let { index -> index in 0 until paragraphCount } != true }
        val paragraphsValid = paragraphs != null && paragraphs.size in 1..8 && invalidParagraphs == 0 && duplicateParagraphs == 0
        val sentences = row?.get("sentence_ids") as? JsonArray
        // Sentence IDs remain exact, including whitespace, because the production parser does not trim them.
        val sentenceIds = sentences.orEmpty().mapNotNull(::string)
        val duplicateSentences = sentenceIds.size - sentenceIds.toSet().size
        val emptySentences = sentenceIds.count(String::isBlank)
        val sentencesValid = sentences != null && sentences.size in 1..ReadingAiProtocol.MAX_QUESTION_SENTENCES &&
            sentenceIds.size == sentences.size && emptySentences == 0 && duplicateSentences == 0
        val knownSentences = sentenceParagraphs?.let { mapping -> sentenceIds.count { it in mapping } }
        val sentenceReferencesValid = sentenceParagraphs?.let { sentencesValid && knownSentences == sentenceIds.size }
        val paragraphSetsMatch = sentenceParagraphs?.let { mapping ->
            paragraphsValid && sentenceReferencesValid == true && sentenceIds.mapNotNull { mapping[it] }.toSet() == paragraphIndices.toSet()
        }
        val forbiddenCount = row?.keys?.count { it in forbiddenEvidenceFields } ?: 0
        val structureValid = row != null && idValid && questionValid && answerValid && paragraphsValid && sentencesValid && forbiddenCount == 0
        val referencesValid = sentenceParagraphs?.let { paragraphsValid && sentenceReferencesValid == true && paragraphSetsMatch == true }
        return QuestionAudit(buildJsonObject {
            put("position", position); put("item_type", type(element))
            put("id_type", type(row?.get("id"))); put("id_length", id?.length)
            put("id_ascii_valid", id?.let(asciiId::matches)); put("id_valid", idValid)
            put("question_type", type(row?.get("question"))); put("question_length", question?.trim()?.length)
            put("question_raw_length", question?.length); put("question_nonblank", question?.isNotBlank()); put("question_valid", questionValid)
            put("answer_type", type(row?.get("answer"))); put("answer_length", answer?.trim()?.length)
            put("answer_raw_length", answer?.length); put("answer_nonblank", answer?.isNotBlank()); put("answer_valid", answerValid)
            put("paragraphs_type", type(row?.get("paragraphs"))); put("paragraphs_count", paragraphs?.size)
            put("paragraphs_duplicate_count", if (paragraphs != null) duplicateParagraphs else null)
            put("paragraphs_invalid_count", invalidParagraphs); put("paragraphs_valid", paragraphsValid)
            put("sentence_ids_type", type(row?.get("sentence_ids"))); put("sentence_ids_count", sentences?.size)
            put("sentence_ids_non_string_count", sentences?.let { it.size - sentenceIds.size })
            put("sentence_ids_empty_count", if (sentences != null) emptySentences else null)
            put("sentence_ids_duplicate_count", if (sentences != null) duplicateSentences else null)
            put("sentence_ids_known_count", knownSentences)
            put("sentence_ids_unknown_count", knownSentences?.let { sentenceIds.size - it })
            put("sentence_ids_valid", sentencesValid); put("sentence_ids_reference_valid", sentenceReferencesValid)
            put("paragraph_set_matches_evidence", paragraphSetsMatch); put("forbidden_evidence_fields_count", forbiddenCount)
            put("structure_valid", structureValid); put("references_valid", referencesValid)
        }, id, structureValid, referencesValid)
    }
}

/** Deterministic safety checks for the audit formatter; these never use environment credentials. */
class ReadingAnalysisAuditMetadataTest {
    private fun node(id: String, parent: String?, label: String = "private-label", paragraph: JsonElement = JsonNull) = buildJsonObject {
        put("id", id); put("parent_id", parent); put("label", label); put("paragraph", paragraph)
    }
    private fun response(nodes: List<JsonObject>, model: String = "deepseek-v4-flash", finish: String = "stop",
        paragraphs: JsonElement? = null, questions: JsonElement? = null) = buildJsonObject {
        put("model", model)
        putJsonArray("choices") { add(buildJsonObject {
            put("finish_reason", finish)
            putJsonObject("message") { put("content", buildJsonObject {
                put("nodes", JsonArray(nodes))
                paragraphs?.let { put("paragraphs", it) }; questions?.let { put("questions", it) }
            }.toString()) }
        }) }
    }.toString()

    private fun paragraph(index: JsonElement, role: JsonElement = JsonPrimitive("claim"),
        summary: JsonElement = JsonPrimitive("private-paragraph-summary")) = buildJsonObject {
        put("index", index); put("role", role); put("summary", summary)
    }

    private fun question(id: String, paragraphs: List<Int>, sentenceIds: List<String>) = buildJsonObject {
        put("id", id); put("question", "private-question-text"); put("answer", "private-answer-text")
        put("paragraphs", JsonArray(paragraphs.map(::JsonPrimitive)))
        put("sentence_ids", JsonArray(sentenceIds.map(::JsonPrimitive)))
    }

    @Test fun metadataNeverContainsNodeIdsLabelsRawContentOrKeyLikeModelFields() {
        val rootId = "private-source-id"
        val label = "private-source-label-and-text"
        val report = ReadingAnalysisAuditMetadata.inspect(response(listOf(
            node(rootId, null, label), node("private-child-id", rootId, label, JsonPrimitive(1))
        )), 2)
        val encoded = report.toString()
        listOf(rootId, label, "private-child-id").forEach { assertFalse(encoded.contains(it)) }
        assertEquals("deepseek-v4-flash", report.getValue("model").jsonPrimitive.content)
        assertEquals("stop", report.getValue("finish_reason").jsonPrimitive.content)
        assertEquals(2, report.getValue("nodes_count").jsonPrimitive.int)
        assertEquals(2, report.getValue("max_depth").jsonPrimitive.int)
        assertFalse(report.getValue("has_cycle").jsonPrimitive.boolean)
        val unsafeModel = "sk-privateCredentialForFixtureOnly"
        val stripped = ReadingAnalysisAuditMetadata.inspect(response(listOf(node(rootId, null)), unsafeModel, label), 1)
        assertEquals(JsonNull, stripped["model"]); assertEquals(JsonNull, stripped["finish_reason"])
        assertFalse(stripped.toString().contains(unsafeModel)); assertFalse(stripped.toString().contains(label))
    }

    @Test fun perNodeTypesLengthsAndParagraphBoundsDescribeTheStrictParserInputs() {
        val report = ReadingAnalysisAuditMetadata.inspect(response(listOf(
            node("重复", null, "x".repeat(121), JsonPrimitive("0")),
            node("重复", null, " ", JsonPrimitive(5)),
        )), 2)
        assertEquals(1, report.getValue("duplicate_ids_count").jsonPrimitive.int)
        assertEquals(2, report.getValue("root_count").jsonPrimitive.int)
        assertEquals(JsonNull, report["max_depth"])
        val nodes = report.getValue("nodes").jsonArray
        assertFalse(nodes[0].jsonObject.getValue("id_ascii_valid").jsonPrimitive.boolean)
        assertEquals(121, nodes[0].jsonObject.getValue("label_length").jsonPrimitive.int)
        assertEquals("string", nodes[0].jsonObject.getValue("paragraph_type").jsonPrimitive.content)
        assertFalse(nodes[0].jsonObject.getValue("paragraph_valid").jsonPrimitive.boolean)
        assertEquals(0, nodes[1].jsonObject.getValue("label_length").jsonPrimitive.int)
        assertFalse(nodes[1].jsonObject.getValue("label_nonblank").jsonPrimitive.boolean)
        assertFalse(nodes[1].jsonObject.getValue("paragraph_in_range").jsonPrimitive.boolean)
    }

    @Test fun graphAuditSeparatesDeepTreesCyclesAndMissingParents() {
        val deep = (0..8).map { node("node-$it", if (it == 0) null else "node-${it - 1}") }
        val deepReport = ReadingAnalysisAuditMetadata.inspect(response(deep), 1)
        assertEquals(9, deepReport.getValue("max_depth").jsonPrimitive.int)
        assertEquals(1, deepReport.getValue("root_count").jsonPrimitive.int)
        val cycle = ReadingAnalysisAuditMetadata.inspect(response(listOf(node("a", "b"), node("b", "a"))), 1)
        assertTrue(cycle.getValue("has_cycle").jsonPrimitive.boolean); assertEquals(JsonNull, cycle["max_depth"])
        val missing = ReadingAnalysisAuditMetadata.inspect(response(listOf(node("a", "absent"))), 1)
        assertEquals(1, missing.getValue("missing_parents_count").jsonPrimitive.int)
        assertFalse(missing.getValue("graph_readable").jsonPrimitive.boolean)
        assertEquals(JsonNull, missing["has_cycle"])
    }

    @Test fun malformedAndOversizedMetadataIsExplicitWithoutEchoingInput() {
        // A bare token can become a JsonPrimitive; an unfinished object must fail parsing.
        val privateText = "private-not-json"
        val malformed = ReadingAnalysisAuditMetadata.inspect("{\"payload\":\"$privateText\",", 1)
        assertFalse(malformed.getValue("response_json_parseable").jsonPrimitive.boolean)
        assertEquals(JsonNull, malformed["nodes_count"]); assertFalse(malformed.toString().contains(privateText))
        val scalar = ReadingAnalysisAuditMetadata.inspect(JsonPrimitive(privateText).toString(), 1)
        assertTrue(scalar.getValue("response_json_parseable").jsonPrimitive.boolean)
        assertEquals("string", scalar.getValue("response_type").jsonPrimitive.content)
        assertEquals(JsonNull, scalar["nodes_count"]); assertFalse(scalar.toString().contains(privateText))
        val bounded = ReadingAnalysisAuditMetadata.inspect(response((0..512).map { node("node-$it", null) }), 1)
        assertEquals(513, bounded.getValue("nodes_count").jsonPrimitive.int)
        assertEquals(512, bounded.getValue("nodes_audited_count").jsonPrimitive.int)
        assertTrue(bounded.getValue("nodes_metadata_truncated").jsonPrimitive.boolean)
        assertEquals(JsonNull, bounded["duplicate_ids_count"])
    }

    @Test fun paragraphCoverageAndQuestionReferencesAreAuditedWithoutEchoingLearningMaterial() {
        val report = ReadingAnalysisAuditMetadata.inspect(response(listOf(node("root-private", null)),
            paragraphs = JsonArray(listOf(paragraph(JsonPrimitive(0)), paragraph(JsonPrimitive(1)))),
            questions = JsonArray(listOf(question("question-private", listOf(1), listOf("sentence-private"))))),
            2, mapOf("sentence-private" to 1))
        assertEquals(2, report.getValue("paragraphs_count").jsonPrimitive.int)
        assertEquals(0, report.getValue("paragraphs_missing_count").jsonPrimitive.int)
        assertTrue(report.getValue("paragraphs_missing_indices").jsonArray.isEmpty())
        assertTrue(report.getValue("paragraphs_complete_coverage").jsonPrimitive.boolean)
        assertEquals(2, report.getValue("paragraphs_structure_valid_count").jsonPrimitive.int)
        assertEquals(1, report.getValue("questions_structure_valid_count").jsonPrimitive.int)
        assertEquals(1, report.getValue("questions_reference_valid_count").jsonPrimitive.int)
        assertTrue(report.getValue("questions_contract_valid").jsonPrimitive.boolean)
        val encoded = report.toString()
        listOf("root-private", "question-private", "sentence-private", "private-question-text", "private-answer-text",
            "private-paragraph-summary", "\"claim\"").forEach { assertFalse(encoded.contains(it)) }
    }

    @Test fun paragraphAuditSeparatesMissingDuplicateWrongTypeAndInvalidRole() {
        val report = ReadingAnalysisAuditMetadata.inspect(response(emptyList(), paragraphs = JsonArray(listOf(
            paragraph(JsonPrimitive(0)), paragraph(JsonPrimitive(0)),
            paragraph(JsonPrimitive("1"), JsonPrimitive("private-unknown-role")),
            paragraph(JsonPrimitive(9), JsonPrimitive(3), JsonPrimitive("x".repeat(201))),
        ))), 3)
        assertEquals(listOf(1, 2), report.getValue("paragraphs_missing_indices").jsonArray.map { it.jsonPrimitive.int })
        assertEquals(1, report.getValue("paragraphs_duplicate_indices_count").jsonPrimitive.int)
        assertEquals(2, report.getValue("paragraphs_invalid_indices_count").jsonPrimitive.int)
        assertEquals(2, report.getValue("paragraphs_invalid_roles_count").jsonPrimitive.int)
        assertFalse(report.getValue("paragraphs_complete_coverage").jsonPrimitive.boolean)
        val rows = report.getValue("paragraphs").jsonArray
        assertEquals("string", rows[2].jsonObject.getValue("index_type").jsonPrimitive.content)
        assertEquals("number", rows[3].jsonObject.getValue("role_type").jsonPrimitive.content)
        assertEquals(201, rows[3].jsonObject.getValue("summary_length").jsonPrimitive.int)
        assertFalse(rows[3].jsonObject.getValue("summary_valid").jsonPrimitive.boolean)
        assertFalse(report.toString().contains("private-unknown-role"))
        assertFalse(report.toString().contains("x".repeat(201)))
    }

    @Test fun questionAuditSeparatesStructuralErrorsUnknownIdsAndWrongParagraphSets() {
        val duplicate = question("q-private", listOf(0), listOf("s-private"))
        val unknown = question("q-private", listOf(0), listOf("unknown-private"))
        val wrongParagraph = question("q-third", listOf(1), listOf("s-private"))
        val badShape = JsonObject(question("q-fourth", listOf(0, 0), listOf("s-private", "s-private")) + mapOf(
            "answer" to JsonPrimitive(" "), "question" to JsonPrimitive("x".repeat(701)),
            "sentence_ids" to JsonArray(listOf(JsonPrimitive("s-private"), JsonPrimitive("s-private"), JsonPrimitive(7))),
            "evidence" to JsonPrimitive("private-do-not-save")))
        val report = ReadingAnalysisAuditMetadata.inspect(response(emptyList(),
            questions = JsonArray(listOf(duplicate, unknown, wrongParagraph, badShape))), 2, mapOf("s-private" to 0))
        assertEquals(1, report.getValue("questions_duplicate_ids_count").jsonPrimitive.int)
        assertEquals(3, report.getValue("questions_structure_valid_count").jsonPrimitive.int)
        assertEquals(1, report.getValue("questions_reference_valid_count").jsonPrimitive.int)
        assertFalse(report.getValue("questions_contract_valid").jsonPrimitive.boolean)
        val rows = report.getValue("questions").jsonArray.map { it.jsonObject }
        assertEquals(1, rows[1].getValue("sentence_ids_unknown_count").jsonPrimitive.int)
        assertTrue(rows[2].getValue("sentence_ids_reference_valid").jsonPrimitive.boolean)
        assertFalse(rows[2].getValue("paragraph_set_matches_evidence").jsonPrimitive.boolean)
        assertEquals(1, rows[3].getValue("sentence_ids_non_string_count").jsonPrimitive.int)
        assertEquals(1, rows[3].getValue("sentence_ids_duplicate_count").jsonPrimitive.int)
        assertEquals(1, rows[3].getValue("paragraphs_duplicate_count").jsonPrimitive.int)
        assertEquals(1, rows[3].getValue("forbidden_evidence_fields_count").jsonPrimitive.int)
        listOf("q-private", "q-third", "q-fourth", "s-private", "unknown-private", "private-do-not-save",
            "x".repeat(701)).forEach { assertFalse(report.toString().contains(it)) }
    }

    @Test fun unavailableSourceReferenceValidationIsNotReportedAsZeroValidEvidence() {
        val wire = response(emptyList(), questions = JsonArray(listOf(question("q", listOf(0), listOf("s")))))
        val unavailable = ReadingAnalysisAuditMetadata.inspect(wire, 1)
        assertFalse(unavailable.getValue("sentence_reference_validation_available").jsonPrimitive.boolean)
        assertEquals(JsonNull, unavailable["questions_reference_valid_count"])
        assertEquals(JsonNull, unavailable.getValue("questions").jsonArray[0].jsonObject["references_valid"])
        val invalid = ReadingAnalysisAuditMetadata.inspect(wire, 1, emptyMap())
        assertTrue(invalid.getValue("sentence_reference_validation_available").jsonPrimitive.boolean)
        assertEquals(0, invalid.getValue("questions_reference_valid_count").jsonPrimitive.int)
        assertFalse(invalid.getValue("questions").jsonArray[0].jsonObject.getValue("references_valid").jsonPrimitive.boolean)
    }

    @Test fun oversizedParagraphAndQuestionArraysHaveExplicitlyIncompleteAggregateMetadata() {
        val report = ReadingAnalysisAuditMetadata.inspect(response(emptyList(),
            paragraphs = JsonArray((0..512).map { paragraph(JsonPrimitive(it)) }),
            questions = JsonArray((0..512).map { question("q-$it", listOf(0), listOf("s")) })), 80, mapOf("s" to 0))
        listOf("paragraphs", "questions").forEach { field ->
            assertEquals(513, report.getValue("${field}_count").jsonPrimitive.int)
            assertEquals(512, report.getValue("${field}_audited_count").jsonPrimitive.int)
            assertTrue(report.getValue("${field}_metadata_truncated").jsonPrimitive.boolean)
            assertEquals(JsonNull, report["${field}_structure_valid_count"])
        }
        assertEquals(JsonNull, report["paragraphs_missing_indices"])
        assertEquals(JsonNull, report["questions_reference_valid_count"])
        assertEquals(JsonNull, report["questions_contract_valid"])
    }
}
