@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
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

@Composable
private fun backgroundKnowledgeViewModel(repository: BackgroundRepository): BackgroundKnowledgeViewModel =
    viewModel(key = "background-knowledge", factory = remember(repository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                BackgroundKnowledgeViewModel(repository, extras.createSavedStateHandle()) as T
        }
    })

@Composable
internal fun BackgroundKnowledgePanel(
    records: List<BackgroundRecord>, recordErrors: Int, repository: BackgroundRepository,
    onExport: () -> Unit, model: BackgroundKnowledgeViewModel? = null,
) {
    val vm = model ?: backgroundKnowledgeViewModel(repository)
    LaunchedEffect(records) { vm.restoreRecord(records) }
    LaunchedEffect(vm) { if (vm.recentView && vm.recent.isEmpty() && !vm.loading) vm.loadRecent() }
    val saved = remember(records, vm.search) { records.filter { backgroundRecordMatches(it, vm.search) }.sortedByDescending { it.updatedAt } }
    val recent = remember(vm.recent, vm.search) { vm.recent.filter { backgroundLookupMatches(it, vm.search) } }
    LazyColumn(Modifier.fillMaxSize().testTag("background_knowledge_panel"), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("header") {
            Text("背景资料", style = MaterialTheme.typography.headlineSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(!vm.recentView, { vm.showRecent(false) }, label = { Text("已留存 ${records.size}") }, modifier = Modifier.testTag("background_knowledge_saved_tab"))
                FilterChip(vm.recentView, { vm.showRecent(true) }, label = { Text("最近检索") }, modifier = Modifier.testTag("background_knowledge_recent_tab"))
                TextButton(onClick = onExport, enabled = records.isNotEmpty(), modifier = Modifier.testTag("background_knowledge_export")) { Text("导出 Markdown") }
            }
            OutlinedTextField(vm.search, vm::updateSearch, Modifier.fillMaxWidth().testTag("background_knowledge_search"),
                label = { Text("搜索概念、语境、来源或标签") }, singleLine = true)
            if (recordErrors > 0) Text("$recordErrors 条留存记录暂不可读取，原数据已保留。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("background_record_load_errors"))
            vm.notice?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            vm.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        if (vm.recentView) {
            item("recent-status") {
                Text("检索历史尚未留存为笔记。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (vm.loading) Text("读取最近资料…", style = MaterialTheme.typography.labelSmall)
                if (vm.error != null) TextButton(onClick = vm::loadRecent, modifier = Modifier.testTag("background_recent_retry")) { Text("重新读取") }
            }
            if (recent.isEmpty() && !vm.loading && vm.error == null) item("recent-empty") {
                Text(if (vm.search.isBlank()) "还没有检索记录。" else "没有匹配的检索记录。", style = MaterialTheme.typography.bodyMedium)
            }
            items(recent, key = { it.id }) { lookup ->
                OutlinedCard(onClick = { vm.openHistory(lookup.id) }, modifier = Modifier.fillMaxWidth().testTag("background_recent_${lookup.id}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(lookup.query.term, style = MaterialTheme.typography.titleMedium)
                        Text(lookup.sources.map { it.provider }.distinct().joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (lookup.query.context.isNotBlank()) Text(lookup.query.context, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Text(backgroundRecordDate(lookup.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            if (saved.isEmpty()) item("saved-empty") {
                Text(if (vm.search.isBlank()) "在背景资料中选择一个含义，再留存到这里。" else "没有匹配的留存记录。", style = MaterialTheme.typography.bodyMedium)
            }
            items(saved, key = { it.id }) { record ->
                val concept = record.lookup.candidates.single { it.id == record.selectedCandidateId }
                OutlinedCard(onClick = { vm.openRecord(record) }, modifier = Modifier.fillMaxWidth().testTag("background_record_${record.id}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(record.lookup.query.term, style = MaterialTheme.typography.titleMedium)
                        Text(concept.title, style = MaterialTheme.typography.bodyMedium)
                        if (record.lookup.query.context.isNotBlank()) Text(record.lookup.query.context, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        if (record.comment.isNotBlank()) Text(record.comment, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            record.tags.forEach { Text("# $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                            Text(backgroundRecordDate(record.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    vm.historyId?.let { id ->
        val research = backgroundResearchViewModel(repository, "knowledge-history:$id")!!
        ModalBottomSheet(onDismissRequest = vm::closeDetail, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().height(LocalConfiguration.current.screenHeightDp.dp * .84f)
                .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
                BackgroundResearchPanel(research, "knowledge-history:$id", BackgroundQuery(""), vm::closeDetail,
                    fixedLookupId = id, closeLabel = "返回知识库")
            }
        }
    }
    vm.selectedRecord?.let { record ->
        BackgroundRecordDetail(record, vm::closeDetail, vm::edit, vm::requestDelete, vm.mutating)
        if (vm.editing) BackgroundRecordEdit(record, vm.mutating, vm.mutationError, vm::cancelEdit, vm::update,
            onReload = { vm.reloadSelected(records) })
        if (vm.deleting) AlertDialog(onDismissRequest = vm::cancelDelete, title = { Text("删除这条留存？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(record.lookup.query.term)
                Text("删除这条笔记，检索历史和原文会保留。", style = MaterialTheme.typography.bodySmall)
                vm.mutationError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = { TextButton(onClick = vm::confirmDelete, enabled = !vm.mutating,
                modifier = Modifier.testTag("background_record_confirm_delete")) { Text(if (vm.mutating) "删除中…" else "删除") } },
            dismissButton = { TextButton(onClick = vm::cancelDelete, enabled = !vm.mutating,
                modifier = Modifier.testTag("background_record_cancel_delete")) { Text("取消") } })
    }
}

/** Reads the complete saved snapshot. No research repository or cache is used in this view. */
@Composable
private fun BackgroundRecordDetail(record: BackgroundRecord, onClose: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, busy: Boolean) {
    val lookup = record.lookup
    val selected = lookup.candidates.single { it.id == record.selectedCandidateId }
    val sources = remember(record.id) { BackgroundResearch.selectedSources(lookup, record.selectedCandidateId) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().height(LocalConfiguration.current.screenHeightDp.dp * .84f).testTag("background_record_detail")
            .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).navigationBarsPadding().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(lookup.query.term, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onClose, modifier = Modifier.testTag("background_record_close")) { Text("返回知识库") }
            }
            Text(selected.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
            Text("留存于 ${backgroundRecordDate(record.createdAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (lookup.query.context.isNotBlank()) {
                Text("检索时语境", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(lookup.query.context, Modifier.fillMaxWidth().testTag("background_record_context"), style = MaterialTheme.typography.bodyMedium) }
            }
            if (lookup.query.source.title != null || lookup.query.source.source != null) Text(lookup.query.source.display(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            record.explanation?.let { result ->
                Text("AI 解释 · 留存版本", style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(result.text, Modifier.fillMaxWidth().testTag("background_record_explanation"), style = MaterialTheme.typography.bodyLarge) }
                BackgroundEvidenceDisclosure(record.id, result, "background_record")
            }
            if (record.comment.isNotBlank()) {
                Text("我的想法", style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(record.comment, style = MaterialTheme.typography.bodyMedium) }
            }
            if (record.tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                record.tags.forEach { Text("# $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onEdit, enabled = !busy, modifier = Modifier.testTag("background_record_edit")) { Text("编辑想法与标签") }
                TextButton(onClick = onDelete, enabled = !busy, modifier = Modifier.testTag("background_record_delete")) { Text("删除留存") }
            }
            Text("留存资料", style = MaterialTheme.typography.labelLarge)
            sources.forEach { BackgroundInlineSource(it, record.id, "background_record") }
        }
    }
}

@Composable
private fun BackgroundRecordEdit(record: BackgroundRecord, busy: Boolean, error: String?, onCancel: () -> Unit,
    onSave: (String, List<String>) -> Unit, onReload: () -> Unit) {
    var comment by rememberSaveable(record.id, record.revision) { mutableStateOf(record.comment) }
    var tags by rememberSaveable(record.id, record.revision) { mutableStateOf(record.tags.joinToString("，")) }
    val parsed = tags.split(',', '，', ';', '；', '\n').map { it.trim().removePrefix("#").trim() }.filter { it.isNotBlank() }.distinct()
    val valid = comment.length <= BackgroundRecords.MAX_COMMENT && parsed.size <= BackgroundRecords.MAX_TAGS && parsed.all { it.length <= BackgroundRecords.MAX_TAG_LENGTH }
    AlertDialog(onDismissRequest = { if (!busy) onCancel() }, title = { Text("编辑背景笔记") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(record.lookup.query.term, style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(comment, { comment = it }, Modifier.fillMaxWidth().testTag("background_record_comment"), label = { Text("我的想法") }, minLines = 3, maxLines = 7, enabled = !busy)
            OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth().testTag("background_record_tags"), label = { Text("标签，以逗号分隔") }, enabled = !busy)
            if (!valid) Text("评论最多 12,000 字符；最多 20 个标签，每个最多 100 字符。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("background_record_edit_error"))
                TextButton(onClick = onReload, enabled = !busy) { Text("重新读取记录") }
            }
        }
    }, confirmButton = { TextButton(onClick = { onSave(comment, parsed) }, enabled = !busy && valid,
        modifier = Modifier.testTag("background_record_save")) { Text(if (busy) "保存中…" else "保存") } },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.testTag("background_record_cancel")) { Text("取消") } })
}

private fun backgroundRecordDate(time: Long) = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
