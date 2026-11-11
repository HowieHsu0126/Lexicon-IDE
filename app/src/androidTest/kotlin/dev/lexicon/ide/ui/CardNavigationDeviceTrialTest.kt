package dev.lexicon.ide.ui

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.ConnectivityManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.Article
import dev.lexicon.domain.ArticleBody
import dev.lexicon.domain.Card
import dev.lexicon.domain.Capture
import dev.lexicon.domain.ContextOrigin
import dev.lexicon.domain.ReadingMode
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

/**
 * Same before/after trial on two existing publisher cards. Unsaved UI edits only: no
 * save, rating, pause, article open, AI, sync, key change or learning-data restoration.
 * Known cross-card draft leakage is an observation in before; after requires isolation.
 */
@RunWith(AndroidJUnit4::class)
class CardNavigationDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val codec = Json { ignoreUnknownKeys = true }

    @Test fun existingPublisherCardDraftsStayWithTheirOwnCard() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Existing card navigation trial is opt-in", args.getString("cardNavigationTrial") == "1")
        val phase = requireNotNull(args.getString("phase")).also { require(it in setOf("before", "after")) }
        val run = requireNotNull(args.getString("evidenceRun")).also {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")))
        }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiconApplication
        val output = File(app.filesDir, "validation-1.22.0/card-navigation/$phase/$run")
        check(!output.exists() && output.mkdirs()) { "Preserve earlier trial evidence; use a new evidenceRun" }
        val reportFile = File(output, "device-trial.json")
        val version = app.packageManager.getPackageInfo(app.packageName, 0)
        val report = JSONObject().put("schema", "lexicon.card.navigation.trial.v1")
            .put("status", "started").put("phase", phase).put("evidenceRun", run)
            .put("version", version.versionName).put("versionCode", version.longVersionCode)
            .put("startedAt", System.currentTimeMillis()).put("screenshots", JSONArray())
            .put("scope", "Two existing The Conversation cards, unsaved definition draft and navigation; no rating or saved edits")
            .put("snapshotScope", "All application database tables after repository initialization; host must check pre-startup preservation separately")
            .put("jsonContainsLearningText", false)
        val repo = app.repository
        val database = app.getDatabasePath("lexicon.sqlite")
        var original: Map<String, List<List<String?>>>? = null
        var activity: ActivityScenario<MainActivity>? = null
        var completed = false
        var behaviorCorrect = false
        var preservationCorrect = false
        var stage = "preflight"
        var failureClass: String? = null
        reportFile.writeText(report.toString(2))
        try {
            requireOfflineNoKey(app)
            runBlocking { repo.initialize() }
            check(runBlocking { repo.jobs.first() }.none { it.status == "running" }) { "An existing AI job is active" }
            val baselineRows = snapshot(database).also { original = it }
            report.put("originalTableHashes", tableHashes(baselineRows)).put("originalSnapshotKeptOnlyInMemory", true)
            val cards = runBlocking { repo.cards.first() }
            val captures = runBlocking { repo.captures.first() }.associateBy { it.id }
            val articles = runBlocking { repo.articles.first() }.associateBy { it.id }
            val bodies = baselineRows.getValue("article_bodies").associate { row ->
                requireNotNull(row[0]) to codec.decodeFromString<ArticleBody>(requireNotNull(row[2]))
            }
            val candidates = cards.filter { card ->
                val capture = captures[card.captureId]
                val article = capture?.articleId?.let(articles::get)
                val body = article?.id?.let(bodies::get)
                validPublisherMaterial(card, capture, article, body)
            }.sortedBy { it.id }
            check(candidates.size >= 2) { "Two existing original publisher cards with exact cached provenance are required" }
            val a = candidates[0]
            val b = candidates[1]
            check(a.content.contextDefinition != b.content.contextDefinition)
            check(a.content.contextDefinition != DRAFT && b.content.contextDefinition != DRAFT)
            report.put("cardCount", cards.size).put("captureCount", captures.size)
                .put("candidateCount", candidates.size).put("cardAHash", hash(a.id)).put("cardBHash", hash(b.id))
                .put("cardADefinitionHash", hash(a.content.contextDefinition)).put("cardBDefinitionHash", hash(b.content.contextDefinition))
                .put("fixedDraftHash", hash(DRAFT)).put("bothCapturesAndNativeSnapshotsVerified", true)
            activity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(20_000) { exists("daily_reading") }
            stage = "open_card_a"
            compose.onNodeWithText(">_  命令").performClick()
            compose.onNodeWithText("搜索命令…").performTextInput("cards")
            compose.onNodeWithText("浏览生词卡").performClick()
            openCard(a)
            check(!exists("card_definition_edit")) { "Card A unexpectedly opened with a prior draft" }
            compose.onNodeWithTag("card_edit_analysis").performScrollTo().performClick()
            compose.onNodeWithTag("card_definition_edit").performScrollTo().performClick().performTextReplacement(DRAFT)
            check(field("card_definition_edit") == DRAFT)
            screenshot(output, report, "01-card-a-unsaved-draft.png")
            check(snapshot(database).getValue("cards") == baselineRows.getValue("cards"))

            stage = "switch_to_card_b"
            allCards()
            openCard(b)
            val bInitiallyEditing = exists("card_definition_edit")
            val initiallyVisibleDefinition = if (bInitiallyEditing) field("card_definition_edit") else null
            val initiallyVisibleHeadword = if (exists("card_headword_edit")) field("card_headword_edit") else null
            report.put("observations", JSONObject()
                .put("cardBInitiallyEditing", bInitiallyEditing)
                .put("cardBInitiallyShowsADraft", initiallyVisibleDefinition == DRAFT)
                .put("cardBInitialDefinitionHash", initiallyVisibleDefinition?.let(::hash) ?: JSONObject.NULL)
                .put("cardBInitialHeadwordMatchesB", initiallyVisibleHeadword?.let { it == b.content.headword } ?: JSONObject.NULL))
            screenshot(output, report, "02-card-b-initial-view.png")
            if (!bInitiallyEditing) compose.onNodeWithTag("card_edit_analysis").performScrollTo().performClick()
            compose.onNodeWithTag("card_definition_edit").performScrollTo().assertIsDisplayed()
            val bDefinitionMatches = field("card_definition_edit") == b.content.contextDefinition
            val bHeadwordMatches = field("card_headword_edit") == b.content.headword
            val bShowsDraft = field("card_definition_edit") == DRAFT
            report.getJSONObject("observations")
                .put("cardBEditorDefinitionMatchesB", bDefinitionMatches)
                .put("cardBEditorHeadwordMatchesB", bHeadwordMatches)
                .put("cardBEditorShowsADraft", bShowsDraft)
                .put("cardBEditorDefinitionHash", hash(field("card_definition_edit")))
            screenshot(output, report, "03-card-b-editor.png")
            check(snapshot(database).getValue("cards") == baselineRows.getValue("cards"))

            stage = "return_to_card_a"
            allCards()
            openCard(a)
            val aEditing = exists("card_definition_edit")
            val aDraftRetained = aEditing && field("card_definition_edit") == DRAFT
            report.getJSONObject("observations").put("cardAReturnedInEditingMode", aEditing)
                .put("cardAUnsavedDraftRetained", aDraftRetained)
            if (aEditing) compose.onNodeWithTag("card_definition_edit").performScrollTo()
            screenshot(output, report, "04-card-a-returned.png")
            behaviorCorrect = !bInitiallyEditing && bDefinitionMatches && bHeadwordMatches && !bShowsDraft && aDraftRetained
            report.put("crossCardDraftLeakObserved", bShowsDraft || initiallyVisibleDefinition == DRAFT)
                .put("isolationAndOwnDraftRetentionSatisfied", behaviorCorrect)
                .put("knownBaselineBugObserved", phase == "before" && !behaviorCorrect)
            completed = true
        } catch (error: Throwable) {
            failureClass = error.javaClass.simpleName
            report.put("failureClass", failureClass).put("failedStage", stage)
                .put("failureLocations", JSONArray(error.stackTrace.take(8).map { "${it.fileName}:${it.lineNumber}" }))
            runCatching { screenshot(output, report, "failure.png") }
        } finally {
            runCatching { activity?.close() }.onFailure {
                completed = false
                report.put("activityCloseFailureClass", it.javaClass.simpleName)
            }
            val before = original
            if (before != null) {
                runCatching {
                    requireOfflineNoKey(app)
                    val after = snapshot(database)
                    report.put("finalTableHashes", tableHashes(after))
                    check(after == before) { "Stored data changed during the unsaved-only trial" }
                    preservationCorrect = true
                }.onFailure {
                    report.put("preservationFailureClass", it.javaClass.simpleName)
                }
            }
            report.put("originalDataPreserved", preservationCorrect)
                .put("savedEdits", 0).put("ratings", 0).put("pauseActions", 0)
                .put("articleOpenActions", 0).put("aiActions", 0).put("syncActions", 0)
                .put("status", when {
                    !completed || failureClass != null || !preservationCorrect -> "failed"
                    phase == "before" -> "baseline_observed"
                    behaviorCorrect -> "passed"
                    else -> "failed"
                })
            reportFile.writeText(report.toString(2))
        }
        check(completed && failureClass == null && preservationCorrect && (phase == "before" || behaviorCorrect)) {
            "Card navigation trial did not meet its phase contract; inspect the sanitized report"
        }
    }

    private fun validPublisherMaterial(card: Card, capture: Capture?, article: Article?, body: ArticleBody?): Boolean {
        if (capture == null || article == null || body == null) return false
        if (!card.sourceRef.url.orEmpty().startsWith("https://theconversation.com/") || card.contextOrigin != ContextOrigin.ORIGINAL ||
            capture.contextOrigin != ContextOrigin.ORIGINAL || article.readingMode != ReadingMode.NATIVE || !article.read) return false
        if (capture.sourceRef != card.sourceRef || capture.sentence != card.sourceSentence ||
            body.articleId != article.id || capture.bodyHash != body.contentHash || body.expiresAt <= System.currentTimeMillis()) return false
        val start = capture.selectionStart ?: return false
        val end = capture.selectionEnd ?: return false
        return start >= 0 && end > start && end <= body.text.length && body.text.substring(start, end) == capture.selectedText
    }
    private fun openCard(card: Card) {
        compose.waitUntil(10_000) { exists("card_library_search") }
        compose.waitForIdle()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasTestTag("card_library_${card.id}"))
        compose.onNodeWithTag("card_library_${card.id}").performClick()
        compose.waitUntil(10_000) { exists("card_review_pause") }
        check(compose.onAllNodesWithText(card.content.headword, substring = false).fetchSemanticsNodes().isNotEmpty())
    }
    private fun allCards() {
        compose.onNodeWithText("← 所有卡片").performScrollTo().performClick()
        compose.waitUntil(10_000) { exists("card_library_search") }
    }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun field(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun requireOfflineNoKey(app: LexiconApplication) {
        check(app.getSystemService(ConnectivityManager::class.java).activeNetwork == null) { "Offline trial requires no active network" }
        check(!runBlocking { app.repository.hasApiKey() }) { "Use the existing no-key trial state" }
    }
    private fun snapshot(file: File): Map<String, List<List<String?>>> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            tables.associateWith { table ->
                require(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
                db.rawQuery("SELECT * FROM \"$table\" ORDER BY 1", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
                }
            }
        }
    private fun tableHashes(rows: Map<String, List<List<String?>>>) = JSONObject().apply {
        rows.forEach { (table, values) -> put(table, JSONObject().put("rows", values.size)
            .put("sha256", hash(JSONArray(values.map { JSONArray(it) }).toString()))) }
    }
    private fun screenshot(output: File, report: JSONObject, name: String) {
        compose.waitForIdle()
        val file = File(output, name)
        check(!file.exists())
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { image.recycle() }
        report.getJSONArray("screenshots").put(name)
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private companion object {
        const val DRAFT = "Unsaved navigation draft A. This test text must never be persisted."
    }
}
