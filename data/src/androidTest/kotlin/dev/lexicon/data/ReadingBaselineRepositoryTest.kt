package dev.lexicon.data

import android.content.Context
import androidx.room3.Room
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingBaselineRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val codec = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val text = "The committee considers a public library. Residents compare the available sites.\n\nVolunteers describe the opening hours. Members discuss the costs and benefits."
    private val article = Article("baseline-fixture", "g", listOf("fixture-source"), "A new library", "https://example.org/baseline",
        "Summary stays outside the assessment", sourceName = "Self-authored test", readingMode = ReadingMode.NATIVE)
    private val body = ArticleBody(article.id, text, sha256(text), 0, Long.MAX_VALUE, "Self-authored test")
    private var now = 1000L
    private data class Fixture(val db: LexiconDatabase, val speed: LocalSpeedReadingRepository,
        val repository: LocalReadingBaselineRepository, val plan: SpeedPlan)
    private suspend fun fixture(ai: Boolean = true, cacheBody: Boolean = false): Fixture {
        val db = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO).build()
        db.dao().putArticle(article.row())
        if (cacheBody) db.dao().putBody(BodyRow(body.articleId, body.expiresAt, codec.encodeToString(body)))
        val generator = SpeedPlanGenerator { _, input, proposal ->
            val paragraphs = ParagraphFocus.paragraphs(input.text)
            proposal.copy(origin = "ai", model = "synthetic/no-network", questions = proposal.paragraphIndices.map { index ->
                val paragraph = paragraphs[index]
                SpeedQuestion("p$index", index, "What is considered?", listOf("A library", "A flight", "An election"),
                    0, paragraph.start, paragraph.endExclusive, "The paragraph discusses a library.")
            })
        }
        val speed = LocalSpeedReadingRepository(db, generator, hasKey = { true }, sourceAllowed = { true }, clock = { now })
        val plan = speed.prepare(article, body, SpeedGranularity.L2, 0, 2, true, ai)
        return Fixture(db, speed, LocalReadingBaselineRepository(db, speed, clock = { now }), plan)
    }
    private fun Article.row() = ArticleRow(id, url, publishedAt ?: 0, codec.encodeToString(this))
    private fun readAll(attempt: ReadingBaselineAttempt) = attempt.copy(finishedAt = now,
        paragraphs = attempt.paragraphs.map { it.copy(activeMillis = 6000, completed = true) })
    private fun answers(plan: SpeedPlan) = plan.questions.map { SpeedAnswer(it.id, 0, false) }
    private suspend fun invalid(block: suspend () -> Unit) {
        try { block(); fail("Expected rejected assessment mutation") } catch (_: IllegalArgumentException) { }
    }

    @Test fun cachedBodyPreparationDoesNotMarkReadButNormalOpenDoes() = runBlocking {
        val fixture = fixture(ai = false, cacheBody = true)
        try {
            val app = LexiconRepository(context, fixture.db)
            assertEquals(body, app.prepareBaselineBody(article.id))
            assertFalse(codec.decodeFromString<Article>(fixture.db.dao().article(article.id)!!.data).read)
            assertNull(fixture.db.readingDao().entry("reading-exposure:article:${article.id}"))
            assertNull(fixture.db.readingDao().entry("reading-exposure:body:${body.contentHash}"))
            assertTrue(fixture.db.dao().observeJobs().first().isEmpty())
            assertEquals(body, app.openArticle(article.id))
            assertTrue(codec.decodeFromString<Article>(fixture.db.dao().article(article.id)!!.data).read)
            assertNotNull(fixture.db.readingDao().entry("reading-exposure:body:${body.contentHash}"))
            assertFalse(fixture.repository.begin(fixture.plan.planKey, true).localUnseen)
        } finally { fixture.db.close() }
    }

    @Test fun beginAtomicallyReservesLocalFirstExposureAndFreezesUserDeclarationAndSource() = runBlocking {
        val f = fixture()
        try {
            val first = f.repository.begin(f.plan.planKey, false)
            assertTrue(first.localUnseen); assertFalse(first.userConfirmedUnseen)
            assertTrue(first.paragraphs.all { it.activeMillis == 0L && !it.completed })
            assertNull(first.readingFinishedAt); assertFalse(first.completed)
            val source = f.db.readingDao().entry("baseline-source:${first.id}")
            f.db.dao().putArticle(article.copy(title = "Changed feed title", read = false).row())
            val second = f.repository.begin(f.plan.planKey, true)
            assertFalse(second.localUnseen); assertTrue(second.userConfirmedUnseen)
            assertEquals(first, f.repository.get(first.id))
            assertEquals(source, f.db.readingDao().entry("baseline-source:${first.id}"))
            assertTrue(f.speed.library.first().runs.isEmpty()); assertTrue(f.speed.library.first().naturalAttempts.isEmpty())
        } finally { f.db.close() }
    }

    @Test fun preparingBaselinePreservesExposureBeforeExpiringAnOlderReadAliasBody() = runBlocking {
        val f = fixture(ai = false, cacheBody = true)
        try {
            val alias = article.copy(id = "expired-syndication", url = "https://example.org/expired", read = true)
            f.db.dao().putArticle(alias.row())
            f.db.dao().putBody(BodyRow(alias.id, 1, codec.encodeToString(body.copy(articleId = alias.id, expiresAt = 1))))
            assertEquals(body, LexiconRepository(context, f.db).prepareBaselineBody(article.id))
            assertNull(f.db.readingDao().bodyRow(alias.id))
            assertNotNull(f.db.readingDao().entry("reading-exposure:body:${body.contentHash}"))
            assertFalse(f.repository.begin(f.plan.planKey, true).localUnseen)
        } finally { f.db.close() }
    }

    @Test fun concurrentStartsCannotBothClaimUnseenMaterial() = runBlocking {
        val f = fixture()
        try {
            val other = LocalReadingBaselineRepository(f.db, f.speed, clock = { now })
            val attempts = coroutineScope { listOf(async { f.repository.begin(f.plan.planKey, true) },
                async { other.begin(f.plan.planKey, true) }).awaitAll() }
            assertEquals(1, attempts.count { it.localUnseen })
            assertEquals(2, attempts.map { it.id }.distinct().size)
            assertEquals(2, f.repository.library.first().size)
        } finally { f.db.close() }
    }

    @Test fun knownReadSessionPreviewTrainingAndCachedAliasesDisqualifyNewMaterial() = runBlocking {
        val cases: List<suspend (Fixture) -> Unit> = listOf(
            { f -> f.db.dao().putArticle(article.copy(read = true).row()) },
            { f -> f.db.readingDao().put(PreferenceRow("reading-library:session:prior", codec.encodeToString(
                ReadingSession("prior", article.id, StudyMode.INTENSIVE, null, 0, 1, 0, 0, 0, 2, 0, false)))) },
            { f -> f.speed.markSentencePreviewed(f.plan.planKey, 0) },
            { f -> f.db.readingDao().put(PreferenceRow("speed-library:run:prior", codec.encodeToString(
                SpeedRun("prior", article.id, body.contentHash, f.plan.granularity, f.plan.planKey, 0, 1, 1,
                    1, 1, List(f.plan.chunks.size) { if (it == 0) 1L else 0L }, 0, 0, emptyList(), f.plan.questions.size, false, 60, f.plan.origin)))) },
            { f ->
                val alias = article.copy(id = "syndicated-read", url = "https://example.org/alias", read = true)
                f.db.dao().putArticle(alias.row())
                f.db.dao().putBody(BodyRow(alias.id, 1, codec.encodeToString(body.copy(articleId = alias.id, expiresAt = 1))))
            },
            { f -> f.db.readingDao().put(PreferenceRow("reading-exposure:body:${body.contentHash}", "1")) },
            { f -> f.db.dao().putCapture(CaptureRow("capture", 1, codec.encodeToString(
                Capture("capture", target = "library", selectedText = "library", bodyHash = body.contentHash, createdAt = 1)))) },
            { f -> f.db.readingDao().put(PreferenceRow("reading-library:session:damaged", "not-json")) },
        )
        for (seed in cases) {
            val f = fixture()
            try { seed(f); assertFalse(f.repository.begin(f.plan.planKey, true).localUnseen) }
            finally { f.db.close() }
        }
    }

    @Test fun baselineReservationAlsoPreventsLaterGuidedFirstAttemptClaims() = runBlocking {
        val f = fixture()
        try {
            val baseline = f.repository.begin(f.plan.planKey, true)
            val dwell = List(f.plan.chunks.size) { 1000L }
            f.speed.saveRun(SpeedRun("guided-after-baseline", article.id, body.contentHash, f.plan.granularity, f.plan.planKey,
                1000, 1000 + dwell.sum(), dwell.sum(), 999, 999, dwell, 0, 0, answers(f.plan), f.plan.questions.size, true,
                60, f.plan.origin, firstAttempt = true, pacingStable = true, pacedCompletion = true))
            assertFalse(f.speed.library.first().runs.single().firstAttempt)
            assertTrue(f.repository.get(baseline.id)!!.localUnseen)
        } finally { f.db.close() }
    }

    @Test fun allReadingPrecedesAnswersAndOnlyAllSubmittedAnswersRevealCorrectness() = runBlocking {
        val f = fixture()
        try {
            val initial = f.repository.begin(f.plan.planKey, true)
            now = 13_000
            invalid { f.repository.save(readAll(initial).copy(answers = answers(f.plan), readingFinishedAt = 13_000, completed = true)) }
            assertEquals(initial, f.repository.get(initial.id))
            val read = f.repository.save(readAll(initial))
            assertEquals(13_000L, read.readingFinishedAt); assertFalse(read.completed); assertNull(read.revealedAt)
            now = 63_000
            val pending = f.repository.save(read.copy(finishedAt = now, answers = answers(f.plan).take(1)))
            assertFalse(pending.answers.single().correct); assertNull(pending.revealedAt)
            assertNull(ReadingBaselineMetrics.summarize(pending, f.plan).accuracy)
            now = 64_000
            val final = f.repository.save(pending.copy(finishedAt = now, answers = listOf(SpeedAnswer("p0", 0, false), SpeedAnswer("p1", 1, true))))
            assertTrue(final.completed); assertEquals(13_000L, final.readingFinishedAt); assertEquals(64_000L, final.revealedAt)
            assertEquals(listOf(true, false), final.answers.map { it.correct })
            val summary = ReadingBaselineMetrics.summarize(final, f.plan)
            assertEquals(12_000L, summary.activeMillis); assertEquals(.5, summary.accuracy!!, .0)
            assertTrue(summary.qualifiesAsBaseline); assertNotNull(summary.effectiveWordsPerMinute)
            assertTrue(f.speed.library.first().runs.isEmpty())
        } finally { f.db.close() }
    }

    @Test fun qualificationCannotBeForgedAndReadingTimeAndSubmittedChoicesCannotBeChanged() = runBlocking {
        val f = fixture()
        try {
            f.db.dao().putArticle(article.copy(read = true).row())
            val initial = f.repository.begin(f.plan.planKey, false)
            now = 13_000
            val read = f.repository.save(readAll(initial).copy(localUnseen = true, userConfirmedUnseen = true, questionCount = 999))
            assertFalse(read.localUnseen); assertFalse(read.userConfirmedUnseen); assertEquals(2, read.questionCount)
            now = 14_000
            invalid { f.repository.save(read.copy(finishedAt = now, paragraphs = read.paragraphs.map { it.copy(activeMillis = 7000) })) }
            val pending = f.repository.save(read.copy(finishedAt = now, answers = answers(f.plan).take(1)))
            now = 15_000
            invalid { f.repository.save(pending.copy(finishedAt = now, answers = listOf(SpeedAnswer("p0", 1, false)))) }
            invalid { f.repository.save(pending.copy(finishedAt = now, answers = listOf(SpeedAnswer("missing", 0, false)))) }
            assertEquals(pending, f.repository.get(pending.id))
        } finally { f.db.close() }
    }

    @Test fun completedAssessmentIsImmutableAndCompletionEventIsRecordedOnce() = runBlocking {
        val f = fixture()
        try {
            val initial = f.repository.begin(f.plan.planKey, true)
            now = 13_000; val read = f.repository.save(readAll(initial))
            now = 14_000; val complete = f.repository.save(read.copy(finishedAt = now, answers = answers(f.plan)))
            now = 18_000
            assertEquals(complete, f.repository.save(complete.copy(finishedAt = now, completed = false,
                answers = emptyList(), localUnseen = false, readingFinishedAt = null)))
            assertEquals(complete, f.repository.get(complete.id))
            assertEquals(1, f.db.dao().allEvents().count { codec.decodeFromString<LocalEvent>(it.data).type == "reading_baseline_completed" })
        } finally { f.db.close() }
    }

    @Test fun offlineAssessmentFinishesWithoutInventedQuestionsOrScores() = runBlocking {
        val f = fixture(ai = false)
        try {
            val initial = f.repository.begin(f.plan.planKey, true)
            now = 13_000; val complete = f.repository.save(readAll(initial))
            assertTrue(complete.completed); assertTrue(complete.answers.isEmpty()); assertEquals(0, complete.questionCount)
            val summary = ReadingBaselineMetrics.summarize(complete, f.plan)
            assertNotNull(summary.wordsPerMinute); assertNull(summary.accuracy); assertNull(summary.effectiveWordsPerMinute)
            assertFalse(summary.qualifiesAsBaseline)
        } finally { f.db.close() }
    }

    @Test fun partialSnapshotsOnlyAccumulateAndOlderDeliveriesCannotEraseNewProgress() = runBlocking {
        val f = fixture()
        try {
            val initial = f.repository.begin(f.plan.planKey, true)
            now = 3000
            val partial = f.repository.save(initial.copy(finishedAt = now, pauses = 1,
                paragraphs = listOf(NaturalParagraphTiming(0, 2000, false), NaturalParagraphTiming(1, 0, false))))
            assertEquals(partial, f.repository.save(initial))
            now = 4000
            invalid { f.repository.save(partial.copy(finishedAt = now, pauses = 0)) }
            invalid { f.repository.save(partial.copy(finishedAt = now,
                paragraphs = listOf(NaturalParagraphTiming(0, 1000, false), NaturalParagraphTiming(1, 0, false)))) }
            assertEquals(partial, LocalReadingBaselineRepository(f.db, f.speed, clock = { now }).get(partial.id))
        } finally { f.db.close() }
    }

    @Test fun fabricatedFutureClockCannotMakeExcessReadingTimeValid() = runBlocking {
        val f = fixture()
        try {
            val initial = f.repository.begin(f.plan.planKey, true)
            now = 2000
            invalid { f.repository.save(initial.copy(finishedAt = 100_000,
                paragraphs = initial.paragraphs.map { it.copy(activeMillis = 15_000, completed = true) },
                readingFinishedAt = 100_000, completed = true)) }
            assertEquals(initial, f.repository.get(initial.id))
        } finally { f.db.close() }
    }

    @Test fun historyMaterialUsesFrozenFragmentsAndSourceAfterBodyExpirationAndFeedChanges() = runBlocking {
        val f = fixture(cacheBody = true)
        try {
            f.db.dao().putArticle(article.copy(topic = "Education").row())
            val attempt = f.repository.begin(f.plan.planKey, true)
            val before = f.repository.library.first()
            val jobsBefore = f.db.dao().observeJobs().first()
            val expected = SourceRef(article.title, article.sourceName, article.url, article.publishedAt)
            f.db.dao().putArticle(article.copy(title = "Different feed title", sourceName = "Later feed source", topic = "Business").row())
            f.db.dao().expireBodies(Long.MAX_VALUE)
            val material = requireNotNull(f.repository.material(attempt.id))
            assertEquals(expected, material.sourceRef)
            assertEquals("Education", material.topic)
            assertEquals(f.plan.planKey, material.planKey); assertEquals(body.contentHash, material.bodyHash)
            assertEquals(f.plan.paragraphIndices, material.paragraphs.map { it.paragraph })
            material.paragraphs.forEach { assertEquals(it.text, body.text.substring(it.start, it.end)) }
            val evidence = f.plan.questions.first()
            assertEquals(body.text.substring(evidence.evidenceStart, evidence.evidenceEnd),
                material.textAt(evidence.evidenceStart, evidence.evidenceEnd))
            assertEquals(before, f.repository.library.first())
            assertTrue(f.db.dao().allEvents().isEmpty())
            assertEquals(jobsBefore, f.db.dao().observeJobs().first())
        } finally { f.db.close() }
    }

    @Test fun damagedOrMismatchedRetainedMaterialIsNotPresentedAsTheOriginal() = runBlocking {
        val f = fixture()
        try {
            val attempt = f.repository.begin(f.plan.planKey, true)
            val key = "speed-plan:${f.plan.planKey}"
            val original = requireNotNull(f.db.readingDao().entry(key))
            val record = codec.decodeFromString<SpeedPlanRecord>(original.data)
            f.db.readingDao().put(original.copy(data = codec.encodeToString(record.copy(sourceDigest = "0".repeat(64)))))
            assertNull(f.repository.material(attempt.id))
            f.db.readingDao().put(original)
            val badSource = record.source.map { it.copy(start = it.start + 1) }
            f.db.readingDao().put(original.copy(data = codec.encodeToString(record.copy(source = badSource,
                sourceDigest = sha256(codec.encodeToString(badSource))))))
            assertNull(f.repository.material(attempt.id))
            f.db.readingDao().put(original)
            f.db.readingDao().put(PreferenceRow("baseline-library:attempt:${attempt.id}",
                codec.encodeToString(attempt.copy(bodyHash = "a".repeat(64)))))
            assertNull(f.repository.material(attempt.id))
        } finally { f.db.close() }
    }

    @Test fun legacyMissingSourceRemainsUnlinkedAndDoesNotUseTheLatestFeedAttribution() = runBlocking {
        val f = fixture()
        try {
            val attempt = f.repository.begin(f.plan.planKey, true)
            f.db.useWriterConnection { connection -> connection.usePrepared("DELETE FROM preferences WHERE key=?") {
                it.bindText(1, "baseline-source:${attempt.id}"); it.step()
            } }
            f.db.dao().putArticle(article.copy(topic = "Education").row())
            val material = requireNotNull(f.repository.material(attempt.id))
            assertEquals(SourceRef(), material.sourceRef)
            assertNull(material.topic)
            assertNull(f.db.readingDao().entry("baseline-source:${attempt.id}"))
        } finally { f.db.close() }
    }

    @Test fun explicitResumePreservesAllProgressAndMonotonicallyMarksHistoryContinuation() = runBlocking {
        val f = fixture()
        try {
            val initial = f.repository.begin(f.plan.planKey, true)
            now = 7_000
            val partial = f.repository.save(initial.copy(finishedAt = now, pauses = 1,
                paragraphs = initial.paragraphs.mapIndexed { index, value -> if (index == 0) value.copy(activeMillis = 6000, completed = true) else value }))
            val source = f.db.readingDao().entry("baseline-source:${initial.id}")
            now = 90_000
            val resumed = f.repository.resume(initial.id)
            assertEquals(partial.copy(resumedFromHistory = true), resumed)
            assertEquals(resumed, f.repository.resume(initial.id))
            assertEquals(1, f.repository.library.first().size)
            assertEquals(source, f.db.readingDao().entry("baseline-source:${initial.id}"))
            assertTrue(f.db.dao().allEvents().isEmpty())
            val reading = f.repository.save(readAll(partial)) // old checkpoint cannot clear the flag
            assertTrue(reading.resumedFromHistory)
            now = 91_000
            val completed = f.repository.save(reading.copy(finishedAt = now, answers = answers(f.plan), resumedFromHistory = false))
            assertTrue(completed.resumedFromHistory); assertTrue(completed.completed)
            val summary = ReadingBaselineMetrics.summarize(completed, f.plan)
            assertEquals(1.0, summary.accuracy!!, 0.0)
            assertNotNull(summary.wordsPerMinute); assertFalse(summary.qualifiesAsBaseline)
            assertNull(summary.effectiveWordsPerMinute)
        } finally { f.db.close() }
    }

    @Test fun resumingAtQuestionStageKeepsTheRecordedAnswerAndFrozenReadingTime() = runBlocking {
        val f = fixture()
        try {
            val initial = f.repository.begin(f.plan.planKey, true)
            now = 13_000
            val reading = f.repository.save(readAll(initial))
            now = 14_000
            val pending = f.repository.save(reading.copy(finishedAt = now, answers = answers(f.plan).take(1)))
            now = 100_000
            val resumed = f.repository.resume(initial.id)
            assertEquals(pending.copy(resumedFromHistory = true), resumed)
            assertNull(resumed.revealedAt); assertFalse(resumed.answers.single().correct)
            assertEquals(13_000L, resumed.readingFinishedAt)
            invalid { f.repository.save(resumed.copy(finishedAt = now, answers = listOf(SpeedAnswer("p0", 1, false)))) }
            assertEquals(resumed, f.repository.get(initial.id))
        } finally { f.db.close() }
    }

    @Test fun completedHistoryIsImmutableEvenWhenRetainedMaterialIsUnavailable() = runBlocking {
        val f = fixture()
        try {
            val initial = f.repository.begin(f.plan.planKey, true)
            now = 13_000
            val reading = f.repository.save(readAll(initial))
            now = 14_000
            val completed = f.repository.save(reading.copy(finishedAt = now, answers = answers(f.plan)))
            val events = f.db.dao().allEvents()
            f.db.useWriterConnection { connection -> connection.usePrepared("DELETE FROM preferences WHERE key=?") {
                it.bindText(1, "speed-plan:${f.plan.planKey}"); it.step()
            } }
            assertNull(f.repository.material(completed.id))
            assertEquals(completed, f.repository.resume(completed.id))
            assertFalse(f.repository.get(completed.id)!!.resumedFromHistory)
            assertEquals(events, f.db.dao().allEvents())
        } finally { f.db.close() }
    }

    @Test fun concurrentResumeAndCheckpointCannotLoseProgressOrRestoreBaselineQualification() = runBlocking {
        val f = fixture()
        try {
            val initial = f.repository.begin(f.plan.planKey, true)
            now = 13_000
            coroutineScope {
                listOf(async { f.repository.resume(initial.id) }, async { f.repository.save(readAll(initial)) }).awaitAll()
            }
            val stored = requireNotNull(f.repository.get(initial.id))
            assertTrue(stored.resumedFromHistory); assertTrue(stored.paragraphs.all { it.completed })
            assertEquals(12_000L, stored.paragraphs.sumOf { it.activeMillis })
            assertEquals(13_000L, stored.readingFinishedAt)
            assertEquals(1, f.repository.library.first().size)
        } finally { f.db.close() }
    }
}
