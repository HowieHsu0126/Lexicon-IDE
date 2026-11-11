@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.lexicon.domain.*
import dev.lexicon.domain.Card as LearningCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.mapNotNull
import androidx.browser.customtabs.CustomTabsIntent
import android.net.Uri
import java.util.UUID

internal val ReaderViewportParagraphKey = SemanticsPropertyKey<Int>("ReaderViewportParagraph")
internal var SemanticsPropertyReceiver.readerViewportParagraph by ReaderViewportParagraphKey
internal val ReaderPositionReadyKey = SemanticsPropertyKey<Boolean>("ReaderPositionReady")
internal var SemanticsPropertyReceiver.readerPositionReady by ReaderPositionReadyKey
internal val ReaderNavigationFocusPhaseKey = SemanticsPropertyKey<String>("ReaderNavigationFocusPhase")
internal var SemanticsPropertyReceiver.readerNavigationFocusPhase by ReaderNavigationFocusPhaseKey
internal val ReaderReflowPendingKey = SemanticsPropertyKey<Boolean>("ReaderReflowPending")
internal var SemanticsPropertyReceiver.readerReflowPending by ReaderReflowPendingKey
internal data class ReaderViewportPosition(val entryKey: String?, val bodyHash: String?, val paragraphIndex: Int?)

