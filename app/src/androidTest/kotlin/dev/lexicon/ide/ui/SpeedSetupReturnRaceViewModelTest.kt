package dev.lexicon.ide.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Public-interface race test; no candidate draft helper, UI launch, user DB or AI provider. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SpeedSetupReturnRaceViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher
    private val store = ViewModelStore()

    @Before fun setUp() {
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        try { store.clear(); scheduler.runCurrent() }
        finally { Dispatchers.resetMain() }
    }

    @Test fun oneBackRequestIsNotLostWhenPreparationFinishesBeforeTheExitProfileRead() = runTest(dispatcher) {
        val preferences = GatedPreferences()
        val plans = GatedPlans()
        val saved = SavedStateHandle()
        val vm = SpeedReadingViewModel(plans, saved,
            wall = { 1_000_000L + scheduler.currentTime }, monotonic = { scheduler.currentTime },
            computationDispatcher = dispatcher, personalizationRepository = preferences)
        store.put("setup-return-race", vm)
        vm.bind(ARTICLE, BODY, false, false, 1, 0)
        runCurrent()
        vm.setSpeed(90); runCurrent()
        assertEquals(90, preferences.current.value.rateFor(SpeedGranularity.L2))
        assertFalse(vm.state.value.personalizationBusy)

        vm.prepare(false); runCurrent()
        assertTrue("The offline plan request has reached its suspend boundary", plans.prepareStarted.isCompleted)
        assertEquals(1, plans.prepareCalls)
        assertTrue(vm.state.value.loading)
        assertEquals(SpeedStage.SETUP, vm.state.value.stage)
        assertNull(vm.state.value.plan)

        // Existing long-lived profile collectors remain live. Only the next fresh state.first()
        // subscription, made by the exit persistence operation, is held at this boundary.
        val exitProfileRead = CompletableDeferred<Unit>()
        preferences.nextReadGate = exitProfileRead
        var exits = 0
        vm.leave { exits++ }
        runCurrent()
        val exitReadWasSuspended = preferences.blockedReads == 1

        // Deliver the prepared plan first, then the profile read. There is no second Back call.
        // A valid fix may cancel preparation/exit earlier; the test does not require the buggy
        // intermediate transition to READING once the user has already requested an exit.
        plans.releasePrepare.complete(Unit); runCurrent()
        val stageBeforeExitRead = vm.state.value.stage
        exitProfileRead.complete(Unit); runCurrent()

        assertEquals("The first Back request must survive a completed prepare and delayed profile read; " +
            "profileReadSuspended=$exitReadWasSuspended, stageBeforeRead=$stageBeforeExitRead, " +
            "stageNow=${vm.state.value.stage}, busy=${vm.state.value.personalizationBusy}", 1, exits)
        assertFalse(vm.state.value.playing)
        assertFalse(vm.state.value.personalizationBusy)
        assertNull(vm.state.value.personalizationError)
        assertEquals(90, preferences.current.value.rateFor(SpeedGranularity.L2))
        assertEquals(1, plans.prepareCalls)
        assertTrue("Leaving before playback must not create a scored or partial run", plans.submitted.isEmpty())
        assertTrue("The completed exit releases both setup and training checkpoints", saved.keys().isEmpty())
        runCurrent()
        assertEquals("Late completion must not deliver the same exit twice", 1, exits)
    }

    private class GatedPreferences : PersonalizationRepository {
        val current = MutableStateFlow(PersonalizationProfile())
        var nextReadGate: CompletableDeferred<Unit>? = null
        var blockedReads = 0
        override val state: Flow<PersonalizationProfile> = flow {
            val gate = nextReadGate.also { nextReadGate = null }
            if (gate != null) { blockedReads++; gate.await() }
            emitAll(current)
        }

        override suspend fun remember(granularity: SpeedGranularity, rate: Int, sentenceOnly: Boolean,
            expectedRevision: Long, paceManuallyAdjusted: Boolean): PersonalizationProfile {
            checkRevision(expectedRevision)
            val old = current.value
            return if (old.enabled) commit(old.copy(granularity = granularity,
                rates = old.rates + (granularity to rate), sentenceOnly = sentenceOnly)) else old
        }

        override suspend fun setEnabled(enabled: Boolean, expectedRevision: Long): PersonalizationProfile {
            checkRevision(expectedRevision)
            return commit(current.value.copy(enabled = enabled))
        }
        override suspend fun apply(suggestion: PaceSuggestion, expectedRevision: Long): PersonalizationProfile =
            error("No scored suggestion is part of the exit race")
        override suspend fun dismiss(suggestion: PaceSuggestion, expectedRevision: Long): PersonalizationProfile =
            error("No suggestion is part of the exit race")
        override suspend fun undo(decisionId: String, expectedRevision: Long): PersonalizationProfile =
            error("No suggestion is part of the exit race")

        private fun checkRevision(expected: Long) {
            if (current.value.revision != expected) throw PersonalizationConflict("Synthetic CAS conflict")
        }
        private fun commit(next: PersonalizationProfile): PersonalizationProfile {
            if (next != current.value) current.value = next.copy(revision = current.value.revision + 1)
            return current.value
        }
    }

    private class GatedPlans : SpeedReadingRepository {
        override val library = MutableStateFlow(SpeedLibrary())
        val prepareStarted = CompletableDeferred<Unit>()
        val releasePrepare = CompletableDeferred<Unit>()
        val submitted = mutableListOf<SpeedRun>()
        var prepareCalls = 0
        override suspend fun prepare(article: Article, body: ArticleBody, granularity: SpeedGranularity,
            startParagraph: Int, paragraphCount: Int, aiAllowed: Boolean, useAi: Boolean): SpeedPlan {
            check(!useAi) { "This fixture has no model provider" }
            prepareCalls++
            prepareStarted.complete(Unit)
            releasePrepare.await()
            val plan = SpeedChunker.plan(article.id, body.contentHash, body.text, granularity,
                startParagraph, paragraphCount, 1_000_000L)
            return plan.copy(planKey = SpeedChunker.planKey(plan))
        }
        override suspend fun saveRun(run: SpeedRun) { submitted += run }
        override suspend fun saveCorrection(correction: SpeedCorrection): Unit =
            error("No correction is part of the exit race")
    }

    companion object {
        private const val TEXT = "Residents discuss a community reading room and compare several proposals. " +
            "Volunteers collect comments before the next meeting."
        private val ARTICLE = Article("setup-exit-fixture", "setup-exit-guid", listOf("fixture-source"),
            "Self-authored setup exit fixture", "https://example.test/setup-exit", "")
        private val BODY = ArticleBody(ARTICLE.id, TEXT, "setup-exit-body-hash", 0, Long.MAX_VALUE, "Self-authored fixture")
    }
}
