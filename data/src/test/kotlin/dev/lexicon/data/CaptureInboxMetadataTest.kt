package dev.lexicon.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/** List metadata is independent of the captured learning material. No Android or network work. */
class CaptureInboxMetadataTest {
    private fun row(id: String, data: String = CaptureInboxMetadata.encode(id, 500)) =
        PreferenceRow(CaptureInboxMetadata.key(id), data)

    @Test fun validMetadataBindsItsExactIdToItsOwnKey() {
        for (id in listOf("capture-1", "capture:2/part", "原文-β-🙂")) {
            assertEquals(id, CaptureInboxMetadata.archivedId(row(id)))
            assertNotEquals(CaptureInboxMetadata.key(id), CaptureInboxMetadata.key("other:$id"))
        }
        val id = "capture-1"
        assertEquals(id, CaptureInboxMetadata.archivedId(row(id, CaptureInboxMetadata.encode(id, 0))))
        assertEquals(id, CaptureInboxMetadata.archivedId(row(id, CaptureInboxMetadata.encode(id, Long.MAX_VALUE))))
    }

    @Test fun anotherKeyOrAnotherCaptureCannotHideTheRequestedRecord() {
        val value = CaptureInboxMetadata.encode("first", 500)
        assertNull(CaptureInboxMetadata.archivedId(PreferenceRow(CaptureInboxMetadata.key("second"), value)))
        assertNull(CaptureInboxMetadata.archivedId(PreferenceRow("unrelated", value)))
        assertNull(CaptureInboxMetadata.archivedId(PreferenceRow(CaptureInboxMetadata.PREFIX + "first", value)))
    }

    @Test fun unknownIncompleteOrWronglyTypedMetadataStaysVisible() {
        val invalid = listOf(
            "", "null", "[]", "{", "{}",
            """{"version":2,"captureId":"first","archivedAt":500}""",
            """{"version":"1","captureId":"first","archivedAt":500}""",
            """{"version":1.0,"captureId":"first","archivedAt":500}""",
            """{"version":1,"captureId":"first"}""",
            """{"version":1,"captureId":false,"archivedAt":500}""",
            """{"version":1,"captureId":"first","archivedAt":"500"}""",
            """{"version":1,"captureId":"first","archivedAt":-1}""",
            """{"version":1,"captureId":"first","archivedAt":1.5}""",
            """{"version":1,"captureId":"first","archivedAt":null}""",
            """{"version":1,"captureId":"first","archivedAt":9223372036854775808}""",
            """{"version":1,"captureId":"first","archivedAt":500,"archived":false}""",
        )
        invalid.forEach { value -> assertNull("Invalid metadata must not hide a capture", CaptureInboxMetadata.archivedId(row("first", value))) }
    }

    @Test fun invalidOrOversizedIdentityAndPayloadStaysVisible() {
        for (id in listOf("", "   ", "first\nsecond", "first\u0000second", "x".repeat(257))) {
            assertFalse(CaptureInboxMetadata.validId(id))
            assertTrue(runCatching { CaptureInboxMetadata.encode(id, 500) }.isFailure)
        }
        assertTrue(CaptureInboxMetadata.validId("x".repeat(256)))
        assertNull(CaptureInboxMetadata.archivedId(row("first", " ".repeat(2_049))))
        assertTrue(runCatching { CaptureInboxMetadata.encode("first", -1) }.isFailure)
    }

    @Test fun whitespaceAndFieldOrderDoNotChangeValidStructuredMetadata() {
        val value = """ { "archivedAt" : 500, "captureId" : "first", "version" : 1 } """
        assertEquals("first", CaptureInboxMetadata.archivedId(row("first", value)))
        assertEquals("first", CaptureInboxMetadata.archivedId(row("first", value.replace("first", "fi\\u0072st"))))
    }

    @Test fun serializedRecordContainsOnlyIdentityVersionAndDecisionTime() {
        val encoded = CaptureInboxMetadata.encode("first", 500)
        assertEquals(setOf("version", "captureId", "archivedAt"), Json.parseToJsonElement(encoded).jsonObject.keys)
        assertFalse(encoded.contains("sentence"))
        assertFalse(encoded.contains("analysis"))
        assertFalse(encoded.contains("status"))
    }
}