@Composable
internal fun ZenReader(
    article: Article, body: ArticleBody?, loading: Boolean, settings: AppSettings,
    aiAllowed: Boolean, captureVisible: Boolean, onBack: () -> Unit, onCapture: (Capture) -> Unit,
    onRetry: () -> Unit, onOpenOriginal: () -> Unit, onToggleSerif: () -> Unit,
    onEvent: (String, Int) -> Unit,
    display: ReaderDisplay = ReaderDisplay(), onTools: () -> Unit = {}, onMode: () -> Unit = {},
    onFocusedParagraph: (Int) -> Unit = {},
    onSkimWordTap: ((Int, Int) -> Boolean)? = null,
    onRestoreComplete: (String) -> Unit = {},
    onViewportPosition: (ReaderViewportPosition) -> Unit = {},
) {
    val renderProbe = LocalReaderRenderProbe.current
    if (renderProbe != null) SideEffect { renderProbe.record(ReaderRenderEvent.READER_COMPOSITION) }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var entered by remember(article.id) { mutableStateOf(false) }
    LaunchedEffect(article.id, body?.contentHash) { entered = body != null }
    var chrome by rememberSaveable(article.id) { mutableStateOf(false) }
    var bounds by remember(article.id, body?.contentHash) { mutableStateOf(emptyList<ReadingParagraphBounds>()) }
    var anchorTop by remember(article.id, body?.contentHash, display.jumpStart, display.jumpEnd) { mutableStateOf<Float?>(null) }
    var positionLine by remember(article.id, body?.contentHash, display.jumpStart, display.jumpEnd) { mutableStateOf<ReaderFocusLine?>(null) }
    val anchorRange = remember(body?.contentHash, display.jumpBodyHash, display.jumpStart, display.jumpEnd) {
        val start = display.jumpStart; val end = display.jumpEnd
        if (body != null && display.jumpBodyHash == body.contentHash && start != null && end != null && start >= 0 && end > start && end <= body.text.length)
            TextRange(start, end) else null
    }
    var textTop by remember(article.id, body?.contentHash) { mutableFloatStateOf(0f) }
    var textPositioned by remember(article.id, body?.contentHash) { mutableStateOf(false) }
    var viewportTop by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val reflowSource = remember(article.id, body?.contentHash, display.checkpointKey) {
        body?.let { ReaderReflowSource(article.id, it.contentHash, display.checkpointKey) }
    }
    val reflow = remember(reflowSource) { reflowSource?.let(::ReaderReflowController) }
    var reflowState by remember(reflow) { mutableStateOf(ReaderReflowState()) }
    val reflowPending = reflowState.request != null
    val cancelReflowNow by rememberUpdatedState({ reflowState = reflow?.cancel() ?: ReaderReflowState() })
    var scrollBodyHash by rememberSaveable(article.id) { mutableStateOf<String?>(body?.contentHash) }
    var appliedJump by rememberSaveable(article.id) { mutableStateOf<String?>(null) }
    var appliedRestore by rememberSaveable(article.id) { mutableStateOf<String?>(null) }
    var jumpInProgress by remember(article.id) { mutableStateOf(false) }
    var navigationFocus by rememberSaveable(article.id, stateSaver = ReaderNavigationFocusSaver) { mutableStateOf<ReaderNavigationFocus?>(null) }
    val navigationRequest = remember(article.id, body?.contentHash, display.jumpVersion, display.jumpParagraph,
        display.jumpBodyHash, display.jumpStart, display.jumpEnd) { ReaderNavigationRequest.from(article.id, body, display) }
    val activeNavigation = navigationFocus?.takeIf { it.request == navigationRequest }
    val pinnedRequest = activeNavigation?.takeIf { it.phase == ReaderNavigationPhase.PINNED }?.request
    var resolvedFocus by remember(article.id, body?.contentHash) { mutableIntStateOf(-1) }
    val focusedParagraphNow by rememberUpdatedState(onFocusedParagraph)
    val readerStyle = readerTextStyle(settings.reader)
    val density = LocalDensity.current
    val releaseDistance = with(density) { 18.dp.toPx() }
    val movement = remember(navigationRequest, activeNavigation?.phase, releaseDistance) { ReaderNavigationMovement(releaseDistance) }
    val movementNow by rememberUpdatedState(movement)
    fun releaseNavigation() {
        navigationFocus?.takeIf { it.request == navigationRequest && it.phase != ReaderNavigationPhase.RELEASED }?.let {
            navigationFocus = it.copy(phase = ReaderNavigationPhase.RELEASED)
        }
    }
    // A local function reference compares by signature, not its captured request. Use a lambda
    // so rememberUpdatedState replaces the initial no-request callback when a jump arrives.
    val releaseNavigationNow by rememberUpdatedState({ releaseNavigation() })
    val navigationScroll = remember(article.id) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y != 0f) cancelReflowNow()
            return Offset.Zero
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && movementNow.userScroll(consumed.y)) releaseNavigationNow()
            return Offset.Zero
        }
    } }
    fun navigationLine(request: ReaderNavigationRequest): ReaderFocusLine? {
        if (!textPositioned || scrollBodyHash != request.bodyHash || viewportHeight <= 0 || scroll.maxValue == Int.MAX_VALUE) return null
        val paragraph = bounds.firstOrNull { it.index == request.paragraphIndex } ?: return null
        return if (request.start != null) positionLine else ReaderFocusLine(paragraph.top, paragraph.top + 1f)
    }
    val navigationPinVisible by remember(pinnedRequest) { derivedStateOf {
        pinnedRequest?.let { request -> navigationLine(request)?.let { line ->
            val top = viewportTop - textTop + scroll.value
            line.top >= top - 1f && line.bottom <= top + viewportHeight + 1f
        } } == true
    } }
    LaunchedEffect(article.id, body?.contentHash, navigationRequest) {
        if (body == null) return@LaunchedEffect // A same-body loading gap must keep the consumed decision.
        if (navigationRequest == null) navigationFocus = null
        else if (navigationFocus?.request != navigationRequest) navigationFocus = ReaderNavigationFocus(navigationRequest,
            if (appliedJump == navigationRequest.jumpId) ReaderNavigationPhase.RELEASED else ReaderNavigationPhase.PENDING)
    }
    var viewportParagraph by remember(article.id, body?.contentHash, display.checkpointKey) { mutableStateOf<Int?>(null) }
    val restoreCompleteNow by rememberUpdatedState(onRestoreComplete)
    val viewportPositionNow by rememberUpdatedState(onViewportPosition)
    val waitingForPosition = display.restoreWaiting || (display.restoreToken != null && appliedRestore != display.restoreToken)
    val reflowAllowed = body != null && !waitingForPosition && !jumpInProgress &&
        (navigationRequest == null || appliedJump == navigationRequest.jumpId) &&
        (activeNavigation == null || activeNavigation.phase == ReaderNavigationPhase.RELEASED)
    val reflowAllowedNow by rememberUpdatedState(reflowAllowed)
    LaunchedEffect(reflow, reflowAllowed) {
        if (!reflowAllowed) reflowState = reflow?.cancel(retainPlacement = true) ?: ReaderReflowState()
    }
    LaunchedEffect(reflow, reflowState.request?.serial) {
        val controller = reflow ?: return@LaunchedEffect
        val serial = reflowState.request?.serial ?: return@LaunchedEffect
        try {
            val target = snapshotFlow { reflowState.readyRevision to scroll.maxValue }.mapNotNull { (ready, maximum) ->
                if (ready == null) null
                else controller.beginApply(serial, scroll.value, maximum, reflowAllowedNow && !scroll.isScrollInProgress)
                    .also { reflowState = controller.state }
            }.first()
            // Reflow is a one-shot placement correction, never an animation through unrelated text.
            scroll.scrollTo(target.scroll)
            reflowState = controller.scrollFinished(serial, scroll.value)
        } catch (cancelled: CancellationException) {
            reflowState = controller.cancel(serial)
            throw cancelled
        }
    }
    val bodyAlpha by animateFloatAsState(if (entered && !waitingForPosition) 1f else 0f, tween(220), label = "reader_arrival")
    LaunchedEffect(article.id, body?.contentHash) {
        body?.let {
            // A null loading frame must not erase the restored position of this same snapshot.
            if (scrollBodyHash != null && scrollBodyHash != it.contentHash) scroll.scrollTo(0)
            scrollBodyHash = it.contentHash
        }
    }
    val edgeThreshold = with(LocalDensity.current) { 110.dp.toPx() }
    val showEdgeHandle by remember(scroll, edgeThreshold) { derivedStateOf { scroll.value > edgeThreshold } }
    LaunchedEffect(body?.contentHash, display.restoreToken, display.restoreWaiting) {
        val token = display.restoreToken ?: return@LaunchedEffect
        if (body == null || display.restoreWaiting) return@LaunchedEffect
        if (appliedRestore == token) { restoreCompleteNow(token); return@LaunchedEffect }
        val target = snapshotFlow {
            if (!textPositioned || scrollBodyHash != body.contentHash || viewportHeight <= 0 || scroll.maxValue == Int.MAX_VALUE) null
            else if (display.restoreParagraph == null) 0
            else bounds.find { it.index == display.restoreParagraph }?.let {
                // Match the actual reading line so restoring a short paragraph cannot immediately save its neighbour.
                (textTop - viewportTop + it.top - viewportHeight * .42f + 1f).toInt().coerceIn(0, scroll.maxValue)
            }
        }.filterNotNull().first()
        scroll.scrollTo(target)
        appliedRestore = token
        withFrameNanos { }
        restoreCompleteNow(token)
    }
    LaunchedEffect(body?.contentHash, navigationRequest, anchorRange,
        display.restoreWaiting, display.restoreToken, appliedRestore) {
        val request = navigationRequest ?: return@LaunchedEffect
        if (body == null || display.restoreWaiting || (display.restoreToken != null && appliedRestore != display.restoreToken)) return@LaunchedEffect
        if (appliedJump == request.jumpId) {
            if (navigationFocus?.request == request && navigationFocus?.phase == ReaderNavigationPhase.PENDING)
                navigationFocus = ReaderNavigationFocus(request, ReaderNavigationPhase.PINNED)
            return@LaunchedEffect
        }
        // Bounds and the text's position arrive in separate layout callbacks. Wait for both, then
        // consume this request once so later relayouts or sheet dismissals cannot pull the reader back.
        val target = snapshotFlow {
            bounds.find { it.index == request.paragraphIndex }?.takeIf { textPositioned && scrollBodyHash == body.contentHash && viewportHeight > 0 && scroll.maxValue != Int.MAX_VALUE && (anchorRange == null || anchorTop != null) }?.let {
                (textTop - viewportTop + (anchorTop ?: it.top) - viewportHeight * .22f).toInt().coerceIn(0, scroll.maxValue)
            }
        }.filterNotNull().first()
        jumpInProgress = true
        try {
            appliedJump = request.jumpId
            scroll.animateScrollTo(target)
            if (navigationFocus?.request == request && navigationFocus?.phase != ReaderNavigationPhase.RELEASED)
                navigationFocus = ReaderNavigationFocus(request, ReaderNavigationPhase.PINNED)
        } catch (cancelled: CancellationException) {
            if (navigationFocus?.request == request && navigationFocus?.phase == ReaderNavigationPhase.PENDING)
                navigationFocus = ReaderNavigationFocus(request, ReaderNavigationPhase.RELEASED)
            throw cancelled
        }
        finally { jumpInProgress = false }
    }
    // A font/window relayout can move a still-owned target. Reposition only a pinned request;
    // released requests are never reactivated and user scroll frames are not layout keys.
    LaunchedEffect(pinnedRequest, bounds, positionLine, viewportHeight, viewportTop, textTop, readerStyle) {
        val request = pinnedRequest ?: return@LaunchedEffect
        if (jumpInProgress || display.restoreWaiting) return@LaunchedEffect
        val line = navigationLine(request) ?: return@LaunchedEffect
        val top = viewportTop - textTop + scroll.value
        if (line.top < top - 1f || line.bottom > top + viewportHeight + 1f) {
            jumpInProgress = true
            try {
                scroll.scrollTo((textTop - viewportTop + line.top - viewportHeight * .22f).toInt().coerceIn(0, scroll.maxValue))
                withFrameNanos { }
            } finally { jumpInProgress = false }
        }
    }
    LaunchedEffect(pinnedRequest, bounds, viewportHeight, readerStyle, density.density, density.fontScale) {
        if (pinnedRequest == null) return@LaunchedEffect
        movement.observeScroll(scroll.value, moving = false, owned = true)
        snapshotFlow { scroll.value to scroll.isScrollInProgress }.collect { (value, moving) ->
            if (movement.observeScroll(value, moving, jumpInProgress || waitingForPosition)) releaseNavigationNow()
        }
    }
    LaunchedEffect(activeNavigation, navigationPinVisible, jumpInProgress, reflowPending) {
        if (reflowPending || jumpInProgress || activeNavigation?.phase == ReaderNavigationPhase.PENDING || (pinnedRequest != null && !navigationPinVisible))
            focusedParagraphNow(-1) // Never attribute a layout/navigation wait to an invisible paragraph.
        else if (pinnedRequest != null) focusedParagraphNow(pinnedRequest.paragraphIndex)
        else if (resolvedFocus >= 0) focusedParagraphNow(resolvedFocus)
    }
    LaunchedEffect(body?.contentHash, display.checkpointKey, display.restoreToken, display.restoreWaiting, appliedRestore,
        navigationRequest, activeNavigation, navigationPinVisible, appliedJump, jumpInProgress, reflowPending, captureVisible, display.aiLevel) {
        snapshotFlow {
            val pendingJump = navigationRequest != null && appliedJump != navigationRequest.jumpId
            val index = if (body == null || display.aiLevel != null || waitingForPosition || pendingJump || jumpInProgress || reflowPending ||
                (pinnedRequest != null && !navigationPinVisible) ||
                captureVisible || scroll.isScrollInProgress || !textPositioned || scrollBodyHash != body.contentHash ||
                viewportHeight <= 0 || scroll.maxValue == Int.MAX_VALUE) null
            else {
                val top = viewportTop - textTop + scroll.value
                val viewport = ReadingViewport(top, top + viewportHeight)
                val chosen = pinnedRequest?.paragraphIndex ?: resolvedFocus
                chosen.takeIf { candidate -> bounds.any { it.index == candidate && it.bottom > viewport.top && it.top < viewport.bottom } }
            }
            ReaderViewportPosition(display.checkpointKey, body?.contentHash, index)
        }.collect { position -> viewportParagraph = position.paragraphIndex; viewportPositionNow(position) }
    }
    val readerInk = readerStyle.color
    val readerAccent = if (readerBackground(settings.reader).luminance() < .5f) Color(0xFFCFB0FF) else MaterialTheme.colorScheme.primary
    val highContrast = settings.reader.comfort == ReadingComfort.HIGH_CONTRAST
    val sessionStart = remember(article.id) { System.currentTimeMillis() }
    var lastCaptureAt by remember(article.id) { mutableLongStateOf(sessionStart) }
    val currentOnEvent by rememberUpdatedState(onEvent)
    DisposableEffect(article.id) {
        val window = context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        currentOnEvent("zen_opened", 1)
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            currentOnEvent("zen_duration_seconds", ((System.currentTimeMillis() - sessionStart) / 1_000).toInt().coerceAtLeast(0))
        }
    }
    LaunchedEffect(chrome) { if (chrome) { delay(3_000); chrome = false } }
    Box(Modifier.fillMaxSize().background(readerBackground(settings.reader)).windowInsetsPadding(WindowInsets.displayCutout)
        .nestedScroll(navigationScroll)
        .testTag("zen_reader_viewport").onSizeChanged { viewportHeight = it.height }
        .semantics {
            readerViewportParagraph = viewportParagraph ?: -1; readerPositionReady = viewportParagraph != null && !waitingForPosition && !reflowPending
            readerNavigationFocusPhase = activeNavigation?.phase?.name?.lowercase() ?: "none"
            readerReflowPending = reflowPending
        }
        .onGloballyPositioned { viewportTop = it.positionInRoot().y }) {
        if (body != null) {
            // One read-only field preserves cross-paragraph selection and exact UTF-16 offsets.
            // Save only offsets, never the full article in the Activity saved-state Bundle.
            var savedStart by rememberSaveable(article.id, body.contentHash) { mutableIntStateOf(0) }
            var savedEnd by rememberSaveable(article.id, body.contentHash) { mutableIntStateOf(0) }
            val textState = remember(article.id, body.contentHash) {
                TextFieldState(initialText = body.text, initialSelection = TextRange(
                    savedStart.coerceIn(0, body.text.length), savedEnd.coerceIn(0, body.text.length),
                ))
            }
            LaunchedEffect(textState) {
                snapshotFlow { textState.selection }.collect {
                    savedStart = it.start; savedEnd = it.end
                }
            }
            val captureSelection = {
                val selection = textState.selection
                if (!selection.collapsed) {
                    runCatching { TextCapture.extract(body.text, selection.start, selection.end) }.getOrNull()?.let { snapshot ->
                        val cap = captureAt(article, body, snapshot.start, snapshot.end, aiAllowed, display.aiLevel != null)
                        lastCaptureAt = System.currentTimeMillis()
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onCapture(cap)
                    }
                }
            }
            Column(Modifier.fillMaxSize().graphicsLayer { alpha = if (waitingForPosition) 0f else bodyAlpha; translationY = if (waitingForPosition) 0f else (1f - bodyAlpha) * 10.dp.toPx() }
                .verticalScroll(scroll).padding(horizontal = 26.dp, vertical = 42.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(article.sourceName, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium, color = readerInk.copy(alpha = .8f))
                    TextButton(onClick = onTools, colors = ButtonDefaults.textButtonColors(contentColor = readerAccent)) { Text(if (display.aiLevel != null) "AI · ${display.aiLevel}" else if (display.mode == StudyMode.SKIM) "略读 · 工具" else "精读 · 工具") }
                }
                Text(article.title, color = readerInk, style = MaterialTheme.typography.headlineMedium.copy(fontFamily = ReadingSerif, fontSize = 30.sp, lineHeight = 38.sp))
                Text(listOf(article.author, dateLabel(article.publishedAt)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = readerInk.copy(alpha = .75f))
                HorizontalDivider(modifier = Modifier.width(42.dp), thickness = 2.dp, color = readerAccent.copy(alpha = .6f))
                FocusedArticleText(
                    state = textState, captureVisible = captureVisible,
                    highlights = display.highlights, focusEnabled = display.mode == StudyMode.INTENSIVE && !display.quiet && !highContrast,
                    emphasizedParagraphs = if (display.quiet || highContrast || display.mode != StudyMode.SKIM) emptySet() else display.emphasized,
                    onParagraphBounds = { bounds = it }, onFocusedParagraph = { index ->
                        resolvedFocus = index
                        if (!reflowPending && !jumpInProgress && activeNavigation?.phase != ReaderNavigationPhase.PENDING &&
                            (pinnedRequest == null || navigationPinVisible)) focusedParagraphNow(index)
                    },
                    activeRange = anchorRange.takeUnless { display.quiet }, onActiveRangeTop = { anchorTop = it },
                    navigationFocus = pinnedRequest.takeIf { navigationPinVisible },
                    viewportInRoot = { ReadingViewport(viewportTop, viewportTop + viewportHeight).takeIf { it.valid } },
                    positionRange = anchorRange, onPositionLine = { positionLine = it }, onParagraphTap = {
                        releaseNavigationNow()
                        reflowState = reflow?.cancel(retainPlacement = true) ?: ReaderReflowState()
                    },
                    reflowSource = reflowSource,
                    onReflowLayout = { measured ->
                        reflow?.let { controller ->
                            reflowState = controller.onLayout(measured, scroll.value, !scroll.isScrollInProgress, reflowAllowedNow)
                        }
                    },
                    onReflowPositioned = { measured, viewport ->
                        reflow?.let { controller ->
                            reflowState = controller.onPositioned(measured, viewport, scroll.value, reflowAllowedNow)
                        }
                    },
                    onTapWord = if (display.quiet) null else { start, end ->
                        if (end > start && !(display.mode == StudyMode.SKIM && onSkimWordTap?.invoke(start, end) == true)) {
                            lastCaptureAt = System.currentTimeMillis(); onCapture(captureAt(article, body, start, end, aiAllowed, display.aiLevel != null))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                        .onGloballyPositioned { textTop = it.positionInRoot().y + scroll.value; textPositioned = true }
                        .appendTextContextMenuComponents {
                            separator()
                            item(key = "lexicon.lookup", label = "查词 / 捕获") { captureSelection(); close() }
                            separator()
                        }
,
                    textStyle = readerStyle,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(body.license.ifBlank { article.license }, style = MaterialTheme.typography.bodySmall, color = readerInk.copy(alpha = .75f))
                TextButton(onClick = onOpenOriginal, colors = ButtonDefaults.textButtonColors(contentColor = readerAccent)) { Text("页面视图") }
                TextButton(onClick = onBack, colors = ButtonDefaults.textButtonColors(contentColor = readerAccent)) { Text("← 退出 Zen") }
                Spacer(Modifier.height(48.dp))
            }
        } else if (loading) Column(Modifier.align(Alignment.Center).padding(32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("加载中…", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onBack) { Text("返回列表") }
        } else {
            InAppArticlePage(article, aiAllowed, onBack, onCapture)
        }
        AnimatedVisibility(body != null && chrome && !captureVisible,
            enter = fadeIn(tween(180)) + slideInVertically(tween(240, easing = FastOutSlowInEasing)) { -it / 3 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { -it / 3 },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp)) {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .97f), shadowElevation = 3.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = RoundedCornerShape(18.dp)) {
                ReaderToolbarActions(display.mode, onBack, onMode, onTools, onToggleSerif)
            }
        }
        // The edge affordance must not intercept the header's own tools button after a short scroll.
        if (body != null && !chrome && !captureVisible && showEdgeHandle) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(24.dp).clickable { chrome = true })
        }
        if (settings.focusTimer) ReaderFocusTimer(sessionStart, lastCaptureAt, !chrome && !captureVisible)
    }
}

