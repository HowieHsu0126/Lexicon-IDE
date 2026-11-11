package dev.lexicon.ide.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.data.LexiconDatabase
import dev.lexicon.data.LocalPersonalizationRepository
import dev.lexicon.data.PreferenceRow
import dev.lexicon.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real Room and preference transactions, isolated synthetic run source; no API or installed user data. */
@RunWith(AndroidJUnit4::class)
class PaceUndoProvenanceViewModelTest {
    private val databases = mutableListOf<LexiconDatabase>()
    private val stores = mutableListOf<ViewModelStore>()
    private val codec = Json { encodeDefaults = true }
    private val article = Article("undo-fixture", "fixture", listOf("synthetic"), "Synthetic practice",
        "https://example.org/undo-fixture", "", readingMode = ReadingMode.NATIVE)
    private val text = "Residents compare library proposals before choosing a practical schedule."
    private val body = ArticleBody(article.id, text, fingerprint(text), 1, Long.MAX_VALUE, "Self-authored test sentence")

    @After fun cleanup() {
        onMain { stores.forEach { it.clear() } }
        databases.forEach { it.close() }
    }

    @Test fun aDraftRateRoundTripBlocksSameTurnUndoAndPermanentlyRemovesItsProvenance() {
        val context = prepared()
        val before = runBlocking { context.preferences.state.first() }
        val vm = controller(context)
        await(vm) { it.lastApplied?.id == context.offer.id && it.groupsPerMinute == 63 }
        onMain {
            vm.setSpeed(80)
            vm.setSpeed(63)
            assertNull("The previous undo action must disappear in the same main turn", vm.state.value.lastApplied)
            vm.undoPaceSuggestion(context.offer.id)
            assertEquals(63, vm.state.value.profile.rateFor(SpeedGranularity.L2))
        }
        await(vm) { !it.personalizationBusy && !it.profile.undoPaceBindings.containsKey(SpeedGranularity.L2) }
        assertInvalidated(context, before)
        val reopened = controller(context)
        await(reopened) { !it.loading && it.profile.revision > before.revision }
        assertNull(reopened.state.value.lastApplied)
        onMain { reopened.undoPaceSuggestion(context.offer.id) }
        await(reopened) { !it.personalizationBusy }
        assertEquals(63, runBlocking { context.preferences.state.first().rateFor(SpeedGranularity.L2) })
        assertEquals(0, context.speed.generations)
    }

    @Test fun disabledRememberingWithOnlyAPerGranularityTokenStillInvalidatesTheEditedRate() {
        val context = prepared()
        runBlocking {
            var profile = context.preferences.state.first()
            profile = context.preferences.remember(SpeedGranularity.L1, 77, true, profile.revision)
            context.preferences.setEnabled(false, profile.revision)
        }
        val before = runBlocking { context.preferences.state.first() }
        assertNull(before.activePaceBinding)
        assertNotNull(before.undoPaceBindings[SpeedGranularity.L2])
        assertFalse(before.enabled)
        val vm = controller(context)
        await(vm) { it.profile.revision == before.revision && it.lastApplied?.id == context.offer.id }
        assertEquals(SpeedGranularity.L2, vm.state.value.selectedGranularity)
        onMain {
            vm.setSpeed(80)
            vm.setSpeed(63)
            vm.undoPaceSuggestion(context.offer.id)
            assertNull(vm.state.value.lastApplied)
        }
        await(vm) { !it.personalizationBusy && !it.profile.undoPaceBindings.containsKey(SpeedGranularity.L2) }
        assertInvalidated(context, before)
        val after = runBlocking { context.preferences.state.first() }
        assertFalse(after.enabled)
        assertEquals(SpeedGranularity.L1, after.granularity)
        assertEquals(77, after.rateFor(SpeedGranularity.L1))
        assertTrue(after.sentenceOnly)
        assertEquals(0, context.speed.generations)
    }

