package dev.lexicon.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Real in-memory Room with self-authored source/questions; the generator never accesses a model or network. */
@RunWith(AndroidJUnit4::class)
class SpeedComprehensionReviewRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val codec = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val introduction = "A regional bulletin introduces several community projects."
    private val firstEvidence = "The council approved a new library. Residents will share the former station."
    private val secondEvidence = "Opening hours will expand in autumn."
    private val text = "$introduction\n\n$firstEvidence\n\n$secondEvidence Volunteers will offer evening workshops."
    private val article = Article("comprehension-fixture", "g", listOf("synthetic"), "A community library",
        "https://example.org/comprehension-fixture", "Self-authored fixture", sourceName = "Self-authored test",
        readingMode = ReadingMode.NATIVE)
    private val body = ArticleBody(article.id, text, sha256(text), 100, 1_000, "Self-authored test material")

    private data class Fixture(
        val db: LexiconDatabase,
        val repository: LocalSpeedReadingRepository,
        val plan: SpeedPlan,
        val saved: SpeedRun,
        val generations: AtomicInteger,
        val keyChecks: AtomicInteger,
        val sourceChecks: AtomicInteger,
    )
    private data class Snapshot(
        val preferences: List<PreferenceRow>, val events: List<EventRow>, val jobs: List<JobRow>,
        val articles: List<ArticleRow>, val body: BodyRow?, val library: SpeedLibrary,
        val generations: Int, val keyChecks: Int, val sourceChecks: Int,
    )

    private suspend fun fixture(completed: Boolean = true, answers: Int = 2, useAi: Boolean = true): Fixture {
        val db = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO).build()
        try {
            val generations = AtomicInteger()
            val keyChecks = AtomicInteger()
            val sourceChecks = AtomicInteger()
            val generator = SpeedPlanGenerator { _, source, local ->
                generations.incrementAndGet()
                check(source.text == text && local.paragraphIndices == listOf(1, 2))
                val firstStart = text.indexOf(firstEvidence)
                val secondStart = text.indexOf(secondEvidence)
                local.copy(origin = "ai", model = "synthetic/no-network", questions = listOf(
                    SpeedQuestion("library-location", 1, "What will occupy the former station?",
                        listOf("A theatre", "A shared library", "A private office"), 1,
                        firstStart, firstStart + firstEvidence.length,
                        "The first sentence identifies the library; the next identifies its shared location."),
                    SpeedQuestion("library-hours", 2, "When will opening hours expand?",
                        listOf("In autumn", "In spring", "Next summer"), 0,
                        secondStart, secondStart + secondEvidence.length,
                        "The statement explicitly says that opening hours will expand in autumn."),
                ))
            }
            val repository = LocalSpeedReadingRepository(db, generator,
                hasKey = { keyChecks.incrementAndGet(); true },
                sourceAllowed = { sourceChecks.incrementAndGet(); true }, clock = { 500 })
            db.dao().putArticle(ArticleRow(article.id, article.url, 100, codec.encodeToString(article)))
            db.dao().putBody(BodyRow(body.articleId, body.expiresAt, codec.encodeToString(body)))
            db.readingDao().put(PreferenceRow("unrelated-fixture-preference", "retain these exact self-authored bytes"))
            val plan = repository.prepare(article, body, SpeedGranularity.L2, 1, 2, true, useAi)
            val dwell = List(plan.chunks.size) { 1_000L }
            val submitted = SpeedRun("comprehension-run", article.id, body.contentHash, plan.granularity, plan.planKey,
                2_000, 2_000 + dwell.sum(), dwell.sum(), 0, 0, dwell, 0, 0,
                plan.questions.take(answers).map { SpeedAnswer(it.id, 0, false) }, 0, completed, 60, "untrusted-input",
                firstAttempt = true, pacingStable = true, pacedCompletion = completed)
            repository.saveRun(submitted)
            val saved = repository.library.first().runs.single()
            return Fixture(db, repository, plan, saved, generations, keyChecks, sourceChecks)
        } catch (failure: Throwable) { db.close(); throw failure }
    }

    private suspend fun snapshot(fixture: Fixture) = Snapshot(
        fixture.db.readingDao().studyRows(""), fixture.db.dao().allEvents(), fixture.db.dao().observeJobs().first(),
        fixture.db.dao().allArticles(), fixture.db.readingDao().bodyRow(article.id), fixture.repository.library.first(),
        fixture.generations.get(), fixture.keyChecks.get(), fixture.sourceChecks.get(),
    )

    @Test fun completedWrongAnswerReturnsTheRecordedChoicesAndExactCrossSentenceEvidenceWithoutWrites() = runBlocking {
        val f = fixture()
        try {
            assertFalse(f.saved.answers.first().correct)
            assertTrue(f.saved.answers.last().correct)
            assertTrue(f.plan.questions.first().evidenceStart > introduction.length)
            val before = snapshot(f)
            repeat(2) {
                val review = f.repository.comprehensionReview(f.saved)
                assertEquals(SpeedComprehensionReviewStatus.AVAILABLE, review.status)
                assertEquals(listOf("library-location", "library-hours"), review.items.map { item -> item.question.id })
                val first = review.items.first()
                assertEquals(0, first.answer.selectedIndex)
                assertFalse(first.answer.correct)
                assertEquals(1, first.question.answerIndex)
                assertEquals("A theatre", first.question.options[first.answer.selectedIndex])
                assertEquals("A shared library", first.question.options[first.question.answerIndex])
                assertEquals(firstEvidence, first.evidenceText)
                assertEquals(secondEvidence, review.items.last().evidenceText)
                assertEquals(f.plan.questions, review.items.map { item -> item.question })
                assertEquals(f.saved.answers, review.items.map { item -> item.answer })
                assertEquals(before, snapshot(f))
            }
            assertEquals(1, f.generations.get())
            assertTrue(f.db.readingDao().studyRows("speed-exposure:").isEmpty())
        } finally { f.db.close() }
    }

    @Test fun expiredAndDeletedArticleBodyDoesNotRemoveTheFrozenQuestionEvidence() = runBlocking {
        val f = fixture()
        try {
            assertNotNull(f.db.readingDao().bodyRow(article.id))
            f.db.dao().expireBodies(1_001)
            assertNull(f.db.readingDao().bodyRow(article.id))
            val before = snapshot(f)
            val review = f.repository.comprehensionReview(f.saved)
            assertEquals(SpeedComprehensionReviewStatus.AVAILABLE, review.status)
            assertEquals(firstEvidence, review.items.first().evidenceText)
            assertEquals(secondEvidence, review.items.last().evidenceText)
            assertEquals(before, snapshot(f))
        } finally { f.db.close() }
    }

    @Test fun aChangedCanonicalRunRejectsTheOldSnapshotAndDoesNotRewriteEitherRecord() = runBlocking {
        val f = fixture()
        try {
            val key = "speed-library:run:${f.saved.id}"
            val changed = f.saved.copy(pauses = f.saved.pauses + 1)
            // Deliberate isolated-fixture mutation represents a stale caller, never installed user data.
            f.db.readingDao().put(PreferenceRow(key, codec.encodeToString(changed)))
            val before = snapshot(f)
            val stale = f.repository.comprehensionReview(f.saved)
            assertEquals(SpeedComprehensionReviewStatus.UNAVAILABLE, stale.status)
            assertTrue(stale.items.isEmpty())
            assertEquals(before, snapshot(f))
            val current = f.repository.comprehensionReview(changed)
            assertEquals(SpeedComprehensionReviewStatus.AVAILABLE, current.status)
            assertEquals(firstEvidence, current.items.first().evidenceText)
            assertEquals(before, snapshot(f))
        } finally { f.db.close() }
    }

    @Test fun missingCanonicalRunDoesNotRecreateItOrRevealStoredSolutions() = runBlocking {
        val f = fixture()
        try {
            f.db.readingDao().delete("speed-library:run:${f.saved.id}")
            val before = snapshot(f)
            val review = f.repository.comprehensionReview(f.saved)
            assertEquals(SpeedComprehensionReviewStatus.UNAVAILABLE, review.status)
            assertTrue(review.items.isEmpty())
            assertEquals(before, snapshot(f))
        } finally { f.db.close() }
    }

    @Test fun malformedOrDigestMismatchedSourceRemainsUnavailableWithoutFallbackOrRepair() = runBlocking {
        for (malformed in listOf(false, true)) {
            val f = fixture()
            try {
                val key = "speed-plan:${f.plan.planKey}"
                val row = requireNotNull(f.db.readingDao().entry(key))
                val broken = if (malformed) "{synthetic-malformed-snapshot" else {
                    val original = codec.decodeFromString<SpeedPlanRecord>(row.data)
                    // Same-length edited text leaves the old digest in place, so validation must reject it.
                    codec.encodeToString(original.copy(source = original.source.mapIndexed { index, part ->
                        if (index == 0) part.copy(text = part.text.replace("council", "cabinet")) else part
                    }))
                }
                assertNotEquals(row.data, broken)
                f.db.readingDao().put(PreferenceRow(key, broken))
                assertNotNull("A valid article body must not be used to replace the damaged frozen evidence",
                    f.db.readingDao().bodyRow(article.id))
                val before = snapshot(f)
                val review = f.repository.comprehensionReview(f.saved)
                assertEquals(SpeedComprehensionReviewStatus.UNAVAILABLE, review.status)
                assertTrue(review.items.isEmpty())
                assertEquals(before, snapshot(f))
                assertEquals(broken, f.db.readingDao().entry(key)?.data)
            } finally { f.db.close() }
        }
    }

    @Test fun unfinishedOrPartiallyAnsweredAttemptsExposeNoReferenceAnswers() = runBlocking {
        for ((completed, answers) in listOf(false to 1, false to 2, true to 1)) {
            val f = fixture(completed = completed, answers = answers)
            try {
                val before = snapshot(f)
                val review = f.repository.comprehensionReview(f.saved)
                assertEquals(SpeedComprehensionReviewStatus.INCOMPLETE, review.status)
                assertTrue(review.items.isEmpty())
                assertEquals(before, snapshot(f))
            } finally { f.db.close() }
        }
    }

    @Test fun ruleOnlyPracticeDoesNotManufactureQuestionsOrTriggerTheGenerator() = runBlocking {
        val f = fixture(answers = 0, useAi = false)
        try {
            val before = snapshot(f)
            val review = f.repository.comprehensionReview(f.saved)
            assertEquals(SpeedComprehensionReviewStatus.NO_QUESTIONS, review.status)
            assertTrue(review.items.isEmpty())
            assertEquals(before, snapshot(f))
            assertEquals(0, f.generations.get())
            assertEquals(0, f.keyChecks.get())
            assertEquals(0, f.sourceChecks.get())
        } finally { f.db.close() }
    }
}
