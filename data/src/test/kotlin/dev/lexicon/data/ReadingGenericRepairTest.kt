package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Non-article tasks repair their own schema without inheriting article sentence constraints. */
class ReadingGenericRepairTest {
    private data class Operation(
        val kind: String,
        val input: JsonObject,
        val budget: Int,
        val instructions: List<String>,
        val call: suspend (DeepSeekReadingAi) -> StudyAssist,
    )

    private fun translation(): Operation {
        val request = StudyRequest("translation", "The study found a qualified association, not a causal effect.",
            context = "The comparison includes two groups.", title = "Synthetic study", source = "Synthetic fixture")
        return Operation("translation", ReadingAiProtocol.assistPayload(request), 6000,
            listOf("Translate the supplied English text into natural Chinese.", "Preserve attribution, qualifiers, numbers")) {
            it.assist(request)
        }
    }

    private fun rewrite(): Operation {
        val request = StudyRequest("rewrite", "The researchers reported a possible association in both groups.",
            context = "The study does not establish causation.", targetLevel = "B1", title = "Synthetic study")
        return Operation("rewrite", ReadingAiProtocol.assistPayload(request), 6000,
            listOf("Rewrite the entire supplied text", "Preserve every substantive claim", "This is an AI learning adaptation")) {
            it.assist(request)
        }
    }

    private fun review(): Operation {
        val notes = listOf(
            ReadingNote("original-note", null, null, "The evidence remains limited.", "Keep the qualification.",
                listOf("argument"), 1L, origin = ContextOrigin.ORIGINAL),
            ReadingNote("adapted-note", null, null, "There is only limited evidence.", "A simpler learning adaptation.",
                listOf("rewrite"), 2L, origin = ContextOrigin.AI_GENERATED),
            ReadingNote("user-note", null, null, "My own practice sentence.", "User-written practice.",
                listOf("practice"), 3L, origin = ContextOrigin.USER_SUPPLIED),
        )
        return Operation("review", ReadingAiProtocol.reviewPayload(notes), 4000,
            listOf("Preserve every note's origin.", "AI_GENERATED.ai_rewrite", "USER_SUPPLIED.user_supplied_text",
                "Keep user_comment separate from saved text.")) { it.review(notes) }
    }

    private fun service(server: MockWebServer) = DeepSeekReadingAi(
        { "synthetic-key" }, OkHttpClient(), server.url("/chat/completions").toString(), clock = { 1L })
    private fun wire(server: MockWebServer) = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    private fun assertRepairMessages(operation: Operation, first: JsonObject, second: JsonObject) {
        val initialMessages = first.getValue("messages").jsonArray
        val repairedMessages = second.getValue("messages").jsonArray
        assertEquals(2, initialMessages.size); assertEquals(2, repairedMessages.size)
        val initialSystem = initialMessages[0].jsonObject.getValue("content").jsonPrimitive.content
        val repairedSystem = repairedMessages[0].jsonObject.getValue("content").jsonPrimitive.content
        assertEquals(initialSystem + ReadingAiProtocol.repairInstruction(null), repairedSystem)
        operation.instructions.forEach { assertTrue(repairedSystem.contains(it)) }
        listOf("sentence IDs", "paragraph indices", "sentence_units", "sentence_ids", "private-invalid-response")
            .forEach { assertFalse(repairedSystem.contains(it, ignoreCase = true)) }
        assertEquals(initialMessages[1], repairedMessages[1])
        val input = Json.parseToJsonElement(repairedMessages[1].jsonObject.getValue("content").jsonPrimitive.content).jsonObject
        assertEquals(operation.input, input)
        assertEquals(operation.budget, first.getValue("max_tokens").jsonPrimitive.int)
        assertEquals(first.getValue("max_tokens"), second.getValue("max_tokens"))
    }

    private suspend fun successfulRepair(operation: Operation, invalid: String) {
        MockWebServer().use { server ->
            server.enqueue(ReadingAiFixtures.response(invalid))
            server.enqueue(ReadingAiFixtures.response(ReadingAiFixtures.assist("Complete synthetic task result.")))
            server.start()
            val result = operation.call(service(server))
            assertEquals(operation.kind, result.kind)
            assertEquals("Complete synthetic task result.", result.text)
            assertEquals(2, server.requestCount)
            assertRepairMessages(operation, wire(server), wire(server))
        }
    }

    @Test fun translationRepairsInvalidJsonWithoutLosingItsFullInputOrTranslationRules() = runBlocking {
        successfulRepair(translation(), "{private-invalid-response")
    }

    @Test fun rewriteRepairsMissingFieldsWithoutAddingArticleReferenceRequirements() = runBlocking {
        successfulRepair(rewrite(), """{"title":"private-invalid-response","score":null}""")
    }

    @Test fun reviewRepairsItsSchemaWhilePreservingEveryNotesProvenance() = runBlocking {
        successfulRepair(review(), "{}")
    }

    @Test fun twoInvalidResponsesDoNotStartAThirdCallForAnyGenericTask() = runBlocking {
        listOf(translation(), rewrite(), review()).forEach { operation ->
            MockWebServer().use { server ->
                server.enqueue(ReadingAiFixtures.response("{private-invalid-response"))
                server.enqueue(ReadingAiFixtures.response("{}"))
                // A mistakenly added third attempt fails this test immediately instead of waiting for a response.
                server.enqueue(ReadingAiFixtures.response(ReadingAiFixtures.assist()))
                server.start()
                val failure = try {
                    operation.call(service(server)); throw AssertionError("Expected schema rejection")
                } catch (failure: ApiFailure) { failure }
                assertEquals("invalid_schema", failure.code); assertNull(failure.validationSection)
                assertEquals(2, server.requestCount)
                assertRepairMessages(operation, wire(server), wire(server))
            }
        }
    }
}
