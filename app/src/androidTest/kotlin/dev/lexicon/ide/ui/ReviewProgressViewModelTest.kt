package dev.lexicon.ide.ui

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** UI coordination only. Real committed-count and transaction semantics are tested in data. */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ReviewProgressViewModelTest {
    private lateinit var dispatcher: TestDispatcher
    private lateinit var store: ViewModelStore
    private val day = ReviewDayRange(1_000, 5_000)
    private val nextDay = ReviewDayRange(5_000, 9_000)
    private val card = CaptureAnalysisTestRepository.card(CaptureAnalysisTestRepository.capture)

    @Before fun before() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        store = ViewModelStore()
    }
    @After fun after() { store.clear(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }
    private fun model(repository: AppRepository) = LexiconViewModel(repository).also { store.put("review-progress", it) }
    private fun receipt(count: Int, id: String = "committed-$count", at: Long = 2_000) = ReviewDaySummary(count,
        if (count == 0) null else ReviewReceipt(id, card.id, at, Rating.GOOD, count - 1, count))

    @Test fun initialLoadingStaysUnknownUntilTheRepositoryEmitsEvenWhenItsRealResultIsZero() = runTest(dispatcher) {
        val stream = MutableSharedFlow<ReviewDaySummary>()
        val repository = ReviewProgressRepository().apply { historyAction = { _, _ -> stream } }
        val vm = model(repository)
        assertEquals(ReviewProgressUiState(loading = true), vm.reviewProgress)
        vm.observeReviewDay(day)
        assertEquals(ReviewProgressUiState(day, loading = true), vm.reviewProgress)
        runCurrent()
        assertEquals(listOf(day), repository.observations)
        assertNull(vm.reviewProgress?.summary)
        assertTrue(requireNotNull(vm.reviewProgress).loading)
        stream.emit(ReviewDaySummary()); runCurrent()
        assertEquals(ReviewProgressUiState(day, ReviewDaySummary()), vm.reviewProgress)
        assertNull(vm.busy); assertNull(vm.notice)
    }

    @Test fun unavailableOptionalHistoryDoesNotPretendToHaveAZeroCount() = runTest(dispatcher) {
        val vm = model(CaptureAnalysisTestRepository())
        vm.observeReviewDay(day); vm.retryReviewHistory(); runCurrent()
        assertNull(vm.reviewProgress)
    }

    @Test fun summaryChangesOnlyWhenCommittedHistoryEmitsNotWhenAScoringCallbackCompletes() = runTest(dispatcher) {
        val stream = MutableSharedFlow<ReviewDaySummary>()
        val repository = ReviewProgressRepository().apply { historyAction = { _, _ -> stream } }
        val vm = model(repository); runCurrent(); vm.observeReviewDay(day); runCurrent()
        val before = receipt(2); stream.emit(before); runCurrent()
        var completed = 0
        vm.review(card, Rating.GOOD) { completed++ }; runCurrent()
        assertEquals(1, completed)
        assertEquals(ReviewProgressUiState(day, before), vm.reviewProgress)
        val committed = receipt(3, "new-committed-log")
        stream.emit(committed); runCurrent()
        assertEquals(ReviewProgressUiState(day, committed), vm.reviewProgress)
        assertEquals(0, repository.backing.analysisCalls)
    }

    @Test fun sameActiveWindowDoesNotResubscribeAndExplicitRetryRetainsOnlyItsKnownSummary() = runTest(dispatcher) {
        val stream = MutableSharedFlow<ReviewDaySummary>()
        val repository = ReviewProgressRepository().apply { historyAction = { _, _ -> stream } }
        val vm = model(repository); runCurrent()
        vm.observeReviewDay(day); vm.observeReviewDay(day.copy()); runCurrent()
        val known = receipt(2); stream.emit(known); runCurrent()
        repeat(3) { vm.observeReviewDay(day.copy()) }; runCurrent()
        assertEquals(listOf(day), repository.observations)
        assertEquals(ReviewProgressUiState(day, known), vm.reviewProgress)
        vm.retryReviewHistory()
        assertEquals(ReviewProgressUiState(day, known, loading = true), vm.reviewProgress)
        runCurrent()
        assertEquals(listOf(day, day), repository.observations)
        val fresh = receipt(3); stream.emit(fresh); runCurrent()
        assertEquals(ReviewProgressUiState(day, fresh), vm.reviewProgress)
    }

    @Test fun dateAndTimezoneChangesClearThePriorDaysCountBeforeTheNewFlowResponds() = runTest(dispatcher) {
        val now = Instant.parse("2026-11-04T18:30:00Z").toEpochMilli()
        val shanghai = ReviewDayRange.from(now, ZoneId.of("Asia/Shanghai"))
        val tomorrow = ReviewDayRange.from(shanghai.endExclusive, ZoneId.of("Asia/Shanghai"))
        val newYork = ReviewDayRange.from(shanghai.endExclusive, ZoneId.of("America/New_York"))
        val streams = listOf(shanghai, tomorrow, newYork).associateWith { MutableSharedFlow<ReviewDaySummary>() }
        val repository = ReviewProgressRepository().apply { historyAction = { range, _ -> streams.getValue(range) } }
        val vm = model(repository); runCurrent(); vm.observeReviewDay(shanghai); runCurrent()
        streams.getValue(shanghai).emit(receipt(7, at = shanghai.startInclusive)); runCurrent()
        vm.observeReviewDay(tomorrow)
        assertEquals(ReviewProgressUiState(tomorrow, loading = true), vm.reviewProgress)
        runCurrent()
        streams.getValue(tomorrow).emit(receipt(1, at = tomorrow.startInclusive)); runCurrent()
        vm.observeReviewDay(newYork)
        assertEquals(ReviewProgressUiState(newYork, loading = true), vm.reviewProgress)
        runCurrent()
        streams.getValue(shanghai).emit(receipt(99, at = shanghai.startInclusive))
        streams.getValue(tomorrow).emit(receipt(99, at = tomorrow.startInclusive)); runCurrent()
        assertEquals(ReviewProgressUiState(newYork, loading = true), vm.reviewProgress)
        val current = receipt(4, at = newYork.startInclusive)
        streams.getValue(newYork).emit(current); runCurrent()
        assertEquals(ReviewProgressUiState(newYork, current), vm.reviewProgress)
        assertEquals(listOf(shanghai, tomorrow, newYork), repository.observations)
    }

    @Test fun uncancellableOldWindowEmissionCannotClearTheNewWindowsLoadingOrSetItsCount() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val current = MutableSharedFlow<ReviewDaySummary>()
        var lateEmissions = 0
        val repository = ReviewProgressRepository().apply { historyAction = { range, _ ->
            if (range == day) uncancellableFlow { collector ->
                release.await(); collector.emit(receipt(9)); lateEmissions++
            } else current
        } }
        val vm = model(repository); runCurrent()
        try {
            vm.observeReviewDay(day); runCurrent(); vm.observeReviewDay(nextDay); runCurrent()
            release.complete(Unit); runCurrent()
            assertEquals("The stale collector must actually have emitted after cancellation", 1, lateEmissions)
            assertEquals(ReviewProgressUiState(nextDay, loading = true), vm.reviewProgress)
            val latest = receipt(1, at = 6_000); current.emit(latest); runCurrent()
            assertEquals(ReviewProgressUiState(nextDay, latest), vm.reviewProgress)
        } finally { release.complete(Unit); runCurrent() }
    }

    @Test fun uncancellableEarlierAttemptCannotReplaceAConfirmedRetryForTheSameWindow() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val current = MutableSharedFlow<ReviewDaySummary>()
        var lateEmissions = 0
        val repository = ReviewProgressRepository().apply { historyAction = { _, attempt ->
            if (attempt == 1) uncancellableFlow { collector ->
                release.await(); collector.emit(receipt(9, "old-attempt")); lateEmissions++
            } else current
        } }
        val vm = model(repository); runCurrent()
        try {
            vm.observeReviewDay(day); runCurrent(); vm.retryReviewHistory(); runCurrent()
            val latest = receipt(2, "retry-receipt"); current.emit(latest); runCurrent()
            release.complete(Unit); runCurrent()
            assertEquals(1, lateEmissions)
            assertEquals(ReviewProgressUiState(day, latest), vm.reviewProgress)
            assertEquals(listOf(day, day), repository.observations)
        } finally { release.complete(Unit); runCurrent() }
    }

    @Test fun uncancellableOldFailureCannotMarkTheNewWindowsConfirmedHistoryAsFailed() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val current = MutableSharedFlow<ReviewDaySummary>()
        var lateFailures = 0
        val repository = ReviewProgressRepository().apply { historyAction = { range, _ ->
            if (range == day) uncancellableFlow {
                release.await(); lateFailures++; throw IOException("private obsolete diagnostic")
            } else current
        } }
        val vm = model(repository); runCurrent()
        try {
            vm.observeReviewDay(day); runCurrent(); vm.observeReviewDay(nextDay); runCurrent()
            val latest = receipt(1, at = 6_000); current.emit(latest); runCurrent()
            release.complete(Unit); runCurrent()
            assertEquals(1, lateFailures)
            assertEquals(ReviewProgressUiState(nextDay, latest), vm.reviewProgress)
            assertNull(vm.notice)
        } finally { release.complete(Unit); runCurrent() }
    }

    @Test fun readFailureExposesNoDiagnosticAndAnExplicitRetryCanRecoverFromUnknown() = runTest(dispatcher) {
        val current = MutableSharedFlow<ReviewDaySummary>()
        val diagnostic = "private diagnostic contains raw review JSON"
        val repository = ReviewProgressRepository().apply { historyAction = { _, attempt ->
            if (attempt == 1) flow { throw IOException(diagnostic) } else current
        } }
        val vm = model(repository); runCurrent(); vm.observeReviewDay(day); runCurrent()
        assertEquals(ReviewProgressUiState(day, failed = true), vm.reviewProgress)
        assertFalse(vm.reviewProgress.toString().contains(diagnostic)); assertNull(vm.notice); assertNull(vm.busy)
        vm.retryReviewHistory()
        assertEquals(ReviewProgressUiState(day, loading = true), vm.reviewProgress)
        runCurrent()
        val latest = receipt(2); current.emit(latest); runCurrent()
        assertEquals(ReviewProgressUiState(day, latest), vm.reviewProgress)
    }

    @Test fun laterReadFailureRetainsThePreviouslyVerifiedSummaryAndRetryClearsOnlyTheError() = runTest(dispatcher) {
        val known = receipt(2)
        val current = MutableSharedFlow<ReviewDaySummary>()
        val repository = ReviewProgressRepository().apply { historyAction = { _, attempt ->
            if (attempt == 1) flow { emit(known); throw IOException("private later failure") } else current
        } }
        val vm = model(repository); runCurrent(); vm.observeReviewDay(day); runCurrent()
        assertEquals(ReviewProgressUiState(day, known, failed = true), vm.reviewProgress)
        vm.retryReviewHistory()
        assertEquals(ReviewProgressUiState(day, known, loading = true), vm.reviewProgress)
        runCurrent()
        val latest = receipt(3); current.emit(latest); runCurrent()
        assertEquals(ReviewProgressUiState(day, latest), vm.reviewProgress)
        assertNull(vm.notice)
    }

    @Test fun schedulingFailureOnlySuggestsLocalRetryAndReleasesBusyWithoutCallingSuccess() = runTest(dispatcher) {
        val repository = ReviewProgressRepository().apply { reviewAction = { throw IOException("private scoring diagnostic") } }
        val vm = model(repository); runCurrent()
        var completed = 0
        vm.review(card, Rating.GOOD) { completed++ }
        assertEquals("scheduling", vm.busy)
        runCurrent()
        assertEquals("[error] 本地评分未保存，请重试。", vm.notice)
        assertFalse(requireNotNull(vm.notice).contains("API")); assertFalse(requireNotNull(vm.notice).contains("private"))
        assertNull(vm.busy); assertEquals(0, completed)
        repository.reviewAction = { ReviewOutcome.APPLIED }
        vm.review(card, Rating.GOOD) { completed++ }; runCurrent()
        assertNull(vm.busy); assertEquals(1, completed); assertNull(vm.notice)
        assertEquals(0, repository.backing.analysisCalls)
    }

    @Test fun pendingRatingRejectsDuplicateClicksAndOnlyAppliedCallsSuccessWithoutInventingHistory() = runTest(dispatcher) {
        val repository = ReviewProgressRepository()
        val vm = model(repository); runCurrent()
        var completed = 0
        for (outcome in ReviewOutcome.entries) {
            val release = CompletableDeferred<ReviewOutcome>()
            repository.reviewAction = { release.await() }
            val priorSubmissions = repository.reviews.size
            try {
                vm.review(card, Rating.GOOD) { completed++ }
                vm.review(card, Rating.EASY) { completed++ }
                assertEquals("scheduling", vm.busy)
                runCurrent()
                vm.review(card, Rating.AGAIN) { completed++ }; runCurrent()
                assertEquals(priorSubmissions + 1, repository.reviews.size)
                assertEquals(ReviewSubmission(card.id, Rating.GOOD, card.state.reps), repository.reviews.last())
                release.complete(outcome); runCurrent()
                assertNull(vm.busy)
            } finally { release.complete(outcome); runCurrent() }
        }
        assertEquals(1, completed)
        assertNull(vm.reviewProgress?.summary)
        assertEquals(0, repository.backing.captureSaves)
        assertEquals(0, repository.backing.analysisCalls)
    }

    /** A deliberately non-cooperative source exercises the generation guard after cancel(), not just Flow cancellation. */
    private fun uncancellableFlow(block: suspend (FlowCollector<ReviewDaySummary>) -> Unit): Flow<ReviewDaySummary> =
        object : Flow<ReviewDaySummary> {
            override suspend fun collect(collector: FlowCollector<ReviewDaySummary>) {
                withContext(NonCancellable) { block(collector) }
            }
        }
}

private data class ReviewSubmission(val cardId: String, val rating: Rating, val expectedReps: Int)

private class ReviewProgressRepository(
    val backing: CaptureAnalysisTestRepository = CaptureAnalysisTestRepository(),
) : AppRepository by backing {
    val observations = mutableListOf<ReviewDayRange>()
    val reviews = mutableListOf<ReviewSubmission>()
    var historyAction: (ReviewDayRange, Int) -> Flow<ReviewDaySummary> = { _, _ -> emptyFlow() }
    var reviewAction: suspend (ReviewSubmission) -> ReviewOutcome = { ReviewOutcome.APPLIED }
    override val reviewHistory: ReviewHistoryRepository = object : ReviewHistoryRepository {
        override fun observeDay(range: ReviewDayRange): Flow<ReviewDaySummary> {
            observations += range
            return historyAction(range, observations.size)
        }
    }
    override suspend fun reviewCard(cardId: String, rating: Rating, expectedReps: Int, now: Long): ReviewOutcome {
        val submission = ReviewSubmission(cardId, rating, expectedReps)
        reviews += submission
        return reviewAction(submission)
    }
}
