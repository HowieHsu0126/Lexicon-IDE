package dev.lexicon.ide.ui

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import java.io.File
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ReadingNoteSaveViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher
    private lateinit var store: ViewModelStore
    private val note = ReadingNote("save-note", "article", "body", "An exact quotation.", "A reader's own interpretation.",
        listOf("语境"), 1, SourceRef("Synthetic article"))

    @Before fun setUp() {
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        store = ViewModelStore()
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        try { store.clear(); scheduler.runCurrent() }
        finally { Dispatchers.resetMain() }
    }

    @Test fun duplicateSaveIsRejectedWhileOriginalWaitsAndTrailingCallbackMeansSuccess() = runTest(dispatcher) {
        val repository = NoteSaveReadingRepository()
        val model = model(repository)
        var successfulCallbacks = 0
        var duplicateError: String? = null
        model.saveNote(note) {
            assertEquals(listOf(note), repository.persisted)
            assertFalse(model.noteBusy)
            successfulCallbacks++
        }
        assertTrue(model.noteBusy)
        model.saveNote(note, { successfulCallbacks++ }, { duplicateError = it })
        runCurrent()
        assertEquals(listOf(note), repository.attempted)
        assertTrue(repository.persisted.isEmpty())
        assertEquals(0, successfulCallbacks)
        assertEquals("笔记正在保存，请稍后重试", duplicateError)
        repository.gate.complete(Unit)
        runCurrent()
        assertEquals(1, successfulCallbacks)
        assertEquals(listOf(note), repository.persisted)
        assertFalse(model.noteBusy)
        assertNull(model.error)
    }

    @Test fun failedSaveReportsBoundedErrorAndRetryPersistsTheSameDraft() = runTest(dispatcher) {
        val repository = NoteSaveReadingRepository()
        val model = model(repository)
        var successes = 0
        val failures = mutableListOf<String>()
        model.saveNote(note, { successes++ }, { failures += it })
        runCurrent()
        repository.gate.completeExceptionally(IOException("private internal storage details"))
        runCurrent()
        assertEquals(0, successes)
        assertEquals(listOf("笔记未保存，请重试"), failures)
        assertEquals("笔记未保存，请重试", model.error)
        assertFalse(model.noteBusy)
        assertTrue(repository.persisted.isEmpty())

        repository.gate = CompletableDeferred()
        model.saveNote(note, { successes++ }, { failures += it })
        assertNull(model.error)
        assertTrue(model.noteBusy)
        runCurrent()
        assertEquals(listOf(note, note), repository.attempted)
        assertEquals(0, successes)
        repository.gate.complete(Unit)
        runCurrent()
        assertEquals(1, successes)
        assertEquals(listOf(note), repository.persisted)
        assertEquals(1, failures.size)
        assertFalse(model.noteBusy)
    }

    @Test fun cancellationDoesNotReportAStorageFailureOrClaimSuccess() = runTest(dispatcher) {
        val repository = NoteSaveReadingRepository()
        val model = model(repository)
        var success = false
        var failure: String? = null
        model.saveNote(note, { success = true }, { failure = it })
        runCurrent()
        store.clear()
        runCurrent()
        assertFalse(success)
        assertNull(failure)
        assertNull(model.error)
        assertFalse(model.noteBusy)
        assertTrue(repository.persisted.isEmpty())
    }

    private fun model(repository: ReadingRepository) = ReadingViewModel(NoteSaveAppRepository(repository)).also {
        store.put("reading", it)
    }
}

private class NoteSaveReadingRepository : ReadingRepository by UiTestReadingRepository() {
    var gate = CompletableDeferred<Unit>()
    val attempted = mutableListOf<ReadingNote>()
    val persisted = mutableListOf<ReadingNote>()
    override suspend fun saveNote(note: ReadingNote) {
        attempted += note
        gate.await()
        persisted += note
    }
}

private class NoteSaveAppRepository(override val reading: ReadingRepository) : AppRepository {
    override val articles = MutableStateFlow<List<Article>>(emptyList())
    override val sources = MutableStateFlow<List<Source>>(emptyList())
    override val captures = MutableStateFlow<List<Capture>>(emptyList())
    override val cards = MutableStateFlow<List<Card>>(emptyList())
    override val settings = MutableStateFlow(AppSettings())
    override val status = MutableStateFlow("[ready]")
    override val jobs = MutableStateFlow<List<AiJob>>(emptyList())
    override suspend fun initialize() = Unit
    override suspend fun sync() = SyncResult(0, 0, emptyList())
    override suspend fun setSourceEnabled(id: String, enabled: Boolean) = Unit
    override suspend fun markArticleRead(id: String) = Unit
    override suspend fun openArticle(id: String): ArticleBody? = null
    override suspend fun classifyPending() = Unit
    override suspend fun saveCapture(capture: Capture) = Unit
    override suspend fun lookup(text: String) = emptyList<DictionaryEntry>()
    override suspend fun updateSettings(settings: AppSettings) { this.settings.value = settings }
    override suspend fun hasApiKey() = false
    override suspend fun setApiKey(key: String) = Unit
    override suspend fun clearApiKey() = Unit
    override suspend fun dictionaryStatus() = DictionaryStatus()
    override suspend fun importDictionary(file: File) = DictionaryStatus()
    override suspend fun analyzeCapture(capture: Capture, deep: Boolean): AnalysisResult = error("Unexpected vocabulary request")
    override suspend fun saveCard(capture: Capture, analysis: AnalysisResult): Card = error("Unexpected vocabulary write")
    override suspend fun updateCard(card: Card) = Unit
    override suspend fun reviewCard(cardId: String, rating: Rating, expectedReps: Int, now: Long) = ReviewOutcome.APPLIED
    override suspend fun exportAnki(destination: File) = destination
    override suspend fun confirmAnkiExport(exportFile: File) = Unit
    override suspend fun exportEvents(destination: File) = destination
    override suspend fun recordEvent(type: String, value: Int) = Unit
}
