package dev.lexicon.ide.ui

import org.junit.Assert.*
import org.junit.Test

class ArticlePageIdentityTest {
    @Test fun trackingDoesNotChangeSourceButArticleIdDoes() {
        assertTrue(sameArticlePage("https://example.org/read?id=1&utm_source=feed#p2", "https://example.org/read?id=1"))
        assertFalse(sameArticlePage("https://example.org/read?id=2", "https://example.org/read?id=1"))
        assertFalse(sameArticlePage("https://other.org/read?id=1", "https://example.org/read?id=1"))
        assertFalse(sameArticlePage("javascript:alert(1)", "https://example.org/read?id=1"))
    }
}
