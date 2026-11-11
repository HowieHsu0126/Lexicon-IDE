package dev.lexicon.ide.ui

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import dev.lexicon.ide.LexiconApplication
import dev.lexicon.ide.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Opt-in installed-app trial. Reads only existing, unexpired real-publisher article caches.
 * Does not inject articles, mock a repository, submit AI requests or inspect the API Key.
 * A real local training record remains as the result of the explicitly requested device trial.
 */
@RunWith(AndroidJUnit4::class)
class SpeedDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()

    private data class Candidate(val article: Article, val body: ArticleBody, val paragraph: Int)

    @Test fun cachedExternalArticleCompletesOfflineSpeedTrainingThroughAppNavigation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lexiconSpeedDeviceTrial") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val transferTrial = InstrumentationRegistry.getArguments().getString("lexiconSpeedTransferTrial") == "1"
        val app = instrumentation.targetContext.applicationContext as LexiconApplication
        val repository = app.repository
        val speedRepository = requireNotNull(repository.speedReading)
        val output = File(app.filesDir, "speed-device-trial").apply { mkdirs() }
        val report = JSONObject().put("status", "started")
            .put("operations", JSONArray()).put("screenshots", JSONArray())
            .put("aiSubmissions", 0).put("networkTrafficMeasured", false)
        fun saveReport() { File(output, "device-trial.json").writeText(report.toString(2)) }
        fun step(value: String) { report.getJSONArray("operations").put(value); saveReport() }
        fun skip(reason: String) {
            report.put("status", "skipped").put("reason", reason)
            saveReport()
            assumeTrue(reason, false)
        }
        fun screenshot(name: String) {
            compose.waitForIdle()
            instrumentation.waitForIdleSync()
            SystemClock.sleep(250)
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(output, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            report.getJSONArray("screenshots").put(name)
            saveReport()
        }

        runBlocking { repository.initialize() }
        val databaseFile = app.getDatabasePath("lexicon.sqlite")
        if (!databaseFile.isFile) skip("No installed article database is available.")
        val articles = runBlocking { repository.articles.first() }
        val sources = runBlocking { repository.sources.first() }
        val jobsBefore = runBlocking { repository.jobs.first() }
        if (jobsBefore.any { it.status == "running" }) skip("An AI job is already running; the offline trial will not overlap it.")
        val candidate = findCandidate(databaseFile, articles, sources)
        if (candidate == null) skip("No already-read real-publisher article has a suitable unexpired native cache. No network fetch or fixture fallback was attempted.")
        val selected = requireNotNull(candidate)
        report.put("articleId", selected.article.id).put("articleTitle", selected.article.title)
        report.put("paragraph", selected.paragraph + 1)
        val originalSettings = runBlocking { repository.settings.first() }
        val originalSettingsRow = readPreference(databaseFile, "settings")
        val existingRunIds = runBlocking { speedRepository.library.first().runs.map { it.id }.toSet() }
        val existingNaturalIds = runBlocking { speedRepository.library.first().naturalAttempts.map { it.id }.toSet() }
        var scenario: ActivityScenario<MainActivity>? = null
        var settingsChanged = false
        saveReport()
        try {
            // updateSettings may schedule title classification when that flag is true.
            // Disable both automatic entry points before opening any article.
            runBlocking { repository.updateSettings(originalSettings.copy(titleClassification = false,
                reader = originalSettings.reader.copy(automaticStudy = false))) }
            settingsChanged = true
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(20_000) { existsTag("daily_reading") }
            step("Opened MainActivity and the daily reading list")

            // This route works for any cached read article, including one not selected in today's edition.
            compose.onNodeWithTag("daily_reading").performScrollToNode(hasText("读过的文章"))
            compose.onNodeWithText("读过的文章").performClick()
            val lazyList = SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)
            compose.onNode(lazyList).performScrollToNode(hasText(selected.article.title))
            compose.onNodeWithText(selected.article.title).performClick()
            compose.waitUntil(20_000) { existsTag("zen_article_text") }
            step("Read articles → selected the cached real article")
            compose.onNodeWithText("精读 · 工具").performScrollTo().performClick()
            compose.onNodeWithText("意群速读 →").performScrollTo().performClick()
            compose.waitUntil(10_000) { existsTag("speed_prepare_offline") }
            step("Reading tools → sense-group speed reading")

            // Exercise each granularity control once; retain L2 for the actual one-paragraph run.
            compose.onNodeWithText("L1 · 舒适").performScrollTo().performClick()
            compose.onNodeWithText("L3 · 挑战").performScrollTo().performClick()
            compose.onNodeWithText("L2 · 标准").performScrollTo().performClick()
            val from = compose.onNodeWithText("从第 ", substring = true).fetchSemanticsNode()
                .config[SemanticsProperties.Text].joinToString(" ") { it.text }
            val initialParagraph = requireNotNull(Regex("从第 (\\d+) 段开始").find(from)).groupValues[1].toInt() - 1
            val difference = selected.paragraph - initialParagraph
            repeat(kotlin.math.abs(difference)) {
                compose.onNodeWithContentDescription(if (difference > 0) "起始段落后移" else "起始段落前移")
                    .performScrollTo().performClick()
            }
            compose.onNodeWithText("1 段").performScrollTo().performClick()
            compose.onNodeWithTag("speed_slider").performScrollTo().performTouchInput {
                click(Offset(width * .86f, centerY))
            }
            compose.onNodeWithTag("speed_prepare_offline").performScrollTo().performClick()
            compose.waitUntil(10_000) { existsTag("speed_original_text") }
            assertTrue("The trial must use local rule segmentation", existsText("本地规则切分 · 本轮不测理解率"))
            step("Selected L1, L3 and L2; prepared one cached paragraph with local rules")
            screenshot("01-offline-reading-ready.png")

            compose.onNodeWithTag("speed_play_pause").performClick()
            compose.waitUntil(8_000) { (groupCounter()?.first ?: 0) >= 2 }
            compose.onNodeWithTag("speed_play_pause").performClick()
            val paused = requireNotNull(groupCounter())
            assertTrue(existsText("开始 / 继续"))
            val pausedAt = SystemClock.elapsedRealtime()
            compose.waitUntil(3_000) { SystemClock.elapsedRealtime() - pausedAt >= 800 }
            assertEquals(paused, groupCounter())
            compose.onNodeWithTag("speed_repeat").performClick()
            assertEquals(paused, groupCounter())
            compose.onNodeWithTag("speed_previous").performClick()
            assertEquals(paused.first - 1, groupCounter()?.first)
            step("Automatic advance → pause → stable cursor → repeat → previous group")
            screenshot("02-real-article-paused.png")
            compose.onNodeWithTag("speed_play_pause").performClick()
            compose.waitUntil(50_000) { existsTag("speed_restart") || existsTag("speed_error") }
            assertFalse("Offline playback returned an error", existsTag("speed_error"))
            compose.waitUntil(8_000) { !existsText("正在保存…") }
            compose.onNodeWithTag("speed_restart").performScrollTo().assertIsEnabled()
            compose.onAllNodesWithText("未测").assertCountEquals(2)
            step("Automatic advance reached the training result; comprehension remains unmeasured")
            compose.onNodeWithText("本次训练").performScrollTo()
            screenshot("03-offline-result.png")

            val newRuns = runBlocking { speedRepository.library.first().runs.filter { it.id !in existingRunIds } }
            assertEquals(1, newRuns.size)
            val run = newRuns.single()
            assertEquals(selected.article.id, run.articleId)
            assertEquals(selected.body.contentHash, run.bodyHash)
            assertEquals(SpeedGranularity.L2, run.granularity)
            assertEquals("rules", run.planOrigin)
            assertTrue(run.completed)
            assertEquals(true, run.pacedCompletion)
            assertTrue(run.activeMillis > 0)
            assertTrue(run.revisits >= 2)
            assertTrue(run.pauses >= 1)
            assertEquals(0, run.questionCount)
            assertTrue(run.answers.isEmpty())
            val metrics = SpeedMetrics.summarize(run)
            assertNull(metrics.comprehension)
            assertNull(metrics.effectiveWordsPerMinute)
            report.put("metrics", JSONObject().put("completed", run.completed).put("pacedCompletion", run.pacedCompletion)
                .put("activeMillis", run.activeMillis).put("wordsRead", run.wordsRead).put("groupsRead", run.groupsRead)
                .put("targetGroupsPerMinute", run.groupsPerMinute).put("actualGroupsPerMinute", metrics.groupsPerMinute)
                .put("playbackWordsPerMinute", metrics.wordsPerMinute).put("revisits", run.revisits).put("pauses", run.pauses)
                .put("comprehension", JSONObject.NULL).put("effectiveWordsPerMinute", JSONObject.NULL))

            compose.onNodeWithTag("speed_edit_segmentation").performScrollTo().performClick()
            compose.waitUntil(8_000) { existsTag("segmentation_original_preview") }
            assertFalse("The cached sentence must be available", existsTag("segmentation_error"))
            screenshot("05-real-article-segmentation.png")
            compose.onNodeWithTag("segmentation_close").performClick()
            compose.waitUntil(5_000) { !existsTag("speed_segmentation_editor") }
            step("Result → inspect the real sentence boundaries → close without altering the completed run")

            if (transferTrial) {
                compose.onNodeWithTag("speed_next_round").performScrollTo().assertIsEnabled().performClick()
                compose.waitUntil(10_000) { existsTag("speed_original_text") }
                assertTrue(existsText("淡出练习"))
                screenshot("06-faded-real-article.png")
                compose.onNodeWithTag("speed_play_pause").performClick()
                compose.waitUntil(50_000) { existsTag("speed_next_round") || existsTag("speed_error") }
                assertFalse(existsTag("speed_error"))
                compose.onNodeWithTag("speed_next_round").performScrollTo().assertIsEnabled().performClick()
                compose.waitUntil(10_000) { existsTag("natural_original_text") }
                val paragraph = ParagraphFocus.paragraphs(selected.body.text)[selected.paragraph]
                compose.onNodeWithTag("natural_original_text").assertTextEquals(selected.body.text.substring(paragraph.start, paragraph.endExclusive))
                compose.onNodeWithTag("speed_slider").assertDoesNotExist()
                screenshot("07-natural-real-article.png")
                compose.onNodeWithTag("natural_play_pause").performClick()
                val naturalStarted = SystemClock.elapsedRealtime()
                compose.waitUntil(5_000) { SystemClock.elapsedRealtime() - naturalStarted >= 2000 }
                compose.onNodeWithTag("natural_play_pause").performClick()
                compose.onNodeWithTag("natural_complete_paragraph").performClick()
                compose.waitUntil(10_000) { existsTag("natural_result") && !existsText("正在保存…") }
                assertFalse(existsTag("natural_error"))
                screenshot("08-natural-result.png")
                val library = runBlocking { speedRepository.library.first() }
                val faded = library.runs.single { it.id !in existingRunIds && it.round == SpeedPacedRound.FADED }
                val natural = library.naturalAttempts.single { it.id !in existingNaturalIds }
                assertEquals(run.id, faded.parentRunId)
                assertEquals(run.planKey, faded.planKey)
                assertEquals(faded.id, natural.parentRunId)
                assertEquals(run.planKey, natural.planKey)
                assertTrue(faded.completed && natural.completed)
                assertEquals(0, faded.questionCount)
                assertTrue(faded.answers.isEmpty())
                report.put("transfer", JSONObject().put("status", "passed").put("sameFrozenPlan", true)
                    .put("naturalActiveMillis", natural.paragraphs.sumOf { it.activeMillis })
                    .put("newPacedRuns", 2).put("newNaturalAttempts", 1))
                step("Completed explicit faded and natural rounds with the same cached source and no new questions")
                compose.onNodeWithTag("speed_read_original").performScrollTo().performClick()
                compose.waitUntil(10_000) { existsTag("zen_article_text") }
                compose.waitForIdle()
                assertParagraphVisible(selected.body, selected.paragraph)
                screenshot("09-anchored-original.png")
                step("Natural result → original reader at the actual trained paragraph")
            } else {
                compose.onNodeWithTag("speed_next_paragraph").performScrollTo().assertIsEnabled().performClick()
                compose.waitUntil(10_000) { existsTag("speed_prepare_offline") }
                compose.onNodeWithText("从第 ${selected.paragraph + 2} 段开始").assertExists()
                compose.onNodeWithText("${run.groupsPerMinute} 意群/分钟").performScrollTo().assertExists()
                step("Next paragraph kept the chosen pace and opened setup without an AI request")
                screenshot("04-next-paragraph-settings.png")
                compose.onNodeWithContentDescription("返回阅读").performClick()
                compose.waitUntil(10_000) { existsTag("zen_article_text") }
                step("Next-paragraph setup → original native reader")
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(10_000) { existsText(">_  命令") && !existsTag("zen_article_text") }
            step("System back button → article list")
            compose.onNodeWithText(">_  命令").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("速读")
            compose.onNodeWithText("意群速读记录").performClick()
            compose.waitUntil(10_000) { existsTag("speed_history") }
            compose.onNodeWithTag("speed_history").performScrollToNode(hasText(selected.article.title))
            compose.onAllNodesWithText(selected.article.title).onFirst().assertIsDisplayed()
            step("Command palette → sense-group training history → recorded article")
            screenshot("04-training-history.png")
            val after = runBlocking { speedRepository.library.first().runs.filter { it.id !in existingRunIds } }
            assertEquals(if (transferTrial) 2 else 1, after.size)
            assertEquals(run, after.single { it.id == run.id })
            val jobsAfter = runBlocking { repository.jobs.first() }
            assertEquals(jobsBefore.map { it.id }.toSet(), jobsAfter.map { it.id }.toSet())
            report.put("status", "passed").put("newTrainingRecords", if (transferTrial) 2 else 1).put("newAiJobIds", 0)
                .put("untestedPaths", JSONArray(listOf("Daily article direct speed-reading button", "AI calibration and comprehension", "Predictive back gesture", "Physical MagicOS device performance")))
            saveReport()
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failureType", failure.javaClass.simpleName)
            runCatching { screenshot("failure.png") }
            saveReport()
            throw failure
        } finally {
            scenario?.close()
            if (settingsChanged) {
                // Exact local restoration avoids updateSettings restarting paid classification.
                // Activity is closed, so the next settings collector reads this restored row.
                try {
                    restorePreference(databaseFile, "settings", originalSettingsRow)
                    val restored = readPreference(databaseFile, "settings") == originalSettingsRow
                    report.put("settingsRestored", restored)
                    check(restored) { "The original reading settings were not restored" }
                } catch (failure: Throwable) {
                    report.put("status", "failed").put("settingsRestored", false)
                        .put("restoreFailureType", failure.javaClass.simpleName)
                    throw failure
                } finally { saveReport() }
            }
        }
    }

    private fun existsTag(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun assertParagraphVisible(body: ArticleBody, paragraph: Int) {
        val field = compose.onNodeWithTag("zen_article_text")
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val offset = ParagraphFocus.paragraphs(body.text)[paragraph].start
        val bounds = layouts.single().getBoundingBox(offset)
        val position = field.fetchSemanticsNode().positionInRoot
        val viewport = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("The trained paragraph must be visible", bounds.top + position.y >= viewport.top && bounds.bottom + position.y <= viewport.bottom)
    }
    private fun existsText(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun groupCounter(): Pair<Int, Int>? {
        val pattern = Regex("1 / 1 段 · (\\d+) / (\\d+) 意群")
        return compose.onAllNodes(hasText("意群", substring = true)).fetchSemanticsNodes().asSequence()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().asSequence() }
            .mapNotNull { pattern.find(it.text) }.firstOrNull()?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    }

    private fun findCandidate(database: File, articles: List<Article>, sources: List<Source>): Candidate? {
        val allowedSources = sources.filter { source ->
            val host = Uri.parse(source.feedUrl).host.orEmpty()
            source.readingMode == ReadingMode.NATIVE && host.isNotBlank() && !fixtureName(source.id + " " + source.name + " " + host)
        }.map { it.id }.toSet()
        val realArticles = articles.filter { article ->
            val uri = Uri.parse(article.url)
            article.read && article.readingMode == ReadingMode.NATIVE && article.sourceIds.any { it in allowedSources } &&
                uri.scheme == "https" && uri.host == "theconversation.com" &&
                !fixtureName(article.id + " " + article.sourceName) &&
                !Regex("(?i)fixture|synthetic|self-authored|测试").containsMatchIn(article.title)
        }
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            for (article in realArticles) {
                val cached = db.rawQuery("SELECT data FROM article_bodies WHERE articleId=? AND expiresAt>?", arrayOf(article.id,
                    (System.currentTimeMillis() + 120_000).toString())).use { cursor ->
                    if (cursor.moveToFirst()) JSONObject(cursor.getString(0)) else null
                } ?: continue
                val text = cached.optString("text")
                val hash = cached.optString("contentHash")
                if (text.isBlank() || hash != fingerprint(text) || cached.optString("articleId") != article.id) continue
                val paragraphs = ParagraphFocus.paragraphs(text)
                val transferTrial = InstrumentationRegistry.getArguments().getString("lexiconSpeedTransferTrial") == "1"
                val eligible = paragraphs.indices.take(12).filter { !transferTrial || it >= 3 }
                val index = eligible.firstOrNull { i ->
                    val p = paragraphs[i]
                    val words = SpeedChunker.wordCount(text.substring(p.start, p.endExclusive))
                    words in 30..120 && p.endExclusive - p.start <= 1800
                } ?: continue
                return Candidate(article, ArticleBody(article.id, text, hash, cached.optLong("cachedAt"),
                    cached.optLong("expiresAt"), cached.optString("license")), index)
            }
        }
        return null
    }

    private fun fixtureName(value: String) = Regex("(?i)fixture|synthetic|trial|example\\.(org|com)|test-source|测试").containsMatchIn(value)

    private fun readPreference(database: File, key: String): String? =
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT data FROM preferences WHERE `key`=?", arrayOf(key)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }

    private fun restorePreference(database: File, key: String, original: String?) {
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            if (original == null) db.delete("preferences", "`key`=?", arrayOf(key))
            else db.insertWithOnConflict("preferences", null, ContentValues().apply {
                put("key", key); put("data", original)
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        }
    }
}
