package dev.lexicon.domain

import org.junit.Assert.*
import org.junit.Test

class CardSourceTargetTest {
    private val article = Article("original", "guid", emptyList(), "Original article", "https://publisher.test/original", "", sourceName = "Publisher")
    private val other = article.copy(id = "other", guid = "other", title = "Other article", url = "https://publisher.test/other")
    private val source = SourceRef(article.title, article.sourceName, article.url, 42)
    private val text = "A robust method works.\n\nA robust method works."
    private val start = text.lastIndexOf("robust")
    private val body = ArticleBody(article.id, text, "original-body", 0, Long.MAX_VALUE, "test")
    private val capture = Capture("capture", article.id, "robust", "robust", "A robust method works.", ContextOrigin.ORIGINAL,
        source, start, start + 6, body.contentHash, 1)
    private val card = Card("card", capture.id, VocabularyContent("robust", "effective", emptyList(), "sound", "neutral", "high"),
        capture.sentence, source, createdAt = 1)

    @Test fun unchangedOriginalMaterialUsesItsExactRepeatedOccurrence() {
        val target = requireNotNull(resolveCardSourceTarget(card, listOf(capture), listOf(article, other)))
        assertEquals(article.id, target.articleId)
        assertEquals(ReaderAnchor(body.contentHash, start, start + 6), target.anchor)
        assertEquals(1, target.anchor?.paragraphIndex(body))
    }

    @Test fun changedBodyIsNeverSearchedForMatchingWords() {
        val target = requireNotNull(resolveCardSourceTarget(card, listOf(capture), listOf(article)))
        assertNull(target.anchor?.paragraphIndex(body.copy(contentHash = "updated-body")))
        assertNull(target.anchor?.paragraphIndex(body.copy(text = "Short")))
    }

    @Test fun reboundCaptureCannotRedirectTheCardToAnotherArticle() {
        for (rebound in listOf(capture.copy(articleId = other.id), capture.copy(articleId = other.id,
            sourceRef = SourceRef(other.title, other.sourceName, other.url)))) {
            val target = resolveCardSourceTarget(card, listOf(rebound), listOf(other, article))
            assertEquals(CardSourceTarget(article.id), target)
            assertNull(resolveCardSourceTarget(card, listOf(rebound), listOf(other)))
        }
    }

    @Test fun changedSentenceSourceOrOriginOnlyAllowsAnUnanchoredImmutableUrlFallback() {
        val changes = listOf(
            capture.copy(sentence = "A different sentence."),
            capture.copy(sourceRef = source.copy(title = "Changed title")),
            capture.copy(contextOrigin = ContextOrigin.USER_SUPPLIED),
        )
        changes.forEach { assertEquals(CardSourceTarget(article.id), resolveCardSourceTarget(card, listOf(it), listOf(article))) }
    }

    @Test fun userAndAiContextNeverBecomeOriginalOffsets() {
        for (origin in listOf(ContextOrigin.USER_SUPPLIED, ContextOrigin.AI_GENERATED, ContextOrigin.MISSING)) {
            assertEquals(CardSourceTarget(article.id), resolveCardSourceTarget(card.copy(contextOrigin = origin),
                listOf(capture.copy(contextOrigin = origin)), listOf(article)))
        }
    }

    @Test fun missingCaptureCanOnlyUseOneExactImmutableUrlMatch() {
        assertEquals(CardSourceTarget(article.id), resolveCardSourceTarget(card, emptyList(), listOf(article, other)))
        assertNull(resolveCardSourceTarget(card, emptyList(), listOf(article, article.copy(id = "duplicate"))))
        assertNull(resolveCardSourceTarget(card, emptyList(), listOf(other)))
        assertNull(resolveCardSourceTarget(card, emptyList(), listOf(article.copy(url = article.url + "/"))))
    }

    @Test fun exactCaptureCanDisambiguateSameUrlButDuplicateCaptureIdsCannotSupplyAnAnchor() {
        val duplicate = article.copy(id = "duplicate")
        assertNotNull(resolveCardSourceTarget(card, listOf(capture), listOf(article, duplicate))?.anchor)
        assertEquals(CardSourceTarget(article.id), resolveCardSourceTarget(card, listOf(capture, capture), listOf(article)))
        assertNull(resolveCardSourceTarget(card, listOf(capture, capture), listOf(article, duplicate)))
    }

    @Test fun incompleteOrInvalidRangesDoNotProduceAnchors() {
        listOf(capture.copy(bodyHash = null), capture.copy(bodyHash = ""), capture.copy(selectionStart = null),
            capture.copy(selectionEnd = null), capture.copy(selectionStart = -1),
            capture.copy(selectionEnd = start), capture.copy(selectionEnd = start - 1)).forEach {
            assertEquals(CardSourceTarget(article.id), resolveCardSourceTarget(card, listOf(it), listOf(article)))
        }
    }

    @Test fun unavailableSourcesDoNotCreateExternalOrGuessedDestinations() {
        assertNull(resolveCardSourceTarget(card, listOf(capture), emptyList()))
        for (url in listOf(null, "", " ")) {
            val noUrl = source.copy(url = url)
            assertNull(resolveCardSourceTarget(card.copy(sourceRef = noUrl), listOf(capture.copy(sourceRef = noUrl)), listOf(article)))
        }
        assertNull(resolveCardSourceTarget(card.copy(captureId = "other-capture", sourceRef = source.copy(url = other.url)),
            listOf(capture), listOf(article)))
    }
}
