package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

internal object SpeedAiFixture {
    val text = "This paragraph is not selected and must not be sent.\n\nThe report takes local needs into account. It describes a plan for public libraries.\n\nA city council evaluates the proposal. Members compare the expected costs and benefits."
    val article = Article("speed-article", "g", listOf("s"), "A local reading proposal", "https://example.org/speed", "Never send this summary", sourceName = "Synthetic")
    val body = ArticleBody(article.id, text, sha256(text), 1, 100_000, "Self-authored fixture")
    fun plan(count: Int = 2) = SpeedChunker.plan(article.id, body.contentHash, text, SpeedGranularity.L2, 1, count, 100)
    fun raw(plan: SpeedPlan = plan()) = buildJsonObject {
        putJsonArray("chunks") { plan.chunks.forEach { c -> add(buildJsonObject {
            put("start", c.start); put("end", c.end); put("paragraph", c.paragraph); put("sentence", c.sentence)
            put("quote", text.substring(c.start, c.end)); put("reason", "sense_group")
        }) } }
        putJsonArray("questions") { plan.paragraphIndices.forEach { index ->
            val p = ParagraphFocus.paragraphs(text)[index]
            add(buildJsonObject { put("paragraph", index); put("prompt", "What does this paragraph describe?")
                putJsonArray("options") { add("The proposal and its consideration"); add("A completed election"); add("A sporting event") }
                put("answer_index", 0); put("evidence_start", p.start); put("evidence_end", p.endExclusive)
                put("evidence_quote", text.substring(p.start, p.endExclusive)); put("explanation", "题干依据该段所述提议和讨论。")
            })
        } }
    }
    fun response(raw: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(buildJsonObject {
        put("model", "fixture-model")
        putJsonArray("choices") { add(buildJsonObject { put("finish_reason", "stop"); putJsonObject("message") { put("content", raw) } }) }
    }.toString())
    fun references(article: Article = this.article, body: ArticleBody = this.body, plan: SpeedPlan = plan()): JsonObject {
        val payload = SpeedReadingProtocol.payload(article, body, plan)
        val units = payload.getValue("source_units").jsonArray.map { it.jsonObject }
        return buildJsonObject {
            put("chunks", payload.getValue("proposed_chunks"))
            putJsonArray("questions") { plan.paragraphIndices.forEach { paragraph ->
                val selected = units.filter { it.getValue("paragraph").jsonPrimitive.int == paragraph }
                add(buildJsonObject {
                    put("paragraph", paragraph); put("prompt", "What does this paragraph describe?")
                    putJsonArray("options") { add("A local proposal"); add("A sporting event"); add("A flight") }
                    put("answer_index", 0); put("evidence_first", selected.first().getValue("id"))
                    put("evidence_last", selected.last().getValue("id")); put("explanation", "依据原段。")
                })
            } }
        }
    }
}

class SpeedReadingAiTest {
    private fun parse(raw: JsonObject) = SpeedReadingProtocol.parse(raw.toString(), SpeedAiFixture.body, SpeedAiFixture.plan(), "model", 100)
    private fun mutateFirst(raw: JsonObject, field: String, change: (JsonObject) -> JsonObject): JsonObject = JsonObject(raw +
        (field to JsonArray(raw.getValue(field).jsonArray.mapIndexed { index, value -> if (index == 0) change(value.jsonObject) else value })))

    @Test fun requestContainsOnlySelectedParagraphsAndFrozenProposal() {
        val p = SpeedAiFixture.plan()
        val payload = SpeedReadingProtocol.payload(SpeedAiFixture.article, SpeedAiFixture.body, p)
        assertFalse(payload.toString().contains("This paragraph is not selected"))
        assertFalse(payload.toString().contains(SpeedAiFixture.article.summary))
        assertEquals(listOf(1, 2), payload.getValue("paragraphs").jsonArray.map { it.jsonObject.getValue("paragraph").jsonPrimitive.int })
        assertEquals(p.chunks.size, payload.getValue("proposed_chunks").jsonArray.size)
        assertThrows(ApiFailure::class.java) { SpeedReadingProtocol.payload(SpeedAiFixture.article, SpeedAiFixture.body.copy(contentHash = "wrong"), p) }
    }