/** Keep once-per-second clock state below the article's composition boundary. */
@Composable
private fun BoxScope.ReaderFocusTimer(sessionStart: Long, lastCaptureAt: Long, visible: Boolean) {
    val renderProbe = LocalReaderRenderProbe.current
    if (renderProbe != null) SideEffect { renderProbe.record(ReaderRenderEvent.TIMER_COMPOSITION) }
    var elapsed by remember(sessionStart) { mutableLongStateOf(0) }
    var idle by remember(sessionStart) { mutableLongStateOf(0) }
    LaunchedEffect(lastCaptureAt) {
        while (true) {
            renderProbe?.record(ReaderRenderEvent.TIMER_TICK)
            val now = System.currentTimeMillis()
            elapsed = (now - sessionStart) / 1000
            idle = (now - lastCaptureAt) / 1000
            delay(1_000)
        }
    }
    if (idle >= 90 && visible) Text(
        "${elapsed / 60}:${(elapsed % 60).toString().padStart(2, '0')}",
        Modifier.align(Alignment.TopEnd).padding(12.dp), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun CapturePanel(
    capture: Capture, articles: List<Article>, sources: List<Source>, vm: LexiconViewModel,
    onDismiss: () -> Unit, onCardSaved: (LearningCard) -> Unit, onSettings: () -> Unit,
    assist: StudyAssist? = null, assistBusy: Boolean = false, assistError: String? = null, assistRequest: StudyRequest? = null, noteBusy: Boolean = false,
    onAssist: ((StudyRequest) -> Unit)? = null, onNote: ((ReadingNote, () -> Unit) -> Unit)? = null,
    backgroundRepository: BackgroundRepository? = null, captureBody: ArticleBody? = null,
    onOpenExistingCard: ((LearningCard) -> Unit)? = null,
    retained: Boolean = false,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var targetField by rememberSaveable(capture.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(capture.target)) }
    var sentenceField by rememberSaveable(capture.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(capture.sentence)) }
    val target = targetField.text
    val sentence = sentenceField.text
    var associatedId by rememberSaveable(capture.id) { mutableStateOf(capture.articleId) }
    var chooseSource by rememberSaveable(capture.id) { mutableStateOf(false) }
    var analysis by remember(capture.id) { mutableStateOf<AnalysisResult?>(null) }
    var showDictionary by rememberSaveable(capture.id) { mutableStateOf(false) }
    var dictionaryTargetDraft by rememberSaveable(capture.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(capture.target)) }
    var dictionarySentenceDraft by rememberSaveable(capture.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(capture.sentence)) }
    val dictionaryPageState = key(capture.id) { rememberSaveableStateHolder() }
    var showOriginalSelection by rememberSaveable(capture.id) { mutableStateOf(false) }
    var analyzedCapture by remember(capture.id) { mutableStateOf<Capture?>(null) }
    var showSavedAnalysis by rememberSaveable(capture.id) { mutableStateOf(false) }
    var noteEditor by rememberSaveable(capture.id) { mutableStateOf(false) }
    var showAssist by rememberSaveable(capture.id) { mutableStateOf(false) }
    var showBackground by rememberSaveable(capture.id) { mutableStateOf(false) }
    val backgroundVm = backgroundResearchViewModel(backgroundRepository, "capture:${capture.id}")
    val captureScroll = key(capture.id) { rememberScrollState() }
    val backgroundScroll = key(capture.id) { rememberScrollState() }
    val sentenceLocation = remember(capture.id) { BringIntoViewRequester() }
    var returnToSentence by rememberSaveable(capture.id) { mutableStateOf(false) }
    var innerBackProgress by remember { mutableFloatStateOf(0f) }
    val visibleBackProgress by animateFloatAsState(innerBackProgress, tween(100), label = "capture_inner_back")
    val currentSettings by vm.settings.collectAsState()
    var localCues by rememberSaveable(capture.id) { mutableStateOf(false) }
    var requestedAssist by remember(capture.id) { mutableStateOf<StudyRequest?>(null) }
    var speaking by remember { mutableStateOf<android.speech.tts.TextToSpeech?>(null) }
    DisposableEffect(Unit) { onDispose { speaking?.stop(); speaking?.shutdown() } }
    val article = articles.find { it.id == associatedId }
    val allowAi = article?.let { a -> sources.filter { it.id in a.sourceIds }.any { it.aiAllowed } } ?: capture.aiAllowed
    val busy = vm.busy != null
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { vm.busy == null || it != SheetValue.Hidden })
    fun leave(action: () -> Unit = onDismiss) {
        vm.closeDictionaryLookup(capture.id)
        scope.launch { try { sheetState.hide() } finally { action() } }
    }
    fun returnToCapture(atSentence: Boolean = false) {
        if (showDictionary) {
            targetField = dictionaryTargetDraft
            sentenceField = dictionarySentenceDraft
        }
        showDictionary = false; showBackground = false; returnToSentence = atSentence
    }
    LaunchedEffect(showDictionary, showBackground, returnToSentence) {
        if (returnToSentence && !showDictionary && !showBackground) {
            withFrameNanos { }
            sentenceLocation.bringIntoView()
            returnToSentence = false
        }
    }
    val source = if (associatedId == capture.articleId) capture.sourceRef
        else article?.let { SourceRef(it.title, it.sourceName, it.url, it.publishedAt) } ?: SourceRef()
    fun snapshot(): Capture = capture.copy(
        target = target.trim(), selectedText = capture.selectedText.ifBlank { target.trim() },
        sentence = sentence.trim(),
        contextOrigin = when {
            sentence.isBlank() -> ContextOrigin.MISSING
            sentence.trim() == capture.sentence && capture.contextOrigin == ContextOrigin.ORIGINAL -> ContextOrigin.ORIGINAL
            sentence.trim() == capture.sentence && capture.contextOrigin == ContextOrigin.AI_GENERATED -> ContextOrigin.AI_GENERATED
            else -> ContextOrigin.USER_SUPPLIED
        },
        articleId = associatedId, sourceRef = source, aiAllowed = allowAi,
        updatedAt = System.currentTimeMillis(),
    )
    DisposableEffect(capture.id, vm) {
        vm.openDictionaryLookup(capture.id, target)
        onDispose { vm.closeDictionaryLookup(capture.id) }
    }
    val requestedDictionaryLookup = vm.dictionaryLookupFor(capture.id, target)
    // Startup metadata may arrive after the first local lookup. Keep the saved scope while waiting,
    // but do not present those entries under an unknown or previously selected dictionary.
    var rememberedDictionaryId by rememberSaveable(capture.id) { mutableStateOf<String?>(null) }
    val resolvedDictionaryId = vm.dictionaryStatus.id.takeUnless { vm.dictionaryInitializing }
    val dictionaryScopeId = resolvedDictionaryId ?: rememberedDictionaryId.orEmpty()
    SideEffect { if (resolvedDictionaryId != null) rememberedDictionaryId = resolvedDictionaryId }
    val dictionaryLookup = requestedDictionaryLookup.takeUnless { vm.dictionaryInitializing }
    val dictionaryLoading = vm.dictionaryInitializing || requestedDictionaryLookup == null || requestedDictionaryLookup.loading
    val dictionaryEntries = dictionaryLookup?.entries?.takeIf { vm.dictionaryStatus.ready }.orEmpty()
    val dictionaryIdentity = remember(capture.id, target.trim(), dictionaryScopeId) {
        "${capture.id}:${fingerprint(target.trim() + "\u0000" + dictionaryScopeId)}"
    }
    val dictionaryProbe = if (dictionaryEntries.isEmpty()) null else LocalDictionaryRenderProbe.current
        ?.bindResult(capture.id, target.trim(), vm.dictionaryStatus.id, dictionaryEntries)
    LaunchedEffect(target, sentence, associatedId) {
        showAssist = false; requestedAssist = null
        if (analyzedCapture?.let { it.target != target.trim() || it.sentence != sentence.trim() || it.articleId != associatedId } == true) {
            analysis = null; analyzedCapture = null
        }
    }
    val currentInput = snapshot().copy(updatedAt = 0)
    LaunchedEffect(currentInput, busy) { if (!busy) vm.inspectAvailableAnalysis(currentInput) }
    DisposableEffect(capture.id) { onDispose { vm.closeAnalysisProbe() } }
    val available = vm.availableAnalysisFor(currentInput)
    val savedAnalysis = available as? CaptureAnalysisAvailability.Ready
    val existingCard = available as? CaptureAnalysisAvailability.ExistingCard
    val inputValid = ContentValidator.validateCapture(currentInput).isEmpty()
    val currentAnalysis = analysis?.takeIf { analyzedCapture?.copy(updatedAt = 0) == currentInput }
    val canSave = target.isNotBlank() || capture.selectedText.isNotBlank()
    val conflict = available is CaptureAnalysisAvailability.Restricted
    val canAnalyze = inputValid && allowAi && vm.hasKey && !busy && available != null && !conflict && existingCard == null
    val canSaveCard = inputValid && allowAi && !busy && !conflict && existingCard == null &&
        (savedAnalysis != null || currentAnalysis != null || canAnalyze)
    ModalBottomSheet(
        onDismissRequest = { if (!busy) { vm.closeDictionaryLookup(capture.id); onDismiss() } },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        // This handler belongs to the sheet's own dialog. The outer Activity cannot consume its back events.
        PredictiveBackHandler(enabled = (showDictionary || showBackground) && !noteEditor) { events ->
            try {
                events.collect { innerBackProgress = it.progress }
                returnToCapture()
            } catch (_: CancellationException) { /* A cancelled gesture keeps the current inner page. */ }
            finally { innerBackProgress = 0f }
        }
        Column(Modifier.fillMaxWidth().fillMaxHeight(.95f).testTag("capture_panel").imePadding().graphicsLayer {
            translationX = size.width * visibleBackProgress * .04f
            alpha = 1f - visibleBackProgress * .08f
        }) {
            if (!showDictionary) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().testTag("capture_header").padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        if (showBackground) {
                            TextButton(onClick = { focusManager.clearFocus(); keyboard?.hide(); returnToCapture() },
                                modifier = Modifier.testTag("background_back_to_capture")) { Text("返回查词") }
                            TextButton(enabled = !busy, onClick = { leave() }, modifier = Modifier.testTag("background_continue_reading")) { Text("继续原文") }
                        } else {
                            Text("查词", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            TextButton(enabled = !busy, onClick = { leave() }, modifier = Modifier.testTag("capture_close")) { Text("关闭") }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!showDictionary && showBackground && backgroundVm != null) {
              Column(Modifier.fillMaxSize().verticalScroll(backgroundScroll).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val identity = "capture:${capture.id}:${fingerprint(target + "\u0000" + sentence + "\u0000" + associatedId + "\u0000" + allowAi)}"
                BackgroundResearchPanel(backgroundVm, identity,
                    BackgroundQuery(target, backgroundCaptureContext(capture, captureBody, sentence), associatedId,
                        capture.bodyHash.takeIf { associatedId == capture.articleId }, source, allowAi),
                    onClose = { leave() }, onBack = { returnToCapture() }, showHeader = false)
                Spacer(Modifier.height(28.dp))
              }
            } else if (!showDictionary) {
            Column(Modifier.fillMaxSize().testTag("capture_body_scroll").verticalScroll(captureScroll).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (vm.busy != null) Text("处理中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            vm.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("[error]")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedTextField(targetField, {
                targetField = boundedCaptureField(it, CaptureLimits.TARGET)
                vm.editDictionaryLookup(capture.id, targetField.text)
            }, Modifier.fillMaxWidth().testTag("capture_target"), label = { Text("目标词 / 短语") }, singleLine = true, enabled = !busy)
            if (capture.selectedText.isNotBlank() && capture.selectedText != target) {
                FieldLabel("最初选中的文本")
                val originalPreview = remember(capture.selectedText) { DictionaryPresentation.preview(capture.selectedText, maxCharacters = 360, maxLines = 3) }
                SelectionContainer { Text(originalPreview.text, Modifier.testTag("capture_original_preview"), maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (dictionaryLoading && target.isNotBlank()) Text("查词中…", Modifier.testTag("capture_dictionary_lookup_loading"), style = MaterialTheme.typography.bodySmall)
            else if (dictionaryLookup?.error != null) {
                Text(dictionaryLookup.error, Modifier.testTag("capture_dictionary_lookup_error"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy, onClick = { vm.retryDictionaryLookup(capture.id, target) }, modifier = Modifier.testTag("capture_dictionary_retry")) { Text("重试查词") }
            }
            else if (dictionaryEntries.isNotEmpty()) {
                Surface(modifier = Modifier.animateContentSize(tween(200)), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(vm.dictionaryStatus.name, Modifier.testTag("capture_dictionary_source"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        dictionaryEntries.take(1).forEachIndexed { index, entry ->
                            val preview = remember(entry.word, entry.definition) { DictionaryEntryPreview.select(entry.word, entry.definition) }
                            Text("${entry.word}  ${entry.phonetic}", Modifier.testTag("capture_dictionary_word_$index")
                                .dictionaryDrawProbe(dictionaryProbe, index, DictionaryTextPart.WORD, false), style = MaterialTheme.typography.titleSmall,
                                onTextLayout = if (dictionaryProbe == null) null else { layout -> dictionaryProbe.onTextLayout(index, DictionaryTextPart.WORD, false,
                                    layout.layoutInput.text.length, layout.lineCount, (0 until layout.lineCount).any(layout::isLineEllipsized)) })
                            Text(preview.text.ifBlank { "无释义" }, Modifier.testTag("capture_dictionary_definition_$index")
                                .dictionaryDrawProbe(dictionaryProbe, index, DictionaryTextPart.DEFINITION, false), style = MaterialTheme.typography.bodyMedium,
                                onTextLayout = if (dictionaryProbe == null) null else { layout -> dictionaryProbe.onTextLayout(index, DictionaryTextPart.DEFINITION, false,
                                    layout.layoutInput.text.length, layout.lineCount, (0 until layout.lineCount).any(layout::isLineEllipsized)) },
                                maxLines = 4, overflow = TextOverflow.Ellipsis)
                            if (entry.tags.isNotBlank()) Text(entry.tags, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                dictionaryTargetDraft = targetField
                                dictionarySentenceDraft = sentenceField
                                focusManager.clearFocus()
                                // The dialog fields can lose focus as their branch leaves composition.
                                // Restore their entry selection only when the capture page returns.
                                keyboard?.hide()
                                showBackground = false; showDictionary = true
                            },
                                modifier = Modifier.testTag("capture_dictionary_toggle"), contentPadding = PaddingValues(0.dp)) { Text("全部释义") }
                            Text("${dictionaryEntries.size} 条匹配", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else if (!vm.dictionaryStatus.ready) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FieldLabel("词典")
                        Text("选择 MDX 词典后可离线查词。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(enabled = target.isNotBlank(), contentPadding = PaddingValues(0.dp), onClick = {
                            val url = Uri.parse("https://www.ldoceonline.com/search/").buildUpon().appendQueryParameter("q", target.trim()).build()
                            runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url) }
                                .onFailure { vm.notify("[error] 无法打开浏览器") }
                        }) { Text("朗文在线查词 ↗") }
                    }
                }
            }
            else if (dictionaryLookup != null && target.isNotBlank()) Text("未找到词条", Modifier.testTag("capture_dictionary_empty"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(sentenceField, { sentenceField = boundedCaptureField(it, CaptureLimits.SENTENCE) },
                Modifier.fillMaxWidth().testTag("capture_sentence").bringIntoViewRequester(sentenceLocation), label = { Text("原句") },
                minLines = 3, maxLines = 7, enabled = !busy,
                supportingText = { Text(if (sentence.isBlank()) "补充原句后可制卡" else if (snapshot().contextOrigin == ContextOrigin.ORIGINAL) "原文引用" else if (snapshot().contextOrigin == ContextOrigin.AI_GENERATED) "AI 改写语境" else "用户补充") })
            FlowRow(Modifier.fillMaxWidth().testTag("capture_assist_actions"), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = {
                    speaking?.shutdown()
                    speaking = android.speech.tts.TextToSpeech(context) { status -> if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                        speaking?.language = java.util.Locale.US
                        speaking?.speak(target.take(200), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "word")
                    } }
                }, enabled = target.isNotBlank()) { Text("发音") }
                TextButton(enabled = sentence.isNotBlank(), onClick = { localCues = !localCues }) { Text("结构线索") }
                if (onAssist != null) listOf("translation" to "译文", "syntax" to "句法").forEach { (kind, label) ->
                    TextButton(enabled = allowAi && sentence.isNotBlank() && !assistBusy, onClick = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        showAssist = true
                        val request = StudyRequest(kind, sentence,
                            context = sentence, title = source.title.orEmpty(), source = source.source.orEmpty(), aiAllowed = allowAi)
                        requestedAssist = request; onAssist(request)
                    }) { Text(label) }
                }
                if (backgroundVm != null) TextButton(enabled = target.isNotBlank(), onClick = {
                    focusManager.clearFocus(); keyboard?.hide(); showDictionary = false; showBackground = true
                }, modifier = Modifier.testTag("capture_background")) { Text("背景") }
                if (onNote != null) TextButton(onClick = { noteEditor = true }) { Text("批注") }
            }
            if (localCues) {
                val cues = SentenceCues.inSentence(sentence)
                Text("离线线索 · 需结合整句判断", style = MaterialTheme.typography.labelSmall)
                if (cues.isEmpty()) Text("先找限定动词，再核对主语、修饰成分与指代。", style = MaterialTheme.typography.bodySmall)
                cues.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (showAssist) {
                if (assistBusy) Text(if (requestedAssist?.kind == "background") "检索背景…" else "分析中…", style = MaterialTheme.typography.bodySmall)
                assistError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                assist?.takeIf { assistRequest == requestedAssist }?.let { value ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            StudyAssistContent(value, retry = requestedAssist?.let { previous -> { onAssist?.invoke(previous); Unit } })
                            TextButton(onClick = { showAssist = false }) { Text("收起") }
                        }
                    }
                }
            }
            FieldLabel("来源")
            Text(source.display(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (capture.bodyHash == null) {
                TextButton(enabled = !busy, onClick = { chooseSource = !chooseSource }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (chooseSource) "收起来源候选" else "手动关联来源（可选）")
                }
                AnimatedVisibility(chooseSource, enter = fadeIn(tween(160)), exit = fadeOut(tween(100))) {
                  Column(Modifier.animateContentSize(tween(200))) {
                    Text("仅当你确认选中文本来自该文章时关联。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { associatedId = null; chooseSource = false }) { Text("保留未关联来源") }
                    articles.sortedByDescending { it.cachedAt }.take(12).forEach { candidate ->
                        TextButton(enabled = !busy, onClick = { associatedId = candidate.id; chooseSource = false }) {
                            Text(candidate.title, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                  }
                }
            }
            if (!allowAi) Text("此来源未开启 AI 分析。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (vm.analysisProbeError != null) {
                Text(vm.analysisProbeError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy, onClick = { vm.inspectAvailableAnalysis(currentInput) }, modifier = Modifier.testTag("retry_saved_analysis")) { Text("重试读取") }
            }
            if (available is CaptureAnalysisAvailability.Conflict) Text("内容已调整，需要重新分析；已有结果仍保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (available is CaptureAnalysisAvailability.Restricted && allowAi) Text("此来源当前不可使用 AI 分析，已有结果仍保留。", style = MaterialTheme.typography.bodySmall)
            existingCard?.let { existing ->
                Text(if (existing.matchesInput) "该捕获已有卡片" else "该捕获已有卡片，请核对其中的词条和语境", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !busy && onOpenExistingCard != null, onClick = {
                    onOpenExistingCard?.let { open -> leave { open(existing.card) } }
                }, modifier = Modifier.testTag("capture_existing_card")) { Text("查看已有卡片") }
            }
            if (!vm.hasKey && allowAi && savedAnalysis == null && currentAnalysis == null && existingCard == null) TextButton(enabled = !busy, onClick = {
                if (canSave) vm.saveCapture(snapshot()) { leave(onSettings) } else leave(onSettings)
            }) { Text("配置 DeepSeek API Key") }
            if (savedAnalysis != null && currentAnalysis == null) TextButton(onClick = { showSavedAnalysis = !showSavedAnalysis }, modifier = Modifier.testTag("preview_saved_analysis")) {
                Text(if (showSavedAnalysis) "收起已有分析" else "查看已有分析")
            }
            val shownAnalysis = currentAnalysis ?: savedAnalysis?.analysis?.takeIf { showSavedAnalysis }
            AnimatedVisibility(shownAnalysis != null, enter = fadeIn(tween(220)), exit = fadeOut(tween(100))) {
              shownAnalysis?.let { result -> Column(Modifier.animateContentSize(tween(220)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HorizontalDivider()
                Text("AI 分析", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AnalysisContent(result.content)
              } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(enabled = !busy && canSave, onClick = { vm.saveCapture(snapshot()) { leave() } }, modifier = Modifier.weight(1f).testTag("capture_save")) {
                    Text(if (retained) "保存修改" else "留待处理")
                }
                OutlinedButton(enabled = canAnalyze, onClick = {
                    val value = snapshot()
                    vm.analyze(value, true) { result -> analysis = result; analyzedCapture = value }
                }, modifier = Modifier.weight(1f)) { Text("深度分析") }
            }
            if (existingCard == null) Button(enabled = canSaveCard, onClick = {
                val value = snapshot()
                val saved: (LearningCard) -> Unit = { card -> leave { onCardSaved(card) } }
                when {
                    savedAnalysis != null -> vm.saveAvailableAnalysis(value, savedAnalysis.receiptId, saved)
                    currentAnalysis != null -> vm.saveAnalysis(value, currentAnalysis, saved)
                    else -> vm.generateCard(value, saved)
                }
            }, modifier = Modifier.fillMaxWidth().testTag("generate_card")) {
                Text(if (savedAnalysis != null || currentAnalysis != null) "保存已有分析" else "生成并保存生词卡")
            }
            if (capture.selectedText.isNotBlank() && capture.selectedText != target) {
                TextButton(onClick = { showOriginalSelection = !showOriginalSelection }, modifier = Modifier.testTag("capture_original_toggle"), contentPadding = PaddingValues(0.dp)) {
                    Text(if (showOriginalSelection) "收起完整选文" else "查看完整选文")
                }
                if (showOriginalSelection) SelectionContainer {
                    Text(capture.selectedText, Modifier.testTag("capture_original_full"), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(28.dp))
            }
            }
            dictionaryPageState.SaveableStateProvider("dictionary") {
            DictionaryDetailContent(
                visible = showDictionary, identity = dictionaryIdentity, entries = dictionaryEntries,
                dictionaryName = vm.dictionaryStatus.name, preferences = currentSettings.reader,
                onBack = { returnToCapture() }, onReturnToSentence = { returnToCapture(atSentence = true) }, onClose = { if (!busy) leave() },
                modifier = Modifier.fillMaxSize(), renderProbe = dictionaryProbe,
                loading = dictionaryLoading, error = dictionaryLookup?.error,
                onRetry = { vm.retryDictionaryLookup(capture.id, target) }, closeEnabled = !busy,
            )
            }
            }
        }
    }
    if (noteEditor && onNote != null) {
        var comment by rememberSaveable(capture.id) { mutableStateOf("") }
        var tags by rememberSaveable(capture.id) { mutableStateOf("") }
        var asNode by rememberSaveable(capture.id) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { noteEditor = false }, title = { Text("笔记") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(capture.selectedText.take(500), style = MaterialTheme.typography.bodySmall)
                assistError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(comment, { comment = it.take(4000) }, label = { Text("批注") }, minLines = 2)
                OutlinedTextField(tags, { tags = it.take(300) }, label = { Text("标签，以逗号分隔") })
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(asNode, { asNode = it }); Text("加入个人思维导图") }
            } },
            confirmButton = { TextButton(enabled = !noteBusy, onClick = {
                val snap = snapshot()
                onNote(ReadingNote(UUID.randomUUID().toString(), snap.articleId, snap.bodyHash, snap.selectedText,
                    comment.trim(), tags.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(12),
                    System.currentTimeMillis(), snap.sourceRef, snap.selectionStart.takeUnless { capture.contextOrigin == ContextOrigin.AI_GENERATED },
                    snap.selectionEnd.takeUnless { capture.contextOrigin == ContextOrigin.AI_GENERATED }, asNode, capture.contextOrigin)) { noteEditor = false }
            }) { Text("保存笔记") } }, dismissButton = { TextButton(onClick = { noteEditor = false }) { Text("取消") } })
    }
}

private fun boundedCaptureField(value: TextFieldValue, maximum: Int): TextFieldValue {
    var end = value.text.length.coerceAtMost(maximum)
    if (end > 0 && end < value.text.length && value.text[end - 1].isHighSurrogate() && value.text[end].isLowSurrogate()) end--
    val text = value.text.substring(0, end)
    fun bounded(range: TextRange) = TextRange(range.start.coerceIn(0, end), range.end.coerceIn(0, end))
    return value.copy(text = text, selection = bounded(value.selection), composition = value.composition?.let(::bounded)?.takeUnless { it.collapsed })
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
