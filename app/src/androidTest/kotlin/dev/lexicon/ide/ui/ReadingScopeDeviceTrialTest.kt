package dev.lexicon.ide.ui

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
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
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Disabled unless lexiconReadingScopeTrial=1. Reads existing publisher caches only; never requests AI.
 * lexiconScopeTheme=day|night; lexiconScopeFontScale=1|2 asserts the actual system setting (does not set it).
 * lexiconScopeCachedSwitch=1 additionally exercises an already-cached partial study and Activity.recreate.
 */
@RunWith(AndroidJUnit4::class)
class ReadingScopeDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val codec = Json { ignoreUnknownKeys = true }
    private data class Material(val article: Article, val body: ArticleBody, val full: ArticleStudy, val partial: ArticleStudy?)
    private data class Snapshot(val settings: AppSettings, val settingsBytes: ByteArray?, val profileBytes: ByteArray?,
        val tables: Map<String, Map<String, ByteArray>>, val learning: Map<String, ByteArray>,
        val sessions: Map<String, ByteArray>, val events: Map<String, ByteArray>)

    @Test fun installedPublisherScopePreviewPreservesOriginalAndAllLearningData() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Real reading scope trial requires explicit opt-in", arguments.getString("lexiconReadingScopeTrial") == "1")
        val theme = when (arguments.getString("lexiconScopeTheme") ?: "day") {
            "day" -> ThemeMode.LIGHT
            "night" -> ThemeMode.DARK
            else -> throw IllegalArgumentException("lexiconScopeTheme must be day or night")
        }
        val expectedScale = when (arguments.getString("lexiconScopeFontScale") ?: "1") {
            "1", "1.0" -> 1f
            "2", "2.0" -> 2f
            else -> throw IllegalArgumentException("lexiconScopeFontScale must be 1 or 2")
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as LexiconApplication
        val repository = app.repository
        val variant = "${if (theme == ThemeMode.DARK) "night" else "day"}-font${(expectedScale * 100).toInt()}"
        val output = File(app.filesDir, "validation-1.10.0/reading-scope-trial/$variant").apply { mkdirs() }
        val report = JSONObject().put("status", "started").put("automatedInteractionTrial", true)
            .put("aiActionInvocations", 0).put("networkTrafficMeasured", false).put("humanReadingPerformanceMeasured", false)
            .put("variant", variant).put("expectedFontScale", expectedScale).put("screenshots", JSONArray())
            .put("untestedPaths", JSONArray())
        fun save() { File(output, "device-trial.json").writeText(report.toString(2)) }
        fun skip(reason: String): Nothing {
            report.put("status", "skipped").put("reason", reason); save()
            assumeTrue(reason, false); error("Skipped")
        }
        val database = app.getDatabasePath("lexicon.sqlite")
        var snapshot: Snapshot? = null
        var material: Material? = null
        var activity: ActivityScenario<MainActivity>? = null
        var settingsChanged = false
        var failed = false
        val startedAt = System.currentTimeMillis()
        save()
        try {
            if (abs(app.resources.configuration.fontScale - expectedScale) > .02f)
                skip("Actual system font scale does not match the requested trial variant")
            if (runBlocking { repository.hasApiKey() }) skip("This browsing-only trial requires no installed API key")
            runBlocking { repository.initialize() }
            if (!database.isFile) skip("The installed database is unavailable")
            if (rows(database, "ai_jobs").values.any { codec.decodeFromString<AiJob>(it.toString(Charsets.UTF_8)).status == "running" })
                skip("Existing AI work is running; the trial will not overlap it")
            val reading = repository.reading ?: skip("The reading repository is unavailable")
            val candidate = materials(database, runBlocking { repository.articles.first() }, reading)
                .sortedByDescending { arguments.getString("lexiconScopeCachedSwitch") == "1" && it.partial != null }
                .firstOrNull() ?: skip("No already-read The Conversation article has an unexpired original, full study, and prior exposure records")
            material = candidate
            snapshot = Snapshot(runBlocking { repository.settings.first() }, preference(database, "settings"),
                preference(database, "personalization:profile"), protectedTables.associateWith { rows(database, it) },
                learningRows(database), sessionRows(database), rows(database, "local_events"))
            val saved = requireNotNull(snapshot)
            report.put("articleId", candidate.article.id).put("bodyHash", candidate.body.contentHash)
                .put("publisherHost", "theconversation.com").put("partialCacheAvailable", candidate.partial != null)
                .put("initializedBeforeSnapshot", true)
            settingsChanged = true
            runBlocking { repository.updateSettings(saved.settings.copy(theme = theme, titleClassification = false,
                reader = saved.settings.reader.copy(automaticStudy = false))) }
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            activity = scenario
            compose.waitUntil(20_000) { exists("daily_reading") }
            openArticle(candidate)
            val focused = openTools()
            node("study_scope_toggle").assertTextContains("全文", substring = true).performClick()
            val expected = requireNotNull(ReadingStudyScopes.defaultScope(candidate.body, focused, 3))
            assertEquals("The default preview must cover three complete paragraphs", 3, expected.endParagraphExclusive - expected.firstParagraph)
            assertTrue("The start field must use the current original paragraph", editableText("study_scope_first") == (expected.firstParagraph + 1).toString())
            assertTrue("The end field must use the proposed third paragraph", editableText("study_scope_last") == expected.endParagraphExclusive.toString())
            assertTrue("The range count must use the exact original substring",
                text("study_scope_count") == "${ReadingStudyScopes.label(expected)} · 3 段 · ${expected.endExclusive - expected.start} 字符")
            val paragraphs = ParagraphFocus.paragraphs(candidate.body.text)
            val first = paragraphs[expected.firstParagraph]
            val last = paragraphs[expected.endParagraphExclusive - 1]
            assertTrue("The beginning preview must match the frozen original",
                text("study_scope_preview_first") == candidate.body.text.substring(first.start, first.endExclusive))
            assertTrue("The ending preview must match the frozen original",
                text("study_scope_preview_last") == candidate.body.text.substring(last.start, last.endExclusive))
            node("study_scope_analyze").assertIsDisplayed() // Deliberately never clicked, regardless of source permission.
            report.put("exactThreeParagraphPreview", true)
            node("study_scope_count")
            verifyRealLayout(scenario, expectedScale, report)
            screenshot(output, report, "01-original-three-paragraph-preview.png")

            node("study_scope_first").performTextReplacement((paragraphs.size + 1).toString())
            node("study_scope_last").performTextReplacement((paragraphs.size + 1).toString())
            dismissKeyboardIfVisible()
            node("study_scope_error").assertTextEquals("段落范围为 1–${paragraphs.size}。")
            node("study_scope_analyze").assertIsNotEnabled()
            screenshot(output, report, "02-out-of-range-disabled.png")
            node("study_scope_first").performTextReplacement("3")
            node("study_scope_last").performTextReplacement("1")
            dismissKeyboardIfVisible()
            node("study_scope_error").assertTextEquals("结束段落不能早于开始段落。")
            node("study_scope_analyze").assertIsNotEnabled()
            node("study_scope_current").performClick()
            report.put("invalidRangesDisabledWithoutClamping", true)

            if (arguments.getString("lexiconScopeCachedSwitch") == "1" && candidate.partial != null) {
                selectSaved(candidate.partial)
                node("study_scope_notice").assertTextContains(ReadingStudyScopes.label(candidate.partial.scope), substring = true)
                scenario.recreate()
                compose.waitUntil(20_000) { exists("zen_article_text") }
                if (!exists("study_scope_toggle")) openTools()
                compose.waitUntil(10_000) { exists("study_scope_notice") && textWithoutScroll("study_scope_notice").contains(ReadingStudyScopes.label(candidate.partial.scope)) }
                if (!exists("study_saved_${ReadingStudyScopes.identity(candidate.partial)}")) node("study_scope_toggle").performClick()
                node("study_saved_list").assertIsDisplayed()
                node("study_saved_${ReadingStudyScopes.identity(candidate.partial)}").assertIsSelected()
                screenshot(output, report, "03-restored-existing-partial-cache.png")
                selectSaved(candidate.full)
                node("study_scope_toggle").assertTextContains("全文", substring = true)
                report.put("existingPartialSwitchAndActivityRecreate", true)
            } else {
                report.put("existingPartialSwitchAndActivityRecreate", false)
                report.getJSONArray("untestedPaths").put(if (candidate.partial == null) "No real partial cache was available; no publisher result was seeded" else "Cached scope Activity recreation was not requested")
            }
            pressBack()
            compose.waitUntil(10_000) { !exists("study_scope_toggle") && exists("zen_article_text") }
            assertTrue("Scope preview must never replace or truncate the original", editorText() == candidate.body.text)
            screenshot(output, report, "04-returned-to-complete-original.png")
            assertProtected(database, saved)
            report.put("originalRemainsComplete", true).put("protectedRecordsUnchanged", true).put("status", "passed")
            report.getJSONArray("untestedPaths").put("Actual AI generation").put("Physical MagicOS frame timing").put("Predictive gesture cancellation")
            save()
        } catch (skipped: AssumptionViolatedException) { throw skipped }
        catch (failure: Throwable) {
            failed = true
            report.put("status", "failed").put("failureType", failure.javaClass.simpleName)
                .put("failureLocations", JSONArray(failure.stackTrace.take(12).map { "${it.fileName}:${it.lineNumber}" }))
            runCatching { screenshot(output, report, "failure.png") }; save()
            throw AssertionError("Reading scope device trial failed; inspect its sanitized report and screenshot")
        } finally {
            val close = runCatching { activity?.close() }
            val cleanup = runCatching {
                snapshot?.let { saved -> material?.let { selected ->
                    awaitSessionWrites(database)
                    cleanupOwnSessionsAndEvents(database, saved, selected.article.id, startedAt, System.currentTimeMillis(), report)
                } }
            }
            val restore = runCatching {
                if (settingsChanged) snapshot?.let { saved ->
                    try { runBlocking {
                        repository.updateSettings(saved.settings)
                        withTimeout(10_000) { repository.settings.first { it == saved.settings } }
                    } } finally {
                        restorePreference(database, "settings", saved.settingsBytes)
                        restorePreference(database, "personalization:profile", saved.profileBytes)
                    }
                    check(sameBytes(saved.settingsBytes, preference(database, "settings")))
                    check(sameBytes(saved.profileBytes, preference(database, "personalization:profile")))
                }
            }
            val preserved = runCatching {
                snapshot?.let { assertProtected(database, it); check(!runBlocking { repository.hasApiKey() }) }
            }
            report.put("activityClosed", close.isSuccess).put("trialSessionsAndEventsCleaned", cleanup.isSuccess)
                .put("preferencesRestored", restore.isSuccess).put("protectedRecordsPreservedAfterCleanup", preserved.isSuccess)
            val restored = close.isSuccess && cleanup.isSuccess && restore.isSuccess && preserved.isSuccess
            if (!restored) report.put("status", "failed").put("restorationFailure", true)
            save()
            if (!failed && !restored) throw AssertionError("Reading scope trial cleanup failed; inspect its sanitized report")
        }
    }

    private fun openArticle(material: Material) {
        compose.onNodeWithTag("daily_reading").performScrollToNode(hasText("读过的文章"))
        compose.onNodeWithText("读过的文章").performClick()
        compose.waitUntil(10_000) { exists("article_history_list") }
        compose.onNodeWithTag("article_history_list").performScrollToNode(hasText(material.article.title))
        compose.onNodeWithText(material.article.title).performClick()
        compose.waitUntil(15_000) { exists("zen_article_text") }
        assertTrue("The reader must use its existing publisher body cache", editorText() == material.body.text)
    }
    private fun openTools(): Int {
        compose.onNodeWithText("精读 · 工具").performScrollTo()
        compose.waitUntil(10_000) { compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().config[FocusedParagraphIndex] == 0 }
        val focused = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().config[FocusedParagraphIndex]
        compose.onNodeWithText("精读 · 工具").performClick()
        compose.waitUntil(10_000) { exists("study_scope_toggle") }
        return focused
    }
    private fun selectSaved(study: ArticleStudy) {
        val tag = "study_saved_${ReadingStudyScopes.identity(study)}"
        if (!exists(tag)) node("study_scope_toggle").performClick()
        node("study_saved_list").assertIsDisplayed()
        node(tag).assertIsDisplayed().performClick()
        node(tag).assertIsSelected()
    }
    private fun verifyRealLayout(activity: ActivityScenario<MainActivity>, expectedScale: Float, report: JSONObject) {
        var windowWidth = 0
        var actualScale = 0f
        activity.onActivity { windowWidth = it.windowManager.currentWindowMetrics.bounds.width(); actualScale = it.resources.configuration.fontScale }
        val layouts = mutableListOf<TextLayoutResult>()
        node("study_scope_count").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val sheet = compose.onNodeWithTag("reader_tools_sheet").fetchSemanticsNode().boundsInRoot
        assertTrue("The real dialog must use the requested system font scale", abs(actualScale - expectedScale) < .02f && abs(layout.layoutInput.density.fontScale - expectedScale) < .02f)
        assertFalse("The range label must not clip vertically", layout.didOverflowHeight)
        assertTrue("Every visible label line must fit horizontally", (0 until layout.lineCount).all { layout.getLineLeft(it) >= -1 && layout.getLineRight(it) <= layout.size.width + 1 })
        assertTrue("The phone trial requires a full-width bottom sheet", abs(sheet.width - windowWidth) <= 2f)
        report.put("systemFontScale", actualScale).put("actualDialogFontScale", layout.layoutInput.density.fontScale)
            .put("sheetWidthPixels", sheet.width).put("windowWidthPixels", windowWidth).put("rangeLabelLineCount", layout.lineCount)
    }
    private fun node(tag: String) = compose.onNodeWithTag(tag).reachReadingTool()
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun editorText() = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun editableText(tag: String) = node(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun text(tag: String): String { node(tag); return textWithoutScroll(tag) }
    private fun textWithoutScroll(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("\n") { it.text }
    private fun pressBack() = InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    private fun dismissKeyboardIfVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var visible = false
        instrumentation.runOnMainSync {
            visible = android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                it.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
            }
        }
        // Send Back only when the input method is actually visible, preserving the sheet otherwise.
        if (visible) pressBack()
    }
    private fun screenshot(output: File, report: JSONObject, name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync(); SystemClock.sleep(150)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(output, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        report.getJSONArray("screenshots").put(name)
        File(output, "device-trial.json").writeText(report.toString(2))
    }
    private fun materials(database: File, articles: List<Article>, reading: ReadingRepository): List<Material> {
        val excluded = Regex("(?i)fixture|synthetic|self-authored|trial|example\\.(org|com)|test-source|测试")
        return articles.mapNotNull { article ->
            val uri = Uri.parse(article.url)
            if (!article.read || article.readingMode != ReadingMode.NATIVE || uri.scheme != "https" || uri.host != "theconversation.com" ||
                excluded.containsMatchIn(article.id + " " + article.title + " " + article.sourceName)) return@mapNotNull null
            val body = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT data FROM article_bodies WHERE articleId=? AND expiresAt>?", arrayOf(article.id, (System.currentTimeMillis() + 600_000).toString())).use {
                    if (it.moveToFirst()) runCatching { codec.decodeFromString<ArticleBody>(it.getString(0)) }.getOrNull() else null
                }
            } ?: return@mapNotNull null
            if (body.articleId != article.id || !ReadingStudyScopes.supportsFull(body) || ParagraphFocus.paragraphs(body.text).size < 3 || body.text.length < 400) return@mapNotNull null
            if (preference(database, "reading-exposure:article:${article.id}") == null || preference(database, "reading-exposure:body:${body.contentHash}") == null) return@mapNotNull null
            val cached = runBlocking { reading.cachedStudies(article.id, body.contentHash) }
                .filter { runCatching { ReadingStudyScopes.validateStudy(it, body) }.isSuccess }
            val full = cached.firstOrNull { it.scope == null } ?: return@mapNotNull null
            Material(article, body, full, cached.firstOrNull { it.scope != null })
        }
    }
    private fun assertProtected(database: File, before: Snapshot) {
        before.tables.forEach { (table, values) -> assertTrue("Existing $table rows must remain exactly unchanged", sameRows(values, rows(database, table))) }
        assertTrue("Existing study caches and learning records must remain unchanged", sameRows(before.learning, learningRows(database)))
    }
    private fun rows(database: File, table: String): Map<String, ByteArray> = queryRows(database,
        "SELECT ${if (table == "article_bodies") "articleId" else "id"},CAST(data AS BLOB) FROM $table")
    private fun learningRows(database: File) = queryRows(database,
        "SELECT `key`,CAST(data AS BLOB) FROM preferences WHERE `key` NOT IN ('settings','personalization:profile') AND `key` NOT LIKE 'reading-library:session:%' AND `key` NOT LIKE 'reading-library:edition:%'")
    private fun sessionRows(database: File) = queryRows(database, "SELECT `key`,CAST(data AS BLOB) FROM preferences WHERE `key` LIKE 'reading-library:session:%'")
    private fun queryRows(database: File, sql: String): Map<String, ByteArray> = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery(sql, emptyArray<String>()).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getBlob(1)) } }
    }
    private fun preference(database: File, key: String): ByteArray? = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT CAST(data AS BLOB) FROM preferences WHERE `key`=?", arrayOf(key)).use { if (it.moveToFirst()) it.getBlob(0) else null }
    }
    private fun restorePreference(database: File, key: String, value: ByteArray?) {
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            if (value == null) db.delete("preferences", "`key`=?", arrayOf(key))
            else db.compileStatement("INSERT OR REPLACE INTO preferences (`key`,data) VALUES (?,CAST(? AS TEXT))").use {
                it.bindString(1, key); it.bindBlob(2, value); check(it.executeInsert() != -1L)
            }
        }
    }
    private fun sameBytes(a: ByteArray?, b: ByteArray?) = if (a == null) b == null else b != null && a.contentEquals(b)
    private fun sameRows(a: Map<String, ByteArray>, b: Map<String, ByteArray>) = a.keys == b.keys && a.all { (key, value) -> sameBytes(value, b[key]) }
    private fun awaitSessionWrites(database: File) = runBlocking {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var previous = sessionRows(database); var stableSince = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(150)
            val current = sessionRows(database)
            if (!sameRows(previous, current)) { previous = current; stableSince = SystemClock.elapsedRealtime() }
            if (SystemClock.elapsedRealtime() - stableSince >= 900) return@runBlocking
        }
        error("Reading session writes did not settle")
    }
    private fun cleanupOwnSessionsAndEvents(database: File, before: Snapshot, articleId: String, start: Long, end: Long, report: JSONObject) {
        val after = sessionRows(database)
        check(before.sessions.all { (key, bytes) -> sameBytes(bytes, after[key]) })
        val added = after.filterKeys { it !in before.sessions }
        val ownedIds = added.map { (key, bytes) ->
            val session = codec.decodeFromString<ReadingSession>(bytes.toString(Charsets.UTF_8))
            check(key == "reading-library:session:${session.id}" && session.articleId == articleId && session.startedAt in start..end &&
                session.finishedAt <= end + 1_000 && !session.completed)
            session.id
        }.toSet()
        val finalEvents = rows(database, "local_events")
        check(before.events.all { (key, bytes) -> sameBytes(bytes, finalEvents[key]) })
        val addedEvents = finalEvents.filterKeys { it !in before.events }
        addedEvents.forEach { (id, bytes) ->
            val event = codec.decodeFromString<LocalEvent>(bytes.toString(Charsets.UTF_8))
            check(event.id == id && event.createdAt in start..(end + 1_000) &&
                (id in ownedIds.map { "reading-session:$it" } || event.type in setOf("zen_opened", "zen_duration_seconds")))
        }
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                added.forEach { (key, bytes) ->
                    val current = db.rawQuery("SELECT CAST(data AS BLOB) FROM preferences WHERE `key`=?", arrayOf(key)).use { if (it.moveToFirst()) it.getBlob(0) else null }
                    check(sameBytes(bytes, current)); check(db.delete("preferences", "`key`=?", arrayOf(key)) == 1)
                }
                addedEvents.forEach { (id, bytes) ->
                    val current = db.rawQuery("SELECT CAST(data AS BLOB) FROM local_events WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getBlob(0) else null }
                    check(sameBytes(bytes, current)); check(db.delete("local_events", "id=?", arrayOf(id)) == 1)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        check(sameRows(before.sessions, sessionRows(database))); check(sameRows(before.events, rows(database, "local_events")))
        report.put("removedTrialSessionCount", added.size).put("removedTrialEventCount", addedEvents.size).put("priorReadingSessionsExactlyPreserved", true)
    }
    companion object { private val protectedTables = listOf("cards", "captures", "ai_jobs", "review_logs", "article_bodies") }
}
