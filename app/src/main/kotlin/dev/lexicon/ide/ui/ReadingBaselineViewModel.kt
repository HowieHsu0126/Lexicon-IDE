package dev.lexicon.ide.ui

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json

/** Independent, self-paced first-exposure measurement. No paced-run records or implicit AI work. */
internal class ReadingBaselineViewModel(
    private val app: AppRepository,
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val wall: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {
    private val baseline = requireNotNull(app.baseline)
    private val plans = requireNotNull(app.speedReading)
    private val mutable = MutableStateFlow(BaselineUiState())
    val state = mutable.asStateFlow()
    val library = baseline.library.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val json = Json { ignoreUnknownKeys = true }
    private var article: Article? = null
    private var entry: String? = null
    private var generation = 0
    private var work: Job? = null
    private var runningSince: Long? = null
    private var foreground = true
    private var pending: ReadingBaselineAttempt? = null
    private var restore = saved.get<String>(KEY)
    private var lastPrepareAi = false
    private var historyRequest: String? = null

    fun bind(article: Article, aiAllowed: Boolean, hasKey: Boolean, entryToken: Int) {
        val identity = "${article.id}:$entryToken"
        if (entry == identity) {
            mutable.update { it.copy(aiAllowed = aiAllowed, hasKey = hasKey) }; return
        }
        pause(); work?.cancel(); generation++
        this.article = article; entry = identity; pending = null; historyRequest = null
        mutable.value = BaselineUiState(aiAllowed = aiAllowed, hasKey = hasKey)
        reload()
    }

    fun reload() {
        historyRequest?.let { openHistory(it) {}; return }
        val a = article ?: return
        if (state.value.loading || state.value.saving || state.value.attempt != null) return
        val token = ++generation
        mutable.update { it.copy(loading = true, error = null) }
        work = viewModelScope.launch {
            try {
                val checkpoint = restore?.let { json.decodeFromString<ReadingBaselineAttempt>(it) }
                if (checkpoint != null && checkpoint.articleId == a.id) {
                    restoreAttempt(checkpoint, token)
                } else {
                    val body = requireNotNull(app.prepareBaselineBody(a.id))
                    if (token != generation) return@launch
                    mutable.update { it.copy(body = body, loading = false) }
                    saved.remove<String>(KEY)
                }
                if (token == generation) restore = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (token == generation) mutable.update { it.copy(loading = false,
                    error = "自测材料暂时无法恢复，请重试。已保存的记录仍保留。") }
            }
        }
    }

    fun setStart(value: Int) {
        if (state.value.stage != BaselineStage.SETUP || state.value.loading) return
        val count = state.value.body?.let { ParagraphFocus.paragraphs(it.text).size } ?: return
        val start = value.coerceIn(0, count - 1)
        mutable.update { it.copy(startParagraph = start, paragraphCount = it.paragraphCount.coerceAtMost(count - start)) }
    }
    fun setCount(value: Int) {
        if (state.value.stage != BaselineStage.SETUP || state.value.loading) return
        val count = state.value.body?.let { ParagraphFocus.paragraphs(it.text).size } ?: return
        mutable.update { it.copy(paragraphCount = value.coerceIn(1, minOf(3, count - it.startParagraph))) }
    }
    fun confirmUnseen(value: Boolean) {
        if (state.value.attempt == null && !state.value.saving) mutable.update { it.copy(userConfirmedUnseen = value) }
    }

    fun prepare(useAi: Boolean) {
        val a = article ?: return
        val s = state.value
        val body = s.body ?: return
        if (s.loading || s.saving || s.attempt != null) return
        val token = ++generation
        lastPrepareAi = useAi
        mutable.update { it.copy(loading = true, error = null) }
        work = viewModelScope.launch {
            try {
                val plan = if (s.error != null && useAi) plans.retryPrepare(a, body, SpeedGranularity.L2,
                    s.startParagraph, s.paragraphCount, s.aiAllowed, true)
                else plans.prepare(a, body, SpeedGranularity.L2, s.startParagraph, s.paragraphCount, s.aiAllowed, useAi)
                SpeedChunker.validate(plan, body.text)
                require(plan.articleId == a.id && plan.bodyHash == body.contentHash)
                if (token == generation) mutable.update { it.copy(plan = plan, stage = BaselineStage.READY, loading = false) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (token == generation) mutable.update { it.copy(loading = false, error = "材料准备失败，可重试或离线阅读。") }
            }
        }
    }

    fun togglePlay() {
        val s = state.value
        if (s.loading || s.saving || s.error != null || !foreground) return
        if (s.playing) { pause(count = true); persist(); return }
        if (s.stage == BaselineStage.READY) {
            val plan = s.plan ?: return
            val token = generation
            mutable.update { it.copy(saving = true) }
            work = viewModelScope.launch {
                try {
                    val attempt = baseline.begin(plan.planKey, s.userConfirmedUnseen)
                    if (token != generation) return@launch
                    ReadingBaselineMetrics.summarize(attempt, plan)
                    mutable.update { it.copy(attempt = attempt, stage = BaselineStage.READING, saving = false) }
                    checkpoint()
                    if (foreground) startClock()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    if (token == generation) mutable.update { it.copy(saving = false, error = "自测尚未开始，请重试。") }
                }
            }
        } else if (s.stage == BaselineStage.READING) startClock()
    }
    private fun startClock() { runningSince = monotonic(); mutable.update { it.copy(playing = true) } }

    /** Text disappears synchronously with the stopped clock, including lifecycle interruptions. */
    fun pause(count: Boolean = false) {
        val from = runningSince ?: return
        runningSince = null
        val s = state.value
        val attempt = s.attempt ?: return
        val durations = attempt.paragraphs.toMutableList()
        val previous = durations[s.paragraphIndex]
        durations[s.paragraphIndex] = previous.copy(activeMillis = previous.activeMillis + (monotonic() - from).coerceAtLeast(0))
        val elapsed = durations.sumOf { it.activeMillis }
        val next = attempt.copy(paragraphs = durations, pauses = attempt.pauses + if (count) 1 else 0,
            finishedAt = maxOf(wall(), attempt.startedAt + elapsed))
        mutable.update { it.copy(playing = false, attempt = next, elapsedMillis = elapsed) }; checkpoint()
    }
    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) { pause(count = true); if (entry != null && state.value.attempt?.completed == false) persist() }
    }

    fun completeParagraph() {
        val s = state.value
        if (!s.playing || s.saving || s.stage != BaselineStage.READING || s.error != null) return
        pause()
        val before = requireNotNull(state.value.attempt)
        if (before.paragraphs[s.paragraphIndex].activeMillis <= 0) return
        val durations = before.paragraphs.mapIndexed { i, timing -> if (i == s.paragraphIndex) timing.copy(completed = true) else timing }
        val last = s.paragraphIndex == durations.lastIndex
        val noQuestions = s.plan!!.questions.isEmpty()
        val next = before.copy(paragraphs = durations, readingFinishedAt = before.finishedAt.takeIf { last },
            completed = last && noQuestions, revealedAt = before.finishedAt.takeIf { last && noQuestions })
        mutable.update { it.copy(attempt = next, paragraphIndex = if (last) s.paragraphIndex else s.paragraphIndex + 1,
            stage = if (!last) BaselineStage.READING else if (noQuestions) BaselineStage.RESULT else BaselineStage.QUESTION) }
        checkpoint(); persist()
    }
    fun answer(index: Int) {
        val s = state.value
        if (s.stage != BaselineStage.QUESTION || s.saving || s.error != null) return
        val question = s.plan?.questions?.getOrNull(s.attempt?.answers?.size ?: 0) ?: return
        if (index in question.options.indices) mutable.update { it.copy(selectedAnswer = index) }
    }
    fun submitAnswer() {
        val s = state.value
        if (s.stage != BaselineStage.QUESTION || s.saving || s.error != null) return
        val selected = s.selectedAnswer ?: return
        val old = s.attempt ?: return
        val question = s.plan?.questions?.getOrNull(old.answers.size) ?: return
        val answers = old.answers + SpeedAnswer(question.id, selected, false)
        val complete = answers.size == s.plan.questions.size
        val finished = maxOf(wall(), old.finishedAt)
        val next = old.copy(answers = answers, completed = complete, revealedAt = finished.takeIf { complete }, finishedAt = finished)
        mutable.update { it.copy(attempt = next, selectedAnswer = null,
            stage = if (complete) BaselineStage.RESULT else BaselineStage.QUESTION) }
        checkpoint(); persist()
    }

    fun retry() {
        if (state.value.attempt?.completed == true && pending == null) return
        if (state.value.attempt == null) historyRequest?.let { openHistory(it) {}; return }
        if (state.value.stage == BaselineStage.SETUP && state.value.body != null && restore == null) {
            prepare(lastPrepareAi); return
        }
        mutable.update { it.copy(error = null) }
        when {
            state.value.attempt != null -> persist()
            state.value.body == null || restore != null -> reload()
            state.value.stage == BaselineStage.SETUP -> prepare(lastPrepareAi)
            else -> togglePlay()
        }
    }
    /** Pending writes retain one immutable candidate; failed final writes never reveal answers. */
    private fun persist(onSaved: () -> Unit = {}) {
        if (state.value.saving) return
        pause()
        val candidate = pending ?: state.value.attempt ?: run { onSaved(); return }
        val token = generation
        pending = candidate
        mutable.update { it.copy(saving = true, error = null, summary = null) }
        work = viewModelScope.launch {
            try {
                val result = baseline.save(candidate)
                if (token != generation) return@launch
                pending = null
                applyRecord(result, requireNotNull(state.value.plan))
                checkpoint(); onSaved()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (token == generation) mutable.update { it.copy(saving = false, error = "自测记录尚未保存，请重试。") }
            }
        }
    }
    fun leave(onLeft: () -> Unit) {
        if (state.value.saving) return
        pause()
        fun exit() { work?.cancel(); generation++; entry = null; historyRequest = null; saved.remove<String>(KEY); restore = null; onLeft() }
        if (state.value.attempt != null && (state.value.attempt?.completed == false || pending != null)) persist(::exit) else exit()
    }

    private suspend fun restoreAttempt(candidate: ReadingBaselineAttempt, token: Int) {
        val plan = requireNotNull(plans.cachedPlan(candidate.planKey))
        if (token != generation) return
        val material = requireNotNull(baseline.material(candidate.id))
        if (token != generation) return
        checkMaterial(material, plan)
        ReadingBaselineMetrics.summarize(candidate, plan)
        val stored = requireNotNull(baseline.get(candidate.id))
        if (token != generation) return
        require(stored.articleId == candidate.articleId && stored.planKey == candidate.planKey && stored.startedAt == candidate.startedAt)
        val result = if (stored.completed) stored else {
            val base = if (stored.answers.size > candidate.answers.size) stored else candidate
            base.copy(paragraphs = if (stored.readingFinishedAt != null) stored.paragraphs else stored.paragraphs.zip(candidate.paragraphs).map { (a, b) ->
                a.copy(activeMillis = maxOf(a.activeMillis, b.activeMillis), completed = a.completed || b.completed)
            }, pauses = maxOf(stored.pauses, candidate.pauses), finishedAt = maxOf(stored.finishedAt, candidate.finishedAt),
                readingFinishedAt = stored.readingFinishedAt ?: candidate.readingFinishedAt,
                localUnseen = stored.localUnseen, userConfirmedUnseen = stored.userConfirmedUnseen,
                resumedFromHistory = stored.resumedFromHistory)
        }
        ReadingBaselineMetrics.summarize(result, plan)
        mutable.update { it.copy(material = material) }
        applyRecord(result, plan); checkpoint()
        if (result != stored) persist()
    }
    private fun applyRecord(record: ReadingBaselineAttempt, plan: SpeedPlan) {
        val summary = ReadingBaselineMetrics.summarize(record, plan)
        val stage = when { record.completed -> BaselineStage.RESULT; record.readingFinishedAt != null -> BaselineStage.QUESTION; else -> BaselineStage.READING }
        mutable.update { it.copy(plan = plan, attempt = record, saving = false, loading = false, playing = false,
            stage = stage, paragraphIndex = record.paragraphs.indexOfFirst { !it.completed }.takeIf { it >= 0 } ?: record.paragraphs.lastIndex,
            elapsedMillis = summary.activeMillis, summary = summary.takeIf { record.completed }, error = null) }
    }
    fun openHistory(id: String, onOpened: (String) -> Unit) {
        if (state.value.saving || (entry == "history:$id" && state.value.attempt?.id == id && state.value.error == null)) return
        pause(); work?.cancel(); pending = null; historyRequest = id
        val token = ++generation
        mutable.value = BaselineUiState(loading = true)
        work = viewModelScope.launch {
            try {
                val savedAttempt = requireNotNull(baseline.get(id))
                val plan = requireNotNull(plans.cachedPlan(savedAttempt.planKey))
                val material = requireNotNull(baseline.material(id))
                checkMaterial(material, plan)
                ReadingBaselineMetrics.summarize(savedAttempt, plan)
                if (token != generation) return@launch
                val attempt = if (savedAttempt.completed) savedAttempt else baseline.resume(id)
                if (token != generation) return@launch
                entry = "history:$id"
                val checkpoint = restore?.let { runCatching { json.decodeFromString<ReadingBaselineAttempt>(it) }.getOrNull() }
                mutable.update { it.copy(material = material) }
                if (checkpoint?.id == id && !attempt.completed) restoreAttempt(checkpoint, token)
                else { applyRecord(attempt, plan); checkpoint() }
                if (token == generation) { restore = null; onOpened(attempt.articleId) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (token == generation) mutable.update { it.copy(loading = false, error = "这条自测记录暂时无法打开，请重试。") } }
        }
    }
    private fun checkMaterial(material: ReadingBaselineMaterial, plan: SpeedPlan) {
        require(material.planKey == plan.planKey && material.bodyHash == plan.bodyHash)
        require(material.paragraphs.map { it.paragraph } == plan.paragraphIndices)
        require(material.paragraphs.all { it.start >= 0 && it.text.isNotBlank() })
        require(plan.chunks.all { chunk -> material.textAt(chunk.start, chunk.end) != null })
        require(plan.questions.all { q -> material.textAt(q.evidenceStart, q.evidenceEnd) != null })
    }
    fun readerAnchor(): ReaderAnchor? = state.value.plan?.let { plan ->
        val chunks = plan.chunks.filter { it.paragraph == plan.paragraphIndices.first() }
        chunks.firstOrNull()?.let { ReaderAnchor(plan.bodyHash, it.start, chunks.last().end) }
    }
    private fun checkpoint() { state.value.attempt?.let { saved[KEY] = json.encodeToString(ReadingBaselineAttempt.serializer(), it) } }
    companion object { private const val KEY = "reading-baseline" }
}
