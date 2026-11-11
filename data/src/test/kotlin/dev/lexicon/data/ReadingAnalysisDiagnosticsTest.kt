package dev.lexicon.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Synthetic failures expose only a fixed section label; response content is never retry feedback. */
class ReadingAnalysisDiagnosticsTest {
    private val marker = "private-response-fragment-do-not-echo"
    private val original = Json.parseToJsonElement(ReadingAiFixtures.bundle).jsonObject
    private fun first(field: String, transform: (JsonObject) -> JsonObject): String = JsonObject(original +
        (field to JsonArray(original.getValue(field).jsonArray.mapIndexed { index, element ->
            if (index == 0) transform(element.jsonObject) else element
        }))).toString()
    private fun invalidCases(): List<Pair<ReadingValidationSection, String>> = listOf(
        ReadingValidationSection.ENVELOPE to "{ malformed $marker",
        ReadingValidationSection.NODES to first("nodes") { JsonObject(it + ("parent_id" to JsonPrimitive(marker))) },
        ReadingValidationSection.HIGHLIGHTS to first("highlights") { JsonObject(it + ("quote" to JsonPrimitive(marker))) },
        ReadingValidationSection.PARAGRAPHS to first("paragraphs") { JsonObject(it + ("role" to JsonPrimitive(marker))) },
        ReadingValidationSection.QUESTIONS to first("questions") { JsonObject(it + ("sentence_ids" to JsonArray(listOf(JsonPrimitive(marker))))) },
        ReadingValidationSection.BACKGROUNDS to first("backgrounds") { JsonObject(it + ("term" to JsonPrimitive(marker))) },
    )
    private fun parse(raw: String) = ReadingAiProtocol.parseAnalysis(raw, ReadingAiFixtures.article, ReadingAiFixtures.body, "test-model", 1)
    private fun service(server: MockWebServer, repairs: Int = 1) = DeepSeekReadingAi(
        { "synthetic-key" }, OkHttpClient(), server.url("/chat/completions").toString(),
        sourceAllowed = { true }, clock = { 1 }, maxSchemaRepairs = repairs)
    private fun wire(server: MockWebServer) = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test fun everyAnalysisGroupReportsOnlyItsFixedSectionAndFriendlyLabel() {
        assertEquals(setOf("envelope", "nodes", "highlights", "paragraphs", "questions", "backgrounds"),
            ReadingValidationSection.entries.map { it.key }.toSet())
        invalidCases().forEach { (section, raw) ->
            val error = assertThrows(ApiFailure::class.java) { parse(raw) }
            assertEquals("invalid_schema", error.code); assertEquals(section, error.validationSection)
            assertEquals("研读结果未通过校验（${section.label}），原文可继续阅读，请重试", error.message)
            assertFalse(error.message.contains(marker)); assertNull(error.cause)
            assertFalse(error.toString().contains(marker))
        }
    }

    @Test fun envelopeSummaryAndCefrFailuresCannotBeMislabeledAsTheLastParsedSection() {
        listOf(
            "[]",
            JsonObject(original - "summary").toString(),
            JsonObject(original + ("summary" to JsonPrimitive("x".repeat(1801)))).toString(),
            JsonObject(original + ("cefr_estimate" to JsonPrimitive("D1"))).toString(),
        ).forEach { raw ->
            assertEquals(ReadingValidationSection.ENVELOPE, assertThrows(ApiFailure::class.java) { parse(raw) }.validationSection)
        }
    }

