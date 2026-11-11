package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Self-authored selected paragraphs; all network tests use local MockWebServer. */
class ReadingScopeProtocolTest {
    private val article = Article("scoped-protocol", "fixture", listOf("fixture"), "A synthetic neighbourhood archive",
        "https://example.org/scoped", "SUMMARY_MUST_NOT_BE_SENT", sourceName = "Synthetic fixture")
    private val text = (0 until 120).joinToString("\n\n") { index ->
        "Unique marker P${index.toString().padStart(3, '0')}. " +
            "Residents review a substantial collection of historical records and compare the available evidence before deciding how the public reading room should operate. ".repeat(2) +
            "A shared phrase repeats. A shared phrase repeats. 😀 The records remain open."
    }
    private val body = ArticleBody(article.id, text, sha256(text), 0, Long.MAX_VALUE, "Self-authored fixture")
    private val scope get() = ReadingStudyScopes.create(body, 90, 93)
    private fun units(scope: StudyScope) = ReadingSkimPlanner.sentences(text).filter { it.paragraphIndex in scope.firstParagraph until scope.endParagraphExclusive }
    private fun raw(scope: StudyScope = this.scope): JsonObject {
        val evidence = units(scope).first()
        return buildJsonObject {
            put("summary", "Selected paragraphs only"); put("cefr_estimate", "B2")
            putJsonArray("nodes") { add(buildJsonObject {
                put("id", "root"); put("parent_id", JsonNull); put("label", "Selected records"); put("paragraph", scope.firstParagraph)
            }) }
            putJsonArray("highlights") { add(buildJsonObject {
                put("id", "h1"); put("quote", "A shared phrase repeats."); put("paragraph", scope.firstParagraph)
                put("occurrence", 2); put("kind", "collocation"); put("explanation", "Synthetic example"); put("exam", "both")
            }) }
            putJsonArray("paragraphs") { (scope.firstParagraph until scope.endParagraphExclusive).forEach { index -> add(buildJsonObject {
                put("index", index); put("role", "example"); put("summary", "Selected paragraph")
            }) } }
            putJsonArray("questions") { add(buildJsonObject {
                put("id", "q1"); put("question", "Which records are discussed?"); put("answer", "The selected records.")
                putJsonArray("paragraphs") { add(evidence.paragraphIndex) }; putJsonArray("sentence_ids") { add(evidence.id) }
            }) }
            putJsonArray("backgrounds") { add(buildJsonObject {
                put("term", "records"); put("meaning", "Documented information"); put("why_here", "The selected discussion")
                put("paragraph", scope.firstParagraph); put("uncertainty", "Synthetic contextual explanation")
            }) }
        }
    }
    private fun parse(value: JsonObject = raw(), scope: StudyScope = this.scope) =
        ReadingAiProtocol.parseAnalysis(value.toString(), article, body, "synthetic", 1, scope)
    private fun replace(value: JsonObject, field: String, operation: (JsonObject) -> JsonObject) =
        JsonObject(value + (field to JsonArray(value.getValue(field).jsonArray.mapIndexed { i, item -> if (i == 0) operation(item.jsonObject) else item })))

