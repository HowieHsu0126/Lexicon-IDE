@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.*

private data class SkimPagerState(val identity: String, val page: Int = 0, val expanded: Boolean = false, val initialized: Boolean = false) {
    companion object {
        val Saver = listSaver<SkimPagerState, Any>(save = { listOf(it.identity, it.page, it.expanded, it.initialized) },
            restore = { SkimPagerState(it[0] as String, it[1] as Int, it[2] as Boolean, it[3] as Boolean) })
    }
}

/** A bounded navigation view; all clicks retain the rendered snapshot's original paragraph indices. */
@Composable
internal fun SkimParagraphNavigator(body: ArticleBody?, directory: SkimDirectory?, focusedParagraph: Int, onParagraph: (Int) -> Unit) {
    var saved by rememberSaveable(stateSaver = SkimPagerState.Saver) { mutableStateOf(SkimPagerState(directory?.identity.orEmpty())) }
    // Retain paging while the same snapshot is loading, but never expose stale ranges during that gap.
    if (body == null || directory == null || directory.articleId != body.articleId || directory.bodyHash != body.contentHash) return
    val state = saved.takeIf { it.identity == directory.identity } ?: SkimPagerState(directory.identity)
    SideEffect { if (saved.identity != directory.identity) saved = state }
    val page = SkimNavigation.page(directory, state.page)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { saved = state.copy(expanded = !state.expanded, initialized = true,
            page = if (state.initialized) page.index else SkimNavigation.initialPage(directory, focusedParagraph)) },
            modifier = Modifier.heightIn(min = 48.dp).testTag("skim_directory_toggle")
            .semantics { stateDescription = if (state.expanded) "已展开" else "已收起" }, contentPadding = PaddingValues(0.dp)) {
            Text("段落导航 · ${directory.entries.size} 段 ${if (state.expanded) "−" else "+"}")
        }
        if (state.expanded) {
            Text("已选 ${page.totalEntries} / 全文 ${directory.totalParagraphs} 段", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (page.entries.isEmpty()) Text("没有匹配段落，请调整筛选。", style = MaterialTheme.typography.bodySmall)
            else {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { saved = state.copy(page = page.index - 1) }, enabled = page.hasPrevious,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("skim_directory_previous")) { Text("上一页") }
                    Text("${page.index + 1} / ${page.pageCount}", Modifier.padding(vertical = 14.dp).testTag("skim_directory_page"), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { saved = state.copy(page = page.index + 1) }, enabled = page.hasNext,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("skim_directory_next")) { Text("下一页") }
                    TextButton(onClick = { saved = state.copy(page = SkimNavigation.initialPage(directory, focusedParagraph)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("当前段附近") }
                }
                page.entries.forEach { entry -> key(entry.key) {
                    val annotation = entry.annotation
                    val excerpt = if (annotation != null) "${paragraphRoleLabel(annotation.role)} · ${annotation.summary}"
                        else body.text.substring(entry.start, entry.endExclusive)
                    TextButton(onClick = { onParagraph(entry.paragraphIndex) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("${if (annotation == null) "skim_rendered_paragraph" else "skim_study_paragraph"}_${entry.paragraphIndex}")
                        .semantics { stateDescription = if (entry.paragraphIndex == focusedParagraph) "当前段落" else "第 ${entry.paragraphIndex + 1} 段" },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = if (entry.paragraphIndex == focusedParagraph)
                            MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("${entry.paragraphIndex + 1} · $excerpt", Modifier.fillMaxWidth(), maxLines = 3, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                } }
            }
        }
    }
}
