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
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.Article
import dev.lexicon.domain.ReadingBaselineAttempt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A saved-attempt index. The caller loads its frozen plan before showing scored results. */
@Composable
internal fun BaselineHistoryScreen(attempts: List<ReadingBaselineAttempt>, articles: List<Article>, onBack: () -> Unit,
    onOpen: (String) -> Unit) {
    val sorted = remember(attempts) { attempts.sortedByDescending { it.finishedAt } }
    val articleMap = remember(articles) { articles.associateBy { it.id } }
    LazyColumn(Modifier.fillMaxSize().testTag("baseline_history"), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("无辅助自测记录", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onBack) { Text("返回") }
            }
        }
        if (sorted.isEmpty()) item {
            Text("从今日文章的“无辅助自测”开始。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(sorted, key = { it.id }) { attempt ->
            Column(Modifier.fillMaxWidth().testTag("baseline_history_${attempt.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(articleMap[attempt.articleId]?.title ?: "已移除文章", style = MaterialTheme.typography.titleMedium.copy(fontFamily = ReadingSerif))
                val status = when {
                    attempt.completed -> "已完成"
                    attempt.readingFinishedAt != null -> "待继续答题"
                    else -> "阅读未完成"
                }
                Text("${baselineDate(attempt.finishedAt)} · $status",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("计时 ${naturalDuration(attempt.paragraphs.sumOf { it.activeMillis })}", style = MaterialTheme.typography.bodySmall)
                    Text("读完 ${attempt.paragraphs.count { it.completed }} / ${attempt.paragraphs.size} 段", style = MaterialTheme.typography.bodySmall)
                    Text("已答 ${attempt.answers.size} / ${attempt.questionCount} 题", style = MaterialTheme.typography.bodySmall)
                }
                if (!attempt.localUnseen || !attempt.userConfirmedUnseen) Text("练习记录", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onOpen(attempt.id) }, contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.testTag("baseline_open_${attempt.id}")) {
                    Text(when {
                        attempt.completed -> "查看记录"
                        attempt.readingFinishedAt != null -> "继续答题"
                        else -> "继续自测"
                    })
                }
                HorizontalDivider()
            }
        }
    }
}

private fun baselineDate(time: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
