package dev.lexicon.ide.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import dev.lexicon.domain.ReadingViewport
import kotlin.math.roundToInt

internal data class ReaderReflowSource(val articleId: String, val bodyHash: String, val entryKey: String?)
internal data class ReaderReflowGeometryKey(
    val measurementStyle: TextStyle,
    val density: Float,
    val fontScale: Float,
    val maxWidth: Int,
    val direction: LayoutDirection,
)
internal data class ReaderReflowAnchor(val offset: Int, val lineTopFraction: Float)
internal data class ReaderReflowLine(val top: Float, val bottom: Float)

/** The production adapter retains a TextLayoutResult; a JVM test can provide deterministic lines. */
internal interface ReaderReflowLayout {
    val source: ReaderReflowSource
    val key: ReaderReflowGeometryKey
    fun anchorIn(viewport: ReadingViewport): ReaderReflowAnchor?
    fun lineForOffset(offset: Int): ReaderReflowLine?
}

internal data class ReaderReflowRequest(
    val source: ReaderReflowSource,
    val serial: Long,
    val anchor: ReaderReflowAnchor,
    val targetKey: ReaderReflowGeometryKey,
)
internal data class ReaderReflowState(val request: ReaderReflowRequest? = null, val readyRevision: Long? = null)
internal data class ReaderReflowTarget(val request: ReaderReflowRequest, val scroll: Int, val unclampedScroll: Float) {
    val clamped: Boolean get() = kotlin.math.abs(scroll - unclampedScroll) > 1f
}

/**
 * UI-local ownership only. No Compose state, scrolling, database writes or per-frame text search.
 * Create one controller per source identity. Callbacks from another body/entry are ignored.
 */
internal class ReaderReflowController(private val source: ReaderReflowSource) {
    private data class Placement(val layout: ReaderReflowLayout, val viewport: ReadingViewport, val scroll: Int)
    private data class Applying(val requestSerial: Long, val actualScroll: Int? = null)
    private var latestLayout: ReaderReflowLayout? = null
    private var placement: Placement? = null
    private var request: ReaderReflowRequest? = null
    // Keep the exact reading token through completed appearance changes until reading resumes.
    // This is not pending UI ownership and does not keep a scroll coroutine alive.
    private var retainedAnchor: ReaderReflowAnchor? = null
    private var applying: Applying? = null
    private var serial = 0L
    private var placementRevision = 0L
    private var readyRevision: Long? = null

    val state: ReaderReflowState get() = ReaderReflowState(request, readyRevision)

    /**
     * Called from onTextLayout BEFORE publishing that result as the field's current layout.
     * `allowReflow` excludes navigation/restore ownership. `scrollIdle` excludes ordinary motion,
     * but an existing reflow request can be retargeted during its own one-shot correction.
     */
    fun onLayout(layout: ReaderReflowLayout, currentScroll: Int, scrollIdle: Boolean, allowReflow: Boolean): ReaderReflowState {
        if (layout.source != source) return state
        val previous = latestLayout
        latestLayout = layout
        if (!allowReflow) return cancel()
        if (previous == null || previous.key == layout.key) return state

        // Preserve the same token across both pending and already completed settings commits.
        // A completed anchor is reusable only while its positioned scroll sample remains current.
        val oldPosition = placement?.takeIf { it.layout.key == previous.key && it.scroll == currentScroll }
        val retained = request?.anchor ?: retainedAnchor?.takeIf { scrollIdle && oldPosition != null }
        val anchor = retained ?: oldPosition?.takeIf { scrollIdle }?.let { previous.anchorIn(it.viewport) }
        placement = null
        readyRevision = null
        applying = null
        retainedAnchor = anchor?.takeIf { it.offset >= 0 && it.lineTopFraction.isFinite() && it.lineTopFraction in 0f..1f }
        request = retainedAnchor?.let { ReaderReflowRequest(source, ++serial, it, layout.key) }
        return state
    }

