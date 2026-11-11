package dev.lexicon.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SpeedRoundRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val text = "The council considers a public library. Residents compare the proposed sites.\n\nVolunteers explain the opening hours. The committee reviews the comments."
    private val article = Article("round-fixture", "g", listOf("s"), "A public library", "https://example.org/rounds", "", sourceName = "Self-authored fixture")
    private val body = ArticleBody(article.id, text, sha256(text), 0, 1, "Self-authored fixture")
    private val calls = AtomicInteger()
    private val codec = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val generator = SpeedPlanGenerator { _, source, plan ->
        calls.incrementAndGet()
        val paragraphs = ParagraphFocus.paragraphs(source.text)
        plan.copy(origin = "ai", model = "synthetic/no-network", questions = plan.paragraphIndices.map { index ->
            val paragraph = paragraphs[index]
            SpeedQuestion("p$index", index, "What is considered?", listOf("A library", "A flight", "A sporting event"),
                0, paragraph.start, paragraph.endExclusive, "Self-authored evidence.")
        })
    }
    private fun db() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).build()
    private fun repo(db: LexiconDatabase) = LocalSpeedReadingRepository(db, generator, hasKey = { true }, sourceAllowed = { true }, clock = { 1000 })
    private suspend fun preparePlan(repository: LocalSpeedReadingRepository, start: Int = 0, count: Int = 2) =
        repository.prepare(article, body, SpeedGranularity.L2, start, count, true, true)
    private fun run(plan: SpeedPlan, id: String, start: Long = 1000, round: SpeedPacedRound = SpeedPacedRound.GUIDED,
        parent: String? = null, completed: Boolean = true): SpeedRun {
        val dwell = List(plan.chunks.size) { 1000L }
        return SpeedRun(id, plan.articleId, plan.bodyHash, plan.granularity, plan.planKey, start, start + dwell.sum(), dwell.sum(),
            999, 999, dwell, 0, 0, plan.questions.map { SpeedAnswer(it.id, 0, false) }, 999, completed, 60, "untrusted",
            firstAttempt = true, pacingStable = true, pacedCompletion = completed, round = round, parentRunId = parent)
    }
    private suspend fun rounds(repository: LocalSpeedReadingRepository, plan: SpeedPlan): Pair<SpeedRun, SpeedRun> {
        val guided = run(plan, "guided")
        repository.saveRun(guided)
        val faded = run(plan, "faded", guided.finishedAt + 100, SpeedPacedRound.FADED, guided.id)
        repository.saveRun(faded)
        val stored = repository.library.first().runs.associateBy { it.id }
        return stored.getValue(guided.id) to stored.getValue(faded.id)
    }
    private fun natural(plan: SpeedPlan, parent: SpeedRun, id: String = "natural", completed: Boolean = true): NaturalReadingAttempt {
        val start = parent.finishedAt + 100
        val paragraphs = plan.paragraphIndices.map { NaturalParagraphTiming(it, 2000, completed) }
        return NaturalReadingAttempt(id, plan.articleId, plan.bodyHash, plan.planKey, parent.id, start,
            start + paragraphs.sumOf { it.activeMillis }, paragraphs, completed, 0, 0)
    }
    private suspend fun invalid(block: suspend () -> Unit) {
        try { block(); fail("Expected a rejected training record") } catch (_: IllegalArgumentException) { }
    }

    @Test fun threeRoundsReuseOnePlanKeepOnlyGuidedAnswersAndUseDistinctCompletionEvents() = runBlocking {
        val db = db()
        try {
            val repository = repo(db); val plan = preparePlan(repository)
            val (guided, faded) = rounds(repository, plan)
            val attempt = natural(plan, faded)
            repository.saveNaturalAttempt(attempt)
            val library = repo(db).library.first()
            assertEquals(2, library.runs.size)
            assertEquals(listOf(attempt), library.naturalAttempts)
            assertEquals(plan.questions.size, guided.questionCount); assertTrue(guided.firstAttempt)
            assertEquals(0, faded.questionCount); assertTrue(faded.answers.isEmpty()); assertFalse(faded.firstAttempt)
            assertEquals(guided.id, faded.parentRunId); assertEquals(faded.id, attempt.parentRunId)
            assertNull(SpeedMetrics.summarize(faded).comprehension)
            assertEquals(plan.questions.size, library.runs.sumOf { it.questionCount })
            assertEquals(plan, repository.cachedPlan(attempt.planKey))
            assertEquals(guided, library.runs.single { it.id == guided.id })
            assertEquals(1, calls.get())
            assertEquals(setOf("speed_training_completed", "speed_faded_completed", "natural_reading_completed"),
                db.dao().allEvents().map { codec.decodeFromString<LocalEvent>(it.data).type }.toSet())
            assertEquals(plan.chunks.sumOf { it.wordCount }, NaturalReadingMetrics.summarize(attempt, plan).wordsRead)
        } finally { db.close() }
    }

    @Test fun fadedRequiresACompletedGuidedParentWithTheSamePlanAndEarlierTime() = runBlocking {
        val db = db()
        try {
            val repository = repo(db); val plan = preparePlan(repository)
            val (guided, faded) = rounds(repository, plan)
            val incomplete = run(plan, "incomplete", completed = false)
            repository.saveRun(incomplete)
            val differentPlan = preparePlan(repository, count = 1)
            val different = run(differentPlan, "different")
            repository.saveRun(different)
            val valid = run(plan, "invalid-child", faded.finishedAt + 100, SpeedPacedRound.FADED, guided.id)
            listOf(valid.copy(parentRunId = null), valid.copy(parentRunId = "missing"),
                valid.copy(parentRunId = faded.id), valid.copy(parentRunId = incomplete.id), valid.copy(parentRunId = different.id),
                valid.copy(parentRunId = valid.id), valid.copy(startedAt = guided.finishedAt - 1),
                valid.copy(round = SpeedPacedRound.GUIDED)).forEach { incoming -> invalid { repository.saveRun(incoming) } }
            assertFalse(repository.library.first().runs.any { it.id == valid.id })
            invalid { repository.saveRun(faded.copy(round = SpeedPacedRound.GUIDED, parentRunId = null)) }
            assertEquals(faded, repository.library.first().runs.single { it.id == faded.id })
        } finally { db.close() }
    }

    @Test fun naturalRequiresACompletedFadedParentFromTheSameFrozenPlan() = runBlocking {
        val db = db()
        try {
            val repository = repo(db); val plan = preparePlan(repository)
            val (guided, faded) = rounds(repository, plan)
            val unfinished = run(plan, "unfinished-faded", faded.finishedAt + 100, SpeedPacedRound.FADED, guided.id, completed = false)
            repository.saveRun(unfinished)
            val valid = natural(plan, faded)
            listOf(valid.copy(parentRunId = "missing"), valid.copy(parentRunId = guided.id),
                valid.copy(parentRunId = unfinished.id), valid.copy(parentRunId = valid.id),
                valid.copy(startedAt = faded.finishedAt - 1)).forEach { incoming -> invalid { repository.saveNaturalAttempt(incoming) } }
            val otherPlan = preparePlan(repository, count = 1)
            val wrongPlan = valid.copy(planKey = otherPlan.planKey, paragraphs = valid.paragraphs.take(1))
            invalid { repository.saveNaturalAttempt(wrongPlan) }
            assertTrue(repository.library.first().naturalAttempts.isEmpty())
        } finally { db.close() }
    }

    @Test fun naturalPartialCheckpointsAccumulateAndCompletedRecordCannotBeRewritten() = runBlocking {
        val db = db()
        try {
            val repository = repo(db); val plan = preparePlan(repository); val (_, faded) = rounds(repository, plan)
            val full = natural(plan, faded)
            val partial = full.copy(finishedAt = full.startedAt + 1000, completed = false,
                paragraphs = listOf(NaturalParagraphTiming(0, 1000, false), NaturalParagraphTiming(1, 0, false)))
            repository.saveNaturalAttempt(partial)
            val checkpoint = partial.copy(finishedAt = partial.finishedAt + 10_000, pauses = 1,
                paragraphs = listOf(NaturalParagraphTiming(0, 1200, true), NaturalParagraphTiming(1, 0, false)))
            repo(db).saveNaturalAttempt(checkpoint)
            assertEquals(1200L, NaturalReadingMetrics.summarize(checkpoint, plan).activeMillis)
            assertNull(NaturalReadingMetrics.summarize(checkpoint, plan).wordsPerMinute)
            repository.saveNaturalAttempt(partial) // Older delivery cannot roll back progress.
            assertEquals(checkpoint, repository.library.first().naturalAttempts.single())
            val completed = full.copy(finishedAt = checkpoint.finishedAt + 4000, pauses = 1)
            repository.saveNaturalAttempt(completed)
            repository.saveNaturalAttempt(completed.copy(finishedAt = completed.finishedAt + 1000,
                paragraphs = completed.paragraphs.map { it.copy(activeMillis = it.activeMillis + 500) }))
            assertEquals(completed, repository.library.first().naturalAttempts.single())
            assertEquals(1, db.dao().allEvents().count { codec.decodeFromString<LocalEvent>(it.data).type == "natural_reading_completed" })
        } finally { db.close() }
    }

    @Test fun naturalCannotRegressTimeCompletionOrCountersAndRejectsInvalidParagraphAccounting() = runBlocking {
        val db = db()
        try {
            val repository = repo(db); val plan = preparePlan(repository); val (_, faded) = rounds(repository, plan)
            val full = natural(plan, faded)
            val partial = full.copy(completed = false, pauses = 1, revisits = 1,
                paragraphs = listOf(NaturalParagraphTiming(0, 2000, true), NaturalParagraphTiming(1, 0, false)))
            repository.saveNaturalAttempt(partial)
            val later = partial.copy(finishedAt = partial.finishedAt + 1000)
            listOf(later.copy(pauses = 0), later.copy(revisits = 0),
                later.copy(paragraphs = listOf(NaturalParagraphTiming(0, 1999, true), NaturalParagraphTiming(1, 0, false))),
                later.copy(paragraphs = listOf(NaturalParagraphTiming(0, 2000, false), NaturalParagraphTiming(1, 0, false)))
            ).forEach { incoming -> invalid { repository.saveNaturalAttempt(incoming) } }
            val malformed = listOf(full.copy(id = "bad", paragraphs = full.paragraphs.reversed()),
                full.copy(id = "bad", paragraphs = full.paragraphs.take(1)),
                full.copy(id = "bad", paragraphs = List(2) { full.paragraphs.first() }),
                full.copy(id = "bad", paragraphs = full.paragraphs.map { it.copy(activeMillis = 0) }),
                full.copy(id = "bad", paragraphs = full.paragraphs.map { it.copy(activeMillis = -1) }),
                full.copy(id = "bad", finishedAt = full.startedAt + 10),
                full.copy(id = "bad", paragraphs = full.paragraphs.map { it.copy(activeMillis = NaturalReadingMetrics.MAX_ACTIVE_MILLIS) },
                    finishedAt = full.startedAt + NaturalReadingMetrics.MAX_ACTIVE_MILLIS * 2))
            malformed.forEach { incoming -> invalid { repository.saveNaturalAttempt(incoming) } }
            assertEquals(partial, repository.library.first().naturalAttempts.single())
        } finally { db.close() }
    }

    @Test fun legacyGuidedRecordsKeepTheirBytesAndCanParentAFadedRound() = runBlocking {
        val db = db()
        try {
            val repository = repo(db); val plan = preparePlan(repository)
            val original = run(plan, "legacy"); repository.saveRun(original)
            val key = "speed-library:run:${original.id}"
            val stored = requireNotNull(db.readingDao().entry(key))
            val legacy = JsonObject(codec.parseToJsonElement(stored.data).jsonObject - setOf("round", "parentRunId")).toString()
            db.readingDao().put(PreferenceRow(key, legacy))
            val decoded = repo(db).library.first().runs.single()
            assertEquals(SpeedPacedRound.GUIDED, decoded.round); assertNull(decoded.parentRunId)
            repository.saveRun(original)
            assertEquals(legacy, db.readingDao().entry(key)?.data)
            repository.saveRun(run(plan, "faded-legacy", original.finishedAt + 1, SpeedPacedRound.FADED, original.id))
            assertEquals(legacy, db.readingDao().entry(key)?.data)
            assertEquals(0, repository.library.first().runs.single { it.id == "faded-legacy" }.questionCount)
        } finally { db.close() }
    }

    @Test fun fadedPartialCanFinishWithoutAddingQuestionsAndCompletedWritesAreIdempotent() = runBlocking {
        val db = db()
        try {
            val repository = repo(db); val plan = preparePlan(repository)
            val guided = run(plan, "guided"); repository.saveRun(guided)
            val partial = run(plan, "faded", guided.finishedAt + 100, SpeedPacedRound.FADED, guided.id, completed = false)
            repository.saveRun(partial)
            val storedPartial = repository.library.first().runs.single { it.id == partial.id }
            assertTrue(storedPartial.answers.isEmpty()); assertEquals(0, storedPartial.questionCount)
            repository.saveRun(partial.copy(completed = true, pacedCompletion = true))
            val stored = repository.library.first().runs.single { it.id == partial.id }
            repository.saveRun(partial.copy(completed = true, pacedCompletion = false))
            assertEquals(stored, repository.library.first().runs.single { it.id == partial.id })
            assertFalse(stored.firstAttempt); assertEquals(true, stored.pacedCompletion)
            assertEquals(plan.questions.size, repository.library.first().runs.sumOf { it.questionCount })
            assertEquals(1, db.dao().allEvents().count { codec.decodeFromString<LocalEvent>(it.data).type == "speed_faded_completed" })
        } finally { db.close() }
    }

    @Test fun concurrentNaturalCompletionKeepsOneImmutableRecordAndOneEvent() = runBlocking {
        val db = db()
        try {
            val first = repo(db); val second = repo(db); val plan = preparePlan(first); val (_, faded) = rounds(first, plan)
            val one = natural(plan, faded)
            val two = one.copy(finishedAt = one.finishedAt + 1000,
                paragraphs = one.paragraphs.map { it.copy(activeMillis = it.activeMillis + 500) })
            coroutineScope { listOf(async { first.saveNaturalAttempt(one) }, async { second.saveNaturalAttempt(two) }).awaitAll() }
            assertTrue(first.library.first().naturalAttempts.single() in listOf(one, two))
            assertEquals(1, db.dao().allEvents().count { codec.decodeFromString<LocalEvent>(it.data).type == "natural_reading_completed" })
        } finally { db.close() }
    }
}
