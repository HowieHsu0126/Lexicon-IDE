package dev.lexicon.ide.ui

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** These local repository fakes expose no article downloader or AI client. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class BaselineProgressViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher
    private lateinit var store: ViewModelStore

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

    @Test fun constructionAndRetryWithoutACollectorDoNotReadFrozenMaterials() = runTest(dispatcher) {
        val fixture = ProgressLoaderFixture()
        fixture.install(fixture.record("first"))
        val model = model(fixture)
        runCurrent()
        model.retry()
        fixture.install(fixture.record("second", started = 2_000_000))
        runCurrent()
        assertEquals(0, fixture.baseline.subscriptions)
        assertTrue(fixture.baseline.materialReads.isEmpty())
        assertTrue(fixture.plans.reads.isEmpty())
        assertNull(model.state.value.summary)
        fixture.assertReadOnly()
    }

    @Test fun activeLibraryChangesRefreshTheSummaryAndEachPlanIsReadOncePerSnapshot() = runTest(dispatcher) {
        val fixture = ProgressLoaderFixture()
        val first = fixture.record("first")
        val firstPlan = requireNotNull(first.plan)
        val samePlan = first.copy(attempt = first.attempt.copy(id = "same-material-later",
            startedAt = first.attempt.startedAt + 60_000, finishedAt = first.attempt.finishedAt + 60_000,
            readingFinishedAt = requireNotNull(first.attempt.readingFinishedAt) + 60_000,
            revealedAt = requireNotNull(first.attempt.revealedAt) + 60_000))
        fixture.install(first, samePlan)
        val model = model(fixture)
        val subscription = collect(model)
        runCurrent()
        assertEquals(listOf(firstPlan.planKey), fixture.plans.reads)
        assertEquals(listOf(first.attempt.id, samePlan.attempt.id), fixture.baseline.materialReads)
        assertEquals(listOf(first.attempt.id), requireNotNull(model.state.value.summary).points.map { it.attemptId })
        assertEquals(mapOf(BaselineExclusion.DUPLICATE to 1), model.state.value.summary?.exclusions)

        val second = fixture.record("second", started = 2_000_000)
        fixture.install(first, samePlan, second)
        runCurrent()
        assertEquals(listOf(firstPlan.planKey, firstPlan.planKey, requireNotNull(second.plan).planKey), fixture.plans.reads)
        assertEquals(listOf(first.attempt.id, second.attempt.id), requireNotNull(model.state.value.summary).points.map { it.attemptId })
        assertFalse(model.state.value.loading)
        assertNull(model.state.value.error)
        assertEquals(1, fixture.baseline.subscriptions)
        subscription.cancel(); runCurrent()
        fixture.assertReadOnly()
    }

    @Test fun multipleCollectorsShareOneLoadUntilTheLastCollectorLeaves() = runTest(dispatcher) {
        val fixture = ProgressLoaderFixture()
        val first = fixture.record("first")
        fixture.install(first)
        val model = model(fixture)
        val firstCollector = collect(model)
        val secondCollector = collect(model)
        runCurrent()
        assertEquals(1, fixture.baseline.activeSubscriptions)
        assertEquals(listOf(first.attempt.id), fixture.baseline.materialReads)
        firstCollector.cancel(); runCurrent()
        assertEquals(1, fixture.baseline.activeSubscriptions)
        secondCollector.cancel(); runCurrent()
        assertEquals(0, fixture.baseline.activeSubscriptions)
        fixture.install(fixture.record("unobserved", started = 2_000_000))
        runCurrent()
        assertEquals(listOf(first.attempt.id), fixture.baseline.materialReads)
        fixture.assertReadOnly()
    }

    @Test fun leavingTheScreenCancelsThePendingReadWithoutReportingFailure() = runTest(dispatcher) {
        val fixture = ProgressLoaderFixture()
        val first = fixture.record("first")
        val gate = CompletableDeferred<Unit>()
        var cancellations = 0
        fixture.install(first)
        fixture.baseline.readMaterial = { id ->
            try { gate.await(); fixture.baseline.materials[id] }
            catch (e: CancellationException) { cancellations++; throw e }
        }
        val model = model(fixture)
        val states = mutableListOf<BaselineProgressUiState>()
        val subscription = collect(model, states)
        runCurrent()
        assertEquals(listOf(first.attempt.id), fixture.baseline.materialReads)
        assertTrue(model.state.value.loading)
        subscription.cancel(); runCurrent()
        assertEquals(1, cancellations)
        assertEquals(0, fixture.baseline.activeSubscriptions)
        assertTrue(states.none { it.error != null })
        assertNull(model.state.value.summary)
        gate.complete(Unit); runCurrent()
        assertNull(model.state.value.summary)
        fixture.assertReadOnly()
    }

    @Test fun lateNonCooperativeReadCannotPublishAnOldSummaryAfterResubscription() = runTest(dispatcher) {
        val fixture = ProgressLoaderFixture()
        val older = fixture.record("older")
        val newer = fixture.record("newer", started = 2_000_000)
        fixture.install(older)
        var oldRead: Continuation<ReadingBaselineMaterial?>? = null
        var delayOld = true
        fixture.baseline.readMaterial = { id ->
            if (id == older.attempt.id && delayOld) {
                delayOld = false
                suspendCoroutine { oldRead = it }
            } else fixture.baseline.materials[id]
        }
        val model = model(fixture)
        val states = mutableListOf<BaselineProgressUiState>()
        val oldCollector = collect(model, states)
        runCurrent()
        assertNotNull(oldRead)
        oldCollector.cancel(); runCurrent()
        fixture.install(newer)
        val newCollector = collect(model, states)
        runCurrent()
        requireNotNull(oldRead).resume(older.material)
        runCurrent()
        assertEquals(listOf(newer.attempt.id), requireNotNull(model.state.value.summary).points.map { it.attemptId })
        assertFalse(model.state.value.loading)
        assertTrue(states.none { state -> state.summary?.points?.any { it.attemptId == older.attempt.id } == true })
        assertTrue(states.none { it.error != null })
        newCollector.cancel(); runCurrent()
        fixture.assertReadOnly()
    }

    @Test fun aNewLibrarySnapshotCancelsItsPredecessorBeforeItCanOverwriteTheResult() = runTest(dispatcher) {
        val fixture = ProgressLoaderFixture()
        val older = fixture.record("older")
        val newer = fixture.record("newer", started = 2_000_000)
        fixture.install(older)
        val gate = CompletableDeferred<Unit>()
        var cancelledOld = false
        fixture.baseline.readMaterial = { id ->
            if (id == older.attempt.id) {
                try { gate.await() } catch (e: CancellationException) { cancelledOld = true; throw e }
            }
            fixture.baseline.materials[id]
        }
        val model = model(fixture)
        val states = mutableListOf<BaselineProgressUiState>()
        val subscription = collect(model, states)
        runCurrent()
        fixture.install(newer)
        runCurrent()
        assertTrue(cancelledOld)
        assertEquals(listOf(newer.attempt.id), requireNotNull(model.state.value.summary).points.map { it.attemptId })
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(newer.attempt.id), requireNotNull(model.state.value.summary).points.map { it.attemptId })
        assertTrue(states.none { state -> state.summary?.points?.any { it.attemptId == older.attempt.id } == true })
        subscription.cancel(); runCurrent()
        fixture.assertReadOnly()
    }

    @Test fun failedRefreshPreservesThePreviousSummaryAndExplicitRetryRecovers() = runTest(dispatcher) {
        val fixture = ProgressLoaderFixture()
        val first = fixture.record("first")
        fixture.install(first)
        val model = model(fixture)
        val states = mutableListOf<BaselineProgressUiState>()
        val subscription = collect(model, states)
        runCurrent()
        val previous = requireNotNull(model.state.value.summary)
        val newer = fixture.record("newer", started = 2_000_000)
        fixture.baseline.readMaterial = { throw IOException("private frozen-text path and storage details") }
        fixture.install(first, newer)
        runCurrent()
        assertEquals(previous, model.state.value.summary)
        assertFalse(model.state.value.loading)
        assertEquals("自测记录暂时无法读取，请重试。", model.state.value.error)
        assertTrue(states.none { it.error?.contains("private") == true })

        val gate = CompletableDeferred<Unit>()
        fixture.baseline.readMaterial = { id -> gate.await(); fixture.baseline.materials[id] }
        model.retry(); runCurrent()
        assertTrue(model.state.value.loading)
        assertEquals(previous, model.state.value.summary)
        assertNull(model.state.value.error)
        gate.complete(Unit); runCurrent()
        assertFalse(model.state.value.loading)
        assertNull(model.state.value.error)
        assertEquals(listOf(first.attempt.id, newer.attempt.id), requireNotNull(model.state.value.summary).points.map { it.attemptId })
        subscription.cancel(); runCurrent()
        fixture.assertReadOnly()
    }

    @Test fun libraryFailureIsRecoverableByRetryWithoutPreparingReplacementContent() = runTest(dispatcher) {
        val fixture = ProgressLoaderFixture()
        val first = fixture.record("first")
        fixture.install(first)
        fixture.baseline.failSubscriptions = 1
        val model = model(fixture)
        val subscription = collect(model)
        runCurrent()
        assertNull(model.state.value.summary)
        assertFalse(model.state.value.loading)
        assertNotNull(model.state.value.error)
        assertTrue(fixture.baseline.materialReads.isEmpty())
        assertTrue(fixture.plans.reads.isEmpty())
        model.retry(); runCurrent()
        assertNull(model.state.value.error)
        assertEquals(listOf(first.attempt.id), requireNotNull(model.state.value.summary).points.map { it.attemptId })
        assertEquals(2, fixture.baseline.subscriptions)
        subscription.cancel(); runCurrent()
        fixture.assertReadOnly()
    }

    @Test fun emptyOrInvalidFrozenRecordsAreExcludedWithoutNetworkPreparationOrWrites() = runTest(dispatcher) {
        val fixture = ProgressLoaderFixture()
        val model = model(fixture)
        val subscription = collect(model)
        runCurrent()
        assertEquals(BaselineProgressSummary(), model.state.value.summary)
        assertFalse(model.state.value.loading)
        assertTrue(fixture.baseline.materialReads.isEmpty())
        assertTrue(fixture.plans.reads.isEmpty())

        val good = fixture.record("valid")
        val missingPlan = fixture.record("missing-plan").copy(plan = null)
        val missingMaterial = fixture.record("missing-material").copy(material = null)
        val badOriginal = fixture.record("mismatched")
        val badMaterial = badOriginal.copy(material = requireNotNull(badOriginal.material).copy(bodyHash = "different-body"))
        val incompleteOriginal = fixture.record("incomplete")
        val incomplete = incompleteOriginal.copy(attempt = incompleteOriginal.attempt.copy(completed = false, revealedAt = null))
        val familiarOriginal = fixture.record("familiar")
        val familiar = familiarOriginal.copy(attempt = familiarOriginal.attempt.copy(userConfirmedUnseen = false))
        fixture.install(good, missingPlan, missingMaterial, badMaterial, incomplete, familiar)
        runCurrent()
        val summary = requireNotNull(model.state.value.summary)
        assertEquals(listOf(good.attempt.id), summary.points.map { it.attemptId })
        assertEquals(mapOf(BaselineExclusion.UNAVAILABLE to 3, BaselineExclusion.INCOMPLETE to 1, BaselineExclusion.FAMILIAR to 1), summary.exclusions)
        assertNull(model.state.value.error)
        assertFalse(model.state.value.loading)
        assertEquals(6, fixture.baseline.materialReads.size)
        subscription.cancel(); runCurrent()
        fixture.assertReadOnly()
    }

    private fun model(fixture: ProgressLoaderFixture) = BaselineProgressViewModel(fixture.baseline, fixture.plans, dispatcher).also {
        store.put("baseline-progress", it)
    }

    private fun TestScope.collect(model: BaselineProgressViewModel, values: MutableList<BaselineProgressUiState>? = null): Job =
        backgroundScope.launch(dispatcher) { model.state.collect { values?.add(it) } }
}

