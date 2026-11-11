@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.*
import kotlin.math.roundToInt

/** Local controls commit pace at the end of a drag, so article planning never runs per pixel. */
@Composable
internal fun SkimReadingControls(
    plan: SkimPlan, study: ArticleStudy?, selectedIds: List<String>, role: String,
    original: Boolean, upgrading: Boolean, canUpgrade: Boolean,
    onMinutes: (Int) -> Unit, onPace: (Int) -> Unit, onRole: (String) -> Unit,
    onQuestion: (String) -> Unit, onUpgrade: () -> Unit, onSentence: (SkimSentenceRange) -> Unit,
    onDisclosure: (Int) -> Unit = {}, onIncludeDisclosures: (Boolean) -> Unit = {},
    afterFilters: @Composable () -> Unit = {},
) {
    var draftPace by remember(plan.planningWpm) { mutableFloatStateOf(plan.planningWpm.toFloat()) }
    Text("已选 ${plan.wordsSelected} 词 · 约 ${"%.1f".format(plan.estimatedMinutes)} 分钟", Modifier.testTag("skim_plan_summary"), style = MaterialTheme.typography.bodySmall)
    if (plan.exceedsBudget) Text("完整句超出预算 ${plan.budgetOverflowWords} 词", style = MaterialTheme.typography.labelSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(5, 10).forEach { n ->
        FilterChip(plan.targetMinutes == n, { onMinutes(n) }, label = { Text("$n 分钟") }, modifier = Modifier.testTag("skim_budget_$n"))
    } }
    Text("规划速度 · ${draftPace.roundToInt()} WPM", style = MaterialTheme.typography.labelMedium)
    Slider(value = draftPace, onValueChange = { draftPace = it }, valueRange = 80f..400f, steps = 15,
        onValueChangeFinished = { onPace((draftPace / 20).roundToInt() * 20) }, modifier = Modifier.testTag("skim_planning_pace"))
    Text("用于安排时间，可按自己的节奏调整。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (original && study?.scope != null) Text("段落角色与问题覆盖${ReadingStudyScopes.label(study.scope)}。其余原文可自由阅读。",
        Modifier.testTag("skim_scope_notice"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("全部", "论点", "证据", "背景", "结论").forEach { name ->
            FilterChip(role == name, { onRole(name) }, label = { Text(name) }, modifier = Modifier.testTag("skim_role_$name"))
        }
    }
    if (original && plan.disclosures.isNotEmpty()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            plan.disclosures.forEach { disclosure ->
                TextButton(onClick = { onDisclosure(disclosure.paragraphIndex) }, modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("skim_disclosure_${disclosure.paragraphIndex}")) { Text("作者声明 · 第 ${disclosure.paragraphIndex + 1} 段") }
            }
            FilterChip(plan.includesDisclosures, { onIncludeDisclosures(!plan.includesDisclosures) },
                label = { Text("纳入略读") }, modifier = Modifier.testTag("skim_include_disclosures"))
        }
    }
    afterFilters()
    if (original && !study?.questions.isNullOrEmpty()) {
        Text("带着问题读 · 可多选", style = MaterialTheme.typography.titleSmall)
        if (plan.questionCoverage.isNotEmpty()) {
            Text("已纳入证据 ${plan.coveredQuestionIds.size} / ${plan.selectedQuestionIds.size} 个问题", Modifier.testTag("skim_question_coverage"),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        study.questions.forEach { q ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(q.id in selectedIds, { onQuestion(q.id) }, label = { Text(q.question) },
                    modifier = Modifier.fillMaxWidth().testTag("skim_question_${q.id}"))
                plan.questionCoverage.singleOrNull { it.questionId == q.id }?.let { coverage ->
                    Text(skimCoverageLabel(coverage), Modifier.testTag("skim_coverage_${q.id}"), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        plan.sentenceRanges.filter { q.id in it.questionIds }.forEachIndexed { index, range ->
                            TextButton(onClick = { onSentence(range) }, modifier = Modifier.testTag("skim_answer_${q.id}_$index")) {
                                Text("原文证据 ${index + 1}")
                            }
                        }
                    }
                }
            }
        }
        if (study.questions.any { it.evidence.isEmpty() } || plan.questionCoverage.any { it.status == SkimQuestionStatus.INVALID_EVIDENCE }) {
            TextButton(onClick = onUpgrade, enabled = canUpgrade && !upgrading, modifier = Modifier.testTag("skim_upgrade_evidence")) {
                Text(if (upgrading) "正在更新…" else "更新问题定位 · AI")
            }
        }
    } else if (!original) Text("原文问题定位在原文中使用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (plan.paragraphIndices.isEmpty()) Text("没有匹配段落，请调整筛选。", style = MaterialTheme.typography.labelSmall)
}

private fun skimCoverageLabel(value: SkimQuestionCoverage): String = when (value.status) {
    SkimQuestionStatus.COVERED -> "全部证据句已纳入"
    SkimQuestionStatus.PARTIAL -> "已纳入 ${value.selectedSentenceIds.size} / ${value.evidenceSentenceIds.size} 句；其余超出预算"
    SkimQuestionStatus.BUDGET_EXCLUDED -> "证据句超出时间预算"
    SkimQuestionStatus.ROLE_EXCLUDED -> "部分证据不在当前段落筛选中"
    SkimQuestionStatus.PARAGRAPH_ONLY -> "仅有相关段落，尚无精确证据句"
    SkimQuestionStatus.INVALID_EVIDENCE -> "证据定位与正文不匹配，请更新"
}
