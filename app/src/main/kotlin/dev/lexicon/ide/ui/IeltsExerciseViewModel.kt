package dev.lexicon.ide.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lexicon.domain.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class IeltsExerciseUiState(
    val connection: IeltsGatewayConnection = IeltsGatewayConnection(null, false, null),
    val preparedArticleId: String? = null,
    val preparedTitle: String? = null,
    val preparedParagraphCount: Int = 0,
    val canGenerate: Boolean = false,
    val canRegenerate: Boolean = false,
    val generation: IeltsGenerationHistoryItem? = null,
    val generationHistory: List<IeltsGenerationHistoryItem> = emptyList(),
    val practiceHistory: List<IeltsPracticeHistoryItem> = emptyList(),
    val candidate: IeltsCandidateView? = null,
    val draftResponses: List<IeltsResponse> = emptyList(),
    val writingText: String = "",
    val writingTask1Text: String? = null,
    val answerView: IeltsAnswerView? = null,
    val busy: Boolean = false,
    val isSaving: Boolean = false,
    val savingDraft: Boolean = false,
    val dirty: Boolean = false,
    val error: String? = null,
    val historyError: String? = null,
    val pairingPreview: IeltsPairingPreview? = null,
    val study: IeltsStudySnapshot? = null,
    val studyRequest: IeltsStudyRequest? = null,
    val studyError: String? = null,
    val studyBusy: Boolean = false,
)

