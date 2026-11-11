package dev.lexicon.ide.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.data.LexiconDatabase
import dev.lexicon.data.LocalSpeedReadingRepository
import dev.lexicon.data.SpeedPlanGenerator
import dev.lexicon.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end local fixture: production UI, ViewModel, pacing, cache and Room persistence.
 * The authored article and controlled AI generator are test inputs, not online quality evidence.
 * Playback uses the real monotonic clock; screenshot results are explicitly labelled fixtures.
 */
@RunWith(AndroidJUnit4::class)
class SpeedReadingFlowTest {
    @get:Rule val compose = createComposeRule()
    private val databases = mutableListOf<LexiconDatabase>()
    private val controllers = mutableListOf<SpeedReadingViewModel>()
    private val firstParagraph = "When a neighbourhood opens a public library, residents gain a shared place to learn. " +
        "The building offers quiet desks, reliable internet access, and books that families can borrow without buying them. " +
        "These resources are especially useful to students who cannot find a peaceful place to study at home. " +
        "However, a useful library needs more than shelves and computers. Staff must listen to local people, " +
        "choose practical opening hours, and help new readers find information that they can trust."
    private val secondParagraph = "The council will evaluate the project by tracking who uses the library and asking whether its services meet local needs. " +
        "A high visitor count alone cannot show whether the building has made learning easier. " +
        "Short interviews may reveal that evening opening hours help working parents, while clear signs make new visitors feel welcome. " +
        "The results will guide small changes before the council considers a larger expansion."
    private val text = "$firstParagraph\n\n$secondParagraph"
    private val article = Article("speed-flow-fixture", "self-authored", listOf("fixture"),
        "A library shaped by local needs · test fixture", "https://example.org/speed-flow-fixture", "",
        readingMode = ReadingMode.NATIVE, sourceName = "Self-authored test fixture")
    private val body = ArticleBody(article.id, text, fingerprint(text), 1, Long.MAX_VALUE, "Self-authored test fixture")

    private class FixtureGenerator : SpeedPlanGenerator {
        val calls = AtomicInteger()
        val release = CompletableDeferred<Unit>()

        override suspend fun refine(article: Article, body: ArticleBody, proposal: SpeedPlan): SpeedPlan {
            calls.incrementAndGet()
            release.await()
            val paragraphs = ParagraphFocus.paragraphs(body.text)
            return proposal.copy(origin = "ai", model = "fixture-only/no-network", questions = proposal.paragraphIndices.map { index ->
                val range = paragraphs[index]
                val end = (body.text.indexOf('.', range.start) + 1).coerceIn(range.start + 1, range.endExclusive)
                if (index == 0) SpeedQuestion("fixture-p0", index, "What is the main purpose of the new library?",
                    listOf("To provide shared resources for learning", "To sell computers to families", "To replace local schools"),
                    0, range.start, end, "The opening sentence describes a shared place for residents to learn.")
                else SpeedQuestion("fixture-p1", index, "How will the council assess the project?",
                    listOf("Only by counting the books", "By examining use and whether services meet local needs", "Only by measuring the building"),
                    1, range.start, end, "The council combines usage information with questions about local needs.")
            })
        }
    }

