@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.Article
import dev.lexicon.domain.SpeedCorrection
import dev.lexicon.domain.SpeedLibrary
import dev.lexicon.domain.SpeedMetrics
import dev.lexicon.domain.SpeedRun
import dev.lexicon.domain.SpeedPacedRound
import dev.lexicon.domain.NaturalReadingAttempt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Local training history. Playback statistics and unreviewed corrections are kept distinct. */
@Composable
internal fun SpeedHistoryScreen(
    library: SpeedLibrary,
    articles: List<Article>,
    onBack: () -> Unit,
    onTrain: (String) -> Unit,
    onExport: (() -> Unit)? = null,
    onInspectCorrection: ((SpeedCorrection) -> Unit)? = null,
    onReadOriginal: ((String) -> Unit)? = null,
    onInspectRun: ((SpeedRun) -> Unit)? = null,
    onPaceHistory: (() -> Unit)? = null,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val runs = remember(library.runs) { library.runs.sortedByDescending { it.finishedAt } }
    val natural = remember(library.naturalAttempts) { library.naturalAttempts.sortedByDescending { it.finishedAt } }
    val articleMap = remember(articles) { articles.associateBy { it.id } }
    val feedback = remember(library.corrections) { library.corrections.sortedByDescending { it.createdAt } }
    val checked = remember(runs) { runs.filter { SpeedMetrics.summarize(it).comprehension != null } }
    val correct = remember(checked) { checked.sumOf { run -> run.answers.count { it.correct } } }
    val answered = remember(checked) { checked.sumOf { it.questionCount } }
    LazyColumn(Modifier.fillMaxSize().testTag("speed_history"), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("速读记录", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onBack) { Text("返回") }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column { Text(runs.size.toString(), style = MaterialTheme.typography.headlineSmall); Text("次训练", style = MaterialTheme.typography.labelMedium) }
                Column {
                    Text(if (answered > 0) "$correct / $answered" else "未测", style = MaterialTheme.typography.headlineSmall)
                    Text("首次理解 · 正确 / 已答", style = MaterialTheme.typography.labelMedium)
                }
                Column { Text(feedback.size.toString(), style = MaterialTheme.typography.headlineSmall); Text("条切分反馈", style = MaterialTheme.typography.labelMedium) }
            }
        }
        if (onPaceHistory != null) item {
            TextButton(onClick = onPaceHistory, modifier = Modifier.fillMaxWidth().testTag("history_open_pace_decisions")) { Text("节奏调整记录") }
        }
        if (tab == 0 && runs.isNotEmpty()) item { SpeedHistoryTrend(runs) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(tab == 0, { tab = 0 }, label = { Text("训练") })
                FilterChip(tab == 1, { tab = 1 }, label = { Text("自主阅读") })
                FilterChip(tab == 2, { tab = 2 }, label = { Text("切分反馈") })
            }
        }
        if (tab == 2) {
            if (feedback.any { it.kind != "boundary_edit" }) item {
                Text("切分准确率：待人工复核", style = MaterialTheme.typography.titleSmall)
                Text("反馈条数不能代表准确率。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (feedback.isEmpty()) item { Text("尚无切分反馈。训练中可标记“这里不该断”。", style = MaterialTheme.typography.bodyMedium) }
            items(feedback, key = { it.id }) { entry ->
                SpeedFeedbackRow(entry, articleMap[entry.articleId], onTrain, onInspectCorrection)
            }
        } else if (tab == 1) {
            item {
                Text("自主计时 · ${natural.size} 次", style = MaterialTheme.typography.titleSmall)
                Text("同一材料的重复练习，记录前台阅读时长与读完确认。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (natural.isEmpty()) item { Text("完成淡出练习后，可继续自主阅读。", style = MaterialTheme.typography.bodyMedium) }
            items(natural, key = { "natural-${it.id}" }) { attempt ->
                NaturalHistoryRow(attempt, articleMap[attempt.articleId], onReadOriginal)
            }
        } else {
            if (runs.isEmpty()) item {
                Text("从文章的“意群速读”开始一次训练。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(runs, key = { it.id }) { run -> SpeedHistoryRow(run, articleMap[run.articleId], onTrain, onInspectRun) }
        }
        if (onExport != null && (runs.isNotEmpty() || feedback.isNotEmpty() || natural.isNotEmpty())) item {
            OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth().testTag("speed_export")) { Text("导出训练记录") }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun SpeedHistoryTrend(runs: List<SpeedRun>) {
    val samples = remember(runs) {
        runs.mapNotNull { run -> SpeedMetrics.summarize(run).wordsPerMinute?.let { run to it } }.take(8).reversed()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("播放节奏 · 最近 ${samples.size} 次", style = MaterialTheme.typography.titleSmall)
        if (samples.size < 2) {
            Text("再完成一次训练后显示趋势。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val maximum = samples.maxOf { it.second }.coerceAtLeast(1.0)
            val description = samples.joinToString("；") { "${speedDate(it.first.finishedAt)}：${it.second.roundToInt()} WPM" }
            val tint = MaterialTheme.colorScheme.primary
            val baseline = MaterialTheme.colorScheme.outlineVariant
            Canvas(Modifier.fillMaxWidth().height(84.dp).testTag("speed_trend").semantics { contentDescription = description }) {
                val slot = size.width / samples.size
                val width = slot * .5f
                drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                samples.forEachIndexed { index, value ->
                    val height = ((value.second / maximum) * (size.height - 4.dp.toPx())).toFloat()
                    drawRect(tint.copy(alpha = if (index == samples.lastIndex) .9f else .5f),
                        Offset(slot * index + (slot - width) / 2f, size.height - height), Size(width, height))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(speedDate(samples.first().first.finishedAt), style = MaterialTheme.typography.labelSmall)
                Text("0–${maximum.roundToInt()} WPM", style = MaterialTheme.typography.labelSmall)
                Text(speedDate(samples.last().first.finishedAt), style = MaterialTheme.typography.labelSmall)
            }
            Text("包含不同材料与粒度，不能据此判断能力提升。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SpeedHistoryRow(run: SpeedRun, article: Article?, onTrain: (String) -> Unit, onInspectRun: ((SpeedRun) -> Unit)?) {
    val summary = remember(run) { SpeedMetrics.summarize(run) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(article?.title ?: "已移除文章", style = MaterialTheme.typography.titleMedium.copy(fontFamily = ReadingSerif))
        Text("${speedDate(run.finishedAt)} · ${run.granularity.name} · ${if (run.round == SpeedPacedRound.FADED) "淡出练习" else "引导练习"} · ${if (run.completed) "完成" else "未完成"}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("目标 ${run.groupsPerMinute} 意群/分钟", style = MaterialTheme.typography.bodySmall)
            Text("实际 ${summary.wordsPerMinute?.let { "${it.roundToInt()} WPM" } ?: "未测"}", style = MaterialTheme.typography.bodySmall)
            Text("回读 ${run.revisits} 次", style = MaterialTheme.typography.bodySmall)
        }
        val accuracy = summary.comprehension?.let { "${run.answers.count { it.correct }} / ${run.questionCount}" }
        if (run.round == SpeedPacedRound.GUIDED) Text("理解 ${accuracy ?: "未测"} · 有效速度 ${summary.effectiveWordsPerMinute?.let { "${it.roundToInt()} WPM" } ?: "未测"}",
            style = MaterialTheme.typography.bodySmall)
        if (!run.firstAttempt) Text("重复练习，不计入首次理解成绩。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (onInspectRun != null) TextButton(onClick = { onInspectRun(run) }, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.testTag("speed_inspect_run_${run.id}")) { Text("回看操作") }
            if (article != null) TextButton(onClick = { onTrain(article.id) }, contentPadding = PaddingValues(0.dp)) { Text("回到文章训练") }
        }
        HorizontalDivider()
    }
}

@Composable
private fun NaturalHistoryRow(attempt: NaturalReadingAttempt, article: Article?, onReadOriginal: ((String) -> Unit)?) {
    Column(Modifier.testTag("natural_history_${attempt.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(article?.title ?: "已移除文章", style = MaterialTheme.typography.titleMedium.copy(fontFamily = ReadingSerif))
        Text("${speedDate(attempt.finishedAt)} · ${if (attempt.completed) "完成" else "未完成"}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("自主计时 ${naturalDuration(attempt.paragraphs.sumOf { it.activeMillis })}", style = MaterialTheme.typography.bodySmall)
            Text("读完 ${attempt.paragraphs.count { it.completed }} / ${attempt.paragraphs.size} 段", style = MaterialTheme.typography.bodySmall)
            Text("暂停 ${attempt.pauses} 次", style = MaterialTheme.typography.bodySmall)
        }
        if (article != null && onReadOriginal != null) TextButton(onClick = { onReadOriginal(article.id) },
            contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("natural_history_original_${attempt.id}")) { Text("回到原文精读") }
        HorizontalDivider()
    }
}

@Composable
private fun SpeedFeedbackRow(correction: SpeedCorrection, article: Article?, onTrain: (String) -> Unit,
    onInspectCorrection: ((SpeedCorrection) -> Unit)?) {
    val savedPersonalSegmentation = correction.kind == "boundary_edit"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(article?.title ?: "已移除文章", style = MaterialTheme.typography.titleMedium.copy(fontFamily = ReadingSerif))
        Text(when (correction.kind) {
            "boundary_edit" -> "个人切分已保存"
            "merge_next" -> "这里不该断"
            "too_long" -> "这一块过长"
            else -> "其他切分问题"
        },
            style = MaterialTheme.typography.bodyMedium)
        if (!savedPersonalSegmentation && correction.comment.isNotBlank()) Text(correction.comment, style = MaterialTheme.typography.bodyMedium)
        Text("${speedDate(correction.createdAt)} · ${correction.granularity.name}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (savedPersonalSegmentation) "历史记录，不表示当前仍在使用。" else "待人工复核",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (onInspectCorrection != null) TextButton(onClick = { onInspectCorrection(correction) }, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.testTag("speed_inspect_correction_${correction.id}")) { Text("查看这句") }
            if (article != null) TextButton(onClick = { onTrain(article.id) }, contentPadding = PaddingValues(0.dp)) { Text("回到文章") }
        }
        HorizontalDivider()
    }
}

private fun speedDate(time: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
