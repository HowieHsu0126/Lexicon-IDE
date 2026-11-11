@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.DictionaryEntry
import dev.lexicon.domain.ReaderPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keep this call in composition while visible is false. identity binds capture/query/dictionary;
 * a separate last-result fingerprint preserves positions through a transient local reload.
 * Opening this page never performs another lookup.
 */
@Composable
internal fun DictionaryDetailContent(
    visible: Boolean,
    identity: String,
    entries: List<DictionaryEntry>,
    dictionaryName: String,
    preferences: ReaderPreferences,
    onBack: () -> Unit,
    onReturnToSentence: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialEntryIndex: Int = 0,
    renderProbe: DictionaryResultRenderProbe? = null,
    loading: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    closeEnabled: Boolean = true,
) {
    key(identity) {
        var lastResultIdentity by rememberSaveable { mutableStateOf<String?>(null) }
        val readyIdentity = remember(visible, entries, loading, error) {
            if (visible && !loading && error == null) DictionaryPresentation.identity("", "", entries) else null
        }
        val resultIdentity = readyIdentity ?: lastResultIdentity
        SideEffect { if (readyIdentity != null && lastResultIdentity != readyIdentity) lastResultIdentity = readyIdentity }
        key(resultIdentity) {
            var selected by rememberSaveable { mutableIntStateOf(initialEntryIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0))) }
            var chooser by rememberSaveable { mutableStateOf(false) }
            val states = rememberSaveableStateHolder()
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(visible) {
                if (visible) { focus.clearFocus(); keyboard?.hide() }
                else chooser = false
            }
            if (visible) {
                val selectedIndex = selected.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
                val entry = entries.getOrNull(selectedIndex)
                fun act(action: () -> Unit) { chooser = false; focus.clearFocus(); keyboard?.hide(); action() }
                BoxWithConstraints(modifier.fillMaxSize().testTag("dictionary_detail")) {
                    val compact = maxHeight < 240.dp
                    Column(Modifier.fillMaxSize()) {
                        Surface(color = MaterialTheme.colorScheme.surface) {
                            val actions: @Composable () -> Unit = {
                                TextButton(onClick = { act(onBack) }, modifier = Modifier.heightIn(min = 48.dp)
                                    .testTag("dictionary_detail_back").semantics { contentDescription = "返回查词" }) { Text("返回查词") }
                                TextButton(onClick = { act(onReturnToSentence) }, modifier = Modifier.heightIn(min = 48.dp)
                                    .testTag("dictionary_detail_sentence")) { Text("原句") }
                                if (!loading && error == null && entries.size > 1) Box {
                                    TextButton(onClick = { chooser = true }, modifier = Modifier.heightIn(min = 48.dp)
                                        .testTag("dictionary_detail_choose_match")
                                        .semantics { stateDescription = "第 ${selectedIndex + 1} 个，共 ${entries.size} 个匹配" }) {
                                        Text("${selectedIndex + 1} / ${entries.size}")
                                    }
                                    DropdownMenu(expanded = chooser, onDismissRequest = { chooser = false },
                                        modifier = Modifier.heightIn(max = 320.dp).testTag("dictionary_detail_matches")) {
                                        entries.forEachIndexed { index, match ->
                                            DropdownMenuItem(text = { Text("${index + 1} · ${match.word}") }, onClick = {
                                                selected = index; chooser = false
                                            }, modifier = Modifier.heightIn(min = 48.dp).testTag("dictionary_detail_match_$index")
                                                .semantics { stateDescription = if (selectedIndex == index) "当前匹配" else "选择匹配" })
                                        }
                                    }
                                }
                                TextButton(enabled = closeEnabled, onClick = { act(onClose) }, modifier = Modifier.heightIn(min = 48.dp)
                                    .testTag("dictionary_detail_close")) { Text("关闭") }
                            }
                            if (compact) Row(Modifier.fillMaxWidth().testTag("dictionary_detail_header").padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                TextButton(onClick = { act(onBack) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    .testTag("dictionary_detail_back").semantics { contentDescription = "返回查词" }) { Text("返回查词") }
                                TextButton(enabled = closeEnabled, onClick = { act(onClose) }, modifier = Modifier.heightIn(min = 48.dp)
                                    .testTag("dictionary_detail_close")) { Text("关闭") }
                                Box {
                                    TextButton(onClick = { chooser = true }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                        .testTag("dictionary_detail_choose_match").semantics { contentDescription = "更多词典操作" }) { Text("⋯") }
                                    DropdownMenu(expanded = chooser, onDismissRequest = { chooser = false },
                                        modifier = Modifier.heightIn(max = 320.dp).testTag("dictionary_detail_matches")) {
                                        DropdownMenuItem(text = { Text("原句") }, onClick = { act(onReturnToSentence) },
                                            modifier = Modifier.heightIn(min = 48.dp).testTag("dictionary_detail_sentence"))
                                        if (!loading && error == null) entries.forEachIndexed { index, match ->
                                            DropdownMenuItem(text = { Text("${index + 1} · ${match.word}") }, onClick = {
                                                selected = index; chooser = false
                                            }, modifier = Modifier.heightIn(min = 48.dp).testTag("dictionary_detail_match_$index"))
                                        }
                                    }
                                }
                            }
                            else FlowRow(Modifier.fillMaxWidth().testTag("dictionary_detail_header").padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) { actions() }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Surface(Modifier.weight(1f).fillMaxWidth(), color = readerBackground(preferences)) {
                            if (loading) Box(Modifier.fillMaxSize().padding(20.dp)) {
                                Text("读取本地释义…", Modifier.testTag("dictionary_detail_loading"), style = MaterialTheme.typography.bodySmall)
                            } else if (error != null) Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(error, Modifier.testTag("dictionary_detail_error"), style = MaterialTheme.typography.bodyMedium)
                                if (onRetry != null) TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("dictionary_detail_retry")) { Text("重试查词") }
                            } else if (entry == null) Box(Modifier.fillMaxSize().padding(20.dp)) {
                                Text("当前词条不可用，请返回查词。", style = MaterialTheme.typography.bodyMedium)
                            } else states.SaveableStateProvider(selectedIndex) {
                                val scroll = rememberLazyListState()
                                val projection by produceState<Pair<String, List<DictionaryTextBlock>>?>(null, entry.definition) {
                                    val source = entry.definition
                                    value = null
                                    val result = withContext(Dispatchers.Default) { DictionaryPresentation.blocks(source) }
                                    value = source to result
                                }
                                val blocks = projection?.takeIf { it.first == entry.definition }?.second
                                if (blocks == null) Box(Modifier.fillMaxSize().padding(20.dp)) {
                                    Text("准备释义…", Modifier.testTag("dictionary_detail_preparing"), style = MaterialTheme.typography.bodySmall)
                                } else {
                                    val textStyle = readerTextStyle(preferences)
                                    LazyColumn(Modifier.fillMaxSize().testTag("dictionary_detail_list"), state = scroll,
                                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
                                        item(key = "heading") {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(dictionaryName, Modifier.testTag("dictionary_detail_source"), style = MaterialTheme.typography.labelSmall,
                                                    color = textStyle.color.copy(alpha = .78f))
                                                Text("${entry.word}  ${entry.phonetic}", Modifier.testTag("dictionary_detail_word")
                                                    .dictionaryDrawProbe(renderProbe, selectedIndex, DictionaryTextPart.WORD, true),
                                                    style = textStyle, onTextLayout = if (renderProbe == null) null else { layout ->
                                                        renderProbe.onTextLayout(selectedIndex, DictionaryTextPart.WORD, true, layout.layoutInput.text.length,
                                                            layout.lineCount, (0 until layout.lineCount).any(layout::isLineEllipsized))
                                                    })
                                                if (entry.tags.isNotBlank()) Text(entry.tags, style = MaterialTheme.typography.labelSmall,
                                                    color = textStyle.color.copy(alpha = .78f))
                                                Spacer(Modifier.height(8.dp))
                                            }
                                        }
                                        itemsIndexed(blocks, key = { _, block -> "definition:${block.start}:${block.endExclusive}" }) { index, block ->
                                            SelectionContainer {
                                                Text(block.text, Modifier.fillMaxWidth().testTag("dictionary_detail_block_${block.start}")
                                                    .dictionaryDrawProbe(renderProbe.takeIf { index == 0 }, selectedIndex, DictionaryTextPart.DEFINITION, true),
                                                    style = textStyle, onTextLayout = if (renderProbe == null || index != 0) null else { layout ->
                                                        renderProbe.onTextLayout(selectedIndex, DictionaryTextPart.DEFINITION, true, layout.layoutInput.text.length,
                                                            layout.lineCount, (0 until layout.lineCount).any(layout::isLineEllipsized))
                                                    })
                                            }
                                        }
                                        if (blocks.isEmpty()) item(key = "empty") { Text("无释义", style = textStyle) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