    @Test fun editingL2CannotRemoveAnUnrelatedL1ApplicationOrItsUndoToken() {
        val context = prepared()
        val l1Offer = runBlocking {
            val profile = context.preferences.state.first()
            val l1 = context.preferences.remember(SpeedGranularity.L1, 60, true, profile.revision)
            val offer = requireNotNull(PersonalizationPolicy.suggest(l1, context.runs))
            context.preferences.apply(offer, l1.revision)
            offer
        }
        val before = runBlocking { context.preferences.state.first() }
        val vm = controller(context)
        await(vm) { it.profile.revision == before.revision && it.groupsPerMinute == 63 }
        onMain {
            vm.setGranularity(SpeedGranularity.L2)
            vm.setSpeed(80)
            vm.setSpeed(63)
            vm.undoPaceSuggestion(context.offer.id)
        }
        await(vm) { !it.personalizationBusy && !it.profile.undoPaceBindings.containsKey(SpeedGranularity.L2) }
        val after = runBlocking { context.preferences.state.first() }
        assertEquals(before.undoPaceBindings[SpeedGranularity.L1], after.undoPaceBindings[SpeedGranularity.L1])
        assertNull(after.activePaceBinding)
        assertEquals(before.rates, after.rates)
        assertEquals(before.decisions, after.decisions)
        assertNull("An L2 setup must not show the remaining L1 undo", vm.state.value.lastApplied)
        assertEquals(listOf(l1Offer.id), PersonalizationHistory.undoableDecisions(after).map { it.id })
        onMain { vm.setGranularity(SpeedGranularity.L1) }
        await(vm) { !it.personalizationBusy && it.lastApplied?.id == l1Offer.id }
        assertEquals(before.undoPaceBindings[SpeedGranularity.L1],
            runBlocking { context.preferences.state.first().undoPaceBindings[SpeedGranularity.L1] })
        assertEquals(0, context.speed.generations)
    }

    @Test fun eachGranularityShowsItsOwnUndoAndOutcomeWithoutRemovingTheOtherRate() {
        val context = prepared()
        val l1Offer = applyL1(context)
        val before = runBlocking { context.preferences.state.first() }
        val vm = controller(context)
        await(vm) { it.lastApplied?.id == l1Offer.id && it.paceOutcomeDecision?.id == l1Offer.id }
        onMain {
            vm.setGranularity(SpeedGranularity.L2)
            assertEquals(context.offer.id, vm.state.value.lastApplied?.id)
            assertNull("An old L1 outcome must disappear immediately", vm.state.value.paceOutcome)
        }
        await(vm) { !it.personalizationBusy && it.paceOutcomeDecision?.id == context.offer.id }
        val switched = runBlocking { context.preferences.state.first() }
        assertEquals(before.rates, switched.rates)
        assertEquals(before.decisions, switched.decisions)
        assertEquals(before.undoPaceBindings, switched.undoPaceBindings)
        assertNull(switched.activePaceBinding)
        onMain { vm.undoPaceSuggestion(l1Offer.id) }
        await(vm) { !it.personalizationBusy }
        assertEquals("A stale L1 callback from an L2 page cannot undo L1", switched,
            runBlocking { context.preferences.state.first() })
        onMain { vm.undoPaceSuggestion(context.offer.id) }
        await(vm) { !it.personalizationBusy && it.profile.rateFor(SpeedGranularity.L2) == 60 }
        val undone = runBlocking { context.preferences.state.first() }
        assertEquals(63, undone.rateFor(SpeedGranularity.L1))
        assertEquals(before.undoPaceBindings[SpeedGranularity.L1], undone.undoPaceBindings[SpeedGranularity.L1])
        assertEquals(PersonalizationDecisionStatus.APPLIED, undone.decisions.single { it.id == l1Offer.id }.status)
        onMain { vm.setGranularity(SpeedGranularity.L1) }
        await(vm) { !it.personalizationBusy && it.lastApplied?.id == l1Offer.id && it.paceOutcomeDecision?.id == l1Offer.id }
        assertEquals(0, context.speed.generations)
    }

