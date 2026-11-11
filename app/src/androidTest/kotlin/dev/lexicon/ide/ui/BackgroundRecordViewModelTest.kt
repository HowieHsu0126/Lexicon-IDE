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
class BackgroundRecordViewModelTest {
    private lateinit var dispatcher: TestDispatcher
    private lateinit var store: ViewModelStore
    private var modelCount = 0
    private val now = System.currentTimeMillis()
    private val query = BackgroundQuery("White House", "The White House announced a proposal.")
    @Before fun before() { dispatcher = StandardTestDispatcher(); Dispatchers.setMain(dispatcher); store = ViewModelStore() }
    @After fun after() { store.clear(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }
    private fun model(repo: BackgroundResearchTestRepository, state: SavedStateHandle = SavedStateHandle()) =
        BackgroundResearchViewModel(repo, state) { now }.also { store.put("record-${modelCount++}", it) }
    private fun repository(multiple: Boolean = false) = BackgroundResearchTestRepository(now).apply {
        cachedLookup = backgroundFixture(query, now, multiple)
    }

    @Test fun fixedHistoryReopensExactReceiptAndRestoresMeaningWithoutNetwork() = runTest(dispatcher) {
        val old = backgroundFixture(query, now - 1000)
        val repo = repository(multiple = true).apply { receipts[old.id] = old }
        val state = SavedStateHandle(); val first = model(repo, state)
        first.bindLookup("history:${old.id}", old.id); runCurrent()
        first.choose("administration"); runCurrent()
        val restored = model(repo, state); restored.bindLookup("history:${old.id}", old.id); runCurrent()
        restored.retrieve(refresh = true); runCurrent()
        assertEquals(old, restored.lookup); assertEquals("administration", restored.selectedCandidateId)
        assertEquals(listOf(old.id, old.id), repo.readLookupCalls)
        assertTrue(repo.cachedQueries.isEmpty()); assertTrue(repo.lookupCalls.isEmpty()); assertTrue(repo.explainCalls.isEmpty())
    }

    @Test fun expiredEvidenceSavesWithoutExplanationAndRepeatedTapDoesNotResave() = runTest(dispatcher) {
        val repo = repository().apply { cachedLookup = backgroundFixture(query.copy(aiAllowed = false), now - BackgroundResearch.CACHE_TTL_MILLIS - 1, false) }
        val vm = model(repo); vm.bindLookup("history", requireNotNull(repo.cachedLookup).id); runCurrent()
        assertTrue(vm.expired); assertNull(vm.explanation)
        vm.saveToKnowledge(); vm.saveToKnowledge(); runCurrent(); vm.saveToKnowledge(); runCurrent()
        assertEquals(1, repo.saveCalls.size); assertNull(repo.saveCalls.single().explanation); assertNull(repo.saveCalls.single().revision)
        assertEquals(vm.lookup, vm.savedRecord?.lookup); assertEquals("building", vm.savedRecord?.selectedCandidateId)
        assertTrue(repo.lookupCalls.isEmpty()); assertTrue(repo.explainCalls.isEmpty())
    }

    @Test fun appendingDisplayedExplanationIsExplicitAndPreservesPersonalMetadata() = runTest(dispatcher) {
        val repo = repository(); val lookup = requireNotNull(repo.cachedLookup)
        val prior = BackgroundRecords.updateMetadata(BackgroundRecords.create(lookup, "building", now), 1, "My comment", listOf("institutions"), now)
        repo.records[prior.id] = prior
        val explanation = backgroundExplanation(lookup, "building"); repo.explanations[lookup.id to "building"] = explanation
        val vm = model(repo); vm.bind("article", query); runCurrent()
        assertEquals(prior, vm.savedRecord); vm.saveToKnowledge(); runCurrent(); assertTrue(repo.saveCalls.isEmpty())
        vm.saveToKnowledge(appendExplanation = true); runCurrent()
        assertEquals(prior.revision, repo.saveCalls.single().revision); assertEquals(explanation, repo.saveCalls.single().explanation)
        assertEquals(prior.comment, vm.savedRecord?.comment); assertEquals(prior.tags, vm.savedRecord?.tags)
        assertEquals(explanation, vm.savedRecord?.explanation); assertTrue(repo.explainCalls.isEmpty())
    }

    @Test fun anExistingSavedExplanationCannotBeReplacedByAnotherDisplayedVersion() = runTest(dispatcher) {
        val repo = repository(); val lookup = requireNotNull(repo.cachedLookup)
        val explanation = backgroundExplanation(lookup, "building")
        val prior = BackgroundRecords.create(lookup, "building", now, explanation)
        repo.records[prior.id] = prior
        repo.explanations[lookup.id to "building"] = explanation.copy(text = "另一个版本。[S1]")
        val vm = model(repo); vm.bind("article", query); runCurrent(); vm.saveToKnowledge(appendExplanation = true); runCurrent()
        assertEquals(prior, vm.savedRecord); assertTrue(repo.saveCalls.isEmpty())
    }

    @Test fun failedSaveKeepsSelectedPreviewUntilExplicitReadAndSaveRetry() = runTest(dispatcher) {
        val repo = repository().apply { saveRecordAction = { error("Synthetic persistence failure") } }
        val vm = model(repo); vm.bind("article", query); runCurrent(); val lookup = vm.lookup
        vm.saveToKnowledge(); runCurrent(); assertNotNull(vm.recordError); assertEquals(lookup, vm.lookup)
        assertEquals("building", vm.selectedCandidateId); assertNull(vm.savedRecord)
        vm.saveToKnowledge(); runCurrent(); assertEquals(1, repo.saveCalls.size)
        repo.saveRecordAction = null; vm.retrySavedRecord(); runCurrent(); vm.saveToKnowledge(); runCurrent()
        assertNull(vm.recordError); assertNotNull(vm.savedRecord); assertEquals(2, repo.saveCalls.size)
        assertTrue(repo.explainCalls.isEmpty()); assertTrue(repo.lookupCalls.isEmpty())
    }

    @Test fun deletingThenReopeningSameHistoryRefreshesSaveStateWithoutLosingMeaning() = runTest(dispatcher) {
        val repo = repository(multiple = true); val lookup = requireNotNull(repo.cachedLookup)
        val vm = model(repo); vm.bindLookup("history", lookup.id); runCurrent(); vm.choose("administration"); runCurrent()
        vm.saveToKnowledge(); runCurrent(); val prior = requireNotNull(vm.savedRecord)
        repo.deleteRecord(prior.id, prior.revision)
        vm.bindLookup("history", lookup.id); runCurrent()
        assertEquals("administration", vm.selectedCandidateId); assertNull(vm.savedRecord)
        vm.saveToKnowledge(); runCurrent(); assertNotNull(vm.savedRecord)
        assertEquals(1, repo.readLookupCalls.size); assertEquals(2, repo.saveCalls.size)
        assertTrue(repo.lookupCalls.isEmpty()); assertTrue(repo.explainCalls.isEmpty())
    }

    @Test fun delayedReadCannotUndoANewerReadOrSuccessfulSave() = runTest(dispatcher) {
        val gate = CompletableDeferred<BackgroundRecord?>(); val repo = repository()
        var reads = 0
        repo.recordReadAction = { _, _ -> if (++reads == 1) withContext(NonCancellable) { gate.await() } else null }
        val vm = model(repo); vm.bind("article", query); runCurrent(); assertTrue(vm.loadingRecord)
        vm.retrySavedRecord(); runCurrent(); assertFalse(vm.loadingRecord)
        vm.saveToKnowledge(); runCurrent(); val saved = requireNotNull(vm.savedRecord)
        gate.complete(null); runCurrent()
        assertEquals(saved, vm.savedRecord); assertNull(vm.recordError); assertFalse(vm.loadingRecord)
    }

    @Test fun delayedReadFailureCannotPoisonANewerRead() = runTest(dispatcher) {
        val gate = CompletableDeferred<BackgroundRecord?>(); val repo = repository()
        var reads = 0
        repo.recordReadAction = { _, _ -> if (++reads == 1) withContext(NonCancellable) { gate.await() } else null }
        val vm = model(repo); vm.bind("article", query); runCurrent(); vm.retrySavedRecord(); runCurrent()
        gate.completeExceptionally(IllegalStateException("Synthetic delayed failure")); runCurrent()
        assertNull(vm.recordError); assertFalse(vm.loadingRecord); vm.saveToKnowledge(); runCurrent(); assertNotNull(vm.savedRecord)
    }

    @Test fun lateSaveCannotShowAnotherMeaningAsSaved() = runTest(dispatcher) {
        val repo = repository(multiple = true); val lookup = requireNotNull(repo.cachedLookup)
        val gate = CompletableDeferred<BackgroundRecord>()
        repo.saveRecordAction = { withContext(NonCancellable) { gate.await() } }
        val vm = model(repo); vm.bind("article", query); runCurrent(); vm.choose("building"); runCurrent()
        vm.saveToKnowledge(); runCurrent(); vm.choose("administration"); runCurrent()
        gate.complete(BackgroundRecords.create(lookup, "building", now)); runCurrent()
        assertEquals("administration", vm.selectedCandidateId); assertNull(vm.savedRecord); assertNull(vm.recordNotice); assertFalse(vm.savingRecord)
    }
}