    @Test fun validOutputRetainsFullTextOffsetsAndHasExactlyOneQuestionPerParagraph() {
        val parsed = parse(SpeedAiFixture.raw())
        assertEquals("ai", parsed.origin); assertEquals("model", parsed.model)
        assertEquals(listOf("p1", "p2"), parsed.questions.map { it.id })
        SpeedChunker.validate(parsed, SpeedAiFixture.text)
        assertEquals(SpeedAiFixture.plan().chunks.map { it.start to it.end }, parsed.chunks.map { it.start to it.end })
    }

    @Test fun unitReferencesBindDeterministicallyToRepeatedTextAndUtf16SourceOffsets() {
        val text = "This paragraph stays local.\n\nThe report compares two reports. The report asks whether the new library can remain open. Readers use the library 📚 on Tuesday."
        val body = SpeedAiFixture.body.copy(text = text, contentHash = sha256(text))
        val plan = SpeedChunker.plan(body.articleId, body.contentHash, text, SpeedGranularity.L3, 1, 1)
        val raw = SpeedAiFixture.references(body = body, plan = plan)
        val first = SpeedReadingProtocol.parse(raw.toString(), body, plan, "model", 1)
        val second = SpeedReadingProtocol.parse(raw.toString(), body, plan, "model", 2)
        assertEquals(plan.chunks.map { it.start to it.end }, first.chunks.map { it.start to it.end })
        assertEquals(first.chunks, second.chunks)
        val paragraph = ParagraphFocus.paragraphs(text)[1]
        assertEquals(text.substring(paragraph.start, paragraph.endExclusive), first.chunks.joinToString("") { text.substring(it.start, it.end) })
        assertEquals(paragraph.start, first.questions.single().evidenceStart)
        assertEquals(paragraph.endExclusive, first.questions.single().evidenceEnd)
        assertFalse(raw.toString().contains("evidence_start"))
        assertEquals(1, first.version)
        assertEquals(4, SpeedReadingProtocol.VERSION)
    }

    @Test fun allLegalBoundariesCanSplitCoarseProposalsWithoutInventingAnySourceText() {
        val plan = SpeedChunker.plan(SpeedAiFixture.article.id, SpeedAiFixture.body.contentHash,
            SpeedAiFixture.text, SpeedGranularity.L3, 1, 2)
        val payload = SpeedReadingProtocol.payload(SpeedAiFixture.article, SpeedAiFixture.body, plan)
        val raw = SpeedAiFixture.references(plan = plan)
        val units = payload.getValue("source_units").jsonArray
        val smallest = JsonArray(units.map { unit -> buildJsonObject {
            put("first", unit.jsonObject.getValue("id")); put("last", unit.jsonObject.getValue("id")); put("reason", "sense_group")
        } })
        val parsed = SpeedReadingProtocol.parse(JsonObject(raw + ("chunks" to smallest)).toString(), SpeedAiFixture.body, plan, "model", 1)
        assertEquals(units.size, parsed.chunks.size)
        assertTrue(parsed.chunks.size >= plan.chunks.size)
        SpeedChunker.validate(parsed, SpeedAiFixture.text)
    }

    @Test fun referenceChunksRejectUnknownMissingDuplicateReversedAndCrossSentenceOrParagraphUnits() {
        val raw = SpeedAiFixture.references()
        val chunks = raw.getValue("chunks").jsonArray
        val units = SpeedReadingProtocol.payload(SpeedAiFixture.article, SpeedAiFixture.body, SpeedAiFixture.plan())
            .getValue("source_units").jsonArray.map { it.jsonObject }
        val nextSentence = units.first { it.getValue("sentence").jsonPrimitive.int == 1 }.getValue("id")
        val nextParagraph = units.first { it.getValue("paragraph").jsonPrimitive.int == 2 }.getValue("id")
        val bad = listOf(
            mutateFirst(raw, "chunks") { JsonObject(it + ("first" to JsonPrimitive("unknown-unit"))) },
            JsonObject(raw + ("chunks" to JsonArray(chunks.drop(1)))),
            JsonObject(raw + ("chunks" to JsonArray(listOf(chunks.first()) + chunks))),
            JsonObject(raw + ("chunks" to JsonArray(chunks.reversed()))),
            mutateFirst(raw, "chunks") { JsonObject(it + ("last" to nextSentence)) },
            mutateFirst(raw, "chunks") { JsonObject(it + ("last" to nextParagraph)) },
            mutateFirst(raw, "chunks") { JsonObject(it - "last") },
            mutateFirst(raw, "chunks") { JsonObject(it + ("start" to JsonPrimitive(-1))) },
            mutateFirst(raw, "chunks") { JsonObject(it + ("quote" to JsonPrimitive("rewritten"))) },
        )
        bad.forEach { assertEquals("invalid_schema", assertThrows(ApiFailure::class.java) { parse(it) }.code) }
    }

