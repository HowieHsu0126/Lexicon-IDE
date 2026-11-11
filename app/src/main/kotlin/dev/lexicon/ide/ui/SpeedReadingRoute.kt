package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.lexicon.domain.*

@Composable
internal fun SpeedReadingRoute(article: Article, body: ArticleBody?, settings: AppSettings, aiAllowed: Boolean,
    hasKey: Boolean, entry: Int, start: Int, vm: SpeedReadingViewModel, onBack: () -> Unit, onReload: () -> Unit,
    onReadAt: (ReaderAnchor) -> Unit = {}, articles: List<Article> = emptyList(), onPaceHistory: (() -> Unit)? = null) {
    val state by vm.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(article.id, body?.contentHash, aiAllowed, hasKey, entry) { vm.bind(article, body, aiAllowed, hasKey, entry, start) }
    DisposableEffect(lifecycle) {
        vm.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ -> vm.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.pause(false) }
    }
    if (body == null) Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("意群速读", style = MaterialTheme.typography.headlineSmall)
        Text("需要可选择的原生正文。正在准备文章，也可稍后重试。")
        TextButton(onClick = onReload) { Text("重试正文") }
        TextButton(onClick = onBack) { Text("返回阅读") }
    } else SpeedReadingScreen(article, body, settings, state, SpeedUiActions(
        onBack, vm::prepare, vm::setGranularity, vm::setStart, vm::setCount, vm::setSpeed,
        vm::togglePlay, vm::previous, vm::next, vm::repeat, vm::seek, vm::setSentenceOnly,
        vm::answer, vm::continueAfterQuestion, vm::restart, vm::correction,
        vm::setRememberTraining, vm::applyPaceSuggestion, vm::dismissPaceSuggestion, vm::undoPaceSuggestion, vm::nextParagraph,
        vm::editSegmentation, vm::saveSegmentation, vm::restoreSegmentation, vm::closeSegmentation, vm::releasePaceHold,
        vm::nextRound, vm::naturalPlayPause, vm::naturalCompleteParagraph, vm::naturalRetry,
        { vm.currentReaderAnchor()?.let { anchor -> vm.leave { onReadAt(anchor) } } },
        vm::recoverProfile, vm::inspectRun, onPaceHistory,
        onRetrySetupSettings = vm::retrySetupSettings,
        onPauseForTools = { vm.pause(count = true) },
    ), articles)
}
