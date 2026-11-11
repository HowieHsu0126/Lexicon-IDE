package dev.lexicon.ide.ui

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import dev.lexicon.ide.CaptureActivity
import dev.lexicon.ide.LexiconApplication
import java.io.File
import java.time.Instant
import java.time.ZoneId
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

/** One public retrieval through the real UI, with no Key and no generated or publisher test material. */
@RunWith(AndroidJUnit4::class)
class BackgroundResearchDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val codec = Json { ignoreUnknownKeys = true }
    private val sentence = "In this self-authored practice sentence, the FOMC is mentioned while readers discuss how monetary policy decisions are made."
    private val query get() = BackgroundQuery("FOMC", sentence)
    private data class Before(val settings: AppSettings, val settingsBytes: ByteArray?,
        val preferences: Map<String, ByteArray>, val tables: Map<String, List<List<String?>>>)

    @Test fun externalCaptureCanReadOfficialEvidenceRecreateAndReturnWithoutAKey() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Background research device trial requires explicit opt-in", args.getString("lexiconBackgroundResearchTrial") == "1")
        val theme = when (args.getString("theme") ?: "day") {
            "day" -> ThemeMode.LIGHT; "night" -> ThemeMode.DARK; else -> error("theme must be day or night")
        }
        val expectedScale = when (args.getString("font") ?: "1") {
            "1" -> 1f; "2" -> 2f; else -> error("font must be 1 or 2")
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as LexiconApplication
        val repository = app.repository
        val database = app.getDatabasePath("lexicon.sqlite")
        val variant = "${if (theme == ThemeMode.DARK) "night" else "day"}-font${(expectedScale * 100).toInt()}"
        val output = File(app.filesDir, "validation-1.11.0/background-trial/$variant").apply { mkdirs() }
        val report = JSONObject().put("status", "started").put("variant", variant).put("expectedFontScale", expectedScale)
            .put("automatedInteractionTrial", true).put("material", "Explicitly self-authored FOMC practice sentence")
            .put("lookupUiSubmissions", 0).put("generationUiSubmissions", 0).put("networkTrafficMeasured", false)
            .put("screenshots", JSONArray())
        fun save() = File(output, "device-trial.json").writeText(report.toString(2))
        fun skip(reason: String): Nothing {
            report.put("status", "skipped").put("reason", reason); save()
            assumeTrue(reason, false); error("Skipped")
        }
        var before: Before? = null
        var activity: ActivityScenario<CaptureActivity>? = null
        var settingsChanged = false
        var ownedCaptureId: String? = null
        var ownedEvents = emptyMap<String, ByteArray>()
        var failed = false
        var finalLookup: BackgroundLookup? = null
        val began = System.currentTimeMillis()
        save()
        try {
            if (abs(app.resources.configuration.fontScale - expectedScale) > .02f)
                skip("The actual system font scale does not match the requested variant")
            if (runBlocking { repository.hasApiKey() }) skip("This trial requires no installed API key; it will not alter an existing key")
            runBlocking { repository.initialize() }
            val background = repository.reading?.backgrounds ?: skip("Background repository is unavailable")
            if (!database.isFile) skip("The installed database is unavailable")
            if (runBlocking { repository.jobs.first() }.any { it.status == "running" }) skip("Existing AI work is running")
            if (runBlocking { repository.captures.first() }.any { it.selectedText == sentence })
                skip("An earlier identical practice capture exists; it will not be reused or deleted")
            val cached = runBlocking { background.cached(query) }
            val cacheRequired = theme == ThemeMode.DARK || expectedScale == 2f
            if (cacheRequired && cached == null) skip("Night or large-font continuation requires the day trial's existing cache")
            if (cached != null && BackgroundResearch.expired(cached, System.currentTimeMillis()))
                skip("The existing evidence is stale; this trial will not force a refresh")
            report.put("cacheExistedBefore", cached != null).put("cachedOnlyContinuation", cacheRequired)
            before = Before(runBlocking { repository.settings.first() }, preference(database, "settings"),
                preferences(database), TABLES.associateWith { rows(database, it) })
            val saved = requireNotNull(before)
            settingsChanged = true
            runBlocking { repository.updateSettings(saved.settings.copy(theme = theme, titleClassification = false,
                reader = saved.settings.reader.copy(automaticStudy = false))) }
            val intent = Intent(app, CaptureActivity::class.java).apply {
                action = Intent.ACTION_PROCESS_TEXT; type = "text/plain"
                putExtra(Intent.EXTRA_PROCESS_TEXT, sentence); putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val scenario = ActivityScenario.launch<CaptureActivity>(intent)
            activity = scenario
            compose.waitUntil(20_000) { exists("capture_target") }
            // Production captures PROCESS_TEXT immediately. Record the exact new object and the two
            // opening event IDs now, before any further interaction, for narrowly owned cleanup.
            compose.waitUntil(10_000) {
                val captures = runBlocking { repository.captures.first() }.filter { it.selectedText == sentence && it.createdAt >= began }
                val events = eventBytes(database).filterKeys { id -> saved.tables.getValue("local_events").none { it[0] == id } }
                captures.size == 1 && events.size == 2
            }
            val captured = runBlocking { repository.captures.first() }.single { it.selectedText == sentence && it.createdAt >= began }
            check(captured.articleId == null && captured.sourceRef == SourceRef() && captured.sentence.isEmpty())
            ownedCaptureId = captured.id
            ownedEvents = eventBytes(database).filterKeys { id -> saved.tables.getValue("local_events").none { it[0] == id } }
            val initialEvents = ownedEvents.values.map { codec.decodeFromString<LocalEvent>(it.toString(Charsets.UTF_8)) }
            check(initialEvents.map { it.type }.toSet() == setOf("capture_saved", "capture_opened"))
            check(initialEvents.all { it.value == 1 && it.createdAt in captured.createdAt..System.currentTimeMillis() })

            node("capture_target").performTextReplacement("FOMC")
            node("capture_sentence").performTextReplacement(sentence)
            hideKeyboard(scenario)
            node("capture_background").assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(15_000) { exists("background_research_panel") }
            compose.onNodeWithTag("background_continue_reading").assertIsDisplayed()
            report.put("backgroundEnteredAtTopWithinCapture", true)
            if (cached == null) {
                compose.waitUntil(10_000) { exists("background_lookup") && compose.onNodeWithTag("background_lookup").isEnabled() }
                check(editable("background_query_term") == "FOMC")
                check(editable("background_query_context") == sentence)
                node("background_lookup").performClick()
                report.put("lookupUiSubmissions", 1); save()
            }
            val lookupStarted = SystemClock.elapsedRealtime()
            compose.waitUntil(45_000) { exists("background_bound_term") && !exists("background_looking_up") }
            val lookup = runBlocking { background.cached(query) } ?: error("The UI lookup did not persist a receipt")
            finalLookup = lookup
            BackgroundResearch.validateLookup(lookup)
            check(lookup.query == query && lookup.status == "retrieved")
            val official = lookup.sources.singleOrNull { it.provider == "Federal Reserve Board" &&
                it.url == "https://www.federalreserve.gov/faqs/about_12844.htm" } ?: error("Required official FOMC evidence was not retrieved")
            check(official.contentHash == fingerprint(official.excerpt))
            val candidate = lookup.candidates.single { official.id in it.sourceIds }
            val selected = BackgroundResearch.selectedSources(lookup, candidate.id)
            check(selected.any { it.url == official.url })
            val selectedOfficial = selected.single { it.url == official.url }
            if (exists("background_candidate_${candidate.id}")) node("background_candidate_${candidate.id}").performClick()
            else check(lookup.candidates.size == 1)
            compose.waitUntil(10_000) { exists("background_explain") }
            report.put("retrievalElapsedMillis", SystemClock.elapsedRealtime() - lookupStarted)
                .put("lookupId", lookup.id).put("officialProvider", official.provider)
                .put("officialContentHash", official.contentHash).put("providerStatuses", JSONArray(lookup.providerOutcomes.map {
                    JSONObject().put("provider", it.provider).put("status", it.status)
                })).put("officialConceptSelected", true)
            verifyLayout(scenario, "background_bound_term", expectedScale, report)
            screenshot(output, report, "01-official-concept.png")
            node("background_source_${selectedOfficial.id}").performClick()
            check(text("background_excerpt_${selectedOfficial.id}") == selectedOfficial.excerpt)
            verifyLayout(scenario, "background_excerpt_${selectedOfficial.id}", expectedScale, report)
            screenshot(output, report, "02-original-official-excerpt.png")
            node("background_refresh") // Scroll to the end, without activating refresh.
            screenshot(output, report, "03-source-provenance.png")

            scenario.recreate()
            compose.waitUntil(20_000) { exists("background_research_panel") || exists("capture_background") }
            if (!exists("background_research_panel")) node("capture_background").performClick()
            compose.waitUntil(15_000) { exists("background_bound_term") && exists("background_explain") }
            check(text("background_bound_term") == "FOMC")
            check(text("background_context_preview") == sentence)
            check(text("background_excerpt_${selectedOfficial.id}") == selectedOfficial.excerpt)
            check(runBlocking { background.cached(query) } == lookup)
            report.put("queryMeaningAndEvidenceRestoredAfterRecreation", true)
            screenshot(output, report, "04-restored-evidence.png")
            node("background_explain").assertIsEnabled().performClick()
            report.put("generationUiSubmissions", 1)
            compose.waitUntil(10_000) { exists("background_explanation_error") && !exists("background_explaining") }
            check(text("background_explanation_error").contains("Key"))
            check(text("background_excerpt_${selectedOfficial.id}") == selectedOfficial.excerpt)
            check(runBlocking { background.cachedExplanation(lookup.id, candidate.id) } == null)
            check(runBlocking { background.cached(query) } == lookup)
            report.put("missingKeyLeavesEvidenceReadable", true).put("modelRequestStarted", false)
            node("background_explanation_error")
            screenshot(output, report, "05-no-key-evidence-retained.png")
            node("background_continue_reading").performClick()
            compose.waitUntil(15_000) { scenario.state == Lifecycle.State.DESTROYED }
            report.put("continueReadingClosedCaptureActivity", true).put("status", "passed")
        } catch (skip: AssumptionViolatedException) { throw skip }
        catch (failure: Throwable) {
            failed = true
            report.put("status", "failed").put("failureType", failure.javaClass.simpleName)
                .put("failureLocations", JSONArray(failure.stackTrace.take(12).map { "${it.fileName}:${it.lineNumber}" }))
            runCatching { screenshot(output, report, "failure.png") }
            throw AssertionError("Background research device trial failed; inspect sanitized report and screenshots")
        } finally {
            val closed = runCatching { activity?.close() }
            val cleanup = runCatching { before?.let { cleanupOwnedCapture(database, it, ownedCaptureId, ownedEvents, began, report) } }
            val settingsRestored = runCatching {
                before?.let { saved ->
                    if (settingsChanged) {
                        try { runBlocking { repository.updateSettings(saved.settings) } }
                        finally { restorePreference(database, "settings", saved.settingsBytes) }
                    }
                    check(sameBytes(saved.settingsBytes, preference(database, "settings")))
                }
            }
            val restored = runCatching {
                before?.let { saved ->
                    check(!runBlocking { repository.hasApiKey() })
                    saved.tables.forEach { (table, prior) -> check(prior == rows(database, table)) }
                    val afterPreferences = preferences(database)
                    check(saved.preferences.all { (key, bytes) -> sameBytes(bytes, afterPreferences[key]) })
                    val added = afterPreferences.filterKeys { it !in saved.preferences }
                    val expectedLookup = finalLookup ?: runBlocking { repository.reading?.backgrounds?.cached(query) }
                    val permittedBackground = expectedLookup?.let { setOf("background:index:1:${BackgroundResearch.queryKey(query)}", "background:lookup:1:${it.id}") }.orEmpty()
                    val dates = setOf(began, System.currentTimeMillis()).map { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() }
                    val editions = added.filterKeys { it.startsWith("reading-library:edition:") }
                    editions.forEach { (key, bytes) ->
                        val edition = codec.decodeFromString<DailyEdition>(bytes.toString(Charsets.UTF_8))
                        check(edition.day in dates && key == "reading-library:edition:${edition.day}" && edition.articleIds.size <= 3)
                    }
                    check(added.keys.all { it in permittedBackground || it in editions })
                    report.put("originalCardsCapturesJobsReviewsLogsAndSettingsPreserved", true)
                        .put("allowedNewDailyEditionCount", editions.size).put("allowedNewBackgroundCacheRows", added.keys.count { it in permittedBackground })
                }
            }
            report.put("activityClosed", closed.isSuccess).put("originalSettingsRestored", settingsRestored.isSuccess)
                .put("ownedCaptureCleanupPassed", cleanup.isSuccess).put("preservationPassed", restored.isSuccess)
            if (closed.isFailure || cleanup.isFailure || settingsRestored.isFailure || restored.isFailure)
                report.put("status", "failed").put("cleanupOrPreservationFailure", true)
            save()
            if (!failed && (closed.isFailure || cleanup.isFailure || settingsRestored.isFailure || restored.isFailure))
                throw AssertionError("Background trial cleanup or preservation failed; inspect sanitized report")
        }
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag).performScrollTo()
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun editable(tag: String) = node(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun text(tag: String) = node(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("\n") { it.text }
    private fun SemanticsNodeInteraction.isEnabled() = runCatching { assertIsEnabled(); true }.getOrDefault(false)
    private fun hideKeyboard(activity: ActivityScenario<CaptureActivity>) {
        activity.onActivity { WindowInspector.getGlobalWindowViews().forEach { root ->
            root.windowInsetsController?.hide(WindowInsets.Type.ime()); root.findFocus()?.clearFocus()
        } }
        compose.waitForIdle(); SystemClock.sleep(200)
    }
    private fun verifyLayout(activity: ActivityScenario<CaptureActivity>, tag: String, scale: Float, report: JSONObject) {
        var actualScale = 0f; var density = 0f; var width = 0
        activity.onActivity {
            actualScale = it.resources.configuration.fontScale; density = it.resources.displayMetrics.density
            width = it.windowManager.currentWindowMetrics.bounds.width()
        }
        val layouts = mutableListOf<TextLayoutResult>()
        node(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        report.put("lastLayoutCheck", JSONObject().put("tag", tag).put("systemScale", actualScale)
            .put("composeScale", layout.layoutInput.density.fontScale).put("systemDensity", density)
            .put("composeDensity", layout.layoutInput.density.density).put("width", layout.size.width)
            .put("height", layout.size.height).put("lines", layout.lineCount)
            .put("paragraphHeight", layout.multiParagraph.height).put("paragraphWidth", layout.multiParagraph.width)
            .put("overflowHeight", layout.didOverflowHeight).put("overflowWidth", layout.didOverflowWidth))
        check(abs(actualScale - scale) < .02f && abs(layout.layoutInput.density.fontScale - scale) < .02f)
        check(abs(layout.layoutInput.density.density - density) < .02f)
        check(!layout.didOverflowHeight && !layout.didOverflowWidth)
        check((0 until layout.lineCount).all { layout.getLineLeft(it) >= -1f && layout.getLineRight(it) <= layout.size.width + 1f })
        check(layout.size.width in 1..width)
        report.put("actualSystemFontScale", actualScale).put("actualComposeFontScale", layout.layoutInput.density.fontScale)
            .put("actualSystemDensity", density).put("actualComposeDensity", layout.layoutInput.density.density)
            .put("windowWidthPixels", width).put("checkedTextLayoutWithoutOverflow", true)
    }
    private fun screenshot(output: File, report: JSONObject, name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync(); SystemClock.sleep(200)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(output, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        report.getJSONArray("screenshots").put(name)
        File(output, "device-trial.json").writeText(report.toString(2))
    }
    private fun rows(file: File, table: String): List<List<String?>> = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT * FROM $table ORDER BY 1,2", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) }) }
        }
    }
    private fun preferences(file: File) = keyedBytes(file, "preferences", "`key`")
    private fun eventBytes(file: File) = keyedBytes(file, "local_events", "id")
    private fun keyedBytes(file: File, table: String, id: String): Map<String, ByteArray> = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT $id,CAST(data AS BLOB) FROM $table ORDER BY $id", null).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getBlob(1)) }
        }
    }
    private fun preference(file: File, key: String): ByteArray? = preferences(file)[key]
    private fun restorePreference(file: File, key: String, bytes: ByteArray?) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            if (bytes == null) db.delete("preferences", "`key`=?", arrayOf(key))
            else db.compileStatement("INSERT OR REPLACE INTO preferences (`key`,data) VALUES (?,CAST(? AS TEXT))").use {
                it.bindString(1, key); it.bindBlob(2, bytes); check(it.executeInsert() != -1L)
            }
        }
    }
    private fun sameBytes(a: ByteArray?, b: ByteArray?) = if (a == null) b == null else b != null && a.contentEquals(b)
    private fun cleanupOwnedCapture(file: File, before: Before, ownedId: String?, events: Map<String, ByteArray>, began: Long, report: JSONObject) {
        val oldCaptureIds = before.tables.getValue("captures").map { it[0] }.toSet()
        val additions = rows(file, "captures").filter { it[0] !in oldCaptureIds }.map { codec.decodeFromString<Capture>(it.last()!!) }
        val owned = if (ownedId != null) additions.single { it.id == ownedId } else {
            val matching = additions.filter { it.selectedText == sentence && it.createdAt in began..System.currentTimeMillis() }
            if (matching.isEmpty()) return
            matching.single()
        }
        val id = owned.id
        check(id !in oldCaptureIds)
        val originalEventIds = before.tables.getValue("local_events").map { it[0] }.toSet()
        val removalEvents = events.ifEmpty { eventBytes(file).filterKeys { it !in originalEventIds } }
        check(removalEvents.size <= 2 && removalEvents.keys.none { it in originalEventIds })
        val eventValues = removalEvents.values.map { codec.decodeFromString<LocalEvent>(it.toString(Charsets.UTF_8)) }
        check(eventValues.map { it.type }.distinct().size == eventValues.size && eventValues.all {
            it.type in setOf("capture_saved", "capture_opened") && it.value == 1 && it.createdAt in owned.createdAt..System.currentTimeMillis()
        })
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                val capture = db.rawQuery("SELECT data FROM captures WHERE id=?", arrayOf(id)).use {
                    check(it.moveToFirst()); codec.decodeFromString<Capture>(it.getString(0))
                }
                check(capture.id == id && capture.selectedText == sentence && capture.target == sentence && capture.sentence.isEmpty() &&
                    capture.articleId == null && capture.sourceRef == SourceRef() && capture.createdAt in began..System.currentTimeMillis())
                db.rawQuery("SELECT COUNT(*) FROM cards WHERE captureId=?", arrayOf(id)).use { check(it.moveToFirst() && it.getInt(0) == 0) }
                removalEvents.forEach { (eventId, expected) ->
                    val actual = db.rawQuery("SELECT CAST(data AS BLOB) FROM local_events WHERE id=?", arrayOf(eventId)).use { if (it.moveToFirst()) it.getBlob(0) else null }
                    check(sameBytes(expected, actual)); check(db.delete("local_events", "id=?", arrayOf(eventId)) == 1)
                }
                check(db.delete("captures", "id=?", arrayOf(id)) == 1)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        report.put("removedOwnedTemporaryCaptureCount", 1).put("removedOwnedOpeningEventCount", removalEvents.size)
    }
    private companion object {
        val TABLES = listOf("sources", "articles", "article_sources", "article_bodies", "captures", "cards", "review_logs", "ai_jobs", "local_events", "topic_cache")
    }
}
