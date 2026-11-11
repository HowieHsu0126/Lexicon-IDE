package dev.lexicon.domain

import kotlinx.serialization.Serializable

/** A local article destination. A missing anchor means ordinary reading, never a guessed quote. */
@Serializable
data class CardSourceTarget(val articleId: String, val anchor: ReaderAnchor? = null)

fun resolveCardSourceTarget(card: Card, captures: List<Capture>, articles: List<Article>): CardSourceTarget? {
    val originalUrl = card.sourceRef.url?.takeIf { it.isNotBlank() } ?: return null
    val capture = captures.singleOrNull { it.id == card.captureId }
    val linked = capture?.articleId?.let { id -> articles.singleOrNull { it.id == id } }
    val sameMaterial = capture != null && linked != null && linked.url == originalUrl &&
        capture.sourceRef == card.sourceRef && capture.sentence == card.sourceSentence &&
        capture.contextOrigin == card.contextOrigin
    if (sameMaterial) {
        val original = requireNotNull(capture)
        val hash = original.bodyHash?.takeIf { it.isNotBlank() }
        val start = original.selectionStart
        val end = original.selectionEnd
        val anchor = if (card.contextOrigin == ContextOrigin.ORIGINAL && hash != null && start != null && end != null &&
            start >= 0 && end > start) ReaderAnchor(hash, start, end) else null
        // The reader verifies the loaded body's hash, bounds and UTF-16 boundaries before locating it.
        return CardSourceTarget(requireNotNull(linked).id, anchor)
    }
    // A capture can be edited or rebound after this card was created. Only the card's own URL
    // may recover a local destination, and an ambiguous URL never chooses an arbitrary article.
    return articles.singleOrNull { it.url == originalUrl }?.let { CardSourceTarget(it.id) }
}
