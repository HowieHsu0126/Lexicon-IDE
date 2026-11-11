package dev.lexicon.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalizationRepositoryIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val json = Json { encodeDefaults = true }
    private fun db() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).build()
    private fun repo(db: LexiconDatabase) = LocalPersonalizationRepository(db, clock = { 100_000 })
    private fun run(index: Int) = SpeedRun(
        id = "personal-run-$index", articleId = "article-$index", bodyHash = sha256("body-$index"),
        granularity = SpeedGranularity.L2, planKey = sha256("plan-$index"), startedAt = index * 1000L,
        finishedAt = index * 1000L + 12_000, activeMillis = 12_000, wordsRead = 60, groupsRead = 12,
        groupMillis = List(12) { 1000L }, revisits = 0, pauses = 0,
        answers = List(3) { SpeedAnswer("q$it", 0, true) }, questionCount = 3, completed = true,
        groupsPerMinute = 60, planOrigin = "ai", firstAttempt = true, pacingStable = true, pacedCompletion = true,
    )
    private suspend fun seed(db: LexiconDatabase, runs: List<SpeedRun> = listOf(run(1), run(2))): List<SpeedRun> {
        runs.forEach { db.readingDao().put(PreferenceRow("speed-library:run:${it.id}", json.encodeToString(it))) }
        return runs
    }
    private suspend fun suggestion(repository: LocalPersonalizationRepository, runs: List<SpeedRun>) =
        requireNotNull(PersonalizationPolicy.suggest(repository.state.first(), runs))
    private suspend fun conflict(block: suspend () -> Unit) {
        try { block(); fail("Expected a personalization conflict") } catch (_: PersonalizationConflict) { }
    }

    @Test fun manualTrainingDefaultsPersistWithoutChangingSettingsOrStartingAiJobs() = runBlocking {
        val db = db()
        try {
            val settings = PreferenceRow("settings", json.encodeToString(AppSettings(titleClassification = true)))
            db.dao().putPreference(settings)
            val repository = repo(db)
            val result = repository.remember(SpeedGranularity.L3, 88, true, 0)
            assertEquals(1L, result.revision); assertEquals(88, result.rateFor(SpeedGranularity.L3)); assertTrue(result.sentenceOnly)
            assertEquals(result, repo(db).state.first())
            assertEquals(settings, db.readingDao().entry("settings"))
            assertTrue(db.dao().observeJobs().first().isEmpty()); assertTrue(db.dao().allEvents().isEmpty())
            assertEquals(result, repository.remember(SpeedGranularity.L3, 88, true, result.revision))
        } finally { db.close() }
    }

    @Test fun applyRecomputesEvidenceAndStoresOneDecisionWithItsAppliedRevision() = runBlocking {
        val db = db()
        try {
            val runs = seed(db); val repository = repo(db); val offer = suggestion(repository, runs)
            val applied = repository.apply(offer, 0)
            assertEquals(offer.toRate, applied.rateFor(offer.granularity)); assertEquals(1L, applied.revision)
            val decision = applied.decisions.single()
            assertEquals(offer, decision.suggestion); assertEquals(PersonalizationDecisionStatus.APPLIED, decision.status)
            assertEquals(applied.revision, decision.appliedRevision)
            assertEquals(AppliedPaceBinding(offer.id, applied.revision, offer.granularity, offer.toRate), applied.activePaceBinding)
            assertEquals(mapOf(offer.granularity to applied.activePaceBinding), applied.undoPaceBindings)
            assertEquals(applied, repository.apply(offer, applied.revision))
            assertEquals(1, repo(db).state.first().decisions.size)
        } finally { db.close() }
    }

    @Test fun forgedOrStaleSuggestionCannotChangeTheProfile() = runBlocking {
        val db = db()
        try {
            val runs = seed(db); val repository = repo(db); val offer = suggestion(repository, runs)
            conflict { repository.apply(offer.copy(toRate = 180), 0) }
            assertNull(db.readingDao().entry(LocalPersonalizationRepository.PROFILE_KEY))
            val manual = repository.remember(SpeedGranularity.L2, 80, false, 0)
            conflict { repository.apply(offer, 0) }
            conflict { repository.apply(offer, manual.revision) }
            assertEquals(manual, repository.state.first())
        } finally { db.close() }
    }

    @Test fun newQualifyingEvidenceInvalidatesAnOtherwiseUnchangedSuggestion() = runBlocking {
        val db = db()
        try {
            val runs = seed(db); val repository = repo(db); val offer = suggestion(repository, runs)
            seed(db, listOf(run(3)))
            conflict { repository.apply(offer, 0) }
            assertEquals(PersonalizationProfile(), repository.state.first())
        } finally { db.close() }
    }

    @Test fun transactionDetectsARunInsertedBetweenPolicyEvaluationAndProfileCommit() = runBlocking {
        val db = db()
        try {
            seed(db)
            val snapshot = db.readingDao().personalizationRunRows()
            seed(db, listOf(run(3)))
            val next = PreferenceRow(LocalPersonalizationRepository.PROFILE_KEY, json.encodeToString(PersonalizationProfile(revision = 1)))
            assertFalse(db.readingDao().compareAndSetPersonalization(null, next, snapshot))
            assertNull(db.readingDao().entry(LocalPersonalizationRepository.PROFILE_KEY))
        } finally { db.close() }
    }

    @Test fun dismissKeepsTheRateAndNeverReappliesTheSameEvidence() = runBlocking {
        val db = db()
        try {
            val runs = seed(db); val repository = repo(db); val offer = suggestion(repository, runs)
            val dismissed = repository.dismiss(offer, 0)
            assertEquals(offer.fromRate, dismissed.rateFor(offer.granularity))
            assertEquals(PersonalizationDecisionStatus.DISMISSED, dismissed.decisions.single().status)
            assertEquals(listOf(PaceHold(SpeedGranularity.L2, 60, 1, 100_000)), dismissed.holds)
            assertNull(PersonalizationPolicy.suggest(dismissed, runs))
            assertEquals(dismissed, repository.dismiss(offer, dismissed.revision))
            assertEquals(dismissed, repository.apply(offer, dismissed.revision))
        } finally { db.close() }
    }

    @Test fun holdSurvivesNewEvidenceRestartAndSameRateChangesUntilExplicitRelease() = runBlocking {
        val db = db()
        try {
            val runs = seed(db); val repository = repo(db); val offer = suggestion(repository, runs)
            val held = repository.dismiss(offer, 0)
            val newer = runs + seed(db, listOf(run(3)))
            val remembered = repository.remember(SpeedGranularity.L2, 60, true, held.revision)
            assertEquals(held.holds, remembered.holds)
            assertNull(PersonalizationPolicy.suggest(remembered, newer))
            val reopened = repo(db)
            assertEquals(remembered, reopened.state.first())
            conflict { reopened.releaseHold(SpeedGranularity.L2, held.revision) }
            val released = reopened.releaseHold(SpeedGranularity.L2, remembered.revision)
            assertTrue(released.holds.isEmpty())
            assertNotNull(PersonalizationPolicy.suggest(released, newer))
            assertNull(PersonalizationPolicy.suggest(released, runs))
        } finally { db.close() }
    }

    @Test fun manualRateChangeClearsOnlyThatGranularityHoldAndUndoEstablishesAHold() = runBlocking {
        val db = db()
        try {
            val runs = seed(db); val repository = repo(db); val offer = suggestion(repository, runs)
            val applied = repository.apply(offer, 0)
            val undone = repository.undo(offer.id, applied.revision)
            assertNull(undone.activePaceBinding)
            assertEquals(listOf(PaceHold(SpeedGranularity.L2, 60, 1, 100_000)), undone.holds)
            val unrelated = repository.remember(SpeedGranularity.L1, 70, true, undone.revision)
            assertEquals(undone.holds, unrelated.holds)
            val changed = repository.remember(SpeedGranularity.L2, 65, true, unrelated.revision)
            assertTrue(changed.holds.isEmpty())
            assertEquals(65, changed.rateFor()); assertEquals(70, changed.rateFor(SpeedGranularity.L1))
        } finally { db.close() }
    }

    @Test fun undoRestoresOnlyTheAppliedRateAndPreservesOtherManualPreferences() = runBlocking {
        val db = db()
        try {
            val runs = seed(db); val repository = repo(db); val offer = suggestion(repository, runs)
            val applied = repository.apply(offer, 0)
            val manual = repository.remember(SpeedGranularity.L1, 77, true, applied.revision)
            assertEquals(applied.undoPaceBindings, manual.undoPaceBindings)
            val undone = repository.undo(offer.id, manual.revision)
            assertEquals(offer.fromRate, undone.rateFor(SpeedGranularity.L2))
            assertEquals(77, undone.rateFor(SpeedGranularity.L1)); assertEquals(SpeedGranularity.L1, undone.granularity)
            assertTrue(undone.sentenceOnly); assertEquals(PersonalizationDecisionStatus.UNDONE, undone.decisions.single().status)
            assertFalse(undone.undoPaceBindings.containsKey(SpeedGranularity.L2))
            assertEquals(undone, repository.undo(offer.id, undone.revision))
            assertEquals(undone, repository.apply(offer, undone.revision))
        } finally { db.close() }
    }

    @Test fun undoDoesNotOverwriteAManualRateOrAcceptAStaleRevision() = runBlocking {
        val db = db()
        try {
            val runs = seed(db); val repository = repo(db); val offer = suggestion(repository, runs)
            val applied = repository.apply(offer, 0)
            val manual = repository.remember(SpeedGranularity.L2, 80, false, applied.revision)
            conflict { repository.undo(offer.id, applied.revision) }
            conflict { repository.undo(offer.id, manual.revision) }
            assertEquals(manual, repository.state.first())
        } finally { db.close() }
    }

    @Test fun disablingPersonalizationStopsRememberingChangesAndBlocksAnOldSuggestion() = runBlocking {
        val db = db()
        try {
            val runs = seed(db); val repository = repo(db); val offer = suggestion(repository, runs)
            val disabled = repository.setEnabled(false, 0)
            conflict { repository.apply(offer, disabled.revision) }
            val manual = repository.remember(SpeedGranularity.L1, 55, true, disabled.revision)
            assertEquals(disabled, manual); assertFalse(manual.enabled)
            assertEquals(60, manual.rateFor()); assertFalse(manual.sentenceOnly)
            assertNull(PersonalizationPolicy.suggest(manual, runs))
            assertEquals(disabled, repo(db).state.first())
        } finally { db.close() }
    }

    @Test fun manualAwayAndBackCannotReviveTheOldUndoEvenWhenItsTargetMatchesAgain() = runBlocking {
        val db = db()
        try {
            val runs = seed(db); val repository = repo(db); val offer = suggestion(repository, runs)
            val applied = repository.apply(offer, 0)
            val away = repository.remember(SpeedGranularity.L2, 80, false, applied.revision)
            val back = repository.remember(SpeedGranularity.L2, offer.toRate, false, away.revision)
            assertEquals(offer.toRate, back.rateFor())
            assertTrue(back.undoPaceBindings.isEmpty()); assertNull(back.activePaceBinding)
            assertTrue(PersonalizationHistory.undoableDecisions(back).isEmpty())
            val before = db.readingDao().entry(LocalPersonalizationRepository.PROFILE_KEY)
            conflict { repo(db).undo(offer.id, back.revision) }
            assertEquals(before, db.readingDao().entry(LocalPersonalizationRepository.PROFILE_KEY))
            assertEquals(back, repository.state.first())
        } finally { db.close() }
    }

    @Test fun explicitSameValueManualChoiceInvalidatesUndoWhenEnabledOrDisabled() = runBlocking {
        for (disable in listOf(false, true)) {
            val db = db()
            try {
                val runs = seed(db); val repository = repo(db); val offer = suggestion(repository, runs)
                val applied = repository.apply(offer, 0)
                val previous = if (disable) repository.setEnabled(false, applied.revision) else applied
                val manual = repository.remember(offer.granularity, offer.toRate, previous.sentenceOnly,
                    previous.revision, paceManuallyAdjusted = true)
                assertEquals(previous.rateFor(), manual.rateFor()); assertEquals(previous.enabled, manual.enabled)
                assertTrue(manual.undoPaceBindings.isEmpty()); assertNull(manual.activePaceBinding)
                conflict { repository.undo(offer.id, manual.revision) }
                assertEquals(manual, repo(db).state.first())
            } finally { db.close() }
        }
    }

    @Test fun laterAppliedChangeOwnsUndoWhenItsTargetEqualsAnOlderDecisionTarget() = runBlocking {
        val db = db()
        try {
            val originalRuns = seed(db); val repository = repo(db); val firstOffer = suggestion(repository, originalRuns)
            val firstApplied = repository.apply(firstOffer, 0)
            val manual = repository.remember(SpeedGranularity.L2, 70, false, firstApplied.revision)
            val newerRuns = seed(db, listOf(run(3), run(4)).map { run ->
                run.copy(groupsPerMinute = 70, answers = run.answers.map { it.copy(correct = false) })
            })
            val secondOffer = suggestion(repository, originalRuns + newerRuns)
            assertEquals(firstOffer.toRate, secondOffer.toRate)
            assertNotEquals(firstOffer.id, secondOffer.id)
            val secondApplied = repository.apply(secondOffer, manual.revision)
            assertEquals(listOf(secondOffer.id), PersonalizationHistory.undoableDecisions(secondApplied).map { it.id })
            conflict { repository.undo(firstOffer.id, secondApplied.revision) }
            assertEquals(secondApplied, repository.state.first())
            val undone = repository.undo(secondOffer.id, secondApplied.revision)
            assertEquals(70, undone.rateFor()); assertTrue(undone.undoPaceBindings.isEmpty())
            assertEquals(PersonalizationDecisionStatus.APPLIED, undone.decisions.first { it.id == firstOffer.id }.status)
            conflict { repository.undo(firstOffer.id, undone.revision) }
        } finally { db.close() }
    }

    @Test fun endingRunAttributionKeepsEachRateUndoAndPersistsWhileMemoryIsDisabled() = runBlocking {
        val db = db()
        try {
            val l2Runs = seed(db); val repository = repo(db); val l2Offer = suggestion(repository, l2Runs)
            val l2Applied = repository.apply(l2Offer, 0)
            val switched = repository.remember(SpeedGranularity.L1, 60, true, l2Applied.revision)
            val l1Runs = seed(db, listOf(run(3), run(4)).map { it.copy(granularity = SpeedGranularity.L1) })
            val l1Offer = suggestion(repository, l2Runs + l1Runs)
            val applied = repository.apply(l1Offer, switched.revision)
            val disabled = repository.setEnabled(false, applied.revision)
            val cleared = repository.invalidatePaceBinding(disabled.revision)
            assertNull(cleared.activePaceBinding)
            assertEquals(disabled.copy(revision = disabled.revision + 1, activePaceBinding = null), cleared)
            assertEquals(setOf(l1Offer.id, l2Offer.id), PersonalizationHistory.undoableDecisions(cleared).map { it.id }.toSet())
            assertEquals(cleared, repo(db).state.first())
            assertEquals(cleared, repository.invalidatePaceBinding(cleared.revision))
            conflict { repository.invalidatePaceBinding(disabled.revision) }
            val enabled = repository.setEnabled(true, cleared.revision)
            assertNull(PersonalizationOutcomes.bindingForStart(enabled, SpeedGranularity.L1, 63, false))
            assertEquals(cleared.undoPaceBindings, enabled.undoPaceBindings)
        } finally { db.close() }
    }

    @Test fun endingLegacyRunAttributionPreservesItsVerifiedUndoBeforeClearingTheOnlyToken() = runBlocking {
        val db = db()
        try {
            val runs = seed(db); val repository = repo(db); val offer = suggestion(repository, runs)
            val applied = repository.apply(offer, 0)
            val legacy = applied.copy(undoPaceBindings = emptyMap())
            db.readingDao().put(PreferenceRow(LocalPersonalizationRepository.PROFILE_KEY, json.encodeToString(legacy)))
            val cleared = repository.invalidatePaceBinding(legacy.revision)
            assertNull(cleared.activePaceBinding)
            assertEquals(mapOf(SpeedGranularity.L2 to applied.activePaceBinding), cleared.undoPaceBindings)
            assertEquals(listOf(offer.id), PersonalizationHistory.undoableDecisions(cleared).map { it.id })
            assertEquals(cleared, repo(db).state.first())
        } finally { db.close() }
    }

    @Test fun applyingAnotherGranularityKeepsTheFirstGranularityUndoOrigin() = runBlocking {
        val db = db()
        try {
            val l2Runs = seed(db); val repository = repo(db); val l2Offer = suggestion(repository, l2Runs)
            val l2Applied = repository.apply(l2Offer, 0)
            val switched = repository.remember(SpeedGranularity.L1, 60, true, l2Applied.revision)
            val l1Runs = seed(db, listOf(run(3), run(4)).map { it.copy(granularity = SpeedGranularity.L1) })
            val l1Offer = suggestion(repository, l2Runs + l1Runs)
            val both = repository.apply(l1Offer, switched.revision)
            assertEquals(setOf(SpeedGranularity.L1, SpeedGranularity.L2), both.undoPaceBindings.keys)
            val undone = repository.undo(l2Offer.id, both.revision)
            assertEquals(60, undone.rateFor(SpeedGranularity.L2)); assertEquals(l1Offer.toRate, undone.rateFor(SpeedGranularity.L1))
            assertEquals(both.activePaceBinding, undone.activePaceBinding)
            assertEquals(SpeedGranularity.L1, undone.granularity); assertTrue(undone.sentenceOnly)
            assertEquals(listOf(l1Offer.id), PersonalizationHistory.undoableDecisions(undone).map { it.id })
            assertEquals(undone, repo(db).state.first())
        } finally { db.close() }
    }

    @Test fun legacyActiveOriginSupportsUndoWithoutGuessingFromTheNumericRate() = runBlocking {
        val db = db()
        try {
            val runs = seed(db); val repository = repo(db); val offer = suggestion(repository, runs)
            val applied = repository.apply(offer, 0)
            val legacy = JsonObject(json.parseToJsonElement(json.encodeToString(applied)).jsonObject - "undoPaceBindings").toString()
            db.readingDao().put(PreferenceRow(LocalPersonalizationRepository.PROFILE_KEY, legacy))
            val loaded = repo(db).state.first()
            assertTrue(loaded.undoPaceBindings.isEmpty()); assertNotNull(loaded.activePaceBinding)
            val undone = repo(db).undo(offer.id, loaded.revision)
            assertEquals(offer.fromRate, undone.rateFor()); assertNull(undone.activePaceBinding)
            assertTrue(undone.undoPaceBindings.isEmpty())
        } finally { db.close() }
    }

    @Test fun unrelatedManualChangePreservesAProvenLegacyActiveOriginBeforeClearingTheGlobalToken() = runBlocking {
        val db = db()
        try {
            val runs = seed(db); val repository = repo(db); val offer = suggestion(repository, runs)
            val applied = repository.apply(offer, 0)
            val legacy = JsonObject(json.parseToJsonElement(json.encodeToString(applied)).jsonObject - "undoPaceBindings").toString()
            db.readingDao().put(PreferenceRow(LocalPersonalizationRepository.PROFILE_KEY, legacy))
            val changed = repo(db).remember(SpeedGranularity.L1, 77, true, applied.revision, paceManuallyAdjusted = true)
            assertNull(changed.activePaceBinding)
            assertEquals(applied.activePaceBinding, changed.undoPaceBindings[SpeedGranularity.L2])
            val undone = repo(db).undo(offer.id, changed.revision)
            assertEquals(60, undone.rateFor(SpeedGranularity.L2)); assertEquals(77, undone.rateFor(SpeedGranularity.L1))
            assertTrue(undone.sentenceOnly)
        } finally { db.close() }
    }

    @Test fun legacyAppliedDecisionWithoutProvenOriginIsReadOnlyEvenAtItsOldTarget() = runBlocking {
        val db = db()
        try {
            val runs = seed(db); val repository = repo(db); val offer = suggestion(repository, runs)
            val applied = repository.apply(offer, 0)
            val legacy = JsonObject(json.parseToJsonElement(json.encodeToString(applied)).jsonObject - setOf("undoPaceBindings", "activePaceBinding")).toString()
            val row = PreferenceRow(LocalPersonalizationRepository.PROFILE_KEY, legacy)
            db.readingDao().put(row)
            val loaded = repo(db).state.first()
            assertEquals(offer.toRate, loaded.rateFor()); assertEquals(1, loaded.decisions.size)
            assertTrue(PersonalizationHistory.undoableDecisions(loaded).isEmpty())
            conflict { repo(db).undo(offer.id, loaded.revision) }
            assertEquals(row, db.readingDao().entry(row.key))
        } finally { db.close() }
    }

    @Test fun perGranularityUndoTokensRejectWrongKeyRevisionDecisionStatusAndRate() = runBlocking {
        val db = db()
        try {
            val runs = seed(db); val repository = repo(db); val offer = suggestion(repository, runs)
            val applied = repository.apply(offer, 0); val token = requireNotNull(applied.activePaceBinding)
            val invalid = listOf(
                applied.copy(undoPaceBindings = mapOf(SpeedGranularity.L1 to token)),
                applied.copy(undoPaceBindings = mapOf(SpeedGranularity.L2 to token.copy(profileRevision = applied.revision + 1))),
                applied.copy(undoPaceBindings = mapOf(SpeedGranularity.L2 to token.copy(decisionId = "missing"))),
                applied.copy(undoPaceBindings = mapOf(SpeedGranularity.L2 to token.copy(rate = token.rate + 1))),
                applied.copy(activePaceBinding = null, decisions = applied.decisions.map { it.copy(status = PersonalizationDecisionStatus.UNDONE) }),
            )
            invalid.forEach { profile ->
                try { PersonalizationStorage.validate(profile); fail("An invalid undo origin must be rejected") }
                catch (_: IllegalArgumentException) { }
            }
            assertEquals(applied, repository.state.first())
        } finally { db.close() }
    }

    @Test fun explicitDraftInvalidationWorksWhileDisabledWithoutSavingAnyDraftSetting() = runBlocking {
        val db = db()
        try {
            val runs = seed(db); val repository = repo(db); val offer = suggestion(repository, runs)
            val applied = repository.apply(offer, 0)
            val disabled = repository.setEnabled(false, applied.revision)
            val invalidated = repository.invalidatePaceUndo(offer.granularity, disabled.revision)
            assertEquals(disabled.copy(revision = disabled.revision + 1, undoPaceBindings = emptyMap(), activePaceBinding = null), invalidated)
            assertEquals(disabled.rates, invalidated.rates); assertEquals(disabled.holds, invalidated.holds)
            assertEquals(disabled.decisions, invalidated.decisions)
            conflict { repository.undo(offer.id, invalidated.revision) }
            assertEquals(invalidated, repo(db).state.first())
        } finally { db.close() }
    }

    @Test fun repeatedDraftInvalidationIsANoOpButStillRejectsAStaleRevision() = runBlocking {
        val db = db()
        try {
            val repository = repo(db)
            assertEquals(PersonalizationProfile(), repository.invalidatePaceUndo(SpeedGranularity.L2, 0))
            assertNull(db.readingDao().entry(LocalPersonalizationRepository.PROFILE_KEY))
            val runs = seed(db); val offer = suggestion(repository, runs)
            val applied = repository.apply(offer, 0)
            conflict { repository.invalidatePaceUndo(SpeedGranularity.L2, 0) }
            assertEquals(applied, repository.state.first())
            val invalidated = repository.invalidatePaceUndo(SpeedGranularity.L2, applied.revision)
            val row = db.readingDao().entry(LocalPersonalizationRepository.PROFILE_KEY)
            assertEquals(invalidated, repository.invalidatePaceUndo(SpeedGranularity.L2, invalidated.revision))
            assertEquals(row, db.readingDao().entry(LocalPersonalizationRepository.PROFILE_KEY))
            conflict { repository.invalidatePaceUndo(SpeedGranularity.L2, applied.revision) }
        } finally { db.close() }
    }

    @Test fun draftInvalidationKeepsOtherGranularityAndItsLegacyActiveOrigin() = runBlocking {
        val db = db()
        try {
            val l2Runs = seed(db); val repository = repo(db); val l2Offer = suggestion(repository, l2Runs)
            val l2Applied = repository.apply(l2Offer, 0)
            val l1Settings = repository.remember(SpeedGranularity.L1, 60, true, l2Applied.revision)
            val l1Runs = seed(db, listOf(run(3), run(4)).map { it.copy(granularity = SpeedGranularity.L1) })
            val l1Offer = suggestion(repository, l2Runs + l1Runs)
            val both = repository.apply(l1Offer, l1Settings.revision)
            // A partially migrated profile has a proven L1 active token and an older L2 map entry.
            val legacy = both.copy(undoPaceBindings = both.undoPaceBindings - SpeedGranularity.L1)
            db.readingDao().put(PreferenceRow(LocalPersonalizationRepository.PROFILE_KEY, json.encodeToString(legacy)))
            val invalidated = repository.invalidatePaceUndo(SpeedGranularity.L2, legacy.revision)
            assertEquals(legacy.activePaceBinding, invalidated.activePaceBinding)
            assertEquals(mapOf(SpeedGranularity.L1 to legacy.activePaceBinding), invalidated.undoPaceBindings)
            assertEquals(legacy.rates, invalidated.rates); assertEquals(legacy.granularity, invalidated.granularity)
            assertEquals(legacy.sentenceOnly, invalidated.sentenceOnly); assertEquals(legacy.holds, invalidated.holds)
            assertEquals(legacy.decisions, invalidated.decisions)
            assertEquals(listOf(l1Offer.id), PersonalizationHistory.undoableDecisions(invalidated).map { it.id })
            val row = db.readingDao().entry(LocalPersonalizationRepository.PROFILE_KEY)
            assertEquals(invalidated, repository.invalidatePaceUndo(SpeedGranularity.L3, invalidated.revision))
            assertEquals(row, db.readingDao().entry(LocalPersonalizationRepository.PROFILE_KEY))
        } finally { db.close() }
    }

    @Test fun competingRepositoriesCannotBothWriteTheSameProfileRevision() = runBlocking {
        val db = db()
        try {
            val first = repo(db); val second = repo(db)
            val results = coroutineScope { listOf(
                async { runCatching { first.remember(SpeedGranularity.L1, 50, false, 0) } },
                async { runCatching { second.remember(SpeedGranularity.L3, 90, true, 0) } },
            ).awaitAll() }
            assertEquals(1, results.count { it.isSuccess })
            assertTrue(results.single { it.isFailure }.exceptionOrNull() is PersonalizationConflict)
            assertEquals(1L, repo(db).state.first().revision)
        } finally { db.close() }
    }

    @Test fun activeBindingSurvivesDisplayAndEnableChangesWithItsOriginalAppliedRevision() = runBlocking {
        val db = db()
        try {
            val runs = seed(db); val repository = repo(db); val offer = suggestion(repository, runs)
            val applied = repository.apply(offer, 0); val token = requireNotNull(applied.activePaceBinding)
            val display = repository.remember(offer.granularity, offer.toRate, true, applied.revision)
            val disabled = repository.setEnabled(false, display.revision)
            val enabled = repository.setEnabled(true, disabled.revision)
            assertTrue(enabled.revision > token.profileRevision)
            assertEquals(token, enabled.activePaceBinding)
            assertEquals(token, repo(db).state.first().activePaceBinding)
            val legacy = JsonObject(json.parseToJsonElement(json.encodeToString(enabled)).jsonObject - "activePaceBinding").toString()
            db.readingDao().put(PreferenceRow(LocalPersonalizationRepository.PROFILE_KEY, legacy))
            assertNull(repo(db).state.first().activePaceBinding)
        } finally { db.close() }
    }

    @Test fun manualRateOrGranularityChangePermanentlyClearsTheActiveBinding() = runBlocking {
        for (changeGranularity in listOf(false, true)) {
            val db = db()
            try {
                val runs = seed(db); val repository = repo(db); val offer = suggestion(repository, runs)
                val applied = repository.apply(offer, 0)
                val changed = repository.remember(if (changeGranularity) SpeedGranularity.L1 else offer.granularity,
                    offer.toRate + 1, false, applied.revision)
                assertNull(changed.activePaceBinding)
                val back = repository.remember(offer.granularity, offer.toRate, false, changed.revision)
                assertNull(repo(db).state.first().activePaceBinding)
                assertNull(repository.apply(offer, back.revision).activePaceBinding)
            } finally { db.close() }
        }
    }

    @Test fun manualAdjustmentLatchClearsTheTokenEvenAfterReturningToTheSameRateOrDisablingPreferences() = runBlocking {
        for (disable in listOf(false, true)) {
            val db = db()
            try {
                val runs = seed(db); val repository = repo(db); val offer = suggestion(repository, runs)
                val applied = repository.apply(offer, 0)
                val current = if (disable) repository.setEnabled(false, applied.revision) else applied
                val cleared = repository.remember(offer.granularity, offer.toRate, current.sentenceOnly, current.revision,
                    paceManuallyAdjusted = true)
                assertNull(cleared.activePaceBinding); assertEquals(offer.toRate, cleared.rateFor())
                assertEquals(current.enabled, cleared.enabled)
                assertNull(repo(db).state.first().activePaceBinding)
            } finally { db.close() }
        }
    }

    @Test fun persistedRunBindingMustMatchTheDecisionAndCannotBeAddedOrChangedLater() = runBlocking {
        val db = db()
        try {
            val runs = seed(db); val preferences = repo(db); val offer = suggestion(preferences, runs)
            val applied = preferences.apply(offer, 0); val token = requireNotNull(applied.activePaceBinding)
            val article = Article("bound-source", "g", listOf("s"), "A local library", "https://example.org/bound", "")
            val text = "Residents discuss a new public library. Volunteers explain the proposal."
            val body = ArticleBody(article.id, text, sha256(text), 0, Long.MAX_VALUE, "Self-authored fixture")
            val speed = LocalSpeedReadingRepository(db, SpeedPlanGenerator { _, _, _ -> error("No AI") }, { false }, { true })
            val plan = speed.prepare(article, body, token.granularity, 0, 1, true, false)
            val dwell = List(plan.chunks.size) { 1000L }
            val partial = SpeedRun("bound", article.id, body.contentHash, plan.granularity, plan.planKey, 101_000,
                101_000 + dwell.sum(), dwell.sum(), 0, 0, dwell, 0, 0, emptyList(), 0, false, token.rate, plan.origin,
                pacingStable = true, pacedCompletion = false, paceBinding = token)
            speed.saveRun(partial)
            speed.saveRun(partial.copy(completed = true, pacedCompletion = true))
            speed.saveRun(partial.copy(id = "initially-unbound", paceBinding = null))
            speed.saveRun(partial.copy(id = "initially-unbound", completed = true, pacedCompletion = true))
            speed.saveRun(partial.copy(id = "cleared"))
            speed.saveRun(partial.copy(id = "cleared", paceBinding = null))
            speed.saveRun(partial.copy(id = "cleared", completed = true, pacedCompletion = true))
            speed.saveRun(partial.copy(id = "wrong-revision", paceBinding = token.copy(profileRevision = token.profileRevision + 1)))
            val stored = speed.library.first().runs.associateBy { it.id }
            assertEquals(token, stored.getValue("bound").paceBinding)
            assertNull(stored.getValue("initially-unbound").paceBinding)
            assertNull(stored.getValue("cleared").paceBinding)
            assertNull(stored.getValue("wrong-revision").paceBinding)
            preferences.undo(offer.id, applied.revision)
            speed.saveRun(partial.copy(completed = true, pacedCompletion = true, paceBinding = null))
            assertEquals(token, speed.library.first().runs.single { it.id == "bound" }.paceBinding)
        } finally { db.close() }
    }

    @Test fun corruptProfileIsPreservedAndCannotSilentlyReenableSuggestions() = runBlocking {
        val db = db()
        try {
            for (payload in listOf("not-json", " ".repeat(2_000_001))) {
                val corrupt = PreferenceRow(LocalPersonalizationRepository.PROFILE_KEY, payload)
                db.readingDao().put(corrupt)
                val repository = repo(db)
                assertFalse(repository.state.first().enabled)
                conflict { repository.remember(SpeedGranularity.L1, 50, false, 0) }
                assertEquals(corrupt, db.readingDao().entry(corrupt.key))
            }
        } finally { db.close() }
    }

    @Test fun profileWriterRejectsUnboundedDecisionsInvalidMetadataAndAlternateKeys() = runBlocking {
        val db = db()
        try {
            val offer = requireNotNull(PersonalizationPolicy.suggest(PersonalizationProfile(), listOf(run(1), run(2))))
            val excessive = PersonalizationProfile(revision = 1, decisions = List(1025) { index ->
                val suggestion = offer.copy(id = "pace:" + sha256("decision-$index"))
                PersonalizationDecision(suggestion.id, suggestion, PersonalizationDecisionStatus.DISMISSED, 100_000)
            })
            val badValues = listOf(
                " ".repeat(2_000_001), json.encodeToString(excessive),
                "{\"revision\":1,\"path\":\"../../profile\"}",
                "{\"revision\":1,\"rates\":{\"L2\":NaN}}",
                json.encodeToString(PersonalizationProfile(revision = 0)),
                json.encodeToString(PersonalizationProfile(revision = 1, rates = mapOf(SpeedGranularity.L2 to 999))),
            )
            for (value in badValues) {
                try {
                    db.readingDao().compareAndSetPersonalization(null,
                        PreferenceRow(LocalPersonalizationRepository.PROFILE_KEY, value), null)
                    fail("Invalid or oversized profile must not be written")
                } catch (_: IllegalArgumentException) { }
                assertNull(db.readingDao().entry(LocalPersonalizationRepository.PROFILE_KEY))
            }
            try {
                db.readingDao().compareAndSetPersonalization(null,
                    PreferenceRow("../personalization:profile", json.encodeToString(PersonalizationProfile(revision = 1))), null)
                fail("Only the fixed profile key is accepted")
            } catch (_: IllegalArgumentException) { }
            assertNull(db.readingDao().entry("../personalization:profile"))
        } finally { db.close() }
    }
}
