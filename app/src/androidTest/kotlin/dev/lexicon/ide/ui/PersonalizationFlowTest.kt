package dev.lexicon.ide.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.data.LexiconDatabase
import dev.lexicon.data.LocalPersonalizationRepository
import dev.lexicon.data.LocalSpeedReadingRepository
import dev.lexicon.data.SpeedPlanGenerator
import dev.lexicon.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic evidence in an isolated in-memory DB; no production user data or API is accessed. */
@RunWith(AndroidJUnit4::class)
class PersonalizationFlowTest {
    @get:Rule val compose = createComposeRule()
    private var db: LexiconDatabase? = null
    private val controllers = mutableListOf<SpeedReadingViewModel>()
    private val generatorCalls = AtomicInteger()
    private val generator = SpeedPlanGenerator { _, body, proposal ->
        generatorCalls.incrementAndGet()
        val paragraphs = ParagraphFocus.paragraphs(body.text)
        proposal.copy(origin = "ai", model = "synthetic-personalization/no-network", questions = proposal.paragraphIndices.map { index ->
            val range = paragraphs[index]
            SpeedQuestion("synthetic-q$index", index, "Which project is discussed?",
                listOf("A community learning project", "An international flight", "A sporting event"), 0,
                range.start, range.endExclusive, "Synthetic test answer; not an assessment of a real reader.")
        })
    }

