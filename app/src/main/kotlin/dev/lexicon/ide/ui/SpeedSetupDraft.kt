package dev.lexicon.ide.ui

import android.os.Bundle
import dev.lexicon.domain.PersonalizationProfile
import dev.lexicon.domain.SpeedGranularity
import dev.lexicon.domain.SpeedPacing

internal data class SpeedSetupScope(val articleId: String, val bodyHash: String, val entry: Int)

/** At most three rates and six editable fields; no plan, text, timer or evidence is stored here. */
internal data class SpeedSetupDraft(
    val scope: SpeedSetupScope,
    val selected: SpeedGranularity = SpeedGranularity.L2,
    val rates: Map<SpeedGranularity, Int> = SpeedGranularity.entries.associateWith { 60 },
    val sentenceOnly: Boolean = false,
    val start: Int = 0,
    val count: Int = 1,
    val epoch: Long = 0,
    val rateEpochs: Map<SpeedGranularity, Long> = emptyMap(),
    val dirtyRates: Set<SpeedGranularity> = emptySet(),
    val selectionEpoch: Long = 0,
    val selectionDirty: Boolean = false,
    val sentenceEpoch: Long = 0,
    val sentenceDirty: Boolean = false,
    val memoryChoice: Boolean? = null,
    val memoryEpoch: Long = 0,
    val memoryDirty: Boolean = false,
) {
    val rate: Int get() = rates.getValue(selected)
    private fun nextEpoch() = Math.addExact(epoch, 1)

    fun merge(profile: PersonalizationProfile): SpeedSetupDraft {
        if (!profile.enabled) return this
        return copy(selected = if (selectionEpoch == 0L) profile.granularity else selected,
            rates = rates + SpeedGranularity.entries.filter { (rateEpochs[it] ?: 0L) == 0L }.associateWith(profile::rateFor),
            sentenceOnly = if (sentenceEpoch == 0L) profile.sentenceOnly else sentenceOnly)
    }

    fun select(value: SpeedGranularity): SpeedSetupDraft {
        if (selected == value) return this
        val next = nextEpoch()
        return copy(selected = value, epoch = next, selectionEpoch = next, selectionDirty = true)
    }

    fun changeRate(value: Int): SpeedSetupDraft {
        val bounded = value.coerceIn(SpeedPacing.MIN_GROUPS_PER_MINUTE, SpeedPacing.MAX_GROUPS_PER_MINUTE)
        if (rate == bounded) return this
        val next = nextEpoch()
        // Editing the displayed rate also confirms which currently visible granularity it belongs to.
        return copy(rates = rates + (selected to bounded), rateEpochs = rateEpochs + (selected to next),
            dirtyRates = dirtyRates + selected, epoch = next, selectionEpoch = next, selectionDirty = true)
    }

    fun sentence(value: Boolean): SpeedSetupDraft {
        if (sentenceOnly == value) return this
        val next = nextEpoch()
        return copy(sentenceOnly = value, epoch = next, sentenceEpoch = next, sentenceDirty = true)
    }

    fun range(start: Int, count: Int): SpeedSetupDraft =
        if (this.start == start && this.count == count) this else copy(start = start, count = count, epoch = nextEpoch())

    fun remember(value: Boolean, confirmed: Boolean): SpeedSetupDraft {
        if ((if (memoryDirty) memoryChoice else confirmed) == value) return this
        val next = nextEpoch()
        return copy(memoryChoice = value, memoryEpoch = next, memoryDirty = true, epoch = next,
            // Enabling explicitly adopts the settings currently shown, not an old profile's defaults.
            selectionEpoch = if (value) next else selectionEpoch, selectionDirty = value || selectionDirty,
            rateEpochs = if (value) rateEpochs + (selected to next) else rateEpochs,
            dirtyRates = if (value) dirtyRates + selected else dirtyRates,
            sentenceEpoch = if (value) next else sentenceEpoch, sentenceDirty = value || sentenceDirty)
    }

    /** An explicit apply/undo is already persisted by its own repository action. */
    fun decidedRate(granularity: SpeedGranularity, value: Int): SpeedSetupDraft {
        val next = nextEpoch()
        return copy(rates = rates + (granularity to value), rateEpochs = rateEpochs + (granularity to next),
            dirtyRates = dirtyRates - granularity, epoch = next)
    }

    fun nextWrite(profile: PersonalizationProfile, manualPace: Boolean): SpeedSetupWrite? {
        if (memoryDirty) return SpeedSetupWrite.Memory(scope, memoryEpoch, requireNotNull(memoryChoice))
        if (!profile.enabled) {
            // Preserve the existing origin invalidation rule without saving disabled preferences.
            if (!manualPace || profile.activePaceBinding == null && profile.undoPaceBindings[selected] == null) return null
            return SpeedSetupWrite.Choice(scope, selected, rate, sentenceOnly,
                rateEpochs[selected] ?: 0L, selectionEpoch, sentenceEpoch, true)
        }
        // Save other edited granularities first; the final write keeps the selected granularity current.
        val target = SpeedGranularity.entries.firstOrNull { it != selected && it in dirtyRates }
            ?: selected.takeIf { it in dirtyRates || selectionDirty || sentenceDirty } ?: return null
        return SpeedSetupWrite.Choice(scope, target, rates.getValue(target), sentenceOnly,
            rateEpochs[target] ?: 0L, selectionEpoch.takeIf { target == selected }, sentenceEpoch, manualPace)
    }

    fun acknowledge(write: SpeedSetupWrite): SpeedSetupDraft {
        if (write.scope != scope) return this
        return when (write) {
            is SpeedSetupWrite.Memory -> if (memoryEpoch == write.epoch) copy(memoryDirty = false, memoryChoice = null) else this
            is SpeedSetupWrite.Choice -> copy(
                dirtyRates = if (rateEpochs[write.granularity] == write.rateEpoch) dirtyRates - write.granularity else dirtyRates,
                selectionDirty = selectionDirty && !(selected == write.granularity && selectionEpoch == write.selectionEpoch),
                sentenceDirty = sentenceDirty && sentenceEpoch != write.sentenceEpoch)
        }
    }

    fun render(state: SpeedUiState): SpeedUiState = state.copy(selectedGranularity = selected, groupsPerMinute = rate,
        sentenceOnly = sentenceOnly, startParagraph = start, paragraphCount = count, rememberTrainingChoice = memoryChoice.takeIf { memoryDirty })

    fun savedState(manualPace: Boolean): Bundle = Bundle().apply {
        putInt("schema", 1); putString("article", scope.articleId); putString("hash", scope.bodyHash); putInt("entry", scope.entry)
        putInt("selected", selected.ordinal); putIntArray("rates", SpeedGranularity.entries.map { rates.getValue(it) }.toIntArray())
        putBoolean("sentence", sentenceOnly); putInt("start", start); putInt("count", count); putLong("epoch", epoch)
        putLongArray("rate-epochs", SpeedGranularity.entries.map { rateEpochs[it] ?: 0L }.toLongArray())
        putBooleanArray("dirty-rates", SpeedGranularity.entries.map { it in dirtyRates }.toBooleanArray())
        putLong("selection-epoch", selectionEpoch); putBoolean("selection-dirty", selectionDirty)
        putLong("sentence-epoch", sentenceEpoch); putBoolean("sentence-dirty", sentenceDirty)
        putInt("memory", when (memoryChoice) { null -> -1; false -> 0; true -> 1 })
        putLong("memory-epoch", memoryEpoch); putBoolean("memory-dirty", memoryDirty)
        putBoolean("manual-pace", manualPace)
    }

    companion object {
        fun restore(saved: Bundle, scope: SpeedSetupScope, paragraphs: Int): SpeedSetupDraft? = runCatching {
            require(saved.getInt("schema") == 1 && saved.getString("article") == scope.articleId &&
                saved.getString("hash") == scope.bodyHash && saved.getInt("entry", -1) == scope.entry)
            val selected = SpeedGranularity.entries[saved.getInt("selected", -1)]
            val rates = requireNotNull(saved.getIntArray("rates"))
            val rateEpochs = requireNotNull(saved.getLongArray("rate-epochs"))
            val dirty = requireNotNull(saved.getBooleanArray("dirty-rates"))
            require(rates.size == 3 && rateEpochs.size == 3 && dirty.size == 3)
            require(rates.all { it in SpeedPacing.MIN_GROUPS_PER_MINUTE..SpeedPacing.MAX_GROUPS_PER_MINUTE })
            val start = saved.getInt("start", -1); val count = saved.getInt("count", -1)
            require(start in 0 until paragraphs && count in 1..minOf(3, paragraphs - start))
            val epoch = saved.getLong("epoch", -1)
            val selectionEpoch = saved.getLong("selection-epoch", -1)
            val sentenceEpoch = saved.getLong("sentence-epoch", -1)
            val memoryEpoch = saved.getLong("memory-epoch", -1)
            require(epoch in 0..1_000_000_000_000L && (rateEpochs.toList() + listOf(selectionEpoch, sentenceEpoch, memoryEpoch)).all { it in 0..epoch })
            val memory = saved.getInt("memory", -2).also { require(it in -1..1) }
            require(!saved.getBoolean("memory-dirty") || memory != -1)
            SpeedSetupDraft(scope, selected, SpeedGranularity.entries.associateWith { rates[it.ordinal] }, saved.getBoolean("sentence"),
                start, count, epoch, SpeedGranularity.entries.associateWith { rateEpochs[it.ordinal] },
                SpeedGranularity.entries.filterTo(mutableSetOf()) { dirty[it.ordinal] }, selectionEpoch, saved.getBoolean("selection-dirty"),
                sentenceEpoch, saved.getBoolean("sentence-dirty"), when (memory) { 0 -> false; 1 -> true; else -> null },
                memoryEpoch, saved.getBoolean("memory-dirty"))
        }.getOrNull()
    }
}

internal sealed interface SpeedSetupWrite {
    val scope: SpeedSetupScope
    data class Memory(override val scope: SpeedSetupScope, val epoch: Long, val enabled: Boolean) : SpeedSetupWrite
    data class Choice(override val scope: SpeedSetupScope, val granularity: SpeedGranularity, val rate: Int,
        val sentenceOnly: Boolean, val rateEpoch: Long, val selectionEpoch: Long?, val sentenceEpoch: Long,
        val manualPace: Boolean) : SpeedSetupWrite
}