    @Test fun disabledMemorySwitchSurvivesRecreationWithoutRestoringTheOldRunAttribution() {
        val context = prepared()
        val l1Offer = applyL1(context)
        runBlocking {
            val current = context.preferences.state.first()
            context.preferences.setEnabled(false, current.revision)
        }
        val before = runBlocking { context.preferences.state.first() }
        val saved = SavedStateHandle()
        val vm = controller(context, saved = saved)
        await(vm) { it.profile.revision == before.revision && it.lastApplied?.id == context.offer.id }
        onMain { vm.setGranularity(SpeedGranularity.L1) }
        await(vm) { !it.personalizationBusy && it.lastApplied?.id == l1Offer.id && it.profile.activePaceBinding == null }
        val after = runBlocking { context.preferences.state.first() }
        assertFalse(after.enabled)
        assertEquals(before.rates, after.rates)
        assertEquals(before.undoPaceBindings, after.undoPaceBindings)
        assertEquals(before.decisions, after.decisions)
        val copy = runBlocking(Dispatchers.Main) {
            SavedStateHandle(saved.keys().associateWith { key ->
                saved.get<Any>(key).let { if (it is android.os.Bundle) android.os.Bundle(it) else it }
            })
        }
        onMain { stores.forEach { it.clear() }; stores.clear() }
        val reopened = controller(context, saved = copy)
        await(reopened) { !it.loading && it.profile.revision == after.revision &&
            it.selectedGranularity == SpeedGranularity.L1 && it.lastApplied?.id == l1Offer.id }
        assertNull(reopened.state.value.profile.activePaceBinding)
        onMain { reopened.setRememberTraining(true) }
        await(reopened) { !it.personalizationBusy && it.profile.enabled }
        assertNull(PersonalizationOutcomes.bindingForStart(reopened.state.value.profile, SpeedGranularity.L1,
            reopened.state.value.groupsPerMinute, false))
        assertEquals(0, context.speed.generations)
    }

    @Test fun anUncommittedGranularityChoiceProjectsItsOwnControlsWhileTheOlderProfileIsStillVisible() {
        val context = prepared()
        val l1Offer = applyL1(context)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = object : PersonalizationRepository by context.preferences {
            override suspend fun remember(granularity: SpeedGranularity, rate: Int, sentenceOnly: Boolean,
                expectedRevision: Long, paceManuallyAdjusted: Boolean): PersonalizationProfile {
                if (granularity == SpeedGranularity.L2) { entered.complete(Unit); release.await() }
                return context.preferences.remember(granularity, rate, sentenceOnly, expectedRevision, paceManuallyAdjusted)
            }
        }
        val vm = controller(context, gated)
        await(vm) { it.lastApplied?.id == l1Offer.id }
        onMain { vm.setGranularity(SpeedGranularity.L2) }
        runBlocking { withTimeout(10_000) { entered.await() } }
        assertEquals(SpeedGranularity.L1, vm.state.value.profile.granularity)
        assertEquals(SpeedGranularity.L2, vm.state.value.selectedGranularity)
        assertEquals(context.offer.id, vm.state.value.lastApplied?.id)
        await(vm) { it.paceOutcomeDecision?.id == context.offer.id }
        onMain { vm.undoPaceSuggestion(l1Offer.id) }
        release.complete(Unit)
        await(vm) { !it.personalizationBusy && it.profile.granularity == SpeedGranularity.L2 }
        val after = runBlocking { context.preferences.state.first() }
        assertEquals(PersonalizationDecisionStatus.APPLIED, after.decisions.single { it.id == l1Offer.id }.status)
        assertEquals(PersonalizationDecisionStatus.APPLIED, after.decisions.single { it.id == context.offer.id }.status)
        assertEquals(0, context.speed.generations)
    }

    private fun applyL1(context: ContextState): PaceSuggestion = runBlocking {
        val profile = context.preferences.state.first()
        val l1 = context.preferences.remember(SpeedGranularity.L1, 60, true, profile.revision)
        val offer = requireNotNull(PersonalizationPolicy.suggest(l1, context.runs))
        context.preferences.apply(offer, l1.revision)
        offer
    }

