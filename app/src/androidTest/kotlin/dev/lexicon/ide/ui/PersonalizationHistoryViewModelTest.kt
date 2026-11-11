package dev.lexicon.ide.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PersonalizationHistoryViewModelTest {
    private lateinit var dispatcher: TestDispatcher
    private val stores = mutableListOf<ViewModelStore>()
    private val fixture = PaceHistoryTestFixture()

    @Before fun setup() { dispatcher = StandardTestDispatcher(); Dispatchers.setMain(dispatcher) }
    @After fun teardown() {
        try { stores.forEach { it.clear() }; dispatcher.scheduler.runCurrent() }
        finally { stores.clear(); Dispatchers.resetMain() }
    }

    @Test fun historyContainsAppliedKeptAndUndoneDecisionsWithoutChangingPreferences() = runTest(dispatcher) {
        val preferences = HistoryPreferences(fixture.profile)
        val speed = HistoryRecords(fixture.runs)
        val vm = observed(preferences, speed)
        runCurrent()
        assertFalse(vm.state.value.loading)
        assertEquals(PersonalizationHistory.decisions(fixture.profile), vm.state.value.decisions)
        for (decision in fixture.profile.decisions) {
            vm.select(decision.id); runCurrent()
            assertEquals(decision, vm.state.value.detail?.decision)
            assertEquals(PaceHistoricalEvidenceStatus.VERIFIED, vm.state.value.detail?.originalEvidence?.status)
            if (decision.status == PersonalizationDecisionStatus.DISMISSED) assertNull(vm.state.value.detail?.outcome)
        }
        vm.select(null); runCurrent()
        assertNull(vm.state.value.selectedId)
        assertNull(vm.state.value.detail)
        assertEquals(fixture.profile, preferences.profile.value)
        assertEquals(0, preferences.writes)
        assertEquals(0, speed.writes)
    }

    @Test fun savedOldSelectionSurvivesNewerProfileDataAndControllerRecreation() = runTest(dispatcher) {
        val preferences = HistoryPreferences(fixture.profile)
        val speed = HistoryRecords(fixture.runs)
        val saved = SavedStateHandle()
        val vm = observed(preferences, speed, saved)
        runCurrent()
        vm.select(fixture.applied.id); runCurrent()
        val newer = fixture.newerDecision()
        preferences.profile.value = fixture.profile.copy(revision = 4, enabled = false,
            rates = mapOf(SpeedGranularity.L2 to 120), decisions = fixture.profile.decisions + newer)
        speed.library.value = SpeedLibrary(runs = fixture.runs + fixture.newerRuns)
        runCurrent()
        assertEquals(fixture.applied.id, vm.state.value.selectedId)
        assertEquals(fixture.applied, vm.state.value.detail?.decision)
        assertEquals(60, vm.state.value.detail?.decision?.suggestion?.fromRate)
        assertEquals(PaceHistoricalEvidenceStatus.VERIFIED, vm.state.value.detail?.originalEvidence?.status)
        val replacement = observed(preferences, speed, SavedStateHandle(mapOf("pace-history-selection" to saved.get<String>("pace-history-selection"))))
        runCurrent()
        assertEquals(fixture.applied.id, replacement.state.value.selectedId)
        assertEquals(vm.state.value.detail, replacement.state.value.detail)
        assertEquals(newer, replacement.state.value.decisions.first())
        assertEquals(0, preferences.writes)
    }

    @Test fun selectingAMissingOrConflictingDecisionKeepsTheIdAndShowsAnExplicitError() = runTest(dispatcher) {
        val preferences = HistoryPreferences(fixture.profile)
        val speed = HistoryRecords(fixture.runs)
        val vm = observed(preferences, speed)
        runCurrent()
        vm.select(fixture.applied.id); runCurrent()
        preferences.profile.value = fixture.profile.copy(decisions = fixture.profile.decisions + fixture.applied.copy(updatedAt = 500_000))
        runCurrent()
        assertEquals(fixture.applied.id, vm.state.value.selectedId)
        assertNull(vm.state.value.detail)
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.decisions.any { it.id == fixture.applied.id })
        vm.select("unavailable-id"); runCurrent()
        assertNull(vm.state.value.detail)
        assertNotNull(vm.state.value.error)
        vm.select(null); runCurrent()
        assertNull(vm.state.value.error)
        assertEquals(0, preferences.writes)
    }

    @Test fun missingOriginalRunsDoNotSwitchToAnotherDecisionOrExposePartialEvidence() = runTest(dispatcher) {
        val preferences = HistoryPreferences(fixture.profile)
        val speed = HistoryRecords(fixture.runs)
        val vm = observed(preferences, speed)
        runCurrent(); vm.select(fixture.applied.id); runCurrent()
        val missingId = fixture.applied.suggestion.evidenceRunIds.first()
        speed.library.value = SpeedLibrary(runs = fixture.runs.filterNot { it.id == missingId })
        runCurrent()
        val detail = requireNotNull(vm.state.value.detail)
        assertEquals(fixture.applied, detail.decision)
        assertEquals(PaceHistoricalEvidenceStatus.MISSING, detail.originalEvidence.status)
        assertEquals(listOf(missingId), detail.originalEvidence.missingIds)
        assertTrue(detail.originalEvidence.runs.isEmpty())
        assertEquals(fixture.appliedAfter, detail.outcomeRuns)
        assertEquals(0, speed.writes)
    }

    @Test fun corruptHealthClearsDetailAndRecoveryKeepsTheExplicitSelection() = runTest(dispatcher) {
        val preferences = HistoryPreferences(fixture.profile)
        val speed = HistoryRecords(fixture.runs)
        val vm = observed(preferences, speed)
        runCurrent(); vm.select(fixture.undone.id); runCurrent()
        assertNotNull(vm.state.value.detail)
        preferences.health.value = PersonalizationHealth(PersonalizationHealthStatus.CORRUPT, "a".repeat(64))
        runCurrent()
        assertNull(vm.state.value.detail)
        assertTrue(vm.state.value.decisions.isEmpty())
        assertNotNull(vm.state.value.error)
        assertEquals(fixture.undone.id, vm.state.value.selectedId)
        speed.library.value = SpeedLibrary(runs = fixture.runs + fixture.newerRuns)
        runCurrent()
        assertNull(vm.state.value.detail)
        preferences.health.value = PersonalizationHealth(PersonalizationHealthStatus.OK)
        runCurrent()
        assertEquals(fixture.undone, vm.state.value.detail?.decision)
        assertNull(vm.state.value.error)
        assertEquals(0, preferences.writes)
    }

    @Test fun failedReadCanRetryWithoutLosingSelectionOrWritingTheProfile() = runTest(dispatcher) {
        val preferences = HistoryPreferences(fixture.profile).apply { failRead = true }
        val speed = HistoryRecords(fixture.runs)
        val vm = observed(preferences, speed, SavedStateHandle(mapOf("pace-history-selection" to fixture.kept.id)))
        runCurrent()
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.detail)
        preferences.failRead = false
        vm.retry(); runCurrent()
        assertNull(vm.state.value.error)
        assertEquals(fixture.kept, vm.state.value.detail?.decision)
        assertNull(vm.state.value.detail?.outcome)
        assertEquals(0, preferences.writes)
        assertEquals(0, speed.writes)
    }

    @Test fun oldUndoCutoffAndAnotherDecisionsBindingRemainSeparate() = runTest(dispatcher) {
        val preferences = HistoryPreferences(fixture.profile)
        val speed = HistoryRecords(fixture.runs)
        val vm = observed(preferences, speed)
        runCurrent(); vm.select(fixture.undone.id); runCurrent()
        val detail = requireNotNull(vm.state.value.detail)
        assertEquals(fixture.undoneBefore, detail.outcomeRuns)
        assertEquals(fixture.undoneBefore.map { it.id }, detail.outcome?.evidenceRunIds)
        assertFalse(detail.outcomeRuns.any { it in fixture.appliedAfter || it == fixture.afterUndo })
        assertEquals(0, preferences.writes)
    }

    private fun TestScope.observed(preferences: HistoryPreferences, speed: HistoryRecords,
        saved: SavedStateHandle = SavedStateHandle()): PersonalizationHistoryViewModel {
        val vm = PersonalizationHistoryViewModel(preferences, speed, saved, dispatcher)
        stores += ViewModelStore().also { it.put("history", vm) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        return vm
    }

    private class HistoryPreferences(initial: PersonalizationProfile) : PersonalizationRepository {
        val profile = MutableStateFlow(initial)
        var failRead = false
        override val state: Flow<PersonalizationProfile> = flow {
            if (failRead) error("Synthetic read unavailable")
            emitAll(profile)
        }
        override val health = MutableStateFlow(PersonalizationHealth(PersonalizationHealthStatus.OK))
        var writes = 0
        private fun forbidden(): Nothing { writes++; error("History must remain read-only") }
        override suspend fun remember(granularity: SpeedGranularity, rate: Int, sentenceOnly: Boolean, expectedRevision: Long,
            paceManuallyAdjusted: Boolean): PersonalizationProfile = forbidden()
        override suspend fun setEnabled(enabled: Boolean, expectedRevision: Long): PersonalizationProfile = forbidden()
        override suspend fun apply(suggestion: PaceSuggestion, expectedRevision: Long): PersonalizationProfile = forbidden()
        override suspend fun dismiss(suggestion: PaceSuggestion, expectedRevision: Long): PersonalizationProfile = forbidden()
        override suspend fun undo(decisionId: String, expectedRevision: Long): PersonalizationProfile = forbidden()
    }

    private class HistoryRecords(runs: List<SpeedRun>) : SpeedReadingRepository {
        override val library = MutableStateFlow(SpeedLibrary(runs = runs))
        var writes = 0
        private fun forbidden(): Nothing { writes++; error("History must not generate or write materials") }
        override suspend fun prepare(article: Article, body: ArticleBody, granularity: SpeedGranularity,
            startParagraph: Int, paragraphCount: Int, aiAllowed: Boolean, useAi: Boolean): SpeedPlan = forbidden()
        override suspend fun saveRun(run: SpeedRun) = forbidden()
        override suspend fun saveCorrection(correction: SpeedCorrection) = forbidden()
    }
}

