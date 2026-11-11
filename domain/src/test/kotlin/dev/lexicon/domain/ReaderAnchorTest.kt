package dev.lexicon.domain

import org.junit.Assert.*
import org.junit.Test

class ReaderAnchorTest {
    private val text = (1..10).joinToString("\n\n") { "Paragraph $it repeats a phrase. Evidence needs context." }
    private val body = ArticleBody("article", text, "exact-body-hash", 1, Long.MAX_VALUE, "Synthetic test")

    @Test fun `sixth and eighth repeated passages resolve from exact offsets`() {
        for (index in listOf(5, 7)) {
            val range = ParagraphFocus.paragraphs(text)[index]
            val phrase = text.indexOf("repeats", range.start)
            assertEquals(index, ReaderAnchor(body.contentHash, phrase, phrase + 7).paragraphIndex(body))
        }
    }

    @Test fun `changed hash invalid range and gap never guess another passage`() {
        val sixth = ParagraphFocus.paragraphs(text)[5]
        assertNull(ReaderAnchor("old-body-hash", sixth.start, sixth.endExclusive).paragraphIndex(body))
        assertNull(ReaderAnchor("", sixth.start, sixth.endExclusive).paragraphIndex(body))
        assertNull(ReaderAnchor(body.contentHash, -1, 3).paragraphIndex(body))
        assertNull(ReaderAnchor(body.contentHash, 3, 3).paragraphIndex(body))
        assertNull(ReaderAnchor(body.contentHash, 0, text.length + 1).paragraphIndex(body))
        assertNull(ReaderAnchor(body.contentHash, sixth.endExclusive, sixth.endExclusive + 2).paragraphIndex(body))
    }

    @Test fun `utf16 offsets cannot bisect supplementary characters`() {
        val unicode = body.copy(text = "A 🧬 example.", contentHash = "unicode")
        assertEquals(0, ReaderAnchor("unicode", 2, 4).paragraphIndex(unicode))
        assertNull(ReaderAnchor("unicode", 3, 4).paragraphIndex(unicode))
        assertNull(ReaderAnchor("unicode", 2, 3).paragraphIndex(unicode))
    }

    @Test fun `only original notes with complete provenance create anchors`() {
        val original = ReadingNote("note", "article", body.contentHash, "Evidence needs context.", "", emptyList(), 1,
            start = 33, end = 56)
        assertEquals(ReaderAnchor(body.contentHash, 33, 56), original.originalAnchor())
        assertNull(original.copy(origin = ContextOrigin.AI_GENERATED).originalAnchor())
        assertNull(original.copy(origin = ContextOrigin.USER_SUPPLIED).originalAnchor())
        assertNull(original.copy(bodyHash = null).originalAnchor())
        assertNull(original.copy(start = null).originalAnchor())
        assertNull(original.copy(articleId = null).originalAnchor())
    }
}
