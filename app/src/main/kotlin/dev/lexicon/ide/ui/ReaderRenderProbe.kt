package dev.lexicon.ide.ui

import androidx.compose.runtime.staticCompositionLocalOf
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray

/** Optional, content-free source counters for instrumented reading performance checks. */
internal class ReaderRenderProbe {
    private val counts = AtomicIntegerArray(ReaderRenderEvent.entries.size)
    private val largestGradient = AtomicInteger()

    fun record(event: ReaderRenderEvent) { counts.incrementAndGet(event.ordinal) }
    fun count(event: ReaderRenderEvent): Int = counts.get(event.ordinal)
    fun observeGradientStops(count: Int) { largestGradient.accumulateAndGet(count, ::maxOf) }
    val maxGradientStops: Int get() = largestGradient.get()
    fun snapshot(): Map<String, Int> = buildMap {
        ReaderRenderEvent.entries.forEach { put(it.name, count(it)) }
        put("MAX_GRADIENT_STOPS", maxGradientStops)
    }
}

internal enum class ReaderRenderEvent {
    READER_COMPOSITION, TEXT_COMPOSITION, TEXT_LAYOUT_CALLBACK, TEXT_LAYOUT_UPDATE,
    TEXT_DRAW, VIEWPORT_CALLBACK, FOCUS_CANDIDATE, BRUSH_BUILD, TIMER_TICK, TIMER_COMPOSITION,
}

/** Null in ordinary app use. Recording never writes Compose state or stores learning content. */
internal val LocalReaderRenderProbe = staticCompositionLocalOf<ReaderRenderProbe?> { null }
