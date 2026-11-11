package dev.lexicon.domain

import kotlinx.serialization.Serializable

/** A half-open UTF-16 range belongs to one exact original-body snapshot. */
@Serializable
data class ReaderAnchor(val bodyHash: String, val start: Int, val end: Int) {
    fun paragraphIndex(body: ArticleBody): Int? {
        if (bodyHash.isBlank() || bodyHash != body.contentHash || start < 0 || end <= start || end > body.text.length) return null
        fun splitsSurrogate(offset: Int) = offset > 0 && offset < body.text.length &&
            body.text[offset - 1].isHighSurrogate() && body.text[offset].isLowSurrogate()
        if (splitsSurrogate(start) || splitsSurrogate(end)) return null
        return ParagraphFocus.paragraphs(body.text).indexOfFirst { start < it.endExclusive && end > it.start }.takeIf { it >= 0 }
    }
}

/** AI rewrites and user-supplied text have no offset relationship to the original article. */
fun ReadingNote.originalAnchor(): ReaderAnchor? {
    if (origin != ContextOrigin.ORIGINAL || articleId == null) return null
    val hash = bodyHash?.takeIf { it.isNotBlank() } ?: return null
    val from = start ?: return null
    val to = end ?: return null
    return ReaderAnchor(hash, from, to).takeIf { from >= 0 && to > from }
}
