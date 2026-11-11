package dev.lexicon.ide.ui

import androidx.compose.runtime.saveable.listSaver
import dev.lexicon.domain.ReadingSession
import dev.lexicon.domain.ReadingSessionMeter
import dev.lexicon.domain.StudyMode

/**
 * Main-thread session accounting. Ticks deliberately use ordinary fields, so reading time does
 * not invalidate the reader's composition. Query a snapshot when opening tools or saving a record.
 * The caller owns the lifecycle/body/panel gate and invokes [recordSecond] only while reading.
 */
internal class ReadingSessionTracker(
    val startedAt: Long,
    initialAssistanceCount: Int = 0,
) {
    var activeMillis: Long = 0L
        private set
    var assistanceCount: Int = 0
        private set
    private var lastAssistanceCount: Int = initialAssistanceCount.coerceAtLeast(0)
    private var dwell = IntArray(0)

    /** A detached snapshot; callers cannot modify the running counters. */
    val dwellSeconds: List<Int> get() = dwell.toList()

    fun recordSecond(focused: Int, paragraphCount: Int) {
        if (paragraphCount <= 0) return
        activeMillis = if (activeMillis <= Long.MAX_VALUE - 1_000L) activeMillis + 1_000L else Long.MAX_VALUE
        if (dwell.size != paragraphCount) dwell = dwell.copyOf(paragraphCount)
        if (focused in dwell.indices && dwell[focused] < MAX_DWELL_SECONDS) dwell[focused]++
    }

    /** The ViewModel counter can restart after process recreation; accumulated usage is retained. */
    fun observeAssistanceCount(current: Int) {
        val value = current.coerceAtLeast(0)
        val added = (value - lastAssistanceCount).coerceAtLeast(0)
        assistanceCount = (assistanceCount.toLong() + added).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        lastAssistanceCount = value
    }

    fun canComplete(paragraphWords: List<Int>, mode: StudyMode, skimParagraphs: Set<Int>): Boolean =
        ReadingSessionMeter.canComplete(paragraphWords, dwell.asList(), activeMillis, mode, skimParagraphs)

    fun snapshot(
        id: String,
        articleId: String,
        mode: StudyMode,
        topic: String?,
        paragraphWords: List<Int>,
        completed: Boolean,
        level: String,
        now: Long = System.currentTimeMillis(),
    ): ReadingSession {
        val seconds = dwell.asList()
        return ReadingSession(
            id = id, articleId = articleId, mode = mode, topic = topic,
            startedAt = startedAt, finishedAt = now,
            activeMillis = activeMillis.coerceAtMost((now - startedAt).coerceAtLeast(0L)),
            wordsRead = ReadingSessionMeter.estimatedWords(paragraphWords, seconds),
            paragraphsRead = ReadingSessionMeter.visited(paragraphWords, seconds).size,
            totalParagraphs = paragraphWords.size, assistanceCount = assistanceCount,
            completed = completed, level = level,
        )
    }

    companion object {
        // ReadingSessionMeter multiplies each dwell count by eight to estimate words.
        private const val MAX_DWELL_SECONDS = Int.MAX_VALUE / 8
        private const val STATE_VERSION = 1L

        /** Contains only counters and a timestamp, never article text or source metadata. */
        val Saver = listSaver<ReadingSessionTracker, Long>(
            save = { tracker -> buildList {
                add(STATE_VERSION); add(tracker.startedAt); add(tracker.activeMillis)
                add(tracker.assistanceCount.toLong()); add(tracker.lastAssistanceCount.toLong())
                tracker.dwell.forEach { add(it.toLong()) }
            } },
            restore = { values ->
                if (values.size < 5 || values[0] != STATE_VERSION) null
                else ReadingSessionTracker(values[1], values[4].coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()).apply {
                    activeMillis = values[2].coerceAtLeast(0L)
                    assistanceCount = values[3].coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                    dwell = IntArray(values.size - 5) { index ->
                        values[index + 5].coerceIn(0L, MAX_DWELL_SECONDS.toLong()).toInt()
                    }
                }
            },
        )
    }
}
