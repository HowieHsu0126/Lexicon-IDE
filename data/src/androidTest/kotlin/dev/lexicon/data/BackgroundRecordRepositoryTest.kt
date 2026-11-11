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
import java.io.File
import java.util.UUID

/** Self-authored fixtures in in-memory Room. Saving and exporting never use a network or Key. */
@RunWith(AndroidJUnit4::class)
class BackgroundRecordRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val json = Json { encodeDefaults = true }
    private val query = BackgroundQuery("bank", "The river bank changes after heavy rainfall.",
        source = SourceRef("Synthetic river report", "Self-authored fixture", "https://example.org/river", 100))
    private fun db() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).build()
    private fun source(index: Int, time: Long) = BackgroundSource("S$index", if (index == 1) "Bank (geography)" else "Bank (finance)",
        "https://en.wikipedia.org/w/index.php?curid=$index&oldid=${index + 100}",
        "This self-authored fixture describes meaning $index of bank. It is synthetic evidence for durable knowledge handling and is not a quotation from Wikipedia.",
        time, "Wikipedia (en)", (index + 100).toString())

    private interface TestAi : ReadingAi, BackgroundResearchAi

    private inner class Harness(val db: LexiconDatabase) {
        var time = 10_000L
        var key = true
        var lookupCalls = 0
        var explanationCalls = 0
        var reviewCalls = 0
        var reviewedNotes = emptyList<ReadingNote>()
        val ai: TestAi = object : TestAi {
            override suspend fun lookup(query: BackgroundQuery): BackgroundLookup {
                lookupCalls++
                val sources = listOf(source(1, time), source(2, time))
                return BackgroundResearch.createLookup(query, listOf(
                    BackgroundCandidate("river", "River bank", "A landform", listOf("S1")),
                    BackgroundCandidate("finance", "Financial bank", "A financial institution", listOf("S2")),
                ), sources, listOf(BackgroundProviderOutcome("Wikipedia (en)", "retrieved")), "retrieved", time)
            }
            override suspend fun explain(query: BackgroundQuery, sources: List<BackgroundSource>, beforeRequest: suspend () -> Unit): StudyAssist {
                beforeRequest(); explanationCalls++
                return StudyAssist("background", "所选概念", "这份资料说明了所选概念。[S1]", "synthetic-model", time,
                    "仅依据保存的资料片段。", sources = sources, retrievalStatus = "retrieved", citedSourceIds = listOf("S1"))
            }
            override suspend fun analyze(article: Article, body: ArticleBody): ArticleStudy = error("Not an archive operation")
            override suspend fun assist(request: StudyRequest): StudyAssist = error("Not an archive operation")
            override suspend fun review(notes: List<ReadingNote>): StudyAssist {
                reviewCalls++; reviewedNotes = notes
                return StudyAssist("review", "表达回顾", "仅整理普通笔记。", "synthetic-model", time)
            }
        }
        fun reading() = LocalReadingRepository(db, ai, { key }, { true }, { time })
        fun backgrounds() = requireNotNull(reading().backgrounds)
    }

    private fun lookupKey(lookup: BackgroundLookup) = "background:lookup:1:${lookup.id}"
    private fun explanationKey(lookup: BackgroundLookup, candidate: String) = "background:explanation:1:${lookup.id}:${sha256(candidate)}"
    private suspend fun fails(code: String, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: ApiFailure) { assertEquals(code, error.code) }
    }
    private suspend fun rejected(action: suspend () -> Unit) {
        try { action(); fail("Expected invalid content to be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun expiredRestrictedEvidenceCanBeSavedWithoutKeyGenerationOrJobs() = runBlocking {
        val db = db(); val h = Harness(db)
        try {
            val repo = h.backgrounds(); val lookup = repo.lookup(query.copy(aiAllowed = false))
            h.key = false; h.time += BackgroundResearch.CACHE_TTL_MILLIS
            db.readingDao().put(PreferenceRow("ai_paused", "true"))
            val saved = repo.saveRecord(lookup.id, "river")
            assertEquals(lookup, saved.lookup); assertNull(saved.explanation)
            assertEquals(saved, h.backgrounds().savedRecord(lookup.id, "river"))
            assertEquals(1, h.lookupCalls); assertEquals(0, h.explanationCalls)
            assertTrue(db.dao().observeJobs().first().isEmpty())
        } finally { db.close() }
    }

    @Test fun simultaneousFirstSavesAndDifferentMeaningsHaveStableIdentities() = runBlocking {
        val db = db(); val h = Harness(db)
        try {
            val lookup = h.backgrounds().lookup(query)
            val saved = coroutineScope { (1..6).map { async { h.backgrounds().saveRecord(lookup.id, "river") } }.awaitAll() }
            assertEquals(1, saved.distinct().size)
            val other = h.backgrounds().saveRecord(lookup.id, "finance")
            assertNotEquals(saved.first().id, other.id)
            assertEquals(2, h.reading().library.first().backgroundRecords.size)
        } finally { db.close() }
    }

    @Test fun mainThreadCollectorReceivesBackgroundSaveEditAndDelete() = runBlocking {
        val db = db(); val h = Harness(db)
        try {
            val reading = h.reading(); val repo = requireNotNull(reading.backgrounds); val lookup = repo.lookup(query)
            val savedEmission = async(Dispatchers.Main.immediate) {
                withTimeout(5000) { reading.library.first { it.backgroundRecords.size == 1 } }
            }
            val saved = repo.saveRecord(lookup.id, "river")
            assertEquals(saved, savedEmission.await().backgroundRecords.single())
            val editedEmission = async(Dispatchers.Main.immediate) {
                withTimeout(5000) { reading.library.first { it.backgroundRecords.singleOrNull()?.revision == saved.revision + 1 } }
            }
            val edited = repo.updateRecord(saved.id, saved.revision, "Visible revision", listOf("culture"))
            assertEquals(edited, editedEmission.await().backgroundRecords.single())
            repo.deleteRecord(edited.id, edited.revision)
            val deleted = withContext(Dispatchers.Main.immediate) {
                withTimeout(5000) { reading.library.first { it.backgroundRecords.isEmpty() } }
            }
            assertEquals(0, deleted.backgroundRecordErrors)
        } finally { db.close() }
    }

    @Test fun onlyExplicitlyDisplayedCachedExplanationIsIncludedOrAppended() = runBlocking {
        val db = db(); val h = Harness(db)
        try {
            val repo = h.backgrounds(); val lookup = repo.lookup(query); val explanation = repo.explain(lookup.id, "river")
            val evidenceOnly = repo.saveRecord(lookup.id, "river")
            assertNull(evidenceOnly.explanation)
            val edited = repo.updateRecord(evidenceOnly.id, evidenceOnly.revision, "My reasoning", listOf("river"))
            assertEquals(edited, repo.saveRecord(lookup.id, "river", explanation))
            val appended = repo.saveRecord(lookup.id, "river", explanation, edited.revision)
            assertEquals(explanation, appended.explanation); assertEquals("My reasoning", appended.comment)
            assertEquals(edited.tags, appended.tags); assertEquals(edited.revision + 1, appended.revision)
            assertEquals(appended, repo.saveRecord(lookup.id, "river", explanation, appended.revision))
            assertEquals(appended, repo.saveRecord(lookup.id, "river", null, appended.revision))
            assertEquals(1, h.explanationCalls)
        } finally { db.close() }
    }

    @Test fun firstSaveCanIncludeShownExplanationAndRejectsUncachedOrChangedOutput() = runBlocking {
        val db = db(); val h = Harness(db)
        try {
            val repo = h.backgrounds(); val lookup = repo.lookup(query); val explanation = repo.explain(lookup.id, "finance")
            fails("background_explanation_changed") { repo.saveRecord(lookup.id, "finance", explanation.copy(text = "Different valid explanation. [S1]")) }
            assertNull(repo.savedRecord(lookup.id, "finance"))
            fails("background_explanation_missing") { repo.saveRecord(lookup.id, "river", explanation) }
            val saved = repo.saveRecord(lookup.id, "finance", explanation)
            assertEquals(explanation, saved.explanation)
            assertEquals("S1", saved.explanation!!.sources.single().id)
            assertEquals(lookup.sources[1].url, saved.explanation!!.sources.single().url)
        } finally { db.close() }
    }

    @Test fun storedExplanationCannotBeReplacedByANewerValidCacheEntry() = runBlocking {
        val db = db(); val h = Harness(db)
        try {
            val repo = h.backgrounds(); val lookup = repo.lookup(query); val original = repo.explain(lookup.id, "river")
            val saved = repo.saveRecord(lookup.id, "river", original)
            val changed = original.copy(text = "Another interpretation. [S1]")
            val key = explanationKey(lookup, "river")
            val raw = json.parseToJsonElement(requireNotNull(db.readingDao().entry(key)).data).jsonObject
            db.readingDao().put(PreferenceRow(key, JsonObject(raw + ("result" to json.encodeToJsonElement(changed))).toString()))
            rejected { repo.saveRecord(lookup.id, "river", changed, saved.revision) }
            assertEquals(saved, repo.savedRecord(lookup.id, "river"))
        } finally { db.close() }
    }

    @Test fun staleMetadataEditsAndDeletionCannotOverwriteAConcurrentRevision() = runBlocking {
        val db = db(); val h = Harness(db)
        try {
            val repo = h.backgrounds(); val lookup = repo.lookup(query); val saved = repo.saveRecord(lookup.id, "river")
            val edited = repo.updateRecord(saved.id, saved.revision, "New comment", listOf("Science"))
            fails("background_record_conflict") { h.backgrounds().updateRecord(saved.id, saved.revision, "Old editor", emptyList()) }
            fails("background_record_conflict") { h.backgrounds().deleteRecord(saved.id, saved.revision) }
            assertEquals(edited, repo.savedRecord(lookup.id, "river"))
            assertEquals(edited, repo.updateRecord(edited.id, edited.revision, edited.comment, edited.tags))
        } finally { db.close() }
    }

    @Test fun concurrentWritersAcceptOnlyOneExpectedRevision() = runBlocking {
        val db = db(); val h = Harness(db)
        try {
            val repo = h.backgrounds(); val lookup = repo.lookup(query); val saved = repo.saveRecord(lookup.id, "river")
            val results = supervisorScope { (1..4).map { index -> async {
                try { h.backgrounds().updateRecord(saved.id, saved.revision, "Writer $index", emptyList()); true }
                catch (error: ApiFailure) { assertEquals("background_record_conflict", error.code); false }
            } }.awaitAll() }
            assertEquals(1, results.count { it }); assertEquals(2, repo.savedRecord(lookup.id, "river")!!.revision)
        } finally { db.close() }
    }

    @Test fun savedKnowledgeSurvivesCacheRemovalAndExportsAllSelectedEvidenceOffline() = runBlocking {
        val db = db(); val h = Harness(db); val output = File(context.cacheDir, "background-export-${UUID.randomUUID()}.md")
        try {
            val repo = h.backgrounds(); val lookup = repo.lookup(query); val explanation = repo.explain(lookup.id, "finance")
            val saved = repo.saveRecord(lookup.id, "finance", explanation)
            db.readingDao().delete(lookupKey(lookup)); db.readingDao().delete(explanationKey(lookup, "finance"))
            db.readingDao().delete("background:index:1:${BackgroundResearch.queryKey(query)}")
            h.key = false; h.time += BackgroundResearch.CACHE_TTL_MILLIS
            assertEquals(saved, h.backgrounds().savedRecord(lookup.id, "finance"))
            assertEquals(saved, h.reading().library.first().backgroundRecords.single())
            val edited = h.backgrounds().updateRecord(saved.id, saved.revision, "Personal observation", listOf("culture"))
            h.reading().exportKnowledge(output)
            val text = output.readText()
            assertTrue(text.contains(query.context)); assertTrue(text.contains("Financial bank"))
            assertTrue(text.contains("curid=2&amp;oldid=102")); assertFalse(text.contains("curid=1"))
            assertTrue(text.contains("CC BY-SA 4.0")); assertTrue(text.contains("资料版本：102"))
            assertTrue(text.contains("AI 背景解释")); assertTrue(text.contains(edited.comment))
            assertEquals(1, h.lookupCalls); assertEquals(1, h.explanationCalls)
        } finally { output.delete(); db.close() }
    }

    @Test fun deletingKnowledgeKeepsOriginalCacheAndCardsAndStaleEditorCannotResurrectIt() = runBlocking {
        val db = db(); val h = Harness(db)
        try {
            val repo = h.backgrounds(); val lookup = repo.lookup(query); val explanation = repo.explain(lookup.id, "river")
            val saved = repo.saveRecord(lookup.id, "river", explanation)
            val cache = db.readingDao().entry(lookupKey(lookup)); val explanationRow = db.readingDao().entry(explanationKey(lookup, "river"))
            val sentinel = CardRow("synthetic-card", "synthetic-capture", 0, 0, 0, "self-authored unrelated sentinel")
            db.dao().putCard(sentinel)
            repo.deleteRecord(saved.id, saved.revision)
            assertNull(repo.savedRecord(lookup.id, "river")); assertTrue(h.reading().library.first().backgroundRecords.isEmpty())
            assertEquals(cache, db.readingDao().entry(lookupKey(lookup))); assertEquals(explanationRow, db.readingDao().entry(explanationKey(lookup, "river")))
            assertEquals(listOf(sentinel), db.dao().allCards())
            fails("background_record_conflict") { repo.saveRecord(lookup.id, "river", explanation, saved.revision) }
            fails("background_record_missing") { repo.updateRecord(saved.id, saved.revision, "Stale editor", emptyList()) }
        } finally { db.close() }
    }

    @Test fun backgroundKnowledgeNeverAutomaticallyEntersExpressionReview() = runBlocking {
        val db = db(); val h = Harness(db)
        try {
            val reading = h.reading(); val repo = requireNotNull(reading.backgrounds); val lookup = repo.lookup(query)
            repo.saveRecord(lookup.id, "river", repo.explain(lookup.id, "river"))
            fails("no_notes") { reading.expressionReview() }; assertEquals(0, h.reviewCalls)
            val note = ReadingNote("ordinary-note", null, null, "My original learning phrase.", "My comment", listOf("writing"), h.time)
            reading.saveNote(note); reading.expressionReview()
            assertEquals(listOf(note), h.reviewedNotes); assertEquals(1, h.reviewCalls)
        } finally { db.close() }
    }

    @Test fun deleteThenExplicitlyResaveCannotMatchAnOldEditorsRevision() = runBlocking {
        val db = db(); val h = Harness(db)
        try {
            val repo = h.backgrounds(); val lookup = repo.lookup(query)
            val first = repo.saveRecord(lookup.id, "river")
            val edited = repo.updateRecord(first.id, first.revision, "Before deletion", emptyList())
            repo.deleteRecord(edited.id, edited.revision)
            val watermarkKey = BackgroundRecordStorage.revisionKey(edited.id)
            assertEquals(edited.revision.toString(), db.readingDao().entry(watermarkKey)?.data)
            assertNull(db.readingDao().entry(BackgroundRecordStorage.key(edited.id)))
            // Clock deliberately stays unchanged: version continuity, not timestamps, prevents ABA.
            val recreated = h.backgrounds().saveRecord(lookup.id, "river")
            assertEquals(edited.id, recreated.id); assertEquals(edited.revision + 1, recreated.revision)
            assertEquals("", recreated.comment)
            fails("background_record_conflict") { repo.updateRecord(first.id, first.revision, "Old window", emptyList()) }
            fails("background_record_conflict") { repo.deleteRecord(edited.id, edited.revision) }
            assertEquals(recreated, repo.savedRecord(lookup.id, "river"))
        } finally { db.close() }
    }

    @Test fun existingHighRevisionWithoutWatermarkIsPreservedAcrossDeleteAndResave() = runBlocking {
        val db = db(); val h = Harness(db)
        try {
            val repo = h.backgrounds(); val lookup = repo.lookup(query)
            val older = BackgroundRecords.create(lookup, "river", h.time).copy(revision = 7)
            db.readingDao().put(BackgroundRecordStorage.row(older))
            val edited = repo.updateRecord(older.id, older.revision, "New comment", emptyList())
            assertEquals(8, edited.revision)
            repo.deleteRecord(edited.id, edited.revision)
            val recreated = repo.saveRecord(lookup.id, "river")
            assertEquals(9, recreated.revision)
            fails("background_record_conflict") { repo.deleteRecord(recreated.id, older.revision) }
        } finally { db.close() }
    }

    @Test fun corruptKnowledgeIsCountedPreservedAndCannotProduceAnApparentlyCompleteExport() = runBlocking {
        val db = db(); val h = Harness(db); val output = File(context.cacheDir, "background-invalid-${UUID.randomUUID()}.md")
        try {
            val repo = h.backgrounds(); val lookup = repo.lookup(query); val saved = repo.saveRecord(lookup.id, "river")
            val key = BackgroundRecordStorage.key(saved.id); val invalid = PreferenceRow(key, "{broken knowledge JSON")
            db.readingDao().put(invalid)
            val library = h.reading().library.first()
            assertEquals(1, library.backgroundRecordErrors); assertTrue(library.backgroundRecords.isEmpty())
            fails("background_record_invalid") { repo.savedRecord(lookup.id, "river") }
            fails("background_record_invalid") { repo.saveRecord(lookup.id, "river") }
            output.writeText("Existing user export")
            fails("background_record_invalid") { h.reading().exportKnowledge(output) }
            assertEquals("Existing user export", output.readText()); assertEquals(invalid, db.readingDao().entry(key))
        } finally { output.delete(); db.close() }
    }

    @Test fun forgedSourceInKnowledgeIsRejectedWithoutChangingItsStoredBytes() = runBlocking {
        val db = db(); val h = Harness(db)
        try {
            val repo = h.backgrounds(); val lookup = repo.lookup(query); val saved = repo.saveRecord(lookup.id, "river")
            val forgedSources = lookup.sources.map { it.copy(url = it.url.replace("en.wikipedia.org", "example.org")) }
            val forgedLookup = BackgroundResearch.createLookup(lookup.query, lookup.candidates, forgedSources, lookup.providerOutcomes, lookup.status, lookup.createdAt)
            val forged = BackgroundRecords.create(forgedLookup, "river", h.time)
            val row = PreferenceRow(BackgroundRecordStorage.key(forged.id), json.encodeToString(forged))
            db.readingDao().put(row)
            val library = h.reading().library.first()
            assertEquals(listOf(saved), library.backgroundRecords); assertEquals(1, library.backgroundRecordErrors)
            fails("background_record_invalid") { repo.savedRecord(forgedLookup.id, "river") }
            assertEquals(row, db.readingDao().entry(row.key))
        } finally { db.close() }
    }

    @Test fun recentQueriesUseOnlyLatestSnapshotAndPublicLookupDistinguishesMissingFromCorrupt() = runBlocking {
        val db = db(); val h = Harness(db)
        try {
            val repo = h.backgrounds(); val old = repo.lookup(query); h.time++
            val latest = repo.lookup(query, refresh = true)
            assertEquals(listOf(latest), repo.recentLookups()); assertEquals(old, repo.readLookup(old.id))
            assertNull(repo.readLookup("0".repeat(64)))
            val row = PreferenceRow(lookupKey(latest), "{broken lookup")
            db.readingDao().put(row)
            fails("background_cache_invalid") { repo.readLookup(latest.id) }
            fails("background_cache_invalid") { repo.recentLookups() }
            assertEquals(row, db.readingDao().entry(row.key))
        } finally { db.close() }
    }

    @Test fun recentQueryListIsBoundedAndNewestFirst() = runBlocking {
        val db = db(); val h = Harness(db)
        try {
            val repo = h.backgrounds(); val lookups = (0..101).map { index ->
                h.time++; repo.lookup(query.copy(term = "bank $index"))
            }
            assertEquals(lookups.reversed().take(100), repo.recentLookups())
        } finally { db.close() }
    }

    @Test fun transactionRejectsChangedEvidenceAndDoesNotCreateAKnowledgeRow() = runBlocking {
        val db = db(); val h = Harness(db)
        try {
            val lookup = h.backgrounds().lookup(query); val expected = requireNotNull(db.readingDao().entry(lookupKey(lookup)))
            val saved = BackgroundRecords.create(lookup, "river", h.time); val record = BackgroundRecordStorage.row(saved)
            db.readingDao().delete(expected.key)
            assertFalse(db.readingDao().changeBackgroundRecord(record.key, null, record, listOf(expected)))
            assertNull(db.readingDao().entry(record.key))
        } finally { db.close() }
    }
}
