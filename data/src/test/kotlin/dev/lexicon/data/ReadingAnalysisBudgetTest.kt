package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Synthetic articles exercise output budgeting without weakening the accepted analysis schema. */
class ReadingAnalysisBudgetTest {
    private data class Fixture(val article: Article, val body: ArticleBody, val analysis: JsonObject)

    private fun fixture(paragraphCount: Int, expanded: Boolean = false): Fixture {
        val article = ReadingAiFixtures.article.copy(id = "budget-article-$paragraphCount")
        val text = (0 until paragraphCount).joinToString("\n\n") { index ->
            "Paragraph ${index + 1} records a measured observation. The researchers compare results with earlier findings."
        }
        val body = ReadingAiFixtures.body.copy(articleId = article.id, text = text, contentHash = sha256(text))
        val sentences = ReadingSkimPlanner.sentences(text)
        val analysis = buildJsonObject {
            put("summary", "合成例文记录观察，并将结果与既有发现比较。")
            put("cefr_estimate", "B2")
            putJsonArray("nodes") {
                repeat(if (expanded) 18 else 2) { index -> add(buildJsonObject {
                    put("id", "n$index"); put("parent_id", if (index == 0) null else "n0")
                    put("label", if (index == 0) "观察与比较" else "观察项目 $index")
                    put("paragraph", if (index == 0) JsonNull else JsonPrimitive((index - 1) % paragraphCount))
                }) }
            }
            putJsonArray("highlights") {
                repeat(if (expanded) 18 else 1) { index -> add(buildJsonObject {
                    put("id", "h$index"); put("quote", "measured observation"); put("paragraph", index)
                    put("occurrence", 1); put("kind", "collocation"); put("explanation", "经测量得到的观察结果")
                    put("exam", "both")
                }) }
            }
            putJsonArray("paragraphs") {
                repeat(paragraphCount) { index -> add(buildJsonObject {
                    put("index", index); put("role", if (index == 0) "introduction" else "evidence")
                    put("summary", "记录观察并与既有发现比较")
                }) }
            }
            putJsonArray("questions") { add(buildJsonObject {
                put("id", "q1"); put("question", "研究人员将结果与什么进行比较？")
                put("answer", "与既有发现比较。")
                putJsonArray("paragraphs") { add(paragraphCount - 1) }
                putJsonArray("sentence_ids") { add(sentences.last().id) }
            }) }
            putJsonArray("backgrounds") {
                repeat(if (expanded) 6 else 0) { index -> add(buildJsonObject {
                    put("term", "researchers"); put("meaning", "在例文中指开展研究的人员。")
                    put("why_here", "说明执行比较的主体。"); put("paragraph", index)
                    put("uncertainty", "例文没有交代人员的身份或机构。")
                }) }
            }
        }
        return Fixture(article, body, analysis)
    }

    private fun service(server: MockWebServer) = DeepSeekReadingAi(
        { "synthetic-key" }, OkHttpClient(), server.url("/chat/completions").toString(),
        sourceAllowed = { true }, clock = { 1L })

    private fun wire(server: MockWebServer) = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
    private fun payload(wire: JsonObject) = Json.parseToJsonElement(
        wire.getValue("messages").jsonArray[1].jsonObject.getValue("content").jsonPrimitive.content).jsonObject
    private fun parse(fixture: Fixture, analysis: JsonObject = fixture.analysis) = ReadingAiProtocol.parseAnalysis(
        analysis.toString(), fixture.article, fixture.body, "test-model", 1L)
    private fun replaceFirst(analysis: JsonObject, field: String, change: (JsonObject) -> JsonObject) =
        JsonObject(analysis + (field to JsonArray(analysis.getValue(field).jsonArray.mapIndexed { index, row ->
            if (index == 0) change(row.jsonObject) else row
        })))

    @Test fun budgetsCoverEveryBoundaryAndRejectUnsupportedParagraphCounts() {
        mapOf(1 to 4096, 12 to 4096, 13 to 6144, 32 to 6144, 33 to 8192, 40 to 8192, 80 to 8192)
            .forEach { (count, expected) -> assertEquals(expected, ReadingAiProtocol.analysisTokenBudget(count)) }
        listOf(-1, 0, 81, Int.MAX_VALUE).forEach { count ->
            assertThrows(IllegalArgumentException::class.java) { ReadingAiProtocol.analysisTokenBudget(count) }
        }
    }

    @Test fun clientConstraintsDescribeTheCompleteSnapshotWithoutSendingTheFeedSummary() {
        listOf(1, 40, 80).forEach { count ->
            val fixture = fixture(count)
            val input = ReadingAiProtocol.analysisPayload(fixture.article, fixture.body)
            val constraints = input.getValue("client_constraints").jsonObject
            assertEquals(setOf("paragraph_count", "expected_paragraph_indices", "max_diagram_nodes"), constraints.keys)
            assertEquals(count, constraints.getValue("paragraph_count").jsonPrimitive.int)
            assertEquals((0 until count).toList(), constraints.getValue("expected_paragraph_indices").jsonArray.map { it.jsonPrimitive.int })
            assertEquals(18, constraints.getValue("max_diagram_nodes").jsonPrimitive.int)
            val paragraphs = input.getValue("paragraphs").jsonArray
            assertEquals(count, paragraphs.size)
            assertEquals((0 until count).toList(), paragraphs.map { it.jsonObject.getValue("index").jsonPrimitive.int })
            assertEquals(fixture.body.text, paragraphs.joinToString("\n\n") { it.jsonObject.getValue("text").jsonPrimitive.content })
            assertFalse(input.toString().contains(fixture.article.summary))
        }
    }

