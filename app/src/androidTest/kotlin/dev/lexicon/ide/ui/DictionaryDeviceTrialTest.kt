package dev.lexicon.ide.ui

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.inspector.WindowInspector
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
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import kotlin.math.abs

/** Opt-in actual capture workflow with the installed personal dictionary; no learning material is created. */
@RunWith(AndroidJUnit4::class)
class DictionaryDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun existingCapturesUseTheSelectedDictionaryAndRetainLocalResultsAfterRecreation() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Personal dictionary trial is opt-in", args.getString("lexiconDictionaryTrial") == "1")
        val variant = requireNotNull(args.getString("variant"))
        require(variant in setOf("day-font100", "night-font200"))
        val scale = requireNotNull(args.getString("font")).toFloat()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiconApplication
        val repo = app.repository
        val db = app.getDatabasePath("lexicon.sqlite")
        val output = File(app.filesDir, "validation-1.18.0/dictionary/$variant").apply { mkdirs() }
        val originalFile = File(output, "original-private.json")
        val reportFile = File(output, "device-trial.json")
        val installed = app.packageManager.getPackageInfo(app.packageName, 0)
        val report = JSONObject().put("status", "started").put("variant", variant)
            .put("version", installed.versionName).put("versionCode", installed.longVersionCode)
            .put("startedAt", System.currentTimeMillis()).put("screenshots", JSONArray()).put("queries", JSONArray())
            .put("material", "Existing pending captures and selected installed personal MDX")
            .put("scope", "Capture dictionary workflow; no source article navigation or AI generation")
            .put("latencyScope", "Observed test-driver input-to-ready latency, including polling; not first-frame latency or P95")
            .put("noDefinitionTextInReport", true)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            check(abs(app.resources.configuration.fontScale - scale) < .02f)
            check(app.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
            check(!runBlocking { repo.hasApiKey() })
            runBlocking { repo.initialize() }
            check(!originalFile.exists()) { "Preserve existing trial evidence" }
            val original = JSONObject()
            TABLES.forEach { original.put(it, JSONArray(rows(db, it).map { row -> JSONArray(row) })) }
            originalFile.writeText(original.toString())
            report.put("originalSnapshotTaken", true)
            val captures = runBlocking { repo.captures.first() }
            val savedCaptureIds = runBlocking { repo.cards.first() }.map { it.captureId }.toSet()
            val pending = captures.filter { it.id !in savedCaptureIds && it.target.isNotBlank() }.sortedByDescending { it.updatedAt }
            val first = requireNotNull(pending.firstOrNull()) { "An existing pending capture is required" }
            val second = requireNotNull(pending.firstOrNull {
                it.id != first.id && !it.target.equals(first.target, true) && !it.target.trim().equals("robust", true)
            }) { "A second existing capture with a different target is required" }
            val candidates = listOf(first, second)
            val dictionary = runBlocking { repo.dictionaryStatus() }
            check(dictionary.ready && dictionary.format == "MDX" && dictionary.id.isNotBlank())
            report.put("selectedDictionaryIdHash", hash(dictionary.id)).put("dictionaryEntryCount", dictionary.entryCount)
                .put("captureIdentityHashes", JSONArray(candidates.map { hash(it.id) }))
                .put("offlineActiveNetworkAbsent", true).put("installedKeyAbsent", true)
            val settings = runBlocking { repo.settings.first() }
            runBlocking { repo.updateSettings(settings.copy(
                theme = if (variant.startsWith("night")) ThemeMode.DARK else ThemeMode.LIGHT,
                titleClassification = false, reader = settings.reader.copy(automaticStudy = false))) }
            report.put("trialSettingsHash", hash(requireNotNull(rows(db, "preferences").single { it[0] == "settings" }[1])))
            reportFile.writeText(report.toString(2))
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(20_000) { exists("daily_reading") }
            openPending()
            openCapture(candidates[0])
            screenshot(output, report, "01-existing-capture.png")
            var lastDefinitions = emptyList<String>()
            for ((index, query) in listOf("book", "world", "robust").withIndex()) {
                val started = SystemClock.elapsedRealtimeNanos()
                reach("capture_target").performTextReplacement(query)
                dismissKeyboard()
                compose.waitUntil(30_000) { dictionaryReady(query) }
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                // Read expected entries only after the UI result; this must not prewarm the timed lookup.
                val expected = runBlocking { repo.lookup(query) }.take(3)
                check(expected.isNotEmpty())
                assertDictionary(expected)
                expandDefinitions()
                compose.onNodeWithTag("capture_dictionary_toggle").assertTextContains("收起释义")
                expected.indices.forEach { item ->
                    reach("capture_dictionary_definition_$item")
                    assertUntruncated("capture_dictionary_definition_$item")
                }
                lastDefinitions = expected.map { it.definition.ifBlank { "无释义" } }
                report.getJSONArray("queries").put(JSONObject().put("query", query)
                    .put("visibleResultCount", expected.size).put("definitionHashes", JSONArray(lastDefinitions.map(::hash)))
                    .put("definitionCharacterCounts", JSONArray(lastDefinitions.map { it.length }))
                    .put("observedInputToReadyMs", elapsedMs).put("expandedTextNoOverflow", true))
                reach("capture_dictionary_word_0")
                screenshot(output, report, "0${index + 2}-$query-expanded.png")
            }
            scenario.recreate()
            compose.waitUntil(30_000) { exists("capture_target") && field("capture_target") == "robust" && dictionaryReady("robust") }
            check(definitions() == lastDefinitions)
            expandDefinitions()
            assertUntruncated("capture_dictionary_definition_0")
            reach("capture_dictionary_word_0")
            screenshot(output, report, "05-recreated-local-result.png")
            report.put("targetAndLocalDefinitionsSurviveRecreation", true)
            closeCapture()
            openCapture(candidates[1])
            check(field("capture_target") == candidates[1].target)
            val expectedSecond = runBlocking { repo.lookup(candidates[1].target) }.take(3)
            if (expectedSecond.isEmpty()) {
                compose.waitUntil(30_000) { exists("capture_dictionary_empty") }
                check(definitions().isEmpty())
            } else {
                compose.waitUntil(30_000) { definitions() == expectedSecond.map { it.definition.ifBlank { "无释义" } } }
                assertDictionary(expectedSecond)
            }
            screenshot(output, report, "06-different-existing-capture.png")
            report.put("differentCaptureRestoresItsOwnTargetAndResults", true)
            closeCapture()
            compose.onNodeWithText(">_  命令").performClick()
            compose.onNodeWithText("搜索命令…").performTextInput("explorer")
            compose.onNodeWithText("打开阅读列表").performClick()
            compose.waitUntil(15_000) { exists("daily_reading") }
            check(runBlocking { repo.dictionaryStatus() }.id == dictionary.id)
            check(app.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
            check(!runBlocking { repo.hasApiKey() })
            report.put("returnedToDailyList", true).put("dictionarySelectionUnchanged", true).put("status", "passed")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failureType", failure.javaClass.simpleName)
                .put("failureLocations", JSONArray(failure.stackTrace.take(14).map { "${it.fileName}:${it.lineNumber}" }))
            runCatching { screenshot(output, report, "failure.png") }
            throw AssertionError("Dictionary capture trial failed; inspect sanitized report")
        } finally {
            scenario?.close()
            SystemClock.sleep(700)
            var failure: Throwable? = null
            if (originalFile.exists()) runCatching { cleanup(db, originalFile, report) }.onFailure {
                failure = it
                report.put("status", "failed").put("cleanupFailure", true)
                    .put("cleanupFailureLocations", JSONArray(it.stackTrace.take(8).map { frame -> "${frame.fileName}:${frame.lineNumber}" }))
            }
            reportFile.writeText(report.toString(2))
            check(failure == null) { "Dictionary trial preservation failed" }
        }
    }

    private fun openPending() {
        compose.onNodeWithText(">_  命令").performClick()
        compose.onNodeWithText("搜索命令…").performTextInput("inbox")
        compose.onNode(hasText("待补充捕获", substring = true)).performClick()
        compose.waitUntil(15_000) { exists("pending_captures") }
    }

    private fun openCapture(capture: Capture) {
        compose.onNodeWithTag("pending_captures").performScrollToNode(hasTestTag("pending_capture_${capture.id}"))
        compose.onNodeWithTag("pending_capture_${capture.id}").performClick()
        compose.waitUntil(15_000) { exists("capture_target") }
        check(field("capture_target") == capture.target)
    }

    private fun closeCapture() {
        dismissKeyboard()
        reach("capture_close").performClick()
        compose.waitUntil(10_000) { !exists("capture_target") && exists("pending_captures") }
    }

    private fun expandDefinitions() {
        val toggle = reach("capture_dictionary_toggle")
        if (text("capture_dictionary_toggle")?.contains("收起释义") != true) toggle.performClick()
    }

    private fun dictionaryReady(query: String): Boolean = !exists("capture_dictionary_lookup_loading") &&
        !exists("capture_dictionary_lookup_error") && text("capture_dictionary_word_0")?.trim()?.startsWith(query, ignoreCase = true) == true &&
        text("capture_dictionary_definition_0")?.isNotBlank() == true

    private fun assertDictionary(expected: List<DictionaryEntry>) {
        check(definitions() == expected.map { it.definition.ifBlank { "无释义" } })
        expected.forEachIndexed { index, entry ->
            check(text("capture_dictionary_word_$index") == "${entry.word}  ${entry.phonetic}")
        }
    }

    private fun definitions(): List<String> = (0..2).mapNotNull { text("capture_dictionary_definition_$it") }
    private fun text(tag: String): String? = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
        ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
    private fun field(tag: String): String = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun reach(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag).also {
        if (runCatching { it.assertIsDisplayed() }.isFailure) it.performScrollTo()
    }

    private fun assertUntruncated(tag: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        check(layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow }) { "Expanded dictionary text is truncated" }
    }

    private fun dismissKeyboard() {
        var visible = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            visible = WindowInspector.getGlobalWindowViews().any { it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        }
        if (visible) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
        }
    }

    private fun cleanup(db: File, originalFile: File, report: JSONObject) {
        val original = JSONObject(originalFile.readText())
        val originalPreferences = savedRows(original, "preferences")
        val originalSettings = requireNotNull(originalPreferences.single { it[0] == "settings" }[1])
        val editionKey = "reading-library:edition:${LocalDate.now()}"
        val edition = rows(db, "preferences").singleOrNull { it[0] == editionKey }
        val ownsEdition = originalPreferences.none { it[0] == editionKey } && edition != null
        if (ownsEdition) {
            val decoded = codec.decodeFromString<DailyEdition>(requireNotNull(edition!![1]))
            val articleIds = rows(db, "articles").map { it[0] }.toSet()
            check(decoded.day == LocalDate.now().toString() && decoded.articleIds.all { it in articleIds })
        }
        SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.beginTransaction()
            try {
                val actual = requireNotNull(rows(database, "preferences").single { it[0] == "settings" }[1])
                check(actual == originalSettings || hash(actual) == report.optString("trialSettingsHash")) { "Settings changed outside trial" }
                if (ownsEdition) {
                    check(rows(database, "preferences").single { it[0] == editionKey } == edition)
                    database.delete("preferences", "`key`=?", arrayOf(editionKey))
                }
                database.compileStatement("UPDATE preferences SET data=? WHERE `key`='settings'").use {
                    it.bindString(1, originalSettings); it.executeUpdateDelete()
                }
                TABLES.forEach { check(savedRows(original, it) == rows(database, it)) { "Original $it rows changed" } }
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
        }
        TABLES.forEach { check(savedRows(original, it) == rows(db, it)) }
        report.put("originalDataPreserved", true).put("capturesCardsReviewsAndProfileUnchanged", true)
            .put("settingsRestored", true).put("ownedDailyEditionRemoved", ownsEdition)
    }

    private fun savedRows(original: JSONObject, table: String): List<List<String?>> = original.getJSONArray(table).let { all ->
        (0 until all.length()).map { i -> all.getJSONArray(i).let { row ->
            (0 until row.length()).map { j -> if (row.isNull(j)) null else row.getString(j) }
        } }
    }
    private fun rows(file: File, table: String): List<List<String?>> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { rows(it, table) }
    private fun rows(db: SQLiteDatabase, table: String): List<List<String?>> =
        db.rawQuery("SELECT * FROM $table ORDER BY 1", null).use { cursor -> buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) })
        } }
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun screenshot(output: File, report: JSONObject, name: String) {
        compose.waitForIdle(); SystemClock.sleep(150)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        report.getJSONArray("screenshots").put(name)
    }
    private companion object {
        val TABLES = listOf("sources", "articles", "article_sources", "article_bodies", "captures", "cards", "review_logs", "ai_jobs", "local_events", "preferences", "topic_cache")
    }
}
