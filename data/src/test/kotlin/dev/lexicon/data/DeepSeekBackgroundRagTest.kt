package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Uses synthetic, client-owned evidence and a local model endpoint; no public network or Key. */
class DeepSeekBackgroundRagTest {
    private val source = BackgroundSource("S1", "Municipal council", "https://en.wikipedia.org/w/index.php?curid=10&oldid=20",
        "A municipal council is a local governing body. This synthetic evidence describes its role in local decisions and services.",
        1000, "Wikipedia (en)", "20")
    private val request = StudyRequest("background", "city council", "A city council is considering a courtyard project.")
    private fun answer(text: String = "市议会参与地方决策和服务。[S1]", ids: List<String> = listOf("S1"), extra: Boolean = false) = buildJsonObject {
        put("title", "市议会背景"); put("text", text); put("score", JsonNull)
        put("uncertainty", "该片段未说明原文所涉城市。")
        putJsonArray("cited_source_ids") { ids.forEach(::add) }
        if (extra) putJsonArray("sources") { add(buildJsonObject { put("url", "https://invented.invalid/"); put("title", "Invented") }) }
    }.toString()
    private fun parse(raw: String) = ReadingAiProtocol.parseBackground(raw, listOf(source), "actual-model", 1001)
    private class Retriever(var result: BackgroundRetrieval) : BackgroundRetriever {
        var calls = 0; var term = ""; var context = ""
        override suspend fun retrieve(term: String, context: String): BackgroundRetrieval {
            calls++; this.term = term; this.context = context; return result
        }
    }
    private fun service(server: MockWebServer, retriever: BackgroundRetriever, key: String? = "synthetic-key") =
        DeepSeekReadingAi({ key }, OkHttpClient(), server.url("/chat/completions").toString(),
            clock = { 1001L }, backgroundRetriever = retriever)

    @Test fun acceptsOnlyProvidedCitationsAndClientSourceMetadata() {
        val result = parse(answer(extra = true))
        assertEquals(listOf(source), result.sources)
        assertEquals("retrieved", result.retrievalStatus)
        assertEquals(listOf("S1"), result.citedSourceIds)
        assertFalse(result.evidence.contains("未检索"))
        assertFalse(result.toString().contains("invented.invalid"))
        val second = source.copy(id = "S2", title = "Local decision making", url = "https://en.wikipedia.org/w/index.php?curid=11&oldid=21", revisionId = "21")
        val selective = ReadingAiProtocol.parseBackground(answer(), listOf(source, second), "actual-model", 1001)
        assertEquals(2, selective.sources.size); assertEquals(listOf("S1"), selective.citedSourceIds)
        listOf(answer(ids = listOf("S9")), answer(text = "伪造引用。[S9]"), answer(ids = listOf("S1", "S1")),
            answer(text = "没有引用。"), answer(text = "带额外编号。[S1] [99]"),
            answer(text = "来源 https://invented.invalid [S1]"), answer(text = "[来源](evil) [S1]"),
            answer(text = "<script>code</script>[S1]"), answer().replace("\"score\":null", "\"score\":1"))
            .forEach { invalid -> assertThrows(ApiFailure::class.java) { parse(invalid) } }
    }

