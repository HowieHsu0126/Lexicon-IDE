package dev.lexicon.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.*
import java.math.MathContext

/** Fixed zero-origin scale. Labels remain normal text, so font scaling never clips canvas glyphs. */
@Composable
internal fun IeltsVisualChart(visual: IeltsVisual, modifier: Modifier = Modifier) {
    val maximum = remember(visual) { visual.series.flatMap { it.cells }.maxOf { IeltsVisualContract.number(it.value) } }
    val primary = MaterialTheme.colorScheme.primary
    val colors = listOf(primary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary)
    Column(modifier.fillMaxWidth().testTag("ielts_task1_visual"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // A source description is reading text. Keep every word and system font scaling;
            // proportional type avoids the oversized block produced by a long IDE heading.
            Text(visual.title, Modifier.fillMaxWidth().testTag("ielts_visual_title"),
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = ReadingSerif))
            Text(visual.unit, Modifier.fillMaxWidth().testTag("ielts_visual_unit"), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (visual.kind == "grouped_bar") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0", style = MaterialTheme.typography.labelSmall)
                Text(maximum.toPlainString(), style = MaterialTheme.typography.labelSmall)
            }
        }
        visual.categories.forEachIndexed { ci, category ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(category, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("ielts_visual_category_$ci"))
                visual.series.forEachIndexed { si, series ->
                    val cell = series.cells[ci]
                    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                        contentDescription = "$category, ${series.label}, ${cell.value} ${visual.unit}"
                    }.testTag("ielts_visual_cell_${si}_$ci"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Values never rely on bar length, color or horizontal scrolling for access.
                        Text("${series.label} · ${cell.value}", style = MaterialTheme.typography.bodyMedium)
                        if (visual.kind == "grouped_bar") {
                            val fraction = remember(cell.value, maximum) {
                                IeltsVisualContract.number(cell.value).divide(maximum, MathContext.DECIMAL64).toFloat()
                            }
                            Box(Modifier.fillMaxWidth().height(10.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                                if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(colors[si]))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun IeltsVisualWritingCard(task: IeltsVisualTask, paragraphs: List<IeltsParagraph>, writing: String,
    words: Int?, editable: Boolean, reviewed: Boolean, onWriting: (String) -> Unit,
    onReadEvidence: () -> Unit = {}, inputGesture: IeltsInputGesture? = null) {
    var evidence by rememberSaveable(task.requestSha256, task.taskId) { mutableStateOf(false) }
    val editorGesture = inputGesture?.let { Modifier.observeIeltsInputStart(it, true) } ?: Modifier
    Surface(Modifier.fillMaxWidth().testTag("ielts_writing_task1"), color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("写作 · Task 1", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                Text("${task.suggestedMinutes} 分钟 · 至少 ${task.minimumWords} 词", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IeltsVisualChart(task.visual)
            Text(IeltsVisualContract.INSTRUCTION, Modifier.fillMaxWidth().testTag("ielts_visual_instruction"),
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = ReadingSerif))
            if (editable) OutlinedTextField(value = writing, onValueChange = onWriting,
                modifier = Modifier.fillMaxWidth().then(editorGesture).testTag("ielts_writing1_input"), minLines = 7,
                label = { Text("写作") }, textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = ReadingSerif))
            else Text(writing.ifBlank { "未填写" }, Modifier.fillMaxWidth().testTag("ielts_writing1_saved"),
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = ReadingSerif))
            Text("${words ?: 0} 词", Modifier.testTag("ielts_writing1_words"), style = MaterialTheme.typography.labelMedium)
            if (reviewed && (words ?: 0) < 150) Text("尚未达到 150 词", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onReadEvidence(); evidence = !evidence },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ielts_visual_evidence_toggle")) {
                Text(if (evidence) "收起数据出处" else "数据出处")
            }
            IeltsDisclosure(evidence) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    task.visual.contextRefs.forEach { ref ->
                        // The package validator already checked these exact ranges against paragraphs.
                        if (paragraphs.any { it.paragraphId == ref.paragraphId }) Text(ref.quote,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = ReadingSerif))
                    }
                }
            }
        }
    }
}
