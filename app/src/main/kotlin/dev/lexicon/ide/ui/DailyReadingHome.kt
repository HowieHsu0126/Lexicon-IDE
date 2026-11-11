@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lexicon.domain.*

@Composable
internal fun DailyReadingHome(articles: List<Article>, edition: DailyEdition?, busy: Boolean, onSync: () -> Unit,
    onOpen: (Article, StudyMode) -> Unit, onSources: () -> Unit, onHistory: () -> Unit, onSpeed: ((Article) -> Unit)? = null,
    onBaseline: ((Article) -> Unit)? = null, resumes: ReadingResumeLibrary = ReadingResumeLibrary(),
    onContinue: ((Article) -> Unit)? = null) {
    val selected = edition?.articleIds.orEmpty().mapNotNull { id -> articles.find { it.id == id } }.take(3)
    val continuations = remember(articles, resumes.records) {
        val known = articles.mapTo(HashSet()) { it.id }
        resumes.records.filter { !it.completed && it.articleId in known }
            .sortedWith(compareByDescending<ReadingResumeRecord> { it.updatedAt }.thenBy { it.articleId })
            .associateBy { it.articleId }
    }
    val recent = continuations.values.firstOrNull()
    LazyColumn(Modifier.fillMaxSize().testTag("daily_reading"), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)) {
        if (recent != null && onContinue != null) item(key = "continuation") {
            val article = articles.first { it.id == recent.articleId }
            Column(Modifier.fillMaxWidth().testTag("daily_continue_card"), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("继续阅读", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(article.title, style = MaterialTheme.typography.titleLarge.copy(fontFamily = ReadingSerif),
                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("daily_continue_title"))
                Text("${if (recent.controls.mode == StudyMode.SKIM) "略读" else "精读"} · 第 ${recent.paragraphIndex + 1} 段",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("daily_continue_position"))
                TextButton(onClick = { onContinue(article) }, modifier = Modifier.testTag("daily_continue")) { Text("继续 →") }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("今日阅读", style = MaterialTheme.typography.headlineSmall)
                    Text("${selected.size} / 3 篇", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onSync, enabled = !busy) { Text(if (busy) "同步中" else "刷新") }
            }
        }
        if (resumes.errors > 0) item {
            Text("部分续读记录暂不可用，仍可从文章开始阅读。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected.isEmpty()) item {
            Text("订阅喜欢的来源，每天最多三篇。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onSources) { Text("选择来源") }
        }
        items(selected, key = { it.id }) { article ->
            Column(Modifier.fillMaxWidth().animateItem(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(article.sourceName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(article.title, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = ReadingSerif, fontSize = 24.sp, lineHeight = 31.sp),
                    maxLines = 5, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onContinue != null && article.id in continuations) TextButton(onClick = { onContinue(article) },
                        modifier = Modifier.testTag("daily_continue_${article.id}")) { Text("继续阅读") }
                    Button(onClick = { onOpen(article, StudyMode.INTENSIVE) }, modifier = Modifier.testTag("daily_intensive_${article.id}")) { Text("精读") }
                    TextButton(onClick = { onOpen(article, StudyMode.SKIM) }, modifier = Modifier.testTag("daily_skim_${article.id}")) { Text("略读 · 5 分钟") }
                    if (onSpeed != null) TextButton(onClick = { onSpeed(article) }) { Text("意群速读") }
                    if (onBaseline != null) TextButton(onClick = { onBaseline(article) },
                        modifier = Modifier.testTag("daily_baseline_${article.id}")) { Text("无辅助自测") }
                    if (article.read) Text("已打开", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            }
        }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onSources) { Text("来源") }
            TextButton(onClick = onHistory) { Text("读过的文章") }
        } }
    }
}
