package dev.lexicon.ide.ui

import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** One playback timer; the UI observes block/stage changes, never a frame-by-frame timer. */
internal class SpeedReadingViewModel(
    private val repository: SpeedReadingRepository,
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val wall: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = SystemClock::elapsedRealtime,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val personalizationRepository: PersonalizationRepository? = null,
) : ViewModel() {
    private val mutable = MutableStateFlow(SpeedUiState())
    val state = mutable.asStateFlow()
    private val inspecting = MutableStateFlow<SpeedCorrectionInspection?>(null)
    val correctionInspection = inspecting.asStateFlow()
    private var inspectionGeneration = 0
    private val recap = MutableStateFlow<SpeedRecapInspection?>(null)
    val recapInspection = recap.asStateFlow()
    private var recapGeneration = 0
    val library = repository.library.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpeedLibrary())
    private var article: Article? = null
    private var body: ArticleBody? = null
    private var identity: String? = null
    private var entry: Int? = null
    private var preparing: Job? = null
    private var ticker: Job? = null
    private var generation = 0
    private var foreground = true
    private var runId = UUID.randomUUID().toString()
    private var startedAt = 0L
    private var runningSince: Long? = null
    private var visitMillis = 0L
    private var durations = LongArray(0)
    private var chunkVisits = IntArray(0)
    private var chunkPauses = IntArray(0)
    private var visitOpen = false
    private var interactionsKnown = false
    private var revisits = 0
    private var pauses = 0
    private var firstAttempt = true
    private var pacingStable: Boolean? = null
    private var paceManuallyAdjusted = false
    private val manuallyEditedRates = mutableSetOf<SpeedGranularity>()
    private var pendingPaceBindingInvalidation = false
    private var paceBinding: AppliedPaceBinding? = null
    // A block appearing for a moment is not evidence of following its scheduled exposure.
    private var pacedBlocks = BooleanArray(0)
    private var manualAdvance: Boolean? = null
    private var editorPlanKey: String? = null
    private var parentRunId: String? = null
    private var naturalController: NaturalReadingController? = null
    private var answered = linkedMapOf<String, SpeedAnswer>()
    private var restore = saved.get<Bundle>(STATE_KEY)
    private var setupRestore = saved.get<Bundle>(SETUP_STATE_KEY)
    private var setupDraft: SpeedSetupDraft? = null
    private var setupWriting = false
    private var setupWriterId = 0L
    private var afterSetupSave: (() -> Unit)? = null
    private var latestProfile: PersonalizationProfile? = null
    private var awaitingEntryDefaults = false
    private var configurationEpoch = 0L
    private var paceEpoch = 0L
    private val personalizationLock = Mutex()
    private var personalizationWrites = 0
    private var nextSetupRate: Pair<SpeedGranularity, Int>? = null
    private val pendingUndoInvalidations = mutableSetOf<SpeedGranularity>()
    private data class PersonalizationSnapshot(
        val profile: PersonalizationProfile, val assessment: PaceAssessment,
        val outcome: PersonalizationOutcome?, val runs: List<SpeedRun>, val outcomeDecision: PersonalizationDecision?,
        val granularity: SpeedGranularity,
    )

    init {
        personalizationRepository?.let { preferences ->
            mutable.update { it.copy(personalizationAvailable = true) }
            viewModelScope.launch {
                preferences.health.catch { e ->
                    if (e is CancellationException) throw e
                    mutable.update { it.copy(recoveryError = "训练偏好暂时无法检查，请稍后重试。") }
                }.collect { health ->
                    mutable.update { it.copy(profileHealth = health) }
                    if (health.status == PersonalizationHealthStatus.CORRUPT) {
                        latestProfile = null
                        mutable.update { it.copy(profile = PersonalizationProfile(enabled = false), suggestion = null,
                            lastApplied = null, paceOutcome = null, paceOutcomeDecision = null, paceAssessment = null, paceEvidenceRuns = emptyList(), recoveryReceipt = null) }
                    }
                }
            }
            viewModelScope.launch {
                combine(preferences.state, repository.library,
                    mutable.map { it.selectedGranularity }.distinctUntilChanged()) { profile, history, granularity ->
                    Triple(profile, history.runs, granularity)
                }.mapLatest { (profile, runs, granularity) ->
                        withContext(computationDispatcher) {
                            val decision = visibleUndoDecision(profile, granularity)
                                ?: PersonalizationHistory.decisions(profile).firstOrNull {
                                    it.suggestion.granularity == granularity && it.status != PersonalizationDecisionStatus.DISMISSED
                                }
                            PersonalizationSnapshot(profile, PersonalizationPolicy.assess(profile, runs),
                                decision?.let { PersonalizationOutcomes.summarize(it, runs) }, runs.toList(), decision, granularity)
                        }
                    }.catch { error ->
                        if (error is CancellationException) throw error
                        mutable.update { it.copy(personalizationError = "训练偏好暂时无法读取。") }
                    }.collect { (profile, assessment, outcome, runs, decision, granularity) ->
                        if (state.value.profileHealth?.status != PersonalizationHealthStatus.CORRUPT &&
                            (latestProfile == null || profile.revision >= latestProfile!!.revision)) {
                            publishProfile(profile)
                            applyEntryDefaults(profile)
                            mutable.update { current ->
                                current.copy(suggestion = assessment.suggestion, paceAssessment = assessment, paceEvidenceRuns = runs,
                                    paceOutcome = outcome.takeIf { current.selectedGranularity == granularity },
                                    paceOutcomeDecision = decision.takeIf { current.selectedGranularity == granularity })
                            }
                            refreshVisiblePersonalization()
                        }
                    }
            }
        }
    }

    fun bind(article: Article, body: ArticleBody?, aiAllowed: Boolean, hasKey: Boolean, entryToken: Int, initialParagraph: Int) {
        // Preserve this entry through a temporary source-loading gap.
        if (body == null && state.value.stage == SpeedStage.SETUP && setupDraft?.scope?.let {
                it.articleId == article.id && it.entry == entryToken } == true) {
            this.article = article; this.body = null
            mutable.update { it.copy(aiAllowed = aiAllowed, hasKey = hasKey) }
            return
        }
        val key = "${article.id}:${body?.contentHash}"
        if (identity != key || entry != entryToken) {
            pause(false); preparing?.cancel(); generation++
            naturalController?.dispose(); naturalController = null; parentRunId = null
            identity = key; entry = entryToken
            this.article = article; this.body = body
            setupWriterId++; setupWriting = false; afterSetupSave = null; setupDraft = null
            configurationEpoch++; nextSetupRate = null; paceManuallyAdjusted = false; paceBinding = null
            manuallyEditedRates.clear(); pendingPaceBindingInvalidation = false
            editorPlanKey = null
            val total = body?.let { ParagraphFocus.paragraphs(it.text).size }?.coerceAtLeast(1) ?: 1
            val previous = state.value
            mutable.value = SpeedUiState(startParagraph = initialParagraph.coerceIn(0, total - 1), aiAllowed = aiAllowed, hasKey = hasKey,
                profile = previous.profile, suggestion = previous.suggestion, lastApplied = previous.lastApplied, paceOutcome = previous.paceOutcome, paceOutcomeDecision = previous.paceOutcomeDecision,
                paceAssessment = previous.paceAssessment, paceEvidenceRuns = previous.paceEvidenceRuns,
                personalizationBusy = personalizationWrites > 0, personalizationAvailable = personalizationRepository != null,
                profileHealth = previous.profileHealth, recoveryReceipt = previous.recoveryReceipt, recoveryError = previous.recoveryError)
            val snapshot = restore
            awaitingEntryDefaults = true
            if (body != null && snapshot?.getString("article") == article.id && snapshot.getString("hash") == body.contentHash) {
                awaitingEntryDefaults = false
                restore = null
                restoreSnapshot(snapshot, body)
            } else if (body != null) {
                val scope = SpeedSetupScope(article.id, body.contentHash, entryToken)
                val retained = setupRestore?.let { SpeedSetupDraft.restore(it, scope, total) }
                setupDraft = retained ?: SpeedSetupDraft(scope, start = initialParagraph.coerceIn(0, total - 1))
                if (retained != null) {
                    paceManuallyAdjusted = setupRestore?.getBoolean("manual-pace") == true
                    restoreManualRateOrigins(requireNotNull(setupRestore), retained.selected)
                    pendingPaceBindingInvalidation = setupRestore?.getBoolean("invalidate-training-origin") == true
                }
                setupRestore = null
                renderSetup()
                latestProfile?.let(::applyEntryDefaults)
                checkpoint()
            } else latestProfile?.let(::applyEntryDefaults)
        }
        this.article = article; this.body = body
        mutable.update { it.copy(aiAllowed = aiAllowed, hasKey = hasKey) }
    }

    fun prepare(useAi: Boolean) {
        if (state.value.stage == SpeedStage.SETUP) {
            if (body == null || state.value.loading || state.value.saving || afterSetupSave != null) return
            persistSetup { prepareConfirmed(useAi, rememberedSetup = true) }
        } else prepareConfirmed(useAi)
    }

    private fun prepareConfirmed(useAi: Boolean, rememberedSetup: Boolean = false) {
        val a = article ?: return
        val b = body ?: return
        if (state.value.loading || state.value.saving || state.value.personalizationBusy || state.value.natural != null) return
        configurationEdited()
        pause(false)
        val before = state.value
        val token = ++generation
        mutable.update { it.copy(loading = true, error = null) }
        preparing = viewModelScope.launch {
            try {
                val plan = if (before.error != null && useAi) repository.retryPrepare(a, b, before.selectedGranularity,
                    before.startParagraph, before.paragraphCount, before.aiAllowed, true)
                else repository.prepare(a, b, before.selectedGranularity, before.startParagraph, before.paragraphCount, before.aiAllowed, useAi)
                if (token != generation) return@launch
                withContext(computationDispatcher) { SpeedChunker.validate(plan, b.text) }
                require(plan.articleId == a.id && plan.bodyHash == b.contentHash)
                val history = repository.library.first().runs
                var isFirstAttempt = true
                for (previous in history.filter { (it.completed || it.answers.isNotEmpty()) && it.bodyHash == b.contentHash }) {
                    val previousPlan = repository.cachedPlan(previous.planKey)
                    val exposed = if (previous.completed) previousPlan?.paragraphIndices else previousPlan?.questions
                        ?.filter { q -> previous.answers.any { it.questionId == q.id } }?.map { it.paragraph }
                    if (exposed == null || exposed.any { it in plan.paragraphIndices }) isFirstAttempt = false
                }
                if (token != generation) return@launch
                firstAttempt = isFirstAttempt
                paceBinding = latestProfile?.let { PersonalizationOutcomes.bindingForStart(it,
                    before.selectedGranularity, before.groupsPerMinute, paceManuallyAdjusted) }
                pacingStable = true; nextSetupRate = null
                parentRunId = null
                manualAdvance = false; pacedBlocks = BooleanArray(plan.chunks.size)
                editorPlanKey = null
                runId = UUID.randomUUID().toString(); startedAt = wall(); visitMillis = 0
                durations = LongArray(plan.chunks.size); pauses = 0; revisits = 0; answered.clear()
                chunkVisits = IntArray(plan.chunks.size); chunkPauses = IntArray(plan.chunks.size)
                visitOpen = false; interactionsKnown = true
                mutable.update { it.copy(stage = SpeedStage.READING, plan = plan, loading = false, chunkIndex = 0,
                    playing = false, answers = emptyList(), selectedAnswer = null, answerRevealed = false,
                    result = null, feedbackSaved = false, canContinueParagraph = false,
                    round = SpeedPacedRound.GUIDED, canContinueRound = false, natural = null) }
                checkpoint()
                saved.remove<Bundle>(SETUP_STATE_KEY); setupDraft = null
                if (!rememberedSetup) rememberCurrent()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == generation) mutable.update { it.copy(loading = false, error = safeError(e)) }
            }
        }
    }

    fun setGranularity(value: SpeedGranularity) {
        if (state.value.saving || state.value.stage != SpeedStage.SETUP || state.value.loading || value == state.value.selectedGranularity) return
        val current = setupDraft ?: return
        paceEpoch++; paceManuallyAdjusted = true; paceBinding = null
        pendingPaceBindingInvalidation = true
        configurationEdited()
        setupDraft = current.select(value)
        renderSetup(); checkpoint(); persistSetup()
    }
    fun setStart(value: Int) {
        if (state.value.saving || state.value.stage != SpeedStage.SETUP || state.value.loading) return
        val total = body?.let { ParagraphFocus.paragraphs(it.text).size }?.coerceAtLeast(1) ?: return
        val start = value.coerceIn(0, total - 1)
        val current = setupDraft ?: return
        val next = current.range(start, current.count.coerceAtMost(total - start))
        if (next == current) return
        configurationEdited(); setupDraft = next; renderSetup(); checkpoint()
    }
    fun setCount(value: Int) {
        if (state.value.saving || state.value.stage != SpeedStage.SETUP || state.value.loading) return
        val current = setupDraft ?: return
        val remaining = (body?.let { ParagraphFocus.paragraphs(it.text).size } ?: return) - current.start
        val next = current.range(current.start, value.coerceIn(1, remaining.coerceIn(1, 3)))
        if (next == current) return
        configurationEdited(); setupDraft = next; renderSetup(); checkpoint()
    }
    fun setSentenceOnly(value: Boolean) {
        if (state.value.saving) return
        if (state.value.stage == SpeedStage.SETUP) {
            if (state.value.loading) return
            val current = setupDraft ?: return
            val next = current.sentence(value)
            if (next == current) return
            configurationEdited(); setupDraft = next; renderSetup(); checkpoint(); persistSetup()
            return
        }
        configurationEdited(); pause(false); mutable.update { it.copy(sentenceOnly = value) }; checkpoint()
    }
    fun setSpeed(value: Int) {
        if (state.value.saving) return
        val speed = value.coerceIn(SpeedPacing.MIN_GROUPS_PER_MINUTE, SpeedPacing.MAX_GROUPS_PER_MINUTE)
        if (state.value.stage == SpeedStage.SETUP) {
            if (state.value.loading) return
            val current = setupDraft ?: return
            val next = current.changeRate(speed)
            if (next == current) return
            paceEpoch++; paceManuallyAdjusted = true; paceBinding = null
            manuallyEditedRates += state.value.selectedGranularity
            invalidateEditedPace(state.value.selectedGranularity)
            configurationEdited()
            if (nextSetupRate?.first == state.value.selectedGranularity) nextSetupRate = null
            setupDraft = next; renderSetup(); checkpoint(); persistSetup()
            return
        }
        if (speed != state.value.groupsPerMinute) {
            paceEpoch++; paceManuallyAdjusted = true; paceBinding = null
            manuallyEditedRates += state.value.selectedGranularity
            invalidateEditedPace(state.value.selectedGranularity)
        }
        configurationEdited()
        if (nextSetupRate?.first == state.value.selectedGranularity) nextSetupRate = null
        val playing = state.value.playing
        pause(false)
        if (state.value.stage in listOf(SpeedStage.READING, SpeedStage.QUESTION) && speed != state.value.groupsPerMinute &&
            (playing || durations.any { it > 0 })) pacingStable = false
        mutable.update { it.copy(groupsPerMinute = speed) }
        checkpoint()
        if (playing) play()
    }
    /** A committed slider edit invalidates only its provenance, even before training is prepared. */
    private fun invalidateEditedPace(granularity: SpeedGranularity) {
        val profile = latestProfile ?: return
        if (PersonalizationHistory.undoableDecisions(profile).none { it.suggestion.granularity == granularity }) return
        if (!pendingUndoInvalidations.add(granularity)) return
        mutable.update { it.copy(lastApplied = it.lastApplied?.takeUnless { d -> d.suggestion.granularity == granularity }) }
        writePersonalization(onUpdated = { updated ->
            pendingUndoInvalidations.remove(granularity)
            publishProfile(updated)
        }) { preferences, current ->
            preferences.invalidatePaceUndo(granularity, current.revision)
        }
    }

    fun togglePlay() { if (state.value.playing) pause(true) else play() }
    fun setForeground(value: Boolean) { foreground = value; if (!value) pause(false) }

    fun recoverProfile() {
        val preferences = personalizationRepository ?: return
        val health = state.value.profileHealth ?: return
        val digest = health.rowDigest ?: return
        if (health.status != PersonalizationHealthStatus.CORRUPT || personalizationWrites > 0 ||
            state.value.loading || state.value.saving || state.value.playing) return
        personalizationWrites++
        mutable.update { it.copy(personalizationBusy = true, recoveryError = null, recoveryReceipt = null) }
        viewModelScope.launch {
            try {
                val receipt = personalizationLock.withLock { preferences.recoverCorrupt(digest) }
                require(receipt.backupExists && receipt.originalDigest == digest && !receipt.profile.enabled)
                latestProfile = null
                publishProfile(receipt.profile)
                mutable.update { it.copy(recoveryReceipt = receipt, suggestion = null, lastApplied = null, paceOutcome = null, paceOutcomeDecision = null, paceAssessment = null, paceEvidenceRuns = emptyList(),
                    personalizationError = null) }
                paceBinding = null; nextSetupRate = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(recoveryError = "偏好尚未恢复；原档案保留，请检查后重试。") } }
            finally {
                personalizationWrites--
                mutable.update { it.copy(personalizationBusy = personalizationWrites > 0) }
            }
        }
    }

    fun setRememberTraining(enabled: Boolean) {
        if (state.value.saving) return
        if (state.value.stage == SpeedStage.SETUP) {
            if (state.value.loading || state.value.profileHealth?.status == PersonalizationHealthStatus.CORRUPT) return
            val current = setupDraft ?: return
            val next = current.remember(enabled, latestProfile?.enabled ?: state.value.profile.enabled)
            if (next == current) {
                if (current.memoryDirty && state.value.personalizationError != null && personalizationWrites == 0)
                    persistSetup()
                return
            }
            setupDraft = next; renderSetup(); checkpoint(); persistSetup()
            return
        }
        if (personalizationWrites > 0) return
        writePersonalization { preferences, profile -> preferences.setEnabled(enabled, profile.revision) }
    }
    fun retrySetupSettings() {
        val current = state.value
        if (current.stage != SpeedStage.SETUP || current.personalizationError == null || current.loading ||
            current.saving || current.personalizationBusy || current.profileHealth?.status == PersonalizationHealthStatus.CORRUPT) return
        persistSetup()
    }

    fun applyPaceSuggestion(id: String) {
        if (state.value.saving || personalizationWrites > 0) return
        val suggestion = visibleSuggestion(id) ?: return
        writePersonalization(expectedPace = paceEpoch, onUpdated = { updated ->
            pendingUndoInvalidations.remove(suggestion.granularity)
            publishProfile(updated)
            mutable.update { state -> state.copy(suggestion = null) }
            updateNextRate(suggestion.granularity, suggestion.toRate)
            paceManuallyAdjusted = false
            manuallyEditedRates.remove(suggestion.granularity)
        }) { preferences, profile ->
            preferences.apply(suggestion, profile.revision)
        }
    }
    fun dismissPaceSuggestion(id: String) {
        if (state.value.saving || personalizationWrites > 0) return
        val suggestion = visibleSuggestion(id) ?: return
        writePersonalization(expectedPace = paceEpoch, onUpdated = { mutable.update { state -> state.copy(suggestion = null) } }) { preferences, profile ->
            preferences.dismiss(suggestion, profile.revision)
        }
    }
    fun undoPaceSuggestion(id: String) {
        if (state.value.saving || personalizationWrites > 0) return
        val current = state.value
        val decision = current.lastApplied?.takeIf { it.id == id && it.suggestion.granularity == current.selectedGranularity } ?: return
        if (decision.suggestion.granularity in pendingUndoInvalidations ||
            visibleUndoDecision(current.profile, current.selectedGranularity)?.id != id) return
        writePersonalization(expectedPace = paceEpoch, onUpdated = {
            mutable.update { state -> state.copy(suggestion = null) }
            updateNextRate(decision.suggestion.granularity, decision.suggestion.fromRate)
        }) { preferences, profile ->
            preferences.undo(id, profile.revision)
        }
    }

    /** A callback from a previous configuration cannot change another granularity's settings. */
    private fun visibleSuggestion(id: String): PaceSuggestion? {
        val current = state.value
        return current.suggestion?.takeIf {
            it.id == id && current.profile.enabled && it.granularity == current.selectedGranularity &&
                it.fromRate == current.groupsPerMinute && current.profile.granularity == current.selectedGranularity &&
                current.profile.rateFor() == current.groupsPerMinute
        }
    }

    fun releasePaceHold() {
        if (state.value.saving || personalizationWrites > 0) return
        val granularity = state.value.selectedGranularity
        writePersonalization { preferences, profile -> preferences.releaseHold(granularity, profile.revision) }
    }

    private fun configurationEdited() { configurationEpoch++; awaitingEntryDefaults = false }

    private fun seedSetupFromCurrent() {
        val owner = article ?: return
        val source = body ?: return
        val token = entry ?: return
        val current = state.value
        val total = ParagraphFocus.paragraphs(source.text).size.coerceAtLeast(1)
        val start = current.startParagraph.coerceIn(0, total - 1)
        val defaults = latestProfile?.takeIf { it.enabled }
        setupDraft = SpeedSetupDraft(SpeedSetupScope(owner.id, source.contentHash, token),
            selected = current.selectedGranularity,
            rates = SpeedGranularity.entries.associateWith { defaults?.rateFor(it) ?: 60 } +
                (current.selectedGranularity to current.groupsPerMinute), sentenceOnly = current.sentenceOnly,
            start = start, count = current.paragraphCount.coerceIn(1, minOf(3, total - start)),
            epoch = 1, rateEpochs = mapOf(current.selectedGranularity to 1L), selectionEpoch = 1,
            selectionDirty = true, sentenceEpoch = 1)
        renderSetup()
    }

    private fun renderSetup() {
        if (state.value.stage != SpeedStage.SETUP) return
        setupDraft?.let { draft -> mutable.update(draft::render) }
        refreshVisiblePersonalization()
    }

    /** One bounded draft drain shares the existing writer/CAS with explicit personalization actions. */
    private fun persistSetup(afterSaved: (() -> Unit)? = null) {
        if (afterSaved != null) afterSetupSave = afterSaved
        val scope = setupDraft?.scope
        if (scope == null || personalizationRepository == null) {
            val next = afterSetupSave; afterSetupSave = null
            next?.invoke()
            return
        }
        if (setupWriting) return
        setupWriting = true
        val writer = ++setupWriterId
        val token = generation
        writePersonalization(onSettled = { succeeded ->
            if (writer == setupWriterId) {
                setupWriting = false
                if (!succeeded) {
                    afterSetupSave = null
                    checkpoint() // A failed save never consumes the draft or closes the editor.
                }
            }
        }) { preferences, initial ->
            var current = initial
            while (token == generation && state.value.stage == SpeedStage.SETUP && setupDraft?.scope == scope) {
                if (state.value.profileHealth?.status == PersonalizationHealthStatus.CORRUPT)
                    throw PersonalizationConflict("本机训练偏好无法读取；当前设置已保留。")
                if (pendingPaceBindingInvalidation) {
                    if (current.activePaceBinding != null) current = preferences.invalidatePaceBinding(current.revision)
                    require(current.activePaceBinding == null)
                    pendingPaceBindingInvalidation = false
                    publishProfile(current)
                    checkpoint()
                }
                setupDraft = requireNotNull(setupDraft).merge(current)
                renderSetup(); checkpoint()
                val change = nextSetupWrite(current) ?: break
                if (state.value.profileHealth?.status == PersonalizationHealthStatus.CORRUPT)
                    throw PersonalizationConflict("本机训练偏好无法读取；当前设置已保留。")
                current = when (change) {
                    is SpeedSetupWrite.Memory -> preferences.setEnabled(change.enabled, current.revision)
                    is SpeedSetupWrite.Choice -> preferences.remember(change.granularity, change.rate,
                        change.sentenceOnly, current.revision, change.manualPace)
                }
                when (change) {
                    is SpeedSetupWrite.Memory -> require(current.enabled == change.enabled)
                    is SpeedSetupWrite.Choice -> if (current.enabled) {
                        require(current.granularity == change.granularity && current.rateFor(change.granularity) == change.rate &&
                            current.sentenceOnly == change.sentenceOnly)
                    } else require(current.activePaceBinding == null && current.undoPaceBindings[change.granularity] == null)
                }
                // Publish the confirmed value before removing its optimistic toggle intent.
                // A newer pending memory epoch still wins in the draft rendered on top of this value.
                publishProfile(current)
                if (token == generation && setupDraft?.scope == scope) {
                    // Disabled profiles can invalidate provenance, but do not acknowledge saved rates.
                    if (change is SpeedSetupWrite.Memory || current.enabled)
                        setupDraft = setupDraft?.acknowledge(change)
                    checkpoint()
                }
            }
            if (token == generation && setupDraft?.scope == scope && !pendingPaceBindingInvalidation && nextSetupWrite(current) == null) {
                // A successful draft write may already have completed a failed fast-path invalidation.
                // Clear only origins proven absent from the confirmed profile, then retire its stale error.
                pendingUndoInvalidations.removeAll { granularity ->
                    current.undoPaceBindings[granularity] == null && current.activePaceBinding?.granularity != granularity
                }
                if (pendingUndoInvalidations.isEmpty()) mutable.update { it.copy(personalizationError = null) }
            }
            current
        }
    }

    private fun nextSetupWrite(profile: PersonalizationProfile): SpeedSetupWrite? = setupDraft?.let { draft ->
        when (val write = draft.nextWrite(profile, draft.selected in manuallyEditedRates)) {
            is SpeedSetupWrite.Choice -> write.copy(manualPace = write.granularity in manuallyEditedRates)
            else -> write
        }
    }

    /** A queued prepare/back action runs once, only after every relevant write has settled successfully. */
    private fun continueSetupIfReady() {
        if (afterSetupSave == null || setupWriting || personalizationWrites > 0 || state.value.stage != SpeedStage.SETUP) return
        if (state.value.personalizationError != null) {
            afterSetupSave = null
            return
        }
        val profile = latestProfile
        if (profile != null && (pendingPaceBindingInvalidation || nextSetupWrite(profile) != null)) {
            persistSetup()
            return
        }
        val action = afterSetupSave; afterSetupSave = null
        action?.invoke()
    }

    private fun applyEntryDefaults(profile: PersonalizationProfile) {
        if (identity == null || state.value.stage != SpeedStage.SETUP || state.value.loading) return
        setupDraft?.let { draft ->
            val firstProfile = awaitingEntryDefaults
            awaitingEntryDefaults = false
            setupDraft = draft.merge(profile)
            renderSetup(); checkpoint()
            if (firstProfile && (pendingPaceBindingInvalidation || nextSetupWrite(profile) != null)) persistSetup()
            return
        }
        if (!awaitingEntryDefaults) return
        awaitingEntryDefaults = false
        if (profile.enabled) mutable.update { it.copy(selectedGranularity = profile.granularity,
            groupsPerMinute = profile.rateFor(), sentenceOnly = profile.sentenceOnly) }
    }

    private fun publishProfile(profile: PersonalizationProfile) {
        if (latestProfile?.let { it.revision > profile.revision } == true) return
        latestProfile = profile
        mutable.update {
            val last = visibleUndoDecision(profile, it.selectedGranularity)
                ?.takeUnless { decision -> decision.suggestion.granularity in pendingUndoInvalidations }
            if (it.profile != profile) it.copy(profile = profile, lastApplied = last, suggestion = null,
                paceAssessment = null, paceOutcome = null, paceOutcomeDecision = null, paceEvidenceRuns = emptyList())
            else it.copy(profile = profile, lastApplied = last)
        }
    }

    private fun visibleUndoDecision(profile: PersonalizationProfile, granularity: SpeedGranularity): PersonalizationDecision? =
        PersonalizationHistory.undoableDecisions(profile).filter { it.suggestion.granularity == granularity }
            .maxWithOrNull(compareBy<PersonalizationDecision> { it.updatedAt }.thenBy { it.id })

    /** Update immediately when the draft changes; asynchronous evidence cannot keep old controls visible. */
    private fun refreshVisiblePersonalization() {
        mutable.update { current ->
            val granularity = current.selectedGranularity
            current.copy(lastApplied = visibleUndoDecision(current.profile, granularity)
                ?.takeUnless { granularity in pendingUndoInvalidations },
                paceOutcome = current.paceOutcome?.takeIf { it.granularity == granularity },
                paceOutcomeDecision = current.paceOutcomeDecision?.takeIf { it.suggestion.granularity == granularity })
        }
    }

    private fun updateNextRate(granularity: SpeedGranularity, rate: Int) {
        configurationEdited()
        if (state.value.stage == SpeedStage.SETUP && setupDraft != null) {
            setupDraft = setupDraft?.decidedRate(granularity, rate)
            renderSetup()
        } else if (state.value.stage == SpeedStage.SETUP && state.value.selectedGranularity == granularity)
            mutable.update { it.copy(groupsPerMinute = rate) }
        else nextSetupRate = granularity to rate
        checkpoint()
    }

    /** Automatic remembering and explicit decisions share one lock; explicit double taps are ignored. */
    private fun writePersonalization(
        expectedConfiguration: Long? = null,
        expectedPace: Long? = null,
        onUpdated: (PersonalizationProfile) -> Unit = {},
        onFinished: () -> Unit = {},
        onSettled: (Boolean) -> Unit = {},
        change: suspend (PersonalizationRepository, PersonalizationProfile) -> PersonalizationProfile,
    ) {
        val preferences = personalizationRepository ?: run { onSettled(true); onFinished(); return }
        val token = generation
        personalizationWrites++
        mutable.update { it.copy(personalizationBusy = true, personalizationError = null) }
        viewModelScope.launch {
            var succeeded = false
            try {
                val updated = personalizationLock.withLock {
                    val current = preferences.state.first()
                    if (token != generation || expectedConfiguration?.let { it != configurationEpoch } == true ||
                        expectedPace?.let { it != paceEpoch } == true) null
                    else change(preferences, current)
                }
                if (updated != null) {
                    succeeded = true
                    publishProfile(updated)
                    if (token == generation && expectedConfiguration?.let { it == configurationEpoch } != false &&
                        expectedPace?.let { it == paceEpoch } != false) onUpdated(updated)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == generation) mutable.update { it.copy(personalizationError =
                    if (e is PersonalizationConflict) e.message ?: "训练偏好已改变，请重试。" else "训练偏好尚未保存，请重试。") }
            } finally {
                personalizationWrites--
                mutable.update { it.copy(personalizationBusy = personalizationWrites > 0) }
                if (token == generation) onFinished()
                onSettled(succeeded && token == generation)
                continueSetupIfReady()
            }
        }
    }

    private fun rememberCurrent(onFinished: () -> Unit = {}) {
        val s = state.value
        val rate = nextSetupRate?.takeIf { it.first == s.selectedGranularity }?.second ?: s.groupsPerMinute
        writePersonalization(expectedConfiguration = configurationEpoch, onFinished = onFinished, onUpdated = { updated ->
            if (PersonalizationHistory.undoableDecisions(updated).none { it.suggestion.granularity == s.selectedGranularity }) {
                pendingUndoInvalidations.remove(s.selectedGranularity)
                publishProfile(updated)
            }
        }) { preferences, profile ->
            val manualRate = s.selectedGranularity in manuallyEditedRates
            if (!profile.enabled && (!manualRate || profile.activePaceBinding == null && profile.undoPaceBindings[s.selectedGranularity] == null)) profile
            else preferences.remember(s.selectedGranularity, rate, s.sentenceOnly, profile.revision, manualRate)
        }
    }

    private fun play() {
        val s = state.value
        val plan = s.plan ?: return
        if (s.saving || s.stage != SpeedStage.READING || s.playing || !foreground || s.chunkIndex !in plan.chunks.indices ||
            s.segmentation != null || s.segmentationBusy || s.natural != null) return
        if (interactionsKnown && !visitOpen) {
            if (chunkVisits[s.chunkIndex] > 0) revisits++
            chunkVisits[s.chunkIndex]++
            visitOpen = true
        }
        runningSince = monotonic()
        mutable.update { it.copy(playing = true, error = null) }
        checkpoint()
        schedule()
    }
    private fun schedule() {
        ticker?.cancel()
        val s = state.value; val plan = s.plan ?: return
        val average = plan.chunks.sumOf { it.wordCount }.toDouble() / plan.chunks.size
        val duration = SpeedPacing.durationMillis(plan.chunks[s.chunkIndex], s.groupsPerMinute, average)
        ticker = viewModelScope.launch {
            delay((duration - visitMillis).coerceAtLeast(1))
            account(); ticker = null
            if (s.chunkIndex in pacedBlocks.indices && visitMillis >= duration) pacedBlocks[s.chunkIndex] = true
            advance(auto = true)
        }
    }
    private fun account() {
        val from = runningSince ?: return
        val now = monotonic()
        val delta = (now - from).coerceAtLeast(0)
        val index = state.value.chunkIndex
        if (index in durations.indices) { durations[index] += delta; visitMillis += delta }
        runningSince = null
    }
    fun pause(count: Boolean = false) {
        naturalController?.pause(count)
        val wasPlaying = state.value.playing
        account(); ticker?.cancel(); ticker = null
        if (count && wasPlaying) {
            pauses++
            if (interactionsKnown && state.value.chunkIndex in chunkPauses.indices) chunkPauses[state.value.chunkIndex]++
        }
        if (wasPlaying) mutable.update { it.copy(playing = false) }
        checkpoint()
    }
    fun next() {
        if (state.value.saving || state.value.stage != SpeedStage.READING || state.value.segmentation != null || state.value.segmentationBusy || state.value.natural != null) return
        pause(false); manualAdvance = true; advance(auto = false)
    }
    fun previous() { seek((state.value.chunkIndex - 1).coerceAtLeast(0)) }
    fun repeat() { seek(state.value.chunkIndex) }
    fun seek(index: Int) {
        val s = state.value; val plan = s.plan ?: return
        if (s.saving || s.stage != SpeedStage.READING || index !in plan.chunks.indices || s.segmentation != null || s.segmentationBusy || s.natural != null) return
        pause(false)
        if (index > s.chunkIndex) manualAdvance = true
        if (!interactionsKnown && index <= s.chunkIndex) revisits++
        visitMillis = 0; visitOpen = false
        mutable.update { it.copy(chunkIndex = index, feedbackSaved = false) }
        checkpoint()
    }
    private fun advance(auto: Boolean) {
        val s = state.value; val plan = s.plan ?: return
        if (s.stage != SpeedStage.READING) return
        val current = plan.chunks[s.chunkIndex]
        val next = plan.chunks.getOrNull(s.chunkIndex + 1)
        if (next == null || next.paragraph != current.paragraph) {
            val question = plan.questions.takeIf { s.round == SpeedPacedRound.GUIDED }?.find { it.paragraph == current.paragraph }
            if (question != null && question.id !in answered) {
                mutable.update { it.copy(stage = SpeedStage.QUESTION, playing = false, selectedAnswer = null, answerRevealed = false) }
                checkpoint(); return
            }
        }
        if (next == null) { finish(); return }
        visitMillis = 0; visitOpen = false
        mutable.update { it.copy(chunkIndex = s.chunkIndex + 1, playing = false, feedbackSaved = false) }
        checkpoint()
        if (auto) play()
    }

    fun answer(option: Int) {
        val s = state.value; val plan = s.plan ?: return
        if (s.saving || s.stage != SpeedStage.QUESTION || s.answerRevealed || s.round != SpeedPacedRound.GUIDED) return
        val question = plan.questions.find { it.paragraph == plan.chunks[s.chunkIndex].paragraph } ?: return
        if (option !in question.options.indices) return
        val answer = SpeedAnswer(question.id, option, option == question.answerIndex)
        answered[question.id] = answer
        mutable.update { it.copy(selectedAnswer = option, answerRevealed = true, answers = answered.values.toList()) }
        checkpoint()
        // Persist exposure at each answer, even when the user skipped playback. A later attempt
        // cannot regain first-attempt status merely by leaving an unfinished paragraph unit.
        val partial = snapshot(false) ?: return
        val token = generation
        viewModelScope.launch {
            try { repository.saveRun(partial) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (token == generation && state.value.stage != SpeedStage.RESULT)
                    mutable.update { it.copy(error = "答案记录待保存；完成训练或返回时会重试。") }
            }
        }
    }
    fun continueAfterQuestion() {
        val s = state.value; val plan = s.plan ?: return
        if (s.saving) return
        if (s.stage == SpeedStage.RESULT && s.error != null) { s.result?.let(::persistResult); return }
        if (s.stage != SpeedStage.QUESTION || !s.answerRevealed) return
        if (s.chunkIndex == plan.chunks.lastIndex) { finish(); return }
        visitMillis = 0; visitOpen = false
        mutable.update { it.copy(stage = SpeedStage.READING, chunkIndex = it.chunkIndex + 1,
            playing = false, selectedAnswer = null, answerRevealed = false) }
        checkpoint()
    }
    private fun finish() {
        pause(false)
        val run = snapshot(completed = durations.all { it > 0 }) ?: return
        mutable.update { it.copy(stage = SpeedStage.RESULT, playing = false, result = run, canContinueParagraph = false, canContinueRound = false) }
        checkpoint(); persistResult(run)
    }
    private fun persistResult(run: SpeedRun) {
        if (state.value.saving) return
        mutable.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                repository.saveRun(run)
                val stored = repository.library.first().runs.find { it.id == run.id } ?: run
                if (state.value.result?.id == run.id) {
                    mutable.update { it.copy(result = stored, saving = false, canContinueParagraph = false,
                        canContinueRound = stored.completed) }
                    rememberCurrent { mutable.update { it.copy(canContinueParagraph = hasNextParagraph(it.plan)) } }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (state.value.result?.id == run.id) mutable.update { it.copy(saving = false, error = safeError(e)) }
            }
        }
    }
    fun leave(onLeft: () -> Unit = {}) {
        pause(false)
        if (state.value.segmentationBusy) return
        if (state.value.segmentation != null) { closeSegmentation(); return }
        if (state.value.saving) return
        fun completeExit() {
            preparing?.cancel(); generation++
            saved.remove<Bundle>(STATE_KEY); restore = null; identity = null
            saved.remove<Bundle>(SETUP_STATE_KEY); setupRestore = null; setupDraft = null
            setupWriterId++; setupWriting = false; afterSetupSave = null
            onLeft()
        }
        if (state.value.stage == SpeedStage.SETUP) {
            if (state.value.loading) {
                preparing?.cancel(); preparing = null; generation++
                mutable.update { it.copy(loading = false) }
            }
            persistSetup(::completeExit)
            return
        }
        naturalController?.let { natural ->
            if (natural.state.saving) return
            natural.save { natural.dispose(); naturalController = null; completeExit() }
            return
        }
        val s = state.value
        val run = if (s.stage == SpeedStage.RESULT) s.result?.takeIf { s.error != null }
            else snapshot(false)?.takeIf { it.activeMillis > 0 || it.answers.isNotEmpty() }
        if (run == null) {
            mutable.update { it.copy(saving = true) }
            rememberCurrent { mutable.update { it.copy(saving = false) }; completeExit() }
            return
        }
        val token = generation
        mutable.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                repository.saveRun(run)
                if (token == generation) rememberCurrent { mutable.update { it.copy(saving = false) }; completeExit() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == generation) mutable.update { it.copy(saving = false, error = "训练记录尚未保存，请重试返回。") }
            }
        }
    }
    fun restart() {
        if (state.value.saving || state.value.natural?.saving == true ||
            state.value.natural?.let { it.result == null || it.error != null } == true ||
            (state.value.stage == SpeedStage.RESULT && state.value.error != null)) return
        resetSetup(state.value.startParagraph, state.value.paragraphCount)
    }
    fun nextParagraph() {
        val s = state.value
        val plan = s.plan ?: return
        if (s.stage != SpeedStage.RESULT || !s.canContinueParagraph || s.saving || s.error != null ||
            s.natural?.let { it.saving || it.result == null || it.error != null } == true) return
        resetSetup(plan.paragraphIndices.last() + 1, 1)
    }
    private fun hasNextParagraph(plan: SpeedPlan?): Boolean = plan != null && body?.let {
        plan.paragraphIndices.last() + 1 < ParagraphFocus.paragraphs(it.text).size
    } == true

    /** A new round reuses the frozen plan and never calls prepare/refine again. */
    fun nextRound() {
        val s = state.value
        val parent = s.result ?: return
        val plan = s.plan ?: return
        if (s.stage != SpeedStage.RESULT || !s.canContinueRound || s.saving || s.error != null || s.natural != null) return
        pause(false)
        if (s.round == SpeedPacedRound.GUIDED) {
            paceBinding = null
            parentRunId = parent.id
            runId = UUID.randomUUID().toString(); startedAt = maxOf(wall(), parent.finishedAt)
            durations = LongArray(plan.chunks.size); pacedBlocks = BooleanArray(plan.chunks.size)
            chunkVisits = IntArray(plan.chunks.size); chunkPauses = IntArray(plan.chunks.size)
            visitOpen = false; interactionsKnown = true
            manualAdvance = false; pacingStable = true; firstAttempt = false
            visitMillis = 0; pauses = 0; revisits = 0; answered.clear()
            mutable.update { it.copy(stage = SpeedStage.READING, round = SpeedPacedRound.FADED,
                chunkIndex = 0, playing = false, answers = emptyList(), selectedAnswer = null,
                answerRevealed = false, result = null, canContinueRound = false, canContinueParagraph = false,
                feedbackSaved = false, segmentationMessage = null, segmentationError = null) }
            checkpoint()
        } else {
            naturalController = createNaturalController(plan, parent)
            mutable.update { it.copy(natural = requireNotNull(naturalController).state, canContinueRound = false) }
            checkpoint()
        }
    }

    private fun createNaturalController(plan: SpeedPlan, parent: SpeedRun): NaturalReadingController {
        val token = generation
        return NaturalReadingController(plan, parent, repository, viewModelScope, wall, monotonic,
            publish = { value -> if (token == generation) mutable.update { it.copy(natural = value) } },
            checkpoint = { if (token == generation) checkpoint() })
    }

    fun naturalPlayPause() { naturalController?.toggle(foreground) }
    fun naturalCompleteParagraph() { naturalController?.completeParagraph() }
    fun naturalRetry() { naturalController?.save() }

    fun currentReaderAnchor(): ReaderAnchor? {
        val s = state.value; val plan = s.plan ?: return null
        val paragraph = s.natural?.let { plan.paragraphIndices.getOrNull(it.paragraphIndex) }
        val chunk = if (paragraph != null) plan.chunks.firstOrNull { it.paragraph == paragraph }
            else plan.chunks.getOrNull(s.chunkIndex)
        return chunk?.let { ReaderAnchor(plan.bodyHash, it.start, it.end) }
    }
    private fun resetSetup(start: Int, count: Int) {
        pause(false); preparing?.cancel(); generation++; configurationEdited()
        naturalController?.dispose(); naturalController = null; parentRunId = null
        durations = LongArray(0); visitMillis = 0; answered.clear(); pacingStable = null
        chunkVisits = IntArray(0); chunkPauses = IntArray(0); visitOpen = false; interactionsKnown = false
        pacedBlocks = BooleanArray(0); manualAdvance = null
        editorPlanKey = null; paceBinding = null; paceManuallyAdjusted = false
        manuallyEditedRates.clear(); pendingPaceBindingInvalidation = false
        val rate = nextSetupRate?.takeIf { it.first == state.value.selectedGranularity }?.second
            ?: latestProfile?.takeIf { it.enabled }?.rateFor(state.value.selectedGranularity) ?: state.value.groupsPerMinute
        nextSetupRate = null
        awaitingEntryDefaults = latestProfile == null && personalizationRepository != null
        mutable.update { it.copy(stage = SpeedStage.SETUP, plan = null, result = null, error = null, playing = false,
            loading = false, startParagraph = start, paragraphCount = count, chunkIndex = 0, groupsPerMinute = rate,
            answers = emptyList(), selectedAnswer = null, answerRevealed = false, feedbackSaved = false, canContinueParagraph = false,
            segmentation = null, segmentationBusy = false, segmentationError = null, segmentationMessage = null,
            round = SpeedPacedRound.GUIDED, canContinueRound = false, natural = null) }
        saved.remove<Bundle>(STATE_KEY)
        setupWriterId++; setupWriting = false; afterSetupSave = null
        seedSetupFromCurrent()
        checkpoint()
    }
    fun correction(kind: String, comment: String) {
        val s = state.value; val plan = s.plan ?: return
        if (s.saving) return
        val chunk = plan.chunks.getOrNull(s.chunkIndex) ?: return
        pause(false)
        val token = generation
        viewModelScope.launch {
            try {
                repository.saveCorrection(SpeedCorrection(UUID.randomUUID().toString(), plan.articleId, plan.bodyHash,
                    plan.granularity, chunk.start, chunk.end, kind, comment.take(500), wall(), plan.planKey))
                if (token == generation) mutable.update { it.copy(feedbackSaved = true, error = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) mutable.update { it.copy(error = safeError(e)) } }
        }
    }

    fun editSegmentation() {
        val s = state.value
        val plan = s.plan ?: return
        if (s.segmentationBusy || s.saving || s.stage !in listOf(SpeedStage.READING, SpeedStage.RESULT) || s.natural != null) return
        val current = plan.chunks.getOrNull(s.chunkIndex) ?: return
        pause(false)
        val token = generation
        mutable.update { it.copy(segmentationBusy = true, segmentationError = null, segmentationMessage = null) }
        viewModelScope.launch {
            try {
                val key = editorPlanKey ?: plan.planKey
                val editingPlan = if (key == plan.planKey) plan else requireNotNull(repository.cachedPlan(key))
                val index = editingPlan.chunks.indexOfFirst { it.paragraph == current.paragraph && it.sentence == current.sentence }
                val draft = requireNotNull(repository.sentenceForEditing(key, index))
                // Record preview independently: closing without playing/saving must not make
                // this paragraph eligible as unseen material on the next visit.
                if (s.stage == SpeedStage.READING) repository.markSentencePreviewed(key, draft.paragraph)
                if (token != generation) return@launch
                // The sentence preview exposes text ahead of the pacing exercise.
                if (s.stage == SpeedStage.READING) firstAttempt = false
                mutable.update { it.copy(segmentation = draft, segmentationBusy = false) }
                checkpoint()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == generation) mutable.update { it.copy(segmentationBusy = false, segmentationError = safeError(e)) }
            }
        }
    }

    fun saveSegmentation(breakOffsets: List<Int>) {
        val draft = state.value.segmentation ?: return
        changeSegmentation { repository.editSentence(draft.planKey, draft.paragraph, draft.sentence, breakOffsets) }
    }

    fun restoreSegmentation() {
        val draft = state.value.segmentation ?: return
        changeSegmentation { repository.restoreSegmentation(draft.planKey) }
    }

    private fun changeSegmentation(change: suspend () -> SpeedPlan) {
        if (state.value.saving || state.value.segmentationBusy) return
        val token = generation
        mutable.update { it.copy(segmentationBusy = true, segmentationError = null) }
        viewModelScope.launch {
            try {
                val updated = change()
                if (token != generation) return@launch
                editorPlanKey = updated.planKey
                mutable.update { it.copy(segmentation = null, segmentationBusy = false,
                    segmentationMessage = if (updated.basePlanKey != null) "个人切分已保存，下次训练生效" else "已恢复原切分，下次训练生效") }
                checkpoint()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == generation) mutable.update { it.copy(segmentationBusy = false, segmentationError = safeError(e)) }
            }
        }
    }

    fun closeSegmentation() {
        if (state.value.saving || state.value.segmentationBusy) return
        mutable.update { it.copy(segmentation = null, segmentationError = null) }
        checkpoint()
    }

    fun inspectRun(run: SpeedRun) {
        val token = ++recapGeneration
        recap.value = SpeedRecapInspection(run)
        viewModelScope.launch {
            try {
                val canonical = requireNotNull(repository.library.first().runs.filter { it.id == run.id }.distinct().singleOrNull())
                require(canonical == run) { "The selected evidence has changed" }
                val plan = requireNotNull(repository.cachedPlan(canonical.planKey))
                require(plan.articleId == canonical.articleId && plan.bodyHash == canonical.bodyHash)
                val comprehension = try { repository.comprehensionReview(canonical) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                val summary = withContext(computationDispatcher) { SpeedInteractionRecap.summarize(canonical, plan) }
                val snippets = mutableMapOf<Int, String>()
                for (item in summary.items) {
                    val chunk = plan.chunks[item.index]
                    val source = repository.sentenceForEditing(plan.planKey, item.index)
                    if (source != null && source.planKey == plan.planKey && source.articleId == canonical.articleId &&
                        source.bodyHash == canonical.bodyHash && chunk.start >= source.start && chunk.end <= source.end &&
                        source.text.length == source.end - source.start) {
                        snippets[item.index] = source.text.substring(chunk.start - source.start, chunk.end - source.start)
                    }
                }
                val latest = repository.library.first().runs.filter { it.id == canonical.id }.distinct().singleOrNull()
                require(latest == canonical) { "The selected evidence changed while it was loading" }
                if (token == recapGeneration) recap.value = SpeedRecapInspection(canonical, plan, summary, snippets,
                    loading = false, comprehension = comprehension)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (token == recapGeneration) recap.value = SpeedRecapInspection(run, loading = false,
                    error = "这条训练的片段暂时无法读取，已有记录仍保留。")
            }
        }
    }
    fun closeRecapInspection() { recapGeneration++; recap.value = null }

    fun inspectCorrection(correction: SpeedCorrection) {
        val token = ++inspectionGeneration
        inspecting.value = SpeedCorrectionInspection(correction)
        viewModelScope.launch {
            try {
                val plan = requireNotNull(repository.cachedPlan(correction.planKey))
                require(plan.articleId == correction.articleId && plan.bodyHash == correction.bodyHash)
                val index = plan.chunks.indexOfFirst { it.start <= correction.start && it.end > correction.start }
                val sentence = requireNotNull(repository.sentenceForEditing(plan.planKey, index))
                if (token == inspectionGeneration) inspecting.value = SpeedCorrectionInspection(correction, sentence, loading = false)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (token == inspectionGeneration) inspecting.value = SpeedCorrectionInspection(correction, loading = false,
                    error = "这条记录的原句缓存无法读取，训练记录仍保留。")
            }
        }
    }

    fun closeCorrectionInspection() { inspectionGeneration++; inspecting.value = null }
    private fun snapshot(completed: Boolean): SpeedRun? {
        val s = state.value; val plan = s.plan ?: return null
        val elapsed = durations.sum()
        return SpeedRun(runId, plan.articleId, plan.bodyHash, plan.granularity, plan.planKey, startedAt,
            maxOf(wall(), startedAt + elapsed), elapsed,
            plan.chunks.indices.filter { durations[it] > 0 }.sumOf { plan.chunks[it].wordCount }, durations.count { it > 0 },
            durations.toList(), revisits, pauses, answered.values.toList(), if (s.round == SpeedPacedRound.GUIDED) plan.questions.size else 0, completed,
            s.groupsPerMinute, plan.origin, firstAttempt, pacingStable,
            if (manualAdvance == null) null else completed && manualAdvance == false &&
                pacedBlocks.size == plan.chunks.size && pacedBlocks.all { it }, s.round, parentRunId, paceBinding,
            if (interactionsKnown) chunkVisits.indices.map { SpeedChunkInteraction(chunkVisits[it], chunkPauses[it], (chunkVisits[it] - 1).coerceAtLeast(0)) } else null)
    }
    private fun restoreManualRateOrigins(snapshot: Bundle, granularity: SpeedGranularity) {
        manuallyEditedRates.clear()
        if (snapshot.containsKey("manual-rate-granularities")) {
            snapshot.getStringArrayList("manual-rate-granularities").orEmpty().mapNotNullTo(manuallyEditedRates) {
                runCatching { SpeedGranularity.valueOf(it) }.getOrNull()
            }
        } else if (paceManuallyAdjusted) manuallyEditedRates += granularity // Conservative legacy checkpoint.
    }

    private fun checkpoint() {
        val s = state.value
        if (s.stage == SpeedStage.SETUP) {
            setupDraft?.let { saved[SETUP_STATE_KEY] = it.savedState(paceManuallyAdjusted).apply {
                putStringArrayList("manual-rate-granularities", ArrayList(manuallyEditedRates.map { level -> level.name }))
                putBoolean("invalidate-training-origin", pendingPaceBindingInvalidation)
            } }
            return
        }
        val plan = s.plan ?: return
        saved[STATE_KEY] = Bundle().apply {
            putString("article", plan.articleId); putString("hash", plan.bodyHash); putString("plan", plan.planKey)
            if (interactionsKnown) {
                putIntArray("chunk-visits", chunkVisits.copyOf()); putIntArray("chunk-pauses", chunkPauses.copyOf())
                putBoolean("visit-open", visitOpen)
            }
            putBoolean("pace-manual", paceManuallyAdjusted)
            putStringArrayList("manual-rate-granularities", ArrayList(manuallyEditedRates.map { it.name }))
            paceBinding?.let { binding ->
                putString("pace-decision", binding.decisionId); putLong("pace-revision", binding.profileRevision)
                putString("pace-granularity", binding.granularity.name); putInt("pace-rate", binding.rate)
            }
            putString("id", runId); putLong("started", startedAt); putString("stage", s.stage.name)
            putString("round", s.round.name); parentRunId?.let { putString("parent-run", it) }
            naturalController?.let { putBundle("natural", it.savedState()) }
            s.result?.let { putLong("finished", it.finishedAt) }
            putInt("index", s.chunkIndex); putLongArray("durations", durations.copyOf()); putLong("visit", visitMillis)
            putInt("revisits", revisits); putInt("pauses", pauses); putInt("speed", s.groupsPerMinute)
            putBoolean("sentence", s.sentenceOnly); putBoolean("first", firstAttempt)
            pacingStable?.let { putBoolean("pacing-stable", it) }
            manualAdvance?.let { putBoolean("manual-advance", it); putBooleanArray("paced-blocks", pacedBlocks.copyOf()) }
            editorPlanKey?.let { putString("editor-plan", it) }
            putBoolean("editing", s.segmentation != null)
            nextSetupRate?.let { (granularity, rate) -> putString("next-granularity", granularity.name); putInt("next-speed", rate) }
            putStringArrayList("questions", ArrayList(answered.keys)); putIntArray("answers", answered.values.map { it.selectedIndex }.toIntArray())
            putBoolean("revealed", s.answerRevealed)
        }
    }
    private fun restoreSnapshot(snapshot: Bundle, body: ArticleBody) {
        val token = generation
        mutable.update { it.copy(loading = true) }
        preparing = viewModelScope.launch {
            try {
                val plan = requireNotNull(repository.cachedPlan(snapshot.getString("plan").orEmpty()))
                require(plan.bodyHash == body.contentHash); SpeedChunker.validate(plan, body.text)
                if (token != generation) return@launch
                val restoredDurations = requireNotNull(snapshot.getLongArray("durations"))
                require(restoredDurations.size == plan.chunks.size && restoredDurations.all { it >= 0 })
                durations = restoredDurations.copyOf(); runId = requireNotNull(snapshot.getString("id")); startedAt = snapshot.getLong("started")
                val visits = snapshot.getIntArray("chunk-visits")
                val userPauses = snapshot.getIntArray("chunk-pauses")
                interactionsKnown = visits != null && userPauses != null && snapshot.containsKey("visit-open")
                if (interactionsKnown) {
                    require(visits!!.size == plan.chunks.size && userPauses!!.size == plan.chunks.size)
                    require(visits.all { it in 0..10_000 } && userPauses.all { it in 0..10_000 })
                    require(visits.indices.all { durations[it] == 0L || visits[it] > 0 })
                }
                chunkVisits = visits?.copyOf() ?: IntArray(plan.chunks.size)
                chunkPauses = userPauses?.copyOf() ?: IntArray(plan.chunks.size)
                visitOpen = interactionsKnown && snapshot.getBoolean("visit-open")
                visitMillis = snapshot.getLong("visit").coerceAtLeast(0); revisits = snapshot.getInt("revisits"); pauses = snapshot.getInt("pauses")
                firstAttempt = snapshot.getBoolean("first", true)
                val round = SpeedPacedRound.valueOf(snapshot.getString("round") ?: "GUIDED")
                parentRunId = snapshot.getString("parent-run")
                paceManuallyAdjusted = snapshot.getBoolean("pace-manual")
                restoreManualRateOrigins(snapshot, plan.granularity)
                paceBinding = snapshot.getString("pace-decision")?.takeUnless { paceManuallyAdjusted }?.let {
                    AppliedPaceBinding(it, snapshot.getLong("pace-revision"),
                        SpeedGranularity.valueOf(requireNotNull(snapshot.getString("pace-granularity"))), snapshot.getInt("pace-rate"))
                }
                pacingStable = if (snapshot.containsKey("pacing-stable")) snapshot.getBoolean("pacing-stable") else null
                val restoredPacedBlocks = snapshot.getBooleanArray("paced-blocks")
                val knownPacing = snapshot.containsKey("manual-advance") && restoredPacedBlocks?.size == plan.chunks.size
                manualAdvance = if (knownPacing) snapshot.getBoolean("manual-advance") else null
                pacedBlocks = restoredPacedBlocks?.takeIf { knownPacing }?.copyOf() ?: BooleanArray(plan.chunks.size)
                editorPlanKey = snapshot.getString("editor-plan")
                nextSetupRate = snapshot.getString("next-granularity")?.let { name ->
                    runCatching { SpeedGranularity.valueOf(name) }.getOrNull()?.let { granularity ->
                        snapshot.getInt("next-speed").takeIf { it in 30..180 }?.let { granularity to it }
                    }
                }
                val ids = snapshot.getStringArrayList("questions").orEmpty(); val indices = snapshot.getIntArray("answers") ?: intArrayOf()
                answered.clear()
                ids.forEachIndexed { i, id -> plan.questions.find { it.id == id }?.let { q ->
                    indices.getOrNull(i)?.takeIf { it in q.options.indices }?.let { choice -> answered[id] = SpeedAnswer(id, choice, choice == q.answerIndex) }
                } }
                val index = snapshot.getInt("index").coerceIn(plan.chunks.indices)
                val storedRun = repository.library.first().runs.find { it.id == runId }
                if (token != generation) return@launch
                if (storedRun != null) {
                    require(storedRun.articleId == plan.articleId && storedRun.bodyHash == plan.bodyHash &&
                        storedRun.planKey == plan.planKey && storedRun.startedAt == startedAt && storedRun.round == round &&
                        storedRun.parentRunId == parentRunId && storedRun.groupMillis.size == durations.size)
                    val oldTrace = storedRun.chunkInteractions
                    val databaseAhead = storedRun.completed || storedRun.groupMillis.indices.any { storedRun.groupMillis[it] > durations[it] } ||
                        storedRun.pauses > pauses || storedRun.revisits > revisits || storedRun.answers.any { answered[it.questionId] != it } ||
                        oldTrace?.indices?.any { !interactionsKnown || oldTrace[it].visits > chunkVisits[it] || oldTrace[it].pauses > chunkPauses[it] } == true
                    val replaceCounters = storedRun.completed || (!interactionsKnown && oldTrace != null)
                    durations = if (replaceCounters) storedRun.groupMillis.toLongArray() else
                        LongArray(durations.size) { maxOf(durations[it], storedRun.groupMillis[it]) }
                    if (oldTrace == null) interactionsKnown = false
                    else {
                        require(oldTrace.size == durations.size)
                        chunkVisits = IntArray(durations.size) { if (replaceCounters) oldTrace[it].visits else maxOf(chunkVisits[it], oldTrace[it].visits) }
                        chunkPauses = IntArray(durations.size) { if (replaceCounters) oldTrace[it].pauses else maxOf(chunkPauses[it], oldTrace[it].pauses) }
                        interactionsKnown = true
                    }
                    pauses = maxOf(pauses, storedRun.pauses, if (interactionsKnown) chunkPauses.sum() else 0)
                    revisits = maxOf(revisits, storedRun.revisits, if (interactionsKnown) chunkVisits.sumOf { (it - 1).coerceAtLeast(0) } else 0)
                    firstAttempt = firstAttempt && storedRun.firstAttempt
                    pacingStable = storedRun.pacingStable?.let { a -> pacingStable?.let { b -> a && b } }
                    paceBinding = storedRun.paceBinding?.takeIf { it == paceBinding }
                    storedRun.answers.forEach { locked ->
                        val question = requireNotNull(plan.questions.find { it.id == locked.questionId })
                        require(locked.selectedIndex in question.options.indices)
                        answered[locked.questionId] = locked.copy(correct = locked.selectedIndex == question.answerIndex)
                    }
                    if (databaseAhead) {
                        // A cumulative checkpoint cannot reveal which visit was active in the newer record.
                        visitMillis = 0; visitOpen = false; manualAdvance = null
                    }
                }
                val stage = if (storedRun?.completed == true) SpeedStage.RESULT
                    else SpeedStage.valueOf(snapshot.getString("stage") ?: "READING")
                val answer = plan.questions.find { it.paragraph == plan.chunks[index].paragraph }?.let { answered[it.id] }
                mutable.update { it.copy(plan = plan, stage = stage, round = round, loading = false, playing = false, chunkIndex = index,
                    selectedGranularity = plan.granularity, startParagraph = plan.paragraphIndices.first(), paragraphCount = plan.paragraphIndices.size,
                    groupsPerMinute = snapshot.getInt("speed", 60).coerceIn(30, 180), sentenceOnly = snapshot.getBoolean("sentence"),
                    answers = answered.values.toList(), selectedAnswer = answer?.selectedIndex, answerRevealed = answer != null) }
                if (stage == SpeedStage.RESULT) {
                    val stored = storedRun
                    val result = stored?.takeIf { it.completed } ?: snapshot(completed = durations.all { ms -> ms > 0 })?.let {
                        it.copy(finishedAt = snapshot.getLong("finished", it.finishedAt))
                    }
                    mutable.update { it.copy(result = result, canContinueParagraph = stored?.completed == true && hasNextParagraph(plan),
                        canContinueRound = stored?.completed == true) }
                    if (stored?.completed != true && result != null) persistResult(result)
                }
                snapshot.getBundle("natural")?.let { natural ->
                    val parent = requireNotNull(repository.library.first().runs.find { it.id == runId })
                    val controller = createNaturalController(plan, parent)
                    naturalController = controller
                    controller.restore(natural)
                    if (token == generation) mutable.update { it.copy(natural = controller.state, canContinueRound = false) }
                }
                if (snapshot.getBoolean("editing")) editSegmentation()
                checkpoint()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (token == generation) {
                    naturalController?.dispose(); naturalController = null; parentRunId = null
                    durations = LongArray(0); pacedBlocks = BooleanArray(0); answered.clear()
                    interactionsKnown = false; visitOpen = false
                    mutable.update { it.copy(loading = false, stage = SpeedStage.SETUP, plan = null, result = null,
                        natural = null, round = SpeedPacedRound.GUIDED, canContinueRound = false, canContinueParagraph = false,
                        error = "上次训练未能恢复，请重新准备。") }
                    saved.remove<Bundle>(STATE_KEY)
                    seedSetupFromCurrent(); checkpoint()
                }
            }
        }
    }
    private fun safeError(error: Exception) = when (error) {
        is dev.lexicon.data.ApiFailure -> error.message ?: "训练准备失败，可重试或使用离线分块。"
        is IllegalArgumentException -> error.message?.take(120) ?: "这组段落暂不适合训练，请减少段数。"
        else -> "操作未完成，请重试；也可使用离线分块。"
    }
    override fun onCleared() { ticker?.cancel(); naturalController?.dispose(); super.onCleared() }
    companion object {
        private const val STATE_KEY = "speed-training-state"
        private const val SETUP_STATE_KEY = "speed-setup-draft"
    }
}