    @Test fun aNewExplicitApplicationClearsAnEarlierFailedInvalidationAndCanBeUndone() {
        val context = prepared()
        val before = runBlocking { context.preferences.state.first() }
        val attempts = AtomicInteger()
        val rememberedAttempts = AtomicInteger()
        val rejectManualWrites = AtomicBoolean(true)
        val failingOnce = object : PersonalizationRepository by context.preferences {
            override suspend fun invalidatePaceUndo(granularity: SpeedGranularity, expectedRevision: Long): PersonalizationProfile {
                if (attempts.incrementAndGet() == 1) throw IOException("Synthetic transient storage failure")
                return context.preferences.invalidatePaceUndo(granularity, expectedRevision)
            }
            override suspend fun remember(granularity: SpeedGranularity, rate: Int, sentenceOnly: Boolean,
                expectedRevision: Long, paceManuallyAdjusted: Boolean): PersonalizationProfile {
                // SETUP also persists through remember. Both paths must fail to leave the old origin intact.
                if (rejectManualWrites.get() && paceManuallyAdjusted) {
                    rememberedAttempts.incrementAndGet()
                    throw IOException("Synthetic unavailable setup storage")
                }
                return context.preferences.remember(granularity, rate, sentenceOnly, expectedRevision, paceManuallyAdjusted)
            }
        }
        val vm = controller(context, failingOnce)
        await(vm) { it.lastApplied?.id == context.offer.id && it.groupsPerMinute == 63 }
        onMain { vm.setSpeed(80); vm.setSpeed(63) }
        await(vm) { !it.personalizationBusy && it.personalizationError != null }
        assertEquals(1, attempts.get())
        assertEquals(1, rememberedAttempts.get())
        assertNull(vm.state.value.lastApplied)
        val unchanged = runBlocking { context.preferences.state.first() }
        assertEquals(before, unchanged)
        assertNotNull(unchanged.undoPaceBindings[SpeedGranularity.L2])
        assertEquals(63, unchanged.rateFor(SpeedGranularity.L2))

        val newRuns = listOf(run(31), run(32)).map { it.copy(groupsPerMinute = 63) }
        runBlocking {
            newRuns.forEach { context.database.readingDao().put(PreferenceRow("speed-library:run:${it.id}", codec.encodeToString(it))) }
        }
        context.speed.library.value = SpeedLibrary(runs = context.runs + newRuns)
        val offered = await(vm) { it.suggestion?.let { offer -> offer.fromRate == 63 && offer.toRate == 66 } == true }
        val newOffer = requireNotNull(offered.suggestion)
        assertNotEquals(context.offer.id, newOffer.id)
        rejectManualWrites.set(false)
        onMain { vm.applyPaceSuggestion(newOffer.id) }
        await(vm) { !it.personalizationBusy && it.lastApplied?.id == newOffer.id && it.profile.rateFor(SpeedGranularity.L2) == 66 }
        val applied = runBlocking { context.preferences.state.first() }
        assertEquals(newOffer.id, applied.undoPaceBindings[SpeedGranularity.L2]?.decisionId)
        assertEquals(77, applied.rateFor(SpeedGranularity.L1))
        onMain { vm.undoPaceSuggestion(newOffer.id) }
        await(vm) { !it.personalizationBusy && it.profile.rateFor(SpeedGranularity.L2) == 63 &&
            it.profile.decisions.single { decision -> decision.id == newOffer.id }.status == PersonalizationDecisionStatus.UNDONE }
        val undone = runBlocking { context.preferences.state.first() }
        assertEquals(PersonalizationDecisionStatus.APPLIED, undone.decisions.single { it.id == context.offer.id }.status)
        assertEquals(PersonalizationDecisionStatus.UNDONE, undone.decisions.single { it.id == newOffer.id }.status)
        assertEquals(77, undone.rateFor(SpeedGranularity.L1))
        assertEquals(0, context.speed.generations)
    }

    @Test fun successfulSetupPersistenceResolvesAFailedFastInvalidationWithoutKeepingAFalseSaveError() {
        val context = prepared()
        val before = runBlocking { context.preferences.state.first() }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val fallback = object : PersonalizationRepository by context.preferences {
            override suspend fun invalidatePaceUndo(granularity: SpeedGranularity, expectedRevision: Long): PersonalizationProfile {
                attempts.incrementAndGet()
                throw IOException("Synthetic fast-path storage failure")
            }
            override suspend fun remember(granularity: SpeedGranularity, rate: Int, sentenceOnly: Boolean,
                expectedRevision: Long, paceManuallyAdjusted: Boolean): PersonalizationProfile {
                entered.complete(Unit)
                release.await()
                return context.preferences.remember(granularity, rate, sentenceOnly, expectedRevision, paceManuallyAdjusted)
            }
        }
        val vm = controller(context, fallback)
        await(vm) { it.lastApplied?.id == context.offer.id && it.groupsPerMinute == 63 }
        onMain { vm.setSpeed(80); vm.setSpeed(63) }
        runBlocking { withTimeout(10_000) { entered.await() } }
        assertTrue(vm.state.value.personalizationBusy)
        assertNull(vm.state.value.lastApplied)
        assertNotNull(vm.state.value.personalizationError)
        assertEquals(before, runBlocking { context.preferences.state.first() })
        release.complete(Unit)
        await(vm) { !it.personalizationBusy }
        val saved = runBlocking { context.preferences.state.first() }
        assertEquals(1, attempts.get())
        assertEquals(before.rates, saved.rates)
        assertEquals(before.decisions, saved.decisions)
        assertNull(saved.activePaceBinding)
        assertNull(saved.undoPaceBindings[SpeedGranularity.L2])
        assertNull(vm.state.value.personalizationError)
        assertNull(vm.state.value.lastApplied)
        assertEquals(63, vm.state.value.groupsPerMinute)
        assertEquals(0, context.speed.generations)
    }

