@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.lexicon.ide.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lexicon.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.UUID

/** Both the normal launch activity and the PROCESS_TEXT activity use this UI. */
@Composable
fun LexiconApp(
    repository: AppRepository,
    initialCaptureText: String? = null,
    onExternalCaptureDone: (() -> Unit)? = null,
) {
    val vm: LexiconViewModel = viewModel(factory = remember(repository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LexiconViewModel(repository) as T
        }
    })
    val readingVm: ReadingViewModel = viewModel(factory = remember(repository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ReadingViewModel(repository) as T
        }
    })
    val vocabularyRepository = repository.vocabulary
    val vocabularyVm: VocabularyLearningViewModel? = if (vocabularyRepository != null) viewModel(factory = remember(vocabularyRepository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                VocabularyLearningViewModel(vocabularyRepository) as T
        }
    }) else null
    val vocabularyState by (vocabularyVm?.state ?: kotlinx.coroutines.flow.flowOf(VocabularyLearningUiState(loading = false)))
        .collectAsStateWithLifecycle(initialValue = VocabularyLearningUiState(loading = vocabularyVm != null))
    val speedRepository = repository.speedReading
    val speedVm: SpeedReadingViewModel? = if (speedRepository != null) viewModel(factory = remember(speedRepository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                SpeedReadingViewModel(speedRepository, extras.createSavedStateHandle(), personalizationRepository = repository.personalization) as T
        }
    }) else null
    val baselineVm: ReadingBaselineViewModel? = if (repository.baseline != null && speedRepository != null)
        viewModel(factory = remember(repository) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                    ReadingBaselineViewModel(repository, extras.createSavedStateHandle()) as T
            }
        }) else null
    val baselineLibrary by (baselineVm?.library ?: kotlinx.coroutines.flow.flowOf(emptyList<ReadingBaselineAttempt>()))
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val ieltsVm: IeltsExerciseViewModel? = if (repository.ieltsGeneration != null && repository.ieltsPractice != null)
        viewModel(factory = remember(repository) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                    IeltsExerciseViewModel(requireNotNull(repository.ieltsGeneration), requireNotNull(repository.ieltsPractice),
                        extras.createSavedStateHandle(), studyRepository = repository.ieltsStudy) as T
            }
        }) else null
    val speedLibrary by (speedVm?.library ?: kotlinx.coroutines.flow.flowOf(SpeedLibrary())).collectAsStateWithLifecycle(initialValue = SpeedLibrary())
    val correctionInspection by (speedVm?.correctionInspection ?: kotlinx.coroutines.flow.flowOf<SpeedCorrectionInspection?>(null))
        .collectAsStateWithLifecycle(initialValue = null)
    val recapInspection by (speedVm?.recapInspection ?: kotlinx.coroutines.flow.flowOf<SpeedRecapInspection?>(null))
        .collectAsStateWithLifecycle(initialValue = null)
    val readingLibrary by readingVm.library.collectAsStateWithLifecycle()
    val articles by vm.articles.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val captures by vm.captures.collectAsStateWithLifecycle()
    val archivedCaptureIds by vm.archivedCaptureIds.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val readerStateHolder = rememberSaveableStateHolder()
    val workspaceStateHolder = rememberSaveableStateHolder()
    var route by rememberSaveable { mutableStateOf("articles") }
    var ieltsOpen by rememberSaveable { mutableStateOf(false) }
    var baselineArticleId by rememberSaveable { mutableStateOf<String?>(null) }
    var baselineHistoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var baselineEntry by rememberSaveable { mutableIntStateOf(0) }
    var readerId by rememberSaveable { mutableStateOf<String?>(null) }
    var readerPage by rememberSaveable { mutableStateOf(false) }
    var speedMode by rememberSaveable { mutableStateOf(false) }
    var paceHistoryOpen by rememberSaveable { mutableStateOf(false) }
    var speedParagraph by rememberSaveable { mutableIntStateOf(0) }
    var readerEntry by rememberSaveable { mutableIntStateOf(0) }
    var readerEntrySerial by rememberSaveable { mutableIntStateOf(0) }
    var initialMode by rememberSaveable { mutableStateOf(StudyMode.INTENSIVE) }
    var readerEntryKind by rememberSaveable { mutableStateOf(ReadingEntryKind.START) }
    var readerBudgetMinutes by rememberSaveable { mutableStateOf<Int?>(null) }
    var readerAnchorHash by rememberSaveable { mutableStateOf<String?>(null) }
    var readerAnchorStart by rememberSaveable { mutableIntStateOf(0) }
    var readerAnchorEnd by rememberSaveable { mutableIntStateOf(0) }
    var readerScopeKey by rememberSaveable { mutableStateOf<String?>(null) }
    var cardId by rememberSaveable { mutableStateOf<String?>(null) }
    var navigationReturns by rememberSaveable(stateSaver = CardNavigationStackSaver) {
        mutableStateOf(emptyList<NavigationReturnPoint>())
    }
    var captureDraft by rememberSaveable(stateSaver = CaptureDraftSaver) { mutableStateOf<Capture?>(null) }
    val captureId = captureDraft?.id
    var commands by rememberSaveable { mutableStateOf(false) }
    var exportConfirm by rememberSaveable { mutableStateOf(false) }
    var externalHandled by rememberSaveable { mutableStateOf(false) }
    var externalWorkspace by rememberSaveable { mutableStateOf(false) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(30_000) } }
    LaunchedEffect(articles, sources, java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate()) {
        readingVm.refreshEdition(articles, sources, now)
    }
    val reviewDay = ReviewDayRange.from(now)
    LaunchedEffect(reviewDay) { vm.observeReviewDay(reviewDay) }
    val dueCards = remember(cards, now) { cards.filter { it.isDueForReview(now) }.sortedBy { it.state.dueAt } }
    val pending = remember(captures, cards, archivedCaptureIds) {
        val saved = cards.mapTo(HashSet()) { it.captureId }
        if (archivedCaptureIds == null) emptyList() else captures.filter { it.id !in saved && it.id !in archivedCaptureIds.orEmpty() }
    }
    val archivedCaptures = remember(captures, archivedCaptureIds) { captures.filter { it.id in archivedCaptureIds.orEmpty() } }
    var settledStatus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(status) {
        settledStatus = null
        if (status.startsWith("[done]") || status.startsWith("[ready]")) { delay(3_000); settledStatus = status }
    }
    val readerArticle = articles.find { it.id == readerId }
    val activeCapture = captureDraft
    val readerAnchor = remember(readerAnchorHash, readerAnchorStart, readerAnchorEnd) {
        readerAnchorHash?.let { ReaderAnchor(it, readerAnchorStart, readerAnchorEnd) }
    }
    fun navigationSnapshot() = CardNavigationSnapshot(
        route, cardId, readerId, readerPage, speedMode, speedParagraph, readerEntry,
        initialMode, readerEntryKind, readerBudgetMinutes, readerAnchorHash,
        readerAnchorStart, readerAnchorEnd, readerScopeKey,
    )
    fun restoreNavigation(type: NavigationReturnType): Boolean {
        val point = navigationReturns.lastOrNull()?.takeIf { it.type == type } ?: return false
        val finishedSourceScope = readerScopeKey.takeIf { type == NavigationReturnType.SOURCE }
        navigationReturns = navigationReturns.dropLast(1)
        val saved = point.snapshot
        route = saved.route; cardId = saved.cardId; readerId = saved.readerId
        readerPage = saved.readerPage; speedMode = saved.speedMode; speedParagraph = saved.speedParagraph
        readerEntry = saved.readerEntry; initialMode = saved.initialMode; readerEntryKind = saved.readerEntryKind
        readerBudgetMinutes = saved.readerBudgetMinutes; readerAnchorHash = saved.readerAnchorHash
        readerAnchorStart = saved.readerAnchorStart; readerAnchorEnd = saved.readerAnchorEnd
        readerScopeKey = saved.readerScopeKey
        finishedSourceScope?.let(readerStateHolder::removeState)
        if (point.externalFinish) onExternalCaptureDone?.invoke()
        return true
    }
    fun openWorkspace(destination: String) {
        if (destination == "ielts") { ieltsOpen = ieltsVm != null; return }
        (navigationReturns.mapNotNull { it.snapshot.readerScopeKey } + listOfNotNull(readerScopeKey))
            .distinct().filter { it.startsWith("card-source:") }.forEach(readerStateHolder::removeState)
        baselineArticleId = null; baselineHistoryId = null; readerId = null
        readerScopeKey = null; readerPage = false; speedMode = false
        cardId = null; navigationReturns = emptyList(); route = destination
    }
    fun advanceReaderEntry() {
        readerEntrySerial = maxOf(readerEntrySerial, readerEntry) + 1
        readerEntry = readerEntrySerial
    }
    val closeReader: () -> Unit = {
        if (!restoreNavigation(NavigationReturnType.SOURCE)) { readerId = null; readerScopeKey = null }
    }
    val closeCard: () -> Unit = {
        if (!restoreNavigation(NavigationReturnType.CARD)) { route = "cards"; cardId = null }
    }
    val openCard: (String) -> Unit = { id ->
        if (!(route == "card" && cardId == id && readerId == null)) {
            val external = initialCaptureText != null && !externalWorkspace
            navigationReturns = navigationReturns + NavigationReturnPoint(
                NavigationReturnType.CARD, navigationSnapshot(), external,
            )
            readerId = null; readerPage = false; speedMode = false
            cardId = id; route = "card"
            if (external) externalWorkspace = true
        }
    }
    val openReading: (String, StudyMode, Int?, ReaderAnchor?) -> Unit = { id, mode, budget, anchor ->
        baselineArticleId = null; baselineHistoryId = null
        if (readerId != id) readerScopeKey = null
        speedMode = false; readerPage = false; initialMode = mode
        readerEntryKind = if (anchor == null) ReadingEntryKind.START else ReadingEntryKind.ANCHOR
        readerBudgetMinutes = budget; readerAnchorHash = anchor?.bodyHash
        readerAnchorStart = anchor?.start ?: 0; readerAnchorEnd = anchor?.end ?: 0
        advanceReaderEntry(); readerId = id
    }
    val cardSources = remember(cards, captures, articles) {
        cards.associate { it.id to resolveCardSourceTarget(it, captures, articles) }
    }
    val openCardSource: (dev.lexicon.domain.Card) -> Unit = { card ->
        val target = resolveCardSourceTarget(card, captures, articles)
        if (target == null) vm.notify("[error] 暂未找到这张卡的原文")
        else {
            navigationReturns = navigationReturns + NavigationReturnPoint(NavigationReturnType.SOURCE, navigationSnapshot())
            openReading(target.articleId, StudyMode.INTENSIVE, null, target.anchor)
            // A temporary visit must not overwrite the reader state underneath this card.
            readerScopeKey = "card-source:${UUID.randomUUID()}"
            if (target.anchor == null) vm.notify("[ready] 已打开文章；当前语境没有可确认的原文位置")
        }
    }
    val continueReading: (Article) -> Unit = { article ->
        openReading(article.id, StudyMode.INTENSIVE, null, null)
        readerEntryKind = ReadingEntryKind.CONTINUE
    }
    val resumeLibrary by readingVm.resumeLibrary.collectAsStateWithLifecycle()

    val closeCapture = {
        captureDraft = null; readingVm.clearResult()
        if (initialCaptureText != null && !externalWorkspace) onExternalCaptureDone?.invoke()
    }
    val showCapture: (Capture) -> Unit = { cap ->
        if (cap.selectedText.length > CaptureLimits.SELECTED_TEXT || cap.sentence.length > CaptureLimits.SENTENCE || cap.target.length > CaptureLimits.TARGET) {
            vm.notify("[error] 选区过长，请缩小选区")
        } else {
            val existing = captures.firstOrNull { it.id == cap.id } ?: captures.firstOrNull { VocabularyLearning.sameCapture(it, cap) }
            val preserved = existing ?: cap
            readingVm.clearResult(); captureDraft = preserved
            vm.recordCaptureOpened()
        }
    }
    LaunchedEffect(initialCaptureText, externalHandled) {
        if (!externalHandled && initialCaptureText != null) {
            externalHandled = true
            val text = initialCaptureText.trim()
            if (text.isNotBlank()) showCapture(Capture(
                id = UUID.randomUUID().toString(), target = if (text.length <= CaptureLimits.TARGET) text else "",
                selectedText = text, createdAt = System.currentTimeMillis(),
            ))
        }
    }
    LaunchedEffect(readerId) { readerId?.let(vm::openBody) }
    LaunchedEffect(vm.notice) {
        if (vm.notice?.startsWith("[done]") == true || vm.notice?.startsWith("[saved]") == true) {
            delay(4_000); vm.clearNotice()
        }
    }

    val exportAnki = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.perform("exporting .apkg", {
            withContext(Dispatchers.IO) {
                val temp = File.createTempFile("lexicon-", ".apkg", context.cacheDir)
                try {
                    vm.createAnki(temp)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                        ?: throw IOException("Destination unavailable")
                    // Mark the snapshot exported only after the document provider closes successfully.
                    vm.confirmAnkiExport(temp)
                } finally { temp.delete() }
            }
        }) { vm.notify("[done] Anki 快照已导出，可在 AnkiDroid 中打开") }
    }
    val exportEvents = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.perform("exporting metrics", {
            withContext(Dispatchers.IO) {
                val temp = File.createTempFile("lexicon-metrics-", ".json", context.cacheDir)
                try {
                    vm.createEvents(temp)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                        ?: throw IOException("Destination unavailable")
                } finally { temp.delete() }
            }
        }) { vm.notify("[done] 本机指标已导出，不含学习文本与 Key") }
    }
    val importDictionary = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.perform("importing dictionary", {
            withContext(Dispatchers.IO) {
                val temp = File.createTempFile("lexicon-dictionary-", ".sqlite", context.cacheDir)
                try {
                    context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { copyDictionaryInput(input, it) } }
                        ?: throw IOException("Dictionary unavailable")
                    vm.importDictionary(temp)
                } finally { temp.delete() }
            }
        }, vm::dictionaryImported)
    }
    val importApiKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.perform("securing key", {
            withContext(Dispatchers.IO) {
                val key = context.contentResolver.openInputStream(uri)?.use(::readApiKeyInput)
                    ?: throw IOException("API file unavailable")
                vm.importKey(key)
            }
        }, vm::keyImported)
    }
    val exportKnowledge = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) vm.perform("exporting notes", {
            withContext(Dispatchers.IO) {
                val temp = File.createTempFile("lexicon-notes-", ".md", context.cacheDir)
                try {
                    readingVm.export(temp)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                        ?: throw IOException("Destination unavailable")
                } finally { temp.delete() }
            }
        }) { vm.notify("[done] 已导出 Markdown，可在 Notion 中导入") }
    }
    PredictiveBackHandler(enabled = !paceHistoryOpen && !commands && captureId == null && !exportConfirm && (ieltsOpen || baselineArticleId != null || readerId != null || route != "articles")) { progress ->
        try {
            progress.collect { backProgress = it.progress }
            if (ieltsOpen) ieltsVm?.onBack { ieltsOpen = false }
            else if (baselineArticleId != null) baselineVm?.leave { baselineArticleId = null; baselineHistoryId = null }
            else if (speedMode) { speedVm?.leave { speedMode = false } }
            else if (readerPage) readerPage = false
            else if (readerId != null) closeReader()
            else if (route == "card") closeCard()
            else openWorkspace("articles")
        } catch (_: CancellationException) { /* Cancelled gesture preserves the route. */ }
        finally { backProgress = 0f }
    }
    LexiconTheme(settings) {
        Surface(Modifier.fillMaxSize(), color = if (initialCaptureText != null && !externalWorkspace) Color.Transparent else MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                translationX = size.width * backProgress * 0.07f
                alpha = 1f - backProgress * 0.12f
            }) {
                if (initialCaptureText != null && !externalWorkspace) {
                    // The translucent PROCESS_TEXT activity leaves the original browser visible behind the sheet.
                    Box(Modifier.fillMaxSize())
                } else if (ieltsOpen && ieltsVm != null) {
                    IeltsExerciseRoute(ieltsVm, articles, onBack = { ieltsOpen = false })
                } else if (baselineArticleId != null && baselineVm != null) {
                    val baselineState by baselineVm.state.collectAsStateWithLifecycle()
                    val existing = articles.find { it.id == baselineArticleId }
                    val ref = baselineState.material?.sourceRef
                    val article = existing ?: Article(requireNotNull(baselineArticleId), requireNotNull(baselineArticleId), emptyList(),
                        ref?.title ?: "已保存的阅读材料", ref?.url.orEmpty(), "", sourceName = ref?.source.orEmpty())
                    ReadingBaselineRoute(article, settings, sources.filter { it.id in article.sourceIds }.any { it.aiAllowed },
                        vm.hasKey, baselineEntry, baselineHistoryId, baselineVm,
                        onBack = { baselineVm.leave { baselineArticleId = null; baselineHistoryId = null } },
                        onReadAt = { anchor -> baselineVm.leave { openReading(article.id, StudyMode.INTENSIVE, null, anchor) } },
                        canOpenOriginal = existing != null)
                } else if (readerId != null) {
                    if (readerArticle != null) readerStateHolder.SaveableStateProvider(readerScopeKey ?: readerArticle.id) {
                      if (speedMode && speedVm != null) SpeedReadingRoute(readerArticle, vm.body?.takeIf { it.articleId == readerId }, settings,
                          sources.filter { it.id in readerArticle.sourceIds }.any { it.aiAllowed }, vm.hasKey, readerEntry, speedParagraph, speedVm,
                          onBack = { speedVm.leave { speedMode = false } }, onReload = { vm.openBody(readerArticle.id) },
                          onReadAt = { anchor -> openReading(readerArticle.id, StudyMode.INTENSIVE, null, anchor) }, articles = articles, onPaceHistory = { paceHistoryOpen = true })
                      else if (readerPage) InAppArticlePage(readerArticle, sources.filter { it.id in readerArticle.sourceIds }.any { it.aiAllowed },
                          onBack = { readerPage = false }, onCapture = showCapture)
                      else ReadingWorkspace(
                        article = readerArticle, body = vm.body?.takeIf { it.articleId == readerId }, loading = vm.bodyLoading,
                        settings = settings, initialMode = initialMode, aiAllowed = sources.filter { it.id in readerArticle.sourceIds }.any { it.aiAllowed },
                        captureVisible = captureId != null, cards = cards, vm = readingVm,
                        onBack = closeReader, onCapture = showCapture, onRetry = { vm.openBody(readerArticle.id) },
                        onOpenPage = { readerPage = true }, onSettings = vm::setSettings, onEvent = vm::event, entryToken = readerEntry,
                        onSpeed = if (speedVm != null) { paragraph -> speedParagraph = paragraph; speedMode = true } else null,
                        initialBudgetMinutes = readerBudgetMinutes, initialAnchor = readerAnchor,
                        entryKind = readerEntryKind,
                        vocabularyVm = vocabularyVm, captures = captures,
                        onVocabularyCard = openCard,
                        onIelts = if (ieltsVm != null) {
                            {
                                vm.body?.takeIf { it.articleId == readerArticle.id }?.let { original ->
                                    ieltsVm.prepare(readerArticle, original, sources.filter { it.id in readerArticle.sourceIds }.any { it.aiAllowed })
                                    ieltsOpen = true
                                }
                            }
                        } else null,
                    ) } else Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                        Text("原文暂不可用")
                        TextButton(onClick = closeReader) { Text("返回") }
                    }
                } else {
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        contentWindowInsets = WindowInsets.safeDrawing,
                        bottomBar = {
                            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 7.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(onClick = { commands = true }, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        shape = RoundedCornerShape(14.dp)) { Text(">_  命令", style = MaterialTheme.typography.labelLarge) }
                                    TextButton(onClick = { openWorkspace("review") }) { Text(if (dueCards.isEmpty()) "复习" else "复习  ${dueCards.size}  →") }
                                }
                            }
                        },
                    ) { insets ->
                        Column(Modifier.fillMaxSize().padding(insets)) {
                            WorkspaceHeader(onHome = { openWorkspace("articles") })
                            val message = vm.busy?.let { "处理中…" } ?: vm.notice ?: status.takeUnless { it == settledStatus }.orEmpty()
                            AnimatedVisibility(message.isNotBlank(), enter = fadeIn(tween(150)), exit = fadeOut(tween(120))) {
                                StatusLine(message, onDismiss = if (vm.notice != null) vm::clearNotice else null)
                            }
                            AnimatedContent(targetState = route to cardId.takeIf { route == "card" }, modifier = Modifier.weight(1f), label = "workspace_route",
                                transitionSpec = {
                                    (fadeIn(tween(210, 35)) + slideInVertically(tween(250, easing = FastOutSlowInEasing)) { it / 35 }) togetherWith
                                        (fadeOut(tween(100)) + slideOutVertically(tween(150)) { -it / 70 })
                                }) { destination ->
                              val visibleRoute = destination.first
                              val visibleCard = cards.find { it.id == destination.second }
                              // The outgoing animation keeps its own card identity. Drafts also
                              // belong to one content revision, not the generic "card" route.
                              val workspaceKey = if (visibleRoute == "card")
                                  "card:${destination.second}:${visibleCard?.updatedAt}" else visibleRoute
                              workspaceStateHolder.SaveableStateProvider(workspaceKey) {
                                when (visibleRoute) {
                                    "articles" -> DailyReadingHome(articles, readingVm.edition, vm.busy != null, vm::sync, { article, mode ->
                                        openReading(article.id, mode, if (mode == StudyMode.SKIM) 5 else null, null)
                                    }, { route = "sources" }, { route = "history" }, onSpeed = if (speedVm != null) { article ->
                                        readerPage = false; speedMode = true; speedParagraph = 0; readerAnchorHash = null
                                        readerBudgetMinutes = null; readerScopeKey = null; advanceReaderEntry(); readerId = article.id
                                    } else null, onBaseline = if (baselineVm != null) { article ->
                                        baselineEntry++; baselineHistoryId = null; baselineArticleId = article.id
                                    } else null, resumes = resumeLibrary, onContinue = continueReading)
                                    "baseline_history" -> BaselineHistoryScreen(baselineLibrary, articles, { route = "articles" }, { id ->
                                        baselineLibrary.find { it.id == id }?.let { attempt ->
                                            baselineEntry++; baselineArticleId = attempt.articleId; baselineHistoryId = id
                                        }
                                    })
                                    "history" -> ArticleExplorer(articles.filter { it.read }, sources, vm.busy != null, vm::sync, { article ->
                                        continueReading(article)
                                    }, { route = "sources" }, onBaseline = if (baselineVm != null) { article ->
                                        baselineEntry++; baselineHistoryId = null; baselineArticleId = article.id
                                    } else null)
                                    "knowledge" -> KnowledgeScreen(readingLibrary, readingVm.busy != null, { readingVm.saveNote(it) }, readingVm::deleteNote,
                                        readingVm::expressionReview, { exportKnowledge.launch("Lexicon-notes-${LocalDate.now()}.md") }, { id ->
                                            openReading(id, StudyMode.INTENSIVE, null, null)
                                        }, noteBusy = readingVm.noteBusy, noteError = readingVm.error,
                                        onSaveNoteAsync = { note, saved, failed -> readingVm.saveNote(note, saved) { message ->
                                            readingVm.clearError(); failed(message)
                                        } }, onOpenNote = { note -> note.articleId?.let { id ->
                                            openReading(id, StudyMode.INTENSIVE, null, note.originalAnchor())
                                        } }, backgroundRepository = readingVm.backgroundRepository)
                                    "speed_history" -> SpeedHistoryScreen(speedLibrary, articles, { route = "articles" }, { id ->
                                        readerPage = false; speedMode = true; speedParagraph = 0; readerAnchorHash = null
                                        readerBudgetMinutes = null; readerScopeKey = null; advanceReaderEntry(); readerId = id
                                    }, onInspectCorrection = speedVm?.let { it::inspectCorrection },
                                        onReadOriginal = { id -> openReading(id, StudyMode.INTENSIVE, null, null) },
                                        onInspectRun = speedVm?.let { it::inspectRun }, onPaceHistory = { paceHistoryOpen = true })
                                    "progress" -> {
                                        val progressVm: BaselineProgressViewModel? = if (repository.baseline != null && speedRepository != null)
                                            viewModel(factory = remember(repository) {
                                                object : ViewModelProvider.Factory {
                                                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                                        BaselineProgressViewModel(requireNotNull(repository.baseline), speedRepository) as T
                                                }
                                            }) else null
                                        val baselineProgress by (progressVm?.state ?: kotlinx.coroutines.flow.flowOf(BaselineProgressUiState(loading = false)))
                                            .collectAsStateWithLifecycle(initialValue = BaselineProgressUiState())
                                        ProgressScreen(readingLibrary, cards, { route = "articles" },
                                            baselineProgress.summary, baselineProgress.loading, baselineProgress.error,
                                            { progressVm?.retry() }, { id ->
                                                baselineLibrary.find { it.id == id }?.let { attempt ->
                                                    baselineEntry++; baselineArticleId = attempt.articleId; baselineHistoryId = id
                                                }
                                            }, { route = "baseline_history" })
                                    }
                                    "captures" -> PendingCaptures(pending, { cap -> captureDraft = cap; vm.recordCaptureOpened() }, {
                                        showCapture(Capture(UUID.randomUUID().toString(), target = "", selectedText = "", createdAt = now))
                                    }, archivedCaptures = archivedCaptures, loading = archivedCaptureIds == null, busy = vm.busy != null,
                                        onArchive = if (vm.canOrganizeCaptures) { cap, archived -> vm.setCaptureArchived(cap, archived) } else null)
                                    "vocabulary" -> vocabularyVm?.let { model ->
                                        VocabularyQueueScreen(model, vocabularyState, onOpenCard = openCard) { id -> openReading(id, StudyMode.INTENSIVE, null, null) }
                                    } ?: EmptyPanel("词汇队列暂不可用", "返回阅读后重试。", "返回") { route = "articles" }
                                    "cards" -> CardLibrary(cards, { openCard(it.id) }, { exportConfirm = true }, now = now)
                                    "card" -> visibleCard?.let { card ->
                                        val origin = navigationReturns.lastOrNull()?.snapshot
                                        val backLabel = when {
                                            origin?.readerId != null -> "← 返回阅读"
                                            origin?.route == "vocabulary" -> "← 返回词汇队列"
                                            origin?.route == "captures" -> "← 返回捕获列表"
                                            origin?.route == "review" -> "← 返回复习"
                                            else -> "← 所有卡片"
                                        }
                                        CardDetail(card, vm.busy != null, { updated, done -> vm.updateCard(updated, done) }, closeCard,
                                            onSetReviewPaused = { paused -> vm.setReviewPaused(card, paused) }, backLabel = backLabel,
                                            onOpenSource = cardSources[card.id]?.let { { openCardSource(card) } })
                                    } ?: EmptyPanel("卡片暂不可用", "返回后重试。", "返回", closeCard)
                                    "review" -> ReviewScreen(cards, vm.busy != null, vm::review, { route = "articles" },
                                        onPauseCard = { card -> vm.setReviewPaused(card, true) }, now = now,
                                        onOpenSource = openCardSource, canOpenSource = { cardSources[it.id] != null },
                                        progress = vm.reviewProgress, onOpenCard = openCard, onRetryHistory = vm::retryReviewHistory)
                                    "sources" -> SourceScreen(sources, vm.busy != null, vm::setSource, vm::sync)
                                    "settings" -> SettingsScreen(settings, vm.hasKey, vm.busy != null, jobs,
                                        vm::setSettings, vm::setKey, vm::clearKey,
                                        { exportEvents.launch("Lexicon-metrics-${LocalDate.now()}.json") },
                                        { exportConfirm = true }, dictionaryStatus = vm.dictionaryStatus,
                                        onImportDictionary = { importDictionary.launch(arrayOf("*/*")) },
                                        onImportApiKey = { importApiKey.launch(arrayOf("text/plain", "application/octet-stream")) },
                                        dictionaries = vm.installedDictionaries, onSelectDictionary = vm::selectDictionary)
                                }
                              }
                            }
                        }
                    }
                }
                if (readerId != null && captureId == null && vm.notice != null) {
                    Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).navigationBarsPadding()) {
                        StatusLine(vm.notice!!, vm::clearNotice)
                    }
                }
            }
            if (commands) CommandPalette(
                dueCount = dueCards.size, pendingCount = pending.size, vocabularyAvailable = vocabularyVm != null,
                onDismiss = { commands = false },
                onCommand = { command ->
                    commands = false
                    when (command) {
                        "export" -> exportConfirm = true
                        "capture" -> showCapture(Capture(UUID.randomUUID().toString(), target = "", selectedText = "", createdAt = now))
                        "theme" -> vm.setSettings(settings.copy(theme = if (settings.theme == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK))
                        "sync" -> vm.sync()
                        else -> openWorkspace(command)
                    }
                },
            )
            activeCapture?.let { cap ->
                CapturePanel(cap, articles, sources, vm, backgroundRepository = readingVm.backgroundRepository, captureBody = vm.body, assist = readingVm.result, assistBusy = readingVm.busy != null, assistError = readingVm.error, assistRequest = readingVm.resultRequest, noteBusy = readingVm.noteBusy,
                    onAssist = { request -> readingVm.clearResult(); readingVm.assist(request, articleId = cap.articleId) }, onNote = { note, done -> readingVm.saveNote(note) { done(); vm.notify("[saved] 笔记已保存") } },
                    onDismiss = closeCapture, onCardSaved = {
                    captureDraft = null
                    if (initialCaptureText != null && !externalWorkspace) onExternalCaptureDone?.invoke()
                    else vm.notify("[done] 生词卡已加入复习队列")
                }, onSettings = {
                    captureDraft = null
                    openWorkspace("settings"); externalWorkspace = true
                }, onOpenExistingCard = { card ->
                    captureDraft = null; readingVm.clearResult()
                    openCard(card.id)
                }, retained = captures.any { it.id == cap.id })
            }
            if (route == "knowledge" && readingVm.error != null) AlertDialog(onDismissRequest = readingVm::clearError,
                title = { Text("暂未完成") }, text = { Text(readingVm.error.orEmpty()) },
                confirmButton = { TextButton(onClick = readingVm::clearError) { Text("知道了") } })
            if (route == "speed_history" && correctionInspection != null) SpeedCorrectionInspectionDialog(
                requireNotNull(correctionInspection), onClose = { speedVm?.closeCorrectionInspection() })
            if (paceHistoryOpen && repository.personalization != null && speedRepository != null) {
                val historyVm: PersonalizationHistoryViewModel = viewModel(factory = remember(repository, speedRepository) {
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                            PersonalizationHistoryViewModel(requireNotNull(repository.personalization), speedRepository, extras.createSavedStateHandle()) as T
                    }
                })
                val historyState by historyVm.state.collectAsStateWithLifecycle()
                PersonalizationHistoryDialog(historyState, articles, historyVm::select, { paceHistoryOpen = false },
                    historyVm::retry, { run -> speedVm?.inspectRun(run) })
            }
            if ((paceHistoryOpen || route == "speed_history" || readerId != null && speedMode) && recapInspection != null) SpeedRecapInspectionDialog(
                requireNotNull(recapInspection), onClose = { speedVm?.closeRecapInspection() })
            if (exportConfirm) AlertDialog(
                onDismissRequest = { exportConfirm = false },
                title = { Text("导出 Anki 快照") },
                text = { Text("导出 ${cards.size} 张生词卡。\n\n这是单向内容快照，后续编辑和复习进度不自动同步。Anki 将自行调度导入的卡片。") },
                confirmButton = { TextButton(enabled = cards.isNotEmpty() && vm.busy == null, onClick = {
                    exportConfirm = false; exportAnki.launch("Lexicon-${LocalDate.now()}.apkg")
                }) { Text("选择保存位置") } },
                dismissButton = { TextButton(onClick = { exportConfirm = false }) { Text("取消") } },
            )
        }
    }
}

