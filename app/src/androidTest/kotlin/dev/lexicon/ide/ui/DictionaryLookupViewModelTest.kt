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
import java.io.File

/** Delayed local responses expose ordering bugs without a real dictionary, installed database, or network. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DictionaryLookupViewModelTest {
    private lateinit var dispatcher: TestDispatcher
    private lateinit var store: ViewModelStore
    @Before fun setup() { dispatcher = StandardTestDispatcher(); Dispatchers.setMain(dispatcher); store = ViewModelStore() }
    @After fun cleanup() { store.clear(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }
    private fun model(repository: DictionaryLookupTestRepository) = LexiconViewModel(repository).also { store.put("dictionary-model", it) }
    private fun entry(text: String) = listOf(DictionaryEntry("alpha", "", text))

    @Test fun anEarlierAToBToARequestCannotClearTheNewestLoadingOrPublishItsResult() = runTest(dispatcher) {
        val gates = List(3) { CompletableDeferred<List<DictionaryEntry>>() }
        val repository = DictionaryLookupTestRepository().apply { query = { _, index -> withContext(NonCancellable) { gates[index - 1].await() } } }
        val vm = model(repository); runCurrent()
        try {
            vm.lookup("alpha"); runCurrent()
            vm.lookup("beta"); runCurrent()
            vm.lookup("alpha"); runCurrent()
            assertEquals(listOf("alpha", "beta", "alpha"), repository.lookups)
            gates[0].complete(entry("Old alpha response")); runCurrent()
            assertTrue("The third request is still pending; an earlier alpha must not clear loading", vm.lookingUp)
            assertTrue("An earlier alpha must not appear under the newest alpha request", vm.dictionary.isEmpty())
            gates[2].complete(entry("Current alpha response")); runCurrent()
            assertEquals(entry("Current alpha response"), vm.dictionary)
            assertFalse(vm.lookingUp)
            gates[1].complete(entry("Old beta response")); runCurrent()
            assertEquals(entry("Current alpha response"), vm.dictionary)
        } finally {
            gates.forEach { if (!it.isCompleted) it.complete(emptyList()) }; runCurrent()
        }
    }

    @Test fun dictionaryImportCannotPublishThePreviousDictionaryUnderTheNewName() = runTest(dispatcher) {
        val oldResponse = CompletableDeferred<List<DictionaryEntry>>()
        val newResult = entry("Definition from the newly selected local dictionary")
        val repository = DictionaryLookupTestRepository().apply {
            query = { _, index -> if (index == 1) withContext(NonCancellable) { oldResponse.await() } else newResult }
        }
        val vm = model(repository); runCurrent()
        try {
            vm.lookup("alpha"); runCurrent()
            repository.selected = DictionaryStatus(ready = true, name = "New synthetic dictionary", entryCount = 2, id = "new", format = "MDX")
            vm.dictionaryImported(repository.selected); runCurrent()
            oldResponse.complete(entry("Definition from the previous local dictionary")); runCurrent()
            assertEquals("new", vm.dictionaryStatus.id)
            assertEquals("The same visible target must be looked up in the new dictionary", newResult, vm.dictionary)
            assertEquals(listOf("alpha", "alpha"), repository.lookups)
            assertFalse(vm.lookingUp)
        } finally {
            if (!oldResponse.isCompleted) oldResponse.complete(emptyList()); runCurrent()
        }
    }

    @Test fun openingQueriesImmediatelyButContinuousEditsWaitForTheLatestQuietPeriod() = runTest(dispatcher) {
        val repo = DictionaryLookupTestRepository().apply { query = { word, _ -> listOf(DictionaryEntry(word, "", "Current $word")) } }
        val vm = model(repo); runCurrent()
        vm.openDictionaryLookup("capture", " alpha ")
        assertTrue(vm.lookingUp); runCurrent()
        assertEquals(listOf("alpha"), repo.lookups)
        assertEquals("alpha", vm.dictionary.single().word)
        vm.editDictionaryLookup("capture", "beta")
        assertTrue("Invalidation happens before the debounce", vm.dictionary.isEmpty())
        assertTrue(vm.lookingUp); assertNull(vm.dictionaryLookupFor("capture", "alpha"))
        advanceTimeBy(179); runCurrent(); assertEquals(listOf("alpha"), repo.lookups)
        vm.editDictionaryLookup("capture", "gamma")
        advanceTimeBy(179); runCurrent(); assertEquals(listOf("alpha"), repo.lookups)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("alpha", "gamma"), repo.lookups)
        assertEquals("gamma", vm.dictionaryLookupFor("capture", " gamma ")?.entries?.single()?.word)
        vm.editDictionaryLookup("capture", " gamma  "); advanceUntilIdle()
        assertEquals("Whitespace alone does not restart a resolved normalized query", listOf("alpha", "gamma"), repo.lookups)
    }

    @Test fun lateFailureCannotPublishAnErrorOrClearAnotherRequestsLoading() = runTest(dispatcher) {
        val failure = CompletableDeferred<Unit>()
        val current = CompletableDeferred<List<DictionaryEntry>>()
        val repo = DictionaryLookupTestRepository().apply { query = { _, index ->
            if (index == 1) withContext(NonCancellable) { failure.await(); error("Synthetic old failure") }
            else current.await()
        } }
        val vm = model(repo); runCurrent()
        try {
            vm.openDictionaryLookup("capture", "alpha"); runCurrent()
            vm.editDictionaryLookup("capture", "beta"); advanceTimeBy(180); runCurrent()
            failure.complete(Unit); runCurrent()
            assertTrue(vm.lookingUp); assertTrue(vm.dictionary.isEmpty())
            assertNull(vm.dictionaryLookupError); assertNull(vm.notice)
            current.complete(entry("Only the latest request may publish")); runCurrent()
            assertFalse(vm.lookingUp); assertNull(vm.dictionaryLookupError)
            assertEquals(entry("Only the latest request may publish"), vm.dictionary)
        } finally {
            failure.complete(Unit); current.complete(emptyList()); runCurrent()
        }
    }

    @Test fun closingInvalidatesPendingOutputAndImportDoesNotReopenTheClosedLookup() = runTest(dispatcher) {
        val response = CompletableDeferred<List<DictionaryEntry>>()
        val repo = DictionaryLookupTestRepository().apply { query = { _, index ->
            if (index == 1) withContext(NonCancellable) { response.await() } else entry("Reopened")
        } }
        val vm = model(repo); runCurrent()
        try {
            vm.openDictionaryLookup("capture", "alpha"); runCurrent()
            vm.closeDictionaryLookup("capture")
            assertFalse(vm.lookingUp); assertTrue(vm.dictionary.isEmpty())
            assertNull(vm.dictionaryLookupFor("capture", "alpha"))
            vm.dictionaryImported(repo.selected.copy(id = "imported")); runCurrent()
            response.complete(entry("Closed response")); runCurrent()
            assertTrue(vm.dictionary.isEmpty()); assertFalse(vm.lookingUp); assertNull(vm.dictionaryLookupError)
            assertEquals(listOf("alpha"), repo.lookups)
            vm.openDictionaryLookup("capture", "alpha"); runCurrent()
            assertEquals(entry("Reopened"), vm.dictionary)
            assertEquals(listOf("alpha", "alpha"), repo.lookups)
        } finally { response.complete(emptyList()); runCurrent() }
    }

    @Test fun identicalWordsInDifferentCapturesRemainIsolatedBeforeAndAfterTheirEffects() = runTest(dispatcher) {
        val response = CompletableDeferred<List<DictionaryEntry>>()
        val repo = DictionaryLookupTestRepository().apply { query = { _, index ->
            if (index == 1) withContext(NonCancellable) { response.await() } else entry("Second capture")
        } }
        val vm = model(repo); runCurrent()
        try {
            vm.openDictionaryLookup("first", "alpha"); runCurrent()
            assertNull("Before the new panel opens, it cannot read the first panel's state", vm.dictionaryLookupFor("second", "alpha"))
            vm.openDictionaryLookup("second", "alpha"); runCurrent()
            vm.closeDictionaryLookup("first")
            response.complete(entry("First capture")); runCurrent()
            assertEquals(entry("Second capture"), vm.dictionaryLookupFor("second", "alpha")?.entries)
            assertNull(vm.dictionaryLookupFor("first", "alpha")); assertFalse(vm.lookingUp)
        } finally { response.complete(emptyList()); runCurrent() }
    }

    @Test fun aBoundErrorRetriesImmediatelyAndEditingClearsItWithoutAStaleGlobalNotice() = runTest(dispatcher) {
        val repo = DictionaryLookupTestRepository().apply { query = { word, index ->
            if (index <= 2) error("Synthetic local failure") else listOf(DictionaryEntry(word, "", "Recovered"))
        } }
        val vm = model(repo); runCurrent()
        vm.openDictionaryLookup("capture", "alpha"); runCurrent()
        assertNotNull(vm.dictionaryLookupFor("capture", "alpha")?.error); assertNull(vm.notice)
        vm.retryDictionaryLookup("other capture", "alpha"); runCurrent()
        assertEquals(1, repo.lookups.size)
        vm.retryDictionaryLookup("capture", "alpha")
        assertNull(vm.dictionaryLookupError); assertTrue(vm.lookingUp); runCurrent()
        assertEquals(2, repo.lookups.size); assertNotNull(vm.dictionaryLookupError)
        vm.editDictionaryLookup("capture", "beta")
        assertNull(vm.dictionaryLookupError); assertTrue(vm.lookingUp)
        vm.retryDictionaryLookup("capture", "alpha"); runCurrent()
        assertEquals(2, repo.lookups.size)
        advanceTimeBy(180); runCurrent()
        assertEquals(listOf("alpha", "alpha", "beta"), repo.lookups)
        assertEquals("beta", vm.dictionary.single().word); assertNull(vm.notice)
    }

    @Test fun clearingTheTargetImmediatelyCancelsPendingInputAndDoesNotQueryBlankText() = runTest(dispatcher) {
        val repo = DictionaryLookupTestRepository().apply { query = { _, _ -> entry("Resolved") } }
        val vm = model(repo); runCurrent()
        vm.openDictionaryLookup("capture", "alpha"); runCurrent()
        vm.editDictionaryLookup("capture", "beta")
        vm.editDictionaryLookup("capture", " \t ")
        assertTrue(vm.dictionary.isEmpty()); assertFalse(vm.lookingUp); assertNull(vm.dictionaryLookupError)
        advanceUntilIdle()
        assertEquals(listOf("alpha"), repo.lookups)
        assertNotNull(vm.dictionaryLookupFor("capture", "")); assertNull(vm.dictionaryLookupFor("capture", "alpha"))
    }

    @Test fun importingDuringAnEditQueriesTheLatestTargetInTheNewDictionaryImmediately() = runTest(dispatcher) {
        val repo = DictionaryLookupTestRepository().apply { query = { word, _ -> listOf(DictionaryEntry(word, "", selected.name)) } }
        val vm = model(repo); runCurrent()
        vm.openDictionaryLookup("capture", "alpha"); runCurrent()
        vm.editDictionaryLookup("capture", "beta"); advanceTimeBy(60); runCurrent()
        repo.selected = repo.selected.copy(id = "new", name = "New local dictionary")
        vm.dictionaryImported(repo.selected)
        assertTrue(vm.dictionary.isEmpty()); assertTrue(vm.lookingUp)
        runCurrent()
        assertEquals(listOf("alpha", "beta"), repo.lookups)
        assertEquals("New local dictionary", vm.dictionary.single().definition)
        advanceUntilIdle(); assertEquals(listOf("alpha", "beta"), repo.lookups)
    }

    @Test fun switchingClearsOutputBeforeTheRepositoryReturnsAndReselectingKeepsTheSafeResult() = runTest(dispatcher) {
        val selectionResponse = CompletableDeferred<DictionaryStatus>()
        val repo = DictionaryLookupTestRepository().apply {
            query = { _, _ -> entry(this.selected.name) }
            selecting = { selectionResponse.await().also { this.selected = it } }
        }
        val vm = model(repo); runCurrent()
        try {
            vm.openDictionaryLookup("capture", "alpha"); runCurrent()
            vm.selectDictionary("original"); runCurrent()
            assertEquals(1, repo.lookups.size); assertTrue(repo.selections.isEmpty())
            vm.selectDictionary("next")
            assertTrue(vm.dictionary.isEmpty()); assertTrue(vm.lookingUp)
            runCurrent(); assertEquals(1, repo.lookups.size)
            selectionResponse.complete(repo.selected.copy(id = "next", name = "Next dictionary")); runCurrent()
            assertEquals("Next dictionary", vm.dictionary.single().definition)
            assertEquals(listOf("alpha", "alpha"), repo.lookups)
            assertEquals("next", vm.dictionaryStatus.id); assertNull(vm.busy)
            vm.selectDictionary("next"); advanceUntilIdle()
            assertEquals(listOf("next"), repo.selections); assertEquals(2, repo.lookups.size)
        } finally { selectionResponse.complete(repo.selected); runCurrent() }
    }

    @Test fun failedDictionarySelectionResumesLookupWithThePreviousDictionary() = runTest(dispatcher) {
        val repo = DictionaryLookupTestRepository().apply {
            query = { _, _ -> entry(selected.name) }; selecting = { error("Synthetic selection failure") }
        }
        val vm = model(repo); runCurrent()
        vm.openDictionaryLookup("capture", "alpha"); runCurrent()
        vm.selectDictionary("unavailable"); runCurrent()
        assertEquals("original", vm.dictionaryStatus.id)
        assertEquals(entry(repo.selected.name), vm.dictionary)
        assertFalse(vm.lookingUp); assertNull(vm.busy); assertNotNull(vm.notice)
    }

    @Test fun slowInitializationCannotReplaceADictionaryAlreadyImportedByTheUser() = runTest(dispatcher) {
        val initialized = CompletableDeferred<Unit>()
        val repo = DictionaryLookupTestRepository().apply { initializing = { initialized.await() } }
        val vm = model(repo); runCurrent()
        try {
            vm.dictionaryImported(repo.selected.copy(id = "new", name = "User selected dictionary")); runCurrent()
            initialized.complete(Unit); runCurrent()
            assertEquals("new", vm.dictionaryStatus.id)
            assertEquals("User selected dictionary", vm.dictionaryStatus.name)
        } finally { initialized.complete(Unit); runCurrent() }
    }
}

internal class DictionaryLookupTestRepository : AppRepository {
    override val articles = MutableStateFlow<List<Article>>(emptyList())
    override val sources = MutableStateFlow<List<Source>>(emptyList())
    override val captures = MutableStateFlow<List<Capture>>(emptyList())
    override val cards = MutableStateFlow<List<Card>>(emptyList())
    override val settings = MutableStateFlow(AppSettings())
    override val status = MutableStateFlow("[ready]")
    override val jobs = MutableStateFlow<List<AiJob>>(emptyList())
    var selected = DictionaryStatus(ready = true, name = "Synthetic local dictionary", entryCount = 2, id = "original", format = "MDX")
    val lookups = mutableListOf<String>()
    val selections = mutableListOf<String>()
    var query: suspend (String, Int) -> List<DictionaryEntry> = { _, _ -> emptyList() }
    var initializing: suspend () -> Unit = {}
    var selecting: suspend (String) -> DictionaryStatus = { id -> selected.copy(id = id, name = "Synthetic $id").also { selected = it } }
    override suspend fun lookup(text: String): List<DictionaryEntry> { lookups += text; return query(text, lookups.size) }
    override suspend fun initialize() = initializing()
    override suspend fun sync() = SyncResult(0, 0, emptyList())
    override suspend fun setSourceEnabled(id: String, enabled: Boolean) = Unit
    override suspend fun markArticleRead(id: String) = Unit
    override suspend fun openArticle(id: String): ArticleBody? = null
    override suspend fun classifyPending() = Unit
    override suspend fun saveCapture(capture: Capture) = error("Lookup tests must not save captures")
    override suspend fun updateSettings(settings: AppSettings) { this.settings.value = settings }
    override suspend fun hasApiKey() = false
    override suspend fun setApiKey(key: String) = error("Lookup tests must not write keys")
    override suspend fun clearApiKey() = Unit
    override suspend fun dictionaryStatus() = selected
    override suspend fun importDictionary(file: File) = selected
    override suspend fun selectDictionary(id: String): DictionaryStatus { selections += id; return selecting(id) }
    override suspend fun analyzeCapture(capture: Capture, deep: Boolean): AnalysisResult = error("Lookup tests must not call AI")
    override suspend fun saveCard(capture: Capture, analysis: AnalysisResult): Card = error("Lookup tests must not write cards")
    override suspend fun updateCard(card: Card) = error("Lookup tests must not write cards")
    override suspend fun reviewCard(cardId: String, rating: Rating, expectedReps: Int, now: Long) = ReviewOutcome.MISSING
    override suspend fun exportAnki(destination: File) = destination
    override suspend fun confirmAnkiExport(exportFile: File) = Unit
    override suspend fun exportEvents(destination: File) = destination
    override suspend fun recordEvent(type: String, value: Int) = Unit
}
