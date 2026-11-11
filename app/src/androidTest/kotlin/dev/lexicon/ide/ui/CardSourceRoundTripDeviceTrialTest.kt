package dev.lexicon.ide.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.ConnectivityManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
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
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
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
 * Actual MainActivity, existing installation, offline and without a key. Opt in with
 * cardSourceRoundTripTrial=1 and a new evidenceRun. No ratings, saves, queue changes or AI.
 * Reading does write sessions/exposure/resumes/events; only verified new owned rows are
 * removed. An original row is never replaced. Host pre-startup preservation is separate.
 */
@RunWith(AndroidJUnit4::class)
class CardSourceRoundTripDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun twoPublisherCardsReturnFromTheirExactOriginalAfterActivityRecreation() = trial("publisher-cards") { t ->
        val materials = publisherMaterials(t)
        materials.forEach { permitReading(t, it.article, it.body) }
        launchHome(t)
        command("cards", "浏览生词卡")
        for ((index, material) in materials.withIndex()) {
            t.stage = "card_${index}_open"
            openCard(material.card)
            compose.onNodeWithTag("card_open_source").performScrollTo().assertIsDisplayed()
            val position = cardPosition(material.card.id)
            screenshot(t, "${index + 1}1-card-before-source.png")
            compose.onNodeWithTag("card_open_source").performClick()
            t.sourceOpenActions++
            awaitOriginal(material.body, material.capture.selectionStart, material.capture.selectionEnd)
            screenshot(t, "${index + 1}2-exact-original.png")
            if (index == 0) {
                t.stage = "source_activity_recreation"
                requireNotNull(t.activity).recreate()
                awaitOriginal(material.body, material.capture.selectionStart, material.capture.selectionEnd)
                t.report.put("sourceActivityRecreationRetainedOriginal", true)
                screenshot(t, "13-original-after-recreation.png")
            }
            t.stage = "card_${index}_source_return"
            back(t)
            compose.waitUntil(15_000) { exists("card_detail_${material.card.id}") }
            compose.waitForIdle()
            check(abs(cardPosition(material.card.id) - position) <= 2f) { "Card position changed after source return" }
            check(!exists("card_definition_edit")) { "Source navigation entered editing" }
            t.report.getJSONArray("cards").put(JSONObject().put("cardIdSha256", hash(material.card.id))
                .put("captureIdSha256", hash(material.capture.id)).put("bodyHash", material.body.contentHash)
                .put("rangeStart", material.capture.selectionStart).put("rangeEnd", material.capture.selectionEnd)
                .put("originalRangeBytesVerified", true).put("rangeVisibleInOriginal", true)
                .put("returnedToSameCard", true).put("cardScrollRetained", true))
            screenshot(t, "${index + 1}4-returned-card.png")
            compose.onNodeWithTag("card_back").performScrollTo().performClick()
            compose.waitUntil(10_000) { exists("card_library_search") }
        }
    }

    @Test fun readerExistingCardAndTemporarySourceReturnToTheOriginalReader() = trial("reader-card-source") { t ->
        val candidates = publisherMaterials(t)
        val initial = candidates.first()
        permitReading(t, initial.article, initial.body)
        launchHome(t)
        compose.onNodeWithTag("daily_reading").performScrollToNode(hasText("读过的文章"))
        compose.onNodeWithText("读过的文章").performClick()
        compose.onNodeWithTag("article_history_list").performScrollToNode(hasText(initial.article.title))
        compose.onNodeWithText(initial.article.title).performClick()
        awaitOriginal(initial.body)
        val material = candidates.firstOrNull { candidate ->
            val start = requireNotNull(candidate.capture.selectionStart)
            val end = requireNotNull(candidate.capture.selectionEnd)
            VocabularyLearning.sameCapture(candidate.capture,
                captureAt(candidate.article, candidate.body, start, end, candidate.capture.aiAllowed, false))
        } ?: error("No existing capture matches the exact native range and original context")
        val start = requireNotNull(material.capture.selectionStart)
        val end = requireNotNull(material.capture.selectionEnd)
        scrollToOffset(start, .40f)
        t.stage = "select_exact_existing_range"
        selectExactRange(start, end)
        val originalPosition = readerPosition()
        val originalParagraph = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode()
            .config[ReaderViewportParagraphKey]
        screenshot(t, "01-original-before-existing-card.png")
        t.report.put("selectionMethod", "BasicTextField OnLongClick for focus, then SetSelection(start,end,false); not a human drag gesture")
            .put("selectedStart", start).put("selectedEnd", end).put("exactSelectionVerifiedBeforeMenuAction", true)
        t.stage = "click_text_context_menu_capture"
        clickCaptureMenu(t)
        compose.waitUntil(15_000) { exists("capture_existing_card") }
        check(field("capture_target") == material.capture.target)
        check(field("capture_sentence") == material.capture.sentence)
        check(snapshot(t.database).getValue("captures") == t.original.getValue("captures"))
        compose.onNodeWithTag("capture_existing_card").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(15_000) { exists("card_detail_${material.card.id}") && !exists("capture_panel") }
        check(!existsText("[done] 生词卡已加入复习队列"))
        t.report.put("existingCardActionActuallyOpenedDetail", true).put("newCardSuccessNoticeAbsent", true)
        screenshot(t, "02-existing-card-detail.png")
        compose.onNodeWithTag("card_open_source").performScrollTo().performClick()
        t.sourceOpenActions++
        awaitOriginal(material.body, start, end)
        screenshot(t, "03-temporary-card-source.png")
        back(t)
        compose.waitUntil(15_000) { exists("card_detail_${material.card.id}") }
        compose.onNodeWithTag("card_back").performScrollTo().performClick()
        awaitOriginal(material.body)
        compose.waitForIdle()
        check(abs(readerPosition() - originalPosition) <= 2f) { "Original reader position was replaced by its temporary source visit" }
        check(!exists("card_detail_${material.card.id}") && !exists("capture_panel"))
        t.report.put("returnedToOriginalReader", true).put("originalReaderScrollRetained", true)
            .put("originalReaderPositionWasNonzero", originalPosition > 0f)
            .put("originalReaderParagraph", originalParagraph)
            .put("originalReaderBodyHash", material.body.contentHash)
        screenshot(t, "04-returned-original-reader.png")
    }

    @Test fun firstReviewCardSourceAvailabilityAndRoundTripAreReportedWithoutRating() = trial("review-source") { t ->
        val first = t.cards.filter { it.isDueForReview(t.startedAt) }.sortedBy { it.state.dueAt }.firstOrNull()
            ?: error("An existing due card is required; the trial will not change the queue")
        val synthetic = first.id == SYNTHETIC_CARD_ID || first.sourceRef.url.orEmpty().contains("example.org")
        t.report.put("firstReviewCardIdSha256", hash(first.id))
            .put("firstReviewCardMaterial", if (synthetic) "existing synthetic fixture; not publisher evidence" else "existing saved card")
        launchHome(t)
        command("review", "开始复习", substring = true)
        compose.waitUntil(15_000) { exists("review_answer") }
        check(compose.onAllNodesWithText(first.content.headword, substring = false).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("review_answer").performScrollTo().performClick()
        compose.waitForIdle()
        check(existsText("Good · 记得"))
        val counts = text("review_counts")
        val sourceButton = exists("review_open_source")
        val capture = t.captures[first.captureId]
        val article = capture?.articleId?.let(t.articles::get)
        val body = article?.id?.let(t.bodies::get)
        val sourceReady = sourceButton && article != null && body != null && article.read &&
            body.expiresAt > System.currentTimeMillis() + 600_000 && resumeKeysAbsent(t, article.id)
        t.report.put("reviewSourceButtonAvailable", sourceButton)
        if (!sourceReady) {
            t.partial = true
            t.report.put("reviewSourceRoundTrip", "not exercised: first card has no protected, available cached original")
                .put("reviewAnswerWasRevealed", true).put("reviewQueueWasNotReordered", true)
            screenshot(t, "01-first-review-available-state.png")
        } else {
            val original = requireNotNull(body)
            permitReading(t, requireNotNull(article), original)
            compose.onNodeWithTag("review_open_source").performScrollTo()
            val position = reviewPosition()
            screenshot(t, "01-review-before-original.png")
            compose.onNodeWithTag("review_open_source").performClick()
            t.sourceOpenActions++
            val exact = capture?.takeIf { it.bodyHash == original.contentHash && validRange(it, original) }
            awaitOriginal(original, exact?.selectionStart, exact?.selectionEnd)
            screenshot(t, "02-review-original.png")
            back(t)
            compose.waitUntil(15_000) { exists("review_card_scroll") }
            compose.waitForIdle()
            check(!exists("review_answer") && existsText("Good · 记得"))
            check(text("review_counts") == counts)
            check(compose.onAllNodesWithText(first.content.headword, substring = false).fetchSemanticsNodes().isNotEmpty())
            check(abs(reviewPosition() - position) <= 2f) { "Review source return lost the current position" }
            t.report.put("reviewSourceRoundTrip", "passed").put("reviewCardAndAnswerRetained", true)
                .put("reviewCountAndScrollRetained", true).put("reviewExactAnchorAvailable", exact != null)
            screenshot(t, "03-returned-review-answer.png")
        }
    }

    private fun trial(name: String, action: (Trial) -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Card source round-trip trial is opt-in", args.getString("cardSourceRoundTripTrial") == "1")
        val run = requireNotNull(args.getString("evidenceRun")).also { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiconApplication
        val output = File(app.filesDir, "validation-1.22.0/card-source-roundtrip/$name/$run")
        check(!output.exists() && output.mkdirs()) { "Use a new evidenceRun; preserve previous trial evidence" }
        val report = JSONObject().put("schema", "lexicon.card.source.roundtrip.trial.v1").put("status", "started")
            .put("case", name).put("evidenceRun", run).put("screenshots", JSONArray()).put("cards", JSONArray())
            .put("jsonContainsLearningText", false).put("snapshotScope", "All database tables after repository initialization; host checks pre-startup separately")
            .put("backMethod", "Activity OnBackPressedDispatcher; not a physical predictive gesture")
            .put("readingWritesExpected", true).put("savedCardEdits", 0).put("ratings", 0).put("aiActions", 0)
        val reportFile = File(output, "device-trial.json")
        reportFile.writeText(report.toString(2))
        var trial: Trial? = null
        var behavior = false
        var preserved = false
        try {
            requireOfflineNoKey(app)
            val repo = app.repository
            runBlocking { repo.initialize() }
            check(runBlocking { repo.jobs.first() }.none { it.status == "running" })
            val settings = runBlocking { repo.settings.first() }
            check(!settings.titleClassification && !settings.reader.automaticStudy) { "Disable automatic AI before taking the trial snapshot" }
            val db = app.getDatabasePath("lexicon.sqlite")
            val original = snapshot(db)
            val started = System.currentTimeMillis()
            val t = Trial(app, db, original, output, report, started,
                runBlocking { repo.cards.first() }, runBlocking { repo.captures.first() }.associateBy { it.id },
                runBlocking { repo.articles.first() }.associateBy { it.id }, runBlocking { repo.sources.first() })
            trial = t
            check(t.bodies.values.all { it.expiresAt > started + 600_000 }) { "Original body caches must remain valid through this trial" }
            verifyEditionWillNotRewriteOriginal(t)
            val version = app.packageManager.getPackageInfo(app.packageName, 0)
            report.put("version", version.versionName).put("versionCode", version.longVersionCode)
                .put("startedAt", started).put("theme", settings.theme.name).put("actualFontScale", app.resources.configuration.fontScale)
                .put("originalTableHashes", tableHashes(original)).put("originalSnapshotKeptOnlyInMemory", true)
            action(t)
            behavior = true
        } catch (error: Throwable) {
            report.put("failedStage", trial?.stage ?: "preflight").put("failureClass", error.javaClass.simpleName)
                .put("failureLocations", JSONArray(error.stackTrace.take(8).map { "${it.fileName}:${it.lineNumber}" }))
            trial?.let { runCatching { screenshot(it, "failure.png") } }
        } finally {
            trial?.let { t ->
                val writerJobs = existingWriterJobs(t)
                runCatching { t.activity?.close() }.onFailure { behavior = false; report.put("activityCloseFailureClass", it.javaClass.simpleName) }
                t.activity = null
                runCatching {
                    runBlocking { withTimeout(15_000) { writerJobs.joinAll() } }
                    report.put("joinedExistingViewModelJobs", writerJobs.size)
                    requireOfflineNoKey(app)
                    val settled = waitForQuietRows(t.database)
                    cleanupOwnedRows(t, settled)
                    val after = waitForQuietRows(t.database)
                    report.put("finalTableHashes", tableHashes(after))
                    check(after == t.original) { "Original database rows changed" }
                    preserved = true
                }.onFailure { report.put("preservationFailureClass", it.javaClass.simpleName) }
                report.put("sourceOpenActions", t.sourceOpenActions)
            }
            report.put("originalDataPreserved", preserved).put("allOriginalRowsCompared", preserved)
                .put("status", if (!behavior || !preserved) "failed" else if (trial?.partial == true) "partial" else "passed")
            reportFile.writeText(report.toString(2))
        }
        check(behavior && preserved) { "Card source trial failed; inspect the sanitized report and screenshots" }
    }

    private inner class Trial(val app: LexiconApplication, val database: File, val original: Map<String, List<List<String?>>>,
        val output: File, val report: JSONObject, val startedAt: Long, val cards: List<Card>, val captures: Map<String, Capture>,
        val articles: Map<String, Article>, val sources: List<Source>) {
        var activity: ActivityScenario<MainActivity>? = null
        var stage = "preflight"
        var partial = false
        var sourceOpenActions = 0
        val visited = linkedMapOf<String, ArticleBody>()
        val bodies = original.getValue("article_bodies").associate { requireNotNull(it[0]) to codec.decodeFromString<ArticleBody>(requireNotNull(it[2])) }
        val day = Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        var expectedEdition: DailyEdition? = null
    }

    private data class Material(val card: Card, val capture: Capture, val article: Article, val body: ArticleBody)
    private fun publisherMaterials(t: Trial): List<Material> = PUBLISHER_CARD_IDS.map { id ->
        val card = t.cards.single { it.id == id }
        val capture = requireNotNull(t.captures[card.captureId])
        val article = requireNotNull(t.articles[capture.articleId])
        val body = requireNotNull(t.bodies[article.id])
        check(article.id == PUBLISHER_ARTICLE_ID && article.read && article.readingMode == ReadingMode.NATIVE)
        check(card.sourceRef.url.orEmpty().startsWith("https://theconversation.com/"))
        check(card.contextOrigin == ContextOrigin.ORIGINAL && capture.contextOrigin == ContextOrigin.ORIGINAL)
        check(capture.sourceRef == card.sourceRef && capture.sentence == card.sourceSentence)
        check(capture.bodyHash == body.contentHash && body.articleId == article.id && validRange(capture, body))
        Material(card, capture, article, body)
    }
    private fun validRange(capture: Capture, body: ArticleBody): Boolean {
        val start = capture.selectionStart ?: return false
        val end = capture.selectionEnd ?: return false
        return start >= 0 && end > start && end <= body.text.length && body.text.substring(start, end) == capture.selectedText
    }
    private fun resumeKeysAbsent(t: Trial, articleId: String): Boolean {
        val suffix = hash(articleId)
        return t.original.getValue("preferences").none { it[0] in setOf("reading-resume:record:$suffix", "reading-resume:generation:$suffix") }
    }
    private fun permitReading(t: Trial, article: Article, body: ArticleBody) {
        check(article.read && body.articleId == article.id && body.expiresAt > System.currentTimeMillis() + 600_000)
        check(resumeKeysAbsent(t, article.id)) { "An existing continuation cannot be overwritten by an add-only trial" }
        t.visited[article.id] = body
    }
    private fun verifyEditionWillNotRewriteOriginal(t: Trial) {
        val rows = t.original.getValue("preferences").filter { it[0]?.startsWith("reading-library:edition:") == true }
        val existing = rows.singleOrNull { it[0] == "reading-library:edition:${t.day}" }?.let { codec.decodeFromString<DailyEdition>(requireNotNull(it[1])) }
        val history = rows.sortedByDescending { it[0] }.take(8).map { codec.decodeFromString<DailyEdition>(requireNotNull(it[1])) }
        val expected = ReadingDailyPlanner.extend(existing, t.articles.values.toList(), t.sources, t.startedAt, ZoneId.systemDefault().id, history)
        check(existing == null || expected == existing) { "Today's original edition would be changed" }
        t.expectedEdition = expected
    }
    private fun launchHome(t: Trial) {
        t.stage = "launch_main_activity"
        t.activity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(20_000) { exists("daily_reading") }
    }
    private fun command(query: String, label: String, substring: Boolean = false) {
        compose.onNodeWithText(">_  命令").performClick()
        compose.onNodeWithText("搜索命令…").performTextInput(query)
        compose.onNodeWithText(label, substring = substring).performClick()
    }
    private fun openCard(card: Card) {
        compose.waitUntil(10_000) { exists("card_library_search") }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(hasTestTag("card_library_${card.id}"))
        compose.onNodeWithTag("card_library_${card.id}").performClick()
        compose.waitUntil(10_000) { exists("card_detail_${card.id}") }
    }
    private fun back(t: Trial) {
        requireNotNull(t.activity).onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
    private fun awaitOriginal(body: ArticleBody, start: Int? = null, end: Int? = null) {
        compose.waitUntil(20_000) { exists("zen_article_text") }
        compose.waitUntil(15_000) {
            runCatching { textBody() == body.text && compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode()
                .config[ReaderPositionReadyKey] && (start == null || (offsetVisible(start) && offsetVisible(requireNotNull(end) - 1))) }.getOrDefault(false)
        }
        check(textBody() == body.text)
    }
    private fun layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("zen_article_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
    private fun scrollToOffset(offset: Int, fraction: Float) {
        val field = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode()
        val viewport = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().boundsInRoot
        val delta = field.positionInRoot.y + layout().getBoundingBox(offset).top - viewport.top - viewport.height * fraction
        readerScrollNode().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
        compose.waitForIdle()
        check(offsetVisible(offset))
    }
    private fun selectExactRange(start: Int, end: Int) {
        val field = compose.onNodeWithTag("zen_article_text")
        // These are the field's public accessibility actions. OnLongClick requests focus
        // without inferring a word; relativeToOriginal=false requests the selection toolbar.
        field.performSemanticsAction(SemanticsActions.OnLongClick) { check(it()) }
        field.performSemanticsAction(SemanticsActions.SetSelection) { check(it(start, end, false)) }
        compose.waitForIdle()
        check(field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange] == TextRange(start, end))
    }
    private fun clickCaptureMenu(t: Trial) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val serviceInfo = requireNotNull(automation.serviceInfo)
        val previousFlags = serviceInfo.flags
        try {
            serviceInfo.flags = previousFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            automation.serviceInfo = serviceInfo
            val deadline = SystemClock.uptimeMillis() + 10_000
            var overflowOpened = false
            val diagnostics = JSONArray()
            val observed = mutableSetOf<String>()
            t.report.put("contextMenuNodes", diagnostics).put("contextMenuAccessibilityFlags", serviceInfo.flags)
            fun record(kind: String, node: AccessibilityNodeInfo, action: String? = null, result: Boolean? = null) {
                val bounds = Rect().also(node::getBoundsInScreen)
                val entry = JSONObject().put("kind", kind).put("class", node.className?.toString())
                    .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                    .put("visible", node.isVisibleToUser).put("enabled", node.isEnabled).put("clickable", node.isClickable)
                    .put("actions", JSONArray(node.actionList.map { it.id })).put("windowId", node.windowId)
                if (action != null) entry.put("attempt", action).put("result", result)
                // Never record arbitrary node text or the selected article contents.
                if (diagnostics.length() < 40 && observed.add(entry.toString())) diagnostics.put(entry)
            }
            while (SystemClock.uptimeMillis() < deadline) {
                // Compose may provide this menu itself; Android's floating toolbar is a
                // separate accessibility window and is intentionally exercised as native UI.
                if (compose.onAllNodesWithText(CAPTURE_MENU_LABEL, substring = false).fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithText(CAPTURE_MENU_LABEL, substring = false).performClick()
                    t.report.put("contextMenuAction", "visible Compose menu item")
                    return
                }
                val roots = listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root }
                val items = roots.flatMap { root ->
                    (root.findAccessibilityNodeInfosByText(CAPTURE_MENU_LABEL) + toolbarNodes(root))
                        .filter { isCaptureMenuLabel(it.text) || isCaptureMenuLabel(it.contentDescription) }
                }.distinctBy { node ->
                    val bounds = Rect().also(node::getBoundsInScreen)
                    "${node.windowId}:${node.className}:$bounds"
                }
                for (item in items) {
                    record("capture", item)
                    val accessibleClick = clickAccessibleItem(item)
                    record("capture", item, "ACTION_CLICK", accessibleClick)
                    if (accessibleClick) {
                        t.report.put("contextMenuAction", "native Android toolbar ACTION_CLICK")
                            .put("contextMenuOverflowOpened", overflowOpened)
                        return
                    }
                    // FloatingToolbar labels can have valid screen bounds while their
                    // accessibility visibility/click flags are false. Tap only the exact
                    // custom label, never the external PROCESS_TEXT entry or its parent.
                    val touch = touchExactMenuItem(automation, item, t)
                    record("capture", item, "bounds-derived touchscreen DOWN/UP", touch)
                    if (touch) {
                        t.report.put("contextMenuAction", "native Android toolbar bounds-derived touchscreen DOWN/UP")
                            .put("contextMenuOverflowOpened", overflowOpened)
                        return
                    }
                }
                if (!overflowOpened) {
                    val overflow = roots.asSequence().mapNotNull(::findVisibleToolbarOverflow).firstOrNull()
                    if (overflow != null) {
                        record("overflow", overflow)
                        overflowOpened = clickAccessibleItem(overflow)
                        record("overflow", overflow, "ACTION_CLICK", overflowOpened)
                    }
                }
                SystemClock.sleep(100)
            }
            error("The visible text context menu did not expose the capture action")
        } finally {
            val current = requireNotNull(automation.serviceInfo)
            current.flags = previousFlags
            automation.serviceInfo = current
        }
    }
    private fun clickAccessibleItem(item: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = item
        repeat(5) {
            val node = current ?: return false
            if (node.isEnabled && (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = node.parent
        }
        return false
    }
    private fun isCaptureMenuLabel(value: CharSequence?): Boolean =
        value?.filterNot { it.isWhitespace() }?.toString() == CAPTURE_MENU_LABEL.filterNot { it.isWhitespace() }
    private fun touchExactMenuItem(automation: UiAutomation, item: AccessibilityNodeInfo, t: Trial): Boolean {
        if (!item.isEnabled || (!isCaptureMenuLabel(item.text) && !isCaptureMenuLabel(item.contentDescription))) return false
        val bounds = Rect().also(item::getBoundsInScreen)
        val metrics = t.app.resources.displayMetrics
        if (bounds.isEmpty || bounds.left < 0 || bounds.top < 0 || bounds.right > metrics.widthPixels ||
            bounds.bottom > metrics.heightPixels) return false
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        return try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            val pressed = automation.injectInputEvent(down, true)
            SystemClock.sleep(50)
            val released = automation.injectInputEvent(up, true)
            pressed && released
        } finally { down.recycle(); up.recycle() }
    }
    private fun toolbarNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty() && nodes.size < 800) {
            val node = pending.removeFirst()
            nodes.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::add)
        }
        return nodes
    }
    private fun findVisibleToolbarOverflow(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return toolbarNodes(root).firstOrNull { node ->
            val description = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
            node.isVisibleToUser && (description == "more options" || description == "show more options" ||
                description == "更多选项" || description == "更多")
        }
    }
    private fun offsetVisible(offset: Int): Boolean {
        val origin = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().positionInRoot
        val box = layout().getBoundingBox(offset)
        val viewport = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().boundsInRoot
        return origin.y + box.top >= viewport.top && origin.y + box.bottom <= viewport.bottom
    }
    private fun readerScrollNode() = compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("zen_article_text")))
    private fun readerPosition() = readerScrollNode().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun cardPosition(id: String) = compose.onNodeWithTag("card_detail_$id").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun reviewPosition() = compose.onNodeWithTag("review_card_scroll").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun textBody() = field("zen_article_text")
    private fun field(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun text(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("\n") { it.text }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun existsText(value: String) = compose.onAllNodesWithText(value, substring = false).fetchSemanticsNodes().isNotEmpty()

    private fun cleanupOwnedRows(t: Trial, settled: Map<String, List<List<String?>>>) {
        t.report.put("preCleanupTableHashes", tableHashes(settled))
        val ended = System.currentTimeMillis()
        val added = settled.mapValues { (table, rows) ->
            val previous = t.original.getValue(table)
            val ids = previous.map { it[0] }.toSet()
            check(rows.filter { it[0] in ids } == previous) { "An original row changed; do not restore the database" }
            rows.filter { it[0] !in ids }
        }
        check(settled.keys == t.original.keys)
        val sessions = added.getValue("preferences").filter { it[0]?.startsWith("reading-library:session:") == true }
            .associate { row -> requireNotNull(row[0]).removePrefix("reading-library:session:") to codec.decodeFromString<ReadingSession>(requireNotNull(row[1])) }
        fun owned(table: String, row: List<String?>): Boolean {
            val id = requireNotNull(row[0])
            if (table == "local_events") {
                val event = codec.decodeFromString<LocalEvent>(requireNotNull(row[2]))
                return event.id == id && event.createdAt in t.startedAt..ended && when (event.type) {
                    "zen_opened", "zen_duration_seconds" -> t.visited.isNotEmpty()
                    "reading_session" -> sessions[id.removePrefix("reading-session:")]?.let {
                        it.articleId in t.visited && it.startedAt in t.startedAt..ended
                    } == true
                    else -> false
                }
            }
            if (table != "preferences") return false
            val data = requireNotNull(row[1])
            if (id == "reading-library:edition:${t.day}") return codec.decodeFromString<DailyEdition>(data) == t.expectedEdition
            if (id.startsWith("reading-library:session:")) return sessions[id.removePrefix("reading-library:session:")]?.let {
                it.articleId in t.visited && it.startedAt in t.startedAt..ended && it.finishedAt <= ended
            } == true
            return t.visited.any { (articleId, body) ->
                when (id) {
                    "reading-exposure:article:$articleId", "reading-exposure:body:${body.contentHash}" -> data.toLongOrNull()?.let { it in t.startedAt..ended } == true
                    "reading-resume:record:${hash(articleId)}" -> codec.decodeFromString<ReadingResumeRecord>(data).let {
                        it.articleId == articleId && it.bodyHash == body.contentHash && it.updatedAt in t.startedAt..ended
                    }
                    "reading-resume:generation:${hash(articleId)}" -> JSONObject(data).let {
                        it.getInt("version") == 1 && it.getLong("generation") > 0 && it.getString("bodyHash") == body.contentHash
                    }
                    else -> false
                }
            }
        }
        added.forEach { (table, rows) -> rows.forEach { check(owned(table, it)) { "Unexpected new row in $table; preserve it for inspection" } } }
        SQLiteDatabase.openDatabase(t.database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                check(snapshot(db) == settled) { "Reading writes continued after the quiet interval" }
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
        error("Reading writes did not become quiet within the bounded observation")
    }
    private fun existingWriterJobs(t: Trial): List<Job> {
        val jobs = linkedSetOf<Job>()
        val observed = JSONArray()
        val scenario = t.activity ?: return emptyList()
        runCatching { scenario.onActivity { activity ->
            // A missing entry must not create a new ViewModel just for the test barrier.
            val provider = ViewModelProvider(activity, object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = error("Existing models only")
            })
            fun retain(name: String, find: () -> ViewModel) {
                runCatching { find().viewModelScope.coroutineContext[Job] }.getOrNull()?.let {
                    jobs += it; observed.put(name)
                }
            }
            retain("LexiconViewModel") { provider[LexiconViewModel::class.java] }
            retain("ReadingViewModel") { provider[ReadingViewModel::class.java] }
            t.visited.keys.forEach { id ->
                retain("ReadingResumeViewModel:${hash(id)}") {
                    provider["reading-resume:${hash(id)}", ReadingResumeViewModel::class.java]
                }
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
        val PUBLISHER_CARD_IDS = listOf("e64bf344-6330-36d9-8119-85ce795b4b56", "a39bd1bb-9fba-3de3-98a6-4d2e69a57a40")
        const val PUBLISHER_ARTICLE_ID = "2aa62935-da9c-3ede-a8a6-658c6215fbeb"
        const val SYNTHETIC_CARD_ID = "1ddc95ea-55ae-377c-8b78-722d76b7d3b8"
        const val CAPTURE_MENU_LABEL = "查词 / 捕获"
    }
}