    @After fun releaseResources() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controllers.forEach { it.pause(); it.viewModelScope.cancel() }
        }
        db?.close()
    }

    private fun article(index: Int) = Article("synthetic-personalization-$index", "synthetic-$index", listOf("synthetic"),
        "Synthetic community project $index", "https://example.org/synthetic-personalization-$index", "",
        readingMode = ReadingMode.NATIVE, sourceName = "Synthetic test fixture")

    private fun body(article: Article): ArticleBody {
        val text = if (article.id.endsWith("1")) {
            "Residents discuss a community learning project at a local library.\n\nVolunteers arrange book groups and explain how families can borrow study materials.\n\nThe committee collects feedback before choosing the reading room's opening hours."
        } else {
            "Families propose a community learning project in a shared garden.\n\nVolunteers teach children how to plant vegetables and measure their growth.\n\nThe committee surveys residents before deciding when to hold outdoor workshops."
        }
        return ArticleBody(article.id, text, fingerprint(text), 0, Long.MAX_VALUE, "Self-authored synthetic fixture")
    }

    private fun controller(speed: LocalSpeedReadingRepository, preferences: LocalPersonalizationRepository): SpeedReadingViewModel {
        lateinit var vm: SpeedReadingViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = SpeedReadingViewModel(speed, personalizationRepository = preferences)
            controllers += vm
        }
        return vm
    }

    @Test fun realRepositoryAndScreenApplyUndoAndRetainTheDecisionAfterRecreation() {
        val database = Room.inMemoryDatabaseBuilder<LexiconDatabase>(ApplicationProvider.getApplicationContext<Context>())
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build().also { db = it }
        val speed = LocalSpeedReadingRepository(database, generator, hasKey = { true }, sourceAllowed = { true })
        val preferences = LocalPersonalizationRepository(database)
        assertNotEquals(body(article(1)).contentHash, body(article(2)).contentHash)
        // Use the production plan/run write path. Durations and correct answers are explicitly synthetic.
        runBlocking {
            for (index in 1..2) {
                val article = article(index); val body = body(article)
                val plan = speed.prepare(article, body, SpeedGranularity.L2, 0, 3, true, true)
                val dwell = List(plan.chunks.size) { 1000L }
                val started = index * 1000L
                speed.saveRun(SpeedRun("synthetic-run-$index", article.id, body.contentHash, plan.granularity, plan.planKey,
                    started, started + dwell.sum(), dwell.sum(), plan.chunks.sumOf { it.wordCount }, plan.chunks.size,
                    dwell, 0, 0, plan.questions.map { SpeedAnswer(it.id, it.answerIndex, true) }, plan.questions.size,
                    completed = true, groupsPerMinute = 60, planOrigin = "ai", firstAttempt = true, pacingStable = true, pacedCompletion = true))
            }
        }
        val currentArticle = article(3); val currentBody = body(currentArticle)
        var current by mutableStateOf(controller(speed, preferences))
        compose.setContent {
            val state by current.state.collectAsState()
            val inspection by current.recapInspection.collectAsState()
            LexiconTheme(AppSettings(theme = ThemeMode.LIGHT)) {
                Column(Modifier.fillMaxSize()) {
                    Text("Synthetic integration fixture · not a learning result")
                    Box(Modifier.weight(1f)) {
                        SpeedReadingScreen(currentArticle, currentBody, AppSettings(theme = ThemeMode.LIGHT), state,
                            SpeedUiActions(onRememberTraining = current::setRememberTraining,
                                onApplySuggestion = current::applyPaceSuggestion,
                                onDismissSuggestion = current::dismissPaceSuggestion,
                                onUndoSuggestion = current::undoPaceSuggestion,
                                onInspectRun = current::inspectRun), articles = listOf(article(1), article(2), currentArticle))
                    }
                }
                inspection?.let { SpeedRecapInspectionDialog(it, current::closeRecapInspection) }
            }
        }
        compose.runOnIdle { current.bind(currentArticle, currentBody, false, false, 1, 0) }
        compose.waitUntil(8_000) { current.state.value.suggestion != null && !current.state.value.personalizationBusy }
        assertEquals(60, current.state.value.groupsPerMinute)
        compose.onNodeWithText("L2 · 建议下轮 60 → 63 意群/分钟").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("speed_pace_evidence").assertTextEquals("本次建议采用 2 轮 · 答对 6/6 题")
        val exactEvidence = runBlocking { speed.library.first().runs.single { it.id == "synthetic-run-2" } }
        val profileBeforeInspection = runBlocking { preferences.state.first() }
        compose.onNodeWithTag("speed_pace_details_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("speed_pace_requirements").performScrollTo().assertTextContains("已采用 6 题 · 2 份不同正文", substring = true)
        compose.onNodeWithText(article(2).title).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pace_assessment_open_synthetic-run-2").performScrollTo().performClick()
        compose.waitUntil(8_000) { current.recapInspection.value?.loading == false }
        val inspected = requireNotNull(current.recapInspection.value)
        assertNull(inspected.error)
        assertEquals(exactEvidence, inspected.run)
        assertEquals(exactEvidence.planKey, inspected.plan?.planKey)
        assertEquals(exactEvidence.bodyHash, inspected.plan?.bodyHash)
        assertNotNull(inspected.summary)
        compose.onNodeWithTag("speed_recap_inspection").assertIsDisplayed()
        compose.onNodeWithTag("speed_recap_inspection_error").assertDoesNotExist()
        compose.onNodeWithTag("speed_recap_inspection_close").performClick()
        compose.onNodeWithTag("speed_recap_inspection").assertDoesNotExist()
        assertEquals(SpeedStage.SETUP, current.state.value.stage)
        assertEquals(60, current.state.value.groupsPerMinute)
        assertEquals(profileBeforeInspection, runBlocking { preferences.state.first() })
        compose.onNodeWithTag("speed_pace_requirements").performScrollTo().assertTextContains("已采用 6 题 · 2 份不同正文", substring = true)
        assertEquals(2, generatorCalls.get())
        compose.onNodeWithTag("speed_pace_apply").performScrollTo().performClick()
        compose.waitUntil(8_000) { current.state.value.profile.rateFor() == 63 && !current.state.value.personalizationBusy }
        assertNull(current.state.value.personalizationError)
        assertEquals(SpeedStage.SETUP, current.state.value.stage)
        assertEquals(63, current.state.value.groupsPerMinute)
        assertEquals(63, runBlocking { preferences.state.first().rateFor() })
        compose.onNodeWithTag("speed_pace_suggestion").assertDoesNotExist()

        compose.onNodeWithTag("speed_pace_undo").performScrollTo().performClick()
        compose.waitUntil(8_000) { current.state.value.profile.rateFor() == 60 && !current.state.value.personalizationBusy }
        assertNull(current.state.value.personalizationError)
        assertEquals(60, current.state.value.groupsPerMinute)
        val undone = runBlocking { preferences.state.first() }
        assertEquals(PersonalizationDecisionStatus.UNDONE, undone.decisions.single().status)
        assertNull(PersonalizationPolicy.suggest(undone, runBlocking { speed.library.first().runs }))
        compose.onNodeWithTag("speed_pace_suggestion").assertDoesNotExist()
        compose.onNodeWithTag("speed_pace_undo").assertDoesNotExist()

        val reopened = LocalPersonalizationRepository(database)
        val replacement = controller(speed, reopened)
        compose.runOnIdle {
            current.viewModelScope.cancel()
            current = replacement
            current.bind(currentArticle, currentBody, false, false, 2, 0)
        }
        compose.waitUntil(8_000) { current.state.value.profile.revision == undone.revision && !current.state.value.personalizationBusy }
        assertEquals(undone, runBlocking { reopened.state.first() })
        assertEquals(60, current.state.value.groupsPerMinute)
        assertNull(current.state.value.suggestion)
        compose.onNodeWithTag("speed_pace_suggestion").assertDoesNotExist()
        assertEquals(2, runBlocking { speed.library.first().runs.size })
        assertEquals(2, generatorCalls.get())
    }
}
