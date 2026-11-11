@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.lexicon.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId

/** Service setup and task history share the same thin coordinator as offline practice. */
@Composable
internal fun IeltsExerciseRoute(vm: IeltsExerciseViewModel, articles: List<Article>, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, state.generation?.requestId) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            repeat(120) {
                delay(5_000)
                val current = vm.state.value
                if (current.generation?.stage !in activeIeltsStages) return@repeatOnLifecycle
                if (!current.busy) vm.refresh()
            }
        }
    }
    val candidate = state.candidate
    if (candidate != null) {
        IeltsPracticeScreen(candidate, state.draftResponses, state.writingText, state.isSaving,
            state.answerView, state.error, vm::answer, vm::write, vm::submit,
            onBack = { vm.onBack(onBack) }, onRetry = vm::retry,
            writingTask1Text = state.writingTask1Text, onWritingTask1 = vm::writeTask1)
        return
    }
    var pairing by rememberSaveable { mutableStateOf(false) }
    var manualPairing by rememberSaveable { mutableStateOf(false) }
    var coverage by rememberSaveable { mutableStateOf(false) }
    var endpoint by rememberSaveable { mutableStateOf("") }
    // Pairing secrets never enter SavedStateHandle, saved instance state or ordinary preferences.
    var token by remember { mutableStateOf("") }
    var readingConnectionFile by remember { mutableStateOf(false) }
    var connectionFileEpoch by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importPairing = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val epoch = connectionFileEpoch
            readingConnectionFile = true
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readNBytes(IeltsPairingPreview.MAX_DOCUMENT_BYTES + 1).also {
                            require(it.isNotEmpty() && it.size <= IeltsPairingPreview.MAX_DOCUMENT_BYTES)
                        }
                    } ?: error("unreadable_pairing_document")
                }
                try {
                    if (epoch == connectionFileEpoch && pairing) { vm.inspectPairing(bytes); manualPairing = false }
                } finally { bytes.fill(0) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (epoch == connectionFileEpoch && pairing) vm.pairingImportFailed() }
            finally { if (epoch == connectionFileEpoch) readingConnectionFile = false }
        }
    }
    LaunchedEffect(state.connection.endpoint, state.connection.paired) {
        if (state.connection.paired) { token = ""; pairing = false }
        if (endpoint.isBlank()) endpoint = state.connection.endpoint.orEmpty()
    }
    val titles = remember(articles) { articles.associate { it.id to it.title } }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().testTag("ielts_workspace")) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { connectionFileEpoch++; token = ""; vm.onBack(onBack) }, modifier = Modifier.heightIn(min = 48.dp).testTag("ielts_workspace_back")) { Text("← 返回") }
            TextButton(onClick = { pairing = !pairing; if (!pairing) {
                connectionFileEpoch++; readingConnectionFile = false; token = ""; vm.discardPairing()
            } }, modifier = Modifier.heightIn(min = 48.dp).testTag("ielts_connection")) {
                Text(if (state.connection.paired) "连接设置" else "连接电脑")
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("ielts_workspace_list"),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("雅思练习 · AI", style = MaterialTheme.typography.headlineMedium)
            }
            item {
                AnimatedVisibility(pairing) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { connectionFileEpoch++; importPairing.launch(arrayOf("application/json", "text/plain")) },
                            enabled = !state.busy && !readingConnectionFile, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ielts_import_pairing")) {
                            Text("导入电脑连接文件")
                        }
                        if (readingConnectionFile) Text("[loading] 读取连接文件…", style = MaterialTheme.typography.labelMedium)
                        val preview = state.pairingPreview
                        if (preview != null) {
                            Text(preview.endpoint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("ielts_pairing_preview"))
                            Text("证书指纹 · 与电脑核对", style = MaterialTheme.typography.labelMedium)
                            Text(preview.certificateSha256.chunked(8).joinToString(" "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("有效至 ${Instant.ofEpochMilli(preview.expiresAt).atZone(ZoneId.systemDefault()).toLocalDate()}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = vm::confirmImportedPairing, enabled = !state.busy,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("ielts_confirm_pairing")) { Text("连接这台电脑") }
                                TextButton(onClick = vm::discardPairing, enabled = !state.busy,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("ielts_discard_pairing")) { Text("取消") }
                            }
                        }
                        TextButton(onClick = { manualPairing = !manualPairing; vm.discardPairing() }, enabled = !state.busy && !readingConnectionFile,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("ielts_manual_pairing")) {
                            Text(if (manualPairing) "收起手动连接" else "手动连接")
                        }
                        AnimatedVisibility(manualPairing) { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth().testTag("ielts_endpoint"),
                            label = { Text("HTTPS 地址") }, placeholder = { Text("https://…") }, singleLine = true)
                        OutlinedTextField(token, { if (it.length <= 128) token = it }, Modifier.fillMaxWidth().testTag("ielts_pairing_token"),
                            label = { Text("配对码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.pair(endpoint.trim(), token) },
                                enabled = !state.busy && endpoint.isNotBlank() && token.isNotBlank(),
                                modifier = Modifier.heightIn(min = 48.dp).testTag("ielts_pair")) { Text("连接") }
                        }
                        } }
                        if (state.connection.paired) TextButton(onClick = vm::clearPairing, enabled = !state.busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("ielts_unpair")) { Text("断开配对") }
                    }
                }
            }
            state.error?.let { error -> item {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("ielts_workspace_error"))
                if (pairing || !state.canRegenerate) TextButton(onClick = { if (pairing && state.pairingPreview != null) vm.confirmImportedPairing()
                    else if (pairing && manualPairing) vm.pair(endpoint.trim(), token) else vm.retry() },
                    enabled = !state.busy && (!pairing || state.pairingPreview != null || (manualPairing && endpoint.isNotBlank() && token.isNotBlank())),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("ielts_workspace_retry")) {
                    Text(if (pairing) "重新连接" else "重试")
                }
            } }
            val generation = state.generation
            if (state.preparedArticleId != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.preparedTitle ?: titles[state.preparedArticleId] ?: "已保存的原文", style = MaterialTheme.typography.titleLarge)
                    Text("${state.preparedParagraphCount} 段 · 阅读 + 写作", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (generation == null || generation.stage == IeltsGenerationStage.PREPARED) {
                        Button(onClick = vm::generate, enabled = state.canGenerate && !state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ielts_generate")) {
                            Text(if (state.connection.paired) "生成练习" else "连接电脑后生成")
                        }
                    } else {
                        Text(ieltsStageLabel(generation.stage, generation.issue), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("ielts_job_state"))
                        if (state.canRegenerate) {
                            Button(onClick = vm::regenerate, enabled = !state.busy && !state.isSaving,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ielts_regenerate")) { Text("重新生成") }
                        } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = vm::refresh, enabled = !state.busy,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("ielts_refresh")) { Text("刷新状态") }
                            if (generation.stage in activeIeltsStages) TextButton(onClick = vm::cancel, enabled = !state.busy,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("ielts_cancel")) { Text("取消生成") }
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { coverage = !coverage }, modifier = Modifier.heightIn(min = 48.dp)) { Text("参考资料范围") }
                AnimatedVisibility(coverage) {
                    Text("已核验官方公开样例与考试格式。尚未建立剑雅 19–21 的完整资料库；本练习不标为近三年真题。Task 1 图表题仍在开发。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.practiceHistory.isNotEmpty()) item { Text("练习记录", style = MaterialTheme.typography.titleMedium) }
            items(state.practiceHistory, key = { "practice:${it.id}" }) { item ->
                TextButton(onClick = { item.attemptId?.let(vm::openAttempt) },
                    shape = RectangleShape,
                    enabled = item.status != IeltsPracticeHistoryStatus.UNAVAILABLE && !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ielts_history_${item.id}")) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(titles[item.articleId] ?: "已保存的原文", color = MaterialTheme.colorScheme.onSurface)
                        Text(when (item.status) {
                            IeltsPracticeHistoryStatus.SUBMITTED -> "已提交 · 查看解析"
                            IeltsPracticeHistoryStatus.DRAFT -> "草稿 · ${item.answeredCount ?: 0}/${item.questionCount ?: 0}"
                            IeltsPracticeHistoryStatus.UNAVAILABLE -> "记录暂不可读"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val pending = state.generationHistory.filter {
                it.stage != IeltsGenerationStage.READY &&
                    (generation?.requestId == null || it.requestId != generation.requestId)
            }
            if (pending.isNotEmpty()) item { Text("生成记录", style = MaterialTheme.typography.titleMedium) }
            items(pending, key = { "generation:${it.id}" }) { item ->
                TextButton(onClick = { item.requestId?.let(vm::openGeneration) },
                    shape = RectangleShape,
                    enabled = item.requestId != null && !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ielts_generation_${item.id}")) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(titles[item.articleId] ?: "原文练习", color = MaterialTheme.colorScheme.onSurface)
                        Text(ieltsStageLabel(item.stage, item.issue), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (state.practiceHistory.isEmpty() && pending.isEmpty() && state.preparedArticleId == null) item {
                Text("打开一篇原文，在阅读工具中选择“雅思练习”。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.historyError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

private val activeIeltsStages = setOf(IeltsGenerationStage.QUEUED, IeltsGenerationStage.GENERATING,
    IeltsGenerationStage.REVIEWING, IeltsGenerationStage.RECONCILING, IeltsGenerationStage.CANCELLING)

private fun ieltsStageLabel(stage: IeltsGenerationStage, issue: IeltsGenerationIssue?): String =
    if (issue == IeltsGenerationIssue.OUTCOME_UNKNOWN) "[checking] 原请求结果待确认" else when (stage) {
    IeltsGenerationStage.PREPARED -> "[ready] 已保存请求"
    IeltsGenerationStage.RECONCILING -> "[checking] 核对任务状态"
    IeltsGenerationStage.QUEUED -> "[queued] 等待生成"
    IeltsGenerationStage.GENERATING -> "[generating] 生成题目"
    IeltsGenerationStage.REVIEWING -> "[reviewing] 独立作答与核验"
    IeltsGenerationStage.READY -> "[done] 练习已保存"
    IeltsGenerationStage.CANCELLING -> "[cancelling] 等待进程结束"
    IeltsGenerationStage.CANCELLED -> "[cancelled] 已取消"
    IeltsGenerationStage.FAILED -> "[failed] 本次生成未通过"
    IeltsGenerationStage.INTERRUPTED -> "[interrupted] 运行状态待确认"
    IeltsGenerationStage.UNAVAILABLE -> "[unavailable] 记录暂不可读"
}
