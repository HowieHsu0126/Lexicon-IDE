package dev.lexicon.ide.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lexicon.domain.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun PersonalizationHistoryDialog(state: PaceHistoryUiState, articles: List<Article>,
    onSelect: (String?) -> Unit, onClose: () -> Unit, onRetry: () -> Unit, onInspectRun: (SpeedRun) -> Unit) {
    val back = { if (state.selectedId != null) onSelect(null) else onClose() }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            PersonalizationHistoryScreen(state, articles, onSelect, back, onRetry, onInspectRun)
        }
    }
}

@Composable
internal fun PersonalizationHistoryScreen(state: PaceHistoryUiState, articles: List<Article>,
    onSelect: (String?) -> Unit, onBack: () -> Unit, onRetry: () -> Unit, onInspectRun: (SpeedRun) -> Unit) {
    val savedPages = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize().safeDrawingPadding().testTag("pace_history")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("节奏调整记录", Modifier.weight(1f).padding(vertical = 14.dp), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).testTag("pace_history_back")) { Text("返回") }
        }
        savedPages.SaveableStateProvider(state.selectedId ?: "history-list") {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("pace_history_content"),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.loading) item { Text("正在读取记录…", style = MaterialTheme.typography.bodySmall) }
                state.error?.let { message -> item {
                    Text(message, Modifier.fillMaxWidth().testTag("pace_history_error"), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("重试") }
                } }
                val detail = state.detail
                if (!state.loading && state.selectedId == null && state.error == null) {
                    if (state.decisions.isEmpty()) item {
                        Text("还没有节奏调整记录。", Modifier.fillMaxWidth().testTag("pace_history_empty"))
                        Text("应用或保持建议后，会保留当时的依据。", Modifier.fillMaxWidth().padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        item { Text("保留每次选择，随时回看依据。", Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(state.decisions, key = { it.id }) { decision ->
                            Column(Modifier.fillMaxWidth().clickable { onSelect(decision.id) }
                                .padding(vertical = 12.dp).testTag("pace_history_decision_" + decision.id),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(paceDecisionStatus(decision), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall)
                                Text("${decision.suggestion.granularity.name} · 建议 ${decision.suggestion.fromRate} → ${decision.suggestion.toRate} 意群/分钟",
                                    Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
                                Text(paceDecisionDate(decision.decidedAt), Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                        }
                    }
                }
                if (detail != null) item { PaceDecisionDetails(detail, articles, onInspectRun) }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun PaceDecisionDetails(detail: PaceDecisionDetail, articles: List<Article>, onInspectRun: (SpeedRun) -> Unit) {
    val decision = detail.decision
    val offer = decision.suggestion
    var expanded by rememberSaveable(decision.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize(tween(160)).testTag("pace_history_detail"),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(paceDecisionStatus(decision), Modifier.fillMaxWidth().testTag("pace_history_detail_status"),
            style = MaterialTheme.typography.headlineSmall)
        Text("${offer.granularity.name} · 当时建议 ${offer.fromRate} → ${offer.toRate} 意群/分钟",
            Modifier.fillMaxWidth().testTag("pace_history_detail_rate"), style = MaterialTheme.typography.titleMedium)
        Text(paceDecisionDate(decision.decidedAt), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (decision.status == PersonalizationDecisionStatus.UNDONE) Text("撤销于 " + paceDecisionDate(decision.updatedAt),
            Modifier.fillMaxWidth().testTag("pace_history_undone_at"), style = MaterialTheme.typography.bodySmall)
        Text("这是当时的决定，不表示当前仍在使用该节奏。", Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("当时的依据", Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall)
        Text("保存的记录：${offer.sampleCount} 轮 · 答对 ${offer.correct}/${offer.total} 题",
            Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
        val original = detail.originalEvidence
        val verified = original.status == PaceHistoricalEvidenceStatus.VERIFIED
        Text(when (original.status) {
            PaceHistoricalEvidenceStatus.VERIFIED -> "已与当时的依据核对一致"
            PaceHistoricalEvidenceStatus.MISSING -> "部分练习已不可用，无法完整核对当时依据。"
            PaceHistoricalEvidenceStatus.CONFLICT -> "原练习存在冲突版本，暂不展示其内容。"
            PaceHistoricalEvidenceStatus.CHANGED -> "当前记录与当时保存的依据不一致。"
            PaceHistoricalEvidenceStatus.UNSUPPORTED_RULE -> "这条记录使用较早的规则，暂不能核对原始依据。"
            PaceHistoricalEvidenceStatus.INVALID_SUMMARY -> "这条建议的记录不完整，暂不能核对依据。"
        }, Modifier.fillMaxWidth().testTag("pace_history_evidence_status"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (verified) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("pace_history_evidence_toggle")) { Text(if (expanded) "收起当时的练习" else "查看当时的练习") }
            if (expanded) PaceEvidenceRows(offer.evidenceRunIds, original.runs, articles, onInspectRun, "pace_history_original")
        }
        HorizontalDivider()
        if (decision.status == PersonalizationDecisionStatus.DISMISSED) Text("当时选择保持 ${offer.fromRate} 意群/分钟，未应用该建议。",
            Modifier.fillMaxWidth().testTag("pace_history_kept"), style = MaterialTheme.typography.bodySmall)
        else {
            if (decision.status == PersonalizationDecisionStatus.UNDONE) Text("后续记录只统计撤销前完成的练习。",
                Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
            SpeedOutcomeControls(detail.outcome, evidenceRuns = detail.outcomeRuns, articles = articles, onInspectRun = onInspectRun,
                decision = decision)
        }
    }
}

internal fun paceDecisionStatus(decision: PersonalizationDecision) = when (decision.status) {
    PersonalizationDecisionStatus.APPLIED -> "已应用"
    PersonalizationDecisionStatus.DISMISSED -> "已保持"
    PersonalizationDecisionStatus.UNDONE -> "已撤销"
}
internal fun paceDecisionDate(time: Long) = runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
}.getOrDefault("时间不可用")