    @Test fun referenceEvidenceMustExistAndStayWithinTheQuestionsParagraph() {
        val raw = SpeedAiFixture.references()
        val units = SpeedReadingProtocol.payload(SpeedAiFixture.article, SpeedAiFixture.body, SpeedAiFixture.plan())
            .getValue("source_units").jsonArray.map { it.jsonObject }
        val foreign = units.first { it.getValue("paragraph").jsonPrimitive.int == 2 }.getValue("id")
        val bad = listOf(
            mutateFirst(raw, "questions") { JsonObject(it + ("evidence_last" to foreign)) },
            mutateFirst(raw, "questions") { JsonObject(it + ("evidence_first" to JsonPrimitive("u999999"))) },
            mutateFirst(raw, "questions") { JsonObject(it - "evidence_last") },
            mutateFirst(raw, "questions") { JsonObject(it + ("evidence_start" to JsonPrimitive(0))) },
            mutateFirst(raw, "questions") { JsonObject(it + ("evidence_quote" to JsonPrimitive("approximate text"))) },
        )
        bad.forEach { assertThrows(ApiFailure::class.java) { parse(it) } }
    }

    @Test fun structuralDiagnosticsContainOnlyBoundedStagesFieldsAndCoordinates() {
        val diagnostics = mutableListOf<SpeedSchemaDiagnostic>()
        val secret = "sk-fixture-private-response"
        val malformed = mutateFirst(SpeedAiFixture.references(), "chunks") { JsonObject(it + ("first" to JsonPrimitive(secret))) }
        val error = assertThrows(ApiFailure::class.java) {
            SpeedReadingProtocol.parse(malformed.toString(), SpeedAiFixture.body, SpeedAiFixture.plan(), "model", 1, diagnostics::add)
        }
        assertEquals("invalid_schema", error.code)
        assertEquals(SpeedSchemaDiagnostic("chunks", "first", 0), diagnostics.single())
        val encoded = Json.encodeToString(diagnostics)
        assertFalse(encoded.contains(secret)); assertFalse(encoded.contains(SpeedAiFixture.text))
        diagnostics.clear()
        assertThrows(ApiFailure::class.java) {
            SpeedReadingProtocol.parse(secret, SpeedAiFixture.body, SpeedAiFixture.plan(), "model", 1, diagnostics::add)
        }
        assertEquals("json", diagnostics.single().stage)
    }

    @Test fun inventedQuotesGapsAndWrongSentenceIdsAreRejected() {
        val raw = SpeedAiFixture.raw()
        val bad = listOf(
            mutateFirst(raw, "chunks") { JsonObject(it + ("quote" to JsonPrimitive("Invented quote"))) },
            mutateFirst(raw, "chunks") { JsonObject(it + ("end" to JsonPrimitive(-1))) },
            mutateFirst(raw, "chunks") { JsonObject(it + ("sentence" to JsonPrimitive(900))) },
            JsonObject(raw + ("chunks" to JsonArray(raw.getValue("chunks").jsonArray.drop(1)))),
        )
        bad.forEach { assertEquals("invalid_schema", assertThrows(ApiFailure::class.java) { parse(it) }.code) }
    }