    private fun database(): LexiconDatabase = Room.inMemoryDatabaseBuilder<LexiconDatabase>(
        ApplicationProvider.getApplicationContext<Context>()).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).build().also(databases::add)

    private fun newRepository(db: LexiconDatabase, generator: FixtureGenerator) =
        LocalSpeedReadingRepository(db, generator, hasKey = { true }, sourceAllowed = { true })

    /** Fixture-only IO timing; no article or credentials enter these diagnostics. */
    private class ReadProbe(private val delegate: SpeedReadingRepository) : SpeedReadingRepository by delegate {
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        private suspend fun <T> read(name: String, block: suspend () -> T): T {
            val start = SystemClock.elapsedRealtime()
            events += "$name:entered@$start"
            return try { block().also { events += "$name:returned+${SystemClock.elapsedRealtime() - start}ms" } }
            catch (failure: Throwable) {
                events += "$name:${failure.javaClass.simpleName}+${SystemClock.elapsedRealtime() - start}ms"
                throw failure
            }
        }
        override suspend fun cachedPlan(planKey: String): SpeedPlan? = read("plan") { delegate.cachedPlan(planKey) }
        override suspend fun sentenceForEditing(planKey: String, chunkIndex: Int): SpeedSegmentation? =
            read("sentence") { delegate.sentenceForEditing(planKey, chunkIndex) }
    }

    private fun controller(repo: SpeedReadingRepository, saved: SavedStateHandle = SavedStateHandle()) =
        SpeedReadingViewModel(repo, saved).also(controllers::add)

    @After fun releaseIsolatedResources() {
        compose.runOnIdle {
            controllers.forEach { it.pause(); it.viewModelScope.cancel() }
        }
        databases.forEach { it.close() }
    }

    @Test fun fixtureFlowSavesOneRunAndExactCorrectionThenShowsHistory() {
        val db = database()
        val generator = FixtureGenerator()
        val repository = newRepository(db, generator)
        val vm = controller(repository)
        var settings by mutableStateOf(AppSettings(theme = ThemeMode.LIGHT))
        var history by mutableStateOf(false)
        compose.setContent { FixtureWorkspace(vm, settings, history) }
        bind(vm)

        compose.onNodeWithText("2 段").performScrollTo().performClick()
        compose.onNodeWithText("L3 · 挑战").performScrollTo().performClick()
        compose.runOnIdle { vm.setSpeed(180) }
        compose.onNodeWithTag("speed_prepare_ai").performScrollTo().performClick()
        compose.waitUntil(5_000) { generator.calls.get() == 1 && vm.state.value.loading }
        compose.onNodeWithTag("speed_prepare_ai").assertIsNotEnabled()
        generator.release.complete(Unit)
        awaitStage(vm, SpeedStage.READING)
        val prepared = requireNotNull(vm.state.value.plan)
        assertEquals(listOf(0, 1), prepared.paragraphIndices)
        assertEquals(2, prepared.questions.size)
        assertEquals("fixture-only/no-network", prepared.model)
        assertFalse(vm.state.value.playing)
        compose.onNodeWithTag("speed_original_text").assertTextEquals(firstParagraph)
        saveFixtureScreenshot("speed-fixture-daylight.png", "LIGHT / Claude Code palette")
        compose.runOnIdle { settings = settings.copy(theme = ThemeMode.DARK) }
        compose.waitForIdle()
        saveFixtureScreenshot("speed-fixture-dracula.png", "DARK / Dracula palette")
        compose.runOnIdle { settings = settings.copy(theme = ThemeMode.LIGHT) }

        compose.onNodeWithTag("speed_play_pause").performClick()
        compose.waitUntil(5_000) { vm.state.value.chunkIndex > 0 }
        compose.onNodeWithTag("speed_play_pause").performClick()
        val pausedIndex = vm.state.value.chunkIndex
        assertFalse(vm.state.value.playing)
        assertTrue(pausedIndex > 0)
        compose.onNodeWithTag("speed_repeat").performClick()
        assertEquals(pausedIndex, vm.state.value.chunkIndex)
        compose.onNodeWithTag("speed_previous").performClick()
        assertEquals(pausedIndex - 1, vm.state.value.chunkIndex)
        val correctedChunk = prepared.chunks[vm.state.value.chunkIndex]
        compose.onNodeWithTag("speed_tools").performClick()
        compose.onNodeWithText("切分纠错").performScrollTo().performClick()
        compose.onNodeWithText("这一块过长").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("测试反馈：检查这个意群的长度")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5_000) { vm.state.value.feedbackSaved }

        compose.onNodeWithTag("speed_play_pause").performClick()
        awaitStage(vm, SpeedStage.QUESTION, 25_000)
        assertFalse(vm.state.value.playing)
        compose.onNodeWithTag("speed_answer_explanation").assertDoesNotExist()
        compose.onNodeWithTag("speed_answer_0").performClick()
        compose.onNodeWithTag("speed_answer_1").assertIsNotEnabled()
        compose.runOnIdle { vm.answer(1) }
        assertEquals(0, vm.state.value.selectedAnswer)
        assertEquals(1, vm.state.value.answers.size)
        compose.onNodeWithTag("speed_continue").performScrollTo().performClick()
        awaitStage(vm, SpeedStage.READING)
        compose.onNodeWithTag("speed_play_pause").performClick()
        awaitStage(vm, SpeedStage.QUESTION, 25_000)
        compose.onNodeWithTag("speed_answer_1").performClick()
        compose.onNodeWithTag("speed_continue").performScrollTo().performClick()
        awaitStage(vm, SpeedStage.RESULT)
        compose.waitUntil(5_000) { !vm.state.value.saving }
        assertNull(vm.state.value.error)

        val library = runBlocking { repository.library.first() }
        val run = library.runs.single()
        val correction = library.corrections.single()
        assertEquals(run, vm.state.value.result)
        assertTrue(run.completed)
        assertTrue(run.firstAttempt)
        assertTrue(run.activeMillis > 0)
        assertTrue(run.groupMillis.all { it > 0 })
        assertEquals(prepared.chunks.size, run.groupsRead)
        assertEquals(2, run.answers.size)
        assertTrue(run.answers.all { it.correct })
        assertTrue(run.revisits >= 2)
        assertTrue(run.pauses >= 1)
        assertEquals(1.0, requireNotNull(SpeedMetrics.summarize(run).comprehension), .0)
        assertNotNull(SpeedMetrics.summarize(run).effectiveWordsPerMinute)
        assertEquals(correctedChunk.start, correction.start)
        assertEquals(correctedChunk.end, correction.end)
        assertEquals(prepared.planKey, correction.planKey)
        assertEquals("too_long", correction.kind)
        assertEquals("测试反馈：检查这个意群的长度", correction.comment)

        // Re-delivery of the same completion cannot produce a second run or event.
        runBlocking { repository.saveRun(run); repository.saveRun(run) }
        compose.runOnIdle { vm.continueAfterQuestion(); vm.continueAfterQuestion() }
        assertEquals(1, runBlocking { repository.library.first().runs.size })
        assertEquals(1, runBlocking { db.dao().allEvents().size })
        assertEquals(1, generator.calls.get())
        val freshRepository = newRepository(db, generator)
        assertEquals(prepared, runBlocking { freshRepository.cachedPlan(prepared.planKey) })
        assertEquals(library, runBlocking { freshRepository.library.first() })

        compose.runOnIdle { vm.leave { history = true } }
        compose.waitUntil(5_000) { history }
        compose.onNodeWithTag("speed_history").assertExists()
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        compose.onNodeWithTag("speed_history").performScrollToNode(hasText("切分反馈"))
        compose.onNodeWithText("切分反馈").performClick()
        compose.onNodeWithTag("speed_history").performScrollToNode(hasText(correction.comment))
        compose.onNodeWithText(correction.comment).assertIsDisplayed()
        compose.onNodeWithText("切分准确率：待人工复核").assertExists()
    }

    @Test fun backgroundAndSavedStateRestoreKeepPausedCursorAndLockedAnswer() {
        val db = database()
        val generator = FixtureGenerator().apply { release.complete(Unit) }
        val repository = newRepository(db, generator)
        var saved = SavedStateHandle()
        var current by mutableStateOf(controller(repository, saved))
        compose.setContent { FixtureWorkspace(current, AppSettings(theme = ThemeMode.LIGHT), false) }
        bind(current)
        compose.runOnIdle { current.setSpeed(180) }
        compose.onNodeWithTag("speed_prepare_ai").performScrollTo().performClick()
        awaitStage(current, SpeedStage.READING)
        compose.onNodeWithTag("speed_play_pause").performClick()
        compose.waitUntil(5_000) { current.state.value.chunkIndex > 0 }
        compose.runOnIdle { current.setForeground(false) }
        val cursor = current.state.value.chunkIndex
        val stoppedAt = SystemClock.elapsedRealtime()
        compose.waitUntil(3_000) { SystemClock.elapsedRealtime() - stoppedAt >= 900 }
        assertEquals(cursor, current.state.value.chunkIndex)
        assertFalse(current.state.value.playing)
        compose.runOnIdle { current.setForeground(true) }
        assertFalse(current.state.value.playing)

        // Recreate the controller from a copied saved-state payload and the real cached plan.
        // This checks the restore contract; it does not claim Android killed this process.
        compose.runOnIdle {
            val restored = copyHandle(saved)
            current.viewModelScope.cancel()
            saved = restored
            current = controller(repository, saved)
            current.bind(article, body, true, true, 1, 0)
        }
        awaitStage(current, SpeedStage.READING)
        assertEquals(cursor, current.state.value.chunkIndex)
        assertFalse(current.state.value.playing)
        assertEquals(1, generator.calls.get())
        compose.runOnIdle {
            repeat(requireNotNull(current.state.value.plan).chunks.size) { current.next() }
        }
        awaitStage(current, SpeedStage.QUESTION)
        compose.onNodeWithTag("speed_answer_1").performClick()
        compose.runOnIdle {
            val restored = copyHandle(saved)
            current.viewModelScope.cancel()
            saved = restored
            current = controller(repository, saved)
            current.bind(article, body, true, true, 1, 0)
        }
        awaitStage(current, SpeedStage.QUESTION)
        assertEquals(1, current.state.value.selectedAnswer)
        assertTrue(current.state.value.answerRevealed)
        compose.onNodeWithTag("speed_answer_0").assertIsNotEnabled()
        compose.runOnIdle { current.answer(0) }
        assertEquals(1, current.state.value.answers.single().selectedIndex)
        assertFalse(current.state.value.answers.single().correct)
        assertEquals(1, generator.calls.get())
        var left = false
        compose.runOnIdle { current.leave { left = true } }
        compose.waitUntil(5_000) { left }
        val partial = runBlocking { repository.library.first().runs.single() }
        assertFalse(partial.completed)
        assertNull(SpeedMetrics.summarize(partial).comprehension)
        assertNull(SpeedMetrics.summarize(partial).effectiveWordsPerMinute)
    }

    @Test fun personalSentenceEditReopensFromRoomAndCanBeRestoredWithoutAi() {
        val db = database()
        val generator = FixtureGenerator()
        val repository = newRepository(db, generator)
        var readProbe = ReadProbe(repository)
        var vm by mutableStateOf(controller(readProbe))
        var history by mutableStateOf(false)
        compose.setContent { FixtureWorkspace(vm, AppSettings(theme = ThemeMode.LIGHT), history) }
        bind(vm)
        compose.onNodeWithTag("speed_prepare_offline").performScrollTo().performClick()
        awaitStage(vm, SpeedStage.READING)
        val original = requireNotNull(vm.state.value.plan)
        compose.onNodeWithTag("speed_tools").performClick()
        compose.onNodeWithTag("speed_edit_segmentation").performScrollTo().performClick()
        compose.waitUntil(5_000) { vm.state.value.segmentation != null }
        val draft = requireNotNull(vm.state.value.segmentation)
        assertTrue(draft.breaks.isNotEmpty())
        val boundary = draft.breaks.first()
        compose.onNodeWithTag("segmentation_boundary_$boundary").performScrollTo().performClick()
        compose.onNodeWithTag("segmentation_save").performClick()
        compose.waitUntil(5_000) { !vm.state.value.segmentationBusy && vm.state.value.segmentation == null }
        assertEquals(original, vm.state.value.plan)
        assertFalse(vm.state.value.playing)
        val correction = runBlocking { repository.library.first().corrections.single() }
        val personal = runBlocking { requireNotNull(repository.cachedPlan(correction.planKey)) }
        assertEquals("manual", personal.origin)
        assertEquals(original.planKey, personal.basePlanKey)
        assertEquals(original.chunks.size - 1, personal.chunks.size)

        // A new controller/repository uses the saved override, not a UI-only draft.
        compose.runOnIdle {
            vm.viewModelScope.cancel()
            readProbe = ReadProbe(newRepository(db, generator))
            vm = controller(readProbe)
            vm.bind(article, body, false, false, 2, 0)
        }
        compose.onNodeWithTag("speed_prepare_offline").performScrollTo().performClick()
        awaitStage(vm, SpeedStage.READING)
        assertEquals(personal, vm.state.value.plan)
        compose.onNodeWithTag("speed_tools").performClick()
        compose.onNodeWithText("${personal.granularity.name} · 个人切分").assertExists()
        compose.onNodeWithTag("speed_edit_segmentation").performScrollTo().performClick()
        compose.waitUntil(5_000) { vm.state.value.segmentation != null }
        compose.onNodeWithTag("segmentation_restore").performClick()
        compose.waitUntil(5_000) { !vm.state.value.segmentationBusy && vm.state.value.segmentation == null }
        assertEquals(personal, vm.state.value.plan)
        val restored = runBlocking { repository.prepare(article, body, original.granularity, 0, 1, false, false) }
        assertEquals(original, restored)
        assertEquals(personal, runBlocking { repository.cachedPlan(personal.planKey) })
        assertEquals(0, generator.calls.get())

        compose.runOnIdle { history = true }
        compose.onNodeWithText("切分反馈").performScrollTo().performClick()
        compose.onNodeWithText("个人切分已保存").assertExists()
        compose.onNodeWithTag("speed_inspect_correction_${correction.id}").performScrollTo().performClick()
        try {
            compose.waitUntil(5_000) { vm.correctionInspection.value?.sentence != null }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            val inspected = vm.correctionInspection.value
            throw AssertionError("Historical inspection: present=${inspected != null}, loading=${inspected?.loading}, " +
                "error=${inspected?.error}, scopeActive=${vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.isActive}, reads=${readProbe.events}", failure)
        }
        compose.onNodeWithTag("speed_correction_sentence").assertTextEquals(draft.text)
    }

    @Test fun threeRoundsKeepFrozenSourceAndSeparateNaturalHistoryWithoutMoreAiCalls() {
        val db = database()
        val generator = FixtureGenerator().also { it.release.complete(Unit) }
        val repository = newRepository(db, generator)
        val vm = controller(repository)
        var settings by mutableStateOf(AppSettings(theme = ThemeMode.LIGHT))
        var history by mutableStateOf(false)
        compose.setContent { FixtureWorkspace(vm, settings, history) }
        bind(vm)
        compose.runOnIdle { vm.setSpeed(180) }
        compose.onNodeWithTag("speed_prepare_ai").performScrollTo().performClick()
        awaitStage(vm, SpeedStage.READING)
        val plan = requireNotNull(vm.state.value.plan)
        compose.onNodeWithTag("speed_original_text").assertTextEquals(firstParagraph)
        saveFixtureScreenshot("rounds-guided-daylight.png", "GUIDED / LIGHT")
        compose.onNodeWithTag("speed_play_pause").performClick()
        awaitStage(vm, SpeedStage.QUESTION, 30_000)
        compose.onNodeWithTag("speed_answer_0").performClick()
        compose.onNodeWithTag("speed_continue").performScrollTo().performClick()
        compose.waitUntil(8_000) { vm.state.value.canContinueRound && !vm.state.value.saving }
        val first = requireNotNull(vm.state.value.result)
        compose.onNodeWithTag("speed_next_round").performScrollTo().performClick()
        awaitStage(vm, SpeedStage.READING)
        assertEquals(SpeedPacedRound.FADED, vm.state.value.round)
        assertFalse(vm.state.value.playing)
        assertEquals(plan, vm.state.value.plan)
        compose.runOnIdle { settings = settings.copy(theme = ThemeMode.DARK) }
        compose.onNodeWithTag("speed_original_text").assertTextEquals(firstParagraph)
        saveFixtureScreenshot("rounds-faded-dracula.png", "FADED / DARK")
        compose.onNodeWithTag("speed_play_pause").performClick()
        compose.waitUntil(30_000) { vm.state.value.canContinueRound && !vm.state.value.saving }
        val second = requireNotNull(vm.state.value.result)
        assertEquals(first.id, second.parentRunId)
        assertTrue(second.answers.isEmpty())
        compose.onNodeWithTag("speed_next_round").performScrollTo().performClick()
        compose.waitUntil(5_000) { vm.state.value.natural != null }
        compose.onNodeWithTag("natural_original_text").assertTextEquals(firstParagraph)
        compose.onNodeWithTag("speed_slider").assertDoesNotExist()
        assertFalse(vm.state.value.natural!!.playing)
        saveFixtureScreenshot("rounds-natural-dracula.png", "NATURAL / DARK / before self-timing")
        compose.onNodeWithTag("natural_play_pause").performClick()
        val began = SystemClock.elapsedRealtime()
        compose.waitUntil(3_000) { SystemClock.elapsedRealtime() - began >= 1000 }
        compose.onNodeWithTag("natural_play_pause").performClick()
        val pausedMillis = vm.state.value.natural!!.elapsedMillis
        assertTrue(pausedMillis >= 1000)
        compose.onNodeWithTag("natural_complete_paragraph").performClick()
        compose.waitUntil(8_000) { vm.state.value.natural?.let { it.result != null && !it.saving } == true }
        assertNull(vm.state.value.natural!!.error)
        val natural = requireNotNull(vm.state.value.natural!!.result)
        assertEquals(second.id, natural.parentRunId)
        assertEquals(plan.planKey, natural.planKey)
        assertEquals(pausedMillis, natural.paragraphs.sumOf { it.activeMillis })
        val library = runBlocking { repository.library.first() }
        assertEquals(listOf(first, second).toSet(), library.runs.toSet())
        assertEquals(listOf(natural), library.naturalAttempts)
        assertEquals(1, library.runs.sumOf { it.answers.size })
        assertEquals(1, generator.calls.get())
        saveFixtureScreenshot("rounds-natural-result.png", "Self-reported completion fixture; no ability claim")
        compose.runOnIdle { history = true }
        compose.onNodeWithText("自主阅读").performScrollTo().performClick()
        compose.onNodeWithTag("natural_history_${natural.id}").performScrollTo().assertExists()
    }

    @Composable private fun FixtureWorkspace(vm: SpeedReadingViewModel, settings: AppSettings, history: Boolean) {
        val state by vm.state.collectAsState()
        val library by vm.library.collectAsState()
        val inspection by vm.correctionInspection.collectAsState()
        LexiconTheme(settings) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Text("测试材料 · 模拟 AI · 非能力测量", Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.weight(1f)) {
                    if (history) SpeedHistoryScreen(library, listOf(article), {}, {}, onInspectCorrection = vm::inspectCorrection)
                    else SpeedReadingScreen(article, body, settings, state, SpeedUiActions(
                        onBack = { vm.leave() }, onPrepare = vm::prepare, onGranularity = vm::setGranularity,
                        onStartParagraph = vm::setStart, onParagraphCount = vm::setCount, onSpeed = vm::setSpeed,
                        onPlayPause = vm::togglePlay, onPrevious = vm::previous, onNext = vm::next,
                        onRepeat = vm::repeat, onSeek = vm::seek, onSentenceOnly = vm::setSentenceOnly,
                        onAnswer = vm::answer, onContinue = vm::continueAfterQuestion,
                        onRestart = vm::restart, onCorrection = vm::correction,
                        onEditSegmentation = vm::editSegmentation, onSaveSegmentation = vm::saveSegmentation,
                        onRestoreSegmentation = vm::restoreSegmentation, onCloseSegmentation = vm::closeSegmentation,
                        onNextRound = vm::nextRound, onNaturalPlayPause = vm::naturalPlayPause,
                        onNaturalCompleteParagraph = vm::naturalCompleteParagraph, onNaturalRetry = vm::naturalRetry,
                        onPauseForTools = { vm.pause(count = true) },
                    ))
                }
            }
            inspection?.let { SpeedCorrectionInspectionDialog(it, vm::closeCorrectionInspection) }
        }
    }

    private fun bind(vm: SpeedReadingViewModel) = compose.runOnIdle { vm.bind(article, body, true, true, 1, 0) }

    private fun awaitStage(vm: SpeedReadingViewModel, stage: SpeedStage, timeout: Long = 8_000) {
        compose.waitUntil(timeout) { (vm.state.value.stage == stage && !vm.state.value.loading) || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        assertEquals(stage, vm.state.value.stage)
    }

    private fun copyHandle(source: SavedStateHandle): SavedStateHandle = SavedStateHandle(source.keys().associateWith { key ->
        val value = source.get<Any?>(key)
        if (value is Bundle) Bundle(value) else value
    })

    private fun saveFixtureScreenshot(name: String, theme: String) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("speed-flow-fixtures").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        directory.resolve(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        directory.resolve("${name.removeSuffix(".png")}.txt").writeText(
            "Production Compose renderer; $theme.\nSelf-authored article with fixture-only/no-network generator.\n" +
                "Isolated in-memory Room database. No API Key or external API was used.\n" +
                "This screenshot is UI evidence, not online AI quality or reading ability evidence.\n")
    }
}
