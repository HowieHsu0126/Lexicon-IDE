package dev.lexicon.data

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class StrictCardSchemaTest {
    private val valid = """{"headword":"test","context_definition":"a check of a claim","collocations":[{"phrase":"test a claim","example":"Researchers test a claim."},{"phrase":"run a test","example":"We run a test."}],"paraphrase":"a check","register_tag":"neutral","exam_relevance":"medium"}"""
    @Test fun rejectsNumbersAndBooleansInsteadOfText() {
        for (bad in listOf(valid.replace("\"headword\":\"test\"", "\"headword\":123"), valid.replace("\"example\":\"We run a test.\"", "\"example\":true"))) {
            try { DeepSeekProtocol.parseContent(bad); fail("Non-string field accepted") }
            catch (e: ApiFailure) { assertEquals("invalid_schema", e.code) }
        }
    }
}
