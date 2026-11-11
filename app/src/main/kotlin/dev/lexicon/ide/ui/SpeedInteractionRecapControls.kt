@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.*

/** Renders the domain's observed actions without inferring difficulty or choosing candidates. */
@Composable
internal fun SpeedInteractionRecapControls(
    summary: SpeedInteractionSummary?,
    run: SpeedRun,
    plan: SpeedPlan?,
    body: ArticleBody?,
    modifier: Modifier = Modifier,
    snippets: Map<Int, String> = emptyMap(),
    initiallyExpanded: Boolean = false,
) {
    if (summary == null) return
    var expanded by rememberSaveable(run.id) { mutableStateOf(initiallyExpanded) }
    val planMatches = plan != null && plan.bodyHash == run.bodyHash && plan.articleId == run.articleId &&
        plan.planKey == run.planKey && plan.granularity == run.granularity && plan.origin == run.planOrigin
    val sourceMatches = planMatches && body != null && body.articleId == run.articleId && body.contentHash == run.bodyHash
    Column(modifier.fillMaxWidth().animateContentSize(tween(180)).testTag("speed_interaction_recap"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp),
            modifier = Modifier.fillMaxWidth().testTag("speed_interaction_toggle")) {
            Text(if (expanded) "收起主动回看与暂停" else "主动回看与暂停", Modifier.fillMaxWidth())
        }
        if (expanded) {
            val limitation = when {
                SpeedInteractionLimit.PLAN_MISMATCH in summary.limitations -> "切分与训练记录不匹配，暂不可回看。"
                SpeedInteractionLimit.INVALID_TRACE in summary.limitations -> "逐块访问记录不完整，暂不能计算。"
                SpeedInteractionLimit.MISSING_VISIT_DATA in summary.limitations -> "这条历史记录未保存逐块访问，无法回看本项。"
                else -> null
            }
            if (limitation != null) Text(limitation, Modifier.testTag("speed_interaction_unavailable"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (summary.items.isEmpty()) Text("未记录主动回看或暂停。", Modifier.testTag("speed_interaction_empty"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else summary.items.forEach { item ->
                Column(Modifier.fillMaxWidth().testTag("speed_interaction_${item.index}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val chunk = plan?.chunks?.getOrNull(item.index)
                    val quote = if (sourceMatches && body != null && chunk != null && chunk.start >= 0 &&
                        chunk.end > chunk.start && chunk.end <= body.text.length) body.text.substring(chunk.start, chunk.end)
                    else if (planMatches && chunk != null) snippets[item.index]?.takeIf { it.length == chunk.end - chunk.start } else null
                    Text(quote ?: "原文片段暂不可用", Modifier.testTag("speed_interaction_quote_${item.index}"),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = ReadingSerif))
                    Text("访问 ${item.visits} 次 · 主动暂停 ${item.pauses} 次 · 回看 ${item.revisits} 次",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    InteractionMetric("累计播放", interactionSeconds(item.activeMillis.toDouble()))
                    InteractionMetric("每次平均", interactionSeconds(item.meanActiveMillis))
                    if (item.plannedMillisPerVisit != null && item.normalizedMeanDwell != null) {
                        InteractionMetric("计划每次", interactionSeconds(item.plannedMillisPerVisit!!.toDouble()))
                        InteractionMetric("每次平均 / 计划", "${"%.2f".format(item.normalizedMeanDwell)} 倍")
                    }
                    HorizontalDivider()
                }
            }
            if (SpeedInteractionLimit.VARIABLE_PACE in summary.limitations) Text("本轮调整过节奏，未比较计划停留。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (SpeedInteractionLimit.PARTIAL_RUN in summary.limitations) Text("本轮尚未完整播放。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("仅描述播放中的主动操作，不代表理解难度或阅读能力。", Modifier.testTag("speed_interaction_scope"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InteractionMetric(label: String, value: String) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun interactionSeconds(value: Double): String = "${"%.1f".format(value / 1000)} 秒"
