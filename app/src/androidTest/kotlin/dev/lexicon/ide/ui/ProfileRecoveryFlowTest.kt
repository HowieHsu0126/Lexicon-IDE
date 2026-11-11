package dev.lexicon.ide.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room3.Room
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.data.*
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

/** UI-to-Room recovery uses only synthetic data in a separate in-memory database. */
@RunWith(AndroidJUnit4::class)
class ProfileRecoveryFlowTest {
    @get:Rule val compose = createComposeRule()
    private var database: LexiconDatabase? = null
    private var controller: SpeedReadingViewModel? = null
    private val generatorCalls = AtomicInteger()
    private val article = Article("recovery-fixture", "fixture", listOf("fixture"), "A community reading room",
        "https://example.org/recovery-fixture", "", readingMode = ReadingMode.NATIVE, sourceName = "Self-authored fixture")
    private val text = "Residents propose a public reading room. The committee compares the available sites. Volunteers describe the opening hours."
    private val body = ArticleBody(article.id, text, fingerprint(text), 0, Long.MAX_VALUE, "Self-authored fixture")
    private val corrupt = " \n{\"revision\":broken, \"note\":\"原始档案\"} \n"
    private val keptCard = CardRow("fixture-card", "fixture-capture", 9000, 2, 8000, "{\"synthetic\":true}")
    private val keptCapture = CaptureRow("fixture-capture", 1, "{\"synthetic\":true}")

