package dev.lexicon.ide.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import java.io.File
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ReadingBaselineViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setUp() {
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        try { stores.forEach { it.clear() }; scheduler.runCurrent() }
        finally { stores.clear(); Dispatchers.resetMain() }
    }

    @Test fun preparationDoesNotReserveAnExposureOrStartImplicitAiWork() = runTest(dispatcher) {
        val fixture = Fixture(scheduler)
        val model = model(fixture)
        model.bind(fixture.article, true, true, 1)
        runCurrent()
        assertEquals(1, fixture.app.baselineBodyReads)
        assertEquals(0, fixture.app.normalBodyReads)
        assertEquals(0, fixture.plans.preparations)
        assertEquals(0, fixture.baseline.begins)
        model.confirmUnseen(true)
        model.prepare(true)
        runCurrent()
        assertEquals(BaselineStage.READY, model.state.value.stage)
        assertFalse(model.state.value.playing)
        assertNull(model.state.value.attempt)
        assertEquals(1, fixture.plans.preparations)
        assertEquals(0, fixture.baseline.begins)
        model.togglePlay()
        runCurrent()
        assertEquals(1, fixture.baseline.begins)
        assertTrue(model.state.value.playing)
    }

    @Test fun pausedAndBackgroundTimeNeverEntersReadingDuration() = runTest(dispatcher) {
        val fixture = Fixture(scheduler)
        val model = model(fixture)
        ready(model, fixture)
        model.togglePlay(); runCurrent()
        advanceTimeBy(4_000)
        model.togglePlay(); runCurrent()
        assertEquals(4_000L, model.state.value.elapsedMillis)
        advanceTimeBy(50_000)
        model.togglePlay()
        advanceTimeBy(6_000)
        model.setForeground(false); runCurrent()
        assertFalse(model.state.value.playing)
        assertEquals(10_000L, model.state.value.elapsedMillis)
        advanceTimeBy(60_000)
        model.setForeground(true); runCurrent()
        assertFalse(model.state.value.playing)
        assertEquals(10_000L, model.state.value.elapsedMillis)
        assertEquals(10_000L, fixture.baseline.records.values.single().paragraphs.sumOf { it.activeMillis })
        assertEquals(2, model.state.value.attempt?.pauses)
    }

    @Test fun allReadingIsSavedBeforeAnswersAndOnlyFinalSubmissionRevealsTheCheck() = runTest(dispatcher) {
        val fixture = Fixture(scheduler)
        val model = model(fixture)
        ready(model, fixture, count = 2)
        model.togglePlay(); runCurrent()
        model.answer(0); model.submitAnswer()
        assertNull(model.state.value.selectedAnswer)
        assertTrue(requireNotNull(model.state.value.attempt).answers.isEmpty())
        advanceTimeBy(5_000); model.completeParagraph(); runCurrent()
        assertEquals(BaselineStage.READING, model.state.value.stage)
        assertEquals(1, model.state.value.paragraphIndex)
        assertNull(model.state.value.attempt?.readingFinishedAt)
        model.togglePlay(); advanceTimeBy(5_000); model.completeParagraph()
        model.answer(0)
        assertNull(model.state.value.selectedAnswer) // Reading-completion write is still pending.
        runCurrent()
        val readingFinished = requireNotNull(model.state.value.attempt?.readingFinishedAt)
        assertEquals(BaselineStage.QUESTION, model.state.value.stage)
        assertNull(model.state.value.summary)
        model.answer(0); model.submitAnswer(); runCurrent()
        assertEquals(1, model.state.value.attempt?.answers?.size)
        assertNull(model.state.value.attempt?.revealedAt)
        assertFalse(requireNotNull(model.state.value.attempt).answers.single().correct)
        assertNull(model.state.value.summary)
        advanceTimeBy(40_000)
        model.answer(1); model.submitAnswer()
        assertTrue(model.state.value.saving)
        assertNull(model.state.value.summary)
        runCurrent()
        val summary = requireNotNull(model.state.value.summary)
        assertEquals(10_000L, summary.activeMillis)
        assertEquals(readingFinished, model.state.value.attempt?.readingFinishedAt)
        assertEquals(1.0, requireNotNull(summary.accuracy), .0001)
        assertTrue(summary.qualifiesAsBaseline)
        assertEquals(BaselineStage.RESULT, model.state.value.stage)
        val completed = model.state.value.attempt
        model.answer(3); model.submitAnswer()
        assertEquals(completed, model.state.value.attempt)
    }

    @Test fun failedFinalWriteRetainsOneAttemptAndRetriesTheSameCandidateWithoutRevealing() = runTest(dispatcher) {
        val fixture = Fixture(scheduler)
        val model = model(fixture)
        ready(model, fixture)
        model.togglePlay(); runCurrent()
        advanceTimeBy(10_000); model.completeParagraph(); runCurrent()
        val id = requireNotNull(model.state.value.attempt).id
        fixture.baseline.failSaves = 1
        model.answer(0); model.submitAnswer(); runCurrent()
        assertNotNull(model.state.value.error)
        assertNull(model.state.value.summary)
        assertFalse(requireNotNull(fixture.baseline.records[id]).completed)
        val failed = fixture.baseline.saveAttempts.last()
        advanceTimeBy(5_000)
        model.retry(); runCurrent()
        assertEquals(failed, fixture.baseline.saveAttempts.last())
        assertEquals(id, model.state.value.attempt?.id)
        assertEquals(1, fixture.baseline.begins)
        assertEquals(1, fixture.baseline.records.size)
        assertNotNull(model.state.value.summary)
        assertNull(model.state.value.error)
    }

    @Test fun pendingBeginIsNotDuplicatedAndBackgroundCompletionDoesNotAutoplay() = runTest(dispatcher) {
        val fixture = Fixture(scheduler)
        val model = model(fixture)
        ready(model, fixture)
        val gate = CompletableDeferred<Unit>()
        fixture.baseline.beginGate = gate
        model.togglePlay(); model.togglePlay(); runCurrent()
        assertEquals(1, fixture.baseline.begins)
        model.setForeground(false)
        advanceTimeBy(60_000)
        gate.complete(Unit); runCurrent()
        assertNotNull(model.state.value.attempt)
        assertFalse(model.state.value.playing)
        assertEquals(0L, model.state.value.elapsedMillis)
        model.setForeground(true); runCurrent()
        assertFalse(model.state.value.playing)
        model.togglePlay(); runCurrent()
        assertTrue(model.state.value.playing)
        assertEquals(1, fixture.baseline.begins)
    }

    @Test fun oldCheckpointMergesNewerDatabaseProgressWithoutRebeginning() = runTest(dispatcher) {
        val fixture = Fixture(scheduler)
        val plan = fixture.plans.makePlan(fixture.article, fixture.body, 0, 2, true)
        advanceTimeBy(10_000)
        val old = restoredAttempt(plan, listOf(NaturalParagraphTiming(0, 3_000, false), NaturalParagraphTiming(1, 0, false)), 3_000)
        val newer = old.copy(paragraphs = listOf(NaturalParagraphTiming(0, 6_000, true), NaturalParagraphTiming(1, 1_000, false)), finishedAt = Fixture.EPOCH + 9_000)
        fixture.baseline.install(newer)
        val model = model(fixture, checkpoint(old))
        model.bind(fixture.article, true, true, 1); runCurrent()
        assertEquals(7_000L, model.state.value.elapsedMillis)
        assertEquals(newer.paragraphs, model.state.value.attempt?.paragraphs)
        assertEquals(1, model.state.value.paragraphIndex)
        assertFalse(model.state.value.playing)
        assertEquals(0, fixture.baseline.begins)
        assertEquals(0, fixture.plans.preparations)
    }

    @Test fun committedReadingFreezeWinsOverAConflictingCheckpoint() = runTest(dispatcher) {
        val fixture = Fixture(scheduler)
        val plan = fixture.plans.makePlan(fixture.article, fixture.body, 0, 2, true)
        advanceTimeBy(20_000)
        val candidate = restoredAttempt(plan, listOf(NaturalParagraphTiming(0, 8_000, true), NaturalParagraphTiming(1, 5_000, true)), 14_000)
            .copy(readingFinishedAt = Fixture.EPOCH + 14_000)
        val stored = candidate.copy(paragraphs = listOf(NaturalParagraphTiming(0, 5_000, true), NaturalParagraphTiming(1, 5_000, true)),
            readingFinishedAt = Fixture.EPOCH + 15_000, finishedAt = Fixture.EPOCH + 16_000,
            answers = listOf(SpeedAnswer(plan.questions.first().id, 0, false)))
        fixture.baseline.install(stored)
        val model = model(fixture, checkpoint(candidate))
        model.bind(fixture.article, true, true, 1); runCurrent()
        assertNull(model.state.value.error)
        assertEquals(stored.paragraphs, model.state.value.attempt?.paragraphs)
        assertEquals(10_000L, model.state.value.elapsedMillis)
        assertEquals(1, model.state.value.attempt?.answers?.size)
        assertEquals(BaselineStage.QUESTION, model.state.value.stage)
    }

    @Test fun cancelledRestoreCannotReplaceANewerArticleWhenOldReadReturnsLate() = runTest(dispatcher) {
        val fixture = Fixture(scheduler)
        val plan = fixture.plans.makePlan(fixture.article, fixture.body, 0, 1, true)
        advanceTimeBy(3_000)
        val candidate = restoredAttempt(plan, listOf(NaturalParagraphTiming(0, 1_000, false)), 1_000)
        fixture.baseline.install(candidate)
        fixture.baseline.delayNextGet = true
        val model = model(fixture, checkpoint(candidate))
        model.bind(fixture.article, true, true, 1); runCurrent()
        val completion = requireNotNull(fixture.baseline.lateGet)
        val other = fixture.article.copy(id = "other-article")
        model.bind(other, true, true, 2); runCurrent()
        completion.resume(candidate); runCurrent()
        assertEquals(other.id, model.state.value.body?.articleId)
        assertNull(model.state.value.attempt)
        assertEquals(BaselineStage.SETUP, model.state.value.stage)
    }

    @Test fun preparationFailureUsesExplicitRetryInsteadOfRepeatingCachedFailure() = runTest(dispatcher) {
        val fixture = Fixture(scheduler)
        val model = model(fixture)
        model.bind(fixture.article, true, true, 1); runCurrent()
        fixture.plans.failPrepare = true
        model.prepare(true); runCurrent()
        assertNotNull(model.state.value.error)
        assertEquals(BaselineStage.SETUP, model.state.value.stage)
        model.retry(); runCurrent()
        assertEquals(BaselineStage.READY, model.state.value.stage)
        assertEquals(1, fixture.plans.preparations)
        assertEquals(1, fixture.plans.retries)
        assertEquals(0, fixture.baseline.begins)
    }

    @Test fun failedRestoreRetriesTheSameCheckpointWithoutStartingAnotherAttempt() = runTest(dispatcher) {
        val fixture = Fixture(scheduler)
        val plan = fixture.plans.makePlan(fixture.article, fixture.body, 0, 1, true)
        advanceTimeBy(3_000)
        val stored = restoredAttempt(plan, listOf(NaturalParagraphTiming(0, 1_000, false)), 1_000)
        fixture.baseline.install(stored)
        fixture.baseline.failGets = 1
        val model = model(fixture, checkpoint(stored))
        model.bind(fixture.article, true, true, 1); runCurrent()
        assertNotNull(model.state.value.error)
        assertNull(model.state.value.attempt)
        model.retry(); runCurrent()
        assertEquals(stored.id, model.state.value.attempt?.id)
        assertEquals(1_000L, model.state.value.elapsedMillis)
        assertEquals(BaselineStage.READING, model.state.value.stage)
        assertNull(model.state.value.error)
        assertEquals(0, fixture.baseline.begins)
        assertEquals(0, fixture.plans.preparations)
    }

    private fun ready(model: ReadingBaselineViewModel, fixture: Fixture, count: Int = 1) {
        model.bind(fixture.article, true, true, 1); scheduler.runCurrent()
        model.setCount(count); model.confirmUnseen(true); model.prepare(true); scheduler.runCurrent()
        assertEquals(BaselineStage.READY, model.state.value.stage)
    }
    private fun model(fixture: Fixture, saved: SavedStateHandle = SavedStateHandle()): ReadingBaselineViewModel {
        val model = ReadingBaselineViewModel(fixture.app, saved, { fixture.now() }, { scheduler.currentTime })
        ViewModelStore().also { it.put("baseline", model); stores += it }
        return model
    }
    private fun checkpoint(attempt: ReadingBaselineAttempt) = SavedStateHandle(mapOf("reading-baseline" to Json.encodeToString(attempt)))
    private fun restoredAttempt(plan: SpeedPlan, timings: List<NaturalParagraphTiming>, elapsedWall: Long) = ReadingBaselineAttempt(
        "restored-attempt", plan.articleId, plan.bodyHash, plan.planKey, Fixture.EPOCH, Fixture.EPOCH + elapsedWall,
        timings, emptyList(), plan.questions.size, localUnseen = true, userConfirmedUnseen = true)
}

