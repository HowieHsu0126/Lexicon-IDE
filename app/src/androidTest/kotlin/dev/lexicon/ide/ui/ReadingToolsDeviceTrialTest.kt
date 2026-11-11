package dev.lexicon.ide.ui

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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

/** Opt-in real original reading trial; baseline and final use the same cached article. */
@RunWith(AndroidJUnit4::class)
class ReadingToolsDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val articleId = "b2a264fb-a98a-3b0d-94e0-7485692115f4"
    @Test fun commonToolsAndCompleteParagraphNavigationRemainReachable() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Real tools trial is opt-in", args.getString("lexiconReadingToolsTrial") == "1")
        val stage = requireNotNull(args.getString("stage")); require(stage in setOf("before", "after"))
        val variant = requireNotNull(args.getString("variant")); require(variant in setOf("day-font100", "night-font200"))
        val scale = requireNotNull(args.getString("font")).toFloat()
        val enhanced = stage == "after"
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiconApplication
        val repo = app.repository
        val db = app.getDatabasePath("lexicon.sqlite")
        val output = File(app.filesDir, "validation-1.14.0/reading-tools/$stage/$variant").apply { mkdirs() }
        val reportFile = File(output, "device-trial.json")
        val originalFile = File(output, "original-private.json")
        var scenario: ActivityScenario<MainActivity>? = null
        val installed = app.packageManager.getPackageInfo(app.packageName, 0)
        val report = JSONObject().put("status", "started").put("stage", stage).put("variant", variant)
            .put("version", installed.versionName).put("versionCode", installed.longVersionCode).put("screenshots", JSONArray())
            .put("material", "Previously cached The Conversation US original")
            .put("startedAt", System.currentTimeMillis()).put("modelCallsRequested", 0)
        try {
            check(abs(app.resources.configuration.fontScale - scale) < .02f)
            check(app.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
            check(!runBlocking { repo.hasApiKey() })
            runBlocking { repo.initialize() }
            check(!originalFile.exists()) { "Preserve existing trial evidence" }
            val suffix = hash(articleId)
            check(rows(db, "preferences").none { it[0] in setOf("reading-resume:record:$suffix", "reading-resume:generation:$suffix") })
            val original = JSONObject()
            TABLES.forEach { original.put(it, JSONArray(rows(db, it).map { row -> JSONArray(row) })) }
            originalFile.writeText(original.toString())
            report.put("originalSnapshotTaken", true)
            val article = runBlocking { repo.articles.first { it.any { a -> a.id == articleId } } }.single { it.id == articleId }
            val body = codec.decodeFromString<ArticleBody>(requireNotNull(rows(db, "article_bodies").single { it[0] == articleId }[2]))
            check(article.read && article.sourceName == "The Conversation · US")
            check(runBlocking { requireNotNull(repo.reading).cachedStudies(articleId, body.contentHash) }.isEmpty()) {
                "This trial measures original-only planning; cached analysis must be absent"
            }
            val settings = runBlocking { repo.settings.first() }
            runBlocking { repo.updateSettings(settings.copy(theme = if (variant.startsWith("night")) ThemeMode.DARK else ThemeMode.LIGHT,
                titleClassification = false, reader = settings.reader.copy(automaticStudy = false, skimPlanningWpm = 160))) }
            report.put("trialSettingsHash", hash(requireNotNull(rows(db, "preferences").single { it[0] == "settings" }[1])))
            report.put("bodyHash", body.contentHash).put("articleId", articleId).put("originalParagraphCount", ParagraphFocus.paragraphs(body.text).size)
                .put("offlineActiveNetworkAbsent", true).put("installedKeyAbsent", true)
                .put("planningWpm", 160).put("cachedStudyCount", 0)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(20_000) { exists("daily_reading") }
            compose.onNodeWithTag("daily_reading").performScrollToNode(hasText("读过的文章"))
            compose.onNodeWithText("读过的文章").performClick()
            compose.onNodeWithTag("article_history_list").performScrollToNode(hasText(article.title))
            compose.onNodeWithText(article.title).performClick()
            compose.waitUntil(20_000) { exists("zen_article_text") }
            check(originalText() == body.text)
            compose.onNodeWithText("精读 · 工具").performScrollTo().performClick()
            reach("reader_tab_略读").performClick()
            reach("skim_budget_10").performClick()
            val contentTag = if (enhanced) "reader_tools_content_略读" else "reader_tools_sheet"
            val scroller = compose.onNodeWithTag(contentTag)
            val initialPosition = scrollPosition(contentTag)
            if (initialPosition > 0f) scroller.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -initialPosition) }
            compose.waitForIdle()
            report.put("contentPositionAfterResetPx", scrollPosition(contentTag))
            report.put("quietVisibleAtContentTop", displayed("reader_quiet"))
            report.put("tabsVisibleAtContentTop", displayed("reader_tab_略读"))
            report.put("initialScrollMaximumPx", scrollMaximum(contentTag))
            val before = scrollPosition(contentTag)
            screenshot(output, report, "01-tools-at-top.png")
            if (enhanced) {
                compose.onNodeWithTag("reader_quiet").assertIsDisplayed()
                compose.onNodeWithTag("reader_tools_primary").assertIsDisplayed()
                compose.onNodeWithTag("reader_tools_close").assertIsDisplayed()
            } else reach("reader_quiet")
            report.put("verticalScrollToQuietPx", scrollPosition(contentTag) - before)
            screenshot(output, report, "02-common-actions.png")
            if (enhanced) check(report.getDouble("verticalScrollToQuietPx") == 0.0)
            // Paging changes the directory only, never the source body or skim questions.
            val plan = ReadingSkimPlanner.plan(body.text, 10)
            val late = plan.paragraphIndices.last()
            report.put("lateDirectoryTarget", late).put("plannedParagraphCount", plan.paragraphIndices.size)
            if (enhanced) {
                reach("skim_directory_toggle").performClick()
                var pages = 0
                val visited = linkedSetOf<Int>()
                fun inspectPage() {
                    val indices = compose.onAllNodes(SemanticsMatcher("original directory entries") {
                        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("skim_rendered_paragraph_") == true
                    }).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag].substringAfterLast('_').toInt() }
                    check(indices.isNotEmpty() && indices.size <= SkimNavigation.PAGE_SIZE)
                    indices.forEach { index ->
                        reach("skim_rendered_paragraph_$index").assertIsDisplayed()
                        check(visited.add(index)) { "Repeated paragraph on another directory page" }
                    }
                }
                inspectPage()
                while (!exists("skim_rendered_paragraph_$late")) {
                    reach("skim_directory_next").assertIsEnabled().performClick()
                    check(++pages <= 100)
                    inspectPage()
                }
                check(visited == plan.paragraphIndices.toSet())
                report.put("nextPageActions", pages).put("reachableDirectoryParagraphs", JSONArray(visited.toList()))
                    .put("allPlannedParagraphsReachable", true)
            }
            reach("skim_rendered_paragraph_$late")
            report.put("tabsVisibleAtLateDirectory", displayed("reader_tab_略读"))
            report.put("quietVisibleAtLateDirectory", displayed("reader_quiet"))
            screenshot(output, report, "03-late-directory.png")
            if (enhanced) {
                compose.onNodeWithTag("reader_quiet").assertIsDisplayed()
                compose.onNodeWithTag("reader_tools_primary").assertIsDisplayed()
                compose.onNodeWithTag("reader_tab_略读").assertIsDisplayed()
                reach("reader_tab_导图").performClick()
                reach("reader_tab_略读").performClick()
                reach("skim_rendered_paragraph_$late").assertExists()
                scenario.recreate()
                compose.waitUntil(20_000) { exists("skim_rendered_paragraph_$late") }
                report.put("directoryPageSurvivesTabSwitchAndRecreation", true)
            }
            reach("skim_rendered_paragraph_$late").performClick()
            compose.waitUntil(15_000) { paragraphVisible(body, late) }
            check(originalText() == body.text)
            assertParagraphVisible(body, late, scale, report)
            screenshot(output, report, "04-exact-original-jump.png")
            report.put("originalDirectoryJumpVerified", true).put("status", "passed")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failureType", failure.javaClass.simpleName)
                .put("failureLocations", JSONArray(failure.stackTrace.take(14).map { "${it.fileName}:${it.lineNumber}" }))
            val bounds = JSONObject()
            listOf("reader_tools_sheet", "reader_tools_header", "reader_tools_footer", "reader_tools_content_略读").forEach { tag ->
                runCatching { compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot }.getOrNull()?.let {
                    bounds.put(tag, JSONObject().put("top", it.top).put("bottom", it.bottom).put("left", it.left).put("right", it.right))
                }
            }
            report.put("toolBoundsAtFailure", bounds)
            runCatching { screenshot(output, report, "failure.png") }
            throw AssertionError("Reading tools trial failed; inspect sanitized report")
        } finally {
            scenario?.close(); SystemClock.sleep(1000)
            var cleanupFailed = false
            if (originalFile.exists()) {
                val originalEvents = JSONObject(originalFile.readText()).getJSONArray("local_events")
                val oldIds = (0 until originalEvents.length()).map { originalEvents.getJSONArray(it).getString(0) }.toSet()
                val generated = rows(db, "local_events").filter { it[0] !in oldIds }
                val recorded = JSONArray()
                generated.forEach { row ->
                    val event = codec.decodeFromString<LocalEvent>(requireNotNull(row[2]))
                    if (event.createdAt >= report.getLong("startedAt") && event.type in setOf("zen_opened", "zen_duration_seconds", "reading_session")) recorded.put(JSONArray(row))
                }
                report.put("ownedEventRows", recorded)
                val restored = runCatching { cleanup(db, originalFile, report) }
                if (restored.isFailure) {
                    cleanupFailed = true; report.put("status", "failed").put("cleanupFailure", true)
                        .put("cleanupFailureLocations", JSONArray(restored.exceptionOrNull()!!.stackTrace.take(8).map { "${it.fileName}:${it.lineNumber}" }))
                }
            }
            reportFile.writeText(report.toString(2))
            check(!cleanupFailed) { "Trial preservation failed" }
        }
    }
    private fun reach(tag: String): SemanticsNodeInteraction {
        val node = compose.onNodeWithTag(tag)
        node.assertExists()
        if (!displayed(tag)) node.performScrollTo()
        return node
    }
    private fun displayed(tag: String): Boolean = runCatching { compose.onNodeWithTag(tag).assertIsDisplayed(); true }.getOrDefault(false)
    private fun scrollPosition(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun scrollMaximum(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
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
    private fun node(tag: String) = compose.onNodeWithTag(tag).performScrollTo()
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
