@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Only validated first-exposure records enter this panel. Ordinary reading and playback stay outside. */
@Composable
internal fun BaselineProgressPanel(
    summary: BaselineProgressSummary?,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onOpenAttempt: (String) -> Unit,
    onHistory: () -> Unit,
) {
    var selectedTopic by rememberSaveable { mutableStateOf<String?>(null) }
    var effective by rememberSaveable { mutableStateOf(false) }
    var showExclusions by rememberSaveable { mutableStateOf(false) }
    val points = summary?.points.orEmpty()
    val topics = remember(points) { points.mapNotNull { it.topic }.distinct().sorted() }
    val activeTopic = selectedTopic?.takeIf { it in topics }
    val filtered = remember(points, activeTopic) { if (activeTopic == null) points else points.filter { it.topic == activeTopic } }
    val excluded = summary?.exclusions.orEmpty().filterValues { it > 0 }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("baseline_progress_panel")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("新材料自测", style = MaterialTheme.typography.titleMedium)
            Text("初次独立完成阅读，并提交全部理解题的记录。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loading) Text("正在读取自测记录…", Modifier.testTag("baseline_progress_loading"), style = MaterialTheme.typography.bodySmall)
            error?.let {
                Text(it, Modifier.testTag("baseline_progress_error"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry, enabled = !loading, modifier = Modifier.testTag("baseline_progress_retry")) { Text("重新读取") }
            }
            if (summary != null) {
                if (topics.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(activeTopic == null, { selectedTopic = null }, label = { Text("全部主题") }, modifier = Modifier.testTag("baseline_topic_all"))
                    topics.forEach { topic -> FilterChip(activeTopic == topic, { selectedTopic = topic }, label = { Text(topic) },
                        modifier = Modifier.testTag("baseline_topic_$topic")) }
                }
                if (filtered.isEmpty()) {
                    Text("还没有合格的新材料自测记录。", Modifier.testTag("baseline_progress_empty"), style = MaterialTheme.typography.bodyMedium)
                    Text("可从文章列表开始无辅助自测；完成阅读和理解题后在这里回看。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val correct = filtered.sumOf { it.correct }
                    val questions = filtered.sumOf { it.questions }
                    Text("${filtered.size} 份材料 · 合计答对 $correct / $questions 题", Modifier.testTag("baseline_progress_counts"),
                        style = MaterialTheme.typography.bodyMedium)
                    if (filtered.size >= 2) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!effective, { effective = false }, label = { Text("自测速度") }, modifier = Modifier.testTag("baseline_metric_wpm"))
                            FilterChip(effective, { effective = true }, label = { Text("有效速度") }, modifier = Modifier.testTag("baseline_metric_effective"))
                        }
                        val recent = filtered.takeLast(8)
                        Text("最近 ${recent.size} 次 · WPM", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        BaselineTrend(recent, effective)
                    } else Text("已有 1 次记录，再完成一份新材料后显示趋势。", Modifier.testTag("baseline_progress_one_sample"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("最近记录", style = MaterialTheme.typography.titleSmall)
                    filtered.takeLast(3).asReversed().forEach { point -> BaselineProgressRow(point, onOpenAttempt) }
                    Text("材料难度与题目可能不同；这些变化不等同于能力提升或训练效果。", Modifier.testTag("baseline_progress_scope"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (excluded.isNotEmpty()) {
                    TextButton(onClick = { showExclusions = !showExclusions }, contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.fillMaxWidth().testTag("baseline_progress_exclusions_toggle")) {
                        Text("${if (showExclusions) "收起" else "查看"}未纳入记录 · ${excluded.values.sum()} 条", Modifier.fillMaxWidth())
                    }
                    if (showExclusions) {
                        Text("以下为全部主题的互斥分类，不随当前筛选变化。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        BaselineExclusion.entries.forEach { reason -> excluded[reason]?.let { count ->
                            Text("${baselineExclusionLabel(reason)} · $count 条", Modifier.testTag("baseline_exclusion_${reason.name}"),
                                style = MaterialTheme.typography.bodySmall)
                        } }
                    }
                }
            } else if (!loading && error == null) Text("自测记录尚未加载。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onHistory, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("baseline_progress_history")) {
                Text("全部自测记录")
            }
        }
    }
}

@Composable
private fun BaselineProgressRow(point: BaselineProgressPoint, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onOpen(point.attemptId) }
        .padding(vertical = 10.dp).testTag("baseline_progress_sample_${point.attemptId}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(point.sourceRef.title?.takeIf { it.isNotBlank() } ?: "未关联标题", style = MaterialTheme.typography.titleSmall.copy(fontFamily = ReadingSerif))
        Text(listOfNotNull(baselineProgressDate(point.measuredAt), point.sourceRef.source?.takeIf { it.isNotBlank() }, point.topic).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${point.wordsPerMinute.roundToInt()} WPM", style = MaterialTheme.typography.bodyMedium)
            Text("答对 ${point.correct} / ${point.questions}", style = MaterialTheme.typography.bodyMedium)
            Text("有效 ${point.effectiveWordsPerMinute.roundToInt()} WPM", style = MaterialTheme.typography.bodyMedium)
        }
        HorizontalDivider()
    }
}

@Composable
private fun BaselineTrend(points: List<BaselineProgressPoint>, effective: Boolean) {
    val values = points.map { if (effective) it.effectiveWordsPerMinute else it.wordsPerMinute }
    val ceiling = (ceil((values.maxOrNull() ?: 0.0) / 50.0) * 50).coerceAtLeast(50.0)
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val description = points.mapIndexed { index, point -> "${baselineProgressDate(point.measuredAt)}，${values[index].roundToInt()} WPM" }.joinToString("；")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(150.dp)) {
            Column(Modifier.width(IntrinsicSize.Max).fillMaxHeight().padding(end = 10.dp), verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End) {
                listOf(ceiling, ceiling / 2, 0.0).forEach { Text(it.roundToInt().toString(), style = MaterialTheme.typography.labelSmall) }
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().testTag("baseline_progress_trend").semantics {
                contentDescription = "${if (effective) "有效阅读速度" else "自测阅读速度"}；$description"
            }) {
                val radius = 4.dp.toPx()
                val left = radius; val right = size.width - radius
                val top = radius; val bottom = size.height - radius
                if (right <= left || bottom <= top) return@Canvas
                repeat(3) { index ->
                    val y = top + (bottom - top) * index / 2
                    drawLine(grid, Offset(left, y), Offset(right, y), 1.dp.toPx())
                }
                val span = (points.last().measuredAt - points.first().measuredAt).coerceAtLeast(1)
                val positions = points.mapIndexed { index, point ->
                    Offset(left + (right - left) * ((point.measuredAt - points.first().measuredAt).toDouble() / span).toFloat(),
                        bottom - (bottom - top) * (values[index] / ceiling).toFloat().coerceIn(0f, 1f))
                }
                val path = Path().apply { moveTo(positions.first().x, positions.first().y); positions.drop(1).forEach { lineTo(it.x, it.y) } }
                drawPath(path, line, style = Stroke(2.dp.toPx()))
                positions.forEach { drawCircle(line, radius, it) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(baselineProgressDate(points.first().measuredAt, dateOnly = true), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text(baselineProgressDate(points.last().measuredAt, dateOnly = true), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
        }
    }
}

private fun baselineProgressDate(time: Long, dateOnly: Boolean = false): String = DateTimeFormatter.ofPattern(if (dateOnly) "yyyy/M/d" else "yyyy/M/d HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))

private fun baselineExclusionLabel(reason: BaselineExclusion): String = when (reason) {
    BaselineExclusion.INCOMPLETE -> "未完成"
    BaselineExclusion.CONTINUATION -> "跨次续读"
    BaselineExclusion.FAMILIAR -> "熟悉材料练习"
    BaselineExclusion.UNMEASURED -> "未取得完整自测数据"
    BaselineExclusion.UNAVAILABLE -> "材料或记录不可用"
    BaselineExclusion.DUPLICATE -> "同一材料的后续记录"
}
