package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.SpeedCorrection
import dev.lexicon.domain.SpeedSegmentation

internal data class SpeedCorrectionInspection(
    val correction: SpeedCorrection,
    val sentence: SpeedSegmentation? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

/** Immutable historical source: readable even after the full article cache expires. */
@Composable
internal fun SpeedCorrectionInspectionDialog(state: SpeedCorrectionInspection, onClose: () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    val sentence = state.sentence
    val preview = remember(sentence, color) {
        sentence?.let { source -> buildAnnotatedString {
            append(source.text)
            (listOf(source.start) + source.breaks + source.end).zipWithNext().forEachIndexed { index, (from, to) ->
                addStyle(SpanStyle(background = color.copy(alpha = if (index % 2 == 0) .17f else .04f)),
                    from - source.start, to - source.start)
            }
        } }
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("切分原句") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) Text("正在读取本机记录…", style = MaterialTheme.typography.bodySmall)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (preview != null) {
                Text("${sentence!!.granularity.name} · 第 ${sentence.paragraph + 1} 段", style = MaterialTheme.typography.labelSmall)
                SelectionContainer { Text(preview, Modifier.testTag("speed_correction_sentence"),
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = ReadingSerif)) }
                Text("当时保存的切分", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("关闭") } })
}
