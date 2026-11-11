package dev.lexicon.ide.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI reachability complements the independently controlled ViewModel write races. */
@RunWith(AndroidJUnit4::class)
class SpeedSetupMemoryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedSetupOffersASeparateSaveRetryWithoutStartingTraining() {
        var retries = 0
        var preparations = 0
        var state by mutableStateOf(SpeedUiState(personalizationAvailable = true,
            personalizationError = "训练偏好尚未保存，请重试。", groupsPerMinute = 97))
        val article = Article("setup-retry-ui", "fixture", emptyList(), "A comfortable reading rhythm", "", "")
        val text = "A reader chooses a comfortable rhythm."
        val body = ArticleBody(article.id, text, fingerprint(text), 1, Long.MAX_VALUE, "Self-authored UI fixture")
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedReadingScreen(article, body, AppSettings(), state,
                SpeedUiActions(onPrepare = { preparations++ }, onRetrySetupSettings = {
                    retries++
                    state = state.copy(personalizationBusy = true)
                }))
        } }
        val retry = compose.onNodeWithTag("speed_retry_setup_save")
        retry.performScrollTo().assertIsEnabled().performTouchInput { click(center) }
        retry.assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(0, preparations)
            assertEquals(97, state.groupsPerMinute)
            state = state.copy(personalizationBusy = false, personalizationError = null)
        }
        retry.assertDoesNotExist()
    }

    @Test fun aPendingPreferenceWriteStillAcceptsTheLatestSwitchChoice() {
        val requests = mutableListOf<Boolean>()
        var state by mutableStateOf(SpeedUiState(personalizationAvailable = true,
            personalizationBusy = true, profile = PersonalizationProfile(enabled = true)))
        val article = Article("setup-memory-ui", "fixture", emptyList(), "A comfortable reading rhythm", "", "")
        val text = "A reader chooses a comfortable rhythm."
        val body = ArticleBody(article.id, text, fingerprint(text), 1, Long.MAX_VALUE, "Self-authored UI fixture")
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedReadingScreen(article, body, AppSettings(), state,
                SpeedUiActions(onRememberTraining = { choice ->
                    requests += choice
                    state = state.copy(rememberTrainingChoice = choice)
                }))
        } }
        val control = compose.onNodeWithTag("speed_remember_settings")
        control.performScrollTo().assertIsEnabled().assertIsOn()
        control.performTouchInput { click(center) }
        control.assertIsOff().assertIsEnabled()
        control.performTouchInput { advanceEventTime(600); click(center) }
        control.assertIsOn()
        compose.runOnIdle {
            assertEquals(listOf(false, true), requests)
            state = state.copy(personalizationBusy = false, rememberTrainingChoice = null,
                profile = state.profile.copy(enabled = false))
        }
        control.assertIsOff()
        compose.runOnIdle { state = state.copy(loading = true) }
        control.assertIsNotEnabled()
    }
}
