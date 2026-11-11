@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lexicon.domain.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class BackgroundSeed(val label: String, val query: BackgroundQuery)

@Composable
internal fun backgroundResearchViewModel(repository: BackgroundRepository?, identity: String): BackgroundResearchViewModel? =
    if (repository == null) null else viewModel(key = "background-research:${fingerprint(identity)}", factory = remember(repository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                BackgroundResearchViewModel(repository, extras.createSavedStateHandle()) as T
        }
    })

/** The same compact result-first panel serves native reading and externally captured text. */
@Composable
internal fun BackgroundResearchPanel(
    vm: BackgroundResearchViewModel, bindingId: String, initialQuery: BackgroundQuery,
    onClose: () -> Unit, onBack: (() -> Unit)? = null, suggestions: List<BackgroundSeed> = emptyList(),
    fixedLookupId: String? = null, closeLabel: String = "继续原文", showHeader: Boolean = true,
) {
    LaunchedEffect(bindingId, fixedLookupId) {
        if (fixedLookupId != null) vm.bindLookup(bindingId, fixedLookupId) else vm.bind(bindingId, initialQuery)
    }
    var editing by rememberSaveable(bindingId) { mutableStateOf(true) }
    var choosing by rememberSaveable(bindingId) { mutableStateOf(true) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val lookup = vm.lookup
    var contextExpanded by rememberSaveable(bindingId, lookup?.id) { mutableStateOf(false) }
    var contextEllipsized by remember(lookup?.id) { mutableStateOf(false) }
    val top = remember { BringIntoViewRequester() }
    LaunchedEffect(lookup?.id) { if (lookup != null) { editing = false; choosing = lookup.candidates.size != 1 } }
    LaunchedEffect(lookup?.id, vm.explanation) { if (lookup != null) { withFrameNanos { }; top.bringIntoView(Rect(0f, 0f, 1f, 1f)) } }
    val selected = lookup?.candidates?.singleOrNull { it.id == vm.selectedCandidateId }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(top).testTag("background_research_panel"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showHeader) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("背景", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { focus.clearFocus(); keyboard?.hide(); onClose() }, modifier = Modifier.testTag("background_continue_reading")) { Text(closeLabel) }
        }
        if (showHeader && onBack != null) TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("background_back_to_capture")) { Text("返回查词") }

        // The displayed meaning belongs to the frozen lookup, never to an unsubmitted draft.
        if (lookup != null) {
            Text(lookup.query.term, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth().testTag("background_bound_term"))
            vm.explanation?.let { result ->
                Column(Modifier.fillMaxWidth().animateContentSize(tween(160)).testTag("background_explanation"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(selected?.title ?: result.title, style = MaterialTheme.typography.titleSmall)
                    Text("AI 解释 · 基于所选资料", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer { Text(result.text, style = MaterialTheme.typography.bodyLarge) }
                    BackgroundEvidenceDisclosure(lookup.id, result)
                }
            }
            if (vm.explaining) Text("正在解释所选含义… 可以继续阅读。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("background_explaining"))
            if (vm.loadingExplanation) Text("读取已有解释…", style = MaterialTheme.typography.labelSmall)
            vm.explanationError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("background_explanation_error"))
                if (!vm.explanationRetryNeeded) TextButton(onClick = vm::retryExplanationCache, enabled = !vm.loadingExplanation,
                    modifier = Modifier.testTag("background_retry_explanation_cache")) { Text("重读已有解释") }
            }
            if (lookup.query.context.isNotBlank()) {
                Text("对应上下文", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(lookup.query.context, style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (contextExpanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { result -> if (!contextExpanded) contextEllipsized = result.lineCount > 0 && result.isLineEllipsized(result.lineCount - 1) },
                    modifier = Modifier.testTag("background_context_preview")) }
                if (contextExpanded || contextEllipsized) TextButton(onClick = { contextExpanded = !contextExpanded }, contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.testTag("background_expand_context")) { Text(if (contextExpanded) "收起上下文" else "展开上下文") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (fixedLookupId == null) TextButton(onClick = { editing = !editing }, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("background_edit_query")) { Text(if (editing) "收起输入" else "修改概念或上下文") }
                if (lookup.candidates.size > 1 && selected != null) TextButton(onClick = { choosing = !choosing },
                    contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("background_change_meaning")) { Text(if (choosing) "收起其他含义" else "不是这个含义？") }
            }
        }
        if (fixedLookupId == null && (editing || lookup == null)) {
            OutlinedTextField(vm.input.term, { vm.update(vm.input.copy(term = it)) }, Modifier.fillMaxWidth().testTag("background_query_term"),
                label = { Text("概念或专有名词") }, singleLine = true, isError = vm.input.term.length > 160)
            OutlinedTextField(vm.input.context, { vm.update(vm.input.copy(context = it)) }, Modifier.fillMaxWidth().testTag("background_query_context"),
                label = { Text("原句与上下文") }, minLines = 2, maxLines = 6, isError = vm.input.context.length > 1200,
                supportingText = { if (vm.input.context.length > 1200) Text("上下文超过 1,200 字符，请缩短，或在原文重新选择。") })
            if (suggestions.isNotEmpty() && vm.input.term.isBlank()) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                suggestions.forEach { seed -> TextButton(onClick = { vm.update(seed.query) }, contentPadding = PaddingValues(0.dp)) { Text(seed.label) } }
            }
        }
        if (vm.loadingCache) Text("读取已有资料…", style = MaterialTheme.typography.labelSmall)
        if (vm.lookingUp) Text("正在检索资料… 可以继续阅读。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("background_looking_up"))
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("background_lookup_error")) }
        if (lookup == null) {
            if (fixedLookupId == null) OutlinedButton(onClick = { focus.clearFocus(); keyboard?.hide(); vm.retrieve() }, enabled = vm.validInput && !vm.lookingUp && !vm.loadingCache,
                modifier = Modifier.testTag("background_lookup")) { Text("检索资料") }
            if (vm.error != null) TextButton(onClick = vm::retryCache, modifier = Modifier.testTag("background_retry_cache")) { Text("重读已有资料") }
        } else {
            if (vm.expired) Text(if (fixedLookupId == null) "资料已超过 7 天，仍可阅读。刷新后再生成新解释。" else "这是超过 7 天的检索资料，仍可阅读与留存。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("background_stale"))
            if (lookup.candidates.isEmpty()) Text("暂未找到匹配资料。可修改概念或刷新检索。", style = MaterialTheme.typography.bodyMedium)
            if (choosing || selected == null) {
                if (lookup.candidates.size > 1) Text("选择与原文相符的含义", style = MaterialTheme.typography.labelLarge)
                lookup.candidates.forEach { candidate ->
                    val providers = lookup.sources.filter { it.id in candidate.sourceIds }.map { it.provider }.distinct().joinToString(" · ")
                    FilterChip(selected = selected?.id == candidate.id, onClick = { vm.choose(candidate.id); choosing = false },
                        label = { Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(candidate.title, style = MaterialTheme.typography.titleSmall)
                            if (candidate.description.isNotBlank() && candidate.description != providers) Text(candidate.description, style = MaterialTheme.typography.bodySmall)
                            Text(providers, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } }, modifier = Modifier.fillMaxWidth().testTag("background_candidate_${candidate.id}"))
                }
            }
            if (selected != null) {
                if (vm.explanation == null || vm.explanationRetryNeeded) OutlinedButton(
                    onClick = { vm.explain(explicitRetry = vm.explanationRetryNeeded) },
                    enabled = lookup.query.aiAllowed && !vm.expired && !vm.explaining && !vm.loadingExplanation && !vm.lookingUp,
                    modifier = Modifier.testTag("background_explain")) { Text(if (vm.explanationRetryNeeded) "重试解释 · AI" else "解释这个含义 · AI") }
                if (!lookup.query.aiAllowed) Text("此来源可查资料，未开启 AI 解释。", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (vm.savedRecord == null) OutlinedButton(onClick = { vm.saveToKnowledge() },
                        enabled = !vm.savingRecord && !vm.loadingRecord && !vm.loadingExplanation && vm.recordError == null,
                        modifier = Modifier.testTag("background_save_record")) { Text(if (vm.savingRecord) "留存中…" else "留存到知识库") }
                    else Text(vm.recordNotice ?: "已留存到知识库", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp).testTag("background_record_saved"))
                    if (vm.savedRecord != null && vm.explanation != null && vm.savedRecord?.explanation == null)
                        TextButton(onClick = { vm.saveToKnowledge(appendExplanation = true) }, enabled = !vm.savingRecord && !vm.loadingRecord && !vm.loadingExplanation && vm.recordError == null,
                            modifier = Modifier.testTag("background_append_explanation")) { Text("加入当前解释") }
                }
                vm.recordError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("background_record_error"))
                    TextButton(onClick = vm::retrySavedRecord, enabled = !vm.loadingRecord && !vm.savingRecord,
                        modifier = Modifier.testTag("background_retry_record")) { Text("重读留存状态") }
                }
                BackgroundResearch.selectedSources(lookup, selected.id).forEach { source -> BackgroundInlineSource(source, lookup.id) }
            }
            if (fixedLookupId == null) TextButton(onClick = { vm.retrieve(refresh = true) }, enabled = !vm.lookingUp && !vm.explaining,
                modifier = Modifier.testTag("background_refresh")) { Text("刷新资料") }
            lookup.providerOutcomes.filter { it.status in setOf("unavailable", "failed", "no_results") }.forEach { outcome ->
                Text("${outcome.provider} · ${if (outcome.status == "no_results") "无匹配资料" else "暂不可用"}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Secondary provenance stays available in full, while the explanation remains the reading focus. */
@Composable
internal fun BackgroundEvidenceDisclosure(snapshotId: String, result: StudyAssist, tagPrefix: String = "background") {
    if (result.evidence.isBlank()) return
    var expanded by rememberSaveable(snapshotId, result) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize(tween(160)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp),
            modifier = Modifier.testTag("${tagPrefix}_evidence_toggle").semantics { stateDescription = if (expanded) "已展开" else "已收起" }) {
            Text(if (expanded) "依据说明 −" else "依据说明 +", style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) SelectionContainer {
            Text(result.evidence, Modifier.fillMaxWidth().testTag("${tagPrefix}_evidence"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun BackgroundInlineSource(source: BackgroundSource, lookupId: String, tagPrefix: String = "background") {
    var expanded by rememberSaveable(lookupId, source.url) { mutableStateOf(false) }
    var openError by remember(source.url) { mutableStateOf(false) }
    val context = LocalContext.current
    val date = remember(source.retrievedAt) { DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(source.retrievedAt)) }
    Column(Modifier.fillMaxWidth().animateContentSize(tween(160)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("${tagPrefix}_source_${source.id}")) {
            Text("[${source.id}] ${source.title}  ${if (expanded) "−" else "+"}", style = MaterialTheme.typography.titleSmall)
        }
        Text("${source.provider} · $date", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(source.excerpt, maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("${tagPrefix}_excerpt_${source.id}")) }
        if (expanded) {
            Text(source.license, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val uri = remember(source.url) { Uri.parse(source.url) }
            TextButton(onClick = { openError = runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri) }.isFailure },
                enabled = uri.scheme == "https" && !uri.host.isNullOrBlank(), contentPadding = PaddingValues(0.dp)) { Text("来源原页 ↗") }
            if (openError) Text("来源原页暂未打开，资料摘录仍可阅读。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A unique occurrence locates a window; ambiguous or absent text keeps the complete paragraph for editing. */
internal fun backgroundParagraphContext(body: ArticleBody?, paragraphIndex: Int, term: String): String {
    if (body == null) return ""
    val paragraph = ParagraphFocus.paragraphs(body.text).getOrNull(paragraphIndex) ?: return ""
    val text = body.text.substring(paragraph.start, paragraph.endExclusive)
    if (text.length <= 1200 || term.isBlank()) return text
    val matches = Regex(Regex.escape(term), RegexOption.IGNORE_CASE).findAll(text).take(2).toList()
    if (matches.size != 1) return text
    val match = matches.single().range
    return BackgroundResearch.contextAround(body.text, paragraph.start + match.first, paragraph.start + match.last + 1)
}

internal fun backgroundCaptureContext(capture: Capture, body: ArticleBody?, editedSentence: String): String {
    val start = capture.selectionStart
    val end = capture.selectionEnd
    if (editedSentence == capture.sentence && body != null && body.articleId == capture.articleId && body.contentHash == capture.bodyHash &&
        start != null && end != null && start >= 0 && end > start && end <= body.text.length && body.text.substring(start, end) == capture.selectedText)
        return runCatching { BackgroundResearch.contextAround(body.text, start, end) }.getOrDefault(editedSentence)
    return editedSentence
}
