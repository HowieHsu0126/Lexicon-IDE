package dev.lexicon.ide.ui

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import dev.lexicon.ide.LexiconApplication
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in integration on an already-read publisher sentence. No invented replacement material or automatic replay. */
@RunWith(AndroidJUnit4::class)
class BackgroundResearchLiveTrialTest {
    private val codec = Json { ignoreUnknownKeys = true }
    private data class Material(val article: Article?, val body: ArticleBody?, val query: BackgroundQuery,
        val selectionStart: Int?, val selectionEnd: Int?, val provider: String, val authored: Boolean = false)
    private data class Before(val settings: AppSettings, val settingsBytes: ByteArray?, val pausedBytes: ByteArray?,
        val preferences: Map<String, ByteArray>, val tables: Map<String, List<List<String?>>>, val jobs: Map<String, List<String?>>)

    @Test fun existingPublisherContextCanUseFrozenOfficialEvidenceForOneExplanation() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Background live integration requires both opt-in flags", args.getString("lexiconBackgroundResearchLive") == "1" &&
            args.getString("lexiconPrivateApiFile") == "1")
        val useAuthored = args.getString("lexiconBackgroundAuthored") == "1"
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiconApplication
        val repository = app.repository
        val database = app.getDatabasePath("lexicon.sqlite")
        val stagedKey = File(app.filesDir, "lexicon-trial-api-key")
        val output = File(app.filesDir, "validation-1.11.0/background-live").apply { mkdirs() }
        val report = JSONObject().put("status", "started")
            .put("materialKind", if (useAuthored) "self_authored_context" else "existing_publisher_original")
            .put("self_authored_context", useAuthored)
            .put("maximumExplicitExplanationCalls", 1).put("maximumInternalSchemaRepairs", 1)
            .put("explicitLookupCalls", 0).put("explicitExplanationCalls", 0)
            .put("httpRequestCountMeasured", false).put("automatedInteractionTrial", false)
        fun save() = File(output, "live-trial.json").writeText(report.toString(2))
        fun stop(reason: String, status: String = "skipped"): Nothing {
            report.put("status", status).put("reason", reason); save()
            assumeTrue(reason, false); error("Skipped")
        }
        var before: Before? = null
        var material: Material? = null
        var lookup: BackgroundLookup? = null
        var candidateId: String? = null
        var settingsChanged = false
        var temporaryKeyInstalled = false
        var failed = false
        save()
        try {
            if (runBlocking { repository.hasApiKey() }) stop("An installed API key exists and will not be replaced")
            runBlocking { repository.initialize() }
            if (!database.isFile) stop("The installed database is unavailable")
            val background = repository.reading?.backgrounds ?: stop("Background repository is unavailable")
            val jobs = jobRows(database)
            if (jobs.values.any { it[1] == "running" }) stop("Existing AI work is running")
            val chosen = if (useAuthored) Material(null, null, BackgroundQuery("FOMC", AUTHORED_SENTENCE), null, null,
                "Federal Reserve Board", authored = true)
            else findMaterial(database, runBlocking { repository.articles.first() }, runBlocking { repository.sources.first() })
                ?: stop("No already-read Conversation body contains an exact Electoral College or FOMC reference; no replacement text was created")
            material = chosen
            before = Before(runBlocking { repository.settings.first() }, preference(database, "settings"), preference(database, "ai_paused"),
                preferences(database), PROTECTED_TABLES.associateWith { rows(database, it) }, jobs)
            val saved = requireNotNull(before)
            if (!chosen.authored) report.put("articleId", chosen.article!!.id).put("publisherHost", "theconversation.com").put("bodyHash", chosen.body!!.contentHash)
                .put("selectionStart", chosen.selectionStart).put("selectionEnd", chosen.selectionEnd)
            report.put("term", chosen.query.term)
                .put("contextCharacters", chosen.query.context.length).put("contextHash", fingerprint(chosen.query.context))
                .put("canonicalSourceFromInstalledArticle", !chosen.authored)
            val priorLookup = runBlocking { background.cached(chosen.query) }
            report.put("lookupCacheExistedBefore", priorLookup != null)
            if (chosen.authored && priorLookup == null)
                stop("The explicit self-authored path requires the interaction trial's cached real official evidence; no second retrieval was started")
            val retrievalStarted = SystemClock.elapsedRealtime()
            val frozen = priorLookup ?: runBlocking {
                report.put("explicitLookupCalls", 1); save()
                withTimeout(45_000) { background.lookup(chosen.query, refresh = false) }
            }
            lookup = frozen
            BackgroundResearch.validateLookup(frozen)
            check(frozen.query == chosen.query)
            check(runBlocking { background.cached(chosen.query) } == frozen)
            report.put("lookupElapsedMillis", SystemClock.elapsedRealtime() - retrievalStarted)
                .put("lookupId", frozen.id).put("retrievalStatus", frozen.status).put("evidencePersistedBeforeExplanation", true)
                .put("providerOutcomes", JSONArray(frozen.providerOutcomes.map { JSONObject().put("provider", it.provider).put("status", it.status) }))
            if (frozen.sources.isEmpty()) stop("No usable evidence was retrieved; the saved lookup state is retained and no explanation was requested", "unavailable")
            val official = frozen.sources.singleOrNull { it.provider == chosen.provider }
                ?: stop("The required official source is unavailable; other retrieved evidence is retained without generating an explanation", "unavailable")
            val candidate = frozen.candidates.singleOrNull { official.id in it.sourceIds }
                ?: stop("No unambiguous official concept candidate was returned; evidence is retained", "unavailable")
            candidateId = candidate.id
            val selected = BackgroundResearch.selectedSources(frozen, candidate.id)
            check(selected.any { it.url == official.url && it.contentHash == fingerprint(it.excerpt) })
            report.put("officialCandidateSelected", true).put("selectedProvider", official.provider)
                .put("selectedEvidenceCount", selected.size).put("officialEvidenceHash", official.contentHash)
            val cachedExplanation = runBlocking { background.cachedExplanation(frozen.id, candidate.id) }
            val explanation = if (cachedExplanation != null) {
                report.put("explanationOutcome", "existing_explanation_reused")
                cachedExplanation
            } else {
                if (BackgroundResearch.expired(frozen, System.currentTimeMillis()))
                    stop("The saved evidence is stale; no automatic refresh or explanation was attempted", "refresh_required")
                val jobId = explanationJobId(frozen, candidate.id)
                if (jobId in jobs || preference(database, explanationKey(frozen, candidate.id)) != null)
                    stop("This explanation has prior work or an unusable cache; the live trial will not replay it", "retry_required")
                if (!stagedKey.isFile) stop("The private staged API key file is unavailable")
                settingsChanged = true
                runBlocking { repository.updateSettings(saved.settings.copy(titleClassification = false,
                    reader = saved.settings.reader.copy(automaticStudy = false))) }
                check(!runBlocking { repository.hasApiKey() })
                val key = stagedKey.inputStream().use(::readApiKeyInput)
                temporaryKeyInstalled = true
                runBlocking { repository.setApiKey(key) }
                check(runBlocking { repository.hasApiKey() }); check(!stagedKey.exists() || stagedKey.delete())
                report.put("temporaryEncryptedKeyImported", true).put("explicitExplanationCalls", 1); save()
                val started = SystemClock.elapsedRealtime()
                try {
                    runBlocking { withTimeout(210_000) { background.explain(frozen.id, candidate.id, explicitRetry = false) } }
                        .also { report.put("explanationOutcome", "new_explanation_generated") }
                } finally { report.put("explanationElapsedMillis", SystemClock.elapsedRealtime() - started) }
            }
            check(explanation.kind == "background" && explanation.retrievalStatus == "retrieved" && explanation.sources == selected)
            check(explanation.model != "retrieval-only" && explanation.citedSourceIds.isNotEmpty())
            check(runBlocking { background.cachedExplanation(frozen.id, candidate.id) } == explanation)
            check(runBlocking { background.cached(chosen.query) } == frozen)
            report.put("frozenEvidenceUnchangedAfterExplanation", true).put("explanationCacheMatches", true)
                .put("citedSourceIds", JSONArray(explanation.citedSourceIds)).put("status", "passed")
        } catch (skip: AssumptionViolatedException) { throw skip }
        catch (failure: Throwable) {
            failed = true
            report.put("status", "failed").put("failureType", failure.javaClass.simpleName)
                .put("failureLocations", JSONArray(failure.stackTrace.take(12).map { "${it.fileName}:${it.lineNumber}" }))
            throw AssertionError("Background live integration failed; inspect its sanitized report")
        } finally {
            val keyCleanup = runCatching {
                if (temporaryKeyInstalled) runBlocking { repository.clearApiKey() }
                check(!stagedKey.exists() || stagedKey.delete())
                before?.let { saved ->
                    if (temporaryKeyInstalled) restorePreference(database, "ai_paused", saved.pausedBytes)
                    check(!runBlocking { repository.hasApiKey() })
                    check(sameBytes(saved.pausedBytes, preference(database, "ai_paused")))
                }
            }
            val settingsCleanup = runCatching {
                before?.let { saved ->
                    if (settingsChanged) {
                        try { runBlocking { repository.updateSettings(saved.settings) } }
                        finally { restorePreference(database, "settings", saved.settingsBytes) }
                    }
                    check(sameBytes(saved.settingsBytes, preference(database, "settings")))
                }
            }
            val preserved = runCatching {
                before?.let { saved ->
                    saved.tables.forEach { (table, original) -> check(original == rows(database, table)) }
                    val after = preferences(database)
                    check(saved.preferences.all { (key, original) -> sameBytes(original, after[key]) })
                    val chosen = requireNotNull(material)
                    val frozen = lookup ?: runBlocking { repository.reading?.backgrounds?.cached(chosen.query) }
                    val permitted = buildSet {
                        frozen?.let {
                            add("background:index:1:${BackgroundResearch.queryKey(chosen.query)}")
                            add("background:lookup:1:${it.id}")
                            candidateId?.let { candidate -> add(explanationKey(it, candidate)) }
                        }
                    }
                    val addedPreferences = after.keys - saved.preferences.keys
                    check(addedPreferences.all { it in permitted })
                    val afterJobs = jobRows(database)
                    check(saved.jobs.all { (id, row) -> afterJobs[id] == row })
                    val addedJobs = afterJobs.filterKeys { it !in saved.jobs }
                    check(addedJobs.size <= report.getInt("explicitExplanationCalls") && addedJobs.size <= 1)
                    addedJobs.forEach { (id, row) ->
                        val job = codec.decodeFromString<AiJob>(row.last()!!)
                        check(frozen != null && candidateId != null && id == explanationJobId(frozen, candidateId!!) &&
                            job.id == id && job.kind == "background_explanation" && job.entityId == frozen.id)
                    }
                    report.put("priorLearningArticlesBodiesSettingsAndJobsPreserved", true)
                        .put("newBackgroundCacheRowCount", addedPreferences.size).put("newExplanationJobCount", addedJobs.size)
                    if (frozen != null) report.put("evidenceRetainedAfterTrial", runBlocking { repository.reading?.backgrounds?.cached(chosen.query) } == frozen)
                }
            }
            report.put("temporaryKeyUsed", temporaryKeyInstalled).put("keyAndStagingCleanupPassed", keyCleanup.isSuccess)
                .put("settingsCleanupPassed", settingsCleanup.isSuccess).put("preservationPassed", preserved.isSuccess)
            if (keyCleanup.isFailure || settingsCleanup.isFailure || preserved.isFailure) report.put("status", "failed").put("cleanupOrPreservationFailure", true)
            save()
            if (!failed && (keyCleanup.isFailure || settingsCleanup.isFailure || preserved.isFailure))
                throw AssertionError("Background live trial cleanup or preservation failed; inspect its sanitized report")
        }
    }

    private fun findMaterial(file: File, articles: List<Article>, sources: List<Source>): Material? {
        val excluded = Regex("(?i)fixture|synthetic|self-authored|trial|test-source|测试")
        val concept = Regex("\\b(?:electoral\\s+college|FOMC)\\b", RegexOption.IGNORE_CASE)
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db -> articles.firstNotNullOfOrNull { article ->
            val uri = Uri.parse(article.url)
            if (!article.read || article.readingMode != ReadingMode.NATIVE || uri.scheme != "https" || uri.host != "theconversation.com" ||
                excluded.containsMatchIn(article.id + " " + article.title + " " + article.sourceName) || sources.none { it.id in article.sourceIds && it.aiAllowed })
                return@firstNotNullOfOrNull null
            val body = db.rawQuery("SELECT data FROM article_bodies WHERE articleId=? AND expiresAt>?",
                arrayOf(article.id, System.currentTimeMillis().toString())).use {
                if (it.moveToFirst()) runCatching { codec.decodeFromString<ArticleBody>(it.getString(0)) }.getOrNull() else null
            } ?: return@firstNotNullOfOrNull null
            if (body.articleId != article.id || body.contentHash != fingerprint(body.text)) return@firstNotNullOfOrNull null
            val match = concept.find(body.text) ?: return@firstNotNullOfOrNull null
            val start = match.range.first; val end = match.range.last + 1
            val context = BackgroundResearch.contextAround(body.text, start, end)
            check(context.contains(match.value))
            val query = BackgroundResearch.normalize(BackgroundQuery(match.value, context, article.id, body.contentHash,
                SourceRef(article.title, article.sourceName, article.url, article.publishedAt), aiAllowed = true))
            Material(article, body, query, start, end, if (query.term.equals("FOMC", true)) "Federal Reserve Board" else "US National Archives")
        } }
    }
    private fun explanationKey(lookup: BackgroundLookup, candidateId: String) = "background:explanation:1:${lookup.id}:${fingerprint(candidateId)}"
    private fun explanationJobId(lookup: BackgroundLookup, candidateId: String) = "background-ai:${fingerprint(explanationKey(lookup, candidateId))}"
    private fun rows(file: File, table: String): List<List<String?>> = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT * FROM $table ORDER BY 1,2", null).use { cursor -> buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) })
        } }
    }
    private fun jobRows(file: File) = rows(file, "ai_jobs").associateBy { requireNotNull(it[0]) }
    private fun preferences(file: File): Map<String, ByteArray> = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT `key`,CAST(data AS BLOB) FROM preferences ORDER BY `key`", null).use { cursor -> buildMap {
            while (cursor.moveToNext()) put(cursor.getString(0), cursor.getBlob(1))
        } }
    }
    private fun preference(file: File, key: String) = preferences(file)[key]
    private fun sameBytes(a: ByteArray?, b: ByteArray?) = if (a == null) b == null else b != null && a.contentEquals(b)
    private fun restorePreference(file: File, key: String, bytes: ByteArray?) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            if (bytes == null) db.delete("preferences", "`key`=?", arrayOf(key))
            else db.compileStatement("INSERT OR REPLACE INTO preferences (`key`,data) VALUES (?,CAST(? AS TEXT))").use {
                it.bindString(1, key); it.bindBlob(2, bytes); check(it.executeInsert() != -1L)
            }
        }
    }
    private companion object {
        const val AUTHORED_SENTENCE = "In this self-authored practice sentence, the FOMC is mentioned while readers discuss how monetary policy decisions are made."
        val PROTECTED_TABLES = listOf("sources", "articles", "article_sources", "article_bodies", "captures", "cards", "review_logs", "local_events", "topic_cache")
    }
}
