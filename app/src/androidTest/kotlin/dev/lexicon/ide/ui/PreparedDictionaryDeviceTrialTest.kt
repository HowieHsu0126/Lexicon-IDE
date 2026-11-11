package dev.lexicon.ide.ui

import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.SystemClock
import android.os.Process
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.compose.ui.geometry.Rect
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
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * dictionaryPreviewTrial=1, variant=day-font100|night-font200, evidenceRun=<unique name>,
 * phase=ready, expectedUserId=10. Root freezes APK identity and the previous 1.32 receipt report.
 * Independent word ranges are frozen below. No cache is rebuilt or removed.
 */
@RunWith(AndroidJUnit4::class)
class PreparedDictionaryDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun installedMdxLookupAndDetailPreserveOwnedDraftsAndAllOriginalRows() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Dictionary preview trial is opt-in", args.getString("dictionaryPreviewTrial") == "1")
        val variant = requireNotNull(args.getString("variant")).also { require(it in setOf("day-font100", "night-font200")) }
        val run = requireNotNull(args.getString("evidenceRun")).also { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) }
        val phase = requireNotNull(args.getString("phase")).also { require(it == "ready") }
        val expectedVersion = requireNotNull(args.getString("expectedVersionName"))
        val expectedVersionCode = requireNotNull(args.getString("expectedVersionCode")).toLong()
        val expectedApkSha = requireSha(args.getString("expectedApkSha256"))
        val previousRun = requireNotNull(args.getString("preparedFromRun"))
        val previousVariant = requireNotNull(args.getString("preparedFromVariant"))
        val previousReportSha = requireSha(args.getString("preparedFromReportSha256"))
        val previousApkSha = requireSha(args.getString("preparedFromApkSha256"))
        require((args.getString("expectedUserId") ?: "10").toInt() == 10)
        val app = instrumentation.targetContext.applicationContext as LexiconApplication
        val actualUser = Regex("^/data/user/(\\d+)/").find(app.applicationInfo.dataDir)?.groupValues?.get(1)?.toInt()
        check(actualUser == 10) { "Dictionary trial may not run against the original user" }
        val output = File(app.filesDir, "validation-1.33.0/dictionary-preview/$phase/$variant/$run")
        check(!output.exists() && output.mkdirs()) { "Use a new evidenceRun; preserve previous evidence" }
        val reportFile = File(output, "device-trial.json")
        val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
        val actualApkSha = hash(File(instrumentation.targetContext.applicationInfo.sourceDir))
        check(packageInfo.versionName == expectedVersion && packageInfo.longVersionCode == expectedVersionCode && actualApkSha == expectedApkSha) {
            "The installed application does not match the frozen preview trial identity"
        }
        val report = JSONObject().put("schema", "lexicon.dictionary.preview.trial.v1")
            .put("previewContractVersion", 2).put("completeMeaningRequiredForBothWords", true)
            .put("status", "started").put("phase", phase).put("variant", variant).put("evidenceRun", run)
            .put("version", packageInfo.versionName).put("versionCode", packageInfo.longVersionCode)
            .put("apkSha256", actualApkSha)
            .put("androidUserId", actualUser).put("actualMainActivity", true)
            .put("processId", Process.myPid()).put("processStartElapsedMs", Process.getStartElapsedRealtime())
            .put("repositoryScope", "Unfiltered application Repository in dedicated Android user 10")
            .put("materialOrigin", "Two self-authored pending captures; actual installed personal MDX")
            .put("databaseDriver", "androidx.sqlite.driver.bundled.BundledSQLiteDriver")
            .put("jsonContainsLearningText", false).put("aiActions", 0).put("ratingsRequested", 0)
            .put("originalRowsRestoredOrReplaced", 0).put("originalSnapshotKeptOnlyInMemory", true)
            .put("latencyScope", "Observed UI action-to-ready including Compose test-driver waits, polling, editing debounce and animation; not first-visible-frame latency, P95 or an 80ms result")
            .put("firstLookupScope", "No Repository lookup or backend opener before the first UI lookup; preservation hashes may warm files, so this is not cold-filesystem or cold-storage latency")
            .put("cacheScope", "Existing prepared index only; no raw/building phase, cache deletion, index replacement or deliberate preparation")
            .put("previewOracleScope", "Frozen independent source UTF-16 ranges and SHA-256, never expected output computed by the production preview selector")
            .put("preparedFilesRemovedOrReplacedByTrial", 0)
            .put("requestedPhaseObserved", false).put("crossProcessReceiptBindingVerified", false)
            .put("screenshots", JSONArray()).put("entries", JSONArray()).put("observedLatencies", JSONArray())
            .put("backendObservations", JSONArray())
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
            val initialBackend = backend(app)
            check(!initialBackend.jobActive && initialBackend.rawIdentity == null && initialBackend.preparedIdentity == null) {
                "Use a fresh instrumentation process; do not clear or cancel existing preparation to force the phase"
            }
            val selectedOnDisk = File(folder, "selected.txt").takeIf { it.isFile }?.readText()?.trim().orEmpty()
            check(selectedOnDisk.matches(Regex("[a-f0-9]{64}"))) { "An installed dictionary selection is required" }
            val preflightReceipt = receiptDescriptor(folder, selectedOnDisk, initialBackend.parserVersion)
            check(selectedOnDisk == SOURCE_SHA) { "The selected MDX differs from the frozen independent preview oracle" }
            val deferred = mapOf(preflightReceipt.relativeArtifact to "${preflightReceipt.bytes}:${preflightReceipt.artifactSha}")
            val filesBeforeSetup = fileSignatures(folder, deferred)
            check(filesBeforeSetup.containsKey("catalog.json") && filesBeforeSetup.containsKey("selected.txt") &&
                filesBeforeSetup.keys.any { !it.startsWith("prepared/") && it.endsWith(".mdx", true) }) {
                "This trial requires an already installed MDX; it does not install or replace user dictionary files"
            }
            val receiptRelative = "prepared/$selectedOnDisk/${initialBackend.parserVersion}/receipt.json"
            check(receiptRelative in filesBeforeSetup) {
                "An existing prepared receipt is required; preserve caches instead of preparing another index"
            }
            report.put("backendBeforeSetup", initialBackend.json())
                .put("expectedPreparedReceiptPresentBeforeSetup", receiptRelative in filesBeforeSetup)
                .put("selectedPreparedArtifactHashedBeforeFirstUiLookup", false)
                .put("deferredPreparedSignatureScope", "Existing selected artifact uses the pre-existing builder receipt SHA and file stat until the first production UI open; full independent byte comparison follows UI use")
            val setupStarted = SystemClock.elapsedRealtimeNanos()
            val installed = runBlocking { repository.installedDictionaries() }
            val dictionary = runBlocking { repository.dictionaryStatus() }
            report.put("dictionarySetupObservedMs", elapsedMs(setupStarted))
                .put("dictionarySetupScope", "Normal installedDictionaries/status; may copy and validate packaged MDX; no lookup")
            check(dictionary.ready && dictionary.format == "MDX" && dictionary.id.isNotBlank())
            val selected = installed.single { it.id == dictionary.id }
            val selectedFile = File(folder, selected.fileName)
            check(selectedFile.isFile && hash(selectedFile) == dictionary.id)
            check(dictionary.id == selectedOnDisk)
            verifyExistingDictionaryFiles(folder, filesBeforeSetup, deferred.keys)
            val setupBackend = backend(app)
            check(setupBackend.rawIdentity == null && setupBackend.preparedIdentity == null)
            report.put("backendAfterSetup", setupBackend.json())
            val settings = runBlocking { repository.settings.first() }
            val actualNight = app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val effectiveDark = settings.theme == ThemeMode.DARK ||
                (settings.theme == ThemeMode.SYSTEM && actualNight == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            check(effectiveDark == (variant == "night-font200")) { "The rendered theme must match the requested variant" }
            report.put("selectedDictionarySha256", dictionary.id).put("dictionaryBytes", selectedFile.length())
                .put("dictionaryEntries", dictionary.entryCount).put("dictionaryMetadataName", dictionary.name)
                .put("dictionaryEditionIndependentlyVerified", false)
                .put("dictionaryFilesBeforeSetup", JSONObject(filesBeforeSetup))
                .put("existingDictionaryFilesPreservedAfterSetup", true)
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
                ownedCapture("dictionary-preview-$run-world", "world", "This self-authored validation sentence uses world as a lookup target.", started),
                ownedCapture("dictionary-preview-$run-robust", "robust", "This self-authored validation sentence uses robust as a lookup target.", started - 1),
            )
            val t = Trial(app, database, original, output, report, started, captures, dictionary.id, filesBeforeSetup, phase, setupBackend.parserVersion)
            t.preparationJob = setupBackend.job
            trial = t
            report.put("startedAt", started).put("baselineQuietIntervalMs", 2_000)
                .put("originalTableHashes", tableHashes(original)).put("originalTableCount", original.size)
                .put("captureIdentityHashes", JSONArray(captures.map { hash(it.id) }))
                .put("snapshotScope", "All lexicon.sqlite rows after normal Application initialization and dictionary setup")
            verifyEditionWillNotRewriteOriginal(t)
            t.receiptBefore = preflightReceipt.json()
            verifyPreviousProcess(t, previousRun, previousVariant, previousReportSha, previousApkSha)
            observeBackend(t, "before_activity")
            insertOwnedCaptures(t)
            t.stage = "open_first_owned_capture_without_lookup_prewarm"
            t.activity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(20_000) { exists("daily_reading") }
            openPending()
            val beforeFirst = observeBackend(t, "before_first_ui_lookup")
            check(beforeFirst.rawIdentity == null && beforeFirst.preparedIdentity == null)
            val firstReadyMs = openCapture(t, captures[0], "world")
            val afterFirst = observeBackend(t, "after_first_ui_lookup")
            check(afterFirst.preparedIdentity != null && afterFirst.rawIdentity == null) { "Ready receipt was not served by the prepared backend" }
            report.put("requestedPhaseObserved", true)
            t.openedBackend = afterFirst
            report.getJSONArray("observedLatencies").put(JSONObject().put("action", "open_world_capture")
                .put("actionToReadyObservedMs", firstReadyMs).put("expectedReadAfterReady", true))
            val expected = runBlocking { repository.lookup("world") }
            check(expected.isNotEmpty() && expected.first().definition.length == WORLD_CHARACTERS) {
                "The frozen installed dictionary must contain the complete 10664-character world definition"
            }
            t.stage = "verify_first_meaning_preview_visible"
            verifyPreview(t, expected.first(), WORLD_PREVIEW, WORLD_MEANING, "worldPreview")
            report.put("firstLookupNotPrewarmedByTrial", true)
                .put("fullResultIdentity", DictionaryPresentation.identity(dictionary.id, "world", expected))
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
            observeBackend(t, "detail_first")

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
            assertSameOpenBackend(t, observeBackend(t, "after_activity_recreation"))

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
            check(secondExpected.isNotEmpty())
            t.stage = "verify_other_capture_complete_meaning_visible"
            verifyPreview(t, secondExpected.first(), ROBUST_PREVIEW, ROBUST_MEANING, "robustPreview")
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
            assertSameOpenBackend(t, observeBackend(t, "after_ui_actions"))
            t.stage = "await_and_verify_prepared_receipt"
            settlePreparation(t)
            assertSameOpenBackend(t, observeBackend(t, "after_preparation_settled"))
            val finalReceipt = preparedReceipt(t)
            check(finalReceipt.toString() == t.receiptBefore?.toString()) { "The ready phase must not replace an existing trusted artifact" }
            report.put("preparedReceipt", finalReceipt).put("preparedArtifactIndependentlyHashedAfterUi", true)
                .put("preparationSettled", true)
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
                    val dictionaryFolder = File(app.noBackupFilesDir, "dictionary")
                    verifyExistingDictionaryFiles(dictionaryFolder, t.dictionaryFiles)
                    val finishBackend = observeBackend(t, "preservation_check")
                    val derived = derivedFiles(t, stable = !finishBackend.jobActive)
                    report.put("derivedFilesAfterTrial", derived)
                    check(derived.getJSONArray("files").length() == 0) { "The preview trial must not add or rebuild a prepared index" }
                    check(runBlocking { app.repository.dictionaryStatus() }.id == t.dictionaryId)
                    report.put("finalTableHashes", tableHashes(after))
                        .put("dictionaryFilesPreserved", true).put("preparedFilesAddedByTrial", 0)
                        .put("dictionarySelectionUnchanged", true)
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
        check(passed && preserved) { "Dictionary preview trial failed; inspect its sanitized report" }
    }

    private class Trial(val app: LexiconApplication, val database: File, val original: Map<String, List<List<String?>>>,
        val output: File, val report: JSONObject, val startedAt: Long, val captures: List<Capture>,
        val dictionaryId: String, val dictionaryFiles: Map<String, String>, val phase: String, val parserVersion: String) {
        var activity: ActivityScenario<MainActivity>? = null
        var stage = "preflight"
        var inserted = false
        val day = Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        var expectedEdition: DailyEdition? = null
        var openActions = 0
        var preparationJob: Job? = null
        var openedBackend: BackendSnapshot? = null
        var receiptBefore: JSONObject? = null
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
    private data class PreviewOracle(
        val sourceCharacters: Int, val sourceSha: String, val start: Int, val endExclusive: Int, val previewSha: String,
    ) {
        init {
            require(sourceCharacters > 0 && start in 0 until sourceCharacters && endExclusive in start + 1..sourceCharacters)
            require(sourceSha.matches(Regex("[a-f0-9]{64}")) && previewSha.matches(Regex("[a-f0-9]{64}")))
        }
    }
    private data class MeaningOracle(val start: Int, val endExclusive: Int, val sha: String) {
        init { require(start >= 0 && endExclusive > start && sha.matches(Regex("[a-f0-9]{64}"))) }
    }
    /** Read existing Compose layout only; never query or call the production preview selector here. */
    private fun verifyPreview(t: Trial, entry: DictionaryEntry, oracle: PreviewOracle, meaning: MeaningOracle, key: String) {
        val tag = "capture_dictionary_definition_0"
        compose.onNodeWithTag(tag).assertIsDisplayed()
        val result = layout(tag)
        val rendered = result.layoutInput.text.text
        val observation = JSONObject().put("sourceCharacters", entry.definition.length).put("sourceSha256", hash(entry.definition))
            .put("expectedOriginalStart", oracle.start).put("expectedOriginalEndExclusive", oracle.endExclusive)
            .put("expectedPreviewSha256", oracle.previewSha).put("layoutInputCharacters", rendered.length)
            .put("layoutInputSha256", hash(rendered)).put("exactOriginalRangeEqual", false)
            .put("lineCount", result.lineCount).put("completeMeaningRequired", true)
            .put("firstMeaningPrefixRequired", true).put("firstMeaningPrefixVisible", false)
            .put("completeMeaningVisible", false)
        t.report.put(key, observation)
        check(entry.definition.length == oracle.sourceCharacters && hash(entry.definition) == oracle.sourceSha) {
            "The complete definition differs from the independently frozen source"
        }
        val originalRange = entry.definition.substring(oracle.start, oracle.endExclusive)
        check(hash(originalRange) == oracle.previewSha && rendered == originalRange) {
            "The preview must equal the frozen original range; a matching prefix is insufficient"
        }
        observation.put("exactOriginalRangeEqual", true)

        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val origin = node.positionInRoot
        val rawBounds = Rect(origin.x, origin.y, origin.x + result.size.width, origin.y + result.size.height)
        val clippedBounds = node.boundsInRoot
        val visibleBounds = rawBounds.intersect(clippedBounds)
        check(visibleBounds.width > 0f && visibleBounds.height > 0f && result.lineCount > 0)
        val lineBounds = (0 until result.lineCount).map { line ->
            Rect(origin.x + result.getLineLeft(line), origin.y + result.getLineTop(line),
                origin.x + result.getLineRight(line), origin.y + result.getLineBottom(line))
        }
        val fullyVisibleLines = lineBounds.map { within(it, visibleBounds) }
        observation.put("rawBoundsInRoot", rectJson(rawBounds)).put("clippedBoundsInRoot", rectJson(clippedBounds))
            .put("displayedLines", JSONArray().apply {
                (0 until result.lineCount).forEach { line ->
                    val start = result.getLineStart(line)
                    val visibleEnd = result.getLineEnd(line, visibleEnd = true)
                    put(JSONObject().put("line", line).put("localStart", start).put("localVisibleEndExclusive", visibleEnd)
                        .put("originalStart", oracle.start + start).put("originalVisibleEndExclusive", oracle.start + visibleEnd)
                        .put("ellipsized", result.isLineEllipsized(line)).put("wholeLineInsideVisibleBounds", fullyVisibleLines[line])
                        .put("boundsInRoot", rectJson(lineBounds[line])))
                }
            })
        check(oracle.start <= meaning.start && oracle.endExclusive >= meaning.endExclusive)
        check(hash(entry.definition.substring(meaning.start, meaning.endExclusive)) == meaning.sha)
        val localStart = meaning.start - oracle.start
        val localEnd = meaning.endExclusive - oracle.start
        val glyphs = JSONArray()
        val glyphVisibility = (localStart until localEnd).filterNot { rendered[it].isWhitespace() }.map { offset ->
            val line = result.getLineForOffset(offset)
            val lineEnd = result.getLineEnd(line, visibleEnd = true)
            val box = result.getBoundingBox(offset).translate(origin)
            val glyphVisible = offset >= result.getLineStart(line) && offset < lineEnd && fullyVisibleLines[line] &&
                box.width > 0f && box.height > 0f && within(box, visibleBounds)
            glyphs.put(JSONObject().put("originalOffset", oracle.start + offset).put("line", line)
                .put("originalLineVisibleEndExclusive", oracle.start + lineEnd).put("visible", glyphVisible))
            glyphVisible
        }
        val visible = glyphVisibility.isNotEmpty() && glyphVisibility.all { it }
        observation.put("meaningOriginalStart", meaning.start).put("meaningOriginalEndExclusive", meaning.endExclusive)
            .put("meaningSha256", meaning.sha).put("requiredMeaningCharacters", meaning.endExclusive - meaning.start)
            .put("requiredGlyphVisibility", glyphs).put("firstMeaningPrefixVisible", visible).put("completeMeaningVisible", visible)
            .put("visibilityScope", "The complete first English meaning checked against displayed line ends and clipped glyph geometry; not merely text in the semantics tree")
        check(visible) { "The complete first English meaning is not actually visible in the four-line preview" }
    }
    private fun rectJson(rect: Rect) = JSONObject().put("left", rect.left).put("top", rect.top).put("right", rect.right).put("bottom", rect.bottom)
    private fun within(inner: Rect, outer: Rect): Boolean =
        listOf(inner.left, inner.top, inner.right, inner.bottom, outer.left, outer.top, outer.right, outer.bottom).all(Float::isFinite) &&
            inner.left >= outer.left - 1f && inner.top >= outer.top - 1f && inner.right <= outer.right + 1f && inner.bottom <= outer.bottom + 1f
    private fun requireSha(value: String?): String = requireNotNull(value).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
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
    private data class BackendSnapshot(
        val observedAtElapsedNs: Long, val selectedId: String, val openedId: String,
        val rawIdentity: String?, val preparedIdentity: String?, val parserVersion: String,
        val cacheSelectionGeneration: Long, val taskGeneration: Long,
        val job: Job?, val jobActive: Boolean, val jobCompleted: Boolean?,
    ) {
        fun json() = JSONObject().put("observedAtElapsedNs", observedAtElapsedNs)
            .put("selectedDictionarySha256", selectedId).put("openedDictionarySha256", openedId)
            .put("rawHandleIdentityWithinProcess", rawIdentity ?: JSONObject.NULL)
            .put("preparedHandleIdentityWithinProcess", preparedIdentity ?: JSONObject.NULL)
            .put("parserVersion", parserVersion).put("cacheSelectionGeneration", cacheSelectionGeneration)
            .put("taskGeneration", taskGeneration).put("preparationJobPresent", job != null)
            .put("preparationJobActive", jobActive).put("preparationJobCompleted", jobCompleted ?: JSONObject.NULL)
    }
    /** Observe fields under their actual owners' locks. No initializer, lookup, opener or close is invoked. */
    private fun backend(app: LexiconApplication): BackendSnapshot {
        val store = requireNotNull(reflected(app.repository, "dictionary"))
        val lock = reflected(store, "lock") as Mutex
        return runBlocking { lock.withLock {
            val cache = requireNotNull(reflected(store, "preparedCache"))
            val monitor = requireNotNull(reflected(cache, "scheduling"))
            synchronized(monitor) {
                val job = reflected(cache, "scheduled") as Job?
                fun identity(value: Any?) = value?.let { Integer.toHexString(System.identityHashCode(it)) }
                BackendSnapshot(SystemClock.elapsedRealtimeNanos(), reflected(store, "selectedId") as String,
                    reflected(store, "openedId") as String, identity(reflected(store, "reader")), identity(reflected(store, "prepared")),
                    reflected(cache, "parserVersion") as String,
                    (reflected(cache, "selectionGeneration") as Number).toLong(),
                    (reflected(cache, "taskGeneration") as Number).toLong(), job, job?.isActive == true, job?.isCompleted)
            }
        } }
    }
    private fun reflected(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
    private fun observeBackend(t: Trial, label: String): BackendSnapshot {
        val value = backend(t.app)
        check(value.selectedId == t.dictionaryId && value.parserVersion == t.parserVersion)
        t.report.getJSONArray("backendObservations").put(value.json().put("stage", label))
        return value
    }
    private fun assertSameOpenBackend(t: Trial, current: BackendSnapshot) {
        val first = requireNotNull(t.openedBackend)
        check(current.openedId == t.dictionaryId)
        check(current.rawIdentity == first.rawIdentity && current.preparedIdentity == first.preparedIdentity) {
            "Dictionary use, Activity recreation or background completion changed the retained backend"
        }
    }
    private fun settlePreparation(t: Trial) {
        val current = observeBackend(t, "before_preparation_join")
        val jobs = listOfNotNull(t.preparationJob, current.job).distinct()
        val started = SystemClock.elapsedRealtimeNanos()
        runBlocking { withTimeout(60_000) { jobs.joinAll() } }
        check(!backend(t.app).jobActive) { "Preparation remains active after the bounded observation" }
        t.report.put("preparationJoinObservedMs", elapsedMs(started)).put("joinedPreparationJobs", jobs.size)
            .put("applicationPreparationCancelledByTrial", false)
    }
    private data class ReceiptDescriptor(
        val sourceSha: String, val parser: String, val artifactSha: String, val relativeArtifact: String,
        val bytes: Long, val rowCount: Long, val receiptSha: String,
    ) {
        fun json() = JSONObject().put("sourceSha256", sourceSha).put("parserVersion", parser)
            .put("artifactSha256", artifactSha).put("relativeArtifact", relativeArtifact)
            .put("artifactBytes", bytes).put("rowCount", rowCount).put("receiptSha256", receiptSha)
    }
    /** At preflight only read the small receipt and stat the artifact; never warm its definition pages. */
    private fun receiptDescriptor(folder: File, sourceSha: String, parser: String): ReceiptDescriptor {
        require(sourceSha.matches(Regex("[a-f0-9]{64}")))
        require(parser.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}")))
        val prefix = "prepared/$sourceSha/$parser/"
        val receiptFile = File(folder, prefix + "receipt.json")
        check(receiptFile.isFile && !Files.isSymbolicLink(receiptFile.toPath()) && receiptFile.length() in 1..4096L)
        val receiptBytes = receiptFile.inputStream().use { it.readNBytes(4097) }
        check(receiptBytes.size in 1..4096)
        val value = JSONObject(receiptBytes.toString(Charsets.UTF_8))
        check(value.getInt("format") == 1 && value.getString("sourceSha256") == sourceSha && value.getString("parserVersion") == parser)
        val name = value.getString("artifactFile")
        check(name.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.sqlite")))
        val artifact = File(folder, prefix + name)
        check(artifact.isFile && !Files.isSymbolicLink(artifact.toPath()) && artifact.length() in 1..512L * 1024 * 1024)
        val artifactSha = value.getString("artifactSha256")
        check(artifactSha.matches(Regex("[a-f0-9]{64}")))
        val count = value.getLong("rowCount"); check(count in 1..500_000L)
        return ReceiptDescriptor(sourceSha, parser, artifactSha, prefix + name, artifact.length(), count,
            MessageDigest.getInstance("SHA-256").digest(receiptBytes).joinToString("") { "%02x".format(it) })
    }
    private fun preparedReceipt(t: Trial): JSONObject {
        val folder = File(t.app.noBackupFilesDir, "dictionary")
        val value = receiptDescriptor(folder, t.dictionaryId, t.parserVersion)
        check(value.rowCount == t.report.getLong("dictionaryEntries"))
        check(hash(File(folder, value.relativeArtifact)) == value.artifactSha) { "The published artifact does not match its builder receipt" }
        return value.json()
    }
    private fun verifyPreviousProcess(t: Trial, previousRun: String, previousVariant: String, previousReportSha: String, previousApkSha: String) {
        require(previousRun.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")))
        require(previousVariant in setOf("day-font100", "night-font200"))
        val file = File(t.app.filesDir, "validation-1.32.0/dictionary-prepared/building/$previousVariant/$previousRun/device-trial.json")
        check(file.isFile && file.length() in 1..1_000_000L)
        check(hash(file) == previousReportSha) { "The prior preparation report differs from its frozen identity" }
        val previous = JSONObject(file.readText())
        check(previous.getString("schema") == "lexicon.dictionary.prepared.trial.v1" && previous.getString("status") == "passed")
        check(previous.getBoolean("originalDataPreserved") && previous.getString("phase") == "building")
        check(previous.getString("version") == "1.32.0" && previous.getLong("versionCode") == 36L)
        check(previous.getString("apkSha256") == previousApkSha) { "The preparation was not produced by the frozen 1.32 application" }
        val receipt = previous.getJSONObject("preparedReceipt")
        val current = requireNotNull(t.receiptBefore)
        listOf("sourceSha256", "parserVersion", "artifactSha256", "relativeArtifact", "receiptSha256").forEach { check(receipt.getString(it) == current.getString(it)) }
        listOf("artifactBytes", "rowCount").forEach { check(receipt.getLong(it) == current.getLong(it)) }
        check(previous.getInt("processId") != Process.myPid() || previous.getLong("processStartElapsedMs") != Process.getStartElapsedRealtime())
        t.report.put("preparedFromRun", previousRun).put("preparedFromVariant", previousVariant)
            .put("preparedFromReportSha256", previousReportSha).put("preparedFromApkSha256", previousApkSha)
            .put("preparedFromVersion", previous.getString("version")).put("crossVersionPreparedReuse", previousApkSha != t.report.getString("apkSha256"))
            .put("crossProcessReceiptBindingVerified", true).put("previousProcessId", previous.getInt("processId"))
    }
    private fun verifyExistingDictionaryFiles(folder: File, original: Map<String, String>, deferred: Set<String> = emptySet()) {
        original.forEach { (path, expected) ->
            val file = File(folder, path)
            check(file.isFile && !Files.isSymbolicLink(file.toPath())) { "An existing dictionary file disappeared" }
            if (path in deferred) check(file.length() == expected.substringBefore(':').toLong())
            else check(stableSignature(file) == expected) { "An existing dictionary file changed" }
        }
        val originalNonPrepared = original.keys.filterNot { it.startsWith("prepared/") }.toSet()
        val currentNonPrepared = folder.walkTopDown().filter { it.isFile }.map { it.relativeTo(folder).invariantSeparatorsPath }
            .filterNot { it.startsWith("prepared/") }.toSet()
        check(currentNonPrepared == originalNonPrepared) { "Only prepared derived additions are allowed" }
    }
    private fun derivedFiles(t: Trial, stable: Boolean): JSONObject {
        val folder = File(t.app.noBackupFilesDir, "dictionary")
        val prefix = "prepared/${t.dictionaryId}/${t.parserVersion}/"
        val added = folder.walkTopDown().filter { it.isFile }.filter {
            it.relativeTo(folder).invariantSeparatorsPath !in t.dictionaryFiles
        }.toList()
        val files = JSONArray()
        added.forEach { file ->
            val path = file.relativeTo(folder).invariantSeparatorsPath
            check(path.startsWith(prefix) && !Files.isSymbolicLink(file.toPath())) { "An unrelated dictionary file was added" }
            val item = JSONObject().put("relativePath", path).put("bytes", file.length()).put("lastModified", file.lastModified())
            if (stable) {
                val signature = stableSignature(file)
                item.put("sha256", signature.substringAfter(':')).put("bytes", signature.substringBefore(':').toLong())
            } else item.put("sha256", JSONObject.NULL)
            files.put(item)
        }
        return JSONObject().put("backgroundSettled", stable).put("files", files)
            .put("totalObservedBytes", added.sumOf { it.length() }).put("activePartialBytesHashed", false)
    }
    private fun stableSignature(file: File): String {
        val bytes = file.length(); val modified = file.lastModified()
        val digest = hash(file)
        check(file.isFile && file.length() == bytes && file.lastModified() == modified) { "File changed while checking preservation" }
        return "$bytes:$digest"
    }
    private fun fileSignatures(folder: File, deferred: Map<String, String> = emptyMap()): Map<String, String> = if (!folder.isDirectory) emptyMap() else
        folder.walkTopDown().filter { it.isFile }.map {
            val relative = it.relativeTo(folder).invariantSeparatorsPath
            relative to (deferred[relative] ?: stableSignature(it))
        }.toMap().toSortedMap()
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
        const val SOURCE_SHA = "898cf9f1c4bf7a7c52669f3e25a5aa8396092b9b77287ba4b7cdc9a16fc77e73"
        const val WORLD_CHARACTERS = 10_664
        val WORLD_PREVIEW = PreviewOracle(WORLD_CHARACTERS,
            "bbb23ba511eb18ee1f2aa3471aeb9c06236541c2cf0eb3a7ba43c6d7fd8f22f9", 69, 189,
            "4e42c5c895ed97000bef43b1201df83e1e25075d4beb80feb7a598d8bea1fda9")
        val ROBUST_PREVIEW = PreviewOracle(647,
            "cd2c7e4e510d5c6fe054baecbc4735ab23396c6b641c116313ccd5526c16178f", 38, 130,
            "389ab069a840606882d852a8c3d15e980a88439a0552b1c390a3b44088b86527")
        val WORLD_MEANING = MeaningOracle(69, 149,
            "d60a9c678a3bb719a8484a37216cee4b66b9574a6c80618091fc7c1324f90e84")
        val ROBUST_MEANING = MeaningOracle(38, 75,
            "00a56fa571f40044d36500fda2cf66c16d41f778da2f8475f76864483b0b19cf")
        const val TARGET_DRAFT = " world "
        const val SENTENCE_DRAFT = "This self-authored draft keeps its cursor and does not change the saved capture."
        const val TARGET_CURSOR = 2
        const val SENTENCE_CURSOR = 16
    }
}
