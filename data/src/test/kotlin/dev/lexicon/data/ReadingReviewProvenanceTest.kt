package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ReadingReviewProvenanceTest {
    private fun note(origin: ContextOrigin) = ReadingNote(origin.name, null, null, "Saved synthetic text.", "My comment", emptyList(), 1, origin = origin)

    @Test fun everySavedOriginHasItsOwnTextFieldAndOnlyOriginalIsAnOriginalQuote() {
        val rows = ReadingAiProtocol.reviewPayload(ContextOrigin.entries.map(::note)).getValue("notes").jsonArray
            .associate { it.jsonObject.getValue("origin").jsonPrimitive.content to it.jsonObject }
        val fields = mapOf(ContextOrigin.ORIGINAL to "original_quote", ContextOrigin.AI_GENERATED to "ai_rewrite",
            ContextOrigin.USER_SUPPLIED to "user_supplied_text", ContextOrigin.MISSING to "unverified_text")
        fields.forEach { (origin, field) ->
            val row = rows.getValue(origin.name)
            assertEquals(note(origin).quote, row.getValue(field).jsonPrimitive.content)
            assertEquals(note(origin).comment, row.getValue("user_comment").jsonPrimitive.content)
            assertEquals(setOf(field), row.keys.intersect(fields.values.toSet()))
            if (origin != ContextOrigin.ORIGINAL) assertFalse(row.containsKey("original_quote"))
        }
    }

    @Test fun reviewPromptForbidsAttributingAiRewriteToThePublisher() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ReadingAiFixtures.response(ReadingAiFixtures.assist()))
            server.start()
            val service = DeepSeekReadingAi({ "test-key" }, OkHttpClient(), server.url("/chat/completions").toString(), clock = { 1 })
            service.review(listOf(note(ContextOrigin.ORIGINAL), note(ContextOrigin.AI_GENERATED)))
            val request = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val messages = request.getValue("messages").jsonArray
            val instruction = messages[0].jsonObject.getValue("content").jsonPrimitive.content
            assertTrue(instruction.contains("Only ORIGINAL.original_quote"))
            assertTrue(instruction.contains("AI_GENERATED.ai_rewrite"))
            assertTrue(instruction.contains("not publisher wording"))
            val input = Json.parseToJsonElement(messages[1].jsonObject.getValue("content").jsonPrimitive.content).jsonObject
            val aiRow = input.getValue("notes").jsonArray.last().jsonObject
            assertEquals("AI_GENERATED", aiRow.getValue("origin").jsonPrimitive.content)
            assertFalse(aiRow.containsKey("original_quote"))
            assertEquals(1, server.requestCount)
        }
    }
}
