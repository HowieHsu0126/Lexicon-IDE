@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lexicon.domain.*
import dev.lexicon.domain.Card as LearningCard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
internal fun KnowledgeScreen(
    library: ReadingLibrary,
    busy: Boolean,
    onSaveNote: (ReadingNote) -> Unit,
    onDeleteNote: (String) -> Unit,
    onReview: () -> Unit,
    onExport: () -> Unit,
    onOpenArticle: (String) -> Unit,
    noteBusy: Boolean = false,
    noteError: String? = null,
    onSaveNoteAsync: ((ReadingNote, () -> Unit, (String) -> Unit) -> Unit)? = null,
    onOpenNote: ((ReadingNote) -> Unit)? = null,
    backgroundRepository: BackgroundRepository? = null,
) {
    var backgroundTab by rememberSaveable { mutableStateOf(false) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var mapsOnly by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ReadingNote?>(null) }
    var inspecting by remember { mutableStateOf<ReadingNote?>(null) }
    var deleting by remember { mutableStateOf<ReadingNote?>(null) }
    var reviewExpanded by rememberSaveable { mutableStateOf(false) }
    val notes = remember(library.notes) { library.notes.distinctBy { it.id }.sortedByDescending { it.createdAt } }
    val tags = remember(notes) { notes.flatMap { it.tags }.distinct().sorted() }
    val visible = remember(notes, tag, mapsOnly) { notes.filter { (tag == null || tag in it.tags) && (!mapsOnly || it.mindMap) } }
    val latestReview = library.reviews.maxByOrNull { it.createdAt }

    Column(Modifier.fillMaxSize().testTag("knowledge_screen")) {
    if (backgroundRepository != null) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilterChip(!backgroundTab, { backgroundTab = false }, label = { Text("阅读笔记") }, modifier = Modifier.testTag("knowledge_tab_notes"))
        FilterChip(backgroundTab, { backgroundTab = true }, label = { Text("背景资料") }, modifier = Modifier.testTag("knowledge_tab_background"))
    }
    if (backgroundTab && backgroundRepository != null) {
        BackgroundKnowledgePanel(library.backgroundRecords, library.backgroundRecordErrors, backgroundRepository, onExport)
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "knowledge_header") {
            InsightHeading("知识库", "原文里的发现，和你自己的理解。")
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExport, enabled = (notes.isNotEmpty() || library.backgroundRecords.isNotEmpty()) && !busy, modifier = Modifier.weight(1f).testTag("knowledge_export")) { Text("导出 Markdown") }
                FilledTonalButton(onClick = onReview, enabled = notes.isNotEmpty() && !busy, modifier = Modifier.weight(1f).testTag("knowledge_review")) { Text(if (busy) "处理中…" else "表达回顾") }
            }
            Text("导出文件可手动导入 Notion。", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (latestReview != null) item(key = "knowledge_review_result") {
            InsightPanel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AI 表达回顾", style = MaterialTheme.typography.titleSmall)
                        Text(insightDate(latestReview.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { reviewExpanded = !reviewExpanded }) { Text(if (reviewExpanded) "收起" else "展开") }
                }
                Text(latestReview.title, style = MaterialTheme.typography.bodyMedium)
                AnimatedVisibility(reviewExpanded) {
                    SelectionContainer { Text(latestReview.text, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        item(key = "knowledge_filters") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                FilterChip(tag == null && !mapsOnly, { tag = null; mapsOnly = false }, label = { Text("全部 ${notes.size}") })
                FilterChip(mapsOnly, { mapsOnly = !mapsOnly }, label = { Text("导图笔记") }, modifier = Modifier.testTag("knowledge_map_filter"))
                tags.forEach { value -> FilterChip(tag == value, { tag = if (tag == value) null else value }, label = { Text("# $value") }, modifier = Modifier.testTag("knowledge_tag_$value")) }
            }
        }
        if (mapsOnly && visible.isNotEmpty()) item(key = "knowledge_map") {
            InsightPanel {
                Text("标签导图", style = MaterialTheme.typography.titleMedium)
                Text("标签连接摘录；点选摘录查看完整笔记与出处。", Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TaggedKnowledgeMap(visible.take(12), onNote = { inspecting = it })
                if (visible.size > 12) Text("导图展示最近 12 条；下方列表保留当前分组的全部 ${visible.size} 条笔记。",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (visible.isEmpty()) item(key = "knowledge_empty") {
            InsightEmpty(if (notes.isEmpty()) "第一条笔记，从阅读中开始" else "这个分组还没有笔记",
                if (notes.isEmpty()) "在原文中选中一句话，记录想法或加上标签。摘录会和出处一起保存在这里。" else "切换标签或查看全部笔记。")
        }
        items(visible, key = { it.id }) { note ->
            InsightPanel(Modifier.animateItem()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(insightDate(note.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(enabled = !busy && !noteBusy, onClick = { onSaveNote(note.copy(mindMap = !note.mindMap)) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(if (note.mindMap) "✓ 已加入导图" else "+ 加入导图", style = MaterialTheme.typography.labelMedium)
                    }
                }
                NoteOriginLabel(note.origin, Modifier.padding(bottom = 8.dp).testTag("note_origin_${note.id}_list"))
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.width(2.dp).height(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .6f)))
                    SelectionContainer(Modifier.padding(start = 12.dp).weight(1f)) { Text(note.quote,
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = ReadingSerif, lineHeight = 27.sp)) }
                }
                if (note.comment.isNotBlank()) {
                    Text("我的想法", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer { Text(note.comment, style = MaterialTheme.typography.bodyMedium) }
                }
                if (note.tags.isNotEmpty()) FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    note.tags.forEach { value -> TextButton(onClick = { tag = value }, contentPadding = PaddingValues(horizontal = 2.dp)) { Text("# $value", style = MaterialTheme.typography.labelMedium) } }
                }
                val sourceTitle = note.source.title.orEmpty().ifBlank { note.source.source.orEmpty().ifBlank { "未关联来源" } }
                val linkedArticle = note.articleId
                HorizontalDivider(Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(sourceTitle, Modifier.weight(1f).testTag("note_source_${note.id}").then(if (linkedArticle != null) Modifier.clickable {
                        if (onOpenNote != null) onOpenNote(note) else onOpenArticle(linkedArticle)
                    } else Modifier)
                        .padding(vertical = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium, color = if (note.articleId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(enabled = !busy && !noteBusy, onClick = { editing = note }) { Text("编辑") }
                    TextButton(enabled = !busy && !noteBusy, onClick = { deleting = note }) { Text("删除", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    }
    editing?.let { note ->
        NoteEditDialog(note, noteBusy, noteError, onDismiss = { editing = null }, onSave = { updated, saved, failed ->
            if (onSaveNoteAsync != null) onSaveNoteAsync(updated, saved, failed)
            else { onSaveNote(updated); saved() }
        })
    }
    inspecting?.let { note ->
        val linkedArticle = note.articleId
        AlertDialog(onDismissRequest = { inspecting = null }, title = { Text("阅读笔记") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NoteOriginLabel(note.origin, Modifier.testTag("note_origin_${note.id}_detail"))
                SelectionContainer { Text(note.quote, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = ReadingSerif)) }
                if (note.comment.isNotBlank()) {
                    Text("我的想法", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(note.comment, style = MaterialTheme.typography.bodyMedium)
                }
                if (note.tags.isNotEmpty()) Text(note.tags.joinToString("  ") { "# $it" }, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(note.source.display(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = {
            Row {
                TextButton(onClick = { editing = note; inspecting = null }) { Text("编辑") }
                if (linkedArticle != null) TextButton(onClick = {
                    inspecting = null
                    if (onOpenNote != null) onOpenNote(note) else onOpenArticle(linkedArticle)
                }) { Text("阅读原文") }
            }
        }, dismissButton = { TextButton(onClick = { inspecting = null }) { Text("关闭") } })
    }
    deleting?.let { note -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这条笔记？") },
        text = { Text("原文和已保存的生词卡会保留。") },
        confirmButton = { TextButton(onClick = { onDeleteNote(note.id); deleting = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun NoteEditDialog(
    note: ReadingNote,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (ReadingNote, () -> Unit, (String) -> Unit) -> Unit,
) {
    var comment by rememberSaveable(note.id) { mutableStateOf(note.comment) }
    var tags by rememberSaveable(note.id) { mutableStateOf(note.tags.joinToString("，")) }
    var pending by remember(note.id) { mutableStateOf(false) }
    var failure by remember(note.id) { mutableStateOf<String?>(null) }
    var attempt by remember(note.id) { mutableIntStateOf(0) }
    val saving = pending || busy
    val visibleError = if (pending) null else failure ?: error?.takeIf { attempt > 0 }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("编辑笔记") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NoteOriginLabel(note.origin, Modifier.testTag("note_origin_${note.id}_edit"))
            Text(note.quote, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = ReadingSerif), color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 5, overflow = TextOverflow.Ellipsis)
            OutlinedTextField(comment, { comment = it.take(8000) }, Modifier.fillMaxWidth().testTag("note_comment"),
                label = { Text("我的想法") }, minLines = 3, maxLines = 8, enabled = !saving)
            OutlinedTextField(tags, { tags = it.take(400) }, Modifier.fillMaxWidth().testTag("note_tags"), label = { Text("标签") },
                supportingText = { Text("用逗号分隔，例如：写作，科技，转折表达") }, enabled = !saving)
            if (visibleError != null) Text(visibleError, Modifier.testTag("note_save_error").semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = !saving, modifier = Modifier.testTag("note_save"), onClick = {
        if (pending || busy) return@TextButton
        val parsed = tags.split(',', '，', ';', '；', '\n').map { it.trim().removePrefix("#").trim().take(40) }.filter { it.isNotBlank() }.distinct().take(20)
        pending = true
        failure = null
        val request = ++attempt
        var completed = false
        val saved = {
            if (!completed && request == attempt) {
                completed = true
                pending = false
                onDismiss()
            }
        }
        val failed: (String) -> Unit = { message ->
            if (!completed && request == attempt) {
                completed = true
                pending = false
                failure = message.take(140).ifBlank { "笔记未保存，请重试" }
            }
        }
        try { onSave(note.copy(comment = comment.trim(), tags = parsed), saved, failed) }
        catch (e: kotlinx.coroutines.CancellationException) { pending = false; throw e }
        catch (_: Exception) { failed("笔记未保存，请重试") }
    }) { Text(if (saving) "保存中…" else if (visibleError != null) "重试保存" else "保存") } },
        dismissButton = { TextButton(enabled = !saving, modifier = Modifier.testTag("note_cancel"), onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun NoteOriginLabel(origin: ContextOrigin, modifier: Modifier = Modifier) {
    Text(when (origin) {
        ContextOrigin.ORIGINAL -> "原文片段"
        ContextOrigin.AI_GENERATED -> "AI 改写片段"
        ContextOrigin.USER_SUPPLIED -> "用户补充片段"
        ContextOrigin.MISSING -> "来源未确认"
    }, modifier, style = MaterialTheme.typography.labelSmall,
        color = if (origin == ContextOrigin.AI_GENERATED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
}

private enum class ProgressMetric(val label: String, val unit: String) {
    SPEED("阅读速度估计", "词 / 分钟"), TIME("阅读时长", "分钟"), FEEDBACK("理解反馈", "AI 评分 / 100"), ASSISTANCE("成功辅助操作", "次 / 10 分钟")
}
private data class ProgressPoint(val day: LocalDate, val value: Double)

@Composable
internal fun ProgressScreen(library: ReadingLibrary, cards: List<LearningCard>, onRead: () -> Unit,
    baselineSummary: BaselineProgressSummary? = null,
    baselineLoading: Boolean = false,
    baselineError: String? = null,
    onBaselineRetry: () -> Unit = {},
    onBaselineOpen: (String) -> Unit = {},
    onBaselineHistory: () -> Unit = {},
) {
    val progress = remember(library) { ReadingProgressCalculator.summarize(library) }
    var metric by rememberSaveable { mutableStateOf(ProgressMetric.SPEED) }
    val points = remember(library, metric) { progressPoints(library, metric) }
    val modeCompletions = remember(library.sessions) {
        library.sessions.distinctBy { it.id }.filter { it.activeMillis >= 0 && it.wordsRead >= 0 && it.completed }.groupingBy { it.mode }.eachCount()
    }
    LazyColumn(Modifier.fillMaxSize().testTag("progress_screen"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item(key = "progress_heading") { InsightHeading("阅读轨迹", "把过程留下，变化才有迹可循。") }
        if (baselineSummary != null || baselineLoading || baselineError != null) item(key = "baseline_progress") {
            BaselineProgressPanel(baselineSummary, baselineLoading, baselineError, onBaselineRetry, onBaselineOpen, onBaselineHistory)
        }
        item(key = "progress_summary") {
            if (LocalDensity.current.fontScale > 1.35f) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProgressStat("完成阅读", "${progress.completed}", "次", Modifier.fillMaxWidth())
                    ProgressStat("有效阅读", formatMeasure(progress.activeMinutes), "分钟", Modifier.fillMaxWidth())
                    ProgressStat("阅读速度估计", progress.wordsPerMinute?.let { formatMeasure(it) } ?: "—", "词 / 分钟", Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProgressStat("完成阅读", "${progress.completed}", "次", Modifier.weight(1f))
                    ProgressStat("有效阅读", formatMeasure(progress.activeMinutes), "分钟", Modifier.weight(1f))
                    ProgressStat("阅读速度估计", progress.wordsPerMinute?.let { formatMeasure(it) } ?: "—", "词 / 分钟", Modifier.weight(1f))
                }
            }
        }
        item(key = "progress_trend") {
            InsightPanel {
                Text("随时间回看", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProgressMetric.entries.forEach { item -> FilterChip(metric == item, { metric = item }, label = { Text(item.label) }, modifier = Modifier.testTag("progress_metric_${item.name.lowercase()}")) }
                }
                if (points.isEmpty()) {
                    InsightEmpty("还没有${metric.label}记录", when (metric) {
                        ProgressMetric.SPEED -> "完成至少 30 秒的阅读后，这里会显示按记录字数与有效用时计算的速度。"
                        ProgressMetric.TIME -> "阅读时积累的有效用时会出现在这里。"
                        ProgressMetric.FEEDBACK -> "完成原文理解练习后，这里会显示 AI 对答案的反馈分数。"
                        ProgressMetric.ASSISTANCE -> "有效阅读累计至少 30 秒后，显示成功辅助操作次数与阅读时间的比值。"
                    })
                } else {
                    Text(metric.unit, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ReadingTrend(points, metric)
                    val latest = points.last()
                    Text("最近记录 · ${latest.day.format(DateTimeFormatter.ofPattern("M月d日"))} · ${formatMeasure(latest.value)} ${metric.unit}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(when (metric) {
                    ProgressMetric.FEEDBACK -> "分数来自 AI 对练习答案的评估，不代表考试成绩或能力测量。"
                    ProgressMetric.ASSISTANCE -> "按成功辅助操作次数与有效阅读时间换算，包含缓存结果；不表示付费 API 调用量或能力评分。"
                    else -> "只显示本机已记录的数据；没有阅读记录的日期不补零。"
                }, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "progress_mode_growth") {
            InsightPanel {
                Text("两种阅读，各自积累", style = MaterialTheme.typography.titleMedium)
                listOf(StudyMode.INTENSIVE to "精读", StudyMode.SKIM to "略读").forEach { (mode, label) ->
                    val completed = modeCompletions[mode] ?: 0
                    Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("growth_${mode.name.lowercase()}"), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Text(if (completed == 0) "尚未完成" else "$completed 次完成 · ${completed.toLong() * 10} 阅读点数",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf(1 to "首次$label", 3 to "三次$label").forEach { (required, title) ->
                                Text("${if (completed >= required) "✓" else "○"} $title", style = MaterialTheme.typography.labelSmall,
                                    color = if (completed >= required) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Text("每完成一次阅读记录 +10 点；点数表示记录积累，不是能力分数。", Modifier.padding(top = 14.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "progress_topics") {
            InsightPanel {
                Text("不同主题，分别回看", style = MaterialTheme.typography.titleMedium)
                if (progress.topics.isEmpty()) Text("阅读后会按主题整理记录。", Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                progress.topics.forEach { topic ->
                    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(topic.topic, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(topic.comprehensionPercent?.let { "AI ${it.roundToInt()} / 100" } ?: "暂无理解反馈",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        topic.comprehensionPercent?.let { score -> LinearProgressIndicator(progress = { (score / 100).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(3.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f)) }
                        Text("${topic.completed} 次完成 · ${formatMeasure(topic.activeMinutes)} 分钟", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (progress.topics.isNotEmpty()) Text("主题分数是相关练习的 AI 反馈均值。", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "progress_growth") {
            InsightPanel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ReadingSprig(progress.badges.count { it.achieved })
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("一点点积累", style = MaterialTheme.typography.titleMedium)
                        Text("${library.notes.distinctBy { it.id }.size} 条笔记 · ${cards.size} 张生词卡", style = MaterialTheme.typography.bodySmall)
                        Text("${progress.readingDays} 个有阅读记录的日子", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                progress.badges.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { badge ->
                            Surface(Modifier.weight(1f), color = if (badge.achieved) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f) else Color.Transparent,
                                shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("${if (badge.achieved) "✓" else "○"} ${badge.title}", style = MaterialTheme.typography.labelLarge,
                                        color = if (badge.achieved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(badge.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "progress_read") {
            FilledTonalButton(onClick = onRead, modifier = Modifier.fillMaxWidth()) { Text("回到阅读") }
            Text(progress.note, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProgressStat(title: String, value: String, unit: String, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = EditorMono), maxLines = 1)
            Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun progressPoints(library: ReadingLibrary, metric: ProgressMetric): List<ProgressPoint> {
    val zone = ZoneId.of("Asia/Shanghai")
    fun day(time: Long) = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    val sessions = library.sessions.distinctBy { it.id }.filter { it.activeMillis >= 0 && it.wordsRead >= 0 }
    return when (metric) {
        ProgressMetric.SPEED -> sessions.groupBy { day(it.startedAt) }.mapNotNull { (day, rows) ->
            val elapsed = rows.sumOf { it.activeMillis }; val words = rows.sumOf { it.wordsRead.toLong() }
            if (elapsed < 30_000 || words <= 0) null else ProgressPoint(day, words * 60000.0 / elapsed) }
        ProgressMetric.TIME -> sessions.filter { it.activeMillis > 0 }.groupBy { day(it.startedAt) }.map { (day, rows) -> ProgressPoint(day, rows.sumOf { it.activeMillis } / 60000.0) }
        ProgressMetric.FEEDBACK -> library.checks.distinctBy { it.id }.filter { it.score.isFinite() && it.score in 0.0..1.0 }
            .groupBy { day(it.createdAt) }.map { (day, rows) -> ProgressPoint(day, rows.map { it.score }.average() * 100) }
        ProgressMetric.ASSISTANCE -> sessions.filter { it.assistanceCount >= 0 }.groupBy { day(it.startedAt) }.mapNotNull { (day, rows) ->
            val elapsed = rows.sumOf { it.activeMillis }; val requests = rows.sumOf { it.assistanceCount.toLong() }
            if (elapsed < 30_000) null else ProgressPoint(day, requests * 600000.0 / elapsed) }
    }.sortedBy { it.day }.takeLast(14)
}

@Composable
private fun ReadingTrend(points: List<ProgressPoint>, metric: ProgressMetric) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val accessible = points.joinToString("，") { "${it.day} ${formatMeasure(it.value)}" }
    Canvas(Modifier.fillMaxWidth().height(190.dp).semantics { contentDescription = "${metric.label}，$accessible" }) {
        val left = 38.dp.toPx(); val top = 18.dp.toPx(); val right = size.width - 10.dp.toPx(); val bottom = size.height - 28.dp.toPx()
        if (right <= left || bottom <= top) return@Canvas
        val quantum = when (metric) { ProgressMetric.SPEED -> 50.0; ProgressMetric.TIME -> 10.0; ProgressMetric.FEEDBACK -> 100.0; ProgressMetric.ASSISTANCE -> 1.0 }
        val ceiling = if (metric == ProgressMetric.FEEDBACK) 100.0 else max(quantum, ceil(points.maxOf { it.value } / quantum) * quantum)
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = label.toArgb(); textSize = 10.sp.toPx(); typeface = Typeface.MONOSPACE }
        repeat(3) { index ->
            val fraction = index / 2f
            val y = bottom - (bottom - top) * fraction
            drawLine(grid, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
            drawContext.canvas.nativeCanvas.drawText(formatMeasure(ceiling * fraction), 0f, y + 3.dp.toPx(), labelPaint)
        }
        val firstDay = points.first().day.toEpochDay(); val span = max(1L, points.last().day.toEpochDay() - firstDay)
        val positions = points.map { point -> Offset(if (points.size == 1) (left + right) / 2 else left + (right - left) * ((point.day.toEpochDay() - firstDay).toFloat() / span),
            bottom - (bottom - top) * (point.value / ceiling).toFloat().coerceIn(0f, 1f)) }
        if (positions.size > 1) {
            val path = Path().apply { moveTo(positions[0].x, positions[0].y); positions.drop(1).forEach { lineTo(it.x, it.y) } }
            drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
        }
        positions.forEach { drawCircle(line, 3.5.dp.toPx(), it) }
        val format = DateTimeFormatter.ofPattern("M/d")
        drawContext.canvas.nativeCanvas.drawText(points.first().day.format(format), left, size.height - 5.dp.toPx(), labelPaint)
        if (points.size > 1) {
            val text = points.last().day.format(format)
            drawContext.canvas.nativeCanvas.drawText(text, right - labelPaint.measureText(text), size.height - 5.dp.toPx(), labelPaint)
        }
    }
}

@Composable
private fun ReadingSprig(growth: Int) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(64.dp).semantics { contentDescription = "阅读记录图案" }) {
        val x = size.width / 2; val y = size.height * .85f
        drawLine(color.copy(alpha = .65f), Offset(x, y), Offset(x, size.height * .22f), 1.5.dp.toPx())
        val leaves = (growth + 1).coerceIn(1, 4)
        repeat(leaves) { index ->
            val left = index % 2 == 0; val cy = size.height * (.65f - index * .12f)
            val end = Offset(x + if (left) -size.width * .25f else size.width * .25f, cy - size.height * .12f)
            val leaf = Path().apply { moveTo(x, cy); quadraticTo(end.x, cy, end.x, end.y); quadraticTo(x, end.y, x, cy); close() }
            drawPath(leaf, color.copy(alpha = .20f)); drawPath(leaf, color.copy(alpha = .7f), style = Stroke(1.dp.toPx()))
        }
        drawLine(color.copy(alpha = .3f), Offset(x - 14.dp.toPx(), y), Offset(x + 14.dp.toPx(), y), 1.dp.toPx())
    }
}

/** A note is drawn once; each real tag has its own connection to that same note. */
@Composable
private fun TaggedKnowledgeMap(notes: List<ReadingNote>, onNote: (ReadingNote) -> Unit) {
    val noteTags = remember(notes) { notes.associate { note -> note.id to note.tags.filter { it.isNotBlank() }.distinct().ifEmpty { listOf("未加标签") } } }
    val tags = remember(noteTags) { noteTags.values.flatten().distinct().sorted() }
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val tagWidth = 112.dp; val quoteWidth = 186.dp; val gap = 42.dp
    val nodeHeight = (118f * scale).dp; val tagHeight = (58f * scale).dp; val rowStep = nodeHeight + 18.dp
    val graphWidth = tagWidth + gap + quoteWidth
    val graphHeight = rowStep * max(tags.size, notes.size)
    val linkColor = MaterialTheme.colorScheme.primary.copy(alpha = .3f)
    Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp).padding(vertical = 12.dp)
        .horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState()).testTag("knowledge_tag_map")) {
        Box(Modifier.size(graphWidth, graphHeight)) {
            Canvas(Modifier.matchParentSize()) {
                notes.forEachIndexed { noteIndex, note ->
                    noteTags[note.id].orEmpty().forEach { tag ->
                        val tagIndex = tags.indexOf(tag)
                        if (tagIndex < 0) return@forEach
                        val from = Offset(tagWidth.toPx(), rowStep.toPx() * tagIndex + nodeHeight.toPx() / 2)
                        val to = Offset((tagWidth + gap).toPx(), rowStep.toPx() * noteIndex + nodeHeight.toPx() / 2)
                        val middle = (from.x + to.x) / 2
                        drawPath(Path().apply { moveTo(from.x, from.y); cubicTo(middle, from.y, middle, to.y, to.x, to.y) },
                            linkColor, style = Stroke(1.5.dp.toPx()))
                    }
                }
            }
            tags.forEachIndexed { index, tag ->
                Surface(Modifier.offset(0.dp, rowStep * index + (nodeHeight - tagHeight) / 2).size(tagWidth, tagHeight),
                    shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .25f))) {
                    Box(Modifier.padding(10.dp), contentAlignment = Alignment.CenterStart) {
                        Text(if (tag == "未加标签") tag else "# $tag", style = MaterialTheme.typography.labelMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            notes.forEachIndexed { index, note ->
                Surface(Modifier.offset(tagWidth + gap, rowStep * index).size(quoteWidth, nodeHeight)
                    .clickable { onNote(note) }.testTag("knowledge_map_note_${note.id}"),
                    color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        NoteOriginLabel(note.origin, Modifier.testTag("note_origin_${note.id}_map"))
                        Text(note.quote, style = MaterialTheme.typography.bodySmall.copy(fontFamily = ReadingSerif), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text("查看笔记与出处 ↗", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

private data class MindMapOutlineRow(
    val node: MindMapNode, val depth: Int, val lastSibling: Boolean,
    val ancestorContinues: List<Boolean>, val children: Int,
)

/** A phone-width outline: the root stays first, and deeper branches expand without a second scroll container. */
@Composable
internal fun MindMapView(nodes: List<MindMapNode>, onParagraph: (Int) -> Unit) {
    val unique = remember(nodes) { nodes.distinctBy { it.id } }
    if (unique.isEmpty()) {
        InsightEmpty("导图待生成", "生成文章分析后，可以沿着观点结构跳转到原文。")
        return
    }
    val roots = remember(unique) {
        val ids = unique.map { it.id }.toSet()
        unique.filter { it.parentId == null || it.parentId !in ids }.ifEmpty { listOf(unique.first()) }
    }
    val identity = remember(unique) { unique.joinToString("|") { "${it.id}:${it.parentId}:${it.label}" } }
    var expanded by rememberSaveable(identity) { mutableStateOf(roots.map { it.id }) }
    val rows = remember(unique, expanded) { mindMapOutline(unique, roots, expanded.toSet()) }
    val connector = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth().animateContentSize(tween(180)).testTag("study_mind_map")) {
        Text("展开分支看细节，点段落编号回到原文。", Modifier.padding(bottom = 12.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        rows.forEach { row -> key(row.node.id) {
            val node = row.node
            val paragraph = node.paragraph
            val open = node.id in expanded
            Box(Modifier.fillMaxWidth().drawBehind {
                val step = 14.dp.toPx(); val middle = (size.height - 8.dp.toPx()) / 2
                val stroke = 1.25.dp.toPx()
                row.ancestorContinues.forEachIndexed { level, continues ->
                    if (continues) drawLine(connector, Offset(level * step + step / 2, 0f), Offset(level * step + step / 2, size.height), stroke)
                }
                if (row.depth > 0) {
                    val x = (row.depth - 1) * step + step / 2
                    drawLine(connector, Offset(x, 0f), Offset(x, if (row.lastSibling) middle else size.height), stroke)
                    drawLine(connector, Offset(x, middle), Offset(row.depth * step, middle), stroke)
                }
                if (open && row.children > 0) {
                    val x = row.depth * step + step / 2
                    drawLine(connector, Offset(x, size.height - 8.dp.toPx()), Offset(x, size.height), stroke)
                }
            }) {
                Surface(Modifier.fillMaxWidth().padding(start = (row.depth * 14).dp, bottom = 8.dp).testTag("mind_map_node_${node.id}"),
                    shape = RoundedCornerShape(12.dp),
                    color = if (row.depth == 0) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f) else MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (row.children > 0) {
                            IconButton(onClick = { expanded = if (open) expanded - node.id else expanded + node.id },
                                modifier = Modifier.testTag("mind_map_toggle_${node.id}").semantics {
                                    contentDescription = "${if (open) "收起" else "展开"}${node.label}，${row.children} 个子节点"
                                    stateDescription = if (open) "已展开" else "已收起"
                                }) {
                                Text(if (open) "▾" else "▸", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        } else Spacer(Modifier.width(8.dp))
                        Text(node.label, Modifier.weight(1f).padding(vertical = 4.dp),
                            style = if (row.depth == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall)
                        if (paragraph != null) {
                            TextButton(onClick = { onParagraph(paragraph) }, contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.testTag("mind_map_paragraph_${node.id}").semantics { contentDescription = "回到原文第 ${paragraph + 1} 段" }) {
                                Text("第 ${paragraph + 1} 段 ↗", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        } }
    }
}

private fun mindMapOutline(nodes: List<MindMapNode>, roots: List<MindMapNode>, expanded: Set<String>): List<MindMapOutlineRow> {
    val children = nodes.groupBy { it.parentId }
    val seen = HashSet<String>()
    val result = ArrayList<MindMapOutlineRow>()
    fun visit(node: MindMapNode, depth: Int, last: Boolean, ancestors: List<Boolean>) {
        if (!seen.add(node.id)) return
        val branch = children[node.id].orEmpty().filter { it.id !in seen }
        result += MindMapOutlineRow(node, depth, last, ancestors, branch.size)
        if (node.id in expanded) branch.forEachIndexed { index, child ->
            visit(child, depth + 1, index == branch.lastIndex, if (depth == 0) emptyList() else ancestors + !last)
        }
    }
    roots.forEachIndexed { index, root -> visit(root, 0, index == roots.lastIndex, emptyList()) }
    return result
}

@Composable
internal fun ReaderAppearanceSheet(prefs: ReaderPreferences, onChange: (ReaderPreferences) -> Unit, onDismiss: () -> Unit) {
    var draft by remember(prefs) { mutableStateOf(prefs.bounded()) }
    fun commit(next: ReaderPreferences) { draft = next.bounded(); onChange(draft) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("阅读外观", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text("完成") }
            }
            Surface(color = readerBackground(draft), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Text("Good reading leaves room for thought.", Modifier.padding(18.dp).testTag("reader_appearance_preview"), style = readerTextStyle(draft))
            }
            Text("字体", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(ReaderFont.SERIF to "衬线", ReaderFont.MONO to "等宽", ReaderFont.SYSTEM to "系统", ReaderFont.OPEN_DYSLEXIC to "OpenDyslexic").forEach { (font, label) ->
                    FilterChip(draft.font == font, { commit(draft.copy(font = font)) }, label = { Text(label) })
                }
            }
            AppearanceSlider("字号", "${draft.sizeSp.roundToInt()} sp", draft.sizeSp, 14f..32f, 17,
                { draft = draft.copy(sizeSp = it) }, { commit(draft) }, "reader_size")
            AppearanceSlider("行高", String.format(Locale.ROOT, "%.1f 倍", draft.lineHeight), draft.lineHeight, 1.2f..3f, 17,
                { draft = draft.copy(lineHeight = it) }, { commit(draft) }, "reader_line_height")
            AppearanceSlider("字间距", String.format(Locale.ROOT, "%.2f sp", draft.letterSpacingSp), draft.letterSpacingSp, 0f..4f, 15,
                { draft = draft.copy(letterSpacingSp = it) }, { commit(draft) }, "reader_letter_spacing")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("舒适预设", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(ReadingComfort.DEFAULT to "标准", ReadingComfort.LOW_STRESS to "低视觉负担", ReadingComfort.HIGH_CONTRAST to "高对比", ReadingComfort.NIGHT_PAPER to "夜间暖纸").forEach { (comfort, label) ->
                    FilterChip(draft.comfort == comfort, { commit(draft.copy(comfort = comfort)) }, label = { Text(label) })
                }
            }
            Text("纸面底色", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("default" to "跟随主题", "paper" to "暖纸", "sage" to "浅苔", "mist" to "雾灰").forEach { (background, label) ->
                    FilterChip(draft.background == background, { commit(draft.copy(background = background, comfort = if (draft.comfort in setOf(ReadingComfort.NIGHT_PAPER, ReadingComfort.HIGH_CONTRAST)) ReadingComfort.DEFAULT else draft.comfort)) }, label = { Text(label) })
                }
            }
            Text("预览会即时更新，完成调整后继续阅读。", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { commit(ReaderPreferences(automaticStudy = draft.automaticStudy)) }) { Text("恢复阅读外观默认值") }
        }
    }
}

@Composable
private fun AppearanceSlider(label: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    onValue: (Float) -> Unit, onFinished: () -> Unit, tag: String) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value, onValue, onValueChangeFinished = onFinished, valueRange = range, steps = steps, modifier = Modifier.fillMaxWidth().testTag(tag))
    }
}

@Composable
private fun InsightHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InsightPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun InsightEmpty(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun insightDate(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("M月d日 · HH:mm"))
private fun formatMeasure(value: Double): String = if (value >= 100 || value % 1 == 0.0) value.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", value)
