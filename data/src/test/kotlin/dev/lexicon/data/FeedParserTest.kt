package dev.lexicon.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class FeedParserTest {
    @Test fun atomKeepsSummarySeparateFromSanitizedFullText() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><entry>
            <id>unique-1</id><title>Research &amp; evidence</title>
            <link rel="alternate" href="https://example.org/article?utm_source=rss&amp;lang=en"/>
            <published>2026-10-28T09:10:00Z</published><author><name>A. Author</name></author>
            <rights>Creative Commons attribution no derivatives</rights>
            <category term="Science + Tech"/>
            <summary type="html">&lt;p&gt;A brief summary.&lt;/p&gt;</summary>
            <content type="html"><![CDATA[<p>First paragraph.</p><script>steal()</script>
            <p>Second <em>paragraph</em>.</p><img src="https://tracker.invalid/pixel"/>]]></content>
            </entry></feed>"""
        val entry = FeedParser.parse(xml, "https://example.org/feed").single()
        assertEquals("A brief summary.", entry.summary)
        assertEquals("First paragraph.\n\nSecond paragraph.", entry.body)
        assertEquals("https://example.org/article?lang=en", entry.url)
        assertEquals("A. Author", entry.author)
        assertNotNull(entry.publishedAt)
        assertEquals("Science & Technology", FeedParser.topic(entry.categories, null))
    }

    @Test fun rssAcceptsCdataAndMissingDateWithoutInventingMetadata() {
        val xml = """<rss xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><item>
            <title>A report</title><link>https://example.org/a</link><guid>rss-id</guid>
            <description><![CDATA[<b>Short</b> &amp; concise.]]></description>
            <content:encoded><![CDATA[<p>The complete report.</p>]]></content:encoded>
            </item></channel></rss>"""
        val entry = FeedParser.parse(xml, "https://example.org/feed").single()
        assertEquals("Short & concise.", entry.summary)
        assertEquals("The complete report.", entry.body)
        assertNull(entry.publishedAt)
        assertEquals("", entry.author)
    }

    @Test fun bodyOnlyFeedNeverCopiesEntireBodyToSummary() {
        val xml = """<feed><entry><title>A</title><link href="https://example.org/a"/><content><![CDATA[<p>Full content</p>]]></content></entry></feed>"""
        assertEquals("", FeedParser.parse(xml, "https://example.org/feed").single().summary)
    }

    @Test fun canonicalizationPreservesMeaningfulParametersAndPathCase() {
        assertEquals("https://example.org/Article?id=12", FeedParser.canonicalUrl("https://EXAMPLE.org:443/Article?utm_medium=x&id=12#part"))
        assertNotEquals(FeedParser.canonicalUrl("https://example.org/a?id=1"), FeedParser.canonicalUrl("https://example.org/a?id=2"))
    }

    @Test fun invalidLinksAreNotImported() {
        val xml = """<rss><channel><item><title>Bad</title><link>javascript:alert(1)</link></item></channel></rss>"""
        assertTrue(FeedParser.parse(xml, "https://example.org/feed").isEmpty())
    }

    @Test fun rdfKeepsDublinCoreDateSubjectAndAuthor() {
        val xml = """<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
            xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/">
            <item rdf:about="https://example.org/paper"><title>A research paper</title>
            <link>https://example.org/paper</link><description>A short abstract.</description>
            <dc:date>2026-10-28</dc:date><dc:creator>A. Researcher</dc:creator>
            <dc:subject>Biotechnology</dc:subject><dc:subject>Climate</dc:subject>
            <category>Climate</category></item></rdf:RDF>"""
        val entry = FeedParser.parse(xml, "https://example.org/feed.rdf").single()
        assertEquals(Instant.parse("2026-10-28T00:00:00Z").toEpochMilli(), entry.publishedAt)
        assertEquals(listOf("Biotechnology", "Climate"), entry.categories)
        assertEquals("A. Researcher", entry.author)
        assertEquals("A short abstract.", entry.summary)
        assertEquals("Science & Technology", FeedParser.topic(entry.categories, null))
        assertNull(entry.body)
    }

    @Test fun malformedPrimaryDateCanFallBackToValidDublinCoreTimestamp() {
        val xml = """<rss xmlns:dc="http://purl.org/dc/elements/1.1/"><channel><item>
            <title>Date fallback</title><link>https://example.org/date</link>
            <pubDate>not a date</pubDate><dc:date>2026-10-28T16:30:00+02:00</dc:date>
            </item></channel></rss>"""
        val entry = FeedParser.parse(xml, "https://example.org/feed").single()
        assertEquals(Instant.parse("2026-10-28T14:30:00Z").toEpochMilli(), entry.publishedAt)
    }
}
