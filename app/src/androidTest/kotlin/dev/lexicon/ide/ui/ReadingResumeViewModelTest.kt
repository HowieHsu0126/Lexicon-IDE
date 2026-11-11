package dev.lexicon.ide.ui

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ReadingResumeViewModelTest {
    private lateinit var dispatcher: TestDispatcher
    private lateinit var store: ViewModelStore
    private var count = 0
    private val body = resumeFixtureBody()
    private val controls = ReadingResumeControls(StudyMode.SKIM, true, 10, "证据", "略读")
    @Before fun before() { dispatcher = StandardTestDispatcher(); Dispatchers.setMain(dispatcher); store = ViewModelStore() }
    @After fun after() { store.clear(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }
    private fun model(repo: ResumeTestRepository) = ReadingResumeViewModel(repo, dispatcher).also { store.put("resume-${count++}", it) }
    private fun previous() = ReadingResumes.create(ReadingResumeLease(body.articleId, body.contentHash, 1, null), 1, body, 7, controls, 100)

    @Test fun noCheckpointIsWrittenUntilTheOriginalViewportSettles() = runTest(dispatcher) {
        val repo = ResumeTestRepository(); val vm = model(repo)
        vm.bind("entry", body, ReadingEntryKind.CONTINUE, false, ReadingResumeControls(StudyMode.SKIM)); runCurrent()
        assertEquals(ReadingResumeControls(), vm.resolution?.controls)
        advanceTimeBy(1000); runCurrent(); assertTrue(repo.checkpoints.isEmpty())
        vm.observe("entry", body.contentHash, 7, controls); advanceTimeBy(599); runCurrent(); assertTrue(repo.checkpoints.isEmpty())
        advanceTimeBy(1); runCurrent(); assertEquals(7, repo.checkpoints.single().paragraphIndex)
        vm.observe("entry", body.contentHash, 7, controls); advanceTimeBy(1000); runCurrent(); assertEquals(1, repo.checkpoints.size)
    }

    @Test fun delayedReadCannotSaveInitialZeroBeforeTheStoredPositionIsAvailable() = runTest(dispatcher) {
        val gate = CompletableDeferred<ReadingResumeLease>(); val repo = ResumeTestRepository().apply { beginAction = { _, _ -> gate.await() } }
        val vm = model(repo); vm.bind("entry", body, ReadingEntryKind.CONTINUE, false, ReadingResumeControls()); runCurrent()
        vm.observe("entry", body.contentHash, 0, ReadingResumeControls()); advanceTimeBy(1000); runCurrent()
        assertNull(vm.resolution); assertTrue(repo.checkpoints.isEmpty())
        gate.complete(ReadingResumeLease(body.articleId, body.contentHash, 2, previous())); runCurrent()
        assertEquals(7, vm.resolution?.paragraphIndex); assertEquals(controls, vm.resolution?.controls)
        assertTrue(repo.checkpoints.isEmpty())
    }

    @Test fun explicitStartAndAnchorIgnoreThePreviousModeWhileContinueRestoresIt() = runTest(dispatcher) {
        val repo = ResumeTestRepository(previous()); val vm = model(repo)
        val start = ReadingResumeControls(StudyMode.INTENSIVE)
        vm.bind("start", body, ReadingEntryKind.START, false, start); runCurrent()
        assertNull(vm.resolution?.paragraphIndex); assertEquals(start, vm.resolution?.controls)
        vm.bind("anchor", body, ReadingEntryKind.ANCHOR, false, start); runCurrent()
        assertNull(vm.resolution?.paragraphIndex); assertEquals(start, vm.resolution?.controls)
        vm.bind("continue", body, ReadingEntryKind.CONTINUE, false, start); runCurrent()
        assertEquals(7, vm.resolution?.paragraphIndex); assertEquals(controls, vm.resolution?.controls)
    }

    @Test fun sameTaskSavedStateDoesNotRepeatAStoredJumpOrOverwriteRestoredControls() = runTest(dispatcher) {
        val repo = ResumeTestRepository(previous()); val vm = model(repo)
        vm.bind("entry", body, ReadingEntryKind.CONTINUE, true, ReadingResumeControls()); runCurrent()
        assertFalse(requireNotNull(vm.resolution).position); assertNull(vm.resolution?.controls); assertNull(vm.resolution?.paragraphIndex)
        vm.bind("entry", body, ReadingEntryKind.CONTINUE, true, ReadingResumeControls()); runCurrent()
        assertEquals(1, repo.beginCalls.size)
    }

    @Test fun differentOriginalBodyDoesNotReuseTheOldRangeOrDeleteItsRecord() = runTest(dispatcher) {
        val old = previous(); val repo = ResumeTestRepository(old); val vm = model(repo)
        val changed = body.copy(text = body.text + " New text.", contentHash = fingerprint(body.text + " New text."))
        vm.bind("changed", changed, ReadingEntryKind.CONTINUE, false, ReadingResumeControls()); runCurrent()
        assertNull(vm.resolution?.paragraphIndex); assertNotNull(vm.resolution?.notice); assertEquals(old, repo.current)
        assertTrue(repo.checkpoints.isEmpty())
    }

    @Test fun supersededBeginsAreSerializedSoTheLatestEntryOwnsTheNewestGeneration() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val repo = ResumeTestRepository(); var calls = 0
        repo.beginAction = { id, hash -> if (++calls == 1) withContext(NonCancellable) { gate.await() }; repo.acquire(id, hash) }
        val vm = model(repo); vm.bind("old", body, ReadingEntryKind.START, false, controls); runCurrent()
        vm.bind("new", body, ReadingEntryKind.START, false, ReadingResumeControls()); runCurrent()
        assertEquals(1, repo.beginCalls.size)
        gate.complete(Unit); runCurrent()
        assertEquals("new", vm.resolution?.key); assertEquals(ReadingResumeControls(), vm.resolution?.controls)
        vm.observe("old", body.contentHash, 0, controls); vm.observe("new", body.contentHash, 4, controls); vm.flush(); runCurrent()
        assertEquals(2L, repo.checkpoints.single().generation); assertEquals(4, repo.current?.paragraphIndex)
    }

    @Test fun changingViewportOrControlsRestartsTheDebounceAndKeepsOnlyTheLatestState() = runTest(dispatcher) {
        val repo = ResumeTestRepository(); val vm = model(repo)
        vm.bind("entry", body, ReadingEntryKind.START, false, controls); runCurrent()
        vm.observe("entry", body.contentHash, 2, controls); advanceTimeBy(400)
        vm.observe("entry", body.contentHash, 4, controls.copy(budgetMinutes = 5)); advanceTimeBy(400); runCurrent()
        assertTrue(repo.checkpoints.isEmpty())
        advanceTimeBy(200); runCurrent(); assertEquals(4, repo.checkpoints.single().paragraphIndex)
        assertEquals(5, repo.current?.controls?.budgetMinutes)
    }

    @Test fun scrollingAndNonOriginalBodiesCannotProduceCheckpoints() = runTest(dispatcher) {
        val repo = ResumeTestRepository(); val vm = model(repo)
        vm.bind("entry", body, ReadingEntryKind.START, false, controls); runCurrent()
        vm.observe("entry", body.contentHash, 3, controls)
        vm.observe("entry", body.contentHash, null, controls); advanceTimeBy(1000); runCurrent(); assertTrue(repo.checkpoints.isEmpty())
        vm.observe("entry", fingerprint("AI rewrite"), 0, controls); advanceTimeBy(1000); runCurrent(); assertTrue(repo.checkpoints.isEmpty())
        vm.flush("entry"); runCurrent(); assertEquals(3, repo.checkpoints.single().paragraphIndex)
    }

    @Test fun leavingBeforeTheDebounceFlushesAndSurvivesViewModelClearing() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val repo = ResumeTestRepository().apply { checkpointGate = gate }
        val vm = model(repo); vm.bind("entry", body, ReadingEntryKind.START, false, controls); runCurrent()
        vm.observe("entry", body.contentHash, 6, controls); vm.flush("entry"); runCurrent()
        store.clear(); gate.complete(Unit); runCurrent()
        assertEquals(6, repo.current?.paragraphIndex)
    }

    @Test fun completionStaysStickyAndOnlyTouchesTheResumeRepository() = runTest(dispatcher) {
        val repo = ResumeTestRepository(); val vm = model(repo)
        vm.bind("entry", body, ReadingEntryKind.START, false, controls); runCurrent()
        vm.observe("entry", body.contentHash, 6, controls); vm.complete("entry"); runCurrent()
        assertTrue(requireNotNull(repo.current).completed)
        vm.observe("entry", body.contentHash, 7, controls, completed = false); vm.flush(); runCurrent()
        assertTrue(requireNotNull(repo.current).completed); assertEquals(listOf(1L, 2L), repo.checkpoints.map { it.sequence })
    }

    @Test fun aFailedReadAllowsReadingButCannotOverwriteTheUnreadPriorState() = runTest(dispatcher) {
        val repo = ResumeTestRepository(previous()).apply { beginAction = { _, _ -> error("Synthetic corrupt record") } }; val vm = model(repo)
        vm.bind("entry", body, ReadingEntryKind.CONTINUE, false, controls); runCurrent()
        assertNotNull(vm.resolution); assertNotNull(vm.error); assertEquals(ReadingResumeControls(), vm.resolution?.controls)
        vm.observe("entry", body.contentHash, 0, controls); vm.flush(); runCurrent(); assertTrue(repo.checkpoints.isEmpty()); assertEquals(7, repo.current?.paragraphIndex)
    }

    @Test fun invalidViewportAndUnknownControlsNeverCrossTheRepositoryBoundary() = runTest(dispatcher) {
        val repo = ResumeTestRepository(); val vm = model(repo)
        vm.bind("entry", body, ReadingEntryKind.START, false, controls); runCurrent()
        vm.observe("entry", body.contentHash, -1, controls); vm.observe("entry", body.contentHash, 999, controls)
        vm.observe("entry", body.contentHash, 4, controls.copy(toolTab = "Unknown")); vm.flush(); runCurrent()
        assertTrue(repo.checkpoints.isEmpty())
    }

    @Test fun aNewContinueWaitsForEveryQueuedCheckpointEvenWhenTheLastValueWasAlreadySubmitted() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val repo = ResumeTestRepository(previous()).apply { checkpointGate = gate }
        val vm = model(repo); vm.bind("old", body, ReadingEntryKind.CONTINUE, false, controls); runCurrent()
        vm.observe("old", body.contentHash, 5, controls); vm.flush("old"); runCurrent()
        vm.observe("old", body.contentHash, 6, controls); vm.flush("old"); runCurrent()
        // bind calls flush again with latest == submitted. The in-flight tail still has to finish.
        vm.bind("new", body, ReadingEntryKind.CONTINUE, false, ReadingResumeControls()); runCurrent()
        assertEquals(1, repo.beginCalls.size); assertNull(vm.resolution); assertEquals(7, repo.current?.paragraphIndex)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(5, 6), repo.checkpoints.map { it.paragraphIndex })
        assertEquals(2, repo.beginCalls.size); assertEquals(6, vm.resolution?.paragraphIndex)
        assertEquals(controls, vm.resolution?.controls); assertEquals("new", vm.resolution?.key)
    }
}

