package dev.lexicon.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixtures for the independently reviewed article-boundary and visible-content regressions. */
class PublicArticleParserRegressionTest {
    private fun paragraphs(label: String) = (1..4).joinToString("") { number ->
        "<p>$label $number. Researchers compare measurements across multiple conditions, " +
            "report the limits of the evidence, and explain why each observed difference matters " +
            "before drawing a conclusion from the available results.</p>"
    }

    @Test fun specificArticleWinsOverLongerMainContainingOtherStories() {
        val article = paragraphs("Actual article")
        val standalone = PublicArticleParser.extract("<article>$article</article>", "https://example.org/story")
        val withOtherStories = PublicArticleParser.extract(
            "<main><article>$article</article><section>${paragraphs("Other stories")}</section></main>",
            "https://example.org/story",
        )
        assertEquals(standalone, withOtherStories)
    }

    @Test fun stylesheetHiddenArticleIsNotPromotedIntoNativeReader() {
        assertNull(PublicArticleParser.extract(
            "<style>.locked { display: none; }</style><article class='locked'>${paragraphs("Hidden content")}</article>",
            "https://example.org/story",
        ))
    }
}