    @After fun close() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { controller?.let { it.pause(); it.viewModelScope.cancel() } }
        database?.close()
    }

    private data class Fixture(val db: LexiconDatabase, val speed: LocalSpeedReadingRepository,
        val preferences: LocalPersonalizationRepository, val vm: SpeedReadingViewModel, val savedRun: SpeedRun)

    private fun start(): Fixture {
        val db = Room.inMemoryDatabaseBuilder<LexiconDatabase>(ApplicationProvider.getApplicationContext<Context>())
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build().also { database = it }
        val speed = LocalSpeedReadingRepository(db, SpeedPlanGenerator { _, _, _ ->
            generatorCalls.incrementAndGet(); error("Recovery must not use an AI service")
        }, hasKey = { false }, sourceAllowed = { true })
        val preferences = LocalPersonalizationRepository(db)
        val savedRun = runBlocking {
            val plan = speed.prepare(article, body, SpeedGranularity.L2, 0, 1, true, false)
            val dwell = List(plan.chunks.size) { 1000L }
            speed.saveRun(SpeedRun("fixture-training", article.id, body.contentHash, plan.granularity, plan.planKey,
                1000, 1000 + dwell.sum(), dwell.sum(), 0, 0, dwell, 0, 0, emptyList(), 0, true,
                60, plan.origin, pacingStable = true, pacedCompletion = true))
            db.readingDao().put(PreferenceRow(LocalPersonalizationRepository.PROFILE_KEY, corrupt))
            db.dao().putCard(keptCard); db.dao().putCapture(keptCapture)
            speed.library.first().runs.single()
        }
        lateinit var vm: SpeedReadingViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = SpeedReadingViewModel(speed, personalizationRepository = preferences).also { controller = it }
        }
        compose.setContent {
            val state by vm.state.collectAsState()
            LexiconTheme(AppSettings(theme = ThemeMode.LIGHT)) {
                SpeedReadingScreen(article, body, AppSettings(theme = ThemeMode.LIGHT), state,
                    SpeedUiActions(onRememberTraining = vm::setRememberTraining, onRecoverProfile = vm::recoverProfile))
            }
        }
        compose.runOnIdle { vm.bind(article, body, false, false, 1, 0) }
        compose.waitUntil(8_000) { vm.state.value.profileHealth?.status == PersonalizationHealthStatus.CORRUPT }
        return Fixture(db, speed, preferences, vm, savedRun)
    }

    @Test fun explicitUiRecoveryPreservesOriginalBytesAndTrainingThenAllowsRememberingAgain() {
        val f = start()
        compose.onNodeWithTag("speed_remember_settings").performScrollTo().assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("profile_recovery_action").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(8_000) { f.vm.state.value.recoveryReceipt?.backupExists == true &&
            f.vm.state.value.profileHealth?.status == PersonalizationHealthStatus.OK && !f.vm.state.value.personalizationBusy }
        val receipt = requireNotNull(f.vm.state.value.recoveryReceipt)
        assertFalse(receipt.profile.enabled); assertEquals(0L, receipt.profile.revision)
        assertNull(f.vm.state.value.recoveryError); assertNull(f.vm.state.value.personalizationError)
        assertNull(f.vm.state.value.suggestion); assertEquals(SpeedStage.SETUP, f.vm.state.value.stage)
        runBlocking {
            assertArrayEquals(corrupt.toByteArray(), f.db.personalizationRecoveryDao().rawEntry(receipt.backupKey)!!.bytes)
            assertEquals(corrupt.toByteArray().size, receipt.originalBytes)
            assertEquals(receipt.profile, f.preferences.state.first())
            assertEquals(f.savedRun, f.speed.library.first().runs.single())
            assertEquals(keptCard, f.db.dao().card(keptCard.id)); assertEquals(keptCapture, f.db.dao().capture(keptCapture.id))
        }
        compose.onNodeWithTag("profile_recovery_action").assertDoesNotExist()
        compose.onNodeWithTag("profile_recovery_success").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("speed_remember_settings").performScrollTo().assertIsOff().assertIsEnabled().performClick()
        compose.waitUntil(8_000) { f.vm.state.value.profile.enabled && f.vm.state.value.profile.revision == 1L && !f.vm.state.value.personalizationBusy }
        compose.onNodeWithTag("speed_remember_settings").assertIsOn()
        runBlocking {
            assertTrue(LocalPersonalizationRepository(f.db).state.first().enabled)
            assertArrayEquals(corrupt.toByteArray(), f.db.personalizationRecoveryDao().rawEntry(receipt.backupKey)!!.bytes)
            assertEquals(f.savedRun, f.speed.library.first().runs.single())
        }
        assertEquals(0, generatorCalls.get())
    }

    @Test fun failedBackupShowsRetryAndNeverReportsSuccessfulRecovery() {
        val f = start()
        runBlocking {
            f.db.useWriterConnection { connection -> connection.usePrepared(
                "CREATE TRIGGER fail_recovery BEFORE INSERT ON preferences WHEN NEW.key LIKE 'personalization:backup:%' BEGIN SELECT RAISE(ABORT, 'fixture backup failure'); END"
            ) { it.step() } }
        }
        compose.onNodeWithTag("profile_recovery_action").performScrollTo().performClick()
        compose.waitUntil(8_000) { f.vm.state.value.recoveryError != null && !f.vm.state.value.personalizationBusy }
        compose.onNodeWithTag("profile_recovery_error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("profile_recovery_success").assertDoesNotExist()
        compose.onNodeWithTag("profile_recovery_action").performScrollTo().assertIsEnabled()
        assertNull(f.vm.state.value.recoveryReceipt)
        assertEquals(PersonalizationHealthStatus.CORRUPT, f.vm.state.value.profileHealth?.status)
        runBlocking {
            assertArrayEquals(corrupt.toByteArray(), f.db.personalizationRecoveryDao().rawEntry(LocalPersonalizationRepository.PROFILE_KEY)!!.bytes)
            assertEquals(f.savedRun, f.speed.library.first().runs.single())
            f.db.useWriterConnection { connection -> connection.usePrepared("DROP TRIGGER fail_recovery") { it.step() } }
        }
        compose.onNodeWithTag("profile_recovery_action").performClick()
        compose.waitUntil(8_000) { f.vm.state.value.recoveryReceipt?.backupExists == true && !f.vm.state.value.personalizationBusy }
        assertNull(f.vm.state.value.recoveryError)
        assertEquals(0, generatorCalls.get())
    }
}
