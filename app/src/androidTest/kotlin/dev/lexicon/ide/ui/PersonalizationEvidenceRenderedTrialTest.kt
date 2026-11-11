package dev.lexicon.ide.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.Process
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.data.BodyRow
import dev.lexicon.data.LexiconDatabase
import dev.lexicon.data.LocalPersonalizationRepository
import dev.lexicon.data.LocalSpeedReadingRepository
import dev.lexicon.data.SpeedPlanGenerator
import dev.lexicon.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * An opt-in rendered journey through production repositories, controllers and screens.
 * All plans, answers and decisions are explicitly synthetic and live only in an in-memory DB.
 * This test never reads the installed learning DB, user dictionaries or API credentials.
 */
@RunWith(AndroidJUnit4::class)
class PersonalizationEvidenceRenderedTrialTest {
    @get:Rule val compose = createComposeRule()
    private val codec = Json { encodeDefaults = true }
    private var database: LexiconDatabase? = null
    private var speedController: SpeedReadingViewModel? = null
    private var historyController: PersonalizationHistoryViewModel? = null
    private val generatorCalls = AtomicInteger()
    private val generationAllowed = AtomicBoolean(true)
    private val clock = AtomicLong(2_000_000L)
    private val examples = (1..5).map(::example)
    private val syntheticGenerator = SpeedPlanGenerator { article, body, proposal ->
        check(generationAllowed.get()) { "Read-only inspection must not generate another plan" }
        generatorCalls.incrementAndGet()
        val example = examples.single { it.article.id == article.id }
        val paragraphs = ParagraphFocus.paragraphs(body.text)
        proposal.copy(origin = "ai", model = "synthetic-evidence-trial/no-network",
            questions = proposal.paragraphIndices.map { paragraph ->
                val range = paragraphs[paragraph]
                SpeedQuestion("${article.id}-q$paragraph", paragraph, example.prompts[paragraph],
                    example.options[paragraph], 0, range.start, range.endExclusive, example.explanations[paragraph])
            })
    }

