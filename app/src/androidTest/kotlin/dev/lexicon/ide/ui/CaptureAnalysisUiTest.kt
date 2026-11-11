package dev.lexicon.ide.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureAnalysisUiTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<LexiconViewModel>()
    private val capture = CaptureAnalysisTestRepository.capture
    private val ready = CaptureAnalysisTestRepository.ready
    private fun model(repo: CaptureAnalysisTestRepository) = LexiconViewModel(repo).also { models += it }

    @After fun dispose() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { models.forEach { it.viewModelScope.cancel() } }
    }

    private fun panel(repo: CaptureAnalysisTestRepository, fontScale: Float = 1f, onCard: (Card) -> Unit = {},
        onExistingCard: ((Card) -> Unit)? = null): LexiconViewModel {
        val vm = model(repo)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                LexiconTheme(AppSettings(theme = ThemeMode.DARK)) {
                    CapturePanel(capture, emptyList(), emptyList(), vm, {}, onCard, {}, onOpenExistingCard = onExistingCard)
                }
            }
        }
        settle(repo, vm)
        return vm
    }

    private fun settle(repo: CaptureAnalysisTestRepository, vm: LexiconViewModel) {
        compose.waitUntil(5_000) { repo.probes.isNotEmpty() && !vm.analysisProbeLoading }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    @Test fun noKeyReadyAnalysisCanBeSavedWithoutGenerationOrCaptureOverwrite() {
        val repo = CaptureAnalysisTestRepository().apply { availableAction = { ready } }
        var result: Card? = null
        val vm = panel(repo, onCard = { result = it })
        assertFalse(vm.hasKey)
        compose.onNodeWithText("深度分析").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("generate_card").performScrollTo().assertTextContains("保存已有分析").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { result != null }
        compose.runOnIdle {
            assertEquals(listOf(ready.receiptId), repo.savedReceipts)
            assertTrue(CaptureAnalysisIdentity.sameInput(capture, requireNotNull(repo.savedInputs.singleOrNull())))
            assertEquals(capture.sentence, result?.sourceSentence)
            assertEquals(0, repo.analysisCalls); assertEquals(0, repo.captureSaves); assertEquals(0, repo.directCardSaves)
        }
    }

    @Test fun missingAnalysisWithoutKeyKeepsCaptureAndGenerationActionsDistinct() {
        val repo = CaptureAnalysisTestRepository(); panel(repo)
        compose.onNodeWithTag("generate_card").performScrollTo().assertTextContains("生成并保存生词卡").assertIsNotEnabled()
        compose.onNodeWithText("深度分析").assertIsNotEnabled()
        compose.onNodeWithTag("capture_save").assertTextContains("留待处理").assertIsEnabled()
        compose.onNodeWithText("配置 DeepSeek API Key").assertExists()
        assertEquals(0, repo.analysisCalls); assertTrue(repo.savedReceipts.isEmpty()); assertEquals(0, repo.captureSaves)
    }

    @Test fun existingCardOpensThroughTheCardCallbackWithoutMakingADuplicate() {
        val existing = CaptureAnalysisTestRepository.card(capture)
        val repo = CaptureAnalysisTestRepository().apply {
            keyConfigured = true
            availableAction = { CaptureAnalysisAvailability.ExistingCard(existing, matchesInput = false) }
        }
        var opened: Card? = null
        panel(repo, onCard = { fail("Opening an existing card must not report a new saved card") }, onExistingCard = { opened = it })
        compose.onNodeWithTag("generate_card").assertDoesNotExist()
        compose.onNodeWithText("深度分析").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("该捕获已有卡片，请核对其中的词条和语境").assertExists()
        compose.onNodeWithTag("capture_existing_card").performScrollTo().performClick()
        compose.waitUntil(5_000) { opened != null }
        assertEquals(existing, opened); assertEquals(0, repo.analysisCalls); assertEquals(0, repo.directCardSaves)
        assertTrue(repo.savedReceipts.isEmpty()); assertEquals(0, repo.captureSaves)
    }

    @Test fun changingInputHidesTheOldReadyReceiptWhileTheNewProbeIsPending() {
        val next = CompletableDeferred<CaptureAnalysisAvailability>()
        val repo = CaptureAnalysisTestRepository().apply {
            availableAction = { if (it.target == capture.target) ready else next.await() }
        }
        val vm = panel(repo)
        compose.onNodeWithTag("generate_card").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("capture_target").performScrollTo().performTextReplacement("robust method")
        compose.waitUntil(5_000) { repo.probes.any { it.target == "robust method" } }
        compose.onNodeWithTag("generate_card").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("preview_saved_analysis").assertDoesNotExist()
        compose.runOnIdle { next.complete(CaptureAnalysisAvailability.Missing) }
        compose.waitUntil { !vm.analysisProbeLoading }
        compose.onNodeWithTag("generate_card").assertIsNotEnabled()
        assertEquals(0, repo.analysisCalls); assertTrue(repo.savedReceipts.isEmpty())
    }

    @Test fun lateReadyFromOldInputCannotEnableSavingTheEditedInput() {
        val old = CompletableDeferred<CaptureAnalysisAvailability>()
        val repo = CaptureAnalysisTestRepository().apply {
            availableAction = { value -> if (value.target == capture.target) withContext(NonCancellable) { old.await() }
                else CaptureAnalysisAvailability.Missing }
        }
        val vm = model(repo)
        compose.setContent { LexiconTheme(AppSettings()) { CapturePanel(capture, emptyList(), emptyList(), vm, {}, {}, {}) } }
        compose.waitUntil { repo.probes.isNotEmpty() }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("capture_target").performScrollTo().performTextReplacement("changed target")
        compose.waitUntil { repo.probes.any { it.target == "changed target" } && !vm.analysisProbeLoading }
        compose.runOnIdle { old.complete(ready) }
        compose.waitForIdle()
        compose.onNodeWithTag("generate_card").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("preview_saved_analysis").assertDoesNotExist()
        assertEquals(0, repo.analysisCalls); assertTrue(repo.savedReceipts.isEmpty())
    }

    @Test fun changedInputWithKeyCanRequestFreshAnalysisInsteadOfSavingTheOldResult() {
        val repo = CaptureAnalysisTestRepository().apply {
            keyConfigured = true
            availableAction = { CaptureAnalysisAvailability.Conflict }
        }
        var saved: Card? = null
        val vm = panel(repo, onCard = { saved = it })
        compose.waitUntil { vm.hasKey }
        compose.onNodeWithText("内容已调整，需要重新分析；已有结果仍保留。").performScrollTo().assertExists()
        compose.onNodeWithText("深度分析").assertIsEnabled()
        compose.onNodeWithTag("generate_card").performScrollTo().assertTextContains("生成并保存生词卡").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { saved != null }
        assertEquals(1, repo.analysisCalls); assertEquals(listOf(false), repo.analysisModes)
        assertEquals(1, repo.directCardSaves); assertTrue(repo.savedReceipts.isEmpty())
    }

    @Test fun restrictedAnalysisCannotBeSavedOrRegeneratedEvenWithAKey() {
        val repo = CaptureAnalysisTestRepository().apply {
            keyConfigured = true
            availableAction = { CaptureAnalysisAvailability.Restricted }
        }
        val vm = panel(repo); compose.waitUntil { vm.hasKey }
        compose.onNodeWithTag("generate_card").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("深度分析").assertIsNotEnabled()
        assertEquals(0, repo.analysisCalls); assertEquals(0, repo.captureSaves); assertTrue(repo.savedReceipts.isEmpty())
    }

    @Test fun cachedSaveFailureKeepsTheOriginalDraftAndRetryUsesTheSameReceipt() {
        var attempts = 0
        val repo = CaptureAnalysisTestRepository().apply {
            availableAction = { ready }
            saveAvailableAction = { value, _ -> attempts++; if (attempts == 1) error("Synthetic local storage failure") else CaptureAnalysisTestRepository.card(value) }
        }
        var saved: Card? = null
        val vm = panel(repo, onCard = { saved = it })
        compose.onNodeWithTag("generate_card").performScrollTo().performClick()
        compose.waitUntil { vm.busy == null && vm.notice?.startsWith("[error]") == true }
        compose.onNodeWithTag("capture_target").performScrollTo().assertTextContains(capture.target)
        compose.onNodeWithTag("capture_sentence").performScrollTo().assertTextContains(capture.sentence)
        settle(repo, vm)
        compose.onNodeWithTag("generate_card").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { saved != null }
        assertEquals(listOf(ready.receiptId, ready.receiptId), repo.savedReceipts)
        assertTrue(repo.savedInputs.all { CaptureAnalysisIdentity.sameInput(capture, it) })
        assertEquals(0, repo.analysisCalls); assertEquals(0, repo.captureSaves)
    }

    @Test fun savedStateRestoreReprobesTheEditedDraftWithoutGeneration() {
        val repo = CaptureAnalysisTestRepository().apply {
            availableAction = { value -> if (value.target == capture.target) ready else CaptureAnalysisAvailability.Missing }
        }
        val vm = model(repo); val restoration = StateRestorationTester(compose)
        restoration.setContent { LexiconTheme(AppSettings()) { CapturePanel(capture, emptyList(), emptyList(), vm, {}, {}, {}) } }
        settle(repo, vm)
        compose.onNodeWithTag("capture_target").performScrollTo().performTextReplacement("robust method")
        compose.waitUntil(5_000) { repo.probes.any { it.target == "robust method" } && !vm.analysisProbeLoading }
        val before = repo.probes.size
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { repo.probes.size > before && !vm.analysisProbeLoading }
        compose.onNodeWithTag("capture_target").assertTextContains("robust method")
        assertEquals("robust method", repo.probes.last().target)
        assertTrue(CaptureAnalysisIdentity.sameInput(repo.probes[before - 1], repo.probes.last()))
        assertEquals(0, repo.analysisCalls); assertEquals(0, repo.captureSaves); assertTrue(repo.savedReceipts.isEmpty())
    }

    @Test fun noKeySavedAnalysisActionRemainsUsableOnDraculaAtTwoHundredPercent() {
        val repo = CaptureAnalysisTestRepository().apply { availableAction = { ready } }
        val systemScale = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale
        org.junit.Assume.assumeTrue("Dialog font trial requires system font scale 2.0", systemScale >= 1.99f)
        panel(repo)
        compose.onNodeWithTag("generate_card").performScrollTo().assertIsDisplayed().assertIsEnabled()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("保存已有分析", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "validation-1.9.0/capture-analysis-ui").apply { mkdirs() }
        directory.resolve("cached-analysis-dracula-font200.png").outputStream().use { output ->
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        File(directory, "layout.json").writeText(org.json.JSONObject().put("width", layouts.single().size.width)
            .put("height", layouts.single().size.height).put("overflowWidth", layouts.single().didOverflowWidth)
            .put("overflowHeight", layouts.single().didOverflowHeight).put("lineCount", layouts.single().lineCount)
            .put("actualFontScale", layouts.single().layoutInput.density.fontScale)
            .put("right", (0 until layouts.single().lineCount).maxOf { layouts.single().getLineRight(it) }).toString(2))
        val layout = layouts.single()
        assertTrue("The dialog must actually render at system 200%", layout.layoutInput.density.fontScale >= 1.99f)
        assertFalse("The save label cannot be clipped vertically", layout.didOverflowHeight)
        assertTrue("Rounded pixel bounds must contain every label line", (0 until layout.lineCount).all {
            layout.getLineLeft(it) >= -1f && layout.getLineRight(it) <= layout.size.width + 1f
        })
        assertEquals(0, repo.analysisCalls); assertEquals(0, repo.captureSaves); assertTrue(repo.savedReceipts.isEmpty())
    }
}

/** Reusable local fixture. No method accesses a network, device key store or production database. */
internal class CaptureAnalysisTestRepository : AppRepository {
    override val articles = MutableStateFlow<List<Article>>(emptyList())
    override val sources = MutableStateFlow<List<Source>>(emptyList())
    override val captures = MutableStateFlow<List<Capture>>(emptyList())
    override val cards = MutableStateFlow<List<Card>>(emptyList())
    override val settings = MutableStateFlow(AppSettings())
    override val status = MutableStateFlow("[ready]")
    override val jobs = MutableStateFlow<List<AiJob>>(emptyList())
    var keyConfigured = false
    var availableAction: suspend (Capture) -> CaptureAnalysisAvailability = { CaptureAnalysisAvailability.Missing }
    var saveAvailableAction: suspend (Capture, String) -> Card = { value, _ -> card(value) }
    val probes = mutableListOf<Capture>()
    val savedInputs = mutableListOf<Capture>()
    val savedReceipts = mutableListOf<String>()
    var captureSaves = 0
    var analysisCalls = 0
    val analysisModes = mutableListOf<Boolean>()
    var directCardSaves = 0
    override suspend fun availableCaptureAnalysis(capture: Capture): CaptureAnalysisAvailability { probes += capture; return availableAction(capture) }
    override suspend fun saveAvailableCaptureAnalysis(capture: Capture, expectedAnalysisId: String): Card {
        savedInputs += capture; savedReceipts += expectedAnalysisId
        return saveAvailableAction(capture, expectedAnalysisId)
    }
    override suspend fun initialize() = Unit
    override suspend fun sync() = SyncResult(0, 0, emptyList())
    override suspend fun setSourceEnabled(id: String, enabled: Boolean) = Unit
    override suspend fun openArticle(id: String): ArticleBody? = null
    override suspend fun markArticleRead(id: String) = Unit
    override suspend fun classifyPending() = Unit
    override suspend fun saveCapture(capture: Capture) { captureSaves++; captures.value = listOf(capture) }
    override suspend fun lookup(text: String) = emptyList<DictionaryEntry>()
    override suspend fun updateSettings(settings: AppSettings) { this.settings.value = settings }
    override suspend fun hasApiKey() = keyConfigured
    override suspend fun setApiKey(key: String) { keyConfigured = true }
    override suspend fun clearApiKey() { keyConfigured = false }
    override suspend fun dictionaryStatus() = DictionaryStatus(ready = true, name = "Local fixture", entryCount = 1)
    override suspend fun importDictionary(file: File) = dictionaryStatus()
    override suspend fun analyzeCapture(capture: Capture, deep: Boolean): AnalysisResult { analysisCalls++; analysisModes += deep; return ready.analysis }
    override suspend fun saveCard(capture: Capture, analysis: AnalysisResult): Card { directCardSaves++; return card(capture).copy(content = analysis.content) }
    override suspend fun updateCard(card: Card) = Unit
    override suspend fun reviewCard(cardId: String, rating: Rating, expectedReps: Int, now: Long) = ReviewOutcome.APPLIED
    override suspend fun exportAnki(destination: File) = destination
    override suspend fun confirmAnkiExport(exportFile: File) = Unit
    override suspend fun exportEvents(destination: File) = destination
    override suspend fun recordEvent(type: String, value: Int) = Unit

    companion object {
        val capture = Capture("cached-capture", target = "robust", selectedText = "robust",
            sentence = "A robust method handles changing conditions.", contextOrigin = ContextOrigin.ORIGINAL,
            sourceRef = SourceRef("Methods", "Example"), selectionStart = 2, selectionEnd = 8, bodyHash = "body-fixture", createdAt = 1)
        val ready = CaptureAnalysisAvailability.Ready(AnalysisResult(VocabularyContent("robust",
            "Remaining reliable when conditions change.",
            listOf(Collocation("robust method", "A robust method handles changes."), Collocation("robust evidence", "The study provides robust evidence.")),
            "reliable under varying conditions", "neutral", "high"), "fixture"), "analysis-receipt")
        fun card(capture: Capture) = Card("saved-card", capture.id, ready.analysis.content, capture.sentence,
            capture.sourceRef, createdAt = 1, contextOrigin = capture.contextOrigin)
    }
}
