package dev.lexicon.ide.ui

import android.os.Bundle
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Bundle/controller lifecycle with a held repository boundary; no Activity, user DB or AI. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SpeedExitSaveGuardViewModelTest {
    private lateinit var clock: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setUp() {
        clock = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(clock)
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        try { stores.forEach { it.clear() }; clock.runCurrent() }
        finally { stores.clear(); Dispatchers.resetMain() }
    }

    @Test fun pendingRunSaveRejectsPlaybackCommandsAndKeepsTheFrozenClockAndAnchor() = runTest(dispatcher) {
        val f = prepared()
        playBriefly(f)
        val gate = CompletableDeferred<Unit>()
        f.repository.saveGate = gate
        var firstExits = 0
        var duplicateExits = 0
        val anchor = requireNotNull(f.vm.currentReaderAnchor())
        f.vm.leave { firstExits++ }; clock.runCurrent()
        val attempt = f.repository.attempts.single()
        assertTrue(f.vm.state.value.saving)
        assertEquals(200L, attempt.activeMillis)
        assertFalse(attempt.completed)
        assertEquals(1, attempt.pauses)
        val frozen = freeze(f)

        playbackCommands(f.vm).forEach { (name, action) ->
            action(); clock.runCurrent()
            clock.advanceTimeBy(2_000); clock.runCurrent()
            assertFrozen(name, f, frozen)
        }
        // Lifecycle cancellation remains legal during persistence and never resumes playback.
        f.vm.setForeground(false); f.vm.pause(); f.vm.setForeground(true); clock.runCurrent()
        assertFrozen("background/foreground", f, frozen)
        repeat(3) { f.vm.leave { duplicateExits++ } }
        clock.runCurrent()
        assertEquals(0, firstExits)
        assertEquals(0, duplicateExits)
        assertEquals(listOf(attempt), f.repository.attempts)
        assertEquals(anchor, f.vm.currentReaderAnchor())

        gate.complete(Unit); clock.runCurrent()
        assertEquals(1, firstExits)
        assertEquals(0, duplicateExits)
        assertEquals(attempt, f.repository.library.value.runs.single { it.id == attempt.id })
        assertFalse(f.vm.state.value.playing)
        assertFalse(f.vm.state.value.saving)
        assertNull(f.saved.get<Bundle>(STATE_KEY))
        clock.advanceTimeBy(10_000); clock.runCurrent()
        assertEquals(1, firstExits)
        assertEquals(200L, f.repository.library.value.runs.single { it.id == attempt.id }.activeMillis)
    }

    @Test fun pendingRunSaveRejectsPreferenceAndMaterialWrites() = runTest(dispatcher) {
        val f = prepared()
        playBriefly(f)
        val gate = CompletableDeferred<Unit>()
        f.repository.saveGate = gate
        var exits = 0
        f.vm.leave { exits++ }; clock.runCurrent()
        val frozen = freeze(f)
        val suggestion = requireNotNull(f.vm.state.value.suggestion)
        val decision = f.vm.state.value.profile.decisions.single()
        assertTrue(f.vm.state.value.profile.holds.isNotEmpty())
        val commands = listOf<Pair<String, () -> Unit>>(
            "remember off" to { f.vm.setRememberTraining(false) },
            "apply suggestion" to { f.vm.applyPaceSuggestion(suggestion.id) },
            "dismiss suggestion" to { f.vm.dismissPaceSuggestion(suggestion.id) },
            "undo decision" to { f.vm.undoPaceSuggestion(decision.id) },
            "release hold" to { f.vm.releasePaceHold() },
            "correction" to { f.vm.correction("too_long", "Synthetic exit-pending check") },
            "edit segmentation" to { f.vm.editSegmentation() },
            "prepare again" to { f.vm.prepare(false) },
            "restart" to { f.vm.restart() },
            "next paragraph" to { f.vm.nextParagraph() },
            "next round" to { f.vm.nextRound() },
            "setup granularity" to { f.vm.setGranularity(SpeedGranularity.L3) },
            "setup start" to { f.vm.setStart(1) },
            "setup count" to { f.vm.setCount(1) },
        )
        commands.forEach { (name, action) -> action(); clock.runCurrent(); assertFrozen(name, f, frozen) }
        gate.complete(Unit); clock.runCurrent()
        assertEquals(1, exits)
        assertEquals(1, f.repository.attempts.size)
        assertTrue(f.repository.corrections.isEmpty())
        assertEquals(1, f.repository.prepareCalls)
    }

    @Test fun theExitStaysFrozenWhileItsConfirmedPreferencesAreBeingWritten() = runTest(dispatcher) {
        val f = prepared()
        playBriefly(f)
        val preferenceGate = CompletableDeferred<Unit>()
        f.preferences.nextRememberGate = preferenceGate
        var exits = 0
        var duplicateExits = 0
        f.vm.leave { exits++ }; clock.runCurrent()
        assertEquals(1, f.repository.attempts.size)
        assertTrue(f.vm.state.value.saving)
        assertTrue(f.vm.state.value.personalizationBusy)
        assertEquals(1, f.preferences.blockedRememberCalls)
        val frozen = freeze(f)

        playbackCommands(f.vm).forEach { (name, action) ->
            action(); clock.runCurrent(); clock.advanceTimeBy(2_000); clock.runCurrent()
            assertFrozen("preferences pending: " + name, f, frozen)
        }
        repeat(2) { f.vm.leave { duplicateExits++ } }; clock.runCurrent()
        assertEquals(0, exits)
        assertEquals(0, duplicateExits)
        preferenceGate.complete(Unit); clock.runCurrent()
        assertEquals(1, exits)
        assertEquals(0, duplicateExits)
        assertFalse(f.vm.state.value.saving)
        assertFalse(f.vm.state.value.playing)
        assertEquals(200L, f.repository.attempts.single().activeMillis)
    }

    @Test fun failedExitKeepsTheRunAndUnlocksNormalActionsBeforeOneSuccessfulRetry() = runTest(dispatcher) {
        val f = prepared()
        playBriefly(f)
        val failedGate = CompletableDeferred<Unit>()
        f.repository.saveGate = failedGate
        var exits = 0
        var duplicates = 0
        f.vm.leave { exits++ }; clock.runCurrent()
        val failed = f.repository.attempts.single()
        f.vm.leave { duplicates++ }; clock.runCurrent()
        failedGate.completeExceptionally(IOException("Synthetic held save failed")); clock.runCurrent()
        assertEquals(0, exits)
        assertEquals(0, duplicates)
        assertEquals(SpeedStage.READING, f.vm.state.value.stage)
        assertFalse(f.vm.state.value.playing)
        assertFalse(f.vm.state.value.saving)
        assertNotNull(f.vm.state.value.error)
        assertNotNull(f.saved.get<Bundle>(STATE_KEY))

        val index = f.vm.state.value.chunkIndex
        f.vm.next(); assertEquals(index + 1, f.vm.state.value.chunkIndex)
        f.vm.previous(); assertEquals(index, f.vm.state.value.chunkIndex)
        f.vm.setSpeed(90); f.vm.setSentenceOnly(true); clock.runCurrent()
        assertEquals(90, f.vm.state.value.groupsPerMinute)
        assertTrue(f.vm.state.value.sentenceOnly)
        f.vm.togglePlay(); clock.runCurrent()
        assertTrue(f.vm.state.value.playing)
        clock.advanceTimeBy(175); f.vm.pause(count = true); clock.runCurrent()
        val successfulGate = CompletableDeferred<Unit>()
        f.repository.saveGate = successfulGate
        f.vm.leave { exits++ }; clock.runCurrent()
        val retry = f.repository.attempts.last()
        assertEquals(failed.id, retry.id)
        assertEquals(failed.planKey, retry.planKey)
        assertEquals(375L, retry.activeMillis)
        assertFalse(retry.completed)
        val frozen = freeze(f)
        f.vm.togglePlay(); f.vm.next(); f.vm.leave { duplicates++ }; clock.runCurrent()
        assertFrozen("retry pending", f, frozen)
        successfulGate.complete(Unit); clock.runCurrent()
        assertEquals(1, exits)
        assertEquals(0, duplicates)
        assertEquals(2, f.repository.attempts.size)
        assertEquals(retry, f.repository.library.value.runs.single { it.id == failed.id })
        assertEquals(90, f.preferences.current.value.rateFor(SpeedGranularity.L2))
        assertTrue(f.preferences.current.value.sentenceOnly)
        assertNull(f.saved.get<Bundle>(STATE_KEY))
        clock.advanceTimeBy(10_000); clock.runCurrent()
        assertEquals(1, exits)
        assertFalse(f.vm.state.value.playing)
    }

    @Test fun pendingQuestionExitCannotAddAnAnswerOrAdvanceAfterAnAlreadyRevealedAnswer() = runTest(dispatcher) {
        for (alreadyAnswered in listOf(false, true)) {
            val f = prepared(withQuestions = true)
            playBriefly(f)
            while (f.vm.state.value.stage == SpeedStage.READING) f.vm.next()
            clock.runCurrent()
            assertEquals(SpeedStage.QUESTION, f.vm.state.value.stage)
            if (alreadyAnswered) { f.vm.answer(1); clock.runCurrent() }
            assertEquals(alreadyAnswered, f.vm.state.value.answerRevealed)
            val priorAttempts = f.repository.attempts.size
            val gate = CompletableDeferred<Unit>()
            f.repository.saveGate = gate
            var exits = 0
            var duplicateExits = 0
            f.vm.leave { exits++ }; clock.runCurrent()
            val frozen = freeze(f)
            f.vm.answer(1); f.vm.continueAfterQuestion()
            f.vm.leave { duplicateExits++ }; clock.runCurrent()
            assertFrozen("question exit answered=" + alreadyAnswered, f, frozen)
            assertEquals(priorAttempts + 1, f.repository.attempts.size)
            gate.complete(Unit); clock.runCurrent()
            assertEquals(1, exits)
            assertEquals(0, duplicateExits)
            assertEquals(if (alreadyAnswered) 1 else 0, f.repository.attempts.last().answers.size)
        }
    }

    private data class Fixture(val vm: SpeedReadingViewModel, val repository: HeldRepository,
        val preferences: RecordingPreferences, val saved: SavedStateHandle)

    private fun prepared(withQuestions: Boolean = false): Fixture {
        val preferences = RecordingPreferences()
        val repository = HeldRepository(withQuestions)
        val saved = SavedStateHandle()
        val vm = SpeedReadingViewModel(repository, saved,
            wall = { WALL + clock.currentTime }, monotonic = { clock.currentTime },
            computationDispatcher = dispatcher, personalizationRepository = preferences)
        stores += ViewModelStore().also { it.put("exit-save", vm) }
        vm.bind(ARTICLE, BODY, true, true, 1, 0); clock.runCurrent()
        vm.setCount(2); vm.prepare(withQuestions); clock.runCurrent()
        assertEquals(SpeedStage.READING, vm.state.value.stage)
        assertFalse(vm.state.value.playing)
        assertFalse(vm.state.value.personalizationBusy)
        assertTrue(requireNotNull(vm.state.value.plan).chunks.size > 5)
        vm.seek(2); clock.runCurrent()
        assertEquals(2, vm.state.value.chunkIndex)
        return Fixture(vm, repository, preferences, saved)
    }

    private fun playBriefly(f: Fixture) {
        f.vm.togglePlay(); clock.runCurrent()
        assertTrue(f.vm.state.value.playing)
        clock.advanceTimeBy(200); f.vm.pause(count = true); clock.runCurrent()
        assertEquals(2, f.vm.state.value.chunkIndex)
        assertFalse(f.vm.state.value.playing)
    }

    private fun playbackCommands(vm: SpeedReadingViewModel): List<Pair<String, () -> Unit>> = listOf(
        "toggle/play" to { vm.togglePlay() }, "next" to { vm.next() }, "previous" to { vm.previous() },
        "repeat" to { vm.repeat() }, "seek" to { vm.seek(vm.state.value.chunkIndex + 1) },
        "speed" to { vm.setSpeed(90) }, "sentence mode" to { vm.setSentenceOnly(!vm.state.value.sentenceOnly) },
    )

    private data class Frozen(val state: SpeedUiState, val checkpoint: Map<String, Any?>,
        val profile: PersonalizationProfile, val preferenceCalls: List<String>, val attempts: List<SpeedRun>,
        val prepareCalls: Int, val corrections: List<SpeedCorrection>, val anchor: ReaderAnchor?)

    private fun freeze(f: Fixture) = Frozen(f.vm.state.value, bundleMap(requireNotNull(f.saved.get<Bundle>(STATE_KEY))),
        f.preferences.current.value, f.preferences.calls.toList(), f.repository.attempts.toList(),
        f.repository.prepareCalls, f.repository.corrections.toList(), f.vm.currentReaderAnchor())

    private fun assertFrozen(command: String, f: Fixture, frozen: Frozen) {
        assertEquals(command + ": reader state", frozen.state, f.vm.state.value)
        assertEquals(command + ": durable checkpoint", frozen.checkpoint, bundleMap(requireNotNull(f.saved.get<Bundle>(STATE_KEY))))
        assertEquals(command + ": confirmed preference", frozen.profile, f.preferences.current.value)
        assertEquals(command + ": preference write attempts", frozen.preferenceCalls, f.preferences.calls)
        assertEquals(command + ": run write attempts", frozen.attempts, f.repository.attempts)
        assertEquals(command + ": prepare calls", frozen.prepareCalls, f.repository.prepareCalls)
        assertEquals(command + ": correction writes", frozen.corrections, f.repository.corrections)
        assertEquals(command + ": exact original anchor", frozen.anchor, f.vm.currentReaderAnchor())
    }

    @Suppress("DEPRECATION")
    private fun bundleMap(bundle: Bundle): Map<String, Any?> = bundle.keySet().associateWith { key ->
        when (val value = bundle.get(key)) {
            is LongArray -> value.toList()
            is IntArray -> value.toList()
            is BooleanArray -> value.toList()
            is Bundle -> bundleMap(value)
            is ArrayList<*> -> value.toList()
            else -> value
        }
    }

    private class HeldRepository(private val withQuestions: Boolean) : SpeedReadingRepository {
        override val library = MutableStateFlow(SpeedLibrary(runs = evidence(60) + evidence(57)))
        var saveGate: CompletableDeferred<Unit>? = null
        val attempts = mutableListOf<SpeedRun>()
        val corrections = mutableListOf<SpeedCorrection>()
        var prepareCalls = 0
        private val cache = mutableMapOf<String, SpeedPlan>()

        override suspend fun cachedPlan(planKey: String) = cache[planKey]
        override suspend fun prepare(article: Article, body: ArticleBody, granularity: SpeedGranularity,
            startParagraph: Int, paragraphCount: Int, aiAllowed: Boolean, useAi: Boolean): SpeedPlan {
            prepareCalls++
            check(useAi == withQuestions) { "The fixture never calls a model provider" }
            val base = SpeedChunker.plan(article.id, body.contentHash, body.text, granularity,
                startParagraph, paragraphCount, WALL)
            val paragraphs = ParagraphFocus.paragraphs(body.text)
            val questions = if (!withQuestions) emptyList() else base.paragraphIndices.map { index ->
                val p = paragraphs[index]
                SpeedQuestion("fixture-question-" + index, index, "Which statement appears in this paragraph?",
                    listOf("Another claim", "The stated claim", "An unrelated claim"), 1, p.start,
                    p.endExclusive, "This is synthetic controller-test material.")
            }
            val plan = base.copy(questions = questions, origin = if (withQuestions) "ai" else "rules")
                .let { it.copy(planKey = SpeedChunker.planKey(it)) }
            SpeedChunker.validate(plan, body.text)
            cache[plan.planKey] = plan
            return plan
        }
        override suspend fun saveRun(run: SpeedRun) {
            attempts += run
            saveGate?.await()
            library.value = library.value.copy(runs = library.value.runs.filterNot { it.id == run.id } + run)
        }
        override suspend fun saveCorrection(correction: SpeedCorrection) { corrections += correction }
    }

    private class RecordingPreferences : PersonalizationRepository {
        val current = MutableStateFlow(initialProfile())
        override val state = current
        val calls = mutableListOf<String>()
        var nextRememberGate: CompletableDeferred<Unit>? = null
        var blockedRememberCalls = 0

        override suspend fun remember(granularity: SpeedGranularity, rate: Int, sentenceOnly: Boolean,
            expectedRevision: Long, paceManuallyAdjusted: Boolean): PersonalizationProfile {
            calls += "remember"
            nextRememberGate?.also { nextRememberGate = null; blockedRememberCalls++; it.await() }
            check(expectedRevision == current.value.revision)
            return if (current.value.enabled) commit(current.value.copy(granularity = granularity,
                rates = current.value.rates + (granularity to rate), sentenceOnly = sentenceOnly)) else current.value
        }
        override suspend fun setEnabled(enabled: Boolean, expectedRevision: Long): PersonalizationProfile =
            mutate("enabled", expectedRevision) { it.copy(enabled = enabled) }
        override suspend fun apply(suggestion: PaceSuggestion, expectedRevision: Long): PersonalizationProfile =
            mutate("apply", expectedRevision) { it.copy(rates = it.rates + (suggestion.granularity to suggestion.toRate)) }
        override suspend fun dismiss(suggestion: PaceSuggestion, expectedRevision: Long): PersonalizationProfile =
            mutate("dismiss", expectedRevision) { it }
        override suspend fun undo(decisionId: String, expectedRevision: Long): PersonalizationProfile =
            mutate("undo", expectedRevision) { it.copy(rates = it.rates + (SpeedGranularity.L2 to 57)) }
        override suspend fun releaseHold(granularity: SpeedGranularity, expectedRevision: Long): PersonalizationProfile =
            mutate("release", expectedRevision) { it.copy(holds = emptyList()) }
        override suspend fun invalidatePaceUndo(granularity: SpeedGranularity, expectedRevision: Long): PersonalizationProfile =
            mutate("invalidate", expectedRevision) { it.copy(activePaceBinding = null, undoPaceBindings = emptyMap()) }
        private fun mutate(name: String, expected: Long, edit: (PersonalizationProfile) -> PersonalizationProfile): PersonalizationProfile {
            calls += name
            check(expected == current.value.revision)
            return commit(edit(current.value))
        }
        private fun commit(next: PersonalizationProfile): PersonalizationProfile {
            if (next != current.value) current.value = next.copy(revision = current.value.revision + 1)
            return current.value
        }
    }

    companion object {
        private const val STATE_KEY = "speed-training-state"
        private const val WALL = 1_000_000L
        private val TEXT = "Residents compare several proposals for a community reading room. " +
            "Volunteers gather comments and check each proposal against the available budget. " +
            "The council invites local groups to explain their priorities at a public meeting.\n\n" +
            "Researchers study how the new room could improve access to books and newspapers. " +
            "They compare earlier projects and describe the limits of the available evidence."
        private val ARTICLE = Article("exit-save-fixture", "exit-save-guid", listOf("fixture-source"),
            "Self-authored exit save fixture", "https://example.test/exit-save", "")
        private val BODY = ArticleBody(ARTICLE.id, TEXT, fingerprint(TEXT), 0, Long.MAX_VALUE, "Self-authored fixture")

        private fun evidence(rate: Int) = (1..2).map { n ->
            SpeedRun("prior-" + rate + "-" + n, "prior-article-" + n, "prior-body-" + n, SpeedGranularity.L2,
                "prior-plan-" + rate + "-" + n, n * 10_000L, n * 10_000L + 3_000, 3_000,
                12, 3, listOf(1_000, 1_000, 1_000), 0, 0,
                (0..2).map { SpeedAnswer("prior-q-" + rate + "-" + n + "-" + it, 1, true) },
                3, true, rate, "ai", true, true, true)
        }
        private fun initialProfile(): PersonalizationProfile {
            val previous = PersonalizationProfile(rates = SpeedGranularity.entries.associateWith { 57 })
            val suggestion = requireNotNull(PersonalizationPolicy.suggest(previous, evidence(57)))
            check(suggestion.toRate == 60)
            val binding = AppliedPaceBinding(suggestion.id, 1, SpeedGranularity.L2, 60)
            return PersonalizationProfile(revision = 1, decisions = listOf(PersonalizationDecision(suggestion.id,
                suggestion, PersonalizationDecisionStatus.APPLIED, 100_000, appliedRevision = 1)),
                activePaceBinding = binding, undoPaceBindings = mapOf(SpeedGranularity.L2 to binding),
                holds = listOf(PaceHold(SpeedGranularity.L2, 60, -1, 100_000)))
        }
    }
}
