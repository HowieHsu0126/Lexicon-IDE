@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.*

/** Optional playback controls live in a modal layer without changing the source-text viewport. */
@Composable
internal fun SpeedReadingToolsSheet(state: SpeedUiState, actions: SpeedUiActions, onClose: () -> Unit,
    onFeedback: () -> Unit, onEdit: () -> Unit) {
    val plan = state.plan ?: return
    val chunk = plan.chunks.getOrNull(state.chunkIndex) ?: return
    val height = LocalConfiguration.current.screenHeightDp.dp * if (LocalDensity.current.fontScale >= 1.5f) .92f else .84f
    ModalBottomSheet(onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = height).navigationBarsPadding().testTag("speed_tools_sheet")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("阅读工具", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("speed_tools_close")
                    .semantics { contentDescription = "返回速读" }) { Text("关闭", maxLines = 1) }
            }
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${state.selectedGranularity.name} · ${chunkReasonLabel(plan.origin, chunk.reason)}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SpeedControl(state, actions, enabled = !state.saving && !state.segmentationBusy)
                SpeedSentenceModeButton(state.sentenceOnly, { actions.onSentenceOnly(!state.sentenceOnly) }, Modifier.fillMaxWidth(),
                    enabled = !state.saving && !state.segmentationBusy)
                TextButton(onClick = actions.onReadOriginal, enabled = !state.saving && !state.segmentationBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("speed_read_original")) {
                    Text(when {
                        state.saving -> "正在保存…"
                        state.error != null -> "重试并精读此处"
                        else -> "在原文精读此处"
                    })
                }
                state.error?.let { error ->
                    Text(error, Modifier.testTag("speed_transition_error"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider()
                TextButton(onClick = onEdit, enabled = !state.saving && !state.segmentationBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("speed_edit_segmentation")) { Text("调整意群") }
                TextButton(onClick = onFeedback, enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("speed_feedback")) {
                    Text(if (state.feedbackSaved) "纠错已保存" else "切分纠错")
                }
                if (!state.playing && state.chunkIndex == 0) SpeedTimingEstimate(plan, state.groupsPerMinute)
                Text(if (state.round == SpeedPacedRound.FADED) "重复练习 · 保留节奏，逐步减少提示" else
                    "${when (plan.origin) { "manual" -> "个人切分"; "ai" -> "AI 校准切分"; else -> "本地规则切分" }} · " +
                        if (plan.questions.isEmpty()) "本轮不测理解率" else "段末检查理解",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SpeedSentenceModeButton(sentenceOnly: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val width = if (LocalDensity.current.fontScale >= 1.5f) Modifier.fillMaxWidth() else Modifier
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.then(width).heightIn(min = 48.dp).testTag("speed_sentence_mode")) {
        Text(if (sentenceOnly) "显示整段" else "只看当前句", width.testTag("speed_sentence_label"), maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun SpeedTimingEstimate(plan: SpeedPlan, groupsPerMinute: Int) {
    val durations = remember(plan, groupsPerMinute) {
        val averageWords = plan.chunks.map { it.wordCount }.average()
        plan.chunks.map { SpeedPacing.durationMillis(it, groupsPerMinute, averageWords) }
    }
    if (durations.isEmpty()) return
    val total = durations.sum()
    val averageSeconds = durations.average() / 1000.0
    Text("平均每块 ${"%.1f".format(averageSeconds)} 秒 · 本组约 ${"%.1f".format(total / 60_000.0)} 分钟",
        Modifier.testTag("speed_timing_estimate"), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun reasonLabel(reason: String) = when (reason) {
    "sentence" -> "句末停顿"; "punctuation" -> "标点停顿"; "clause" -> "从句边界"
    "coordination" -> "并列结构"; "preposition" -> "介词短语"; "phrase" -> "完整短语"
    "length" -> "节奏分块"; "protected" -> "固定表达"; "local" -> "本地规则切分"
    else -> reason.take(70).ifBlank { "语义分块" }
}

private fun chunkReasonLabel(origin: String?, reason: String) = when (origin) {
    "manual" -> "个人切分"
    "ai" -> "AI 切分建议"
    else -> reasonLabel(reason)
}