private class ProgressLoaderFixture {
    val forbidden = mutableListOf<String>()
    val baseline = ProgressBaselineRepository(forbidden)
    val plans = ProgressPlanRepository(forbidden)

    fun install(vararg records: BaselineProgressRecord) {
        records.forEach { record ->
            plans.records[record.attempt.planKey] = record.plan
            baseline.materials[record.attempt.id] = record.material
        }
        baseline.records.value = records.map { it.attempt }
    }

    fun record(id: String, started: Long = 1_000_000L): BaselineProgressRecord {
        val text = "The $id study compares several explanations before drawing a cautious conclusion.\n\n" +
            "Independent observations help readers distinguish direct evidence from unsupported assumptions."
        val hash = fingerprint(text)
        val paragraphs = ParagraphFocus.paragraphs(text)
        val draft = SpeedChunker.plan("article-$id", hash, text, SpeedGranularity.L2, 0, 2, started).copy(
            origin = "ai", questions = paragraphs.mapIndexed { index, paragraph ->
                SpeedQuestion("question-$id-$index", index, "What does this paragraph establish?",
                    listOf("It explains how evidence informs a conclusion.", "It rejects the need to compare explanations."),
                    0, paragraph.start, paragraph.endExclusive, "The paragraph connects observations with the assessment of claims.")
            },
        )
        val plan = draft.copy(planKey = SpeedChunker.planKey(draft))
        val attempt = ReadingBaselineAttempt("attempt-$id", plan.articleId, hash, plan.planKey, started, started + 35_000,
            plan.paragraphIndices.map { NaturalParagraphTiming(it, 15_000, true) },
            plan.questions.map { SpeedAnswer(it.id, 0, true) }, plan.questions.size,
            readingFinishedAt = started + 30_000, revealedAt = started + 35_000, completed = true,
            localUnseen = true, userConfirmedUnseen = true)
        val material = ReadingBaselineMaterial(plan.planKey, hash, SourceRef("Frozen fixture $id", "Self-authored fixture"),
            paragraphs.mapIndexed { index, p -> BaselineSourceParagraph(index, p.start, text.substring(p.start, p.endExclusive)) },
            "Science & Technology")
        return BaselineProgressRecord(attempt, plan, material)
    }