    @After fun closeIsolatedResources() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            speedController?.viewModelScope?.cancel()
            historyController?.viewModelScope?.cancel()
        }
        database?.close()
    }

    @Test fun currentGranularityAndSavedWrongAnswersRemainInspectableWithoutChangingEvidence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Rendered personalization evidence trial is opt-in",
            args.getString("lexiconPersonalizationEvidenceTrial") == "1")
        val variant = requireNotNull(args.getString("variant"))
        require(variant in setOf("day-font100", "night-font200"))
        val run = requireNotNull(args.getString("uniqueRun"))
        require(run.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")))
        val expectedUser = args.getString("expectedUserId")?.toIntOrNull()
        require(expectedUser in setOf(0, 10) && Process.myUid() / 100_000 == expectedUser)
        if (expectedUser == 0) require(args.getString("cleanTestEnvironment") == "1")
        val context = instrumentation.targetContext
        val font = if (variant == "night-font200") 2f else 1f
        val settings = AppSettings(theme = if (variant == "night-font200") ThemeMode.DARK else ThemeMode.LIGHT)
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val output = File(context.filesDir, "validation-personalization-evidence-2026-11-08/$run")
        check(!output.exists() && output.mkdirs()) { "Keep all previous trial artifacts" }
        val report = JSONObject().put("schema", "lexicon.personalization.evidence.rendered.v1")
            .put("status", "started").put("uniqueRun", run).put("variant", variant)
            .put("version", installed.versionName).put("versionCode", installed.longVersionCode)
            .put("apkSha256", digest(File(context.applicationInfo.sourceDir)))
            .put("androidUserId", Process.myUid() / 100_000)
            .put("material", "Self-authored synthetic text, playback and answers; not real learning results")
            .put("database", "Isolated in-memory Room with production LocalSpeedReadingRepository and LocalPersonalizationRepository")
            .put("installedRepositoryAccessedByTrial", false).put("networkRequests", 0)
            .put("screenshots", JSONArray()).put("screenshotMetadata", JSONArray())
            .put("textChecks", JSONArray()).put("openedRuns", JSONArray())
        var stage = "preflight"
        try {
            check(abs(context.resources.configuration.fontScale - font) < .02f)
            check(context.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
            report.put("systemFontScale", context.resources.configuration.fontScale).put("offlineDefaultNetworkAbsent", true)
            val db = Room.inMemoryDatabaseBuilder<LexiconDatabase>(ApplicationProvider.getApplicationContext<Context>())
                .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build().also { database = it }
            val speed = LocalSpeedReadingRepository(db, syntheticGenerator,
                hasKey = { generationAllowed.get() }, sourceAllowed = { true }, clock = { clock.getAndIncrement() })
            val preferences = LocalPersonalizationRepository(db) { clock.getAndIncrement() }
            stage = "prepare_synthetic_frozen_evidence"
            val fixture = prepareEvidence(db, speed, preferences)
            generationAllowed.set(false)
            val generatedBefore = generatorCalls.get()
            check(generatedBefore == 4)
            val runsBefore = runBlocking { speed.library.first().runs }
            report.put("syntheticPlanGenerationCallsBeforeInspection", generatedBefore)
                .put("savedSyntheticRuns", runsBefore.size)
                .put("originalFullBodiesExpired", true)
                .put("initialProfileSha256", digest(codec.encodeToString(fixture.profile)))
                .put("runsSha256", digest(codec.encodeToString(runsBefore)))
                .put("l1DecisionId", fixture.l1.id).put("l2DecisionId", fixture.l2.id)
            instrumentation.runOnMainSync {
                speedController = SpeedReadingViewModel(speed, personalizationRepository = preferences)
                historyController = PersonalizationHistoryViewModel(preferences, speed)
            }
            val vm = requireNotNull(speedController)
            val history = requireNotNull(historyController)
            var historyOpen by mutableStateOf(false)
            compose.setContent {
                val state by vm.state.collectAsState()
                val inspection by vm.recapInspection.collectAsState()
                val historyState by history.state.collectAsState()
                LexiconTheme(settings) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxSize()) {
                            Text("合成验证材料 · 非真实学习成绩", Modifier.fillMaxWidth().statusBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 6.dp).testTag("synthetic_evidence_banner"),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(Modifier.weight(1f)) {
                                SpeedReadingScreen(examples.last().article, examples.last().body, settings, state,
                                    SpeedUiActions(onGranularity = vm::setGranularity,
                                        onRememberTraining = vm::setRememberTraining,
                                        onUndoSuggestion = vm::undoPaceSuggestion,
                                        onInspectRun = vm::inspectRun, onPaceHistory = { historyOpen = true }),
                                    articles = examples.map { it.article })
                            }
                        }
                    }
                    if (historyOpen) PersonalizationHistoryDialog(historyState, examples.map { it.article }, history::select,
                        onClose = { historyOpen = false }, onRetry = history::retry, onInspectRun = vm::inspectRun)
                    inspection?.let { SpeedRecapInspectionDialog(it, vm::closeRecapInspection) }
                }
            }
            compose.runOnIdle { vm.bind(examples.last().article, examples.last().body, false, false, 1, 0) }
            stage = "current_l2_decision"
            awaitReady(vm, SpeedGranularity.L2, 63, fixture.l2.id)
            checkCurrentControls(SpeedGranularity.L2, 63, fixture.l2.id, vm, report)
            screenshot(output, report, "01-l2-current-decision.png", false)

            stage = "switch_to_l1_preserves_both_undo_origins"
            compose.onNodeWithText("L1 · 舒适").performScrollTo().performClick()
            awaitReady(vm, SpeedGranularity.L1, 54, fixture.l1.id)
            val afterSwitch = runBlocking { preferences.state.first() }
            assertEquals(setOf(SpeedGranularity.L1, SpeedGranularity.L2), afterSwitch.undoPaceBindings.keys)
            assertNull(afterSwitch.activePaceBinding)
            checkCurrentControls(SpeedGranularity.L1, 54, fixture.l1.id, vm, report)
            reach("speed_outcome_toggle").performClick()
            readableText("L1 · 54 意群/分钟", report)
            assertEquals(fixture.l1.id, vm.state.value.paceOutcome?.decisionId)
            screenshot(output, report, "02-l1-current-decision.png", false)

            stage = "history_opens_frozen_l1_evidence"
            val readOnlyProfile = runBlocking { preferences.state.first() }
            reach("speed_open_pace_history").performClick()
            compose.waitUntil(10_000) { exists("pace_history") && !history.state.value.loading }
            compose.onNodeWithTag("pace_history_content")
                .performScrollToNode(hasTestTag("pace_history_decision_${fixture.l1.id}"))
            val listPosition = historyPosition()
            compose.onNodeWithTag("pace_history_decision_${fixture.l1.id}").performClick()
            compose.waitUntil(10_000) { history.state.value.detail?.decision?.id == fixture.l1.id }
            reach("pace_history_evidence_toggle").performClick()
            val first = runsBefore.single { it.id == "synthetic-evidence-run-1" }
            val second = runsBefore.single { it.id == "synthetic-evidence-run-2" }
            val firstEntry = "pace_history_original_open_${first.id}"
            reach(firstEntry)
            val detailPosition = historyPosition()
            screenshot(output, report, "03-l1-frozen-evidence-list.png", true)
            reach(firstEntry).performClick()
            inspectAnsweredRun(first, examples[0], vm, output, report, "04-first-wrong-answer.png")
            assertEquals(readOnlyProfile, runBlocking { preferences.state.first() })
            assertEquals(runsBefore, runBlocking { speed.library.first().runs })
            closeInspection()
            compose.onNodeWithTag(firstEntry).assertIsDisplayed()
            assertEquals(detailPosition, historyPosition(), .001f)
            report.put("historyDetailPositionAndExpansionRetained", true)

            stage = "second_run_does_not_reuse_first_question"
            reach("pace_history_original_open_${second.id}").performClick()
            inspectAnsweredRun(second, examples[1], vm, output, report, "05-second-wrong-answer.png")
            compose.onAllNodesWithText(examples[0].prompts[0], useUnmergedTree = true).assertCountEquals(0)
            closeInspection()
            reach("pace_history_back").performClick()
            compose.waitUntil(10_000) { history.state.value.selectedId == null }
            assertEquals(listPosition, historyPosition(), .001f)
            reach("pace_history_back").performClick()
            compose.waitUntil(5_000) { !exists("pace_history") }
            readableText("L1 · 54 意群/分钟", report)
            assertEquals(readOnlyProfile, runBlocking { preferences.state.first() })
            assertEquals(runsBefore, runBlocking { speed.library.first().runs })
            assertEquals(generatedBefore, generatorCalls.get())
            report.put("readOnlyInspectionPreservedProfileAndRuns", true)
                .put("inspectionAdditionalGenerationCalls", 0).put("returnRetainedOutcomeExpansion", true)

            stage = "undo_only_current_l1"
            reach("speed_pace_undo").performClick()
            compose.waitUntil(10_000) { !vm.state.value.personalizationBusy && vm.state.value.groupsPerMinute == 60 }
            val undoneL1 = runBlocking { preferences.state.first() }
            assertEquals(PersonalizationDecisionStatus.UNDONE, undoneL1.decisions.single { it.id == fixture.l1.id }.status)
            assertEquals(PersonalizationDecisionStatus.APPLIED, undoneL1.decisions.single { it.id == fixture.l2.id }.status)
            assertEquals(60, undoneL1.rateFor(SpeedGranularity.L1)); assertEquals(63, undoneL1.rateFor(SpeedGranularity.L2))
            assertEquals(setOf(SpeedGranularity.L2), undoneL1.undoPaceBindings.keys)
            compose.onNodeWithTag("speed_pace_undo").assertDoesNotExist()
            screenshot(output, report, "06-l1-undone-l2-retained.png", false)

            stage = "l2_undo_remains_available"
            compose.onNodeWithText("L2 · 标准").performScrollTo().performClick()
            awaitReady(vm, SpeedGranularity.L2, 63, fixture.l2.id)
            checkCurrentControls(SpeedGranularity.L2, 63, fixture.l2.id, vm, report)
            screenshot(output, report, "07-l2-undo-still-available.png", false)
            reach("speed_pace_undo").performClick()
            compose.waitUntil(10_000) { !vm.state.value.personalizationBusy && vm.state.value.groupsPerMinute == 60 }
            val final = runBlocking { preferences.state.first() }
            assertEquals(60, final.rateFor(SpeedGranularity.L1)); assertEquals(60, final.rateFor(SpeedGranularity.L2))
            assertTrue(final.undoPaceBindings.isEmpty())
            assertTrue(final.decisions.all { it.status == PersonalizationDecisionStatus.UNDONE })
            assertEquals(runsBefore, runBlocking { speed.library.first().runs })
            assertEquals(generatedBefore, generatorCalls.get())
            report.put("currentGranularityControlsVerified", true).put("independentUndoOriginsVerified", true)
                .put("bodyCacheNotRequiredForInspection", true).put("differentRunQuestionIsolationVerified", true)
                .put("finalRunsSha256", digest(codec.encodeToString(runBlocking { speed.library.first().runs })))
                .put("finalProfileSha256", digest(codec.encodeToString(final)))
                .put("profileMutations", "Only explicit granularity choices and the two visible undo actions after fixture setup")
                .put("status", "passed")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failedStage", stage).put("failureType", failure.javaClass.simpleName)
                .put("failureLocations", JSONArray(failure.stackTrace.take(14).map { "${it.fileName}:${it.lineNumber}" }))
            runCatching { screenshot(output, report, "failure.png", exists("pace_history") || exists("speed_recap_inspection")) }
            throw AssertionError("Synthetic personalization evidence trial failed at $stage; inspect its report", failure)
        } finally {
            report.put("finishedAt", System.currentTimeMillis()).put("syntheticGeneratorCalls", generatorCalls.get())
            File(output, "device-trial.json").writeText(report.toString(2))
        }
    }

    private data class Fixture(val profile: PersonalizationProfile, val l1: PersonalizationDecision, val l2: PersonalizationDecision)

    private fun prepareEvidence(db: LexiconDatabase, speed: LocalSpeedReadingRepository,
        preferences: LocalPersonalizationRepository): Fixture = runBlocking {
        for ((index, example) in examples.take(4).withIndex()) {
            val level = if (index < 2) SpeedGranularity.L1 else SpeedGranularity.L2
            val plan = speed.prepare(example.article, example.body, level, 0, 3, true, true)
            val dwell = List(plan.chunks.size) { 1000L }
            val started = (index + 1) * 100_000L
            val answers = plan.questions.mapIndexed { questionIndex, question ->
                val chosen = if (level == SpeedGranularity.L1 && questionIndex < 2) 1 else question.answerIndex
                SpeedAnswer(question.id, chosen, chosen == question.answerIndex)
            }
            speed.saveRun(SpeedRun("synthetic-evidence-run-${index + 1}", example.article.id, example.body.contentHash,
                level, plan.planKey, started, started + dwell.sum(), dwell.sum(), plan.chunks.sumOf { it.wordCount },
                plan.chunks.size, dwell, 0, 0, answers, plan.questions.size, true, 60, "ai",
                firstAttempt = true, pacingStable = true, pacedCompletion = true,
                chunkInteractions = List(plan.chunks.size) { SpeedChunkInteraction(1, 0, 0) }))
            db.dao().putBody(BodyRow(example.article.id, 1L, codec.encodeToString(example.body.copy(expiresAt = 1L))))
        }
        db.dao().expireBodies(2L)
        examples.take(4).forEach { assertNull(db.dao().body(it.article.id, 0L)) }
        var profile = preferences.state.first()
        profile = preferences.remember(SpeedGranularity.L1, 60, false, profile.revision)
        val l1 = requireNotNull(PersonalizationPolicy.suggest(profile, speed.library.first().runs))
        assertEquals(54, l1.toRate); assertEquals(2, l1.correct); assertEquals(6, l1.total)
        profile = preferences.apply(l1, profile.revision)
        profile = preferences.remember(SpeedGranularity.L2, 60, false, profile.revision)
        val l2 = requireNotNull(PersonalizationPolicy.suggest(profile, speed.library.first().runs))
        assertEquals(63, l2.toRate); assertEquals(6, l2.correct); assertEquals(6, l2.total)
        profile = preferences.apply(l2, profile.revision)
        Fixture(profile, profile.decisions.single { it.id == l1.id }, profile.decisions.single { it.id == l2.id })
    }

    private fun awaitReady(vm: SpeedReadingViewModel, level: SpeedGranularity, rate: Int, decision: String) {
        compose.waitUntil(10_000) { vm.state.value.let {
            !it.personalizationBusy && it.selectedGranularity == level && it.groupsPerMinute == rate &&
                it.lastApplied?.id == decision && it.paceOutcome?.decisionId == decision
        } }
        assertNull(vm.state.value.personalizationError)
    }

    private fun checkCurrentControls(level: SpeedGranularity, rate: Int, decision: String, vm: SpeedReadingViewModel,
        report: JSONObject) {
        reach("speed_pace_applied")
        readableText("已应用 ${level.name} · 60 → $rate 意群/分钟", report)
        checkTouch("speed_pace_undo")
        assertEquals(decision, vm.state.value.lastApplied?.id)
        assertEquals(level, vm.state.value.paceOutcome?.granularity)
        assertEquals(decision, vm.state.value.paceOutcomeDecision?.id)
    }

    private fun inspectAnsweredRun(run: SpeedRun, example: Example, vm: SpeedReadingViewModel,
        output: File, report: JSONObject, screenshotName: String) {
        compose.waitUntil(10_000) { vm.recapInspection.value?.let { !it.loading && it.run.id == run.id } == true }
        val inspection = requireNotNull(vm.recapInspection.value)
        assertNull(inspection.error)
        assertEquals(run, inspection.run)
        assertEquals(run.planKey, inspection.plan?.planKey)
        checkTouch("speed_recap_inspection_close")
        val question = requireNotNull(inspection.plan).questions.first()
        assertEquals(0, question.answerIndex)
        assertEquals(1, run.answers.single { it.questionId == question.id }.selectedIndex)
        assertFalse(run.answers.single { it.questionId == question.id }.correct)
        val toggle = "speed_comprehension_question_${question.id}"
        compose.onNodeWithTag("speed_comprehension_explanation_${question.id}").assertDoesNotExist()
        reach(toggle).performClick()
        readableTaggedText("speed_comprehension_prompt_${question.id}", example.prompts[0], report)
        readableTaggedText("speed_comprehension_selected_${question.id}", "你的选择：B", report)
        readableText("B. ${example.options[0][1]}", report)
        readableTaggedText("speed_comprehension_reference_${question.id}", "参考答案：A · AI", report)
        readableText("A. ${example.options[0][0]}", report)
        screenshot(output, report, screenshotName, true)
        readableTaggedText("speed_comprehension_explanation_${question.id}", example.explanations[0], report, true)
        screenshot(output, report, screenshotName.removeSuffix(".png") + "-explanation.png", true)
        compose.onNodeWithTag("speed_comprehension_evidence_${question.id}").assertDoesNotExist()
        checkTouch("speed_comprehension_evidence_toggle_${question.id}")
        reach("speed_comprehension_evidence_toggle_${question.id}").performClick()
        readableTaggedText("speed_comprehension_evidence_${question.id}", example.paragraphs[0], report)
        report.getJSONArray("openedRuns").put(JSONObject().put("runId", run.id).put("planKey", run.planKey)
            .put("questionId", question.id).put("selectedIndex", run.answers.single { it.questionId == question.id }.selectedIndex)
            .put("referenceIndex", question.answerIndex).put("evidenceSha256", digest(example.paragraphs[0])))
        screenshot(output, report, screenshotName.removeSuffix(".png") + "-evidence.png", true)
        checkTouch("speed_recap_inspection_close")
    }

    private fun closeInspection() {
        reach("speed_recap_inspection_close").performClick()
        compose.waitUntil(5_000) { !exists("speed_recap_inspection") }
    }

    private fun reach(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag).assertExists().let { node ->
        if (runCatching { node.assertIsDisplayed() }.isFailure) node.performScrollTo()
        node.assertIsDisplayed()
    }

    private fun checkTouch(tag: String) {
        val bounds = reach(tag).getUnclippedBoundsInRoot()
        assertTrue("$tag has a 48dp touch target", bounds.bottom - bounds.top >= 48.dp && bounds.right - bounds.left >= 48.dp)
    }

    private fun readableText(text: String, report: JSONObject) =
        readable(compose.onNodeWithText(text, useUnmergedTree = true), report)

    private fun readableTaggedText(tag: String, text: String, report: JSONObject, contains: Boolean = false) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
        if (contains) node.assertTextContains(text, substring = true) else node.assertTextEquals(text)
        readable(node, report)
    }

    private fun readable(node: SemanticsNodeInteraction, report: JSONObject) {
        node.assertExists().performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        check(layouts.size == 1)
        val layout = layouts.single()
        // Record the layout flag separately; the decisive check below uses every visible glyph.
        // Font metrics can exceed a nominal line box even when all drawn text remains visible.
        check(layout.lineCount > 0)
        val semantics = node.fetchSemanticsNode()
        val visible = semantics.boundsInRoot
        val origin = semantics.positionInRoot
        for (offset in layout.layoutInput.text.indices) {
            if (layout.layoutInput.text[offset].isWhitespace()) continue
            val box = layout.getBoundingBox(offset)
            check(origin.y + box.top >= visible.top - 2f && origin.y + box.bottom <= visible.bottom + 2f &&
                origin.x + box.left >= visible.left - 2f && origin.x + box.right <= visible.right + 2f) {
                "Expected text has a clipped non-whitespace glyph"
            }
        }
        report.getJSONArray("textChecks").put(JSONObject().put("sha256", digest(layout.layoutInput.text.text))
            .put("characters", layout.layoutInput.text.length).put("lines", layout.lineCount)
            .put("layoutReportsVisualOverflow", layout.hasVisualOverflow)
            .put("allNonWhitespaceGlyphsVisible", true))
    }

    private fun historyPosition(): Float = compose.onNodeWithTag("pace_history_content").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun screenshot(output: File, report: JSONObject, name: String, productionDialog: Boolean) {
        compose.waitForIdle(); SystemClock.sleep(200)
        if (!productionDialog) compose.onNodeWithTag("synthetic_evidence_banner").assertIsDisplayed()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val file = File(output, name)
        check(!file.exists())
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        report.getJSONArray("screenshots").put(name)
        report.getJSONArray("screenshotMetadata").put(JSONObject().put("name", name).put("sha256", digest(file))
            .put("syntheticMaterial", true).put("unmodifiedSystemScreenshot", true)
            .put("baseScreenBannerCoveredByProductionDialog", productionDialog))
    }

    private data class Example(val article: Article, val body: ArticleBody, val paragraphs: List<String>,
        val prompts: List<String>, val options: List<List<String>>, val explanations: List<String>)

    private fun example(index: Int): Example {
        val district = listOf("north", "south", "east", "west", "central")[index - 1]
        val paragraphs = listOf(
            "Readers in the $district district plan weekly lessons at a community library.",
            "They choose small groups so that every learner can ask questions.",
            "The committee will review feedback before changing the programme.",
        )
        val text = paragraphs.joinToString("\n\n")
        val article = Article("synthetic-evidence-$index", "synthetic-$index", listOf("synthetic"),
            "合成材料 · $district district", "https://example.org/synthetic-evidence-$index", "",
            readingMode = ReadingMode.NATIVE, sourceName = "Self-authored synthetic trial")
        return Example(article, ArticleBody(article.id, text, fingerprint(text), 0, Long.MAX_VALUE,
            "Self-authored synthetic text; not a real news report"), paragraphs,
            listOf("Where will the $district district hold the lessons?", "Why do the readers choose small groups?",
                "What will the committee do before changing the programme?"),
            listOf(listOf("At a community library", "At an international airport", "At a sports centre"),
                listOf("Allow every learner to ask questions", "Sell more tickets", "Shorten every lesson"),
                listOf("Review the feedback", "Cancel the programme", "Choose a new city")),
            listOf("The paragraph places the weekly lessons at a community library.",
                "The stated reason for small groups is to allow every learner to ask questions.",
                "The committee plans to review feedback before it changes the programme."))
    }

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(1024 * 1024)
            while (true) { val count = input.read(bytes); if (count < 0) break; sha.update(bytes, 0, count) }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
}
