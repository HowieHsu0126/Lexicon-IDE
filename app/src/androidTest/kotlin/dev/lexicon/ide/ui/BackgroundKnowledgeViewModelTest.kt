package dev.lexicon.ide.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class BackgroundKnowledgeViewModelTest {
    private lateinit var dispatcher: TestDispatcher
    private lateinit var store: ViewModelStore
    private var modelCount = 0
    private val now = System.currentTimeMillis()
    private val query = BackgroundQuery("White House", "A proposal announced at the institution.",
        source = SourceRef(title = "Synthetic reporting", source = "Example publication"))
    private val lookup get() = backgroundFixture(query, now)
    @Before fun before() { dispatcher = StandardTestDispatcher(); Dispatchers.setMain(dispatcher); store = ViewModelStore() }
    @After fun after() { store.clear(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }
    private fun model(repo: BackgroundResearchTestRepository, state: SavedStateHandle = SavedStateHandle()) =
        BackgroundKnowledgeViewModel(repo, state).also { store.put("knowledge-${modelCount++}", it) }
    private fun record() = BackgroundRecords.create(lookup, "building", now, backgroundExplanation(lookup, "building"))

    @Test fun savedRecordRendersFromItsOwnSnapshotAfterCachesAreGone() = runTest(dispatcher) {
        val repo = BackgroundResearchTestRepository(now); val frozen = record(); val vm = model(repo)
        vm.openRecord(frozen); runCurrent()
        assertEquals(frozen, vm.selectedRecord); assertEquals(frozen.explanation, vm.selectedRecord?.explanation)
        assertTrue(repo.cachedQueries.isEmpty()); assertTrue(repo.readLookupCalls.isEmpty()); assertTrue(repo.lookupCalls.isEmpty())
        assertTrue(repo.recordReadCalls.isEmpty()); assertTrue(repo.explainCalls.isEmpty())
    }

    @Test fun searchFindsContextPublisherSelectedSourcePersonalCommentAndTag() {
        val frozen = BackgroundRecords.updateMetadata(record(), 1, "My comparison", listOf("institutions"), now)
        listOf("white house", "PROPOSAL", "Example publication", "Synthetic reporting", "The building", "Synthetic reference", "comparison", "institutions").forEach {
            assertTrue("Expected search match: $it", backgroundRecordMatches(frozen, it))
        }
        assertFalse(backgroundRecordMatches(frozen, "unrelated"))
        assertTrue(backgroundRecordMatches(frozen, "  "))
    }

    @Test fun savedSearchDoesNotMatchAnUnselectedMeaningOrItsSource() {
        val frozen = record()
        assertFalse(backgroundRecordMatches(frozen, "行政机构"))
        assertFalse(backgroundRecordMatches(frozen, "The administration"))
        assertTrue(backgroundLookupMatches(frozen.lookup, "行政机构"))
        assertTrue(backgroundLookupMatches(frozen.lookup, "The administration"))
    }

    @Test fun reopeningHistoryOnlyStoresExactReceiptIdAndNeverReconstructsAQuery() = runTest(dispatcher) {
        val repo = BackgroundResearchTestRepository(now).apply { receipts[lookup.id] = lookup }
        val state = SavedStateHandle(); val vm = model(repo, state)
        vm.showRecent(true); runCurrent(); assertEquals(listOf(lookup), vm.recent)
        vm.openHistory(lookup.id); runCurrent()
        val restored = model(repo, state)
        assertEquals(lookup.id, restored.historyId); assertTrue(restored.recentView); assertNull(restored.selectedRecord)
        assertEquals(1, repo.recentCalls); assertTrue(repo.cachedQueries.isEmpty()); assertTrue(repo.lookupCalls.isEmpty()); assertTrue(repo.explainCalls.isEmpty())
    }

    @Test fun savedSelectionAndSearchRestoreWhenTheLibraryArrives() = runTest(dispatcher) {
        val repo = BackgroundResearchTestRepository(now); val frozen = record(); val state = SavedStateHandle()
        val first = model(repo, state); first.openRecord(frozen); first.updateSearch("institutions")
        val restored = model(repo, state); restored.restoreRecord(emptyList()); assertNull(restored.selectedRecord)
        restored.restoreRecord(listOf(frozen)); assertEquals(frozen, restored.selectedRecord); assertEquals("institutions", restored.search)
        restored.closeDetail(); restored.restoreRecord(listOf(frozen)); assertNull(restored.selectedRecord)
        assertTrue(repo.recordReadCalls.isEmpty()); assertTrue(repo.lookupCalls.isEmpty())
    }

    @Test fun editingUpdatesOnlyCommentAndTagsWithDisplayedRevision() = runTest(dispatcher) {
        val frozen = record(); val repo = BackgroundResearchTestRepository(now).apply { records[frozen.id] = frozen }
        val vm = model(repo); vm.openRecord(frozen); vm.edit(); vm.update("My note", listOf("policy")); vm.update("Double tap", emptyList()); runCurrent()
        val request = repo.updateCalls.single(); val updated = requireNotNull(vm.selectedRecord)
        assertEquals(frozen.id, request.id); assertEquals(frozen.revision, request.revision)
        assertEquals("My note", updated.comment); assertEquals(listOf("policy"), updated.tags)
        assertEquals(frozen.lookup, updated.lookup); assertEquals(frozen.explanation, updated.explanation)
        assertFalse(vm.editing); assertFalse(vm.mutating); assertNull(vm.mutationError)
    }

    @Test fun staleEditKeepsItsDialogUntilTheReaderReloadsCurrentRecord() = runTest(dispatcher) {
        val frozen = record(); val newer = BackgroundRecords.updateMetadata(frozen, 1, "Elsewhere", emptyList(), now)
        val repo = BackgroundResearchTestRepository(now).apply { records[frozen.id] = newer }
        val vm = model(repo); vm.openRecord(frozen); vm.edit(); vm.update("My draft", listOf("draft")); runCurrent()
        assertTrue(vm.editing); assertEquals(frozen, vm.selectedRecord); assertNotNull(vm.mutationError)
        assertEquals(newer, repo.records[frozen.id])
        vm.reloadSelected(listOf(newer)); assertEquals(newer, vm.selectedRecord); assertFalse(vm.editing)
        vm.edit(); vm.update("My draft", listOf("draft")); runCurrent()
        assertEquals(newer.revision, repo.updateCalls.last().revision); assertNull(vm.mutationError)
    }

    @Test fun deleteNeedsAConfirmationAndCancellationDoesNotMutate() = runTest(dispatcher) {
        val frozen = record(); val repo = BackgroundResearchTestRepository(now).apply { records[frozen.id] = frozen }
        val vm = model(repo); vm.openRecord(frozen); vm.confirmDelete(); runCurrent(); assertTrue(repo.deleteCalls.isEmpty())
        vm.requestDelete(); vm.cancelDelete(); vm.confirmDelete(); runCurrent(); assertTrue(repo.deleteCalls.isEmpty())
        vm.requestDelete(); vm.confirmDelete(); vm.confirmDelete(); runCurrent()
        assertEquals(listOf(frozen.id to frozen.revision), repo.deleteCalls); assertNull(vm.selectedRecord); assertFalse(vm.deleting)
        assertNull(repo.records[frozen.id]); assertNotNull(vm.notice)
    }

    @Test fun failedDeleteRetainsTheRecordAndItsConfirmation() = runTest(dispatcher) {
        val frozen = record(); val repo = BackgroundResearchTestRepository(now).apply {
            records[frozen.id] = frozen; deleteRecordAction = { _, _ -> error("Synthetic revision conflict") }
        }
        val vm = model(repo); vm.openRecord(frozen); vm.requestDelete(); vm.confirmDelete(); runCurrent()
        assertTrue(vm.deleting); assertEquals(frozen, vm.selectedRecord); assertNotNull(vm.mutationError); assertFalse(vm.mutating)
        vm.cancelDelete(); assertFalse(vm.deleting); assertEquals(frozen, repo.records[frozen.id])
    }

    @Test fun lateEditDoesNotReplaceAnotherSelectedRecord() = runTest(dispatcher) {
        val first = record(); val other = BackgroundRecords.create(lookup, "administration", now)
        val gate = CompletableDeferred<BackgroundRecord>(); val repo = BackgroundResearchTestRepository(now).apply {
            records[first.id] = first; updateRecordAction = { withContext(NonCancellable) { gate.await() } }
        }
        val vm = model(repo); vm.openRecord(first); vm.edit(); vm.update("My draft", emptyList()); runCurrent()
        vm.openRecord(other); gate.complete(BackgroundRecords.updateMetadata(first, 1, "My draft", emptyList(), now)); runCurrent()
        assertEquals(other, vm.selectedRecord); assertNull(vm.mutationError); assertFalse(vm.mutating)
    }

    @Test fun recentReadFailureIsVisibleAndRetryDoesNotStartRemoteWork() = runTest(dispatcher) {
        val repo = BackgroundResearchTestRepository(now).apply { recentAction = { error("Synthetic corrupt receipt") } }
        val vm = model(repo); vm.showRecent(true); runCurrent()
        assertNotNull(vm.error); assertFalse(vm.loading)
        repo.recentAction = { listOf(lookup) }; vm.loadRecent(); runCurrent()
        assertNull(vm.error); assertEquals(listOf(lookup), vm.recent); assertEquals(2, repo.recentCalls)
        assertTrue(repo.lookupCalls.isEmpty()); assertTrue(repo.explainCalls.isEmpty())
    }

    @Test fun delayedRecentResultCannotReplaceTheLatestLocalRead() = runTest(dispatcher) {
        val gate = CompletableDeferred<List<BackgroundLookup>>(); var calls = 0
        val newer = backgroundFixture(query, now + 1)
        val repo = BackgroundResearchTestRepository(now).apply {
            recentAction = { if (++calls == 1) withContext(NonCancellable) { gate.await() } else listOf(newer) }
        }
        val vm = model(repo); vm.showRecent(true); runCurrent(); vm.loadRecent(); runCurrent()
        gate.complete(listOf(lookup)); runCurrent()
        assertEquals(listOf(newer), vm.recent); assertFalse(vm.loading); assertNull(vm.error)
    }
}
