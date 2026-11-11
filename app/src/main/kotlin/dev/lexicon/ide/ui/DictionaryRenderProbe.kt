package dev.lexicon.ide.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Optional instrumentation seam. Implementations live in androidTest; ordinary app use
 * supplies null. Result identity is opaque, and callbacks receive no dictionary text.
 */
internal interface DictionaryRenderProbe {
    fun bindResult(captureId: String, query: String, dictionaryId: String, resultIdentity: Any): DictionaryResultRenderProbe?
}

internal interface DictionaryResultRenderProbe {
    fun onTextLayout(index: Int, part: DictionaryTextPart, expanded: Boolean, characters: Int, lines: Int, ellipsized: Boolean)
    fun onTextDraw(index: Int, part: DictionaryTextPart, expanded: Boolean, visibleBounds: Boolean)
}

internal enum class DictionaryTextPart { WORD, DEFINITION }

internal val LocalDictionaryRenderProbe = staticCompositionLocalOf<DictionaryRenderProbe?> { null }

private class DictionaryProbeGeometry { var coordinates: LayoutCoordinates? = null }

/** Does not install a layout/draw modifier or allocate geometry when the probe is absent. */
@Composable
internal fun Modifier.dictionaryDrawProbe(
    probe: DictionaryResultRenderProbe?, index: Int, part: DictionaryTextPart, expanded: Boolean,
): Modifier {
    if (probe == null) return this
    val geometry = remember(probe, index, part) { DictionaryProbeGeometry() }
    return onGloballyPositioned { geometry.coordinates = it }.drawWithContent {
        drawContent()
        val coordinates = geometry.coordinates
        val bounds = coordinates?.takeIf { it.isAttached }?.boundsInWindow()
        probe.onTextDraw(index, part, expanded,
            size.width > 0f && size.height > 0f && bounds != null && bounds.width > 0f && bounds.height > 0f)
    }
}
