package dev.lexicon.ide.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.Capture

@Composable
internal fun PendingCaptures(
    captures: List<Capture>, onOpen: (Capture) -> Unit, onNew: () -> Unit,
    archivedCaptures: List<Capture> = emptyList(), loading: Boolean = false, busy: Boolean = false,
    onArchive: ((Capture, Boolean) -> Unit)? = null,
) {
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val activeScroll = rememberLazyListState()
    val archivedScroll = rememberLazyListState()
    val archivedView = showArchived && onArchive != null
    val shown = if (archivedView) archivedCaptures else captures
    val ordered = remember(shown) { shown.sortedByDescending { it.updatedAt } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("捕获", style = MaterialTheme.typography.titleMedium)
            TextButton(enabled = !busy, onClick = onNew, modifier = Modifier.testTag("capture_new")) { Text("新建捕获") }
        }
        if (onArchive != null) Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!showArchived, { showArchived = false }, label = { Text("待处理") },
                modifier = Modifier.testTag("capture_inbox_active"))
            FilterChip(showArchived, { showArchived = true }, label = { Text("已归档") },
                modifier = Modifier.testTag("capture_inbox_archived"))
        }
        LazyColumn(Modifier.weight(1f).testTag("pending_captures"), state = if (archivedView) archivedScroll else activeScroll,
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("count") {
                Text(if (loading) "读取捕获…" else "${shown.size} 条捕获", modifier = Modifier.testTag("capture_inbox_count"),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!loading && shown.isEmpty()) item("empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (archivedView) "没有归档" else "没有待处理捕获", style = MaterialTheme.typography.titleMedium)
                    Text(if (archivedView) "归档的捕获可以随时恢复。" else "查词后，选择“留待处理”即可保留。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!loading) items(ordered, key = { it.id }) { cap ->
                OutlinedCard(onClick = { onOpen(cap) }, modifier = Modifier.fillMaxWidth().testTag("pending_capture_${cap.id}").animateItem(),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(cap.target.ifBlank { "选择目标词 / 短语" }, style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text(cap.sentence.ifBlank { cap.selectedText.ifBlank { "尚未填写文本" } }, maxLines = 3,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        if (cap.sentence.isBlank()) Text("待补充原句", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(cap.sourceRef.display(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            onArchive?.let { change ->
                                TextButton(enabled = !busy, onClick = { change(cap, !showArchived) },
                                    modifier = Modifier.testTag(if (showArchived) "capture_restore_${cap.id}" else "capture_archive_${cap.id}")) {
                                    Text(if (showArchived) "恢复" else "归档")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
