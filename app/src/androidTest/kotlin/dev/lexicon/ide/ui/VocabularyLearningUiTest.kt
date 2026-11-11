package dev.lexicon.ide.ui

import android.graphics.Bitmap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VocabularyLearningUiTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<ViewModel>()
    private val f = VocabularyUiFixture
    private fun model(repo: VocabularyUiRepository) = VocabularyLearningViewModel(repo).also { models += it }
    @After fun dispose() { InstrumentationRegistry.getInstrumentation().runOnMainSync { models.forEach { it.viewModelScope.cancel() } } }

    private fun panel(repo: VocabularyUiRepository, captures: List<Capture> = emptyList(), cards: List<Card> = emptyList(),
        onLookup: (Capture) -> Unit = {}, fontScale: Float = 1f, onOpenCard: ((String) -> Unit)? = null): VocabularyLearningViewModel {
        val vm = model(repo)
        compose.setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                LexiconTheme(AppSettings(theme = ThemeMode.DARK)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        VocabularyHighlightsPanel(f.article, f.body, f.study, f.candidates, cards, captures, vm, state, onOpenCard = onOpenCard, onLookup = onLookup)
                    }
                }
            }
        }
        compose.waitUntil { !vm.state.value.loading }
        return vm
    }
    private fun select(index: Int) = compose.onNodeWithTag("vocabulary_select_${f.candidates[index].id}").performScrollTo().performClick()

    @Test fun selectionRequiresReviewableOriginalSentenceConfirmationAndCancelMakesNoWrites() {
        val repo = VocabularyUiRepository(); panel(repo)
        compose.onNodeWithTag("vocabulary_prepare").performScrollTo().assertIsNotEnabled()
        select(0); select(1)
        compose.onNodeWithTag("vocabulary_prepare").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("vocabulary_confirmation").assertExists()
        compose.onAllNodesWithText(f.candidates.first().capture.sentence).assertCountEquals(2)
        compose.onNodeWithTag("vocabulary_cancel").performClick()
        compose.onNodeWithTag("vocabulary_confirmation").assertDoesNotExist()
        assertEquals(0, repo.prepares); assertTrue(repo.runs.isEmpty())
    }

    @Test fun confirmedSelectionStartsOneQueueAndDoesNotRequireTheSheetToStayOpen() {
        val repo = VocabularyUiRepository(); val gate = CompletableDeferred<Unit>(); repo.runAction = { gate.await() }
        val vm = panel(repo)
        select(0); compose.onNodeWithTag("vocabulary_prepare").performScrollTo().performClick()
        compose.onNodeWithTag("vocabulary_confirm").performClick()
        compose.waitUntil { repo.prepares == 1 && repo.runs.size == 1 }
        compose.onNodeWithTag("vocabulary_confirmation").assertDoesNotExist()
        assertTrue(vm.busy)
        compose.onNodeWithTag("vocabulary_pause_fixture-batch").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil { !vm.busy }; assertEquals(1, repo.cancellations)
        assertEquals(1, repo.runs.size)
    }

    @Test fun familiarityIsReversibleAndDoesNotGenerateCards() {
        val repo = VocabularyUiRepository(); panel(repo)
        val id = f.candidates.first().id
        compose.onNodeWithTag("vocabulary_familiar_$id").performScrollTo().performClick()
        compose.waitUntil { repo.values.value.familiar.isNotEmpty() }
        compose.onNodeWithTag("vocabulary_candidate_$id").assertDoesNotExist()
        compose.onNodeWithTag("vocabulary_show_familiar").performScrollTo().performClick()
        compose.onNodeWithTag("vocabulary_select_$id").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("vocabulary_familiar_$id").performScrollTo().assertTextContains("恢复提示").performClick()
        compose.waitUntil { repo.values.value.familiar.isEmpty() }
        compose.onNodeWithTag("vocabulary_select_$id").performScrollTo().assertIsEnabled()
        assertEquals(0, repo.prepares); assertTrue(repo.runs.isEmpty())
    }

    @Test fun existingManualCaptureIsOccupiedWithoutClaimingTheCandidateWasSavedAndLookupReusesItsIdentity() {
        val repo = VocabularyUiRepository(); val original = f.candidates.first().capture
        val manual = original.copy(id = "manual-capture", createdAt = 99)
        val card = Card("card", manual.id, f.content, manual.sentence, manual.sourceRef, state = FsrsState(phase = CardPhase.REVIEW, reps = 3), createdAt = 1)
        var lookedUp: Capture? = null
        panel(repo, listOf(manual), listOf(card), { lookedUp = it })
        val id = f.candidates.first().id
        compose.onNodeWithTag("vocabulary_saved_$id").assertDoesNotExist()
        compose.onNodeWithTag("vocabulary_occupied_$id").performScrollTo().assertTextEquals("该捕获已有卡片")
        compose.onNodeWithTag("vocabulary_select_$id").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("robust", useUnmergedTree = true).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(manual, lookedUp) }
        assertTrue(repo.values.value.familiar.isEmpty())
    }

    @Test fun occupiedCaptureWithConflictingTargetOrSourceDoesNotClaimTheCurrentCandidateIsSaved() {
        val repo = VocabularyUiRepository(); val first = f.candidates[0]; val second = f.candidates[1]
        val differentSource = Card("source-conflict", first.capture.id, f.content,
            "A different user-supplied sentence.", SourceRef("Another article", "Another source"), createdAt = 1, contextOrigin = ContextOrigin.USER_SUPPLIED)
        val differentTarget = Card("target-conflict", second.capture.id, f.content.copy(headword = "prior evidence"),
            second.capture.sentence, second.capture.sourceRef, createdAt = 1)
        // Even a completed receipt cannot prove the current card still has this source and original context.
        repo.values.value = VocabularyLibrary(batches = listOf(f.batch(VocabularyItemStatus.COMPLETED).copy(
            items = listOf(VocabularyQueueItem(first, VocabularyItemStatus.COMPLETED, cardId = differentSource.id)))))
        val opened = mutableListOf<String>()
        panel(repo, listOf(first.capture, second.capture), listOf(differentSource, differentTarget), onOpenCard = { opened += it })
        listOf(first, second).forEach { candidate ->
            compose.onNodeWithTag("vocabulary_saved_${candidate.id}").assertDoesNotExist()
            compose.onNodeWithTag("vocabulary_occupied_${candidate.id}").performScrollTo().assertTextEquals("该捕获已有卡片")
            compose.onNodeWithTag("vocabulary_select_${candidate.id}").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("vocabulary_existing_card_${candidate.id}").performScrollTo().performClick()
        }
        compose.onNodeWithTag("vocabulary_conflict_${first.id}").performScrollTo().assertTextEquals("原句或来源不同，请核对既有卡片。")
        compose.runOnIdle { assertEquals(listOf("source-conflict", "target-conflict"), opened) }
        assertEquals(0, repo.prepares); assertTrue(repo.runs.isEmpty())
    }

    @Test fun onlyMatchingCompletedCandidateReceiptCanClaimTheOriginalWasSaved() {
        val repo = VocabularyUiRepository(); val candidate = f.candidates.first()
        val card = Card("confirmed-card", candidate.capture.id, f.content, candidate.capture.sentence, candidate.capture.sourceRef, createdAt = 1)
        repo.values.value = VocabularyLibrary(batches = listOf(f.batch(VocabularyItemStatus.COMPLETED).copy(
            items = listOf(VocabularyQueueItem(candidate, VocabularyItemStatus.COMPLETED, cardId = card.id)))))
        panel(repo, listOf(candidate.capture), listOf(card))
        compose.onNodeWithTag("vocabulary_saved_${candidate.id}").performScrollTo().assertTextEquals("已入卡")
        compose.onNodeWithTag("vocabulary_occupied_${candidate.id}").assertDoesNotExist()
        compose.onNodeWithTag("vocabulary_select_${candidate.id}").performScrollTo().assertIsNotEnabled()
        assertTrue(repo.runs.isEmpty())
    }

    @Test fun stableLookupPreservesUserEditedCaptureAndReversedSelectionsUseSameIdentity() {
        val candidate = f.candidates.first(); val edited = candidate.capture.copy(target = "robust method", sentence = "User-adjusted context.", contextOrigin = ContextOrigin.USER_SUPPLIED)
        assertEquals(edited, captureForVocabularyLookup(candidate, listOf(edited)))
        val normal = captureAt(f.article, f.body, 2, 8, true, false)
        val reversed = captureAt(f.article, f.body, 8, 2, true, false)
        assertEquals(candidate.capture.id, normal.id); assertEquals(normal.id, reversed.id)
        val otherStart = f.text.lastIndexOf("robust")
        assertNotEquals(normal.id, captureAt(f.article, f.body, otherStart, otherStart + 6, true, false).id)
        assertNotEquals(normal.id, captureAt(f.article, f.body, 2, 8, true, true).id)
    }

    @Test fun contextPreviewAndConfirmationRemainReachableAtTwoHundredPercent() {
        val repo = VocabularyUiRepository(); panel(repo, fontScale = 2f)
        val id = f.candidates.first().id
        compose.onNodeWithTag("vocabulary_context_$id").performScrollTo().performClick()
        compose.onNodeWithTag("vocabulary_sentence_$id").performScrollTo().assertTextEquals(f.candidates.first().capture.sentence)
        select(0)
        compose.onNodeWithTag("vocabulary_prepare").performScrollTo().performClick()
        compose.onNodeWithTag("vocabulary_confirm").assertIsDisplayed()
        compose.onNodeWithTag("vocabulary_cancel").assertIsDisplayed().performClick()
        assertEquals(0, repo.prepares)
    }

    @Test fun interruptedQueueNeedsExplicitConfirmationAndCompletedQueueHasNoResumeButton() {
        val repo = VocabularyUiRepository()
        repo.values.value = VocabularyLibrary(batches = listOf(f.batch(VocabularyItemStatus.INTERRUPTED), f.batch(VocabularyItemStatus.COMPLETED).copy(id = "done")))
        val vm = model(repo)
        compose.setContent { val state by vm.state.collectAsStateWithLifecycle(); LexiconTheme(AppSettings()) { VocabularyQueueScreen(vm, state) {} } }
        compose.waitUntil { !vm.state.value.loading }
        compose.onNodeWithTag("vocabulary_resume_fixture-batch").performScrollTo().performClick()
        compose.onNodeWithText("中断请求的远端结果无法确认", substring = true).assertIsDisplayed()
        assertTrue(repo.runs.isEmpty())
        compose.onNodeWithTag("vocabulary_resume_confirm").performClick()
        compose.waitUntil { repo.runs.size == 1 }
        compose.onNodeWithTag("vocabulary_resume_done").assertDoesNotExist()
        assertEquals(listOf("fixture-batch"), repo.runs)
    }

    @Test fun skimAndQuietKeepOriginalVocabularyCandidatesInsteadOfArgumentSentences() {
        val repository = VocabularyUiRepository(); val vm = model(repository)
        val reading = object : ReadingRepository by UiTestReadingRepository() {
            override suspend fun cachedStudy(articleId: String, bodyHash: String) = f.study
            override suspend fun cachedStudies(articleId: String, bodyHash: String) = listOfNotNull(cachedStudy(articleId, bodyHash))
        }
        val readingVm = ReadingViewModel(VocabularyWorkspaceApp(reading)).also { models += it }
        compose.setContent { LexiconTheme(AppSettings()) {
            ReadingWorkspace(f.article, f.body, false, AppSettings(reader = ReaderPreferences(automaticStudy = false)), StudyMode.SKIM,
                true, false, emptyList(), readingVm, {}, {}, {}, {}, {}, { _, _ -> }, vocabularyVm = vm)
        } }
        compose.waitUntil { readingVm.study != null && !vm.state.value.loading }
        openHighlights()
        f.candidates.forEach { compose.onNodeWithTag("vocabulary_candidate_${it.id}").performScrollTo().assertExists() }
        compose.onNodeWithText("句式与论点").performScrollTo().assertExists()
        compose.onNodeWithTag("reader_quiet").reachReadingTool().performClick()
        openHighlights()
        f.candidates.forEach { compose.onNodeWithTag("vocabulary_candidate_${it.id}").performScrollTo().assertExists() }
        assertTrue(repository.runs.isEmpty())
    }

    @Test fun bodyReplacementClearsSelectionAndAnOpenConfirmation() {
        val repo = VocabularyUiRepository(); val vm = model(repo)
        val currentBody = mutableStateOf(f.body); val currentStudy = mutableStateOf(f.study)
        compose.setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            LexiconTheme(AppSettings()) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                VocabularyHighlightsPanel(f.article, currentBody.value, currentStudy.value,
                    VocabularyLearning.candidates(f.article, currentBody.value, currentStudy.value, true), emptyList(), emptyList(), vm, state) {}
            } }
        }
        compose.waitUntil { !vm.state.value.loading }; select(0)
        compose.onNodeWithTag("vocabulary_prepare").performScrollTo().performClick()
        compose.onNodeWithTag("vocabulary_confirmation").assertExists()
        compose.runOnIdle {
            val text = "Updated. " + f.text
            currentBody.value = f.body.copy(text = text, contentHash = fingerprint(text))
            currentStudy.value = f.study.copy(bodyHash = fingerprint(text), highlights = f.study.highlights.map { it.copy(start = it.start + 9, end = it.end + 9) })
        }
        compose.onNodeWithTag("vocabulary_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("vocabulary_prepare").performScrollTo().assertIsNotEnabled()
        assertEquals(0, repo.prepares)
    }

    @Test fun rewriteStillLooksUpTheOriginalCandidateWithOriginalProvenance() {
        val repository = VocabularyUiRepository(); val vm = model(repository)
        val rewritten = "A reliable approach considers earlier information."
        val reading = object : ReadingRepository by UiTestReadingRepository() {
            override suspend fun cachedStudy(articleId: String, bodyHash: String) = f.study
            override suspend fun cachedStudies(articleId: String, bodyHash: String) = listOfNotNull(cachedStudy(articleId, bodyHash))
            override suspend fun assist(request: StudyRequest) = StudyAssist("rewrite", "B1", rewritten, "synthetic", 1)
        }
        val readingVm = ReadingViewModel(VocabularyWorkspaceApp(reading)).also { models += it }
        var captured: Capture? = null
        compose.setContent { LexiconTheme(AppSettings()) {
            ReadingWorkspace(f.article, f.body, false, AppSettings(reader = ReaderPreferences(automaticStudy = false)), StudyMode.INTENSIVE,
                true, false, emptyList(), readingVm, {}, { captured = it }, {}, {}, {}, { _, _ -> }, vocabularyVm = vm)
        } }
        compose.waitUntil { readingVm.study != null && !vm.state.value.loading }
        compose.mainClock.advanceTimeBy(3_300)
        compose.onNodeWithText("精读 · 工具").performScrollTo().performClick()
        compose.onNodeWithTag("reader_tab_分级").reachReadingTool().performClick()
        compose.onNodeWithText("B1").performScrollTo().performClick()
        compose.waitUntil { readingVm.result?.kind == "rewrite" }
        assertEquals(rewritten, compose.onNodeWithTag("zen_article_text").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
        openHighlights("AI · B1")
        compose.onNodeWithText("以下重点与原句来自原文。").performScrollTo().assertExists()
        compose.onNodeWithText("robust", useUnmergedTree = true).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(f.candidates.first().capture, captured)
            assertEquals(ContextOrigin.ORIGINAL, captured?.contextOrigin)
            assertEquals(f.body.contentHash, captured?.bodyHash)
        }
        assertTrue(repository.runs.isEmpty())
    }

    @Test fun globalFamiliarityCanBeRestoredWithoutTheArticleAndCompletedCardsCanBeOpened() {
        val repo = VocabularyUiRepository(); val candidate = f.candidates.first()
        repo.values.value = VocabularyLibrary(familiar = listOf(FamiliarTerm(candidate.termKey, candidate.capture.target, 1)),
            batches = listOf(f.batch(VocabularyItemStatus.COMPLETED).copy(items = listOf(VocabularyQueueItem(candidate, VocabularyItemStatus.COMPLETED, cardId = "saved-card")))))
        val vm = model(repo); var opened: String? = null
        compose.setContent { val state by vm.state.collectAsStateWithLifecycle(); LexiconTheme(AppSettings()) {
            VocabularyQueueScreen(vm, state, onOpenCard = { opened = it }) {}
        } }
        compose.waitUntil { !vm.state.value.loading }
        compose.onAllNodesWithText("词汇队列").assertCountEquals(1)
        compose.onNodeWithTag("vocabulary_all_familiar").performScrollTo().performClick()
        compose.onNodeWithTag("vocabulary_restore_${fingerprint(candidate.termKey)}").performScrollTo().performClick()
        compose.waitUntil { repo.values.value.familiar.isEmpty() }
        compose.onNodeWithText("1 / 1 已入卡 · 展开").performScrollTo().performClick()
        compose.onNodeWithTag("vocabulary_card_${candidate.id}").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("saved-card", opened) }
        assertTrue(repo.runs.isEmpty())
    }

    @Test fun multilineVocabularyLabelKeepsItsWholeTextInsideTheButtonAtNormalAndLargeFontSizes() {
        val phrase = "the carefully documented pattern of shared reading and discussion in community life"
        val text = "Researchers observed $phrase."
        val start = text.indexOf(phrase)
        val body = f.body.copy(text = text, contentHash = fingerprint(text))
        val study = f.study.copy(bodyHash = body.contentHash, highlights = listOf(
            StudyHighlight("long-phrase", phrase, start, start + phrase.length, "collocation", "一段用于界面检查的长表达")))
        val candidates = VocabularyLearning.candidates(f.article, body, study, true)
        val candidate = candidates.single()
        val repo = VocabularyUiRepository(); val vm = model(repo)
        val scale = mutableFloatStateOf(1f); val theme = mutableStateOf(ThemeMode.LIGHT)
        var density = 1f
        compose.setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale.floatValue)) {
                LexiconTheme(AppSettings(theme = theme.value)) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
                        VocabularyHighlightsPanel(f.article, body, study, candidates, emptyList(), emptyList(), vm, state) {}
                    }
                }
            }
        }
        compose.waitUntil { !vm.state.value.loading }
        fun verifyAndCapture(name: String) {
            val button = compose.onNodeWithTag("vocabulary_lookup_${candidate.id}").performScrollTo().fetchSemanticsNode()
            val label = compose.onNodeWithTag("vocabulary_label_${candidate.id}", useUnmergedTree = true)
            label.assertTextEquals(phrase)
            val layouts = mutableListOf<TextLayoutResult>()
            label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertTrue("The fixture must render over multiple lines", layout.lineCount >= 2)
            assertFalse("Full vocabulary labels may not be truncated", layout.hasVisualOverflow)
            val bounds = label.fetchSemanticsNode().boundsInRoot
            // Every label line lies within the button's straight-sided area, beyond its 8dp corner radius.
            assertTrue(bounds.left - button.boundsInRoot.left >= 8f * density - 1f)
            assertTrue(button.boundsInRoot.right - bounds.right >= 8f * density - 1f)
            assertTrue(bounds.top - button.boundsInRoot.top >= 8f * density - 1f)
            assertTrue(button.boundsInRoot.bottom - bounds.bottom >= 8f * density - 1f)
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "validation-1.8.0/ui-fixtures").apply { mkdirs() }
            directory.resolve(name).outputStream().use { output ->
                assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        }
        verifyAndCapture("vocabulary-long-phrase-daylight.png")
        compose.runOnIdle { scale.floatValue = 2f; theme.value = ThemeMode.DARK }
        verifyAndCapture("vocabulary-long-phrase-dracula-font200.png")
        compose.onNodeWithTag("vocabulary_select_${candidate.id}").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("vocabulary_prepare").performScrollTo().assertIsEnabled()
        assertEquals(0, repo.prepares); assertTrue(repo.runs.isEmpty())
    }

    private fun openHighlights(label: String = "略读 · 工具") {
        compose.mainClock.advanceTimeBy(3_300)
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.onNodeWithTag("reader_tab_重点").reachReadingTool().performClick()
    }
}

