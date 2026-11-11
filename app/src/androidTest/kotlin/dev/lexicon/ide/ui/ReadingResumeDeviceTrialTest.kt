package dev.lexicon.ide.ui

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import dev.lexicon.ide.LexiconApplication
import dev.lexicon.ide.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/** Two explicitly opted-in phases let the host force-stop the real app between writes and reads. */
@RunWith(AndroidJUnit4::class)
class ReadingResumeDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val articleId = "b2a264fb-a98a-3b0d-94e0-7485692115f4"
    @Test fun realOriginalContinuesAfterAFreshProcessWithoutNetworkOrKey() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Real continuation trial is opt-in", args.getString("lexiconReadingResumeTrial") == "1")
        val phase = requireNotNull(args.getString("phase")); require(phase in setOf("prepare", "verify", "cleanup"))
        val variant = requireNotNull(args.getString("variant")); require(variant in setOf("day-font100", "night-font200"))
        val scale = requireNotNull(args.getString("font")).toFloat()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiconApplication
        val repo = app.repository
        val db = app.getDatabasePath("lexicon.sqlite")
        val output = File(app.filesDir, "validation-1.13.0/reading-resume/$variant").apply { mkdirs() }
        val reportFile = File(output, "device-trial.json")
        val originalFile = File(output, "original-private.json")
        var scenario: ActivityScenario<MainActivity>? = null
        val report = if (phase == "prepare") JSONObject().put("status", "started").put("variant", variant)
            .put("material", "Existing The Conversation US original cached on this installation")
            .put("screenshots", JSONArray()).put("modelCallsRequested", 0).put("networkCallsRequested", 0)
            .put("startedAt", System.currentTimeMillis()).put("prepareProcessId", Process.myPid())
        else JSONObject(reportFile.readText())
        try {
            if (phase == "cleanup") { cleanup(db, originalFile, report); report.put("cleanupOnly", true); return }
            check(abs(app.resources.configuration.fontScale - scale) < .02f)
            check(app.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
            check(!runBlocking { repo.hasApiKey() })
            // The Application starts Room asynchronously. Wait for it before opening the
            // independent inspection connection; the host also compares a pre-startup snapshot.
            runBlocking { repo.initialize() }
            if (phase == "prepare") {
                check(!originalFile.exists()) { "Preserve any unfinished prior trial" }
                val suffix = MessageDigest.getInstance("SHA-256").digest(articleId.toByteArray()).joinToString("") { "%02x".format(it) }
                check(rows(db, "preferences").none { it[0] in setOf("reading-resume:record:$suffix", "reading-resume:generation:$suffix") })
                val original = JSONObject()
                TABLES.forEach { original.put(it, JSONArray(rows(db, it).map { row -> JSONArray(row) })) }
                originalFile.writeText(original.toString())
                report.put("originalSnapshotTaken", true)
            } else check(originalFile.exists()) { "Original data snapshot is required for preservation verification" }
            val resumes = requireNotNull(repo.reading?.resumes)
            val article = runBlocking { repo.articles.first { it.any { a -> a.id == articleId } } }.single { it.id == articleId }
            val body = codec.decodeFromString<ArticleBody>(requireNotNull(rows(db, "article_bodies").single { it[0] == articleId }[2]))
            check(article.read && article.sourceName == "The Conversation · US" && body.articleId == articleId)
            check(ParagraphFocus.paragraphs(body.text).size > 10)
            if (phase == "prepare") {
                check(runBlocking { resumes.library.first() }.records.none { it.articleId == articleId })
            }
            val settings = runBlocking { repo.settings.first() }
            runBlocking { repo.updateSettings(settings.copy(theme = if (variant.startsWith("night")) ThemeMode.DARK else ThemeMode.LIGHT,
                titleClassification = false, reader = settings.reader.copy(automaticStudy = false))) }
            report.put("trialSettingsHash", hash(requireNotNull(rows(db, "preferences").single { it[0] == "settings" }[1])))
            report.put("offlineActiveNetworkAbsent", true).put("installedKeyAbsent", true).put("articleId", articleId).put("bodyHash", body.contentHash)
            if (phase == "prepare") {
                scenario = ActivityScenario.launch(MainActivity::class.java)
                compose.waitUntil(20_000) { exists("daily_reading") }
                compose.onNodeWithTag("daily_reading").performScrollToNode(hasText("读过的文章"))
                compose.onNodeWithText("读过的文章").performClick()
                compose.onNodeWithTag("article_history_list").performScrollToNode(hasText(article.title))
                compose.onNodeWithText(article.title).performClick()
                compose.waitUntil(20_000) { exists("zen_article_text") }
                check(originalText() == body.text)
                compose.onNodeWithText("精读 · 工具").performScrollTo().performClick()
                node("reader_tab_略读").performClick()
                node("skim_budget_10").performClick()
                compose.onNodeWithText("开始略读").reachReadingTool().performClick()
                compose.onNodeWithText("略读 · 工具").performScrollTo().performClick()
                node("reader_quiet").performClick()
                scrollToParagraph(body, 8)
                compose.waitUntil(15_000) { runBlocking { resumes.library.first() }.records.any {
                    it.articleId == articleId && it.paragraphIndex in 7..10 && it.controls.mode == StudyMode.SKIM &&
                        it.controls.quiet && it.controls.budgetMinutes == 10
                } }
                val saved = runBlocking { resumes.library.first() }.records.single { it.articleId == articleId }
                report.put("expectedRecord", JSONObject(codec.encodeToString(saved)))
                assertParagraphVisible(body, saved.paragraphIndex, scale, report)
                screenshot(output, report, "01-reading-before-close.png")
                scenario.close(); scenario = null
                SystemClock.sleep(800)
                val latest = runBlocking { resumes.library.first() }.records.single { it.articleId == articleId }
                check(latest.paragraphIndex == saved.paragraphIndex && latest.controls == saved.controls)
                report.put("prepared", true).put("status", "prepared")
            } else {
                check(report.getBoolean("prepared"))
                check(Process.myPid() != report.getInt("prepareProcessId")) { "A distinct app process is required" }
                report.put("verifyProcessId", Process.myPid()).put("freshProcessVerified", true)
                val expected = codec.decodeFromString<ReadingResumeRecord>(report.getJSONObject("expectedRecord").toString())
                val durable = runBlocking { resumes.library.first() }.records.single { it.articleId == articleId }
                check(durable.paragraphIndex == expected.paragraphIndex && durable.controls == expected.controls)
                scenario = ActivityScenario.launch(MainActivity::class.java)
                compose.waitUntil(20_000) { exists("daily_continue") }
                compose.onNodeWithTag("daily_continue_title").assertTextEquals(article.title)
                screenshot(output, report, "02-home-continuation.png")
                node("daily_continue").performClick()
                compose.waitUntil(20_000) { exists("zen_article_text") }
                compose.waitUntil(15_000) { paragraphVisible(body, expected.paragraphIndex) }
                check(originalText() == body.text)
                assertParagraphVisible(body, expected.paragraphIndex, scale, report)
                screenshot(output, report, "03-restored-original-position.png")
                val after = runBlocking { resumes.library.first() }.records.single { it.articleId == articleId }
                check(after.paragraphIndex == expected.paragraphIndex && after.controls == expected.controls)
                scenario.recreate()
                compose.waitUntil(20_000) { paragraphVisible(body, expected.paragraphIndex) }
                check(originalText() == body.text)
                report.put("activityRecreationRestored", true)
                compose.onNodeWithText("略读 · 工具").performScrollTo().performClick()
                node("skim_budget_10").assertIsSelected()
                node("skim_role_全部").assertIsSelected()
                node("reader_quiet").assertTextContains("显示辅助")
                screenshot(output, report, "04-restored-controls.png")
                report.put("explicitControlsRestored", true).put("originalParagraphRestored", true)
                    .put("actualFontScale", scale).put("status", "passed")
            }
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failureType", failure.javaClass.simpleName)
                .put("failureLocations", JSONArray(failure.stackTrace.take(14).map { "${it.fileName}:${it.lineNumber}" }))
            runCatching { screenshot(output, report, "failure-$phase.png") }
            throw AssertionError("Reading continuation trial failed; inspect sanitized report")
        } finally {
            scenario?.close()
            SystemClock.sleep(800)
            if (originalFile.exists() && phase != "cleanup") {
                val originalEvents = JSONObject(originalFile.readText()).getJSONArray("local_events")
                val oldIds = (0 until originalEvents.length()).map { originalEvents.getJSONArray(it).getString(0) }.toSet()
                val generated = rows(db, "local_events").filter { it[0] !in oldIds }
                val recorded = if (report.has("ownedEventRows")) report.getJSONArray("ownedEventRows") else JSONArray()
                val already = (0 until recorded.length()).map { recorded.getJSONArray(it).getString(0) }.toSet()
                generated.filter { it[0] !in already }.forEach { row ->
                    val event = codec.decodeFromString<LocalEvent>(requireNotNull(row[2]))
                    if (event.createdAt >= report.getLong("startedAt") && event.type in setOf("zen_opened", "zen_duration_seconds", "reading_session")) recorded.put(JSONArray(row))
                }
                report.put("ownedEventRows", recorded)
            }
            var cleanupFailed = false
            if (phase == "verify" || phase == "cleanup" || report.optString("status") == "failed") {
                SystemClock.sleep(800)
                val restored = runCatching {
                    if (originalFile.exists() || phase != "prepare") cleanup(db, originalFile, report)
                }
                if (restored.isFailure) {
                    cleanupFailed = true
                    report.put("status", "failed").put("cleanupFailure", true)
                }
            }
            reportFile.writeText(report.toString(2))
            check(!cleanupFailed) { "Reading continuation trial cleanup or preservation failed" }
        }
    }

    private fun cleanup(db: File, originalFile: File, report: JSONObject) {
        check(originalFile.exists()) { "Preservation snapshot is unavailable" }
        val original = JSONObject(originalFile.readText())
        val watermark = MessageDigest.getInstance("SHA-256").digest(articleId.toByteArray()).joinToString("") { "%02x".format(it) }
        val began = report.getLong("startedAt")
        val sessionIds = rows(db, "preferences").filter { it[0]?.startsWith("reading-library:session:") == true }
            .mapNotNull { row -> runCatching { codec.decodeFromString<ReadingSession>(requireNotNull(row[1])) }.getOrNull() }
            .filter { it.articleId == articleId && it.startedAt >= began }.map { it.id }.toSet()
        val capturedEvents = report.optJSONArray("ownedEventRows") ?: JSONArray()
        val exactEvents = (0 until capturedEvents.length()).associate { i -> capturedEvents.getJSONArray(i).let { row ->
            row.getString(0) to (0 until row.length()).map { j -> if (row.isNull(j)) null else row.getString(j) }
        } }
        SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.beginTransaction()
            try {
                for (table in TABLES) {
                    val saved = original.getJSONArray(table).let { all -> (0 until all.length()).map { i ->
                        all.getJSONArray(i).let { row -> (0 until row.length()).map { j -> if (row.isNull(j)) null else row.getString(j) } }
                    } }
                    val current = rows(database, table)
                    val oldIds = saved.map { it[0] }.toSet()
                    for (row in current.filter { it[0] !in oldIds }) {
                        val id = requireNotNull(row[0])
                        val owned = when (table) {
                            "preferences" -> id in setOf("reading-resume:record:$watermark", "reading-resume:generation:$watermark") ||
                                (id in setOf("reading-exposure:article:$articleId", "reading-exposure:body:${report.getString("bodyHash")}") &&
                                    row[1]?.toLongOrNull()?.let { it >= began && it <= System.currentTimeMillis() } == true) ||
                                (id.startsWith("reading-library:session:") && codec.decodeFromString<ReadingSession>(requireNotNull(row[1])).let {
                                    it.articleId == articleId && it.startedAt >= began
                                })
                            "local_events" -> codec.decodeFromString<LocalEvent>(requireNotNull(row[2])).let {
                                exactEvents[id] == row && it.createdAt >= began &&
                                    (it.type in setOf("zen_opened", "zen_duration_seconds") || (it.type == "reading_session" && id.removePrefix("reading-session:") in sessionIds))
                            }
                            else -> false
                        }
                        check(owned) { "Unexpected added row in $table" }
                        database.delete(table, "${if (table == "preferences") "`key`" else "id"}=?", arrayOf(id))
                    }
                    if (table == "preferences") saved.singleOrNull { it[0] == "settings" }?.let { row ->
                        val actual = requireNotNull(current.single { it[0] == "settings" }[1])
                        check(actual == row[1] || hash(actual) == report.optString("trialSettingsHash")) { "Settings changed outside this trial" }
                        database.compileStatement("UPDATE preferences SET data=? WHERE `key`='settings'").use { it.bindString(1, requireNotNull(row[1])); it.executeUpdateDelete() }
                    }
                }
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
        }
        TABLES.forEach { table ->
            val old = original.getJSONArray(table).let { all -> (0 until all.length()).map { i ->
                all.getJSONArray(i).let { row -> (0 until row.length()).map { j -> if (row.isNull(j)) null else row.getString(j) } }
            } }
            check(old == rows(db, table)) { "Original $table rows changed" }
        }
        report.put("originalDataPreserved", true)
    }
    private fun node(tag: String) = compose.onNodeWithTag(tag).reachReadingTool()
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun originalText() = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun layout(): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("zen_article_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }
    private fun scrollToParagraph(body: ArticleBody, index: Int) {
        val text = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode()
        val viewport = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().boundsInRoot
        val box = layout().getBoundingBox(ParagraphFocus.paragraphs(body.text)[index].start)
        val delta = text.positionInRoot.y + box.top - viewport.top - viewport.height * .22f
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("zen_article_text")))
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
        compose.waitForIdle()
    }
    private fun paragraphVisible(body: ArticleBody, index: Int): Boolean = runCatching {
        val box = layout().getBoundingBox(ParagraphFocus.paragraphs(body.text)[index].start)
        val y = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().positionInRoot.y
        val viewport = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().boundsInRoot
        y + box.top >= viewport.top && y + box.bottom <= viewport.bottom
    }.getOrDefault(false)
    private fun assertParagraphVisible(body: ArticleBody, index: Int, scale: Float, report: JSONObject) {
        check(paragraphVisible(body, index))
        val text = layout()
        check(abs(text.layoutInput.density.fontScale - scale) < .02f)
        check(!text.didOverflowHeight && (0 until text.lineCount).all { text.getLineRight(it) <= text.size.width + 1 })
        report.put("paragraphIndex", index).put("originalTextLayoutNoClipping", true)
    }
    private fun screenshot(out: File, report: JSONObject, name: String) {
        compose.waitForIdle(); SystemClock.sleep(200)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
        report.getJSONArray("screenshots").put(name)
    }
    private fun rows(file: File, table: String): List<List<String?>> = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        rows(db, table)
    }
    private fun rows(db: SQLiteDatabase, table: String): List<List<String?>> =
        db.rawQuery("SELECT * FROM $table ORDER BY 1", null).use { cursor -> buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) })
        } }
    private companion object { val TABLES = listOf("sources", "articles", "article_sources", "article_bodies", "captures", "cards", "review_logs", "ai_jobs", "local_events", "preferences", "topic_cache") }
}
