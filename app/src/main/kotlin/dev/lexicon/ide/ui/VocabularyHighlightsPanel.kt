@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.*

/** Original learning candidates are independent of quiet mode, skim sentence decoration, and rewrites. */
@Composable
internal fun VocabularyHighlightsPanel(
    article: Article, body: ArticleBody?, study: ArticleStudy?, candidates: List<VocabularyCandidate>,
    cards: List<Card>, captures: List<Capture>, model: VocabularyLearningViewModel?, state: VocabularyLearningUiState,
    onOpenCard: ((String) -> Unit)? = null, onLookup: (Capture) -> Unit,
) {
    val identity = candidates.joinToString("|") { it.id }
    var selected by rememberSaveable(article.id, body?.contentHash, identity) { mutableStateOf(emptyList<String>()) }
    var confirm by rememberSaveable(article.id, body?.contentHash, identity) { mutableStateOf(false) }
    var showFamiliar by rememberSaveable(article.id) { mutableStateOf(false) }
    val familiar = state.library.familiar
    fun lookup(candidate: VocabularyCandidate) = onLookup(captureForVocabularyLookup(candidate, captures))
    val linkedCaptures = remember(captures, candidates) { candidates.associate { candidate ->
        candidate.id to captures.filter { VocabularyLearning.sameCapture(it, candidate.capture) }.map { it.id }.toSet()
    } }
    val occupied = remember(cards, candidates, linkedCaptures) { candidates.mapNotNull { candidate ->
        cards.firstOrNull { it.captureId == candidate.capture.id || it.captureId in linkedCaptures[candidate.id].orEmpty() }
            ?.let { candidate.id to it }
    }.toMap() }
    val completed = remember(candidates, state.library.batches) { candidates.mapNotNull { candidate ->
        state.library.batches.asSequence().flatMap { it.items.asSequence() }.firstOrNull {
            it.candidate.id == candidate.id && it.status == VocabularyItemStatus.COMPLETED &&
                VocabularyLearning.sameCapture(it.candidate.capture, candidate.capture)
        }?.let { candidate.id to it }
    }.toMap() }
    val saved = remember(candidates, completed, cards) { candidates.filter { candidate ->
        completed[candidate.id]?.cardId?.let { cardId -> cards.any { card -> card.id == cardId &&
            card.sourceSentence == candidate.capture.sentence && card.sourceRef == candidate.capture.sourceRef &&
            card.contextOrigin == candidate.capture.contextOrigin } } == true
    }.map { it.id }.toSet() }
    val occupiedIds = occupied.keys + completed.keys
    val selectable = candidates.filter { it.id !in occupiedIds && !VocabularyLearning.isFamiliar(it.capture.target, familiar) }
    val selectedCandidates = selectable.filter { it.id in selected }
    LaunchedEffect(selectable.map { it.id }) { selected = selected.filter { id -> selectable.any { it.id == id } } }
    val enabled = model != null && !state.loading && state.error == null

    Text("AI 备考词汇", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (model != null) {
        Text("熟悉标记仅减少提示，复习计划保持不变。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.loading) Text("读取词汇记录…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("vocabulary_loading"))
        state.error?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("vocabulary_load_error"))
            TextButton(onClick = model::retryLoad) { Text("重试读取") }
        }
        model.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("vocabulary_operation_error")) }
    }
    if (candidates.isEmpty()) Text(if (study == null) "生成阅读辅助后可选择要学习的词。" else if (study.scope != null) "所选段落没有可制卡的重点词或短语。" else "本篇没有可制卡的重点词或短语。",
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("vocabulary_empty"))
    val familiarCount = candidates.count { VocabularyLearning.isFamiliar(it.capture.target, familiar) }
    if (familiarCount > 0) FilterChip(showFamiliar, { showFamiliar = !showFamiliar },
        label = { Text(if (showFamiliar) "收起熟悉词 · $familiarCount" else "显示熟悉词 · $familiarCount") },
        modifier = Modifier.testTag("vocabulary_show_familiar"))
    candidates.filter { showFamiliar || !VocabularyLearning.isFamiliar(it.capture.target, familiar) }.forEach { candidate ->
        val known = VocabularyLearning.isFamiliar(candidate.capture.target, familiar)
        var expanded by rememberSaveable(candidate.id) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().animateContentSize(tween(180)).testTag("vocabulary_candidate_${candidate.id}"),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (model != null) Checkbox(candidate.id in selected, { checked ->
                    selected = if (checked) (selected + candidate.id).distinct() else selected - candidate.id
                }, enabled = enabled && !known && candidate.id !in occupiedIds && !model.busy,
                    modifier = Modifier.testTag("vocabulary_select_${candidate.id}").semantics { contentDescription = "选择 ${candidate.capture.target}" })
                TextButton(onClick = { lookup(candidate) }, modifier = Modifier.weight(1f).testTag("vocabulary_lookup_${candidate.id}"),
                    shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(candidate.capture.target, modifier = Modifier.fillMaxWidth().testTag("vocabulary_label_${candidate.id}"), style = MaterialTheme.typography.titleMedium)
                }
            }
            if (candidate.id in saved) Text("已入卡", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("vocabulary_saved_${candidate.id}"))
            else if (candidate.id in occupiedIds) {
                Text("该捕获已有卡片", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("vocabulary_occupied_${candidate.id}"))
                occupied[candidate.id]?.let { card ->
                    if (card.sourceSentence != candidate.capture.sentence || card.sourceRef != candidate.capture.sourceRef ||
                        card.contextOrigin != candidate.capture.contextOrigin) Text("原句或来源不同，请核对既有卡片。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("vocabulary_conflict_${candidate.id}"))
                }
            }
            Text(candidate.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                (occupied[candidate.id]?.id ?: completed[candidate.id]?.cardId)?.let { cardId ->
                    if (onOpenCard != null) TextButton(onClick = { onOpenCard(cardId) },
                        modifier = Modifier.testTag("vocabulary_existing_card_${candidate.id}")) { Text("查看卡片") }
                }
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("vocabulary_context_${candidate.id}")) {
                    Text(if (expanded) "收起原句" else "原句")
                }
                if (model != null) TextButton(onClick = { model.setFamiliar(candidate.capture.target, !known) },
                    enabled = enabled && candidate.termKey !in model.busyTerms,
                    modifier = Modifier.testTag("vocabulary_familiar_${candidate.id}")) { Text(if (known) "恢复提示" else "已熟悉") }
            }
            if (expanded) Text(candidate.capture.sentence, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("vocabulary_sentence_${candidate.id}"))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    if (model != null && candidates.isNotEmpty()) Button(onClick = { confirm = true },
        enabled = enabled && !model.busy && selectedCandidates.isNotEmpty() && selectedCandidates.all { it.capture.aiAllowed } && body != null && study != null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vocabulary_prepare")) {
        Text(if (model.preparing) "保存队列…" else "生成 ${selectedCandidates.size} 张生词卡 · AI")
    }
    if (model != null) VocabularyQueueList(state.library.batches.filter { it.articleId == article.id }, model,
        loading = state.loading || state.error != null, familiar = state.library.familiar, onRead = null, onOpenCard = onOpenCard)

    if (confirm && model != null && body != null && study != null && selectedCandidates.isNotEmpty()) {
        AlertDialog(onDismissRequest = { if (!model.preparing) confirm = false },
            title = { Text("确认原句 · ${selectedCandidates.size} 张") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("将以下词语与原句发送给 DeepSeek。生成后可继续阅读，在词汇队列查看结果。", style = MaterialTheme.typography.bodySmall)
                    model.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    selectedCandidates.forEach { candidate ->
                        Text(candidate.capture.target, style = MaterialTheme.typography.titleSmall)
                        Text(candidate.capture.sentence, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { confirm = false; lookup(candidate) }, modifier = Modifier.testTag("vocabulary_adjust_${candidate.id}")) {
                            Text("单独调整与查词")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                model.prepareAndRun(article, body, study, selectedCandidates.map { it.id }) { confirm = false; selected = emptyList() }
            }, enabled = enabled && !model.busy && selectedCandidates.all { it.capture.aiAllowed }, modifier = Modifier.testTag("vocabulary_confirm")) { Text("确认并生成") } },
            dismissButton = { TextButton(onClick = { confirm = false }, enabled = !model.preparing, modifier = Modifier.testTag("vocabulary_cancel")) { Text("取消") } },
            modifier = Modifier.testTag("vocabulary_confirmation"))
    }
}

