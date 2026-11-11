package dev.lexicon.ide.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.data.LexiconDatabase
import dev.lexicon.data.LocalIeltsPracticeRepository
import dev.lexicon.domain.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt in with lexiconIeltsArtifactTrial=1 and variant=day|night (always 200% type).
 * The operator must supply files/ielts-trial-input/verified-package.json first.
 * Missing material is skipped; this test never manufactures a question package.
 * Artifact provenance remains a host claim, not proof of a network/model execution here.
 */
@RunWith(AndroidJUnit4::class)
class IeltsGeneratedMaterialTrialTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val stores = mutableListOf<ViewModelStore>()
    private var database: LexiconDatabase? = null
    private val offline = OfflineGenerations()
    private val completedSteps = mutableListOf<String>()
    private val screenshots = mutableListOf<JsonObject>()
    private val textLayouts = mutableListOf<JsonObject>()
    private lateinit var evidence: File
    private var material: IeltsGatewayVerifiedPackage? = null
    private var artifactHash: String? = null
    private var variant = "day"
    private var stage = "not_started"
    private var pixels48 = 0f
    private val shortWriting = "Public decisions should be judged by their practical benefits and their costs. " +
        "A cautious trial can reveal problems before a policy reaches every community. " +
        "People also need clear evidence and a fair chance to question the proposed change. " +
        "This brief draft is used to check saving and review, and is not a complete essay."

    @After fun dispose() {
        instrumentation.runOnMainSync { stores.forEach(ViewModelStore::clear) }
        database?.close()
    }

    @Test fun suppliedHarnessMaterialSupportsDraftSubmissionAndReopenedReview() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Real-material UI trial is opt-in: set lexiconIeltsArtifactTrial=1.",
            arguments.getString("lexiconIeltsArtifactTrial") == "1")
        variant = arguments.getString("variant") ?: "day"
        require(variant in setOf("day", "night")) { "variant must be day or night" }
        evidence = File(instrumentation.targetContext.filesDir,
            "validation-1.35.0/ielts-real-material/${System.currentTimeMillis()}-$variant-${UUID.randomUUID()}")
        check(evidence.mkdirs())
        val input = File(instrumentation.targetContext.filesDir, "ielts-trial-input/verified-package.json")
        if (!input.isFile) receipt("skipped", "verified_package_missing")
        assumeTrue("No verified-package.json was supplied; no synthetic material will be substituted.", input.isFile)
        try {
            stage = "material_validation"
            val verified = readMaterial(input).also { material = it }
            val validation = IeltsExerciseValidator.validatePackage(verified.request, verified.exercisePackage)
            checkProvenance(verified, validation)
            assertEquals("This trial exercises all four reading forms", 4, verified.exercisePackage.readingQuestions.size)
            assertEquals(setOf("choice", "information", "view", "completion"),
                verified.exercisePackage.readingQuestions.map { when (it) {
                    is IeltsChoiceQuestion -> "choice"
                    is IeltsInformationQuestion -> "information"
                    is IeltsViewQuestion -> "view"
                    is IeltsCompletionQuestion -> "completion"
                } }.toSet())
            checkpoint("material_structure_and_host_claims_validated")

            stage = "isolated_practice_setup"
            val db = Room.inMemoryDatabaseBuilder<LexiconDatabase>(instrumentation.targetContext)
                .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
            database = db
            val practices = LocalIeltsPracticeRepository(db)
            val attemptId = "material-trial-${UUID.randomUUID()}"
            val saved = runBlocking {
                val hash = practices.cache(verified.request, verified.exercisePackage)
                assertEquals(validation.packageSha256, hash)
                practices.begin(hash, attemptId, System.currentTimeMillis())
            }
            assertNull(saved.submission)
            val host = TrialHost(practices)
            instrumentation.runOnMainSync { host.recreate() }
            compose.setContent { host.Content() }
            openHistory(host, attemptId)
            assertEquals(validation.packageSha256, host.model.state.value.candidate?.packageSha256)
            screenshot("01-candidate")
            practiceNode("ielts_source_toggle").performClick()
            textFits("ielts_source_${verified.request.paragraphs.first().paragraphId}")
            screenshot("02-original")
            practiceNode("ielts_source_toggle").performClick()

            stage = "draft_entry"
            val expected = verified.exercisePackage.readingQuestions.map { IeltsResponse(it.questionId, reference(it)) }
            verified.exercisePackage.readingQuestions.forEachIndexed { index, question ->
                textFits("ielts_prompt_${question.questionId}")
                compose.onNodeWithTag("ielts_reference_${question.questionId}").assertDoesNotExist()
                if (question is IeltsChoiceQuestion) question.options.forEach { option ->
                    textFits("ielts_option_text_${question.questionId}_${option.optionId.name}",
                        "ielts_option_${question.questionId}_${option.optionId.name}")
                }
                if (question is IeltsCompletionQuestion) {
                    practiceNode("ielts_input_${question.questionId}").performTextInput(question.answer)
                } else {
                    val tag = "ielts_option_${question.questionId}_${reference(question)}"
                    val option = practiceNode(tag)
                    actionFits(option, tag)
                    option.performClick().assertIsSelected()
                }
                compose.waitUntil(10_000) { host.model.state.value.draftResponses.any {
                    it.questionId == question.questionId && it.answer == reference(question) } }
                screenshot("03-answer-${index + 1}")
            }
            val task1Writing = verified.exercisePackage.writingTask1.task?.let { task ->
                task.visual.categories.forEachIndexed { ci, _ ->
                    task.visual.series.forEachIndexed { si, _ ->
                        practiceNode("ielts_visual_cell_${si}_$ci").assertIsDisplayed()
                    }
                    screenshot("03-task1-category-${ci + 1}")
                }
                val text = "Visits increased across the reported groups, with a larger increase at the first branch."
                practiceNode("ielts_writing1_input").performTextInput(text)
                compose.waitUntil(10_000) { host.model.state.value.writingTask1Text == text }
                screenshot("03-task1-writing")
                text
            }
            textFits("ielts_writing_prompt")
            practiceNode("ielts_writing_input").performTextInput(shortWriting)
            compose.waitUntil(10_000) { host.model.state.value.writingText == shortWriting }
            val words = IeltsExerciseValidator.wordCount(shortWriting)
            assertTrue(words in 1 until verified.exercisePackage.writingTask2.minimumWords)
            screenshot("04-writing-keyboard")
            closePractice(host)
            val draft = runBlocking { requireNotNull(practices.get(attemptId)) }
            assertEquals(expected, draft.draftResponses)
            assertEquals(shortWriting, draft.writingText)
            assertEquals(task1Writing, draft.writingTask1Text)
            assertNull(draft.submission)
            assertNull(draft.answerView())
            assertTrue(draft.revision > saved.revision)
            checkpoint("all_reading_forms_and_short_writing_saved_before_exit")

            stage = "draft_reopen_and_submit"
            reopenWorkspace(host)
            openHistory(host, attemptId)
            assertEquals(expected, host.model.state.value.draftResponses)
            assertEquals(shortWriting, host.model.state.value.writingText)
            assertEquals(task1Writing, host.model.state.value.writingTask1Text)
            assertNull(host.model.state.value.answerView)
            verified.exercisePackage.readingQuestions.forEach { question ->
                if (question is IeltsCompletionQuestion) {
                    practiceNode("ielts_input_${question.questionId}").assertTextContains(question.answer)
                } else practiceNode("ielts_option_${question.questionId}_${reference(question)}").assertIsSelected()
            }
            checkpoint("draft_restored_through_history_with_new_viewmodel")
            val submit = compose.onNodeWithTag("ielts_submit").assertIsDisplayed().assertIsEnabled()
            actionFits(submit, "ielts_submit")
            screenshot("05-restored-draft")
            submit.performClick()
            compose.waitUntil(15_000) { host.model.state.value.answerView != null && !host.model.state.value.isSaving && !host.model.state.value.busy }
            val committed = runBlocking { requireNotNull(practices.get(attemptId)) }
            assertTrue(committed.submitted)
            assertEquals(expected, committed.submission?.readingAnswers)
            assertEquals(shortWriting, committed.submission?.writingTask2Text)
            assertEquals(task1Writing, committed.submission?.writingTask1Text)
            val review = requireNotNull(committed.answerView())
            assertEquals(review, host.model.state.value.answerView)
            assertEquals(words, review.writingWords)
            assertFalse(review.writingMeetsMinimum)
            assertNull(review.officialBandScore)
            compose.onNodeWithTag("ielts_submit").assertDoesNotExist()
            compose.onNodeWithTag("ielts_title").assertIsDisplayed().assertTextEquals("练习回看")
            screenshot("06-submitted")
            checkpoint("persisted_submission_precedes_reference_reveal")

            stage = "reference_review"
            verified.exercisePackage.readingQuestions.forEachIndexed { index, question ->
                val toggle = practiceNode("ielts_review_toggle_${question.questionId}")
                actionFits(toggle, "ielts_review_toggle_${question.questionId}")
                toggle.performClick()
                textFits("ielts_reference_${question.questionId}")
                screenshot("07-reference-${index + 1}")
                textFits("ielts_explanation_${question.questionId}")
                if (question.sourceRefs.isNotEmpty()) {
                    textFits("ielts_evidence_${question.questionId}_0")
                    screenshot("08-evidence-${index + 1}")
                } else {
                    textFits("ielts_ng_scope_${question.questionId}")
                    textFits("ielts_ng_reason_${question.questionId}")
                    screenshot("08-absence-scope-${index + 1}")
                }
                practiceNode("ielts_review_toggle_${question.questionId}").performClick()
            }
            practiceNode("ielts_writing_input").assertTextContains(shortWriting)
            practiceNode("ielts_writing_count").assertTextEquals("$words 词 / ${verified.exercisePackage.writingTask2.minimumWords} 词要求")
            textFits("ielts_short_writing")
            screenshot("09-short-writing-review")
            if (task1Writing != null) {
                practiceNode("ielts_writing1_saved").assertTextEquals(task1Writing)
                screenshot("09-task1-review")
            }
            checkpoint("references_evidence_and_short_writing_boundary_rendered_at_font200")

            stage = "submitted_history_reopen"
            closePractice(host)
            reopenWorkspace(host)
            openHistory(host, attemptId)
            compose.waitUntil(10_000) { host.model.state.value.answerView != null }
            assertEquals(review, host.model.state.value.answerView)
            assertEquals(committed, runBlocking { practices.get(attemptId) })
            compose.onNodeWithTag("ielts_title").assertTextEquals("练习回看")
            compose.onNodeWithTag("ielts_submit").assertDoesNotExist()
            val first = verified.exercisePackage.readingQuestions.first().questionId
            practiceNode("ielts_review_toggle_$first").performClick()
            textFits("ielts_reference_$first")
            screenshot("10-reopened-review")
            assertEquals(0, offline.forbiddenCalls.get())
            assertEquals(3, stores.size)
            checkpoint("submitted_history_reopens_without_duplicate_commit_or_network")
            stage = "complete"
            receipt("passed")
        } catch (failure: Throwable) {
            receipt("failed", failure.javaClass.simpleName)
            throw failure
        }
    }

    private inner class TrialHost(private val practices: IeltsPracticeRepository) {
        var vm by mutableStateOf<IeltsExerciseViewModel?>(null)
            private set
        var visible by mutableStateOf(true)
        private var currentStore: ViewModelStore? = null
        fun recreate() {
            currentStore?.clear()
            val next = ViewModelStore().also { stores += it; currentStore = it }
            vm = IeltsExerciseViewModel(offline, practices, SavedStateHandle()).also { next.put("real-material", it) }
            visible = true
        }
        @Composable fun Content() {
            val density = LocalDensity.current
            val activity = LocalContext.current.findActivity()
            DisposableEffect(activity) {
                val window = activity?.window
                val originalMode = window?.attributes?.softInputMode
                val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
                val originalLightStatus = controller?.isAppearanceLightStatusBars
                val originalLightNavigation = controller?.isAppearanceLightNavigationBars
                (activity as? ComponentActivity)?.enableEdgeToEdge()
                if (window != null && originalMode != null) window.setSoftInputMode(
                    (originalMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                onDispose {
                    if (window != null && originalMode != null) {
                        window.setSoftInputMode(originalMode)
                        WindowCompat.setDecorFitsSystemWindows(window, true)
                        originalLightStatus?.let { controller?.isAppearanceLightStatusBars = it }
                        originalLightNavigation?.let { controller?.isAppearanceLightNavigationBars = it }
                    }
                }
            }
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                pixels48 = with(LocalDensity.current) { 48.dp.toPx() }
                LexiconTheme(AppSettings(theme = if (variant == "night") ThemeMode.DARK else ThemeMode.LIGHT)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize().testTag("material_trial_viewport")) {
                            if (visible) IeltsExerciseRoute(requireNotNull(vm), emptyList()) { visible = false }
                            else Button(onClick = ::recreate,
                                modifier = Modifier.safeDrawingPadding().testTag("material_trial_reopen")) { Text("打开练习记录") }
                        }
                    }
                }
            }
        }
    }

    private val TrialHost.model: IeltsExerciseViewModel get() = requireNotNull(vm)

    private fun openHistory(host: TrialHost, attemptId: String) {
        compose.waitUntil(10_000) { !host.model.state.value.busy && host.model.state.value.practiceHistory.any { it.attemptId == attemptId } }
        val tag = "ielts_history_$attemptId"
        compose.onNodeWithTag("ielts_workspace_list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { host.model.state.value.candidate != null && !host.model.state.value.busy }
        compose.onNodeWithTag("ielts_practice").assertIsDisplayed()
        assertNull(host.model.state.value.error)
    }

    private fun closePractice(host: TrialHost) {
        val back = compose.onNodeWithTag("ielts_back").assertIsDisplayed().assertIsEnabled()
        actionFits(back, "ielts_back")
        back.performClick()
        compose.waitUntil(10_000) { !host.visible }
        compose.onNodeWithTag("material_trial_reopen").assertIsDisplayed()
    }

    private fun reopenWorkspace(host: TrialHost) {
        compose.onNodeWithTag("material_trial_reopen").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { host.visible && !host.model.state.value.busy }
    }

    private fun practiceNode(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("ielts_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }

    private fun actionFits(node: SemanticsNodeInteraction, tag: String) {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("material_trial_viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("$tag keeps a 48dp target", bounds.width >= pixels48 - 1 && bounds.height >= pixels48 - 1)
        assertTrue("$tag fits the viewport width", bounds.left >= viewport.left - 1 && bounds.right <= viewport.right + 1)
    }

    private fun textFits(tag: String, scrollTag: String = tag) {
        practiceNode(scrollTag)
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("$tag exposes its rendered text layout", layouts.isNotEmpty())
        val viewport = compose.onNodeWithTag("material_trial_viewport").fetchSemanticsNode().boundsInRoot
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue("$tag fits the viewport width", bounds.left >= viewport.left - 1 && bounds.right <= viewport.right + 1)
        layouts.forEach { layout ->
            assertEquals("$tag uses 200% type", 2f, layout.layoutInput.density.fontScale, .01f)
            assertTrue("$tag has laid out its text", layout.lineCount > 0)
            for (line in 0 until layout.lineCount) {
                assertFalse("$tag does not ellipsize line $line", layout.isLineEllipsized(line))
                assertTrue("$tag line $line fits horizontally", layout.getLineLeft(line) >= -1.5f && layout.getLineRight(line) <= layout.size.width + 1.5f)
                assertTrue("$tag line $line fits vertically", layout.getLineBottom(line) <= layout.size.height + 1.5f)
            }
            assertEquals("$tag lays out the complete string", layout.layoutInput.text.length,
                layout.getLineEnd(layout.lineCount - 1, visibleEnd = false))
            textLayouts += buildJsonObject {
                put("tag", tag); put("font_scale", layout.layoutInput.density.fontScale)
                put("lines", layout.lineCount); put("width_px", layout.size.width); put("height_px", layout.size.height)
                put("complete_text_laid_out", true); put("line_bounds_fit", true)
            }
        }
    }

    private fun screenshot(name: String) {
        val target = compose.onNodeWithTag("ielts_practice").assertIsDisplayed()
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        val bitmap = target.captureToImage().asAndroidBitmap()
        try { saveImage("$name.png", bitmap) } finally { bitmap.recycle() }
        instrumentation.waitForIdleSync(); SystemClock.sleep(100)
        val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { saveImage("screen-$name.png", screen) } finally { screen.recycle() }
    }

    private fun saveImage(name: String, bitmap: Bitmap) {
        val file = File(evidence, name)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshots += buildJsonObject {
            put("file", name); put("width_px", bitmap.width); put("height_px", bitmap.height)
            put("sha256", sha256(file.readBytes()))
        }
    }

    private fun readMaterial(input: File): IeltsGatewayVerifiedPackage {
        val bytes = input.inputStream().use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer, 0, minOf(buffer.size, IeltsGatewayProtocol.MAX_RESPONSE_BYTES + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
                require(output.size() <= IeltsGatewayProtocol.MAX_RESPONSE_BYTES) { "Verified material exceeds its size bound" }
            }
            output.toByteArray()
        }
        require(bytes.isNotEmpty()) { "Verified material is empty" }
        artifactHash = sha256(bytes)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return IeltsGatewayProtocol.decodeVerifiedPackage(text)
    }

    private fun checkProvenance(value: IeltsGatewayVerifiedPackage, validation: IeltsValidationReceipt) {
        val p = value.provenance
        assertEquals("deepseek_harness", p.kind)
        assertEquals(validation.requestSha256, p.requestSha256)
        assertEquals(validation.packageSha256, p.packageSha256)
        assertEquals("independent_answers_agree", p.semanticReview)
        assertFalse(p.recentThreeYearCoverage)
        IeltsExerciseValidator.validId(p.generationSessionId); IeltsExerciseValidator.validId(p.reviewSessionId)
        assertNotEquals(p.generationSessionId, p.reviewSessionId)
        val explanationSession = p.explanationReviewSessionId
        if (explanationSession == null) {
            // Historical material remains useful for an offline UI trial. It
            // must not be relabelled as having passed the newer explanation gate.
            assertNull(p.explanationReviewSha256); assertNull(p.explanationReview)
            assertTrue(p.realProviderRequestCount in 2..16)
        } else {
            IeltsExerciseValidator.validId(explanationSession)
            assertEquals(3, setOf(p.generationSessionId, p.reviewSessionId, explanationSession).size)
            assertEquals("source_faithfulness_checked", p.explanationReview)
            assertTrue(Regex("[a-f0-9]{64}").matches(requireNotNull(p.explanationReviewSha256)))
            assertTrue(p.realProviderRequestCount in 3..16)
        }
        assertTrue(p.createdAt in 0..IeltsContract.MAX_TIMESTAMP)
        assertTrue(Regex("[A-Za-z0-9][A-Za-z0-9._+:-]{0,63}").matches(p.runtimeVersion))
        assertTrue(listOf(p.requestSha256, p.packageSha256, p.reviewSha256, p.corpusSha256).all { Regex("[a-f0-9]{64}").matches(it) })
        assertEquals("paired_host_receipt", value.provenanceScope)
        assertFalse(value.runtimeExecutedOnClient)
    }

    private fun checkpoint(value: String) { completedSteps += value; receipt("running") }

    private fun receipt(status: String, reason: String? = null) {
        File(evidence, "receipt.json").writeText(buildJsonObject {
            put("schema", "lexicon.ielts.material-ui-trial.v1"); put("status", status); put("stage", stage)
            reason?.let { put("reason", it) }; put("variant", variant); put("font_scale", 2)
            put("material_origin", "operator_supplied_harness_artifact"); put("synthetic_question_fallback_used", false)
            artifactHash?.let { put("artifact_bytes_sha256", it) }
            material?.let { v ->
                put("request_sha256", v.provenance.requestSha256); put("package_sha256", v.provenance.packageSha256)
                put("provenance_kind", v.provenance.kind); put("provenance_scope", v.provenanceScope)
                put("provider_requests_claimed_by_artifact", v.provenance.realProviderRequestCount)
                put("artifact_review_coverage_claimed", if (v.provenance.explanationReviewSessionId == null)
                    "legacy_blind_answer_review_only" else "blind_answers_and_explanation_faithfulness")
                put("explanation_review_independently_verified_by_test", false)
            }
            put("provider_execution_independently_verified", false); put("runtime_executed_on_android", false)
            put("network_operations_by_test", 0); put("forbidden_operation_attempts", offline.forbiddenCalls.get())
            put("database_scope", "isolated_in_memory_room"); put("viewmodels_created", stores.size)
            put("answer_entry", "automatic_from_artifact_reference_key"); put("human_answer_accuracy_measured", false)
            put("writing_origin", "self_authored_short_ui_draft"); put("writing_words", IeltsExerciseValidator.wordCount(shortWriting))
            put("official_band_score_assigned", false); put("online_generation_success_rate_measured", false)
            put("completed_steps", JsonArray(completedSteps.map(::JsonPrimitive)))
            put("text_layout_checks", JsonArray(textLayouts)); put("screenshots", JsonArray(screenshots))
        }.toString())
    }

    private fun reference(question: IeltsReadingQuestion): String = when (question) {
        is IeltsChoiceQuestion -> question.answer.name
        is IeltsInformationQuestion -> question.answer.wire
        is IeltsViewQuestion -> question.answer.wire
        is IeltsCompletionQuestion -> question.answer
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** No HTTP client, real settings, credential access or generation implementation is constructed. */
    private class OfflineGenerations : IeltsGenerationRepository {
        val forbiddenCalls = AtomicInteger()
        override suspend fun connection() = IeltsGatewayConnection(null, false, null)
        override fun observeHistory() = flowOf(emptyList<IeltsGenerationHistoryItem>())
        override suspend fun get(requestId: String): IeltsGenerationRecord? = null
        private fun denied(): Nothing { forbiddenCalls.incrementAndGet(); error("Network operations are disabled in the material UI trial") }
        override suspend fun pair(endpoint: String, token: String): IeltsGatewayConnection = denied()
        override suspend fun clearPairing(): Unit = denied()
        override suspend fun generate(submit: IeltsGatewaySubmit): IeltsGenerationRecord = denied()
        override suspend fun reconcile(requestId: String): IeltsGenerationRecord = denied()
        override suspend fun cancel(requestId: String): IeltsGenerationRecord = denied()
    }
}
