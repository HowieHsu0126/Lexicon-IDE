package dev.lexicon.data

import dev.lexicon.domain.*
import org.junit.Assert.*
import org.junit.Test

class AnkiFieldsTest {
    private fun card() = Card("id-1", "capture-1", VocabularyContent("<word>", "Meaning & sense",
        listOf(Collocation("a phrase", "An example."), Collocation("another phrase", "Another example.")),
        "A paraphrase", "formal", "high"), "Original <sentence>.", SourceRef("Title", "Publisher", "https://example.org/a"),
        topic = "Science & Technology", createdAt = 1793145600000)

    @Test fun fourFieldsEscapeHtmlAndLabelExamples() {
        val fields = LegacyAnkiExporter.fields(card())
        assertEquals(4, fields.size)
        assertEquals("&lt;word&gt;", fields[0])
        assertTrue(fields[1].contains("AI 例示"))
        assertTrue(fields[2].contains("Original &lt;sentence&gt;."))
        assertTrue(fields[3].contains("topic::Science_&_Technology"))
    }
    @Test fun idStableAcrossEditsAndIdsDifferByRecordKind() {
        assertEquals(LegacyAnkiExporter.stableId("note:id-1"), LegacyAnkiExporter.stableId("note:id-1"))
        assertNotEquals(LegacyAnkiExporter.stableId("note:id-1"), LegacyAnkiExporter.stableId("card:id-1"))
    }
}
