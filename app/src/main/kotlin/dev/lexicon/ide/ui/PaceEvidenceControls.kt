package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Presentation of the policy's selected records. This component does not select or score evidence. */
@Composable
internal fun PaceAssessmentEvidence(
    assessment: PaceAssessment, articles: List<Article>, onInspectRun: ((SpeedRun) -> Unit)?,
) {
    if (assessment.status in setOf(PaceAssessmentStatus.DISABLED, PaceAssessmentStatus.INVALID_PROFILE)) return
    key(assessment.granularity, assessment.rate, assessment.status, assessment.suggestion?.id,
        assessment.evidence.map { it.id }, assessment.correct, assessment.total, assessment.materialCount) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("speed_pace_details_toggle")) {
                Text(if (expanded) "收起建议依据" else "查看建议依据", Modifier.fillMaxWidth())
            }
            if (expanded) {
                Text("当前判断 · ${assessment.granularity.name} · ${assessment.rate} 意群/分钟\n已采用 ${assessment.total} 题 · ${assessment.materialCount} 份不同正文",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().testTag("speed_pace_requirements"))
                Text("至少 ${PersonalizationPolicy.MIN_QUESTIONS} 题、${PersonalizationPolicy.MIN_MATERIALS} 份不同正文；同一篇换段落仍算一份。",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().testTag("pace_assessment_requirements"))
                if (assessment.status == PaceAssessmentStatus.INSUFFICIENT_EVIDENCE &&
                    assessment.total >= PersonalizationPolicy.MIN_QUESTIONS && assessment.materialCount < PersonalizationPolicy.MIN_MATERIALS)
                    Text("题数已够，下一次请换一篇文章完成首次 AI 理解练习。", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().testTag("pace_assessment_next_material"))
                Text("采用同粒度、同节奏的首次 AI 理解练习，需完整播放并保持恒速。整体与最近两轮均达 85% 才建议提速 5%；整体低于 70% 时建议降速 10%。",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                PaceEvidenceRows(assessment.evidence.map { it.id }, assessment.evidence, articles, onInspectRun, "pace_assessment")
                val excluded = assessment.excludedCounts.filterValues { it > 0 }
                if (excluded.isNotEmpty()) Column(Modifier.fillMaxWidth().testTag("speed_pace_exclusions"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("未用于本次判断", style = MaterialTheme.typography.labelLarge)
                    excluded.forEach { (reason, count) ->
                        Text("${paceExclusionLabel(reason)} · $count 轮", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().testTag("pace_excluded_${reason.name}"))
                    }
                }
            }
        }
    }
}

/** IDs and order belong to the supplied assessment/outcome; missing or conflicting records are not substituted. */
@Composable
internal fun PaceEvidenceRows(
    ids: List<String>, runs: List<SpeedRun>, articles: List<Article>, onInspectRun: ((SpeedRun) -> Unit)?, tagPrefix: String,
) {
    val canonical = remember(runs) { runs.filter { it.id.isNotBlank() }.groupBy { it.id }
        .mapValues { (_, copies) -> copies.distinct().singleOrNull() } }
    val articleMap = remember(articles) { articles.groupBy { it.id }.mapValues { (_, copies) -> copies.distinct().singleOrNull() } }
    val selected = ids.distinct().mapNotNull { canonical[it] }
    val unavailable = ids.distinct().count { canonical[it] == null }
    if (unavailable > 0) Text("$unavailable 条记录不可用", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("${tagPrefix}_unavailable"))
    selected.forEach { run -> key(run.id) {
        Column(Modifier.fillMaxWidth().testTag("${tagPrefix}_run_${run.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(articleMap[run.articleId]?.title ?: "已移除文章", style = MaterialTheme.typography.bodyMedium)
            Text("${paceEvidenceDate(run.finishedAt)} · ${run.granularity.name} · ${run.groupsPerMinute} 意群/分钟",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("答对 ${run.answers.count { it.correct }}/${run.questionCount} 题", style = MaterialTheme.typography.bodySmall)
            if (onInspectRun != null) TextButton(onClick = { onInspectRun(run) }, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.heightIn(min = 48.dp).testTag("${tagPrefix}_open_${run.id}")) { Text("回看这次练习") }
        }
    } }
}

internal fun paceAssessmentSummary(assessment: PaceAssessment): String = when (assessment.status) {
    PaceAssessmentStatus.DISABLED -> "训练建议已关闭"
    PaceAssessmentStatus.INVALID_PROFILE -> "训练设置暂不可用"
    PaceAssessmentStatus.INSUFFICIENT_EVIDENCE -> "继续积累：${assessment.total} 题 · ${assessment.materialCount} 份不同正文"
    PaceAssessmentStatus.MAINTAIN -> "本次判断保持当前节奏"
    PaceAssessmentStatus.RATE_LIMIT -> "当前节奏已到调整边界"
    PaceAssessmentStatus.HELD -> "已按你的选择暂缓调速建议"
    PaceAssessmentStatus.ALREADY_DECIDED -> "这组练习已处理，等待新记录"
    PaceAssessmentStatus.SUGGESTED -> "已有下一轮节奏建议"
}

private fun paceExclusionLabel(reason: PaceExclusionReason): String = when (reason) {
    PaceExclusionReason.OTHER_CONFIGURATION -> "粒度或节奏不同"
    PaceExclusionReason.REPEAT_OR_FADED -> "重复或淡出练习"
    PaceExclusionReason.INCOMPLETE_OR_UNSTABLE_PACING -> "未完整播放或未保持恒速"
    PaceExclusionReason.NO_AI_QUESTIONS -> "没有 AI 理解题或尚未作答"
    PaceExclusionReason.INVALID_RECORD -> "记录不完整，无法用于判断"
    PaceExclusionReason.CONFLICTING_DUPLICATE -> "同一记录有冲突版本"
    PaceExclusionReason.OUTSIDE_SELECTED_EVIDENCE -> "符合条件，未进入本次选取范围"
}

private fun paceEvidenceDate(time: Long): String = if (time < 0) "时间不可用" else runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
}.getOrDefault("时间不可用")