/** Bound the SAF copy itself, before the validated importer sees the temporary file. */
internal fun copyDictionaryInput(input: InputStream, output: OutputStream, maxBytes: Long = 256L * 1024 * 1024) {
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) throw IOException("Dictionary exceeds import size limit")
        output.write(buffer, 0, count)
    }
}

/** Local plain-text key import. Never echo rejected file contents into a UI error or log. */
internal fun readApiKeyInput(input: InputStream): String {
    val bytes = input.readNBytes(4097)
    require(bytes.size <= 4096) { "API key file is too large" }
    val key = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim()
    require(Regex("sk-[A-Za-z0-9_-]{16,200}").matches(key)) { "API key file format is invalid" }
    return key
}

@Composable
private fun WorkspaceHeader(onHome: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onHome, contentPadding = PaddingValues(0.dp)) {
                Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("L_", Modifier.padding(horizontal = 10.dp, vertical = 7.dp), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Text("Lexicon", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun CommandPalette(dueCount: Int, pendingCount: Int, vocabularyAvailable: Boolean, onDismiss: () -> Unit, onCommand: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    fun choose(command: String) {
        if (choosing) return
        choosing = true
        scope.launch { sheetState.hide(); onCommand(command) }
    }
    val actions = listOf(
        Triple("articles", "打开阅读列表", "explorer"), Triple("captures", "待处理捕获 · $pendingCount", "inbox"),
        Triple("capture", "新建捕获 / 粘贴文本", "capture"), Triple("review", "开始复习 · $dueCount 到期", "review"),
        Triple("vocabulary", "词汇队列", "vocabulary queue"),
        Triple("cards", "浏览生词卡", "cards"), Triple("export", "导出全部卡片到 Anki", "export .apkg"),
        Triple("knowledge", "笔记与表达回顾", "notes knowledge notion"), Triple("progress", "阅读成长", "progress stats"), Triple("speed_history", "意群速读记录", "speed chunks training"),
        Triple("baseline_history", "无辅助自测记录", "baseline reading test"),
        Triple("ielts", "雅思练习", "IELTS reading writing harness"),
        Triple("sources", "管理内容源", "sources"), Triple("sync", "立即同步", "sync"),
        Triple("theme", "切换日间 / 夜间主题", "theme"), Triple("settings", "API Key 与阅读设置", "settings"),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp)) {

            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("搜索命令…") })
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(actions.filter { (it.first != "vocabulary" || vocabularyAvailable) && (query.isBlank() || it.second.contains(query, true) || it.third.contains(query, true)) }, key = { it.first }) { action ->
                    TextButton(enabled = !choosing, onClick = { choose(action.first) }, modifier = Modifier.fillMaxWidth().animateItem(),
                        contentPadding = PaddingValues(vertical = 14.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(action.second, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