internal class VocabularyWorkspaceApp(override val reading: ReadingRepository) : AppRepository {
    override val articles = MutableStateFlow<List<Article>>(emptyList())
    override val sources = MutableStateFlow<List<Source>>(emptyList())
    override val captures = MutableStateFlow<List<Capture>>(emptyList())
    override val cards = MutableStateFlow<List<Card>>(emptyList())
    override val settings = MutableStateFlow(AppSettings())
    override val status = MutableStateFlow("[ready]")
    override val jobs = MutableStateFlow<List<AiJob>>(emptyList())
    override suspend fun initialize() = Unit
    override suspend fun sync() = SyncResult(0, 0, emptyList())
    override suspend fun setSourceEnabled(id: String, enabled: Boolean) = Unit
    override suspend fun markArticleRead(id: String) = Unit
    override suspend fun openArticle(id: String): ArticleBody? = null
    override suspend fun classifyPending() = Unit
    override suspend fun saveCapture(capture: Capture) = Unit
    override suspend fun lookup(text: String) = emptyList<DictionaryEntry>()
    override suspend fun updateSettings(settings: AppSettings) { this.settings.value = settings }
    override suspend fun hasApiKey() = false
    override suspend fun setApiKey(key: String) = Unit
    override suspend fun clearApiKey() = Unit
    override suspend fun dictionaryStatus() = DictionaryStatus()
    override suspend fun importDictionary(file: File) = DictionaryStatus()
    override suspend fun analyzeCapture(capture: Capture, deep: Boolean): AnalysisResult = error("Unexpected vocabulary request")
    override suspend fun saveCard(capture: Capture, analysis: AnalysisResult): Card = error("Unexpected vocabulary write")
    override suspend fun updateCard(card: Card) = Unit
    override suspend fun reviewCard(cardId: String, rating: Rating, expectedReps: Int, now: Long) = ReviewOutcome.APPLIED
    override suspend fun exportAnki(destination: File) = destination
    override suspend fun confirmAnkiExport(exportFile: File) = Unit
    override suspend fun exportEvents(destination: File) = destination
    override suspend fun recordEvent(type: String, value: Int) = Unit
}
