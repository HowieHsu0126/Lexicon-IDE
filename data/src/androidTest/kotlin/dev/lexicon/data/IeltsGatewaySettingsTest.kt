package dev.lexicon.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android Keystore tests; the pairing health transport alone is an offline fixture. */
@RunWith(AndroidJUnit4::class)
class IeltsGatewaySettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val health = IeltsGatewayHealth("lexicon.ielts.health.v1", "ok", IeltsGatewayCapabilities("0.2.1-rc.5", "test-corpus", "a".repeat(64), listOf("multiple_choice"), listOf("extent_of_agreement"), "official_public_samples_and_format_metadata"))
    private val token = "A".repeat(43)
    private fun isolated(folder: File): Context = object : ContextWrapper(context) {
        override fun getFilesDir(): File = File(folder, "ordinary").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(folder, "no-backup").apply { mkdirs() }
    }
    private fun fakeHealth(action: suspend () -> IeltsGatewayHealth) = object : IeltsGateway {
        override suspend fun health() = action()
        override suspend fun submit(payload: IeltsGatewaySubmit): IeltsGatewayJob = error("unused")
        override suspend fun get(jobId: String): IeltsGatewayJob = error("unused")
        override suspend fun byRequest(requestId: String): IeltsGatewayJob? = error("unused")
        override suspend fun cancel(jobId: String): IeltsGatewayJob = error("unused")
        override suspend fun fetchPackage(job: IeltsGatewayJob, originalSubmit: IeltsGatewaySubmit, expectedHealth: IeltsGatewayHealth): IeltsGatewayVerifiedPackage = error("unused")
    }
    @Test fun healthMustSucceedBeforeTokenOrEndpointIsPersisted() = runBlocking {
        val folder = File(context.cacheDir, "gateway-${UUID.randomUUID()}").apply { mkdirs() }; val isolated = isolated(folder)
        var checked = false
        val store = IeltsGatewaySettings(isolated) { endpoint, key, _ -> fakeHealth {
            checked = true; assertEquals("https://gateway.example/", endpoint.url); assertEquals(token, key())
            assertFalse(File(isolated.noBackupFilesDir, "ielts-gateway-token.aesgcm").exists())
            assertFalse(File(isolated.filesDir, "ielts-gateway.json").exists()); health
        } }
        try {
            assertFalse(store.snapshot().paired)
            assertEquals(health, store.pair("https://gateway.example", token)); assertTrue(checked)
            val state = store.snapshot(); assertTrue(state.paired); assertFalse(state.toString().contains(token))
            val ordinary = File(isolated.filesDir, "ielts-gateway.json").readText()
            assertFalse(ordinary.contains(token)); assertFalse(ordinary.contains("Bearer")); assertFalse(ordinary.contains("token"))
            val encrypted = File(isolated.noBackupFilesDir, "ielts-gateway-token.aesgcm").readBytes()
            assertEquals(1.toByte(), encrypted[0]); assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains(token))
            assertTrue(IeltsGatewaySettings(isolated).snapshot().paired)
        } finally { store.clearPairing(); folder.deleteRecursively() }
    }
    @Test fun independentAliasAndFilePreserveExistingDeepSeekFormatAndKey() = runBlocking {
        val folder = File(context.cacheDir, "gateway-${UUID.randomUUID()}").apply { mkdirs() }; val isolated = isolated(folder)
        val deepSeek = ApiKeyStore(isolated); val original = "deepseek-fixture-${UUID.randomUUID()}"
        val store = IeltsGatewaySettings(isolated) { _, _, _ -> fakeHealth { health } }
        try {
            deepSeek.save(original)
            val before = File(isolated.noBackupFilesDir, "deepseek-key.aesgcm").readBytes()
            store.pair("https://gateway.example", token)
            assertArrayEquals(before, File(isolated.noBackupFilesDir, "deepseek-key.aesgcm").readBytes()); assertEquals(original, deepSeek.read())
            // Swapping independent ciphertexts fails authentication under the other alias.
            val gateway = File(isolated.noBackupFilesDir, "ielts-gateway-token.aesgcm")
            gateway.writeBytes(before)
            try { store.snapshot(); fail("Expected cross-alias decryption rejection") }
            catch (error: IeltsGatewayFailure) { assertEquals(IeltsGatewayFailureCode.STORAGE_UNAVAILABLE, error.code) }
            store.clearPairing(); assertEquals(original, deepSeek.read())
        } finally { store.clearPairing(); deepSeek.clear(); folder.deleteRecursively() }
    }
    @Test fun failedNewPairingLeavesTheWorkingPairUntouched() = runBlocking {
        val folder = File(context.cacheDir, "gateway-${UUID.randomUUID()}").apply { mkdirs() }; val isolated = isolated(folder)
        var failHealth = false
        val store = IeltsGatewaySettings(isolated) { _, _, _ -> fakeHealth {
            if (failHealth) throw IeltsGatewayFailure(IeltsGatewayFailureCode.UNAUTHORIZED)
            health
        } }
        try {
            store.pair("https://gateway.example", token)
            val before = File(isolated.noBackupFilesDir, "ielts-gateway-token.aesgcm").readBytes()
            failHealth = true
            try { store.pair("https://other.example", "B".repeat(43)); fail("Expected health rejection") } catch (_: IeltsGatewayFailure) { }
            assertEquals("https://gateway.example/", store.snapshot().endpoint)
            assertArrayEquals(before, File(isolated.noBackupFilesDir, "ielts-gateway-token.aesgcm").readBytes())
        } finally { store.clearPairing(); folder.deleteRecursively() }
    }
    @Test fun oldClientCannotReadAnotherHostsNewToken() = runBlocking {
        val folder = File(context.cacheDir, "gateway-${UUID.randomUUID()}").apply { mkdirs() }; val isolated = isolated(folder)
        val store = IeltsGatewaySettings(isolated) { _, _, _ -> fakeHealth { health } }
        try {
            store.pair("https://gateway.example", token); val old = store.createClient()
            store.pair("https://other.example", "B".repeat(43))
            // The token binding fails before creating a network call.
            try { old.health(); fail("Expected changed endpoint rejection") }
            catch (error: IeltsGatewayFailure) { assertEquals(IeltsGatewayFailureCode.CONFIGURATION_CHANGED, error.code) }
        } finally { store.clearPairing(); folder.deleteRecursively() }
    }
    @Test fun interruptedTwoFileUpdateCannotAssociateTheNewTokenWithTheOldHost() = runBlocking {
        val folder = File(context.cacheDir, "gateway-${UUID.randomUUID()}").apply { mkdirs() }; val isolated = isolated(folder)
        val store = IeltsGatewaySettings(isolated) { _, _, _ -> fakeHealth { health } }
        try {
            store.pair("https://gateway.example", token)
            val old = store.createClient()
            val config = File(isolated.filesDir, "ielts-gateway.json")
            val previous = config.readBytes()
            store.pair("https://other.example", "B".repeat(43))
            // Simulate death after the new encrypted record commits but before
            // the ordinary settings are replaced: old config + new ciphertext.
            config.writeBytes(previous)
            try { IeltsGatewaySettings(isolated).snapshot(); fail("Expected bound credential mismatch") }
            catch (error: IeltsGatewayFailure) { assertEquals(IeltsGatewayFailureCode.STORAGE_UNAVAILABLE, error.code) }
            try { old.health(); fail("Expected rejection before network dispatch") }
            catch (error: IeltsGatewayFailure) { assertEquals(IeltsGatewayFailureCode.STORAGE_UNAVAILABLE, error.code) }
        } finally { store.clearPairing(); folder.deleteRecursively() }
    }
    @Test fun distinctSettingsInstancesObserveOneConsistentPairAfterConcurrentWrites() = runBlocking {
        val folder = File(context.cacheDir, "gateway-${UUID.randomUUID()}").apply { mkdirs() }; val isolated = isolated(folder)
        val first = IeltsGatewaySettings(isolated) { _, _, _ -> fakeHealth { health } }
        val second = IeltsGatewaySettings(isolated) { _, _, _ -> fakeHealth { health } }
        try {
            listOf(
                async(Dispatchers.Default) { repeat(4) { first.pair("https://first.example", token) } },
                async(Dispatchers.Default) { repeat(4) { second.pair("https://second.example", "B".repeat(43)) } },
            ).awaitAll()
            val one = first.snapshot(); val two = second.snapshot()
            assertTrue(one.paired); assertEquals(one, two)
            assertTrue(one.endpoint in listOf("https://first.example/", "https://second.example/"))
            assertNotNull(first.createClient()); assertNotNull(second.createClient())
        } finally { first.clearPairing(); folder.deleteRecursively() }
    }
}
