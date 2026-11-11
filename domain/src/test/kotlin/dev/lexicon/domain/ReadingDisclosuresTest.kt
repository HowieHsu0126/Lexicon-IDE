package dev.lexicon.domain

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ReadingDisclosuresTest {
    private val singular = "The author does not work for, consult, own shares in or receive funding from any company or organisation that would benefit from this article, and has disclosed no relevant affiliations beyond their academic appointment."
    private val plural = singular.replace("The author does", "The authors do").replace("and has disclosed", "and have disclosed")
    private val affiliation = "Alice Morgan is affiliated with the Brookings Institution."
    private val funding = "Alice Morgan receives funding from the National Science Foundation."
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun article(author: String = "Alice Morgan", url: String = "https://theconversation.com/an-article-123") =
        Article("article", "guid", listOf("source"), "A source article", url, "", author = author, readingMode = ReadingMode.NATIVE)
    private fun body(vararg paragraphs: String, author: String = "Alice Morgan") =
        paragraphs.joinToString("\n\n").let { ArticleBody("article", it, hash(it), 1, 100, "Original source", author) }
    private fun passage(vararg tail: String, author: String = "Alice Morgan") =
        body("An observation 🌍 begins the article.", "The evidence supports a limited conclusion.", *tail, author = author)
    private fun detect(vararg tail: String) = ReadingDisclosures.detect(article(), passage(*tail))

    @Test fun completeGenericTemplatesMatchBothAgreementsAndSpellingVariants() {
        listOf(singular, plural, singular.replace("organisation", "organization"), plural.replace("organisation", "organization")).forEach {
            val matches = detect(it)
            assertEquals(1, matches.size)
            assertEquals(ReadingDisclosureType.CONFLICT_OF_INTEREST, matches.single().type)
        }
    }

    @Test fun genericTemplateDoesNotRequireAnInventedAuthor() {
        assertEquals(1, ReadingDisclosures.detect(article(author = ""), passage(singular, author = "")).size)
    }

    @Test fun authorMetadataQualificationsAreRemovedButNamesRemainExact() {
        val item = passage(affiliation, author = "Alice Morgan, Professor of Politics, Example University")
        assertEquals(ReadingDisclosureType.AFFILIATION, ReadingDisclosures.detect(article(), item).single().type)
        assertTrue(ReadingDisclosures.detect(article(author = "Ann"), passage("Anna Morgan is affiliated with the Brookings Institution.", author = "Ann")).isEmpty())
        assertTrue(detect("Alice is affiliated with the Brookings Institution.").isEmpty())
        assertTrue(detect("Alice Morgan-Smith is affiliated with the Brookings Institution.").isEmpty())
    }

    @Test fun articleAuthorIsUsedOnlyWhenTheBodyHasNoConflictingAuthor() {
        assertEquals(1, ReadingDisclosures.detect(article(), passage(funding, author = "")).size)
        assertEquals(1, ReadingDisclosures.detect(article(author = ""), passage(funding)).size)
        assertTrue(ReadingDisclosures.detect(article(author = "Mary Jones"), passage(funding)).isEmpty())
        assertTrue(ReadingDisclosures.detect(article(), passage(funding, author = "Not a valid; byline")).isEmpty())
    }

    @Test fun multipleAuthorMetadataOnlySupportsItsFirstCompleteName() {
        val metadata = "Alice Morgan and Bob Chen, Example University"
        assertEquals(1, ReadingDisclosures.detect(article(author = metadata), passage(funding, author = metadata)).size)
        assertTrue(ReadingDisclosures.detect(article(author = metadata), passage("Bob Chen receives funding from the National Science Foundation.", author = metadata)).isEmpty())
    }

    @Test fun unicodeNamesPossessiveOrganisationsAndBalancedAcronymsAreRetained() {
        val text = "María O’Neill is affiliated with America’s Research Institute (ARI), the Brookings Institution, and the Institute for Humane Studies."
        val item = passage(text, author = "María O’Neill, Professor")
        assertEquals(ReadingDisclosureType.AFFILIATION, ReadingDisclosures.detect(article(author = "María O’Neill"), item).single().type)
    }

    @Test fun namedFundingAndPronounContinuationsMustUseCompleteTemplates() {
        listOf("He receives", "She receives", "They receive").forEach { subject ->
            assertEquals(1, detect("$funding $subject funding from the Research Council.").size)
        }
        assertTrue(detect("$funding The researchers receive funding from the Research Council.").isEmpty())
        assertTrue(detect("She receives funding from the Research Council.").isEmpty())
    }

    @Test fun fundingResearchQualifierAndMixedAffiliationRemainADeclaration() {
        val text = "Alice Morgan receives funding from the Fetzer Institute and the Lilly Endowment for her scholarly research. She is affiliated with the Institute for Humane Studies and the Aspen Institute."
        assertEquals(ReadingDisclosureType.FUNDING, detect(text).single().type)
        listOf("his", "their").forEach { pronoun ->
            assertEquals(ReadingDisclosureType.FUNDING, detect(text.replace("her scholarly", "$pronoun scholarly")).single().type)
        }
    }

    @Test fun completeEntityGrammarRejectsMixedArgumentAndUnknownLowercaseClauses() {
        listOf(
            "Alice Morgan receives funding from the National Science Foundation, proving Better Governance.",
            "Alice Morgan receives funding from the National Science Foundation because the policy works.",
            "Alice Morgan receives funding from the National Science Foundation and argues for reform.",
            "$funding She is affiliated with the Aspen Institute but the evidence is uncertain.",
            "$funding Therefore, the research should continue.",
            "Alice Morgan receives funding from the government.",
            "Alice Morgan receives funding from the Lilly Endowment for unrelated research.",
        ).forEach { assertTrue(it, detect(it).isEmpty()) }
    }

    @Test fun quotationsNegationsAndReportedDeclarationsRemainOrdinaryText() {
        listOf(
            "\"$singular\"", "‘$affiliation’", "The report states: $funding", "The author writes that $funding",
            "Alice Morgan does not receive funding from the National Science Foundation.",
            "Alice Morgan is not affiliated with the Brookings Institution.",
            "Alice Morgan receives funding from no organisation.",
            "Alice Morgan is affiliated with 'the Brookings Institution'.",
            "Alice Morgan is affiliated with “the Brookings Institution”.",
        ).forEach { assertTrue(it, detect(it).isEmpty()) }
    }

    @Test fun partialGenericTemplatesAndTrailingAnalysisAreNeverHidden() {
        listOf(singular.removeSuffix("."), singular.substringBefore(", and has disclosed") + ".",
            singular.replace("The author does", "The author do"), "$singular This arrangement limits the claims.",
            singular.replace("beyond their academic appointment", "beyond their disputed research findings"))
            .forEach { assertTrue(it, detect(it).isEmpty()) }
    }

    @Test fun onlyAContinuousTailIsDetectedAndAnUnknownFinalParagraphStopsDetection() {
        assertTrue(detect(funding, "A substantive final conclusion follows.").isEmpty())
        val result = detect(singular, "This paragraph discusses why institutions fund research.", affiliation, funding)
        assertEquals(listOf(4, 5), result.map { it.paragraphIndex })
        assertEquals(listOf(ReadingDisclosureType.AFFILIATION, ReadingDisclosureType.FUNDING), result.map { it.type })
    }

    @Test fun detectionIsBoundedToFourTailParagraphsAndKeepsTheFirstTwoOrdinary() {
        assertEquals(listOf(4, 5, 6, 7), detect(*Array(6) { singular }).map { it.paragraphIndex })
        assertEquals(listOf(2), ReadingDisclosures.detect(article(), body(singular, plural, affiliation)).map { it.paragraphIndex })
        assertTrue(ReadingDisclosures.detect(article(), body("A short article.", affiliation)).isEmpty())
    }

    @Test fun returnedOffsetsUseTheUnchangedOriginalIncludingUnicodeAndWhitespace() {
        val item = passage("  Alice Morgan  receives funding from the National Science Foundation.  ", plural)
        val original = item.copy()
        val results = ReadingDisclosures.detect(article(), item)
        val spans = ParagraphFocus.paragraphs(item.text)
        assertEquals(listOf(2, 3), results.map { it.paragraphIndex })
        results.forEach { result ->
            assertEquals(spans[result.paragraphIndex].start, result.start)
            assertEquals(spans[result.paragraphIndex].endExclusive, result.endExclusive)
            assertEquals(item.text.substring(spans[result.paragraphIndex].start, spans[result.paragraphIndex].endExclusive),
                item.text.substring(result.start, result.endExclusive))
        }
        assertEquals(original, item)
        assertEquals(hash(item.text), item.contentHash)
    }

    @Test fun sourceHostAndUrlIdentityAreExactAndUnknownSourcesFailOpen() {
        val item = passage(singular)
        listOf("https://theconversation.com/article", "https://www.theconversation.com/article", "http://theconversation.com/article")
            .forEach { assertEquals(1, ReadingDisclosures.detect(article(url = it), item).size) }
        listOf("https://theconversation.com.example.org/article", "https://faketheconversation.com/article", "https://example.org/theconversation.com",
            "https://theconversation.com@evil.example/article", "https://user@theconversation.com/article", "file://theconversation.com/article",
            "https://theconversation.com:9999/article", "not a url", "")
            .forEach { assertTrue(it, ReadingDisclosures.detect(article(url = it), item).isEmpty()) }
    }

    @Test fun mismatchedArticleHashOrReadingModeCannotLabelAnotherSnapshot() {
        val item = passage(singular)
        assertTrue(ReadingDisclosures.detect(article(), item.copy(articleId = "another-article")).isEmpty())
        assertTrue(ReadingDisclosures.detect(article(), item.copy(contentHash = hash("different source"))).isEmpty())
        assertTrue(ReadingDisclosures.detect(article().copy(readingMode = ReadingMode.EXTERNAL), item).isEmpty())
        assertTrue(ReadingDisclosures.detect(article().copy(id = ""), item.copy(articleId = "")).isEmpty())
    }

    @Test fun oversizedOrMalformedEntitiesRemainVisibleAsOrdinaryText() {
        listOf(
            "Alice Morgan receives funding from " + "Research ".repeat(70) + "Foundation.",
            "Alice Morgan is affiliated with the Research Institute (RI.",
            "Alice Morgan is affiliated with the Research Institute ((RI)).",
            "Alice Morgan is affiliated with the Research Institute; the Brookings Institution.",
            "Alice Morgan receives funding from the Research Foundation\u0000.",
            "$funding He receives funding from the Research Council. He receives funding from the Research Council. He receives funding from the Research Council.",
        ).forEach { assertTrue(ReadingDisclosures.detect(article(), passage(it)).isEmpty()) }
    }
}
