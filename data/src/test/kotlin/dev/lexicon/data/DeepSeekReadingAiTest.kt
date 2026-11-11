package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

internal object ReadingAiFixtures {
    val text = "😀 A repeated expression can guide a reader. The repeated expression has the same spelling.\n\nA public library provides access to books, but this example makes no claim about a particular city."
    val article = Article("study-article", "g", listOf("allowed-source"), "Synthetic reading example", "https://example.org/study", "Do not send this summary.", sourceName = "Synthetic fixture")
    val body = ArticleBody(article.id, text, sha256(text), 1, 1000, "Synthetic example")
    val bundle = """{"summary":"阅读表达与公共图书馆的合成例文。","cefr_estimate":"B2",
      "nodes":[{"id":"n0","parent_id":null,"label":"主旨","paragraph":null},{"id":"n1","parent_id":"n0","label":"例示","paragraph":1}],
      "highlights":[{"id":"h1","quote":"repeated expression","paragraph":0,"occurrence":2,"kind":"collocation","explanation":"重复表达","exam":"both"}],
      "paragraphs":[{"index":0,"role":"introduction","summary":"提出表达"},{"index":1,"role":"example","summary":"介绍图书馆例示"}],
      "questions":[{"id":"q1","question":"图书馆提供什么？","answer":"书籍。","paragraphs":[1],"sentence_ids":["${ReadingSkimPlanner.sentences(text).last().id}"]}],
      "backgrounds":[{"term":"public library","meaning":"公共图书馆","why_here":"解释例文中的机构","paragraph":1,"uncertainty":"没有特定城市的信息。"}]}"""
    fun assist(text: String = "A complete synthetic response.", score: JsonElement = JsonNull) = buildJsonObject {
        put("title", "学习辅助"); put("text", text); put("score", score); put("uncertainty", "输入没有提供外部事实核验依据。")
    }.toString()
    fun response(content: String, finish: String = "stop") = MockResponse().setHeader("Content-Type", "application/json").setBody(buildJsonObject {
        put("model", "actual-test-model")
        putJsonArray("choices") { add(buildJsonObject { put("finish_reason", finish); putJsonObject("message") { put("content", content) } }) }
    }.toString())
}

class ReadingAiProtocolTest {
    private val article = ReadingAiFixtures.article
    private val body = ReadingAiFixtures.body
    private val bundle = ReadingAiFixtures.bundle
    private fun parse(raw: String) = ReadingAiProtocol.parseAnalysis(raw, article, body, "model", 100)

    @Test fun wholeSnapshotIsParagraphIndexedAndNeverSilentlyTruncated() {
        val payload = ReadingAiProtocol.analysisPayload(article, body)
        val rows = payload.getValue("paragraphs").jsonArray
        assertEquals(2, rows.size)
        assertEquals(ParagraphFocus.paragraphs(body.text).map { body.text.substring(it.start, it.endExclusive) }, rows.map { it.jsonObject.getValue("text").jsonPrimitive.content })
        assertFalse(payload.toString().contains(article.summary))
        val over = "x".repeat(ReadingAiProtocol.MAX_INPUT + 1)
        val failure = assertThrows(ApiFailure::class.java) { ReadingAiProtocol.analysisPayload(article, body.copy(text = over, contentHash = sha256(over))) }
        assertEquals("input_too_long", failure.code)
        assertThrows(ApiFailure::class.java) { ReadingAiProtocol.analysisPayload(article, body.copy(contentHash = "stale")) }
    }

    @Test fun quotedOccurrenceResolvesClientUtf16OffsetsWithEmojiAndRepeatedWords() {
        val result = parse(bundle)
        val highlight = result.highlights.single()
        assertEquals(body.text.indexOf("repeated expression", body.text.indexOf("repeated expression") + 1), highlight.start)
        assertEquals(highlight.quote, body.text.substring(highlight.start, highlight.end))
        assertEquals(article.id, result.articleId)
        assertEquals(body.contentHash, result.bodyHash)
        assertEquals(100L, result.createdAt)
        assertTrue(result.backgrounds.single().uncertainty.contains("未作外部查证"))
        val injected = bundle.replace("\"occurrence\":2", "\"occurrence\":2,\"start\":9999,\"end\":10000")
        assertEquals(highlight.start, parse(injected).highlights.single().start)
    }

