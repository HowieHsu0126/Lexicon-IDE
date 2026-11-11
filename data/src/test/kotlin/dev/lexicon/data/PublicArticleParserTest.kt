package dev.lexicon.data

import org.junit.Assert.*
import org.junit.Test

class PublicArticleParserTest {
    private val prose = (1..4).joinToString("") { "<p>Paragraph $it explains how a careful research design compares observations across conditions and accounts for uncertain measurements without discarding contrary evidence.</p>" }
    @Test fun publicArticleKeepsParagraphsAndDropsNavigationAndHiddenContent() {
        val result = PublicArticleParser.extract("<main><nav>Menu</nav><article>$prose<p hidden>secret</p><p style='display:none'>hidden</p><aside>Related</aside><script>unsafe()</script></article></main>", "https://example.org/story")!!
        assertEquals(4, result.split("\n\n").size)
        assertFalse(result.contains("secret")); assertFalse(result.contains("hidden")); assertFalse(result.contains("unsafe"))
    }
    @Test fun subscriptionSchemaCannotBePromotedToPublicReader() {
        assertNull(PublicArticleParser.extract("<script type='application/ld+json'>{\"isAccessibleForFree\":false}</script><article>$prose</article>", "https://example.org/paid"))
        assertNull(PublicArticleParser.extract("<article>$prose<p>Subscribe to continue reading</p></article>", "https://example.org/paid"))
    }
    @Test fun teaserOrNonArticleCannotBeCalledFullText() {
        assertNull(PublicArticleParser.extract("<article><p>A short summary.</p></article>", "https://example.org/teaser"))
        assertNull(PublicArticleParser.extract("<div>$prose</div>", "https://example.org/index"))
    }
    @Test fun stylesheetHiddenTextIsNotRecoveredAndMainCannotSwallowNeighborStories() {
        assertNull(PublicArticleParser.extract("<style>.locked{display:none}</style><article class='locked'>$prose</article>", "https://example.org/locked"))
        val parsed = PublicArticleParser.extract("<main><article>$prose</article><section><p>Another story</p>$prose</section></main>", "https://example.org/public")!!
        assertFalse(parsed.contains("Another story")); assertEquals(4, parsed.split("\n\n").size)
    }
}
