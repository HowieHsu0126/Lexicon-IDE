package dev.lexicon.ide.ui

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
import java.util.Locale
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
 * A real MainActivity lookup-only trial on existing publisher text. No explicit save,
 * edit, AI request or rating. The before phase records automatic inbox growth; after
 * requires the visible inbox to stay unchanged. Read-only preflight dictionary queries
 * choose resolvable words, so this trial makes no cold-latency or dictionary-quality claim.
 * Only new, fully identified trial rows are removed; existing rows are never restored.
 */
@RunWith(AndroidJUnit4::class)
class CaptureInboxDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun closingThreeLocalLookupsDoesNotCreatePendingWork() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Existing lookup/inbox trial is opt-in", args.getString("captureInboxTrial") == "1")
        val phase = requireNotNull(args.getString("phase")).also { require(it in setOf("before", "after")) }
        val run = requireNotNull(args.getString("evidenceRun")).also {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")))
        }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiconApplication
        val output = File(app.filesDir, "validation-1.23.0/capture-inbox/$phase/$run")
        check(!output.exists() && output.mkdirs()) { "Use a new evidenceRun; preserve previous evidence" }
        val report = JSONObject().put("schema", "lexicon.capture.inbox.trial.v1")
            .put("status", "started").put("phase", phase).put("evidenceRun", run)
            .put("screenshots", JSONArray()).put("lookups", JSONArray())
            .put("jsonContainsLearningText", false)
            .put("snapshotScope", "All database tables after repository initialization and before MainActivity launch; host checks pre-startup separately")
            .put("explicitCaptureSaves", 0).put("savedCardEdits", 0).put("ratings", 0).put("aiActions", 0)
            .put("lookupMethod", "Touch at an actual text-layout word boundary; no direct capture callback")
            .put("preflightDictionaryQueries", "Read-only word selection; the measured UI is intentionally not a cold-performance sample")
        val reportFile = File(output, "device-trial.json")
        reportFile.writeText(report.toString(2))
        var trial: Trial? = null
        var finished = false
        var desiredBehavior = false
        var preserved = false
        try {
            requireOfflineNoKey(app)
            val repo = app.repository
            runBlocking { repo.initialize() }
            check(runBlocking { repo.jobs.first() }.none { it.status == "running" }) { "An existing AI job is active" }
            val settings = runBlocking { repo.settings.first() }
            check(!settings.titleClassification && !settings.reader.automaticStudy)
            val database = app.getDatabasePath("lexicon.sqlite")
            val original = snapshot(database)
            val startedAt = System.currentTimeMillis()
            val t = Trial(app, database, original, output, report, startedAt,
                runBlocking { repo.articles.first() }, runBlocking { repo.sources.first() },
                runBlocking { repo.captures.first() }, runBlocking { repo.cards.first() })
            trial = t
            check(t.article.read && t.article.captured && t.article.readingMode == ReadingMode.NATIVE)
            check(t.article.url.startsWith("https://theconversation.com/"))
            check(t.bodies.values.all { it.expiresAt > startedAt + 600_000 }) { "Caches must stay valid through this bounded trial" }
            check(t.body.articleId == t.article.id)
            check(t.original.getValue("preferences").none { it[0] in setOf(
                "reading-resume:record:${hash(t.article.id)}", "reading-resume:generation:${hash(t.article.id)}") }) {
                "An existing continuation may not be overwritten by this add-only trial"
            }
            verifyEditionWillNotRewriteOriginal(t)
            val dictionary = runBlocking { repo.dictionaryStatus() }
            check(dictionary.ready) { "An installed local dictionary is required" }
            val version = app.packageManager.getPackageInfo(app.packageName, 0)
            report.put("version", version.versionName).put("versionCode", version.longVersionCode)
                .put("startedAt", startedAt).put("theme", settings.theme.name)
                .put("actualFontScale", app.resources.configuration.fontScale)
                .put("articleIdSha256", hash(t.article.id)).put("bodyHash", t.body.contentHash)
                .put("selectedDictionaryIdSha256", hash(dictionary.id))
                .put("originalTableHashes", tableHashes(original))
                .put("originalSnapshotKeptOnlyInMemory", true)

            t.stage = "launch_and_read_initial_inbox"
            t.activity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(20_000) { exists("daily_reading") }
            openInbox(phase)
            val beforeInbox = visibleInboxCount()
            report.put("inboxCountBefore", beforeInbox).put("storedCaptureCountBefore", t.captures.size)
            screenshot(t, "01-inbox-before-lookups.png")
            back(t)
            compose.waitUntil(10_000) { exists("daily_reading") }
            compose.onNodeWithTag("daily_reading").performScrollToNode(hasText("读过的文章"))
            compose.onNodeWithText("读过的文章").performClick()
            compose.onNodeWithTag("article_history_list").performScrollToNode(hasText(t.article.title))
            t.articleOpened = true
            compose.onNodeWithText(t.article.title).performClick()
            awaitReader(t.body)

            t.stage = "choose_three_existing_text_words"
            val selections = selectResolvableWords(t)
            t.selections += selections
            report.put("selectionPlanSha256", hash(selections.joinToString("|") { "${it.capture.selectionStart}:${it.capture.selectionEnd}:${hash(it.capture.selectedText)}" }))
                .put("plannedLookupCount", selections.size)
            for ((index, selected) in selections.withIndex()) {
                t.stage = "lookup_${index + 1}"
                val start = requireNotNull(selected.capture.selectionStart)
                val end = requireNotNull(selected.capture.selectionEnd)
                val hit = start + (end - start) / 2
                scrollToOffset(hit)
                val boundary = layout().getWordBoundary(hit)
                check(boundary.min == start && boundary.max == end)
                t.attemptedIds += selected.capture.id
                val point = layout().getBoundingBox(hit).center
                compose.onNodeWithTag("zen_article_text").performTouchInput { advanceEventTime(600); click(point) }
                compose.waitUntil(15_000) { exists("capture_target") }
                check(field("capture_target") == selected.capture.target)
                check(field("capture_sentence") == selected.capture.sentence)
                compose.waitUntil(15_000) {
                    exists("capture_dictionary_definition_0") || exists("capture_dictionary_empty") || exists("capture_dictionary_lookup_error")
                }
                val found = exists("capture_dictionary_definition_0")
                val lookupReport = JSONObject().put("index", index + 1)
                    .put("targetSha256", hash(selected.capture.target)).put("captureIdSha256", hash(selected.capture.id))
                    .put("rangeStart", start).put("rangeEnd", end).put("targetMatchesOriginal", true)
                    .put("originalSentenceMatches", true).put("localDefinitionFound", found)
                report.getJSONArray("lookups").put(lookupReport)
                check(found) { "Preflight word has no visible local definition; do not report a successful lookup" }
                val expected = selected.entries.first()
                compose.onNodeWithTag("capture_dictionary_definition_0").performScrollTo().assertIsDisplayed()
                check(text("capture_dictionary_word_0") == "${expected.word}  ${expected.phonetic}")
                check(text("capture_dictionary_definition_0") == DictionaryPresentation.preview(expected.definition.ifBlank { "无释义" }).text)
                lookupReport.put("visibleDefinitionMatchesDictionary", true)
                screenshot(t, "0${index + 2}-local-lookup-${index + 1}.png")
                compose.onNodeWithTag("capture_close").assertIsDisplayed().performClick()
                compose.waitUntil(10_000) { !exists("capture_panel") }
                awaitReader(t.body)
                val afterClose = runBlocking { repo.captures.first() }
                lookupReport.put("storedAfterClose", afterClose.any { it.id == selected.capture.id })
                    .put("storedCaptureCountAfterClose", afterClose.size)
            }

            t.stage = "inspect_inbox_after_lookup_only_closes"
            back(t)
            compose.waitUntil(10_000) { exists("article_history_list") }
            openInbox(phase)
            val afterInbox = visibleInboxCount()
            val storedAfter = runBlocking { repo.captures.first() }
            val created = storedAfter.filter { it.id in t.attemptedIds }
            val visibleTrialIds = mutableListOf<String>()
            for (capture in created) {
                val node = compose.onNodeWithTag("pending_capture_${capture.id}")
                val visible = runCatching {
                    compose.onNodeWithTag("pending_captures").performScrollToNode(hasTestTag("pending_capture_${capture.id}"))
                    node.assertIsDisplayed()
                }.isSuccess
                if (visible) visibleTrialIds += capture.id
            }
            compose.onNodeWithTag("pending_captures").performSemanticsAction(SemanticsActions.ScrollToIndex) { it(0) }
            report.put("inboxCountAfter", afterInbox).put("inboxCountDelta", afterInbox - beforeInbox)
                .put("storedCaptureCountAfter", storedAfter.size).put("newLookupCaptureRows", created.size)
                .put("lookupRowsVisibleInInbox", visibleTrialIds.size)
                .put("automaticInboxGrowthObserved", afterInbox > beforeInbox && visibleTrialIds.isNotEmpty())
                .put("lookupOnlyCloses", selections.size)
            screenshot(t, "05-inbox-after-lookups.png")
            check(snapshot(database).getValue("cards") == original.getValue("cards"))
            check(snapshot(database).getValue("review_logs") == original.getValue("review_logs"))
            desiredBehavior = afterInbox == beforeInbox && visibleTrialIds.isEmpty()
            report.put("lookupOnlyDoesNotAddPendingWork", desiredBehavior)
            finished = true
        } catch (error: Throwable) {
            report.put("failureClass", error.javaClass.simpleName).put("failedStage", trial?.stage ?: "preflight")
                .put("failureLocations", JSONArray(error.stackTrace.take(8).map { "${it.fileName}:${it.lineNumber}" }))
            trial?.let { runCatching { screenshot(it, "failure.png") } }
        } finally {
            trial?.let { t ->
                val jobs = existingWriterJobs(t)
                runCatching { t.activity?.close() }.onFailure { finished = false; report.put("activityCloseFailureClass", it.javaClass.simpleName) }
                t.activity = null
                runCatching {
                    runBlocking { withTimeout(15_000) { jobs.joinAll() } }
                    report.put("joinedExistingViewModelJobs", jobs.size)
                    requireOfflineNoKey(app)
                    cleanupOwnedRows(t, waitForQuietRows(t.database))
                    val after = waitForQuietRows(t.database)
                    report.put("finalTableHashes", tableHashes(after))
                    check(after == t.original)
                    preserved = true
                }.onFailure { report.put("preservationFailureClass", it.javaClass.simpleName) }
            }
            report.put("originalDataPreserved", preserved).put("allOriginalRowsCompared", preserved)
                .put("status", when {
                    !finished || !preserved -> "failed"
                    phase == "before" -> "baseline_observed"
                    desiredBehavior -> "passed"
                    else -> "failed"
                })
            reportFile.writeText(report.toString(2))
        }
        check(finished && preserved && (phase == "before" || desiredBehavior)) {
            "Lookup/inbox trial failed its phase contract; inspect the sanitized report"
        }
    }

    /** All edits and archive operations belong to one newly created trial capture. */
    @Test fun explicitSavedCaptureCanBeArchivedAndRestoredWithoutLosingItsDraft() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Capture retention trial is opt-in", args.getString("captureRetentionTrial") == "1")
        val run = requireNotNull(args.getString("evidenceRun")).also {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")))
        }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiconApplication
        val output = File(app.filesDir, "validation-1.23.0/capture-retention/$run")
        check(!output.exists() && output.mkdirs()) { "Use a new evidenceRun; preserve previous evidence" }
        val report = JSONObject().put("schema", "lexicon.capture.retention.trial.v1")
            .put("status", "started").put("evidenceRun", run).put("screenshots", JSONArray())
            .put("jsonContainsLearningText", false).put("savedCardEdits", 0).put("ratings", 0).put("aiActions", 0)
            .put("scope", "One new capture from real publisher text, edited with an explicit synthetic user sentence; no original rows edited")
            .put("snapshotScope", "All database tables after initialization and before launch; host checks pre-startup separately")
            .put("lookupMethod", "Touch at actual text-layout word boundary; dictionary preflight is not a cold-performance sample")
        val reportFile = File(output, "device-trial.json")
        reportFile.writeText(report.toString(2))
        var trial: Trial? = null
        var finished = false
        var preserved = false
        try {
            requireOfflineNoKey(app)
            val repo = app.repository
            runBlocking { repo.initialize() }
            check(runBlocking { repo.jobs.first() }.none { it.status == "running" })
            val settings = runBlocking { repo.settings.first() }
            check(!settings.titleClassification && !settings.reader.automaticStudy)
            val inbox = requireNotNull(repo.captureInbox)
            val db = app.getDatabasePath("lexicon.sqlite")
            val original = snapshot(db)
            val started = System.currentTimeMillis()
            val t = Trial(app, db, original, output, report, started,
                runBlocking { repo.articles.first() }, runBlocking { repo.sources.first() },
                runBlocking { repo.captures.first() }, runBlocking { repo.cards.first() })
            trial = t
            t.additionalCaptureOpens = 1
            check(t.article.read && t.article.captured && t.article.readingMode == ReadingMode.NATIVE)
            check(t.article.url.startsWith("https://theconversation.com/"))
            check(t.bodies.values.all { it.expiresAt > started + 600_000 })
            check(original.getValue("preferences").none { it[0] in setOf(
                "reading-resume:record:${hash(t.article.id)}", "reading-resume:generation:${hash(t.article.id)}") })
            verifyEditionWillNotRewriteOriginal(t)
            check(runBlocking { repo.dictionaryStatus() }.ready)
            val originallyArchived = runBlocking { inbox.archivedCaptureIds.first() }
            val archivedVisibleBefore = t.captures.count { it.id in originallyArchived }
            val version = app.packageManager.getPackageInfo(app.packageName, 0)
            report.put("version", version.versionName).put("versionCode", version.longVersionCode)
                .put("startedAt", started).put("theme", settings.theme.name).put("actualFontScale", app.resources.configuration.fontScale)
                .put("articleIdSha256", hash(t.article.id)).put("bodyHash", t.body.contentHash)
                .put("originalTableHashes", tableHashes(original)).put("originalSnapshotKeptOnlyInMemory", true)
            t.stage = "initial_inbox"
            t.activity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(20_000) { exists("daily_reading") }
            openInbox()
            val beforeInbox = visibleInboxCount()
            report.put("inboxCountBefore", beforeInbox).put("archivedCountBefore", archivedVisibleBefore)
            back(t)
            compose.waitUntil(10_000) { exists("daily_reading") }
            compose.onNodeWithTag("daily_reading").performScrollToNode(hasText("读过的文章"))
            compose.onNodeWithText("读过的文章").performClick()
            compose.onNodeWithTag("article_history_list").performScrollToNode(hasText(t.article.title))
            t.articleOpened = true
            compose.onNodeWithText(t.article.title).performClick()
            awaitReader(t.body)

            t.stage = "open_and_edit_new_capture"
            val selected = selectResolvableWords(t).first()
            t.selections += selected
            t.attemptedIds += selected.capture.id
            val start = requireNotNull(selected.capture.selectionStart)
            val end = requireNotNull(selected.capture.selectionEnd)
            val hit = start + (end - start) / 2
            scrollToOffset(hit)
            check(layout().getWordBoundary(hit).let { it.min == start && it.max == end })
            val point = layout().getBoundingBox(hit).center
            compose.onNodeWithTag("zen_article_text").performTouchInput { advanceEventTime(600); click(point) }
            compose.waitUntil(15_000) { exists("capture_target") }
            check(field("capture_target") == selected.capture.target && field("capture_sentence") == selected.capture.sentence)
            val draftTarget = selected.capture.target.uppercase(Locale.ROOT)
            val draftSentence = selected.capture.sentence + " This is a user-supplied trial note."
            check(draftSentence.length <= CaptureLimits.SENTENCE)
            val expected = selected.capture.copy(target = draftTarget, sentence = draftSentence, contextOrigin = ContextOrigin.USER_SUPPLIED)
            t.expectedSavedInputs[selected.capture.id] = expected
            compose.onNodeWithTag("capture_target").performScrollTo().performTextReplacement(draftTarget)
            compose.onNodeWithTag("capture_sentence").performScrollTo().performTextReplacement(draftSentence)
            check(snapshot(db).getValue("captures") == original.getValue("captures")) { "Editing an unsaved lookup persisted it" }
            screenshot(t, "01-unsaved-edited-capture.png")
            t.stage = "recreate_unsaved_capture"
            requireNotNull(t.activity).recreate()
            compose.waitUntil(20_000) { exists("capture_target") && field("capture_target") == draftTarget && field("capture_sentence") == draftSentence }
            check(snapshot(db).getValue("captures") == original.getValue("captures"))
            report.put("unsavedDraftSurvivedActivityRecreation", true).put("unsavedCaptureRowsAdded", 0)
                .put("editedTargetSha256", hash(draftTarget)).put("editedSentenceSha256", hash(draftSentence))
                .put("userSuppliedSentenceIsNotOriginal", true)
            screenshot(t, "02-unsaved-draft-after-recreation.png")

            t.stage = "explicitly_save_capture"
            compose.onNodeWithTag("capture_save").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(15_000) { !exists("capture_panel") }
            awaitReader(t.body)
            val saved = runBlocking { repo.captures.first() }.single { it.id == selected.capture.id }
            checkSameMaterial(saved, expected)
            val savedRow = snapshot(db).getValue("captures").single { it[0] == saved.id }
            check(snapshot(db).getValue("captures").size == original.getValue("captures").size + 1)
            report.put("explicitCaptureSaves", 1).put("savedCaptureIdSha256", hash(saved.id))
                .put("exactSourceAndRangeRetained", true).put("savedContextOrigin", saved.contextOrigin.name)
            back(t)
            compose.waitUntil(10_000) { exists("article_history_list") }
            openInbox()
            awaitInboxCount(beforeInbox + 1)
            scrollToCapture(saved.id)
            screenshot(t, "03-explicitly-saved-inbox-item.png")

            t.stage = "archive_only_new_capture"
            compose.onNodeWithTag("capture_archive_${saved.id}").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { saved.id in runBlocking { inbox.archivedCaptureIds.first() } }
            awaitInboxCount(beforeInbox)
            check(snapshot(db).getValue("captures").single { it[0] == saved.id } == savedRow)
            compose.onNodeWithTag("capture_inbox_archived").performClick()
            compose.waitUntil(10_000) { exists("capture_restore_${saved.id}") }
            awaitInboxCount(archivedVisibleBefore + 1)
            scrollToCapture(saved.id)
            screenshot(t, "04-archived-item.png")
            compose.onNodeWithTag("pending_capture_${saved.id}").performClick()
            compose.waitUntil(10_000) { exists("capture_target") }
            check(field("capture_target") == draftTarget && field("capture_sentence") == draftSentence)
            compose.onNodeWithTag("capture_save").performScrollTo().assertTextEquals("保存修改")
            screenshot(t, "05-opened-archived-capture.png")
            compose.onNodeWithTag("capture_close").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { !exists("capture_panel") }
            check(saved.id in runBlocking { inbox.archivedCaptureIds.first() }) { "Viewing an archived capture restored it implicitly" }
            check(snapshot(db).getValue("captures").single { it[0] == saved.id } == savedRow)
            t.stage = "restore_only_new_capture"
            scrollToCapture(saved.id)
            compose.onNodeWithTag("capture_restore_${saved.id}").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { saved.id !in runBlocking { inbox.archivedCaptureIds.first() } }
            compose.onNodeWithTag("capture_inbox_active").performClick()
            awaitInboxCount(beforeInbox + 1)
            scrollToCapture(saved.id)
            screenshot(t, "06-restored-inbox-item.png")
            check(snapshot(db).getValue("captures").single { it[0] == saved.id } == savedRow)
            check(snapshot(db).getValue("cards") == original.getValue("cards"))
            check(snapshot(db).getValue("review_logs") == original.getValue("review_logs"))
            check(snapshot(db).getValue("ai_jobs") == original.getValue("ai_jobs"))
            report.put("archivedAndRestoredExplicitly", true).put("viewingArchiveDidNotRestoreIt", true)
                .put("savedMaterialUnchangedAcrossArchiveRoundTrip", true).put("cardsReviewsAndJobsUnchanged", true)
                .put("inboxCountAfterRestore", beforeInbox + 1)
            finished = true
        } catch (error: Throwable) {
            report.put("failureClass", error.javaClass.simpleName).put("failedStage", trial?.stage ?: "preflight")
                .put("failureLocations", JSONArray(error.stackTrace.take(8).map { "${it.fileName}:${it.lineNumber}" }))
            trial?.let { runCatching { screenshot(it, "failure.png") } }
        } finally {
            trial?.let { t ->
                val jobs = existingWriterJobs(t)
                runCatching { t.activity?.close() }.onFailure { finished = false; report.put("activityCloseFailureClass", it.javaClass.simpleName) }
                t.activity = null
                runCatching {
                    runBlocking { withTimeout(15_000) { jobs.joinAll() } }
                    report.put("joinedExistingViewModelJobs", jobs.size)
                    requireOfflineNoKey(app)
                    cleanupOwnedRows(t, waitForQuietRows(t.database))
                    val after = waitForQuietRows(t.database)
                    report.put("finalTableHashes", tableHashes(after))
                    check(after == t.original)
                    preserved = true
                }.onFailure { report.put("preservationFailureClass", it.javaClass.simpleName) }
            }
            report.put("originalDataPreserved", preserved).put("allOriginalRowsCompared", preserved)
                .put("status", if (finished && preserved) "passed" else "failed")
            reportFile.writeText(report.toString(2))
        }
        check(finished && preserved) { "Capture retention trial failed; inspect the sanitized report" }
    }

    private data class Selection(val capture: Capture, val entries: List<DictionaryEntry>)
    private inner class Trial(val app: LexiconApplication, val database: File, val original: Map<String, List<List<String?>>>,
        val output: File, val report: JSONObject, val startedAt: Long, val articles: List<Article>, val sources: List<Source>,
        val captures: List<Capture>, val cards: List<Card>) {
        val article = articles.single { it.id == ARTICLE_ID }
        val bodies = original.getValue("article_bodies").associate { requireNotNull(it[0]) to codec.decodeFromString<ArticleBody>(requireNotNull(it[2])) }
        val body = requireNotNull(bodies[article.id])
        val day = Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val selections = mutableListOf<Selection>()
        val attemptedIds = linkedSetOf<String>()
        val expectedSavedInputs = mutableMapOf<String, Capture>()
        var additionalCaptureOpens = 0
        var activity: ActivityScenario<MainActivity>? = null
        var articleOpened = false
        var expectedEdition: DailyEdition? = null
        var stage = "preflight"
    }

    private fun selectResolvableWords(t: Trial): List<Selection> {
        val result = mutableListOf<Selection>()
        val seen = mutableSetOf<String>()
        val allowed = t.sources.any { it.id in t.article.sourceIds && it.aiAllowed }
        var queries = 0
        for (match in Regex("\\b[A-Za-z]{4,12}\\b").findAll(t.body.text)) {
            if (queries >= 30 || result.size == LOOKUP_COUNT) break
            if (!seen.add(match.value.lowercase(Locale.ROOT))) continue
            val start = match.range.first
            val end = match.range.last + 1
            val boundary = layout().getWordBoundary(start + (end - start) / 2)
            if (boundary.min != start || boundary.max != end) continue
            val capture = captureAt(t.article, t.body, start, end, allowed, false)
            if (t.captures.any { it.id == capture.id || VocabularyLearning.sameCapture(it, capture) }) continue
            if (capture.sentence.isBlank() || capture.contextOrigin != ContextOrigin.ORIGINAL) continue
            queries++
            val entries = runBlocking { t.app.repository.lookup(capture.target) }
            if (entries.firstOrNull()?.definition?.isNotBlank() == true) result += Selection(capture, entries)
        }
        t.report.put("preflightDictionaryQueryCount", queries)
        check(result.size == LOOKUP_COUNT) { "Three unused original word ranges with local definitions are required" }
        return result
    }
    private fun openInbox(phase: String = "after") {
        compose.onNodeWithText(">_  命令").performClick()
        compose.onNodeWithText("搜索命令…").performTextInput("inbox")
        compose.onNodeWithText(if (phase == "before") "待补充捕获" else "待处理捕获", substring = true).performClick()
        compose.waitUntil(10_000) { exists("pending_captures") || existsText("还没有捕获") }
    }
    private fun visibleInboxCount(): Int {
        if (existsText("还没有捕获")) return 0
        compose.onNodeWithTag("pending_captures").performSemanticsAction(SemanticsActions.ScrollToIndex) { it(0) }
        compose.waitForIdle()
        compose.waitUntil(10_000) {
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
                .flatMap { it.config[SemanticsProperties.Text] }.any { it.text.matches(Regex("\\d+ 条捕获")) }
        }
        val match = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
            .flatMap { it.config[SemanticsProperties.Text] }.map { it.text }.singleOrNull { it.matches(Regex("\\d+ 条捕获")) }
        return requireNotNull(match) { "The inbox must expose a visible capture count" }.substringBefore(' ').toInt()
    }
    private fun awaitInboxCount(expected: Int) {
        compose.onNodeWithTag("pending_captures").performSemanticsAction(SemanticsActions.ScrollToIndex) { it(0) }
        compose.waitUntil(10_000) { runCatching { text("capture_inbox_count") == "$expected 条捕获" }.getOrDefault(false) }
        check(visibleInboxCount() == expected)
    }
    private fun back(t: Trial) {
        requireNotNull(t.activity).onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
    private fun scrollToCapture(id: String) {
        compose.onNodeWithTag("pending_captures").performScrollToNode(hasTestTag("pending_capture_$id"))
        compose.onNodeWithTag("pending_capture_$id").assertIsDisplayed()
    }
    private fun checkSameMaterial(actual: Capture, expected: Capture) {
        check(actual.id == expected.id && actual.target == expected.target && actual.selectedText == expected.selectedText &&
            actual.sentence == expected.sentence && actual.contextOrigin == expected.contextOrigin && actual.articleId == expected.articleId &&
            actual.sourceRef == expected.sourceRef && actual.bodyHash == expected.bodyHash && actual.selectionStart == expected.selectionStart &&
            actual.selectionEnd == expected.selectionEnd && actual.aiAllowed == expected.aiAllowed)
    }
    private fun awaitReader(body: ArticleBody) {
        compose.waitUntil(20_000) { exists("zen_article_text") }
        compose.waitUntil(15_000) {
            runCatching { field("zen_article_text") == body.text && compose.onNodeWithTag("zen_reader_viewport")
                .fetchSemanticsNode().config[ReaderPositionReadyKey] }.getOrDefault(false)
        }
    }
    private fun layout(): TextLayoutResult {
        val values = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("zen_article_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(values) }
        return values.single()
    }
    private fun scrollToOffset(offset: Int) {
        val field = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode()
        val viewport = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().boundsInRoot
        val delta = field.positionInRoot.y + layout().getBoundingBox(offset).top - viewport.top - viewport.height * .42f
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("zen_article_text")))
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
        compose.waitForIdle()
        val current = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().positionInRoot
        val box = layout().getBoundingBox(offset)
        check(current.y + box.top >= viewport.top && current.y + box.bottom <= viewport.bottom)
    }
    private fun field(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun text(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("\n") { it.text }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun existsText(text: String) = compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty()

    private fun verifyEditionWillNotRewriteOriginal(t: Trial) {
        val rows = t.original.getValue("preferences").filter { it[0]?.startsWith("reading-library:edition:") == true }
        val existing = rows.singleOrNull { it[0] == "reading-library:edition:${t.day}" }?.let { codec.decodeFromString<DailyEdition>(requireNotNull(it[1])) }
        val history = rows.sortedByDescending { it[0] }.take(8).map { codec.decodeFromString<DailyEdition>(requireNotNull(it[1])) }
        t.expectedEdition = ReadingDailyPlanner.extend(existing, t.articles, t.sources, t.startedAt, ZoneId.systemDefault().id, history)
        check(existing == null || t.expectedEdition == existing) { "Today's original edition would change" }
    }
    private fun cleanupOwnedRows(t: Trial, settled: Map<String, List<List<String?>>>) {
        val ended = System.currentTimeMillis()
        t.report.put("preCleanupTableHashes", tableHashes(settled))
        check(settled.keys == t.original.keys)
        val added = settled.mapValues { (table, rows) ->
            val old = t.original.getValue(table)
            val ids = old.map { it[0] }.toSet()
            check(rows.filter { it[0] in ids } == old) { "An original row changed; do not restore it" }
            rows.filter { it[0] !in ids }
        }
        val sessions = added.getValue("preferences").filter { it[0]?.startsWith("reading-library:session:") == true }
            .associate { requireNotNull(it[0]).removePrefix("reading-library:session:") to codec.decodeFromString<ReadingSession>(requireNotNull(it[1])) }
        fun owned(table: String, row: List<String?>): Boolean {
            val id = requireNotNull(row[0])
            if (table == "captures") {
                val capture = codec.decodeFromString<Capture>(requireNotNull(row[2]))
                val original = t.selections.singleOrNull { it.capture.id == id }?.capture ?: return false
                val knownInputs = listOfNotNull(original, t.expectedSavedInputs[id])
                return id in t.attemptedIds && capture.id == id && capture.createdAt in t.startedAt..ended &&
                    capture.updatedAt in capture.createdAt..ended && capture.articleId == t.article.id &&
                    capture.bodyHash == t.body.contentHash && knownInputs.any { expected ->
                        capture.target == expected.target && capture.selectedText == expected.selectedText && capture.sentence == expected.sentence &&
                            capture.contextOrigin == expected.contextOrigin && capture.sourceRef == expected.sourceRef &&
                            capture.selectionStart == expected.selectionStart && capture.selectionEnd == expected.selectionEnd
                    }
            }
            if (table == "local_events") {
                val event = codec.decodeFromString<LocalEvent>(requireNotNull(row[2]))
                return event.id == id && event.createdAt in t.startedAt..ended && when (event.type) {
                    "capture_saved", "capture_opened" -> t.attemptedIds.isNotEmpty()
                    "zen_opened", "zen_duration_seconds" -> t.articleOpened
                    "reading_session" -> sessions[id.removePrefix("reading-session:")]?.let {
                        it.articleId == t.article.id && it.startedAt in t.startedAt..ended
                    } == true
                    else -> false
                }
            }
            if (table != "preferences") return false
            val data = requireNotNull(row[1])
            if (id.startsWith("capture-inbox:archived:v1:")) {
                val archive = JSONObject(data)
                val captureId = archive.optString("captureId")
                return captureId in t.expectedSavedInputs && id == "capture-inbox:archived:v1:${hash(captureId)}" &&
                    archive.getInt("version") == 1 && archive.getLong("archivedAt") in t.startedAt..ended &&
                    archive.keys().asSequence().toSet() == setOf("version", "captureId", "archivedAt")
            }
            if (id == "reading-library:edition:${t.day}") return codec.decodeFromString<DailyEdition>(data) == t.expectedEdition
            if (id.startsWith("reading-library:session:")) return sessions[id.removePrefix("reading-library:session:")]?.let {
                it.articleId == t.article.id && it.startedAt in t.startedAt..ended && it.finishedAt <= ended
            } == true
            if (!t.articleOpened) return false
            return when (id) {
                "reading-exposure:article:${t.article.id}", "reading-exposure:body:${t.body.contentHash}" -> data.toLongOrNull()?.let { it in t.startedAt..ended } == true
                "reading-resume:record:${hash(t.article.id)}" -> codec.decodeFromString<ReadingResumeRecord>(data).let {
                    it.articleId == t.article.id && it.bodyHash == t.body.contentHash && it.updatedAt in t.startedAt..ended
                }
                "reading-resume:generation:${hash(t.article.id)}" -> JSONObject(data).let {
                    it.getInt("version") == 1 && it.getLong("generation") > 0 && it.getString("bodyHash") == t.body.contentHash
                }
                else -> false
            }
        }
        check(added.getValue("local_events").count { row -> codec.decodeFromString<LocalEvent>(requireNotNull(row[2])).type in setOf("capture_saved", "capture_opened") } <= t.attemptedIds.size * 2 + t.additionalCaptureOpens)
        added.forEach { (table, rows) -> rows.forEach { check(owned(table, it)) { "Unexpected new row in $table; preserve for inspection" } } }
        SQLiteDatabase.openDatabase(t.database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                check(snapshot(db) == settled) { "Writes continued after the quiet interval" }
                added.forEach { (table, rows) -> rows.forEach { row ->
                    val key = if (table == "preferences") "`key`" else "id"
                    check(db.delete(table, "$key=?", arrayOf(requireNotNull(row[0]))) == 1)
                } }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        t.report.put("removedOwnedRows", JSONObject().apply { added.forEach { (table, rows) -> if (rows.isNotEmpty()) put(table, rows.size) } })
            .put("originalRowsRestoredOrReplaced", 0).put("quietIntervalMs", 2_000)
    }
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
        error("Reading writes did not become quiet")
    }
    private fun existingWriterJobs(t: Trial): List<Job> {
        val jobs = linkedSetOf<Job>()
        val observed = JSONArray()
        val scenario = t.activity ?: return emptyList()
        runCatching { scenario.onActivity { activity ->
            val provider = ViewModelProvider(activity, object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = error("Existing models only")
            })
            fun retain(name: String, find: () -> ViewModel) {
                runCatching { find().viewModelScope.coroutineContext[Job] }.getOrNull()?.let { jobs += it; observed.put(name) }
            }
            retain("LexiconViewModel") { provider[LexiconViewModel::class.java] }
            retain("ReadingViewModel") { provider[ReadingViewModel::class.java] }
            if (t.articleOpened) retain("ReadingResumeViewModel:${hash(t.article.id)}") {
                provider["reading-resume:${hash(t.article.id)}", ReadingResumeViewModel::class.java]
            }
        } }.onFailure { t.report.put("writerJobInspectionFailureClass", it.javaClass.simpleName) }
        t.report.put("observedWriterModels", observed)
        return jobs.toList()
    }
    private fun requireOfflineNoKey(app: LexiconApplication) {
        check(app.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
        check(!runBlocking { app.repository.hasApiKey() })
    }
    private fun snapshot(file: File): Map<String, List<List<String?>>> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use(::snapshot)
    private fun snapshot(db: SQLiteDatabase): Map<String, List<List<String?>>> {
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return tables.associateWith { table ->
            require(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            db.rawQuery("SELECT * FROM \"$table\" ORDER BY 1", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
            }
        }
    }
    private fun tableHashes(rows: Map<String, List<List<String?>>>) = JSONObject().apply {
        rows.forEach { (table, values) -> put(table, JSONObject().put("rows", values.size).put("sha256", hash(JSONArray(values.map { JSONArray(it) }).toString()))) }
    }
    private fun screenshot(t: Trial, name: String) {
        compose.waitForIdle()
        val file = File(t.output, name)
        check(!file.exists())
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() }
        t.report.getJSONArray("screenshots").put(name)
        File(t.output, "device-trial.json").writeText(t.report.toString(2))
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private companion object {
        const val ARTICLE_ID = "2aa62935-da9c-3ede-a8a6-658c6215fbeb"
        const val LOOKUP_COUNT = 3
    }
}
