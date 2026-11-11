@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.*
import kotlin.math.roundToInt

/** A reading surface, not a timer owner. The controller owns lifecycle and every recorded action. */
@Composable
internal fun SpeedReadingScreen(
    article: Article,
    body: ArticleBody?,
    settings: AppSettings,
    state: SpeedUiState,
    actions: SpeedUiActions,
    articles: List<Article> = emptyList(),
) {
    var feedback by rememberSaveable(article.id) { mutableStateOf(false) }
    var toolsOpen by rememberSaveable(article.id, state.plan?.planKey) { mutableStateOf(false) }
    val toolsAvailable = state.stage == SpeedStage.READING && state.natural == null && body != null &&
        state.plan?.chunks?.getOrNull(state.chunkIndex) != null
    val toolsVisible = toolsOpen && toolsAvailable && !feedback && state.segmentation == null && !state.segmentationBusy
    val focus = remember { FocusRequester() }
    val currentActions by rememberUpdatedState(actions)
    val currentState by rememberUpdatedState(state)
    LaunchedEffect(toolsAvailable) { if (!toolsAvailable) toolsOpen = false }
    LaunchedEffect(state.stage, state.natural != null, feedback, toolsVisible, state.segmentation, state.segmentationBusy) {
        if ((state.stage == SpeedStage.READING || state.natural?.result == null && state.natural != null) &&
            !feedback && !toolsVisible && state.segmentation == null && !state.segmentationBusy) focus.requestFocus()
    }
    val background = readerBackground(settings.reader)
    val ink = readerTextStyle(settings.reader).color
    CompositionLocalProvider(LocalContentColor provides ink) {
        Column(Modifier.fillMaxSize().background(background).safeDrawingPadding()
            .focusRequester(focus).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || currentState.saving || currentState.natural?.saving == true || (currentState.stage != SpeedStage.READING && currentState.natural == null) || feedback || toolsVisible ||
                    currentState.segmentation != null || currentState.segmentationBusy) false
                else if (currentState.natural != null) {
                    if (event.key == Key.Spacebar && currentState.natural?.result == null && currentState.natural?.saving == false) {
                        currentActions.onNaturalPlayPause(); true
                    } else false
                } else when (event.key) {
                    Key.Spacebar -> { currentActions.onPlayPause(); true }
                    Key.DirectionLeft -> { currentActions.onPrevious(); true }
                    Key.DirectionRight -> { currentActions.onNext(); true }
                    else -> false
                }
            }.focusable().testTag("speed_reading_screen")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = actions.onBack, enabled = !state.saving && state.natural?.saving != true, modifier = Modifier.semantics { contentDescription = "返回阅读" }) { Text("←") }
                Text(when {
                    state.natural != null -> "自主阅读"
                    state.round == SpeedPacedRound.FADED && state.stage != SpeedStage.SETUP -> "淡出练习"
                    else -> "意群速读"
                }, style = MaterialTheme.typography.titleMedium, color = ink, modifier = Modifier.weight(1f), maxLines = 1)
                if (toolsAvailable) TextButton(onClick = {
                    // This command is idempotent even if a timer transition just paused playback.
                    actions.onPauseForTools()
                    toolsOpen = true
                }, enabled = !state.saving && !state.segmentationBusy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("speed_tools")
                        .semantics { contentDescription = "速读工具" }) { Text("工具", maxLines = 1) }
                else if (state.natural == null) Text(state.selectedGranularity.name, Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = .72f))
            }
            state.error?.let { error ->
                Text(error, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp).testTag("speed_error"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (state.segmentation == null) {
                val message = state.segmentationError ?: state.segmentationMessage
                    ?: if (state.segmentationBusy) "正在准备切分…" else null
                message?.let { Text(it, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)
                    .testTag("speed_segmentation_status"), style = MaterialTheme.typography.labelSmall,
                    color = if (state.segmentationError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (state.natural != null) NaturalReadingPanel(body, settings, state.natural, actions,
                state.canContinueParagraph, Modifier.weight(1f))
            else when (state.stage) {
                SpeedStage.SETUP -> SpeedSetup(article, body, state, actions, Modifier.weight(1f), articles)
                SpeedStage.READING -> SpeedPlayback(body, settings, state, actions, Modifier.weight(1f))
                SpeedStage.QUESTION -> SpeedComprehension(body, state, actions, Modifier.weight(1f))
                SpeedStage.RESULT -> SpeedRecap(body, state, actions, Modifier.weight(1f), articles)
            }
        }
        if (toolsVisible) SpeedReadingToolsSheet(state, actions, onClose = { toolsOpen = false },
            onFeedback = { toolsOpen = false; feedback = true },
            onEdit = { toolsOpen = false; actions.onEditSegmentation() })
        if (feedback) SpeedCorrectionDialog(state, { feedback = false }) { kind, comment ->
            actions.onCorrection(kind, comment)
            feedback = false
        }
        state.segmentation?.let { segmentation ->
            SpeedSegmentationEditor(segmentation, state.segmentationBusy, state.segmentationError,
                actions.onSaveSegmentation, actions.onRestoreSegmentation, actions.onCloseSegmentation)
        }
    }
}

@Composable
private fun SpeedSetup(article: Article, body: ArticleBody?, state: SpeedUiState, actions: SpeedUiActions, modifier: Modifier, articles: List<Article>) {
    val paragraphs = remember(body?.contentHash) { ParagraphFocus.paragraphs(body?.text.orEmpty()) }
    val available = (paragraphs.size - state.startParagraph).coerceAtLeast(0)
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(article.title, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = ReadingSerif))
        Text("按语义块阅读，先保持理解，再逐步提速。", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("训练范围", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("从第 ${state.startParagraph + 1} 段开始", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Row {
                TextButton(onClick = { actions.onStartParagraph(state.startParagraph - 1) }, enabled = !state.loading && state.startParagraph > 0,
                    modifier = Modifier.semantics { contentDescription = "起始段落前移" }) { Text("−") }
                TextButton(onClick = { actions.onStartParagraph(state.startParagraph + 1) },
                    enabled = !state.loading && state.startParagraph + 1 < paragraphs.size,
                    modifier = Modifier.semantics { contentDescription = "起始段落后移" }) { Text("+") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..3).forEach { count ->
                FilterChip(state.paragraphCount == count, { actions.onParagraphCount(count) },
                    enabled = !state.loading && count <= available, label = { Text("$count 段") })
            }
        }
        paragraphs.getOrNull(state.startParagraph)?.let { paragraph ->
            Text(body!!.text.substring(paragraph.start, paragraph.endExclusive).take(200),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = ReadingSerif), maxLines = 4,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("分块粒度", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeedGranularity.entries.forEach { level ->
                FilterChip(state.selectedGranularity == level, { actions.onGranularity(level) }, enabled = !state.loading,
                    label = { Text("${level.name} · ${when (level) { SpeedGranularity.L1 -> "舒适"; SpeedGranularity.L2 -> "标准"; SpeedGranularity.L3 -> "挑战" }}") })
            }
        }
        Text("约 ${state.selectedGranularity.minimumWords}–${state.selectedGranularity.maximumWords} 词 / 块 · 固定表达优先保留完整",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SpeedControl(state, actions, enabled = !state.loading)
        if (state.personalizationAvailable) {
            RememberTrainingSettingsToggle(state.rememberTrainingChoice ?: state.profile.enabled, actions.onRememberTraining,
                editable = !state.loading && !state.saving && state.profileHealth?.status != PersonalizationHealthStatus.CORRUPT)
            PersonalizationSection(state, actions, articles)
        }
        if (state.aiAllowed && state.hasKey) {
            Button(onClick = { actions.onPrepare(true) }, enabled = body != null && !state.loading && !state.personalizationBusy,
                modifier = Modifier.fillMaxWidth().testTag("speed_prepare_ai")) { Text(if (state.loading) "准备中…" else "校准切分并出题") }
            Text("AI 只处理所选段落，每段一道理解题。", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Text(if (!state.aiAllowed) "此来源仅支持离线练习。" else "配置 DeepSeek Key 后可校准切分并生成理解题。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { actions.onPrepare(false) }, enabled = body != null && !state.loading && !state.personalizationBusy,
            modifier = Modifier.fillMaxWidth().testTag("speed_prepare_offline")) { Text(if (state.loading) "准备中…" else "离线分块练习") }
        Text("离线切分供练习参考；没有理解题时不计算有效速度。", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SpeedPlayback(body: ArticleBody?, settings: AppSettings, state: SpeedUiState, actions: SpeedUiActions,
    modifier: Modifier) {
    val plan = state.plan
    val chunk = plan?.chunks?.getOrNull(state.chunkIndex)
    if (body == null || plan == null || chunk == null) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text("训练材料未准备好") }
        return
    }
    val number = plan.paragraphIndices.indexOf(chunk.paragraph) + 1
    val paragraphChunks = remember(plan, chunk.paragraph) { plan.chunks.withIndex().filter { it.value.paragraph == chunk.paragraph } }
    val group = paragraphChunks.indexOfFirst { it.index == state.chunkIndex } + 1
    Column(modifier.fillMaxWidth()) {
        val counter = "$number / ${plan.paragraphIndices.size} 段 · $group / ${paragraphChunks.size} 意群"
        Text(counter, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).testTag("speed_group_counter"),
            style = MaterialTheme.typography.labelMedium)
        SpeedChunkSurface(body.text, plan, state.chunkIndex, state.sentenceOnly, state.round, readerTextStyle(settings.reader),
            Modifier.weight(1f).fillMaxWidth().testTag("speed_chunk_surface")) { index ->
                if (!state.saving && !state.segmentationBusy) actions.onSeek(index)
            }
        // Pause changes the command label only; it never expands controls into the reading area.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).testTag("speed_playback_controls"),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions.onPrevious, enabled = !state.saving && !state.segmentationBusy && state.chunkIndex > 0,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.width(48.dp).heightIn(min = 48.dp).testTag("speed_previous")
                    .semantics { contentDescription = "上一意群" }) { Text("←", maxLines = 1) }
            Button(onClick = actions.onPlayPause, enabled = !state.saving && !state.segmentationBusy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("speed_play_pause").semantics {
                    contentDescription = if (state.playing) "暂停速读" else "开始或继续速读"
                }) { Text(if (state.playing) "暂停" else "播放", maxLines = 1) }
            TextButton(onClick = actions.onNext, enabled = !state.saving && !state.segmentationBusy, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.width(48.dp).heightIn(min = 48.dp).testTag("speed_next")
                    .semantics { contentDescription = "下一意群" }) { Text("→", maxLines = 1) }
            TextButton(onClick = actions.onRepeat, enabled = !state.saving && !state.segmentationBusy, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.width(48.dp).heightIn(min = 48.dp).testTag("speed_repeat")
                    .semantics { contentDescription = "重读当前意群" }) { Text("↺", maxLines = 1) }
        }
    }
}

/** Unchanged sentence/paragraph text; only a clipped paint overlay cross-fades at block changes. */
@Composable
private fun SpeedChunkSurface(text: String, plan: SpeedPlan, index: Int, sentenceOnly: Boolean, round: SpeedPacedRound, style: TextStyle,
    modifier: Modifier, onSeek: (Int) -> Unit) {
    val active = plan.chunks[index]
    val spans = remember(text) { ParagraphFocus.paragraphs(text) }
    val chunks = remember(plan, active.paragraph, active.sentence, sentenceOnly) {
        plan.chunks.withIndex().filter { it.value.paragraph == active.paragraph && (!sentenceOnly || it.value.sentence == active.sentence) }
    }
    val sourceRange = spans.getOrNull(active.paragraph)
    val start = if (sentenceOnly) chunks.first().value.start else sourceRange?.start ?: chunks.first().value.start
    val end = if (sentenceOnly) chunks.last().value.end else sourceRange?.endExclusive ?: chunks.last().value.end
    val visible = remember(text, start, end) { text.substring(start.coerceIn(0, text.length), end.coerceIn(start, text.length)) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    // ReaderStyle separates font metrics from its foreground color. A theme change must not
    // discard the measured geometry and recapture a block the user has manually scrolled past.
    val metricStyle = style.copy(color = Color.Unspecified)
    var layout by remember(visible, metricStyle, density.density, density.fontScale, direction) {
        mutableStateOf<TextLayoutResult?>(null)
    }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val fade = remember { Animatable(1f) }
    var previous by remember { mutableIntStateOf(index) }
    LaunchedEffect(index) {
        fade.snapTo(0f)
        fade.animateTo(1f, tween(120))
        previous = index
    }
    val maximum = scroll.maxValue
    val measured = layout?.takeIf { result ->
        viewport.width > 0 && viewport.height > 0 && result.layoutInput.text.text == visible &&
            result.size.width == viewport.width && maximum != Int.MAX_VALUE &&
            maximum == (result.size.height - viewport.height).coerceAtLeast(0)
    }
    val activeBounds = measured?.let { speedActiveRangeBounds(it, active.start - start, active.end - start) }
    // Only a block/window or measured geometry change owns one positioning attempt. Scroll
    // position and paint colors are deliberately absent: user scrolling must not start a retry.
    LaunchedEffect(plan.planKey, index, start, end, activeBounds, measured?.size, viewport, maximum) {
        val bounds = activeBounds ?: return@LaunchedEffect
        val target = speedRangeScrollTarget(bounds, viewport.height, scroll.value, maximum) ?: return@LaunchedEffect
        scroll.animateScrollTo(target, tween(180))
    }
    val currentOnSeek by rememberUpdatedState(onSeek)
    val tint = MaterialTheme.colorScheme.primary
    val foreground = style.color
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val faded = round == SpeedPacedRound.FADED
    val roundFade = remember { Animatable(0f) }
    LaunchedEffect(round) { roundFade.animateTo(if (faded) 1f else 0f, tween(280)) }
    val guidedBackground = if (dark) .16f else .11f
    val fadedBackground = if (dark) .04f else .025f
    val backgroundAlpha = guidedBackground + (fadedBackground - guidedBackground) * roundFade.value
    val boundaryAlpha = .26f - .19f * roundFade.value
    val textAlpha = .72f + .18f * roundFade.value
    val dimmed = foreground.copy(alpha = textAlpha)
    Box(modifier.padding(horizontal = 24.dp, vertical = 12.dp).onSizeChanged { viewport = it }) {
        BasicText(visible, modifier = Modifier.fillMaxWidth().verticalScroll(scroll)
            .testTag("speed_original_text")
            .drawWithCache {
                val measured = layout
                val activePath = measured?.getPathForRange((active.start - start).coerceIn(0, visible.length), (active.end - start).coerceIn(0, visible.length))
                val priorChunk = plan.chunks.getOrNull(previous)?.takeIf { it.start >= start && it.end <= end }
                val priorPath = if (measured != null && priorChunk != null && previous != index) measured.getPathForRange(priorChunk.start - start, priorChunk.end - start) else null
                val boundaries = measured?.let { result -> chunks.dropLast(1).map { entry ->
                    val boundary = (entry.value.end - start - 1).coerceIn(0, (visible.length - 1).coerceAtLeast(0))
                    result.getBoundingBox(boundary)
                } }.orEmpty()
                onDrawWithContent {
                    val progress = fade.value
                    priorPath?.let { drawPath(it, tint.copy(alpha = backgroundAlpha * (1f - progress))) }
                    activePath?.let { drawPath(it, tint.copy(alpha = backgroundAlpha * progress)) }
                    boundaries.forEach { box ->
                        drawLine(tint.copy(alpha = boundaryAlpha), Offset(box.right + 2.dp.toPx(), box.top + box.height * .24f),
                            Offset(box.right + 2.dp.toPx(), box.bottom - box.height * .24f), strokeWidth = 1.dp.toPx())
                    }
                    drawContent()
                    if (measured != null) {
                        priorPath?.let { clipPath(it) { drawText(measured, color = foreground, alpha = 1f - progress) } }
                        activePath?.let { clipPath(it) { drawText(measured, color = foreground, alpha = progress) } }
                    }
                }
            }.pointerInput(plan, start, end) {
                detectTapGestures { point ->
                    layout?.let { result ->
                        val offset = result.getOffsetForPosition(point) + start
                        chunks.firstOrNull { offset >= it.value.start && offset < it.value.end }?.let { currentOnSeek(it.index) }
                    }
                }
            }, style = style.copy(color = dimmed), onTextLayout = { layout = it })
    }
}

@Composable
internal fun SpeedControl(state: SpeedUiState, actions: SpeedUiActions, enabled: Boolean = true) {
    var drag by remember(state.groupsPerMinute) { mutableFloatStateOf(state.groupsPerMinute.toFloat()) }
    val matchingPlan = state.plan?.takeIf { plan ->
        state.stage != SpeedStage.SETUP || (plan.granularity == state.selectedGranularity &&
            plan.paragraphIndices == (state.startParagraph until state.startParagraph + state.paragraphCount).toList())
    }
    val equivalent = matchingPlan?.let { SpeedPacing.equivalentWordsPerMinute(it, drag.roundToInt()) }
        ?: drag * state.selectedGranularity.preferredWords.toDouble()
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${drag.roundToInt()} 意群/分钟", Modifier.testTag("speed_rate_label"), style = MaterialTheme.typography.bodyMedium)
        Text("播放约 ${equivalent.roundToInt()} WPM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(drag, { drag = it }, enabled = enabled, onValueChangeFinished = { actions.onSpeed(drag.roundToInt()) },
        valueRange = SpeedPacing.MIN_GROUPS_PER_MINUTE.toFloat()..SpeedPacing.MAX_GROUPS_PER_MINUTE.toFloat(),
        modifier = Modifier.fillMaxWidth().testTag("speed_slider").semantics { contentDescription = "意群推进速度" })
}

@Composable
private fun SpeedComprehension(body: ArticleBody?, state: SpeedUiState, actions: SpeedUiActions, modifier: Modifier) {
    val plan = state.plan
    val paragraph = plan?.chunks?.getOrNull(state.chunkIndex)?.paragraph
    val question = plan?.questions?.firstOrNull { it.paragraph == paragraph }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("停一下，检查理解", style = MaterialTheme.typography.headlineSmall)
        if (question == null) {
            Text("本段未配置理解题。", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = actions.onContinue, modifier = Modifier.fillMaxWidth()) { Text("继续") }
            return@Column
        }
        Text(question.prompt, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("speed_question"))
        question.options.forEachIndexed { option, text ->
            Surface(shape = RoundedCornerShape(12.dp), color = if (state.selectedAnswer == option) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().selectable(state.selectedAnswer == option, enabled = !state.answerRevealed,
                    role = Role.RadioButton, onClick = { actions.onAnswer(option) }).testTag("speed_answer_$option")) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(state.selectedAnswer == option, onClick = null, enabled = !state.answerRevealed)
                    Text(text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (state.answerRevealed) {
            HorizontalDivider()
            Text(if (state.selectedAnswer == question.answerIndex) "理解一致" else "再核对这处信息", style = MaterialTheme.typography.titleSmall)
            Text(question.explanation, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("speed_answer_explanation"))
            body?.text?.takeIf { question.evidenceStart >= 0 && question.evidenceEnd in question.evidenceStart..it.length }?.let { text ->
                Text(text.substring(question.evidenceStart, question.evidenceEnd), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = ReadingSerif),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("AI 出题与参考答案 · 仅描述本次练习", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = actions.onContinue, modifier = Modifier.fillMaxWidth().testTag("speed_continue")) { Text("继续") }
        }
    }
}

@Composable
private fun SpeedRecap(body: ArticleBody?, state: SpeedUiState, actions: SpeedUiActions, modifier: Modifier, articles: List<Article>) {
    val run = state.result
    val metrics = run?.let(SpeedMetrics::summarize)
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(if (state.round == SpeedPacedRound.FADED) "淡出练习完成" else "本次训练", style = MaterialTheme.typography.headlineSmall)
        if (run == null || metrics == null) {
            Text(if (state.saving) "正在保存…" else "尚未取得训练记录", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        if (state.saving) Text("正在保存…", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (state.error != null) OutlinedButton(onClick = actions.onContinue,
            modifier = Modifier.fillMaxWidth().testTag("speed_retry_save")) { Text("重试保存训练记录") }
        SpeedMetric("本次播放速度", metrics.wordsPerMinute?.let { "${it.roundToInt()} WPM" } ?: "未测")
        if (state.round == SpeedPacedRound.GUIDED) {
            SpeedMetric("理解正确率", metrics.comprehension?.let { "${(it * 100).roundToInt()}%" } ?: "未测")
            SpeedMetric("有效速度", metrics.effectiveWordsPerMinute?.let { "${it.roundToInt()} WPM" } ?: "未测")
        }
        Text(when {
            state.round == SpeedPacedRound.FADED -> "重复练习：本轮只保留播放节奏，不新增理解成绩或调速建议。"
            !run.firstAttempt -> "重复练习保留节奏记录，不作为首次理解成绩。"
            metrics.comprehension == null -> "缺少完整理解检测，不计算有效速度。"
            metrics.comprehension!! < .7 -> "先放慢节奏，读懂后再提速。"
            metrics.comprehension!! < .85 -> "保持当前节奏，继续关注理解。"
            else -> "本次理解表现稳定；可换一篇文章继续检验。"
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        SpeedMetric("意群 / 分钟", metrics.groupsPerMinute?.let { it.roundToInt().toString() } ?: "未测")
        SpeedMetric("每块累计播放", metrics.meanGroupMillis?.let { "${"%.1f".format(it / 1000)} 秒" } ?: "未测")
        SpeedMetric("回读 / 暂停", "${run.revisits} / ${run.pauses}")
        val interactions = remember(run, state.plan) { state.plan?.let { SpeedInteractionRecap.summarize(run, it) } }
        SpeedInteractionRecapControls(interactions, run, state.plan, body)
        Text("速度按本次实际播放时间计算，不等同于独立阅读能力。", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = actions.onEditSegmentation, enabled = !state.saving && !state.segmentationBusy,
            modifier = Modifier.fillMaxWidth().testTag("speed_edit_segmentation")) { Text("调整意群") }
        if (state.round == SpeedPacedRound.GUIDED && state.personalizationAvailable) PersonalizationSection(state, actions, articles)
        if (state.canContinueRound) Button(onClick = actions.onNextRound,
            enabled = !state.saving && state.error == null,
            modifier = Modifier.fillMaxWidth().testTag("speed_next_round")) {
            Text(if (state.round == SpeedPacedRound.GUIDED) "淡出提示，再读一遍" else "去掉提示，自主阅读")
        }
        if (state.canContinueParagraph) OutlinedButton(onClick = actions.onNextParagraph,
            enabled = !state.saving && !state.personalizationBusy && state.error == null,
            modifier = Modifier.fillMaxWidth().testTag("speed_next_paragraph")) { Text("练习下一段") }
        OutlinedButton(onClick = actions.onRestart, enabled = !state.saving && state.error == null,
            modifier = Modifier.fillMaxWidth().testTag("speed_restart")) { Text("调整后再练") }
        TextButton(onClick = actions.onReadOriginal, modifier = Modifier.fillMaxWidth().testTag("speed_read_original")) { Text("在原文精读本段") }
    }
}

@Composable
private fun PersonalizationSection(state: SpeedUiState, actions: SpeedUiActions, articles: List<Article>) {
    ProfileRecoveryControls(state.profileHealth, state.loading || state.saving || state.personalizationBusy,
        state.recoveryError, state.recoveryReceipt, actions.onRecoverProfile)
    val assessment = state.paceAssessment?.takeIf { it.granularity == state.selectedGranularity && it.rate == state.groupsPerMinute }
    SpeedPersonalizationControls(state.suggestion?.takeIf { it.granularity == state.selectedGranularity && it.fromRate == state.groupsPerMinute },
        state.lastApplied?.takeIf { it.suggestion.granularity == state.selectedGranularity },
        actions.onApplySuggestion, actions.onDismissSuggestion, actions.onUndoSuggestion,
        editable = !state.loading && !state.saving && !state.personalizationBusy,
        hold = state.profile.holds.filter { it.granularity == state.selectedGranularity && it.rate == state.groupsPerMinute }.maxByOrNull { it.createdAt },
        onReleaseHold = actions.onReleasePaceHold, assessment = assessment, articles = articles, onInspectRun = actions.onInspectRun)
    if (assessment == null && state.paceAssessment != null && state.profile.enabled) Text(
        "本次节奏已调整，开始训练后更新建议依据。", Modifier.testTag("speed_pace_configuration_changed"),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SpeedOutcomeControls(state.paceOutcome?.takeIf { it.granularity == state.selectedGranularity },
        evidenceRuns = state.paceEvidenceRuns, articles = articles, onInspectRun = actions.onInspectRun,
        decision = state.paceOutcomeDecision?.takeIf { it.suggestion.granularity == state.selectedGranularity })
    if (state.profile.decisions.isNotEmpty() && actions.onPaceHistory != null) TextButton(
        onClick = actions.onPaceHistory, modifier = Modifier.fillMaxWidth().testTag("speed_open_pace_history")) { Text("节奏调整记录") }
    state.personalizationError?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (state.stage == SpeedStage.SETUP && state.profileHealth?.status != PersonalizationHealthStatus.CORRUPT)
            TextButton(onClick = actions.onRetrySetupSettings,
                enabled = !state.loading && !state.saving && !state.personalizationBusy,
                modifier = Modifier.testTag("speed_retry_setup_save")) { Text("重试保存") }
    }
}

@Composable
private fun SpeedMetric(label: String, value: String) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SpeedCorrectionDialog(state: SpeedUiState, dismiss: () -> Unit, save: (String, String) -> Unit) {
    var kind by rememberSaveable { mutableStateOf("merge_next") }
    var comment by rememberSaveable { mutableStateOf("") }
    val current = state.plan?.chunks?.getOrNull(state.chunkIndex)
    val next = state.plan?.chunks?.getOrNull(state.chunkIndex + 1)
    val canMerge = current != null && next != null && current.paragraph == next.paragraph && current.sentence == next.sentence
    LaunchedEffect(canMerge) { if (!canMerge && kind == "merge_next") kind = "too_long" }
    AlertDialog(onDismissRequest = dismiss, title = { Text("切分纠错") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("merge_next" to "这里不该断", "too_long" to "这一块过长", "other" to "其他问题").forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().selectable(kind == value, enabled = value != "merge_next" || canMerge,
                    role = Role.RadioButton, onClick = { kind = value }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(kind == value, null, enabled = value != "merge_next" || canMerge)
                    Text(label, Modifier.padding(start = 8.dp))
                }
            }
            OutlinedTextField(comment, { comment = it.take(400) }, label = { Text("补充说明（可选）") }, minLines = 2)
            Text("合理切分可以不止一种；反馈仅保存在本机。", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }, confirmButton = { TextButton(onClick = { save(kind, comment.trim()) }, enabled = !state.saving) { Text("保存") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
