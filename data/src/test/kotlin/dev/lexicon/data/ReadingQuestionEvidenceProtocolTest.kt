package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ReadingQuestionEvidenceProtocolTest {
    private val article = Article("question-source", "fixture", listOf("fixture"), "Synthetic argument",
        "https://example.org/questions", "Do not send the summary.", sourceName = "Self-authored fixture")
    private val text = "An example repeats. An example repeats. 😀 A final statement.\n\n制度不同。观点也不同！"
    private val body = ArticleBody(article.id, text, sha256(text), 0, 1, "Self-authored fixture")
    private fun units(body: ArticleBody = this.body) = ReadingSkimPlanner.sentences(body.text)
    private fun question(id: String, selected: List<ReadingSentenceUnit>) = buildJsonObject {
        put("id", id); put("question", "Which claim is stated?"); put("answer", "The selected original statement.")
        putJsonArray("paragraphs") { selected.map { it.paragraphIndex }.distinct().forEach { add(it) } }
        putJsonArray("sentence_ids") { selected.forEach { add(it.id) } }
    }
    private fun raw(body: ArticleBody = this.body, questions: List<JsonObject> = listOf(question("q1", listOf(units(body).first())))) = buildJsonObject {
        put("summary", "Synthetic overview"); put("cefr_estimate", "B2")
        putJsonArray("nodes") { add(buildJsonObject { put("id", "root"); put("parent_id", JsonNull); put("label", "Overview"); put("paragraph", JsonNull) }) }
        putJsonArray("highlights") { }
        putJsonArray("paragraphs") { ParagraphFocus.paragraphs(body.text).indices.forEach { index -> add(buildJsonObject {
            put("index", index); put("role", "example"); put("summary", "Synthetic passage")
        }) } }
        put("questions", JsonArray(questions)); putJsonArray("backgrounds") { }
    }
    private fun parse(value: JsonObject, body: ArticleBody = this.body) =
        ReadingAiProtocol.parseAnalysis(value.toString(), article, body, "synthetic-model", 1)

    @Test fun requestCarriesStableSentenceIdsAndVerbatimTextWithoutModelOffsets() {
        val first = ReadingAiProtocol.analysisPayload(article, body)
        val second = ReadingAiProtocol.analysisPayload(article, body)
        assertEquals(first, second)
        assertEquals(2, first.getValue("protocol_version").jsonPrimitive.int)
        val sentences = first.getValue("sentence_units").jsonArray
        assertEquals(units().size, sentences.size)
        assertEquals(units().map { it.id }, sentences.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(units().map { body.text.substring(it.start, it.endExclusive) }, sentences.map { it.jsonObject.getValue("quote").jsonPrimitive.content })
        assertTrue(sentences.all { it.jsonObject.keys == setOf("id", "paragraph", "quote") })
        assertFalse(first.toString().contains(article.summary))
    }

    @Test fun repeatedSentencesResolveTheChosenOccurrenceAndSharedEvidenceStaysShared() {
        val repeated = units().filter { body.text.substring(it.start, it.endExclusive) == "An example repeats." }
        assertEquals(2, repeated.size); assertNotEquals(repeated[0].id, repeated[1].id)
        val selected = listOf(repeated[1])
        val result = parse(raw(questions = listOf(question("q1", selected), question("q2", selected))))
        assertEquals(2, result.schemaVersion)
        assertEquals(listOf(repeated[1].evidenceRange()), result.questions[0].evidence)
        assertEquals(result.questions[0].evidence, result.questions[1].evidence)
        assertEquals(text.indexOf("An example repeats.", 1), result.questions[0].evidence.single().start)
    }

    @Test fun multilingualPunctuationAndEmojiPreserveExactUtf16Boundaries() {
        val selected = units().filter { body.text.substring(it.start, it.endExclusive).contains("😀") || it.paragraphIndex == 1 }
        assertTrue(selected.size >= 3)
        val result = parse(raw(questions = listOf(question("q1", selected.reversed()))))
        val evidence = result.questions.single().evidence
        assertEquals(selected.sortedBy { it.start }.map { it.evidenceRange() }, evidence)
        evidence.forEach { range ->
            assertEquals(units().single { it.id == range.sentenceId }.evidenceRange(), range)
            assertTrue(range.start == 0 || !Character.isLowSurrogate(text[range.start]))
            assertTrue(range.endExclusive == text.length || !Character.isLowSurrogate(text[range.endExclusive]))
        }
    }

    @Test fun missingEmptyDuplicateForeignAndMismatchedSentenceReferencesFailClosed() {
        val unit = units().first()
        val valid = question("q1", listOf(unit))
        val variants = listOf(
            JsonObject(valid - "sentence_ids"),
            JsonObject(valid + ("sentence_ids" to JsonArray(emptyList()))),
            JsonObject(valid + ("sentence_ids" to JsonArray(listOf(JsonPrimitive(unit.id), JsonPrimitive(unit.id))))),
            JsonObject(valid + ("sentence_ids" to JsonArray(listOf(JsonPrimitive("unknown-sentence"))))),
            JsonObject(valid + ("sentence_ids" to JsonArray(listOf(JsonPrimitive(0))))),
            JsonObject(valid + ("paragraphs" to JsonArray(listOf(JsonPrimitive(1))))),
            JsonObject(valid + ("paragraphs" to JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(1))))),
            JsonObject(valid + ("evidence_start" to JsonPrimitive(unit.start))),
            JsonObject(valid + ("evidence_quote" to JsonPrimitive("approximately the same statement"))),
        )
        variants.forEach { value ->
            val failure = assertThrows(ApiFailure::class.java) { parse(raw(questions = listOf(value))) }
            assertEquals("invalid_schema", failure.code)
        }
    }

    @Test fun evidenceCannotBorrowAnIdFromAnotherSnapshotEvenAtTheSamePosition() {
        val changed = body.copy(text = text.replace("An example", "Another example"), contentHash = sha256(text.replace("An example", "Another example")))
        val oldQuestion = question("q1", listOf(units().first()))
        assertThrows(ApiFailure::class.java) { parse(raw(changed, listOf(oldQuestion)), changed) }
        assertThrows(ApiFailure::class.java) { parse(raw(), body.copy(contentHash = "stale")) }
    }

    @Test fun responseCannotSelectTooManySentencesOrReuseQuestionIds() {
        val longText = (1..25).joinToString(" ") { "Sentence $it states an example." }
        val longer = body.copy(text = longText, contentHash = sha256(longText))
        assertEquals(25, units(longer).size)
        assertThrows(ApiFailure::class.java) { parse(raw(longer, listOf(question("q1", units(longer)))), longer) }
        val same = question("same", listOf(units().first()))
        assertThrows(ApiFailure::class.java) { parse(raw(questions = listOf(same, same))) }
    }

    @Test fun missingSentenceReferencesPermitOnlyOneSchemaRepairWithTheSameMaterial() = runBlocking {
        MockWebServer().use { server ->
            val valid = raw()
            val missing = JsonObject(valid + ("questions" to JsonArray(listOf(JsonObject(question("q1", listOf(units().first())) - "sentence_ids")))))
            server.enqueue(ReadingAiFixtures.response(missing.toString()))
            server.enqueue(ReadingAiFixtures.response(valid.toString()))
            server.start()
            val service = DeepSeekReadingAi({ "synthetic-key" }, OkHttpClient(), server.url("/chat/completions").toString(),
                sourceAllowed = { true }, clock = { 1 })
            val study = service.analyze(article, body)
            assertEquals(2, study.schemaVersion); assertTrue(study.questions.single().evidence.isNotEmpty())
            assertEquals(2, server.requestCount)
            val requests = (1..2).map { Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject }
            assertEquals(requests[0].getValue("messages").jsonArray[1], requests[1].getValue("messages").jsonArray[1])
            val prompt = requests[0].getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonPrimitive.content
            assertTrue(prompt.contains("sentence_ids")); assertTrue(prompt.contains("Do not invent IDs"))
        }
    }
}
