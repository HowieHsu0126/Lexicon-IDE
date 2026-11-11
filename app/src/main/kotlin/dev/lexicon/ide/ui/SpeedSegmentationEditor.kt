package dev.lexicon.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import dev.lexicon.domain.SpeedSegmentation

/** Edits only domain-approved absolute offsets. The original sentence is never rewritten. */
@Composable
internal fun SpeedSegmentationEditor(
    segmentation: SpeedSegmentation,
    busy: Boolean,
    error: String?,
    onSave: (List<Int>) -> Unit,
    onRestore: () -> Unit,
    onClose: () -> Unit,
) {
    var draft by rememberSaveable(segmentation.planKey, segmentation.start, segmentation.end, segmentation.breaks) {
        mutableStateOf<List<Int>>(segmentation.breaks.toList())
    }
    val legal = remember(segmentation) { segmentation.legalBreaks.sorted().distinct() }
    val original = remember(segmentation) { segmentation.suggestedBreaks.toSet() }
    val current = remember(draft) { draft.toSet() }
    val difference = (current - original).size + (original - current).size
    val changed = current != segmentation.breaks.toSet()
    val tint = MaterialTheme.colorScheme.primary
    val preview = remember(segmentation.text, segmentation.start, segmentation.end, draft, tint) {
        buildAnnotatedString {
            append(segmentation.text)
            (listOf(segmentation.start) + draft.sorted() + segmentation.end).zipWithNext().forEachIndexed { index, (start, end) ->
                addStyle(SpanStyle(background = tint.copy(alpha = if (index % 2 == 0) .17f else .045f)),
                    start - segmentation.start, end - segmentation.start)
            }
        }
    }
    val edges = remember(segmentation, legal) { listOf(segmentation.start) + legal + segmentation.end }
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(
        dismissOnBackPress = !busy, dismissOnClickOutside = false, usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false,
    )) {
        val dialogView = LocalView.current
        val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
        val lightBackground = MaterialTheme.colorScheme.background.luminance() > .5f
        DisposableEffect(dialogWindow, lightBackground) {
            val controller = dialogWindow?.let { WindowCompat.getInsetsController(it, dialogView) }
            val previousStatus = controller?.isAppearanceLightStatusBars
            val previousNavigation = controller?.isAppearanceLightNavigationBars
            controller?.isAppearanceLightStatusBars = lightBackground
            controller?.isAppearanceLightNavigationBars = lightBackground
            onDispose {
                previousStatus?.let { controller?.isAppearanceLightStatusBars = it }
                previousNavigation?.let { controller?.isAppearanceLightNavigationBars = it }
            }
        }
        Surface(Modifier.fillMaxSize().testTag("speed_segmentation_editor"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("调整意群", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose, enabled = !busy, modifier = Modifier.testTag("segmentation_close")) { Text("关闭") }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("segmentation_content"),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item(key = "preview") {
                        Text("${segmentation.granularity.name} · 第 ${segmentation.paragraph + 1} 段",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer {
                            Text(preview, Modifier.fillMaxWidth().padding(top = 10.dp).testTag("segmentation_original_preview"),
                                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = ReadingSerif))
                        }
                    }
                    item(key = "summary") {
                        Text("${draft.size + 1} 个意群 · 与原切分相差 $difference 处",
                            Modifier.testTag("segmentation_difference"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("点选词间断点。亮色为分开，灰色为连读。", Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (legal.isEmpty()) item(key = "no-boundaries") {
                        Text("本句没有可调整的断点。", style = MaterialTheme.typography.bodyMedium)
                    }
                    itemsIndexed(legal, key = { _, offset -> offset }) { index, offset ->
                        val left = segmentation.text.substring(edges[index] - segmentation.start, offset - segmentation.start).trim()
                        val right = segmentation.text.substring(offset - segmentation.start, edges[index + 2] - segmentation.start).trim()
                        val selected = offset in current
                        Surface(shape = RoundedCornerShape(10.dp),
                            color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(selected, enabled = !busy,
                                role = Role.Switch, onValueChange = { split ->
                                    draft = if (split) (draft + offset).distinct().sorted() else draft.filterNot { it == offset }
                                }).testTag("segmentation_boundary_$offset").semantics {
                                    contentDescription = "$left 与 $right 之间${if (selected) "分开" else "连读"}"
                                }.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(left, Modifier.weight(1f).testTag("segmentation_boundary_${offset}_left"),
                                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                    Text(if (selected) "│" else "·", style = MaterialTheme.typography.titleMedium,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(right, Modifier.weight(1f).testTag("segmentation_boundary_${offset}_right"),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 20.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    error?.let { Text(it, Modifier.testTag("segmentation_error"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error) }
                    if (segmentation.planKey != segmentation.basePlanKey) TextButton(onClick = onRestore, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("segmentation_restore")) { Text("恢复整组原切分") }
                    Button(onClick = { onSave(draft.sorted()) }, enabled = changed && !busy,
                        modifier = Modifier.fillMaxWidth().testTag("segmentation_save")) {
                        Text(if (busy) "保存中…" else "保存，下次训练生效")
                    }
                }
            }
        }
    }
}