internal class ResumeTestRepository(initial: ReadingResumeRecord? = null) : ReadingResumeRepository {
    override val library = MutableStateFlow(ReadingResumeLibrary(listOfNotNull(initial)))
    var current: ReadingResumeRecord? = initial; private set
    val beginCalls = CopyOnWriteArrayList<Pair<String, String>>()
    val checkpoints = CopyOnWriteArrayList<ReadingResumeRecord>()
    var beginAction: (suspend (String, String) -> ReadingResumeLease)? = null
    var checkpointGate: CompletableDeferred<Unit>? = null
    private var generation = initial?.generation ?: 0
    fun acquire(articleId: String, bodyHash: String) = ReadingResumeLease(articleId, bodyHash, ++generation, current)
    override suspend fun begin(articleId: String, bodyHash: String): ReadingResumeLease {
        beginCalls += articleId to bodyHash
        return beginAction?.invoke(articleId, bodyHash) ?: acquire(articleId, bodyHash)
    }
    override suspend fun checkpoint(lease: ReadingResumeLease, sequence: Long, body: ArticleBody, paragraphIndex: Int, controls: ReadingResumeControls, completed: Boolean): Boolean {
        checkpointGate?.await()
        val candidate = ReadingResumes.create(lease, sequence, body, paragraphIndex, controls, 1000L + sequence, completed)
        checkpoints += candidate
        if (lease.generation != generation || current?.let { it.generation == generation && it.sequence >= sequence } == true) return false
        current = candidate.copy(completed = completed || current?.let { it.generation == generation && it.completed } == true)
        library.value = ReadingResumeLibrary(listOf(requireNotNull(current)))
        return true
    }
    override suspend fun clear(record: ReadingResumeRecord): Boolean {
        if (record != current) return false
        generation++; current = null; library.value = ReadingResumeLibrary(); return true
    }
}

internal fun resumeFixtureBody(): ArticleBody {
    val text = (1..12).joinToString("\n\n") { index -> "Paragraph $index. " +
        "This synthetic passage describes a different aspect of reading. Readers compare claims with evidence and keep the original context available. ".repeat(3) }
    return ArticleBody("resume-fixture", text, fingerprint(text), 1, Long.MAX_VALUE, "Synthetic local test")
}
