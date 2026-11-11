package dev.lexicon.ide.ui

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SpeedPaceBindingTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher
    private val stores = linkedMapOf<SpeedReadingViewModel, ViewModelStore>()

    @Before fun setup() {
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
    }
    @After fun teardown() {
        try { stores.values.forEach { it.clear() }; scheduler.runCurrent() }
        finally { stores.clear(); Dispatchers.resetMain() }
    }

    @Test fun manualRateRoundTripClearsPersistedBindingEvenWhenTheFinalNumberIsUnchanged() = runTest(dispatcher) {
        val preferences = Preferences(appliedProfile())
        val repository = Plans(preferences)
        val first = model(repository, preferences)
        bind(first); runCurrent()
        assertEquals(63, first.state.value.groupsPerMinute)
        first.setSpeed(80); first.setSpeed(63); first.leave(); runCurrent()
        assertEquals(63, preferences.state.value.rateFor())
        assertNull(preferences.state.value.activePaceBinding)
        assertTrue(preferences.remembered.last().manual)
        val next = model(repository, preferences)
        bind(next); runCurrent(); next.prepare(false); runCurrent()
        next.togglePlay(); runCurrent(); advanceTimeBy(100); next.leave(); runCurrent()
        assertNull(repository.submitted.last().paceBinding)
    }

    @Test fun disabledRememberingStillPersistsManualBindingInvalidation() = runTest(dispatcher) {
        val preferences = Preferences(appliedProfile())
        val repository = Plans(preferences)
        val vm = model(repository, preferences)
        bind(vm); runCurrent()
        vm.setRememberTraining(false); runCurrent()
        assertNotNull(preferences.state.value.activePaceBinding)
        vm.setSpeed(80); vm.setSpeed(63); vm.leave(); runCurrent()
        assertFalse(preferences.state.value.enabled)
        assertEquals(63, preferences.state.value.rateFor())
        assertNull(preferences.state.value.activePaceBinding)
        assertTrue(preferences.remembered.last().manual)
        assertFalse(preferences.remembered.last().enabled)
    }

    @Test fun manualGranularityRoundTripAlsoInvalidatesTheAppliedToken() = runTest(dispatcher) {
        val preferences = Preferences(appliedProfile())
        val repository = Plans(preferences)
        val vm = model(repository, preferences)
        bind(vm); runCurrent()
        vm.setGranularity(SpeedGranularity.L1); vm.setGranularity(SpeedGranularity.L2)
        assertEquals(63, vm.state.value.groupsPerMinute)
        vm.leave(); runCurrent()
        assertFalse("Only changing granularity must not mark a rate edit", preferences.remembered.last().manual)
        assertNull(preferences.state.value.activePaceBinding)
        assertNotNull(preferences.state.value.undoPaceBindings[SpeedGranularity.L2])
    }

    @Test fun disabledGranularityRoundTripRetainsUndoButCannotAttributeAReopenedRunToTheOldSuggestion() = runTest(dispatcher) {
        val preferences = Preferences(appliedProfile())
        val repository = Plans(preferences)
        val first = model(repository, preferences)
        bind(first); runCurrent()
        first.setRememberTraining(false); runCurrent()
        first.setGranularity(SpeedGranularity.L1); first.setGranularity(SpeedGranularity.L2)
        first.leave(); runCurrent()
        val retained = preferences.state.value.undoPaceBindings[SpeedGranularity.L2]
        assertNotNull(retained)
        assertNull(preferences.state.value.activePaceBinding)
        assertFalse(preferences.state.value.enabled)
        stores.remove(first)!!.clear(); runCurrent()
        val reopened = model(repository, preferences)
        bind(reopened); runCurrent()
        reopened.setRememberTraining(true); runCurrent()
        reopened.prepare(false); runCurrent()
        reopened.togglePlay(); runCurrent(); advanceTimeBy(120); reopened.leave(); runCurrent()
        assertNull(repository.submitted.last().paceBinding)
        assertNull(preferences.state.value.activePaceBinding)
    }

    @Test fun displayOnlyRememberingPreservesTheOriginalAppliedRevisionOnANewRun() = runTest(dispatcher) {
        val preferences = Preferences(appliedProfile())
        val original = requireNotNull(preferences.state.value.activePaceBinding)
        val repository = Plans(preferences)
        val vm = model(repository, preferences)
        bind(vm); runCurrent()
        vm.setSentenceOnly(true); vm.prepare(false); runCurrent()
        assertTrue(preferences.state.value.revision > original.profileRevision)
        assertEquals(original, preferences.state.value.activePaceBinding)
        vm.togglePlay(); runCurrent(); advanceTimeBy(120); vm.leave(); runCurrent()
        assertEquals(original, repository.submitted.last().paceBinding)
        assertEquals(original, repository.library.value.runs.last().paceBinding)
        assertFalse(preferences.remembered.any { it.manual })
    }

    @Test fun clearingAnExistingPartialBindingSurvivesFurtherSaveAndAnOlderCheckpoint() = runTest(dispatcher) {
        val preferences = Preferences(appliedProfile())
        val token = requireNotNull(preferences.state.value.activePaceBinding)
        val repository = Plans(preferences)
        val saved = SavedStateHandle()
        val first = model(repository, preferences, saved)
        bind(first); runCurrent(); first.prepare(false); runCurrent()
        first.togglePlay(); runCurrent(); advanceTimeBy(100); first.pause()
        val older = Bundle(requireNotNull(saved.get<Bundle>(STATE_KEY)))
        first.leave(); runCurrent()
        assertEquals(token, repository.submitted.last().paceBinding)
        stores.remove(first)!!.clear(); runCurrent()

        val second = model(repository, preferences, SavedStateHandle(mapOf(STATE_KEY to Bundle(older))))
        bind(second); runCurrent()
        second.setSpeed(80); second.setSpeed(63)
        second.togglePlay(); runCurrent(); advanceTimeBy(100); second.leave(); runCurrent()
        assertNull(repository.submitted.last().paceBinding)
        assertNull(repository.library.value.runs.single().paceBinding)
        stores.remove(second)!!.clear(); runCurrent()

        val restored = model(repository, preferences, SavedStateHandle(mapOf(STATE_KEY to Bundle(older))))
        bind(restored); runCurrent(); restored.togglePlay(); runCurrent(); advanceTimeBy(100_000); runCurrent()
        assertNotNull(restored.state.value.result)
        // The durable repository has already cleared the binding; old saved state cannot restore it.
        assertNull(restored.state.value.result!!.paceBinding)
        assertNull(repository.library.value.runs.single().paceBinding)
    }

    @Test fun aManualRateEditWhileApplyIsInFlightWinsAndClearsTheCommittedToken() = runTest(dispatcher) {
        val preferences = Preferences()
        val repository = Plans(preferences, withSuggestionEvidence = true)
        val vm = model(repository, preferences)
        bind(vm); runCurrent()
        val offer = requireNotNull(vm.state.value.suggestion)
        val gate = CompletableDeferred<Unit>(); preferences.applyGate = gate
        vm.applyPaceSuggestion(offer.id); runCurrent()
        vm.setSpeed(80); vm.setSpeed(60)
        gate.complete(Unit); runCurrent()
        assertEquals(60, vm.state.value.groupsPerMinute)
        vm.leave(); runCurrent()
        assertEquals(60, preferences.state.value.rateFor())
        assertNull(preferences.state.value.activePaceBinding)
        assertTrue(preferences.remembered.last().manual)
    }

    @Test fun displayAndRangeChangesDoNotCancelAnInFlightExplicitPaceApplication() = runTest(dispatcher) {
        val preferences = Preferences()
        val repository = Plans(preferences, withSuggestionEvidence = true)
        val vm = model(repository, preferences)
        bind(vm); runCurrent()
        val offer = requireNotNull(vm.state.value.suggestion)
        val gate = CompletableDeferred<Unit>(); preferences.applyGate = gate
        vm.applyPaceSuggestion(offer.id); runCurrent()
        vm.setSentenceOnly(true); vm.setStart(1)
        gate.complete(Unit); runCurrent()
        assertTrue(vm.state.value.sentenceOnly); assertEquals(1, vm.state.value.startParagraph)
        assertEquals(offer.toRate, vm.state.value.groupsPerMinute)
        val token = requireNotNull(preferences.state.value.activePaceBinding)
        vm.prepare(false); runCurrent(); vm.togglePlay(); runCurrent(); advanceTimeBy(100); vm.leave(); runCurrent()
        assertEquals(token, repository.submitted.last().paceBinding)
        assertEquals(token, preferences.state.value.activePaceBinding)
    }

    @Test fun aNewExplicitApplicationResetsEarlierManualEditsForTheNextGuidedRun() = runTest(dispatcher) {
        val preferences = Preferences()
        val repository = Plans(preferences, withSuggestionEvidence = true)
        val vm = model(repository, preferences)
        bind(vm); runCurrent()
        vm.setSpeed(80); vm.setSpeed(60)
        runCurrent()
        assertFalse(vm.state.value.personalizationBusy)
        val offer = requireNotNull(vm.state.value.suggestion)
        vm.applyPaceSuggestion(offer.id); runCurrent()
        assertEquals(offer.toRate, vm.state.value.groupsPerMinute)
        val token = requireNotNull(preferences.state.value.activePaceBinding)
        vm.prepare(false); runCurrent(); vm.togglePlay(); runCurrent(); advanceTimeBy(100); vm.leave(); runCurrent()
        assertEquals(token, repository.submitted.last().paceBinding)
        assertFalse(preferences.remembered.last().manual)
        assertEquals(token, preferences.state.value.activePaceBinding)
    }

    @Test fun aManualEditBeforeQueuedApplyStartsPreventsTheStaleDecision() = runTest(dispatcher) {
        val preferences = Preferences()
        val repository = Plans(preferences, withSuggestionEvidence = true)
        val vm = model(repository, preferences)
        bind(vm); runCurrent()
        vm.applyPaceSuggestion(requireNotNull(vm.state.value.suggestion).id)
        vm.setSpeed(80)
        runCurrent()
        assertEquals(0, preferences.applyCalls)
        assertEquals(80, vm.state.value.groupsPerMinute)
        vm.leave(); runCurrent()
        assertEquals(80, preferences.state.value.rateFor())
        assertNull(preferences.state.value.activePaceBinding)
    }

    @Test fun preparationWaitsForTheExplicitPreferenceWriteToFinish() = runTest(dispatcher) {
        val preferences = Preferences()
        val repository = Plans(preferences, withSuggestionEvidence = true)
        val vm = model(repository, preferences)
        bind(vm); runCurrent()
        val offer = requireNotNull(vm.state.value.suggestion)
        val gate = CompletableDeferred<Unit>(); preferences.applyGate = gate
        vm.applyPaceSuggestion(offer.id); runCurrent()
        vm.prepare(false); runCurrent()
        assertEquals(SpeedStage.SETUP, vm.state.value.stage)
        assertEquals(0, repository.prepareCalls)
        gate.complete(Unit); runCurrent()
        assertEquals(SpeedStage.READING, vm.state.value.stage)
        assertEquals(1, repository.prepareCalls)
        assertEquals(offer.toRate, vm.state.value.groupsPerMinute)
        vm.togglePlay(); runCurrent(); advanceTimeBy(100); vm.leave(); runCurrent()
        assertEquals(preferences.state.value.activePaceBinding, repository.submitted.last().paceBinding)
        assertNotNull(repository.submitted.last().paceBinding)
    }

    private fun model(repository: Plans, preferences: Preferences, saved: SavedStateHandle = SavedStateHandle()): SpeedReadingViewModel =
        SpeedReadingViewModel(repository, saved, wall = { WALL + scheduler.currentTime }, monotonic = { scheduler.currentTime },
            computationDispatcher = dispatcher, personalizationRepository = preferences).also {
            stores[it] = ViewModelStore().also { store -> store.put("binding", it) }
        }
    private fun bind(model: SpeedReadingViewModel) = model.bind(ARTICLE, BODY, true, true, 1, 0)

    private data class Remembered(val rate: Int, val manual: Boolean, val enabled: Boolean)
    private class Preferences(initial: PersonalizationProfile = PersonalizationProfile()) : PersonalizationRepository {
        override val state = MutableStateFlow(initial)
        var applyGate: CompletableDeferred<Unit>? = null
        var applyCalls = 0
        val remembered = mutableListOf<Remembered>()
        override suspend fun remember(granularity: SpeedGranularity, rate: Int, sentenceOnly: Boolean,
            expectedRevision: Long, paceManuallyAdjusted: Boolean): PersonalizationProfile {
            checkRevision(expectedRevision)
            val current = state.value
            remembered += Remembered(rate, paceManuallyAdjusted, current.enabled)
            val changedPace = current.granularity != granularity || current.rateFor(granularity) != rate
            val binding = current.activePaceBinding.takeUnless { paceManuallyAdjusted || (current.enabled && changedPace) }
            return commit(if (current.enabled) current.copy(granularity = granularity, rates = current.rates + (granularity to rate),
                sentenceOnly = sentenceOnly, activePaceBinding = binding) else current.copy(activePaceBinding = binding))
        }
        override suspend fun invalidatePaceBinding(expectedRevision: Long): PersonalizationProfile {
            checkRevision(expectedRevision)
            val current = state.value
            val origin = current.activePaceBinding
            val undo = if (origin != null && !current.undoPaceBindings.containsKey(origin.granularity))
                current.undoPaceBindings + (origin.granularity to origin) else current.undoPaceBindings
            return commit(current.copy(activePaceBinding = null, undoPaceBindings = undo))
        }
        override suspend fun setEnabled(enabled: Boolean, expectedRevision: Long): PersonalizationProfile {
            checkRevision(expectedRevision); return commit(state.value.copy(enabled = enabled))
        }
        override suspend fun apply(suggestion: PaceSuggestion, expectedRevision: Long): PersonalizationProfile {
            checkRevision(expectedRevision); applyCalls++; applyGate?.await(); checkRevision(expectedRevision)
            val revision = state.value.revision + 1
            val decision = PersonalizationDecision(suggestion.id, suggestion, PersonalizationDecisionStatus.APPLIED, 900_000,
                appliedRevision = revision)
            return commit(state.value.copy(rates = state.value.rates + (suggestion.granularity to suggestion.toRate),
                decisions = state.value.decisions + decision,
                activePaceBinding = AppliedPaceBinding(suggestion.id, revision, suggestion.granularity, suggestion.toRate)))
        }
        override suspend fun dismiss(suggestion: PaceSuggestion, expectedRevision: Long): PersonalizationProfile {
            checkRevision(expectedRevision); return state.value
        }
        override suspend fun undo(decisionId: String, expectedRevision: Long): PersonalizationProfile {
            checkRevision(expectedRevision)
            val decision = state.value.decisions.single { it.id == decisionId }
            return commit(state.value.copy(rates = state.value.rates + (decision.suggestion.granularity to decision.suggestion.fromRate),
                activePaceBinding = null, decisions = state.value.decisions.map {
                    if (it.id == decisionId) it.copy(status = PersonalizationDecisionStatus.UNDONE, updatedAt = WALL) else it
                }))
        }
        private fun checkRevision(value: Long) { if (value != state.value.revision) throw PersonalizationConflict("Fixture revision changed") }
        private fun commit(next: PersonalizationProfile): PersonalizationProfile {
            if (next == state.value) return next
            return next.copy(revision = state.value.revision + 1).also { state.value = it }
        }
    }

    private class Plans(private val preferences: Preferences, withSuggestionEvidence: Boolean = false) : SpeedReadingRepository {
        override val library = MutableStateFlow(SpeedLibrary(runs = if (withSuggestionEvidence) evidence() else emptyList()))
        private val cached = mutableMapOf<String, SpeedPlan>()
        val submitted = mutableListOf<SpeedRun>()
        var prepareCalls = 0; private set
        override suspend fun cachedPlan(planKey: String) = cached[planKey]
        override suspend fun prepare(article: Article, body: ArticleBody, granularity: SpeedGranularity,
            startParagraph: Int, paragraphCount: Int, aiAllowed: Boolean, useAi: Boolean): SpeedPlan {
            prepareCalls++
            return SpeedChunker.plan(article.id, body.contentHash, body.text, granularity, startParagraph, paragraphCount).let {
                it.copy(planKey = SpeedChunker.planKey(it)).also { plan -> cached[plan.planKey] = plan }
            }
        }
        override suspend fun saveRun(run: SpeedRun) {
            submitted += run
            val old = library.value.runs.find { it.id == run.id }
            if (old?.completed == true) return
            val verified = run.paceBinding?.takeIf { it == preferences.state.value.activePaceBinding &&
                it.granularity == run.granularity && it.rate == run.groupsPerMinute }
            val binding = if (old == null) verified else old.paceBinding?.takeIf { it == verified }
            library.value = library.value.copy(runs = library.value.runs.filterNot { it.id == run.id } + run.copy(paceBinding = binding))
        }
        override suspend fun saveCorrection(correction: SpeedCorrection) = Unit
    }

    companion object {
        private const val STATE_KEY = "speed-training-state"
        private const val WALL = 1_000_000L
        private const val TEXT = "The local council considers a library and reviews several proposals. Residents discuss the likely costs.\n\nVolunteers collect comments from nearby towns and explain the opening hours."
        private val ARTICLE = Article("binding-article", "g", listOf("s"), "A local proposal", "https://example.org/binding", "")
        private val BODY = ArticleBody(ARTICLE.id, TEXT, "binding-body", 0, Long.MAX_VALUE, "Self-authored")
        private fun evidence() = (1..2).map { index ->
            SpeedRun("evidence-$index", "material-$index", "hash-$index", SpeedGranularity.L2, "plan-$index",
                index * 10_000L, index * 10_000L + 3000, 3000, 12, 3, listOf(1000, 1000, 1000), 0, 0,
                (1..3).map { SpeedAnswer("q-$index-$it", 0, true) }, 3, true, 60, "ai", true, true, true)
        }
        private fun appliedProfile(): PersonalizationProfile {
            val offer = requireNotNull(PersonalizationPolicy.suggest(PersonalizationProfile(), evidence()))
            val decision = PersonalizationDecision(offer.id, offer, PersonalizationDecisionStatus.APPLIED, 900_000, appliedRevision = 1)
            return PersonalizationProfile(revision = 1, rates = mapOf(SpeedGranularity.L1 to 60, SpeedGranularity.L2 to 63, SpeedGranularity.L3 to 60),
                decisions = listOf(decision), activePaceBinding = AppliedPaceBinding(offer.id, 1, SpeedGranularity.L2, 63))
        }
    }
}