    @Test fun requestContainsNumberedExcerptsAndMinimalContextButNoSourceUrlsOrWholeArticle() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ReadingAiFixtures.response(answer())); server.start()
            val retriever = Retriever(BackgroundRetrieval(listOf(source), "retrieved", "retrieved"))
            val result = service(server, retriever).assist(request.copy(context = "c".repeat(1200) + "PRIVATE_FULL_ARTICLE_MARKER"))
            val wire = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val messages = wire.getValue("messages").jsonArray
            val instruction = messages[0].jsonObject.getValue("content").jsonPrimitive.content
            val payload = Json.parseToJsonElement(messages[1].jsonObject.getValue("content").jsonPrimitive.content).jsonObject
            assertTrue(instruction.contains("Use ONLY the numbered retrieved_evidence"))
            assertEquals("city council", payload.getValue("selected_term").jsonPrimitive.content)
            assertEquals(1200, payload.getValue("reading_context").jsonPrimitive.content.length)
            assertEquals(source.excerpt, payload.getValue("retrieved_evidence").jsonArray.single().jsonObject.getValue("excerpt").jsonPrimitive.content)
            assertFalse(payload.toString().contains("PRIVATE_FULL_ARTICLE_MARKER")); assertFalse(payload.toString().contains(source.url))
            assertEquals(1, retriever.calls); assertEquals("city council", retriever.term); assertEquals(1200, retriever.context.length)
            assertEquals("actual-test-model", result.model); assertEquals(listOf(source), result.sources)
        }
    }

    @Test fun citationRepairReusesOneFrozenRetrievalAndNeverImportsModelUrls() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ReadingAiFixtures.response(answer(text = "Invented [S9]", ids = listOf("S9"))))
            server.enqueue(ReadingAiFixtures.response(answer(extra = true))); server.start()
            val retriever = Retriever(BackgroundRetrieval(listOf(source), "retrieved", "retrieved"))
            val result = service(server, retriever).assist(request)
            assertEquals(1, retriever.calls); assertEquals(2, server.requestCount); assertEquals(listOf(source), result.sources)
            val requests = (0..1).map { Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject }
            val messages = requests.map { it.getValue("messages").jsonArray }
            assertEquals(messages[0][1], messages[1][1])
            assertEquals(1800, requests[0].getValue("max_tokens").jsonPrimitive.int)
            assertEquals(requests[0].getValue("max_tokens"), requests[1].getValue("max_tokens"))
            val firstSystem = messages[0][0].jsonObject.getValue("content").jsonPrimitive.content
            val repairedSystem = messages[1][0].jsonObject.getValue("content").jsonPrimitive.content
            assertEquals(firstSystem + ReadingAiProtocol.repairInstruction(null), repairedSystem)
            assertTrue(repairedSystem.contains("Use ONLY the numbered retrieved_evidence"))
            listOf("sentence IDs", "paragraph indices", "sentence_units", "sentence_ids", "Invented [S9]").forEach { irrelevant ->
                assertFalse(repairedSystem.contains(irrelevant, ignoreCase = true))
            }
        }
    }

    @Test fun noEvidenceOrNoKeyNeverCallsModelAndStillReturnsExplicitUsefulState() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (status in listOf("no_results", "unavailable")) {
                val retriever = Retriever(BackgroundRetrieval(emptyList(), status, "未取得证据，请重试。"))
                val result = service(server, retriever).assist(request)
                assertEquals(status, result.retrievalStatus); assertTrue(result.sources.isEmpty()); assertEquals("retrieval-only", result.model)
                assertEquals(1, retriever.calls)
            }
            val result = service(server, Retriever(BackgroundRetrieval(listOf(source), "retrieved", "retrieved")), key = null).assist(request)
            assertEquals("retrieved", result.retrievalStatus); assertEquals("retrieval-only", result.model)
            assertTrue(result.text.contains(source.excerpt)); assertTrue(result.evidence.contains("未启用")); assertEquals(0, server.requestCount)
        }
    }

    @Test fun sourceAndInputChecksPrecedeRetrievalAndAuthenticationDoesNotRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("not logged synthetic-key")); server.start()
            val retriever = Retriever(BackgroundRetrieval(listOf(source), "retrieved", "retrieved"))
            val ai = service(server, retriever)
            for (invalid in listOf(request.copy(aiAllowed = false), request.copy(text = "x".repeat(161)))) {
                try { ai.assist(invalid); fail("Expected invalid request") } catch (_: ApiFailure) { }
            }
            assertEquals(0, retriever.calls); assertEquals(0, server.requestCount)
            try { ai.assist(request); fail("Expected authentication failure") } catch (e: ApiFailure) {
                assertEquals("authentication", e.code); assertFalse(e.message.contains("synthetic-key"))
            }
            assertEquals(1, retriever.calls); assertEquals(1, server.requestCount)
        }
    }

    @Test fun explicitRetrievalOnlyPathNeverReadsKeyAndSourceRevisionMustBePositive() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val retriever = Retriever(BackgroundRetrieval(listOf(source), "retrieved", "retrieved"))
            val ai = DeepSeekReadingAi({ throw AssertionError("Retrieval-only must not read Key") }, OkHttpClient(),
                server.url("/chat/completions").toString(), clock = { 1001L }, backgroundRetriever = retriever)
            val result = ai.retrieveBackground(request)
            assertEquals("retrieval-only", result.model); assertEquals(1, retriever.calls); assertEquals(0, server.requestCount)
            listOf("0", "-20", "not-a-revision").forEach { revision ->
                val invalid = source.copy(revisionId = revision, url = "https://en.wikipedia.org/w/index.php?curid=10&oldid=$revision")
                assertThrows(IllegalArgumentException::class.java) { ReadingAiProtocol.backgroundPayload(request, listOf(invalid)) }
            }
        }
    }
}