/** Shared synthetic material. Every decision is generated by the production pace-v2 domain policy. */
internal class PaceHistoryTestFixture {
    private val base = PersonalizationProfile()
    private val beforeApplied = listOf(run(1), run(2))
    private val beforeKept = listOf(run(3), run(4))
    private val beforeUndone = listOf(run(5), run(6))
    val applied = decided(beforeApplied, PersonalizationDecisionStatus.APPLIED, 100_000, 1)
    val kept = decided(beforeKept, PersonalizationDecisionStatus.DISMISSED, 200_000, null)
    val undone = decided(beforeUndone, PersonalizationDecisionStatus.UNDONE, 300_000, 3).copy(updatedAt = 600_000)
    val profile = base.copy(revision = 3, decisions = listOf(applied, kept, undone))
    val appliedAfter = listOf(run(15, rate = 63), run(16, rate = 63)).map { it.copy(paceBinding = token(applied)) }
    val undoneBefore = listOf(run(35, rate = 63), run(36, rate = 63)).map { it.copy(paceBinding = token(undone)) }
    val afterUndo = run(65, rate = 63).copy(paceBinding = token(undone))
    val runs = beforeApplied + beforeKept + beforeUndone + appliedAfter + undoneBefore + afterUndo
    val articles = runs.map { Article(it.articleId, it.articleId, listOf("synthetic"),
        "Synthetic evidence article ${it.articleId.substringAfterLast('-')}", "https://example.org/${it.articleId}", "") }
    val newerRuns = listOf(run(70), run(71))
    fun newerDecision() = decided(newerRuns, PersonalizationDecisionStatus.APPLIED, 800_000, 4)
    fun detail(decision: PersonalizationDecision, sourceRuns: List<SpeedRun> = runs) = PersonalizationHistory.inspect(decision, sourceRuns)
    fun state(decision: PersonalizationDecision? = null) = PaceHistoryUiState(PersonalizationHistory.decisions(profile),
        decision?.let { detail(it) }, decision?.id, loading = false)
    private fun token(decision: PersonalizationDecision) = AppliedPaceBinding(decision.id,
        requireNotNull(decision.appliedRevision), decision.suggestion.granularity, decision.suggestion.toRate)
    private fun decided(runs: List<SpeedRun>, status: PersonalizationDecisionStatus, time: Long, revision: Long?): PersonalizationDecision {
        val suggestion = requireNotNull(PersonalizationPolicy.suggest(base, runs))
        return PersonalizationDecision(suggestion.id, suggestion, status, time, appliedRevision = revision)
    }
    private fun run(number: Int, rate: Int = 60): SpeedRun {
        val started = number * 10_000L
        return SpeedRun("history-run-$number", "history-article-$number", "history-body-$number", SpeedGranularity.L2,
            "history-plan-$number", started, started + 3_000, 3_000, 12, 3, listOf(1_000L, 1_000L, 1_000L), 0, 0,
            (1..3).map { SpeedAnswer("history-$number-q$it", 0, true) }, 3, true, rate, "ai", true, true, true)
    }
}