    @Test fun wrongQuotesAmbiguousOccurrencesAndOutOfRangeReferencesAreRejected() {
        listOf(
            bundle.replace("\"occurrence\":2,", ""),
            bundle.replace("\"occurrence\":2", "\"occurrence\":0"),
            bundle.replace("\"occurrence\":2", "\"occurrence\":\"2\""),
            bundle.replace("\"quote\":\"repeated expression\"", "\"quote\":\"fabricated quotation\""),
            bundle.replace("\"paragraph\":0", "\"paragraph\":20"),
            bundle.replace("\"paragraphs\":[1]", "\"paragraphs\":[1,1]"),
            bundle.replace("\"term\":\"public library\"", "\"term\":\"not mentioned\""),
        ).forEach { assertThrows(ApiFailure::class.java) { parse(it) } }
    }

    @Test fun malformedTreeEnumsAndParagraphCoverageAreRejected() {
        listOf(
            bundle.replace("\"parent_id\":null", "\"parent_id\":\"n1\""),
            bundle.replace("\"parent_id\":\"n0\"", "\"parent_id\":\"unknown\""),
            bundle.replace("\"id\":\"n1\"", "\"id\":\"n0\""),
            bundle.replace("\"cefr_estimate\":\"B2\"", "\"cefr_estimate\":\"D1\""),
            bundle.replace("\"role\":\"example\"", "\"role\":\"opinionated\""),
            bundle.replace("\"kind\":\"collocation\"", "\"kind\":\"guaranteed_exam\""),
            bundle.replace("\"index\":1", "\"index\":0"),
        ).forEach { assertThrows(ApiFailure::class.java) { parse(it) } }
    }

    @Test fun comparePayloadContainsExactlyTwoUserTitlesAndNoInventedArticleInputs() {
        val request = StudyRequest("compare", "Council considers tree planting", "Residents question planting cost", title = "unused", source = "unused")
        val payload = ReadingAiProtocol.assistPayload(request)
        assertEquals(setOf("kind", "titles"), payload.keys)
        assertEquals(listOf(request.text, request.context), payload.getValue("titles").jsonArray.map { it.jsonObject.getValue("title").jsonPrimitive.content })
        assertThrows(ApiFailure::class.java) { ReadingAiProtocol.assistPayload(request.copy(context = "")) }
    }

    @Test fun onlyAnswerChecksMayReturnFiniteScoresInUnitInterval() {
        val request = StudyRequest("check_answer", "My answer", "Question and supplied reference")
        assertEquals(.75, ReadingAiProtocol.parseAssist(ReadingAiFixtures.assist(score = JsonPrimitive(.75)), request, "model", 1).score!!, 0.0)
        assertNull(ReadingAiProtocol.parseAssist(ReadingAiFixtures.assist(), request, "model", 1).score)
        listOf(JsonPrimitive(75), JsonPrimitive(-.1), JsonPrimitive("0.5")).forEach { score ->
            assertThrows(ApiFailure::class.java) { ReadingAiProtocol.parseAssist(ReadingAiFixtures.assist(score = score), request, "model", 1) }
        }
        assertThrows(ApiFailure::class.java) { ReadingAiProtocol.parseAssist(ReadingAiFixtures.assist(score = JsonPrimitive(.5)), request.copy(kind = "rewrite"), "model", 1) }
    }

    @Test fun backgroundRequiresExplicitLimitsAndRewriteRequiresSupportedLevel() {
        val background = StudyRequest("background", "public library", "An example refers to a public library.")
        // Legacy ungrounded responses are no longer accepted for cultural background.
        assertThrows(ApiFailure::class.java) { ReadingAiProtocol.parseAssist(ReadingAiFixtures.assist(), background, "model", 1) }
        assertThrows(ApiFailure::class.java) { ReadingAiProtocol.parseAssist("""{"title":"a","text":"b","score":null}""", background, "model", 1) }
        assertThrows(ApiFailure::class.java) { ReadingAiProtocol.assistPayload(StudyRequest("rewrite", "Input text", targetLevel = "C2")) }
        listOf("B1", "B2", "C1").forEach { ReadingAiProtocol.assistPayload(StudyRequest("rewrite", "Input text", targetLevel = it)) }
    }

