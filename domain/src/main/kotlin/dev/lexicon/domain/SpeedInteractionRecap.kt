package dev.lexicon.domain

import kotlinx.serialization.Serializable

/**
 * A visit starts with playback after entering a chunk. Pausing/resuming that same visit does not
 * add another visit. Re-entering or explicitly repeating a previously played chunk does so only
 * when playback starts again. `pauses` counts user pauses, excluding lifecycle interruptions.
 */
@Serializable
data class SpeedChunkInteraction(val visits: Int, val pauses: Int, val revisits: Int)

enum class SpeedInteractionLimit {
    PLAYBACK_ONLY,
    MISSING_VISIT_DATA,
    INVALID_TRACE,
    PLAN_MISMATCH,
    VARIABLE_PACE,
    PARTIAL_RUN,
}

data class SpeedInteractionItem(
    val index: Int,
    val visits: Int,
    val pauses: Int,
    val revisits: Int,
    val activeMillis: Long,
    val plannedMillisPerVisit: Long?,
    val meanActiveMillis: Double,
    /** Mean active playback time / planned single-visit dwell; not a difficulty or ability score. */
    val normalizedMeanDwell: Double?,
)

data class SpeedInteractionSummary(
    val items: List<SpeedInteractionItem>,
    val observedChunks: Int,
    val limitations: Set<SpeedInteractionLimit>,
)

/** Selects explicit reader interactions instead of ranking naturally longer autoplay chunks. */
object SpeedInteractionRecap {
    private const val MAX_ACTIVE_MILLIS = 86_400_000L
    private const val MAX_INTERACTIONS_PER_CHUNK = 10_000

    fun summarize(run: SpeedRun, plan: SpeedPlan, limit: Int = 3): SpeedInteractionSummary {
        val limits = linkedSetOf(SpeedInteractionLimit.PLAYBACK_ONLY)
        if (!run.completed) limits += SpeedInteractionLimit.PARTIAL_RUN
        if (run.articleId != plan.articleId || run.bodyHash != plan.bodyHash || run.planKey != plan.planKey ||
            run.granularity != plan.granularity || run.planOrigin != plan.origin) {
            return SpeedInteractionSummary(emptyList(), 0, limits + SpeedInteractionLimit.PLAN_MISMATCH)
        }
        val interactions = run.chunkInteractions
            ?: return SpeedInteractionSummary(emptyList(), 0, limits + SpeedInteractionLimit.MISSING_VISIT_DATA)
        if (!validTrace(run, plan, interactions)) {
            return SpeedInteractionSummary(emptyList(), 0, limits + SpeedInteractionLimit.INVALID_TRACE)
        }
        val steady = run.pacingStable == true
        if (!steady) limits += SpeedInteractionLimit.VARIABLE_PACE
        val averageWords = plan.chunks.sumOf { it.wordCount }.toDouble() / plan.chunks.size
        val items = interactions.mapIndexedNotNull { index, interaction ->
            val active = run.groupMillis[index]
            // Timer lateness and longer planned blocks alone are not evidence of reader difficulty.
            if (active == 0L || interaction.visits == 0 || (interaction.pauses == 0 && interaction.revisits == 0)) return@mapIndexedNotNull null
            val expected = if (steady) SpeedPacing.durationMillis(plan.chunks[index], run.groupsPerMinute, averageWords) else null
            val mean = active.toDouble() / interaction.visits
            SpeedInteractionItem(index, interaction.visits, interaction.pauses, interaction.revisits, active,
                expected, mean, expected?.let { mean / it })
        }.sortedWith(compareByDescending<SpeedInteractionItem> { it.revisits }
            .thenByDescending { it.pauses }
            .thenByDescending { it.normalizedMeanDwell ?: 0.0 }
            .thenBy { it.index })
            .take(limit.coerceAtLeast(0))
        return SpeedInteractionSummary(items, interactions.count { it.visits > 0 }, limits)
    }

    private fun validTrace(run: SpeedRun, plan: SpeedPlan, interactions: List<SpeedChunkInteraction>): Boolean {
        if (plan.chunks.size !in 1..SpeedChunker.MAX_UNIT_CHARACTERS ||
            interactions.size != plan.chunks.size || run.groupMillis.size != plan.chunks.size) return false
        if (plan.chunks.any { it.wordCount <= 0 || it.start < 0 || it.end <= it.start || it.paragraph !in plan.paragraphIndices } ||
            plan.chunks.sumOf { it.wordCount.toLong() } !in 1..SpeedChunker.MAX_UNIT_CHARACTERS.toLong()) return false
        if (run.startedAt < 0 || run.finishedAt < run.startedAt || run.activeMillis !in 0..MAX_ACTIVE_MILLIS ||
            run.activeMillis > run.finishedAt - run.startedAt || run.pauses < 0 || run.revisits < 0 ||
            run.groupsPerMinute !in SpeedPacing.MIN_GROUPS_PER_MINUTE..SpeedPacing.MAX_GROUPS_PER_MINUTE) return false
        if (run.groupMillis.any { it !in 0..MAX_ACTIVE_MILLIS } || run.groupMillis.sum() != run.activeMillis ||
            run.groupsRead != run.groupMillis.count { it > 0 } ||
            run.wordsRead != plan.chunks.indices.filter { run.groupMillis[it] > 0 }.sumOf { plan.chunks[it].wordCount }) return false
        if (interactions.any { it.visits !in 0..MAX_INTERACTIONS_PER_CHUNK || it.pauses !in 0..MAX_INTERACTIONS_PER_CHUNK ||
                it.revisits != (it.visits - 1).coerceAtLeast(0) || (it.visits == 0 && it.pauses > 0) }) return false
        if (interactions.indices.any { run.groupMillis[it] > 0 && interactions[it].visits == 0 }) return false
        // Global pauses may also contain background interruptions from the existing aggregate.
        return interactions.sumOf { it.pauses.toLong() } <= run.pauses.toLong()
    }
}
