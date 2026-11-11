package dev.lexicon.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.IeltsGatewayFailure
import dev.lexicon.domain.IeltsGatewayFailureCode
import dev.lexicon.domain.IeltsGatewayProtocol
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real HTTPS/Keystore probe. It sends health requests only, never generation or model requests. */
@RunWith(AndroidJUnit4::class)
class IeltsImportedGatewayDeviceTest {
    private val base: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val inputDirectory: File get() = File(base.filesDir, "ielts-tls-input")

    private class Probe(val context: Context, val root: File) {
        var pin: String? = null
        var replacementPin: String? = null
        var healthResponses = 0
        var expectedRejection: IeltsGatewayFailureCode? = null
        var oldClientRejection: IeltsGatewayFailureCode? = null
        var usesKeystore = false
        var peerChainVerified = false
        var checkpoint = "input"
    }

    private fun isolated(root: File): Context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "ordinary").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String) = File(File(root, "databases").apply { mkdirs() }, name)
    }

    private fun readInput(name: String): ByteArray {
        require(name in setOf("pairing.json", "replacement-pairing.json", "ca-pairing.json"))
        val file = File(inputDirectory, name)
        assertTrue("A required opt-in TLS fixture is missing", file.isFile)
        val bytes = file.inputStream().use { it.readNBytes(IeltsPairingDocument.MAX_BYTES + 1) }
        if (bytes.isEmpty() || bytes.size > IeltsPairingDocument.MAX_BYTES) {
            bytes.fill(0)
            throw AssertionError("The TLS fixture exceeds the production document limit")
        }
        return bytes
    }

    private fun configuration(probe: Probe) = File(probe.context.filesDir, "ielts-gateway.json")
    private fun ciphertext(probe: Probe) = File(probe.context.noBackupFilesDir, "ielts-gateway-token.aesgcm")
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun rejection(action: suspend () -> Unit): IeltsGatewayFailureCode {
        try { action() }
        catch (failure: IeltsGatewayFailure) { return failure.code }
        throw AssertionError("The operation did not reject the invalid pairing")
    }

    private fun runProbe(name: String, action: suspend (Probe) -> Unit) {
        assumeTrue("Production TLS probes require an explicit opt-in",
            InstrumentationRegistry.getArguments().getString("lexiconIeltsTlsProbe") == "1")
        assumeTrue("Production TLS probes require an operator-supplied pairing file",
            File(inputDirectory, "pairing.json").isFile)
        val root = File(base.cacheDir, "ielts-production-tls-${UUID.randomUUID()}")
        check(root.mkdirs())
        val probe = Probe(isolated(root), root)
        var passed = false
        var failureCode: String? = null
        try {
            runBlocking { action(probe) }
            passed = true
        } catch (failure: Throwable) {
            // Do not attach the original exception: transport/parser failures may contain private input.
            failureCode = when (failure) {
                is IeltsGatewayFailure -> failure.code.name
                is AssertionError -> "ASSERTION_FAILED"
                else -> "PROBE_FAILED"
            }
        } finally {
            // ApiKeyStore.clear only removes this isolated ciphertext; no Keystore alias is deleted.
            runCatching { runBlocking { IeltsGatewaySettings(probe.context).clearPairing() } }
            val cleaned = root.deleteRecursively()
            if (!cleaned) { passed = false; failureCode = "ISOLATED_CLEANUP_FAILED" }
            val receipt = buildJsonObject {
                put("schema", "lexicon.ielts.tls-device-probe.v1")
                put("case", name)
                put("status", if (passed) "passed" else "failed")
                put("checkpoint", probe.checkpoint)
                put("explicit_opt_in", true)
                put("production_transport", true)
                put("production_keystore_used", probe.usesKeystore)
                put("isolated_files_only", true)
                put("isolated_files_cleaned", cleaned)
                put("health_responses_validated", probe.healthResponses)
                put("generation_methods_called", 0)
                put("model_api_methods_called", 0)
                put("provider_usage_verified_by_this_probe", false)
                put("peer_chain_verified", probe.peerChainVerified)
                probe.pin?.let { put("certificate_sha256", it) }
                probe.replacementPin?.let { put("replacement_certificate_sha256", it) }
                probe.expectedRejection?.let { put("expected_rejection", it.name) }
                probe.oldClientRejection?.let { put("old_client_rejection", it.name) }
                failureCode?.let { put("failure_code", it) }
            }
            val directory = File(base.filesDir, "validation-1.36.0/tls-probe").apply { mkdirs() }
            File(directory, "$name.json").writeText(receipt.toString())
        }
        if (!passed) throw AssertionError("TLS probe $name failed: $failureCode")
    }

    @Test fun localInspectionThenExplicitPairingSurvivesSettingsRecreationWithoutPlaintextSecrets() = runProbe("import-and-restore") { probe ->
        val bytes = readInput("pairing.json")
        try {
            val document = IeltsPairingDocument.parse(bytes)
            probe.pin = document.trust.certificateSha256
            val settings = IeltsGatewaySettings(probe.context)
            probe.checkpoint = "local-inspection"
            val preview = settings.inspectPairing(bytes)
            assertEquals(document.endpoint.url, preview.endpoint)
            assertEquals(probe.pin, preview.certificateSha256)
            assertFalse("Inspect must not persist any files", probe.root.walkTopDown().any { it.isFile })
            assertFalse(settings.snapshot().paired)
            assertFalse(preview.toString().contains(document.token))
            assertFalse(document.toString().contains(document.token))
            assertFalse(document.toString().contains("BEGIN CERTIFICATE"))

            probe.checkpoint = "explicit-import"
            val health = settings.pairImported(bytes)
            IeltsGatewayProtocol.validateHealth(health)
            probe.healthResponses++; probe.usesKeystore = true
            probe.checkpoint = "restored-authenticated-health"
            val restored = IeltsGatewaySettings(probe.context)
            val state = restored.snapshot()
            assertTrue(state.paired)
            assertEquals(document.endpoint.url, state.endpoint)
            assertEquals(probe.pin, state.certificateSha256)
            assertFalse(state.toString().contains(document.token))
            assertEquals(health, restored.createClient().health())
            probe.healthResponses++

            probe.checkpoint = "persisted-secret-exclusion"
            val plain = configuration(probe).readText()
            val encrypted = ciphertext(probe).readBytes()
            assertFalse("Ordinary configuration contains the pairing secret", plain.contains(document.token))
            assertFalse(plain.contains("pairing_token")); assertFalse(plain.contains("Bearer "))
            assertTrue("Ciphertext uses the production envelope", encrypted.size >= 29 && encrypted[0] == 1.toByte())
            assertFalse("Ciphertext contains the plaintext token", encrypted.toString(Charsets.ISO_8859_1).contains(document.token))
        } finally { bytes.fill(0) }
    }

    @Test fun wrongImportedCertificateRejectsTlsAndPreservesTheWorkingPair() = runProbe("wrong-certificate") { probe ->
        val originalBytes = readInput("pairing.json")
        val replacementBytes = readInput("replacement-pairing.json")
        try {
            val original = IeltsPairingDocument.parse(originalBytes)
            val replacement = IeltsPairingDocument.parse(replacementBytes)
            assertTrue("Replacement must target the same origin", original.endpoint == replacement.endpoint)
            assertTrue("Replacement must use a different valid certificate", original.trust.certificateSha256 != replacement.trust.certificateSha256)
            probe.pin = original.trust.certificateSha256; probe.replacementPin = replacement.trust.certificateSha256
            val settings = IeltsGatewaySettings(probe.context)
            probe.checkpoint = "original-import"
            val health = settings.pairImported(originalBytes)
            probe.healthResponses++; probe.usesKeystore = true
            val beforeState = settings.snapshot()
            val beforeConfiguration = configuration(probe).readBytes()
            val beforeCiphertext = ciphertext(probe).readBytes()
            val oldClient = settings.createClient()
            probe.checkpoint = "wrong-certificate-rejection"
            val rejected = rejection { settings.pairImported(replacementBytes) }
            assertEquals(IeltsGatewayFailureCode.TLS_REJECTED, rejected)
            probe.expectedRejection = rejected
            probe.checkpoint = "working-pair-preserved"
            assertTrue("Rejected TLS changed configuration", beforeConfiguration.contentEquals(configuration(probe).readBytes()))
            assertTrue("Rejected TLS changed ciphertext", beforeCiphertext.contentEquals(ciphertext(probe).readBytes()))
            assertEquals(beforeState, IeltsGatewaySettings(probe.context).snapshot())
            assertEquals(health, oldClient.health())
            probe.healthResponses++
        } finally { originalBytes.fill(0); replacementBytes.fill(0) }
    }

    @Test fun replacingOnlyThePlaintextCertificateBreaksTheEncryptedBindingAndStopsTheOldClient() = runProbe("certificate-binding") { probe ->
        val originalBytes = readInput("pairing.json")
        val replacementBytes = readInput("replacement-pairing.json")
        try {
            val original = IeltsPairingDocument.parse(originalBytes)
            val replacement = IeltsPairingDocument.parse(replacementBytes)
            assertTrue("Replacement must target the same origin", original.endpoint == replacement.endpoint)
            assertTrue("Replacement certificate must differ", original.trust.certificateSha256 != replacement.trust.certificateSha256)
            probe.pin = original.trust.certificateSha256; probe.replacementPin = replacement.trust.certificateSha256
            val settings = IeltsGatewaySettings(probe.context)
            probe.checkpoint = "original-authenticated-health"
            val health = settings.pairImported(originalBytes)
            probe.healthResponses++; probe.usesKeystore = true
            val oldClient = settings.createClient()
            assertEquals(health, oldClient.health()); probe.healthResponses++
            probe.checkpoint = "plaintext-certificate-tamper"
            val beforeCiphertext = ciphertext(probe).readBytes()
            val saved = Json.parseToJsonElement(configuration(probe).readText()).jsonObject
            configuration(probe).writeText(JsonObject(saved + ("certificate_pem" to JsonPrimitive(replacement.trust.pem))).toString())
            assertTrue("The tamper fixture must leave ciphertext untouched", beforeCiphertext.contentEquals(ciphertext(probe).readBytes()))
            probe.checkpoint = "ciphertext-binding-rejection"
            val restored = IeltsGatewaySettings(probe.context)
            assertEquals(IeltsGatewayFailureCode.STORAGE_UNAVAILABLE, rejection { restored.snapshot() })
            assertEquals(IeltsGatewayFailureCode.STORAGE_UNAVAILABLE, rejection { restored.createClient() })
            probe.expectedRejection = IeltsGatewayFailureCode.STORAGE_UNAVAILABLE
            probe.checkpoint = "old-client-dispatch-rejection"
            val rejected = rejection { oldClient.health() }
            assertEquals(IeltsGatewayFailureCode.CONFIGURATION_CHANGED, rejected)
            probe.oldClientRejection = rejected
            // The production token-provider check precedes Call.Factory.newCall; the host receipt independently checks traffic.
        } finally { originalBytes.fill(0); replacementBytes.fill(0) }
    }

    @Test fun productionClientAcceptsTheSelectedCaSignedLeafAndExposesItsActualPeerChain() = runProbe("ca-signed-leaf") { probe ->
        val bytes = readInput("ca-pairing.json")
        try {
            val document = IeltsPairingDocument.parse(bytes)
            probe.pin = document.trust.certificateSha256
            val selected = CertificateFactory.getInstance("X.509").generateCertificate(
                ByteArrayInputStream(document.trust.pem.toByteArray(Charsets.US_ASCII))) as X509Certificate
            assertTrue("This probe requires a CA-signed leaf", selected.subjectX500Principal != selected.issuerX500Principal)
            assertEquals(-1, selected.basicConstraints)
            val client = IeltsGatewayClient.secureClient(document.trust)
            try {
                probe.checkpoint = "ca-leaf-authenticated-tls"
                withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(document.endpoint.url + "health")
                        .header("Authorization", "Bearer ${document.token}")
                        .header("Accept", "application/json").get().build()
                    client.newCall(request).execute().use { response ->
                        assertEquals(200, response.code)
                        probe.checkpoint = "actual-peer-chain"
                        val peers = requireNotNull(response.handshake).peerCertificates
                        assertTrue("The real TLS response must expose its peer chain", peers.isNotEmpty())
                        assertEquals(document.trust.certificateSha256, digest(peers.first().encoded))
                        probe.checkpoint = "ca-health-body"
                        val body = requireNotNull(response.body)
                        assertEquals("application", body.contentType()?.type)
                        assertEquals("json", body.contentType()?.subtype)
                        val source = body.source()
                        source.request(IeltsGatewayProtocol.MAX_RESPONSE_BYTES.toLong() + 1)
                        assertTrue("Health response exceeds the production limit", source.buffer.size <= IeltsGatewayProtocol.MAX_RESPONSE_BYTES)
                        IeltsGatewayProtocol.validateHealth(IeltsGatewayProtocol.decodeHealth(source.readUtf8()))
                        probe.healthResponses++; probe.peerChainVerified = true
                    }
                }
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        } finally { bytes.fill(0) }
    }
}
