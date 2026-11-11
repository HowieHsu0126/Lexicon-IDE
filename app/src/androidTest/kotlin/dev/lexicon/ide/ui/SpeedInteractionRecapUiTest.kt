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
class SpeedInteractionRecapUiTest {
    @get:Rule val compose = createComposeRule()
    private val text = "The report provides evidence. It also acknowledges uncertainty."
    private val second = text.indexOf("It also")
    private val body = ArticleBody("article", text, fingerprint(text), 1, Long.MAX_VALUE, "Self-authored fixture")
    private val plan = SpeedPlan("article", body.contentHash, SpeedGranularity.L2, listOf(0),
        listOf(SenseChunk(0, second, 0, 0, 4, "sentence"), SenseChunk(second, text.length, 0, 1, 4, "sentence")), planKey = "plan")
    private val run = SpeedRun("run", "article", body.contentHash, SpeedGranularity.L2, "plan", 0, 10_000, 9_000,
        8, 2, listOf(6_000L, 3_000L), 1, 1, emptyList(), 0, true, 60, "rules")
    private val summary = SpeedInteractionSummary(listOf(SpeedInteractionItem(0, 2, 1, 1, 6_000, 1_000, 3_000.0, 3.0)),
        2, setOf(SpeedInteractionLimit.PLAYBACK_ONLY))

    @Test fun explicitInteractionRecapStartsCollapsedAndKeepsTimeDenominatorsClear() {
        compose.setContent { LexiconTheme(AppSettings()) { SpeedInteractionRecapControls(summary, run, plan, body) } }
        compose.onNodeWithTag("speed_interaction_quote_0").assertDoesNotExist()
        compose.onNodeWithTag("speed_interaction_toggle").performClick()
        compose.onNodeWithTag("speed_interaction_quote_0").assertTextEquals(text.substring(0, second))
        compose.onNodeWithText("访问 2 次 · 主动暂停 1 次 · 回看 1 次").assertIsDisplayed()
        compose.onNodeWithText("累计播放").assertIsDisplayed()
        compose.onNodeWithText("6.0 秒").assertIsDisplayed()
        compose.onNodeWithText("每次平均").assertIsDisplayed()
        compose.onNodeWithText("3.0 秒").assertIsDisplayed()
        compose.onNodeWithText("计划每次").assertIsDisplayed()
        compose.onNodeWithText("3.00 倍").assertIsDisplayed()
    }

    @Test fun bodyWithTheWrongHashCannotSupplyAnApparentlyMatchingQuote() {
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedInteractionRecapControls(summary, run, plan, body.copy(contentHash = "different-source"))
        } }
        compose.onNodeWithTag("speed_interaction_toggle").performClick()
        compose.onNodeWithTag("speed_interaction_quote_0").assertTextEquals("原文片段暂不可用")
        compose.onNodeWithText(text.substring(0, second)).assertDoesNotExist()
    }

    @Test fun legacyRecordsDoNotInventZeroPausesOrPerVisitTiming() {
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedInteractionRecapControls(SpeedInteractionSummary(emptyList(), 0,
                setOf(SpeedInteractionLimit.PLAYBACK_ONLY, SpeedInteractionLimit.MISSING_VISIT_DATA)), run, plan, body)
        } }
        compose.onNodeWithTag("speed_interaction_toggle").performClick()
        compose.onNodeWithTag("speed_interaction_unavailable").assertTextEquals("这条历史记录未保存逐块访问，无法回看本项。")
        compose.onNodeWithTag("speed_interaction_empty").assertDoesNotExist()
        compose.onNodeWithText("每次平均").assertDoesNotExist()
    }

    @Test fun variablePaceShowsObservedTimesWithoutAnExpectedDwellRatioAtLargeFont() {
        val variable = summary.copy(items = listOf(summary.items.single().copy(plannedMillisPerVisit = null, normalizedMeanDwell = null)),
            limitations = setOf(SpeedInteractionLimit.PLAYBACK_ONLY, SpeedInteractionLimit.VARIABLE_PACE))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                LexiconTheme(AppSettings(theme = ThemeMode.DARK)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { SpeedInteractionRecapControls(variable, run, plan, body) }
                }
            }
        }
        compose.onNodeWithTag("speed_interaction_toggle").performClick()
        compose.onNodeWithText("每次平均").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("计划每次").assertDoesNotExist()
        compose.onNodeWithText("3.00 倍").assertDoesNotExist()
        compose.onNodeWithText("本轮调整过节奏，未比较计划停留。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("speed_interaction_scope").performScrollTo()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("speed_interaction_scope").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
    }

    @Test fun normalAutoplayDoesNotProduceATopThreeOfSupposedDifficultChunks() {
        compose.setContent { LexiconTheme(AppSettings()) {
            SpeedInteractionRecapControls(summary.copy(items = emptyList()), run, plan, body)
        } }
        compose.onNodeWithTag("speed_interaction_toggle").performClick()
        compose.onNodeWithTag("speed_interaction_empty").assertTextEquals("未记录主动回看或暂停。")
        compose.onNodeWithTag("speed_interaction_quote_0").assertDoesNotExist()
    }

    @Test fun inspectionShowsOnlyMatchingFrozenSnippetsAndOpensWithoutAnotherDisclosureClick() {
        var state by mutableStateOf(SpeedRecapInspection(run, plan, summary,
            snippets = mapOf(0 to text.substring(0, second)), loading = false))
        var closed = 0
        compose.setContent { LexiconTheme(AppSettings()) { SpeedRecapInspectionDialog(state) { closed++ } } }
        compose.onNodeWithTag("speed_interaction_quote_0").assertTextEquals(text.substring(0, second))
        compose.runOnIdle { state = state.copy(snippets = mapOf(0 to "wrong length")) }
        compose.onNodeWithTag("speed_interaction_quote_0").assertTextEquals("原文片段暂不可用")
        compose.onNodeWithTag("speed_recap_inspection_close").performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun loadingInspectionKeepsUnknownDataHiddenAndLegacyInspectionExplainsTheGap() {
        var state by mutableStateOf(SpeedRecapInspection(run))
        compose.setContent { LexiconTheme(AppSettings()) { SpeedRecapInspectionDialog(state) {} } }
        compose.onNodeWithText("正在读取记录…").assertIsDisplayed()
        compose.onNodeWithText("操作记录暂不可用。").assertDoesNotExist()
        compose.onNodeWithTag("speed_interaction_recap").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loading = false, plan = plan, summary = SpeedInteractionSummary(emptyList(), 0,
            setOf(SpeedInteractionLimit.PLAYBACK_ONLY, SpeedInteractionLimit.MISSING_VISIT_DATA))) }
        compose.onNodeWithTag("speed_interaction_unavailable").assertTextEquals("这条历史记录未保存逐块访问，无法回看本项。")
        compose.onNodeWithText("累计播放").assertDoesNotExist()
    }
}
