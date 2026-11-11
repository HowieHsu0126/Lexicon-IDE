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
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeedRepositoryIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val text = "The report considers local needs. It proposes a public library.\n\nThe council reviews the proposal. Members compare costs and benefits."
    private val article = Article("speed-local", "g", listOf("s"), "A local proposal", "https://example.org/local", "", sourceName = "Synthetic")
    private val body = ArticleBody(article.id, text, sha256(text), 0, 1_000_000, "Self-authored")
    private fun db() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
    private class Generator(val create: suspend (ArticleBody, SpeedPlan) -> SpeedPlan) : SpeedPlanGenerator {
        var calls = 0
        override suspend fun refine(article: Article, body: ArticleBody, proposal: SpeedPlan): SpeedPlan { calls++; return create(body, proposal) }
    }
    private fun validAi(body: ArticleBody, plan: SpeedPlan) = plan.copy(origin = "ai", model = "test", questions = plan.paragraphIndices.map { i ->
        val p = ParagraphFocus.paragraphs(body.text)[i]
        SpeedQuestion("p$i", i, "What is discussed?", listOf("A local proposal", "A sporting event", "A flight"), 0, p.start, p.endExclusive, "原段讨论地方提议。")
    })
    private fun repo(db: LexiconDatabase, ai: Generator, key: suspend () -> Boolean = { true }, allow: suspend (Article) -> Boolean = { true }) =
        LocalSpeedReadingRepository(db, ai, key, allow, clock = { 1000 })
    private suspend fun plan(repo: LocalSpeedReadingRepository, ai: Boolean = false, start: Int = 0, count: Int = 1, level: SpeedGranularity = SpeedGranularity.L2) =
        repo.prepare(article, body, level, start, count, true, ai)
    private fun run(plan: SpeedPlan, id: String = "r1", completed: Boolean = true, answer: Int = 0): SpeedRun {
        val times = plan.chunks.map { 1000L }
        return SpeedRun(id, article.id, body.contentHash, plan.granularity, plan.planKey, 1000,
            1000 + times.sum(), times.sum(), 9999, 9999, times, 0, 0,
            plan.questions.map { SpeedAnswer(it.id, answer, answer != it.answerIndex) }, 999, completed, 60, "forged")
    }

    @Test fun rulesWorkWithoutKeyAndAreRestorableButNeverGainInventedQuestions() = runBlocking {
        val db = db(); val ai = Generator { _, _ -> error("No AI expected") }
        try {
            val repo = repo(db, ai, key = { false })
            val p = plan(repo)
            assertTrue(p.questions.isEmpty()); assertEquals("rules", p.origin); assertEquals(0, ai.calls)
            assertEquals(p, repo.cachedPlan(p.planKey))
            assertEquals(p, plan(repo))
            assertNull(repo.cachedPlan("invalid-key"))
        } finally { db.close() }
    }

    @Test fun sourceKeyAndPauseChecksPrecedeAiCacheAndTransport() = runBlocking {
        val db = db(); val ai = Generator(::validAi)
        try {
            val allowed = repo(db, ai)
            plan(allowed, ai = true); assertEquals(1, ai.calls)
            for ((repository, expected) in listOf(repo(db, ai, key = { false }) to "missing_key", repo(db, ai, allow = { false }) to "source_restricted")) {
                try { plan(repository, ai = true); fail("Expected policy check") } catch (e: ApiFailure) { assertEquals(expected, e.code) }
            }
            db.readingDao().put(PreferenceRow("ai_paused", "true"))
            try { plan(allowed, ai = true); fail("Expected pause") } catch (e: ApiFailure) { assertEquals("paused", e.code) }
            assertEquals(1, ai.calls)
            assertEquals("rules", plan(allowed).origin)
        } finally { db.close() }
    }

    @Test fun cacheSeparatesGranularityParagraphSelectionAndRulesFromAi() = runBlocking {
        val db = db(); val ai = Generator(::validAi)
        try {
            val repo = repo(db, ai)
            val values = listOf(plan(repo), plan(repo, ai = true), plan(repo, level = SpeedGranularity.L1), plan(repo, start = 1), plan(repo, count = 2))
            assertEquals(values.size, values.map { it.planKey }.distinct().size)
            assertEquals(values[1], plan(repo, ai = true)); assertEquals(1, ai.calls)
            assertNull(repo(db, ai).cachedPlan("a".repeat(64)))
        } finally { db.close() }
    }

    @Test fun interruptedGenerationNeedsExplicitRetryAndKeepsTheOriginalJob() = runBlocking {
        val db = db(); val entered = CompletableDeferred<Unit>(); var pause = true
        val ai = Generator { body, proposal -> if (pause) { entered.complete(Unit); awaitCancellation() }; validAi(body, proposal) }
        try {
            val repo = repo(db, ai)
            val pending = async { plan(repo, ai = true) }
            withTimeout(3000) { entered.await() }; pending.cancelAndJoin()
            assertEquals(1, ai.calls)
            try { plan(repo, ai = true); fail("Explicit retry required") } catch (e: ApiFailure) { assertEquals("retry_required", e.code) }
            pause = false
            val recovered = repo.retryPrepare(article, body, SpeedGranularity.L2, 0, 1, true, true)
            assertEquals("ai", recovered.origin); assertEquals(2, ai.calls)
            assertEquals(recovered, plan(repo, ai = true))
        } finally { db.close() }
    }

    @Test fun authenticFailuresPauseButCannotCacheAnUnvalidatedPlan() = runBlocking {
        val db = db(); val ai = Generator { _, _ -> throw ApiFailure("authentication", "Synthetic invalid key") }
        try {
            val repo = repo(db, ai)
            try { plan(repo, ai = true); fail("Expected authentication") } catch (e: ApiFailure) { assertEquals("authentication", e.code) }
            assertEquals("true", db.readingDao().entry("ai_paused")?.data)
            assertEquals(1, ai.calls)
            try { plan(repo, ai = true); fail("Expected pause") } catch (e: ApiFailure) { assertEquals("paused", e.code) }
        } finally { db.close() }
    }

    @Test fun persistenceDerivesAnswersAndWordCountsAndMakesCompletedRunIdempotent() = runBlocking {
        val db = db(); val ai = Generator(::validAi)
        try {
            val repo = repo(db, ai); val p = plan(repo, ai = true)
            repo.saveRun(run(p))
            var library = repo.library.first(); val saved = library.runs.single()
            assertEquals(p.chunks.sumOf { it.wordCount }, saved.wordsRead)
            assertEquals(p.chunks.size, saved.groupsRead); assertEquals(1, saved.questionCount)
            assertTrue(saved.answers.single().correct); assertEquals("ai", saved.planOrigin); assertTrue(saved.firstAttempt)
            repo.saveRun(run(p, answer = 1))
            library = repo.library.first(); assertEquals(1, library.runs.size); assertEquals(saved, library.runs.single())
            assertEquals(1.0, SpeedMetrics.summarize(saved).comprehension!!, .0)
            assertEquals(1, db.dao().allEvents().size)
        } finally { db.close() }
    }

    @Test fun partialRunCanFinishButCannotRewriteAnAlreadySubmittedAnswer() = runBlocking {
        val db = db(); val ai = Generator(::validAi)
        try {
            val repo = repo(db, ai); val p = plan(repo, ai = true)
            val partial = run(p, completed = false).copy(answers = emptyList())
            repo.saveRun(partial); repo.saveRun(run(p))
            assertTrue(repo.library.first().runs.single().completed)
            assertEquals(1, repo.library.first().runs.size)
            assertEquals(1, db.dao().allEvents().size)
        } finally { db.close() }
    }

    @Test fun partialSnapshotsNeverUpgradeChangedOrUnknownPacingToStable() = runBlocking {
        val db = db(); val ai = Generator(::validAi)
        try {
            val repo = repo(db, ai); val p = plan(repo, ai = true)
            val changed = run(p, "changed-pacing", completed = false).copy(pacingStable = true)
            repo.saveRun(changed)
            repo.saveRun(changed.copy(pacingStable = false))
            repo.saveRun(changed.copy(completed = true, pacingStable = true))
            val unknown = run(p, "unknown-pacing", completed = false).copy(pacingStable = null)
            repo.saveRun(unknown)
            repo.saveRun(unknown.copy(completed = true, pacingStable = true))
            val rows = repo.library.first().runs.associateBy { it.id }
            assertEquals(false, rows.getValue("changed-pacing").pacingStable)
            assertNull(rows.getValue("unknown-pacing").pacingStable)
            assertTrue(rows.values.all { it.completed })
        } finally { db.close() }
    }

    @Test fun pacedCompletionAllowsKnownPartialToFinishButPreservesLegacyAndCompletedFailures() = runBlocking {
        val db = db(); val ai = Generator(::validAi)
        try {
            val repo = repo(db, ai); val p = plan(repo, ai = true)
            val known = run(p, "known-pacing", completed = false).copy(pacedCompletion = false)
            repo.saveRun(known); repo.saveRun(known.copy(completed = true, pacedCompletion = true))
            val legacy = run(p, "legacy-pacing", completed = false).copy(pacedCompletion = null)
            repo.saveRun(legacy); repo.saveRun(legacy.copy(completed = true, pacedCompletion = true))
            val failed = run(p, "failed-pacing", completed = true).copy(pacedCompletion = false)
            repo.saveRun(failed); repo.saveRun(failed.copy(pacedCompletion = true))
            try { repo.saveRun(known.copy(id = "invalid-partial", pacedCompletion = true)); fail("Partial cannot be paced completion") }
            catch (_: IllegalArgumentException) { }
            val rows = repo.library.first().runs.associateBy { it.id }
            assertEquals(true, rows.getValue("known-pacing").pacedCompletion)
            assertNull(rows.getValue("legacy-pacing").pacedCompletion)
            assertEquals(false, rows.getValue("failed-pacing").pacedCompletion)
        } finally { db.close() }
    }

    @Test fun sameBodyImportedUnderAnotherArticleIdDoesNotRegainFirstAttempt() = runBlocking {
        val db = db(); val ai = Generator(::validAi)
        try {
            val repo = repo(db, ai); val first = plan(repo, ai = true)
            repo.saveRun(run(first, "original-source"))
            val alias = article.copy(id = "same-text-other-source")
            val aliasBody = body.copy(articleId = alias.id)
            val repeated = repo.prepare(alias, aliasBody, SpeedGranularity.L2, 0, 1, true, true)
            repo.saveRun(run(repeated, "syndicated-source").copy(articleId = alias.id))
            assertFalse(repo.library.first().runs.single { it.id == "syndicated-source" }.firstAttempt)
        } finally { db.close() }
    }

    @Test fun repeatMaterialIsNotAFirstAttemptAcrossGranularitiesButNewParagraphIs() = runBlocking {
        val db = db(); val ai = Generator(::validAi)
        try {
            val repo = repo(db, ai); val original = plan(repo, ai = true)
            repo.saveRun(run(original, "first"))
            val repeated = plan(repo, ai = true, level = SpeedGranularity.L1)
            repo.saveRun(run(repeated, "repeat").copy(firstAttempt = true))
            val fresh = plan(repo, ai = true, start = 1)
            repo.saveRun(run(fresh, "fresh"))
            val rows = repo.library.first().runs.associateBy { it.id }
            assertFalse(rows.getValue("repeat").firstAttempt)
            assertNull(SpeedMetrics.summarize(rows.getValue("repeat")).comprehension)
            assertTrue(rows.getValue("fresh").firstAttempt)
        } finally { db.close() }
    }

    @Test fun correctionRequiresExistingExactPlanAndDoesNotRewriteOnDuplicateId() = runBlocking {
        val db = db(); val ai = Generator(::validAi)
        try {
            val repo = repo(db, ai)
            val longerText = "Readers visit the library before work; staff prepare the reading room for new visitors; volunteers collect comments about opening hours and discuss possible changes with the council."
            val longerBody = body.copy(text = longerText, contentHash = sha256(longerText))
            val p = repo.prepare(article, longerBody, SpeedGranularity.L2, 0, 1, true, false)
            val c = p.chunks.zipWithNext().first { (a, b) -> a.sentence == b.sentence }.first
            val correction = SpeedCorrection("c1", article.id, longerBody.contentHash, p.granularity, c.start, c.end, "merge_next", "这里可以合并", 1000, p.planKey)
            repo.saveCorrection(correction); repo.saveCorrection(correction.copy(comment = "不能覆盖"))
            assertEquals(correction, repo.library.first().corrections.single())
            try { repo.saveCorrection(correction.copy(id = "wrong", end = c.end - 1)); fail("Exact range required") } catch (_: IllegalArgumentException) { }
            try { repo.saveRun(run(p).copy(planKey = "0".repeat(64))); fail("Known plan required") } catch (e: ApiFailure) { assertEquals("missing_plan", e.code) }
        } finally { db.close() }
    }

    @Test fun anAnswerRevealedInAPartialRunDisqualifiesOnlyItsParagraphFromFirstAttempt() = runBlocking {
        val db = db(); val ai = Generator(::validAi)
        try {
            val repo = repo(db, ai); val both = plan(repo, ai = true, count = 2)
            // A user can reach a question with manual next even without measured playback time.
            val exposed = run(both, "partial-exposure", completed = false).copy(
                finishedAt = 1000, activeMillis = 0, groupMillis = List(both.chunks.size) { 0 },
                answers = listOf(SpeedAnswer(both.questions.first().id, 0, false)),
            )
            repo.saveRun(exposed)
            val repeated = plan(repo, ai = true, level = SpeedGranularity.L1)
            repo.saveRun(run(repeated, "revealed-again"))
            val fresh = plan(repo, ai = true, start = 1)
            repo.saveRun(run(fresh, "unrevealed"))
            val rows = repo.library.first().runs.associateBy { it.id }
            assertFalse(rows.getValue("revealed-again").firstAttempt)
            assertNull(SpeedMetrics.summarize(rows.getValue("revealed-again")).comprehension)
            assertTrue(rows.getValue("unrevealed").firstAttempt)
        } finally { db.close() }
    }

    @Test fun mergeCorrectionCannotJoinAcrossSentenceOrParagraphBoundaries() = runBlocking {
        val db = db(); val ai = Generator(::validAi)
        try {
            val repo = repo(db, ai); val p = plan(repo, count = 2)
            val sentenceEnd = p.chunks.zipWithNext().first { (a, b) -> a.sentence != b.sentence }.first
            val last = p.chunks.last()
            listOf(sentenceEnd, last).forEachIndexed { index, c ->
                val invalid = SpeedCorrection("merge-$index", article.id, body.contentHash, p.granularity,
                    c.start, c.end, "merge_next", "", 1000, p.planKey)
                try { repo.saveCorrection(invalid); fail("A same-sentence following chunk is required") } catch (_: IllegalArgumentException) { }
            }
            assertTrue(repo.library.first().corrections.isEmpty())
            val valid = SpeedCorrection("long-last", article.id, body.contentHash, p.granularity,
                last.start, last.end, "too_long", "句末仍可反馈过长", 1000, p.planKey)
            repo.saveCorrection(valid)
            assertEquals(valid, repo.library.first().corrections.single())
        } finally { db.close() }
    }

    @Test fun corruptedStoredSourceOrPlanIsNotRestored() = runBlocking {
        val db = db(); val ai = Generator(::validAi)
        try {
            val repo = repo(db, ai); val p = plan(repo)
            val key = "speed-plan:${p.planKey}"
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val record = json.decodeFromString<SpeedPlanRecord>(db.readingDao().entry(key)!!.data)
            val changed = record.copy(source = record.source.map { it.copy(text = it.text.replace("report", "editor")) })
            db.readingDao().put(PreferenceRow(key, json.encodeToString(changed)))
            assertNull(repo.cachedPlan(p.planKey))
        } finally { db.close() }
    }
}
