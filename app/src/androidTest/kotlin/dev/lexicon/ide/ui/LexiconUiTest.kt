package dev.lexicon.ide.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.lexicon.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File

@RunWith(AndroidJUnit4::class)
class LexiconUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun commandSheetClosesBeforeAnimatedDestinationRemainsUsable() {
        compose.setContent { LexiconApp(FakeUiRepository()) }
        compose.onNodeWithText(">_  命令").performClick()
        compose.onNodeWithText("搜索命令…").performTextInput("cards")
        compose.onNodeWithText("浏览生词卡").performClick()
        compose.onNodeWithText("还没有生词卡").assertIsDisplayed()
        compose.onNodeWithText("想做点什么？").assertDoesNotExist()
        compose.onNodeWithText(">_  命令").performClick()
        compose.onNodeWithText("搜索命令…").performTextInput("settings")
        compose.onNodeWithText("API Key 与阅读设置").performClick()
        compose.onNodeWithText("导入 Key").performScrollTo().assertIsDisplayed()
    }

    @Test fun externalArticleOpensInsideApp() {
        val repository = FakeUiRepository()
        repository.articles.value = listOf(Article("external", "g", listOf("publisher"), "An in-app article", "https://example.org/article", "Summary", sourceName = "Publisher"))
        compose.setContent { LexiconApp(repository) }
        compose.onNodeWithText("精读").performClick()
        compose.onNodeWithTag("in_app_article_page").assertIsDisplayed()
        compose.onNodeWithText("段落聚焦").assertIsDisplayed()
    }

    @Test fun contextlessCaptureCannotGenerateUntilUserSuppliesSentence() {
        val repository = FakeUiRepository()
        val vm = LexiconViewModel(repository)
        val capture = Capture("capture", target = "robust", selectedText = "robust", createdAt = 1)
        compose.setContent {
            LexiconTheme(AppSettings()) {
                CapturePanel(capture, emptyList(), emptyList(), vm, {}, {}, {})
            }
        }
        compose.waitUntil { vm.hasKey }
        compose.onNodeWithTag("generate_card").assertIsNotEnabled()
        compose.onNodeWithTag("capture_sentence").performScrollTo().performTextInput("A robust method remains useful under changing conditions.")
        compose.onNodeWithTag("generate_card").assertIsEnabled()
        assertEquals("Typing a sentence must not invoke the paid API", 0, repository.analysisCalls)
    }

    @Test fun sourceSearchAndSubscriptionFilterKeepSelectedSource() {
        val initial = listOf(
            Source("conversation", "The Conversation · US", "https://theconversation.com/us/articles.atom", ReadingMode.NATIVE, enabled = true),
            Source("economist-business", "The Economist · Business", "https://www.economist.com/business/rss.xml", defaultTopic = "Business"),
            Source("economist-science", "The Economist · Science & Technology", "https://www.economist.com/science-and-technology/rss.xml", defaultTopic = "Science & Technology"),
        )
        compose.setContent {
            var sources by remember { mutableStateOf(initial) }
            LexiconTheme(AppSettings()) {
                SourceScreen(sources, false, { id, enabled -> sources = sources.map { if (it.id == id) it.copy(enabled = enabled) else it } }, {})
            }
        }
        compose.onNodeWithTag("source_search").performTextInput("经济学人 科技")
        compose.onNodeWithText("The Economist · Science & Technology").assertIsDisplayed()
        compose.onNodeWithText("The Economist · Business").assertDoesNotExist()
        compose.onNodeWithText("App 内阅读").assertIsDisplayed()
        compose.onNodeWithTag("source_toggle_economist-science").performClick()
        compose.onNodeWithTag("sources_subscribed").performClick()
        compose.onNodeWithTag("source_toggle_economist-science").assertIsOn()
        compose.onNodeWithTag("source_search").performTextClearance()
        compose.onNodeWithText("The Conversation · US").assertIsDisplayed()
        compose.onNodeWithText("The Economist · Business").assertDoesNotExist()
    }

    @Test fun generatedCardClosesSheetAfterWorkCompletes() {
        val repository = FakeUiRepository()
        val vm = LexiconViewModel(repository)
        var saved: Card? = null
        val capture = Capture("ready", target = "robust", selectedText = "robust",
            sentence = "A robust method handles changing conditions.", contextOrigin = ContextOrigin.USER_SUPPLIED, createdAt = 1)
        compose.setContent {
            LexiconTheme(AppSettings()) {
                CapturePanel(capture, emptyList(), emptyList(), vm, {}, { saved = it }, {})
            }
        }
        compose.waitUntil { vm.hasKey }
        compose.mainClock.advanceTimeBy(400) // Let the debounced lookup settle before measuring the moving button.
        compose.waitForIdle()
        compose.onNodeWithTag("generate_card").performScrollTo().assertIsEnabled()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("generate_card").performClick()
        compose.waitUntil(5_000) { repository.analysisCalls == 1 }
        compose.waitUntil(5_000) { saved != null }
        compose.runOnIdle {
            assertEquals(1, repository.analysisCalls)
            assertEquals(null, vm.busy)
            assertEquals(capture.sentence, saved?.sourceSentence)
        }
    }

    @Test fun sourceRestrictionDisablesAiEvenWithSentenceAndKey() {
        val repository = FakeUiRepository()
        val vm = LexiconViewModel(repository)
        val capture = Capture("restricted", target = "robust", selectedText = "robust",
            sentence = "This method is robust.", contextOrigin = ContextOrigin.USER_SUPPLIED, aiAllowed = false, createdAt = 1)
        compose.setContent {
            LexiconTheme(AppSettings()) { CapturePanel(capture, emptyList(), emptyList(), vm, {}, {}, {}) }
        }
        compose.waitUntil { vm.hasKey }
        compose.onNodeWithTag("generate_card").assertIsNotEnabled()
        compose.onNodeWithText("深度分析").assertIsNotEnabled()
        assertEquals(0, repository.analysisCalls)
    }

    @Test fun reviewRequiresRevealBeforeRatingAndPassesExactRating() {
        var rating: Rating? = null
        var reviewedCard: Card? = null
        compose.setContent {
            LexiconTheme(AppSettings()) {
                ReviewScreen(listOf(exampleCard()), false, { card, grade, done -> reviewedCard = card; rating = grade; done() }, {})
            }
        }
        compose.onNodeWithText("Good · 记得").assertDoesNotExist()
        compose.onNodeWithTag("review_answer").performScrollTo().performClick()
        compose.onNodeWithText("Good · 记得").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(Rating.GOOD, rating)
            assertEquals("card", reviewedCard?.id)
        }
    }

    @Test fun unsavedApiKeyDoesNotEnterSavedInstanceState() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            LexiconTheme(AppSettings()) {
                SettingsScreen(AppSettings(), false, false, emptyList(), {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("api_key").performScrollTo().performTextInput("local-test-value")
        // Password fields deliberately expose masked EditableText; InputText is the test-only raw semantics.
        compose.onNodeWithTag("api_key").assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("local-test-value")))
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("api_key").assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("")))
    }

    private fun exampleCard() = Card(
        id = "card", captureId = "capture", content = vocabulary(), sourceSentence = "A robust method handles variation.",
        sourceRef = SourceRef(title = "Methods", source = "Example"), createdAt = 1,
    )
}

