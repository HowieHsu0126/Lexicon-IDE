package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingInsightsScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun noteEditsPreserveOriginalQuoteAndCanJoinMindMap() {
        val original = ReadingNote("note", "article", "body", "Evidence matters more than confidence.", "Initial thought", listOf("写作"), 1,
            SourceRef("An article", "A publisher", "https://example.org/story"), start = 12, end = 50)
        var saved = original
        compose.setContent {
            var library by remember { mutableStateOf(ReadingLibrary(notes = listOf(original))) }
            LexiconTheme(AppSettings()) {
                KnowledgeScreen(library, false, { note -> saved = note; library = library.copy(notes = listOf(note)) }, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("编辑").performScrollTo().performClick()
        compose.onNodeWithTag("note_comment").performTextReplacement("Connect claims to evidence.")
        compose.onNodeWithTag("note_tags").performTextReplacement("写作，论证，写作")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals(original.quote, saved.quote)
            assertEquals(original.source, saved.source)
            assertEquals(original.start, saved.start)
            assertEquals(listOf("写作", "论证"), saved.tags)
            assertEquals("Connect claims to evidence.", saved.comment)
        }
        compose.onNodeWithText("+ 加入导图").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(saved.mindMap) }
    }

    @Test fun noteSaveFailureRetainsDraftAndRetryClosesOnlyOnSuccess() {
        val original = ReadingNote("retry-note", "article", "body", "Evidence needs context.", "Initial thought", listOf("写作"), 1,
            SourceRef("Original article", "Publisher"), start = 0, end = 23)
        val attempts = mutableListOf<ReadingNote>()
        var saved: (() -> Unit)? = null
        var failed: ((String) -> Unit)? = null
        var library by mutableStateOf(ReadingLibrary(notes = listOf(original)))
        compose.setContent {
            LexiconTheme(AppSettings()) {
                KnowledgeScreen(library, false, {}, {}, {}, {}, {}, onSaveNoteAsync = { note, onSaved, onError ->
                    attempts += note
                    saved = onSaved
                    failed = onError
                })
            }
        }
        compose.onNodeWithText("编辑").performScrollTo().performClick()
        compose.onNodeWithTag("note_comment").performTextReplacement("Keep the claim and its context together.")
        compose.onNodeWithTag("note_tags").performTextReplacement("论证，语境，论证")
        compose.onNodeWithTag("note_save").performClick()
        compose.onNodeWithText("编辑笔记").assertIsDisplayed()
        compose.onNodeWithTag("note_save").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("note_cancel").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, attempts.size) }
        val firstSuccess = saved
        compose.runOnIdle { failed?.invoke("本机存储暂不可用，请重试") }
        compose.onNodeWithTag("note_save_error").assertTextEquals("本机存储暂不可用，请重试")
        compose.onNodeWithTag("note_comment").assertTextContains("Keep the claim and its context together.")
        compose.onNodeWithTag("note_tags").assertTextContains("论证，语境，论证")
        compose.onNodeWithTag("note_save").assertIsEnabled()
        // A completed failed attempt cannot close a newer editor state if a stale callback arrives.
        compose.runOnIdle { firstSuccess?.invoke() }
        compose.onNodeWithText("编辑笔记").assertIsDisplayed()
        compose.onNodeWithTag("note_save").performClick()
        compose.onNodeWithTag("note_save_error").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(2, attempts.size)
            assertEquals(attempts.first(), attempts.last())
            assertEquals(original.quote, attempts.last().quote)
            assertEquals(original.source, attempts.last().source)
            assertEquals(listOf("论证", "语境"), attempts.last().tags)
            library = library.copy(notes = listOf(attempts.last()))
        }
        // The list may publish the saved note before the operation acknowledges completion.
        compose.onNodeWithText("编辑笔记").assertIsDisplayed()
        compose.runOnIdle { saved?.invoke() }
        compose.onNodeWithText("编辑笔记").assertDoesNotExist()
        compose.onNodeWithText("Keep the claim and its context together.").performScrollTo().assertIsDisplayed()
    }

    @Test fun immediateRepeatedNoteFailuresRemainRetryableWithoutLosingText() {
        val note = ReadingNote("quick-failure", null, "body", "An exact quotation.", "", emptyList(), 1, SourceRef())
        val attempts = mutableListOf<ReadingNote>()
        compose.setContent {
            LexiconTheme(AppSettings()) {
                KnowledgeScreen(ReadingLibrary(notes = listOf(note)), false, {}, {}, {}, {}, {},
                    onSaveNoteAsync = { updated, _, failed -> attempts += updated; failed("笔记未保存，请重试") })
            }
        }
        compose.onNodeWithText("编辑").performScrollTo().performClick()
        compose.onNodeWithTag("note_comment").performTextReplacement("Do not discard this draft.")
        repeat(2) {
            compose.onNodeWithTag("note_save").performClick()
            compose.onNodeWithTag("note_save_error").assertTextEquals("笔记未保存，请重试")
            compose.onNodeWithTag("note_save").assertIsEnabled()
            compose.onNodeWithTag("note_comment").assertTextContains("Do not discard this draft.")
        }
        compose.runOnIdle { assertEquals(2, attempts.size); assertEquals(attempts.first(), attempts.last()) }
    }

    @Test fun emptyProgressDoesNotInventReadingSpeedOrFeedback() {
        compose.setContent { LexiconTheme(AppSettings()) { ProgressScreen(ReadingLibrary(), emptyList(), {}) } }
        compose.onNodeWithText("—").assertIsDisplayed()
        compose.onNodeWithText("还没有阅读速度估计记录").assertIsDisplayed()
        compose.onNodeWithText("理解反馈").performClick()
        compose.onNodeWithText("还没有理解反馈记录").assertIsDisplayed()
        compose.onNodeWithTag("progress_metric_assistance").performScrollTo().performClick()
        compose.onNodeWithText("还没有成功辅助操作记录").assertIsDisplayed()
    }

    @Test fun appearanceFontChoiceUpdatesRealPreference() {
        var selected = ReaderPreferences()
        compose.setContent {
            var prefs by remember { mutableStateOf(ReaderPreferences()) }
            LexiconTheme(AppSettings()) {
                ReaderAppearanceSheet(prefs, { prefs = it; selected = it }, {})
            }
        }
        compose.onNodeWithText("OpenDyslexic").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ReaderFont.OPEN_DYSLEXIC, selected.font) }
        compose.onNodeWithTag("reader_appearance_preview").assertIsDisplayed()
    }

    @Test fun taggedKnowledgeMapKeepsRealLinksAndOpensTheSelectedSource() {
        val science = ReadingNote("science", "article-science", "body", "Evidence supports the scientific claim.", "Compare the methods.",
            listOf("科学", "论证"), 2, SourceRef("Science source"), mindMap = true)
        val culture = ReadingNote("culture", "article-culture", "body", "This story describes a cultural tradition.", "", listOf("文化"), 1,
            SourceRef("Culture source"), mindMap = true)
        var opened: String? = null
        compose.setContent {
            LexiconTheme(AppSettings()) {
                KnowledgeScreen(ReadingLibrary(notes = listOf(science, culture)), false, {}, {}, {}, {}, { opened = it })
            }
        }
        compose.onNodeWithTag("knowledge_map_filter").performClick()
        compose.onNodeWithTag("knowledge_tag_科学").performScrollTo().performClick()
        compose.onNodeWithTag("knowledge_map_note_culture").assertDoesNotExist()
        compose.onNodeWithTag("knowledge_map_note_science").performScrollTo().performClick()
        compose.onNodeWithText("阅读笔记").assertIsDisplayed()
        compose.onNodeWithText("阅读原文").performClick()
        compose.runOnIdle { assertEquals("article-science", opened) }
    }

    @Test fun assistanceUsesActualTimeAndModeGrowthDeduplicatesSessions() {
        val started = 1_700_000_000_000L
        fun session(id: String, mode: StudyMode, millis: Long, requests: Int) = ReadingSession(
            id = id, articleId = "article-$id", mode = mode, topic = "Science & Technology", startedAt = started,
            finishedAt = started + millis, activeMillis = millis, wordsRead = 320, paragraphsRead = 4, totalParagraphs = 4,
            assistanceCount = requests, completed = true)
        val intensive = session("intensive", StudyMode.INTENSIVE, 120_000, 2)
        val skim = session("skim", StudyMode.SKIM, 180_000, 3)
        compose.setContent {
            LexiconTheme(AppSettings()) { ProgressScreen(ReadingLibrary(sessions = listOf(intensive, skim, intensive)), emptyList(), {}) }
        }
        compose.onNodeWithTag("progress_metric_assistance").performScrollTo().performClick()
        compose.onNodeWithText("10 次 / 10 分钟", substring = true).assertIsDisplayed()
        compose.onNodeWithText("✓ 首次精读").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("○ 三次精读").assertIsDisplayed()
        compose.onNodeWithText("✓ 首次略读").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("○ 三次略读").assertIsDisplayed()
    }

    @Test fun noteOriginsStayVisibleInListMapAndDetail() {
        val ai = ReadingNote("ai", "source", "ai-body", "A simplified explanation written by AI.", "", listOf("阅读"), 3,
            SourceRef("Original article"), mindMap = true, origin = ContextOrigin.AI_GENERATED)
        val user = ai.copy(id = "user", createdAt = 2, origin = ContextOrigin.USER_SUPPLIED, quote = "A phrase supplied by the reader.")
        val original = ai.copy(id = "original", createdAt = 1, origin = ContextOrigin.ORIGINAL, quote = "An unchanged quotation from the article.")
        compose.setContent {
            LexiconTheme(AppSettings()) { KnowledgeScreen(ReadingLibrary(notes = listOf(ai, user, original)), false, {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithTag("note_origin_ai_list").performScrollTo().assertTextEquals("AI 改写片段")
        compose.onNodeWithTag("note_origin_user_list").performScrollTo().assertTextEquals("用户补充片段")
        compose.onNodeWithTag("note_origin_original_list").performScrollTo().assertTextEquals("原文片段")
        compose.onNodeWithTag("knowledge_map_filter").performScrollTo().performClick()
        compose.onNodeWithTag("note_origin_ai_map", useUnmergedTree = true).performScrollTo().assertTextEquals("AI 改写片段")
        compose.onNodeWithTag("knowledge_map_note_ai").performScrollTo().performClick()
        compose.onNodeWithTag("note_origin_ai_detail").assertTextEquals("AI 改写片段")
    }

    @Test fun articleMindMapFitsPhoneAndExpandsBranchesAtLargeFont() {
        val nodes = listOf(
            MindMapNode("root", null, "文章观点"),
            MindMapNode("claim", "root", "研究背景与主要论点", 0),
            MindMapNode("evidence", "claim", "证据与限制需要结合原文逐项理解", 2),
            MindMapNode("conclusion", "root", "结论", 3),
        )
        var jumped: Int? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                LexiconTheme(AppSettings()) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        MindMapView(nodes) { jumped = it }
                    }
                }
            }
        }
        compose.onNodeWithTag("mind_map_node_root").assertIsDisplayed()
        compose.onNodeWithTag("mind_map_node_claim").assertIsDisplayed()
        compose.onNodeWithTag("mind_map_node_evidence").assertDoesNotExist()
        val rootBounds = compose.onNodeWithTag("mind_map_node_root").fetchSemanticsNode().boundsInRoot
        val branchBounds = compose.onNodeWithTag("mind_map_node_claim").fetchSemanticsNode().boundsInRoot
        assertTrue(rootBounds.top < branchBounds.top)
        assertTrue(branchBounds.right <= rootBounds.right + 1f)
        compose.onNodeWithTag("mind_map_toggle_claim").performClick()
        compose.onNodeWithTag("mind_map_paragraph_evidence").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, jumped) }
        compose.onNodeWithTag("mind_map_toggle_claim").performScrollTo().performClick()
        compose.onNodeWithTag("mind_map_node_evidence").assertDoesNotExist()
    }
}
