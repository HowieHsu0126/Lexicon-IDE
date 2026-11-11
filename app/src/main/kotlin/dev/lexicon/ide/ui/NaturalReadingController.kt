package dev.lexicon.ide.ui

import android.os.Bundle
import dev.lexicon.domain.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

internal data class NaturalUiState(
    val plan: SpeedPlan,
    val paragraphIndex: Int = 0,
    val playing: Boolean = false,
    val elapsedMillis: Long = 0,
    val paragraphMillis: Long = 0,
    val result: NaturalReadingAttempt? = null,
    val saving: Boolean = false,
    val error: String? = null,
)

/** Self-paced paragraph accounting. Never distributes time across unobserved sense groups. */
internal class NaturalReadingController(
    private val plan: SpeedPlan,
    private val parentRun: SpeedRun,
    private val repository: SpeedReadingRepository,
    private val scope: CoroutineScope,
    private val wall: () -> Long,
    private val monotonic: () -> Long,
    private val publish: (NaturalUiState) -> Unit,
    private val checkpoint: () -> Unit,
) {
    var state = NaturalUiState(plan); private set
    private var attemptId = UUID.randomUUID().toString()
    private var startedAt = maxOf(wall(), parentRun.finishedAt)
    private var durations = LongArray(plan.paragraphIndices.size)
    private var confirmed = BooleanArray(plan.paragraphIndices.size)
    private var runningSince: Long? = null
    private var pauses = 0
    private var live = true

    init {
        require(parentRun.completed && parentRun.round == SpeedPacedRound.FADED && parentRun.planKey == plan.planKey)
    }

    fun toggle(foreground: Boolean) {
        if (state.saving || state.result != null || !live) return
        if (state.playing) pause(true)
        else if (foreground) {
            runningSince = monotonic()
            state = state.copy(playing = true, error = null)
            emit()
        }
    }

    fun pause(count: Boolean = false) {
        if (!live) return
        runningSince?.let { from ->
            durations[state.paragraphIndex] += (monotonic() - from).coerceAtLeast(0)
            if (count) pauses++
        }
        runningSince = null
        state = state.copy(playing = false)
        emit()
    }

    fun completeParagraph() {
        if (state.saving || state.result != null || !live) return
        pause()
        val index = state.paragraphIndex
        if (durations[index] <= 0) {
            state = state.copy(error = "先开始本段阅读，再确认读完。")
            emit(); return
        }
        confirmed[index] = true
        if (index < plan.paragraphIndices.lastIndex) {
            state = state.copy(paragraphIndex = index + 1, error = null)
            emit()
            save()
        } else {
            state = state.copy(result = snapshot(), error = null)
            emit(); save()
        }
    }

    /** Persist the same final snapshot on retry; a new wall clock must not rewrite it. */
    fun save(onSaved: () -> Unit = {}) {
        if (state.saving || !live) return
        pause()
        val record = state.result ?: snapshot()
        if (record.paragraphs.none { it.activeMillis > 0 || it.completed }) { onSaved(); return }
        state = state.copy(saving = true, error = null)
        emit()
        scope.launch {
            try {
                repository.saveNaturalAttempt(record)
                if (live) {
                    state = state.copy(saving = false)
                    emit(); onSaved()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (live) { state = state.copy(saving = false, error = "自主阅读记录尚未保存，请重试。"); emit() }
            }
        }
    }

    fun savedState(): Bundle = Bundle().apply {
        val record = state.result ?: snapshot()
        putString("id", record.id); putLong("started", record.startedAt); putLong("finished", record.finishedAt)
        putString("plan", record.planKey); putString("parent", record.parentRunId)
        putLongArray("times", record.paragraphs.map { it.activeMillis }.toLongArray())
        putBooleanArray("confirmed", record.paragraphs.map { it.completed }.toBooleanArray())
        putBoolean("completed", record.completed); putInt("pauses", record.pauses)
        putInt("paragraph", state.paragraphIndex)
    }

    suspend fun restore(bundle: Bundle) {
        require(bundle.getString("plan") == plan.planKey && bundle.getString("parent") == parentRun.id)
        val times = requireNotNull(bundle.getLongArray("times"))
        val completed = requireNotNull(bundle.getBooleanArray("confirmed"))
        require(times.size == plan.paragraphIndices.size && completed.size == times.size)
        val candidate = NaturalReadingAttempt(requireNotNull(bundle.getString("id")), plan.articleId, plan.bodyHash,
            plan.planKey, parentRun.id, bundle.getLong("started"), bundle.getLong("finished"),
            plan.paragraphIndices.mapIndexed { index, paragraph -> NaturalParagraphTiming(paragraph, times[index], completed[index]) },
            bundle.getBoolean("completed"), bundle.getInt("pauses"), 0)
        val stored = repository.library.first().naturalAttempts.find { it.id == candidate.id }
        NaturalReadingMetrics.summarize(candidate, plan)
        stored?.let {
            NaturalReadingMetrics.summarize(it, plan)
            require(it.parentRunId == candidate.parentRunId && it.startedAt == candidate.startedAt)
        }
        val record = when {
            stored == null -> candidate
            stored.completed -> stored
            else -> candidate.copy(finishedAt = maxOf(candidate.finishedAt, stored.finishedAt),
                paragraphs = candidate.paragraphs.zip(stored.paragraphs).map { (a, b) ->
                    a.copy(activeMillis = maxOf(a.activeMillis, b.activeMillis), completed = a.completed || b.completed)
                }, pauses = maxOf(candidate.pauses, stored.pauses), revisits = maxOf(candidate.revisits, stored.revisits))
        }
        require(record.planKey == plan.planKey && record.bodyHash == plan.bodyHash && record.articleId == plan.articleId &&
            record.parentRunId == parentRun.id && record.startedAt >= parentRun.finishedAt)
        require(record.paragraphs.map { it.paragraph } == plan.paragraphIndices &&
            record.paragraphs.all { it.activeMillis >= 0 && (!it.completed || it.activeMillis > 0) })
        require(!record.completed || record.paragraphs.all { it.completed })
        NaturalReadingMetrics.summarize(record, plan)
        val savedIndex = bundle.getInt("paragraph")
        require(savedIndex in plan.paragraphIndices.indices)
        val index = if (record.completed) plan.paragraphIndices.lastIndex
            else record.paragraphs.indexOfFirst { !it.completed }.takeIf { it >= 0 } ?: savedIndex
        attemptId = record.id; startedAt = record.startedAt; pauses = record.pauses
        durations = record.paragraphs.map { it.activeMillis }.toLongArray()
        confirmed = record.paragraphs.map { it.completed }.toBooleanArray()
        state = state.copy(paragraphIndex = index, result = record.takeIf { it.completed }, playing = false,
            elapsedMillis = durations.sum(), paragraphMillis = durations[index])
        if (live) publish(state)
        if (record.completed && stored?.completed != true) save()
    }

    fun dispose() { runningSince = null; live = false }

    private fun snapshot(): NaturalReadingAttempt {
        val elapsed = durations.sum()
        return NaturalReadingAttempt(attemptId, plan.articleId, plan.bodyHash, plan.planKey, parentRun.id,
            startedAt, maxOf(wall(), startedAt + elapsed), plan.paragraphIndices.mapIndexed { index, paragraph ->
                NaturalParagraphTiming(paragraph, durations[index], confirmed[index])
            }, confirmed.all { it }, pauses, 0)
    }

    private fun emit() {
        state = state.copy(elapsedMillis = durations.sum(), paragraphMillis = durations[state.paragraphIndex])
        if (live) { publish(state); checkpoint() }
    }
}
