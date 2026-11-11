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

/** In-memory Room and self-authored snippets; no real HTTP, credentials, or publication text. */
@RunWith(AndroidJUnit4::class)
class BackgroundResearchRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val json = Json { encodeDefaults = true }
    private val query = BackgroundQuery("bank", "The river bank changes after heavy rainfall.")
    private val article = Article("background-article", "synthetic", listOf("fixture"), "Synthetic river report",
        "https://example.org/river", "Unused synthetic summary", publishedAt = 100, sourceName = "Self-authored fixture")
    private fun associated() = query.copy(articleId = article.id, bodyHash = sha256("synthetic body no longer cached"),
        source = SourceRef(article.title, article.sourceName, article.url, article.publishedAt))
    private fun db() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).build()
    private suspend fun seed(db: LexiconDatabase) { db.dao().putArticle(ArticleRow(article.id, article.url, 100, json.encodeToString(article))) }
    private fun source(index: Int, time: Long) = BackgroundSource("S$index", if (index == 1) "Bank (geography)" else "Bank (finance)",
        "https://en.wikipedia.org/w/index.php?curid=$index&oldid=${index + 100}",
        "This self-authored source describes meaning $index of bank. It is a synthetic evidence passage used only to check durable source handling and does not quote Wikipedia.",
        time, "Wikipedia (en)", (index + 100).toString())
    private inner class Harness(val db: LexiconDatabase) {
        var time = 10_000L
        var key = false
        var allowed = true
        var lookupCalls = 0
        var explanationCalls = 0
        var submittedCalls = 0
        var nextLookupFailure: Exception? = null
        var nextExplanationFailure: Exception? = null
        var lookupStatus = "retrieved"
        var explaining: (suspend () -> Unit)? = null
        var afterSubmission: (suspend () -> Unit)? = null
        var alter: (StudyAssist) -> StudyAssist = { it }
        var seenSources = emptyList<BackgroundSource>()
        val ai = object : BackgroundResearchAi {
            override suspend fun lookup(query: BackgroundQuery): BackgroundLookup {
                lookupCalls++
                nextLookupFailure?.let { nextLookupFailure = null; throw it }
                val sources = if (lookupStatus == "retrieved") listOf(source(1, time), source(2, time)) else emptyList()
                return BackgroundResearch.createLookup(query,
                    sources.mapIndexed { index, s -> BackgroundCandidate("c$index", s.title, s.provider, listOf(s.id)) }, sources,
                    listOf(BackgroundProviderOutcome("Wikipedia (en)", lookupStatus)), lookupStatus, time)
            }
            override suspend fun explain(query: BackgroundQuery, sources: List<BackgroundSource>, beforeRequest: suspend () -> Unit): StudyAssist {
                explanationCalls++; seenSources = sources
                explaining?.invoke()
                beforeRequest()
                submittedCalls++
                afterSubmission?.invoke()
                nextExplanationFailure?.let { nextExplanationFailure = null; throw it }
                return alter(StudyAssist("background", "背景解释", "这份资料解释了所选概念。[S1]", "synthetic-model", time,
                    "仅依据客户端冻结资料。", sources = sources, retrievalStatus = "retrieved", citedSourceIds = listOf("S1")))
            }
        }
        fun repository() = LocalBackgroundRepository(db, ai, { key }, { allowed }, { time })
    }
    private fun indexKey(query: BackgroundQuery = this.query) = "background:index:1:${BackgroundResearch.queryKey(query)}"
    private fun lookupKey(value: BackgroundLookup) = "background:lookup:1:${value.id}"
    private fun explanationKey(value: BackgroundLookup, candidate: String = value.candidates.first().id) =
        "background:explanation:1:${value.id}:${sha256(candidate)}"
    private suspend fun failure(code: String, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: ApiFailure) { assertEquals(code, error.code) }
    }

    @Test fun explicitTermLookupNeedsNeitherKeyNorAiPermissionAndCreatesNoGenerationJob() = runBlocking {
        val db = db(); val h = Harness(db)
        try {
            val restricted = query.copy(aiAllowed = false); val repo = h.repository()
            val lookup = repo.lookup(restricted)
            assertEquals(lookup, repo.cached(restricted)); assertEquals(lookup, repo.lookup(restricted))
            assertEquals(1, h.lookupCalls); assertEquals(0, h.explanationCalls)
            failure("source_restricted") { repo.explain(lookup.id, lookup.candidates.first().id) }
            assertTrue(db.dao().observeJobs().first().isEmpty())
        } finally { db.close() }
    }

    @Test fun staleEvidenceRemainsReadableWithoutNetworkButCannotGenerateUntilRefreshed() = runBlocking {
        val db = db(); val h = Harness(db)
        try {
            val repo = h.repository(); val lookup = repo.lookup(query)
            val row = db.readingDao().entry(lookupKey(lookup))
            h.time += BackgroundResearch.CACHE_TTL_MILLIS; h.key = true
            assertEquals(lookup, h.repository().cached(query)); assertEquals(lookup, repo.lookup(query))
            failure("refresh_required") { repo.explain(lookup.id, "c0") }
            assertEquals(1, h.lookupCalls); assertEquals(0, h.explanationCalls); assertEquals(row, db.readingDao().entry(lookupKey(lookup)))
            assertTrue(db.dao().observeJobs().first().isEmpty())
            val refreshed = repo.lookup(query, refresh = true)
            assertNotEquals(lookup.id, refreshed.id); assertEquals(refreshed, repo.cached(query))
            assertNotNull(db.readingDao().entry(lookupKey(lookup))); assertEquals(2, h.lookupCalls)
        } finally { db.close() }
    }

    @Test fun firstEmptyLookupIsRememberedUntilAnExplicitRefresh() = runBlocking {
        val db = db(); val h = Harness(db).apply { lookupStatus = "no_results" }
        try {
            val repo = h.repository(); val empty = repo.lookup(query)
            assertEquals("no_results", empty.status); assertEquals(empty, repo.lookup(query)); assertEquals(empty, repo.cached(query))
            h.lookupStatus = "retrieved"; h.time++
            assertEquals("retrieved", repo.lookup(query, refresh = true).status)
            assertEquals(2, h.lookupCalls); assertEquals(0, h.explanationCalls)
        } finally { db.close() }
    }

    @Test fun failedRefreshLeavesPriorReceiptAndIndexUntouched() = runBlocking {
        val db = db(); val h = Harness(db)
        try {
            val repo = h.repository(); val first = repo.lookup(query)
            val index = db.readingDao().entry(indexKey()); val row = db.readingDao().entry(lookupKey(first))
            h.lookupStatus = "unavailable"; h.time++
            failure("background_unavailable") { repo.lookup(query, refresh = true) }
            h.lookupStatus = "no_results"
            failure("background_no_results") { repo.lookup(query, refresh = true) }
            h.nextLookupFailure = ApiFailure("network", "Synthetic offline")
            failure("network") { repo.lookup(query, refresh = true) }
            assertEquals(first, repo.cached(query)); assertEquals(index, db.readingDao().entry(indexKey()))
            assertEquals(row, db.readingDao().entry(lookupKey(first))); assertEquals(0, h.explanationCalls)
        } finally { db.close() }
    }

    @Test fun modelAuthenticationFailurePreservesEvidenceAndOnlyExplicitRetryUsesItAgain() = runBlocking {
        val db = db(); val h = Harness(db).apply { key = true }
        try {
            val repo = h.repository(); val lookup = repo.lookup(query)
            val row = db.readingDao().entry(lookupKey(lookup)); h.nextExplanationFailure = ApiFailure("authentication", "Synthetic authentication")
            failure("authentication") { repo.explain(lookup.id, "c0") }
            assertEquals(row, db.readingDao().entry(lookupKey(lookup))); assertEquals(lookup, h.repository().cached(query))
            assertEquals("true", db.readingDao().entry("ai_paused")?.data)
            assertNull(repo.cachedExplanation(lookup.id, "c0"))
            failure("retry_required") { h.repository().explain(lookup.id, "c0") }
            assertEquals(1, h.explanationCalls); assertEquals(1, h.lookupCalls)
            db.readingDao().put(PreferenceRow("ai_paused", "false"))
            val explanation = h.repository().explain(lookup.id, "c0", explicitRetry = true)
            assertEquals(explanation, repo.cachedExplanation(lookup.id, "c0"))
            assertEquals(2, h.explanationCalls); assertEquals(1, h.lookupCalls)
            assertEquals(1, db.dao().observeJobs().first().size)
        } finally { db.close() }
    }

    @Test fun cancellationAfterRetrievalKeepsEvidenceAndDoesNotBlockCachedReads() = runBlocking {
        val db = db(); val h = Harness(db).apply { key = true }
        try {
            val repo = h.repository(); val lookup = repo.lookup(query); val entered = CompletableDeferred<Unit>()
            h.explaining = { entered.complete(Unit); awaitCancellation() }
            val task = async { repo.explain(lookup.id, "c0") }
            withTimeout(3000) { entered.await() }
            assertEquals(lookup, withTimeout(1000) { repo.cached(query) })
            task.cancelAndJoin()
            assertEquals("interrupted", db.dao().observeJobs().first().single().status)
            val reopened = h.repository()
            assertEquals(lookup, reopened.cached(query)); failure("retry_required") { reopened.explain(lookup.id, "c0") }
            h.explaining = null
            assertEquals("retrieved", reopened.explain(lookup.id, "c0", explicitRetry = true).retrievalStatus)
            assertEquals(1, h.lookupCalls); assertEquals(2, h.explanationCalls)
        } finally { db.close() }
    }

    @Test fun concurrentRepositoriesShareLookupAndOneExplanation() = runBlocking {
        val db = db(); val h = Harness(db).apply { key = true }
        try {
            val a = h.repository(); val b = h.repository()
            val lookups = coroutineScope { listOf(async { a.lookup(query) }, async { b.lookup(query) }).awaitAll() }
            assertEquals(1, lookups.distinct().size); assertEquals(1, h.lookupCalls)
            val lookup = lookups.first()
            val explanations = coroutineScope { listOf(async { a.explain(lookup.id, "c0") }, async { b.explain(lookup.id, "c0") }).awaitAll() }
            assertEquals(1, explanations.distinct().size); assertEquals(1, h.explanationCalls)
            assertEquals(1, db.dao().observeJobs().first().size)
        } finally { db.close() }
    }

    @Test fun chosenConceptAloneSuppliesEvidenceAndHasItsOwnExplanationCache() = runBlocking {
        val db = db(); val h = Harness(db).apply { key = true }
        try {
            val repo = h.repository(); val lookup = repo.lookup(query)
            val finance = repo.explain(lookup.id, "c1")
            assertEquals(listOf(lookup.sources[1].copy(id = "S1")), h.seenSources)
            val geography = repo.explain(lookup.id, "c0")
            assertEquals(listOf(lookup.sources[0]), geography.sources)
            assertEquals(finance, repo.cachedExplanation(lookup.id, "c1")); assertEquals(geography, repo.cachedExplanation(lookup.id, "c0"))
            assertEquals(1, h.lookupCalls); assertEquals(2, h.explanationCalls)
        } finally { db.close() }
    }

    @Test fun savedExplanationSurvivesKeyRemovalPermissionRevocationAndExpiry() = runBlocking {
        val db = db(); val h = Harness(db).apply { key = true }
        try {
            seed(db); val repo = h.repository(); val lookup = repo.lookup(associated())
            val explanation = repo.explain(lookup.id, "c0")
            h.key = false; h.allowed = false; h.time += BackgroundResearch.CACHE_TTL_MILLIS
            assertEquals(explanation, h.repository().cachedExplanation(lookup.id, "c0"))
            assertEquals(explanation, h.repository().explain(lookup.id, "c0"))
            assertEquals(1, h.explanationCalls); assertEquals(1, h.lookupCalls)
        } finally { db.close() }
    }

    @Test fun missingAndPausedKeysLeaveSavedEvidenceWithoutCreatingGenerationJobs() = runBlocking {
        val db = db(); val h = Harness(db)
        try {
            val repo = h.repository(); val lookup = repo.lookup(query)
            failure("missing_key") { repo.explain(lookup.id, "c0") }
            h.key = true; db.readingDao().put(PreferenceRow("ai_paused", "true"))
            failure("paused") { repo.explain(lookup.id, "c0") }
            assertEquals(lookup, repo.cached(query)); assertEquals(0, h.explanationCalls)
            assertTrue(db.dao().observeJobs().first().isEmpty())
        } finally { db.close() }
    }

    @Test fun unknownAssociationAndForgedCanonicalSourceCannotGenerate() = runBlocking {
        val db = db(); val h = Harness(db).apply { key = true }
        try {
            val repo = h.repository(); val unlinked = repo.lookup(associated())
            failure("source_restricted") { repo.explain(unlinked.id, "c0") }
            seed(db)
            val forged = repo.lookup(associated().copy(source = associated().source.copy(title = "A different article")))
            failure("source_restricted") { repo.explain(forged.id, "c0") }
            h.allowed = false
            failure("source_restricted") { repo.explain(unlinked.id, "c0") }
            assertEquals(0, h.explanationCalls); assertTrue(db.dao().observeJobs().first().isEmpty())
        } finally { db.close() }
    }

    @Test fun optionalDateAndExpiredBodyDoNotDiscardAValidCanonicalAssociation() = runBlocking {
        val db = db(); val h = Harness(db).apply { key = true }
        try {
            seed(db); val repo = h.repository()
            val lookup = repo.lookup(associated().copy(source = associated().source.copy(publishedAt = null)))
            assertNull(db.dao().body(article.id, Long.MIN_VALUE))
            assertEquals("retrieved", repo.explain(lookup.id, "c0").retrievalStatus)
            assertEquals(1, h.explanationCalls)
        } finally { db.close() }
    }

    @Test fun permissionRevokedDuringGenerationPreventsSavingButKeepsTheEvidence() = runBlocking {
        val db = db(); val h = Harness(db).apply { key = true }
        try {
            seed(db); val repo = h.repository(); val lookup = repo.lookup(associated())
            h.afterSubmission = { h.allowed = false }
            failure("source_restricted") { repo.explain(lookup.id, "c0") }
            assertNull(repo.cachedExplanation(lookup.id, "c0")); assertEquals(lookup, repo.cached(associated()))
            assertEquals("failed", db.dao().observeJobs().first().single().status)
            assertEquals(1, h.explanationCalls); assertEquals(1, h.lookupCalls)
        } finally { db.close() }
    }

    @Test fun invalidGeneratedReferencesNeverReplaceTheFrozenReceipt() = runBlocking {
        val db = db(); val h = Harness(db).apply { key = true }
        try {
            val repo = h.repository(); val lookup = repo.lookup(query)
            val original = db.readingDao().entry(lookupKey(lookup))
            h.alter = { it.copy(sources = listOf(source(2, h.time).copy(id = "S1"))) }
            failure("background_explanation_failed") { repo.explain(lookup.id, "c0") }
            assertNull(repo.cachedExplanation(lookup.id, "c0")); assertEquals(original, db.readingDao().entry(lookupKey(lookup)))
            failure("retry_required") { repo.explain(lookup.id, "c0") }
            h.alter = { it.copy(text = "Wrong citation [S9]") }
            failure("background_explanation_failed") { repo.explain(lookup.id, "c0", explicitRetry = true) }
            assertEquals(original, db.readingDao().entry(lookupKey(lookup))); assertEquals(1, h.lookupCalls)
        } finally { db.close() }
    }

    @Test fun corruptReceiptIsNotSilentlyOverwrittenAndExplicitRefreshLeavesItsBytesAvailable() = runBlocking {
        val db = db(); val h = Harness(db)
        try {
            val repo = h.repository(); val lookup = repo.lookup(query)
            val broken = PreferenceRow(lookupKey(lookup), "invalid-saved-receipt")
            db.readingDao().put(broken)
            assertNull(repo.cached(query)); failure("background_cache_invalid") { repo.lookup(query) }
            assertEquals(1, h.lookupCalls); assertEquals(broken, db.readingDao().entry(lookupKey(lookup)))
            h.time++
            val refreshed = repo.lookup(query, refresh = true)
            assertEquals(refreshed, repo.cached(query)); assertEquals(broken, db.readingDao().entry(lookupKey(lookup)))
        } finally { db.close() }
    }

    @Test fun corruptExplanationNeedsExplicitRetryWithoutRepeatingRetrieval() = runBlocking {
        val db = db(); val h = Harness(db).apply { key = true }
        try {
            val repo = h.repository(); val lookup = repo.lookup(query)
            val row = PreferenceRow(explanationKey(lookup), "invalid-saved-explanation")
            db.readingDao().put(row)
            assertNull(repo.cachedExplanation(lookup.id, "c0")); failure("retry_required") { repo.explain(lookup.id, "c0") }
            assertEquals(row, db.readingDao().entry(explanationKey(lookup))); assertEquals(0, h.explanationCalls)
            assertEquals("retrieved", repo.explain(lookup.id, "c0", explicitRetry = true).retrievalStatus)
            assertEquals(1, h.lookupCalls); assertEquals(1, h.explanationCalls)
        } finally { db.close() }
    }

    @Test fun currentSourceKeyPauseAndExpiryAreCheckedAtTheActualSubmissionBoundary() = runBlocking {
        for (code in listOf("source_restricted", "missing_key", "paused", "refresh_required", "background_cache_invalid")) {
            val db = db(); val h = Harness(db).apply { key = true }
            try {
                seed(db); val repo = h.repository(); val lookup = repo.lookup(associated())
                h.explaining = {
                    when (code) {
                        "source_restricted" -> h.allowed = false
                        "missing_key" -> h.key = false
                        "paused" -> db.readingDao().put(PreferenceRow("ai_paused", "true"))
                        "refresh_required" -> h.time += BackgroundResearch.CACHE_TTL_MILLIS
                        else -> db.readingDao().put(PreferenceRow(lookupKey(lookup), "synthetic-corrupt-receipt"))
                    }
                }
                failure(code) { repo.explain(lookup.id, "c0") }
                assertEquals(1, h.explanationCalls); assertEquals(0, h.submittedCalls)
                assertNull(repo.cachedExplanation(lookup.id, "c0"))
                assertEquals("failed", db.dao().observeJobs().first().single().status)
            } finally { db.close() }
        }
    }

    @Test fun concurrentExplicitRetriesShareOneFailedAttemptAndALaterRetryRemainsExplicit() = runBlocking {
        val db = db(); val h = Harness(db).apply { key = true }
        try {
            val repo = h.repository(); val lookup = repo.lookup(query)
            h.nextExplanationFailure = ApiFailure("network", "First synthetic failure")
            failure("network") { repo.explain(lookup.id, "c0") }
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            h.explaining = { entered.complete(Unit); release.await() }
            h.nextExplanationFailure = ApiFailure("network", "Synthetic failed explicit retry")
            val first = async(Dispatchers.IO) { runCatching { repo.explain(lookup.id, "c0", explicitRetry = true) } }
            withTimeout(3000) { entered.await() }
            val second = async(Dispatchers.IO) { runCatching { h.repository().explain(lookup.id, "c0", explicitRetry = true) } }
            delay(150) // Let the second database cache read reach the occupied generation lane.
            release.complete(Unit)
            assertEquals("network", (first.await().exceptionOrNull() as ApiFailure).code)
            assertEquals("network", (second.await().exceptionOrNull() as ApiFailure).code)
            assertEquals(2, h.explanationCalls); assertEquals(2, h.submittedCalls); assertEquals(1, h.lookupCalls)
            h.explaining = null
            assertEquals("retrieved", repo.explain(lookup.id, "c0", explicitRetry = true).retrievalStatus)
            assertEquals(3, h.explanationCalls)
        } finally { db.close() }
    }
}