private fun vocabulary() = VocabularyContent(
    headword = "robust", contextDefinition = "Remaining effective under a range of conditions.",
    collocations = listOf(Collocation("robust method", "The robust method handles changing inputs."), Collocation("robust evidence", "The analysis provides robust evidence.")),
    paraphrase = "reliable under varied conditions", registerTag = "neutral", examRelevance = "high",
)

private class FakeUiRepository : AppRepository {
    override val reading = UiTestReadingRepository()
    override val articles = MutableStateFlow<List<Article>>(emptyList())
    override val sources = MutableStateFlow<List<Source>>(emptyList())
    override val captures = MutableStateFlow<List<Capture>>(emptyList())
    override val cards = MutableStateFlow<List<Card>>(emptyList())
    override val settings = MutableStateFlow(AppSettings())
    override val status = MutableStateFlow("[ready]")
    override val jobs = MutableStateFlow<List<AiJob>>(emptyList())
    var analysisCalls = 0
    override suspend fun initialize() = Unit
    override suspend fun sync() = SyncResult(0, 0, emptyList())
    override suspend fun setSourceEnabled(id: String, enabled: Boolean) = Unit
    override suspend fun markArticleRead(id: String) = Unit
    override suspend fun openArticle(id: String): ArticleBody? = null
    override suspend fun classifyPending() = Unit
    override suspend fun saveCapture(capture: Capture) { captures.value = listOf(capture) }
    override suspend fun lookup(text: String) = listOf(DictionaryEntry("robust", "", "strong and unlikely to break or fail"))
    override suspend fun updateSettings(settings: AppSettings) { this.settings.value = settings }
    override suspend fun hasApiKey() = true
    override suspend fun setApiKey(key: String) = Unit
    override suspend fun clearApiKey() = Unit
    override suspend fun dictionaryStatus() = DictionaryStatus()
    override suspend fun importDictionary(file: File) = DictionaryStatus(ready = true, entryCount = 2)
    override suspend fun analyzeCapture(capture: Capture, deep: Boolean): AnalysisResult { analysisCalls++; return AnalysisResult(vocabulary(), "test") }
    override suspend fun saveCard(capture: Capture, analysis: AnalysisResult) = Card("card", capture.id, analysis.content, capture.sentence, capture.sourceRef, createdAt = 1)
    override suspend fun updateCard(card: Card) = Unit
    override suspend fun reviewCard(cardId: String, rating: Rating, expectedReps: Int, now: Long) = ReviewOutcome.APPLIED
    override suspend fun exportAnki(destination: File) = destination
    override suspend fun confirmAnkiExport(exportFile: File) = Unit
    override suspend fun exportEvents(destination: File) = destination
    override suspend fun recordEvent(type: String, value: Int) = Unit
}
