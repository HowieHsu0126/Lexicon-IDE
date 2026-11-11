package dev.lexicon.domain

import org.junit.Assert.*
import org.junit.Test

class DomainHelpersTest {
    @Test fun `repeated word selects the real occurrence and second sentence`() {
        val body = "The first bank closed. The river bank is steep."
        val start = body.lastIndexOf("bank")
        val capture = TextCapture.extract(body, start, start + 4)
        assertEquals("bank", capture.selectedText)
        assertEquals("The river bank is steep.", capture.sentence)
        assertEquals(start, capture.start)
        assertEquals(capture.sentence, body.substring(capture.sentenceStart, capture.sentenceEnd))
    }

    @Test fun `reverse and cross paragraph selections preserve exact source`() {
        val body = "First paragraph here.\n\nSecond paragraph follows."
        val start = body.indexOf("here")
        val end = body.indexOf("follows") + "follows".length
        val capture = TextCapture.extract(body, end, start)
        assertEquals(body.substring(start, end), capture.selectedText)
        assertEquals(body, capture.sentence)
    }

    @Test fun `selection handles English honorific`() {
        val body = "Dr. Jones studies language. Other scientists agree."
        val start = body.indexOf("studies")
        assertEquals("Dr. Jones studies language.", TextCapture.extract(body, start, start + 7).sentence)
    }

    @Test fun `selection at a paragraph separator remains inside the sentence snapshot`() {
        val body = "\n\nA first paragraph.\n\nA second paragraph."
        for (start in listOf(0, 1, body.indexOf("\n\n", 2) + 1)) {
            val capture = TextCapture.extract(body, start, start + 5)
            assertTrue(capture.sentenceStart <= capture.start)
            assertTrue(capture.sentenceEnd >= capture.end)
        }
    }

    @Test(expected = IllegalArgumentException::class) fun `invalid selection does not silently select another word`() {
        TextCapture.extract("A valid sentence.", -1, 5)
    }

    @Test(expected = IllegalArgumentException::class) fun `selection never divides a surrogate pair`() {
        TextCapture.extract("A 🧬 molecule.", 3, 4)
    }

    @Test fun `url normalization preserves meaningful identity`() {
        assertEquals("https://example.com/a%20b?edition=uk&id=2",
            UrlCanonicalizer.canonicalize("HTTPS://EXAMPLE.COM:443/a%20b?utm_source=feed&edition=uk&id=2#top"))
        assertNotEquals(UrlCanonicalizer.canonicalize("https://x.test/?id=1"), UrlCanonicalizer.canonicalize("https://x.test/?id=2"))
        assertNotEquals(UrlCanonicalizer.canonicalize("https://x.test/article"), UrlCanonicalizer.canonicalize("https://x.test/article/"))
    }

    @Test(expected = IllegalArgumentException::class) fun `credentials in url are rejected`() {
        UrlCanonicalizer.canonicalize("https://user:secret@example.com/article")
    }

    @Test fun `publisher topic maps while unknown labels remain unknown`() {
        assertEquals("Science & Technology", TopicMapper.fromCategories(listOf("Science + Tech"))?.topic)
        assertNull(TopicMapper.fromCategories(listOf("Daily roundup")))
        assertFalse(TopicMapper.isValid(TopicResult("1", "Education", "Biotech")))
        assertTrue(TopicMapper.isValid(TopicResult("1", null, null)))
    }

    private fun validContent() = VocabularyContent("robust", "Strong enough to withstand the stated challenge.",
        listOf(Collocation("robust evidence", "The study provides robust evidence."), Collocation("robust analysis", "A robust analysis accounts for uncertainty.")),
        "resilient", "formal", "high")

    @Test fun `schema fields are validated beyond parseability`() {
        assertTrue(ContentValidator.validate(validContent()).isEmpty())
        val invalid = validContent().copy(registerTag = "academic", contextDefinition = "", collocations = emptyList())
        assertEquals(3, ContentValidator.validate(invalid).size)
        val repeated = validContent().copy(collocations = listOf(validContent().collocations[0], validContent().collocations[0]))
        assertTrue(ContentValidator.validate(repeated).any { "重复" in it })
    }

    @Test fun `contextless capture stays pending instead of claiming a sentence`() {
        val capture = Capture("capture", target = "robust", selectedText = "robust", createdAt = 0)
        assertTrue(ContentValidator.validateCapture(capture).isNotEmpty())
        val supplied = capture.copy(sentence = "The estimate remains robust.", contextOrigin = ContextOrigin.USER_SUPPLIED)
        assertTrue(ContentValidator.validateCapture(supplied).isEmpty())
        assertFalse(ContentValidator.validateCapture(supplied.copy(aiAllowed = false)).isEmpty())
    }

    @Test fun `short summary does not produce a difficulty grade`() {
        assertNull(Readability.estimate("A brief article summary.").fleschKincaidGrade)
        val longText = (1..10).joinToString(" ") { "This careful analysis considers several possible explanations for the observed result." }
        val estimate = Readability.estimate(longText)
        assertNotNull(estimate.fleschKincaidGrade)
        assertTrue(estimate.words > 50)
        assertEquals(1, estimate.minutes)
    }
}
