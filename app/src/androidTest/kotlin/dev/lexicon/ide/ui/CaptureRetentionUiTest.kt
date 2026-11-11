package dev.lexicon.ide.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** Full application routes over local synthetic repositories. No SQL, key store or HTTP.
 * Restoration below exercises saved-instance state, not an actual OS process termination.
 */
@RunWith(AndroidJUnit4::class)
class CaptureRetentionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeTemporaryDraftRestoresItsExactSecondOccurrenceAndOriginalSource() {
        val repo = RetentionRepository()
        val restoration = StateRestorationTester(compose)
        var restoring = false
        restoration.setContent {
            DisposableEffect(Unit) { onDispose {
                if (restoring) {
                    // A new feed/catalog snapshot must not relabel an already captured quote.
                    repo.backing.articles.value = listOf(repo.article.copy(
                        title = "A changed catalog title", sourceName = "Changed catalog source",
                        url = "https://example.invalid/changed-catalog-url", publishedAt = 999,
                    ))
                    repo.captures.value = emptyList()
                }
            } }
            LexiconApp(repo)
        }
        openNative(repo)
        tapSecondRobust(repo)
        val initial = latestProbe(repo)
        val start = repo.body.text.lastIndexOf("robust")
        assertEquals(start, initial.selectionStart)
        assertEquals(start + "robust".length, initial.selectionEnd)
        assertEquals("robust", initial.selectedText)
        assertEquals(repo.body.contentHash, initial.bodyHash)
        assertEquals(repo.article.id, initial.articleId)
        assertEquals(ContextOrigin.ORIGINAL, initial.contextOrigin)
        assertEquals(repo.originalRef, initial.sourceRef)
        compose.onNodeWithTag("capture_target").performScrollTo().performTextReplacement("robust evidence")
        compose.onNodeWithTag("capture_target").performTextInputSelection(TextRange(2, 8))
        awaitProbe(repo) { it.target == "robust evidence" }
        val edited = latestProbe(repo)
        val probesBeforeRestore = repo.probes.size
        assertEquals(0, repo.captureSaveCalls)
        assertTrue(repo.captures.value.isEmpty())
        assertEquals(1, repo.openedEvents())
        compose.runOnIdle { restoring = true }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { exists("capture_panel") && repo.probes.size > probesBeforeRestore }
        awaitProbe(repo) { it.target == "robust evidence" }
        compose.onNodeWithTag("capture_target").assertTextContains("robust evidence")
        assertEquals(TextRange(2, 8), fieldSelection("capture_target"))
        assertEquals(edited, latestProbe(repo))
        assertEquals(repo.originalRef, latestProbe(repo).sourceRef)
        assertEquals(initial.id, latestProbe(repo).id)
        assertTrue(repo.captures.value.isEmpty())
        assertEquals(0, repo.captureSaveCalls)
        assertEquals(1, repo.openedEvents())
        closeCapture()
        awaitReader()
        assertTrue(repo.captures.value.isEmpty())
        assertEquals(0, repo.analysisCalls)
    }

    @Test fun externalEditedDraftRestoresWithoutACaptureRowOrAnotherOpenEvent() {
        val repo = RetentionRepository()
        val restoration = StateRestorationTester(compose)
        var finishes = 0
        restoration.setContent { LexiconApp(repo, initialCaptureText = "robust", onExternalCaptureDone = { finishes++ }) }
        awaitCapture()
        val initial = latestProbe(repo)
        compose.onNodeWithTag("capture_target").performScrollTo().performTextReplacement("robust method")
        compose.onNodeWithTag("capture_sentence").performScrollTo().performTextReplacement(USER_SENTENCE)
        compose.onNodeWithTag("capture_sentence").performTextInputSelection(TextRange(2, 8))
        awaitProbe(repo) { it.target == "robust method" && it.sentence == USER_SENTENCE }
        val edited = latestProbe(repo)
        assertEquals(initial.id, edited.id)
        assertEquals("robust", edited.selectedText)
        assertEquals(ContextOrigin.USER_SUPPLIED, edited.contextOrigin)
        assertEquals(SourceRef(), edited.sourceRef)
        assertNull(edited.articleId)
        assertNull(edited.bodyHash)
        assertNull(edited.selectionStart)
        assertNull(edited.selectionEnd)
        val probesBeforeRestore = repo.probes.size
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { exists("capture_panel") && repo.probes.size > probesBeforeRestore }
        awaitProbe(repo) { it.target == "robust method" && it.sentence == USER_SENTENCE }
        assertEquals(edited, latestProbe(repo))
        assertEquals(TextRange(2, 8), fieldSelection("capture_sentence"))
        assertEquals(0, finishes)
        assertEquals(0, repo.captureSaveCalls)
        assertTrue(repo.captures.value.isEmpty())
        assertEquals(1, repo.openedEvents())
        closeCapture()
        compose.waitUntil(5_000) { finishes == 1 && !exists("capture_panel") }
        assertTrue(repo.captures.value.isEmpty())
        assertEquals(0, repo.analysisCalls)
    }

    @Test fun closingANewEmptyCaptureDoesNotWriteOrReportASaveFailure() {
        val repo = RetentionRepository()
        compose.setContent { LexiconApp(repo) }
        awaitHome()
        command("capture", "新建捕获 / 粘贴文本")
        awaitCapture()
        assertEquals("", fieldText("capture_target"))
        compose.onNodeWithTag("capture_save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("捕获尚未保存", substring = true).assertDoesNotExist()
        compose.onNodeWithText("操作未完成", substring = true).assertDoesNotExist()
        assertEquals(0, repo.captureSaveCalls)
        closeCapture()
        awaitHome()
        assertTrue(repo.captures.value.isEmpty())
        assertEquals(0, repo.captureSaveCalls)
        assertEquals(0, repo.analysisCalls)
    }

    @Test fun failedExplicitRetentionKeepsTheDraftAndRetryStoresOnlyOneCapture() {
        val repo = RetentionRepository().apply { saveFailure = true }
        var finishes = 0
        compose.setContent { LexiconApp(repo, initialCaptureText = "robust", onExternalCaptureDone = { finishes++ }) }
        awaitCapture()
        compose.onNodeWithTag("capture_sentence").performScrollTo().performTextReplacement(USER_SENTENCE)
        awaitProbe(repo) { it.sentence == USER_SENTENCE }
        val input = latestProbe(repo)
        assertEquals(0, repo.captureSaveCalls)
        compose.onNodeWithTag("capture_save").performScrollTo().assertTextContains("留待处理").performClick()
        compose.waitUntil(5_000) { repo.captureSaveCalls == 1 }
        compose.waitForIdle()
        compose.onNodeWithTag("capture_panel").assertExists()
        compose.onNodeWithTag("capture_save").assertIsEnabled()
        assertEquals(USER_SENTENCE, fieldText("capture_sentence"))
        assertEquals(0, finishes)
        assertTrue(repo.captures.value.isEmpty())
        compose.runOnIdle { repo.saveFailure = false }
        compose.onNodeWithTag("capture_save").performScrollTo().performClick()
        compose.waitUntil(5_000) { finishes == 1 && !exists("capture_panel") }
        val saved = repo.captures.value.single()
        assertEquals(2, repo.captureSaveCalls) // Two explicit attempts, one durable row.
        assertEquals(input.id, saved.id)
        assertEquals(input.selectedText, saved.selectedText)
        assertEquals(USER_SENTENCE, saved.sentence)
        assertEquals(ContextOrigin.USER_SUPPLIED, saved.contextOrigin)
        assertEquals(SourceRef(), saved.sourceRef)
        assertEquals(0, repo.analysisCalls)
    }

    @Test fun openingKeySettingsExplicitlyRetainsTheExternalInputOnce() {
        val repo = RetentionRepository()
        var finishes = 0
        compose.setContent { LexiconApp(repo, initialCaptureText = "robust", onExternalCaptureDone = { finishes++ }) }
        awaitCapture()
        val id = latestProbe(repo).id
        compose.onNodeWithText("配置 DeepSeek API Key").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { !exists("capture_panel") && repo.captures.value.size == 1 }
        compose.onNodeWithTag("api_key").performScrollTo().assertExists()
        assertEquals(0, finishes)
        assertEquals(1, repo.captureSaveCalls)
        assertEquals(id, repo.captures.value.single().id)
        assertEquals("robust", repo.captures.value.single().selectedText)
        assertEquals(ContextOrigin.MISSING, repo.captures.value.single().contextOrigin)
        assertEquals(0, repo.keyChanges)
        assertEquals(0, repo.analysisCalls)
        command("inbox", "待处理捕获", substring = true)
        compose.onNodeWithTag("pending_capture_$id").performScrollTo().assertIsDisplayed()
    }

    @Test fun failedAnalysisRetainsItsInputAndReopeningDoesNotRetryTheModel() {
        val repo = RetentionRepository().apply { keyConfigured = true; analysisFailure = true }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { LexiconApp(repo) }
        openNative(repo)
        tapSecondRobust(repo)
        val initial = latestProbe(repo)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("深度分析").fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) } }
        compose.onNodeWithText("深度分析").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { repo.analysisCalls == 1 && repo.captures.value.size == 1 }
        compose.waitForIdle()
        compose.onNodeWithTag("capture_panel").assertExists()
        compose.onNodeWithTag("capture_save").performScrollTo().assertTextContains("保存修改")
        val retained = repo.captures.value.single()
        assertEquals(initial.id, retained.id)
        assertEquals(initial.selectedText, retained.selectedText)
        assertEquals(initial.sentence, retained.sentence)
        assertEquals(initial.bodyHash, retained.bodyHash)
        assertEquals(initial.selectionStart, retained.selectionStart)
        assertEquals(initial.selectionEnd, retained.selectionEnd)
        assertEquals(initial.sourceRef, retained.sourceRef)
        assertEquals(1, repo.captureSaveCalls)
        closeCapture()
        awaitReader()
        // Return to the workbench without creating another lookup or another AI request.
        returnFromReader()
        command("inbox", "待处理捕获", substring = true)
        compose.onNodeWithTag("pending_capture_${retained.id}").performScrollTo().performClick()
        awaitCapture()
        val probesBeforeRestore = repo.probes.size
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { exists("capture_panel") && repo.probes.size > probesBeforeRestore }
        assertEquals(retained.id, latestProbe(repo).id)
        assertEquals(retained.sentence, fieldText("capture_sentence"))
        assertEquals(listOf(retained), repo.captures.value)
        assertEquals(1, repo.analysisCalls)
        assertEquals(1, repo.captureSaveCalls)
    }

    @Test fun archivingAndReopeningTheSameOriginalKeepsItsCachedAnalysisAndCardMaterial() {
        val repo = RetentionRepository()
        val kept = repo.seedOriginal()
        val unrelatedCard = CaptureAnalysisTestRepository.card(kept.copy(id = "unrelated-capture")).copy(id = "unrelated-card")
        repo.cards.value = listOf(unrelatedCard)
        val initialCards = repo.cards.value
        val initialCached = repo.cached.toMap()
        val initialCaptures = repo.captures.value
        compose.setContent { LexiconApp(repo) }
        awaitHome()
        command("inbox", "待处理捕获", substring = true)
        compose.onNodeWithTag("capture_archive_${kept.id}").performScrollTo().performClick()
        compose.waitUntil(5_000) { kept.id in repo.inbox.archivedCaptureIds.value }
        compose.onNodeWithTag("pending_capture_${kept.id}").assertDoesNotExist()
        compose.onNodeWithTag("capture_inbox_archived").performClick()
        compose.onNodeWithTag("pending_capture_${kept.id}").performScrollTo().performClick()
        awaitCapture()
        compose.onNodeWithTag("generate_card").performScrollTo().assertTextContains("保存已有分析").assertIsEnabled()
        assertEquals(0, repo.analysisCalls)
        assertEquals(0, repo.captureSaveCalls)
        closeCapture()
        compose.onNodeWithTag("capture_inbox_archived").assertIsSelected()
        compose.onNodeWithText("Lexicon").performClick()
        openNative(repo)
        tapSecondRobust(repo)
        assertEquals(kept.id, latestProbe(repo).id)
        compose.onNodeWithTag("generate_card").performScrollTo().assertTextContains("保存已有分析").assertIsEnabled()
        assertEquals(setOf(kept.id), repo.inbox.archivedCaptureIds.value)
        assertEquals(initialCaptures, repo.captures.value)
        assertEquals(0, repo.captureSaveCalls)
        closeCapture()
        returnFromReader()
        command("inbox", "待处理捕获", substring = true)
        compose.onNodeWithTag("capture_inbox_archived").performClick()
        compose.onNodeWithTag("capture_restore_${kept.id}").performScrollTo().performClick()
        compose.waitUntil(5_000) { repo.inbox.archivedCaptureIds.value.isEmpty() }
        compose.onNodeWithTag("capture_inbox_active").performClick()
        compose.onNodeWithTag("pending_capture_${kept.id}").performScrollTo().assertIsDisplayed()
        assertEquals(listOf(kept.id to true, kept.id to false), repo.inbox.decisions)
        assertEquals(initialCaptures, repo.captures.value)
        assertEquals(initialCards, repo.cards.value)
        assertEquals(initialCached, repo.cached)
        assertEquals(0, repo.analysisCalls)
        assertEquals(0, repo.captureSaveCalls)
        assertEquals(0, repo.cardSaves)
    }

    @Test fun failedArchivingKeepsTheItemAndOffersALocalRetryWithoutNetworkAdvice() {
        val repo = RetentionRepository()
        val kept = repo.seedOriginal()
        val before = repo.captures.value
        repo.inbox.fail = true
        compose.setContent { LexiconApp(repo) }
        awaitHome()
        command("inbox", "待处理捕获", substring = true)
        compose.onNodeWithTag("capture_archive_${kept.id}").performScrollTo().performClick()
        compose.waitUntil(5_000) { repo.inbox.attempts == 1 }
        compose.waitForIdle()
        compose.onNodeWithText("归档状态未保存", substring = true).assertIsDisplayed()
        compose.onNodeWithText("网络", substring = true).assertDoesNotExist()
        compose.onNodeWithText("API Key", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("pending_capture_${kept.id}").assertExists()
        compose.onNodeWithTag("capture_archive_${kept.id}").assertIsEnabled()
        assertEquals(before, repo.captures.value)
        assertTrue(repo.inbox.archivedCaptureIds.value.isEmpty())
        assertTrue(repo.inbox.decisions.isEmpty())
        compose.runOnIdle { repo.inbox.fail = false }
        compose.onNodeWithTag("capture_archive_${kept.id}").performScrollTo().performClick()
        compose.waitUntil(5_000) { repo.inbox.archivedCaptureIds.value == setOf(kept.id) }
        compose.waitForIdle()
        compose.onNodeWithTag("pending_capture_${kept.id}").assertDoesNotExist()
        assertEquals(2, repo.inbox.attempts)
        assertEquals(listOf(kept.id to true), repo.inbox.decisions)
        assertEquals(before, repo.captures.value)
        assertEquals(0, repo.captureSaveCalls)
        assertEquals(0, repo.analysisCalls)
    }

    private fun openNative(repo: RetentionRepository) {
        awaitHome()
        compose.waitUntil(5_000) { exists("daily_intensive_${repo.article.id}") }
        compose.onNodeWithTag("daily_intensive_${repo.article.id}").performScrollTo().performClick()
        awaitReader()
    }

    private fun tapSecondRobust(repo: RetentionRepository) {
        val field = compose.onNodeWithTag("zen_article_text")
        field.performScrollTo()
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val start = repo.body.text.lastIndexOf("robust")
        assertTrue(start > repo.body.text.indexOf("robust"))
        val point = layouts.single().getBoundingBox(start + 2).center
        field.performTouchInput { advanceEventTime(600); click(point) }
        awaitCapture()
        awaitProbe(repo) { it.selectionStart == start }
    }

    private fun returnFromReader() {
        // The visible reader exit is exercised rather than mutating the root route.
        compose.onNodeWithText("← 退出 Zen").performScrollTo().performClick()
        awaitHome()
    }

    private fun command(query: String, label: String, substring: Boolean = false) {
        compose.onNodeWithText(">_  命令").performClick()
        compose.onNodeWithText("搜索命令…").performTextInput(query)
        compose.onNodeWithText(label, substring = substring).performClick()
        settle()
    }

    private fun closeCapture() {
        compose.onNodeWithTag("capture_close").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { !exists("capture_panel") }
        settle()
    }

    private fun awaitHome() { compose.waitUntil(5_000) { exists("daily_reading") }; settle() }
    private fun awaitReader() { compose.waitUntil(5_000) { exists("zen_article_text") && !exists("capture_panel") }; settle() }
    private fun awaitCapture() { compose.waitUntil(5_000) { exists("capture_target") }; settle() }
    private fun settle() { compose.mainClock.advanceTimeBy(600); compose.waitForIdle() }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun fieldText(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun fieldSelection(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
    private fun latestProbe(repo: RetentionRepository): Capture {
        compose.waitUntil(5_000) { repo.probes.isNotEmpty() }
        return repo.probes.last()
    }
    private fun awaitProbe(repo: RetentionRepository, expected: (Capture) -> Boolean) {
        compose.waitUntil(5_000) { repo.probes.lastOrNull()?.let(expected) == true }
        compose.waitForIdle()
    }

    private class RetentionRepository(val backing: CaptureAnalysisTestRepository = CaptureAnalysisTestRepository()) : AppRepository by backing {
        override val reading = UiTestReadingRepository()
        override val captures = backing.captures
        override val cards = backing.cards
        val inbox = Inbox { id -> captures.value.any { it.id == id } }
        override val captureInbox: CaptureInboxRepository = inbox
        val article = Article("retention-article", "retention-guid", listOf("retention-source"),
            "Evidence and method", "https://example.invalid/retention", "", author = "Synthetic author", publishedAt = 123,
            read = true, readingMode = ReadingMode.NATIVE, sourceName = "Synthetic publication")
        val body = ArticleBody(article.id,
            "A robust method needs evidence.\n\nThe 🧪 report explains why a robust claim must remain open to testing.",
            fingerprint("A robust method needs evidence.\n\nThe 🧪 report explains why a robust claim must remain open to testing."),
            1, Long.MAX_VALUE, "Synthetic test material")
        val originalRef = SourceRef(article.title, article.sourceName, article.url, article.publishedAt)
        val probes = mutableListOf<Capture>()
        val events = mutableListOf<Pair<String, Int>>()
        val cached = mutableMapOf<String, CaptureAnalysisAvailability.Ready>()
        var captureSaveCalls = 0
        var cardSaves = 0
        var analysisCalls = 0
        var saveFailure = false
        var analysisFailure = false
        var keyConfigured = false
        var keyChanges = 0

        init {
            backing.articles.value = listOf(article)
            backing.sources.value = listOf(Source("retention-source", "Synthetic publication", "https://example.invalid/feed",
                ReadingMode.NATIVE, enabled = true, aiAllowed = true))
            backing.settings.value = AppSettings(titleClassification = false, reader = ReaderPreferences(automaticStudy = false))
        }

        fun seedOriginal(): Capture {
            val start = body.text.lastIndexOf("robust")
            return captureAt(article, body, start, start + 6, true, false).also {
                captures.value = listOf(it)
                cached[it.id] = CaptureAnalysisTestRepository.ready
            }
        }

        fun openedEvents() = events.filter { it.first == "capture_opened" }.sumOf { it.second }
        override suspend fun openArticle(id: String) = body.takeIf { it.articleId == id }
        override suspend fun hasApiKey() = keyConfigured
        override suspend fun setApiKey(key: String) { keyChanges++; keyConfigured = true }
        override suspend fun clearApiKey() { keyChanges++; keyConfigured = false }
        override suspend fun saveCapture(capture: Capture) {
            captureSaveCalls++
            if (saveFailure) throw IOException("Synthetic local persistence failure")
            captures.value = captures.value.filterNot { it.id == capture.id } + capture
        }
        override suspend fun availableCaptureAnalysis(capture: Capture): CaptureAnalysisAvailability {
            probes += capture
            cards.value.firstOrNull { it.captureId == capture.id }?.let { return CaptureAnalysisAvailability.ExistingCard(it, true) }
            return cached[capture.id] ?: CaptureAnalysisAvailability.Missing
        }
        override suspend fun analyzeCapture(capture: Capture, deep: Boolean): AnalysisResult {
            analysisCalls++
            check(captures.value.any { it.id == capture.id && it.selectedText == capture.selectedText && it.sentence == capture.sentence }) {
                "Explicit analysis must retain its input before the synthetic model boundary"
            }
            if (analysisFailure) throw IOException("Synthetic model transport failure")
            return CaptureAnalysisTestRepository.ready.analysis
        }
        override suspend fun saveCard(capture: Capture, analysis: AnalysisResult): Card {
            cardSaves++
            return CaptureAnalysisTestRepository.card(capture).copy(content = analysis.content).also {
                cards.value = cards.value.filterNot { old -> old.id == it.id } + it
            }
        }
        override suspend fun recordEvent(type: String, value: Int) { events += type to value }
    }

    private class Inbox(private val captureExists: (String) -> Boolean) : CaptureInboxRepository {
        override val archivedCaptureIds = MutableStateFlow<Set<String>>(emptySet())
        val decisions = mutableListOf<Pair<String, Boolean>>()
        var attempts = 0
        var fail = false
        override suspend fun setArchived(captureId: String, archived: Boolean): Boolean {
            attempts++
            if (fail) throw IOException("Synthetic local organization failure")
            if (!captureExists(captureId)) return false
            decisions += captureId to archived
            archivedCaptureIds.value = if (archived) archivedCaptureIds.value + captureId else archivedCaptureIds.value - captureId
            return true
        }
    }

    companion object {
        private const val USER_SENTENCE = "A robust method handles changing conditions."
    }
}
