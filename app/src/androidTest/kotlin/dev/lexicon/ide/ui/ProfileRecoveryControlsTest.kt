package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileRecoveryControlsTest {
    @get:Rule val compose = createComposeRule()
    private val corrupt = PersonalizationHealth(PersonalizationHealthStatus.CORRUPT, "frozen-fixture-digest")
    private val receipt = PersonalizationRecoveryReceipt(PersonalizationProfile(enabled = false), "fixture-backup", "frozen-fixture-digest", 128, true)

    @Test fun normalAndMissingPreferencesDoNotOfferResetOrPerformAnAction() {
        var health by mutableStateOf(PersonalizationHealth(PersonalizationHealthStatus.MISSING))
        var recoveries = 0
        compose.setContent { LexiconTheme(AppSettings()) {
            ProfileRecoveryControls(health, false, null, null, { recoveries++ })
        } }
        compose.onNodeWithTag("profile_recovery").assertDoesNotExist()
        compose.runOnIdle { health = PersonalizationHealth(PersonalizationHealthStatus.OK) }
        compose.onNodeWithTag("profile_recovery").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, recoveries) }
    }

    @Test fun corruptionNeedsOneExplicitActionAndPendingRecoveryDisablesRepeatClicks() {
        var busy by mutableStateOf(false)
        var calls = 0
        compose.setContent { LexiconTheme(AppSettings()) {
            ProfileRecoveryControls(corrupt, busy, null, null, { calls++; busy = true })
        } }
        compose.onNodeWithTag("profile_recovery_consequence").assertTextEquals("恢复默认训练偏好，原档案保留在本机。")
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag("profile_recovery_action").performClick().assertIsNotEnabled()
        compose.onNodeWithText("正在恢复…").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithText(corrupt.rowDigest!!).assertDoesNotExist()
    }

    @Test fun unverifiedBackupNeverShowsSuccessAndVerifiedRecoveryKeepsMemoryDisabled() {
        var result by mutableStateOf(receipt.copy(backupExists = false))
        compose.setContent { LexiconTheme(AppSettings()) {
            ProfileRecoveryControls(PersonalizationHealth(PersonalizationHealthStatus.OK), false, null, result, {})
        } }
        compose.onNodeWithTag("profile_recovery_success").assertDoesNotExist()
        compose.runOnIdle { result = receipt }
        compose.onNodeWithTag("profile_recovery_success").assertIsDisplayed()
        compose.onNodeWithText("训练设置记忆已关闭，可按需重新开启。").assertIsDisplayed()
        compose.onNodeWithText(receipt.backupKey).assertDoesNotExist()
        compose.onNodeWithText(receipt.originalDigest).assertDoesNotExist()
    }

    @Test fun recoveryErrorAndActionRemainReadableOnDraculaAtTwoHundredPercent() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                LexiconTheme(AppSettings(theme = ThemeMode.DARK)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ProfileRecoveryControls(corrupt, false, "偏好已变化，请重新检查后再恢复。", null, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("profile_recovery_action").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("profile_recovery_error").performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("profile_recovery_consequence").performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
    }
}
