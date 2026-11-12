package dev.lexicon.ide.ui

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.data.*
import dev.lexicon.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Isolated real storage and production ViewModel; only the generation transport is synthetic. */
class IeltsStudyFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val stores = mutableListOf<ViewModelStore>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db by lazy { Room.inMemoryDatabaseBuilder<LexiconDatabase>(context)
        .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build() }
    private val practices by lazy { LocalIeltsPracticeRepository(db) }
    private val study by lazy { LocalIeltsStudyRepository(db) }
    private val generations = GenerationRecorder()
    private var model by mutableStateOf<IeltsExerciseViewModel?>(null)
    private var ids = 0
    private var failFeedback = false
    private val evidence by lazy { File(context.filesDir, "validation-study-145/${System.currentTimeMillis()}-${UUID.randomUUID()}").apply { check(mkdirs()) } }
    private val night = InstrumentationRegistry.getArguments().getString("variant") == "night"
    private val fontScale = if (night) 2f else 1f

    @After fun close() { compose.runOnIdle { stores.forEach { it.clear() } }; db.close() }
    private fun recreate() {
        stores.lastOrNull()?.clear()
        val backing = LocalIeltsStudyRepository(db)
        val access = object : IeltsStudyRepository by backing {
            override suspend fun reflect(attemptId: String, questionId: String, intent: IeltsStudyIntent?,
                expectedFeedbackRevision: Long, expectedProfileRevision: Long, now: Long): IeltsStudySnapshot {
                if (failFeedback) throw IeltsStudyFailure(IeltsStudyFailureCode.UNAVAILABLE)
                return backing.reflect(attemptId, questionId, intent, expectedFeedbackRevision, expectedProfileRevision, now)
            }
        }
        model = IeltsExerciseViewModel(generations, practices, SavedStateHandle(), clock = { 1000 },
            idFactory = { "study-request-${++ids}" }, studyRepository = access)
        stores += ViewModelStore().also { it.put("study-test", model!!) }
    }
    private fun setup(submitted: Boolean = true): IeltsSavedPractice = runBlocking {
        val root = Json.parseToJsonElement(InstrumentationRegistry.getInstrumentation().context.assets.open("ielts-task1.json")
            .bufferedReader().use { it.readText() }).jsonObject
        val request = IeltsExerciseJson.decodeRequest(root.getValue("request").toString())
        val pkg = IeltsExerciseJson.decodePackage(root.getValue("package").toString())
        val hash = practices.cache(request, pkg); val start = practices.begin(hash, "study-attempt", 10)
        val responses = pkg.readingQuestions.map { q -> IeltsResponse(q.questionId, when (q) {
            is IeltsChoiceQuestion -> q.answer.name
            is IeltsInformationQuestion -> q.answer.wire
            is IeltsViewQuestion -> q.answer.wire
            is IeltsCompletionQuestion -> q.answer
        }) }
        val draft = practices.saveWritingDrafts(start.attemptId, start.revision, responses, "My original opinion.", "My chart overview.", 20)
        val saved = if (submitted) practices.submit(start.attemptId, draft.revision, 30) else draft
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge(); it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.runOnIdle { recreate() }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                LexiconTheme(AppSettings(theme = if (night) ThemeMode.DARK else ThemeMode.LIGHT)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        IeltsExerciseRoute(requireNotNull(model), emptyList()) {}
                    }
                }
            }
        }
        compose.waitUntil(10_000) { model?.state?.value?.busy == false && model?.state?.value?.study != null }
        compose.runOnIdle { model!!.openAttempt(saved.attemptId) }
        compose.waitUntil(10_000) { model!!.state.value.candidate != null && !model!!.state.value.busy }
        saved
    }
    private fun practice(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("ielts_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun workspace(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("ielts_workspace_list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun feedback(question: String, intent: IeltsStudyIntent?) {
        practice("ielts_reflection_$question").performClick()
        practice(when (intent) {
            IeltsStudyIntent.PRACTICE_AGAIN -> "ielts_again_$question"
            IeltsStudyIntent.QUESTIONABLE -> "ielts_questionable_$question"
            null -> "ielts_undo_reflection_$question"
        }).performClick()
        compose.waitUntil(10_000) { !model!!.state.value.studyBusy && model!!.state.value.study!!.feedback.any { it.questionId == question && it.intent == intent } }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        try { File(evidence, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { image.recycle() }
    }
    private fun prepareNext() {
        val text = "A new article offers another reading opportunity."
        val article = Article("next-article", "self", emptyList(), "A new reading passage", "", "", readingMode = ReadingMode.NATIVE)
        compose.runOnIdle { model!!.prepare(article, ArticleBody(article.id, text, IeltsGatewayProtocol.bodyHash(text), 0, Long.MAX_VALUE, "self-authored"), true) }
        compose.waitUntil(10_000) { model!!.state.value.preparedArticleId == article.id && !model!!.state.value.busy }
    }

    @Test fun submittedReflectionRestoresAndFreezesTargetedRequestsBeforeGeneration() {
        val saved = setup()
        feedback("q2", IeltsStudyIntent.PRACTICE_AGAIN)
        practice("ielts_reflection_q2").assertTextEquals("已选再练此类")
        screenshot("01-marked-for-practice")
        assertEquals(0, generations.calls)
        assertEquals(saved, runBlocking { practices.get(saved.attemptId) })
        compose.runOnIdle { recreate() }
        compose.waitUntil(10_000) { model!!.state.value.study?.profile?.focus != null && !model!!.state.value.busy }
        prepareNext()
        workspace("ielts_study_focus").assertTextEquals("专项 · 信息判断")
        workspace("ielts_study_evidence").performClick(); screenshot("02-focus-evidence")
        workspace("ielts_generate").performClick()
        compose.waitUntil(10_000) { generations.calls == 1 && !model!!.state.value.busy }
        assertTrue(generations.bindingExistedBeforeGenerate)
        assertEquals(IeltsStudyPolicy.requests(IeltsReadingType.IDENTIFYING_INFORMATION), generations.last!!.readingRequests)
        assertEquals(saved, runBlocking { practices.get(saved.attemptId) })
        assertNotNull(runBlocking { study.requestBinding(generations.last!!.requestId) })
        File(evidence, "receipt.json").writeText("""{"passed":true,"night":$night,"font_scale":$fontScale,"model_calls":0,"transport":"synthetic_recorder","feedback_saved":true,"origin_unchanged":true,"request_binding_before_dispatch":true}""")
    }

    @Test fun questioningTheItemRemovesTheFocusAndDoesNotGradeOrRewriteIt() {
        val saved = setup()
        feedback("q2", IeltsStudyIntent.PRACTICE_AGAIN)
        // Collapse the remembered local section before reusing the same entry.
        practice("ielts_reflection_q2").performClick()
        feedback("q2", IeltsStudyIntent.QUESTIONABLE)
        assertNull(model!!.state.value.study!!.profile.focus)
        practice("ielts_reflection_q2").assertTextEquals("题目存疑"); screenshot("03-questionable")
        assertEquals(saved, runBlocking { practices.get(saved.attemptId) })
        prepareNext(); compose.onNodeWithTag("ielts_study_focus").assertDoesNotExist()
        workspace("ielts_generate").performClick()
        compose.waitUntil(10_000) { generations.calls == 1 && !model!!.state.value.busy }
        assertEquals(IeltsStudyPolicy.requests(null), generations.last!!.readingRequests)
    }

    @Test fun draftDoesNotExposeStudyFeedbackOrFutureReferenceAnswers() {
        setup(submitted = false)
        practice("ielts_prompt_q2").assertIsDisplayed()
        compose.onNodeWithTag("ielts_reflection_q2").assertDoesNotExist()
        compose.onNodeWithTag("ielts_reference_q2").assertDoesNotExist()
        compose.runOnIdle { model!!.reflect("q2", IeltsStudyIntent.PRACTICE_AGAIN) }
        assertEquals(0L, runBlocking { study.load().profile.revision }); assertEquals(0, generations.calls)
    }

    @Test fun restoringBalancedPracticeDoesNotEraseTheOriginalReflection() {
        val saved = setup()
        feedback("q2", IeltsStudyIntent.PRACTICE_AGAIN)
        prepareNext()
        workspace("ielts_study_balanced").performClick()
        compose.waitUntil(10_000) { !model!!.state.value.studyBusy && model!!.state.value.study!!.profile.focus == null }
        assertEquals(IeltsStudyIntent.PRACTICE_AGAIN, runBlocking { study.load(saved.attemptId).feedback.single().intent })
        workspace("ielts_generate").performClick()
        compose.waitUntil(10_000) { generations.calls == 1 && !model!!.state.value.busy }
        assertEquals(IeltsStudyPolicy.requests(null), generations.last!!.readingRequests)
    }

    @Test fun aFailedFeedbackWriteKeepsTheOldChoiceAndCanBeReloadedWithoutSendingAnything() {
        val saved = setup()
        feedback("q2", IeltsStudyIntent.PRACTICE_AGAIN)
        failFeedback = true
        practice("ielts_questionable_q2").performClick()
        compose.waitUntil(10_000) { !model!!.state.value.studyBusy && model!!.state.value.studyError != null }
        compose.onNodeWithTag("ielts_study_error").assertIsDisplayed()
        assertEquals(IeltsStudyIntent.PRACTICE_AGAIN, model!!.state.value.study!!.feedback.single().intent)
        failFeedback = false
        compose.onNodeWithTag("ielts_study_reload").performClick()
        compose.waitUntil(10_000) { !model!!.state.value.studyBusy && model!!.state.value.studyError == null }
        practice("ielts_questionable_q2").performClick()
        compose.waitUntil(10_000) { !model!!.state.value.studyBusy && model!!.state.value.study!!.profile.focus == null }
        assertEquals(saved, runBlocking { practices.get(saved.attemptId) }); assertEquals(0, generations.calls)
    }

    private inner class GenerationRecorder : IeltsGenerationRepository {
        var calls = 0; var last: IeltsGatewaySubmit? = null; var bindingExistedBeforeGenerate = false
        private val values = mutableMapOf<String, IeltsGenerationRecord>()
        private val health = IeltsGatewayHealth("lexicon.ielts.health.v1", "ok", IeltsGatewayCapabilities("test", "synthetic",
            "e".repeat(64), listOf("multiple_choice", "identifying_information", "identifying_writers_views", "short_text_completion"),
            listOf("extent_of_agreement"), "official_public_samples_and_format_metadata"))
        override suspend fun connection() = IeltsGatewayConnection("https://paired.example/", true, health)
        override suspend fun pair(endpoint: String, token: String) = error("unused")
        override suspend fun clearPairing() = error("unused")
        override fun observeHistory() = flowOf(emptyList<IeltsGenerationHistoryItem>())
        override suspend fun get(requestId: String) = values[requestId]
        override suspend fun generate(submit: IeltsGatewaySubmit): IeltsGenerationRecord {
            val binding = study.requestBinding(submit.requestId)
            bindingExistedBeforeGenerate = binding?.submitHash == IeltsExerciseJson.canonicalSha256(IeltsGatewayProtocol.encodeSubmit(submit))
            calls++; last = submit
            return IeltsGenerationRecord(submit = submit, submitHash = IeltsExerciseJson.canonicalSha256(IeltsGatewayProtocol.encodeSubmit(submit)),
                endpoint = "https://paired.example/", expectedHealth = health, attemptId = "generated-attempt", revision = 1,
                createdAt = 1000, updatedAt = 1000, dispatchClaimed = true, stage = IeltsGenerationStage.RECONCILING)
                .also { values[submit.requestId] = it }
        }
        override suspend fun reconcile(requestId: String) = values.getValue(requestId)
        override suspend fun cancel(requestId: String) = error("unused")
    }
}
