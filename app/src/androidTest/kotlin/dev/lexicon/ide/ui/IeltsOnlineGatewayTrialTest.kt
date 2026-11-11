package dev.lexicon.ide.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.data.*
import dev.lexicon.domain.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in paid integration: real file picker, production HTTPS client/host and one explicit generation. */
@RunWith(AndroidJUnit4::class)
class IeltsOnlineGatewayTrialTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val stores = mutableListOf<ViewModelStore>()
    private var database: LexiconDatabase? = null
    private var access: IeltsGatewaySettings? = null
    private lateinit var evidence: File
    private var stage = "not_started"
    private var requestId: String? = null
    private var packageHash: String? = null
    private var certificateHash: String? = null
    private var knownRequestReplay = false
    private val steps = mutableListOf<String>()
    private val screenshots = mutableListOf<JsonObject>()
    private val writing = "A careful local trial can show whether a policy works in practice. " +
        "Costs and benefits may differ between communities. This short draft checks saving and review; it is not a complete essay."

    @After fun dispose() {
        instrumentation.runOnMainSync { stores.forEach(ViewModelStore::clear) }
        access?.let { runBlocking { it.clearPairing() } }
        database?.close()
    }

    @Test fun importedPersonalHostGeneratesOneRealExerciseAndPreservesDraftAndReview() {
        assumeTrue("Requires explicit real-provider authorization and a foreground host.",
            InstrumentationRegistry.getArguments().getString("lexiconIeltsOnlineTrial") == "1")
        val inputFile = File(instrumentation.targetContext.filesDir, "ielts-online-input/article.json")
        assumeTrue("No synthetic response or prior generated package is substituted.", inputFile.isFile)
        evidence = File(instrumentation.targetContext.filesDir,
            "validation-1.36.0/ielts-online/${System.currentTimeMillis()}-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val input = inputFile.inputStream().use { it.readNBytes(200_001) }.also { require(it.size <= 200_000) }
            val fields = Json.parseToJsonElement(input.toString(Charsets.UTF_8)).jsonObject
            require(fields["schema"]?.jsonPrimitive?.content == "lexicon.ielts.online-trial-input.v1")
            require(fields["source_kind"]?.jsonPrimitive?.content == "self_authored_fictional")
            val text = fields.getValue("body").jsonPrimitive.content
            val originalRequestId = fields["reuse_completed_request_id"]?.jsonPrimitive?.content?.also {
                IeltsGatewayProtocol.validateRequestId(it); knownRequestReplay = true
            }
            val bodyHash = IeltsGatewayProtocol.bodyHash(text)
            require(bodyHash == fields.getValue("body_sha256").jsonPrimitive.content)
            certificateHash = fields.getValue("certificate_sha256").jsonPrimitive.content.also { require(Regex("[a-f0-9]{64}").matches(it)) }
            val article = Article(fields.getValue("article_id").jsonPrimitive.content, "self-authored", emptyList(),
                fields.getValue("title").jsonPrimitive.content, "", "", readingMode = ReadingMode.NATIVE,
                sourceName = "Self-authored fictional integration material")
            val body = ArticleBody(article.id, text, bodyHash, 0, Long.MAX_VALUE, "Self-authored integration material")
            val context = instrumentation.targetContext
            val private = File(context.cacheDir, "ielts-online-${UUID.randomUUID()}").apply { check(mkdirs()) }
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir() = File(private, "ordinary").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(private, "no-backup").apply { mkdirs() }
            }
            val settings = IeltsGatewaySettings(isolated).also { access = it }
            val db = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO).build().also { database = it }
            val practices = LocalIeltsPracticeRepository(db)
            val generations = LocalIeltsGenerationRepository(db, settings, practices)
            val host = Host(generations, practices, originalRequestId)
            instrumentation.runOnMainSync { host.recreate() }
            compose.setContent { host.Content() }
            compose.waitUntil(10_000) { !host.model.state.value.busy }
            compose.runOnIdle { host.model.prepare(article, body, true) }
            compose.waitUntil(10_000) { host.model.state.value.preparedArticleId == article.id && !host.model.state.value.busy }
            compose.onNodeWithTag("ielts_connection").performClick()
            workspace("ielts_import_pairing").performClick()
            stage = "awaiting_system_file_selection"; receipt("running")
            // The root operator chooses the locally transferred file in Android DocumentsUI.
            compose.waitUntil(180_000) { host.model.state.value.pairingPreview != null }
            assertEquals(certificateHash, host.model.state.value.pairingPreview!!.certificateSha256)
            assertFalse(runBlocking { settings.snapshot() }.paired)
            screenshot("01-imported-preview")
            checkpoint("system_file_selected_and_public_identity_confirmed")
            workspace("ielts_confirm_pairing").performClick()
            compose.waitUntil(45_000) { !host.model.state.value.busy }
            assertTrue("Production TLS pairing failed", host.model.state.value.connection.paired)
            assertEquals(certificateHash, host.model.state.value.connection.certificateSha256)
            assertEquals(host.model.state.value.connection, runBlocking { IeltsGatewaySettings(isolated).snapshot() })
            screenshot("02-connected")
            checkpoint("production_https_health_and_keystore_restore")

            workspace("ielts_generate").assertIsEnabled().performClick()
            compose.waitUntil(45_000) { host.model.state.value.generation != null && !host.model.state.value.busy }
            requestId = requireNotNull(host.model.state.value.generation?.requestId)
            stage = "waiting_for_original_host_request"; receipt("running")
            compose.waitUntil(660_000) {
                host.model.state.value.candidate != null || host.model.state.value.generation?.stage in
                    setOf(IeltsGenerationStage.FAILED, IeltsGenerationStage.INTERRUPTED, IeltsGenerationStage.CANCELLED)
            }
            val candidate = requireNotNull(host.model.state.value.candidate) { "Original host request did not produce a verified package" }
            packageHash = candidate.packageSha256
            assertEquals(bodyHash, candidate.bodyHash)
            val generated = runBlocking { requireNotNull(generations.get(requireNotNull(requestId))) }
            assertEquals(certificateHash, generated.certificateSha256)
            assertEquals(IeltsGenerationStage.READY, generated.stage)
            val attemptId = generated.attemptId
            screenshot("03-real-candidate")
            checkpoint("one_original_request_completed_and_package_validated")

            // Use arbitrary valid answers. This is an interaction trial, not a measured comprehension score.
            candidate.readingQuestions.forEach { question ->
                val choice = when (question) {
                    is IeltsCandidateChoice -> question.options.first().optionId.name
                    is IeltsCandidateInformation -> IeltsInformationAnswer.NOT_GIVEN.wire
                    is IeltsCandidateViewQuestion -> IeltsViewAnswer.NOT_GIVEN.wire
                    is IeltsCandidateCompletion -> "The"
                }
                if (question is IeltsCandidateCompletion) practice("ielts_input_${question.questionId}").performTextInput(choice)
                else practice("ielts_option_${question.questionId}_$choice").performClick()
            }
            practice("ielts_writing_input").performTextInput(writing)
            screenshot("04-writing-keyboard")
            compose.onNodeWithTag("ielts_back").performClick()
            compose.waitUntil(15_000) { !host.visible }
            val draft = runBlocking { requireNotNull(practices.get(attemptId)) }
            assertEquals(writing, draft.writingText); assertNull(draft.submission)
            assertEquals(4, draft.draftResponses.size)
            checkpoint("answers_and_short_writing_saved_before_exit")
            compose.onNodeWithTag("online_reopen").performClick()
            openHistory(host, attemptId)
            assertEquals(draft.draftResponses, host.model.state.value.draftResponses)
            assertEquals(writing, host.model.state.value.writingText)
            compose.onNodeWithTag("ielts_submit").assertIsEnabled().performClick()
            compose.waitUntil(15_000) { host.model.state.value.answerView != null && !host.model.state.value.busy }
            val submitted = runBlocking { requireNotNull(practices.get(attemptId)) }
            assertTrue(submitted.submitted)
            assertEquals(submitted.answerView(), host.model.state.value.answerView)
            assertNull(submitted.answerView()!!.officialBandScore)
            practice("ielts_review_toggle_${candidate.readingQuestions.first().questionId}").performClick()
            screenshot("05-submitted-review")
            checkpoint("restored_draft_and_persisted_submission_before_reference")
            compose.onNodeWithTag("ielts_back").performClick()
            compose.waitUntil(15_000) { !host.visible }
            compose.onNodeWithTag("online_reopen").performClick()
            openHistory(host, attemptId)
            assertEquals(submitted.answerView(), host.model.state.value.answerView)
            assertEquals(submitted, runBlocking { practices.get(attemptId) })
            screenshot("06-reopened-review")
            checkpoint("submitted_review_restored_without_duplicate_commit")
            stage = "complete"; receipt("passed")
        } catch (failure: Throwable) {
            receipt("failed", failure.javaClass.simpleName)
            throw failure
        }
    }

    private inner class Host(val generations: IeltsGenerationRepository, val practices: IeltsPracticeRepository,
        val originalRequestId: String?) {
        var vm by mutableStateOf<IeltsExerciseViewModel?>(null)
        var visible by mutableStateOf(true)
        val model get() = requireNotNull(vm)
        private var store: ViewModelStore? = null
        fun recreate() {
            store?.clear(); store = ViewModelStore().also { stores += it }
            vm = IeltsExerciseViewModel(generations, practices, SavedStateHandle(),
                idFactory = { originalRequestId ?: "ielts-${UUID.randomUUID()}" }).also { store!!.put("online-trial", it) }
            visible = true
        }
        @Composable fun Content() {
            val activity = LocalContext.current.findActivity()
            DisposableEffect(activity) {
                val mode = activity?.window?.attributes?.softInputMode
                (activity as? ComponentActivity)?.enableEdgeToEdge()
                activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                onDispose { if (mode != null) activity?.window?.setSoftInputMode(mode) }
            }
            LexiconTheme(AppSettings(theme = ThemeMode.LIGHT)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (visible) IeltsExerciseRoute(model, emptyList()) { visible = false }
                    else Button(onClick = ::recreate, modifier = Modifier.safeDrawingPadding().testTag("online_reopen")) { Text("打开练习记录") }
                }
            }
        }
    }
    private fun workspace(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("ielts_workspace_list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun practice(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("ielts_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun openHistory(host: Host, id: String) {
        compose.waitUntil(15_000) { !host.model.state.value.busy && host.model.state.value.practiceHistory.any { it.attemptId == id } }
        workspace("ielts_history_$id").performClick()
        compose.waitUntil(15_000) { host.model.state.value.candidate != null && !host.model.state.value.busy }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle(); instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(evidence, "$name.png")
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() }
        screenshots += buildJsonObject { put("file", file.name); put("sha256", hash(file.readBytes())) }
    }
    private fun checkpoint(name: String) { steps += name; stage = name; receipt("running") }
    private fun receipt(status: String, failure: String? = null) {
        if (!::evidence.isInitialized) return
        File(evidence, "receipt.json").writeText(buildJsonObject {
            put("schema", "lexicon.ielts.online-ui-trial.v1"); put("status", status); put("stage", stage)
            put("request_id", requestId); put("package_sha256", packageHash); put("certificate_sha256", certificateHash)
            put("failure_type", failure); put("database_scope", "isolated_in_memory_room")
            put("tls_scope", "explicitly_imported_personal_leaf_with_default_hostname_validation")
            put("transport", "adb_reverse_loopback"); put("provider_execution", "foreground_mac_harness_host")
            put("real_file_picker", true); put("source_kind", "self_authored_fictional")
            put("reuses_explicitly_completed_host_request", knownRequestReplay)
            put("answer_entry", "arbitrary_valid_test_answers"); put("human_comprehension_measured", false)
            put("official_band_score_assigned", false); put("viewmodels_created", stores.size)
            putJsonArray("completed_steps") { steps.forEach { add(it) } }
            putJsonArray("screenshots") { screenshots.forEach { add(it) } }
        }.toString())
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
