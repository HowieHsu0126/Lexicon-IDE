package dev.lexicon.ide.ui

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real cached-content interaction trial. No fixture is inserted and no human ability is measured. */
@RunWith(AndroidJUnit4::class)
class ProgressSkimDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val codec = Json { ignoreUnknownKeys = true }
    private data class Candidate(val article: Article, val body: ArticleBody, val study: ArticleStudy?, val aiAllowed: Boolean)
    private data class Trial(val repository: AppRepository, val candidate: Candidate, val output: File, val report: JSONObject)

    @Test fun realProgressAndSkimPlanningRemainGroundedAndPersistAcrossReaderEntries() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("lexiconProgressSkimTrial") == "1")
        val liveRequested = arguments.getString("lexiconLiveSkim") == "1"
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiconApplication
        val repository = app.repository
        val output = File(app.filesDir, "validation-1.7.0/progress-skim-trial").apply { mkdirs() }
        val report = JSONObject().put("status", "started").put("automatedInteractionTrial", true)
            .put("humanReadingPerformanceMeasured", false).put("networkTrafficMeasured", false)
            .put("liveSkimRequested", liveRequested).put("aiActionInvocations", 0)
            .put("operations", JSONArray()).put("screenshots", JSONArray()).put("untestedPaths", JSONArray())
        fun save() { File(output, "device-trial.json").writeText(report.toString(2)) }
        fun skip(reason: String): Nothing {
            report.put("status", "skipped").put("reason", reason); save(); assumeTrue(reason, false); error("Skipped")
        }
        runBlocking { repository.initialize() }
        val database = app.getDatabasePath("lexicon.sqlite")
        if (!database.isFile) skip("The installed database is unavailable.")
        val baseline = repository.baseline ?: skip("Baseline history is unavailable.")
        val speed = repository.speedReading ?: skip("Frozen speed plans are unavailable.")
        val reading = repository.reading ?: skip("Reading data is unavailable.")
        val jobsBefore = runBlocking { repository.jobs.first() }
        if (jobsBefore.any { it.status == "running" }) skip("An AI job is already running; the trial will not overlap it.")
        val candidate = candidates(database, runBlocking { repository.articles.first() }, runBlocking { repository.sources.first() }, reading)
            .sortedBy { if (liveRequested && it.aiAllowed && (it.study == null || it.study.questions.any { q -> q.evidence.isEmpty() })) 0 else 1 }
            .firstOrNull() ?: skip("No already-read The Conversation article has a valid native cache. No download or fixture fallback was attempted.")
        val baselineBefore = runBlocking { baseline.library.first() }.associateBy { it.id }
        val plans = mutableMapOf<String, SpeedPlan?>()
        val expectedProgress = runBlocking {
            BaselineProgressCalculator.summarize(baselineBefore.values.map { attempt ->
                if (attempt.planKey !in plans) plans[attempt.planKey] = speed.cachedPlan(attempt.planKey)
                BaselineProgressRecord(attempt, plans[attempt.planKey], baseline.material(attempt.id))
            })
        }
        val speedBefore = runBlocking { speed.library.first() }
        val readingBefore = runBlocking { reading.library.first() }
        val cardsBefore = runBlocking { repository.cards.first() }.associateBy { it.id }
        val capturesBefore = runBlocking { repository.captures.first() }.associateBy { it.id }
        val originalSettings = runBlocking { repository.settings.first() }
        val originalSettingsBytes = readRawPreference(database, "settings")
        val originalProfileBytes = readRawPreference(database, "personalization:profile")
        val originalPausedBytes = readRawPreference(database, "ai_paused")
        val originalHasKey = runBlocking { repository.hasApiKey() }
        var temporaryKeyInstalled = false
        val stagedKey = File(app.filesDir, "lexicon-trial-api-key")
        val priorSessionRows = sessionRows(database)
        val trial = Trial(repository, candidate, output, report)
        val trialStartedAt = System.currentTimeMillis()
        var preferencesChanged = false
        var activity: ActivityScenario<MainActivity>? = null
        var primaryFailure: Throwable? = null
        report.put("articleId", candidate.article.id).put("publisherHost", "theconversation.com")
            .put("bodyHash", candidate.body.contentHash).put("cachedStudyVersionBefore", candidate.study?.schemaVersion ?: JSONObject.NULL)
            .put("baselineRecordCountBefore", baselineBefore.size)
        save()
        try {
            runBlocking { repository.updateSettings(originalSettings.copy(titleClassification = false,
                reader = originalSettings.reader.copy(automaticStudy = false))) }
            preferencesChanged = true
            if (liveRequested && arguments.getString("lexiconPrivateApiFile") == "1") {
                check(!originalHasKey) { "A temporary trial key must never replace an existing key" }
                try {
                    val key = stagedKey.inputStream().use(::readApiKeyInput)
                    temporaryKeyInstalled = true
                    runBlocking { repository.setApiKey(key) }
                    check(runBlocking { repository.hasApiKey() })
                    report.put("temporaryEncryptedKeyImported", true)
                } finally { check(!stagedKey.exists() || stagedKey.delete()) }
            }
            activity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(20_000) { existsTag("daily_reading") }
            verifyActualProgress(trial, expectedProgress, baselineBefore)
            openCommand("explorer", "打开阅读列表")
            compose.waitUntil(10_000) { existsTag("daily_reading") }
            openCachedArticle(candidate)
            openSkimTools()
            val beforePace = runBlocking { repository.settings.first().reader.bounded().skimPlanningWpm }
            val fraction = if (beforePace >= 260) .2f else .8f
            compose.onNodeWithTag("skim_planning_pace").performScrollTo().performTouchInput {
                click(Offset(width * fraction, centerY))
            }
            compose.waitUntil(10_000) { runBlocking { repository.settings.first().reader.skimPlanningWpm } != beforePace }
            val savedPace = runBlocking { repository.settings.first().reader.skimPlanningWpm }
            assertTrue("The slider must commit a bounded planning pace", savedPace in 80..400 && savedPace % 20 == 0)
            compose.onNodeWithText("规划速度 · $savedPace WPM").assertIsDisplayed()
            screenshot(trial, "02-skim-planning-pace.png")
            report.put("planningWpmBefore", beforePace).put("planningWpmAfterDrag", savedPace)
            step(trial, "Changed the planning slider in the real reader and observed the persisted settings value")
            pressBack()
            compose.waitUntil(10_000) { !existsTag("skim_planning_pace") }
            pressBack()
            compose.waitUntil(10_000) { existsTag("article_history_list") && !existsTag("zen_article_text") }
            compose.onNodeWithTag("article_history_list").performScrollToNode(hasText(candidate.article.title))
            compose.onNodeWithText(candidate.article.title).performClick()
            compose.waitUntil(15_000) { existsTag("zen_article_text") }
            openSkimTools()
            compose.onNodeWithTag("skim_planning_pace").performScrollTo()
            val reopenedPace = compose.onNodeWithTag("skim_planning_pace").fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo].current.toInt()
            assertEquals("Reopening the article must restore the saved planning pace", savedPace, reopenedPace)
            assertEquals(savedPace, runBlocking { repository.settings.first().reader.skimPlanningWpm })
            compose.onNodeWithText("规划速度 · $savedPace WPM").assertIsDisplayed()
            report.put("planningPacePersistedAcrossReaderEntries", true)
            screenshot(trial, "03-reopened-planning-pace.png")
            step(trial, "Exited and reopened the same publisher article; the planning pace remained unchanged")

            val study = optionalLiveStudy(trial, liveRequested)
            if (study != null && usableQuestions(candidate.body, study).size >= 2) {
                verifyQuestionEvidence(trial, study, savedPace)
            } else {
                report.put("multiQuestionEvidenceVerified", false)
                report.getJSONArray("untestedPaths").put("At least two valid exact-sentence question evidence records were unavailable")
            }
            assertPreserved("Existing baseline attempts", baselineBefore, runBlocking { baseline.library.first() }.associateBy { it.id })
            assertTrue("Paced and natural training records must remain unchanged", speedBefore == runBlocking { speed.library.first() })
            assertPreserved("Existing vocabulary cards", cardsBefore, runBlocking { repository.cards.first() }.associateBy { it.id })
            assertPreserved("Existing captures", capturesBefore, runBlocking { repository.captures.first() }.associateBy { it.id })
            val readingAfter = runBlocking { reading.library.first() }
            assertTrue("Notes and checks must remain unchanged", readingBefore.notes == readingAfter.notes && readingBefore.checks == readingAfter.checks)
            assertTrue("Expression reviews must remain unchanged", readingBefore.reviews == readingAfter.reviews)
            val jobsAfter = runBlocking { repository.jobs.first() }
            val addedJobs = jobsAfter.filter { job -> jobsBefore.none { it.id == job.id } }
            if (report.getInt("aiActionInvocations") == 0) assertTrue("Offline interaction must not add AI jobs", addedJobs.isEmpty())
            else assertTrue("Only the single requested study may add an AI job", addedJobs.size <= 1 && addedJobs.all { it.kind == "reading_study" })
            report.put("newAiJobCount", addedJobs.size).put("oldLearningRecordsPreserved", true)
                .put("status", if (report.optString("liveStudyStatus") in setOf("failed", "timeout", "insufficient_evidence")) "partial" else "passed")
            report.getJSONArray("untestedPaths").put("Physical MagicOS performance").put("Predictive back gesture cancellation").put("Human reading ability")
            save()
        } catch (failure: Throwable) {
            primaryFailure = failure
            report.put("status", "failed").put("failureType", failure.javaClass.simpleName)
                .put("failureStack", JSONArray(failure.stackTrace.take(16).map { it.toString() }))
            runCatching { screenshot(trial, "failure.png") }; save(); throw failure
        } finally {
            val activityClose = runCatching { activity?.close() }
            val keyRestore = runCatching {
                if (temporaryKeyInstalled) {
                    runBlocking { repository.clearApiKey() }
                    restoreRawPreference(database, "ai_paused", originalPausedBytes)
                }
                check(!stagedKey.exists() || stagedKey.delete())
                check(runBlocking { repository.hasApiKey() } == originalHasKey)
            }
            report.put("apiKeyStateRestored", keyRestore.isSuccess)
            if (preferencesChanged) {
                val cleanup = runCatching {
                    awaitSessionWrites(database)
                    cleanupOwnSessions(database, priorSessionRows, candidate.article.id, trialStartedAt, System.currentTimeMillis(), report)
                }
                val settingsRestore = runCatching {
                    try {
                        runBlocking {
                            repository.updateSettings(originalSettings)
                            withTimeout(10_000) { repository.settings.first { it == originalSettings } }
                        }
                    } finally {
                        // Preserve original stored bytes even if the asynchronous settings stream fails.
                        restoreRawPreference(database, "settings", originalSettingsBytes)
                    }
                    sameBytes(originalSettingsBytes, readRawPreference(database, "settings"))
                }
                val profileRestore = runCatching {
                    restoreRawPreference(database, "personalization:profile", originalProfileBytes)
                    sameBytes(originalProfileBytes, readRawPreference(database, "personalization:profile"))
                }
                report.put("activityClosed", activityClose.isSuccess)
                    .put("trialSessionsCleaned", cleanup.isSuccess).put("settingsRestored", settingsRestore.getOrDefault(false))
                    .put("personalizationRestored", profileRestore.getOrDefault(false))
                val restored = activityClose.isSuccess && keyRestore.isSuccess && cleanup.isSuccess && settingsRestore.getOrDefault(false) && profileRestore.getOrDefault(false)
                if (!restored) {
                    report.put("status", "failed").put("restoreFailure", "Local restoration or owned-session cleanup did not complete")
                }
                save()
                if (!restored) {
                    val restorationFailure = IllegalStateException("Trial-owned sessions or original preferences were not restored")
                    listOfNotNull(activityClose.exceptionOrNull(), keyRestore.exceptionOrNull(), cleanup.exceptionOrNull(), settingsRestore.exceptionOrNull(), profileRestore.exceptionOrNull())
                        .forEach(restorationFailure::addSuppressed)
                    val originalFailure = primaryFailure
                    if (originalFailure != null) originalFailure.addSuppressed(restorationFailure) else throw restorationFailure
                }
            } else activityClose.exceptionOrNull()?.let { closeFailure ->
                val originalFailure = primaryFailure
                if (originalFailure != null) originalFailure.addSuppressed(closeFailure) else throw closeFailure
            }
        }
    }

    private fun verifyActualProgress(trial: Trial, expected: BaselineProgressSummary, attempts: Map<String, ReadingBaselineAttempt>) {
        openCommand("progress", "阅读成长")
        compose.waitUntil(15_000) { existsTag("baseline_progress_panel") && !existsTag("baseline_progress_loading") }
        assertFalse("Local progress must load without a repository error", existsTag("baseline_progress_error"))
        if (expected.points.isEmpty()) {
            scrollProgress("baseline_progress_empty").assertIsDisplayed()
            compose.onNodeWithTag("baseline_progress_trend").assertDoesNotExist()
        } else {
            scrollProgress("baseline_progress_counts").assertTextEquals("${expected.points.size} 份材料 · 合计答对 ${expected.correct} / ${expected.questions} 题")
            if (expected.points.size >= 2) scrollProgress("baseline_progress_trend").assertIsDisplayed()
            else compose.onNodeWithTag("baseline_progress_trend").assertDoesNotExist()
        }
        val excludedPractice = attempts.values.filter { it.resumedFromHistory || !it.localUnseen || !it.userConfirmedUnseen }
        assertTrue("Practice and continuation attempts cannot become independent measurements",
            expected.points.none { point -> excludedPractice.any { it.id == point.attemptId } })
        excludedPractice.forEach { compose.onNodeWithTag("baseline_progress_sample_${it.id}").assertDoesNotExist() }
        screenshot(trial, "01-real-baseline-progress.png")
        if (expected.exclusions.isNotEmpty()) {
            scrollProgress("baseline_progress_exclusions_toggle").performClick()
            expected.exclusions.forEach { (reason, count) ->
                scrollProgress("baseline_exclusion_${reason.name}").assertTextContains(" · $count 条", substring = true)
            }
            screenshot(trial, "01b-real-excluded-records.png")
        }
        trial.report.put("qualifiedBaselineMaterials", expected.materialCount).put("qualifiedCorrect", expected.correct)
            .put("qualifiedQuestions", expected.questions).put("practiceExcluded", true)
            .put("baselineExclusions", JSONObject(expected.exclusions.mapKeys { it.key.name }))
        step(trial, "Opened progress from the command palette and compared its counts with the local frozen-record calculator")
    }

    private fun optionalLiveStudy(trial: Trial, requested: Boolean): ArticleStudy? {
        val repository = trial.repository
        val reading = requireNotNull(repository.reading)
        val candidate = trial.candidate
        val before = runBlocking { reading.cachedStudy(candidate.article.id, candidate.body.contentHash) }
        if (!requested) {
            trial.report.put("liveStudyStatus", "not_requested")
            trial.report.getJSONArray("untestedPaths").put("Live DeepSeek study generation was not requested")
            return before
        }
        if (!candidate.aiAllowed || !runBlocking { repository.hasApiKey() }) {
            trial.report.put("liveStudyStatus", if (!candidate.aiAllowed) "source_not_enabled" else "no_key")
            trial.report.getJSONArray("untestedPaths").put("Live study was unavailable; no API action was invoked")
            return before
        }
        val generationTag = when {
            before == null -> "reader_generate_study"
            before.questions.any { it.evidence.isEmpty() } -> "skim_upgrade_evidence"
            else -> null
        }
        if (generationTag == null) {
            trial.report.put("liveStudyStatus", "existing_precise_cache")
            trial.report.getJSONArray("untestedPaths").put("The current article already had precise evidence; no refresh control was available")
            return before
        }
        compose.waitUntil(10_000) { existsTag(generationTag) }
        compose.onNodeWithTag(generationTag).performScrollTo().assertIsEnabled().performClick()
        trial.report.put("aiActionInvocations", 1).put("liveStudyStatus", "waiting")
        step(trial, "Invoked one visible generate or update command; no retry will be issued")
        var result: ArticleStudy? = null
        var nextRead = 0L
        val outcome = runCatching {
            compose.waitUntil(240_000) {
                val now = SystemClock.elapsedRealtime()
                if (now < nextRead) false else {
                    nextRead = now + 500
                    val cached = runBlocking { reading.cachedStudy(candidate.article.id, candidate.body.contentHash) }
                    if (cached != null && (before == null || cached != before)) result = cached
                    result != null || existsTag("reader_assist_error")
                }
            }
        }
        if (outcome.isFailure) {
            trial.report.put("liveStudyStatus", "timeout")
            trial.report.getJSONArray("untestedPaths").put("The single live generation did not reach a confirmed result within 240 seconds")
            screenshot(trial, "04-live-study-timeout.png")
            return before
        }
        if (result == null) {
            trial.report.put("liveStudyStatus", "failed")
            trial.report.getJSONArray("untestedPaths").put("The requested study returned an error; the error text and API payload are not included")
            screenshot(trial, "04-live-study-error.png")
            return before
        }
        val study = requireNotNull(result)
        val count = usableQuestions(candidate.body, study).size
        trial.report.put("liveStudyStatus", if (count >= 2) "succeeded" else "insufficient_evidence")
            .put("studySchemaVersionAfter", study.schemaVersion).put("exactEvidenceQuestionCount", count)
            .put("newCachedStudyObserved", true)
        screenshot(trial, "04-updated-reading-evidence.png")
        step(trial, "Observed the new cached study for the same original body without recording its content")
        return study
    }

    private fun verifyQuestionEvidence(trial: Trial, study: ArticleStudy, pace: Int) {
        val selected = usableQuestions(trial.candidate.body, study).take(2)
        compose.onNodeWithTag("skim_budget_10").performScrollTo().performClick()
        compose.onNodeWithTag("skim_role_全部").performScrollTo().performClick()
        selected.forEach { question -> compose.onNodeWithTag("skim_question_${question.id}").performScrollTo().assertIsNotSelected().performClick() }
        selected.forEach { question -> compose.onNodeWithTag("skim_question_${question.id}").performScrollTo().assertIsSelected() }
        val plan = ReadingSkimPlanner.plan(trial.candidate.body.text, 10, study, selectedQuestions = selected, planningWpm = pace)
        compose.onNodeWithTag("skim_question_coverage").performScrollTo()
            .assertTextEquals("已纳入证据 ${plan.coveredQuestionIds.size} / 2 个问题")
        screenshot(trial, "05-two-selected-questions.png")
        val question = selected.first { q -> plan.sentenceRanges.any { q.id in it.questionIds } }
        val range = plan.sentenceRanges.first { question.id in it.questionIds }
        compose.onNodeWithTag("skim_answer_${question.id}_0").performScrollTo().performClick()
        compose.waitUntil(10_000) { !existsTag("skim_question_coverage") && existsTag("zen_article_text") }
        compose.waitUntil(10_000) { offsetVisible(range.start) }
        assertTrue("Evidence navigation must use the unchanged original body", editorText() == trial.candidate.body.text)
        screenshot(trial, "06-exact-original-evidence.png")
        val wordOffset = (range.start until range.endExclusive).first { trial.candidate.body.text[it].isLetterOrDigit() }
        tapOffset(wordOffset)
        compose.waitUntil(10_000) { existsTag("skim_preview_question_${question.id}") }
        val linked = study.questions.filter { it.id in range.questionIds }
        linked.forEach { compose.onNodeWithTag("skim_preview_question_${it.id}").performScrollTo().assertTextEquals(it.question) }
        screenshot(trial, "07-question-back-reference.png")
        compose.onNodeWithTag("skim_preview_close").performScrollTo().performClick()
        compose.waitUntil(10_000) { !existsTag("skim_preview_close") }
        assertTrue("Closing the evidence preview must preserve the original text", editorText() == trial.candidate.body.text)
        assertTrue("Closing the preview must preserve the evidence position", offsetVisible(wordOffset))
        screenshot(trial, "08-back-to-original.png")
        trial.report.put("multiQuestionEvidenceVerified", true).put("selectedQuestionCount", 2)
            .put("coveredQuestionCount", plan.coveredQuestionIds.size).put("preciseOriginalAnchorVerified", true)
            .put("tapBackReferenceVerified", true).put("continuedOriginalWithoutCapture", true)
        step(trial, "Selected two questions, jumped to an exact original sentence, tapped its question reference, and continued reading")
    }

    private fun usableQuestions(body: ArticleBody, study: ArticleStudy): List<ReadingQuestion> = study.questions.filter { question ->
        question.evidence.isNotEmpty() && ReadingSkimPlanner.plan(body.text, 10, study,
            selectedQuestions = listOf(question), planningWpm = 400).questionCoverage.singleOrNull()?.status == SkimQuestionStatus.COVERED
    }

    private fun openCachedArticle(candidate: Candidate) {
        compose.onNodeWithTag("daily_reading").performScrollToNode(hasText("读过的文章"))
        compose.onNodeWithText("读过的文章").performClick()
        compose.waitUntil(10_000) { existsTag("article_history_list") }
        compose.onNodeWithTag("article_history_list").performScrollToNode(hasText(candidate.article.title))
        compose.onNodeWithText(candidate.article.title).performClick()
        compose.waitUntil(15_000) { existsTag("zen_article_text") }
        assertTrue("The reader must display the cached original", editorText() == candidate.body.text)
    }

    private fun openSkimTools() {
        val label = if (existsText("略读 · 工具")) "略读 · 工具" else "精读 · 工具"
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.onNodeWithTag("reader_tab_略读").reachReadingTool().performClick()
        compose.waitUntil(10_000) { existsTag("skim_planning_pace") }
    }

    private fun openCommand(query: String, label: String) {
        compose.onNodeWithText(">_  命令").performClick()
        compose.onNode(hasSetTextAction()).performTextInput(query)
        compose.onNodeWithText(label).performClick()
    }
    private fun scrollProgress(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("progress_screen").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun pressBack() = InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    private fun existsTag(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun existsText(value: String) = compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
    private fun editorText() = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun offsetVisible(offset: Int): Boolean {
        val field = compose.onNodeWithTag("zen_article_text")
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val bounds = layouts.single().getBoundingBox(offset)
        val origin = field.fetchSemanticsNode().positionInRoot
        val viewport = compose.onNodeWithTag("zen_reader_viewport").fetchSemanticsNode().boundsInRoot
        return bounds.top + origin.y >= viewport.top && bounds.bottom + origin.y <= viewport.bottom
    }
    private fun tapOffset(offset: Int) {
        val field = compose.onNodeWithTag("zen_article_text")
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val center = layouts.single().getBoundingBox(offset).center
        val node = field.fetchSemanticsNode()
        // Test touch coordinates start at the clipped visible bounds; TextLayout uses the full field.
        val visibleLocal = center + node.positionInRoot - node.boundsInRoot.topLeft
        field.performTouchInput { advanceEventTime(600); click(visibleLocal) }
    }
    private fun step(trial: Trial, description: String) {
        trial.report.getJSONArray("operations").put(description)
        File(trial.output, "device-trial.json").writeText(trial.report.toString(2))
    }
    private fun screenshot(trial: Trial, name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync(); SystemClock.sleep(150)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(trial.output, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        trial.report.getJSONArray("screenshots").put(name)
        File(trial.output, "device-trial.json").writeText(trial.report.toString(2))
    }

    private fun candidates(database: File, articles: List<Article>, sources: List<Source>, reading: ReadingRepository): List<Candidate> {
        val excluded = Regex("(?i)fixture|synthetic|self-authored|trial|example\\.(org|com)|test-source|测试")
        val nativeSources = sources.filter { it.readingMode == ReadingMode.NATIVE &&
            !excluded.containsMatchIn(it.id + " " + it.name + " " + Uri.parse(it.feedUrl).host.orEmpty()) }.associateBy { it.id }
        return SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            articles.mapNotNull { article ->
                val uri = Uri.parse(article.url)
                if (!article.read || article.readingMode != ReadingMode.NATIVE || article.sourceIds.none { it in nativeSources } ||
                    uri.scheme != "https" || uri.host != "theconversation.com" || excluded.containsMatchIn(article.id + " " + article.title + " " + article.sourceName)) return@mapNotNull null
                val body = db.rawQuery("SELECT data FROM article_bodies WHERE articleId=? AND expiresAt>?",
                    arrayOf(article.id, (System.currentTimeMillis() + 600_000).toString())).use { cursor ->
                    if (cursor.moveToFirst()) runCatching { codec.decodeFromString<ArticleBody>(cursor.getString(0)) }.getOrNull() else null
                } ?: return@mapNotNull null
                if (body.articleId != article.id || body.text.length !in 400..24_000 || body.contentHash != fingerprint(body.text) ||
                    ParagraphFocus.paragraphs(body.text).size !in 2..80) return@mapNotNull null
                Candidate(article, body, runBlocking { reading.cachedStudy(article.id, body.contentHash) }, article.sourceIds.any { nativeSources[it]?.aiAllowed == true })
            }
        }
    }

    private fun <T> assertPreserved(label: String, before: Map<String, T>, after: Map<String, T>) {
        assertTrue("$label must remain identical", before == after)
    }

    /** Byte access also preserves malformed legacy profile data without printing or decoding it. */
    private fun readRawPreference(database: File, key: String): ByteArray? =
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT CAST(data AS BLOB) FROM preferences WHERE `key`=?", arrayOf(key))
                .use { if (it.moveToFirst()) it.getBlob(0) else null }
        }
    private fun restoreRawPreference(database: File, key: String, original: ByteArray?) {
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            if (original == null) db.compileStatement("DELETE FROM preferences WHERE `key`=?").use {
                it.bindString(1, key); it.executeUpdateDelete()
            } else db.compileStatement("INSERT OR REPLACE INTO preferences (`key`, data) VALUES (?, CAST(? AS TEXT))").use {
                it.bindString(1, key); it.bindBlob(2, original); check(it.executeInsert() != -1L)
            }
        }
    }
    private fun sameBytes(left: ByteArray?, right: ByteArray?) = if (left == null) right == null else right != null && left.contentEquals(right)
    private fun sameRows(left: Map<String, ByteArray>, right: Map<String, ByteArray>) =
        left.keys == right.keys && left.all { (key, bytes) -> sameBytes(bytes, right[key]) }

    private fun sessionRows(database: File): Map<String, ByteArray> =
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT `key`, CAST(data AS BLOB) FROM preferences WHERE `key` LIKE 'reading-library:session:%'", emptyArray<String>()).use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getBlob(1)) }
            }
        }

    /** Closing the Activity cancels its recorder; wait for its final in-flight write before diffing. */
    private fun awaitSessionWrites(database: File) = runBlocking {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var previous = sessionRows(database)
        var unchangedSince = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(150)
            val current = sessionRows(database)
            if (!sameRows(previous, current)) { previous = current; unchangedSince = SystemClock.elapsedRealtime() }
            if (SystemClock.elapsedRealtime() - unchangedSince >= 900) return@runBlocking
        }
        error("Reading session writes did not settle after the Activity closed")
    }

    private fun cleanupOwnSessions(database: File, before: Map<String, ByteArray>, articleId: String,
        startedAt: Long, closedAt: Long, report: JSONObject) {
        val after = sessionRows(database)
        check(before.all { (key, value) -> sameBytes(value, after[key]) }) { "An existing reading session changed" }
        val newRows = after.filterKeys { it !in before }
        val owned = newRows.map { (key, bytes) ->
            val session = codec.decodeFromString<ReadingSession>(bytes.toString(Charsets.UTF_8))
            check(key == "reading-library:session:${session.id}" && session.articleId == articleId &&
                session.startedAt in startedAt..closedAt && session.finishedAt <= closedAt + 1_000 && !session.completed) {
                "An unexpected new reading session is not owned by this trial; it was not deleted"
            }
            key to session.id
        }
        report.put("trialOwnedSessionIds", JSONArray(owned.map { it.second }))
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                owned.forEach { (key, id) ->
                    // Verify the exact post-close bytes before deleting only the confirmed new row.
                    val actual = db.rawQuery("SELECT CAST(data AS BLOB) FROM preferences WHERE `key`=?", arrayOf(key))
                        .use { if (it.moveToFirst()) it.getBlob(0) else null }
                    check(sameBytes(newRows[key], actual)) { "A trial session changed before cleanup" }
                    check(db.delete("preferences", "`key`=?", arrayOf(key)) == 1)
                    db.delete("local_events", "id=?", arrayOf("reading-session:$id"))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        check(sameRows(before, sessionRows(database))) { "Prior reading sessions were not preserved exactly" }
        report.put("removedTrialSessionCount", owned.size).put("priorReadingSessionsExactlyPreserved", true)
    }
}
