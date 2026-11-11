package dev.lexicon.ide.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import dev.lexicon.domain.ParagraphFocus
import dev.lexicon.domain.ReadingParagraphBounds
import dev.lexicon.domain.ReadingViewport
import dev.lexicon.domain.StudyHighlight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal val FocusedParagraphIndex = SemanticsPropertyKey<Int>("FocusedParagraphIndex")
internal var SemanticsPropertyReceiver.focusedParagraphIndex by FocusedParagraphIndex

private data class TappedParagraph(val index: Int, val viewportTop: Float)

/** Placement bookkeeping stays outside Compose state and does not remeasure text while scrolling. */
private class ReaderReflowPlacement {
    var layout: ReaderReflowLayout? = null
    var measuredSize: IntSize = IntSize.Zero
}

/**
 * A single, unchanged text field owns selection. Only the text paint and a quiet paragraph tint
 * change during focus transitions; neither line wrapping nor character offsets are animated.
 */
@Composable
internal fun FocusedArticleText(
    state: TextFieldState,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    captureVisible: Boolean = false,
    highlights: List<StudyHighlight> = emptyList(),
    activeRange: TextRange? = null,
    focusEnabled: Boolean = true,
    emphasizedParagraphs: Set<Int> = emptySet(),
    onTapWord: ((Int, Int) -> Unit)? = null,
    onFocusedParagraph: (Int) -> Unit = {},
    onParagraphBounds: (List<ReadingParagraphBounds>) -> Unit = {},
    onActiveRangeTop: (Float?) -> Unit = {},
    navigationFocus: ReaderNavigationRequest? = null,
    viewportInRoot: (() -> ReadingViewport?)? = null,
    positionRange: TextRange? = activeRange,
    onPositionLine: (ReaderFocusLine?) -> Unit = {},
    onParagraphTap: (Int) -> Unit = {},
    reflowSource: ReaderReflowSource? = null,
    onReflowLayout: (ReaderReflowLayout) -> Unit = {},
    onReflowPositioned: (ReaderReflowLayout, ReadingViewport) -> Unit = { _, _ -> },
) {
    val renderProbe = LocalReaderRenderProbe.current
    if (renderProbe != null) SideEffect { renderProbe.record(ReaderRenderEvent.TEXT_COMPOSITION) }
    val text = state.text
    val paragraphs = remember(text) { ParagraphFocus.paragraphs(text) }
    var focusedIndex by remember(state, paragraphs) {
        val initialOffset = if (state.selection.collapsed) 0 else state.selection.min
        mutableIntStateOf(ParagraphFocus.atOffset(paragraphs, initialOffset) ?: -1)
    }
    var paragraphBounds by remember(state) { mutableStateOf(emptyList<ReadingParagraphBounds>()) }
    val viewport = remember(state) { ParagraphViewportTracker() }
    val candidates = remember(state) { MutableStateFlow<Int?>(null) }
    // Only drawing and hit testing read this state. Animated paint never becomes a layout input.
    var textLayout by remember(state) { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val hysteresis = with(density) { 24.dp.toPx() }
    val tapReleaseDistance = with(density) { 18.dp.toPx() }
    val forcedIndex = navigationFocus?.paragraphIndex?.takeIf { it in paragraphs.indices }
    val effectiveFocusedIndex = forcedIndex ?: focusedIndex
    val frozen = captureVisible || !state.selection.collapsed || forcedIndex != null
    val frozenNow by rememberUpdatedState(captureVisible || !state.selection.collapsed)
    val tapWordNow by rememberUpdatedState(onTapWord)
    val highlightsNow by rememberUpdatedState(highlights)
    val paragraphChangedNow by rememberUpdatedState(onFocusedParagraph)
    val paragraphBoundsNow by rememberUpdatedState(onParagraphBounds)
    val viewportInRootNow by rememberUpdatedState(viewportInRoot)
    val paragraphTapNow by rememberUpdatedState(onParagraphTap)
    val reflowLayoutNow by rememberUpdatedState(onReflowLayout)
    val reflowPositionedNow by rememberUpdatedState(onReflowPositioned)
    val reflowPlacement = remember(state, reflowSource) { ReaderReflowPlacement() }
    val validHighlights = remember(text, highlights) {
        highlights.filter { it.start >= 0 && it.end > it.start && it.end <= text.length &&
            text.subSequence(it.start, it.end).toString() == it.quote }
    }
    val validActiveRange = activeRange?.takeIf { it.min >= 0 && it.max <= text.length && !it.collapsed }
    val validPositionRange = positionRange?.takeIf { it.min >= 0 && it.max <= text.length && !it.collapsed }
    val rangeTopNow by rememberUpdatedState(onActiveRangeTop)
    val positionLineNow by rememberUpdatedState(onPositionLine)
    LaunchedEffect(textLayout, validPositionRange) {
        val line = validPositionRange?.let { range -> textLayout?.let { layout ->
            val index = layout.getLineForOffset(range.min)
            ReaderFocusLine(layout.getLineTop(index), layout.getLineBottom(index))
        } }
        rangeTopNow(line?.top)
        positionLineNow(line)
    }
    LaunchedEffect(state, navigationFocus) {
        if (forcedIndex != null) {
            viewport.unpin()
            focusedIndex = forcedIndex
        }
    }
    fun updateCandidate() {
        candidates.value = viewport.choose(paragraphBounds, focusedIndex.takeIf { it >= 0 }, hysteresis, tapReleaseDistance)
    }
    LaunchedEffect(candidates, frozen) {
        candidates.collectLatest { candidate ->
            renderProbe?.record(ReaderRenderEvent.FOCUS_CANDIDATE)
            if (!frozen && candidate != null && candidate != focusedIndex) {
                // Candidate changes cancel only this settle job, not the text composition. Moving
                // inside the same paragraph leaves the job running; fast flings skip brief targets.
                delay(90)
                focusedIndex = candidate
            }
        }
    }
    LaunchedEffect(state, effectiveFocusedIndex) { if (effectiveFocusedIndex >= 0) paragraphChangedNow(effectiveFocusedIndex) }

    val scheme = MaterialTheme.colorScheme
    val foreground = textStyle.color.takeOrElse { scheme.onSurface }
    val dimOpacity = if (scheme.background.luminance() > 0.5f) 0.52f else 0.42f
    val targets = remember(paragraphs.size, focusEnabled, if (focusEnabled) effectiveFocusedIndex else -1, emphasizedParagraphs) {
        List(paragraphs.size) { index ->
            if (focusEnabled) index == effectiveFocusedIndex else emphasizedParagraphs.isEmpty() || index in emphasizedParagraphs
        }
    }
    // One clock drives the cross-fade, regardless of article length. Interrupted transitions begin
    // at their current painted weights rather than snapping the previous paragraph back to 0 or 1.
    val paint = remember(state, paragraphs.size) { ParagraphPaintTransition(targets) }
    LaunchedEffect(paint, targets) { paint.retarget(targets) }
    // The field retains native layout, selection and semantics. Its transparent glyph paint is
    // replaced once below, after native selection backgrounds, using that exact cached layout.
    val measurementStyle = remember(textStyle) { textStyle.copy(color = Color.Transparent) }
    val focusTint = scheme.primary
    val languageTint = scheme.secondary

    BasicTextField(
        state = state,
        readOnly = true,
        textStyle = measurementStyle,
        modifier = modifier
            .testTag("zen_article_text")
            .semantics { focusedParagraphIndex = effectiveFocusedIndex }
            .onGloballyPositioned { coordinates ->
                renderProbe?.record(ReaderRenderEvent.VIEWPORT_CALLBACK)
                val clipped = coordinates.boundsInWindow()
                if (clipped.height <= 0f) {
                    viewport.update(0f, 0f)
                } else {
                    val supplied = viewportInRootNow?.invoke()?.takeIf { it.valid }
                    val localViewport = if (supplied != null) {
                        val origin = coordinates.positionInRoot().y
                        ReadingViewport(supplied.top - origin, supplied.bottom - origin)
                    } else {
                        val origin = coordinates.localToWindow(Offset.Zero)
                        val root = coordinates.findRootCoordinates().boundsInWindow()
                        ReadingViewport(root.top - origin.y, root.bottom - origin.y)
                    }
                    viewport.update(localViewport.top, localViewport.bottom)
                    // A new layout cannot be paired with the previous field's placement.
                    reflowPlacement.layout?.takeIf { coordinates.size == reflowPlacement.measuredSize }?.let {
                        reflowPositionedNow(it, localViewport)
                    }
                }
                updateCandidate()
            }
            .drawWithCache {
                val horizontalPadding = 9.dp.toPx()
                val verticalPadding = 7.dp.toPx()
                val layout = textLayout
                val bounds = paragraphBounds
                val highlightPaths = layout?.let { result -> validHighlights.map { highlight ->
                    result.getPathForRange(highlight.start, highlight.end) to
                        if (highlight.kind in setOf("sentence", "argument")) languageTint else focusTint
                } }.orEmpty()
                val activePath = if (layout != null && validActiveRange != null) {
                    layout.getPathForRange(validActiveRange.min, validActiveRange.max)
                } else null
                val currentWeights = FloatArray(paragraphs.size)
                var lastProgress = Float.NaN
                var lastVersion = -1
                var cachedBrush: Brush? = null
                onDrawWithContent {
                    renderProbe?.record(ReaderRenderEvent.TEXT_DRAW)
                    // Read animation values here, not in composition or the cache builder. A focus
                    // cross-fade invalidates only this draw phase, never TextField measurement.
                    val progress = paint.progress.value
                    if (progress != lastProgress || paint.version != lastVersion || cachedBrush == null) {
                        paint.copyWeights(currentWeights, progress)
                        cachedBrush = paragraphBrush(bounds, currentWeights, layout?.size?.height?.toFloat() ?: 0f, foreground, dimOpacity, renderProbe)
                        lastProgress = progress
                        lastVersion = paint.version
                    }
                    bounds.forEach { paragraph ->
                        val weight = currentWeights.getOrElse(paragraph.index) { 0f }
                        if (weight > 0.001f && (focusEnabled || emphasizedParagraphs.isNotEmpty())) {
                            drawRoundRect(
                                color = focusTint.copy(alpha = 0.045f * weight),
                                topLeft = Offset(-horizontalPadding, paragraph.top - verticalPadding),
                                size = Size(size.width + horizontalPadding * 2f, paragraph.bottom - paragraph.top + verticalPadding * 2f),
                                cornerRadius = CornerRadius(12.dp.toPx()),
                            )
                        }
                    }
                    highlightPaths.forEach { (path, color) -> drawPath(path, color.copy(alpha = .14f)) }
                    activePath?.let { drawPath(it, focusTint.copy(alpha = .25f)) }
                    drawContent()
                    layout?.let {
                        drawText(
                            textLayoutResult = it,
                            brush = cachedBrush!!,
                            // Do not inherit the native field's transparent measurement paint.
                            alpha = 1f,
                        )
                    }
                }
            }
            .pointerInput(state, paragraphs) {
                awaitEachGesture {
                    // Observe taps before the text field handles them; never consume a pointer.
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                    if (up != null && !frozenNow && up.uptimeMillis - down.uptimeMillis < 200 &&
                        (up.position - down.position).getDistance() < viewConfiguration.touchSlop) {
                        textLayout?.let { layout ->
                            val offset = layout.getOffsetForPosition(up.position)
                            ParagraphFocus.atOffset(paragraphs, offset)?.let { index ->
                                focusedIndex = index
                                viewport.pin(index)
                                candidates.value = index
                                paragraphTapNow(index)
                            }
                            val hit = listOf(offset, offset - 1).firstOrNull { it in 0 until text.length && layout.getBoundingBox(it).contains(up.position) }
                            if (hit != null && text[hit].isLetterOrDigit()) {
                                val matched = highlightsNow.firstOrNull { it.kind in setOf("vocabulary", "collocation", "phrase") && hit >= it.start && hit < it.end && it.start >= 0 && it.end <= text.length && text.subSequence(it.start, it.end).toString() == it.quote }
                                val word = matched?.let { TextRange(it.start, it.end) } ?: layout.getWordBoundary(hit)
                                if (!word.collapsed && word.min >= 0 && word.max <= text.length &&
                                    text.subSequence(word.min, word.max).any { it.isLetterOrDigit() }) tapWordNow?.invoke(word.min, word.max)
                            }
                        }
                    }
                }
            },
        onTextLayout = { getResult ->
            renderProbe?.record(ReaderRenderEvent.TEXT_LAYOUT_CALLBACK)
            getResult()?.let { layout ->
                reflowSource?.let { source ->
                    readerReflowLayout(source, text.toString(), layout)?.let { measured ->
                        reflowLayoutNow(measured)
                        reflowPlacement.layout = measured
                        reflowPlacement.measuredSize = layout.size
                    }
                }
                if (layout != textLayout) renderProbe?.record(ReaderRenderEvent.TEXT_LAYOUT_UPDATE)
                textLayout = layout
                val newBounds = paragraphs.mapIndexed { index, paragraph ->
                    val firstLine = layout.getLineForOffset(paragraph.start)
                    val lastLine = layout.getLineForOffset(paragraph.endExclusive - 1)
                    ReadingParagraphBounds(index, layout.getLineTop(firstLine), layout.getLineBottom(lastLine))
                }
                if (newBounds != paragraphBounds) { paragraphBounds = newBounds; paragraphBoundsNow(newBounds); updateCandidate() }
            }
        },
    )
}

/** Constant color inside each paragraph; color ramps occupy only the blank line between them. */
private fun paragraphBrush(
    bounds: List<ReadingParagraphBounds>,
    weights: FloatArray,
    textHeight: Float,
    foreground: Color,
    dimOpacity: Float,
    renderProbe: ReaderRenderProbe? = null,
): Brush {
    renderProbe?.record(ReaderRenderEvent.BRUSH_BUILD)
    if (bounds.isEmpty() || textHeight <= 0f) return SolidColor(foreground)
    fun color(index: Int): Color {
        val weight = weights.getOrElse(index) { 0f }.coerceIn(0f, 1f)
        return foreground.copy(alpha = foreground.alpha * (dimOpacity + (1f - dimOpacity) * weight))
    }
    val firstColor = color(bounds.first().index)
    val stops = buildList {
        add(0f to firstColor)
        var previous = bounds.first()
        var previousColor = firstColor
        for (index in 1 until bounds.size) {
            val paragraph = bounds[index]
            val nextColor = color(paragraph.index)
            if (nextColor != previousColor) {
                // Equal neighboring colors form one interval. Long dim stretches do not need a
                // shader stop per paragraph; ramps still occupy only inter-paragraph whitespace.
                add((previous.bottom / textHeight).coerceIn(0f, 1f) to previousColor)
                add((paragraph.top / textHeight).coerceIn(0f, 1f) to nextColor)
            }
            previous = paragraph
            previousColor = nextColor
        }
        add(1f to previousColor)
    }
    renderProbe?.observeGradientStops(stops.size)
    if (stops.size == 2) return SolidColor(firstColor)
    return Brush.verticalGradient(*stops.toTypedArray(), startY = 0f, endY = textHeight)
}

/** Plain geometry storage: scrolling publishes state only when the candidate paragraph changes. */
internal class ParagraphViewportTracker {
    private var top = 0f
    private var bottom = 0f
    private var pinned: TappedParagraph? = null

    fun update(top: Float, bottom: Float) { this.top = top; this.bottom = bottom }
    fun pin(index: Int) { pinned = TappedParagraph(index, top) }
    fun unpin() { pinned = null }

    fun choose(bounds: List<ReadingParagraphBounds>, previous: Int?, hysteresis: Float, tapRelease: Float): Int? {
        val valid = top.isFinite() && bottom.isFinite() && bottom > top
        pinned?.let {
            if (valid && abs(top - it.viewportTop) <= tapRelease) return it.index
            pinned = null
        }
        if (!valid || bounds.isEmpty()) return previous
        val anchor = top + (bottom - top) * .42f
        val margin = hysteresis.coerceAtLeast(0f)
        var nearest: Int? = null
        var nearestDistance = Float.POSITIVE_INFINITY
        var nearestCenter = Float.POSITIVE_INFINITY
        // Same ordering and hysteresis as ParagraphFocus.choose, without allocating a filtered
        // paragraph list and comparison lambdas for every scrolling placement callback.
        for (paragraph in bounds) {
            if (!paragraph.top.isFinite() || !paragraph.bottom.isFinite() || paragraph.bottom <= paragraph.top ||
                paragraph.bottom <= top || paragraph.top >= bottom) continue
            if (paragraph.index == previous && anchor >= paragraph.top - margin && anchor <= paragraph.bottom + margin) return previous
            val distance = max(max(paragraph.top - anchor, anchor - paragraph.bottom), 0f)
            val center = abs((max(paragraph.top, top) + min(paragraph.bottom, bottom)) / 2f - anchor)
            if (distance < nearestDistance || distance == nearestDistance &&
                (center < nearestCenter || center == nearestCenter && (nearest == null || paragraph.index < nearest))) {
                nearest = paragraph.index
                nearestDistance = distance
                nearestCenter = center
            }
        }
        return nearest ?: previous
    }
}

/** Draw-only animation state. No animation value is read during composition or measurement. */
private class ParagraphPaintTransition(initial: List<Boolean>) {
    val progress = Animatable(1f)
    var version = 0
        private set
    private var from = FloatArray(initial.size) { if (initial[it]) 1f else 0f }
    private var to = from.copyOf()

    suspend fun retarget(targets: List<Boolean>) {
        if (targets.indices.all { to[it] == if (targets[it]) 1f else 0f }) return
        val nextFrom = FloatArray(to.size)
        copyWeights(nextFrom, progress.value)
        from = nextFrom
        to = FloatArray(targets.size) { if (targets[it]) 1f else 0f }
        version++
        progress.snapTo(0f)
        progress.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
    }

    fun copyWeights(destination: FloatArray, fraction: Float) {
        val amount = fraction.coerceIn(0f, 1f)
        for (index in destination.indices) destination[index] = from[index] + (to[index] - from[index]) * amount
    }
}