@OptIn(ExperimentalCoroutinesApi::class)
private class Fixture(private val scheduler: TestCoroutineScheduler) {
    val article = Article("baseline-article", "g", listOf("source"), "A synthetic reading exercise", "https://example.org/baseline", "")
    private val text = "Researchers compare evidence before drawing a conclusion.\n\n" +
        "New observations can distinguish competing explanations.\n\n" +
        "A clear report describes the limits of each measurement."
    val body = ArticleBody(article.id, text, fingerprint(text), EPOCH, Long.MAX_VALUE, "Synthetic")
    fun now() = EPOCH + scheduler.currentTime
    val plans = BaselineTestPlans(::now)
    val baseline = BaselineTestRepository(plans, ::now)
    val app = BaselineTestAppRepository(plans, baseline, body)
    companion object { const val EPOCH = 1_000_000L }
}

internal class BaselineTestPlans(private val clock: () -> Long) : SpeedReadingRepository {
    override val library = MutableStateFlow(SpeedLibrary())
    private val plans = mutableMapOf<String, SpeedPlan>()
    private val materials = mutableMapOf<String, ReadingBaselineMaterial>()
    var preparations = 0
    var retries = 0
    var failPrepare = false
    fun makePlan(article: Article, body: ArticleBody, start: Int, count: Int, useAi: Boolean): SpeedPlan {
        val paragraphs = ParagraphFocus.paragraphs(body.text)
        val base = SpeedChunker.plan(article.id, body.contentHash, body.text, SpeedGranularity.L2, start, count, clock())
        val draft = base.copy(origin = if (useAi) "ai" else "rules", questions = if (!useAi) emptyList() else base.paragraphIndices.map { index ->
            SpeedQuestion("q-$index", index, "What does paragraph ${index + 1} establish?", listOf("Choice A", "Choice B", "Choice C", "Choice D"),
                index % 4, paragraphs[index].start, paragraphs[index].endExclusive, "The evidence is stated in the original paragraph.")
        })
        val result = draft.copy(planKey = SpeedChunker.planKey(draft))
        plans[result.planKey] = result
        materials[result.planKey] = ReadingBaselineMaterial(result.planKey, body.contentHash, SourceRef(article.title, article.sourceName),
            result.paragraphIndices.map { index -> BaselineSourceParagraph(index, paragraphs[index].start,
                body.text.substring(paragraphs[index].start, paragraphs[index].endExclusive)) })
        return result
    }
    fun material(planKey: String) = materials[planKey]
    override suspend fun cachedPlan(planKey: String) = plans[planKey]
    override suspend fun prepare(article: Article, body: ArticleBody, granularity: SpeedGranularity, startParagraph: Int, paragraphCount: Int,
        aiAllowed: Boolean, useAi: Boolean): SpeedPlan {
        preparations++
        if (failPrepare) throw IOException("Cached failed request needs explicit retry")
        return makePlan(article, body, startParagraph, paragraphCount, useAi)
    }
    override suspend fun retryPrepare(article: Article, body: ArticleBody, granularity: SpeedGranularity, startParagraph: Int, paragraphCount: Int,
        aiAllowed: Boolean, useAi: Boolean): SpeedPlan {
        retries++
        return makePlan(article, body, startParagraph, paragraphCount, useAi)
    }
    override suspend fun saveRun(run: SpeedRun) { error("Baseline must not write a paced run") }
    override suspend fun saveCorrection(correction: SpeedCorrection) { error("Unexpected correction") }
}

