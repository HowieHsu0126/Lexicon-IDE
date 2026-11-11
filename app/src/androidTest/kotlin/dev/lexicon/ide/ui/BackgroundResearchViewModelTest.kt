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
class BackgroundResearchViewModelTest {
    private lateinit var dispatcher: TestDispatcher
    private lateinit var store: ViewModelStore
    private var modelCount = 0
    private val now = System.currentTimeMillis()
    private val query = BackgroundQuery("White House", "The White House announced a new proposal.")
    @Before fun before() { dispatcher = StandardTestDispatcher(); Dispatchers.setMain(dispatcher); store = ViewModelStore() }
    @After fun after() { store.clear(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }
    private fun model(repo: BackgroundResearchTestRepository, state: SavedStateHandle = SavedStateHandle()) =
        BackgroundResearchViewModel(repo, state) { now }.also { store.put("background-${modelCount++}", it) }

    @Test fun openingAndEditingReadOnlyCachesWithoutAnyNetworkOrGeneration() = runTest(dispatcher) {
        val repo = BackgroundResearchTestRepository(now); val vm = model(repo)
        vm.bind("article", query); runCurrent()
        assertEquals(listOf(query), repo.cachedQueries); assertNull(vm.lookup)
        vm.update(query.copy(term = "Electoral College")); runCurrent()
        assertEquals(2, repo.cachedQueries.size); assertTrue(repo.lookupCalls.isEmpty()); assertTrue(repo.explainCalls.isEmpty())
    }

    @Test fun multipleMeaningsRequireChoiceThenASeparateExplicitExplanation() = runTest(dispatcher) {
        val repo = BackgroundResearchTestRepository(now); val vm = model(repo)
        vm.bind("article", query); runCurrent(); vm.retrieve(); runCurrent()
        assertNotNull(vm.lookup); assertNull(vm.selectedCandidateId); assertTrue(repo.explainCalls.isEmpty())
        vm.explain(); runCurrent(); assertTrue(repo.explainCalls.isEmpty())
        vm.choose("administration"); runCurrent(); assertTrue(repo.explainCalls.isEmpty())
        vm.explain(); runCurrent()
        assertEquals(1, repo.explainCalls.size); assertEquals("administration", repo.explainCalls.single().second)
        assertNotNull(vm.explanation)
    }

    @Test fun staleLookupFromPreviousInputCannotReplaceCurrentConcept() = runTest(dispatcher) {
        val gate = CompletableDeferred<BackgroundLookup>(); val repo = BackgroundResearchTestRepository(now)
        repo.lookupAction = { value, _ -> if (value.term == query.term) withContext(NonCancellable) { gate.await() } else backgroundFixture(value, now) }
        val vm = model(repo); vm.bind("article", query); runCurrent(); vm.retrieve(); runCurrent()
        val other = query.copy(term = "Electoral College")
        vm.update(other); runCurrent(); vm.retrieve(); runCurrent()
        gate.complete(backgroundFixture(query, now)); runCurrent()
        assertEquals(other, vm.lookup?.query); assertFalse(vm.lookingUp); assertNull(vm.error)
    }

    @Test fun lateExplanationCannotReplaceNewlyChosenMeaning() = runTest(dispatcher) {
        val gate = CompletableDeferred<StudyAssist>(); val repo = BackgroundResearchTestRepository(now)
        repo.cachedLookup = backgroundFixture(query, now)
        repo.explainAction = { lookup, candidate, _ -> withContext(NonCancellable) { gate.await() } }
        val vm = model(repo); vm.bind("article", query); runCurrent(); vm.choose("building"); runCurrent(); vm.explain(); runCurrent()
        vm.choose("administration"); runCurrent()
        gate.complete(backgroundExplanation(requireNotNull(repo.cachedLookup), "building")); runCurrent()
        assertEquals("administration", vm.selectedCandidateId); assertNull(vm.explanation); assertFalse(vm.explaining)
        assertEquals(1, repo.explainCalls.size)
    }

    @Test fun delayedCachedSnapshotCannotRevertAnExplicitRefreshOfTheSameQuery() = runTest(dispatcher) {
        val gate = CompletableDeferred<BackgroundLookup?>()
        val old = backgroundFixture(query, now - 1000)
        val fresh = backgroundFixture(query, now)
        val repo = BackgroundResearchTestRepository(now).apply {
            cacheAction = { withContext(NonCancellable) { gate.await() } }
            lookupAction = { _, _ -> fresh }
        }
        val vm = model(repo); vm.bind("article", query); runCurrent()
        vm.retrieve(refresh = true); runCurrent(); vm.choose("administration"); runCurrent()
        gate.complete(old); runCurrent()
        assertEquals(fresh, vm.lookup); assertEquals("administration", vm.selectedCandidateId)
        assertNull(vm.error); assertFalse(vm.loadingCache); assertFalse(vm.lookingUp)
    }

    @Test fun delayedCacheFailureCannotReplaceSuccessfulRefreshState() = runTest(dispatcher) {
        val gate = CompletableDeferred<BackgroundLookup?>()
        val repo = BackgroundResearchTestRepository(now).apply { cacheAction = { withContext(NonCancellable) { gate.await() } } }
        val vm = model(repo); vm.bind("article", query); runCurrent(); vm.retrieve(refresh = true); runCurrent()
        val successful = vm.lookup
        gate.completeExceptionally(IllegalStateException("Synthetic delayed cache failure")); runCurrent()
        assertEquals(successful, vm.lookup); assertNull(vm.error); assertFalse(vm.loadingCache); assertFalse(vm.lookingUp)
    }

    @Test fun successfulLocalCacheRetryClearsItsPreviousReadError() = runTest(dispatcher) {
        var reads = 0
        val fixture = backgroundFixture(query, now)
        val repo = BackgroundResearchTestRepository(now).apply { cacheAction = { if (++reads == 1) error("Synthetic read error") else fixture } }
        val vm = model(repo); vm.bind("article", query); runCurrent(); assertNotNull(vm.error)
        vm.retryCache(); runCurrent()
        assertEquals(fixture, vm.lookup); assertNull(vm.error); assertEquals(2, reads)
        assertTrue(repo.lookupCalls.isEmpty()); assertTrue(repo.explainCalls.isEmpty())
    }

    @Test fun changingDraftImmediatelyHidesPreviousExplanation() = runTest(dispatcher) {
        val repo = BackgroundResearchTestRepository(now).apply { cachedLookup = backgroundFixture(query, now, multiple = false) }
        val snapshot = requireNotNull(repo.cachedLookup)
        repo.explanations[snapshot.id to "building"] = backgroundExplanation(snapshot, "building")
        val vm = model(repo); vm.bind("article", query); runCurrent(); assertNotNull(vm.explanation)
        vm.update(query.copy(term = "Congress"))
        assertNull(vm.explanation); assertNull(vm.lookup); assertNull(vm.selectedCandidateId)
        runCurrent(); assertTrue(repo.explainCalls.isEmpty()); assertTrue(repo.lookupCalls.isEmpty())
    }

    @Test fun selectedMeaningRestoresFromSavedStateWithNoKeyAndNoNewRequest() = runTest(dispatcher) {
        val repo = BackgroundResearchTestRepository(now).apply { cachedLookup = backgroundFixture(query, now) }
        val snapshot = requireNotNull(repo.cachedLookup)
        repo.explanations[snapshot.id to "administration"] = backgroundExplanation(snapshot, "administration")
        val state = SavedStateHandle(); val first = model(repo, state)
        first.bind("article", query); runCurrent(); first.choose("administration"); runCurrent()
        val restored = model(repo, state); restored.bind("article", query.copy(term = "Default ignored during restoration")); runCurrent()
        assertEquals(query, restored.input); assertEquals("administration", restored.selectedCandidateId)
        assertEquals(first.explanation, restored.explanation)
        assertTrue(repo.lookupCalls.isEmpty()); assertTrue(repo.explainCalls.isEmpty())
    }

    @Test fun failedExplanationKeepsEvidenceAndRequiresAnExplicitRetry() = runTest(dispatcher) {
        val repo = BackgroundResearchTestRepository(now).apply {
            cachedLookup = backgroundFixture(query, now, multiple = false)
            explainAction = { lookup, candidate, retry ->
                if (!retry) throw dev.lexicon.data.ApiFailure("retry_required", "解释中断，请明确重试。")
                backgroundExplanation(lookup, candidate)
            }
        }
        val vm = model(repo); vm.bind("article", query); runCurrent(); vm.explain(); runCurrent()
        assertNotNull(vm.lookup); assertTrue(vm.explanationRetryNeeded); assertNull(vm.explanation)
        vm.bind("article", query); runCurrent(); assertEquals(1, repo.explainCalls.size)
        vm.explain(explicitRetry = true); runCurrent()
        assertEquals(listOf(false, true), repo.explainCalls.map { it.third }); assertNotNull(vm.explanation)
    }

    @Test fun expiredEvidenceRemainsReadableAndRefreshFailureRetainsIt() = runTest(dispatcher) {
        val repo = BackgroundResearchTestRepository(now).apply {
            cachedLookup = backgroundFixture(query, now - BackgroundResearch.CACHE_TTL_MILLIS - 1, multiple = false)
            lookupAction = { _, _ -> throw IllegalStateException("Synthetic failure") }
        }
        val vm = model(repo); vm.bind("article", query); runCurrent(); val before = vm.lookup
        assertTrue(vm.expired); vm.explain(); runCurrent(); assertTrue(repo.explainCalls.isEmpty())
        vm.retrieve(refresh = true); runCurrent(); assertEquals(before, vm.lookup); assertNotNull(vm.error)
        assertEquals(true, repo.lookupCalls.single().second)
    }

    @Test fun restrictedAiStillAllowsPublicLookupAndSavedExplanations() = runTest(dispatcher) {
        val restricted = query.copy(aiAllowed = false); val repo = BackgroundResearchTestRepository(now)
        val vm = model(repo); vm.bind("article", restricted); runCurrent(); vm.retrieve(); runCurrent()
        assertEquals(1, repo.lookupCalls.size); vm.choose("building"); runCurrent(); vm.explain(); runCurrent()
        assertTrue(repo.explainCalls.isEmpty()); assertNotNull(vm.lookup)
    }

    @Test fun cachedExplanationReadFailureCanRetryWithoutModelWork() = runTest(dispatcher) {
        val repo = BackgroundResearchTestRepository(now).apply {
            cachedLookup = backgroundFixture(query, now, multiple = false); explanationReadFailures = 1
        }
        val snapshot = requireNotNull(repo.cachedLookup)
        repo.explanations[snapshot.id to "building"] = backgroundExplanation(snapshot, "building")
        val vm = model(repo); vm.bind("article", query); runCurrent()
        assertNotNull(vm.explanationError); assertFalse(vm.explanationRetryNeeded)
        vm.retryExplanationCache(); runCurrent()
        assertNotNull(vm.explanation); assertNull(vm.explanationError); assertTrue(repo.explainCalls.isEmpty())
    }

    @Test fun longAmbiguousParagraphRequiresExplicitContextInsteadOfFirstOccurrence() {
        val text = "Congress " + "background detail ".repeat(90) + "Congress considers the proposal."
        val body = ArticleBody("context", text, fingerprint(text), 1, Long.MAX_VALUE, "Synthetic")
        assertEquals(text, backgroundParagraphContext(body, 0, "Congress"))
        val tail = text.replaceFirst("Congress", "The committee")
        val unique = body.copy(text = tail, contentHash = fingerprint(tail))
        val window = backgroundParagraphContext(unique, 0, "Congress")
        assertTrue(window.length <= 1200); assertTrue(window.contains("Congress considers"))
    }
}

internal class BackgroundResearchTestRepository(private val now: Long = System.currentTimeMillis()) : BackgroundRepository {
    var cachedLookup: BackgroundLookup? = null
    val cachedQueries = mutableListOf<BackgroundQuery>()
    val lookupCalls = mutableListOf<Pair<BackgroundQuery, Boolean>>()
    val explainCalls = mutableListOf<Triple<String, String, Boolean>>()
    val explanations = mutableMapOf<Pair<String, String>, StudyAssist>()
    var explanationReadFailures = 0
    val receipts = mutableMapOf<String, BackgroundLookup>()
    val records = mutableMapOf<String, BackgroundRecord>()
    val readLookupCalls = mutableListOf<String>()
    var recentCalls = 0
    val recordReadCalls = mutableListOf<Pair<String, String>>()
    val saveCalls = mutableListOf<RecordSaveRequest>()
    val updateCalls = mutableListOf<RecordUpdateRequest>()
    val deleteCalls = mutableListOf<Pair<String, Int>>()
    var recordReadAction: (suspend (String, String) -> BackgroundRecord?)? = null
    var recentAction: (suspend () -> List<BackgroundLookup>)? = null
    var saveRecordAction: (suspend (RecordSaveRequest) -> BackgroundRecord)? = null
    var updateRecordAction: (suspend (RecordUpdateRequest) -> BackgroundRecord)? = null
    var deleteRecordAction: (suspend (String, Int) -> Unit)? = null
    var cacheAction: (suspend (BackgroundQuery) -> BackgroundLookup?)? = null
    var lookupAction: suspend (BackgroundQuery, Boolean) -> BackgroundLookup = { query, _ -> backgroundFixture(query, now) }
    var explainAction: suspend (BackgroundLookup, String, Boolean) -> StudyAssist = { lookup, candidate, _ -> backgroundExplanation(lookup, candidate) }
    override suspend fun cached(query: BackgroundQuery): BackgroundLookup? {
        cachedQueries += query
        cacheAction?.let { return it(query) }
        return cachedLookup?.takeIf { BackgroundResearch.queryKey(it.query) == BackgroundResearch.queryKey(query) }
    }
    override suspend fun lookup(query: BackgroundQuery, refresh: Boolean): BackgroundLookup {
        lookupCalls += query to refresh
        return lookupAction(query, refresh).also { cachedLookup = it }
    }
    override suspend fun cachedExplanation(lookupId: String, candidateId: String): StudyAssist? {
        if (explanationReadFailures > 0) { explanationReadFailures--; error("Synthetic read failure") }
        return explanations[lookupId to candidateId]
    }
    override suspend fun explain(lookupId: String, candidateId: String, explicitRetry: Boolean): StudyAssist {
        explainCalls += Triple(lookupId, candidateId, explicitRetry)
        return explainAction(requireNotNull(cachedLookup).also { check(it.id == lookupId) }, candidateId, explicitRetry)
            .also { explanations[lookupId to candidateId] = it }
    }
    override suspend fun readLookup(id: String): BackgroundLookup? {
        readLookupCalls += id
        return receipts[id] ?: cachedLookup?.takeIf { it.id == id }
    }
    override suspend fun recentLookups(): List<BackgroundLookup> {
        recentCalls++
        return recentAction?.invoke() ?: (receipts.values + listOfNotNull(cachedLookup)).distinctBy { it.id }.sortedByDescending { it.createdAt }
    }
    override suspend fun savedRecord(lookupId: String, candidateId: String): BackgroundRecord? {
        recordReadCalls += lookupId to candidateId
        recordReadAction?.let { return it(lookupId, candidateId) }
        return records[BackgroundRecords.id(lookupId, candidateId)]
    }
    override suspend fun saveRecord(lookupId: String, candidateId: String, displayedExplanation: StudyAssist?, expectedRevision: Int?): BackgroundRecord {
        val request = RecordSaveRequest(lookupId, candidateId, displayedExplanation, expectedRevision)
        saveCalls += request
        saveRecordAction?.let { return it(request) }
        val id = BackgroundRecords.id(lookupId, candidateId)
        val existing = records[id]
        if (existing != null && expectedRevision == null) return existing
        if (displayedExplanation != null) require(displayedExplanation == explanations[lookupId to candidateId])
        val result = if (existing != null) BackgroundRecords.withExplanation(existing, requireNotNull(expectedRevision), requireNotNull(displayedExplanation), now)
        else BackgroundRecords.create(requireNotNull(receipts[lookupId] ?: cachedLookup?.takeIf { it.id == lookupId }), candidateId, now, displayedExplanation)
        records[id] = result
        return result
    }
    override suspend fun updateRecord(id: String, expectedRevision: Int, comment: String, tags: List<String>): BackgroundRecord {
        val request = RecordUpdateRequest(id, expectedRevision, comment, tags)
        updateCalls += request
        updateRecordAction?.let { return it(request) }
        return BackgroundRecords.updateMetadata(requireNotNull(records[id]), expectedRevision, comment, tags, now).also { records[id] = it }
    }
    override suspend fun deleteRecord(id: String, expectedRevision: Int) {
        deleteCalls += id to expectedRevision
        deleteRecordAction?.let { it(id, expectedRevision); return }
        require(records[id]?.revision == expectedRevision)
        records.remove(id)
    }
}

internal data class RecordSaveRequest(val lookupId: String, val candidateId: String, val explanation: StudyAssist?, val revision: Int?)
internal data class RecordUpdateRequest(val id: String, val revision: Int, val comment: String, val tags: List<String>)

internal fun backgroundFixture(query: BackgroundQuery, time: Long, multiple: Boolean = true): BackgroundLookup {
    val sources = listOf(
        BackgroundSource("S1", "The building", "https://example.org/building", "This synthetic source describes a residence and its architecture.", time, "Synthetic reference"),
        BackgroundSource("S2", "The administration", "https://example.org/administration", "This synthetic source describes executive officials and their policies.", time, "Synthetic reference"),
    ).take(if (multiple) 2 else 1)
    val candidates = listOf(
        BackgroundCandidate("building", "建筑与官邸", "该名称指向实际建筑。", listOf("S1")),
        BackgroundCandidate("administration", "政府与行政机构", "该名称用于指代作出政策决定的机构。", listOf("S2")),
    ).take(sources.size)
    return BackgroundResearch.createLookup(query, candidates, sources, listOf(BackgroundProviderOutcome("Synthetic reference", "retrieved")), "retrieved", time)
}

internal fun backgroundExplanation(lookup: BackgroundLookup, candidate: String) = StudyAssist("background", "语境解释",
    if (candidate == "building") "在这组测试资料中，该名称指建筑。[S1]" else "在这组测试资料中，该名称指行政机构。[S1]", "synthetic", lookup.createdAt,
    evidence = "仅供协议与界面测试。", sources = BackgroundResearch.selectedSources(lookup, candidate), retrievalStatus = "retrieved", citedSourceIds = listOf("S1"))
