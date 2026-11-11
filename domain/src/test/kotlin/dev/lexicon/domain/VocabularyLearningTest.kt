package dev.lexicon.domain

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class VocabularyLearningTest {
    private val text = "Dr. Smith takes into account the evidence.\n\nThe evidence remains limited. A clear argument follows."
    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private val article = Article("a", "g", listOf("s"), "Source title", "https://example.org/a", "Summary", sourceName = "Source")
    private val body = ArticleBody("a", text, hash(text), 1, 10, "license")
    private fun highlight(quote: String, from: Int = 0, kind: String = "vocabulary", id: String = quote): StudyHighlight {
        val start = text.indexOf(quote, from)
        return StudyHighlight(id, quote, start, start + quote.length, kind, "语境提示")
    }
    private fun study(highlights: List<StudyHighlight>) = ArticleStudy("a", body.contentHash, "summary", "B2", emptyList(),
        highlights, emptyList(), emptyList(), emptyList(), "fixture", 7)
    private fun candidates(vararg h: StudyHighlight) = VocabularyLearning.candidates(article, body, study(h.toList()), true)

    @Test fun capturesExactSecondOccurrenceAndCompleteOriginalSentence() {
        val h = highlight("evidence", text.indexOf("\n\n"))
        val c = candidates(h).single()
        assertEquals("The evidence remains limited.", c.capture.sentence)
        assertEquals(text.lastIndexOf("evidence"), c.capture.selectionStart)
        assertEquals(ContextOrigin.ORIGINAL, c.capture.contextOrigin)
        assertEquals(article.title, c.capture.sourceRef.title)
        VocabularyLearning.requireValid(c)
    }
    @Test fun honorificAndPhraseArePreservedWithOriginalOffsets() {
        val c = candidates(highlight("takes into account", kind = "collocation")).single()
        assertEquals("Dr. Smith takes into account the evidence.", c.capture.sentence)
        assertEquals("takes into account", c.capture.target)
        assertEquals(c.capture.selectedText, text.substring(c.capture.selectionStart!!, c.capture.selectionEnd!!))
    }
    @Test fun candidateIdentityIgnoresModelHighlightIdAndRegenerationTime() {
        val h = highlight("evidence")
        val first = candidates(h).single()
        val changed = VocabularyLearning.candidates(article, body, study(listOf(h.copy(id = "new-ai-id"))).copy(createdAt = 100), true).single()
        assertEquals(first.id, changed.id)
        assertEquals(first.capture.id, changed.capture.id)
        assertEquals(VocabularyLearning.batchId(listOf(first)), VocabularyLearning.batchId(listOf(changed)))
    }
    @Test fun nativeForwardReverseSelectionAndAiHighlightUseOneOccurrenceIdentity() {
        val c = candidates(highlight("evidence")).single()
        assertEquals(c.capture.id, VocabularyLearning.captureId(article.id, body.contentHash,
            c.capture.selectionEnd!!, c.capture.selectionStart!!, c.capture.target))
        assertNotEquals(c.capture.id, VocabularyLearning.captureId(article.id, body.contentHash,
            c.capture.selectionStart!!, c.capture.selectionEnd!!, "different target"))
    }
    @Test fun differentOccurrenceOrBodyNeverSharesIdentity() {
        val a = candidates(highlight("evidence")).single()
        val b = candidates(highlight("evidence", text.indexOf("\n\n"))).single()
        assertNotEquals(a.id, b.id)
        val nextText = "$text More text."
        val nextBody = body.copy(text = nextText, contentHash = hash(nextText))
        val nextStudy = study(listOf(highlight("evidence"))).copy(bodyHash = nextBody.contentHash)
        val next = VocabularyLearning.candidates(article, nextBody, nextStudy, true).single()
        assertNotEquals(a.id, next.id)
    }
    @Test fun onlyWordsAndCollocationsBecomeBatchCandidates() {
        assertEquals(2, candidates(highlight("evidence"), highlight("takes into account", kind = "collocation"),
            highlight("The evidence remains limited.", kind = "sentence"), highlight("A clear argument follows.", kind = "argument")).size)
    }
    @Test fun invalidRangesCannotCreateGuessedCaptures() {
        val h = highlight("evidence")
        assertTrue(candidates(h.copy(start = -1)).isEmpty())
        assertTrue(candidates(h.copy(quote = "invented")).isEmpty())
        assertTrue(candidates(h.copy(end = text.length + 1)).isEmpty())
    }
    @Test fun staleOrTamperedSnapshotsAreRejected() {
        val s = study(listOf(highlight("evidence")))
        assertThrows(IllegalArgumentException::class.java) { VocabularyLearning.candidates(article, body.copy(text = text + "x"), s, true) }
        assertThrows(IllegalArgumentException::class.java) { VocabularyLearning.candidates(article, body, s.copy(bodyHash = "stale"), true) }
        assertThrows(IllegalArgumentException::class.java) { VocabularyLearning.candidates(article.copy(id = "other"), body, s, true) }
    }
    @Test fun selectionIsExplicitBoundedUniqueAndSourceAllowed() {
        val s = study(listOf(highlight("evidence")));val id = candidates(highlight("evidence")).single().id
        assertEquals(1, VocabularyLearning.selected(article, body, s, listOf(id), true).size)
        for (ids in listOf(emptyList(), listOf("absent"), listOf(id,id))) {
            assertThrows(IllegalArgumentException::class.java) { VocabularyLearning.selected(article, body, s, ids, true) }
        }
        assertThrows(IllegalArgumentException::class.java) { VocabularyLearning.selected(article, body, s, listOf(id), false) }
        assertFalse(VocabularyLearning.candidates(article, body, s, false).single().capture.aiAllowed)
    }
    @Test fun familiarityIsExplicitConservativeAndReversible() {
        assertEquals("it's relevant", VocabularyLearning.termKey("  IT’S   relevant  "))
        assertNotEquals(VocabularyLearning.termKey("policy"), VocabularyLearning.termKey("policies"))
        val known = listOf(FamiliarTerm("evidence", "evidence", 1))
        assertTrue(VocabularyLearning.isFamiliar("EVIDENCE", known))
        assertFalse(VocabularyLearning.isFamiliar("new evidence", known))
        assertFalse(VocabularyLearning.isFamiliar("evidence", emptyList()))
    }
    @Test fun sameCaptureAllowsExistingManualIdentityButRejectsEditedOrExternalContext() {
        val c = candidates(highlight("evidence")).single().capture
        assertTrue(VocabularyLearning.sameCapture(c, c.copy(id = "manual-id", createdAt = 1)))
        assertFalse(VocabularyLearning.sameCapture(c, c.copy(sentence = "An edited sentence.")))
        assertFalse(VocabularyLearning.sameCapture(c, c.copy(contextOrigin = ContextOrigin.USER_SUPPLIED)))
        assertFalse(VocabularyLearning.sameCapture(c, c.copy(bodyHash = null)))
        assertFalse(VocabularyLearning.sameCapture(c, c.copy(sourceRef = SourceRef())))
    }
    @Test fun frozenCandidateRejectsReboundTextAndBrokenReferences() {
        val c = candidates(highlight("evidence")).single()
        assertThrows(IllegalArgumentException::class.java) { VocabularyLearning.requireValid(c.copy(id = "different")) }
        assertThrows(IllegalArgumentException::class.java) { VocabularyLearning.requireValid(c.copy(termKey = "other")) }
        assertThrows(IllegalArgumentException::class.java) { VocabularyLearning.requireValid(c.copy(capture = c.capture.copy(sentence = "Missing original"))) }
    }
    @Test fun duplicateModelRangesCoalesceAndBatchSelectionOrderDoesNotChangeIdentity() {
        val a = highlight("evidence");val b = highlight("limited")
        val values = candidates(a, a.copy(id = "duplicate"), b)
        assertEquals(2, values.size)
        assertEquals(VocabularyLearning.batchId(values), VocabularyLearning.batchId(values.reversed()))
    }
}
