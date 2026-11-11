package dev.lexicon.data

import dev.lexicon.domain.*
import org.junit.Assert.*
import org.junit.Test

class DeepSeekProtocolTest {
    private val valid = """{"headword":"mitigate","context_definition":"reduce harm here",
        "collocations":[{"phrase":"mitigate risk","example":"Planning can mitigate risk."},
        {"phrase":"mitigate harm","example":"Trees can mitigate harm from heat."}],
        "paraphrase":"reduce the adverse effects","register_tag":"formal","exam_relevance":"medium"}"""

    @Test fun validResponseBecomesTypedContent() { assertEquals("mitigate", DeepSeekProtocol.parseContent(valid).headword) }
    @Test fun invalidEnumsAreRejected() {
        assertThrows(ApiFailure::class.java) { DeepSeekProtocol.parseContent(valid.replace("formal", "definitely-in-exam")) }
    }
    @Test fun missingFieldsAndEmptyJsonAreRejected() {
        assertThrows(ApiFailure::class.java) { DeepSeekProtocol.parseContent("{}") }
        assertThrows(ApiFailure::class.java) { DeepSeekProtocol.parseContent("") }
    }
    @Test fun classificationContainsOnlyTitlesAndSourceLabels() {
        val payload = DeepSeekProtocol.classificationPayload(listOf(TopicInput("id", "A title", "Publisher"))).toString()
        assertTrue(payload.contains("A title"))
        listOf("summary", "body", "sentence", "capture", "api_key").forEach { assertFalse(payload.contains(it)) }
    }
    @Test fun classifierRejectsUnknownIdsAndCrossTopicChildren() {
        val input = listOf(TopicInput("1", "title", "source"))
        assertThrows(ApiFailure::class.java) { DeepSeekProtocol.parseTopics("""{"articles":[{"id":"other","topic":null,"subtopic":null}]}""", input) }
        assertThrows(ApiFailure::class.java) { DeepSeekProtocol.parseTopics("""{"articles":[{"id":"1","topic":"Health","subtopic":"AI"}]}""", input) }
        assertNull(DeepSeekProtocol.parseTopics("""{"articles":[{"id":"1","topic":null,"subtopic":null}]}""", input).single().topic)
    }
    @Test fun modelCannotSupplySourceSentenceThroughAnalysisContent() {
        val content = DeepSeekProtocol.parseContent(valid.dropLast(1) + ",\"source_sentence\":\"invented quote\",\"source_ref\":\"invented publisher\"}")
        assertEquals("mitigate", content.headword)
        assertFalse(content.toString().contains("invented"))
    }
    @Test fun generatedContentUsesTheSameBoundsAsTheEditor() {
        val tooLong = valid.replace("reduce harm here", "x".repeat(1_201))
        assertThrows(ApiFailure::class.java) { DeepSeekProtocol.parseContent(tooLong) }
    }
    @Test fun classifierRequiresTypedStringsAndExplicitNullableFields() {
        val input = listOf(TopicInput("1", "title", "source"))
        assertThrows(ApiFailure::class.java) { DeepSeekProtocol.parseTopics("""{"articles":[{"id":1,"topic":null,"subtopic":null}]}""", input) }
        assertThrows(ApiFailure::class.java) { DeepSeekProtocol.parseTopics("""{"articles":[{"id":"1"}]}""", input) }
    }
}