    fun assertReadOnly() = assertEquals("Progress loading may only read the baseline library, cached plans and frozen materials", emptyList<String>(), forbidden)
}

private class ProgressBaselineRepository(private val forbidden: MutableList<String>) : ReadingBaselineRepository {
    val records = MutableStateFlow<List<ReadingBaselineAttempt>>(emptyList())
    val materials = mutableMapOf<String, ReadingBaselineMaterial?>()
    val materialReads = mutableListOf<String>()
    var subscriptions = 0
    var activeSubscriptions = 0
    var failSubscriptions = 0
    var readMaterial: suspend (String) -> ReadingBaselineMaterial? = { materials[it] }
    override val library: Flow<List<ReadingBaselineAttempt>> = flow {
        subscriptions++
        activeSubscriptions++
        try {
            if (failSubscriptions > 0) { failSubscriptions--; throw IOException("private database details") }
            emitAll(records)
        } finally { activeSubscriptions-- }
    }
    override suspend fun material(id: String): ReadingBaselineMaterial? { materialReads += id; return readMaterial(id) }
    override suspend fun begin(planKey: String, userConfirmedUnseen: Boolean): ReadingBaselineAttempt = prohibited("begin")
    override suspend fun save(attempt: ReadingBaselineAttempt): ReadingBaselineAttempt = prohibited("save baseline")
    override suspend fun get(id: String): ReadingBaselineAttempt? = prohibited("get individual baseline")
    override suspend fun resume(id: String): ReadingBaselineAttempt = prohibited("resume baseline")
    private fun prohibited(operation: String): Nothing { forbidden += operation; error("Unexpected $operation") }
}

