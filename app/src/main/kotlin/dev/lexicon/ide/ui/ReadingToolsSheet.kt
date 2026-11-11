@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.StudyMode

/** One holder per bounded article/body/entry/level identity, kept alive while the sheet is closed. */
@Composable
internal fun ReadingToolsSheet(
    visible: Boolean, identity: String, mode: StudyMode, tab: String, quiet: Boolean, completed: Boolean, canComplete: Boolean,
    onTab: (String) -> Unit, onClose: () -> Unit, onAppearance: () -> Unit, onPrimary: () -> Unit,
    onQuiet: () -> Unit, onComplete: () -> Unit, contentReady: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    key(identity) {
        val stateHolder = rememberSaveableStateHolder()
        val density = LocalDensity.current
        val height = LocalConfiguration.current.screenHeightDp.dp * if (density.fontScale >= 1.5f) .92f else .84f
        if (visible) ModalBottomSheet(onDismissRequest = onClose,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ReadingToolsLayout(mode, tab, quiet, completed, canComplete, onTab, onClose, onAppearance, onPrimary, onQuiet, onComplete,
                stateHolder, Modifier.fillMaxWidth().height(height).imePadding().navigationBarsPadding(), contentReady, content)
        }
    }
}

/** Fixed chrome with one scrolling content viewport; compact windows expose secondary actions in a menu. */
@Composable
internal fun ReadingToolsLayout(
    mode: StudyMode, tab: String, quiet: Boolean, completed: Boolean, canComplete: Boolean,
    onTab: (String) -> Unit, onClose: () -> Unit, onAppearance: () -> Unit, onPrimary: () -> Unit,
    onQuiet: () -> Unit, onComplete: () -> Unit, stateHolder: SaveableStateHolder, modifier: Modifier = Modifier,
    contentReady: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val tabScroll = rememberScrollState()
    val selectedTab = remember { BringIntoViewRequester() }
    LaunchedEffect(tab) { withFrameNanos { }; selectedTab.bringIntoView() }
    fun act(action: () -> Unit) { focus.clearFocus(); keyboard?.hide(); action() }
    BoxWithConstraints(modifier.testTag("reader_tools_sheet")) {
        val fontScale = LocalDensity.current.fontScale
        val compact = maxHeight < 260.dp + 90.dp * fontScale
        val minimumUsefulHeight = 152.dp + 48.dp * fontScale + if (maxWidth < 320.dp) 48.dp * fontScale else 0.dp
        val scrollEverything = maxHeight < minimumUsefulHeight
        var more by rememberSaveable { mutableStateOf(false) }
        val header: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).testTag("reader_tools_header"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (mode == StudyMode.INTENSIVE) "精读" else "略读", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { act(onAppearance) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Aa") }
                TextButton(onClick = { act(onClose) }, modifier = Modifier.heightIn(min = 48.dp).testTag("reader_tools_close")
                    .semantics { contentDescription = "返回原文" }) { Text("关闭") }
            }
        }
        val tabs: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth().testTag("reader_tools_tabs").horizontalScroll(tabScroll).padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("导图", "重点", "略读", "背景", "检测", "分级", "对照").forEach { label ->
                    FilterChip(tab == label, { act { onTab(label) } }, label = { Text(label) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("reader_tab_$label")
                            .then(if (label == tab) Modifier.bringIntoViewRequester(selectedTab) else Modifier))
                }
            }
        }
        val footer: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().testTag("reader_tools_footer").padding(horizontal = 18.dp, vertical = 6.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (compact && !scrollEverything) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { act(onPrimary) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("reader_tools_primary")) {
                        Text(if (tab == "略读") "开始略读" else "继续阅读")
                    }
                    Box {
                        TextButton(onClick = { focus.clearFocus(); keyboard?.hide(); more = true },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("reader_tools_more").semantics { contentDescription = "更多阅读操作" }) { Text("更多") }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = { Text(if (quiet) "显示辅助" else "无干扰通读") },
                                onClick = { more = false; act(onQuiet) }, modifier = Modifier.heightIn(min = 48.dp).testTag("reader_quiet"))
                            DropdownMenuItem(text = { Text(if (completed) "已完成本次阅读" else "完成本次阅读") },
                                onClick = { more = false; act(onComplete) }, enabled = !completed && canComplete,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("reader_complete"))
                        }
                    }
                } else {
                    FilledTonalButton(onClick = { act(onPrimary) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reader_tools_primary")) {
                        Text(if (tab == "略读") "开始略读" else "继续阅读")
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { act(onQuiet) }, modifier = Modifier.heightIn(min = 48.dp).testTag("reader_quiet")) {
                            Text(if (quiet) "显示辅助" else "无干扰通读")
                        }
                        TextButton(onClick = { act(onComplete) }, enabled = !completed && canComplete,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("reader_complete")) {
                            Text(if (completed) "已完成本次阅读" else "完成本次阅读")
                        }
                    }
                }
            }
        }
        // Keep the provider, content callsite and field nodes stable when IME/rotation changes layout strategy.
        stateHolder.SaveableStateProvider(tab) {
            val contentScroll = rememberScrollState()
            var savedPosition by rememberSaveable { mutableIntStateOf(0) }
            var restoring by remember { mutableStateOf(true) }
            val ready by rememberUpdatedState(contentReady)
            LaunchedEffect(contentReady) {
                restoring = true
                if (contentReady) {
                    // The cached study and directory must have reached layout before a restored
                    // position is clamped to the current content extent.
                    withFrameNanos { }
                    withFrameNanos { }
                    contentScroll.scrollTo(savedPosition)
                    restoring = false
                }
            }
            LaunchedEffect(contentScroll) {
                snapshotFlow { Triple(ready, restoring, contentScroll.value) }.collect { (available, pending, position) ->
                    if (available && !pending) savedPosition = position
                }
            }
            Column(Modifier.fillMaxSize().then(if (scrollEverything)
                Modifier.testTag("reader_tools_content_$tab").verticalScroll(contentScroll) else Modifier)) {
                header(); tabs()
                Column(Modifier.fillMaxWidth().then(if (scrollEverything) Modifier else
                    Modifier.weight(1f).testTag("reader_tools_content_$tab").verticalScroll(contentScroll))
                    .padding(horizontal = 22.dp).padding(top = 8.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                footer()
            }
        }
    }
}
