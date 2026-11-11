package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DeepSeekServiceTest {
    private val cardJson = """{"headword":"mitigate","context_definition":"reduce harm here",
        "collocations":[{"phrase":"mitigate risk","example":"Planning can mitigate risk."},
        {"phrase":"mitigate harm","example":"Trees can mitigate harm from heat."}],
        "paraphrase":"reduce the adverse effects","register_tag":"formal","exam_relevance":"medium"}"""
    private fun response(content: String, reason: String = "stop") = MockResponse().setHeader("Content-Type", "application/json").setBody(buildJsonObject {
        put("model", "returned-model"); putJsonArray("choices") { add(buildJsonObject {
            put("finish_reason", reason); putJsonObject("message") { put("content", content) }
        }) }
    }.toString())
    private fun capture() = Capture("capture-1", target = "mitigate", selectedText = "mitigate", sentence = "Trees can mitigate urban heat.", createdAt = 1, contextOrigin = ContextOrigin.ORIGINAL)

    @Test fun schemaRepairIsBoundedAndOriginalInputIsRetained() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response("{}")); server.enqueue(response(cardJson)); server.start()
            val service = DeepSeekService({ "test-key" }, OkHttpClient(), server.url("/chat/completions").toString())
            val result = service.analyze(capture(), false)
            assertEquals("returned-model", result.model)
            assertEquals(2, server.requestCount)
            val first = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals(first.getValue("messages").jsonArray[1], second.getValue("messages").jsonArray[1])
            assertEquals("deepseek-flash", first.getValue("model").jsonPrimitive.content)
        }
    }

    @Test fun emptyResponseGetsAtMostOneRepair() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response("")); server.enqueue(response("")); server.start()
            val service = DeepSeekService({ "test-key" }, OkHttpClient(), server.url("/").toString())
            try { service.analyze(capture()); fail("Expected failure") }
            catch (e: ApiFailure) { assertEquals("empty_response", e.code) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun authenticationFailureDoesNotRetryOrExposeServerBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("sensitive original text and test-key")); server.start()
            val service = DeepSeekService({ "test-key" }, OkHttpClient(), server.url("/").toString())
            try { service.analyze(capture()); fail("Expected failure") }
            catch (e: ApiFailure) { assertEquals("authentication", e.code); assertFalse(e.message.contains("sensitive")); assertFalse(e.message.contains("test-key")) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun rateLimitIsRecoverableWithoutAutomaticPaidRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429)); server.start()
            val service = DeepSeekService({ "test-key" }, OkHttpClient(), server.url("/").toString())
            try { service.analyze(capture()); fail("Expected failure") }
            catch (e: ApiFailure) { assertEquals("rate_limit", e.code) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun deepAnalysisUsesProButNeverIncludesWholeArticle() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response(cardJson)); server.start()
            val service = DeepSeekService({ "test-key" }, OkHttpClient(), server.url("/").toString())
            service.analyze(capture(), deep = true)
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("deepseek-v4-pro", body.getValue("model").jsonPrimitive.content)
            val input = Json.parseToJsonElement(body.getValue("messages").jsonArray[1].jsonObject.getValue("content").jsonPrimitive.content).jsonObject
            assertFalse(input.containsKey("body")); assertEquals(capture().sentence, input.getValue("source_sentence").jsonPrimitive.content)
        }
    }

    @Test fun restrictedSourcesDoNotStartRequests() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val service = DeepSeekService({ "test-key" }, OkHttpClient(), server.url("/").toString())
            try { service.analyze(capture().copy(aiAllowed = false)); fail("Expected failure") }
            catch (e: ApiFailure) { assertEquals("source_restricted", e.code) }
            assertEquals(0, server.requestCount)
        }
    }
}
