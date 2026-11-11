package dev.lexicon.data

import dev.lexicon.domain.*

/** Only recorded interactions can be persisted; elapsed dwell never manufactures visits. */
internal object SpeedInteractionStorage {
    fun validate(run: SpeedRun, plan: SpeedPlan) {
        val trace = run.chunkInteractions ?: return
        require(trace.size == plan.chunks.size && run.groupMillis.size == plan.chunks.size)
        require(trace.all { it.visits in 0..10_000 && it.pauses in 0..10_000 && it.revisits in 0..10_000 &&
            it.revisits == (it.visits - 1).coerceAtLeast(0) && (it.visits > 0 || it.pauses == 0) }) {
            "意群访问与回读记录不一致"
        }
        require(trace.indices.all { run.groupMillis[it] == 0L || trace[it].visits > 0 }) {
            "意群计时缺少实际播放记录"
        }
        require(trace.sumOf { it.pauses.toLong() } <= run.pauses.toLong())
    }

    fun merged(previous: SpeedRun?, incoming: SpeedRun): List<SpeedChunkInteraction>? {
        if (previous == null) return incoming.chunkInteractions
        val old = previous.chunkInteractions ?: return null
        val next = requireNotNull(incoming.chunkInteractions) { "已记录的意群访问不能删除" }
        require(old.size == next.size && old.zip(next).all { (before, after) ->
            after.visits >= before.visits && after.pauses >= before.pauses && after.revisits >= before.revisits
        }) { "已记录的意群访问次数不能回退" }
        return next
    }
}