@Composable
internal fun VocabularyQueueList(batches: List<VocabularyBatch>, model: VocabularyLearningViewModel, loading: Boolean = false,
    familiar: List<FamiliarTerm> = emptyList(), onRead: ((String) -> Unit)? = null, onOpenCard: ((String) -> Unit)? = null, showHeading: Boolean = true) {
    var resumeId by rememberSaveable { mutableStateOf<String?>(null) }
    if (showHeading && batches.isNotEmpty()) Text("词汇队列", style = MaterialTheme.typography.titleMedium)
    batches.sortedByDescending { it.createdAt }.forEach { batch ->
        val remaining = batch.items.count { retryableVocabularyItem(it, familiar) }
        val complete = batch.items.count { it.status == VocabularyItemStatus.COMPLETED }
        var expanded by rememberSaveable(batch.id) { mutableStateOf(remaining > 0) }
        Column(Modifier.fillMaxWidth().testTag("vocabulary_batch_${batch.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)) {
                Text("$complete / ${batch.items.size} 已入卡 · ${if (expanded) "收起" else "展开"}", modifier = Modifier.fillMaxWidth())
            }
            if (expanded) {
                if (onRead != null) TextButton(onClick = { onRead(batch.articleId) }) { Text(batch.source.title ?: "回到原文") }
                batch.items.forEach { item ->
                    Text("${item.candidate.capture.target} · ${vocabularyItemLabel(item)}", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("vocabulary_item_${item.candidate.id}"))
                    if (item.status == VocabularyItemStatus.COMPLETED && onOpenCard != null) item.cardId?.let { cardId ->
                        TextButton(onClick = { onOpenCard(cardId) }, modifier = Modifier.testTag("vocabulary_card_${item.candidate.id}")) { Text("查看卡片") }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (model.activeBatchId == batch.id) TextButton(onClick = model::pause, modifier = Modifier.testTag("vocabulary_pause_${batch.id}")) { Text("暂停") }
                    else if (remaining > 0) TextButton(onClick = { resumeId = batch.id }, enabled = !model.busy && !loading,
                        modifier = Modifier.testTag("vocabulary_resume_${batch.id}")) { Text("继续未完成项 · $remaining") }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    batches.find { it.id == resumeId }?.let { batch ->
        val uncertain = batch.items.any { it.status in setOf(VocabularyItemStatus.INTERRUPTED, VocabularyItemStatus.RUNNING) }
        AlertDialog(onDismissRequest = { resumeId = null }, title = { Text("继续词汇队列") },
            text = { Text(if (uncertain) "中断请求的远端结果无法确认，重试可能再次调用 AI。已入卡项目不会重复生成。"
                else "仅处理未完成项目，仍熟悉的词会跳过。已保存的分析会直接入卡，其余项目可能调用 AI。") },
            confirmButton = { TextButton(onClick = { resumeId = null; model.resume(batch) }, enabled = !model.busy,
                modifier = Modifier.testTag("vocabulary_resume_confirm")) { Text("继续") } },
            dismissButton = { TextButton(onClick = { resumeId = null }) { Text("取消") } })
    }
}

internal fun vocabularyItemLabel(item: VocabularyQueueItem): String = when (item.status) {
    VocabularyItemStatus.PENDING -> "待处理"
    VocabularyItemStatus.RUNNING -> "生成中"
    VocabularyItemStatus.COMPLETED -> "已入卡"
    VocabularyItemStatus.SKIPPED_FAMILIAR -> "已熟悉，已跳过"
    VocabularyItemStatus.INTERRUPTED -> "已中断，等待确认"
    VocabularyItemStatus.FAILED -> when (item.errorCode) {
        "authentication", "missing_key" -> "请配置 API Key"
        "quota", "ai_paused" -> "AI 已暂停，请检查 Key 或余额"
        "source_restricted" -> "来源未开启 AI"
        "invalid_schema" -> "分析未通过校验"
        "rate_limit", "http_429" -> "请求过于频繁，稍后重试"
        "capture_conflict", "capture_changed", "snapshot_mismatch", "input_conflict" -> "原句已调整，请核对捕获"
        "queue_damaged" -> "队列材料不可用，请保留记录并重新选择"
        else -> "未完成，可重试"
    }
}

@Composable
internal fun VocabularyQueueScreen(model: VocabularyLearningViewModel, state: VocabularyLearningUiState, onOpenCard: ((String) -> Unit)? = null, onRead: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).testTag("vocabulary_queue_screen"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("词汇队列", style = MaterialTheme.typography.headlineSmall)
        if (state.loading) Text("读取队列…", style = MaterialTheme.typography.bodySmall)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = model::retryLoad) { Text("重试") } }
        model.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (!state.loading && state.error == null && state.library.batches.isEmpty()) Text("在文章「重点」中选择词语，确认原句后生成。")
        var showFamiliar by rememberSaveable { mutableStateOf(false) }
        if (state.library.familiar.isNotEmpty()) {
            TextButton(onClick = { showFamiliar = !showFamiliar }, modifier = Modifier.testTag("vocabulary_all_familiar")) {
                Text("熟悉词 · ${state.library.familiar.size} · ${if (showFamiliar) "收起" else "展开"}")
            }
            if (showFamiliar) {
                Text("仅减少阅读提示，不改变已有复习计划。", style = MaterialTheme.typography.bodySmall)
                state.library.familiar.sortedBy { it.label }.forEach { term ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(term.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { model.setFamiliar(term.label, false) },
                            enabled = !state.loading && state.error == null && term.key !in model.busyTerms,
                            modifier = Modifier.testTag("vocabulary_restore_${fingerprint(term.key)}")) { Text("恢复提示") }
                    }
                }
            }
        }
        VocabularyQueueList(state.library.batches, model, state.loading || state.error != null, state.library.familiar, onRead, onOpenCard, showHeading = false)
    }
}

/** Opening documentation preserves an existing user-edited capture instead of overwriting it. */
internal fun captureForVocabularyLookup(candidate: VocabularyCandidate, captures: List<Capture>): Capture =
    captures.firstOrNull { it.id == candidate.capture.id }
        ?: captures.firstOrNull { VocabularyLearning.sameCapture(it, candidate.capture) }
        ?: candidate.capture