    private fun assertInvalidated(context: ContextState, before: PersonalizationProfile) {
        val after = runBlocking { context.preferences.state.first() }
        assertEquals(before.rates, after.rates)
        assertEquals(before.decisions, after.decisions)
        assertEquals(before.enabled, after.enabled)
        assertEquals(before.granularity, after.granularity)
        assertEquals(before.sentenceOnly, after.sentenceOnly)
        assertNull(after.activePaceBinding)
        assertFalse(after.undoPaceBindings.containsKey(SpeedGranularity.L2))
        assertTrue(PersonalizationHistory.undoableDecisions(after).none { it.id == context.offer.id })
        runBlocking {
            try {
                context.preferences.undo(context.offer.id, after.revision)
                fail("A later screen must not restore undo provenance from the matching rate")
            } catch (_: PersonalizationConflict) { }
        }
    }

    private fun prepared(): ContextState = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<LexiconDatabase>(ApplicationProvider.getApplicationContext<Context>())
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build().also(databases::add)
        val runs = listOf(run(1), run(2), run(21).copy(granularity = SpeedGranularity.L1), run(22).copy(granularity = SpeedGranularity.L1))
        runs.forEach { db.readingDao().put(PreferenceRow("speed-library:run:${it.id}", codec.encodeToString(it))) }
        val preferences = LocalPersonalizationRepository(db, clock = { 1_000_000 })
        var profile = preferences.remember(SpeedGranularity.L1, 77, true, 0)
        profile = preferences.remember(SpeedGranularity.L2, 60, true, profile.revision)
        val offer = requireNotNull(PersonalizationPolicy.suggest(profile, runs))
        preferences.apply(offer, profile.revision)
        ContextState(preferences, Runs(runs), offer, runs, db)
    }

    private fun controller(context: ContextState, preferences: PersonalizationRepository = context.preferences,
        saved: SavedStateHandle = SavedStateHandle()): SpeedReadingViewModel {
        lateinit var vm: SpeedReadingViewModel
        onMain {
            vm = SpeedReadingViewModel(context.speed, saved, personalizationRepository = preferences)
            stores += ViewModelStore().also { it.put("undo-provenance", vm) }
            vm.bind(article, body, true, false, 1, 0)
        }
        return vm
    }
    private fun await(vm: SpeedReadingViewModel, condition: (SpeedUiState) -> Boolean) = runBlocking {
        withTimeout(10_000) { vm.state.first(condition) }
    }
    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }
    private fun run(index: Int) = SpeedRun("undo-run-$index", "undo-article-$index", fingerprint("body-$index"), SpeedGranularity.L2,
        fingerprint("plan-$index"), index * 1_000L, index * 1_000L + 12_000, 12_000, 60, 12, List(12) { 1_000L },
        0, 0, List(3) { SpeedAnswer("question-$index-$it", 0, true) }, 3, true, 60, "ai", true, true, true)
    private data class ContextState(val preferences: LocalPersonalizationRepository, val speed: Runs,
        val offer: PaceSuggestion, val runs: List<SpeedRun>, val database: LexiconDatabase)
    private class Runs(runs: List<SpeedRun>) : SpeedReadingRepository {
        override val library = MutableStateFlow(SpeedLibrary(runs = runs))
        var generations = 0
        override suspend fun prepare(article: Article, body: ArticleBody, granularity: SpeedGranularity,
            startParagraph: Int, paragraphCount: Int, aiAllowed: Boolean, useAi: Boolean): SpeedPlan {
            generations++; error("Draft slider changes must not prepare a training plan")
        }
        override suspend fun saveRun(run: SpeedRun) = error("Setup-only test cannot save a run")
        override suspend fun saveCorrection(correction: SpeedCorrection) = error("Setup-only test cannot save a correction")
    }
}
