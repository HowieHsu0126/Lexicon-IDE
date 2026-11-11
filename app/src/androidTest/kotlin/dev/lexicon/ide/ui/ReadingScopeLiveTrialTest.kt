package dev.lexicon.ide.ui

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import dev.lexicon.ide.LexiconApplication
import java.io.File
import java.security.MessageDigest
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

/** One opt-in request against existing publisher material; default suites never import a key or call AI. */
@RunWith(AndroidJUnit4::class)
class ReadingScopeLiveTrialTest {
    private val codec = Json { ignoreUnknownKeys = true }
    private data class Material(val article: Article, val body: ArticleBody, val full: ArticleStudy, val scope: StudyScope)
    private data class Original(val settings: AppSettings, val settingsBytes: ByteArray?, val pausedBytes: ByteArray?,
        val protected: Map<String, String>, val jobs: Map<String, ByteArray>, val fullBytes: ByteArray?, val scopeBytes: ByteArray?)

    @Test fun threeExistingPublisherParagraphsGenerateOnceAndPreserveAllPriorLearning() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Scoped live trial requires both explicit flags",
            args.getString("lexiconScopeLive") == "1" && args.getString("lexiconPrivateApiFile") == "1")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiconApplication
        val repository = app.repository
        val database = app.getDatabasePath("lexicon.sqlite")
        val stagedKey = File(app.filesDir, "lexicon-trial-api-key")
        val output = File(app.filesDir, "validation-1.10.0/scoped-live").apply { mkdirs() }
        val report = JSONObject().put("status", "started").put("maximumExplicitScopeCalls", 1)
            .put("maximumInternalSchemaRepairs", 1).put("explicitScopeCalls", 0).put("httpRequestCountMeasured", false)
            .put("createsCards", false).put("interactionPerformanceMeasured", false)
        fun save() = File(output, "scoped-live.json").writeText(report.toString(2))
        fun skip(reason: String): Nothing {
            report.put("status", "skipped").put("reason", reason); save()
            assumeTrue(reason, false); error("Skipped")
        }
        var original: Original? = null
        var material: Material? = null
        var targetKey: String? = null
        var temporaryKeyInstalled = false
        var settingsChanged = false
        var failed = false
        save()
        try {
            runBlocking { repository.initialize() }
            if (!database.isFile) skip("The installed database is unavailable")
            if (runBlocking { repository.hasApiKey() }) skip("An installed API key exists; it will not be replaced")
            val reading = repository.reading ?: skip("Reading repository is unavailable")
            val jobs = jobRows(database)
            if (jobs.values.any { codec.decodeFromString<AiJob>(it.toString(Charsets.UTF_8)).status == "running" })
                skip("Existing AI work is running")
            val chosen = choose(database, runBlocking { repository.articles.first() },
                runBlocking { repository.sources.first() }, reading) ?: skip("No eligible opened Conversation body and full-study cache with three paragraphs")
            material = chosen
            val scopeKey = ReadingStudyScopes.cacheKey(chosen.article.id, chosen.body.contentHash, 2, chosen.scope)
            val fullKey = ReadingStudyScopes.cacheKey(chosen.article.id, chosen.body.contentHash, chosen.full.schemaVersion)
            val expectedJobId = "reading-ai:${fingerprint(scopeKey)}"
            targetKey = scopeKey
            val priorScope = runBlocking { reading.cachedStudies(chosen.article.id, chosen.body.contentHash) }
                .singleOrNull { it.scope == chosen.scope }
            val scopeBytes = preference(database, scopeKey)
            if (priorScope == null && (scopeBytes != null || expectedJobId in jobs))
                skip("This scope has prior unusable cache or work; the trial will not replay it")
            original = Original(runBlocking { repository.settings.first() }, preference(database, "settings"),
                preference(database, "ai_paused"), protectedRows(database, scopeKey), jobs,
                preference(database, fullKey), scopeBytes)
            val saved = requireNotNull(original)
            check(saved.fullBytes != null)
            report.put("articleId", chosen.article.id).put("publisherHost", "theconversation.com")
                .put("bodyHash", chosen.body.contentHash).put("fullStudyVersion", chosen.full.schemaVersion)
                .put("scope", scopeJson(chosen.scope)).put("cachedBefore", priorScope != null)
            val started = SystemClock.elapsedRealtime()
            val result = if (priorScope != null) {
                report.put("outcome", "existing_scope_reused")
                priorScope
            } else {
                if (!stagedKey.isFile) skip("The private staged key file is unavailable")
                settingsChanged = true
                runBlocking { repository.updateSettings(saved.settings.copy(titleClassification = false,
                    reader = saved.settings.reader.copy(automaticStudy = false))) }
                check(!runBlocking { repository.hasApiKey() })
                val key = stagedKey.inputStream().use(::readApiKeyInput)
                temporaryKeyInstalled = true
                runBlocking { repository.setApiKey(key) }
                check(runBlocking { repository.hasApiKey() })
                check(!stagedKey.exists() || stagedKey.delete())
                report.put("temporaryEncryptedKeyImported", true).put("explicitScopeCalls", 1); save()
                // One public call only. The service's bounded schema repair is not retried by this test.
                val requestStarted = SystemClock.elapsedRealtime()
                try {
                    runBlocking { withTimeout(210_000) { reading.studyRange(chosen.article, chosen.body, chosen.scope,
                        aiAllowed = true, explicitRetry = true) } }.also { report.put("outcome", "new_scope_generated") }
                } finally { report.put("explicitRequestElapsedMillis", SystemClock.elapsedRealtime() - requestStarted) }
            }
            report.put("elapsedMillis", SystemClock.elapsedRealtime() - started)
            check(result.scope == chosen.scope && result.schemaVersion == 2)
            ReadingStudyScopes.validateStudy(result, chosen.body)
            check(result.paragraphs.map { it.index } == listOf(0, 1, 2))
            val cached = runBlocking { reading.cachedStudies(chosen.article.id, chosen.body.contentHash) }
                .singleOrNull { it.scope == chosen.scope }
            check(cached == result)
            check(runBlocking { reading.cachedStudy(chosen.article.id, chosen.body.contentHash) } == chosen.full)
            check(sameBytes(saved.fullBytes, preference(database, fullKey)))
            if (saved.scopeBytes != null) check(sameBytes(saved.scopeBytes, preference(database, scopeKey)))
            report.put("cachedResultMatches", true).put("fullStudyBytesPreserved", true)
                .put("paragraphIndices", JSONArray(result.paragraphs.map { it.index }))
                .put("highlightRanges", JSONArray(result.highlights.map { JSONObject().put("start", it.start).put("endExclusive", it.end) }))
                .put("questionEvidenceRanges", JSONArray(result.questions.flatMap { it.evidence }.map {
                    JSONObject().put("paragraphIndex", it.paragraphIndex).put("start", it.start).put("endExclusive", it.endExclusive)
                })).put("globalRangesValidatedAgainstOriginal", true).put("status", "passed")
        } catch (skip: AssumptionViolatedException) { throw skip }
        catch (failure: Throwable) {
            failed = true
            report.put("status", "failed").put("failureType", failure.javaClass.simpleName)
                .put("failureLocations", JSONArray(failure.stackTrace.take(10).map { "${it.fileName}:${it.lineNumber}" }))
            // No exception message or cause is forwarded: either could contain text or a transport body.
            throw AssertionError("Scoped live trial failed; inspect its sanitized report")
        } finally {
            val restored = runCatching {
                if (temporaryKeyInstalled) runBlocking { repository.clearApiKey() }
                check(!stagedKey.exists() || stagedKey.delete())
                original?.let { saved ->
                    if (temporaryKeyInstalled) restorePreference(database, "ai_paused", saved.pausedBytes)
                    if (settingsChanged) {
                        try { runBlocking { repository.updateSettings(saved.settings) } }
                        finally { restorePreference(database, "settings", saved.settingsBytes) }
                    }
                    check(!runBlocking { repository.hasApiKey() })
                    check(sameBytes(saved.settingsBytes, preference(database, "settings")))
                    check(sameBytes(saved.pausedBytes, preference(database, "ai_paused")))
                }
            }
            report.put("temporaryKeyUsed", temporaryKeyInstalled).put("stagingRemoved", !stagedKey.exists())
                .put("temporaryKeyRemoved", restored.isSuccess && temporaryKeyInstalled)
                .put("originalSettingsAndPauseRestored", restored.isSuccess)
            val preserved = runCatching {
                original?.let { saved ->
                    val chosen = requireNotNull(material); val scopeKey = requireNotNull(targetKey)
                    check(saved.protected == protectedRows(database, scopeKey))
                    val finalJobs = jobRows(database)
                    check(saved.jobs.all { (id, bytes) -> sameBytes(bytes, finalJobs[id]) })
                    val added = finalJobs.filterKeys { it !in saved.jobs }
                    val expectedJobId = "reading-ai:${fingerprint(scopeKey)}"
                    check(added.size <= 1 && added.size <= report.getInt("explicitScopeCalls"))
                    check(added.all { (id, bytes) ->
                        val job = codec.decodeFromString<AiJob>(bytes.toString(Charsets.UTF_8))
                        id == expectedJobId && job.id == id && job.kind == "reading_study_range" && job.entityId == chosen.article.id
                    })
                    val fullKey = ReadingStudyScopes.cacheKey(chosen.article.id, chosen.body.contentHash, chosen.full.schemaVersion)
                    check(sameBytes(saved.fullBytes, preference(database, fullKey)))
                    report.put("newAiJobCount", added.size).put("priorAiJobBytesPreserved", true)
                        .put("allPriorLearningObjectsAndBodyPreserved", true).put("fullStudyBytesPreserved", true)
                }
            }
            if (restored.isFailure || preserved.isFailure) report.put("status", "failed").put("restorationOrPreservationFailure", true)
            save()
            if (!failed && (restored.isFailure || preserved.isFailure))
                throw AssertionError("Scoped live trial preservation or cleanup failed; inspect its sanitized report")
        }
    }

    private fun choose(database: File, articles: List<Article>, sources: List<Source>, reading: ReadingRepository): Material? {
        val excluded = Regex("(?i)fixture|synthetic|self-authored|trial|example\\.(org|com)|test-source|测试")
        val ordered = articles.sortedBy { if (it.id == "2aa62935-da9c-3ede-a8a6-658c6215fbeb") 0 else 1 }
        return SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db -> ordered.firstNotNullOfOrNull { article ->
            val uri = Uri.parse(article.url)
            if (!article.read || article.readingMode != ReadingMode.NATIVE || uri.scheme != "https" || uri.host != "theconversation.com" ||
                excluded.containsMatchIn(article.id + " " + article.title + " " + article.sourceName) ||
                sources.none { it.id in article.sourceIds && it.aiAllowed }) return@firstNotNullOfOrNull null
            val body = db.rawQuery("SELECT data FROM article_bodies WHERE articleId=? AND expiresAt>?",
                arrayOf(article.id, (System.currentTimeMillis() + 600_000).toString())).use { cursor ->
                if (cursor.moveToFirst()) runCatching { codec.decodeFromString<ArticleBody>(cursor.getString(0)) }.getOrNull() else null
            } ?: return@firstNotNullOfOrNull null
            if (body.articleId != article.id || body.text.length < 400 || body.contentHash != fingerprint(body.text)) return@firstNotNullOfOrNull null
            val scope = runCatching { ReadingStudyScopes.create(body, 0, 3) }.getOrNull() ?: return@firstNotNullOfOrNull null
            val full = runBlocking { reading.cachedStudy(article.id, body.contentHash) } ?: return@firstNotNullOfOrNull null
            if (full.scope != null) return@firstNotNullOfOrNull null
            Material(article, body, full, scope)
        } }
    }

    private fun scopeJson(scope: StudyScope) = JSONObject().put("firstParagraph", scope.firstParagraph)
        .put("endParagraphExclusive", scope.endParagraphExclusive).put("start", scope.start)
        .put("endExclusive", scope.endExclusive).put("totalParagraphs", scope.totalParagraphs).put("contentHash", scope.contentHash)
    private fun fingerprint(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun preference(database: File, key: String): ByteArray? =
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT CAST(data AS BLOB) FROM preferences WHERE `key`=?", arrayOf(key)).use { if (it.moveToFirst()) it.getBlob(0) else null }
        }
    private fun restorePreference(database: File, key: String, bytes: ByteArray?) {
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            if (bytes == null) db.delete("preferences", "`key`=?", arrayOf(key))
            else db.compileStatement("INSERT OR REPLACE INTO preferences (`key`,data) VALUES (?,CAST(? AS TEXT))").use {
                it.bindString(1, key); it.bindBlob(2, bytes); check(it.executeInsert() != -1L)
            }
        }
    }
    private fun jobRows(database: File): Map<String, ByteArray> =
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id,CAST(data AS BLOB) FROM ai_jobs ORDER BY id", emptyArray<String>()).use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getBlob(1)) }
            }
        }
    private fun sameBytes(a: ByteArray?, b: ByteArray?) = if (a == null) b == null else b != null && a.contentEquals(b)

    /** Hash every column in every protected row, including scheduling timestamps and raw JSON bytes. */
    private fun protectedRows(database: File, scopeKey: String): Map<String, String> =
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val tables = mapOf("sources" to "id", "articles" to "id", "article_sources" to "sourceId,guid",
                "article_bodies" to "articleId", "captures" to "id", "cards" to "id", "review_logs" to "id",
                "local_events" to "id", "topic_cache" to "titleHash")
            buildMap {
                tables.forEach { (table, order) -> put(table, db.rawQuery("SELECT * FROM $table ORDER BY $order", emptyArray<String>()).use(::cursorDigest)) }
                put("preferences", db.rawQuery("SELECT * FROM preferences WHERE `key` NOT IN (?,?,?) ORDER BY `key`",
                    arrayOf(scopeKey, "settings", "ai_paused")).use(::cursorDigest))
            }
        }
    private fun cursorDigest(cursor: Cursor): String {
        val digest = MessageDigest.getInstance("SHA-256")
        while (cursor.moveToNext()) {
            digest.update(0x7f.toByte())
            for (index in 0 until cursor.columnCount) {
                val type = cursor.getType(index)
                val bytes = when (type) {
                    Cursor.FIELD_TYPE_NULL -> byteArrayOf()
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
                    else -> cursor.getString(index).toByteArray(Charsets.UTF_8)
                }
                digest.update("$type:${bytes.size}:".toByteArray(Charsets.US_ASCII)); digest.update(bytes)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