internal class BaselineTestRepository(private val plans: BaselineTestPlans, private val clock: () -> Long) : ReadingBaselineRepository {
    override val library = MutableStateFlow<List<ReadingBaselineAttempt>>(emptyList())
    val records = linkedMapOf<String, ReadingBaselineAttempt>()
    val saveAttempts = mutableListOf<ReadingBaselineAttempt>()
    var begins = 0
    var resumes = 0
    var materialReads = 0
    var materialAvailable = true
    var materialOverride: ReadingBaselineMaterial? = null
    var failSaves = 0
    var failGets = 0
    var beginGate: CompletableDeferred<Unit>? = null
    var delayNextGet = false
    var lateGet: Continuation<ReadingBaselineAttempt?>? = null
    fun install(attempt: ReadingBaselineAttempt) { records[attempt.id] = attempt; library.value = records.values.toList() }
    override suspend fun begin(planKey: String, userConfirmedUnseen: Boolean): ReadingBaselineAttempt {
        begins++
        beginGate?.await()
        val plan = requireNotNull(plans.cachedPlan(planKey))
        return ReadingBaselineAttempt("attempt-$begins", plan.articleId, plan.bodyHash, plan.planKey, clock(), clock(),
            plan.paragraphIndices.map { NaturalParagraphTiming(it, 0, false) }, emptyList(), plan.questions.size,
            localUnseen = true, userConfirmedUnseen = userConfirmedUnseen).also(::install)
    }
    override suspend fun save(attempt: ReadingBaselineAttempt): ReadingBaselineAttempt {
        saveAttempts += attempt
        if (failSaves > 0) { failSaves--; throw IOException("Synthetic storage failure") }
        val old = requireNotNull(records[attempt.id])
        if (old.completed) return old
        val plan = requireNotNull(plans.cachedPlan(attempt.planKey))
        if (old.readingFinishedAt == null) require(attempt.answers.isEmpty())
        else require(old.paragraphs == attempt.paragraphs)
        require(old.answers.all { previous -> attempt.answers.any { it.questionId == previous.questionId && it.selectedIndex == previous.selectedIndex } })
        val readingFinished = old.readingFinishedAt ?: clock().takeIf { attempt.paragraphs.all { it.completed } }
        val complete = readingFinished != null && attempt.answers.size == plan.questions.size
        val reveal = clock().takeIf { complete && plan.questions.isNotEmpty() }
        val canonical = attempt.copy(readingFinishedAt = readingFinished, completed = complete, revealedAt = reveal, finishedAt = clock(),
            answers = attempt.answers.map { answer -> answer.copy(correct = reveal != null && answer.selectedIndex == plan.questions.single { it.id == answer.questionId }.answerIndex) },
            localUnseen = old.localUnseen, userConfirmedUnseen = old.userConfirmedUnseen, resumedFromHistory = old.resumedFromHistory)
        ReadingBaselineMetrics.summarize(canonical, plan)
        install(canonical)
        return canonical
    }
    override suspend fun get(id: String): ReadingBaselineAttempt? {
        if (failGets > 0) { failGets--; throw IOException("Synthetic restore failure") }
        if (delayNextGet) { delayNextGet = false; return suspendCoroutine { lateGet = it } }
        return records[id]
    }
    override suspend fun material(id: String): ReadingBaselineMaterial? {
        materialReads++
        if (!materialAvailable) return null
        return materialOverride ?: records[id]?.let { plans.material(it.planKey) }
    }
    override suspend fun resume(id: String): ReadingBaselineAttempt {
        resumes++
        val old = requireNotNull(records[id])
        return if (old.completed) old else old.copy(resumedFromHistory = true).also(::install)
    }
}