/** One screen coordinator. SavedStateHandle contains identities only; Room owns all prose. */
class IeltsExerciseViewModel(
    private val generations: IeltsGenerationRepository,
    private val practices: IeltsPracticeRepository,
    private val savedState: SavedStateHandle,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { "ielts-${UUID.randomUUID()}" },
    private val validationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val studyRepository: IeltsStudyRepository? = null,
) : ViewModel() {
    private val mutable = MutableStateFlow(IeltsExerciseUiState())
    val state: StateFlow<IeltsExerciseUiState> = mutable.asStateFlow()
    private var prepared: IeltsGatewaySubmit? = null
    private var selectedGeneration: IeltsGenerationRecord? = null
    private val aiDisabledArticles = mutableSetOf<String>()
    private var savedPractice: IeltsSavedPractice? = null
    private var importedPairing: ByteArray? = null
    private var pairingEpoch = 0L
    private var selectionEpoch = 0L
    private var editSequence = 0L
    private var operation: Job? = null
    private var debounce: Job? = null
    private var leaving: Job? = null
    private var studyOperation: Job? = null
    private var inputLocks = 0
    private var initializing = true
    private data class Preparation(val article: Article, val body: ArticleBody, val aiAllowed: Boolean)
    private var queuedPreparation: Preparation? = null
    private val draftMutex = Mutex()
    private var retryKind = Retry.REFRESH
    private var retryAttemptId: String? = null
    private enum class Retry { REFRESH, SAVE, SUBMIT, OPEN }

    init {
        viewModelScope.launch {
            try { generations.observeHistory().collect { value -> mutable.update { it.copy(generationHistory = value) } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(historyError = "生成记录暂时无法读取") } }
        }
        viewModelScope.launch {
            try { practices.observeHistory().collect { value -> mutable.update { it.copy(practiceHistory = value) } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(historyError = "练习记录暂时无法读取") } }
        }
        launchOperation(Retry.REFRESH, onFinished = {
            initializing = false
            queuedPreparation?.also { queuedPreparation = null }?.let { prepare(it.article, it.body, it.aiAllowed) }
        }) {
            val epoch = selectionEpoch
            loadConnection()
            loadStudy()
            if (resolvePendingRegeneration(epoch)) return@launchOperation
            val attemptId = savedState.get<String>(ATTEMPT_KEY)
            val requestId = savedState.get<String>(REQUEST_KEY)
            if (requestId != null) {
                val restored = generations.get(requestId)
                if (epoch != selectionEpoch) return@launchOperation
                restored?.let(::showGeneration)
            }
            if (epoch != selectionEpoch) return@launchOperation
            if (attemptId != null) loadPractice(attemptId)
            else if (requestId != null) generations.get(requestId)?.takeIf { it.packageHash != null }?.let { loadPractice(it.attemptId) }
        }
    }

    /** Explicit entry action. Recomposition must not call prepare. No request is sent here. */
    fun prepare(article: Article, body: ArticleBody, aiAllowed: Boolean) {
        if (aiAllowed) aiDisabledArticles.remove(article.id) else aiDisabledArticles.add(article.id)
        if (initializing) { queuedPreparation = Preparation(article, body, aiAllowed); return }
        if (!aiAllowed) {
            prepared = null
            mutable.update { it.copy(canGenerate = false, canRegenerate = false, error = "此来源未开放 AI 处理，仍可阅读和本地查词") }
            return
        }
        val current = prepared
        if (current != null && current.articleId == article.id && current.body == body.text && aiAllowed) return
        val epoch = selectionEpoch
        launchOperation(Retry.REFRESH, lockInput = true) {
            if (!flushDraft()) return@launchOperation
            prepared = null
            loadStudy()
            val payload = withContext(validationDispatcher) {
                require(aiAllowed) { "source_ai_disabled" }
                require(article.id == body.articleId && IeltsGatewayProtocol.bodyHash(body.text) == body.contentHash)
                val paragraphs = ParagraphFocus.paragraphs(body.text)
                require(paragraphs.isNotEmpty() && paragraphs.size <= 80)
                require(paragraphs.sumOf { (it.endExclusive - it.start).toLong() } <= IeltsContract.MAX_TEXT) { "source_too_long" }
                IeltsGatewaySubmit(requestId = idFactory(), articleId = article.id, body = body.text,
                    paragraphs = paragraphs.mapIndexed { index, paragraph ->
                        IeltsParagraph("p${index + 1}", paragraph.start, paragraph.endExclusive,
                            body.text.substring(paragraph.start, paragraph.endExclusive))
                    }, readingRequests = IeltsStudyPolicy.requests(state.value.study?.profile?.focus?.kind),
                    writingTask2Request = IeltsWritingRequest("w2", IeltsWritingType.EXTENT_OF_AGREEMENT),
                    writingTask1Request = IeltsVisualRequest("w1").takeIf {
                        "source_numeric_visual_v1" in state.value.connection.health?.capabilities?.supportedWritingTask1Policies.orEmpty()
                    }).also(IeltsGatewayProtocol::validateSubmit)
            }
            if (epoch != selectionEpoch) return@launchOperation
            selectionEpoch++
            prepared = payload; selectedGeneration = null; savedPractice = null; editSequence = 0
            savedState[REQUEST_KEY] = payload.requestId; savedState.remove<String>(ATTEMPT_KEY)
            savedState.remove<String>(PENDING_REQUEST_KEY)
            mutable.update { it.copy(preparedArticleId = article.id, preparedTitle = article.title,
                preparedParagraphCount = payload.paragraphs.size, generation = null, candidate = null,
                draftResponses = emptyList(), writingText = "", writingTask1Text = null, answerView = null, dirty = false,
                studyRequest = null, error = null) }
            updateCanGenerate()
        }
    }

    fun generate() {
        val original = prepared ?: return
        if (!state.value.canGenerate) return
        val epoch = selectionEpoch
        launchOperation(Retry.REFRESH) {
            val binding = studyRepository?.requestBinding(original.requestId)
            val profile = binding?.profile ?: state.value.study?.profile
            val payload = original.copy(readingRequests = if (profile != null) IeltsStudyPolicy.requests(profile.focus?.kind) else original.readingRequests,
                writingTask1Request = IeltsVisualRequest("w1").takeIf {
                    "source_numeric_visual_v1" in state.value.connection.health?.capabilities?.supportedWritingTask1Policies.orEmpty()
                })
            try {
                if (studyRepository != null) {
                    IeltsGatewayProtocol.requireSupported(payload, requireNotNull(state.value.connection.health))
                    val frozen = studyRepository.freeze(payload, requireNotNull(profile).revision, clock())
                    mutable.update { it.copy(studyRequest = frozen) }
                }
            } catch (failure: IeltsStudyFailure) {
                mutable.update { it.copy(studyError = studyMessage(failure)) }
                return@launchOperation
            }
            if (epoch != selectionEpoch) return@launchOperation
            prepared = payload
            val result = generations.generate(payload)
            if (epoch != selectionEpoch) return@launchOperation
            showGeneration(result)
            if (result.packageHash != null) loadPractice(result.attemptId)
        }
    }

    /** A terminal retry is a new durable request. The original record is never edited. */
    fun regenerate() {
        if (!state.value.canRegenerate || state.value.busy || state.value.isSaving) return
        val originalId = savedState.get<String>(REQUEST_KEY) ?: return
        val epoch = selectionEpoch
        launchOperation(Retry.REFRESH, lockInput = true) {
            if (!flushDraft()) return@launchOperation
            val original = generations.get(originalId)
                ?: throw IeltsGenerationFailure(IeltsGenerationIssue.STORAGE_UNAVAILABLE)
            if (epoch != selectionEpoch) return@launchOperation
            if (original.requestId != originalId) throw IeltsGenerationFailure(IeltsGenerationIssue.INVALID_RESPONSE)
            showGeneration(original)
            if (!mayRegenerate(original) || original.submit.articleId in aiDisabledArticles ||
                state.value.candidate != null || savedState.get<String>(PENDING_REQUEST_KEY) != null) return@launchOperation
            val replacement = original.submit.copy(requestId = idFactory())
            withContext(validationDispatcher) { IeltsGatewayProtocol.validateSubmit(replacement) }
            if (replacement.requestId == originalId || generations.get(replacement.requestId) != null)
                throw IeltsGenerationFailure(IeltsGenerationIssue.REQUEST_REJECTED)
            if (epoch != selectionEpoch || original.submit.articleId in aiDisabledArticles) return@launchOperation
            // generate() saves before its single POST. Retain both identities until
            // a read confirms that the new record is durable; errors keep the old selection.
            savedState[PENDING_REQUEST_KEY] = replacement.requestId
            studyRepository?.inherit(original.submit, replacement, clock())
            val result = generations.generate(replacement)
            if (epoch != selectionEpoch) return@launchOperation
            if (result.submit != replacement) throw IeltsGenerationFailure(IeltsGenerationIssue.INVALID_RESPONSE)
            val stored = generations.get(replacement.requestId)
                ?: throw IeltsGenerationFailure(IeltsGenerationIssue.STORAGE_UNAVAILABLE)
            if (epoch != selectionEpoch) return@launchOperation
            bindRegeneration(original, stored)
        }
    }

    /** Lifecycle callers may run this once on resume. There is no timer or retry loop in this model. */
    fun refresh() {
        val epoch = selectionEpoch
        launchOperation(Retry.REFRESH) {
            loadConnection()
            if (resolvePendingRegeneration(epoch)) return@launchOperation
            val requestId = savedState.get<String>(REQUEST_KEY) ?: return@launchOperation
            val current = generations.get(requestId) ?: return@launchOperation
            val result = if (current.packageHash == null) generations.reconcile(requestId) else current
            if (epoch != selectionEpoch) return@launchOperation
            showGeneration(result)
            if (result.packageHash != null && savedPractice == null) loadPractice(result.attemptId)
        }
    }

    fun reconcile() = refresh()
    fun cancel() {
        val id = savedState.get<String>(REQUEST_KEY) ?: return
        val epoch = selectionEpoch
        launchOperation(Retry.REFRESH, lockInput = true) {
            val result = generations.cancel(id)
            if (epoch == selectionEpoch) showGeneration(result)
        }
    }

    fun openGeneration(requestId: String) {
        val epoch = selectionEpoch
        launchOperation(Retry.REFRESH, lockInput = true) {
            if (!flushDraft()) return@launchOperation
            val result = generations.get(requestId) ?: throw IeltsGenerationFailure(IeltsGenerationIssue.STORAGE_UNAVAILABLE)
            if (epoch != selectionEpoch) return@launchOperation
            selectionEpoch++; prepared = null; savedPractice = null
            if (savedState.get<String>(REQUEST_KEY) != requestId) savedState.remove<String>(PENDING_REQUEST_KEY)
            savedState[REQUEST_KEY] = requestId; savedState.remove<String>(ATTEMPT_KEY)
            mutable.update { it.copy(candidate = null, draftResponses = emptyList(), writingText = "", writingTask1Text = null, answerView = null,
                preparedArticleId = result.submit.articleId, preparedTitle = null,
                preparedParagraphCount = result.submit.paragraphs.size, dirty = false) }
            showGeneration(result)
            if (result.packageHash != null) loadPractice(result.attemptId)
        }
    }

    fun openAttempt(id: String) {
        retryAttemptId = id
        val epoch = selectionEpoch
        launchOperation(Retry.OPEN, lockInput = true) {
            if (!flushDraft()) return@launchOperation
            val result = practices.get(id) ?: throw IllegalStateException("missing_attempt")
            if (epoch != selectionEpoch) return@launchOperation
            selectionEpoch++; prepared = null; selectedGeneration = null
            savedState.remove<String>(REQUEST_KEY)
            savedState.remove<String>(PENDING_REQUEST_KEY)
            mutable.update { it.copy(generation = null, preparedArticleId = result.request.articleId,
                preparedTitle = null, preparedParagraphCount = result.request.paragraphs.size) }
            showPractice(result)
        }
    }

    fun answer(questionId: String, value: String) {
        val practice = savedPractice ?: return
        if (state.value.isSaving || practice.submitted) return
        val question = practice.exercise.readingQuestions.singleOrNull { it.questionId == questionId } ?: return
        if (value.isNotEmpty() && runCatching { IeltsExerciseValidator.validateResponse(question, value) }.isFailure) return
        val previous = state.value.draftResponses.associate { it.questionId to it.answer }
        val changed = if (value.isEmpty()) previous - questionId else previous + (questionId to value)
        val ordered = practice.exercise.readingQuestions.mapNotNull { q -> changed[q.questionId]?.let { IeltsResponse(q.questionId, it) } }
        if (ordered == state.value.draftResponses) return
        editSequence++
        mutable.update { it.copy(draftResponses = ordered, dirty = true, error = null) }
        scheduleSave()
    }

    fun write(value: String) {
        if (savedPractice == null || savedPractice?.submitted == true || state.value.isSaving || value == state.value.writingText) return
        if (runCatching { IeltsExerciseValidator.wordCount(value) }.isFailure) return
        editSequence++
        mutable.update { it.copy(writingText = value, dirty = true, error = null) }
        scheduleSave()
    }

    fun writeTask1(value: String) {
        if (savedPractice?.exercise?.writingTask1?.status != "available" || savedPractice?.submitted == true ||
            state.value.isSaving || value == state.value.writingTask1Text) return
        if (runCatching { IeltsExerciseValidator.wordCount(value) }.isFailure) return
        editSequence++
        mutable.update { it.copy(writingTask1Text = value, dirty = true, error = null) }
        scheduleSave()
    }

    fun submit() {
        if (savedPractice == null || savedPractice?.submitted == true || operation?.isActive == true ||
            leaving?.isActive == true || state.value.busy) return
        debounce?.cancel()
        val epoch = selectionEpoch
        launchOperation(Retry.SUBMIT, lockInput = true) {
            if (!flushDraft()) { retryKind = Retry.SUBMIT; return@launchOperation }
            val current = savedPractice ?: return@launchOperation
            val result = practices.submit(current.attemptId, current.revision, clock())
            if (epoch == selectionEpoch) showPractice(result)
        }
    }

    /** Root calls this before leaving. The callback runs only after the local draft is durable. */
    fun flush(onSaved: () -> Unit = {}) {
        if (leaving?.isActive == true) return
        debounce?.cancel()
        acquireInputLock()
        leaving = viewModelScope.launch {
            try { if (withContext(NonCancellable) { flushDraft() }) onSaved() }
            finally { unlockInput() }
        }
    }

    fun clearSelection(onCleared: () -> Unit = {}) = flush {
        queuedPreparation = null
        selectionEpoch++; prepared = null; selectedGeneration = null; savedPractice = null; editSequence = 0
        savedState.remove<String>(REQUEST_KEY); savedState.remove<String>(ATTEMPT_KEY); savedState.remove<String>(PENDING_REQUEST_KEY)
        mutable.update { it.copy(preparedArticleId = null, preparedTitle = null, preparedParagraphCount = 0, canGenerate = false, canRegenerate = false,
            generation = null, candidate = null, draftResponses = emptyList(), writingText = "", writingTask1Text = null, answerView = null,
            studyRequest = null, dirty = false, error = null) }
        onCleared()
    }
    fun onBack(onClosed: () -> Unit) = clearSelection { discardPairing(); onClosed() }

    /** A local preview does not contact the host or retain the document in saved state. */
    fun inspectPairing(document: ByteArray) {
        if (state.value.busy) return
        discardPairing()
        val snapshot = document.copyOf()
        val epoch = pairingEpoch
        launchOperation(Retry.REFRESH) {
            try {
                val preview = generations.inspectPairing(snapshot)
                if (epoch != pairingEpoch) return@launchOperation
                importedPairing = snapshot.copyOf()
                mutable.update { it.copy(pairingPreview = preview) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (epoch == pairingEpoch) throw failure
            } finally { snapshot.fill(0) }
        }
    }
    fun confirmImportedPairing() {
        if (state.value.busy) return
        val snapshot = importedPairing?.copyOf() ?: return
        val epoch = pairingEpoch
        launchOperation(Retry.REFRESH) {
            try {
                val connection = generations.pairImported(snapshot)
                if (epoch != pairingEpoch) return@launchOperation
                mutable.update { it.copy(connection = connection) }
                discardPairing()
                updateCanGenerate()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (epoch == pairingEpoch) throw failure
            } finally { snapshot.fill(0) }
        }
    }
    fun discardPairing() {
        pairingEpoch++
        importedPairing?.fill(0); importedPairing = null
        mutable.update { it.copy(pairingPreview = null) }
    }
    fun pairingImportFailed() {
        discardPairing()
        mutable.update { it.copy(error = "连接文件无法读取，请重新选择电脑导出的文件") }
    }
    override fun onCleared() { importedPairing?.fill(0); importedPairing = null; super.onCleared() }

    fun pair(endpoint: String, token: String) {
        discardPairing()
        launchOperation(Retry.REFRESH) {
            val connection = generations.pair(endpoint, token)
            mutable.update { it.copy(connection = connection) }
            updateCanGenerate()
        }
    }
    fun clearPairing() {
        launchOperation(Retry.REFRESH) {
            generations.clearPairing(); loadConnection()
            // Old requests retain their old host binding in Room.
        }
    }
    fun retry() {
        when (retryKind) {
            Retry.SUBMIT -> submit()
            Retry.SAVE -> flush()
            Retry.OPEN -> retryAttemptId?.let(::openAttempt) ?: refresh()
            Retry.REFRESH -> refresh()
        }
    }

    private fun scheduleSave() {
        debounce?.cancel()
        val epoch = selectionEpoch
        debounce = viewModelScope.launch {
            delay(450)
            if (epoch == selectionEpoch) flushDraft()
        }
    }

    private suspend fun flushDraft(): Boolean = withContext(NonCancellable) { draftMutex.withLock {
        val current = savedPractice ?: return@withLock true
        if (current.submitted || !state.value.dirty) return@withLock true
        val epoch = selectionEpoch
        val sequence = editSequence
        val responses = state.value.draftResponses.toList()
        val writing = state.value.writingText
        val writing1 = state.value.writingTask1Text
        mutable.update { it.copy(savingDraft = true) }
        try {
            val result = withContext(NonCancellable) {
                practices.saveWritingDrafts(current.attemptId, current.revision, responses, writing, writing1, clock())
            }
            if (epoch == selectionEpoch && savedPractice?.attemptId == result.attemptId) {
                savedPractice = result
                mutable.update { it.copy(dirty = editSequence != sequence, error = null) }
            }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            retryKind = Retry.SAVE
            mutable.update { it.copy(error = "草稿未保存，当前输入已保留。请重试后再离开。", dirty = true) }
            false
        } finally { mutable.update { it.copy(savingDraft = false) } }
    } }

    private suspend fun loadConnection() {
        val connection = generations.connection()
        mutable.update { it.copy(connection = connection) }
        updateCanGenerate()
    }

    /** Feedback is independent from immutable answers. Failures do not block reading a saved exercise. */
    private suspend fun loadStudy(attemptId: String? = null) {
        val repository = studyRepository ?: return
        val epoch = selectionEpoch
        try {
            val snapshot = repository.load(attemptId)
            if (epoch == selectionEpoch) mutable.update { it.copy(study = snapshot, studyError = null) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (epoch == selectionEpoch) mutable.update { it.copy(study = null, studyError = studyMessage(failure)) } }
    }

    fun reflect(questionId: String, intent: IeltsStudyIntent?) {
        val repository = studyRepository ?: return
        val current = savedPractice?.takeIf { it.submitted } ?: return
        val snapshot = state.value.study ?: return
        if (state.value.busy || state.value.isSaving || studyOperation?.isActive == true) return
        if (current.exercise.readingQuestions.none { it.questionId == questionId }) return
        val epoch = selectionEpoch
        val revision = snapshot.feedback.singleOrNull { it.questionId == questionId && it.attemptId == current.attemptId }?.revision ?: 0
        studyOperation = viewModelScope.launch {
            mutable.update { it.copy(studyBusy = true, studyError = null) }
            try {
                val next = repository.reflect(current.attemptId, questionId, intent, revision, snapshot.profile.revision, clock())
                if (epoch == selectionEpoch && savedPractice?.attemptId == current.attemptId) mutable.update { it.copy(study = next) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (epoch == selectionEpoch) mutable.update { it.copy(studyError = studyMessage(failure)) }
            } finally {
                studyOperation = null; mutable.update { it.copy(studyBusy = false) }; updateCanGenerate()
            }
        }
        updateCanGenerate()
    }

    fun useBalancedStudy() {
        val repository = studyRepository ?: return
        val profile = state.value.study?.profile ?: return
        if (state.value.busy || state.value.studyBusy || state.value.studyRequest != null) return
        val epoch = selectionEpoch
        studyOperation = viewModelScope.launch {
            mutable.update { it.copy(studyBusy = true, studyError = null) }
            try {
                val next = repository.useBalanced(profile.revision, clock())
                if (epoch == selectionEpoch) mutable.update { it.copy(study = next) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (epoch == selectionEpoch) mutable.update { it.copy(studyError = studyMessage(failure)) } }
            finally { studyOperation = null; mutable.update { it.copy(studyBusy = false) }; updateCanGenerate() }
        }
        updateCanGenerate()
    }

    fun reloadStudy() {
        if (state.value.busy || state.value.studyBusy) return
        val id = savedPractice?.takeIf { it.submitted }?.attemptId
        val epoch = selectionEpoch
        studyOperation = viewModelScope.launch {
            mutable.update { it.copy(studyBusy = true) }
            try {
                val next = studyRepository?.load(id)
                if (epoch == selectionEpoch) mutable.update { it.copy(study = next, studyError = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (epoch == selectionEpoch) mutable.update { it.copy(studyError = studyMessage(failure)) } }
            finally { studyOperation = null; mutable.update { it.copy(studyBusy = false) }; updateCanGenerate() }
        }
    }

    private fun studyMessage(error: Exception): String = when ((error as? IeltsStudyFailure)?.code) {
        IeltsStudyFailureCode.CONFLICT -> "复盘记录已更新，请重新读取后选择"
        else -> "复盘暂未保存，已有作答保留。请重新读取后选择"
    }
    private fun mayRegenerate(value: IeltsGenerationRecord): Boolean {
        val job = value.job ?: return false
        return value.dispatchClaimed && job.requestId == value.requestId && value.packageHash == null && job.processReaped == 1 &&
            value.issue != IeltsGenerationIssue.OUTCOME_UNKNOWN && job.error != "provider_completion_unknown" &&
            ((value.stage == IeltsGenerationStage.FAILED && job.state == IeltsGatewayJobState.FAILED) ||
                (value.stage == IeltsGenerationStage.CANCELLED && job.state == IeltsGatewayJobState.CANCELLED))
    }
    private suspend fun resolvePendingRegeneration(epoch: Long): Boolean {
        val pendingId = savedState.get<String>(PENDING_REQUEST_KEY) ?: return false
        val originalId = savedState.get<String>(REQUEST_KEY)
            ?: throw IeltsGenerationFailure(IeltsGenerationIssue.STORAGE_UNAVAILABLE)
        val original = generations.get(originalId)
            ?: throw IeltsGenerationFailure(IeltsGenerationIssue.STORAGE_UNAVAILABLE)
        if (epoch != selectionEpoch) return true
        if (original.requestId != originalId) throw IeltsGenerationFailure(IeltsGenerationIssue.INVALID_RESPONSE)
        showGeneration(original)
        val stored = generations.get(pendingId)
        if (epoch != selectionEpoch) return true
        if (stored == null) {
            // A successful local lookup establishes that no new row was saved.
            savedState.remove<String>(PENDING_REQUEST_KEY)
            updateCanGenerate()
            return false
        }
        bindRegeneration(original, stored)
        return true
    }
    private suspend fun bindRegeneration(original: IeltsGenerationRecord, stored: IeltsGenerationRecord) {
        val pendingId = savedState.get<String>(PENDING_REQUEST_KEY)
        if (pendingId == null || savedState.get<String>(REQUEST_KEY) != original.requestId ||
            pendingId == original.requestId || stored.requestId != pendingId ||
            stored.submit != original.submit.copy(requestId = pendingId))
            throw IeltsGenerationFailure(IeltsGenerationIssue.INVALID_RESPONSE)
        selectionEpoch++; prepared = null; savedPractice = null; editSequence = 0
        savedState[REQUEST_KEY] = stored.requestId
        savedState.remove<String>(ATTEMPT_KEY); savedState.remove<String>(PENDING_REQUEST_KEY)
        mutable.update { it.copy(candidate = null, draftResponses = emptyList(), writingText = "", writingTask1Text = null, answerView = null, dirty = false) }
        showGeneration(stored)
        if (stored.packageHash != null) loadPractice(stored.attemptId)
    }
    private suspend fun loadPractice(id: String) {
        val epoch = selectionEpoch
        val value = practices.get(id) ?: throw IllegalStateException("missing_attempt")
        if (epoch == selectionEpoch) showPractice(value)
    }
    private suspend fun showPractice(value: IeltsSavedPractice) {
        val epoch = selectionEpoch
        val views = withContext(validationDispatcher) { value.candidate() to value.answerView() }
        if (epoch != selectionEpoch) return
        savedPractice = value; editSequence = 0
        savedState[ATTEMPT_KEY] = value.attemptId
        mutable.update { it.copy(candidate = views.first, draftResponses = value.draftResponses.toList(),
            writingText = value.writingText, writingTask1Text = value.writingTask1Text, answerView = views.second, dirty = false, error = null, canGenerate = false, canRegenerate = false) }
        loadStudy(value.attemptId.takeIf { value.submitted })
    }
    private fun showGeneration(value: IeltsGenerationRecord) {
        selectedGeneration = value
        val summary = value.toHistoryItem()
        mutable.update { current -> current.copy(generation = summary, preparedArticleId = value.submit.articleId,
            preparedTitle = current.preparedTitle.takeIf { current.preparedArticleId == value.submit.articleId },
            preparedParagraphCount = value.submit.paragraphs.size, error = value.issue?.let(::ieltsGenerationIssueText)) }
        updateCanGenerate()
    }
    private fun updateCanGenerate() {
        mutable.update { current ->
            val generation = current.generation
            current.copy(canGenerate = prepared != null && current.connection.paired && !current.busy && !current.isSaving && !current.studyBusy &&
                (studyRepository == null || (current.study != null && current.studyError == null)) &&
                current.candidate == null && (generation == null || generation.stage == IeltsGenerationStage.PREPARED),
                canRegenerate = current.connection.paired && current.candidate == null &&
                    savedState.get<String>(PENDING_REQUEST_KEY) == null && selectedGeneration?.let { record ->
                        record.requestId == generation?.requestId && record.requestId == savedState.get<String>(REQUEST_KEY) &&
                            record.submit.articleId !in aiDisabledArticles && mayRegenerate(record)
                    } == true)
        }
    }
    private fun launchOperation(retry: Retry, lockInput: Boolean = false, onFinished: () -> Unit = {}, block: suspend () -> Unit) {
        if (operation?.isActive == true || leaving?.isActive == true || state.value.busy) return
        retryKind = retry
        if (lockInput) acquireInputLock()
        mutable.update { it.copy(busy = true, error = null, canGenerate = false) }
        operation = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(error = safeMessage(failure)) } }
            finally {
                if (lockInput) unlockInput()
                operation = null
                mutable.update { it.copy(busy = false) }; updateCanGenerate()
                onFinished()
            }
        }
    }
    private fun acquireInputLock() { inputLocks++; mutable.update { it.copy(isSaving = true, canGenerate = false) } }
    private fun unlockInput() {
        inputLocks = (inputLocks - 1).coerceAtLeast(0)
        mutable.update { it.copy(isSaving = inputLocks > 0) }; updateCanGenerate()
    }
    private fun safeMessage(failure: Exception): String = when (failure) {
        is IeltsStudyFailure -> studyMessage(failure)
        is IeltsGenerationFailure -> ieltsGenerationIssueText(failure.issue)
        is IeltsGatewaySubmissionUncertain -> ieltsGenerationIssueText(IeltsGenerationIssue.OUTCOME_UNKNOWN)
        is IeltsGatewayFailure -> when (failure.code) {
            IeltsGatewayFailureCode.HTTPS_REQUIRED -> "请输入 HTTPS 网关地址"
            IeltsGatewayFailureCode.TOKEN_INVALID -> "配对码格式不正确"
            IeltsGatewayFailureCode.PAIRING_DOCUMENT_INVALID -> "连接文件格式不正确，请从电脑重新导出"
            IeltsGatewayFailureCode.CERTIFICATE_INVALID -> "电脑证书不可用，请重新导出连接文件"
            IeltsGatewayFailureCode.TLS_REJECTED -> "无法确认电脑证书，配对未完成；请核对地址和连接文件"
            IeltsGatewayFailureCode.NOT_PAIRED -> "请先配对出题网关"
            IeltsGatewayFailureCode.UNAUTHORIZED -> "配对已失效，请重新配对"
            IeltsGatewayFailureCode.CONFIGURATION_CHANGED -> "网关配置已改变，请连接原网关继续此任务"
            else -> "网关暂时不可用，已保存的请求与作答会保留"
        }
        is IllegalArgumentException -> when (failure.message) {
            "source_ai_disabled" -> "此来源未开放 AI 处理，仍可阅读和本地查词"
            "source_too_long" -> "原文超过本次练习的 24,000 字符范围，请选择较短文章"
            else -> "当前原文或作答无法验证，已有内容已保留"
        }
        else -> "操作未完成，已有请求和作答已保留，请重试"
    }
    companion object {
        const val REQUEST_KEY = "ielts_current_request_id"
        const val ATTEMPT_KEY = "ielts_current_attempt_id"
        const val PENDING_REQUEST_KEY = "ielts_pending_regeneration_request_id"
    }
}

fun ieltsGenerationIssueText(issue: IeltsGenerationIssue): String = when (issue) {
    IeltsGenerationIssue.OUTCOME_UNKNOWN -> "正在等待原请求确认，可刷新查询。不会重复生成。"
    IeltsGenerationIssue.NOT_FOUND -> "原网关尚未找到此请求，可稍后刷新"
    IeltsGenerationIssue.CONNECTION_CHANGED -> "请连接此任务原来的网关，当前配对不会接收旧任务"
    IeltsGenerationIssue.NOT_PAIRED -> "请先配对出题网关"
    IeltsGenerationIssue.UNAUTHORIZED -> "配对已失效，请重新配对"
    IeltsGenerationIssue.STORAGE_UNAVAILABLE -> "本地记录暂时无法读取或保存，已有内容保留"
    IeltsGenerationIssue.INVALID_RESPONSE -> "题包或来源校验未通过，暂不展示练习"
    IeltsGenerationIssue.REMOTE_FAILURE -> "出题任务暂未完成，可刷新查看原任务"
    IeltsGenerationIssue.REQUEST_REJECTED -> "请求未被接受，原请求和已有作答保留"
}