    /**
     * Only a placement for the latest reported geometry can become ready. The local viewport and
     * scroll value must be sampled in the same callback. With no request this changes plain fields
     * only; the returned empty state is equal across ordinary scroll frames.
     */
    fun onPositioned(layout: ReaderReflowLayout, viewport: ReadingViewport, scroll: Int, allowReflow: Boolean): ReaderReflowState {
        if (layout.source != source || latestLayout?.key != layout.key) return state
        if (viewport.valid && scroll >= 0) {
            val previousPlacement = placement
            val moved = previousPlacement?.scroll?.let { it != scroll } == true
            // Once the target geometry has been placed, motion before our correction begins is
            // external even if no pointer callback arrived. Its new placement remains usable.
            // onLayout clears placement, so the first new-layout clamp is never mistaken for it.
            if (moved && applying == null && request?.targetKey == layout.key && previousPlacement?.layout?.key == layout.key) {
                clearRequest()
            }
            // Also cover accessibility/semantic scrolling that does not dispatch a pointer drag.
            if (request == null && moved) retainedAnchor = null
            placement = Placement(layout, viewport, scroll)
        }
        if (!allowReflow) {
            // Navigation may release ownership without another placement (for example, a word tap).
            // Retain the measured viewport for the next metric change, but never own a correction.
            clearRequest()
            retainedAnchor = null
            return state
        }
        if (!viewport.valid || scroll < 0) return state
        val active = request ?: return state
        if (active.targetKey != layout.key) return state
        val correction = applying
        if (correction != null) {
            // Release only after the new position has actually been placed, not just assigned.
            if (correction.requestSerial == active.serial && correction.actualScroll == scroll) clearRequest()
        } else readyRevision = ++placementRevision
        return state
    }

    /** Reserve this request once; the owner then calls ScrollState.scrollTo at normal priority. */
    fun beginApply(serial: Long, currentScroll: Int, maxScroll: Int, allowReflow: Boolean): ReaderReflowTarget? {
        val active = request?.takeIf { it.serial == serial && it.source == source } ?: return null
        if (!allowReflow) { cancel(); return null }
        if (applying != null || maxScroll < 0 || maxScroll == Int.MAX_VALUE) return null
        val positioned = placement?.takeIf {
            it.layout.key == active.targetKey && latestLayout?.key == active.targetKey && it.scroll == currentScroll
        } ?: return null
        val line = positioned.layout.lineForOffset(active.anchor.offset) ?: run { cancel(); return null }
        val height = positioned.viewport.bottom - positioned.viewport.top
        if (!line.top.isFinite() || !line.bottom.isFinite() || line.bottom <= line.top || !height.isFinite() || height <= 0f) {
            cancel(); return null
        }
        val desired = currentScroll + line.top - positioned.viewport.top - height * active.anchor.lineTopFraction
        if (!desired.isFinite()) { cancel(); return null }
        val target = desired.roundToInt().coerceIn(0, maxScroll)
        applying = Applying(active.serial)
        readyRevision = null
        return ReaderReflowTarget(active, target, desired)
    }

    /** Called after scrollTo returns. A late completion can never consume a newer request. */
    fun scrollFinished(serial: Long, actualScroll: Int): ReaderReflowState {
        val active = request?.takeIf { it.serial == serial } ?: return state
        if (applying?.requestSerial != serial || actualScroll < 0) return state
        applying = Applying(serial, actualScroll)
        val positioned = placement
        if (positioned != null && positioned.layout.key == active.targetKey && positioned.scroll == actualScroll) clearRequest()
        return state
    }

    /**
     * User drag/fling or explicit navigation owns the next movement, including late callbacks.
     * A stationary paragraph/word tap can keep its valid placement while ending anchor reuse.
     */
    fun cancel(serial: Long? = null, retainPlacement: Boolean = false): ReaderReflowState {
        if (serial != null && request?.serial != serial) return state
        clearRequest()
        retainedAnchor = null
        if (!retainPlacement) placement = null
        return state
    }

    private fun clearRequest() {
        request = null
        applying = null
        readyRevision = null
    }
}
