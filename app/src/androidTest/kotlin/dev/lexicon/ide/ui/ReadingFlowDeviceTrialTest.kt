package dev.lexicon.ide.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.SystemClock
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
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
 * Opt-in actual-app journey: readingFlowTrial=1, uniqueRun=<fresh name>, expectedUserId=10.
 * Optional variant=day-font100 (default) or night-font200; configure the device before launch.
 * Optional toolbarAppearanceTrial=1 adds phase=before|after toolbar evidence after the original journey.
 * Optional typographyTrial=1 observes real appearance changes under its own frozen reflow contract.
 * Uses only real touches for navigation, scrolling and word lookup. Text geometry is observed,
 * never changed. Four self-authored source rows are the only inserted learning fixture.
 */
@RunWith(AndroidJUnit4::class)
class ReadingFlowDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun middleArticleLookupAndModeSwitchPreserveTheContinuousReadingPosition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Continuous reading observation is opt-in", args.getString("readingFlowTrial") == "1")
        val variant = (args.getString("variant") ?: "day-font100").also {
            require(it in setOf("day-font100", "night-font200"))
        }
        val expectedDark = variant == "night-font200"
        val expectedFontScale = if (expectedDark) 2f else 1f
        val maximumMiddleScrolls = if (expectedDark) 64 else 24
        val toolbarAppearance = args.getString("toolbarAppearanceTrial") == "1"
        val typographyTrial = args.getString("typographyTrial") == "1"
        val requestedPhase = if (toolbarAppearance || typographyTrial) requireNotNull(args.getString("phase")).also {
            require(it in setOf("before", "after"))
        } else null
        val toolbarPhase = requestedPhase.takeIf { toolbarAppearance }
        val typographyPhase = requestedPhase.takeIf { typographyTrial }
        val run = requireNotNull(args.getString("uniqueRun") ?: args.getString("evidenceRun")).also {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")))
        }
        require((args.getString("expectedUserId") ?: "10").toInt() == 10)
        val app = instrumentation.targetContext.applicationContext as LexiconApplication
        val actualUser = Regex("^/data/user/(\\d+)/").find(app.applicationInfo.dataDir)?.groupValues?.get(1)?.toInt()
        check(actualUser == 10) { "Reading flow is restricted to dedicated Android user 10" }
        val outputRoot = if (typographyTrial) "validation-reader-reflow-2026-11-06" else "validation-reader-flow-2026-11-05"
        val output = File(app.filesDir, "$outputRoot/$run")
        check(!output.exists() && output.mkdirs()) { "Use a new uniqueRun; preserve previous observations" }
        val reportFile = File(output, "device-trial.json")
        val started = System.currentTimeMillis()
        val fixture = Fixture(run, started)
        val version = app.packageManager.getPackageInfo(app.packageName, 0)
        val report = JSONObject().put("schema", "lexicon.reader.flow.trial.v1").put("status", "started")
            .put("uniqueRun", run).put("variant", variant).put("startedAt", started).put("androidUserId", actualUser)
            .put("version", version.versionName).put("versionCode", version.longVersionCode)
            .put("apkSha256", hash(File(instrumentation.targetContext.applicationInfo.sourceDir)))
            .put("actualMainActivity", true).put("repositoryScope", "Unfiltered application Repository in dedicated Android user 10")
            .put("materialOrigin", "Self-authored long article embedded in test source; not publisher or comprehension evidence")
            .put("automatedJourney", true).put("humanReadingPerformanceMeasured", false)
            .put("databaseDriver", "androidx.sqlite.driver.bundled.BundledSQLiteDriver")
            .put("jsonContainsLearningText", false).put("aiActions", 0).put("ratingsRequested", 0)
            .put("capturesSaved", 0).put("originalRowsRestoredOrReplaced", 0)
            .put("observations", JSONArray()).put("touches", JSONArray()).put("differences", JSONArray()).put("screenshots", JSONArray())
            .put("protocolFrozenBeforeUi", JSONObject()
                .put("variant", variant).put("expectedFontScale", expectedFontScale).put("expectedDarkTheme", expectedDark)
                .put("toolbarAppearanceTrial", toolbarAppearance).put("toolbarAppearancePhase", toolbarPhase ?: JSONObject.NULL)
                .put("typographyTrial", typographyTrial).put("typographyPhase", typographyPhase ?: JSONObject.NULL)
                .put("typographyAnchor", "First fully visible English word on the text line intersecting viewport y=42%; skip punctuation/space from lineStart, otherwise nearest fully visible nonempty line. Freeze UTF-16 range before each setting change; never search the word after reflow.")
                .put("typographyChanges", JSONArray(listOf("size:18sp->approximately26sp", "lineHeight:1.8->approximately2.4", "font:serif->mono")))
                .put("typographySizeAcceptedSp", JSONArray(listOf(25f, 27f)))
                .put("typographyLineHeightAccepted", JSONArray(listOf(2.29f, 2.51f)))
                .put("typographyLineTopTolerancePx", 2f).put("typographyStableMs", STABLE_MS)
                .put("typographyRequireSameViewportHeight", true).put("typographyRequiredNavigationPhase", "none")
                .put("typographySettingsOwnership", "Only an initially absent settings row, created by a real UI change and matched to an observed canonical JSON state; original rows never overwritten")
                .put("toolbarLabels", JSONArray(TOOLBAR_LABELS))
                .put("toolbarGeometryTolerancePx", 1f)
                .put("toolbarGeometryOracle", "complete_label_geometry_2026-11-06")
                .put("toolbarTouchMinimumDp", 48f).put("toolbarTouchOverlapTolerancePx", 1f)
                .put("toolbarFreshRevealTimeoutMs", 5_000)
                .put("toolbarAfterContract", "All four labels and button areas fully visible; touch Aa, then close appearance with the original 2px/1200ms position contract and no IME")
                .put("bodySha256", fixture.body.contentHash).put("wordCount", fixture.wordCount)
                .put("paragraphCount", fixture.paragraphs.size).put("targetParagraph", TARGET_PARAGRAPH)
                .put("targetStart", fixture.targetStart).put("targetEnd", fixture.targetEnd)
                .put("targetSentenceStart", fixture.selection.sentenceStart).put("targetSentenceEnd", fixture.selection.sentenceEnd)
                .put("stableWindowMs", STABLE_MS).put("positionTolerancePx", POSITION_TOLERANCE)
                .put("minimumObservedScrollPx", MOVEMENT_FLOOR).put("maximumMiddleScrolls", maximumMiddleScrolls)
                .put("maximumSkimContinuationScrolls", 5).put("swipeDurationMs", SWIPE_MS)
                .put("swipeInteriorFractions", JSONArray(listOf(.28, .72))))
        reportFile.writeText(report.toString(2))
        var trial: Trial? = null
        var journeyPassed = false
        var preserved = false
        var toolbarObserved = false
        var toolbarDesiredBehavior = false
        var typographyObserved = false
        var typographyDesiredBehavior = false
        val toolbarReport = JSONObject().put("enabled", toolbarAppearance).put("phase", toolbarPhase ?: JSONObject.NULL)
            .put("scope", "Additional toolbar observation, separate from the original reading journey")
            .put("appearanceRoundTripVerified", false)
        report.put("toolbarAppearance", toolbarReport)
        val typographyReport = JSONObject().put("enabled", typographyTrial).put("phase", typographyPhase ?: JSONObject.NULL)
            .put("scope", "Additional typography reflow observation; original journey and no-change toolbar round trip stay separate")
            .put("stages", JSONArray()).put("settingsStates", JSONArray())
        report.put("typography", typographyReport)
        try {
            requireOfflineNoKey(app)
            check(abs(app.resources.configuration.fontScale - expectedFontScale) < .02f) { "System font scale must match the requested variant" }
            check(fixture.wordCount >= 1000 && fixture.paragraphs.size == 12)
            check(fixture.body.text.substring(fixture.targetStart, fixture.targetEnd) == "world")
            check(ParagraphFocus.atOffset(fixture.paragraphs, fixture.targetStart) == TARGET_PARAGRAPH)
            val repository = app.repository
            runBlocking { repository.initialize() }
            val setupStarted = SystemClock.elapsedRealtimeNanos()
            val installed = runBlocking { repository.installedDictionaries() }
            val dictionary = runBlocking { repository.dictionaryStatus() }
            report.put("dictionarySetupObservedMs", elapsedMs(setupStarted))
                .put("dictionarySetupScope", "Normal packaged dictionary setup/status; no query before the visible word lookup")
            check(dictionary.ready && dictionary.format == "MDX")
            val selected = installed.single { it.id == dictionary.id }
            val dictionaryFolder = File(app.noBackupFilesDir, "dictionary")
            check(hash(File(dictionaryFolder, selected.fileName)) == dictionary.id)
            val dictionaryFiles = fileSignatures(dictionaryFolder)
            val settings = runBlocking { repository.settings.first() }
            val actualNight = app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            val effectiveDark = settings.theme == ThemeMode.DARK ||
                (settings.theme == ThemeMode.SYSTEM && actualNight == Configuration.UI_MODE_NIGHT_YES)
            check(effectiveDark == expectedDark) { "The rendered theme must match the requested variant" }
            check(settings.reader.comfort != ReadingComfort.HIGH_CONTRAST) { "Intensive paragraph focus must be visible for this observation" }
            report.put("selectedDictionarySha256", dictionary.id).put("dictionaryFilesBefore", JSONObject(dictionaryFiles))
                .put("actualFontScale", app.resources.configuration.fontScale)
                .put("actualNightMode", actualNight).put("effectiveDark", effectiveDark)
                .put("theme", settings.theme.name).put("readerPreferencesSha256", hash(codec.encodeToString(ReaderPreferences.serializer(), settings.reader)))
            val database = app.getDatabasePath("lexicon.sqlite")
            val original = waitForQuietRows(database)
            if (typographyTrial) {
                check(original.getValue("preferences").none { it[0] == "settings" }) { "Typography observation must not change an original settings row" }
                check(settings == AppSettings()) { "An absent settings row must resolve to the untouched defaults" }
                check(settings.reader.font == ReaderFont.SERIF && settings.reader.sizeSp == 18f && settings.reader.lineHeight == 1.8f)
                typographyReport.put("originalSettingsRowAbsent", true)
                    .put("initialSettingsSha256", hash(codec.encodeToString(AppSettings.serializer(), settings)))
                    .put("initialReaderPreferences", JSONObject(codec.encodeToString(ReaderPreferences.serializer(), settings.reader)))
            }
            listOf("articles", "article_sources", "article_bodies", "captures", "cards", "review_logs", "ai_jobs").forEach {
                check(original.getValue(it).isEmpty()) { "Dedicated reading user contains existing learning materials" }
            }
            val t = Trial(app, database, original, fixture, output, report, dictionary.id, dictionaryFiles, started)
            if (typographyTrial) t.typographyInitialSettings = settings
            trial = t
            report.put("originalTableHashes", tableHashes(original)).put("originalSnapshotKeptOnlyInMemory", true)
                .put("baselineQuietIntervalMs", 2_000)
            verifyEditionWillNotRewriteOriginal(t)
            insertFixture(t)

            t.stage = "open_owned_article_from_history"
            t.activity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(20_000) { exists("daily_reading") }
            touch(t, compose.onNodeWithText("读过的文章"), "open_article_history")
            compose.waitUntil(15_000) { exists("article_history_list") }
            t.readerOpened = true
            touch(t, compose.onNodeWithText(fixture.article.title), "open_owned_article")
            compose.waitUntil(20_000) { exists("zen_article_text") && readerReady() }
            val entry = stable(t, "article_entry", "01-article-entry.png")
            check(entry.mode == StudyMode.INTENSIVE)

            t.stage = "read_to_middle_with_real_swipes"
            var middle: Observation? = null
            repeat(maximumMiddleScrolls) { attempt ->
                if (middle != null) return@repeat
                if (targetSafelyVisible(t)) {
                    middle = stable(t, "middle_before_lookup", "02-middle-before-lookup.png")
                } else {
                    val bounds = targetBounds(t)
                    val viewport = readerViewportBounds()
                    val direction = if (bounds.top < viewport.top + edgeMargin(t)) "toward_start" else "toward_end"
                    swipeReader(t, direction, "middle_scroll_${attempt + 1}")
                    waitReaderSettled()
                }
            }
            if (middle == null && targetSafelyVisible(t)) middle = stable(t, "middle_before_lookup", "02-middle-before-lookup.png")
            val beforeLookup = requireNotNull(middle) { "Real scrolling did not expose the frozen middle target" }
            check(beforeLookup.scroll > MOVEMENT_FLOOR && targetSafelyVisible(t))
            t.stage = "tap_frozen_middle_word_and_lookup"
            val target = targetBounds(t)
            val viewport = readerViewportBounds()
            val local = target.center - viewport.topLeft
            val lookupStarted = SystemClock.elapsedRealtimeNanos()
            t.captureOpens++
            viewportTouch(t, local, "lookup_middle_word")
            compose.waitUntil(30_000) { exists("capture_target") && dictionaryReady() }
            report.put("lookupTouchToReadyObservedMs", elapsedMs(lookupStarted))
                .put("lookupTimingScope", "Includes touch injection and test-driver waits; not first-visible-frame latency or a human reading measurement")
            check(field("capture_target") == "world")
            check(field("capture_sentence") == fixture.selection.sentence)
            check(compose.onAllNodesWithText("原文引用").fetchSemanticsNodes().isNotEmpty())
            check(compose.onAllNodesWithText(SourceRef(fixture.article.title, fixture.source.name).display()).fetchSemanticsNodes().isNotEmpty())
            check(!keyboardVisible()) { "Read-only word lookup unexpectedly opened the keyboard" }
            val expectedEntries = runBlocking { repository.lookup("world") }
            val preview = text("capture_dictionary_definition_0")
            check(expectedEntries.isNotEmpty() && preview.isNotBlank() && expectedEntries.first().definition.startsWith(preview))
            report.put("lookupOriginalSentenceSha256", hash(field("capture_sentence")))
                .put("lookupTargetStart", fixture.targetStart).put("lookupTargetEnd", fixture.targetEnd)
                .put("lookupContextMatchesFrozenRange", true).put("lookupDidNotOpenIme", true)
                .put("captureScope", "Unsaved UI target and exact source sentence; no claim of a persisted capture range")
            screenshot(t, "03-middle-lookup.png")
            t.stage = "close_lookup_without_leaving_middle"
            touch(t, compose.onNodeWithTag("capture_close"), "close_lookup")
            compose.waitUntil(15_000) { !exists("capture_target") && readerReady() }
            val afterLookup = stable(t, "middle_after_lookup", "04-middle-after-lookup.png")
            assertPosition(t, beforeLookup, afterLookup, "lookup_round_trip")
            check(afterLookup.focus == TARGET_PARAGRAPH && afterLookup.viewportParagraph == TARGET_PARAGRAPH)
            verifyNoLearningWrites(t)

            t.stage = "open_skim_tools_in_place"
            openChrome(t, "show_middle_tools")
            touch(t, compose.onNodeWithText("阅读工具"), "open_reading_tools_without_header_scroll")
            compose.waitUntil(15_000) { exists("reader_tools_sheet") }
            touch(t, compose.onNodeWithTag("reader_tab_略读"), "choose_skim_tools")
            compose.onNodeWithTag("reader_tools_primary").assertTextContains("开始略读")
            check(!keyboardVisible())
            screenshot(t, "05-skim-tools-at-middle.png")
            touch(t, compose.onNodeWithTag("reader_tools_primary"), "start_skim_in_place")
            compose.waitUntil(15_000) { !exists("reader_tools_sheet") && readerReady() && mode() == StudyMode.SKIM }
            val skimStart = stable(t, "skim_start_same_position", "06-skim-start.png")
            assertPosition(t, afterLookup, skimStart, "start_skim")
            val skimResume = awaitResume(t, skimStart, StudyMode.SKIM)
            report.put("skimResume", resumeEvidence(skimResume))

            t.stage = "continue_reading_in_skim"
            var skimContinued: Observation? = null
            repeat(5) { attempt ->
                if (skimContinued != null) return@repeat
                swipeReader(t, "toward_end", "skim_continue_${attempt + 1}")
                val current = stable(t, "skim_scroll_${attempt + 1}", "07-skim-scroll-${attempt + 1}.png")
                if (current.viewportParagraph > skimStart.viewportParagraph) skimContinued = current
            }
            val afterSkimScroll = requireNotNull(skimContinued) { "Actual skim swipes did not reach a later paragraph" }
            check(afterSkimScroll.scroll - skimStart.scroll > MOVEMENT_FLOOR &&
                skimStart.textY - afterSkimScroll.textY > MOVEMENT_FLOOR)
            t.stage = "return_to_intensive_in_place"
            openChrome(t, "show_skim_mode_control")
            touch(t, compose.onNodeWithText("略读", substring = false), "switch_back_to_intensive")
            compose.waitUntil(15_000) { readerReady() && mode() == StudyMode.INTENSIVE }
            val final = stable(t, "intensive_after_skim_scroll", "08-returned-to-intensive.png")
            assertPosition(t, afterSkimScroll, final, "return_to_intensive")
            check(final.focus == final.viewportParagraph)
            val finalResume = awaitResume(t, final, StudyMode.INTENSIVE)
            check(finalResume.generation == skimResume.generation && finalResume.sequence > skimResume.sequence)
            report.put("finalResume", resumeEvidence(finalResume)).put("resumeMatchesVisibleOriginal", true)
                .put("sameResumeGenerationAcrossModeSwitch", true).put("allJourneyNavigationUsedTouch", true)
                .put("allLookupAndModeTransitionsImeHidden", true).put("headerScrollActionsUsed", 0)
            verifyNoLearningWrites(t)
            journeyPassed = true
            if (toolbarAppearance) {
                t.stage = "observe_final_toolbar_appearance_action"
                revealFreshChrome(t, "show_final_toolbar_for_appearance_observation")
                val toolbar = observeToolbarLayout(t)
                toolbarReport.put("buttons", toolbar.buttons).put("allActionsFullyVisible", toolbar.allVisible)
                    .put("aaFullyVisible", toolbar.aaVisible).put("touchPairs", toolbar.touchPairs)
                    .put("touchTargetsDisjoint", toolbar.touchTargetsDisjoint).put("observed", true)
                screenshot(t, "09-toolbar-appearance-observation.png")
                toolbarObserved = true
                if (toolbarPhase == "after") {
                    check(toolbar.allVisible) { "The complete toolbar must be visible before testing Aa" }
                    // A screenshot can outlast the three-second chrome lifetime. Reopen by the
                    // same real handle and recheck current geometry before touching the action.
                    revealFreshChrome(t, "show_toolbar_before_appearance_touch")
                    val currentToolbar = observeToolbarLayout(t)
                    toolbarReport.put("beforeTouchButtons", currentToolbar.buttons)
                        .put("beforeTouchPairs", currentToolbar.touchPairs)
                        .put("beforeTouchTargetsDisjoint", currentToolbar.touchTargetsDisjoint)
                    check(currentToolbar.allVisible && currentToolbar.aaVisible)
                    t.stage = "touch_toolbar_aa"
                    touch(t, compose.onNodeWithText("Aa", substring = false), "open_appearance_from_toolbar")
                    compose.waitUntil(15_000) { exists("reader_appearance_preview") }
                    compose.onNodeWithText("阅读外观").assertIsDisplayed()
                    check(!keyboardVisible()) { "Opening appearance unexpectedly opened the keyboard" }
                    screenshot(t, "10-appearance-opened-from-toolbar.png")
                    t.stage = "close_appearance_and_restore_reading"
                    touch(t, compose.onNodeWithText("完成", substring = false), "close_appearance_without_changes")
                    compose.waitUntil(15_000) { !exists("reader_appearance_preview") && readerReady() }
                    val afterAppearance = stable(t, "after_toolbar_appearance_round_trip", "11-after-appearance-return.png")
                    assertPosition(t, final, afterAppearance, "toolbar_appearance_round_trip")
                    check(afterAppearance.mode == final.mode && afterAppearance.viewportParagraph == final.viewportParagraph &&
                        afterAppearance.focus == final.focus && !keyboardVisible())
                    check(runBlocking { repository.settings.first() } == settings) { "Opening and closing appearance changed settings" }
                    verifyNoLearningWrites(t)
                    toolbarReport.put("appearanceOpenedByTouch", true).put("appearanceRoundTripVerified", true)
                        .put("appearanceDidNotOpenIme", true).put("settingsUnchanged", true)
                    toolbarDesiredBehavior = true
                }
            }
            if (typographyTrial) {
                typographyDesiredBehavior = observeTypographyChanges(t)
                typographyObserved = true
            }
        } catch (error: Throwable) {
            report.put("failedStage", trial?.stage ?: "preflight").put("failureClass", error.javaClass.simpleName)
                .put("failureLocations", JSONArray(error.stackTrace.take(12).map { "${it.fileName}:${it.lineNumber}" }))
            trial?.let { runCatching { screenshot(it, "failure.png") } }
        } finally {
            trial?.let { t ->
                val jobs = existingWriterJobs(t)
                runCatching { t.activity?.close() }.onFailure { journeyPassed = false; report.put("activityCloseFailureClass", it.javaClass.simpleName) }
                t.activity = null
                runCatching {
                    runBlocking { withTimeout(15_000) { jobs.joinAll() } }
                    requireOfflineNoKey(app)
                    val settled = waitForQuietRows(t.database)
                    if (t.inserted) cleanupOwnedRows(t, settled)
                    val after = waitForQuietRows(t.database)
                    check(after == t.original)
                    t.typographyInitialSettings?.let { initial ->
                        check(after.getValue("preferences").none { it[0] == "settings" })
                        check(runBlocking { app.repository.settings.first() } == initial)
                        typographyReport.put("settingsRowAbsentAfterCleanup", true)
                            .put("removedOwnedSettingsRows", t.typographySettingsRowsDeleted)
                            .put("ownedSettingsRemoved", t.typographySettingsRowsDeleted == 1)
                            .put("settingsReturnedToInitialWithoutWriting", true)
                    }
                    val dictionaryAfter = fileSignatures(File(app.noBackupFilesDir, "dictionary"))
                    check(dictionaryAfter == t.dictionaryFiles)
                    check(runBlocking { app.repository.dictionaryStatus() }.id == t.dictionaryId)
                    report.put("finalTableHashes", tableHashes(after)).put("dictionaryFilesAfter", JSONObject(dictionaryAfter))
                        .put("dictionaryFilesPreserved", true).put("dictionarySelectionUnchanged", true)
                    preserved = true
                    val eventCountMatches = report.optInt("ownedCaptureOpenEvents", -1) == t.captureOpens
                    report.put("captureEventCountMatchesActions", eventCountMatches)
                    if (journeyPassed && !eventCountMatches) {
                        journeyPassed = false
                        report.put("failedStage", "capture_event_accounting")
                            .put("failureClass", "MissingCaptureOpenEvent")
                    }
                }.onFailure { error -> report.put("preservationFailureClass", error.javaClass.simpleName)
                    .put("preservationFailureLocations", JSONArray(error.stackTrace.take(10).map { "${it.fileName}:${it.lineNumber}" })) }
            }
            val toolbarContractPassed = !toolbarAppearance || (toolbarObserved && (toolbarPhase == "before" || toolbarDesiredBehavior))
            val typographyContractPassed = !typographyTrial || (typographyObserved && (typographyPhase == "before" || typographyDesiredBehavior))
            typographyReport.put("observed", typographyObserved).put("desiredBehaviorVerified", typographyDesiredBehavior)
                .put("status", when {
                    !typographyTrial -> "not_requested"
                    !typographyObserved -> "failed"
                    typographyPhase == "before" -> "baseline_observed"
                    typographyDesiredBehavior -> "passed"
                    else -> "failed"
                })
            toolbarReport.put("observed", toolbarObserved).put("desiredBehaviorVerified", toolbarDesiredBehavior)
                .put("status", when {
                    !toolbarAppearance -> "not_requested"
                    !toolbarObserved -> "failed"
                    toolbarPhase == "before" -> "baseline_observed"
                    toolbarDesiredBehavior -> "passed"
                    else -> "failed"
                })
            report.put("journeyPassed", journeyPassed).put("fixtureInsertSucceeded", trial?.inserted == true)
                .put("originalDataPreserved", preserved).put("allOriginalRowsCompared", preserved)
                .put("status", when {
                    !journeyPassed || !preserved || !toolbarContractPassed || !typographyContractPassed -> "failed"
                    (toolbarAppearance && toolbarPhase == "before") || (typographyTrial && typographyPhase == "before") -> "baseline_observed"
                    else -> "passed"
                })
            reportFile.writeText(report.toString(2))
        }
        check(journeyPassed && preserved && (!toolbarAppearance || (toolbarObserved && (toolbarPhase == "before" || toolbarDesiredBehavior))) &&
            (!typographyTrial || (typographyObserved && (typographyPhase == "before" || typographyDesiredBehavior)))) {
            "Continuous reading or its requested toolbar phase failed; inspect the separate report scopes"
        }
    }

    private class Trial(val app: LexiconApplication, val database: File, val original: Map<String, List<List<String?>>>,
        val fixture: Fixture, val output: File, val report: JSONObject, val dictionaryId: String,
        val dictionaryFiles: Map<String, String>, val startedAt: Long) {
        var activity: ActivityScenario<MainActivity>? = null
        var stage = "preflight"
        var inserted = false
        var readerOpened = false
        var captureOpens = 0
        var typographyInitialSettings: AppSettings? = null
        var typographyMutationStarted = false
        var typographySettingsRowsDeleted = 0
        val typographyConfirmedSettingsRows = linkedSetOf<String>()
        val day = Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        var expectedEdition: DailyEdition? = null
    }

    private data class Observation(val scroll: Float, val textX: Float, val textY: Float,
        val viewportParagraph: Int, val focus: Int, val mode: StudyMode, val data: JSONObject) {
        val key = "$scroll:$textX:$textY:$viewportParagraph:$focus:$mode"
    }
    private fun stable(t: Trial, name: String, screenshotName: String): Observation {
        var previous: Observation? = null
        var quietSince = SystemClock.elapsedRealtime()
        var result: Observation? = null
        compose.waitUntil(15_000) {
            val current = runCatching { measure(t) }.getOrNull()
            val now = SystemClock.elapsedRealtime()
            if (current == null) { previous = null; quietSince = now; false }
            else {
                if (current.key != previous?.key) quietSince = now
                previous = current
                if (now - quietSince >= STABLE_MS) {
                    current.data.put("phase", name).put("stableForMs", now - quietSince).put("screenshot", screenshotName)
                    result = current; true
                } else false
            }
        }
        val observed = requireNotNull(result)
        check(!keyboardVisible()) { "Read-only reading unexpectedly opened the keyboard" }
        t.report.getJSONArray("observations").put(observed.data)
        screenshot(t, screenshotName)
        return observed
    }
    private fun measure(t: Trial): Observation {
        check(readerReady())
        val field = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode()
        check(field.config[SemanticsProperties.EditableText].text == t.fixture.body.text)
        val viewportNode = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode()
        val viewport = viewportNode.boundsInRoot
        val paragraph = viewportNode.config[ReaderViewportParagraphKey]
        val focus = field.config[FocusedParagraphIndex]
        check(paragraph in t.fixture.paragraphs.indices && focus in t.fixture.paragraphs.indices)
        val origin = field.positionInRoot
        val currentMode = mode()
        val scroll = readerScroll()
        val target = targetBounds(t)
        val data = JSONObject().put("bodySha256", t.fixture.body.contentHash).put("exactBodyUnchanged", true)
            .put("mode", currentMode.name).put("scroll", scroll).put("textOrigin", JSONArray(listOf(origin.x, origin.y)))
            .put("viewportBounds", JSONArray(listOf(viewport.left, viewport.top, viewport.right, viewport.bottom)))
            .put("viewportParagraph", paragraph).put("focusedParagraph", focus)
            .put("navigationFocusPhase", viewportNode.config[ReaderNavigationFocusPhaseKey])
            .put("targetGlyphBounds", JSONArray(listOf(target.left, target.top, target.right, target.bottom)))
            .put("sourceSelectionStart", field.config[SemanticsProperties.TextSelectionRange].start)
            .put("sourceSelectionEnd", field.config[SemanticsProperties.TextSelectionRange].end)
        return Observation(scroll, origin.x, origin.y, paragraph, focus, currentMode, data)
    }
    private fun assertPosition(t: Trial, before: Observation, after: Observation, label: String) {
        val dx = after.textX - before.textX
        val dy = after.textY - before.textY
        val ds = after.scroll - before.scroll
        val preserved = abs(dx) <= POSITION_TOLERANCE && abs(dy) <= POSITION_TOLERANCE && abs(ds) <= POSITION_TOLERANCE
        t.report.getJSONArray("differences").put(JSONObject().put("transition", label)
            .put("textDeltaX", dx).put("textDeltaY", dy).put("scrollDelta", ds)
            .put("beforeParagraph", before.viewportParagraph).put("afterParagraph", after.viewportParagraph)
            .put("positionPreserved", preserved))
        check(preserved) { "An overlay or mode switch moved the settled reading position" }
    }
    private fun awaitResume(t: Trial, observed: Observation, expectedMode: StudyMode): ReadingResumeRecord {
        val repository = requireNotNull(t.app.repository.reading?.resumes)
        var selected: ReadingResumeRecord? = null
        compose.waitUntil(15_000) {
            val current = runBlocking { repository.library.first() }.records.singleOrNull { it.articleId == t.fixture.article.id }
            if (current?.paragraphIndex == observed.viewportParagraph && current.controls.mode == expectedMode) {
                selected = current; true
            } else false
        }
        return requireNotNull(selected).also {
            ReadingResumes.validate(it, t.fixture.body)
            check(!it.completed && !it.controls.quiet && it.controls.budgetMinutes == 5)
        }
    }
    private fun resumeEvidence(record: ReadingResumeRecord) = JSONObject().put("articleIdSha256", hash(record.articleId))
        .put("bodySha256", record.bodyHash).put("paragraph", record.paragraphIndex).put("start", record.start).put("end", record.end)
        .put("mode", record.controls.mode.name).put("quiet", record.controls.quiet).put("budgetMinutes", record.controls.budgetMinutes)
        .put("generation", record.generation).put("sequence", record.sequence).put("completed", record.completed)

    private data class TypographyAnchor(val start: Int, val end: Int, val lineTop: Float, val viewportHeight: Float,
        val beforeScroll: Float, val beforeScrollMax: Float, val data: JSONObject)
    private data class TypographySample(val lineTop: Float, val viewportHeight: Float, val scroll: Float, val scrollMax: Float,
        val wordVisible: Boolean, val styleMatches: Boolean, val data: JSONObject) {
        val key: String get() = data.toString()
    }

    /** Each intervention starts from the current ordinary reading line, without introducing a navigation request. */
    private fun observeTypographyChanges(t: Trial): Boolean {
        val report = t.report.getJSONObject("typography")
        var allPassed = true
        listOf("size", "line_height", "font").forEachIndexed { index, change ->
            val prefix = "${12 + index * 4}-typography-$change"
            t.stage = "typography_${change}_freeze"
            stable(t, "typography_${change}_before", "$prefix-before.png")
            val anchor = freezeTypographyAnchor(t)
            val previous = runBlocking { t.app.repository.settings.first() }
            val stage = JSONObject().put("change", change).put("anchorFrozenBeforeUi", anchor.data)
                .put("beforePreferences", JSONObject(codec.encodeToString(ReaderPreferences.serializer(), previous.reader)))
            report.getJSONArray("stages").put(stage)
            File(t.output, "device-trial.json").writeText(t.report.toString(2))

            t.stage = "typography_${change}_open_appearance"
            revealFreshChrome(t, "typography_${change}_reveal_toolbar")
            touch(t, compose.onNodeWithText("Aa", substring = false), "typography_${change}_open_appearance")
            compose.waitUntil(15_000) { exists("reader_appearance_preview") }
            check(!keyboardVisible())
            t.stage = "typography_${change}_real_control"
            when (change) {
                "size" -> dragAppearanceSlider(t, "reader_size", 26f, change, previous)
                "line_height" -> dragAppearanceSlider(t, "reader_line_height", 2.4f, change, previous)
                "font" -> {
                    val control = reachAppearanceControl(t, { compose.onNodeWithText("等宽", substring = false) }, change)
                    beginTypographyMutation(t, previous)
                    touch(t, control, "typography_font_select_mono")
                }
            }
            val updated = awaitTypographySettings(t, previous, change)
            stage.put("afterPreferences", JSONObject(codec.encodeToString(ReaderPreferences.serializer(), updated.reader)))
                .put("changePersistedThroughRealUi", true)
            screenshot(t, "$prefix-control.png")
            touch(t, reachAppearanceControl(t, { compose.onNodeWithText("完成", substring = false) }, "${change}_done"), "typography_${change}_close_appearance")
            compose.waitUntil(15_000) { !exists("reader_appearance_preview") && readerReady() }
            t.stage = "typography_${change}_measure_same_offset"
            val after = stableTypography(t, anchor)
            stage.put("after", after.data)
            val delta = after.lineTop - anchor.lineTop
            val wantedScroll = after.scroll + delta
            val sameHeight = after.viewportHeight == anchor.viewportHeight
            val clampRequired = wantedScroll < -1f || wantedScroll > after.scrollMax + 1f ||
                anchor.beforeScroll <= 1f || anchor.beforeScroll >= anchor.beforeScrollMax - 1f
            val sameLineTop = abs(delta) <= POSITION_TOLERANCE
            val ordinaryReading = after.data.getString("navigationFocusPhase") == "none" && after.data.getString("mode") == StudyMode.INTENSIVE.name
            val retained = after.wordVisible && after.styleMatches && sameHeight && sameLineTop && !clampRequired && ordinaryReading
            stage.put("sameFrozenWordVisible", after.wordVisible).put("sameViewportHeight", sameHeight)
                .put("lineTopDeltaPx", delta).put("lineTopPreservedWithin2px", sameLineTop)
                .put("scrollDeltaPx", after.scroll - anchor.beforeScroll).put("unclampedDesiredScrollPx", wantedScroll)
                .put("boundaryClampRequired", clampRequired).put("eligibleMiddleRange", !clampRequired)
                .put("ordinaryReadingWithoutNavigationPin", ordinaryReading)
                .put("reflowPositionContractPassed", retained).put("imeHiddenAfterClose", !keyboardVisible())
            screenshot(t, "$prefix-after.png")

            // A failed baseline position comparison does not stop the remaining interventions.
            // A real continuation swipe also checks that a later layout does not pull the reader back.
            t.stage = "typography_${change}_continue_reading"
            val swipe = runCatching { swipeReader(t, "toward_end", "typography_${change}_continue_swipe") }
            val continued = stable(t, "typography_${change}_continued", "$prefix-continued.png")
            val continuationDelta = continued.scroll - after.scroll
            val continuedNormally = swipe.isSuccess && continuationDelta > MOVEMENT_FLOOR &&
                continued.data.getString("navigationFocusPhase") == "none" && continued.mode == StudyMode.INTENSIVE
            stage.put("continuationScrollDeltaPx", continuationDelta).put("continuationNotPulledBack", continuedNormally)
                .put("continuation", continued.data).put("stageContractPassed", retained && continuedNormally)
            if (swipe.isFailure) stage.put("continuationFailureClass", swipe.exceptionOrNull()?.javaClass?.simpleName)
            allPassed = allPassed && retained && continuedNormally
            verifyNoLearningWrites(t)
            File(t.output, "device-trial.json").writeText(t.report.toString(2))
        }
        report.put("completedInterventions", 3).put("allInterventionsPassed", allPassed)
        return allPassed
    }

    private fun articleLayout(t: Trial): Pair<SemanticsNode, TextLayoutResult> {
        val node = compose.onNodeWithTag("zen_article_text")
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val semantic = node.fetchSemanticsNode()
        check(layout.layoutInput.text.text == t.fixture.body.text && semantic.config[SemanticsProperties.EditableText].text == t.fixture.body.text)
        return semantic to layout
    }
    private fun freezeTypographyAnchor(t: Trial): TypographyAnchor {
        check(readerReady() && mode() == StudyMode.INTENSIVE && !keyboardVisible())
        val viewportNode = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode()
        check(viewportNode.config[ReaderNavigationFocusPhaseKey] == "none") { "Typography observation requires ordinary unpinned reading" }
        val viewport = viewportNode.boundsInRoot
        val (node, layout) = articleLayout(t)
        val origin = node.positionInRoot
        val lineAt42 = layout.getLineForVerticalPosition(viewport.top + viewport.height * .42f - origin.y)
        val words = Regex("[A-Za-z0-9]+(?:['’-][A-Za-z0-9]+)*").findAll(t.fixture.body.text).toList()
        val lineOrder = (0 until layout.lineCount).sortedWith(compareBy<Int> { abs(it - lineAt42) }.thenBy { it })
        for (line in lineOrder) {
            val lineStart = layout.getLineStart(line)
            val lineEnd = layout.getLineEnd(line, visibleEnd = true)
            // The first lexical word is determined from this line's original UTF-16 range only.
            val word = words.firstOrNull { candidate ->
                candidate.range.first >= lineStart && candidate.range.first < lineEnd && candidate.range.all { offset ->
                    val glyph = layout.getBoundingBox(offset)
                    val root = Rect(glyph.left + origin.x, glyph.top + origin.y, glyph.right + origin.x, glyph.bottom + origin.y)
                    root.width > 0f && root.height > 0f && containsRect(viewport, root) && containsRect(node.boundsInRoot, root)
                }
            } ?: continue
            val start = word.range.first
            val end = word.range.last + 1
            val glyphs = (start until end).map { offset -> layout.getBoundingBox(offset).let {
                Rect(it.left + origin.x, it.top + origin.y, it.right + origin.x, it.bottom + origin.y)
            } }
            if (glyphs.any { it.width <= 0f || it.height <= 0f || !containsRect(viewport, it) || !containsRect(node.boundsInRoot, it) }) continue
            val top = layout.getLineTop(layout.getLineForOffset(start)) + origin.y - viewport.top
            val scroll = readerScroll()
            val scrollMax = readerScrollMaximum()
            val data = JSONObject().put("bodySha256", t.fixture.body.contentHash).put("start", start).put("end", end)
                .put("wordSha256", hash(t.fixture.body.text.substring(start, end)))
                .put("paragraph", ParagraphFocus.atOffset(t.fixture.paragraphs, start))
                .put("lineAt42Percent", lineAt42).put("chosenLine", line).put("lineStart", lineStart).put("lineEnd", lineEnd)
                .put("lineTopInViewportPx", top).put("lineTopViewportRatio", top / viewport.height)
                .put("viewportHeightPx", viewport.height).put("scrollPx", scroll).put("scrollMaximumPx", scrollMax)
                .put("navigationFocusPhase", "none").put("wordBoundsInRoot", JSONArray(glyphs.map(::rectJson)))
            return TypographyAnchor(start, end, top, viewport.height, scroll, scrollMax, data)
        }
        error("No fully visible English word near the 42% reading line")
    }
    private fun measureTypography(t: Trial, anchor: TypographyAnchor): TypographySample {
        check(readerReady())
        val viewportNode = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode()
        val viewport = viewportNode.boundsInRoot
        val (node, layout) = articleLayout(t)
        val origin = node.positionInRoot
        val line = layout.getLineForOffset(anchor.start)
        val lineTop = layout.getLineTop(line) + origin.y - viewport.top
        val glyphs = (anchor.start until anchor.end).map { offset -> layout.getBoundingBox(offset).let {
            Rect(it.left + origin.x, it.top + origin.y, it.right + origin.x, it.bottom + origin.y)
        } }
        val visible = glyphs.all { it.width > 0f && it.height > 0f && containsRect(viewport, it) && containsRect(node.boundsInRoot, it) }
        val settings = runBlocking { t.app.repository.settings.first() }
        val prefs = settings.reader
        val style = layout.layoutInput.style
        val styleMatches = abs(style.fontSize.value - prefs.sizeSp) < .02f &&
            abs(style.lineHeight.value - prefs.sizeSp * prefs.lineHeight) < .02f &&
            style.fontFamily == (if (prefs.font == ReaderFont.MONO) EditorMono else ReadingSerif)
        val scroll = readerScroll()
        val scrollMax = readerScrollMaximum()
        val data = JSONObject().put("bodySha256", t.fixture.body.contentHash).put("exactBodyUnchanged", true)
            .put("start", anchor.start).put("end", anchor.end).put("wordSha256", hash(t.fixture.body.text.substring(anchor.start, anchor.end)))
            .put("line", line).put("lineTopInViewportPx", lineTop).put("lineTopViewportRatio", lineTop / viewport.height)
            .put("viewportHeightPx", viewport.height).put("viewportBounds", rectJson(viewport))
            .put("wordBoundsInRoot", JSONArray(glyphs.map(::rectJson))).put("sameWordFullyVisible", visible)
            .put("scrollPx", scroll).put("scrollMaximumPx", scrollMax).put("textOrigin", JSONArray(listOf(origin.x, origin.y)))
            .put("mode", mode().name).put("navigationFocusPhase", viewportNode.config[ReaderNavigationFocusPhaseKey])
            .put("viewportParagraph", viewportNode.config[ReaderViewportParagraphKey]).put("focusedParagraph", node.config[FocusedParagraphIndex])
            .put("readerPreferences", JSONObject(codec.encodeToString(ReaderPreferences.serializer(), prefs)))
            .put("bodyFontSizeSp", style.fontSize.value).put("bodyLineHeightSp", style.lineHeight.value)
            .put("bodyStyleMatchesPreferences", styleMatches)
        return TypographySample(lineTop, viewport.height, scroll, scrollMax, visible, styleMatches, data)
    }
    private fun stableTypography(t: Trial, anchor: TypographyAnchor): TypographySample {
        var previous: String? = null
        var quietSince = SystemClock.elapsedRealtime()
        var observed: TypographySample? = null
        compose.waitUntil(15_000) {
            val current = runCatching { measureTypography(t, anchor) }.getOrNull()
            val now = SystemClock.elapsedRealtime()
            if (current == null) { previous = null; quietSince = now; false }
            else {
                if (current.key != previous) quietSince = now
                previous = current.key
                if (now - quietSince >= STABLE_MS) { current.data.put("stableForMs", now - quietSince); observed = current; true } else false
            }
        }
        check(!keyboardVisible())
        return requireNotNull(observed)
    }
    private fun readerScrollMaximum() = compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("zen_article_text")))
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()

    private fun appearanceScroller() = compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("reader_appearance_preview")))
    private fun reachAppearanceControl(t: Trial, target: () -> SemanticsNodeInteraction, label: String): SemanticsNodeInteraction {
        repeat(16) { attempt ->
            val interaction = target()
            val node = interaction.fetchSemanticsNode()
            val raw = rawBounds(node)
            val scroller = appearanceScroller()
            val viewport = scroller.fetchSemanticsNode().boundsInRoot
            if (raw.width > 0f && raw.height > 0f && containsRect(viewport, raw) && containsRect(node.boundsInRoot, raw)) {
                interaction.assertIsDisplayed(); return interaction
            }
            val towardEnd = raw.bottom > viewport.bottom
            val event = JSONObject().put("action", "typography_sheet_${label}_${attempt + 1}").put("kind", "sheet_swipe")
                .put("towardEnd", towardEnd).put("viewportBounds", rectJson(viewport))
            t.report.getJSONArray("touches").put(event)
            scroller.performTouchInput {
                val start = Offset(centerX, height * if (towardEnd) .72f else .28f)
                val end = Offset(centerX, height * if (towardEnd) .28f else .72f)
                event.put("startLocal", JSONArray(listOf(start.x, start.y))).put("endLocal", JSONArray(listOf(end.x, end.y)))
                swipe(start, end, SWIPE_MS)
            }
            compose.waitForIdle()
        }
        error("Real sheet swipes did not expose the complete $label control")
    }
    private fun dragAppearanceSlider(t: Trial, tag: String, target: Float, label: String, previous: AppSettings) {
        val control = reachAppearanceControl(t, { compose.onNodeWithTag(tag) }, label)
        val node = control.fetchSemanticsNode()
        val progress = node.config[SemanticsProperties.ProgressBarRangeInfo]
        val range = progress.range.endInclusive - progress.range.start
        check(range > 0f && target in progress.range)
        val event = JSONObject().put("action", "typography_${label}_drag_slider").put("kind", "slider_drag")
            .put("requestedValue", target).put("beforeUiValue", progress.current).put("controlBounds", rectJson(node.boundsInRoot))
        beginTypographyMutation(t, previous)
        t.report.getJSONArray("touches").put(event)
        control.performTouchInput {
            val inset = minOf(12f * t.app.resources.displayMetrics.density, width * .15f)
            fun x(value: Float) = inset + (width - 2f * inset) * ((value - progress.range.start) / range).coerceIn(0f, 1f)
            val start = Offset(x(progress.current), centerY)
            val end = Offset(x(target), centerY)
            event.put("startLocal", JSONArray(listOf(start.x, start.y))).put("endLocal", JSONArray(listOf(end.x, end.y)))
            swipe(start, end, SWIPE_MS)
        }
        compose.waitForIdle()
    }
    private fun beginTypographyMutation(t: Trial, previous: AppSettings) {
        check(t.typographyInitialSettings != null && t.original.getValue("preferences").none { it[0] == "settings" })
        check(readSettingsRow(t) == t.typographyConfirmedSettingsRows.lastOrNull()) {
            "Settings changed outside the observed UI sequence; do not overwrite the row"
        }
        check(runBlocking { t.app.repository.settings.first() } == previous)
        t.typographyMutationStarted = true
    }
    private fun awaitTypographySettings(t: Trial, previous: AppSettings, change: String): AppSettings {
        var accepted: AppSettings? = null
        compose.waitUntil(15_000) {
            val raw = readSettingsRow(t) ?: return@waitUntil false
            val value = codec.decodeFromString<AppSettings>(raw)
            val singleChange = when (change) {
                "size" -> value.reader.sizeSp in 25f..27f && value == previous.copy(reader = previous.reader.copy(sizeSp = value.reader.sizeSp))
                "line_height" -> value.reader.lineHeight in 2.29f..2.51f && value == previous.copy(reader = previous.reader.copy(lineHeight = value.reader.lineHeight))
                "font" -> value == previous.copy(reader = previous.reader.copy(font = ReaderFont.MONO))
                else -> false
            }
            if (!singleChange || value == previous || runBlocking { t.app.repository.settings.first() } != value) false
            else {
                check(raw == codec.encodeToString(AppSettings.serializer(), value)) { "Unexpected settings JSON fields or encoding; preserve for inspection" }
                check(typographySettingsOnlyAllowedFields(t, value))
                t.typographyConfirmedSettingsRows += raw
                t.report.getJSONObject("typography").getJSONArray("settingsStates").put(JSONObject().put("change", change)
                    .put("settingsSha256", hash(raw)).put("repositoryAndDatabaseMatch", true).put("allOtherSettingsUnchanged", true)
                    .put("readerPreferences", JSONObject(codec.encodeToString(ReaderPreferences.serializer(), value.reader))))
                accepted = value; true
            }
        }
        return requireNotNull(accepted)
    }
    private fun readSettingsRow(t: Trial): String? = openDatabase(t.database, true).use { db ->
        db.prepare("SELECT data FROM preferences WHERE `key`='settings'").use { if (it.step()) it.getText(0) else null }
    }
    private fun typographySettingsOnlyAllowedFields(t: Trial, settings: AppSettings): Boolean {
        val initial = t.typographyInitialSettings ?: return false
        return settings == initial.copy(reader = initial.reader.copy(font = settings.reader.font,
            sizeSp = settings.reader.sizeSp, lineHeight = settings.reader.lineHeight))
    }

    private data class ToolbarObservation(val buttons: JSONArray, val allVisible: Boolean, val aaVisible: Boolean,
        val touchPairs: JSONArray, val touchTargetsDisjoint: Boolean)

    /** Observe unmerged Text leaves so layout origin and clipping refer to the same text node. */
    private fun observeToolbarLayout(t: Trial): ToolbarObservation {
        val began = SystemClock.elapsedRealtime()
        val viewport = readerViewportBounds()
        val candidates = TOOLBAR_LABELS.associateWith { label ->
            compose.onAllNodesWithText(label, substring = false, useUnmergedTree = true).fetchSemanticsNodes()
                .filter { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true &&
                    node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action != null }
        }
        val anchor = candidates.getValue("阅读工具").filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
            .minByOrNull { it.positionInRoot.y } ?: error("The visible toolbar text anchor is missing")
        val anchorY = anchor.positionInRoot.y
        val reports = JSONArray()
        val visibility = linkedMapOf<String, Boolean>()
        val reportsByLabel = linkedMapOf<String, JSONObject>()
        val touchBoundsByLabel = linkedMapOf<String, Rect>()
        val minimumTouchPx = 48f * t.app.resources.displayMetrics.density
        compose.runOnIdle {
            TOOLBAR_LABELS.forEach { label ->
                // The article footer has another exit label. Its raw position is far from the
                // current chrome; choosing by geometry avoids moving the article to find it.
                val leaf = candidates.getValue(label).minByOrNull { abs(it.positionInRoot.y - anchorY) }
                val item = JSONObject().put("label", label).put("textNodeFound", leaf != null)
                if (leaf == null) {
                    visibility[label] = false
                    item.put("fullyVisible", false).put("reason", "No matching toolbar Text layout")
                } else {
                    val layouts = mutableListOf<TextLayoutResult>()
                    val layoutAvailable = leaf.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true
                    val layout = layouts.singleOrNull()
                    val raw = rawBounds(leaf)
                    val clipped = leaf.boundsInRoot
                    var ancestor = leaf.parent
                    var button: SemanticsNode? = null
                    while (ancestor != null && button == null) {
                        if (ancestor.config.getOrNull(SemanticsActions.OnClick)?.action != null) button = ancestor
                        else ancestor = ancestor.parent
                    }
                    val buttonRaw = button?.let(::rawBounds)
                    val buttonClip = button?.boundsInRoot
                    val touchBounds = button?.touchBoundsInRoot
                    val geometry = inspectToolbarTextGeometry(label, layout, raw, clipped)
                    val textVisible = raw.width > 0f && raw.height > 0f && containsRect(clipped, raw) && containsRect(viewport, raw)
                    val textInsideButton = buttonRaw != null && containsRect(buttonRaw, raw)
                    val buttonVisible = buttonRaw != null && buttonClip != null && buttonRaw.width > 0f && buttonRaw.height > 0f &&
                        containsRect(buttonClip, buttonRaw) && containsRect(viewport, buttonRaw)
                    val minimumTouchTarget = touchBounds != null && touchBounds.width + 1f >= minimumTouchPx &&
                        touchBounds.height + 1f >= minimumTouchPx
                    val touchInsideViewport = touchBounds != null && containsRect(viewport, touchBounds)
                    if (touchBounds != null) touchBoundsByLabel[label] = touchBounds
                    val fullyVisible = layoutAvailable && geometry.completeInsideAllocatedNode && textVisible && textInsideButton &&
                        buttonVisible && minimumTouchTarget && touchInsideViewport
                    visibility[label] = fullyVisible
                    item.put("fullyVisible", fullyVisible).put("layoutAvailable", layoutAvailable).put("textMatchesLabel", geometry.matchesExpectedText)
                        .put("untruncatedLayout", geometry.completeInsideAllocatedNode)
                        .put("textGeometry", toolbarGeometryJson(geometry))
                        .put("textBoundsFullyVisible", textVisible).put("textInsideButton", textInsideButton)
                        .put("buttonBoundsFullyVisible", buttonVisible).put("minimumTouchTarget48dp", minimumTouchTarget)
                        .put("touchBoundsInsideViewport", touchInsideViewport).put("minimumTouchTargetPx", minimumTouchPx)
                        .put("touchWidthPx", touchBounds?.width ?: JSONObject.NULL)
                        .put("touchHeightPx", touchBounds?.height ?: JSONObject.NULL)
                        .put("textRawBounds", rectJson(raw)).put("textClippedBounds", rectJson(clipped))
                        .put("buttonRawBounds", buttonRaw?.let(::rectJson) ?: JSONObject.NULL)
                        .put("buttonClippedBounds", buttonClip?.let(::rectJson) ?: JSONObject.NULL)
                        .put("buttonTouchBounds", touchBounds?.let(::rectJson) ?: JSONObject.NULL)
                        .put("viewportBounds", rectJson(viewport)).put("distanceFromToolbarAnchorY", abs(raw.top - anchorY))
                    layout?.let { value ->
                        item.put("layoutText", value.layoutInput.text.text).put("layoutWidth", value.size.width).put("layoutHeight", value.size.height)
                            .put("lineCount", value.lineCount).put("didOverflowWidth", value.didOverflowWidth).put("didOverflowHeight", value.didOverflowHeight)
                            // Keep the original flags as diagnostics; complete node/line/glyph geometry is the amended oracle.
                            .put("hasVisualOverflow", value.hasVisualOverflow)
                            .put("paragraphWidth", value.multiParagraph.width).put("paragraphHeight", value.multiParagraph.height)
                            .put("paragraphWidthMinusLayoutWidth", value.multiParagraph.width - value.size.width)
                            .put("paragraphDidExceedMaxLines", value.multiParagraph.didExceedMaxLines)
                            .put("inputConstraints", JSONObject()
                                .put("minWidth", value.layoutInput.constraints.minWidth).put("maxWidth", value.layoutInput.constraints.maxWidth)
                                .put("minHeight", value.layoutInput.constraints.minHeight).put("maxHeight", value.layoutInput.constraints.maxHeight))
                            .put("paragraphIntrinsics", JSONObject()
                                .put("minIntrinsicWidth", value.multiParagraph.intrinsics.minIntrinsicWidth)
                                .put("maxIntrinsicWidth", value.multiParagraph.intrinsics.maxIntrinsicWidth))
                            .put("characterBounds", JSONArray(value.layoutInput.text.text.indices.map { offset ->
                                val bounds = value.getBoundingBox(offset)
                                val rootBounds = Rect(raw.left + bounds.left, raw.top + bounds.top, raw.left + bounds.right, raw.top + bounds.bottom)
                                JSONObject().put("utf16Offset", offset).put("character", value.layoutInput.text.text.substring(offset, offset + 1))
                                    .put("line", value.getLineForOffset(offset)).put("localBounds", rectJson(bounds)).put("rootBounds", rectJson(rootBounds))
                                    .put("insideAllocatedLayout", containsRect(Rect(0f, 0f, value.size.width.toFloat(), value.size.height.toFloat()), bounds))
                                    .put("insideClippedTextNode", containsRect(clipped, rootBounds))
                            }))
                            .put("lines", JSONArray((0 until value.lineCount).map { line -> JSONObject()
                                .put("start", value.getLineStart(line)).put("end", value.getLineEnd(line))
                                .put("left", value.getLineLeft(line)).put("right", value.getLineRight(line))
                                .put("top", value.getLineTop(line)).put("bottom", value.getLineBottom(line))
                                .put("ellipsized", value.isLineEllipsized(line)) }))
                    }
                }
                reportsByLabel[label] = item
                reports.put(item)
            }
        }
        val touchPairs = JSONArray()
        val touchDisjointByLabel = TOOLBAR_LABELS.associateWith { touchBoundsByLabel.size == TOOLBAR_LABELS.size }.toMutableMap()
        TOOLBAR_LABELS.forEachIndexed { index, firstLabel ->
            TOOLBAR_LABELS.drop(index + 1).forEach { secondLabel ->
                val first = touchBoundsByLabel[firstLabel]
                val second = touchBoundsByLabel[secondLabel]
                val measured = first != null && second != null
                val overlapWidth = if (first != null && second != null)
                    (minOf(first.right, second.right) - maxOf(first.left, second.left)).coerceAtLeast(0f) else null
                val overlapHeight = if (first != null && second != null)
                    (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)).coerceAtLeast(0f) else null
                val overlaps = overlapWidth != null && overlapHeight != null && overlapWidth > 1f && overlapHeight > 1f
                if (!measured || overlaps) {
                    touchDisjointByLabel[firstLabel] = false
                    touchDisjointByLabel[secondLabel] = false
                }
                touchPairs.put(JSONObject().put("firstLabel", firstLabel).put("secondLabel", secondLabel).put("measured", measured)
                    .put("overlapWidthPx", overlapWidth ?: JSONObject.NULL).put("overlapHeightPx", overlapHeight ?: JSONObject.NULL)
                    .put("overlapsBeyondTolerance", overlaps).put("disjointWithinTolerance", measured && !overlaps))
            }
        }
        TOOLBAR_LABELS.forEach { label ->
            val disjoint = touchDisjointByLabel[label] == true
            visibility[label] = visibility[label] == true && disjoint
            reportsByLabel.getValue(label).put("touchBoundsDoNotOverlap", disjoint).put("fullyVisible", visibility[label])
        }
        check(!keyboardVisible())
        val chromeStillVisible = runCatching { compose.onNodeWithText("阅读工具").assertIsDisplayed() }.isSuccess
        t.report.getJSONObject("toolbarAppearance").put("geometryTolerancePx", 1f)
            .put("geometryOracle", "complete_label_geometry_2026-11-06")
            .put("minimumTouchTargetDp", 48f).put("minimumTouchTargetPx", minimumTouchPx).put("touchOverlapTolerancePx", 1f)
            .put("geometryObservationDurationMs", SystemClock.elapsedRealtime() - began)
            .put("chromeVisibleAtGeometryEnd", chromeStillVisible)
        check(chromeStillVisible) { "Chrome expired during geometry observation; do not report an action as missing" }
        return ToolbarObservation(reports, TOOLBAR_LABELS.all { visibility[it] == true }, visibility["Aa"] == true,
            touchPairs, TOOLBAR_LABELS.all { touchDisjointByLabel[it] == true })
    }
    private fun toolbarGeometryJson(geometry: ToolbarTextGeometry) = JSONObject()
        .put("layoutAvailable", geometry.layoutAvailable).put("matchesExpectedText", geometry.matchesExpectedText)
        .put("allocationNonempty", geometry.allocationNonempty).put("nodeLayoutAgree", geometry.nodeLayoutAgree)
        .put("nodeUnclipped", geometry.nodeUnclipped).put("completeCharacterCoverage", geometry.completeCharacterCoverage)
        .put("noEllipsis", geometry.noEllipsis).put("lineBoxesFit", geometry.lineBoxesFit).put("glyphBoxesFit", geometry.glyphBoxesFit)
        .put("completeInsideAllocatedNode", geometry.completeInsideAllocatedNode)
        .put("lines", JSONArray(geometry.lines.map { line -> JSONObject()
            .put("index", line.index).put("start", line.start).put("end", line.end).put("bounds", rectJson(line.bounds))
            .put("ellipsized", line.ellipsized).put("contiguousCoverage", line.contiguousCoverage).put("insideAllocation", line.insideAllocation) }))
        .put("glyphs", JSONArray(geometry.glyphs.map { glyph -> JSONObject()
            .put("start", glyph.start).put("end", glyph.end).put("line", glyph.line).put("bounds", rectJson(glyph.bounds))
            .put("hasRequiredArea", glyph.hasRequiredArea).put("coveredByLine", glyph.coveredByLine).put("insideAllocation", glyph.insideAllocation) }))
    private fun rawBounds(node: SemanticsNode): Rect {
        val origin = node.positionInRoot
        return Rect(origin.x, origin.y, origin.x + node.size.width, origin.y + node.size.height)
    }
    private fun containsRect(outer: Rect, inner: Rect): Boolean = outer.width > 0f && outer.height > 0f &&
        inner.left >= outer.left - 1f && inner.top >= outer.top - 1f && inner.right <= outer.right + 1f && inner.bottom <= outer.bottom + 1f
    private fun rectJson(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))

    private fun revealFreshChrome(t: Trial, label: String) {
        // Let the normal three-second timeout and exit animation finish. A new real edge tap
        // gives measurement its own complete chrome window without altering the production clock.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("阅读工具").fetchSemanticsNodes().isEmpty() }
        openChrome(t, label)
        compose.waitForIdle()
    }

    private fun openChrome(t: Trial, label: String) {
        check(readerScroll() > 110f * t.app.resources.displayMetrics.density)
        if (runCatching { compose.onNodeWithText("阅读工具").assertIsDisplayed() }.isSuccess) return
        val viewport = readerViewportBounds()
        viewportTouch(t, Offset(viewport.width / 2f, 12f * t.app.resources.displayMetrics.density), label)
        compose.waitUntil(2_000) { compose.onAllNodesWithText("阅读工具").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun touch(t: Trial, node: SemanticsNodeInteraction, label: String) {
        node.assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val data = JSONObject().put("action", label).put("kind", "touch")
        t.report.getJSONArray("touches").put(data)
        node.performTouchInput {
            data.put("local", JSONArray(listOf(centerX, centerY)))
                .put("root", JSONArray(listOf(bounds.left + centerX, bounds.top + centerY)))
            advanceEventTime(600); click(center)
        }
    }
    private fun viewportTouch(t: Trial, point: Offset, label: String) {
        val viewport = readerViewportBounds()
        check(point.x > 0f && point.x < viewport.width && point.y > 0f && point.y < viewport.height)
        t.report.getJSONArray("touches").put(JSONObject().put("action", label).put("kind", "viewport_touch")
            .put("local", JSONArray(listOf(point.x, point.y)))
            .put("root", JSONArray(listOf(viewport.left + point.x, viewport.top + point.y))))
        compose.onNodeWithTag("zen_reader_viewport").performTouchInput { advanceEventTime(600); click(point) }
    }
    private fun swipeReader(t: Trial, direction: String, label: String) {
        check(direction in setOf("toward_end", "toward_start"))
        val beforeScroll = readerScroll()
        val beforeOrigin = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().positionInRoot.y
        val viewport = readerViewportBounds()
        val data = JSONObject().put("action", label).put("kind", "interior_swipe").put("direction", direction)
            .put("durationMs", SWIPE_MS).put("scrollBefore", beforeScroll)
        t.report.getJSONArray("touches").put(data)
        compose.onNodeWithTag("zen_reader_viewport").performTouchInput {
            val start = Offset(centerX, height * if (direction == "toward_end") .72f else .28f)
            val end = Offset(centerX, height * if (direction == "toward_end") .28f else .72f)
            data.put("startLocal", JSONArray(listOf(start.x, start.y))).put("endLocal", JSONArray(listOf(end.x, end.y)))
                .put("startRoot", JSONArray(listOf(viewport.left + start.x, viewport.top + start.y)))
                .put("endRoot", JSONArray(listOf(viewport.left + end.x, viewport.top + end.y)))
            swipe(start, end, SWIPE_MS)
        }
        waitReaderSettled()
        val scrollDelta = readerScroll() - beforeScroll
        val originDelta = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().positionInRoot.y - beforeOrigin
        data.put("scrollDelta", scrollDelta).put("textOriginDelta", originDelta)
        check(abs(scrollDelta) > MOVEMENT_FLOOR && abs(originDelta) > MOVEMENT_FLOOR) { "Injected swipe did not move the reader" }
    }
    private fun readerReady() = runCatching {
        compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().config[ReaderPositionReadyKey]
    }.getOrDefault(false)
    private fun waitReaderSettled() { compose.waitUntil(15_000) { readerReady() }; compose.waitForIdle() }
    private fun readerScroll() = compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("zen_article_text")))
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun readerViewportBounds() = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().boundsInRoot
    private fun mode(): StudyMode = when {
        compose.onAllNodesWithText("精读 · 工具").fetchSemanticsNodes().isNotEmpty() -> StudyMode.INTENSIVE
        compose.onAllNodesWithText("略读 · 工具").fetchSemanticsNodes().isNotEmpty() -> StudyMode.SKIM
        else -> error("The original reader mode is not observable")
    }
    private fun targetBounds(t: Trial): Rect {
        val node = compose.onNodeWithTag("zen_article_text")
        val result = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        val layout = result.single()
        check(layout.layoutInput.text.text == t.fixture.body.text)
        val glyph = layout.getBoundingBox(t.fixture.targetStart + 2)
        val origin = node.fetchSemanticsNode().positionInRoot
        return Rect(glyph.left + origin.x, glyph.top + origin.y, glyph.right + origin.x, glyph.bottom + origin.y)
    }
    private fun edgeMargin(t: Trial) = 72f * t.app.resources.displayMetrics.density
    private fun targetSafelyVisible(t: Trial): Boolean {
        val target = targetBounds(t)
        val viewport = readerViewportBounds()
        return target.top >= viewport.top + edgeMargin(t) && target.bottom <= viewport.bottom - edgeMargin(t) &&
            target.left >= viewport.left && target.right <= viewport.right
    }
    private fun dictionaryReady() = !exists("capture_dictionary_lookup_loading") && !exists("capture_dictionary_lookup_error") &&
        runCatching { text("capture_dictionary_word_0").trim().startsWith("world", true) && text("capture_dictionary_definition_0").isNotBlank() }.getOrDefault(false)
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun field(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun text(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
    private fun keyboardVisible(): Boolean {
        var visible = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            visible = WindowInspector.getGlobalWindowViews().any { it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        }
        return visible
    }

    private fun insertFixture(t: Trial) {
        val f = t.fixture
        openDatabase(t.database).use { db -> transaction(db) {
            check(snapshot(db) == t.original)
            insert(db, "sources", "id" to f.source.id, "data" to codec.encodeToString(Source.serializer(), f.source))
            insert(db, "articles", "id" to f.article.id, "url" to f.article.url, "publishedAt" to requireNotNull(f.article.publishedAt), "data" to codec.encodeToString(Article.serializer(), f.article))
            insert(db, "article_sources", "sourceId" to f.source.id, "guid" to f.article.guid, "articleId" to f.article.id)
            insert(db, "article_bodies", "articleId" to f.body.articleId, "expiresAt" to f.body.expiresAt, "data" to codec.encodeToString(ArticleBody.serializer(), f.body))
        } }
        t.inserted = true
        t.report.put("fixtureRowsInserted", 4)
    }
    private fun insert(db: SQLiteConnection, table: String, vararg entries: Pair<String, Any>) {
        val columns = entries.joinToString(",") { "\"${it.first}\"" }
        val marks = entries.joinToString(",") { "?" }
        db.prepare("INSERT INTO $table ($columns) VALUES ($marks)").use { statement ->
            entries.forEachIndexed { index, (_, value) -> when (value) {
                is String -> statement.bindText(index + 1, value)
                is Long -> statement.bindLong(index + 1, value)
                else -> error("Unsupported fixture value")
            } }
            statement.step()
        }
        check(changes(db) == 1L)
    }
    private fun verifyEditionWillNotRewriteOriginal(t: Trial) {
        val rows = t.original.getValue("preferences").filter { it[0]?.startsWith("reading-library:edition:") == true }
        val existing = rows.singleOrNull { it[0] == "reading-library:edition:${t.day}" }?.let { codec.decodeFromString<DailyEdition>(requireNotNull(it[1])) }
        val history = rows.sortedByDescending { it[0] }.take(8).map { codec.decodeFromString<DailyEdition>(requireNotNull(it[1])) }
        val sources = runBlocking { t.app.repository.sources.first() } + t.fixture.source
        val expected = ReadingDailyPlanner.extend(existing, listOf(t.fixture.article), sources, t.startedAt, ZoneId.systemDefault().id, history)
        check(existing == null || existing == expected) { "An original daily edition would change" }
        t.expectedEdition = expected
    }
    private fun verifyNoLearningWrites(t: Trial) {
        val rows = snapshot(t.database)
        listOf("captures", "cards", "review_logs", "ai_jobs").forEach { check(rows.getValue(it) == t.original.getValue(it)) }
    }
    private fun cleanupOwnedRows(t: Trial, settled: Map<String, List<List<String?>>>) {
        check(t.inserted && settled.keys == t.original.keys)
        val added = settled.mapValues { (table, rows) ->
            val before = t.original.getValue(table)
            val keys = before.map { rowKey(table, it) }.toSet()
            check(rows.filter { rowKey(table, it) in keys } == before) { "An original row changed; never restore it from a snapshot" }
            rows.filter { rowKey(table, it) !in keys }
        }
        val f = t.fixture
        val ended = System.currentTimeMillis()
        val sessions = added.getValue("preferences").filter { it[0]?.startsWith("reading-library:session:") == true }
            .associate { requireNotNull(it[0]).removePrefix("reading-library:session:") to codec.decodeFromString<ReadingSession>(requireNotNull(it[1])) }
        var openEvents = 0
        added.forEach { (table, rows) -> rows.forEach { row ->
            val id = requireNotNull(row[0])
            val owned = when (table) {
                "sources" -> id == f.source.id && codec.decodeFromString<Source>(requireNotNull(row[1])) == f.source
                "articles" -> id == f.article.id && row[1] == f.article.url && row[2]?.toLongOrNull() == f.article.publishedAt &&
                    codec.decodeFromString<Article>(requireNotNull(row[3])) == f.article
                "article_sources" -> row == listOf(f.source.id, f.article.guid, f.article.id)
                "article_bodies" -> id == f.article.id && row[1]?.toLongOrNull() == f.body.expiresAt && codec.decodeFromString<ArticleBody>(requireNotNull(row[2])) == f.body
                "local_events" -> codec.decodeFromString<LocalEvent>(requireNotNull(row[2])).let { event ->
                    event.id == id && event.createdAt == row[1]?.toLongOrNull() && event.createdAt in t.startedAt..ended && when (event.type) {
                        "capture_opened" -> { openEvents++; event.value == 1 && t.captureOpens > 0 }
                        "zen_opened" -> t.readerOpened && event.value == 1
                        "zen_duration_seconds" -> t.readerOpened && event.value >= 0
                        "reading_session" -> id.startsWith("reading-session:") && sessions[id.removePrefix("reading-session:")]?.let { session ->
                            session.id == id.removePrefix("reading-session:") && session.articleId == f.article.id &&
                                session.startedAt in t.startedAt..ended && event.createdAt == session.finishedAt && event.value == 1
                        } == true
                        else -> false
                    }
                }
                "preferences" -> {
                    val data = requireNotNull(row[1])
                    when {
                        id == "reading-library:edition:${t.day}" -> codec.decodeFromString<DailyEdition>(data) == t.expectedEdition
                        id == "settings" -> t.typographyInitialSettings != null && t.typographyMutationStarted &&
                            t.original.getValue("preferences").none { it[0] == "settings" } &&
                            t.typographyConfirmedSettingsRows.lastOrNull() == data && codec.decodeFromString<AppSettings>(data).let {
                                typographySettingsOnlyAllowedFields(t, it) && codec.encodeToString(AppSettings.serializer(), it) == data
                            }
                        !t.readerOpened -> false
                        id.startsWith("reading-library:session:") -> sessions[id.removePrefix("reading-library:session:")]?.let {
                            it.id == id.removePrefix("reading-library:session:") && it.articleId == f.article.id &&
                                it.startedAt in t.startedAt..ended && it.finishedAt in it.startedAt..ended &&
                                it.totalParagraphs == f.paragraphs.size && it.level == "原文" && !it.completed && it.assistanceCount == 0
                        } == true
                        id == "reading-exposure:article:${f.article.id}" || id == "reading-exposure:body:${f.body.contentHash}" -> data.toLongOrNull()?.let { it in t.startedAt..ended } == true
                        id == "reading-resume:record:${hash(f.article.id)}" -> codec.decodeFromString<ReadingResumeRecord>(data).let {
                            ReadingResumes.validate(it, f.body); it.updatedAt in t.startedAt..ended && !it.completed
                        }
                        id == "reading-resume:generation:${hash(f.article.id)}" -> JSONObject(data).let {
                            it.getInt("version") == 1 && it.getLong("generation") > 0 && it.getString("bodyHash") == f.body.contentHash
                        }
                        else -> false
                    }
                }
                else -> false
            }
            check(owned) { "Unexpected added row; retain all rows for inspection" }
        } }
        check(openEvents <= t.captureOpens)
        openDatabase(t.database).use { db -> transaction(db) {
            check(snapshot(db) == settled) { "Database changed after quiet interval" }
            listOf("local_events", "preferences", "article_bodies", "article_sources", "articles", "sources").forEach { table ->
                added.getValue(table).forEach { row ->
                    val ownedSettings = table == "preferences" && row[0] == "settings" && t.typographyInitialSettings != null
                    val where = when (table) {
                        "preferences" -> if (ownedSettings) "`key`=? AND data=?" else "`key`=?"
                        "article_bodies" -> "articleId=?"
                        "article_sources" -> "sourceId=? AND guid=?"
                        else -> "id=?"
                    }
                    db.prepare("DELETE FROM $table WHERE $where").use { statement ->
                        statement.bindText(1, requireNotNull(row[0]))
                        if (table == "article_sources") statement.bindText(2, requireNotNull(row[1]))
                        if (ownedSettings) statement.bindText(2, requireNotNull(row[1]))
                        statement.step()
                    }
                    check(changes(db) == 1L)
                }
            }
        } }
        t.typographySettingsRowsDeleted = added.getValue("preferences").count { it[0] == "settings" }
        t.report.put("captureOpenActions", t.captureOpens).put("ownedCaptureOpenEvents", openEvents)
            .put("ownedReadingSessions", sessions.size)
            .put("removedOwnedRows", JSONObject().apply { added.forEach { (table, rows) -> if (rows.isNotEmpty()) put(table, rows.size) } })
    }
    private fun rowKey(table: String, row: List<String?>) = if (table == "article_sources") JSONArray(row.take(2)).toString() else requireNotNull(row[0])
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
        try { snapshot(db).also { db.exec("COMMIT") } } catch (error: Throwable) { runCatching { db.exec("ROLLBACK") }; throw error }
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
    private fun transaction(db: SQLiteConnection, action: () -> Unit) {
        db.exec("BEGIN IMMEDIATE TRANSACTION")
        try { action(); db.exec("COMMIT") } catch (error: Throwable) { runCatching { db.exec("ROLLBACK") }; throw error }
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
        error("Database writes did not settle")
    }
    private fun existingWriterJobs(t: Trial): List<Job> {
        val jobs = linkedSetOf<Job>()
        t.activity?.onActivity { activity ->
            val provider = ViewModelProvider(activity, object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = error("Existing ViewModels only")
            })
            fun retain(find: () -> ViewModel) { runCatching { find().viewModelScope.coroutineContext[Job] }.getOrNull()?.let(jobs::add) }
            retain { provider[LexiconViewModel::class.java] }; retain { provider[ReadingViewModel::class.java] }
            if (t.readerOpened) retain { provider["reading-resume:${hash(t.fixture.article.id)}", ReadingResumeViewModel::class.java] }
        }
        t.report.put("joinedExistingViewModelJobs", jobs.size)
        return jobs.toList()
    }
    private fun tableHashes(rows: Map<String, List<List<String?>>>) = JSONObject().apply {
        rows.forEach { (table, values) -> put(table, JSONObject().put("rows", values.size).put("sha256", hash(JSONArray(values.map { JSONArray(it) }).toString()))) }
    }
    private fun fileSignatures(folder: File): Map<String, String> = if (!folder.isDirectory) emptyMap() else
        folder.walkTopDown().filter { it.isFile }.associate { it.relativeTo(folder).invariantSeparatorsPath to "${it.length()}:${hash(it)}" }.toSortedMap()
    private fun screenshot(t: Trial, name: String) {
        compose.waitForIdle()
        val file = File(t.output, name)
        check(!file.exists())
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() }
        t.report.getJSONArray("screenshots").put(name)
        File(t.output, "device-trial.json").writeText(t.report.toString(2))
    }
    private class Fixture(run: String, now: Long) {
        private val prefix = "reader-flow:$run"
        val source = Source("$prefix:source", "自写长文（交互测试）", "https://example.invalid/reader-flow/$run/feed",
            readingMode = ReadingMode.NATIVE, enabled = false, aiAllowed = false,
            license = "Self-authored Lexicon instrumentation text, dedicated to the public domain under CC0; synthetic scenario, not a publisher article.")
        val text = LONG_TEXT.trimIndent().split("\n\n").joinToString("\n\n") { it.trim().replace(Regex("\\s+"), " ") }
        val wordCount = Regex("[A-Za-z]+(?:['-][A-Za-z]+)*").findAll(text).count()
        val article = Article("$prefix:article", "$prefix:guid", listOf(source.id), "自写长文：一间阅读室如何连接社区（交互测试）",
            "https://example.invalid/reader-flow/$run/article", "用于连续阅读、查词与模式切换的自写材料。",
            author = "Lexicon instrumentation fixture", publishedAt = now, topic = "Culture", topicOrigin = "synthetic_fixture",
            read = true, cachedAt = now, readingMode = ReadingMode.NATIVE, sourceName = source.name, license = source.license, wordCount = wordCount)
        val body = ArticleBody(article.id, text, hash(text), now, now + 3_600_000, source.license, article.author)
        val paragraphs = ParagraphFocus.paragraphs(text)
        private val targetSentenceStart = text.indexOf(TARGET_SENTENCE).also { check(it >= 0 && text.indexOf(TARGET_SENTENCE, it + 1) < 0) }
        val targetStart = targetSentenceStart + TARGET_SENTENCE.indexOf("world")
        val targetEnd = targetStart + "world".length
        val selection = TextCapture.extract(text, targetStart, targetEnd)
    }
    private companion object {
        const val STABLE_MS = 1_200L
        const val SWIPE_MS = 650L
        const val POSITION_TOLERANCE = 2f
        const val MOVEMENT_FLOOR = 8f
        const val TARGET_PARAGRAPH = 6
        val TOOLBAR_LABELS = listOf("← 退出 Zen", "精读", "阅读工具", "Aa")
        const val TARGET_SENTENCE = "In this imagined world, a robust reading room keeps local knowledge within reach."
        fun elapsedMs(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        val LONG_TEXT = """
            This is a self-authored account of an imagined neighbourhood reading room. The people, decisions and places in it are fictional, and the text makes no claim about a real community. Its purpose is to provide continuous prose for testing a reading interface. In the story, residents inherit a small empty shop beside a bus stop. They want to turn it into a place where anyone can read without buying a drink. The proposal seems simple at first, but each conversation reveals another practical question. Who will open the door, which books will fit on the shelves, and how will different readers share a quiet room?

            The first meeting begins with memories rather than drawings. One resident remembers studying at a kitchen table while a television played nearby. Another describes the pleasure of finding an unfamiliar book by chance. A student says that the wider world becomes easier to understand when reading can happen without hurry. These accounts do not produce a single design. They establish several different reasons for using the room. The group writes those reasons on separate sheets and keeps them visible during later discussions. Whenever an attractive proposal adds cost or complexity, someone asks which of the original needs it would actually serve.

            Space soon becomes the main constraint. A long table would support group work, while several smaller tables would give individual readers more choice. Tall shelves would hold more books but could block the light from the front window. The residents place empty boxes on the floor to represent furniture, then walk between them carrying bags and coats. They notice that a route which looks generous on a sketch can feel narrow in practice. Instead of treating the first drawing as a commitment, they use it as a question. Each arrangement is tested against ordinary movements before anyone orders materials.

            Choosing books requires a different kind of discussion. The group has received boxes of donations, but quantity alone does not make a useful collection. Some books are damaged, several sets are incomplete, and many titles repeat subjects already represented. Volunteers agree to describe the collection before expanding it. They sort the books into broad themes and record where readers ask for something they cannot find. A small empty space on a shelf is allowed to remain empty until a clear need appears. This makes the room look less crowded and leaves room for requests that the founders did not anticipate.

            Opening hours expose another tension. The people who can volunteer most easily are not always available when other residents need the room. A regular morning schedule would be dependable, but students and evening workers might rarely benefit from it. The group considers a short trial with several different sessions rather than announcing permanent hours immediately. Volunteers record arrivals without collecting names, and visitors can leave brief comments about timing. Those observations help distinguish a quiet session from an inconvenient one. The residents also decide that cancelling an occasional session honestly is better than promising availability they cannot maintain.

            Quietness needs to be negotiated, because it does not mean the same thing to everyone. Some visitors want complete silence, while others read with children or discuss a difficult passage with a friend. The room is too small to divide into separate rooms. The group therefore experiments with a quiet side, a conversation table near the entrance, and clear signs that use ordinary language. They listen from several seats before judging the arrangement. A rule that sounds reasonable in a meeting may still fail when chairs scrape across the floor. Practical adjustments matter as much as the wording of the rule.

            The seventh discussion turns from furniture to access. In this imagined world, a robust reading room keeps local knowledge within reach. Residents suggest a small shelf for maps, guides to public services, and explanations of unfamiliar local procedures. The shelf is useful only if people can find what they need without feeling embarrassed to ask. Volunteers therefore test the labels with visitors who did not attend the planning meetings. They replace internal terms with short questions, keep several languages together where possible, and leave a clear route back to the main collection. Help should remain available without taking control away from the reader.

            Once the room opens, maintenance becomes part of its daily rhythm. A loose chair leg and a confusing shelf label are small problems when someone notices them early. They become larger problems when each visitor assumes that somebody else has already reported them. The volunteers keep a simple notebook near the desk. Each entry names the problem, records whether it has been checked, and leaves enough space for a later response. The notebook is not a performance chart. It gives the next volunteer a reliable account of what needs attention and prevents the same concern from being rediscovered every afternoon.

            The residents also think about readers who prefer different formats. Some people bring a screen, some choose large print, and others listen while following a page. Electrical sockets and adjustable lights become more useful than a single preferred device. The group avoids rearranging the whole room around one person, but it asks whether small changes would help many people. A lamp can move without shifting a shelf. A chair can have a firm back without being heavy. These details give visitors choices that are easy to understand and easy to reverse when their needs change during a long reading session.

            After several weeks, the group reviews its observations. A busy afternoon does not automatically mean that everyone found what they wanted. A quiet hour does not prove that the room lacked value. Visitors may need time to settle into a book before they say anything at all. The review therefore combines modest counts with specific accounts of what happened. The volunteers compare requests, note where people hesitated, and describe which changes made a task easier. They avoid turning every action into a target. The aim is to improve the conditions for reading, not to reward behaviour merely because it is easy to count.

            One proposed improvement is deliberately postponed. A complicated reservation system might solve occasional competition for a popular seat, but it would also introduce accounts, rules and another device to maintain. The residents first try a short shared note explaining how to ask for a turn. If the simple arrangement repeatedly fails, they can reconsider the larger system with better evidence. Postponement is not a refusal to improve. It preserves attention for problems that are already affecting visitors. The group learns to distinguish a useful capability from a feature that would require more explanation than the problem itself.

            The imagined reading room remains unfinished in a productive sense. Its shelves, hours and signs can change as different people use it, while its basic purpose stays recognisable. A visitor should be able to enter, choose something to read, ask for help when necessary, and return to the page without losing their place. The residents judge new ideas against that ordinary journey. They value improvements that make the room calmer and easier to use without demanding more attention from the reader. The story ends with a volunteer closing the notebook, adjusting a lamp, and leaving the next visitor enough quiet space to begin.
        """
    }
}
