package dev.lexicon.ide.ui

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
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import dev.lexicon.ide.LexiconApplication
import dev.lexicon.ide.MainActivity
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual MainActivity/Repository, offline and restricted to Android user 10. Only two owned,
 * self-authored pending captures are inserted; no original row is restored or replaced.
 * dictionaryFastPathTrial=1, variant=day-font100|night-font200, evidenceRun=<unique name>,
 * optional phase=before|after (default after), expectedUserId=10.
 */
@RunWith(AndroidJUnit4::class)
class DictionaryFastPathDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun installedMdxLookupAndDetailPreserveOwnedDraftsAndAllOriginalRows() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Dictionary fast-path trial is opt-in", args.getString("dictionaryFastPathTrial") == "1")
        val variant = requireNotNull(args.getString("variant")).also { require(it in setOf("day-font100", "night-font200")) }
        val run = requireNotNull(args.getString("evidenceRun")).also { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) }
        val phase = (args.getString("phase") ?: "after").also { require(it in setOf("before", "after")) }
        require((args.getString("expectedUserId") ?: "10").toInt() == 10)
        val app = instrumentation.targetContext.applicationContext as LexiconApplication
        val actualUser = Regex("^/data/user/(\\d+)/").find(app.applicationInfo.dataDir)?.groupValues?.get(1)?.toInt()
        check(actualUser == 10) { "Dictionary trial may not run against the original user" }
        val output = File(app.filesDir, "validation-1.26.0/dictionary-fast-path/$phase/$variant/$run")
        check(!output.exists() && output.mkdirs()) { "Use a new evidenceRun; preserve previous evidence" }
        val reportFile = File(output, "device-trial.json")
        val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
        val report = JSONObject().put("schema", "lexicon.dictionary.fast.path.trial.v1")
            .put("status", "started").put("phase", phase).put("variant", variant).put("evidenceRun", run)
            .put("version", packageInfo.versionName).put("versionCode", packageInfo.longVersionCode)
            .put("apkSha256", hash(File(instrumentation.targetContext.applicationInfo.sourceDir)))
            .put("androidUserId", actualUser).put("actualMainActivity", true)
            .put("repositoryScope", "Unfiltered application Repository in dedicated Android user 10")
            .put("materialOrigin", "Two self-authored pending captures; actual installed personal MDX")
            .put("databaseDriver", "androidx.sqlite.driver.bundled.BundledSQLiteDriver")
            .put("jsonContainsLearningText", false).put("aiActions", 0).put("ratingsRequested", 0)
            .put("originalRowsRestoredOrReplaced", 0).put("originalSnapshotKeptOnlyInMemory", true)
            .put("latencyScope", "Observed UI action-to-ready including Compose test-driver waits, polling, editing debounce and animation; not first-visible-frame latency, P95 or an 80ms result")
            .put("firstLookupScope", "No Repository lookup before opening the first capture; dictionary installation already read the file, so this is not a cold filesystem claim")
            .put("screenshots", JSONArray()).put("entries", JSONArray()).put("observedLatencies", JSONArray())
        reportFile.writeText(report.toString(2))
        var trial: Trial? = null
        var passed = false
        var preserved = false
        try {
            requireOfflineNoKey(app)
            val fontScale = app.resources.configuration.fontScale
            check(abs(fontScale - if (variant == "night-font200") 2f else 1f) < .02f)
            val repository = app.repository
            val initializationStarted = SystemClock.elapsedRealtimeNanos()
            runBlocking { repository.initialize() }
            report.put("repositoryInitializationObservedMs", elapsedMs(initializationStarted))
                .put("initializationScope", "Awaiting normal Application initialization; the Application may already have begun it")
            val folder = File(app.noBackupFilesDir, "dictionary")
            val filesBeforeSetup = fileSignatures(folder)
            val setupStarted = SystemClock.elapsedRealtimeNanos()
            val installed = runBlocking { repository.installedDictionaries() }
            val dictionary = runBlocking { repository.dictionaryStatus() }
            report.put("dictionarySetupObservedMs", elapsedMs(setupStarted))
                .put("dictionarySetupScope", "Normal installedDictionaries/status; may copy and validate packaged MDX; no lookup")
            check(dictionary.ready && dictionary.format == "MDX" && dictionary.id.isNotBlank())
            val selected = installed.single { it.id == dictionary.id }
            val selectedFile = File(folder, selected.fileName)
            check(selectedFile.isFile && hash(selectedFile) == dictionary.id)
            val filesAfterSetup = fileSignatures(folder)
            filesBeforeSetup.filterKeys { it.endsWith(".mdx", true) || it.endsWith(".sqlite", true) }.forEach { (name, signature) ->
                check(filesAfterSetup[name] == signature) { "An existing dictionary payload changed during normal setup" }
            }
            val settings = runBlocking { repository.settings.first() }
            val actualNight = app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val effectiveDark = settings.theme == ThemeMode.DARK ||
                (settings.theme == ThemeMode.SYSTEM && actualNight == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            check(effectiveDark == (variant == "night-font200")) { "The rendered theme must match the requested variant" }
            report.put("selectedDictionarySha256", dictionary.id).put("dictionaryBytes", selectedFile.length())
                .put("dictionaryEntries", dictionary.entryCount).put("dictionaryMetadataName", dictionary.name)
                .put("dictionaryEditionIndependentlyVerified", false)
                .put("dictionaryFilesBeforeSetup", JSONObject(filesBeforeSetup))
                .put("dictionaryFilesAfterSetup", JSONObject(filesAfterSetup))
                .put("normalPackagedDictionarySetupRetained", true)
                .put("actualFontScale", fontScale).put("actualNightMode", actualNight)
                .put("theme", settings.theme.name).put("effectiveDarkTheme", effectiveDark)
                .put("readerPreferencesSha256", hash(codec.encodeToString(ReaderPreferences.serializer(), settings.reader)))
            val database = app.getDatabasePath("lexicon.sqlite")
            val original = waitForQuietRows(database)
            listOf("articles", "article_sources", "article_bodies", "captures", "cards", "review_logs", "ai_jobs").forEach {
                check(original.getValue(it).isEmpty()) { "Dedicated dictionary trial contains existing learning material" }
            }
            val started = System.currentTimeMillis()
            val captures = listOf(
                ownedCapture("dictionary-fast-$run-world", "world", "This self-authored validation sentence uses world as a lookup target.", started),
                ownedCapture("dictionary-fast-$run-robust", "robust", "This self-authored validation sentence uses robust as a lookup target.", started - 1),
            )
            val t = Trial(app, database, original, output, report, started, captures, dictionary.id, filesAfterSetup)
            trial = t
            report.put("startedAt", started).put("baselineQuietIntervalMs", 2_000)
                .put("originalTableHashes", tableHashes(original))
                .put("captureIdentityHashes", JSONArray(captures.map { hash(it.id) }))
                .put("snapshotScope", "All lexicon.sqlite rows after normal Application initialization and dictionary setup")
            verifyEditionWillNotRewriteOriginal(t)
            insertOwnedCaptures(t)
            t.stage = "open_first_owned_capture_without_lookup_prewarm"
            t.activity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(20_000) { exists("daily_reading") }
            openPending()
            val firstReadyMs = openCapture(t, captures[0], "world")
            report.getJSONArray("observedLatencies").put(JSONObject().put("action", "open_world_capture")
                .put("actionToReadyObservedMs", firstReadyMs).put("expectedReadAfterReady", true))
            val expected = runBlocking { repository.lookup("world") }
            check(expected.isNotEmpty() && expected.any { it.definition.length == WORLD_CHARACTERS }) {
                "The frozen installed dictionary must contain the complete 10664-character world definition"
            }
            val preview = layout("capture_dictionary_definition_0").layoutInput.text.text
            check(preview.isNotEmpty() && expected.first().definition.startsWith(preview))
            report.put("firstLookupNotPrewarmedByTrial", true).put("previewCharacters", preview.length)
                .put("previewSha256", hash(preview)).put("fullResultIdentity", DictionaryPresentation.identity(dictionary.id, "world", expected))
            screenshot(t, "01-first-world-preview.png")

            t.stage = "edit_unsaved_draft_with_ime"
            reach("capture_target").performClick().performTextReplacement(TARGET_DRAFT)
            setCursor("capture_target", TARGET_CURSOR)
            reach("capture_sentence").performClick().performTextReplacement(SENTENCE_DRAFT)
            setCursor("capture_sentence", SENTENCE_CURSOR)
            compose.waitUntil(15_000) { keyboardVisible() }
            compose.waitUntil(20_000) { dictionaryReady("world") }
            assertDraft()
            verifyOwnedCapturesUnchanged(t)
            screenshot(t, "02-draft-with-keyboard.png")

            t.stage = "open_complete_detail"
            val opener = reach("capture_dictionary_toggle")
            check(keyboardVisible())
            val detailStarted = SystemClock.elapsedRealtimeNanos()
            opener.performClick()
            awaitDetail()
            compose.waitUntil(10_000) { !keyboardVisible() }
            report.getJSONArray("observedLatencies").put(JSONObject().put("action", "open_world_detail")
                .put("actionToReadyObservedMs", elapsedMs(detailStarted)))
            report.put("imeVisibleBeforeDetail", true).put("imeHiddenAfterDetail", true)
            assertFixedDetailActions()
            screenshot(t, "03-detail-first.png")

            t.stage = "verify_complete_dictionary_text"
            expected.forEachIndexed { index, entry -> verifyEntry(t, index, expected.size, entry) }
            val chosenIndex = expected.indices.maxBy { expected[it].definition.length }
            selectEntry(chosenIndex, expected.size)
            val chosenEntry = expected[chosenIndex]
            val chosenBlocks = DictionaryPresentation.blocks(chosenEntry.definition)
            compose.onNodeWithTag("dictionary_detail_list").performScrollToIndex(chosenBlocks.size)
            compose.onNodeWithTag("dictionary_detail_list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            compose.waitForIdle()
            val beforeRecreate = position()
            check(beforeRecreate.firstBlock > 0 && beforeRecreate.lastBlock == chosenBlocks.last().start)
            assertFixedDetailActions()
            screenshot(t, "04-detail-at-end.png")

            t.stage = "recreate_detail_at_end"
            requireNotNull(t.activity).recreate()
            awaitDetail()
            compose.waitUntil(30_000) { runCatching { samePosition(beforeRecreate, position(), app.resources.displayMetrics.density) }.getOrDefault(false) }
            val restored = position()
            assertVisibleBlocks(chosenEntry)
            check(runBlocking { repository.lookup("world") } == expected)
            check(!keyboardVisible())
            assertFixedDetailActions()
            report.put("positionBeforeRecreate", beforeRecreate.json()).put("positionAfterRecreate", restored.json())
                .put("detailPositionRestored", true).put("sameLocalResultAfterRecreate", true)
                .put("recoveryScope", "Activity recreation; not OS process termination")
            screenshot(t, "05-restored-detail.png")

            t.stage = "return_draft_and_reopen_detail"
            compose.onNodeWithTag("dictionary_detail_back").performClick()
            awaitCapture()
            assertDraft()
            check(!keyboardVisible())
            verifyOwnedCapturesUnchanged(t)
            report.put("backKeptDraftAndCursors", true)
            reach("capture_dictionary_toggle").performClick()
            awaitDetail()
            compose.waitUntil(15_000) { samePosition(restored, position(), app.resources.displayMetrics.density) }
            report.put("reopenedDetailKeptPosition", true)
            if (!exists("dictionary_detail_sentence")) compose.onNodeWithTag("dictionary_detail_choose_match").performClick()
            reach("dictionary_detail_sentence").performClick()
            awaitCapture()
            compose.onNodeWithTag("capture_sentence").assertIsDisplayed()
            assertDraft()
            check(!keyboardVisible())
            report.put("sentenceActionKeptDraftAndCursors", true).put("sentenceActionDidNotOpenIme", true)
            screenshot(t, "06-returned-to-sentence.png")

            t.stage = "close_and_open_other_owned_capture"
            reach("capture_dictionary_toggle").performClick()
            awaitDetail()
            compose.onNodeWithTag("dictionary_detail_close").performClick()
            compose.waitUntil(15_000) { !exists("dictionary_detail") && !exists("capture_target") && exists("pending_captures") }
            val secondReadyMs = openCapture(t, captures[1], "robust")
            check(field("capture_target") == captures[1].target && field("capture_sentence") == captures[1].sentence)
            check(!exists("dictionary_detail"))
            val secondExpected = runBlocking { repository.lookup("robust") }
            val secondPreview = layout("capture_dictionary_definition_0").layoutInput.text.text
            check(secondExpected.isNotEmpty() && secondPreview.isNotEmpty() && secondExpected.first().definition.startsWith(secondPreview))
            report.getJSONArray("observedLatencies").put(JSONObject().put("action", "open_robust_capture")
                .put("actionToReadyObservedMs", secondReadyMs).put("expectedReadAfterReady", true))
            report.put("otherCaptureKeptOwnDraft", true).put("oldDetailAbsentForOtherCapture", true)
                .put("otherCaptureResultIdentity", DictionaryPresentation.identity(dictionary.id, "robust", secondExpected))
            screenshot(t, "07-other-owned-capture.png")
            closeCapture()
            verifyOwnedCapturesUnchanged(t)
            requireOfflineNoKey(app)
            check(runBlocking { repository.dictionaryStatus() }.id == dictionary.id)
            report.put("dictionarySelectionUnchanged", true).put("offlineAndNoKeyAtEnd", true)
            passed = true
        } catch (error: Throwable) {
            report.put("failedStage", trial?.stage ?: "preflight").put("failureClass", error.javaClass.simpleName)
                .put("failureLocations", JSONArray(error.stackTrace.take(12).map { "${it.fileName}:${it.lineNumber}" }))
            trial?.let { runCatching { screenshot(it, "failure.png") } }
        } finally {
            trial?.let { t ->
                val jobs = existingWriterJobs(t)
                runCatching { t.activity?.close() }.onFailure { passed = false; report.put("activityCloseFailureClass", it.javaClass.simpleName) }
                t.activity = null
                runCatching {
                    runBlocking { withTimeout(15_000) { jobs.joinAll() } }
                    requireOfflineNoKey(app)
                    val settled = waitForQuietRows(t.database)
                    if (t.inserted) {
                        verifyOwnedCapturesUnchanged(t)
                        val events = ownedOpenEvents(t, settled)
                        if (passed) check(events.size == t.openActions)
                        report.put("captureOpenActions", t.openActions).put("ownedCaptureOpenEvents", events.size)
                        cleanupOwnedRows(t, settled)
                    }
                    val after = waitForQuietRows(t.database)
                    check(after == t.original)
                    val dictionaryAfter = fileSignatures(File(app.noBackupFilesDir, "dictionary"))
                    check(dictionaryAfter == t.dictionaryFiles)
                    check(runBlocking { app.repository.dictionaryStatus() }.id == t.dictionaryId)
                    report.put("finalTableHashes", tableHashes(after)).put("dictionaryFilesAfterTrial", JSONObject(dictionaryAfter))
                        .put("dictionaryFilesPreserved", true).put("dictionarySelectionUnchanged", true)
                    preserved = true
                }.onFailure { error ->
                    report.put("preservationFailureClass", error.javaClass.simpleName)
                        .put("preservationFailureLocations", JSONArray(error.stackTrace.take(8).map { "${it.fileName}:${it.lineNumber}" }))
                }
            }
            report.put("fixtureInsertSucceeded", trial?.inserted == true).put("originalDataPreserved", preserved)
                .put("allOriginalRowsCompared", preserved).put("status", if (passed && preserved) "passed" else "failed")
            reportFile.writeText(report.toString(2))
        }
        check(passed && preserved) { "Dictionary fast-path trial failed; inspect its sanitized report" }
    }

    private class Trial(val app: LexiconApplication, val database: File, val original: Map<String, List<List<String?>>>,
        val output: File, val report: JSONObject, val startedAt: Long, val captures: List<Capture>,
        val dictionaryId: String, val dictionaryFiles: Map<String, String>) {
        var activity: ActivityScenario<MainActivity>? = null
        var stage = "preflight"
        var inserted = false
        val day = Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        var expectedEdition: DailyEdition? = null
        var openActions = 0
    }

    private fun ownedCapture(id: String, word: String, sentence: String, at: Long) = Capture(
        id = id, target = word, selectedText = word, sentence = sentence, contextOrigin = ContextOrigin.USER_SUPPLIED,
        sourceRef = SourceRef(title = "Self-authored dictionary validation fixture", source = "Lexicon local test"),
        createdAt = at, aiAllowed = false,
    )

    private fun insertOwnedCaptures(t: Trial) {
        openDatabase(t.database).use { db -> transaction(db) {
            check(snapshot(db) == t.original) { "Database changed before fixture insertion" }
            t.captures.forEach { capture ->
                db.prepare("INSERT INTO captures (id,createdAt,data) VALUES (?,?,?)").use {
                    it.bindText(1, capture.id); it.bindLong(2, capture.createdAt); it.bindText(3, codec.encodeToString(Capture.serializer(), capture)); it.step()
                }
                check(changes(db) == 1L)
            }
        } }
        t.inserted = true
        t.report.put("fixtureRowsInserted", 2).put("fixtureInsertion", "Two explicitly owned pending captures before launch; no save, rating or AI action")
    }

    private fun verifyOwnedCapturesUnchanged(t: Trial) {
        val rows = snapshot(t.database)
        check(rows.getValue("captures").map { codec.decodeFromString<Capture>(requireNotNull(it[2])) }.toSet() == t.captures.toSet())
        listOf("articles", "article_sources", "article_bodies", "cards", "review_logs", "ai_jobs").forEach {
            check(rows.getValue(it) == t.original.getValue(it)) { "Lookup changed learning data" }
        }
        ownedOpenEvents(t, rows)
    }

    private fun ownedOpenEvents(t: Trial, rows: Map<String, List<List<String?>>>): List<List<String?>> {
        val original = t.original.getValue("local_events")
        val ids = original.map { it[0] }.toSet()
        val current = rows.getValue("local_events")
        check(current.filter { it[0] in ids } == original)
        val added = current.filter { it[0] !in ids }
        check(added.size <= t.openActions)
        added.forEach { row ->
            val event = codec.decodeFromString<LocalEvent>(requireNotNull(row[2]))
            check(event.id == row[0] && event.createdAt == row[1]?.toLongOrNull() &&
                event.type == "capture_opened" && event.value == 1 &&
                event.createdAt in t.startedAt..System.currentTimeMillis())
        }
        return added
    }

    private fun verifyEditionWillNotRewriteOriginal(t: Trial) {
        val rows = t.original.getValue("preferences").filter { it[0]?.startsWith("reading-library:edition:") == true }
        val existing = rows.singleOrNull { it[0] == "reading-library:edition:${t.day}" }
            ?.let { codec.decodeFromString<DailyEdition>(requireNotNull(it[1])) }
        val history = rows.sortedByDescending { it[0] }.take(8).map { codec.decodeFromString<DailyEdition>(requireNotNull(it[1])) }
        val sources = runBlocking { t.app.repository.sources.first() }
        val expected = ReadingDailyPlanner.extend(existing, emptyList(), sources, t.startedAt, ZoneId.systemDefault().id, history)
        check(existing == null || existing == expected) { "An original daily edition would change" }
        t.expectedEdition = expected
        t.report.put("trialEditionDay", t.day)
    }

    private fun cleanupOwnedRows(t: Trial, settled: Map<String, List<List<String?>>>) {
        check(t.inserted && settled.keys == t.original.keys)
        val openEvents = ownedOpenEvents(t, settled)
        val added = settled.mapValues { (table, rows) ->
            val previous = t.original.getValue(table)
            val keys = previous.map { it[0] }.toSet()
            check(rows.filter { it[0] in keys } == previous) { "An original row changed; retain it for inspection" }
            rows.filter { it[0] !in keys }
        }
        val owned = t.captures.associateBy { it.id }
        added.forEach { (table, rows) -> rows.forEach { row ->
            val valid = when (table) {
                "captures" -> owned[row[0]]?.let { capture ->
                    row[1]?.toLongOrNull() == capture.createdAt && codec.decodeFromString<Capture>(requireNotNull(row[2])) == capture
                } == true
                "preferences" -> row[0] == "reading-library:edition:${t.day}" &&
                    codec.decodeFromString<DailyEdition>(requireNotNull(row[1])) == t.expectedEdition
                "local_events" -> row in openEvents
                else -> false
            }
            check(valid) { "Unexpected added row; preserve it instead of broad cleanup" }
        } }
        openDatabase(t.database).use { db -> transaction(db) {
            check(snapshot(db) == settled) { "Database changed after the quiet interval" }
            listOf("captures", "preferences", "local_events").forEach { table -> added.getValue(table).forEach { row ->
                val column = if (table == "preferences") "`key`" else "id"
                db.prepare("DELETE FROM $table WHERE $column=?").use { it.bindText(1, requireNotNull(row[0])); it.step() }
                check(changes(db) == 1L)
            } }
        } }
        t.report.put("removedOwnedRows", JSONObject().apply { added.forEach { (table, rows) -> if (rows.isNotEmpty()) put(table, rows.size) } })
    }

    private fun openPending() {
        compose.onNodeWithText(">_  命令").performClick()
        compose.onNodeWithText("搜索命令…").performTextInput("inbox")
        compose.onNode(hasText("待处理捕获", substring = true)).performClick()
        compose.waitUntil(15_000) { exists("pending_captures") }
    }
    private fun openCapture(t: Trial, capture: Capture, query: String): Double {
        compose.onNodeWithTag("pending_captures").performScrollToNode(hasTestTag("pending_capture_${capture.id}"))
        val started = SystemClock.elapsedRealtimeNanos()
        t.openActions++
        compose.onNodeWithTag("pending_capture_${capture.id}").performClick()
        compose.waitUntil(30_000) { exists("capture_target") && dictionaryReady(query) }
        val elapsed = elapsedMs(started)
        check(field("capture_target") == capture.target)
        return elapsed
    }
    private fun closeCapture() {
        if (keyboardVisible()) InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { !keyboardVisible() }
        compose.onNodeWithTag("capture_close").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { !exists("capture_target") && exists("pending_captures") }
    }
    private fun awaitCapture() { compose.waitUntil(20_000) { exists("capture_target") && !exists("dictionary_detail") } }
    private fun awaitDetail() {
        compose.waitUntil(30_000) { exists("dictionary_detail") && exists("dictionary_detail_list") &&
            !exists("dictionary_detail_loading") && !exists("dictionary_detail_preparing") && !exists("dictionary_detail_error") }
        compose.waitForIdle()
    }
    private fun selectEntry(index: Int, count: Int) {
        if (count > 1) {
            compose.onNodeWithTag("dictionary_detail_choose_match").performClick()
            reach("dictionary_detail_match_$index").performClick()
            awaitDetail()
        }
        compose.onNodeWithTag("dictionary_detail_list").performScrollToIndex(0)
    }
    private fun verifyEntry(t: Trial, index: Int, count: Int, entry: DictionaryEntry) {
        selectEntry(index, count)
        val blocks = DictionaryPresentation.blocks(entry.definition)
        check(blocks.isNotEmpty())
        val observed = StringBuilder()
        val blockReports = JSONArray()
        var end = 0
        blocks.forEachIndexed { blockIndex, block ->
            check(block.start == end && block.endExclusive > block.start)
            compose.onNodeWithTag("dictionary_detail_list").performScrollToIndex(blockIndex + 1)
            val result = layout("dictionary_detail_block_${block.start}")
            val rendered = result.layoutInput.text.text
            check(rendered == entry.definition.substring(block.start, block.endExclusive))
            assertTextFits(result)
            observed.append(rendered); end = block.endExclusive
            blockReports.put(JSONObject().put("start", block.start).put("end", end)
                .put("characters", rendered.length).put("sha256", hash(rendered)).put("lines", result.lineCount))
            assertFixedDetailActions()
        }
        check(end == entry.definition.length && observed.toString() == entry.definition)
        t.report.getJSONArray("entries").put(JSONObject().put("entryIndex", index).put("wordSha256", hash(entry.word))
            .put("definitionCharacters", entry.definition.length).put("definitionSha256", hash(entry.definition))
            .put("renderedJoinedSha256", hash(observed.toString())).put("allBlocksMatchOriginal", true)
            .put("allLayoutsFit", true).put("blocks", blockReports))
    }
    private fun assertFixedDetailActions() {
        listOf("dictionary_detail_header", "dictionary_detail_back", "dictionary_detail_close")
            .forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        if (exists("dictionary_detail_sentence")) compose.onNodeWithTag("dictionary_detail_sentence").assertIsDisplayed()
        else compose.onNodeWithTag("dictionary_detail_choose_match").assertIsDisplayed()
    }
    private fun assertDraft() {
        check(field("capture_target") == TARGET_DRAFT && field("capture_sentence") == SENTENCE_DRAFT)
        check(selection("capture_target") == TextRange(TARGET_CURSOR) && selection("capture_sentence") == TextRange(SENTENCE_CURSOR))
    }
    private fun setCursor(tag: String, position: Int) = compose.onNodeWithTag(tag)
        .performSemanticsAction(SemanticsActions.SetSelection) { it(position, position, false) }
    private fun dictionaryReady(query: String) = !exists("capture_dictionary_lookup_loading") &&
        !exists("capture_dictionary_lookup_error") && text("capture_dictionary_word_0")?.trim()?.startsWith(query, true) == true &&
        text("capture_dictionary_definition_0")?.isNotBlank() == true
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun text(tag: String): String? = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
        ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
    private fun field(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun selection(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
    private fun reach(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag).also {
        if (runCatching { it.assertIsDisplayed() }.isFailure) it.performScrollTo()
    }
    private fun layout(tag: String): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }
    private fun assertTextFits(result: TextLayoutResult) {
        check(!result.hasVisualOverflow && !result.didOverflowWidth && !result.didOverflowHeight)
        check((0 until result.lineCount).none(result::isLineEllipsized))
        check((0 until result.lineCount).all { result.getLineLeft(it) >= -1f && result.getLineRight(it) <= result.size.width + 1f })
    }
    private data class DetailPosition(val firstBlock: Int, val firstTop: Float, val lastBlock: Int, val scrollValue: Float) {
        fun json() = JSONObject().put("firstVisibleBlockStart", firstBlock).put("firstBlockTopRelativePx", firstTop)
            .put("lastVisibleBlockStart", lastBlock).put("scrollValue", scrollValue)
    }
    private val blockMatcher = SemanticsMatcher("dictionary detail text block") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("dictionary_detail_block_") == true
    }
    private fun position(): DetailPosition {
        val list = compose.onNodeWithTag("dictionary_detail_list").fetchSemanticsNode()
        val viewport = list.boundsInRoot
        val visible = compose.onAllNodes(blockMatcher).fetchSemanticsNodes().filter {
            it.boundsInRoot.height > 0f && it.boundsInRoot.bottom > viewport.top && it.boundsInRoot.top < viewport.bottom
        }.sortedBy { it.config[SemanticsProperties.TestTag].substringAfterLast('_').toInt() }
        check(visible.isNotEmpty())
        val first = visible.first()
        return DetailPosition(first.config[SemanticsProperties.TestTag].substringAfterLast('_').toInt(),
            first.positionInRoot.y - viewport.top, visible.last().config[SemanticsProperties.TestTag].substringAfterLast('_').toInt(),
            list.config[SemanticsProperties.VerticalScrollAxisRange].value())
    }
    private fun samePosition(before: DetailPosition, after: DetailPosition, density: Float) =
        before.firstBlock == after.firstBlock && before.lastBlock == after.lastBlock && abs(before.firstTop - after.firstTop) <= 3f * density
    private fun assertVisibleBlocks(entry: DictionaryEntry) {
        val ranges = DictionaryPresentation.blocks(entry.definition).associateBy { it.start }
        compose.onAllNodes(blockMatcher).fetchSemanticsNodes().forEach { node ->
            val tag = node.config[SemanticsProperties.TestTag]
            check(layout(tag).layoutInput.text.text == ranges.getValue(tag.substringAfterLast('_').toInt()).text)
        }
    }
    private fun keyboardVisible(): Boolean {
        var visible = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            visible = WindowInspector.getGlobalWindowViews().any { it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        }
        return visible
    }
    private fun requireOfflineNoKey(app: LexiconApplication) {
        check(app.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
        check(!runBlocking { app.repository.hasApiKey() })
    }
    private fun openDatabase(file: File, queryOnly: Boolean = false): SQLiteConnection {
        check(file.isFile)
        val db = BundledSQLiteDriver().open(file.absolutePath)
        try { db.exec("PRAGMA busy_timeout=5000"); if (queryOnly) db.exec("PRAGMA query_only=ON"); return db }
        catch (error: Throwable) { db.close(); throw error }
    }
    private fun snapshot(file: File): Map<String, List<List<String?>>> = openDatabase(file, true).use { db ->
        db.exec("BEGIN DEFERRED TRANSACTION")
        try { snapshot(db).also { db.exec("COMMIT") } }
        catch (error: Throwable) { runCatching { db.exec("ROLLBACK") }; throw error }
    }
    private fun snapshot(db: SQLiteConnection): Map<String, List<List<String?>>> {
        val tables = db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use {
            buildList { while (it.step()) add(it.getText(0)) }
        }
        return tables.associateWith { table ->
            require(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            db.prepare("SELECT * FROM \"$table\"").use { statement ->
                buildList { while (statement.step()) add((0 until statement.getColumnCount()).map { if (statement.isNull(it)) null else statement.getText(it) }) }
                    .sortedBy { JSONArray(it).toString() }
            }
        }
    }
    private fun transaction(db: SQLiteConnection, block: () -> Unit) {
        db.exec("BEGIN IMMEDIATE TRANSACTION")
        try { block(); db.exec("COMMIT") }
        catch (error: Throwable) { runCatching { db.exec("ROLLBACK") }; throw error }
    }
    private fun SQLiteConnection.exec(sql: String) { prepare(sql).use { it.step() } }
    private fun changes(db: SQLiteConnection) = db.prepare("SELECT changes()").use { check(it.step()); it.getLong(0) }
    private fun waitForQuietRows(file: File): Map<String, List<List<String?>>> {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var previous = snapshot(file)
        var quietSince = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(200)
            val next = snapshot(file)
            if (next != previous) { previous = next; quietSince = SystemClock.elapsedRealtime() }
            else if (SystemClock.elapsedRealtime() - quietSince >= 2_000) return next
        }
        error("Writes did not become quiet within the bounded observation")
    }
    private fun existingWriterJobs(t: Trial): List<Job> {
        val jobs = linkedSetOf<Job>()
        t.activity?.onActivity { activity ->
            val provider = ViewModelProvider(activity, object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = error("Existing ViewModels only")
            })
            fun retain(find: () -> ViewModel) { runCatching { find().viewModelScope.coroutineContext[Job] }.getOrNull()?.let(jobs::add) }
            retain { provider[LexiconViewModel::class.java] }
            retain { provider[ReadingViewModel::class.java] }
        }
        t.report.put("joinedExistingViewModelJobs", jobs.size)
        return jobs.toList()
    }
    private fun tableHashes(rows: Map<String, List<List<String?>>>) = JSONObject().apply {
        rows.forEach { (table, values) -> put(table, JSONObject().put("rows", values.size).put("sha256", hash(JSONArray(values.map { JSONArray(it) }).toString()))) }
    }
    private fun fileSignatures(folder: File): Map<String, String> = if (!folder.isDirectory) emptyMap() else
        folder.walkTopDown().filter { it.isFile }.map { it.relativeTo(folder).invariantSeparatorsPath to "${it.length()}:${hash(it)}" }.toMap().toSortedMap()
    private fun screenshot(t: Trial, name: String) {
        compose.waitForIdle()
        val file = File(t.output, name)
        check(!file.exists())
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() }
        t.report.getJSONArray("screenshots").put(name)
        File(t.output, "device-trial.json").writeText(t.report.toString(2))
    }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun elapsedMs(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
    private companion object {
        const val WORLD_CHARACTERS = 10_664
        const val TARGET_DRAFT = " world "
        const val SENTENCE_DRAFT = "This self-authored draft keeps its cursor and does not change the saved capture."
        const val TARGET_CURSOR = 2
        const val SENTENCE_CURSOR = 16
    }
}
