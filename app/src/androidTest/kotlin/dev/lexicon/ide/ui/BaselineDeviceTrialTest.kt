package dev.lexicon.ide.ui

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
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
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in real-repository interaction trial. Uses an already-read, unexpired publisher cache in
 * the real daily edition or read-article history. It adds one familiar-text practice record, not a baseline.
 * No article fixtures, AI requests, edition replacement or credential inspection are used.
 */
@RunWith(AndroidJUnit4::class)
class BaselineDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()
    private data class Candidate(val article: Article, val body: ArticleBody, val paragraph: Int)

    @Test fun cachedPublisherArticleCompletesOfflineSelfTestAndReopensItsSavedResult() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lexiconBaselineDeviceTrial") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as LexiconApplication
        val repository = app.repository
        val baseline = requireNotNull(repository.baseline)
        val plans = requireNotNull(repository.speedReading)
        val reading = requireNotNull(repository.reading)
        val output = File(app.filesDir, "baseline-device-trial").apply { mkdirs() }
        val report = JSONObject().put("status", "started").put("automatedInteractionTrial", true)
            .put("humanReadingPerformanceMeasured", false).put("aiActionInvocations", 0)
            .put("networkTrafficMeasured", false).put("operations", JSONArray()).put("screenshots", JSONArray())
        fun saveReport() { File(output, "device-trial.json").writeText(report.toString(2)) }
        fun step(value: String) { report.getJSONArray("operations").put(value); saveReport() }
        fun skip(reason: String): Nothing {
            report.put("status", "skipped").put("reason", reason); saveReport()
            assumeTrue(reason, false)
            error("Unreachable skipped trial")
        }
        fun screenshot(name: String) {
            compose.waitForIdle(); instrumentation.waitForIdleSync()
            SystemClock.sleep(200)
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(output, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            report.getJSONArray("screenshots").put(name); saveReport()
        }

        runBlocking { repository.initialize() }
        val database = app.getDatabasePath("lexicon.sqlite")
        if (!database.isFile) skip("No installed article database is available.")
        val articles = runBlocking { repository.articles.first() }
        val sources = runBlocking { repository.sources.first() }
        val jobsBefore = runBlocking { repository.jobs.first() }
        if (jobsBefore.any { it.status == "running" }) skip("An AI job is already running; this offline trial will not overlap it.")
        val candidates = findCandidates(database, articles, sources)
        if (candidates.isEmpty()) skip("No already-read publisher article has a suitable unexpired native cache. No fetch or fixture fallback was attempted.")
        val before = runBlocking { baseline.library.first() }.associateBy { it.id }
        val cardsBefore = runBlocking { repository.cards.first() }.associateBy { it.id }
        val capturesBefore = runBlocking { repository.captures.first() }.associateBy { it.id }
        val speedBefore = runBlocking { plans.library.first() }
        val originalSettings = runBlocking { repository.settings.first() }
        val settingsRow = readPreference(database, "settings")
        var settingsChanged = false
        var scenario: ActivityScenario<MainActivity>? = null
        saveReport()
        try {
            runBlocking { repository.updateSettings(originalSettings.copy(titleClassification = false,
                reader = originalSettings.reader.copy(automaticStudy = false))) }
            settingsChanged = true
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(20_000) { existsTag("daily_reading") }
            val today = LocalDate.now().toString()
            compose.waitUntil(15_000) { runBlocking { reading.library.first().editions.any { it.day == today } } }
            val edition = runBlocking { reading.library.first().editions.first { it.day == today } }
            val selected = candidates.firstOrNull { it.article.id in edition.articleIds } ?: candidates.first()
            val fromDaily = selected.article.id in edition.articleIds
            report.put("articleId", selected.article.id).put("articleTitle", selected.article.title)
                .put("source", selected.article.sourceName).put("bodyHash", selected.body.contentHash)
                .put("paragraph", selected.paragraph + 1).put("editionDay", edition.day)
                .put("entryPath", if (fromDaily) "daily" else "read-article-history")
            val span = ParagraphFocus.paragraphs(selected.body.text)[selected.paragraph]
            val originalParagraph = selected.body.text.substring(span.start, span.endExclusive)
            if (fromDaily) {
                compose.onNodeWithTag("daily_reading").performScrollToNode(hasTestTag("daily_baseline_${selected.article.id}"))
                compose.onNodeWithTag("daily_baseline_${selected.article.id}").performClick()
                step("MainActivity → actual daily edition → cached publisher article self-test")
            } else {
                compose.onNodeWithTag("daily_reading").performScrollToNode(hasText("读过的文章"))
                compose.onNodeWithText("读过的文章").performClick()
                compose.waitUntil(10_000) { existsTag("article_history_list") }
                compose.onNodeWithTag("article_history_list").performScrollToNode(hasTestTag("article_baseline_${selected.article.id}"))
                compose.onNodeWithTag("article_baseline_${selected.article.id}").performClick()
                step("MainActivity → read-article history → cached publisher article self-test")
            }
            compose.waitUntil(20_000) { existsTag("baseline_prepare_offline") || existsTag("baseline_error") }
            assertFalse("Cached materials failed to prepare", existsTag("baseline_error"))
            compose.onNodeWithTag("zen_article_text").assertDoesNotExist()
            compose.onNodeWithTag("baseline_original_text").assertDoesNotExist()
            repeat(selected.paragraph) {
                compose.onNodeWithContentDescription("自测起始段落后移").performScrollTo().performClick()
            }
            compose.onNodeWithText("1 段").performScrollTo().performClick()
            compose.onNodeWithTag("baseline_unseen_confirmation").performScrollTo().assertIsOff()
            compose.onNodeWithText(originalParagraph).assertDoesNotExist()
            screenshot("01-setup-no-preview.png")
            compose.onNodeWithTag("baseline_prepare_offline").performScrollTo().performClick()
            compose.waitUntil(10_000) { existsTag("baseline_play_pause") || existsTag("baseline_error") }
            assertFalse(existsTag("baseline_error"))
            compose.onNodeWithTag("baseline_original_text").assertDoesNotExist()
            step("Selected one later paragraph without a text preview or unseen-material declaration; prepared offline")
            screenshot("02-ready-before-clock.png")

            compose.onNodeWithTag("baseline_play_pause").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { existsTag("baseline_original_text") }
            compose.onNodeWithTag("baseline_original_text").assertTextEquals(originalParagraph)
            val started = SystemClock.elapsedRealtime()
            compose.waitUntil(9_000) { SystemClock.elapsedRealtime() - started >= 6_000 }
            screenshot("03-reading-original.png")
            compose.onNodeWithTag("baseline_play_pause").performClick()
            compose.waitUntil(10_000) { !existsTag("baseline_original_text") && !existsText("正在保存…") }
            compose.onNodeWithText(originalParagraph).assertDoesNotExist()
            compose.onNodeWithTag("baseline_paused_cover").assertTextEquals("已暂停")
            val pausedRecord = runBlocking { baseline.library.first().single { it.id !in before } }
            val pausedMillis = pausedRecord.paragraphs.sumOf { it.activeMillis }
            val pausedAt = SystemClock.elapsedRealtime()
            compose.waitUntil(3_000) { SystemClock.elapsedRealtime() - pausedAt >= 900 }
            val stillPaused = runBlocking { baseline.get(pausedRecord.id) }
            assertEquals(pausedMillis, requireNotNull(stillPaused).paragraphs.sumOf { it.activeMillis })
            step("Started the real paragraph, paused, verified text removal and stable saved active time")
            screenshot("04-paused-text-hidden.png")
            compose.onNodeWithTag("baseline_play_pause").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { existsTag("baseline_original_text") }
            compose.onNodeWithTag("baseline_original_text").assertTextEquals(originalParagraph)
            val resumed = SystemClock.elapsedRealtime()
            compose.waitUntil(9_000) { SystemClock.elapsedRealtime() - resumed >= 6_000 }
            compose.onNodeWithTag("baseline_complete_paragraph").performClick()
            compose.waitUntil(15_000) { existsTag("baseline_read_original") || existsTag("baseline_error") }
            assertFalse(existsTag("baseline_error"))
            compose.onNodeWithTag("baseline_read_original").performScrollTo().assertIsEnabled()

            val records = runBlocking { baseline.library.first() }
            val record = records.single { it.id !in before }
            val plan = runBlocking { requireNotNull(plans.cachedPlan(record.planKey)) }
            val summary = ReadingBaselineMetrics.summarize(record, plan)
            assertTrue(record.completed)
            assertEquals(selected.article.id, record.articleId)
            assertEquals(selected.body.contentHash, record.bodyHash)
            assertEquals(listOf(selected.paragraph), plan.paragraphIndices)
            assertEquals("rules", plan.origin)
            assertFalse(record.userConfirmedUnseen)
            assertFalse(summary.qualifiesAsBaseline)
            assertTrue(summary.activeMillis >= 12_000)
            assertTrue(record.pauses >= 1)
            assertEquals(0, record.questionCount)
            assertTrue(record.answers.isEmpty())
            assertNull(summary.accuracy)
            assertNull(summary.effectiveWordsPerMinute)
            compose.onNodeWithText("阅读练习").performScrollTo().assertIsDisplayed()
            screenshot("05-saved-offline-practice.png")
            report.put("record", JSONObject().put("id", record.id).put("planKey", record.planKey)
                .put("completed", record.completed).put("localUnseen", record.localUnseen)
                .put("userConfirmedUnseen", record.userConfirmedUnseen).put("qualifiesAsBaseline", summary.qualifiesAsBaseline)
                .put("activeMillis", summary.activeMillis).put("wordsRead", summary.wordsRead).put("pauses", record.pauses)
                .put("questionCount", record.questionCount).put("accuracy", JSONObject.NULL)
                .put("effectiveWordsPerMinute", JSONObject.NULL).put("sourceStart", span.start).put("sourceEnd", span.endExclusive))
            step("Resumed, explicitly finished the paragraph and saved one offline familiar-material practice record")

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(10_000) { existsText(">_  命令") && !existsTag("baseline_screen") }
            compose.onNodeWithText(">_  命令").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("自测")
            compose.onNodeWithText("无辅助自测记录").performClick()
            compose.waitUntil(10_000) { existsTag("baseline_history") }
            compose.onNodeWithTag("baseline_history").performScrollToNode(hasTestTag("baseline_open_${record.id}"))
            screenshot("06-command-history.png")
            compose.onNodeWithTag("baseline_open_${record.id}").performClick()
            compose.waitUntil(15_000) { existsTag("baseline_read_original") || existsTag("baseline_error") }
            assertFalse(existsTag("baseline_error"))
            compose.onNodeWithTag("baseline_play_pause").assertDoesNotExist()
            compose.onNodeWithTag("baseline_question").assertDoesNotExist()
            compose.onNodeWithTag("baseline_read_original").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(15_000) { existsTag("zen_article_text") }
            compose.waitForIdle()
            assertParagraphVisible(selected.body, selected.paragraph)
            screenshot("07-original-precise-anchor.png")
            step("Command palette → saved self-test → original reader at the exact trained paragraph")

            val after = runBlocking { baseline.library.first() }.associateBy { it.id }
            assertEquals(1, after.keys.minus(before.keys).size)
            before.forEach { (id, value) -> assertEquals(value, after[id]) }
            assertEquals(record, after[record.id])
            assertEquals(cardsBefore, runBlocking { repository.cards.first() }.associateBy { it.id })
            assertEquals(capturesBefore, runBlocking { repository.captures.first() }.associateBy { it.id })
            assertEquals(speedBefore, runBlocking { plans.library.first() })
            assertEquals(jobsBefore.map { it.id }.toSet(), runBlocking { repository.jobs.first() }.map { it.id }.toSet())
            report.put("status", "passed").put("newPracticeRecords", 1).put("newAiJobIds", 0)
                .put("existingLearningRecordsPreserved", true).put("preciseOriginalAnchorVerified", true)
                .put("untestedPaths", JSONArray(listOf("Live AI comprehension", "Predictive back gesture", "Physical MagicOS performance", "Human reading ability")))
            saveReport()
        } catch (failure: Throwable) {
            if (report.optString("status") != "skipped") {
                report.put("status", "failed").put("failureType", failure.javaClass.simpleName)
                runCatching { screenshot("failure.png") }
                saveReport()
            }
            throw failure
        } finally {
            scenario?.close()
            if (settingsChanged) {
                try {
                    restorePreference(database, "settings", settingsRow)
                    val restored = readPreference(database, "settings") == settingsRow
                    report.put("settingsRestored", restored)
                    check(restored) { "The original settings were not restored" }
                } catch (failure: Throwable) {
                    report.put("status", "failed").put("settingsRestored", false).put("restoreFailureType", failure.javaClass.simpleName)
                    throw failure
                } finally { saveReport() }
            }
        }
    }

    private fun existsTag(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun existsText(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun assertParagraphVisible(body: ArticleBody, paragraph: Int) {
        val field = compose.onNodeWithTag("zen_article_text")
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val bounds = layouts.single().getBoundingBox(ParagraphFocus.paragraphs(body.text)[paragraph].start)
        val position = field.fetchSemanticsNode().positionInRoot
        val viewport = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("The trained paragraph must be visible", bounds.top + position.y >= viewport.top && bounds.bottom + position.y <= viewport.bottom)
    }

    private fun findCandidates(database: File, articles: List<Article>, sources: List<Source>): List<Candidate> {
        val excluded = Regex("(?i)fixture|synthetic|self-authored|trial|example\\.(org|com)|test-source|测试")
        val allowed = sources.filter { it.readingMode == ReadingMode.NATIVE &&
            !excluded.containsMatchIn(it.id + " " + it.name + " " + Uri.parse(it.feedUrl).host.orEmpty()) }.map { it.id }.toSet()
        return SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            articles.filter { article ->
                val uri = Uri.parse(article.url)
                article.read && article.readingMode == ReadingMode.NATIVE && article.sourceIds.any { it in allowed } &&
                    uri.scheme == "https" && uri.host == "theconversation.com" &&
                    !excluded.containsMatchIn(article.id + " " + article.title + " " + article.sourceName)
            }.mapNotNull { article ->
                val cached = db.rawQuery("SELECT data FROM article_bodies WHERE articleId=? AND expiresAt>?",
                    arrayOf(article.id, (System.currentTimeMillis() + 300_000).toString())).use { cursor ->
                    if (cursor.moveToFirst()) JSONObject(cursor.getString(0)) else null
                } ?: return@mapNotNull null
                val text = cached.optString("text")
                val hash = cached.optString("contentHash")
                if (text.isBlank() || hash != fingerprint(text) || cached.optString("articleId") != article.id) return@mapNotNull null
                val paragraphs = ParagraphFocus.paragraphs(text)
                val paragraph = paragraphs.indices.take(12).firstOrNull { index ->
                    val p = paragraphs[index]
                    index >= 3 && SpeedChunker.wordCount(text.substring(p.start, p.endExclusive)) in 30..120 && p.endExclusive - p.start <= 1800
                } ?: return@mapNotNull null
                Candidate(article, ArticleBody(article.id, text, hash, cached.optLong("cachedAt"), cached.optLong("expiresAt"), cached.optString("license")), paragraph)
            }
        }
    }

    private fun readPreference(database: File, key: String): String? =
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT data FROM preferences WHERE `key`=?", arrayOf(key)).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }

    private fun restorePreference(database: File, key: String, original: String?) {
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            if (original == null) db.delete("preferences", "`key`=?", arrayOf(key))
            else check(db.insertWithOnConflict("preferences", null, ContentValues().apply {
                put("key", key); put("data", original)
            }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
        }
    }
}
