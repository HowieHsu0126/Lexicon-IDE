package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Local-only training preferences. No settings writer, API client or article text is involved. */
class LocalPersonalizationRepository(
    private val db: LexiconDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : PersonalizationRepository {
    private val dao = db.readingDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()
    private val recoveryDao = db.personalizationRecoveryDao()
    override val health: Flow<PersonalizationHealth> = recoveryDao.observeProfile().map { row ->
        PersonalizationStorage.health(row?.bytes)
    }.flowOn(Dispatchers.Default)
    override val state: Flow<PersonalizationProfile> = recoveryDao.observeProfile().map { row ->
        // A corrupt profile cannot enable suggestions or be silently overwritten by a mutation.
        runCatching { decode(row?.bytes) }.getOrElse { PersonalizationProfile(enabled = false) }
    }.flowOn(Dispatchers.Default)

    override suspend fun recoverCorrupt(expectedRowDigest: String): PersonalizationRecoveryReceipt = withContext(Dispatchers.IO) {
        lock.withLock { recoveryDao.recover(expectedRowDigest, "${PersonalizationStorage.BACKUP_PREFIX}${UUID.randomUUID()}") }
    }

    override suspend fun remember(granularity: SpeedGranularity, rate: Int, sentenceOnly: Boolean,
        expectedRevision: Long, paceManuallyAdjusted: Boolean): PersonalizationProfile {
        require(rate in SpeedPacing.MIN_GROUPS_PER_MINUTE..SpeedPacing.MAX_GROUPS_PER_MINUTE) { "训练节奏须在 30–180 意群/分钟之间" }
        return mutate(expectedRevision) { current, _ ->
            val changedPace = current.granularity != granularity || current.rateFor(granularity) != rate
            val binding = current.activePaceBinding.takeUnless { paceManuallyAdjusted || (current.enabled && changedPace) }
            val undoBindings = preservedUndoBindings(current).let { bindings ->
                if (paceManuallyAdjusted || (current.enabled && current.rateFor(granularity) != rate)) bindings - granularity else bindings
            }
            val next = if (!current.enabled) current.copy(activePaceBinding = binding, undoPaceBindings = undoBindings)
                else current.copy(granularity = granularity, rates = current.rates + (granularity to rate), sentenceOnly = sentenceOnly,
                    activePaceBinding = binding, undoPaceBindings = undoBindings,
                    holds = if (current.rateFor(granularity) != rate) current.holds.filterNot { it.granularity == granularity } else current.holds)
            if (next == current) current else next.copy(revision = nextRevision(current))
        }
    }

    override suspend fun setEnabled(enabled: Boolean, expectedRevision: Long): PersonalizationProfile =
        mutate(expectedRevision) { current, _ ->
            if (current.enabled == enabled) current else current.copy(revision = nextRevision(current), enabled = enabled)
        }

    override suspend fun invalidatePaceBinding(expectedRevision: Long): PersonalizationProfile =
        mutate(expectedRevision) { current, _ ->
            if (current.activePaceBinding == null) current
            else current.copy(revision = nextRevision(current), activePaceBinding = null,
                undoPaceBindings = preservedUndoBindings(current))
        }

    override suspend fun invalidatePaceUndo(granularity: SpeedGranularity, expectedRevision: Long): PersonalizationProfile =
        mutate(expectedRevision) { current, _ ->
            if (!current.undoPaceBindings.containsKey(granularity) && current.activePaceBinding?.granularity != granularity) current
            else current.copy(revision = nextRevision(current),
                undoPaceBindings = preservedUndoBindings(current) - granularity,
                activePaceBinding = current.activePaceBinding?.takeUnless { it.granularity == granularity })
        }

    override suspend fun apply(suggestion: PaceSuggestion, expectedRevision: Long): PersonalizationProfile =
        decide(suggestion, expectedRevision, apply = true)

    override suspend fun dismiss(suggestion: PaceSuggestion, expectedRevision: Long): PersonalizationProfile =
        decide(suggestion, expectedRevision, apply = false)

    private suspend fun decide(suggestion: PaceSuggestion, expectedRevision: Long, apply: Boolean): PersonalizationProfile =
        mutate(expectedRevision, includeRuns = true) { current, runs ->
            val previous = current.decisions.find { it.id == suggestion.id }
            if (previous != null) {
                if (previous.suggestion != suggestion) throw PersonalizationConflict("此建议与已记录的依据不一致")
                // Replayed decisions never reapply a dismissed or undone pace change.
                current
            } else {
                val expected = PersonalizationPolicy.suggest(current, runs)
                if (expected == null || expected != suggestion) throw PersonalizationConflict("建议或训练依据已更新，请重新查看")
                val revision = nextRevision(current)
                val now = clock().also { require(it >= 0) }
                val decision = PersonalizationDecision(id = suggestion.id, suggestion = suggestion,
                    status = if (apply) PersonalizationDecisionStatus.APPLIED else PersonalizationDecisionStatus.DISMISSED,
                    decidedAt = now, updatedAt = now, appliedRevision = if (apply) revision else null)
                current.copy(revision = revision,
                    rates = if (apply) current.rates + (suggestion.granularity to suggestion.toRate) else current.rates,
                    decisions = current.decisions + decision,
                    activePaceBinding = if (apply) AppliedPaceBinding(suggestion.id, revision, suggestion.granularity, suggestion.toRate)
                        else current.activePaceBinding,
                    undoPaceBindings = if (apply) preservedUndoBindings(current) +
                        (suggestion.granularity to AppliedPaceBinding(suggestion.id, revision, suggestion.granularity, suggestion.toRate))
                        else preservedUndoBindings(current),
                    holds = if (apply) current.holds else hold(current.holds, suggestion, now))
            }
        }

    override suspend fun undo(decisionId: String, expectedRevision: Long): PersonalizationProfile =
        mutate(expectedRevision) { current, _ ->
            val decision = current.decisions.find { it.id == decisionId }
                ?: throw PersonalizationConflict("找不到这次节奏调整")
            if (decision.status == PersonalizationDecisionStatus.UNDONE) current
            else {
                if (decision.status != PersonalizationDecisionStatus.APPLIED) throw PersonalizationConflict("这条建议尚未应用")
                val change = decision.suggestion
                if (PersonalizationHistory.undoableDecisions(current).none { it.id == decision.id })
                    throw PersonalizationConflict("这次调整已被后续选择取代，保留当前设置")
                val updated = decision.copy(status = PersonalizationDecisionStatus.UNDONE, updatedAt = clock().also { require(it >= 0) })
                current.copy(revision = nextRevision(current), rates = current.rates + (change.granularity to change.fromRate),
                    decisions = current.decisions.map { if (it.id == decision.id) updated else it },
                    activePaceBinding = current.activePaceBinding?.takeUnless { it.decisionId == decision.id },
                    undoPaceBindings = preservedUndoBindings(current) - change.granularity,
                    holds = hold(current.holds, change, updated.updatedAt))
            }
        }

    override suspend fun releaseHold(granularity: SpeedGranularity, expectedRevision: Long): PersonalizationProfile =
        mutate(expectedRevision) { current, _ ->
            val retained = current.holds.filterNot { it.granularity == granularity }
            if (retained.size == current.holds.size) current else current.copy(revision = nextRevision(current), holds = retained)
        }

    private fun hold(existing: List<PaceHold>, suggestion: PaceSuggestion, now: Long): List<PaceHold> {
        val direction = if (suggestion.toRate > suggestion.fromRate) 1 else -1
        return existing.filterNot { it.granularity == suggestion.granularity && it.rate == suggestion.fromRate && it.direction == direction } +
            PaceHold(suggestion.granularity, suggestion.fromRate, direction, now)
    }

    /** Preserve a proven legacy active origin before an unrelated preference change clears it. */
    private fun preservedUndoBindings(profile: PersonalizationProfile): Map<SpeedGranularity, AppliedPaceBinding> {
        val active = profile.activePaceBinding ?: return profile.undoPaceBindings
        if (profile.undoPaceBindings.containsKey(active.granularity) ||
            PersonalizationHistory.undoableDecisions(profile).none { it.id == active.decisionId }) return profile.undoPaceBindings
        return profile.undoPaceBindings + (active.granularity to active)
    }

    private suspend fun mutate(expectedRevision: Long, includeRuns: Boolean = false,
        change: (PersonalizationProfile, List<SpeedRun>) -> PersonalizationProfile): PersonalizationProfile = withContext(Dispatchers.IO) {
        lock.withLock {
            val row = dao.rawEntry(PROFILE_KEY)
            val current = decode(row?.bytes)
            if (expectedRevision < 0 || current.revision != expectedRevision) throw PersonalizationConflict("偏好已改变，请使用当前设置重试")
            val runRows = if (includeRuns) dao.personalizationRunRows() else null
            val runs = runRows.orEmpty().mapNotNull { runCatching { json.decodeFromString<SpeedRun>(it.data) }.getOrNull() }
            val next = change(current, runs)
            if (next == current) return@withLock current
            PersonalizationStorage.validate(next)
            if (!dao.compareAndSetPersonalization(row?.bytes?.let(PersonalizationStorage::text), PreferenceRow(PROFILE_KEY, json.encodeToString(next)), runRows))
                throw PersonalizationConflict("偏好或训练记录刚刚改变，请重新查看建议")
            next
        }
    }

    private fun decode(bytes: ByteArray?): PersonalizationProfile = try {
        PersonalizationStorage.decodeRaw(bytes)
    } catch (_: Exception) { throw PersonalizationConflict("本机个性化设置无法读取，原记录已保留") }

    private fun nextRevision(profile: PersonalizationProfile): Long {
        if (profile.revision == Long.MAX_VALUE) throw PersonalizationConflict("本机偏好版本已达到上限")
        return profile.revision + 1
    }

    companion object { const val PROFILE_KEY = "personalization:profile" }
}
