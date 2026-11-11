@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.lexicon.domain.ArticleBody
import dev.lexicon.domain.AppSettings
import dev.lexicon.domain.NaturalReadingMetrics
import dev.lexicon.domain.ParagraphFocus
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Original paragraph, manual scroll and explicit completion. The controller owns all time. */
@Composable
internal fun NaturalReadingPanel(
    body: ArticleBody?,
    settings: AppSettings,
    state: NaturalUiState,
    actions: SpeedUiActions,
    canContinueParagraph: Boolean,
    modifier: Modifier = Modifier,
) {
    val result = state.result
    if (result != null) {
        NaturalReadingResult(state, actions, canContinueParagraph, modifier)
        return
    }
    val plan = state.plan
    val paragraph = plan.paragraphIndices.getOrNull(state.paragraphIndex)
    val text = remember(body?.contentHash, plan.bodyHash, paragraph) {
        if (body == null || body.contentHash != plan.bodyHash || paragraph == null) null
        else ParagraphFocus.paragraphs(body.text).getOrNull(paragraph)?.let { body.text.substring(it.start, it.endExclusive) }
    }
    Column(modifier.fillMaxWidth().testTag("natural_reading_panel")) {
        Text("${state.paragraphIndex + 1} / ${plan.paragraphIndices.size} 段",
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).testTag("natural_paragraph_counter"),
            style = MaterialTheme.typography.labelMedium)
        if (text == null) {
            Text("原文暂不可用，请返回文章重试。", Modifier.weight(1f).padding(24.dp),
                style = MaterialTheme.typography.bodyMedium)
        } else key(plan.planKey, paragraph) {
            // This component receives no ticking state. Paragraph layout stays unchanged when the timer changes.
            NaturalParagraphText(text, settings, Modifier.weight(1f).fillMaxWidth())
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (state.playing) "正在计时" else if (state.elapsedMillis > 0) "已读 ${naturalDuration(state.elapsedMillis)} · 已暂停" else "按自己的节奏读完这一段",
                Modifier.testTag("natural_timer_status"), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let { error ->
                Text(error, Modifier.testTag("natural_error"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = actions.onNaturalRetry, enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth().testTag("natural_retry")) { Text("重试保存") }
            }
            Button(onClick = actions.onNaturalPlayPause, enabled = text != null && !state.saving,
                modifier = Modifier.fillMaxWidth().testTag("natural_play_pause")) {
                Text(when {
                    state.saving -> "正在保存…"
                    state.playing -> "暂停"
                    state.paragraphMillis > 0 -> "继续阅读"
                    else -> "开始阅读"
                })
            }
            OutlinedButton(onClick = actions.onNaturalCompleteParagraph,
                enabled = text != null && !state.saving && (state.playing || state.paragraphMillis > 0),
                modifier = Modifier.fillMaxWidth().testTag("natural_complete_paragraph")) { Text("读完本段") }
        }
    }
}

@Composable
private fun NaturalParagraphText(text: String, settings: AppSettings, modifier: Modifier) {
    Box(modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
        BasicText(text, modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("natural_original_text"),
            style = readerTextStyle(settings.reader))
    }
}

@Composable
private fun NaturalReadingResult(state: NaturalUiState, actions: SpeedUiActions, canContinueParagraph: Boolean, modifier: Modifier) {
    val summary = remember(state.result, state.plan) { runCatching { NaturalReadingMetrics.summarize(state.result!!, state.plan) }.getOrNull() }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).testTag("natural_result"),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(if (state.result?.completed == true) "自主阅读完成" else "本次自主阅读", style = MaterialTheme.typography.headlineSmall)
        if (state.saving) Text("正在保存…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.error?.let {
            Text(it, Modifier.testTag("natural_error"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = actions.onNaturalRetry, enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().testTag("natural_retry")) { Text("重试保存") }
        }
        if (summary != null) {
            NaturalMetric("自主计时", naturalDuration(summary.activeMillis))
            NaturalMetric("完成段落", "${summary.completedParagraphs} / ${summary.totalParagraphs}")
            // Avoid foregrounding a misleading four-digit rate from an accidental quick finish.
            // Ten seconds is a display threshold, not a reading-comprehension or ability test.
            NaturalMetric("自测阅读速度", summary.wordsPerMinute?.takeIf { summary.activeMillis >= 10_000 }
                ?.let { "${it.roundToInt()} WPM" } ?: "暂不估算")
            NaturalMetric("暂停", "${state.result!!.pauses} 次")
            if (summary.activeMillis < 10_000) Text("计时不足 10 秒，先保留用时与完成记录。",
                Modifier.testTag("natural_short_timing"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("同一材料的重复练习；速度由读完确认与前台计时估算。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Text("训练记录暂不可用。", style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        if (canContinueParagraph) OutlinedButton(onClick = actions.onNextParagraph,
            enabled = !state.saving && state.error == null, modifier = Modifier.fillMaxWidth().testTag("speed_next_paragraph")) {
            Text("练习下一段")
        }
        Button(onClick = actions.onReadOriginal, enabled = !state.saving && state.error == null,
            modifier = Modifier.fillMaxWidth().testTag("speed_read_original")) { Text("在原文精读本段") }
    }
}

@Composable
private fun NaturalMetric(label: String, value: String) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

internal fun naturalDuration(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return if (seconds < 60) "${seconds} 秒" else "${seconds / 60} 分 ${seconds % 60} 秒"
}
