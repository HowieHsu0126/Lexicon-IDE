package dev.lexicon.ide.ui

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

/** Flow and provenance checks using synthetic local records; no Android database or model call. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PersonalizationAssessmentViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setup() {
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
    }

    @After fun teardown() {
        try { stores.forEach { it.clear() }; scheduler.runCurrent() }
        finally { stores.clear(); Dispatchers.resetMain() }
    }

    @Test fun publishedAssessmentMatchesItsExactEvidenceAndUpdatesWhenAnotherRunArrives() = runTest(dispatcher) {
        val speed = Records(listOf(evidence(1)))
        val preferences = Preferences()
        val vm = model(speed, preferences)
        runCurrent()
        assertEquals(PaceAssessmentStatus.INSUFFICIENT_EVIDENCE, vm.state.value.paceAssessment?.status)
        assertEquals(listOf(evidence(1)), vm.state.value.paceAssessment?.evidence)
        assertNull(vm.state.value.suggestion)
        speed.library.value = SpeedLibrary(runs = listOf(evidence(1), evidence(2)))
        runCurrent()
        val expected = PersonalizationPolicy.assess(preferences.state.value, speed.library.value.runs)
        assertEquals(expected, vm.state.value.paceAssessment)
        assertEquals(expected.suggestion, vm.state.value.suggestion)
        assertEquals(speed.library.value.runs, vm.state.value.paceEvidenceRuns)
        assertEquals(0, speed.modelCalls)
        assertEquals(0, preferences.writes)
    }

    @Test fun aNewProfileRateCannotKeepAnAssessmentFromThePreviousRate() = runTest(dispatcher) {
        val initial = listOf(evidence(1), evidence(2))
        val speed = Records(initial)
        val preferences = Preferences()
        val vm = model(speed, preferences)
        runCurrent()
        assertEquals(60, vm.state.value.paceAssessment?.rate)
        preferences.state.value = preferences.state.value.copy(revision = 1, rates = mapOf(SpeedGranularity.L2 to 80))
        runCurrent()
        val changed = requireNotNull(vm.state.value.paceAssessment)
        assertEquals(80, changed.rate)
        assertEquals(PaceAssessmentStatus.INSUFFICIENT_EVIDENCE, changed.status)
        assertEquals(2, changed.excludedCounts[PaceExclusionReason.OTHER_CONFIGURATION])
        assertTrue(changed.evidence.isEmpty())
        assertNull(vm.state.value.suggestion)
        val current = listOf(evidence(3, rate = 80), evidence(4, rate = 80))
        speed.library.value = SpeedLibrary(runs = initial + current)
        runCurrent()
        assertEquals(PersonalizationPolicy.assess(preferences.state.value, initial + current), vm.state.value.paceAssessment)
        assertEquals(84, vm.state.value.suggestion?.toRate)
        assertEquals(0, speed.modelCalls)
    }

    @Test fun disablingAndReenablingRememberingRefreshesTheExplanationWithoutChangingRuns() = runTest(dispatcher) {
        val speed = Records(listOf(evidence(1), evidence(2)))
        val original = speed.library.value
        val preferences = Preferences()
        val vm = model(speed, preferences)
        val text = "Readers compare the evidence before choosing a suitable pace."
        val article = Article("assessment-settings", "g", listOf("synthetic"), "Training settings",
            "https://example.org/assessment-settings", "Self-authored fixture")
        vm.bind(article, ArticleBody(article.id, text, fingerprint(text), 1, Long.MAX_VALUE, "Self-authored test"),
            true, false, 1, 0)
        runCurrent()
        assertEquals(0, preferences.writes)
        vm.setRememberTraining(false)
        runCurrent()
        assertEquals(PaceAssessmentStatus.DISABLED, vm.state.value.paceAssessment?.status)
        assertNull(vm.state.value.suggestion)
        assertFalse(vm.state.value.profile.enabled)
        vm.setRememberTraining(true)
        runCurrent()
        assertEquals(PaceAssessmentStatus.SUGGESTED, vm.state.value.paceAssessment?.status)
        assertNotNull(vm.state.value.suggestion)
        assertEquals(original, speed.library.value)
        assertEquals(2, preferences.writes)
        assertEquals(0, speed.modelCalls)
    }

    @Test fun memoryCommandsBeforeBindingDoNotCreateSettingsWrites() = runTest(dispatcher) {
        val speed = Records(listOf(evidence(1), evidence(2)))
        val preferences = Preferences()
        val vm = model(speed, preferences)
        runCurrent()
        val before = vm.state.value
        vm.setRememberTraining(false)
        runCurrent()
        assertEquals(before, vm.state.value)
        assertEquals(0, preferences.writes)
        assertEquals(0, speed.modelCalls)
    }

    @Test fun holdReleaseApplicationAndUndoKeepAssessmentAndHistoricalOutcomeSeparate() = runTest(dispatcher) {
        val runs = listOf(evidence(1), evidence(2))
        val speed = Records(runs)
        val preferences = Preferences(PersonalizationProfile(holds = listOf(PaceHold(SpeedGranularity.L2, 60, 1, 0))))
        val vm = model(speed, preferences)
        runCurrent()
        assertEquals(PaceAssessmentStatus.HELD, vm.state.value.paceAssessment?.status)
        vm.releasePaceHold()
        runCurrent()
        val offer = requireNotNull(vm.state.value.suggestion)
        vm.applyPaceSuggestion(offer.id)
        runCurrent()
        assertEquals(63, vm.state.value.paceAssessment?.rate)
        assertEquals(PaceAssessmentStatus.INSUFFICIENT_EVIDENCE, vm.state.value.paceAssessment?.status)
        assertEquals(offer.id, vm.state.value.paceOutcome?.decisionId)
        assertEquals(0, vm.state.value.paceOutcome?.sampleCount)
        val applied = preferences.state.value.decisions.single()
        val binding = requireNotNull(preferences.state.value.activePaceBinding)
        val followUps = listOf(evidence(20, rate = 63), evidence(21, rate = 63)).map { it.copy(paceBinding = binding) }
        speed.library.value = SpeedLibrary(runs = runs + followUps)
        runCurrent()
        assertEquals(PersonalizationOutcomes.summarize(applied, speed.library.value.runs), vm.state.value.paceOutcome)
        assertEquals(2, vm.state.value.paceOutcome?.sampleCount)
        vm.undoPaceSuggestion(offer.id)
        runCurrent()
        assertEquals(60, vm.state.value.paceAssessment?.rate)
        assertEquals(PaceAssessmentStatus.HELD, vm.state.value.paceAssessment?.status)
        assertNull(vm.state.value.suggestion)
        assertEquals(offer.id, vm.state.value.paceOutcome?.decisionId)
        assertEquals(2, vm.state.value.paceOutcome?.sampleCount)
        assertEquals(PersonalizationDecisionStatus.UNDONE, preferences.state.value.decisions.single().status)
        assertEquals(0, speed.modelCalls)
    }

    @Test fun corruptProfileClearsAssessmentAndCannotRepublishItFromNewRunData() = runTest(dispatcher) {
        val speed = Records(listOf(evidence(1), evidence(2)))
        val preferences = Preferences()
        val vm = model(speed, preferences)
        runCurrent()
        assertNotNull(vm.state.value.suggestion)
        preferences.health.value = PersonalizationHealth(PersonalizationHealthStatus.CORRUPT, "a".repeat(64))
        runCurrent()
        fun assertCleared() {
            assertNull(vm.state.value.paceAssessment)
            assertNull(vm.state.value.suggestion)
            assertNull(vm.state.value.paceOutcome)
            assertTrue(vm.state.value.paceEvidenceRuns.isEmpty())
            assertFalse(vm.state.value.profile.enabled)
        }
        assertCleared()
        speed.library.value = SpeedLibrary(runs = speed.library.value.runs + evidence(3))
        runCurrent()
        assertCleared()
        assertEquals(0, preferences.writes)
        assertEquals(0, speed.modelCalls)
    }

    @Test fun inspectionAcceptsTheSelectedCanonicalSnapshotAndIdenticalCopies() = runTest(dispatcher) {
        val pair = inspectable()
        val speed = Records(listOf(pair.second, pair.second), pair.first)
        val vm = model(speed, Preferences())
        runCurrent()
        vm.inspectRun(pair.second)
        runCurrent()
        val inspection = requireNotNull(vm.recapInspection.value)
        assertFalse(inspection.loading)
        assertNull(inspection.error)
        assertEquals(pair.second, inspection.run)
        assertEquals(pair.first, inspection.plan)
        assertNotNull(inspection.summary)
        assertEquals(1, speed.planReads)
        assertEquals(0, speed.modelCalls)
    }

    @Test fun inspectionRejectsChangedMissingOrConflictingSameIdBeforeReadingItsPlan() = runTest(dispatcher) {
        val (plan, selected) = inspectable()
        val changed = selected.copy(pauses = selected.pauses + 1)
        listOf(emptyList(), listOf(changed), listOf(selected, changed)).forEach { rows ->
            val speed = Records(rows, plan)
            val vm = model(speed, Preferences())
            runCurrent()
            vm.inspectRun(selected)
            runCurrent()
            val inspection = requireNotNull(vm.recapInspection.value)
            assertFalse(inspection.loading)
            assertNotNull(inspection.error)
            assertNull(inspection.plan)
            assertNull(inspection.summary)
            assertEquals(0, speed.planReads)
            assertEquals(0, speed.modelCalls)
        }
    }

    @Test fun anInspectionCannotPublishStaleEvidenceChangedWhileItsPlanWasLoading() = runTest(dispatcher) {
        val (plan, selected) = inspectable()
        val speed = Records(listOf(selected), plan)
        val gate = CompletableDeferred<Unit>()
        speed.planGate = gate
        val vm = model(speed, Preferences())
        runCurrent()
        vm.inspectRun(selected)
        runCurrent()
        assertTrue(requireNotNull(vm.recapInspection.value).loading)
        speed.library.value = SpeedLibrary(runs = listOf(selected.copy(pauses = selected.pauses + 1)))
        gate.complete(Unit)
        runCurrent()
        val inspection = requireNotNull(vm.recapInspection.value)
        assertFalse(inspection.loading)
        assertNotNull(inspection.error)
        assertNull(inspection.plan)
        assertNull(inspection.summary)
        assertEquals(0, speed.modelCalls)
    }

    private fun model(speed: Records, preferences: Preferences) = SpeedReadingViewModel(speed,
        wall = { 1_000_000L + scheduler.currentTime }, monotonic = { scheduler.currentTime },
        computationDispatcher = dispatcher, personalizationRepository = preferences).also { vm ->
            stores += ViewModelStore().also { it.put("assessment", vm) }
        }

    private fun evidence(number: Int, rate: Int = 60): SpeedRun {
        val start = number * 10_000L
        return SpeedRun("evidence-$number", "article-$number", "body-$number", SpeedGranularity.L2, "plan-$number",
            start, start + 3_000, 3_000, 12, 3, listOf(1_000, 1_000, 1_000), 0, 0,
            (0..2).map { SpeedAnswer("q-$number-$it", 0, true) }, 3, true, rate, "ai", true, true, true)
    }

    private fun inspectable(): Pair<SpeedPlan, SpeedRun> {
        val text = "Researchers compare observations before reaching a careful conclusion."
        val draft = SpeedChunker.plan("inspect", fingerprint(text), text, SpeedGranularity.L2, 0, 1, 1)
        val plan = draft.copy(planKey = SpeedChunker.planKey(draft))
        val duration = plan.chunks.size * 1_000L
        return plan to SpeedRun("inspect-run", plan.articleId, plan.bodyHash, plan.granularity, plan.planKey,
            10_000, 10_000 + duration, duration, plan.chunks.sumOf { it.wordCount }, plan.chunks.size,
            List(plan.chunks.size) { 1_000L }, 0, 0, emptyList(), 0, true, 60, plan.origin,
            pacingStable = true, pacedCompletion = true, chunkInteractions = List(plan.chunks.size) { SpeedChunkInteraction(1, 0, 0) })
    }

    private class Records(runs: List<SpeedRun>, private val plan: SpeedPlan? = null) : SpeedReadingRepository {
        override val library = MutableStateFlow(SpeedLibrary(runs = runs))
        var modelCalls = 0
        var planReads = 0
        var planGate: CompletableDeferred<Unit>? = null
        override suspend fun cachedPlan(planKey: String): SpeedPlan? {
            planReads++
            planGate?.await()
            return plan?.takeIf { it.planKey == planKey }
        }
        override suspend fun prepare(article: Article, body: ArticleBody, granularity: SpeedGranularity,
            startParagraph: Int, paragraphCount: Int, aiAllowed: Boolean, useAi: Boolean): SpeedPlan {
            modelCalls++
            error("Assessment must not generate training material")
        }
        override suspend fun saveRun(run: SpeedRun) = error("Read-only assessment must not save a run")
        override suspend fun saveCorrection(correction: SpeedCorrection) = error("Read-only assessment must not save a correction")
    }

    /** A revision-checked synthetic state source; repository transaction semantics have separate tests. */
    private class Preferences(initial: PersonalizationProfile = PersonalizationProfile()) : PersonalizationRepository {
        override val state = MutableStateFlow(initial)
        override val health = MutableStateFlow(PersonalizationHealth(PersonalizationHealthStatus.OK))
        var writes = 0
        override suspend fun remember(granularity: SpeedGranularity, rate: Int, sentenceOnly: Boolean,
            expectedRevision: Long, paceManuallyAdjusted: Boolean): PersonalizationProfile {
            val current = state.value
            check(current.revision == expectedRevision)
            check(!paceManuallyAdjusted && current.granularity == granularity && current.rateFor(granularity) == rate &&
                current.sentenceOnly == sentenceOnly) { "Assessment fixture permits only the unchanged visible settings" }
            return current // Re-enabling adopts the visible setup; unchanged settings need no extra write.
        }
        override suspend fun setEnabled(enabled: Boolean, expectedRevision: Long) = commit(expectedRevision, state.value.copy(enabled = enabled))
        override suspend fun releaseHold(granularity: SpeedGranularity, expectedRevision: Long) =
            commit(expectedRevision, state.value.copy(holds = state.value.holds.filterNot { it.granularity == granularity }))
        override suspend fun apply(suggestion: PaceSuggestion, expectedRevision: Long): PersonalizationProfile {
            val revision = expectedRevision + 1
            val decision = PersonalizationDecision(suggestion.id, suggestion, PersonalizationDecisionStatus.APPLIED, 100_000,
                appliedRevision = revision)
            return commit(expectedRevision, state.value.copy(rates = state.value.rates + (suggestion.granularity to suggestion.toRate),
                decisions = state.value.decisions + decision,
                activePaceBinding = AppliedPaceBinding(suggestion.id, revision, suggestion.granularity, suggestion.toRate)))
        }
        override suspend fun dismiss(suggestion: PaceSuggestion, expectedRevision: Long): PersonalizationProfile = error("Unused fixture action")
        override suspend fun undo(decisionId: String, expectedRevision: Long): PersonalizationProfile {
            val current = state.value
            val decision = current.decisions.single { it.id == decisionId }
            val suggestion = decision.suggestion
            return commit(expectedRevision, current.copy(rates = current.rates + (suggestion.granularity to suggestion.fromRate),
                decisions = current.decisions.map { if (it.id == decisionId) it.copy(status = PersonalizationDecisionStatus.UNDONE, updatedAt = 1_000_000) else it },
                activePaceBinding = null,
                holds = current.holds + PaceHold(suggestion.granularity, suggestion.fromRate, suggestion.toRate.compareTo(suggestion.fromRate), 1_000_000)))
        }
        private fun commit(expected: Long, next: PersonalizationProfile): PersonalizationProfile {
            check(expected == state.value.revision)
            writes++
            return next.copy(revision = expected + 1).also { state.value = it }
        }
    }
}