    @Test fun requestContainsOnlyTheChosenTextWithOriginalGlobalIndicesAndSentenceIds() {
        assertTrue(body.text.length > 24_000); assertTrue(scope.start > 24_000)
        val payload = ReadingAiProtocol.analysisPayload(article, body, scope)
        val paragraphs = payload.getValue("paragraphs").jsonArray
        assertEquals(listOf(90, 91, 92), paragraphs.map { it.jsonObject.getValue("index").jsonPrimitive.int })
        val original = ParagraphFocus.paragraphs(text)
        assertEquals((90..92).map { text.substring(original[it].start, original[it].endExclusive) },
            paragraphs.map { it.jsonObject.getValue("text").jsonPrimitive.content })
        assertFalse(payload.toString().contains("P000")); assertFalse(payload.toString().contains("P119"))
        assertFalse(payload.toString().contains(article.summary))
        assertEquals(units(scope).map { it.id }, payload.getValue("sentence_units").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(3, payload.getValue("client_constraints").jsonObject.getValue("paragraph_count").jsonPrimitive.int)
    }

    @Test fun parsedOffsetsStayGlobalAndRepeatedQuotesResolveWithinTheSelectedParagraph() {
        val result = parse()
        assertEquals(body.contentHash, result.bodyHash); assertEquals(scope, result.scope)
        assertEquals(listOf(90, 91, 92), result.paragraphs.map { it.index })
        val paragraph = ParagraphFocus.paragraphs(text)[90]
        val first = text.indexOf("A shared phrase repeats.", paragraph.start)
        val second = text.indexOf("A shared phrase repeats.", first + 1)
        assertEquals(second, result.highlights.single().start)
        assertEquals("A shared phrase repeats.", text.substring(result.highlights.single().start, result.highlights.single().end))
        assertEquals(units(scope).first().evidenceRange(), result.questions.single().evidence.single())
        assertTrue(result.questions.single().id.startsWith("sc-")); assertTrue(result.questions.single().id.length <= 80)
        ReadingStudyScopes.validateStudy(result, body)
    }

    @Test fun distinctScopesNamespaceQuestionIdsButReuseExactGlobalSentenceEvidence() {
        val a = ReadingStudyScopes.create(body, 90, 92); val b = ReadingStudyScopes.create(body, 90, 93)
        val left = parse(raw(a), a); val right = parse(raw(b), b)
        assertNotEquals(left.questions.single().id, right.questions.single().id)
        assertEquals(left.questions.single().evidence, right.questions.single().evidence)
        assertEquals(left.highlights.single().start, right.highlights.single().start)
    }

    @Test fun everyReferenceFamilyRejectsUnselectedOriginalParagraphs() {
        val value = raw()
        val unselected = ReadingSkimPlanner.sentences(text).first()
        val variants = listOf(
            replace(value, "nodes") { JsonObject(it + ("paragraph" to JsonPrimitive(0))) },
            replace(value, "highlights") { JsonObject(it + ("paragraph" to JsonPrimitive(0))) },
            replace(value, "paragraphs") { JsonObject(it + ("index" to JsonPrimitive(0))) },
            replace(value, "questions") { JsonObject(it + ("paragraphs" to JsonArray(listOf(JsonPrimitive(0)))) +
                ("sentence_ids" to JsonArray(listOf(JsonPrimitive(unselected.id))))) },
            replace(value, "questions") { JsonObject(it + ("sentence_ids" to JsonArray(listOf(JsonPrimitive(unselected.id))))) },
            replace(value, "backgrounds") { JsonObject(it + ("paragraph" to JsonPrimitive(0))) },
        )
        variants.forEach { assertEquals("invalid_schema", assertThrows(ApiFailure::class.java) { parse(it) }.code) }
    }

    @Test fun scopeAndBodyMutationCannotBorrowAnOldSelection() {
        assertThrows(ApiFailure::class.java) { ReadingAiProtocol.analysisPayload(article, body, scope.copy(contentHash = "0".repeat(64))) }
        assertThrows(ApiFailure::class.java) { ReadingAiProtocol.analysisPayload(article, body, scope.copy(start = scope.start + 1)) }
        assertThrows(ApiFailure::class.java) { ReadingAiProtocol.analysisPayload(article, body.copy(contentHash = "stale"), scope) }
        assertThrows(ApiFailure::class.java) { ReadingAiProtocol.analysisPayload(article, body) }
    }

    @Test fun transportSendsOneScopedRequestAndUsesOnlyTheSelectedCountForBudget() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ReadingAiFixtures.response(raw().toString())); server.start()
            val ai = DeepSeekReadingAi({ "synthetic-key" }, OkHttpClient(), server.url("/chat/completions").toString(), sourceAllowed = { true }, clock = { 1 })
            val result = ai.analyzeRange(article, body, scope)
            assertEquals(scope, result.scope); assertEquals(1, server.requestCount)
            val wire = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals(4096, wire.getValue("max_tokens").jsonPrimitive.int)
            val messages = wire.getValue("messages").jsonArray
            val prompt = messages[0].jsonObject.getValue("content").jsonPrimitive.content
            assertTrue(prompt.contains("ONLY the selected")); assertFalse(prompt.contains("Analyze the complete user-opened article"))
            assertTrue(prompt.contains("Do not infer unseen paragraphs"))
            assertEquals(ReadingAiProtocol.analysisPayload(article, body, scope),
                Json.parseToJsonElement(messages[1].jsonObject.getValue("content").jsonPrimitive.content))
        }
    }

    @Test fun scopedRepairKeepsTheExactSelectedInputAndDoesNotExpandToFullArticle() = runBlocking {
        MockWebServer().use { server ->
            val value = raw(); val missing = JsonObject(value + ("paragraphs" to JsonArray(value.getValue("paragraphs").jsonArray.dropLast(1))))
            server.enqueue(ReadingAiFixtures.response(missing.toString())); server.enqueue(ReadingAiFixtures.response(value.toString())); server.start()
            val ai = DeepSeekReadingAi({ "synthetic-key" }, OkHttpClient(), server.url("/chat/completions").toString(), sourceAllowed = { true })
            ai.analyzeRange(article, body, scope); assertEquals(2, server.requestCount)
            val requests = (1..2).map { Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject }
            assertEquals(requests[0].getValue("messages").jsonArray[1], requests[1].getValue("messages").jsonArray[1])
            assertEquals(requests[0].getValue("max_tokens"), requests[1].getValue("max_tokens"))
        }
    }
}
