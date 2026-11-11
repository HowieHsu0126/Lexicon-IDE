package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
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

@RunWith(AndroidJUnit4::class)
class BackgroundKnowledgeUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = System.currentTimeMillis()
    private val query = BackgroundQuery("White House", "The institution announced a synthetic proposal.")
    private val models = mutableListOf<ViewModel>()
    private fun model(repo: BackgroundResearchTestRepository) = BackgroundKnowledgeViewModel(repo).also { models += it }
    private fun record() = backgroundFixture(query, now).let { BackgroundRecords.create(it, "administration", now, backgroundExplanation(it, "administration")) }
    @After fun dispose() { InstrumentationRegistry.getInstrumentation().runOnMainSync { models.forEach { it.viewModelScope.cancel() } } }

    @Test fun knowledgeTabExposesSavedBackgroundAndSearchWithoutReadingResearchCaches() {
        val frozen = record(); val repo = BackgroundResearchTestRepository(now)
        compose.setContent { LexiconTheme(AppSettings(theme = ThemeMode.LIGHT)) {
            KnowledgeScreen(ReadingLibrary(backgroundRecords = listOf(frozen)), false, {}, {}, {}, {}, {}, backgroundRepository = repo)
        } }
        compose.onNodeWithTag("knowledge_tab_background").performClick()
        node("background_record_${frozen.id}").assertExists()
        node("background_knowledge_search").performTextReplacement("proposal")
        node("background_record_${frozen.id}").assertExists()
        node("background_knowledge_search").performTextReplacement("unrelated")
        compose.onNodeWithTag("background_record_${frozen.id}").assertDoesNotExist()
        compose.onNodeWithText("没有匹配的留存记录。").assertExists()
        compose.runOnIdle { assertTrue(repo.cachedQueries.isEmpty()); assertTrue(repo.readLookupCalls.isEmpty()); assertTrue(repo.lookupCalls.isEmpty()) }
        compose.onNodeWithTag("knowledge_tab_notes").performClick()
        node("knowledge_export").assertIsEnabled()
    }

    @Test fun savedDetailRetainsExactContextExplanationAndSelectedEvidenceWithoutAnyCache() {
        val frozen = record(); val repo = BackgroundResearchTestRepository(now); val vm = model(repo)
        compose.setContent { Panel(repo, vm, listOf(frozen)) }
        node("background_record_${frozen.id}").performClick()
        node("background_record_context").assertTextEquals(frozen.lookup.query.context)
        node("background_record_explanation").assertTextEquals(requireNotNull(frozen.explanation).text)
        compose.onNodeWithTag("background_record_evidence").assertDoesNotExist()
        node("background_record_evidence_toggle").performClick()
        node("background_record_evidence").assertTextEquals(requireNotNull(frozen.explanation).evidence)
        node("background_record_source_S1").performClick()
        node("background_record_excerpt_S1").assertTextEquals(BackgroundResearch.selectedSources(frozen.lookup, "administration").single().excerpt)
        compose.onNodeWithTag("background_record_source_S2").assertDoesNotExist()
        compose.onNodeWithText("检索时语境").assertExists()
        compose.runOnIdle {
            assertTrue(repo.cachedQueries.isEmpty()); assertTrue(repo.recordReadCalls.isEmpty()); assertTrue(repo.readLookupCalls.isEmpty())
            assertTrue(repo.lookupCalls.isEmpty()); assertTrue(repo.explainCalls.isEmpty())
        }
        node("background_record_close").performClick()
        compose.waitUntil { vm.selectedRecord == null }
        compose.onNodeWithTag("background_record_detail").assertDoesNotExist()
    }

    @Test fun editorSavesOnlyPersonalCommentAndTagsAndLeavesSourcesIntact() {
        val frozen = record(); val repo = BackgroundResearchTestRepository(now).apply { records[frozen.id] = frozen }; val vm = model(repo)
        compose.setContent { Panel(repo, vm, listOf(frozen)) }
        node("background_record_${frozen.id}").performClick(); node("background_record_edit").performClick()
        node("background_record_comment").performTextReplacement("我对机构名称的理解")
        node("background_record_tags").performTextReplacement("制度，# 表达，制度")
        dialogButton("background_record_save").performClick()
        compose.waitUntil { !vm.editing && vm.selectedRecord?.comment == "我对机构名称的理解" }
        compose.runOnIdle {
            val result = requireNotNull(repo.records[frozen.id])
            assertEquals(listOf("制度", "表达"), result.tags); assertEquals(frozen.lookup, result.lookup); assertEquals(frozen.explanation, result.explanation)
            assertEquals(frozen.revision, repo.updateCalls.single().revision); assertTrue(repo.explainCalls.isEmpty()); assertTrue(repo.lookupCalls.isEmpty())
        }
    }

    @Test fun staleEditKeepsEnteredDraftVisibleAndDoesNotOverwriteNewerRecord() {
        val frozen = record(); val newer = BackgroundRecords.updateMetadata(frozen, 1, "Existing update", listOf("saved"), now)
        val repo = BackgroundResearchTestRepository(now).apply { records[frozen.id] = newer }; val vm = model(repo)
        compose.setContent { Panel(repo, vm, listOf(frozen)) }
        node("background_record_${frozen.id}").performClick(); node("background_record_edit").performClick()
        node("background_record_comment").performTextReplacement("My retained draft")
        node("background_record_tags").performTextReplacement("draft")
        dialogButton("background_record_save").performClick()
        compose.waitUntil { vm.mutationError != null }
        node("background_record_edit_error").assertExists()
        node("background_record_comment").assertTextContains("My retained draft")
        node("background_record_tags").assertTextContains("draft")
        compose.runOnIdle { assertEquals(newer, repo.records[frozen.id]); assertTrue(vm.editing) }
        dialogButton("background_record_cancel").performClick()
        compose.waitUntil { !vm.editing }
    }

    @Test fun deleteIsConfirmedAndCancelLeavesTheSavedSnapshot() {
        val frozen = record(); val repo = BackgroundResearchTestRepository(now).apply { records[frozen.id] = frozen }; val vm = model(repo)
        compose.setContent { Panel(repo, vm, listOf(frozen)) }
        node("background_record_${frozen.id}").performClick(); node("background_record_delete").performClick()
        dialogButton("background_record_confirm_delete").assertIsEnabled()
        compose.runOnIdle { assertTrue(repo.deleteCalls.isEmpty()) }
        dialogButton("background_record_cancel_delete").performClick()
        compose.runOnIdle { assertEquals(frozen, repo.records[frozen.id]); assertTrue(repo.deleteCalls.isEmpty()) }
        node("background_record_delete").performClick(); dialogButton("background_record_confirm_delete").performClick()
        compose.waitUntil { vm.selectedRecord == null }
        compose.runOnIdle { assertEquals(listOf(frozen.id to frozen.revision), repo.deleteCalls); assertNull(repo.records[frozen.id]) }
    }

    @Test fun recentReceiptCanBeChosenAndSavedWithoutANewLookupOrExplanation() {
        val lookup = backgroundFixture(query, now - 1000)
        val repo = BackgroundResearchTestRepository(now).apply {
            receipts[lookup.id] = lookup; cachedLookup = backgroundFixture(query, now)
            explanations[lookup.id to "administration"] = backgroundExplanation(lookup, "administration")
        }
        val vm = model(repo)
        compose.setContent { Panel(repo, vm, emptyList()) }
        node("background_knowledge_recent_tab").performClick()
        compose.waitUntil { !vm.loading && vm.recent.isNotEmpty() }
        node("background_recent_${lookup.id}").performClick()
        compose.waitUntil { compose.onAllNodesWithTag("background_bound_term").fetchSemanticsNodes().isNotEmpty() }
        node("background_candidate_administration").performClick()
        compose.waitUntil { compose.onAllNodesWithTag("background_explanation").fetchSemanticsNodes().isNotEmpty() }
        node("background_save_record").assertIsEnabled().performClick()
        compose.waitUntil { repo.records.isNotEmpty() }
        node("background_record_saved").assertExists()
        compose.runOnIdle {
            val saved = repo.records.values.single()
            assertEquals(lookup, saved.lookup); assertEquals("administration", saved.selectedCandidateId)
            assertEquals(repo.explanations[lookup.id to "administration"], saved.explanation)
            assertEquals(listOf(lookup.id), repo.readLookupCalls); assertTrue(repo.cachedQueries.isEmpty())
            assertTrue(repo.lookupCalls.isEmpty()); assertTrue(repo.explainCalls.isEmpty())
        }
        compose.onNodeWithTag("background_refresh").assertDoesNotExist()
        compose.onNodeWithTag("background_edit_query").assertDoesNotExist()
        node("background_continue_reading").performClick()
        compose.waitUntil { vm.historyId == null }
    }

    @Test fun corruptRecordsAndHistoryFailuresAreVisibleInsteadOfAnEmptySuccess() {
        val repo = BackgroundResearchTestRepository(now).apply { recentAction = { error("Synthetic read failure") } }; val vm = model(repo)
        compose.setContent { Panel(repo, vm, emptyList(), errors = 2) }
        node("background_record_load_errors").assertTextContains("2 条", substring = true)
        node("background_knowledge_recent_tab").performClick()
        compose.waitUntil { vm.error != null }
        node("background_recent_retry").assertExists()
        compose.onNodeWithText("还没有检索记录。").assertDoesNotExist()
        compose.runOnIdle { assertTrue(repo.explainCalls.isEmpty()); assertTrue(repo.lookupCalls.isEmpty()) }
    }

    @Test fun appendActionOnlyAppearsForARecordWithoutAnExplanation() {
        val lookup = backgroundFixture(query, now, false); val explanation = backgroundExplanation(lookup, "building")
        val frozen = BackgroundRecords.create(lookup, "building", now, explanation)
        val repo = BackgroundResearchTestRepository(now).apply {
            cachedLookup = lookup; records[frozen.id] = frozen
            explanations[lookup.id to "building"] = explanation.copy(text = "另一份测试解释。[S1]")
        }
        val vm = BackgroundResearchViewModel(repo, clock = { now }).also { models += it }
        compose.setContent { LexiconTheme(AppSettings(theme = ThemeMode.DARK)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                BackgroundResearchPanel(vm, "append", query, {})
            }
        } }
        compose.waitUntil { vm.savedRecord != null && vm.explanation != null }
        compose.onNodeWithTag("background_evidence").assertDoesNotExist()
        node("background_evidence_toggle").performClick()
        node("background_evidence").assertTextEquals(requireNotNull(vm.explanation).evidence)
        node("background_record_saved").assertExists()
        compose.onNodeWithTag("background_append_explanation").assertDoesNotExist()
        compose.runOnIdle { assertTrue(repo.saveCalls.isEmpty()); assertTrue(repo.explainCalls.isEmpty()) }
    }

    @Test fun evidenceDisclosureResetsWhenTheRecordOrExplanationChanges() {
        val first = requireNotNull(record().explanation).copy(evidence = "完整依据第一段。\n" + "这段说明保留所有可供核对的限制与出处。".repeat(12))
        var identity by mutableStateOf("receipt:first")
        var displayed by mutableStateOf(first)
        compose.setContent { LexiconTheme(AppSettings(theme = ThemeMode.DARK)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                BackgroundEvidenceDisclosure(identity, displayed)
            }
        } }
        compose.onNodeWithTag("background_evidence").assertDoesNotExist()
        node("background_evidence_toggle").performClick()
        node("background_evidence").assertTextEquals(first.evidence)
        compose.runOnIdle { identity = "receipt:second" }
        compose.onNodeWithTag("background_evidence").assertDoesNotExist()
        node("background_evidence_toggle").performClick()
        node("background_evidence").assertTextEquals(first.evidence)
        val changed = first.copy(evidence = "另一版本的完整依据；先由读者主动展开。")
        compose.runOnIdle { displayed = changed }
        compose.onNodeWithTag("background_evidence").assertDoesNotExist()
        node("background_evidence_toggle").performClick()
        node("background_evidence").assertTextEquals(changed.evidence)
    }

    @Composable private fun Panel(repo: BackgroundResearchTestRepository, vm: BackgroundKnowledgeViewModel, records: List<BackgroundRecord>, errors: Int = 0) {
        LexiconTheme(AppSettings(theme = ThemeMode.DARK)) { BackgroundKnowledgePanel(records, errors, repo, {}, vm) }
    }
    private fun node(tag: String) = compose.onNodeWithTag(tag).performScrollTo()
    private fun dialogButton(tag: String) = compose.onNodeWithTag(tag).assertIsDisplayed()
}