    @Test fun categorizationKeepsAllOriginalLengthAndReferenceLimits() {
        val tooLong = listOf(
            ReadingValidationSection.NODES to first("nodes") { JsonObject(it + ("label" to JsonPrimitive("x".repeat(121)))) },
            ReadingValidationSection.HIGHLIGHTS to first("highlights") { JsonObject(it + ("quote" to JsonPrimitive("x".repeat(601)))) },
            ReadingValidationSection.PARAGRAPHS to first("paragraphs") { JsonObject(it + ("summary" to JsonPrimitive("x".repeat(201)))) },
            ReadingValidationSection.QUESTIONS to first("questions") { JsonObject(it + ("question" to JsonPrimitive("x".repeat(701)))) },
            ReadingValidationSection.BACKGROUNDS to first("backgrounds") { JsonObject(it + ("meaning" to JsonPrimitive("x".repeat(1201)))) },
        )
        tooLong.forEach { (section, raw) ->
            assertEquals(section, assertThrows(ApiFailure::class.java) { parse(raw) }.validationSection)
        }
        val missingIds = first("questions") { JsonObject(it - "sentence_ids") }
        assertEquals(ReadingValidationSection.QUESTIONS, assertThrows(ApiFailure::class.java) { parse(missingIds) }.validationSection)
        val accepted = parse(ReadingAiFixtures.bundle)
        assertEquals(2, accepted.schemaVersion)
        assertTrue(accepted.questions.single().evidence.isNotEmpty())
    }

    @Test fun oneRepairIncludesOnlyTheFailedGroupsStaticConstraintsAndUnchangedInput() = runBlocking {
        invalidCases().forEach { (section, raw) ->
            MockWebServer().use { server ->
                server.enqueue(ReadingAiFixtures.response(raw))
                server.enqueue(ReadingAiFixtures.response(ReadingAiFixtures.bundle))
                server.start()
                val result = service(server).analyze(ReadingAiFixtures.article, ReadingAiFixtures.body)
                assertEquals(2, result.schemaVersion); assertEquals(2, server.requestCount)
                val first = wire(server); val second = wire(server)
                val firstMessages = first.getValue("messages").jsonArray
                val secondMessages = second.getValue("messages").jsonArray
                assertEquals(2, secondMessages.size)
                assertEquals(firstMessages[1], secondMessages[1])
                val initialSystem = firstMessages[0].jsonObject.getValue("content").jsonPrimitive.content
                val repairSystem = secondMessages[0].jsonObject.getValue("content").jsonPrimitive.content
                assertEquals(initialSystem + ReadingAiProtocol.repairInstruction(section), repairSystem)
                assertTrue(repairSystem.contains("in section: ${section.key}"))
                assertFalse(second.toString().contains(marker))
                assertEquals(first.getValue("max_tokens"), second.getValue("max_tokens"))
            }
        }
    }

    @Test fun secondFailureReturnsItsOwnSectionWithoutStartingAThirdRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ReadingAiFixtures.response(invalidCases().first { it.first == ReadingValidationSection.HIGHLIGHTS }.second))
            server.enqueue(ReadingAiFixtures.response(invalidCases().first { it.first == ReadingValidationSection.QUESTIONS }.second))
            server.start()
            val error = try { service(server).analyze(ReadingAiFixtures.article, ReadingAiFixtures.body); throw AssertionError("Expected failure") }
                catch (failure: ApiFailure) { failure }
            assertEquals("invalid_schema", error.code); assertEquals(ReadingValidationSection.QUESTIONS, error.validationSection)
            assertEquals(2, server.requestCount); assertFalse(error.message.contains(marker))
            wire(server)
            val feedback = wire(server).getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonPrimitive.content
            assertTrue(feedback.contains("in section: highlights"))
        }
    }

    @Test fun diagnosticOnlyModeDoesNotAutomaticallyRepair() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ReadingAiFixtures.response(invalidCases().last().second)); server.start()
            val error = try { service(server, repairs = 0).analyze(ReadingAiFixtures.article, ReadingAiFixtures.body); throw AssertionError("Expected failure") }
                catch (failure: ApiFailure) { failure }
            assertEquals(ReadingValidationSection.BACKGROUNDS, error.validationSection)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun authenticationAndIncompleteResponsesRetainTransportCodesWithoutSchemaRepair() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody(marker))
            server.enqueue(ReadingAiFixtures.response(marker, finish = "length"))
            server.start()
            val service = service(server)
            listOf("authentication", "incomplete").forEachIndexed { index, expected ->
                val failure = try { service.analyze(ReadingAiFixtures.article, ReadingAiFixtures.body); throw AssertionError("Expected failure") }
                    catch (failure: ApiFailure) { failure }
                assertEquals(expected, failure.code); assertNull(failure.validationSection)
                assertFalse(failure.message.contains(marker)); assertEquals(index + 1, server.requestCount)
            }
        }
    }
}
