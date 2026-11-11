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
import java.time.Instant

/** Real Room persistence with synthetic evidence and a fake AI; no HTTP or user credentials. */
@RunWith(AndroidJUnit4::class)
class BackgroundCacheIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = Instant.parse("2026-11-01T08:00:00Z").toEpochMilli()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val request = StudyRequest("background", "city council", "A city council considers local services.", title = "Synthetic reading", source = "Synthetic source")
    private fun database() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).build()
    private fun repository(db: LexiconDatabase, ai: ScriptedAi, key: suspend () -> Boolean = { true }, clock: () -> Long = { now }) =
        LocalReadingRepository(db, ai, key, { true }, clock)
    private fun cacheKey(value: StudyRequest = request) = "reading-cache:background:3:${sha256(json.encodeToString(value))}"
    private fun legacyKey(value: StudyRequest = request) = "reading-cache:assist:1:${sha256(json.encodeToString(value))}"
    private fun source(id: String = "S1", retrievedAt: Long = now) = BackgroundSource(
        id, "City council", "https://en.wikipedia.org/w/index.php?curid=${if (id == "S1") 10 else 20}&oldid=100",
        "A city council considers local services in this synthetic evidence excerpt. The description is a test fixture and not a quotation from Wikipedia.",
        retrievedAt, "Wikipedia (en)", "100",
    )
    private fun retrieved(sources: List<BackgroundSource> = listOf(source()), model: String = "test-model", createdAt: Long = now) =
        StudyAssist("background", "背景解释", "市议会参与地方事务。[S1]", model, createdAt,
            "根据客户端检索的合成证据整理。", sources = sources, retrievalStatus = "retrieved", citedSourceIds = listOf("S1"))

    @Test fun oldUngroundedCacheIsIgnoredAndNewRequestCacheRestoresSources() = runBlocking {
        val db = database(); val ai = ScriptedAi { retrieved() }
        try {
            val legacyJson = """{"kind":"background","title":"旧背景","text":"旧的无检索解释","model":"old-model","createdAt":1,"evidence":"未检索外部资料","score":null}"""
            val old = json.decodeFromString<StudyAssist>(legacyJson)
            assertTrue(old.sources.isEmpty()); assertEquals("not_requested", old.retrievalStatus)
            assertTrue(old.citedSourceIds.isEmpty())
            db.readingDao().put(PreferenceRow(legacyKey(), legacyJson))
            val repo = repository(db, ai)
            assertNull(repo.cachedAssist(request))
            val fresh = repo.assist(request)
            assertEquals(1, ai.calls)
            assertEquals("retrieved", fresh.retrievalStatus)
            assertEquals(listOf(source()), fresh.sources)
            assertNotNull(db.readingDao().entry(cacheKey()))
            assertEquals(fresh, repo.cachedAssist(request))
            assertEquals(fresh, repo.assist(request))
            assertEquals(1, ai.calls)
            // The full request, including context, is part of the cache address.
            assertNull(repo.cachedAssist(request.copy(context = "A different sentence.")))
            assertNull(repo.cachedAssist(request.copy(text = "public library")))
            assertNotNull(db.readingDao().entry(legacyKey()))
        } finally { db.close() }
    }

    @Test fun previousBroadRetrievalPolicyDoesNotBypassContextFiltering() = runBlocking {
        val db = database(); val ai = ScriptedAi { retrieved() }
        try {
            val oldKey = "reading-cache:background:2:${sha256(json.encodeToString(request))}"
            db.readingDao().put(PreferenceRow(oldKey, json.encodeToString(retrieved().copy(text = "旧策略包含无关城市案例。[S1]"))))
            val repo = repository(db, ai)
            assertNull(repo.cachedAssist(request))
            assertEquals("市议会参与地方事务。[S1]", repo.assist(request).text)
            assertEquals(1, ai.calls); assertNotNull(db.readingDao().entry(oldKey)); assertNotNull(db.readingDao().entry(cacheKey()))
        } finally { db.close() }
    }

    @Test fun oldestEvidenceTimestampControlsExpiryEvenWhenResultWasCreatedRecently() = runBlocking {
        val db = database(); val ai = ScriptedAi { retrieved() }
        try {
            val stale = retrieved(listOf(source("S1", now - 8L * DAY), source("S2", now)), createdAt = now)
            db.readingDao().put(PreferenceRow(cacheKey(), json.encodeToString(stale)))
            val repo = repository(db, ai)
            assertNull(repo.cachedAssist(request))
            val refreshed = repo.assist(request)
            assertEquals(1, ai.calls); assertEquals(now, refreshed.sources.single().retrievedAt)
            assertEquals(refreshed, repo.cachedAssist(request))
        } finally { db.close() }
    }

    @Test fun freshEvidenceCacheWorksWithoutKeyOrNetworkAfterRepositoryRecreation() = runBlocking {
        val db = database(); val online = ScriptedAi { retrieved(listOf(source(retrievedAt = now - 6L * DAY))) }
        try {
            val saved = repository(db, online).assist(request)
            val offline = ScriptedAi { throw ApiFailure("network", "Synthetic offline failure must not be reached") }
            val reopened = repository(db, offline, key = { false })
            assertEquals(saved, reopened.cachedAssist(request))
            assertEquals(saved, reopened.assist(request))
            assertEquals(0, offline.calls)
            assertEquals(saved.sources, json.decodeFromString<StudyAssist>(db.readingDao().entry(cacheKey())!!.data).sources)
        } finally { db.close() }
    }

    @Test fun expiredCacheDoesNotBecomeFreshWhenReadAndRequiresAnExplicitNewAssist() = runBlocking {
        val db = database(); var time = now
        val ai = ScriptedAi { retrieved(listOf(source(retrievedAt = time)), createdAt = time) }
        try {
            val repo = repository(db, ai, clock = { time })
            repo.assist(request); assertEquals(1, ai.calls)
            time += 6L * DAY
            assertNotNull(repo.cachedAssist(request)); assertEquals(1, ai.calls)
            time += 2L * DAY
            assertNull(repo.cachedAssist(request)); assertEquals(1, ai.calls)
            repo.assist(request)
            assertEquals(2, ai.calls)
            assertEquals(time, repo.cachedAssist(request)!!.sources.single().retrievedAt)
        } finally { db.close() }
    }

    @Test fun noKeyCanRetrieveButOtherAssistsRequireKeyAndNewKeyUpgradesRetrievalOnlyCache() = runBlocking {
        val db = database(); var hasKey = false
        val ai = ScriptedAi { value ->
            assertEquals("background", value.kind)
            retrieved(model = if (hasKey) "test-model" else "retrieval-only")
        }
        try {
            val repo = repository(db, ai, key = { hasKey })
            val retrievalOnly = repo.assist(request)
            assertEquals("retrieval-only", retrievalOnly.model); assertEquals(1, ai.calls)
            assertEquals(retrievalOnly, repo.cachedAssist(request))
            try { repo.assist(StudyRequest("translation", "A sentence.")); fail("Non-background request requires a key") }
            catch (failure: ApiFailure) { assertEquals("missing_key", failure.code) }
            assertEquals(1, ai.calls)
            hasKey = true
            assertNull(repo.cachedAssist(request))
            val generated = repo.assist(request)
            assertEquals("test-model", generated.model); assertEquals(2, ai.calls)
            assertEquals(generated, repo.cachedAssist(request))
        } finally { db.close() }
    }

    @Test fun emptyAndUnavailableRetrievalsAreNotCachedAndCanBeRetried() = runBlocking {
        for (status in listOf("no_results", "unavailable")) {
            val db = database()
            val ai = ScriptedAi { StudyAssist("background", "未取得证据", "本次未取得可引用材料。", "retrieval-only", now,
                "没有生成无来源解释。", retrievalStatus = status) }
            try {
                val repo = repository(db, ai, key = { false })
                assertEquals(status, repo.assist(request).retrievalStatus)
                val job = db.readingDao().job("reading-ai:${sha256(cacheKey())}")!!
                assertEquals(status, job.status)
                assertEquals("reading_background", json.decodeFromString<AiJob>(job.data).kind)
                assertNull(repo.cachedAssist(request)); assertNull(db.readingDao().entry(cacheKey()))
                assertEquals(status, repo.assist(request).retrievalStatus)
                assertEquals(2, ai.calls)
                assertNull(db.readingDao().entry(cacheKey()))
            } finally { db.close() }
        }
    }

    @Test fun cancelledBackgroundRemainsInterruptedAndOnlyExplicitAssistRetriesIt() = runBlocking {
        val db = database(); val entered = CompletableDeferred<Unit>(); var suspendRequest = true
        val ai = ScriptedAi {
            if (suspendRequest) { entered.complete(Unit); awaitCancellation() }
            retrieved()
        }
        try {
            val repo = repository(db, ai)
            val pending = async { repo.assist(request) }
            withTimeout(3000) { entered.await() }
            withTimeout(3000) { pending.cancelAndJoin() }
            val jobId = "reading-ai:${sha256(cacheKey())}"
            val interrupted = db.readingDao().job(jobId)!!
            assertEquals("interrupted", interrupted.status)
            assertEquals("cancelled", json.decodeFromString<AiJob>(interrupted.data).errorCode)
            assertNull(db.readingDao().entry(cacheKey()))
            assertEquals(1, ai.calls)
            suspendRequest = false
            val reopened = repository(db, ai)
            reopened.initialize(); assertNull(reopened.cachedAssist(request)); assertEquals(1, ai.calls)
            val recovered = reopened.assist(request)
            assertEquals("retrieved", recovered.retrievalStatus); assertEquals(2, ai.calls)
            assertEquals("succeeded", db.readingDao().job(jobId)!!.status)
            assertEquals(recovered, reopened.cachedAssist(request))
        } finally { db.close() }
    }

    @Test fun rejectedSourceNeverInvokesRetrievalOrCreatesSuccessfulBackground() = runBlocking {
        val db = database(); val ai = ScriptedAi { retrieved() }
        try {
            val repo = repository(db, ai, key = { false })
            try { repo.assist(request.copy(aiAllowed = false)); fail("Expected source restriction") }
            catch (failure: ApiFailure) { assertEquals("source_restricted", failure.code) }
            assertEquals(0, ai.calls)
            assertNull(repo.cachedAssist(request.copy(aiAllowed = false)))
            assertTrue(db.dao().observeJobs().first().isEmpty())
        } finally { db.close() }
    }

    @Test fun pausedAiStillAllowsRetrievalWithoutCallingGenerationWithOrWithoutKey() = runBlocking {
        for (initialKey in listOf(false, true)) {
            val db = database(); var key = initialKey; val ai = ScriptedAi { retrieved() }
            try {
                db.readingDao().put(PreferenceRow("ai_paused", "true"))
                val repo = repository(db, ai, key = { key })
                val snippet = repo.assist(request)
                assertEquals("retrieval-only", snippet.model); assertEquals(listOf(source()), snippet.sources)
                assertEquals(1, ai.retrievalOnlyCalls); assertEquals(0, ai.generationCalls)
                assertEquals(snippet, repo.cachedAssist(request)); assertEquals(snippet, repo.assist(request))
                assertEquals(1, ai.calls)
                key = true
                try { repo.assist(StudyRequest("syntax", "A synthetic sentence.")); fail("Paused generation must be blocked") }
                catch (failure: ApiFailure) { assertEquals("paused", failure.code) }
                assertEquals(0, ai.generationCalls)
                db.readingDao().put(PreferenceRow("ai_paused", "false"))
                assertNull(repo.cachedAssist(request))
                assertEquals("test-model", repo.assist(request).model)
                assertEquals(1, ai.generationCalls); assertEquals(1, ai.retrievalOnlyCalls)
            } finally { db.close() }
        }
    }

    private class ScriptedAi(private val operation: suspend (StudyRequest) -> StudyAssist) : ReadingAi {
        var calls = 0
        var generationCalls = 0
        var retrievalOnlyCalls = 0
        override suspend fun analyze(article: Article, body: ArticleBody): ArticleStudy = error("Not used by background cache tests")
        override suspend fun assist(request: StudyRequest): StudyAssist { calls++; generationCalls++; return operation(request) }
        override suspend fun retrieveBackground(request: StudyRequest): StudyAssist { calls++; retrievalOnlyCalls++; return operation(request).copy(model = "retrieval-only") }
        override suspend fun review(notes: List<ReadingNote>): StudyAssist = error("Not used by background cache tests")
    }
    private companion object { const val DAY = 86_400_000L }
}
