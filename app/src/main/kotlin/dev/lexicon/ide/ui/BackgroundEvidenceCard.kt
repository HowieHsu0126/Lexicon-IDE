package dev.lexicon.ide.ui

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.BackgroundSource
import dev.lexicon.domain.StudyAssist
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Evidence stays readable in the reading panel. Only client-validated URLs can be opened. */
@Composable
internal fun StudyAssistContent(value: StudyAssist, retry: (() -> Unit)? = null) {
    if (value.kind != "background") {
        Text("AI · ${value.title}", style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(value.text, style = MaterialTheme.typography.bodyMedium) }
        return
    }
    var selectedSource by remember(value) { mutableStateOf<BackgroundSource?>(null) }
    val retrieved = value.retrievalStatus == "retrieved" && value.sources.isNotEmpty()
    Column(Modifier.testTag("background_result"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (retrieved) value.title else "暂未找到背景资料", style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(value.text, style = MaterialTheme.typography.bodyMedium) }
        if (retrieved) {
            Text(if (value.model == "retrieval-only") "检索摘录" else "AI 解释 · 附检索依据",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                value.sources.filter { it.id in value.citedSourceIds }.forEach { source ->
                    OutlinedButton(onClick = { selectedSource = source }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Text("[${source.id}] ${source.title}", maxLines = 1)
                    }
                }
            }
        } else retry?.let { TextButton(onClick = it) { Text("重新检索") } }
    }
    selectedSource?.let { source ->
        BackgroundSourceDialog(source, value.evidence, onDismiss = { selectedSource = null })
    }
}

@Composable
private fun BackgroundSourceDialog(source: BackgroundSource, evidence: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var openError by remember(source) { mutableStateOf(false) }
    val height = LocalConfiguration.current.screenHeightDp.dp * .58f
    val uri = remember(source.url) { Uri.parse(source.url) }
    val retrievedDate = remember(source.retrievedAt) {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(source.retrievedAt))
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("[${source.id}] ${source.title}") },
        text = { Column(Modifier.heightIn(max = height).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(source.provider, style = MaterialTheme.typography.labelSmall)
            SelectionContainer { Text(source.excerpt, style = MaterialTheme.typography.bodyMedium) }
            if (evidence.isNotBlank()) Text(evidence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text("检索于 $retrievedDate", style = MaterialTheme.typography.labelSmall)
            Text(listOfNotNull(source.license, source.revisionId?.let { "修订 $it" }).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
            Text(uri.host.orEmpty(), style = MaterialTheme.typography.labelSmall)
            if (openError) Text("未能打开来源，摘录仍可离线查看。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("返回阅读") } },
        dismissButton = { TextButton(enabled = uri.scheme == "https" && !uri.host.isNullOrBlank(), onClick = {
            openError = runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri) }.isFailure
        }) { Text("原始条目 ↗") } },
    )
}
