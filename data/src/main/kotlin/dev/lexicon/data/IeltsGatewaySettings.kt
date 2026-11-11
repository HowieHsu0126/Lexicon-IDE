package dev.lexicon.data

import android.content.Context
import android.util.AtomicFile
import dev.lexicon.domain.*
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Ordinary configuration and the independent Keystore ciphertext have separate storage paths. */
class IeltsGatewaySettings internal constructor(
    context: Context,
    private val pairingClient: (IeltsGatewayEndpoint, () -> String?, String?) -> IeltsGateway,
) : IeltsGatewayAccess {
    constructor(context: Context) : this(context, { endpoint, token, certificate ->
        IeltsGatewayClient(endpoint, token, trustedCertificatePem = certificate)
    })
    private val configuration = AtomicFile(File(context.filesDir, "ielts-gateway.json"))
    private val tokens = ApiKeyStore(context, fileName = "ielts-gateway-token.aesgcm", alias = "lexicon.ielts.gateway.token.v1")
    private val operations = Mutex()
    private val files = directoryLocks.computeIfAbsent(context.filesDir.canonicalPath + "\u0000" + context.noBackupFilesDir.canonicalPath) { Any() }

    override suspend fun snapshot(): IeltsGatewayConnection = operations.withLock {
        withContext(Dispatchers.IO) {
            synchronized(files) {
                val saved = readConfiguration()
                if (saved == null) IeltsGatewayConnection(null, false, null)
                else IeltsGatewayConnection(saved.endpoint.url, readToken(saved) != null, saved.health, saved.certificateSha256)
            }
        }
    }
    /** Validate the candidate through authenticated HTTPS before changing the saved pairing. */
    override suspend fun pair(endpoint: String, token: String): IeltsGatewayHealth = operations.withLock {
        val candidate = IeltsGatewayEndpoint.parse(endpoint)
        requireIeltsPairingToken(token)
        val health = pairingClient(candidate, { token }, null).health()
        IeltsGatewayProtocol.validateHealth(health)
        withContext(Dispatchers.IO) { synchronized(files) { storePair(candidate, token, health, null) } }
        health
    }
    override suspend fun inspectPairing(document: ByteArray): IeltsPairingPreview {
        val snapshot = document.copyOf()
        return try { withContext(Dispatchers.IO) { IeltsPairingDocument.parse(snapshot).preview() } }
        finally { snapshot.fill(0) }
    }
    override suspend fun pairImported(document: ByteArray): IeltsGatewayHealth {
        val snapshot = document.copyOf()
        return try { operations.withLock {
            val candidate = withContext(Dispatchers.IO) { IeltsPairingDocument.parse(snapshot) }
            val health = pairingClient(candidate.endpoint, { candidate.token }, candidate.trust.pem).health()
            IeltsGatewayProtocol.validateHealth(health)
            withContext(Dispatchers.IO) { synchronized(files) {
                storePair(candidate.endpoint, candidate.token, health, candidate.trust)
            } }
            health
        } } finally { snapshot.fill(0) }
    }
    override suspend fun clearPairing() = operations.withLock {
        withContext(Dispatchers.IO) { synchronized(files) { tokens.clear(); configuration.delete() } }
    }
    override suspend fun createClient(): IeltsGatewayClient = operations.withLock {
        withContext(Dispatchers.IO) {
            synchronized(files) {
                val saved = readConfiguration() ?: throw IeltsGatewayFailure(IeltsGatewayFailureCode.NOT_PAIRED)
                if (readToken(saved) == null) throw IeltsGatewayFailure(IeltsGatewayFailureCode.NOT_PAIRED)
                // An older client must not send a replacement host's token after
                // pairing changes. Recheck endpoint and health on every call.
                IeltsGatewayClient(saved.endpoint, tokenProvider = {
                    synchronized(files) {
                        if (readConfiguration() != saved) throw IeltsGatewayFailure(IeltsGatewayFailureCode.CONFIGURATION_CHANGED)
                        readToken(saved)
                    }
                }, expectedHealth = saved.health, trustedCertificatePem = saved.certificatePem)
            }
        }
    }
    private data class Saved(val endpoint: IeltsGatewayEndpoint, val health: IeltsGatewayHealth,
        val certificatePem: String? = null, val certificateSha256: String? = null)

    private fun storePair(endpoint: IeltsGatewayEndpoint, token: String, health: IeltsGatewayHealth, trust: IeltsGatewayTrust?) {
        trust?.checkValidity()
        val saved = Saved(endpoint, health, trust?.pem, trust?.certificateSha256)
        val encoded = buildJsonObject {
            put("schema", if (trust == null) "lexicon.ielts.gateway-settings.v1" else "lexicon.ielts.gateway-settings.v2")
            put("endpoint", endpoint.url)
            put("health", Json.parseToJsonElement(IeltsGatewayProtocol.encodeHealth(health)))
            trust?.let { put("certificate_pem", it.pem) }
        }.toString().toByteArray(Charsets.UTF_8)
        try {
            // The bound credential is one authenticated ciphertext. A crash
            // between its write and the ordinary configuration write cannot
            // associate this token with a different host on the next startup.
            tokens.save(tokenRecord(saved, token))
            val out = configuration.startWrite()
            try { out.write(encoded); configuration.finishWrite(out) }
            catch (error: Exception) { configuration.failWrite(out); throw error }
        } catch (_: Exception) {
            // Do not associate a new token with the previous host after a partial
            // write. Recovery is re-pairing; DeepSeek's store remains separate.
            tokens.clear(); configuration.delete()
            throw IeltsGatewayFailure(IeltsGatewayFailureCode.STORAGE_UNAVAILABLE)
        }
    }
    private fun readToken(saved: Saved): String? = try {
        tokens.read()?.let { record ->
            val pieces = record.split(':')
            val pinned = saved.certificateSha256 != null
            require(pieces.size == if (pinned) 5 else 4)
            require(pieces[0] == if (pinned) "v2" else "v1")
            require(pieces[1] == IeltsGatewayProtocol.bodyHash(saved.endpoint.url))
            require(pieces[2] == IeltsExerciseJson.canonicalSha256(IeltsGatewayProtocol.encodeHealth(saved.health)))
            if (pinned) require(pieces[3] == saved.certificateSha256)
            pieces.last().also(::requireIeltsPairingToken)
        }
    } catch (_: Exception) { throw IeltsGatewayFailure(IeltsGatewayFailureCode.STORAGE_UNAVAILABLE) }

    private fun tokenRecord(saved: Saved, token: String): String {
        val identity = "${IeltsGatewayProtocol.bodyHash(saved.endpoint.url)}:${IeltsExerciseJson.canonicalSha256(IeltsGatewayProtocol.encodeHealth(saved.health))}"
        return if (saved.certificateSha256 == null) "v1:$identity:$token"
        else "v2:$identity:${saved.certificateSha256}:$token"
    }

    private fun readConfiguration(): Saved? {
        if (!configuration.baseFile.exists()) return null
        return try {
            val bytes = configuration.readFully(); require(bytes.size <= 32_768)
            val canonical = IeltsExerciseJson.canonicalJson(bytes.toString(Charsets.UTF_8))
            val value = Json.parseToJsonElement(canonical).jsonObject
            val pinned = value["schema"] == JsonPrimitive("lexicon.ielts.gateway-settings.v2")
            require(pinned || value["schema"] == JsonPrimitive("lexicon.ielts.gateway-settings.v1"))
            require(value.keys == if (pinned) setOf("schema", "endpoint", "health", "certificate_pem") else setOf("schema", "endpoint", "health"))
            val endpoint = value.getValue("endpoint").jsonPrimitive; require(endpoint.isString)
            val parsed = IeltsGatewayEndpoint.parse(endpoint.content)
            val trust = if (pinned) {
                val pem = value.getValue("certificate_pem").jsonPrimitive; require(pem.isString)
                // An expired certificate remains readable configuration; network use is rejected separately.
                IeltsGatewayTrust.fromPem(parsed, pem.content, checkDates = false)
            } else null
            Saved(parsed, IeltsGatewayProtocol.decodeHealth(value.getValue("health").toString()), trust?.pem, trust?.certificateSha256)
        } catch (_: Exception) { throw IeltsGatewayFailure(IeltsGatewayFailureCode.STORAGE_UNAVAILABLE) }
    }
    private companion object {
        // Settings may be reconstructed by more than one repository/component.
        // All readers and the two-file commit share the same directory lock.
        val directoryLocks = ConcurrentHashMap<String, Any>()
    }
}

internal fun requireIeltsPairingToken(value: String) {
    try {
        require(Regex("[A-Za-z0-9_-]{43,128}").matches(value))
        require(Base64.getUrlDecoder().decode(value).size >= 32)
    } catch (_: Exception) { throw IeltsGatewayFailure(IeltsGatewayFailureCode.TOKEN_INVALID) }
}
