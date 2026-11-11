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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real cached-content trials. Automated timings are not human reading measurements. */
@RunWith(AndroidJUnit4::class)
class ContinuityDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()
    private data class Candidate(val article: Article, val body: ArticleBody, val paragraph: Int)
    private data class Session(
        val repository: AppRepository, val candidate: Candidate, val output: File, val report: JSONObject,
        val baselineBefore: Map<String, ReadingBaselineAttempt>, val speedBefore: SpeedLibrary,
    )

    @Test fun interruptedSelfTestResumesTheSameSavedAttemptAndReturnsToItsOriginalParagraph() = trial("baseline-continuity") { session ->
        val repository = session.repository
        val baseline = requireNotNull(repository.baseline)
        val selected = session.candidate
        val span = ParagraphFocus.paragraphs(selected.body.text)[selected.paragraph]
        val paragraphText = selected.body.text.substring(span.start, span.endExclusive)
        openArticleHistory()
        compose.onNodeWithTag("article_history_list").performScrollToNode(hasTestTag("article_baseline_${selected.article.id}"))
        compose.onNodeWithTag("article_baseline_${selected.article.id}").performClick()
        compose.waitUntil(15_000) { existsTag("baseline_prepare_offline") || existsTag("baseline_error") }
        assertFalse(existsTag("baseline_error"))
        repeat(selected.paragraph) { compose.onNodeWithContentDescription("自测起始段落后移").performScrollTo().performClick() }
        compose.onNodeWithText("1 段").performScrollTo().performClick()
        compose.onNodeWithTag("baseline_unseen_confirmation").performScrollTo().assertIsOff()
        compose.onNodeWithTag("baseline_prepare_offline").performScrollTo().performClick()
        compose.waitUntil(10_000) { existsTag("baseline_play_pause") }
        compose.onNodeWithTag("baseline_original_text").assertDoesNotExist()
        compose.onNodeWithTag("baseline_play_pause").performClick()
        compose.waitUntil(10_000) { existsTag("baseline_original_text") }
        compose.onNodeWithTag("baseline_original_text").assertTextEquals(paragraphText)
        activeWait(5_000)
        compose.onNodeWithTag("baseline_play_pause").performClick()
        compose.waitUntil(10_000) { !existsTag("baseline_original_text") && !existsText("正在保存…") }
        val paused = runBlocking { baseline.library.first().single { it.id !in session.baselineBefore } }
        assertFalse(paused.completed)
        assertTrue(paused.paragraphs.sumOf { it.activeMillis } >= 5_000)
        screenshot(session, "01-paused-before-exit.png")
        step(session, "Read five foreground seconds, paused with the original hidden, and saved one incomplete attempt")
        pressBack()
        compose.waitUntil(10_000) { existsText(">_  命令") && !existsTag("baseline_screen") }
        openCommand("自测", "无辅助自测记录")
        compose.waitUntil(10_000) { existsTag("baseline_history") }
        compose.onNodeWithTag("baseline_history").performScrollToNode(hasTestTag("baseline_open_${paused.id}"))
        screenshot(session, "02-incomplete-history.png")
        compose.onNodeWithTag("baseline_open_${paused.id}").performClick()
        compose.waitUntil(15_000) { existsTag("baseline_play_pause") || existsTag("baseline_error") }
        assertFalse(existsTag("baseline_error"))
        compose.onNodeWithText("续读练习").assertIsDisplayed()
        compose.onNodeWithText("继续本段").assertIsDisplayed()
        compose.onNodeWithTag("baseline_original_text").assertDoesNotExist()
        val resumed = runBlocking { requireNotNull(baseline.get(paused.id)) }
        assertEquals(paused.id, resumed.id)
        assertEquals(paused.startedAt, resumed.startedAt)
        assertEquals(paused.paragraphs, resumed.paragraphs)
        assertTrue(resumed.resumedFromHistory)
        val material = runBlocking { requireNotNull(baseline.material(paused.id)) }
        assertEquals(selected.body.contentHash, material.bodyHash)
        assertEquals(paragraphText, material.textAt(span.start, span.endExclusive))
        screenshot(session, "03-resumed-still-paused.png")
        step(session, "Exited and resumed the same saved attempt through command history, without autoplay or a new preparation")
        compose.onNodeWithTag("baseline_play_pause").performClick()
        compose.waitUntil(10_000) { existsTag("baseline_original_text") }
        compose.onNodeWithTag("baseline_original_text").assertTextEquals(paragraphText)
        activeWait(6_000)
        compose.onNodeWithTag("baseline_complete_paragraph").performClick()
        compose.waitUntil(15_000) { existsTag("baseline_read_original") || existsTag("baseline_error") }
        assertFalse(existsTag("baseline_error"))
        val completed = runBlocking { requireNotNull(baseline.get(paused.id)) }
        val plan = runBlocking { requireNotNull(repository.speedReading!!.cachedPlan(completed.planKey)) }
        val summary = ReadingBaselineMetrics.summarize(completed, plan)
        assertTrue(completed.completed && completed.resumedFromHistory)
        assertFalse(summary.qualifiesAsBaseline)
        assertNull(summary.accuracy)
        assertNull(summary.effectiveWordsPerMinute)
        compose.onNodeWithText("续读记录").performScrollTo().assertIsDisplayed()
        screenshot(session, "04-continuation-result.png")
        compose.onNodeWithTag("baseline_read_original").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(15_000) { existsTag("zen_article_text") }
        assertParagraphVisible(selected.body, selected.paragraph)
        screenshot(session, "05-original-anchor.png")
        val after = runBlocking { baseline.library.first() }.associateBy { it.id }
        assertEquals(setOf(paused.id), after.keys.minus(session.baselineBefore.keys))
        assertEquals(completed, after[paused.id])
        session.report.put("sameId", true).put("attemptId", paused.id).put("beginsCount", after.keys.minus(session.baselineBefore.keys).size)
            .put("resumedFromHistory", completed.resumedFromHistory).put("qualifiesAsBaseline", summary.qualifiesAsBaseline)
            .put("snapshotSourceExact", true).put("preciseOriginalAnchorVerified", true).put("activeMillis", summary.activeMillis)
        step(session, "Finished as continuation practice and returned to the original trained paragraph")
    }

    @Test fun realPausedAndReplayedGroupsRemainInspectableFromTrainingHistory() = trial("speed-interaction-continuity") { session ->
        val selected = session.candidate
        val speed = requireNotNull(session.repository.speedReading)
        openArticleHistory()
        compose.onNodeWithTag("article_history_list").performScrollToNode(hasText(selected.article.title))
        compose.onNodeWithText(selected.article.title).performClick()
        compose.waitUntil(15_000) { existsTag("zen_article_text") }
        compose.onNodeWithText("精读 · 工具").performScrollTo().performClick()
        compose.onNodeWithText("意群速读 →").performScrollTo().performClick()
        compose.waitUntil(10_000) { existsTag("speed_prepare_offline") }
        compose.onNodeWithText("L2 · 标准").performScrollTo().performClick()
        val from = compose.onNodeWithText("从第 ", substring = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString(" ") { it.text }
        val start = requireNotNull(Regex("从第 (\\d+) 段开始").find(from)).groupValues[1].toInt() - 1
        val change = selected.paragraph - start
        repeat(kotlin.math.abs(change)) {
            compose.onNodeWithContentDescription(if (change > 0) "起始段落后移" else "起始段落前移").performScrollTo().performClick()
        }
        compose.onNodeWithText("1 段").performScrollTo().performClick()
        compose.onNodeWithTag("speed_slider").performScrollTo().performTouchInput { click(Offset(width * .85f, centerY)) }
        compose.onNodeWithTag("speed_prepare_offline").performScrollTo().performClick()
        compose.waitUntil(10_000) { existsTag("speed_original_text") }
        compose.onNodeWithTag("speed_play_pause").performClick()
        compose.waitUntil(10_000) { (groupNumber() ?: 0) >= 2 }
        compose.onNodeWithTag("speed_play_pause").performClick()
        val repeatedGroup = requireNotNull(groupNumber())
        compose.onNodeWithTag("speed_repeat").performClick()
        compose.onNodeWithTag("speed_play_pause").performClick()
        compose.waitUntil(10_000) { (groupNumber() ?: 0) > repeatedGroup }
        compose.onNodeWithTag("speed_play_pause").performClick()
        screenshot(session, "01-paused-after-real-replay.png")
        compose.onNodeWithTag("speed_play_pause").performClick()
        compose.waitUntil(50_000) { existsTag("speed_restart") || existsTag("speed_error") }
        assertFalse(existsTag("speed_error"))
        compose.waitUntil(10_000) { !existsText("正在保存…") }
        val oldIds = session.speedBefore.runs.map { it.id }.toSet()
        val run = runBlocking { speed.library.first().runs.single { it.id !in oldIds } }
        val plan = runBlocking { requireNotNull(speed.cachedPlan(run.planKey)) }
        val summary = SpeedInteractionRecap.summarize(run, plan)
        assertTrue(run.completed)
        assertTrue(summary.items.any { it.pauses > 0 })
        assertTrue(summary.items.any { it.revisits > 0 && it.visits >= 2 })
        val item = summary.items.first()
        val chunk = plan.chunks[item.index]
        val quote = selected.body.text.substring(chunk.start, chunk.end)
        compose.onNodeWithTag("speed_interaction_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("speed_interaction_quote_${item.index}").performScrollTo().assertTextEquals(quote)
        screenshot(session, "02-result-active-interactions.png")
        step(session, "Played a real cached paragraph, explicitly paused and replayed a group, then inspected recorded interactions")
        returnToWorkspace()
        openCommand("速读", "意群速读记录")
        compose.waitUntil(10_000) { existsTag("speed_history") }
        compose.onNodeWithTag("speed_history").performScrollToNode(hasTestTag("speed_inspect_run_${run.id}"))
        compose.onNodeWithTag("speed_inspect_run_${run.id}").performClick()
        compose.waitUntil(15_000) { existsTag("speed_interaction_quote_${item.index}") || existsTag("speed_recap_inspection_error") }
        assertFalse(existsTag("speed_recap_inspection_error"))
        compose.onNodeWithTag("speed_interaction_quote_${item.index}").assertTextEquals(quote)
        screenshot(session, "03-history-same-run-recap.png")
        compose.onNodeWithTag("speed_recap_inspection_close").performClick()
        val after = runBlocking { speed.library.first() }
        assertEquals(run, after.runs.single { it.id == run.id })
        assertEquals(1, after.runs.count { it.id !in oldIds })
        session.report.put("sameRun", true).put("runId", run.id).put("newTrainingRecords", 1)
            .put("snapshotSourceExact", true).put("activeInteractionItems", summary.items.size)
            .put("recordedPauses", run.pauses).put("recordedRevisits", run.revisits)
        step(session, "Reopened the same run from command history and verified the frozen original snippet")
    }

    private fun trial(name: String, execute: (Session) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lexiconContinuityDeviceTrial") == "1")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiconApplication
        val repository = app.repository
        val output = File(app.filesDir, "continuity-device-trial/$name").apply { mkdirs() }
        val report = JSONObject().put("status", "started").put("automatedInteractionTrial", true)
            .put("humanReadingPerformanceMeasured", false).put("aiActionInvocations", 0).put("networkTrafficMeasured", false)
            .put("operations", JSONArray()).put("screenshots", JSONArray())
        fun save() { File(output, "device-trial.json").writeText(report.toString(2)) }
        fun skip(reason: String): Nothing {
            report.put("status", "skipped").put("reason", reason); save(); assumeTrue(reason, false); error("Skipped")
        }
        runBlocking { repository.initialize() }
        val database = app.getDatabasePath("lexicon.sqlite")
        if (!database.isFile) skip("The installed article database is unavailable.")
        val jobs = runBlocking { repository.jobs.first() }
        if (jobs.any { it.status == "running" }) skip("An AI job is already running; the offline trial will not overlap it.")
        val candidate = candidate(database, runBlocking { repository.articles.first() }, runBlocking { repository.sources.first() })
            ?: skip("No already-read publisher article has a suitable unexpired native cache. No fixture or network fallback was attempted.")
        val baselineBefore = runBlocking { requireNotNull(repository.baseline).library.first() }.associateBy { it.id }
        val speedBefore = runBlocking { requireNotNull(repository.speedReading).library.first() }
        val cards = runBlocking { repository.cards.first() }.associateBy { it.id }
        val captures = runBlocking { repository.captures.first() }.associateBy { it.id }
        val settings = runBlocking { repository.settings.first() }
        val settingsRow = readPreference(database)
        val originalProfileBytes = readProfileBytes(database)
        var changed = false
        var activity: ActivityScenario<MainActivity>? = null
        val session = Session(repository, candidate, output, report, baselineBefore, speedBefore)
        report.put("articleId", candidate.article.id).put("articleTitle", candidate.article.title)
            .put("bodyHash", candidate.body.contentHash).put("paragraph", candidate.paragraph + 1)
        save()
        try {
            runBlocking { repository.updateSettings(settings.copy(titleClassification = false,
                reader = settings.reader.copy(automaticStudy = false))) }
            changed = true
            activity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(20_000) { existsTag("daily_reading") }
            execute(session)
            val baselineAfter = runBlocking { repository.baseline!!.library.first() }.associateBy { it.id }
            baselineBefore.forEach { (id, record) -> assertEquals(record, baselineAfter[id]) }
            val speedAfter = runBlocking { repository.speedReading!!.library.first() }
            speedBefore.runs.forEach { previous -> assertEquals(previous, speedAfter.runs.single { it.id == previous.id }) }
            assertEquals(speedBefore.corrections, speedAfter.corrections)
            assertEquals(speedBefore.naturalAttempts, speedAfter.naturalAttempts)
            assertEquals(cards, runBlocking { repository.cards.first() }.associateBy { it.id })
            assertEquals(captures, runBlocking { repository.captures.first() }.associateBy { it.id })
            assertEquals(jobs.map { it.id }.toSet(), runBlocking { repository.jobs.first() }.map { it.id }.toSet())
            report.put("status", "passed").put("oldLearningRecordsPreserved", true).put("newAiJobIds", 0)
                .put("untestedPaths", JSONArray(listOf("Live AI comprehension", "Predictive back gesture", "Physical MagicOS performance", "Human reading ability")))
            save()
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failureType", failure.javaClass.simpleName)
            runCatching { screenshot(session, "failure.png") }; save(); throw failure
        } finally {
            activity?.close()
            if (changed) {
                try {
                    val settingsRestored = runCatching { restorePreference(database, settingsRow); readPreference(database) == settingsRow }
                    val profileRestored = runCatching {
                        restoreProfileBytes(database, originalProfileBytes)
                        val restored = readProfileBytes(database)
                        if (originalProfileBytes == null) restored == null else restored != null && originalProfileBytes.contentEquals(restored)
                    }
                    report.put("settingsRestored", settingsRestored.getOrDefault(false))
                        .put("personalizationRestored", profileRestored.getOrDefault(false))
                    check(settingsRestored.getOrDefault(false) && profileRestored.getOrDefault(false)) {
                        "The original local preferences were not restored"
                    }
                } catch (failure: Throwable) {
                    report.put("status", "failed").put("restoreFailureType", failure.javaClass.simpleName)
                    throw failure
                } finally { save() }
            }
        }
    }

    private fun openArticleHistory() {
        compose.onNodeWithTag("daily_reading").performScrollToNode(hasText("读过的文章"))
        compose.onNodeWithText("读过的文章").performClick()
        compose.waitUntil(10_000) { existsTag("article_history_list") }
    }
    private fun openCommand(query: String, label: String) {
        compose.onNodeWithText(">_  命令").performClick()
        compose.onNode(hasSetTextAction()).performTextInput(query)
        compose.onNodeWithText(label).performClick()
    }
    private fun returnToWorkspace() {
        if (existsTag("speed_reading_screen")) {
            pressBack()
            compose.waitUntil(15_000) { !existsTag("speed_reading_screen") }
        }
        if (!existsText(">_  命令")) {
            pressBack()
            compose.waitUntil(15_000) { existsText(">_  命令") }
        }
    }
    private fun pressBack() = InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    private fun activeWait(millis: Long) {
        val start = SystemClock.elapsedRealtime()
        compose.waitUntil(millis + 3_000) { SystemClock.elapsedRealtime() - start >= millis }
    }
    private fun existsTag(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun existsText(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun groupNumber(): Int? = compose.onAllNodesWithTag("speed_group_counter").fetchSemanticsNodes().firstOrNull()
        ?.config?.get(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        ?.let { Regex("1 / 1 段 · (\\d+) /").find(it)?.groupValues?.get(1)?.toInt() }
    private fun step(session: Session, value: String) {
        session.report.getJSONArray("operations").put(value)
        File(session.output, "device-trial.json").writeText(session.report.toString(2))
    }
    private fun screenshot(session: Session, name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync(); SystemClock.sleep(200)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(session.output, name).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
        session.report.getJSONArray("screenshots").put(name)
        File(session.output, "device-trial.json").writeText(session.report.toString(2))
    }
    private fun assertParagraphVisible(body: ArticleBody, paragraph: Int) {
        val field = compose.onNodeWithTag("zen_article_text")
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val bounds = layouts.single().getBoundingBox(ParagraphFocus.paragraphs(body.text)[paragraph].start)
        val position = field.fetchSemanticsNode().positionInRoot
        val viewport = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.top + position.y >= viewport.top && bounds.bottom + position.y <= viewport.bottom)
    }
    private fun candidate(database: File, articles: List<Article>, sources: List<Source>): Candidate? {
        val excluded = Regex("(?i)fixture|synthetic|self-authored|trial|example\\.(org|com)|test-source|测试")
        val sourceIds = sources.filter { it.readingMode == ReadingMode.NATIVE &&
            !excluded.containsMatchIn(it.id + " " + it.name + " " + Uri.parse(it.feedUrl).host.orEmpty()) }.map { it.id }.toSet()
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            for (article in articles) {
                val uri = Uri.parse(article.url)
                if (!article.read || article.readingMode != ReadingMode.NATIVE || article.sourceIds.none { it in sourceIds } ||
                    uri.scheme != "https" || uri.host != "theconversation.com" || excluded.containsMatchIn(article.id + " " + article.title + " " + article.sourceName)) continue
                val row = db.rawQuery("SELECT data FROM article_bodies WHERE articleId=? AND expiresAt>?",
                    arrayOf(article.id, (System.currentTimeMillis() + 300_000).toString())).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null } ?: continue
                val text = row.optString("text"); val hash = row.optString("contentHash")
                if (text.isBlank() || hash != fingerprint(text) || row.optString("articleId") != article.id) continue
                val paragraphs = ParagraphFocus.paragraphs(text)
                val selected = paragraphs.indices.take(12).firstOrNull { index ->
                    val p = paragraphs[index]
                    index >= 3 && SpeedChunker.wordCount(text.substring(p.start, p.endExclusive)) in 30..120 && p.endExclusive - p.start <= 1800
                } ?: continue
                return Candidate(article, ArticleBody(article.id, text, hash, row.optLong("cachedAt"), row.optLong("expiresAt"), row.optString("license")), selected)
            }
        }
        return null
    }
    private fun readPreference(database: File): String? = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT data FROM preferences WHERE `key`='settings'", emptyArray<String>()).use { if (it.moveToFirst()) it.getString(0) else null }
    }
    private fun restorePreference(database: File, original: String?) {
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            if (original == null) db.delete("preferences", "`key`='settings'", emptyArray())
            else check(db.insertWithOnConflict("preferences", null, ContentValues().apply { put("key", "settings"); put("data", original) }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
        }
    }

    /** Read exact stored bytes, including invalid UTF-8; never convert the profile to a String. */
    private fun readProfileBytes(database: File): ByteArray? =
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT CAST(data AS BLOB) FROM preferences WHERE `key`=?", arrayOf("personalization:profile"))
                .use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null }
        }

    private fun restoreProfileBytes(database: File, original: ByteArray?) {
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            if (original == null) db.compileStatement("DELETE FROM preferences WHERE `key`=?").use { statement ->
                statement.bindString(1, "personalization:profile"); statement.executeUpdateDelete()
            } else db.compileStatement("INSERT OR REPLACE INTO preferences (`key`, data) VALUES (?, CAST(? AS TEXT))").use { statement ->
                statement.bindString(1, "personalization:profile"); statement.bindBlob(2, original)
                check(statement.executeInsert() != -1L)
            }
        }
    }
}
