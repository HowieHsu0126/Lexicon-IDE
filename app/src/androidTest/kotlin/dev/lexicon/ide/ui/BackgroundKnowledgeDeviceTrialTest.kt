package dev.lexicon.ide.ui

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.SystemClock
import android.view.WindowInsets
import android.view.inspector.WindowInspector
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real installed-cache trial. Never retrieves public pages or calls a model. */
@RunWith(AndroidJUnit4::class)
class BackgroundKnowledgeDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val codec = Json { ignoreUnknownKeys = true }
    private val comment = "试用记录：将概念含义与文章立场分开核对。"
    private val personalTag = "背景回看试用"
    @Test fun existingBackgroundCanBeFoundKeptEditedExportedAndRemovedOffline() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Background knowledge trial requires explicit opt-in", args.getString("lexiconBackgroundKnowledgeTrial") == "1")
        val theme = if (args.getString("theme") == "night") ThemeMode.DARK else ThemeMode.LIGHT
        val scale = (args.getString("font") ?: "1").toFloat()
        require(scale == 1f || scale == 2f)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiconApplication
        val repo = app.repository
        val db = app.getDatabasePath("lexicon.sqlite")
        val variant = "${if (theme == ThemeMode.DARK) "night" else "day"}-font${(scale * 100).toInt()}"
        val output = File(app.filesDir, "validation-1.12.0/background-knowledge/$variant").apply { mkdirs() }
        val report = JSONObject().put("status", "started").put("automatedInteractionTrial", true)
            .put("material", "Existing 1.11 FOMC public-source lookup and explanation with explicitly self-authored context")
            .put("variant", variant).put("screenshots", JSONArray()).put("modelCallsRequested", 0).put("lookupCallsRequested", 0)
        fun save() = File(output, "device-trial.json").writeText(report.toString(2))
        fun skip(reason: String): Nothing { report.put("status", "skipped").put("reason", reason); save(); assumeTrue(reason, false); error(reason) }
        var scenario: ActivityScenario<MainActivity>? = null
        var settings: AppSettings? = null
        var before: Map<String, List<List<String?>>>? = null
        var ownedId: String? = null
        var expectedLookup: BackgroundLookup? = null
        var failed = false
        val began = System.currentTimeMillis()
        save()
        try {
            if (abs(app.resources.configuration.fontScale - scale) > .02f) skip("Actual system font scale differs")
            if (runBlocking { repo.hasApiKey() }) skip("An installed Key exists; this trial will not change it")
            if (app.getSystemService(ConnectivityManager::class.java).activeNetwork != null) skip("An active default network exists; offline evidence is required")
            runBlocking { repo.initialize() }
            val reading = repo.reading ?: skip("Reading repository is unavailable")
            val backgrounds = reading.backgrounds ?: skip("Background repository is unavailable")
            val lookup = runBlocking { backgrounds.recentLookups() }.firstOrNull { it.query.term == "FOMC" &&
                it.query.articleId == null && it.query.context.startsWith("In this self-authored practice sentence,") &&
                it.sources.any { source -> source.provider == "Federal Reserve Board" } } ?: skip("The real prior FOMC cache is unavailable")
            expectedLookup = lookup
            val source = lookup.sources.single { it.provider == "Federal Reserve Board" }
            val candidate = lookup.candidates.single { source.id in it.sourceIds }
            val explanation = runBlocking { backgrounds.cachedExplanation(lookup.id, candidate.id) } ?: skip("The prior live explanation is unavailable")
            if (runBlocking { backgrounds.savedRecord(lookup.id, candidate.id) } != null) skip("This background is already kept by the user")
            val proposedId = BackgroundRecords.id(lookup.id, candidate.id)
            if (rows(db, "preferences").any { it[0] == "background-record-revision:$proposedId" }) skip("An existing revision watermark belongs to the user")
            settings = runBlocking { repo.settings.first() }
            before = TABLES.associateWith { rows(db, it) }
            ownedId = BackgroundRecords.id(lookup.id, candidate.id)
            runBlocking { repo.updateSettings(settings!!.copy(theme = theme, titleClassification = false,
                reader = settings!!.reader.copy(automaticStudy = false))) }
            report.put("offlineActiveNetworkAbsent", true).put("lookupId", lookup.id).put("recordId", ownedId)
                .put("expectedExplanationModel", explanation.model)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            val activity = requireNotNull(scenario)
            openKnowledge()
            compose.onNodeWithTag("knowledge_tab_background").assertIsDisplayed().performClick()
            node("background_knowledge_search").performTextReplacement("FOMC")
            hideKeyboard(activity)
            node("background_knowledge_recent_tab").performClick()
            node("background_recent_${lookup.id}").assertIsDisplayed().performClick()
            compose.waitUntil(15_000) { exists("background_bound_term") }
            if (exists("background_candidate_${candidate.id}")) node("background_candidate_${candidate.id}").performClick()
            compose.waitUntil(10_000) { exists("background_explanation") }
            check(text("background_context_preview") == lookup.query.context)
            screenshot(output, report, "01-found-existing-background.png")
            node("background_save_record").assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(10_000) { runBlocking { backgrounds.savedRecord(lookup.id, candidate.id) } != null }
            val initial = requireNotNull(runBlocking { backgrounds.savedRecord(lookup.id, candidate.id) })
            check(initial.lookup == lookup && initial.explanation == explanation && initial.selectedCandidateId == candidate.id)
            node("background_continue_reading").performClick()
            node("background_knowledge_saved_tab").performClick()
            node("background_record_${initial.id}").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { exists("background_record_detail") }
            check(text("background_record_context") == lookup.query.context)
            check(text("background_record_explanation") == explanation.text)
            verifyLayout("background_record_explanation", scale, report)
            screenshot(output, report, "02-kept-cited-explanation.png")
            node("background_record_edit").performClick()
            node("background_record_comment").performTextReplacement(comment)
            node("background_record_tags").performTextReplacement(personalTag)
            hideKeyboard(activity)
            compose.onNodeWithTag("background_record_save").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { runBlocking { backgrounds.savedRecord(lookup.id, candidate.id) }?.comment == comment }
            val edited = requireNotNull(runBlocking { backgrounds.savedRecord(lookup.id, candidate.id) })
            check(edited.revision > initial.revision && edited.tags == listOf(personalTag))
            check(edited.lookup == lookup && edited.explanation == explanation)
            val export = File(output, "background-knowledge.md")
            runBlocking { reading.exportKnowledge(export) }
            val markdown = export.readText()
            check(markdown.contains(personalTag) && markdown.contains(comment) && markdown.contains(source.provider))
            check(markdown.contains(source.contentHash!!) && markdown.contains("AI") && markdown.contains("检索时语境"))
            report.put("repositoryMarkdownExportVerified", true).put("uiSaveAndEditVerified", true)
            node("background_record_close").performClick()
            activity.close(); scenario = null
            scenario = ActivityScenario.launch(MainActivity::class.java)
            openKnowledge()
            compose.onNodeWithTag("knowledge_tab_background").performClick()
            node("background_knowledge_search").performTextReplacement(personalTag)
            hideKeyboard(requireNotNull(scenario))
            node("background_knowledge_saved_tab").performClick()
            node("background_record_${initial.id}").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { exists("background_record_detail") }
            check(text("background_record_explanation") == explanation.text)
            screenshot(output, report, "03-reopened-from-personal-tag.png")
            report.put("reopenedAfterActivityCloseWithoutReconstructingQuery", true)
            node("background_record_delete").performClick()
            compose.onNodeWithTag("background_record_confirm_delete").performClick()
            compose.waitUntil(10_000) { runBlocking { backgrounds.savedRecord(lookup.id, candidate.id) } == null }
            check(runBlocking { backgrounds.readLookup(lookup.id) } == lookup)
            check(runBlocking { backgrounds.cachedExplanation(lookup.id, candidate.id) } == explanation)
            report.put("deleteKeepsOriginalCacheAndExplanation", true).put("status", "passed")
        } catch (stop: AssumptionViolatedException) { throw stop }
        catch (failure: Throwable) {
            failed = true
            report.put("status", "failed").put("failureType", failure.javaClass.simpleName)
                .put("failureLocations", JSONArray(failure.stackTrace.take(12).map { "${it.fileName}:${it.lineNumber}" }))
            runCatching { screenshot(output, report, "failure.png") }
            throw AssertionError("Background knowledge trial failed; inspect sanitized report")
        } finally {
            val cleanup = runCatching {
                scenario?.close()
                val lookup = expectedLookup
                val id = ownedId
                if (id != null && lookup != null && before != null) {
                    val current = rows(db, "preferences").singleOrNull { it[0] == "reading-library:background:$id" }
                    if (current != null) {
                        val record = codec.decodeFromString<BackgroundRecord>(requireNotNull(current[1]))
                        check(record.id == id && record.lookup == lookup && record.createdAt >= began)
                        runBlocking { repo.reading!!.backgrounds!!.deleteRecord(id, record.revision) }
                        report.put("ownedInterruptedTrialRecordCleaned", true)
                    }
                }
                // Normal deletion retains only a revision watermark against stale editors. Remove
                // this trial-owned watermark after verifying it did not exist before this trial.
                ownedId?.let { id -> before?.get("preferences")?.let { original ->
                    val key = "background-record-revision:$id"
                    check(original.none { it[0] == key })
                    SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
                        val value = database.rawQuery("SELECT data FROM preferences WHERE `key`=?", arrayOf(key)).use {
                            if (it.moveToFirst()) it.getString(0) else null
                        }
                        check(value == null || value.toIntOrNull()?.let { it in 1..20 } == true)
                        if (value != null) check(database.delete("preferences", "`key`=?", arrayOf(key)) == 1)
                    }
                } }
                settings?.let { runBlocking { repo.updateSettings(it) } }
                before?.get("preferences")?.singleOrNull { it[0] == "settings" }?.let { saved -> restoreSettingBytes(db, saved[1]) }
                before?.forEach { (table, old) -> check(old == rows(db, table)) { "Original $table rows changed" } }
                check(!runBlocking { repo.hasApiKey() })
            }
            report.put("originalDataAndCachesPreserved", cleanup.isSuccess)
            if (cleanup.isFailure) report.put("status", "failed").put("cleanupFailure", true)
            save()
            if (!failed && cleanup.isFailure) throw AssertionError("Knowledge trial cleanup or preservation failed")
        }
    }
    private fun openKnowledge() {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(">_  命令").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(">_  命令").performClick()
        compose.onNodeWithText("搜索命令…").performTextInput("knowledge")
        compose.onNodeWithText("笔记与表达回顾").performClick()
        compose.waitUntil(10_000) { exists("knowledge_screen") }
    }
    private fun node(tag: String) = compose.onNodeWithTag(tag).performScrollTo()
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun text(tag: String) = node(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("\n") { it.text }
    private fun hideKeyboard(activity: ActivityScenario<MainActivity>) {
        activity.onActivity { WindowInspector.getGlobalWindowViews().forEach { view ->
            view.windowInsetsController?.hide(WindowInsets.Type.ime()); view.findFocus()?.clearFocus()
        } }
        compose.waitForIdle(); SystemClock.sleep(200)
    }
    private fun verifyLayout(tag: String, scale: Float, report: JSONObject) {
        val layouts = mutableListOf<TextLayoutResult>()
        node(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        check(abs(layout.layoutInput.density.fontScale - scale) < .02f)
        check(!layout.didOverflowHeight && (0 until layout.lineCount).all { layout.getLineRight(it) <= layout.size.width + 1 })
        report.put("actualTextFontScale", layout.layoutInput.density.fontScale).put("explanationTextHasNoClipping", true)
    }
    private fun screenshot(out: File, report: JSONObject, name: String) {
        compose.waitForIdle(); SystemClock.sleep(200)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
        report.getJSONArray("screenshots").put(name)
    }
    private fun rows(file: File, table: String): List<List<String?>> = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT * FROM $table ORDER BY 1", null).use { cursor -> buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) })
        } }
    }
    private fun restoreSettingBytes(file: File, raw: String?) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.compileStatement("UPDATE preferences SET data=? WHERE `key`='settings'").use { stmt -> stmt.bindString(1, requireNotNull(raw)); stmt.executeUpdateDelete() }
        }
    }
    private companion object { val TABLES = listOf("sources", "articles", "article_sources", "article_bodies", "captures", "cards", "review_logs", "ai_jobs", "local_events", "preferences", "topic_cache") }
}