    @Test fun transportUsesTheBudgetForSmallMiddleFortyAndEightyParagraphArticles() = runBlocking {
        listOf(2 to 4096, 20 to 6144, 40 to 8192, 80 to 8192).forEach { (count, expectedBudget) ->
            val fixture = fixture(count)
            MockWebServer().use { server ->
                server.enqueue(ReadingAiFixtures.response(fixture.analysis.toString())); server.start()
                val result = service(server).analyze(fixture.article, fixture.body)
                assertEquals(count, result.paragraphs.size)
                assertEquals((0 until count).toList(), result.paragraphs.map { it.index })
                assertEquals(1, server.requestCount)
                val request = wire(server)
                assertEquals(expectedBudget, request.getValue("max_tokens").jsonPrimitive.int)
                assertEquals(count, payload(request).getValue("client_constraints").jsonObject.getValue("paragraph_count").jsonPrimitive.int)
            }
        }
    }

    @Test fun oneRepairRetainsTheLargeArticleBudgetAndTheIdenticalCompleteInput() = runBlocking {
        listOf(40, 80).forEach { count ->
            val fixture = fixture(count)
            val incomplete = JsonObject(fixture.analysis +
                ("paragraphs" to JsonArray(fixture.analysis.getValue("paragraphs").jsonArray.dropLast(1))))
            MockWebServer().use { server ->
                server.enqueue(ReadingAiFixtures.response(incomplete.toString()))
                server.enqueue(ReadingAiFixtures.response(fixture.analysis.toString())); server.start()
                assertEquals(count, service(server).analyze(fixture.article, fixture.body).paragraphs.size)
                assertEquals(2, server.requestCount)
                val first = wire(server); val second = wire(server)
                assertEquals(8192, first.getValue("max_tokens").jsonPrimitive.int)
                assertEquals(first.getValue("max_tokens"), second.getValue("max_tokens"))
                assertEquals(first.getValue("messages").jsonArray[1], second.getValue("messages").jsonArray[1])
                assertEquals(ReadingAiProtocol.analysisPayload(fixture.article, fixture.body), payload(second))
                assertTrue(second.getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonPrimitive.content
                    .contains("in section: paragraphs"))
            }
        }
    }

    @Test fun lowerOutputRecommendationsKeepTheExistingValidatorMaxima() {
        val fixture = fixture(18, expanded = true)
        val accepted = parse(fixture)
        assertEquals(18, accepted.nodes.size); assertEquals(18, accepted.highlights.size)
        assertEquals(6, accepted.backgrounds.size)
        listOf("nodes" to ReadingValidationSection.NODES, "highlights" to ReadingValidationSection.HIGHLIGHTS,
            "backgrounds" to ReadingValidationSection.BACKGROUNDS).forEach { (field, section) ->
            val rows = fixture.analysis.getValue(field).jsonArray
            val oversized = JsonObject(fixture.analysis + (field to JsonArray(rows + rows.last())))
            val failure = assertThrows(ApiFailure::class.java) { parse(fixture, oversized) }
            assertEquals("invalid_schema", failure.code); assertEquals(section, failure.validationSection)
        }
    }

    @Test fun longArticleStillRequiresExactCoverageRolesAndQuestionReferences() {
        val fixture = fixture(40)
        val paragraphs = fixture.analysis.getValue("paragraphs").jsonArray
        val invalid = listOf(
            ReadingValidationSection.PARAGRAPHS to JsonObject(fixture.analysis + ("paragraphs" to JsonArray(paragraphs.dropLast(1)))),
            ReadingValidationSection.PARAGRAPHS to replaceFirst(fixture.analysis, "paragraphs") { JsonObject(it + ("index" to JsonPrimitive(1))) },
            ReadingValidationSection.PARAGRAPHS to replaceFirst(fixture.analysis, "paragraphs") { JsonObject(it + ("role" to JsonPrimitive("invented-role"))) },
            ReadingValidationSection.QUESTIONS to replaceFirst(fixture.analysis, "questions") {
                JsonObject(it + ("sentence_ids" to JsonArray(listOf(JsonPrimitive("not-an-input-sentence")))))
            },
            ReadingValidationSection.QUESTIONS to replaceFirst(fixture.analysis, "questions") {
                JsonObject(it + ("paragraphs" to JsonArray(listOf(JsonPrimitive(0)))))
            },
        )
        invalid.forEach { (section, analysis) ->
            val failure = assertThrows(ApiFailure::class.java) { parse(fixture, analysis) }
            assertEquals("invalid_schema", failure.code); assertEquals(section, failure.validationSection)
        }
    }

    @Test fun compactAnalysisStillRequiresEverySchemaField() {
        val fixture = fixture(40)
        fixture.analysis.keys.forEach { field ->
            assertThrows(ApiFailure::class.java) { parse(fixture, JsonObject(fixture.analysis - field)) }
        }
    }
}
