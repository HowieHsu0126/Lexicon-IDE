package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local UI contracts; no network, production database, device key store or saved learning edits. */
@RunWith(AndroidJUnit4::class)
class CardSourceActionsUiTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<LexiconViewModel>()
    private val capture = CaptureAnalysisTestRepository.capture
    private val card = CaptureAnalysisTestRepository.card(capture)

    @After fun dispose() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { models.forEach { it.viewModelScope.cancel() } }
    }

    @Test fun existingCardOnlyUsesTheOpenCallbackWithoutSavingOrGenerating() {
        val repo = CaptureAnalysisTestRepository().apply {
            availableAction = { CaptureAnalysisAvailability.ExistingCard(card, matchesInput = true) }
        }
        var opened: Card? = null
        var saved = 0
        panel(repo, onSaved = { saved++ }, onExisting = { opened = it })
        compose.onNodeWithTag("generate_card").assertDoesNotExist()
        compose.onNodeWithTag("capture_existing_card").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { opened != null }
        compose.runOnIdle {
            assertEquals(card, opened)
            assertEquals(0, saved)
            assertEquals(0, repo.captureSaves)
            assertEquals(0, repo.analysisCalls)
            assertEquals(0, repo.directCardSaves)
            assertTrue(repo.savedReceipts.isEmpty())
        }
    }

    @Test fun creatingANewCardOnlyUsesTheSavedCallback() {
        val repo = CaptureAnalysisTestRepository().apply { keyConfigured = true }
        var saved: Card? = null
        var opened = 0
        panel(repo, onSaved = { saved = it }, onExisting = { opened++ })
        compose.onNodeWithTag("capture_existing_card").assertDoesNotExist()
        compose.onNodeWithTag("generate_card").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { saved != null }
        compose.runOnIdle {
            assertEquals(capture.id, saved?.captureId)
            assertEquals(capture.sentence, saved?.sourceSentence)
            assertEquals(0, opened)
            assertEquals(1, repo.captureSaves)
            assertEquals(1, repo.analysisCalls)
            assertEquals(listOf(false), repo.analysisModes)
            assertEquals(1, repo.directCardSaves)
            assertTrue(repo.savedReceipts.isEmpty())
        }
    }

    @Test fun absentExistingCardCallbackCannotFallBackToNewCardSuccess() {
        val repo = CaptureAnalysisTestRepository().apply {
            availableAction = { CaptureAnalysisAvailability.ExistingCard(card, matchesInput = true) }
        }
        var saved = 0
        panel(repo, onSaved = { saved++ })
        compose.onNodeWithTag("capture_existing_card").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(0, saved)
            assertEquals(0, repo.captureSaves)
            assertEquals(0, repo.analysisCalls)
            assertEquals(0, repo.directCardSaves)
        }
    }

    @Test fun cardDetailExposesTheProvidedReturnAndSourceActionsWithoutEditing() {
        var returns = 0
        var sources = 0
        compose.setContent {
            LexiconTheme(AppSettings()) {
                Box(Modifier.fillMaxSize()) {
                    CardDetail(card, false, { _, _ -> fail("Navigation must not save card content") }, { returns++ },
                        backLabel = "← 返回阅读", onOpenSource = { sources++ }, sourceActionLabel = "回到原句")
                }
            }
        }
        compose.onNodeWithTag("card_detail_${card.id}").assertExists()
        compose.onNodeWithTag("card_back").assertTextContains("← 返回阅读")
        compose.onNodeWithText(card.sourceRef.display()).assertExists()
        compose.onNodeWithTag("card_open_source").performScrollTo().assertTextContains("回到原句").performClick()
        compose.onNodeWithTag("card_back").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, sources); assertEquals(1, returns) }
    }

    @Test fun cardDetailWithoutAnAvailableSourceKeepsItsDefaultReturnOnly() {
        compose.setContent {
            LexiconTheme(AppSettings()) {
                Box(Modifier.fillMaxSize()) { CardDetail(card, false, { _, _ -> }, {}) }
            }
        }
        compose.onNodeWithTag("card_back").assertTextContains("← 所有卡片")
        compose.onNodeWithTag("card_open_source").assertDoesNotExist()
        compose.onNodeWithText(card.sourceRef.display()).assertExists()
    }

    private fun panel(repo: CaptureAnalysisTestRepository, onSaved: (Card) -> Unit,
        onExisting: ((Card) -> Unit)? = null) {
        val vm = LexiconViewModel(repo).also { models += it }
        compose.setContent {
            LexiconTheme(AppSettings(theme = ThemeMode.DARK)) {
                CapturePanel(capture, emptyList(), emptyList(), vm, {}, onSaved, {}, onOpenExistingCard = onExisting)
            }
        }
        compose.waitUntil(5_000) { repo.probes.isNotEmpty() && !vm.analysisProbeLoading }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }
}
