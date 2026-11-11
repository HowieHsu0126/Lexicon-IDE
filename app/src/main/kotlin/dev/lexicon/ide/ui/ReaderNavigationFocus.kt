package dev.lexicon.ide.ui

import androidx.compose.runtime.saveable.Saver
import dev.lexicon.domain.ArticleBody
import dev.lexicon.domain.ParagraphFocus
import dev.lexicon.domain.ReaderAnchor
import kotlinx.serialization.json.*
import kotlin.math.abs

/** A navigation request is independent of text selection and belongs to one rendered snapshot. */
internal data class ReaderNavigationRequest(
    val articleId: String,
    val bodyHash: String,
    val version: Int,
    val paragraphIndex: Int,
    val start: Int?,
    val end: Int?,
) {
    val jumpId: String get() = "$bodyHash:$version"

    companion object {
        fun from(articleId: String, body: ArticleBody?, display: ReaderDisplay): ReaderNavigationRequest? {
            body ?: return null
            val index = display.jumpParagraph ?: return null
            if (display.jumpBodyHash != null && display.jumpBodyHash != body.contentHash) return null
            if (index !in ParagraphFocus.paragraphs(body.text).indices) return null
            if (display.jumpStart != null || display.jumpEnd != null) {
                if (display.jumpBodyHash != body.contentHash) return null
                val start = display.jumpStart ?: return null
                val end = display.jumpEnd ?: return null
                if (ReaderAnchor(body.contentHash, start, end).paragraphIndex(body) != index) return null
            }
            return ReaderNavigationRequest(articleId, body.contentHash, display.jumpVersion, index, display.jumpStart, display.jumpEnd)
        }
    }
}

internal enum class ReaderNavigationPhase { PENDING, PINNED, RELEASED }
internal data class ReaderNavigationFocus(val request: ReaderNavigationRequest, val phase: ReaderNavigationPhase)
internal data class ReaderFocusLine(val top: Float, val bottom: Float)

/** Only identity and an explicit consumed/released decision enter saved state, never the article. */
internal val ReaderNavigationFocusSaver = Saver<ReaderNavigationFocus?, String>(
    save = { state -> state?.let {
        buildJsonObject {
            put("schema", 1); put("articleId", it.request.articleId); put("bodyHash", it.request.bodyHash)
            put("version", it.request.version); put("paragraph", it.request.paragraphIndex)
            put("start", it.request.start); put("end", it.request.end); put("phase", it.phase.name)
        }.toString()
    } },
    restore = { encoded -> runCatching {
        val root = Json.parseToJsonElement(encoded).jsonObject
        require(root.getValue("schema").jsonPrimitive.int == 1)
        fun string(key: String) = root.getValue(key).jsonPrimitive.let { require(it.isString); it.content }
        fun offset(key: String) = root.getValue(key).takeUnless { it == JsonNull }?.jsonPrimitive?.int
        val request = ReaderNavigationRequest(string("articleId"), string("bodyHash"), root.getValue("version").jsonPrimitive.int,
            root.getValue("paragraph").jsonPrimitive.int, offset("start"), offset("end"))
        require(request.articleId.isNotBlank() && request.bodyHash.isNotBlank() && request.paragraphIndex >= 0)
        require((request.start == null && request.end == null) ||
            (request.start != null && request.end != null && request.start >= 0 && request.end > request.start))
        ReaderNavigationFocus(request, ReaderNavigationPhase.valueOf(string("phase")))
    }.getOrNull() },
)

/** Plain counters keep scroll frames out of Compose state; only crossing the threshold changes UI. */
internal class ReaderNavigationMovement(private val releaseDistance: Float) {
    private var userDistance = 0f
    private var indirectDistance = 0f
    private var lastScroll: Int? = null

    fun userScroll(consumed: Float): Boolean {
        if (consumed.isFinite()) userDistance += abs(consumed)
        return userDistance > releaseDistance
    }

    /** Accessibility and keyboard scrolls can update ScrollState without a nested user-input event. */
    fun observeScroll(value: Int, moving: Boolean, owned: Boolean): Boolean {
        val previous = lastScroll
        lastScroll = value
        if (owned) { indirectDistance = 0f; return false }
        if (moving && previous != null) indirectDistance += abs(value.toLong() - previous).toFloat()
        return indirectDistance > releaseDistance
    }
}
