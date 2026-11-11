@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.*
import kotlin.math.roundToInt

/** Passive self-test UI: the controller alone owns exposure, time, answers and saved results. */
@Composable
internal fun ReadingBaselineScreen(article: Article, settings: AppSettings, state: BaselineUiState, actions: BaselineUiActions) {
    val ink = readerTextStyle(settings.reader).color
    val focus = remember { FocusRequester() }
    val currentState by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    LaunchedEffect(state.stage) {
        if (state.stage == BaselineStage.READY || state.stage == BaselineStage.READING) focus.requestFocus()
    }
    CompositionLocalProvider(LocalContentColor provides ink) {
        Column(Modifier.fillMaxSize().background(readerBackground(settings.reader)).safeDrawingPadding()
            .focusRequester(focus).onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Spacebar &&
                    currentState.stage in listOf(BaselineStage.READY, BaselineStage.READING) &&
                    !currentState.loading && !currentState.saving && currentState.error == null) {
                    currentActions.onPlayPause(); true
                } else false
            }.focusable().testTag("baseline_screen")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = actions.onBack, enabled = !state.saving,
                    modifier = Modifier.semantics { contentDescription = "返回" }) { Text("←") }
                Text(if (state.attempt?.resumedFromHistory == true) "续读练习" else "无辅助自测",
                    Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            }
            state.error?.let { error ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(error, Modifier.testTag("baseline_error"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (state.stage != BaselineStage.SETUP || state.body == null) {
                        TextButton(onClick = if (state.body == null && state.stage == BaselineStage.SETUP) actions.onReload else actions.onRetry,
                            enabled = !state.loading && !state.saving, modifier = Modifier.testTag("baseline_retry")) { Text("重试") }
                    }
                }
            }
            when (state.stage) {
                BaselineStage.SETUP -> BaselineSetup(article, state, actions, Modifier.weight(1f))
                BaselineStage.READY, BaselineStage.READING -> BaselineReading(settings, state, actions, Modifier.weight(1f))
                BaselineStage.QUESTION -> BaselineQuestion(state, actions, Modifier.weight(1f))
                BaselineStage.RESULT -> BaselineResult(state, actions, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BaselineSetup(article: Article, state: BaselineUiState, actions: BaselineUiActions, modifier: Modifier) {
    val paragraphs = remember(state.body?.contentHash) { ParagraphFocus.paragraphs(state.body?.text.orEmpty()) }
    val available = (paragraphs.size - state.startParagraph).coerceAtLeast(0)
    val editable = !state.loading && !state.saving
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(state.material?.sourceRef?.title?.takeIf { it.isNotBlank() } ?: article.title,
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = ReadingSerif))
        (state.material?.sourceRef?.source?.takeIf { it.isNotBlank() } ?: article.sourceName).takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("先独立阅读，再检查理解。开始前不显示正文。", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("阅读范围", style = MaterialTheme.typography.titleSmall)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("从第 ${state.startParagraph + 1} 段开始", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = { actions.onStartParagraph(state.startParagraph - 1) }, enabled = editable && state.startParagraph > 0,
                    modifier = Modifier.semantics { contentDescription = "自测起始段落前移" }) { Text("−") }
                TextButton(onClick = { actions.onStartParagraph(state.startParagraph + 1) }, enabled = editable && state.startParagraph + 1 < paragraphs.size,
                    modifier = Modifier.semantics { contentDescription = "自测起始段落后移" }) { Text("+") }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..3).forEach { count ->
                FilterChip(state.paragraphCount == count, { actions.onParagraphCount(count) }, enabled = editable && count <= available,
                    label = { Text("$count 段") })
            }
        }
        Row(Modifier.fillMaxWidth().toggleable(state.userConfirmedUnseen, enabled = editable, role = Role.Checkbox,
            onValueChange = actions.onConfirmUnseen).padding(vertical = 8.dp).testTag("baseline_unseen_confirmation"),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(state.userConfirmedUnseen, onCheckedChange = null, enabled = editable)
            Text("此前未读过这部分", Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Text("已读材料仍可练习；只有新材料的完整阅读与答题记录才进入基线。", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.aiAllowed && state.hasKey) {
            Button(onClick = { actions.onPrepare(true) }, enabled = editable && state.body != null && available > 0,
                modifier = Modifier.fillMaxWidth().testTag("baseline_prepare_ai")) { Text(if (state.loading) "准备中…" else "准备自测与理解题") }
            Text("DeepSeek 仅为所选段落出题，读完后才显示。", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Text(if (!state.aiAllowed) "此来源支持离线计时。" else "配置 DeepSeek Key 后可加入理解题。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { actions.onPrepare(false) }, enabled = editable && state.body != null && available > 0,
            modifier = Modifier.fillMaxWidth().testTag("baseline_prepare_offline")) { Text(if (state.loading) "准备中…" else "仅离线计时") }
        if (state.body == null && !state.loading) TextButton(onClick = actions.onReload,
            modifier = Modifier.fillMaxWidth().testTag("baseline_load_body")) { Text("加载阅读材料") }
    }
}

@Composable
private fun BaselineReading(settings: AppSettings, state: BaselineUiState, actions: BaselineUiActions, modifier: Modifier) {
    val plan = state.plan
    val paragraph = plan?.paragraphIndices?.getOrNull(state.paragraphIndex)
    val body = state.body
    val material = state.material?.takeIf { it.bodyHash == plan?.bodyHash && it.planKey == plan?.planKey }
    val text = remember(material, body?.contentHash, plan?.bodyHash, paragraph) {
        material?.paragraphs?.singleOrNull { it.paragraph == paragraph }?.text
            ?: if (body == null || body.contentHash != plan?.bodyHash || paragraph == null) null
            else ParagraphFocus.paragraphs(body.text).getOrNull(paragraph)?.let { body.text.substring(it.start, it.endExclusive) }
    }
    val reading = state.stage == BaselineStage.READING && state.playing
    Column(modifier.fillMaxWidth()) {
        Text("${state.paragraphIndex + 1} / ${plan?.paragraphIndices?.size ?: state.paragraphCount} 段",
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).testTag("baseline_paragraph_counter"),
            style = MaterialTheme.typography.labelMedium)
        key(plan?.planKey, paragraph) {
            val scroll = rememberScrollState()
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                if (reading && text != null) BasicText(text, style = readerTextStyle(settings.reader),
                    modifier = Modifier.fillMaxSize().verticalScroll(scroll).testTag("baseline_original_text"))
                else Text(if (state.stage == BaselineStage.READY) "准备好后，开始阅读。" else "已暂停",
                    Modifier.testTag("baseline_paused_cover"), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (reading) "正在计时" else if (state.elapsedMillis > 0) "已读 ${naturalDuration(state.elapsedMillis)}" else "按自己的节奏阅读",
                Modifier.testTag("baseline_timer_status"), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.attempt?.resumedFromHistory == true) Text("跨次续读作为练习，不纳入首次阅读基线。",
                Modifier.testTag("baseline_resume_notice"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = actions.onPlayPause, enabled = text != null && !state.loading && !state.saving && state.error == null,
                modifier = Modifier.fillMaxWidth().testTag("baseline_play_pause")) {
                Text(when {
                    state.saving -> "正在保存…"
                    reading -> "暂停"
                    state.stage == BaselineStage.READY -> "开始阅读"
                    else -> if (state.attempt?.resumedFromHistory == true) "继续本段" else "继续阅读"
                }, Modifier.fillMaxWidth().testTag("baseline_play_pause_label"), textAlign = TextAlign.Center)
            }
            if (state.stage == BaselineStage.READING) OutlinedButton(onClick = actions.onCompleteParagraph,
                enabled = !state.loading && !state.saving && state.error == null && reading,
                modifier = Modifier.fillMaxWidth().testTag("baseline_complete_paragraph")) { Text("读完本段") }
        }
    }
}

@Composable
private fun BaselineQuestion(state: BaselineUiState, actions: BaselineUiActions, modifier: Modifier) {
    val questions = state.plan?.questions.orEmpty()
    val index = state.attempt?.answers?.size ?: 0
    val question = questions.getOrNull(index)
    key(question?.id) {
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("理解检测", style = MaterialTheme.typography.headlineSmall)
            if (question == null) {
                Text("理解题暂不可用。", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Text("${index + 1} / ${questions.size} 题", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(question.prompt, Modifier.testTag("baseline_question"), style = MaterialTheme.typography.titleMedium)
            question.options.forEachIndexed { option, label ->
                Surface(shape = RoundedCornerShape(12.dp),
                    color = if (state.selectedAnswer == option) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().selectable(state.selectedAnswer == option, enabled = !state.saving && !state.loading && state.error == null,
                        role = Role.RadioButton, onClick = { actions.onAnswer(option) }).testTag("baseline_answer_$option")) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(state.selectedAnswer == option, null, enabled = !state.saving && !state.loading && state.error == null)
                        Text(label, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text("全部提交后查看答案与原文依据。", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = actions.onSubmitAnswer, enabled = state.selectedAnswer != null && !state.saving && !state.loading && state.error == null,
                modifier = Modifier.fillMaxWidth().testTag("baseline_submit_answer")) {
                Text(if (state.saving) "正在保存…" else if (index == questions.lastIndex) "提交全部答案" else "下一题")
            }
        }
    }
}

@Composable
private fun BaselineResult(state: BaselineUiState, actions: BaselineUiActions, modifier: Modifier) {
    val attempt = state.attempt
    val summary = state.summary
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).testTag("baseline_result"),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(when {
            attempt?.resumedFromHistory == true -> "续读记录"
            summary?.qualifiesAsBaseline == true -> "新材料自测"
            attempt != null && (!attempt.localUnseen || !attempt.userConfirmedUnseen) -> "阅读练习"
            else -> "自测记录"
        }, style = MaterialTheme.typography.headlineSmall)
        if (state.loading || state.saving) Text(if (state.loading) "正在加载记录…" else "正在保存…", style = MaterialTheme.typography.labelSmall)
        if (attempt == null || summary == null) {
            if (!state.loading && !state.saving) Text("记录暂不可用。", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        state.material?.sourceRef?.title?.takeIf { it.isNotBlank() }?.let { title ->
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = ReadingSerif))
        }
        BaselineMetric("自主计时", naturalDuration(summary.activeMillis))
        BaselineMetric("自测阅读速度", summary.wordsPerMinute?.let { "${it.roundToInt()} WPM" } ?: "未测")
        BaselineMetric("理解正确率", summary.accuracy?.let { "${(it * 100).roundToInt()}%" } ?: "未测")
        BaselineMetric("有效阅读速度", summary.effectiveWordsPerMinute?.let { "${it.roundToInt()} WPM" } ?: "未测")
        BaselineMetric("完成段落", "${attempt.paragraphs.count { it.completed }} / ${attempt.paragraphs.size}")
        BaselineMetric("暂停", "${attempt.pauses} 次")
        Text(when {
            attempt.resumedFromHistory -> "跨次续读作为练习保留，不纳入首次阅读基线。"
            !attempt.localUnseen || !attempt.userConfirmedUnseen -> "本次作为练习保留，不计入新材料基线。"
            !summary.completeReading -> "尚未完整读完所选段落，不计入基线。"
            summary.wordsPerMinute == null -> "计时不足以估算阅读速度，本次不计入基线。"
            summary.accuracy == null -> "没有完整理解检测，不计算有效速度。"
            else -> "记录仅描述本次材料与理解题，不能代表标准化阅读能力。"
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (attempt.completed && attempt.revealedAt != null) state.plan?.questions?.forEach { question ->
            BaselineEvidence(question, attempt.answers.firstOrNull { it.questionId == question.id },
                state.body?.takeIf { it.contentHash == attempt.bodyHash },
                state.material?.takeIf { it.bodyHash == attempt.bodyHash && it.planKey == attempt.planKey })
        }
        if (state.canOpenOriginal) Button(onClick = actions.onReadOriginal, enabled = !state.loading && !state.saving && state.error == null,
            modifier = Modifier.fillMaxWidth().testTag("baseline_read_original")) { Text("回到原文精读") }
    }
}

@Composable
private fun BaselineEvidence(question: SpeedQuestion, answer: SpeedAnswer?, body: ArticleBody?, material: ReadingBaselineMaterial?) {
    var expanded by rememberSaveable(question.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp),
            modifier = Modifier.fillMaxWidth().testTag("baseline_evidence_toggle_${question.id}")) {
            Text(if (expanded) "收起答案与依据" else "查看答案与依据", Modifier.fillMaxWidth())
        }
        if (expanded) {
            Text(question.prompt, style = MaterialTheme.typography.titleSmall)
            Text("你的选择：${answer?.selectedIndex?.let { question.options.getOrNull(it) } ?: "未作答"}", style = MaterialTheme.typography.bodyMedium)
            Text("参考答案：${question.options.getOrNull(question.answerIndex).orEmpty()}", style = MaterialTheme.typography.bodyMedium)
            Text(question.explanation, Modifier.testTag("baseline_explanation_${question.id}"), style = MaterialTheme.typography.bodyMedium)
            val quote = material?.textAt(question.evidenceStart, question.evidenceEnd) ?: body?.text?.takeIf {
                question.evidenceStart >= 0 && question.evidenceEnd > question.evidenceStart && question.evidenceEnd <= it.length
            }?.substring(question.evidenceStart, question.evidenceEnd)
            quote?.let {
                Text(it, Modifier.testTag("baseline_evidence_${question.id}"),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = ReadingSerif), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("AI 出题与参考答案", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BaselineMetric(label: String, value: String) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
