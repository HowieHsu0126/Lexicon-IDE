@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.PaceSuggestion
import dev.lexicon.domain.PaceHold
import dev.lexicon.domain.PersonalizationDecision
import dev.lexicon.domain.PersonalizationDecisionStatus
import dev.lexicon.domain.PaceAssessment
import dev.lexicon.domain.PaceAssessmentStatus
import dev.lexicon.domain.Article
import dev.lexicon.domain.SpeedRun

/** One accessible switch; persistence and the opt-in policy belong to the caller. */
@Composable
internal fun RememberTrainingSettingsToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
) {
    Row(modifier.fillMaxWidth().toggleable(value = enabled, enabled = editable, role = Role.Switch,
        onValueChange = onEnabledChange).padding(vertical = 4.dp).testTag("speed_remember_settings"),
        verticalAlignment = Alignment.CenterVertically) {
        Text("记住训练设置", Modifier.weight(1f).padding(end = 14.dp), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = enabled, onCheckedChange = null, enabled = editable)
    }
}

/**
 * Displays supplied evidence without computing a recommendation. The caller supplies only a
 * currently undoable decision; the repository remains responsible for revision checks.
 */
@Composable
internal fun SpeedPersonalizationControls(
    suggestion: PaceSuggestion?,
    lastApplied: PersonalizationDecision?,
    onApply: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onUndo: (String) -> Unit,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
    hold: PaceHold? = null,
    onReleaseHold: () -> Unit = {},
    assessment: PaceAssessment? = null,
    articles: List<Article> = emptyList(),
    onInspectRun: ((SpeedRun) -> Unit)? = null,
) {
    val proposal = suggestion?.takeIf { it.hasDisplayableEvidence() }
    val applied = lastApplied?.takeIf {
        it.status == PersonalizationDecisionStatus.APPLIED && it.suggestion.hasDisplayableEvidence()
    }
    if (proposal == null && applied == null && hold == null && assessment == null) return
    Column(modifier.fillMaxWidth().animateContentSize(tween(160)).testTag("speed_personalization"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (proposal != null) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("speed_pace_suggestion")) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${proposal.granularity.name} · 建议下轮 ${proposal.fromRate} → ${proposal.toRate} 意群/分钟",
                        style = MaterialTheme.typography.titleSmall)
                    Text("本次建议采用 ${proposal.sampleCount} 轮 · 答对 ${proposal.correct}/${proposal.total} 题",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("speed_pace_evidence"))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onDismiss(proposal.id) }, enabled = editable,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("speed_pace_keep")
                                .semantics { contentDescription = "保持此节奏" }) { Text("保持此节奏") }
                        TextButton(onClick = { onApply(proposal.id) }, enabled = editable,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("speed_pace_apply")) { Text("应用") }
                    }
                }
            }
        }
        if (assessment != null && (proposal == null || assessment.suggestion == proposal)) {
            if (proposal == null && !(assessment.status == PaceAssessmentStatus.HELD && hold != null))
                Text(paceAssessmentSummary(assessment), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().testTag("speed_pace_status"))
            PaceAssessmentEvidence(assessment, articles, onInspectRun)
        }
        if (applied != null) {
            FlowRow(Modifier.fillMaxWidth().testTag("speed_pace_applied"),
                horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("已应用 ${applied.suggestion.granularity.name} · ${applied.suggestion.fromRate} → ${applied.suggestion.toRate} 意群/分钟",
                    Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onUndo(applied.id) }, enabled = editable,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("speed_pace_undo")) { Text("撤销") }
            }
        }
        if (hold != null) {
            Column(Modifier.fillMaxWidth().testTag("speed_pace_hold")) {
                Text("已保持此节奏 · ${hold.granularity.name} · ${hold.rate} 意群/分钟",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("暂缓${if (hold.direction > 0) "提速" else "降速"}建议；修改节奏或允许后续建议后，按新记录判断。", Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onReleaseHold, enabled = editable,
                    modifier = Modifier.align(Alignment.End).sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("speed_pace_release_hold")) { Text("允许后续建议") }
            }
        }
    }
}

/** Presentation sanity check only: no threshold, speed increment or evidence selection policy. */
private fun PaceSuggestion.hasDisplayableEvidence() = id.isNotBlank() && fromRate > 0 && toRate > 0 &&
    fromRate != toRate && sampleCount > 0 && total > 0 && correct in 0..total
