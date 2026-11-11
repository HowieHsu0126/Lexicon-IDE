package dev.lexicon.ide.ui

import dev.lexicon.domain.Capture
import dev.lexicon.domain.CaptureLimits
import dev.lexicon.domain.ContextOrigin
import dev.lexicon.domain.SourceRef
import org.junit.Assert.*
import org.junit.Test

class CaptureDraftStateTest {
    @Test fun nativeDraftRetainsIdentityAndOriginalUtf16Provenance() {
        val original = Capture("lookup-1", "article-1", "reliable", "reliable evidence 🧭",
            sentence = "We need reliable evidence 🧭 to compare these claims.", contextOrigin = ContextOrigin.ORIGINAL,
            sourceRef = SourceRef("Original title", "Original publisher", "https://example.invalid/original", 17),
            selectionStart = 120_042, selectionEnd = 120_062, bodyHash = "original-snapshot", createdAt = 100, updatedAt = 101,
            aiAllowed = false)
        assertEquals(original, CaptureDraftCodec.decode(CaptureDraftCodec.encode(original)))
    }

    @Test fun externalAndBlankDraftsDoNotNeedADatabaseRowToRestore() {
        listOf(
            Capture("external", target = "account for", selectedText = "account for", createdAt = 5),
            Capture("blank", target = "", selectedText = "", createdAt = 6),
        ).forEach { assertEquals(it, CaptureDraftCodec.decode(CaptureDraftCodec.encode(it))) }
        assertNull(CaptureDraftCodec.decode(""))
    }

    @Test fun malformedOrOversizedDraftDoesNotRestoreAnUnusableModal() {
        assertNull(CaptureDraftCodec.decode("{broken"))
        assertNull(CaptureDraftCodec.decode("{}"))
        val sample = Capture("draft", target = "word", selectedText = "word", createdAt = 0)
        assertNull(CaptureDraftCodec.decode(CaptureDraftCodec.encode(sample.copy(id = ""))))
        assertNull(CaptureDraftCodec.decode(CaptureDraftCodec.encode(sample.copy(selectedText = "a".repeat(CaptureLimits.SELECTED_TEXT + 1)))))
        assertNull(CaptureDraftCodec.decode(" ".repeat(262_145)))
    }

    @Test fun unknownFieldsDoNotReplaceTheSavedSourceWithCurrentMetadata() {
        val sample = Capture("draft", target = "word", selectedText = "word", createdAt = 1,
            sourceRef = SourceRef("Saved title", "Saved publisher", "https://example.invalid/saved"))
        val encoded = CaptureDraftCodec.encode(sample).dropLast(1) + ",\"futureField\":\"ignored\"}"
        assertEquals(sample, CaptureDraftCodec.decode(encoded))
    }
}
