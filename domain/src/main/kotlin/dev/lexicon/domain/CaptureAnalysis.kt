package dev.lexicon.domain

/** A local-only probe. Looking at a capture must never save it or start model work. */
sealed interface CaptureAnalysisAvailability {
    data object Missing : CaptureAnalysisAvailability
    data class Ready(val analysis: AnalysisResult, val receiptId: String, val deep: Boolean = false) : CaptureAnalysisAvailability
    /** A changed capture still offers its existing card for inspection, never a second card. */
    data class ExistingCard(val card: Card, val matchesInput: Boolean = true) : CaptureAnalysisAvailability
    data object Restricted : CaptureAnalysisAvailability
    data object Conflict : CaptureAnalysisAvailability
}

/** Input identity excludes timestamps and mutable permission; permission is checked separately. */
object CaptureAnalysisIdentity {
    fun sameInput(left: Capture, right: Capture): Boolean =
        left.id == right.id && left.target == right.target && left.selectedText == right.selectedText &&
            left.sentence == right.sentence && left.articleId == right.articleId && left.sourceRef == right.sourceRef &&
            left.contextOrigin == right.contextOrigin && left.bodyHash == right.bodyHash &&
            left.selectionStart == right.selectionStart && left.selectionEnd == right.selectionEnd
}
