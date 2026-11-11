@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.*

/** Shared across tool tabs; a new original body or reading entry owns a fresh draft. */
internal class ReadingStudyScopeState(expanded: Boolean = false, initialized: Boolean = false, first: String = "", last: String = "") {
    val expanded = mutableStateOf(expanded)
    val initialized = mutableStateOf(initialized)
    val first = mutableStateOf(first)
    val last = mutableStateOf(last)
    companion object {
        val Saver = listSaver<ReadingStudyScopeState, Any>(
            save = { listOf(it.expanded.value, it.initialized.value, it.first.value, it.last.value) },
            restore = { ReadingStudyScopeState(it[0] as Boolean, it[1] as Boolean, it[2] as String, it[3] as String) },
        )
    }
}

@Composable
internal fun rememberReadingStudyScopeState(identity: String): ReadingStudyScopeState =
    rememberSaveable(identity, saver = ReadingStudyScopeState.Saver) { ReadingStudyScopeState() }

/** Choosing a range changes assistance only; the original body stays in the reader. */
@Composable
internal fun ReadingStudyScopeControls(
    body: ArticleBody, focusedParagraph: Int, study: ArticleStudy?, saved: List<ArticleStudy>,
    loading: Boolean, busy: Boolean, allowed: Boolean,
    onChoose: (ArticleStudy) -> Unit, onAnalyze: (StudyScope) -> Unit, onAnalyzeFull: () -> Unit,
    state: ReadingStudyScopeState? = null,
) {
    val draft = state ?: rememberReadingStudyScopeState("${body.articleId}:${body.contentHash}")
    var expanded by draft.expanded
    var initialized by draft.initialized
    var first by draft.first
    var last by draft.last
    val paragraphs = remember(body.contentHash) { ParagraphFocus.paragraphs(body.text) }
    val fullSupported = remember(body.contentHash) { ReadingStudyScopes.supportsFull(body) }
    val currentIdentity = remember(study) { study?.let(ReadingStudyScopes::identity) }
    val savedIdentities = remember(saved) { saved.map { it to ReadingStudyScopes.identity(it) } }
    val currentLabel = study?.scope?.let(ReadingStudyScopes::label) ?: if (study == null) "未分析" else "全文"
    fun selectCurrent() {
        val proposed = runCatching { ReadingStudyScopes.defaultScope(body, focusedParagraph, preferredCount = 3) }.getOrNull()
        first = ((proposed?.firstParagraph ?: focusedParagraph.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))) + 1).toString()
        last = (proposed?.endParagraphExclusive ?: first.toInt()).toString()
        initialized = true
    }
    val selected = remember(body.contentHash, first, last) {
        val from = first.toIntOrNull()
        val to = last.toIntOrNull()
        if (from == null || to == null) null else runCatching { ReadingStudyScopes.create(body, from - 1, to) }.getOrNull()
    }
    val rangeMessage = when {
        first.isBlank() || last.isBlank() -> "输入起止段落。"
        first.toIntOrNull() == null || last.toIntOrNull() == null -> "段落编号需为整数。"
        first.toInt() !in 1..paragraphs.size || last.toInt() !in 1..paragraphs.size -> "段落范围为 1–${paragraphs.size}。"
        first.toInt() > last.toInt() -> "结束段落不能早于开始段落。"
        selected == null -> "所选内容超过单次范围，请减少段落；超长单段可在原文划选句子。"
        else -> null
    }
    val existing = selected?.let { scope -> saved.firstOrNull { it.scope == scope } }
    Column(Modifier.fillMaxWidth().animateContentSize(tween(180)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = {
            if (!initialized) selectCurrent()
            expanded = !expanded
        }, contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.testTag("study_scope_toggle")) {
            Text("辅助范围 · $currentLabel", style = MaterialTheme.typography.labelLarge)
        }
        if (study?.scope != null) Text("导图、重点与问题仅覆盖${ReadingStudyScopes.label(study.scope)}。", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("study_scope_notice"))
        if (study == null && !fullSupported) Text("长文可分段研读，原文完整保留。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("study_long_article_hint"))
        AnimatedVisibility(expanded, enter = fadeIn(tween(160)), exit = fadeOut(tween(100))) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (loading) Text("读取已有辅助…", style = MaterialTheme.typography.labelSmall)
                if (saved.isNotEmpty()) {
                    Text("已保存", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.testTag("study_saved_list").horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        savedIdentities.forEach { (entry, identity) ->
                            FilterChip(selected = currentIdentity == identity,
                                onClick = { onChoose(entry) }, label = { Text(entry.scope?.let(ReadingStudyScopes::label) ?: "全文") },
                                modifier = Modifier.testTag("study_saved_$identity"))
                        }
                    }
                }
                Text("选择原文段落 · 共 ${paragraphs.size} 段", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(first, { first = it.take(7) }, Modifier.weight(1f).testTag("study_scope_first"),
                        label = { Text("起始段") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !busy)
                    OutlinedTextField(last, { last = it.take(7) }, Modifier.weight(1f).testTag("study_scope_last"),
                        label = { Text("结束段") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !busy)
                }
                TextButton(onClick = { selectCurrent() }, enabled = !busy, contentPadding = PaddingValues(horizontal = 0.dp),
                    modifier = Modifier.testTag("study_scope_current")) { Text("从当前段开始") }
                if (selected == null) Text(rangeMessage.orEmpty(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("study_scope_error"))
                else {
                    Text("${ReadingStudyScopes.label(selected)} · ${selected.endParagraphExclusive - selected.firstParagraph} 段 · ${selected.endExclusive - selected.start} 字符",
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("study_scope_count"))
                    Text(body.text.substring(paragraphs[selected.firstParagraph].start, paragraphs[selected.firstParagraph].endExclusive),
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("study_scope_preview_first"))
                    if (selected.endParagraphExclusive > selected.firstParagraph + 1) {
                        Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val end = paragraphs[selected.endParagraphExclusive - 1]
                        Text(body.text.substring(end.start, end.endExclusive), maxLines = 3, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("study_scope_preview_last"))
                    }
                }
                if (!allowed) Text("此来源未开启 AI 分析。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { selected?.let { scope -> if (existing != null) onChoose(existing) else onAnalyze(scope) } },
                    enabled = selected != null && !busy && (existing != null || allowed),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("study_scope_analyze")) {
                    Text(if (existing != null) "查看已有分析" else "分析所选段落 · AI")
                }
                if (fullSupported && saved.none { it.scope == null }) TextButton(onClick = onAnalyzeFull, enabled = allowed && !busy,
                    modifier = Modifier.testTag("study_scope_full")) { Text("分析全文 · AI") }
            }
        }
    }
}
