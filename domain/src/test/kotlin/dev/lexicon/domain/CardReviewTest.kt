package dev.lexicon.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class CardReviewTest {
    private val content = VocabularyContent("robust", "Remaining effective under the stated conditions.",
        listOf(Collocation("robust evidence", "The report supplies robust evidence."),
            Collocation("robust method", "A robust method tolerates variation.")), "reliable", "formal", "medium")
    private fun card() = Card("card", "capture", content, "The robust method tolerates variation.", SourceRef(), createdAt = 50)

    @Test fun `legacy card without pause field remains active`() {
        val codec = Json { encodeDefaults = true }
        val encoded = codec.parseToJsonElement(codec.encodeToString(card())).jsonObject
        val legacy = codec.decodeFromString<Card>(JsonObject(encoded - "reviewPaused").toString())
        assertFalse(legacy.reviewPaused)
        assertTrue(legacy.isDueForReview(100))
    }

    @Test fun `pause excludes every FSRS phase including new cards`() {
        CardPhase.entries.forEach { phase ->
            val paused = card().copy(state = FsrsState(phase = phase, dueAt = 0), reviewPaused = true)
            assertFalse(phase.name, paused.isDueForReview(Long.MAX_VALUE))
        }
    }

    @Test fun `resume retains the original due boundary and the independent state`() {
        val original = card().copy(state = FsrsState(CardPhase.REVIEW, 3.0, 4.0, 2000, 1000, null, 5, 1),
            updatedAt = 70, ankiExportedAt = 90)
        val paused = original.copy(reviewPaused = true)
        val resumed = paused.copy(reviewPaused = false)
        assertEquals(original, resumed)
        assertFalse(resumed.isDueForReview(1999))
        assertTrue(resumed.isDueForReview(2000))
        assertTrue(resumed.isDueForReview(30_000))
        assertEquals(original.state, paused.state)
    }

    @Test fun `new cards remain due irrespective of stored due time when active`() {
        assertTrue(card().copy(state = FsrsState(dueAt = Long.MAX_VALUE)).isDueForReview(0))
        assertFalse(card().copy(state = FsrsState(dueAt = Long.MAX_VALUE), reviewPaused = true).isDueForReview(0))
    }
}
