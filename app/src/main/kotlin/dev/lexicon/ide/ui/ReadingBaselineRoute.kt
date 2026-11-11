package dev.lexicon.ide.ui

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.lexicon.domain.*

@Composable
internal fun ReadingBaselineRoute(article: Article, settings: AppSettings, aiAllowed: Boolean, hasKey: Boolean,
    entry: Int, historyId: String?, vm: ReadingBaselineViewModel, onBack: () -> Unit, onReadAt: (ReaderAnchor?) -> Unit, canOpenOriginal: Boolean = true) {
    val state by vm.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(article.id, entry, historyId, aiAllowed, hasKey) {
        if (historyId == null) vm.bind(article, aiAllowed, hasKey, entry)
        else vm.openHistory(historyId) {}
    }
    DisposableEffect(lifecycle) {
        vm.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ -> vm.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.setForeground(false) }
    }
    ReadingBaselineScreen(article, settings, state.copy(canOpenOriginal = canOpenOriginal), BaselineUiActions(onBack, vm::reload,
        vm::setStart, vm::setCount, vm::confirmUnseen, vm::prepare, vm::togglePlay,
        vm::completeParagraph, vm::answer, vm::submitAnswer, vm::retry, { onReadAt(vm.readerAnchor()) }))
}
