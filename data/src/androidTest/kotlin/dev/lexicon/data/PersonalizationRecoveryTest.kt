package dev.lexicon.data

import android.content.Context
import androidx.room3.Room
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Corruption is synthetic; every fixture is an isolated Room database without API access. */
@RunWith(AndroidJUnit4::class)
class PersonalizationRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val codec = Json { encodeDefaults = true }
    private val key = LocalPersonalizationRepository.PROFILE_KEY
    private fun db() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).build()
    private suspend fun raw(db: LexiconDatabase): ByteArray = requireNotNull(db.personalizationRecoveryDao().rawEntry(key)).bytes
    private suspend fun seed(db: LexiconDatabase, value: String) = db.readingDao().put(PreferenceRow(key, value))
    private suspend fun bytes(db: LexiconDatabase, value: ByteArray) {
        db.useWriterConnection { connection ->
            connection.usePrepared("INSERT OR REPLACE INTO preferences (`key`,data) VALUES (?,CAST(? AS TEXT))") {
                it.bindText(1, key); it.bindBlob(2, value); it.step()
            }
        }
    }
    private suspend fun sql(db: LexiconDatabase, value: String) {
        db.useWriterConnection { connection -> connection.usePrepared(value) { it.step() } }
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        assertTrue("Mutation must fail before changing stored data", runCatching { block() }.isFailure)
    }
    private fun historyProfile(): PersonalizationProfile {
        val suggestion = PaceSuggestion("pace:${"a".repeat(64)}", SpeedGranularity.L2, 60, 63,
            listOf("run-one", "run-two"), 6, 6, 2, "b".repeat(64), "pace-v2")
        return PersonalizationProfile(revision = 1, decisions = listOf(PersonalizationDecision(
            suggestion.id, suggestion, PersonalizationDecisionStatus.DISMISSED, 1000)))
    }

    @Test fun healthDistinguishesMissingHealthyAndDamagedWithoutImplicitWrites() = runBlocking {
        val db = db()
        try {
            val repository = LocalPersonalizationRepository(db)
            assertEquals(PersonalizationHealth(PersonalizationHealthStatus.MISSING), repository.health.first())
            assertEquals(PersonalizationProfile(), repository.state.first())
            assertNull(db.readingDao().entry(key))
            val value = codec.encodeToString(historyProfile())
            seed(db, value)
            assertEquals(PersonalizationHealthStatus.OK, repository.health.first().status)
            assertEquals(historyProfile(), repository.state.first())
            seed(db, "{bad-json")
            assertEquals(PersonalizationHealthStatus.CORRUPT, repository.health.first().status)
            assertFalse(repository.state.first().enabled)
            assertArrayEquals("{bad-json".toByteArray(), raw(db))
        } finally { db.close() }
    }

    @Test fun healthUsesTheSameStrictSchemaAsAllWriters() = runBlocking {
        val db = db()
        try {
            val repository = LocalPersonalizationRepository(db)
            val invalid = listOf("{} trailing", "{\"revision\":-1}", "{\"enabled\":true,\"unknown\":42}",
                "{\"rates\":{\"L2\":181}}", codec.encodeToString(historyProfile()).replace("pace-v2", "invalid version!"))
            invalid.forEach { value ->
                seed(db, value)
                assertEquals(PersonalizationHealthStatus.CORRUPT, repository.health.first().status)
                rejected { repository.setEnabled(true, 0) }
                assertArrayEquals(value.toByteArray(), raw(db))
            }
        } finally { db.close() }
    }

    @Test fun explicitRecoveryPreservesExactBytesThenResetsOnlyToDisabledDefaults() = runBlocking {
        val db = db()
        try {
            val value = " \n{\"revision\":broken, \"note\":\"原记录\"} \n"
            seed(db, value)
            val repository = LocalPersonalizationRepository(db)
            val digest = requireNotNull(repository.health.first().rowDigest)
            val receipt = repository.recoverCorrupt(digest)
            assertEquals(PersonalizationProfile(enabled = false), receipt.profile)
            assertEquals(digest, receipt.originalDigest); assertEquals(value.toByteArray().size, receipt.originalBytes)
            assertTrue(receipt.backupExists)
            assertArrayEquals(value.toByteArray(), db.personalizationRecoveryDao().rawEntry(receipt.backupKey)!!.bytes)
            assertEquals(receipt.profile, repository.state.first())
            assertEquals(PersonalizationHealthStatus.OK, repository.health.first().status)
            assertEquals(receipt.profile, LocalPersonalizationRepository(db).state.first())
            val enabled = repository.setEnabled(true, receipt.profile.revision)
            assertTrue(enabled.enabled); assertEquals(1L, enabled.revision); assertNull(enabled.activePaceBinding)
            assertArrayEquals(value.toByteArray(), db.personalizationRecoveryDao().rawEntry(receipt.backupKey)!!.bytes)
        } finally { db.close() }
    }

    @Test fun malformedUtf8IsNotSilentlyReplacedBeforeBackupOrMutation() = runBlocking {
        val db = db()
        try {
            val valid = codec.encodeToString(historyProfile())
            val damaged = valid.toByteArray().also { it[valid.indexOf("run-one")] = 0xff.toByte() }
            bytes(db, damaged)
            val repository = LocalPersonalizationRepository(db)
            assertEquals(PersonalizationHealthStatus.CORRUPT, repository.health.first().status)
            assertFalse(repository.state.first().enabled)
            rejected { repository.setEnabled(false, 1) }
            assertArrayEquals(damaged, raw(db))
            val receipt = repository.recoverCorrupt(requireNotNull(repository.health.first().rowDigest))
            assertArrayEquals(damaged, db.personalizationRecoveryDao().rawEntry(receipt.backupKey)!!.bytes)
            assertTrue(receipt.backupExists)
        } finally { db.close() }
    }

    @Test fun staleRecoveryCannotOverwriteANewerHealthyOrDamagedRow() = runBlocking {
        val db = db()
        try {
            val repository = LocalPersonalizationRepository(db)
            seed(db, "broken-old")
            val old = requireNotNull(repository.health.first().rowDigest)
            seed(db, codec.encodeToString(historyProfile()))
            rejected { repository.recoverCorrupt(old) }
            assertEquals(historyProfile(), repository.state.first())
            seed(db, "broken-new")
            rejected { repository.recoverCorrupt(old) }
            assertArrayEquals("broken-new".toByteArray(), raw(db))
            val receipt = repository.recoverCorrupt(requireNotNull(repository.health.first().rowDigest))
            assertArrayEquals("broken-new".toByteArray(), db.personalizationRecoveryDao().rawEntry(receipt.backupKey)!!.bytes)
        } finally { db.close() }
    }

    @Test fun missingAndHealthyProfilesCannotBeResetByRecovery() = runBlocking {
        val db = db()
        try {
            val repository = LocalPersonalizationRepository(db)
            rejected { repository.recoverCorrupt("a".repeat(64)) }
            assertNull(db.readingDao().entry(key))
            seed(db, codec.encodeToString(historyProfile()))
            rejected { repository.recoverCorrupt(requireNotNull(repository.health.first().rowDigest)) }
            assertEquals(historyProfile(), repository.state.first())
        } finally { db.close() }
    }

    @Test fun concurrentRecoveryHasOneWinnerAndPreservesItsSingleBackup() = runBlocking {
        val db = db()
        try {
            seed(db, "broken")
            val first = LocalPersonalizationRepository(db); val second = LocalPersonalizationRepository(db)
            val digest = requireNotNull(first.health.first().rowDigest)
            val results = coroutineScope {
                listOf(async { runCatching { first.recoverCorrupt(digest) } },
                    async { runCatching { second.recoverCorrupt(digest) } }).awaitAll()
            }
            assertEquals(1, results.count { it.isSuccess })
            val receipt = results.mapNotNull { it.getOrNull() }.single()
            assertArrayEquals("broken".toByteArray(), db.personalizationRecoveryDao().rawEntry(receipt.backupKey)!!.bytes)
            assertEquals(PersonalizationProfile(enabled = false), first.state.first())
        } finally { db.close() }
    }

    @Test fun recoveryDoesNotTouchTrainingCardsCapturesOrOtherPreferences() = runBlocking {
        val db = db()
        try {
            val training = PreferenceRow("speed-library:run:kept", "{\"persisted\":true}")
            val baseline = PreferenceRow("baseline-library:attempt:kept", "{\"persisted\":true}")
            val other = PreferenceRow("reader:layout", "serif")
            val card = CardRow("kept-card", "kept-capture", 99, 2, 100, "{\"persisted\":true}")
            val capture = CaptureRow("kept-capture", 1, "{\"persisted\":true}")
            listOf(training, baseline, other).forEach { db.readingDao().put(it) }
            db.dao().putCard(card); db.dao().putCapture(capture)
            seed(db, "broken")
            val repository = LocalPersonalizationRepository(db)
            repository.recoverCorrupt(requireNotNull(repository.health.first().rowDigest))
            listOf(training, baseline, other).forEach { assertEquals(it, db.readingDao().entry(it.key)) }
            assertEquals(card, db.dao().card(card.id)); assertEquals(capture, db.dao().capture(capture.id))
            assertTrue(db.dao().allEvents().isEmpty())
        } finally { db.close() }
    }

    @Test fun existingBackupCannotBeOverwrittenAndFailureLeavesCorruptProfileIntact() = runBlocking {
        val db = db()
        try {
            seed(db, "broken")
            val backup = PreferenceRow("personalization:backup:existing", "original-backup")
            db.readingDao().put(backup)
            val digest = requireNotNull(LocalPersonalizationRepository(db).health.first().rowDigest)
            rejected { db.personalizationRecoveryDao().recover(digest, backup.key) }
            assertArrayEquals("broken".toByteArray(), raw(db))
            assertEquals(backup, db.readingDao().entry(backup.key))
        } finally { db.close() }
    }

    @Test fun backupWriteFailureRollsBackWithoutResettingTheProfile() = runBlocking {
        val db = db()
        try {
            seed(db, "broken")
            sql(db, "CREATE TRIGGER fail_backup BEFORE INSERT ON preferences WHEN NEW.key LIKE 'personalization:backup:%' BEGIN SELECT RAISE(ABORT, 'fixture backup failure'); END")
            val repository = LocalPersonalizationRepository(db)
            rejected { repository.recoverCorrupt(requireNotNull(repository.health.first().rowDigest)) }
            assertArrayEquals("broken".toByteArray(), raw(db))
            assertEquals(PersonalizationHealthStatus.CORRUPT, repository.health.first().status)
        } finally { db.close() }
    }

    @Test fun backupVerificationFailureRollsBackTheCopyAndTheReset() = runBlocking {
        val db = db()
        try {
            seed(db, "broken")
            sql(db, "CREATE TRIGGER alter_backup AFTER INSERT ON preferences WHEN NEW.key LIKE 'personalization:backup:%' BEGIN UPDATE preferences SET data='changed' WHERE key=NEW.key; END")
            val backupKey = "personalization:backup:verification-fixture"
            val digest = requireNotNull(LocalPersonalizationRepository(db).health.first().rowDigest)
            rejected { db.personalizationRecoveryDao().recover(digest, backupKey) }
            assertArrayEquals("broken".toByteArray(), raw(db))
            assertNull(db.personalizationRecoveryDao().rawEntry(backupKey))
        } finally { db.close() }
    }
}
