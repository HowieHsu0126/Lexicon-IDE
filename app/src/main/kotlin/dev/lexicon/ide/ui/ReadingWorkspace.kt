@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lexicon.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Presentation-only controls. Original text and AI variants never share a capture provenance. */
internal data class ReaderDisplay(
    val mode: StudyMode = StudyMode.INTENSIVE, val quiet: Boolean = false,
    val highlights: List<StudyHighlight> = emptyList(), val emphasized: Set<Int> = emptySet(),
    val jumpParagraph: Int? = null, val jumpVersion: Int = 0, val aiLevel: String? = null,
    val jumpBodyHash: String? = null,
    val jumpStart: Int? = null, val jumpEnd: Int? = null,
    val checkpointKey: String? = null, val restoreToken: String? = null,
    val restoreParagraph: Int? = null, val restoreWaiting: Boolean = false,
)

private data class SkimDirectoryRequest(val body: ArticleBody?, val plan: SkimPlan, val study: ArticleStudy?, val original: Boolean)

@Composable
internal fun ReadingWorkspace(
    article: Article, body: ArticleBody?, loading: Boolean, settings: AppSettings, initialMode: StudyMode,
    aiAllowed: Boolean, captureVisible: Boolean, cards: List<Card>, vm: ReadingViewModel,
    onBack: () -> Unit, onCapture: (Capture) -> Unit, onRetry: () -> Unit, onOpenPage: () -> Unit,
    onSettings: (AppSettings) -> Unit, onEvent: (String, Int) -> Unit, entryToken: Int = 0, onSpeed: ((Int) -> Unit)? = null,
    initialBudgetMinutes: Int? = null, initialAnchor: ReaderAnchor? = null,
    vocabularyVm: VocabularyLearningViewModel? = null, captures: List<Capture> = emptyList(), onVocabularyCard: ((String) -> Unit)? = null,
    entryKind: ReadingEntryKind = ReadingEntryKind.START,
    onIelts: (() -> Unit)? = null,
) {
    var lastBodyHash by rememberSaveable(article.id) { mutableStateOf(body?.contentHash) }
    val bodyIdentity = body?.contentHash ?: lastBodyHash
    LaunchedEffect(body?.contentHash) { body?.let { lastBodyHash = it.contentHash } }
    var mode by rememberSaveable(article.id) { mutableStateOf(initialMode) }
    var appliedEntry by rememberSaveable(article.id) { mutableIntStateOf(entryToken) }
    var quiet by rememberSaveable(article.id) { mutableStateOf(false) }
    var tools by rememberSaveable(article.id) { mutableStateOf(false) }
    var appearance by rememberSaveable(article.id) { mutableStateOf(false) }
    var tab by rememberSaveable(article.id) { mutableStateOf("导图") }
    var minutes by rememberSaveable(article.id) { mutableIntStateOf(if (initialBudgetMinutes == 10) 10 else 5) }
    var roleFilter by rememberSaveable(article.id) { mutableStateOf("全部") }
    var questionIds by rememberSaveable(article.id) { mutableStateOf(emptyList<String>()) }
    var questionBodyHash by rememberSaveable(article.id) { mutableStateOf(body?.contentHash) }
    var questionStudyIdentity by rememberSaveable(article.id) { mutableStateOf<String?>(null) }
    var selectedStudyScope by rememberSaveable(article.id, bodyIdentity) { mutableStateOf<String?>(null) }
    var studySelectionRestored by remember(vm, article.id, bodyIdentity) { mutableStateOf(false) }
    var planningWpm by rememberSaveable(article.id) { mutableIntStateOf(settings.reader.bounded().skimPlanningWpm) }
    var focused by rememberSaveable(article.id) { mutableIntStateOf(0) }
    var jump by rememberSaveable(article.id) { mutableStateOf<Int?>(null) }
    var jumpVersion by rememberSaveable(article.id) { mutableIntStateOf(0) }
    var jumpBodyHash by rememberSaveable(article.id) { mutableStateOf<String?>(null) }
    var jumpStart by rememberSaveable(article.id) { mutableStateOf<Int?>(null) }
    var jumpEnd by rememberSaveable(article.id) { mutableStateOf<Int?>(null) }
    var previewStart by rememberSaveable(article.id, body?.contentHash) { mutableStateOf<Int?>(null) }
    var previewWordStart by rememberSaveable(article.id, body?.contentHash) { mutableIntStateOf(0) }
    var previewWordEnd by rememberSaveable(article.id, body?.contentHash) { mutableIntStateOf(0) }
    var appliedAnchorEntry by rememberSaveable(article.id) { mutableStateOf<Int?>(null) }
    var anchorNotice by remember(article.id) { mutableStateOf<String?>(null) }
    var level by rememberSaveable(article.id) { mutableStateOf("原文") }
    var variant by remember(article.id, bodyIdentity) { mutableStateOf<StudyAssist?>(null) }
    var completed by rememberSaveable(article.id, bodyIdentity) { mutableStateOf(false) }
    val resumeVm = readingResumeViewModel(vm.resumeRepository, article.id)
    val resumeKind = if (initialAnchor != null) ReadingEntryKind.ANCHOR else entryKind
    val resumeEntryKey = "${article.id}:$entryToken"
    val resumeKey = "$resumeEntryKey:${bodyIdentity.orEmpty()}"
    var resumeAppliedKey by rememberSaveable(article.id) { mutableStateOf<String?>(null) }
    var controlsAppliedEntry by rememberSaveable(article.id) { mutableStateOf<String?>(null) }
    var resumePositionedKey by rememberSaveable(article.id) { mutableStateOf<String?>(null) }
    var resumeViewport by remember(resumeKey) { mutableStateOf<Int?>(null) }
    var resumeNotice by remember(resumeKey) { mutableStateOf<String?>(null) }
    val entryDefaults = ReadingResumeControls(if (resumeKind == ReadingEntryKind.ANCHOR) StudyMode.INTENSIVE else initialMode,
        budgetMinutes = if (initialBudgetMinutes == 10) 10 else 5,
        toolTab = if (resumeKind != ReadingEntryKind.ANCHOR && initialMode == StudyMode.SKIM) "略读" else "导图")
    LaunchedEffect(resumeVm, resumeKey, body?.contentHash) {
        if (body != null) resumeVm?.bind(resumeKey, body, resumeKind, resumePositionedKey == resumeKey, entryDefaults)
    }
    val resumeResolution = if (resumeVm != null) resumeVm.resolution?.takeIf { it.key == resumeKey }
        else body?.let { ReadingResumeResolution(resumeKey,
            if (resumePositionedKey == resumeKey) null else if (resumeKind == ReadingEntryKind.CONTINUE) ReadingResumeControls() else entryDefaults,
            position = resumePositionedKey != resumeKey) }
    LaunchedEffect(resumeResolution, resumeKey) {
        val resolution = resumeResolution ?: return@LaunchedEffect
        if (resumeAppliedKey != resumeKey) {
            // A refreshed body needs a new position/lease, not a new set of explicit reader controls.
            if (controlsAppliedEntry != resumeEntryKey) {
                resolution.controls?.let { controls ->
                    mode = controls.mode; quiet = controls.quiet; minutes = controls.budgetMinutes
                    roleFilter = controls.roleFilter; tab = controls.toolTab
                    tools = false; appearance = false
                }
                controlsAppliedEntry = resumeEntryKey
            }
            resumeNotice = resolution.notice
            resumeAppliedKey = resumeKey
        }
    }
    LaunchedEffect(resumeVm?.error) { resumeVm?.error?.let { resumeNotice = it } }
    LaunchedEffect(resumeNotice) { if (resumeNotice != null) { delay(5_000); resumeNotice = null } }
    val resumeWaiting = body != null && (resumeResolution == null || resumeAppliedKey != resumeKey)
    LaunchedEffect(entryToken) {
        if (appliedEntry != entryToken) {
            mode = if (resumeKind == ReadingEntryKind.CONTINUE) StudyMode.INTENSIVE else initialMode; completed = false; quiet = false
            tools = false; appearance = false; roleFilter = "全部"; questionIds = emptyList(); previewStart = null
            initialBudgetMinutes?.let { minutes = if (it == 10) 10 else 5 }
            level = "原文"; variant = null; jump = null; jumpBodyHash = null; jumpStart = null; jumpEnd = null
            anchorNotice = null; appliedEntry = entryToken
        }
    }
    LaunchedEffect(entryToken, initialAnchor, body?.contentHash) {
        if (initialAnchor != null && body != null && appliedAnchorEntry != entryToken) {
            appliedAnchorEntry = entryToken
            mode = StudyMode.INTENSIVE; level = "原文"; variant = null
            quiet = false; tools = false; appearance = false; completed = false
            roleFilter = "全部"; questionIds = emptyList()
            jump = initialAnchor.paragraphIndex(body)
            jumpStart = initialAnchor.start.takeIf { jump != null }; jumpEnd = initialAnchor.end.takeIf { jump != null }
            jumpBodyHash = body.contentHash; jumpVersion++
            if (jump == null) anchorNotice = "未能定位原摘录，已打开当前正文"
        }
    }
    LaunchedEffect(anchorNotice) {
        if (anchorNotice != null) { delay(5_000); anchorNotice = null }
    }
    val study = vm.study?.takeIf { it.articleId == article.id && it.bodyHash == body?.contentHash }
    val backgroundVm = backgroundResearchViewModel(vm.backgroundRepository, "reader:${article.id}")
    val studyIdentity = remember(study) { study?.let(ReadingStudyScopes::identity) }
    fun scopeKey(value: ArticleStudy) = value.scope?.let { "${it.firstParagraph}:${it.endParagraphExclusive}" } ?: "full"
    LaunchedEffect(vm, body?.contentHash, vm.loadingStudies, vm.availableStudies) {
        if (!studySelectionRestored && body != null && !vm.loadingStudies &&
            vm.currentArticle == "${article.id}:${body.contentHash}") {
            selectedStudyScope?.let { selected -> vm.availableStudies.firstOrNull { scopeKey(it) == selected }?.let(vm::chooseStudy) }
            studySelectionRestored = true
        }
    }
    LaunchedEffect(study, studySelectionRestored) {
        if (studySelectionRestored && study != null) selectedStudyScope = scopeKey(study)
    }
    LaunchedEffect(body?.contentHash) {
        body?.let {
            if (questionBodyHash != null && questionBodyHash != it.contentHash) {
                questionIds = emptyList(); roleFilter = "全部"; previewStart = null
                jumpStart = null; jumpEnd = null
            }
            questionBodyHash = it.contentHash
        }
    }
    LaunchedEffect(study) {
        if (study != null) {
            val identity = studyIdentity
            if (questionStudyIdentity != null && questionStudyIdentity != identity) {
                questionIds = emptyList(); previewStart = null
            }
            questionStudyIdentity = identity
            questionIds = questionIds.filter { id -> study.questions.count { it.id == id } == 1 }
        }
    }
    LaunchedEffect(settings.reader.skimPlanningWpm) { planningWpm = settings.reader.bounded().skimPlanningWpm }
    LaunchedEffect(article.id, body?.contentHash, settings.reader.automaticStudy, aiAllowed) {
        body?.let { vm.prepare(article, it, settings.reader.automaticStudy && resumeKind != ReadingEntryKind.CONTINUE, aiAllowed) }
    }
    val fullStudySupported = remember(body?.contentHash) { body?.let(ReadingStudyScopes::supportsFull) == true }
    fun rewriteRequest(target: String) = StudyRequest("rewrite", body!!.text, targetLevel = target, title = article.title, source = article.sourceName, aiAllowed = aiAllowed)
    LaunchedEffect(body?.contentHash, level) {
        if (body != null && level != "原文" && variant == null) {
            variant = vm.cachedAssist(rewriteRequest(level))
            if (variant == null) { level = "原文"; completed = false }
        }
    }
    val rendered = remember(body, variant, level) {
        if (body == null || level == "原文") body
        else if (variant == null) null
        else body.copy(text = variant!!.text, contentHash = fingerprint(variant!!.text), license = "AI 改写 · $level")
    }
    var lastRenderedHash by rememberSaveable(article.id, entryToken, bodyIdentity, level) { mutableStateOf<String?>(null) }
    val renderedIdentity = rendered?.contentHash ?: lastRenderedHash ?: bodyIdentity.orEmpty()
    SideEffect { rendered?.contentHash?.let { if (lastRenderedHash != it) lastRenderedHash = it } }
    val paragraphs = remember(rendered?.contentHash) { ParagraphFocus.paragraphs(rendered?.text.orEmpty()) }
    var includeDisclosures by rememberSaveable(article.id, entryToken, bodyIdentity, level) { mutableStateOf(false) }
    val skimOriginal = remember(article, body, level) { body?.takeIf { level == "原文" }?.let { SkimOriginal(article, it) } }
    val skimPlan = remember(rendered?.contentHash, minutes, study, roleFilter, questionIds, level, planningWpm, skimOriginal, includeDisclosures) {
        val analysis = study.takeIf { level == "原文" }
        val roles = when (roleFilter) { "论点" -> setOf("claim", "analysis"); "证据" -> setOf("evidence", "example"); "背景" -> setOf("background", "introduction"); "结论" -> setOf("conclusion"); else -> emptySet() }
        ReadingSkimPlanner.plan(rendered?.text.orEmpty(), if (minutes == 10) 10 else 5, analysis, roles = roles,
            selectedQuestions = questionIds.mapNotNull { id -> analysis?.questions?.singleOrNull { it.id == id } }, planningWpm = planningWpm,
            original = skimOriginal, includeDisclosures = includeDisclosures)
    }
    val important = remember(skimPlan) { skimPlan.paragraphIndices.toSet() }
    val skimHighlights = remember(skimPlan, rendered?.contentHash) { skimPlan.sentenceRanges.mapIndexed { i, range ->
        StudyHighlight("skim-$i", rendered!!.text.substring(range.start, range.endExclusive), range.start, range.endExclusive, "argument",
            if (range.questionIds.isEmpty()) "略读选句" else "问题证据 · ${range.questionIds.size}")
    } }
    val vocabularyState by (vocabularyVm?.state ?: kotlinx.coroutines.flow.flowOf(VocabularyLearningUiState(loading = false)))
        .collectAsStateWithLifecycle(initialValue = VocabularyLearningUiState(loading = vocabularyVm != null))
    val vocabularyCandidates = remember(article, body, study, aiAllowed) {
        if (body == null || study == null) emptyList()
        else runCatching { VocabularyLearning.candidates(article, body, study, aiAllowed) }.getOrDefault(emptyList())
    }
    val highlights = remember(study, vocabularyState.library.familiar, quiet, level, mode, skimHighlights) {
        if (quiet) emptyList() else if (mode == StudyMode.SKIM) skimHighlights
        else if (level != "原文") emptyList() else study?.highlights.orEmpty().filterNot {
            it.kind in setOf("vocabulary", "collocation") && VocabularyLearning.isFamiliar(it.quote, vocabularyState.library.familiar)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    var sessionId by rememberSaveable(article.id, mode, level, appliedEntry, bodyIdentity) { mutableStateOf(UUID.randomUUID().toString()) }
    val started = rememberSaveable(sessionId) { System.currentTimeMillis() }
    val tracker = rememberSaveable(sessionId, saver = ReadingSessionTracker.Saver) {
        ReadingSessionTracker(started, vm.assistanceCount(article.id))
    }
    LaunchedEffect(sessionId, vm.assistanceCount(article.id)) {
        tracker.observeAssistanceCount(vm.assistanceCount(article.id))
    }
    val paragraphWords = remember(rendered?.contentHash) { paragraphs.map { wordCount(rendered!!.text.substring(it.start, it.endExclusive)) } }
    // A transient loading frame keeps this session's own word counts. A new source hash gets a
    // fresh tracker above, so old dwell cannot be applied to a different text.
    val retainedWords = remember(sessionId) { arrayOf(paragraphWords) }
    if (rendered != null) SideEffect { retainedWords[0] = paragraphWords }
    // Counters stay outside snapshot state. Opening tools reads one snapshot while timing is paused.
    val canComplete = tools && tracker.canComplete(paragraphWords, mode, important)
    val session = {
        tracker.observeAssistanceCount(vm.assistanceCount(article.id))
        tracker.snapshot(sessionId, article.id, mode, article.topic, retainedWords[0], completed, level)
    }
    val currentSession by rememberUpdatedState(session)
    val closingSession = remember(sessionId) { arrayOf(session) }
    SideEffect { closingSession[0] = session }
    DisposableEffect(sessionId) { onDispose { closingSession[0]().takeIf { it.activeMillis >= 1000 }?.let(vm::record) } }
    val currentFocus by rememberUpdatedState(focused)
    val resumeTimingReady = !resumeWaiting && (level != "原文" || resumePositionedKey == resumeKey)
    LaunchedEffect(sessionId, foreground, tools, appearance, captureVisible, previewStart, completed, rendered?.contentHash, resumeTimingReady) {
        if (foreground && !tools && !appearance && !captureVisible && previewStart == null && !completed && rendered != null && resumeTimingReady) while (true) {
            delay(1000)
            tracker.recordSecond(currentFocus, paragraphs.size)
        }
    }
    LaunchedEffect(sessionId, foreground) {
        if (!foreground && currentSession().activeMillis >= 1000) vm.record(currentSession())
        else while (true) { delay(15_000); if (currentSession().activeMillis >= 1000) vm.record(currentSession()) }
    }
    val resumeControls = ReadingResumeControls(mode, quiet, if (minutes == 10) 10 else 5, roleFilter, tab)
    LaunchedEffect(resumeVm, resumeKey, body?.contentHash, level, resumeViewport, resumeControls, completed, resumeWaiting) {
        if (body != null && level == "原文" && !resumeWaiting) resumeVm?.observe(resumeKey, body.contentHash, resumeViewport, resumeControls, completed)
        else resumeVm?.pause(resumeKey)
    }
    DisposableEffect(resumeVm, resumeKey, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) resumeVm?.flush(resumeKey) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); resumeVm?.flush(resumeKey) }
    }
    val context = LocalContext.current
    var speaker by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    var speechRequested by remember { mutableStateOf(false) }
    var pendingSpeech by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf(false) }
    var speechRate by rememberSaveable { mutableFloatStateOf(1.5f) }
    var speechError by remember { mutableStateOf<String?>(null) }
    // Bind to the system speech service only after the reader requests audio.
    DisposableEffect(speechRequested) {
        if (!speechRequested) return@DisposableEffect onDispose { }
        val tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (!ttsReady) { pendingSpeech = false; speechError = "朗读暂不可用，请检查系统语音资源" }
        }
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { if (id == "reading-last") android.os.Handler(android.os.Looper.getMainLooper()).post { speaking = false } }
            @Deprecated("TTS legacy error callback")
            override fun onError(id: String?) { android.os.Handler(android.os.Looper.getMainLooper()).post { speaking = false; speechError = "朗读未完成，请检查系统语音资源" } }
        })
        speaker = tts
        onDispose { tts.stop(); tts.shutdown(); speaker = null }
    }
    LaunchedEffect(foreground) { if (!foreground) { speaker?.stop(); speaking = false; pendingSpeech = false } }
    LaunchedEffect(ttsReady, pendingSpeech) {
        if (ttsReady && pendingSpeech && foreground && rendered != null) {
            pendingSpeech = false; speechError = null
            if ((speaker?.setLanguage(Locale.US) ?: TextToSpeech.LANG_NOT_SUPPORTED) < 0) {
                speechError = "请先安装系统英语语音资源"
            } else {
                speaker?.setSpeechRate(speechRate)
                val chunks = skimPlan.sentenceRanges.flatMap { range -> rendered.text.substring(range.start, range.endExclusive).chunked(3000) }
                chunks.forEachIndexed { index, chunk -> speaker?.speak(chunk, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (index == chunks.lastIndex) "reading-last" else "reading-$index") }
                speaking = chunks.isNotEmpty()
            }
        }
    }
    fun jumpTo(index: Int) {
        if (level != "原文") { vm.record(currentSession()); level = "原文"; variant = null; completed = false }
        jump = index; jumpBodyHash = body?.contentHash; jumpStart = null; jumpEnd = null; jumpVersion++; tools = false
    }
    fun jumpToRendered(index: Int) {
        jump = index; jumpBodyHash = rendered?.contentHash; jumpStart = null; jumpEnd = null; jumpVersion++; tools = false
    }
    fun jumpToSentence(range: SkimSentenceRange) {
        if (level != "原文" || body == null) return
        if (mode != StudyMode.SKIM) { vm.record(currentSession()); mode = StudyMode.SKIM; completed = false }
        quiet = false
        jump = range.paragraphIndex; jumpBodyHash = body.contentHash; jumpStart = range.start; jumpEnd = range.endExclusive
        jumpVersion++; tools = false
    }
    fun request(request: StudyRequest, result: (StudyAssist) -> Unit = {}) { vm.assist(request, articleId = article.id, onResult = result) }
    val restoreToken = resumeKey.takeIf { level == "原文" && resumeKind != ReadingEntryKind.ANCHOR &&
        resumeResolution?.position == true && resumePositionedKey != resumeKey }
    val display = remember(mode, quiet, highlights, important, jump, jumpVersion, level, jumpBodyHash, jumpStart, jumpEnd,
        resumeKey, restoreToken, resumeResolution?.paragraphIndex, resumeWaiting) {
        ReaderDisplay(mode, quiet, highlights, important, jump, jumpVersion, level.takeUnless { it == "原文" }, jumpBodyHash, jumpStart, jumpEnd,
            resumeKey, restoreToken, resumeResolution?.paragraphIndex, resumeWaiting)
    }
    Box(Modifier.fillMaxSize()) {
        ZenReader(article, rendered, loading || (body != null && level != "原文" && variant == null), settings, aiAllowed, captureVisible || previewStart != null, onBack, onCapture, onRetry, onOpenPage,
            onToggleSerif = { appearance = true }, onEvent = onEvent, display = display,
            onTools = { tools = true }, onMode = {
                vm.record(session()); mode = if (mode == StudyMode.INTENSIVE) StudyMode.SKIM else StudyMode.INTENSIVE
                completed = false
            }, onFocusedParagraph = { focused = it }, onRestoreComplete = { key -> if (key == resumeKey) resumePositionedKey = key },
            onViewportPosition = { position ->
                if (position.entryKey == resumeKey && position.bodyHash == body?.contentHash && level == "原文") {
                    resumeViewport = position.paragraphIndex
                    if (position.paragraphIndex != null && !resumeWaiting) resumePositionedKey = resumeKey
                }
            }, onSkimWordTap = { start, end ->
                val hit = skimPlan.sentenceRanges.firstOrNull { start >= it.start && end <= it.endExclusive && it.questionIds.isNotEmpty() }
                if (level == "原文" && hit != null) {
                    previewStart = hit.start; previewWordStart = start; previewWordEnd = end; true
                } else false
            })
        anchorNotice?.let { message ->
            Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).testTag("reader_anchor_notice"),
                shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(message, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        resumeNotice?.let { message ->
            Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).testTag("reader_resume_notice"),
                shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(message, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (appearance) ReaderAppearanceSheet(settings.reader, { onSettings(settings.copy(reader = it.bounded())) }, { appearance = false })
    val preview = skimPlan.sentenceRanges.firstOrNull { it.start == previewStart && it.questionIds.isNotEmpty() }?.takeIf { level == "原文" }
    LaunchedEffect(preview, previewStart) { if (previewStart != null && preview == null) previewStart = null }
    if (preview != null && rendered != null) ModalBottomSheet(onDismissRequest = { previewStart = null }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("这句话对应的问题", style = MaterialTheme.typography.titleMedium)
            preview.questionIds.mapNotNull { id -> study?.questions?.singleOrNull { it.id == id } }.forEach {
                Text(it.question, Modifier.testTag("skim_preview_question_${it.id}"), style = MaterialTheme.typography.bodyLarge)
            }
            Text(rendered.text.substring(preview.start, preview.endExclusive), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = ReadingSerif))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = {
                    previewStart = null
                    if (previewWordStart >= preview.start && previewWordEnd > previewWordStart && previewWordEnd <= preview.endExclusive)
                        onCapture(captureAt(article, rendered, previewWordStart, previewWordEnd, aiAllowed, false))
                }, modifier = Modifier.testTag("skim_preview_lookup")) { Text("查词 / 捕获") }
                TextButton(onClick = { previewStart = null }, modifier = Modifier.testTag("skim_preview_close")) { Text("继续阅读") }
            }
        }
    }
    val toolsIdentity = "${article.id}:$entryToken:${bodyIdentity.orEmpty()}:$level:$renderedIdentity"
    val scopeDraft = rememberReadingStudyScopeState("${article.id}:$entryToken:${bodyIdentity.orEmpty()}")
    val directoryRequest = remember(rendered, skimPlan, study, level) { SkimDirectoryRequest(rendered, skimPlan, study, level == "原文") }
    val directoryProjection by produceState<Pair<SkimDirectoryRequest, SkimDirectory?>?>(null, directoryRequest) {
        val request = directoryRequest
        value = null
        val projected = withContext(Dispatchers.Default) {
            request.body?.let { runCatching { SkimNavigation.build(it, request.plan, request.study, request.original) }.getOrNull() }
        }
        value = request to projected
    }
    val skimDirectory = directoryProjection?.takeIf { it.first == directoryRequest }?.second
    ReadingToolsSheet(visible = tools, identity = toolsIdentity, mode = mode, tab = tab, quiet = quiet, completed = completed, canComplete = canComplete,
        contentReady = body != null && !vm.loadingStudies && (tab != "略读" || directoryProjection?.first == directoryRequest),
        onTab = { tab = it; vm.clearResult() }, onClose = { tools = false }, onAppearance = { tools = false; appearance = true },
        onPrimary = {
            if (tab == "略读" && mode != StudyMode.SKIM) { vm.record(session()); mode = StudyMode.SKIM; completed = false }
            tools = false
        }, onQuiet = { quiet = !quiet; tools = false }, onComplete = {
            completed = true; vm.record(session().copy(completed = true)); if (level == "原文") resumeVm?.complete(resumeKey); tools = false
        }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onSpeed != null) TextButton(onClick = { tools = false; onSpeed(focused) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("意群速读 →") }
                if (onIelts != null) TextButton(onClick = { tools = false; onIelts() }, enabled = body != null && aiAllowed,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("reader_ielts")) { Text("雅思练习 →") }
            }
            if (tab != "背景" && vm.busy != null) Text(vm.busy!! + "…", Modifier.testTag("reader_assist_busy"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (tab != "背景") vm.error?.let { Text(it, Modifier.testTag("reader_assist_error"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            body?.takeIf { tab != "背景" }?.let { original ->
                ReadingStudyScopeControls(original, focused, study, vm.availableStudies, vm.loadingStudies, vm.busy != null, aiAllowed,
                    onChoose = { selectedStudyScope = scopeKey(it); vm.chooseStudy(it) },
                    onAnalyze = { scope -> vm.generateRange(article, original, scope, aiAllowed, retry = true) },
                    onAnalyzeFull = { vm.generate(article, original, aiAllowed, retry = false) }, state = scopeDraft)
            }
            if (study == null && tab != "背景" && fullStudySupported) {
                if (aiAllowed) OutlinedButton(onClick = { body?.let { vm.generate(article, it, aiAllowed) } }, enabled = body != null && vm.busy == null,
                    modifier = Modifier.testTag("reader_generate_study")) { Text("生成阅读辅助") }
                else Text("此来源未开启 AI。可使用原文略读、查词和笔记。", style = MaterialTheme.typography.bodySmall)
            }
            when (tab) {
                "导图" -> {
                    study?.let { Text(it.summary, style = MaterialTheme.typography.bodyMedium); MindMapView(it.nodes, ::jumpTo) }
                    if (study == null) Text("生成后可点导图节点回到对应段落。", style = MaterialTheme.typography.bodySmall)
                }
                "重点" -> {
                    if (level != "原文") Text("以下重点与原句来自原文。", style = MaterialTheme.typography.bodySmall)
                    VocabularyHighlightsPanel(article, body, study, vocabularyCandidates, cards, captures, vocabularyVm, vocabularyState, onOpenCard = onVocabularyCard) { capture ->
                        tools = false; onCapture(capture)
                    }
                    val otherHighlights = study?.highlights.orEmpty().filter { it.kind !in setOf("vocabulary", "collocation") }
                    if (otherHighlights.isNotEmpty()) Text("句式与论点", style = MaterialTheme.typography.labelMedium)
                    otherHighlights.forEach { item ->
                        TextButton(onClick = {
                            val original = body
                            if (original != null && item.start >= 0 && item.end <= original.text.length && original.text.substring(item.start, item.end) == item.quote) {
                                tools = false; onCapture(captureAt(article, original, item.start, item.end, aiAllowed, false))
                            }
                        }) { Column(Modifier.fillMaxWidth()) { Text(item.quote); Text(item.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    }
                }
                "略读" -> {
                    SkimReadingControls(skimPlan, study, questionIds, roleFilter, level == "原文", vm.busy != null,
                        aiAllowed && body != null, { minutes = it }, { pace ->
                            planningWpm = pace
                            onSettings(settings.copy(reader = settings.reader.copy(skimPlanningWpm = pace).bounded()))
                        }, { roleFilter = it }, { id ->
                            questionIds = if (id in questionIds) questionIds - id else questionIds + id
                        }, { body?.let { original ->
                            val scope = study?.scope
                            if (scope == null) vm.generate(article, original, aiAllowed)
                            else vm.generateRange(article, original, scope, aiAllowed, retry = true)
                        } }, ::jumpToSentence, onDisclosure = ::jumpToRendered, onIncludeDisclosures = { includeDisclosures = it }, afterFilters = {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1.5f, 2f).forEach { value -> FilterChip(speechRate == value, { speechRate = value }, label = { Text("${value}×") }) }
                        TextButton(onClick = {
                            if (speaking || pendingSpeech) { speaker?.stop(); speaking = false; pendingSpeech = false }
                            else { speechRequested = true; pendingSpeech = true }
                        }, enabled = important.isNotEmpty()) { Text(if (speaking) "停止朗读" else if (pendingSpeech) "准备朗读…" else "朗读重点") }
                    }
                    })
                    SkimParagraphNavigator(rendered, skimDirectory, focused, ::jumpToRendered)
                }
                "背景" -> {
                    fun query(term: String, paragraph: Int) = BackgroundQuery(term, backgroundParagraphContext(body, paragraph, term),
                        article.id, body?.contentHash, SourceRef(article.title, article.sourceName, article.url, article.publishedAt), aiAllowed)
                    if (backgroundVm != null) BackgroundResearchPanel(backgroundVm, "reader:${article.id}:${body?.contentHash}", query("", focused),
                        onClose = { tools = false }, suggestions = study?.backgrounds.orEmpty().map { BackgroundSeed(it.term, query(it.term, it.paragraph)) })
                    else Text("背景资料暂不可用，原文可继续阅读。", style = MaterialTheme.typography.bodySmall)
                }
                "检测" -> ReadingComprehensionQuestions(study, aiAllowed && vm.busy == null) { question, answer ->
                    vm.answer(article, question, answer, aiAllowed)
                }
                "分级" -> {
                    Text("AI 改写用于逐步进入原文，CEFR 为估计。", style = MaterialTheme.typography.bodySmall)
                    Text("当前：$level${study?.cefrEstimate?.let { " · ${if (study.scope == null) "原文" else "所选段落"}估计 $it" }.orEmpty()}",
                        modifier = Modifier.testTag("study_cefr_scope"))
                    if (body != null && !fullStudySupported) Text("本文超过整篇改写范围。可在原文划选句子使用译文或句法。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("study_rewrite_limit"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { vm.record(session()); level = "原文"; variant = null; completed = false; tools = false }) { Text("原文") }
                        listOf("B1", "B2").forEach { target -> OutlinedButton(enabled = aiAllowed && body != null && fullStudySupported && vm.busy == null, onClick = {
                            request(rewriteRequest(target)) {
                                vm.record(currentSession()); variant = it; level = target; completed = false; tools = false
                            }
                        }) { Text(target) } }
                    }
                }
                "对照" -> {
                    var title by rememberSaveable { mutableStateOf("") }; var source by rememberSaveable { mutableStateOf("") }
                    Text("对照真实标题，辨识措辞、强调点和遗漏。", style = MaterialTheme.typography.bodySmall)
                    Text(article.title, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(title, { title = it.take(800) }, Modifier.fillMaxWidth(), label = { Text("另一家媒体的真实标题") })
                    OutlinedTextField(source, { source = it.take(500) }, Modifier.fillMaxWidth(), label = { Text("媒体名 / 原文链接") })
                    Button(enabled = title.isNotBlank() && source.isNotBlank() && vm.busy == null && aiAllowed, onClick = {
                        request(StudyRequest("compare", article.title, context = title, source = "${article.sourceName} / $source", aiAllowed = aiAllowed))
                    }) { Text("分析标题视角") }
                }
            }
            speechError?.takeIf { tab == "略读" }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            vm.result?.takeUnless { it.kind == "rewrite" || it.kind == "background" || tab == "背景" }?.let { value ->
                HorizontalDivider()
                StudyAssistContent(value, retry = vm.resultRequest?.let { previous -> { request(previous) } })
                value.score?.let { Text("本次 AI 理解评估：${(it * 100).toInt()}%", style = MaterialTheme.typography.labelSmall) }
            }
    }
}

internal fun captureAt(article: Article, body: ArticleBody, start: Int, end: Int, allowed: Boolean, aiText: Boolean): Capture {
    val snapshot = TextCapture.extract(body.text, start, end)
    val target = snapshot.selectedText.trim().takeIf { it.length <= CaptureLimits.TARGET }.orEmpty()
    val id = if (aiText) UUID.randomUUID().toString() else VocabularyLearning.captureId(article.id, body.contentHash, snapshot.start, snapshot.end, target)
    return Capture(id, article.id, target,
        snapshot.selectedText, snapshot.sentence, if (aiText) ContextOrigin.AI_GENERATED else ContextOrigin.ORIGINAL,
        SourceRef(article.title, article.sourceName, article.url, article.publishedAt), snapshot.start, snapshot.end, body.contentHash,
        System.currentTimeMillis(), aiAllowed = allowed)
}

internal fun wordCount(text: String) = Regex("[\\p{L}]+(?:['’-][\\p{L}]+)*").findAll(text).count()
internal fun fingerprint(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/** UI vocabulary is mapped to domain paragraph roles; the planner owns selection and budgets. */
internal fun paragraphRoleLabel(role: String) = when (role) {
    "introduction" -> "引言"; "background" -> "背景"; "claim" -> "论点"; "evidence" -> "证据"
    "counterargument" -> "反驳"; "analysis" -> "分析"; "conclusion" -> "结论"; "example" -> "例证"
    "transition" -> "过渡"; else -> "其他"
}
