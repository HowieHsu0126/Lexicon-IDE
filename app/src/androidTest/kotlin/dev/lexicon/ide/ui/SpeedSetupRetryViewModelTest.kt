package dev.lexicon.ide.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Public retry contract. No candidate helper, real application DB, navigation or AI provider. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SpeedSetupRetryViewModelTest {
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

    @Test fun explicitRetrySavesTheRetainedRateOnceWithoutStartingTrainingOrLeavingSetup() = runTest(dispatcher) {
        val preferences = Preferences().apply { failRemember = true }
        val plans = Plans()
        val saved = SavedStateHandle()
        val vm = model(plans, preferences, saved)
        bind(vm); runCurrent()

        vm.setSpeed(94); runCurrent()
        assertEquals(1, preferences.rememberCalls)
        assertEquals(60, preferences.state.value.rateFor(SpeedGranularity.L2))
        assertEquals(94, vm.state.value.groupsPerMinute)
        assertNotNull(vm.state.value.personalizationError)
        assertFalse(vm.state.value.personalizationBusy)
        assertRetainedSetup(vm, plans, saved)

        // A no-change slider commit remains a no-op; the new retry action is explicit.
        val error = vm.state.value.personalizationError
        vm.setSpeed(94); runCurrent()
        assertEquals(1, preferences.rememberCalls)
        assertEquals(error, vm.state.value.personalizationError)

        preferences.failRemember = false
        val retryGate = CompletableDeferred<Unit>()
        preferences.rememberGate = retryGate
        vm.retrySetupSettings(); runCurrent()
        assertEquals(2, preferences.rememberCalls)
        assertTrue(vm.state.value.personalizationBusy)
        assertEquals(60, preferences.state.value.rateFor(SpeedGranularity.L2))
        assertEquals(94, vm.state.value.groupsPerMinute)
        assertRetainedSetup(vm, plans, saved)

        vm.retrySetupSettings(); vm.retrySetupSettings(); runCurrent()
        assertEquals("Repeated Retry while the first retry waits must not submit again", 2, preferences.rememberCalls)

        retryGate.complete(Unit); runCurrent()
        assertEquals(2, preferences.rememberCalls)
        assertEquals(94, preferences.state.value.rateFor(SpeedGranularity.L2))
        assertEquals(94, vm.state.value.groupsPerMinute)
        assertNull(vm.state.value.personalizationError)
        assertFalse(vm.state.value.personalizationBusy)
        assertRetainedSetup(vm, plans, saved)
        val revision = preferences.state.value.revision

        vm.retrySetupSettings(); runCurrent()
        assertEquals("The successful retry leaves no error to retry", 2, preferences.rememberCalls)
        assertEquals(revision, preferences.state.value.revision)
        assertRetainedSetup(vm, plans, saved)
    }

    @Test fun retryWithoutAPreviousSaveErrorIsANoOp() = runTest(dispatcher) {
        val preferences = Preferences()
        val plans = Plans()
        val saved = SavedStateHandle()
        val vm = model(plans, preferences, saved)
        bind(vm); runCurrent()

        vm.retrySetupSettings(); vm.retrySetupSettings(); runCurrent()
        assertEquals(0, preferences.rememberCalls)
        assertEquals(0L, preferences.state.value.revision)
        assertEquals(60, vm.state.value.groupsPerMinute)
        assertNull(vm.state.value.personalizationError)
        assertFalse(vm.state.value.personalizationBusy)
        assertRetainedSetup(vm, plans, saved)
    }

    private fun model(plans: Plans, preferences: Preferences, saved: SavedStateHandle): SpeedReadingViewModel =
        SpeedReadingViewModel(plans, saved, wall = { 1_000_000L + scheduler.currentTime },
            monotonic = { scheduler.currentTime }, computationDispatcher = dispatcher,
            personalizationRepository = preferences).also { store.put("setup-retry", it) }

    private fun bind(vm: SpeedReadingViewModel) = vm.bind(ARTICLE, BODY, false, false, 1, 0)

    private fun assertRetainedSetup(vm: SpeedReadingViewModel, plans: Plans, saved: SavedStateHandle) {
        assertEquals(SpeedStage.SETUP, vm.state.value.stage)
        assertNull(vm.state.value.plan)
        assertNull(vm.state.value.result)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.saving)
        assertFalse(vm.state.value.playing)
        assertEquals(0, plans.prepareCalls)
        assertTrue(plans.submitted.isEmpty())
        assertTrue(plans.library.value.runs.isEmpty())
        assertTrue("Retry keeps the active setup checkpoint instead of consuming it as an exit", saved.keys().isNotEmpty())
    }

    private class Preferences : PersonalizationRepository {
        override val state = MutableStateFlow(PersonalizationProfile())
        var rememberCalls = 0
        var failRemember = false
        var rememberGate: CompletableDeferred<Unit>? = null

        override suspend fun remember(granularity: SpeedGranularity, rate: Int, sentenceOnly: Boolean,
            expectedRevision: Long, paceManuallyAdjusted: Boolean): PersonalizationProfile {
            rememberCalls++
            val gate = rememberGate.also { rememberGate = null }
            gate?.await()
            if (failRemember) throw IOException("Synthetic preference write failure")
            check(expectedRevision == state.value.revision) { "Synthetic revision conflict" }
            val old = state.value
            check(old.enabled) { "This fixture only retries an enabled profile" }
            val next = old.copy(granularity = granularity, rates = old.rates + (granularity to rate), sentenceOnly = sentenceOnly)
            if (next != old) state.value = next.copy(revision = old.revision + 1)
            return state.value
        }

        override suspend fun setEnabled(enabled: Boolean, expectedRevision: Long): PersonalizationProfile =
            error("Rate retry must not change the memory toggle")
        override suspend fun apply(suggestion: PaceSuggestion, expectedRevision: Long): PersonalizationProfile =
            error("No suggestion is part of this retry fixture")
        override suspend fun dismiss(suggestion: PaceSuggestion, expectedRevision: Long): PersonalizationProfile =
            error("No suggestion is part of this retry fixture")
        override suspend fun undo(decisionId: String, expectedRevision: Long): PersonalizationProfile =
            error("No suggestion is part of this retry fixture")
    }

    private class Plans : SpeedReadingRepository {
        override val library = MutableStateFlow(SpeedLibrary())
        val submitted = mutableListOf<SpeedRun>()
        var prepareCalls = 0
        override suspend fun prepare(article: Article, body: ArticleBody, granularity: SpeedGranularity,
            startParagraph: Int, paragraphCount: Int, aiAllowed: Boolean, useAi: Boolean): SpeedPlan {
            prepareCalls++
            error("Settings retry must not prepare a training plan")
        }
        override suspend fun saveRun(run: SpeedRun) { submitted += run }
        override suspend fun saveCorrection(correction: SpeedCorrection): Unit =
            error("No correction is part of this retry fixture")
    }

    companion object {
        private const val TEXT = "Residents discuss a community reading room before the next meeting. Volunteers collect comments."
        private val ARTICLE = Article("setup-retry-fixture", "setup-retry-guid", listOf("fixture-source"),
            "Self-authored setup retry fixture", "https://example.test/setup-retry", "")
        private val BODY = ArticleBody(ARTICLE.id, TEXT, "setup-retry-body-hash", 0, Long.MAX_VALUE, "Self-authored fixture")
    }
}