    @Test fun questionsRejectMissingCoverageInvalidOptionsAndUnsupportedEvidence() {
        val raw = SpeedAiFixture.raw()
        val bad = listOf(
            JsonObject(raw + ("questions" to JsonArray(raw.getValue("questions").jsonArray.drop(1)))),
            mutateFirst(raw, "questions") { JsonObject(it + ("options" to JsonArray(List(3) { JsonPrimitive("same") }))) },
            mutateFirst(raw, "questions") { JsonObject(it + ("answer_index" to JsonPrimitive(3))) },
            mutateFirst(raw, "questions") { JsonObject(it + ("answer_index" to JsonPrimitive("0"))) },
            mutateFirst(raw, "questions") { JsonObject(it + ("evidence_quote" to JsonPrimitive("Not in the paragraph"))) },
            mutateFirst(raw, "questions") { JsonObject(it + ("paragraph" to JsonPrimitive(0))) },
        )
        bad.forEach { assertThrows(ApiFailure::class.java) { parse(it) } }
    }

    @Test fun malformedJsonDoesNotProduceAnEmptySuccessfulPlan() {
        for (raw in listOf("{}", "null", "[]", "not json")) {
            assertThrows(ApiFailure::class.java) { SpeedReadingProtocol.parse(raw, SpeedAiFixture.body, SpeedAiFixture.plan(), "model", 1) }
        }
    }

    @Test fun oneStructureRepairKeepsFrozenInputAndBoundedOutput() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(SpeedAiFixture.response("{}")); server.enqueue(SpeedAiFixture.response(SpeedAiFixture.raw().toString())); server.start()
            val ai = DeepSeekSpeedReadingAi({ "fixture-key" }, OkHttpClient(), server.url("/chat/completions").toString())
            assertEquals("ai", ai.refine(SpeedAiFixture.article, SpeedAiFixture.body, SpeedAiFixture.plan()).origin)
            assertEquals(2, server.requestCount)
            val a = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val b = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals(a.getValue("messages").jsonArray[1], b.getValue("messages").jsonArray[1])
            assertEquals("deepseek-flash", a.getValue("model").jsonPrimitive.content)
            assertEquals(6000, a.getValue("max_tokens").jsonPrimitive.int)
        }
    }

    @Test fun productionRequestCarriesGeneralSemanticExamplesWithoutAddingThemToSourceMaterial() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(SpeedAiFixture.response(SpeedAiFixture.references().toString())); server.start()
            val ai = DeepSeekSpeedReadingAi({ "fixture-key" }, OkHttpClient(), server.url("/chat/completions").toString())
            ai.refine(SpeedAiFixture.article, SpeedAiFixture.body, SpeedAiFixture.plan())
            val request = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val messages = request.getValue("messages").jsonArray
            val system = messages[0].jsonObject.getValue("content").jsonPrimitive.content
            val input = messages[1].jsonObject.getValue("content").jsonPrimitive.content
            listOf("The newly appointed regional director", "an urban rail project", "Several dozen applicants",
                "within twelve working days", "subject plus finite predicate is clause").forEach { guidance ->
                assertTrue(system.contains(guidance)); assertFalse(input.contains(guidance))
            }
            assertTrue(system.contains("A legal unit boundary is only a candidate"))
            assertTrue(system.contains("without gaps or duplicates"))
            assertEquals(4, Json.parseToJsonElement(input).jsonObject.getValue("protocol_version").jsonPrimitive.int)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun authenticationAndRateLimitNeverTriggerStructureRepair() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("fixture-key must not leak"))
            server.enqueue(MockResponse().setResponseCode(429)); server.start()
            val ai = DeepSeekSpeedReadingAi({ "fixture-key" }, OkHttpClient(), server.url("/chat/completions").toString())
            for (code in listOf("authentication", "rate_limit")) {
                try { ai.refine(SpeedAiFixture.article, SpeedAiFixture.body, SpeedAiFixture.plan()); fail("Expected $code") }
                catch (error: ApiFailure) { assertEquals(code, error.code); assertFalse(error.message.contains("fixture-key")) }
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun exhaustedStructureRepairFailsWithoutFallbackOrThirdCall() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(SpeedAiFixture.response("{}")) }; server.start()
            val ai = DeepSeekSpeedReadingAi({ "fixture-key" }, OkHttpClient(), server.url("/chat/completions").toString())
            try { ai.refine(SpeedAiFixture.article, SpeedAiFixture.body, SpeedAiFixture.plan()); fail("Expected schema failure") }
            catch (error: ApiFailure) { assertEquals("invalid_schema", error.code) }
            assertEquals(2, server.requestCount)
        }
    }
}