    @Test fun reviewKeepsUserCommentsSeparateFromQuotesAndRejectsOversizedCollections() {
        val note = ReadingNote("n1", null, null, "Original quote", "My comment", listOf("syntax"), 1)
        val row = ReadingAiProtocol.reviewPayload(listOf(note)).getValue("notes").jsonArray.single().jsonObject
        assertEquals(note.quote, row.getValue("original_quote").jsonPrimitive.content)
        assertEquals(note.comment, row.getValue("user_comment").jsonPrimitive.content)
        assertThrows(ApiFailure::class.java) { ReadingAiProtocol.reviewPayload(emptyList()) }
        assertThrows(ApiFailure::class.java) { ReadingAiProtocol.reviewPayload((0..30).map { note.copy(id = "n$it") }) }
        assertThrows(ApiFailure::class.java) { ReadingAiProtocol.reviewPayload(listOf(note.copy(quote = "x".repeat(24_001)))) }
        val review = ReadingAiProtocol.parseReview(ReadingAiFixtures.assist(), "model", 1, 1)
        assertEquals("review", review.kind); assertNull(review.score)
    }
}

class DeepSeekReadingAiTest {
    private fun service(server: MockWebServer, allowed: Boolean = true) = DeepSeekReadingAi(
        { "test-key" }, OkHttpClient(), server.url("/chat/completions").toString(), sourceAllowed = { allowed }, clock = { 123L })

    @Test fun bundleRepairIsOnceAndKeepsTheSameFullInput() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ReadingAiFixtures.response("{}")); server.enqueue(ReadingAiFixtures.response(ReadingAiFixtures.bundle)); server.start()
            val result = service(server).analyze(ReadingAiFixtures.article, ReadingAiFixtures.body)
            assertEquals("actual-test-model", result.model); assertEquals(123L, result.createdAt); assertEquals(2, server.requestCount)
            val first = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals(first.getValue("messages").jsonArray[1], second.getValue("messages").jsonArray[1])
            assertEquals(4096, first.getValue("max_tokens").jsonPrimitive.int)
        }
    }

    @Test fun restrictedSourcesAndAssistsNeverStartNetworkCalls() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            try { service(server, false).analyze(ReadingAiFixtures.article, ReadingAiFixtures.body); fail() }
            catch (e: ApiFailure) { assertEquals("source_restricted", e.code) }
            try { service(server).assist(StudyRequest("syntax", "Input", aiAllowed = false)); fail() }
            catch (e: ApiFailure) { assertEquals("source_restricted", e.code) }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun authenticationAndIncompleteResponsesDoNotGetPaidRepairRetries() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("secret material test-key"))
            server.enqueue(ReadingAiFixtures.response("partial text", "length")); server.start()
            val ai = service(server)
            try { ai.assist(StudyRequest("syntax", "Input")); fail() }
            catch (e: ApiFailure) { assertEquals("authentication", e.code); assertFalse(e.message.contains("secret")); assertFalse(e.message.contains("test-key")) }
            assertEquals(1, server.requestCount)
            try { ai.assist(StudyRequest("syntax", "Input")); fail() }
            catch (e: ApiFailure) { assertEquals("incomplete", e.code) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun rewriteCarriesLevelAndPreservesClaimsInstructionWithoutOriginalMetadataFromModel() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ReadingAiFixtures.response(ReadingAiFixtures.assist("Researchers compared two groups."))); server.start()
            val request = StudyRequest("rewrite", "The researchers compared two groups.", targetLevel = "B1")
            val result = service(server).assist(request)
            assertTrue(result.evidence.contains("AI 改写 · B1"))
            val wire = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val messages = wire.getValue("messages").jsonArray
            assertTrue(messages[0].jsonObject.getValue("content").jsonPrimitive.content.contains("Preserve every substantive claim"))
            val input = Json.parseToJsonElement(messages[1].jsonObject.getValue("content").jsonPrimitive.content).jsonObject
            assertEquals(request.text, input.getValue("text").jsonPrimitive.content)
            assertEquals("B1", input.getValue("target_level").jsonPrimitive.content)
        }
    }
}
