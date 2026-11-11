package dev.lexicon.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeedInteractionRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val article = Article("interactions-fixture", "g", listOf("fixture"), "Community reading",
        "https://example.org/interactions", "Fixture", sourceName = "Self-authored test", readingMode = ReadingMode.NATIVE)
    private val text = "Local people discuss a public library. The committee compares the available sites. Residents describe their changing needs."
    private val body = ArticleBody(article.id, text, sha256(text), 0, Long.MAX_VALUE, "Self-authored test")
    private data class Fixture(val db: LexiconDatabase, val repository: LocalSpeedReadingRepository, val plan: SpeedPlan)
    private suspend fun fixture(): Fixture {
        val db = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO).build()
        val repository = LocalSpeedReadingRepository(db,
            SpeedPlanGenerator { _, _, _ -> error("This fixture must remain offline") },
            hasKey = { false }, sourceAllowed = { true }, clock = { 100_000 })
        val plan = repository.prepare(article, body, SpeedGranularity.L2, 0, 1, true, false)
        return Fixture(db, repository, plan)
    }
    private fun run(plan: SpeedPlan, id: String = "interactions") = SpeedRun(id, article.id, body.contentHash,
        plan.granularity, plan.planKey, 1000, 7000, 3000, 0, 0,
        List(plan.chunks.size) { when (it) { 0 -> 2000L; 1 -> 1000L; else -> 0L } },
        5, 3, emptyList(), 0, false, 60, plan.origin, pacingStable = true, pacedCompletion = false,
        chunkInteractions = List(plan.chunks.size) { when (it) {
            0 -> SpeedChunkInteraction(2, 2, 1)
            1 -> SpeedChunkInteraction(1, 0, 0)
            else -> SpeedChunkInteraction(0, 0, 0)
        } })
    private suspend fun stored(f: Fixture, id: String = "interactions") = f.repository.library.first().runs.single { it.id == id }
    private suspend fun invalid(block: suspend () -> Unit) {
        try { block(); fail("Expected rejected interaction trace") } catch (_: IllegalArgumentException) { }
    }

    @Test fun explicitInteractionsRoundTripAndDoNotConvertSeekTotalsToVisits() = runBlocking {
        val f = fixture()
        try {
            val input = run(f.plan)
            f.repository.saveRun(input)
            val saved = stored(f)
            assertEquals(input.chunkInteractions, saved.chunkInteractions)
            assertEquals(5, saved.revisits) // existing aggregate counts seeks, independently of played revisits
            assertEquals(1, saved.chunkInteractions!!.sumOf { it.revisits })
            val summary = SpeedInteractionRecap.summarize(saved, f.plan)
            assertFalse(SpeedInteractionLimit.INVALID_TRACE in summary.limitations)
            assertEquals(0, summary.items.single().index)
            assertEquals(2, summary.items.single().visits)
        } finally { f.db.close() }
    }

    @Test fun invalidTraceShapeCountsAndPlaybackClaimsNeverReachStorage() = runBlocking {
        val f = fixture()
        try {
            val input = run(f.plan)
            fun withFirst(value: SpeedChunkInteraction) = input.copy(chunkInteractions = input.chunkInteractions!!.mapIndexed { index, old -> if (index == 0) value else old })
            val candidates = listOf(
                input.copy(chunkInteractions = emptyList()),
                withFirst(SpeedChunkInteraction(0, 0, 0)),
                withFirst(SpeedChunkInteraction(2, 2, 0)),
                withFirst(SpeedChunkInteraction(1, -1, 0)),
                withFirst(SpeedChunkInteraction(10_001, 2, 10_000)),
                withFirst(SpeedChunkInteraction(2, 4, 1)),
                input.copy(chunkInteractions = input.chunkInteractions!!.mapIndexed { index, old -> if (index == 2) SpeedChunkInteraction(0, 1, 0) else old }),
            )
            for (candidate in candidates) invalid { f.repository.saveRun(candidate) }
            assertTrue(f.repository.library.first().runs.isEmpty())
        } finally { f.db.close() }
    }

    @Test fun legacyUnknownCannotGainInventedVisitsOnCheckpointRestore() = runBlocking {
        val f = fixture()
        try {
            val input = run(f.plan)
            f.repository.saveRun(input.copy(chunkInteractions = null))
            f.repository.saveRun(input.copy(finishedAt = 8000))
            assertNull(stored(f).chunkInteractions)
            assertEquals(input.groupMillis, stored(f).groupMillis)
            assertTrue(SpeedInteractionLimit.MISSING_VISIT_DATA in SpeedInteractionRecap.summarize(stored(f), f.plan).limitations)
        } finally { f.db.close() }
    }

    @Test fun knownPartialInteractionsAccumulateAndCannotBeRemovedOrDecreased() = runBlocking {
        val f = fixture()
        try {
            val input = run(f.plan)
            f.repository.saveRun(input)
            val before = stored(f)
            invalid { f.repository.saveRun(input.copy(finishedAt = 8000, chunkInteractions = null)) }
            invalid { f.repository.saveRun(input.copy(finishedAt = 8000, chunkInteractions = input.chunkInteractions!!.mapIndexed { index, old ->
                if (index == 0) SpeedChunkInteraction(1, 1, 0) else old
            })) }
            assertEquals(before, stored(f))
            val next = input.copy(finishedAt = 9000, activeMillis = 4000, pauses = 4,
                groupMillis = input.groupMillis.mapIndexed { index, value -> if (index == 0) value + 1000 else value },
                chunkInteractions = input.chunkInteractions!!.mapIndexed { index, old -> if (index == 0) SpeedChunkInteraction(3, 3, 2) else old })
            f.repository.saveRun(next)
            assertEquals(next.chunkInteractions, stored(f).chunkInteractions)
            assertEquals(4000L, stored(f).activeMillis)
        } finally { f.db.close() }
    }

    @Test fun completedTraceRemainsImmutableAcrossLaterReplays() = runBlocking {
        val f = fixture()
        try {
            val input = run(f.plan).copy(finishedAt = 100_000, activeMillis = f.plan.chunks.size * 1000L,
                groupMillis = List(f.plan.chunks.size) { 1000L }, completed = true, pacedCompletion = true,
                chunkInteractions = List(f.plan.chunks.size) { SpeedChunkInteraction(1, 0, 0) })
            f.repository.saveRun(input)
            val completed = stored(f)
            val events = f.db.dao().allEvents()
            f.repository.saveRun(input.copy(finishedAt = 101_000, pauses = 4,
                chunkInteractions = input.chunkInteractions!!.mapIndexed { index, old -> if (index == 0) SpeedChunkInteraction(2, 1, 1) else old }))
            assertEquals(completed, stored(f)); assertEquals(events, f.db.dao().allEvents())
        } finally { f.db.close() }
    }

    @Test fun startedVisitWithoutElapsedTimeIsPreservedWithoutInventingReadWords() = runBlocking {
        val f = fixture()
        try {
            val input = run(f.plan).copy(activeMillis = 0, groupMillis = List(f.plan.chunks.size) { 0L }, pauses = 0,
                chunkInteractions = List(f.plan.chunks.size) { SpeedChunkInteraction(if (it == 0) 1 else 0, 0, 0) })
            f.repository.saveRun(input)
            val saved = stored(f)
            assertEquals(input.chunkInteractions, saved.chunkInteractions)
            assertEquals(0, saved.wordsRead); assertEquals(0, saved.groupsRead)
            assertTrue(SpeedInteractionRecap.summarize(saved, f.plan).items.isEmpty())
        } finally { f.db.close() }
    }
}
