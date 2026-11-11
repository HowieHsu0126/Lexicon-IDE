@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.PersonalizationOutcome
import dev.lexicon.domain.Article
import dev.lexicon.domain.SpeedRun
import kotlin.math.roundToInt

/** Describes the supplied post-application records; never infers a benefit from the change. */
@Composable
internal fun SpeedOutcomeControls(outcome: PersonalizationOutcome?, modifier: Modifier = Modifier,
    evidenceRuns: List<SpeedRun> = emptyList(), articles: List<Article> = emptyList(), onInspectRun: ((SpeedRun) -> Unit)? = null,
    decision: dev.lexicon.domain.PersonalizationDecision? = null) {
    if (outcome == null) return
    var expanded by rememberSaveable(outcome.decisionId) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().animateContentSize(tween(160)).testTag("speed_outcome"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp),
            modifier = Modifier.fillMaxWidth().testTag("speed_outcome_toggle")) {
            Text(if (expanded) "收起应用后的练习" else "查看应用后的练习", Modifier.fillMaxWidth())
        }
        if (expanded) {
            decision?.takeIf { it.id == outcome.decisionId }?.let {
                Text(paceDecisionDate(it.decidedAt) + " · " + paceDecisionStatus(it), Modifier.fillMaxWidth().testTag("speed_outcome_decision"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${outcome.granularity.name} · ${outcome.rate} 意群/分钟", style = MaterialTheme.typography.titleSmall)
            if (outcome.sampleCount == 0) Text("尚无符合条件的后续练习。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                OutcomeMetric("练习 / 材料", "${outcome.sampleCount} 次 / ${outcome.materialCount} 份")
                OutcomeMetric("答对 / 总题数", "${outcome.correct} / ${outcome.total}")
                OutcomeMetric("暂停 / 回读", "${outcome.pauses} / ${outcome.revisits}")
                if (outcome.evidenceEnough && outcome.accuracy != null) OutcomeMetric("这些练习的正确率", "${(outcome.accuracy!! * 100).roundToInt()}%")
                if (!outcome.evidenceEnough) Text("样本仍少，先查看具体练习记录。", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("仅统计沿用该建议、完整播放并首次作答的材料；记录不能说明调速带来的因果效果。",
                Modifier.testTag("speed_outcome_scope"), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            PaceEvidenceRows(outcome.evidenceRunIds, evidenceRuns, articles, onInspectRun, "pace_outcome")
        }
    }
}

@Composable
private fun OutcomeMetric(label: String, value: String) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
