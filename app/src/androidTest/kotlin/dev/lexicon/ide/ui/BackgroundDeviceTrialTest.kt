package dev.lexicon.ide.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import dev.lexicon.domain.StudyRequest
import dev.lexicon.ide.CaptureActivity
import dev.lexicon.ide.LexiconApplication
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
 * Explicit device trial against the installed repository. Uses its existing encrypted Key, if any.
 * One UI submission only; subsequent access is made after verifying a valid persisted cache entry.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun realCaptureRetrievesBackgroundAndDisplaysEvidence() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lexiconRagTrial") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as LexiconApplication
        val repository = requireNotNull(app.repository.reading)
        val output = File(app.filesDir, "rag-trial").apply { mkdirs() }
        val sentence = "A city council is considering a school courtyard project."
        val request = StudyRequest("background", "city council", context = sentence, title = "", source = "", aiAllowed = true)
        val cachedBefore = runBlocking { repository.cachedAssist(request) }
        if (InstrumentationRegistry.getArguments().getString("lexiconRagCachedOnly") == "1") {
            check(cachedBefore != null) { "Cached-only continuation requires existing evidence; no generation will be started" }
        }
        val report = JSONObject().put("status", "started").put("scenario", "ACTION_PROCESS_TEXT → background → evidence → return")
            .put("fixture", "Self-authored city council sentence").put("cacheExistedBefore", cachedBefore != null)
            .put("uiSubmissions", 0).put("screenshots", JSONArray())
        fun saveReport() { File(output, "device-trial.json").writeText(report.toString(2)) }
        fun screenshot(name: String) {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(400) // Wait for real IME/window rendering, outside Compose's test clock.
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Device screenshot unavailable" }
            File(output, name).outputStream().use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
            bitmap.recycle()
            report.getJSONArray("screenshots").put(name)
            saveReport()
        }
        fun exists(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        saveReport()
        val intent = Intent(app, CaptureActivity::class.java).apply {
            action = Intent.ACTION_PROCESS_TEXT
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, "city council")
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val scenario = ActivityScenario.launch<CaptureActivity>(intent)
        try {
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("capture_target").fetchSemanticsNodes().isNotEmpty() }
            val target = compose.onNodeWithTag("capture_target").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
            assertEquals("city council", target)
            compose.onNodeWithTag("capture_sentence").performScrollTo().performTextReplacement(sentence)
            compose.waitForIdle()
            assertEquals(sentence, compose.onNodeWithTag("capture_sentence").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            scenario.onActivity {
                // ModalBottomSheet owns a separate window; hide IME on that actual root.
                WindowInspector.getGlobalWindowViews().forEach { root ->
                    root.windowInsetsController?.hide(WindowInsets.Type.ime())
                    root.findFocus()?.clearFocus()
                }
            }
            compose.waitForIdle()
            SystemClock.sleep(500)
            // The only generated request in this trial. Production JSON repair is already bounded.
            compose.onNodeWithText("背景").performScrollTo().assertIsEnabled().performClick()
            report.put("uiSubmissions", 1)
            saveReport()
            compose.waitUntil(if (cachedBefore != null) 20_000 else 120_000) {
                exists("AI 解释 · 附检索依据") || exists("检索摘录") || exists("暂未找到背景资料")
            }
            assertFalse("Retrieval did not return evidence; no second submission is attempted", exists("暂未找到背景资料"))
            val generated = exists("AI 解释 · 附检索依据")
            assertTrue(generated || exists("检索摘录"))
            compose.onNodeWithTag("background_result").performScrollTo().assertIsDisplayed()
            screenshot("01-grounded-background.png")
            compose.onNodeWithText("[S1] Local government").performScrollTo().performClick()
            compose.onNode(isDialog() and hasAnyDescendant(hasText("返回阅读"))).assertExists()
            screenshot("02-source-excerpt.png")
            compose.onNodeWithText("检索于 ", substring = true).performScrollTo().assertIsDisplayed()
            screenshot("03-source-provenance.png")
            compose.onNodeWithText("返回阅读").assertIsDisplayed().performClick()
            compose.onNode(isDialog() and hasAnyDescendant(hasText("返回阅读"))).assertDoesNotExist()
            compose.onNodeWithTag("background_result").assertIsDisplayed()
            screenshot("04-returned-to-capture.png")

            val cached = runBlocking { repository.cachedAssist(request) }
            assertNotNull("The successful UI result must have a persisted cache entry", cached)
            val saved = requireNotNull(cached)
            assertEquals("retrieved", saved.retrievalStatus)
            assertTrue(saved.sources.any { it.id == "S1" && it.title == "Local government" })
            // Freshly validated cache access exercises the normal repository API without another
            // generation. Timestamp and all client-owned evidence must remain exactly unchanged.
            val repeated = runBlocking { repository.assist(request) }
            assertEquals(saved, repeated)
            report.put("status", "passed").put("generatedExplanation", generated)
                .put("model", saved.model).put("retrievalStatus", saved.retrievalStatus)
                .put("createdAt", saved.createdAt).put("sourceCount", saved.sources.size)
                .put("cacheReadVerified", true).put("sameRequestReturnedExactCachedResult", true)
                .put("networkRequestCountMeasured", false)
                .put("citedSourceIds", JSONArray(saved.citedSourceIds))
                .put("sources", JSONArray(saved.sources.map { source -> JSONObject()
                    .put("id", source.id).put("title", source.title).put("url", source.url)
                    .put("provider", source.provider).put("revisionId", source.revisionId)
                    .put("retrievedAt", source.retrievedAt).put("license", source.license)
                    .put("excerptCharacters", source.excerpt.length) }))
            saveReport()
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failureType", failure.javaClass.simpleName)
            runCatching { screenshot("failure.png") }
            saveReport()
            throw failure
        } finally { scenario.close() }
    }
}
