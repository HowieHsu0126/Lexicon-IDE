package dev.lexicon.ide.ui

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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

/** Opt-in trial of one existing card, restoring its pause flag; never generates content or rates a card. */
@RunWith(AndroidJUnit4::class)
class CardControlDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun existingCardCanPauseRecreateFilterAndResumeWithoutLearningOrAiWrites() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lexiconCardControlTrial") == "1")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiconApplication
        val repository = app.repository
        runBlocking { repository.initialize() }
        assumeTrue("Use the installed no-key trial state", !runBlocking { repository.hasApiKey() })
        val original = runBlocking { repository.cards.first() }.associateBy { it.id }
        val chosen = original.values.firstOrNull { !it.reviewPaused && it.sourceRef.url?.startsWith("https://theconversation.com/") == true }
        assumeTrue("An existing original publisher card is required", chosen != null)
        val card = requireNotNull(chosen)
        val captures = runBlocking { repository.captures.first() }
        val capture = captures.first { it.id == card.captureId }
        val jobs = runBlocking { repository.jobs.first() }
        val database = app.getDatabasePath("lexicon.sqlite")
        val reviews = rows(database, "review_logs")
        val events = rows(database, "local_events")
        val preferences = rows(database, "preferences")
        val output = File(app.filesDir, "validation-1.9.0/card-control-trial").apply { mkdirs() }
        val report = JSONObject().put("status", "started").put("automatedInteractionTrial", true)
            .put("aiActions", 0).put("ratings", 0).put("screenshots", JSONArray())
        fun save() { File(output, "device-trial.json").writeText(report.toString(2)) }
        fun screenshot(name: String) {
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle(); report.getJSONArray("screenshots").put(name); save()
        }
        fun node(tag: String) = compose.onNodeWithTag(tag)
        fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val available = runBlocking { repository.availableCaptureAnalysis(capture) }
            assertTrue("Existing native card has an exact persisted receipt", available is CaptureAnalysisAvailability.ExistingCard && available.matchesInput)
            report.put("existingCardExactInputMatched", true)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(15_000) { exists("daily_reading") }
            compose.onNodeWithText(">_  命令").performClick()
            compose.onNodeWithText("搜索命令…").performTextInput("cards")
            compose.onNodeWithText("浏览生词卡").performClick()
            node("card_library_${card.id}").performScrollTo().performClick()
            node("card_review_pause").performScrollTo().assertTextContains("暂停复习").performClick()
            compose.waitUntil(10_000) { runBlocking { repository.cards.first() }.first { it.id == card.id }.reviewPaused }
            node("card_review_pause").assertTextContains("恢复复习")
            screenshot("01-paused-card.png")
            val paused = runBlocking { repository.cards.first() }.first { it.id == card.id }
            assertEquals(card.copy(reviewPaused = true), paused)
            assertFalse(paused.isDueForReview(System.currentTimeMillis()))
            scenario.recreate()
            compose.waitUntil(15_000) { exists("card_review_pause") }
            node("card_review_pause").assertTextContains("恢复复习")
            report.put("pauseSurvivedActivityRecreation", true)
            compose.onNodeWithText("← 所有卡片").performScrollTo().performClick()
            node("card_filter_paused").performClick()
            node("card_library_${card.id}").assertExists()
            screenshot("02-paused-library.png")
            node("card_library_${card.id}").performClick()
            node("card_review_pause").performScrollTo().performClick()
            compose.waitUntil(10_000) { !runBlocking { repository.cards.first() }.first { it.id == card.id }.reviewPaused }
            node("card_review_pause").assertTextContains("暂停复习")
            screenshot("03-restored-card.png")
            assertEquals(original, runBlocking { repository.cards.first() }.associateBy { it.id })
            assertEquals(captures, runBlocking { repository.captures.first() })
            assertEquals(jobs, runBlocking { repository.jobs.first() })
            assertEquals(reviews, rows(database, "review_logs"))
            assertEquals(events, rows(database, "local_events"))
            assertEquals(preferences, rows(database, "preferences"))
            report.put("originalCardsAndScheduleRestored", true).put("capturesJobsReviewsEventsPreferencesUnchanged", true)
                .put("pausedFilterWorked", true).put("status", "passed")
        } catch (error: Throwable) {
            report.put("status", "failed").put("failureType", error.javaClass.simpleName)
                .put("failureLocations", JSONArray(error.stackTrace.take(10).map { "${it.fileName}:${it.lineNumber}" }))
            runCatching { screenshot("failure.png") }
            throw AssertionError("Card control trial failed; inspect sanitized report and screenshot")
        } finally {
            runCatching { scenario?.close() }
            val restored = runCatching {
                val current = runBlocking { repository.cards.first() }.first { it.id == card.id }
                check(current.copy(reviewPaused = card.reviewPaused) == card)
                if (current.reviewPaused != card.reviewPaused) runBlocking { repository.setCardReviewPaused(card.id, card.reviewPaused) }
                check(runBlocking { repository.cards.first() }.associateBy { it.id } == original)
                check(!runBlocking { repository.hasApiKey() })
            }
            report.put("pauseAndNoKeyStateRestored", restored.isSuccess)
            if (restored.isFailure) report.put("status", "failed")
            save()
            check(restored.isSuccess) { "Trial pause restoration did not complete" }
        }
    }
    private fun rows(file: File, table: String): List<List<String?>> = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT * FROM $table ORDER BY 1", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) }) }
        }
    }
}
