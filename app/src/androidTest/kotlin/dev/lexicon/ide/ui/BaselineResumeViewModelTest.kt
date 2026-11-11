package dev.lexicon.ide.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class BaselineResumeViewModelTest {
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

    @Test fun historyContinuesTheSameAttemptWithoutPreparingAiOrOpeningTheArticle() = runTest(dispatcher) {
        val fixture = ResumeFixture(scheduler)
        val plan = fixture.plan(start = 1, count = 2)
        val original = fixture.partial(plan)
        fixture.baseline.install(original)
        advanceTimeBy(20_000)
        val model = model(fixture)
        val opened = mutableListOf<String>()
        model.openHistory(original.id) { opened += it }; runCurrent()
        assertEquals(listOf(original.articleId), opened)
        assertEquals(original.id, model.state.value.attempt?.id)
        assertEquals(BaselineStage.READING, model.state.value.stage)
        assertTrue(requireNotNull(model.state.value.attempt).resumedFromHistory)
        assertFalse(model.state.value.playing)
        model.openHistory(original.id) { opened += it }; runCurrent()
        assertEquals(1, fixture.baseline.resumes)
        assertEquals(1, opened.size)
        model.togglePlay(); advanceTimeBy(3_000); model.togglePlay(); runCurrent()
        assertEquals(5_000L, model.state.value.elapsedMillis)
        assertEquals(original.id, fixture.baseline.records.values.single().id)
        assertTrue(fixture.baseline.records.values.single().resumedFromHistory)
        assertNoPreparation(fixture)
    }

    @Test fun historyUsesOnlyFrozenSelectedFragmentsAtTheirOriginalOffsets() = runTest(dispatcher) {
        val fixture = ResumeFixture(scheduler)
        val plan = fixture.plan(start = 1, count = 2)
        val attempt = fixture.partial(plan)
        fixture.baseline.install(attempt)
        advanceTimeBy(20_000)
        val model = model(fixture)
        model.openHistory(attempt.id) {}; runCurrent()
        val material = requireNotNull(model.state.value.material)
        val originalParagraphs = ParagraphFocus.paragraphs(fixture.body.text)
        assertNull(model.state.value.body)
        assertEquals(listOf(1, 2), material.paragraphs.map { it.paragraph })
        assertEquals(originalParagraphs[1].start, material.paragraphs.first().start)
        assertTrue(material.paragraphs.first().start > 0)
        assertNull(material.textAt(originalParagraphs[0].start, originalParagraphs[0].endExclusive))
        for (chunk in plan.chunks) {
            assertEquals(fixture.body.text.substring(chunk.start, chunk.end), material.textAt(chunk.start, chunk.end))
        }
        assertEquals(plan.bodyHash, material.bodyHash)
        assertEquals(fixture.article.title, material.sourceRef.title)
        assertNoPreparation(fixture)
    }

    @Test fun finishedReadingResumesAtQuestionsAndRetainsExerciseMetricsWithoutBaselineEligibility() = runTest(dispatcher) {
        val fixture = ResumeFixture(scheduler)
        val plan = fixture.plan(start = 1, count = 2)
        val original = fixture.readingFinished(plan).copy(answers = listOf(SpeedAnswer(plan.questions.first().id, 1, false)))
        fixture.baseline.install(original)
        advanceTimeBy(20_000)
        val model = model(fixture)
        model.openHistory(original.id) {}; runCurrent()
        assertEquals(BaselineStage.QUESTION, model.state.value.stage)
        assertEquals(1, model.state.value.attempt?.answers?.size)
        assertEquals(10_000L, model.state.value.elapsedMillis)
        model.togglePlay(); advanceTimeBy(20_000); model.completeParagraph()
        assertFalse(model.state.value.playing)
        assertEquals(original.paragraphs, model.state.value.attempt?.paragraphs)
        model.answer(2); model.submitAnswer(); runCurrent()
        assertEquals(BaselineStage.RESULT, model.state.value.stage)
        val result = requireNotNull(model.state.value.attempt)
        val summary = requireNotNull(model.state.value.summary)
        assertEquals(original.id, result.id)
        assertEquals(original.readingFinishedAt, result.readingFinishedAt)
        assertEquals(original.paragraphs, result.paragraphs)
        assertEquals(10_000L, summary.activeMillis)
        assertEquals(1.0, requireNotNull(summary.accuracy), 0.0001)
        assertNotNull(summary.wordsPerMinute)
        assertNull(summary.effectiveWordsPerMinute)
        assertFalse(summary.qualifiesAsBaseline)
        assertTrue(result.resumedFromHistory)
        assertEquals(1, fixture.baseline.resumes)
        assertNoPreparation(fixture)
    }

    @Test fun completedHistoryIsReadOnlyAcrossControlsLifecycleRetryAndExit() = runTest(dispatcher) {
        val fixture = ResumeFixture(scheduler)
        val plan = fixture.plan()
        val completed = fixture.completed(plan)
        fixture.baseline.install(completed)
        advanceTimeBy(20_000)
        val model = model(fixture)
        model.openHistory(completed.id) {}; runCurrent()
        assertEquals(BaselineStage.RESULT, model.state.value.stage)
        assertTrue(requireNotNull(model.state.value.summary).qualifiesAsBaseline)
        model.togglePlay(); model.completeParagraph(); model.answer(3); model.submitAnswer()
        model.confirmUnseen(false); model.prepare(true)
        model.setForeground(false); model.setForeground(true); model.retry(); runCurrent()
        var left = false
        model.leave { left = true }; runCurrent()
        assertTrue(left)
        assertEquals(completed, fixture.baseline.records[completed.id])
        assertEquals(0, fixture.baseline.resumes)
        assertTrue(fixture.baseline.saveAttempts.isEmpty())
        assertNoPreparation(fixture)
    }

    @Test fun missingFrozenMaterialFailsBeforeResumeAndRetryUsesTheSameHistoryId() = runTest(dispatcher) {
        val fixture = ResumeFixture(scheduler)
        val attempt = fixture.partial(fixture.plan())
        fixture.baseline.install(attempt)
        fixture.baseline.materialAvailable = false
        advanceTimeBy(20_000)
        val model = model(fixture)
        model.openHistory(attempt.id) {}; runCurrent()
        assertNotNull(model.state.value.error)
        assertNull(model.state.value.attempt)
        assertEquals(0, fixture.baseline.resumes)
        assertTrue(fixture.baseline.saveAttempts.isEmpty())
        fixture.baseline.materialAvailable = true
        model.retry(); runCurrent()
        assertNull(model.state.value.error)
        assertEquals(attempt.id, model.state.value.attempt?.id)
        assertEquals(1, fixture.baseline.resumes)
        assertEquals(1, fixture.baseline.records.size)
        assertNoPreparation(fixture)
    }

    @Test fun mismatchedFrozenMaterialIsRejectedWithoutDownloadingReplacementText() = runTest(dispatcher) {
        val fixture = ResumeFixture(scheduler)
        val plan = fixture.plan(start = 1)
        val attempt = fixture.partial(plan)
        fixture.baseline.install(attempt)
        fixture.baseline.materialOverride = requireNotNull(fixture.plans.material(plan.planKey)).copy(bodyHash = "different-body")
        advanceTimeBy(20_000)
        val model = model(fixture)
        model.openHistory(attempt.id) {}; runCurrent()
        assertNotNull(model.state.value.error)
        assertNull(model.state.value.material)
        assertNull(model.state.value.attempt)
        assertEquals(0, fixture.baseline.resumes)
        fixture.baseline.materialOverride = null
        model.retry(); runCurrent()
        assertEquals(attempt.id, model.state.value.attempt?.id)
        assertNull(model.state.value.error)
        assertNoPreparation(fixture)
    }

    @Test fun lateOldHistoryResponseCannotReplaceANewerSelectionOrResumeTheOldAttempt() = runTest(dispatcher) {
        val fixture = ResumeFixture(scheduler)
        val older = fixture.partial(fixture.plan(start = 0), id = "older")
        val newer = fixture.partial(fixture.plan(start = 1), id = "newer")
        fixture.baseline.install(older); fixture.baseline.install(newer)
        advanceTimeBy(20_000)
        fixture.baseline.delayNextGet = true
        val model = model(fixture)
        val opened = mutableListOf<String>()
        model.openHistory(older.id) { opened += older.id }; runCurrent()
        val pendingOldRead = requireNotNull(fixture.baseline.lateGet)
        model.openHistory(newer.id) { opened += newer.id }; runCurrent()
        assertEquals(newer.id, model.state.value.attempt?.id)
        pendingOldRead.resume(older); runCurrent()
        assertEquals(newer.id, model.state.value.attempt?.id)
        assertEquals(listOf(newer.id), opened)
        assertEquals(1, fixture.baseline.resumes)
        assertFalse(requireNotNull(fixture.baseline.records[older.id]).resumedFromHistory)
        assertNoPreparation(fixture)
    }

    @Test fun historyCheckpointMergeKeepsNewerAnswersFrozenTimeAndDatabaseProvenance() = runTest(dispatcher) {
        val fixture = ResumeFixture(scheduler)
        val plan = fixture.plan(start = 1, count = 2)
        val stored = fixture.readingFinished(plan).copy(
            answers = listOf(SpeedAnswer(plan.questions.first().id, 1, false)),
            localUnseen = false, userConfirmedUnseen = false,
        )
        val oldCheckpoint = stored.copy(
            paragraphs = listOf(NaturalParagraphTiming(1, 8_000, true), NaturalParagraphTiming(2, 5_000, true)),
            readingFinishedAt = ResumeFixture.EPOCH + 14_000,
            answers = emptyList(), localUnseen = true, userConfirmedUnseen = true,
        )
        fixture.baseline.install(stored)
        advanceTimeBy(20_000)
        val model = model(fixture, checkpoint(oldCheckpoint))
        model.openHistory(stored.id) {}; runCurrent()
        val restored = requireNotNull(model.state.value.attempt)
        assertEquals(stored.answers, restored.answers)
        assertEquals(stored.paragraphs, restored.paragraphs)
        assertEquals(stored.readingFinishedAt, restored.readingFinishedAt)
        assertEquals(10_000L, model.state.value.elapsedMillis)
        assertFalse(restored.localUnseen)
        assertFalse(restored.userConfirmedUnseen)
        assertTrue(restored.resumedFromHistory)
        assertEquals(BaselineStage.QUESTION, model.state.value.stage)
        assertEquals(1, fixture.baseline.resumes)
        assertTrue(fixture.baseline.saveAttempts.isEmpty())
        assertNoPreparation(fixture)
    }

    @Test fun completedDatabaseRecordWinsOverAnUnfinishedHistoryCheckpointWithoutWrites() = runTest(dispatcher) {
        val fixture = ResumeFixture(scheduler)
        val plan = fixture.plan()
        val completed = fixture.completed(plan)
        val oldCheckpoint = fixture.partial(plan, id = completed.id)
        fixture.baseline.install(completed)
        advanceTimeBy(20_000)
        val model = model(fixture, checkpoint(oldCheckpoint))
        model.openHistory(completed.id) {}; runCurrent()
        assertEquals(completed, model.state.value.attempt)
        assertEquals(BaselineStage.RESULT, model.state.value.stage)
        assertEquals(0, fixture.baseline.resumes)
        assertTrue(fixture.baseline.saveAttempts.isEmpty())
        assertNoPreparation(fixture)
    }

    private fun model(fixture: ResumeFixture, saved: SavedStateHandle = SavedStateHandle()): ReadingBaselineViewModel =
        ReadingBaselineViewModel(fixture.app, saved, fixture::now, { scheduler.currentTime }).also { model ->
            ViewModelStore().also { it.put("baseline", model); stores += it }
        }

    private fun checkpoint(attempt: ReadingBaselineAttempt) = SavedStateHandle(mapOf("reading-baseline" to Json.encodeToString(attempt)))

    private fun assertNoPreparation(fixture: ResumeFixture) {
        assertEquals(0, fixture.baseline.begins)
        assertEquals(0, fixture.plans.preparations)
        assertEquals(0, fixture.plans.retries)
        assertEquals(0, fixture.app.baselineBodyReads)
        assertEquals(0, fixture.app.normalBodyReads)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class ResumeFixture(private val scheduler: TestCoroutineScheduler) {
    val article = Article("resume-article", "g", listOf("source"), "A frozen history exercise", "https://example.org/resume", "")
    private val text = "The first paragraph is outside the selected history exercise.\n\n" +
        "Researchers compare several explanations before they reach a cautious conclusion.\n\n" +
        "Reliable observations help them identify which claims need additional supporting evidence."
    val body = ArticleBody(article.id, text, fingerprint(text), EPOCH, Long.MAX_VALUE, "Synthetic")
    fun now() = EPOCH + scheduler.currentTime
    val plans = BaselineTestPlans(::now)
    val baseline = BaselineTestRepository(plans, ::now)
    val app = BaselineTestAppRepository(plans, baseline, body)

    fun plan(start: Int = 0, count: Int = 1) = plans.makePlan(article, body, start, count, true)
    fun partial(plan: SpeedPlan, id: String = "history-attempt") = ReadingBaselineAttempt(
        id, plan.articleId, plan.bodyHash, plan.planKey, EPOCH, EPOCH + 5_000,
        plan.paragraphIndices.mapIndexed { i, paragraph -> NaturalParagraphTiming(paragraph, if (i == 0) 2_000L else 0L, false) },
        emptyList(), plan.questions.size, localUnseen = true, userConfirmedUnseen = true,
    )
    fun readingFinished(plan: SpeedPlan): ReadingBaselineAttempt = partial(plan).copy(
        paragraphs = plan.paragraphIndices.map { NaturalParagraphTiming(it, 10_000L / plan.paragraphIndices.size, true) },
        readingFinishedAt = EPOCH + 12_000, finishedAt = EPOCH + 15_000,
    )
    fun completed(plan: SpeedPlan) = readingFinished(plan).copy(
        answers = plan.questions.map { SpeedAnswer(it.id, it.answerIndex, true) }, completed = true, revealedAt = EPOCH + 15_000,
    )
    companion object { const val EPOCH = 1_000_000L }
}
