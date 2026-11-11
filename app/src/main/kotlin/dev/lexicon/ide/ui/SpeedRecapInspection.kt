package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lexicon.domain.SpeedInteractionSummary
import dev.lexicon.domain.SpeedPlan
import dev.lexicon.domain.SpeedRun
import dev.lexicon.domain.SpeedComprehensionReview
import dev.lexicon.domain.SpeedComprehensionReviewStatus

/** The controller validates every supplied frozen snippet against its original sentence. */
internal data class SpeedRecapInspection(
    val run: SpeedRun,
    val plan: SpeedPlan? = null,
    val summary: SpeedInteractionSummary? = null,
    val snippets: Map<Int, String> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null,
    val comprehension: SpeedComprehensionReview? = null,
)

@Composable
internal fun SpeedRecapInspectionDialog(state: SpeedRecapInspection, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("speed_recap_inspection"), color = MaterialTheme.colorScheme.background) {
            key(state.run) { Column(Modifier.safeDrawingPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("speed_recap_inspection_close")) { Text("关闭练习回看") }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("练习回看", style = MaterialTheme.typography.headlineSmall)
                    if (state.loading) Text("正在读取记录…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.error?.let { Text(it, Modifier.testTag("speed_recap_inspection_error"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error) }
                    if (!state.loading && state.error == null) {
                        SpeedComprehensionReviewControls(state.comprehension)
                        HorizontalDivider()
                    }
                    if (!state.loading && state.summary != null) SpeedInteractionRecapControls(state.summary, state.run, state.plan, null,
                        snippets = state.snippets,
                        initiallyExpanded = state.comprehension?.status != SpeedComprehensionReviewStatus.AVAILABLE)
                    else if (!state.loading && state.error == null) Text("操作记录暂不可用。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                }
            } }
        }
    }
}

@Composable
private fun SpeedComprehensionReviewControls(review: SpeedComprehensionReview?) {
    Column(Modifier.fillMaxWidth().testTag("speed_comprehension_review"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("理解题", style = MaterialTheme.typography.titleMedium)
        if (review?.status != SpeedComprehensionReviewStatus.AVAILABLE) {
            Text(when (review?.status) {
                SpeedComprehensionReviewStatus.NO_QUESTIONS -> "本轮没有理解题。"
                SpeedComprehensionReviewStatus.INCOMPLETE -> "本轮尚未完成理解检测，暂不展开参考答案。"
                else -> "理解题资料暂不可用，已有成绩保留。"
            }, Modifier.fillMaxWidth().testTag("speed_comprehension_status"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        Text("答对 ${review.items.count { it.answer.correct }}/${review.items.size} 题 · 当时的 AI 参考答案",
            Modifier.fillMaxWidth().testTag("speed_comprehension_summary"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        review.items.forEachIndexed { index, item -> key(item) {
            val question = item.question
            var expanded by rememberSaveable { mutableStateOf(false) }
            var evidenceExpanded by rememberSaveable { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("speed_comprehension_question_${question.id}")) {
                    Text("第 ${index + 1} 题 · ${if (item.answer.correct) "已答对" else "未答对"} · ${if (expanded) "收起" else "展开"}",
                        Modifier.fillMaxWidth())
                }
                if (expanded) {
                    Text(question.prompt, Modifier.fillMaxWidth().testTag("speed_comprehension_prompt_${question.id}"),
                        style = MaterialTheme.typography.bodyLarge)
                    question.options.forEachIndexed { option, text ->
                        Text("${'A' + option}. $text", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("你的选择：${'A' + item.answer.selectedIndex}",
                        Modifier.fillMaxWidth().testTag("speed_comprehension_selected_${question.id}"), style = MaterialTheme.typography.bodyMedium)
                    Text("参考答案：${'A' + question.answerIndex} · AI",
                        Modifier.fillMaxWidth().testTag("speed_comprehension_reference_${question.id}"), style = MaterialTheme.typography.bodyMedium)
                    Text(question.explanation, Modifier.fillMaxWidth().testTag("speed_comprehension_explanation_${question.id}"),
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { evidenceExpanded = !evidenceExpanded }, contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("speed_comprehension_evidence_toggle_${question.id}")) {
                        Text(if (evidenceExpanded) "收起原文依据" else "查看原文依据 · 第 ${question.paragraph + 1} 段", Modifier.fillMaxWidth())
                    }
                    if (evidenceExpanded) Text(item.evidenceText,
                        Modifier.fillMaxWidth().testTag("speed_comprehension_evidence_${question.id}"),
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
        } }
    }
}
