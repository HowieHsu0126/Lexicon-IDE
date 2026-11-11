package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.AppSettings
import dev.lexicon.domain.BackgroundSource
import dev.lexicon.domain.StudyAssist
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Tests the production evidence card with local evidence fixtures; no network or model calls. */
@RunWith(AndroidJUnit4::class)
class BackgroundEvidenceUiTest {
    @get:Rule val compose = createComposeRule()

    private val source = BackgroundSource(
        id = "S1", title = "Magna Carta", url = "https://en.wikipedia.org/wiki/Magna_Carta",
        excerpt = "Magna Carta is a royal charter of rights agreed to by King John of England in 1215.",
        retrievedAt = 1_798_761_600_000L, provider = "Wikipedia", revisionId = "1234567", license = "CC BY-SA 4.0",
    )
    private val value = StudyAssist(
        kind = "background", title = "Magna Carta", text = "这里借用限制权力的历史象征。[S1]",
        model = "test-model", createdAt = source.retrievedAt, evidence = "来源介绍的是历史背景，不验证本文观点。",
        sources = listOf(source), retrievalStatus = "retrieved", citedSourceIds = listOf("S1"),
    )

    @Test fun retrievedSourceOpensItsExcerptRevisionAndDateThenReturnsToReading() {
        compose.setContent { LexiconTheme(AppSettings()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { StudyAssistContent(value) }
        } }
        compose.onNodeWithText("AI 解释 · 附检索依据").assertIsDisplayed()
        compose.onNodeWithText("[S1] Magna Carta").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText(source.excerpt).assertIsDisplayed()
        compose.onNodeWithText(value.evidence).performScrollTo().assertIsDisplayed()
        val expectedDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(source.retrievedAt))
        compose.onNodeWithText("检索于 $expectedDate").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("CC BY-SA 4.0 · 修订 1234567").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("原始条目 ↗").assertIsEnabled()
        compose.onNodeWithText("返回阅读").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithTag("background_result").assertIsDisplayed()
        compose.onNodeWithText(value.text).assertIsDisplayed()
    }

    @Test fun emptyRetrievalNeverLooksLikeEvidenceBackedAiAndKeepsRetryAvailable() {
        var retried = 0
        // A defensive display test: even stale source objects must not override the result status.
        val noResults = value.copy(text = "没有找到足够的背景资料，请换一个更具体的术语。", retrievalStatus = "no_results")
        compose.setContent { LexiconTheme(AppSettings()) { StudyAssistContent(noResults) { retried++ } } }
        compose.onNodeWithText("暂未找到背景资料").assertIsDisplayed()
        compose.onNodeWithText(noResults.text).assertIsDisplayed()
        compose.onNodeWithText("AI 解释 · 附检索依据").assertDoesNotExist()
        compose.onNodeWithText("检索摘录").assertDoesNotExist()
        compose.onNodeWithText("[S1] Magna Carta").assertDoesNotExist()
        compose.onNodeWithText("重新检索").performClick()
        compose.runOnIdle { assertEquals(1, retried) }
    }

    @Test fun sourceDialogScrollsAtTwoHundredPercentFontAndDismissesWithoutLosingReadingResult() {
        val longSource = source.copy(excerpt = (1..24).joinToString("\n\n") {
            "Historical context $it. A charter describes rights within a particular institution. " +
                "The source should be read with attention to the time and setting of the original document."
        })
        val retrievedOnly = value.copy(model = "retrieval-only", sources = listOf(longSource))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                LexiconTheme(AppSettings()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { StudyAssistContent(retrievedOnly) }
                }
            }
        }
        compose.onNodeWithText("检索摘录").assertIsDisplayed()
        compose.onNodeWithText("AI 解释 · 附检索依据").assertDoesNotExist()
        compose.onNodeWithText("[S1] Magna Carta").performScrollTo().performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithText("CC BY-SA 4.0 · 修订 1234567").assertIsNotDisplayed()
        compose.onNodeWithText("CC BY-SA 4.0 · 修订 1234567").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("en.wikipedia.org").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("返回阅读").assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithTag("background_result").assertIsDisplayed()
    }
}