private class ProgressPlanRepository(private val forbidden: MutableList<String>) : SpeedReadingRepository {
    val records = mutableMapOf<String, SpeedPlan?>()
    val reads = mutableListOf<String>()
    override val library: Flow<SpeedLibrary> get() = prohibited("read paced library")
    override suspend fun cachedPlan(planKey: String): SpeedPlan? { reads += planKey; return records[planKey] }
    override suspend fun prepare(article: Article, body: ArticleBody, granularity: SpeedGranularity, startParagraph: Int,
        paragraphCount: Int, aiAllowed: Boolean, useAi: Boolean): SpeedPlan = prohibited("prepare plan")
    override suspend fun retryPrepare(article: Article, body: ArticleBody, granularity: SpeedGranularity, startParagraph: Int,
        paragraphCount: Int, aiAllowed: Boolean, useAi: Boolean): SpeedPlan = prohibited("retry plan generation")
    override suspend fun saveRun(run: SpeedRun): Unit = prohibited("save paced run")
    override suspend fun saveNaturalAttempt(attempt: NaturalReadingAttempt): Unit = prohibited("save natural attempt")
    override suspend fun saveCorrection(correction: SpeedCorrection): Unit = prohibited("save correction")
    override suspend fun markSentencePreviewed(planKey: String, paragraph: Int): Unit = prohibited("mark sentence previewed")
    override suspend fun sentenceForEditing(planKey: String, chunkIndex: Int): SpeedSegmentation? = prohibited("open sentence editor")
    override suspend fun editSentence(planKey: String, paragraph: Int, sentence: Int, breakOffsets: List<Int>): SpeedPlan = prohibited("edit sentence")
    override suspend fun restoreSegmentation(planKey: String): SpeedPlan = prohibited("restore segmentation")
    private fun prohibited(operation: String): Nothing { forbidden += operation; error("Unexpected $operation") }
}
