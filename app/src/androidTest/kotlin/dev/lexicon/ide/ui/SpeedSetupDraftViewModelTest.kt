package dev.lexicon.ide.ui

import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Independent SETUP contract tests. Only public ViewModel actions, UI state, SavedStateHandle and
 * recorded repository calls are observed. No candidate draft helper computes expected values.
 * Android Bundle round trips simulate saved-state delivery, not actual OS process termination.
 * Repositories, text and the one archived decision are synthetic in-memory fixtures;
 * these tests do not produce learning scores, call AI, or access the user's database.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SpeedSetupDraftViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher
    private val stores = linkedMapOf<SpeedReadingViewModel, ViewModelStore>()

    @Before fun setUp() {
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        try {
            stores.values.forEach { it.clear() }
            scheduler.runCurrent()
        } finally {
            stores.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun selectedGranularityAndUnchangedRateDoNotWriteOrInvalidateAnExistingBinding() = runTest(dispatcher) {
        val initial = boundProfile()
        val preferences = Preferences(initial)
        val plans = Plans()
        val saved = SavedStateHandle()
        val vm = model(plans, preferences, saved)
        bind(vm); runCurrent()
        assertEquals(63, vm.state.value.groupsPerMinute)
        val before = vm.state.value
        assertNotNull("The fixture includes a structurally valid, undoable origin", before.lastApplied)
        val beforeSaved = savedBytes(saved)

        vm.setGranularity(SpeedGranularity.L2)
        vm.setSpeed(63)
        vm.setGranularity(SpeedGranularity.L2)
        runCurrent()

        assertEquals(before, vm.state.value)
        assertArrayEquals(beforeSaved, savedBytes(saved))
        assertEquals(initial, preferences.current.value)
        assertEquals(initial.activePaceBinding, vm.state.value.profile.activePaceBinding)
        assertTrue(preferences.remembers.isEmpty())
        assertTrue(preferences.invalidations.isEmpty())
        assertTrue(preferences.commits.isEmpty())
        vm.setSentenceOnly(true); runCurrent()
        assertTrue(preferences.current.value.sentenceOnly)
        assertEquals(initial.activePaceBinding, preferences.current.value.activePaceBinding)
        assertEquals(initial.undoPaceBindings, preferences.current.value.undoPaceBindings)
        assertTrue(preferences.remembers.isNotEmpty())
        assertTrue("Display-only edits must not masquerade as pace edits", preferences.remembers.none { it.manual })
        assertNoTraining(plans)
    }

    @Test fun committedSetupRateSavesWithoutPreparingAndRepeatedSelectionCannotResetIt() = runTest(dispatcher) {
        val preferences = Preferences()
        val plans = Plans()
        val saved = SavedStateHandle()
        val vm = model(plans, preferences, saved)
        bind(vm); runCurrent()
        val gate = CompletableDeferred<Unit>()
        preferences.rememberGate = gate
        vm.setSpeed(90)
        runCurrent()
        assertEquals(90, vm.state.value.groupsPerMinute)
        assertEquals(SpeedStage.SETUP, vm.state.value.stage)
        assertTrue(vm.state.value.personalizationBusy)
        assertEquals(1, preferences.remembers.size)
        assertEquals(60, preferences.current.value.rateFor(SpeedGranularity.L2))
        assertTrue(saved.keys().isNotEmpty())
        val beforeNoOp = savedBytes(saved)

        vm.setGranularity(SpeedGranularity.L2)
        vm.setSpeed(90)
        runCurrent()
        assertEquals(90, vm.state.value.groupsPerMinute)
        assertArrayEquals(beforeNoOp, savedBytes(saved))
        assertEquals(1, preferences.remembers.size)
        gate.complete(Unit); runCurrent()

        assertEquals(90, preferences.current.value.rateFor(SpeedGranularity.L2))
        assertEquals(SpeedGranularity.L2, preferences.current.value.granularity)
        assertFalse(vm.state.value.personalizationBusy)
        assertNull(vm.state.value.personalizationError)
        val settledRevision = preferences.current.value.revision
        val settledCalls = preferences.remembers.size
        vm.setSpeed(90); vm.setGranularity(SpeedGranularity.L2); runCurrent()
        assertEquals(settledRevision, preferences.current.value.revision)
        assertEquals(settledCalls, preferences.remembers.size)
        assertNoTraining(plans)
    }

    @Test fun multiGranularityDraftsAndDisplayChoiceSurviveAnOlderPendingWrite() = runTest(dispatcher) {
        val preferences = Preferences(PersonalizationProfile(rates = rates(55, 60, 75)))
        val plans = Plans()
        val vm = model(plans, preferences)
        bind(vm); runCurrent()
        val gate = CompletableDeferred<Unit>()
        preferences.rememberGate = gate
        vm.setGranularity(SpeedGranularity.L1); runCurrent()
        assertEquals(1, preferences.remembers.size)
        vm.setSpeed(80)
        vm.setGranularity(SpeedGranularity.L2); vm.setSpeed(110)
        vm.setGranularity(SpeedGranularity.L1)
        assertEquals(80, vm.state.value.groupsPerMinute)
        vm.setSpeed(85)
        vm.setGranularity(SpeedGranularity.L3)
        assertEquals(75, vm.state.value.groupsPerMinute)
        vm.setSpeed(120)
        vm.setSentenceOnly(true)
        vm.setGranularity(SpeedGranularity.L2)
        assertEquals(110, vm.state.value.groupsPerMinute)
        runCurrent()
        assertEquals(1, preferences.remembers.size)
        gate.complete(Unit); runCurrent()

        val stored = preferences.current.value
        assertEquals(rates(85, 110, 120), stored.rates)
        assertEquals(SpeedGranularity.L2, stored.granularity)
        assertTrue(stored.sentenceOnly)
        assertEquals(110, vm.state.value.groupsPerMinute)
        assertTrue(vm.state.value.sentenceOnly)
        assertFalse(vm.state.value.personalizationBusy)
        assertNull(vm.state.value.personalizationError)
        assertNoTraining(plans)
    }

    @Test fun disabledMemoryKeepsThisEntryDraftWithoutWritingAndANewEntryUsesDefaults() = runTest(dispatcher) {
        val initial = PersonalizationProfile(enabled = false, granularity = SpeedGranularity.L3,
            rates = rates(99, 115, 130), sentenceOnly = true)
        val preferences = Preferences(initial)
        val plans = Plans()
        val vm = model(plans, preferences)
        bind(vm); runCurrent()
        assertSetup(vm, SpeedGranularity.L2, 60, false, 0, 1)
        vm.setGranularity(SpeedGranularity.L1)
        assertEquals(60, vm.state.value.groupsPerMinute)
        vm.setSpeed(80)
        vm.setGranularity(SpeedGranularity.L2); vm.setSpeed(110)
        vm.setSentenceOnly(true); vm.setStart(2); vm.setCount(2)
        vm.setGranularity(SpeedGranularity.L1)
        bind(vm); runCurrent()
        assertSetup(vm, SpeedGranularity.L1, 80, true, 2, 2)
        assertEquals(initial, preferences.current.value)
        assertTrue(preferences.remembers.isEmpty())

        var exits = 0
        vm.leave { exits++ }; runCurrent()
        assertEquals(1, exits)
        assertTrue(preferences.commits.isEmpty())
        bind(vm, entry = 2); runCurrent()
        assertSetup(vm, SpeedGranularity.L2, 60, false, 0, 1)
        assertEquals(initial, preferences.current.value)
        assertNoTraining(plans)
    }

    @Test fun enablingMemoryCommitsConfirmedDraftsWithoutLoadingOldDefaultsOverThem() = runTest(dispatcher) {
        val preferences = Preferences(PersonalizationProfile(enabled = false, granularity = SpeedGranularity.L3,
            rates = rates(45, 65, 135), sentenceOnly = false))
        val plans = Plans()
        val vm = model(plans, preferences)
        bind(vm); runCurrent()
        vm.setGranularity(SpeedGranularity.L1); vm.setSpeed(80)
        vm.setGranularity(SpeedGranularity.L2); vm.setSpeed(110)
        vm.setGranularity(SpeedGranularity.L1); vm.setSentenceOnly(true)
        runCurrent()
        assertTrue(preferences.remembers.isEmpty())
        vm.setRememberTraining(true); runCurrent()

        assertSetup(vm, SpeedGranularity.L1, 80, true, 0, 1)
        assertTrue(preferences.current.value.enabled)
        assertEquals(80, preferences.current.value.rateFor(SpeedGranularity.L1))
        assertEquals(110, preferences.current.value.rateFor(SpeedGranularity.L2))
        assertEquals(SpeedGranularity.L1, preferences.current.value.granularity)
        assertTrue(preferences.current.value.sentenceOnly)
        assertEquals(listOf(true), preferences.enableCalls)
        assertNull(vm.state.value.personalizationError)
        assertNoTraining(plans)
    }

    @Test fun disablingDuringASuspendedWriteStopsQueuedRatesAfterDisableCommits() = runTest(dispatcher) {
        val preferences = Preferences()
        val plans = Plans()
        val vm = model(plans, preferences)
        bind(vm); runCurrent()
        val gate = CompletableDeferred<Unit>()
        preferences.rememberGate = gate
        vm.setSpeed(90); runCurrent()
        assertEquals(1, preferences.remembers.size)
        vm.setSpeed(105)
        vm.setGranularity(SpeedGranularity.L1); vm.setSpeed(80)
        vm.setRememberTraining(false); runCurrent()
        assertEquals(false, vm.state.value.rememberTrainingChoice)
        gate.complete(Unit); runCurrent()

        assertFalse(preferences.current.value.enabled)
        assertNull(vm.state.value.rememberTrainingChoice)
        assertSetup(vm, SpeedGranularity.L1, 80, false, 0, 1)
        val disabledAt = preferences.commits.indexOfFirst { it.kind == "enabled:false" }
        assertTrue("The explicit off intent must reach the repository", disabledAt >= 0)
        assertTrue(preferences.commits.drop(disabledAt + 1).none { it.kind == "remember" })
        val rememberedAtOff = preferences.remembers.size
        val profileAtOff = preferences.current.value
        vm.setGranularity(SpeedGranularity.L2)
        assertEquals(105, vm.state.value.groupsPerMinute)
        vm.setSpeed(111); vm.setSentenceOnly(true); runCurrent()
        assertEquals(rememberedAtOff, preferences.remembers.size)
        assertEquals(profileAtOff, preferences.current.value)
        assertFalse(vm.state.value.personalizationBusy)
        assertNoTraining(plans)
    }

    @Test fun acknowledgedMemoryChoiceYieldsToCanonicalChangesAndAnOldAckCannotClearANewerChoice() = runTest(dispatcher) {
        val preferences = Preferences(PersonalizationProfile(enabled = false))
        val plans = Plans()
        val vm = model(plans, preferences)
        bind(vm); runCurrent()
        assertNull(vm.state.value.rememberTrainingChoice)
        vm.setRememberTraining(true); runCurrent()
        assertTrue(preferences.current.value.enabled)
        assertTrue(vm.state.value.profile.enabled)
        assertNull("A confirmed choice must stop shadowing the canonical profile", vm.state.value.rememberTrainingChoice)

        // Another legitimate entry commits a newer canonical value. This is not a local click
        // and must be visible once the earlier local choice has been acknowledged.
        preferences.current.value = preferences.current.value.let { it.copy(enabled = false, revision = it.revision + 1) }
        runCurrent()
        assertFalse(vm.state.value.profile.enabled)
        assertNull(vm.state.value.rememberTrainingChoice)
        val callsBeforeClick = preferences.enableCalls.size
        vm.setRememberTraining(true); runCurrent()
        assertEquals(callsBeforeClick + 1, preferences.enableCalls.size)
        assertTrue(preferences.current.value.enabled)
        assertTrue(vm.state.value.profile.enabled)
        assertNull(vm.state.value.rememberTrainingChoice)

        val olderOff = CompletableDeferred<Unit>()
        val newerOn = CompletableDeferred<Unit>()
        preferences.enableGate = olderOff
        vm.setRememberTraining(false); runCurrent()
        assertEquals(false, vm.state.value.rememberTrainingChoice)
        assertTrue(preferences.current.value.enabled)
        preferences.enableGate = newerOn
        vm.setRememberTraining(true); runCurrent()
        assertEquals(true, vm.state.value.rememberTrainingChoice)

        olderOff.complete(Unit); runCurrent()
        assertFalse(preferences.current.value.enabled)
        assertFalse(vm.state.value.profile.enabled)
        assertEquals("The old off acknowledgement must not consume the newer on intent", true,
            vm.state.value.rememberTrainingChoice)
        assertFalse(newerOn.isCompleted)
        assertEquals(true, preferences.enableCalls.last())
        newerOn.complete(Unit); runCurrent()
        assertTrue(preferences.current.value.enabled)
        assertTrue(vm.state.value.profile.enabled)
        assertNull(vm.state.value.rememberTrainingChoice)
        assertFalse(vm.state.value.personalizationBusy)
        assertNull(vm.state.value.personalizationError)
        assertNoTraining(plans)
    }

    @Test fun planlessSavedStateRestoresAllChoicesThroughABodyLoadingGapAndLateProfile() = runTest(dispatcher) {
        val plans = Plans()
        val firstSaved = SavedStateHandle()
        val first = model(plans, Preferences(PersonalizationProfile(enabled = false)), firstSaved)
        bind(first, entry = 7); runCurrent()
        first.setGranularity(SpeedGranularity.L1); first.setSpeed(81)
        first.setGranularity(SpeedGranularity.L3); first.setSpeed(112)
        first.setSentenceOnly(true); first.setStart(2); first.setCount(2)
        runCurrent()
        val snapshot = copiedState(firstSaved)
        assertTrue(snapshot.isNotEmpty())
        assertFalse(snapshot.containsKey(TRAINING_KEY))
        assertTrue(savedBytes(firstSaved).size <= 16_384)
        assertTrue(snapshot.values.filterIsInstance<Bundle>().flatMap(::stringsIn).none {
            it.contains("Residents gather in the coastal library")
        })
        dispose(first); runCurrent()

        val gate = CompletableDeferred<Unit>()
        val preferences = Preferences(PersonalizationProfile(granularity = SpeedGranularity.L2,
            rates = rates(45, 55, 65), sentenceOnly = false), gate)
        val restoredSaved = SavedStateHandle(snapshot)
        val restored = model(plans, preferences, restoredSaved)
        bind(restored, body = null, entry = 7); runCurrent()
        bind(restored, entry = 7); runCurrent()
        assertSetup(restored, SpeedGranularity.L3, 112, true, 2, 2)
        gate.complete(Unit); runCurrent()
        assertSetup(restored, SpeedGranularity.L3, 112, true, 2, 2)
        restored.setGranularity(SpeedGranularity.L1); runCurrent()
        assertEquals(81, restored.state.value.groupsPerMinute)
        assertNoTraining(plans)
    }

    @Test fun anAlreadyBoundSetupDraftAlsoSurvivesATemporaryNullBody() = runTest(dispatcher) {
        val plans = Plans()
        val vm = model(plans, Preferences(PersonalizationProfile(enabled = false)))
        bind(vm); runCurrent()
        vm.setGranularity(SpeedGranularity.L3); vm.setSpeed(118)
        vm.setSentenceOnly(true); vm.setStart(1); vm.setCount(3)
        bind(vm, body = null); runCurrent()
        bind(vm); runCurrent()
        assertSetup(vm, SpeedGranularity.L3, 118, true, 1, 3)
        assertNoTraining(plans)
    }

    @Test fun newEntryArticleAndBodyEachRejectTheOldSetupSnapshot() = runTest(dispatcher) {
        val plans = Plans()
        val saved = SavedStateHandle()
        val first = model(plans, Preferences(PersonalizationProfile(enabled = false)), saved)
        bind(first); runCurrent()
        first.setGranularity(SpeedGranularity.L3); first.setSpeed(117)
        first.setSentenceOnly(true); first.setStart(2); first.setCount(2)
        val snapshot = copiedState(saved)
        dispose(first); runCurrent()
        val replacementArticle = ARTICLE.copy(id = "different-fixture-article")
        val cases = listOf(
            Triple(ARTICLE, BODY, 2),
            Triple(ARTICLE, BODY.copy(text = TEXT + "\n\nAn additional paragraph changes this fixture.", contentHash = "new-fixture-hash"), 1),
            Triple(replacementArticle, BODY.copy(articleId = replacementArticle.id), 1),
        )
        cases.forEach { (article, body, entry) ->
            val initial = PersonalizationProfile(granularity = SpeedGranularity.L1,
                rates = rates(72, 64, 125), sentenceOnly = false)
            val preferences = Preferences(initial)
            val vm = model(plans, preferences, SavedStateHandle(snapshot.mapValues { (_, value) ->
                if (value is Bundle) Bundle(value) else value
            }))
            bind(vm, article, body, entry); runCurrent()
            assertSetup(vm, SpeedGranularity.L1, 72, false, 0, 1)
            assertEquals(initial, preferences.current.value)
            assertTrue(preferences.remembers.isEmpty())
            dispose(vm); runCurrent()
        }
        assertNoTraining(plans)
    }

    @Test fun aLateProfileInitializesOnlyUntouchedFieldsWithoutFreezingTheWholeEditor() = runTest(dispatcher) {
        listOf(false, true).forEach { editPace ->
            val gate = CompletableDeferred<Unit>()
            val preferences = Preferences(PersonalizationProfile(granularity = SpeedGranularity.L3,
                rates = rates(75, 95, 120), sentenceOnly = true), gate)
            val plans = Plans()
            val vm = model(plans, preferences)
            bind(vm); runCurrent()
            vm.setStart(2); vm.setCount(2)
            if (editPace) { vm.setGranularity(SpeedGranularity.L1); vm.setSpeed(82) }
            runCurrent()
            assertTrue(preferences.remembers.isEmpty())
            gate.complete(Unit); runCurrent()
            assertSetup(vm, if (editPace) SpeedGranularity.L1 else SpeedGranularity.L3,
                if (editPace) 82 else 120, true, 2, 2)
            assertNull(vm.state.value.personalizationError)
            assertNoTraining(plans)
            dispose(vm); runCurrent()
        }
    }

    @Test fun aFailedPreferenceSaveKeepsTheDraftAndExitWaitsForASuccessfulRetry() = runTest(dispatcher) {
        val preferences = Preferences()
        val plans = Plans()
        val saved = SavedStateHandle()
        val vm = model(plans, preferences, saved)
        bind(vm); runCurrent()
        preferences.failRemember = true
        vm.setSpeed(94); runCurrent()
        val error = requireNotNull(vm.state.value.personalizationError)
        assertFalse(vm.state.value.personalizationBusy)
        assertEquals(60, preferences.current.value.rateFor())
        val failedCalls = preferences.remembers.size
        vm.setGranularity(SpeedGranularity.L2); vm.setSpeed(94); runCurrent()
        assertEquals(error, vm.state.value.personalizationError)
        assertEquals(failedCalls, preferences.remembers.size)
        assertEquals(94, vm.state.value.groupsPerMinute)

        var exits = 0
        vm.leave { exits++ }; runCurrent()
        assertEquals(0, exits)
        assertEquals(SpeedStage.SETUP, vm.state.value.stage)
        assertEquals(94, vm.state.value.groupsPerMinute)
        assertNotNull(vm.state.value.personalizationError)
        assertTrue(copiedState(saved).isNotEmpty())
        assertFalse(vm.state.value.saving)
        assertFalse(vm.state.value.personalizationBusy)
        preferences.failRemember = false
        vm.leave { exits++ }; runCurrent()
        assertEquals(1, exits)
        assertEquals(94, preferences.current.value.rateFor(SpeedGranularity.L2))
        assertNull(vm.state.value.personalizationError)
        assertTrue("A successful exit releases its setup saved state", saved.keys().isEmpty())
        assertNoTraining(plans)
    }

    @Test fun prepareWaitsForPersistenceAndAFailedFlushNeedsExplicitRetryBeforeOneOfflinePlan() = runTest(dispatcher) {
        val preferences = Preferences()
        val plans = Plans()
        val saved = SavedStateHandle()
        val vm = model(plans, preferences, saved)
        bind(vm); runCurrent()
        val gate = CompletableDeferred<Unit>()
        preferences.rememberGate = gate
        vm.setGranularity(SpeedGranularity.L3); vm.setSpeed(96); runCurrent()
        vm.prepare(false); runCurrent()
        assertEquals(0, plans.prepareCalls)
        assertEquals(SpeedStage.SETUP, vm.state.value.stage)
        preferences.failRemember = true
        gate.complete(Unit); runCurrent()
        assertEquals(0, plans.prepareCalls)
        assertEquals(SpeedStage.SETUP, vm.state.value.stage)
        assertEquals(96, vm.state.value.groupsPerMinute)
        assertNotNull(vm.state.value.personalizationError)
        assertFalse(saved.keys().contains(TRAINING_KEY))

        preferences.failRemember = false
        val retryGate = CompletableDeferred<Unit>()
        preferences.rememberGate = retryGate
        vm.prepare(false); runCurrent()
        assertEquals(0, plans.prepareCalls)
        retryGate.complete(Unit); runCurrent()

        assertEquals(1, plans.prepareCalls)
        assertEquals(SpeedStage.READING, vm.state.value.stage)
        assertFalse(vm.state.value.playing)
        assertEquals(96, vm.state.value.groupsPerMinute)
        assertEquals(96, preferences.current.value.rateFor(SpeedGranularity.L3))
        assertNotNull(saved.get<Bundle>(TRAINING_KEY))
        assertTrue(plans.submitted.isEmpty())
    }

    private fun model(plans: Plans, preferences: PersonalizationRepository,
        saved: SavedStateHandle = SavedStateHandle()): SpeedReadingViewModel =
        SpeedReadingViewModel(plans, saved, wall = { WALL + scheduler.currentTime },
            monotonic = { scheduler.currentTime }, computationDispatcher = dispatcher,
            personalizationRepository = preferences).also { vm ->
            stores[vm] = ViewModelStore().also { it.put("setup-fixture", vm) }
        }

    private fun dispose(vm: SpeedReadingViewModel) { stores.remove(vm)?.clear() }

    private fun bind(vm: SpeedReadingViewModel, article: Article = ARTICLE, body: ArticleBody? = BODY,
        entry: Int = 1) = vm.bind(article, body, false, false, entry, 0)

    private fun assertSetup(vm: SpeedReadingViewModel, granularity: SpeedGranularity, rate: Int,
        sentenceOnly: Boolean, start: Int, count: Int) {
        val state = vm.state.value
        assertEquals(SpeedStage.SETUP, state.stage)
        assertNull(state.plan)
        assertEquals(granularity, state.selectedGranularity)
        assertEquals(rate, state.groupsPerMinute)
        assertEquals(sentenceOnly, state.sentenceOnly)
        assertEquals(start, state.startParagraph)
        assertEquals(count, state.paragraphCount)
        assertFalse(state.playing)
    }

    private fun assertNoTraining(plans: Plans) {
        assertEquals(0, plans.prepareCalls)
        assertEquals(0, plans.cacheReads)
        assertTrue(plans.submitted.isEmpty())
        assertTrue(plans.library.value.runs.isEmpty())
    }

    /** Serializes the VM's real saved values without depending on a proposed draft key/schema. */
    private fun savedBundle(saved: SavedStateHandle): Bundle = Bundle().apply {
        saved.keys().sorted().forEach { key ->
            when (val value = saved.get<Any?>(key)) {
                null -> putString(key, null)
                is Bundle -> putBundle(key, Bundle(value))
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Boolean -> putBoolean(key, value)
                else -> fail("Unexpected saved-state value type: ${value::class.java.simpleName}")
            }
        }
    }

    private fun savedBytes(saved: SavedStateHandle): ByteArray = Parcel.obtain().let { parcel ->
        try { parcel.writeBundle(savedBundle(saved)); parcel.marshall() }
        finally { parcel.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun copiedState(saved: SavedStateHandle): Map<String, Any?> = Parcel.obtain().let { parcel ->
        try {
            parcel.writeBundle(savedBundle(saved)); parcel.setDataPosition(0)
            val restored = requireNotNull(parcel.readBundle(javaClass.classLoader))
            restored.keySet().associateWith { restored.get(it) }
        } finally { parcel.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun stringsIn(bundle: Bundle): List<String> = bundle.keySet().flatMap { key ->
        when (val value = bundle.get(key)) {
            is String -> listOf(value)
            is Bundle -> stringsIn(value)
            else -> emptyList()
        }
    }

    private data class RememberCall(val granularity: SpeedGranularity, val rate: Int,
        val sentenceOnly: Boolean, val manual: Boolean, val revision: Long)
    private data class Commit(val kind: String, val profile: PersonalizationProfile)

    /** A CAS repository spy. Suspensions and failures occur at the real suspend interface. */
    private class Preferences(initial: PersonalizationProfile = PersonalizationProfile(),
        readGate: CompletableDeferred<Unit>? = null) : PersonalizationRepository {
        val current = MutableStateFlow(initial)
        override val state: Flow<PersonalizationProfile> = if (readGate == null) current
            else flow { readGate.await(); emitAll(current) }
        val remembers = mutableListOf<RememberCall>()
        val enableCalls = mutableListOf<Boolean>()
        val invalidations = mutableListOf<SpeedGranularity>()
        val commits = mutableListOf<Commit>()
        var rememberGate: CompletableDeferred<Unit>? = null
        var enableGate: CompletableDeferred<Unit>? = null
        var failRemember = false

        override suspend fun remember(granularity: SpeedGranularity, rate: Int, sentenceOnly: Boolean,
            expectedRevision: Long, paceManuallyAdjusted: Boolean): PersonalizationProfile {
            checkRevision(expectedRevision)
            remembers += RememberCall(granularity, rate, sentenceOnly, paceManuallyAdjusted, expectedRevision)
            val gate = rememberGate.also { rememberGate = null }
            gate?.await()
            checkRevision(expectedRevision)
            if (failRemember) throw IOException("Synthetic preference storage failure")
            val old = current.value
            val changedPace = old.granularity != granularity || old.rateFor(granularity) != rate
            val binding = old.activePaceBinding.takeUnless { paceManuallyAdjusted || old.enabled && changedPace }
            val next = if (old.enabled) old.copy(granularity = granularity, rates = old.rates + (granularity to rate),
                sentenceOnly = sentenceOnly, activePaceBinding = binding,
                undoPaceBindings = if (paceManuallyAdjusted) old.undoPaceBindings - granularity else old.undoPaceBindings)
            else old.copy(activePaceBinding = binding,
                undoPaceBindings = if (paceManuallyAdjusted) old.undoPaceBindings - granularity else old.undoPaceBindings)
            return commit("remember", next)
        }

        override suspend fun setEnabled(enabled: Boolean, expectedRevision: Long): PersonalizationProfile {
            checkRevision(expectedRevision)
            enableCalls += enabled
            val gate = enableGate.also { enableGate = null }
            gate?.await()
            checkRevision(expectedRevision)
            return commit("enabled:$enabled", current.value.copy(enabled = enabled))
        }

        override suspend fun invalidatePaceBinding(expectedRevision: Long): PersonalizationProfile {
            checkRevision(expectedRevision)
            return commit("invalidate-binding", current.value.copy(activePaceBinding = null))
        }

        override suspend fun invalidatePaceUndo(granularity: SpeedGranularity, expectedRevision: Long): PersonalizationProfile {
            checkRevision(expectedRevision)
            invalidations += granularity
            return commit("invalidate", current.value.copy(
                activePaceBinding = current.value.activePaceBinding?.takeUnless { it.granularity == granularity },
                undoPaceBindings = current.value.undoPaceBindings - granularity))
        }

        override suspend fun apply(suggestion: PaceSuggestion, expectedRevision: Long): PersonalizationProfile =
            error("SETUP draft tests must not manufacture an application from scored evidence")
        override suspend fun dismiss(suggestion: PaceSuggestion, expectedRevision: Long): PersonalizationProfile =
            error("No suggestion dismissal belongs to this fixture")
        override suspend fun undo(decisionId: String, expectedRevision: Long): PersonalizationProfile =
            error("No suggestion undo belongs to this fixture")

        private fun checkRevision(expected: Long) {
            if (expected != current.value.revision) throw PersonalizationConflict("Synthetic CAS conflict")
        }
        private fun commit(kind: String, next: PersonalizationProfile): PersonalizationProfile {
            if (next != current.value) current.value = next.copy(revision = current.value.revision + 1)
            return current.value.also { commits += Commit(kind, it) }
        }
    }

    private class Plans : SpeedReadingRepository {
        override val library = MutableStateFlow(SpeedLibrary())
        var prepareCalls = 0
        var cacheReads = 0
        val submitted = mutableListOf<SpeedRun>()
        private val cache = mutableMapOf<String, SpeedPlan>()
        override suspend fun cachedPlan(planKey: String): SpeedPlan? { cacheReads++; return cache[planKey] }
        override suspend fun prepare(article: Article, body: ArticleBody, granularity: SpeedGranularity,
            startParagraph: Int, paragraphCount: Int, aiAllowed: Boolean, useAi: Boolean): SpeedPlan {
            check(!useAi) { "This fixture never calls an AI generator" }
            prepareCalls++
            val local = SpeedChunker.plan(article.id, body.contentHash, body.text, granularity,
                startParagraph, paragraphCount, WALL)
            return local.copy(planKey = SpeedChunker.planKey(local)).also { cache[it.planKey] = it }
        }
        override suspend fun saveRun(run: SpeedRun) {
            submitted += run
            library.value = library.value.copy(runs = library.value.runs.filterNot { it.id == run.id } + run)
        }
        override suspend fun saveCorrection(correction: SpeedCorrection): Unit =
            error("No correction write belongs to this fixture")
    }

    companion object {
        private const val TRAINING_KEY = "speed-training-state"
        private const val WALL = 1_000_000L
        private const val TEXT = "Residents gather in the coastal library to discuss reading projects. " +
            "Volunteers explain the opening hours.\n\n" +
            "Researchers compare flood measurements with earlier records. Local councils review the findings.\n\n" +
            "Students organise a shared garden near the school. Families volunteer to care for the plants.\n\n" +
            "The committee gathers feedback before choosing a new plan. Residents discuss the next meeting."
        private val ARTICLE = Article("setup-fixture-article", "setup-fixture-guid", listOf("fixture-source"),
            "Self-authored setup state fixture", "https://example.test/setup-state", "")
        private val BODY = ArticleBody(ARTICLE.id, TEXT, "setup-fixture-body-hash", 0, Long.MAX_VALUE, "Self-authored fixture")
        private fun rates(l1: Int, l2: Int, l3: Int) = mapOf(
            SpeedGranularity.L1 to l1, SpeedGranularity.L2 to l2, SpeedGranularity.L3 to l3)
        private fun boundProfile(): PersonalizationProfile {
            // Archived synthetic metadata only. The referenced evidence is intentionally absent;
            // no provider supplies scored runs or claims a new recommendation/learning result.
            val evidenceHash = "0".repeat(64)
            val id = "pace:c110b0b3d9257f647a674a1ea78f1ffb7cc07c44f322a469e6c589d9b1d7f087"
            val suggestion = PaceSuggestion(id, SpeedGranularity.L2, 60, 63,
                listOf("synthetic-archived-evidence-a", "synthetic-archived-evidence-b"),
                6, 6, 2, evidenceHash, PersonalizationPolicy.RULE_VERSION)
            val decision = PersonalizationDecision(id, suggestion, PersonalizationDecisionStatus.APPLIED,
                WALL - 1_000, appliedRevision = 7)
            val token = AppliedPaceBinding(id, 7, SpeedGranularity.L2, 63)
            return PersonalizationProfile(revision = 7, rates = rates(60, 63, 60),
                decisions = listOf(decision), activePaceBinding = token,
                undoPaceBindings = mapOf(SpeedGranularity.L2 to token))
        }
    }
}