internal class BaselineTestAppRepository(override val speedReading: SpeedReadingRepository,
    override val baseline: ReadingBaselineRepository, private val body: ArticleBody) : AppRepository {
    override val articles = MutableStateFlow<List<Article>>(emptyList())
    override val sources = MutableStateFlow<List<Source>>(emptyList())
    override val captures = MutableStateFlow<List<Capture>>(emptyList())
    override val cards = MutableStateFlow<List<Card>>(emptyList())
    override val settings = MutableStateFlow(AppSettings())
    override val status = MutableStateFlow("[ready]")
    override val jobs = MutableStateFlow<List<AiJob>>(emptyList())
    var baselineBodyReads = 0
    var normalBodyReads = 0
    override suspend fun prepareBaselineBody(id: String): ArticleBody { baselineBodyReads++; return body.copy(articleId = id) }
    override suspend fun openArticle(id: String): ArticleBody { normalBodyReads++; return body.copy(articleId = id) }
    override suspend fun initialize() = Unit
    override suspend fun sync() = SyncResult(0, 0, emptyList())
    override suspend fun setSourceEnabled(id: String, enabled: Boolean) = Unit
    override suspend fun markArticleRead(id: String) = Unit
    override suspend fun classifyPending() = Unit
    override suspend fun saveCapture(capture: Capture) = Unit
    override suspend fun lookup(text: String) = emptyList<DictionaryEntry>()
    override suspend fun updateSettings(settings: AppSettings) { this.settings.value = settings }
    override suspend fun hasApiKey() = false
    override suspend fun setApiKey(key: String) = Unit
    override suspend fun clearApiKey() = Unit
    override suspend fun dictionaryStatus() = DictionaryStatus()
    override suspend fun importDictionary(file: File) = DictionaryStatus()
    override suspend fun analyzeCapture(capture: Capture, deep: Boolean): AnalysisResult = error("Unexpected analysis")
    override suspend fun saveCard(capture: Capture, analysis: AnalysisResult): Card = error("Unexpected vocabulary save")
    override suspend fun updateCard(card: Card) = Unit
    override suspend fun reviewCard(cardId: String, rating: Rating, expectedReps: Int, now: Long) = ReviewOutcome.APPLIED
    override suspend fun exportAnki(destination: File) = destination
    override suspend fun confirmAnkiExport(exportFile: File) = Unit
    override suspend fun exportEvents(destination: File) = destination
    override suspend fun recordEvent(type: String, value: Int) = Unit
}
