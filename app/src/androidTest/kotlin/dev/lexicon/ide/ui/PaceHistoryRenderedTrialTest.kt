package dev.lexicon.ide.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/** Opt-in rendered journey over self-authored fixtures; it never reads or writes the installed learning database. */
@RunWith(AndroidJUnit4::class)
class PaceHistoryRenderedTrialTest {
    @get:Rule val compose = createComposeRule()

    @Test fun threeHistoricalDecisionsKeepTheirOwnEvidenceAndReturnState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Synthetic rendered history trial is opt-in", arguments.getString("lexiconPaceHistoryRenderedTrial") == "1")
        val variant = requireNotNull(arguments.getString("variant"))
        require(variant in setOf("day-font100", "night-font200"))
        val context = instrumentation.targetContext
        val expectedScale = if (variant == "night-font200") 2f else 1f
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val output = File(context.filesDir, "validation-1.17.0/synthetic-history/$variant")
        check(!output.exists()) { "Preserve existing rendered trial evidence" }
        check(output.mkdirs())
        val report = JSONObject().put("status", "started").put("variant", variant)
            .put("version", installed.versionName).put("versionCode", installed.longVersionCode)
            .put("material", "Self-authored synthetic PaceHistoryTestFixture; not installed profile data or a learning result")
            .put("installedDatabaseAccessed", false).put("modelCallsRequested", 0)
            .put("screenshots", JSONArray()).put("selectedDecisionIds", JSONArray()).put("openedRunIds", JSONArray())
        val fixture = PaceHistoryTestFixture()
        val originalProfile = Json.encodeToString(fixture.profile)
        val originalRuns = Json.encodeToString(fixture.runs)
        var state by mutableStateOf(fixture.state())
        val selected = mutableListOf<String?>()
        val opened = mutableListOf<SpeedRun>()
        var closed = 0
        try {
            check(abs(context.resources.configuration.fontScale - expectedScale) < .02f)
            report.put("systemFontScale", context.resources.configuration.fontScale)
            compose.setContent {
                LexiconTheme(AppSettings(theme = if (variant.startsWith("night")) ThemeMode.DARK else ThemeMode.LIGHT)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxSize()) {
                            Text("合成验证材料 · 非真实学习记录", Modifier.fillMaxWidth().statusBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 6.dp).testTag("synthetic_history_banner"),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(Modifier.weight(1f)) {
                                PersonalizationHistoryScreen(state, fixture.articles,
                                    onSelect = { id ->
                                        selected += id
                                        state = fixture.state(fixture.profile.decisions.singleOrNull { it.id == id })
                                    }, onBack = {
                                        if (state.selectedId != null) { selected += null; state = fixture.state() } else closed++
                                    }, onRetry = { error("Synthetic fixture must not need retry") }, onInspectRun = { opened += it })
                            }
                        }
                    }
                }
            }
            compose.onNodeWithTag("synthetic_history_banner").assertIsDisplayed()
            assertEquals(fixture.undone.id, state.decisions.first().id)
            assertNoMutations()
            screenshot(output, report, "01-synthetic-history-list.png")

            fun select(decision: PersonalizationDecision): Float {
                compose.onNodeWithTag("pace_history_content").performScrollToNode(hasTestTag("pace_history_decision_${decision.id}"))
                val position = listPosition()
                compose.onNodeWithTag("pace_history_decision_${decision.id}").performClick()
                assertEquals(decision.id, state.selectedId)
                assertEquals(decision, state.detail?.decision)
                compose.onNodeWithTag("pace_history_detail_status").assertTextEquals(paceDecisionStatus(decision))
                report.getJSONArray("selectedDecisionIds").put(decision.id)
                assertNoMutations()
                return position
            }
            fun back(expectedPosition: Float) {
                compose.onNodeWithTag("pace_history_back").assertIsDisplayed().performClick()
                compose.waitForIdle()
                assertNull(state.selectedId)
                compose.onNodeWithTag("pace_history_detail").assertDoesNotExist()
                assertEquals(expectedPosition, listPosition(), .001f)
            }

            val latestPosition = select(fixture.undone)
            reach("pace_history_undone_at").assertTextEquals("撤销于 " + paceDecisionDate(fixture.undone.updatedAt))
            compose.onNodeWithText("后续记录只统计撤销前完成的练习。").performScrollTo().assertIsDisplayed()
            screenshot(output, report, "02-undone-cutoff.png")
            reach("speed_outcome_toggle").performClick()
            val cutoffRun = fixture.undoneBefore.first()
            reach("pace_outcome_open_${cutoffRun.id}").performClick()
            assertEquals(cutoffRun, opened.last())
            compose.onNodeWithTag("pace_outcome_run_${fixture.afterUndo.id}").assertDoesNotExist()
            report.put("undoCutoffVerified", true)
            back(latestPosition)

            val oldPosition = select(fixture.applied)
            reach("pace_history_evidence_toggle").performClick()
            val originalRun = fixture.detail(fixture.applied).originalEvidence.runs.first()
            reach("pace_history_original_open_${originalRun.id}").performClick()
            assertEquals(originalRun, opened.last())
            fixture.undone.suggestion.evidenceRunIds.forEach {
                compose.onNodeWithTag("pace_history_original_run_$it").assertDoesNotExist()
            }
            screenshot(output, report, "03-old-decision-original-evidence.png")
            reach("speed_outcome_toggle").performClick()
            val appliedRun = fixture.appliedAfter.first()
            reach("pace_outcome_open_${appliedRun.id}").performClick()
            assertEquals(appliedRun, opened.last())
            reach("speed_outcome_decision").assertTextEquals(paceDecisionDate(fixture.applied.decidedAt) + " · 已应用")
            screenshot(output, report, "04-old-decision-follow-up.png")
            back(oldPosition)
            val reopenedPosition = select(fixture.applied)
            reach("pace_history_original_open_${originalRun.id}").assertIsDisplayed()
            reach("pace_outcome_open_${appliedRun.id}").assertIsDisplayed()
            report.put("detailExpansionRetained", true)
            back(reopenedPosition)

            val keptPosition = select(fixture.kept)
            reach("pace_history_kept").assertTextEquals("当时选择保持 60 意群/分钟，未应用该建议。")
            compose.onNodeWithTag("speed_outcome_toggle").assertDoesNotExist()
            compose.onNodeWithText("0%").assertDoesNotExist()
            screenshot(output, report, "05-kept-without-invented-outcome.png")
            back(keptPosition)
            screenshot(output, report, "06-returned-to-list.png")
            assertEquals(listOf(fixture.undone.id, null, fixture.applied.id, null, fixture.applied.id, null, fixture.kept.id, null), selected)
            assertEquals(listOf(cutoffRun, originalRun, appliedRun), opened)
            opened.forEach { report.getJSONArray("openedRunIds").put(it.id) }
            assertEquals(originalProfile, Json.encodeToString(fixture.profile))
            assertEquals(originalRuns, Json.encodeToString(fixture.runs))
            assertEquals(0, closed)
            assertNoMutations()
            report.put("listPositionRetained", true).put("fixedRecordsOpened", true)
                .put("syntheticProfileUnchanged", true).put("syntheticRunsUnchanged", true)
                .put("fixtureProfileSha256", sha256(originalProfile)).put("fixtureRunsSha256", sha256(originalRuns))
                .put("status", "passed")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failureType", failure.javaClass.simpleName)
                .put("failureLocations", JSONArray(failure.stackTrace.take(12).map { "${it.fileName}:${it.lineNumber}" }))
            runCatching { screenshot(output, report, "failure.png") }
            throw AssertionError("Synthetic pace-history rendered journey failed; inspect its bounded report", failure)
        } finally {
            File(output, "device-trial.json").writeText(report.toString(2))
        }
    }

    private fun assertNoMutations() {
        listOf("speed_pace_apply", "speed_pace_undo", "speed_pace_keep", "speed_pace_release_hold")
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
    }

    private fun reach(tag: String): SemanticsNodeInteraction {
        val node = compose.onNodeWithTag(tag).assertExists()
        if (runCatching { node.assertIsDisplayed() }.isFailure) node.performScrollTo()
        return node.assertIsDisplayed()
    }

    private fun listPosition(): Float = compose.onNodeWithTag("pace_history_content").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun screenshot(output: File, report: JSONObject, name: String) {
        compose.onNodeWithTag("synthetic_history_banner").assertIsDisplayed()
        compose.waitForIdle(); SystemClock.sleep(200)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(output, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        report.getJSONArray("screenshots").put(name)
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
