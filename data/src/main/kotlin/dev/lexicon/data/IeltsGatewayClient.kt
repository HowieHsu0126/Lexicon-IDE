package dev.lexicon.data

import dev.lexicon.domain.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink

/** HTTPS with system trust or one explicitly imported host certificate; hostname validation is always retained. */
class IeltsGatewayClient private constructor(
    private val endpoint: IeltsGatewayEndpoint,
    private val tokenProvider: () -> String?,
    private val expectedHealth: IeltsGatewayHealth?,
    private val calls: Call.Factory,
    private val trust: IeltsGatewayTrust? = null,
) : IeltsGateway {
    constructor(endpoint: IeltsGatewayEndpoint, tokenProvider: () -> String?, expectedHealth: IeltsGatewayHealth? = null,
        trustedCertificatePem: String? = null) : this(endpoint, tokenProvider, expectedHealth,
        trustedCertificatePem?.let { IeltsGatewayTrust.fromPem(endpoint, it) })
    private constructor(endpoint: IeltsGatewayEndpoint, tokenProvider: () -> String?, expectedHealth: IeltsGatewayHealth?,
        trust: IeltsGatewayTrust?) : this(endpoint, tokenProvider, expectedHealth, secureClient(trust), trust)

    override suspend fun health(): IeltsGatewayHealth = decode {
        val value = IeltsGatewayProtocol.decodeHealth(exchange("GET", "health").requireCode(200).body)
        if (expectedHealth != null && value.capabilities != expectedHealth.capabilities)
            throw IeltsGatewayFailure(IeltsGatewayFailureCode.CONFIGURATION_CHANGED)
        value
    }
    override suspend fun submit(payload: IeltsGatewaySubmit): IeltsGatewayJob {
        val body = IeltsGatewayProtocol.encodeSubmit(payload)
        expectedHealth?.let { IeltsGatewayProtocol.requireSupported(payload, it) }
        val response = try { exchange("POST", "exercise-jobs", body) }
        catch (e: IeltsGatewayFailure) {
            if (e.code in setOf(IeltsGatewayFailureCode.TRANSPORT, IeltsGatewayFailureCode.INVALID_RESPONSE,
                    IeltsGatewayFailureCode.TLS_REJECTED))
                throw IeltsGatewaySubmissionUncertain(payload.requestId)
            throw e
        }
        if (response.status >= 500 || response.status == 408 || response.status in 200..399 && response.status != 202)
            throw IeltsGatewaySubmissionUncertain(payload.requestId)
        response.requireCode(202)
        // An accepted POST with an unreadable/mismatched receipt is still an
        // uncertain write. The caller keeps this ID and reconciles by-request.
        return try {
            IeltsGatewayProtocol.decodeJob(response.body).also {
                require(it.requestId == payload.requestId)
            }
        } catch (_: Exception) { throw IeltsGatewaySubmissionUncertain(payload.requestId) }
    }
    override suspend fun get(jobId: String): IeltsGatewayJob {
        IeltsGatewayProtocol.validateJobId(jobId)
        return decode { IeltsGatewayProtocol.decodeJob(exchange("GET", "exercise-jobs/$jobId").requireCode(200).body).also { require(it.id == jobId) } }
    }
    override suspend fun byRequest(requestId: String): IeltsGatewayJob? {
        IeltsGatewayProtocol.validateRequestId(requestId)
        val response = exchange("GET", "exercise-jobs/by-request/$requestId")
        if (response.status == 404 && IeltsGatewayProtocol.errorCode(response.body) == "not_found") return null
        return decode { IeltsGatewayProtocol.decodeJob(response.requireCode(200).body).also { require(it.requestId == requestId) } }
    }
    override suspend fun cancel(jobId: String): IeltsGatewayJob {
        IeltsGatewayProtocol.validateJobId(jobId)
        return decode { IeltsGatewayProtocol.decodeJob(exchange("POST", "exercise-jobs/$jobId/cancel").requireCode(202).body).also { require(it.id == jobId) } }
    }
    override suspend fun fetchPackage(job: IeltsGatewayJob, originalSubmit: IeltsGatewaySubmit, expectedHealth: IeltsGatewayHealth): IeltsGatewayVerifiedPackage {
        IeltsGatewayProtocol.validateJob(job); IeltsGatewayProtocol.requireSupported(originalSubmit, expectedHealth)
        if (this.expectedHealth != null && this.expectedHealth.capabilities != expectedHealth.capabilities)
            throw IeltsGatewayFailure(IeltsGatewayFailureCode.CONFIGURATION_CHANGED)
        require(job.requestId == originalSubmit.requestId && job.state == IeltsGatewayJobState.READY)
        val body = exchange("GET", "exercise-jobs/${job.id}/package").requireCode(200).body
        return withContext(Dispatchers.Default) {
            decode {
                val artifact = IeltsGatewayProtocol.decodeVerifiedPackage(body)
                IeltsGatewayProtocol.validateVerifiedPackage(artifact, job, originalSubmit, expectedHealth)
            }
        }
    }
    private data class GatewayResponse(val status: Int, val body: String) {
        fun requireCode(expected: Int): GatewayResponse {
            if (status == expected) return this
            val code = when {
                status == 401 || status == 403 -> IeltsGatewayFailureCode.UNAUTHORIZED
                status == 404 -> IeltsGatewayFailureCode.NOT_FOUND
                status == 409 && IeltsGatewayProtocol.errorCode(body) == "request_id_conflict" -> IeltsGatewayFailureCode.CONFLICT
                status == 409 && IeltsGatewayProtocol.errorCode(body) == "package_unavailable" -> IeltsGatewayFailureCode.PACKAGE_UNAVAILABLE
                status in 300..399 -> IeltsGatewayFailureCode.INVALID_RESPONSE
                status in 400..499 -> IeltsGatewayFailureCode.SUBMIT_REJECTED
                else -> IeltsGatewayFailureCode.REMOTE_FAILURE
            }
            throw IeltsGatewayFailure(code, status)
        }
    }
    private suspend fun exchange(method: String, path: String, json: String? = null): GatewayResponse = withContext(Dispatchers.IO) {
        // A reused TLS connection must not outlive the explicitly trusted certificate.
        trust?.checkValidity()
        val token = tokenProvider() ?: throw IeltsGatewayFailure(IeltsGatewayFailureCode.NOT_PAIRED)
        requireIeltsPairingToken(token)
        val request = Request.Builder().url(endpoint.url + path)
            .header("Authorization", "Bearer $token").header("Accept", "application/json")
            .apply {
                if (method == "POST") post(oneShotBody(json))
                else get()
            }.build()
        try {
            calls.newCall(request).await().use { response ->
                val responseBody = response.body ?: throw IeltsGatewayFailure(IeltsGatewayFailureCode.INVALID_RESPONSE)
                val contentType = responseBody.contentType()
                if (contentType == null || contentType.type != "application" || contentType.subtype != "json") throw IeltsGatewayFailure(IeltsGatewayFailureCode.INVALID_RESPONSE)
                val charset = contentType.charset(Charsets.UTF_8)
                if (charset != Charsets.UTF_8) throw IeltsGatewayFailure(IeltsGatewayFailureCode.INVALID_RESPONSE)
                if (responseBody.contentLength() > IeltsGatewayProtocol.MAX_RESPONSE_BYTES) throw IeltsGatewayFailure(IeltsGatewayFailureCode.INVALID_RESPONSE)
                val source = responseBody.source()
                source.request(IeltsGatewayProtocol.MAX_RESPONSE_BYTES.toLong() + 1)
                if (source.buffer.size > IeltsGatewayProtocol.MAX_RESPONSE_BYTES) throw IeltsGatewayFailure(IeltsGatewayFailureCode.INVALID_RESPONSE)
                val bytes = source.readByteArray()
                val raw = try {
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                } catch (_: Exception) { throw IeltsGatewayFailure(IeltsGatewayFailureCode.INVALID_RESPONSE) }
                GatewayResponse(response.code, raw)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: IeltsGatewayFailure) { throw failure }
        catch (_: SSLHandshakeException) { throw IeltsGatewayFailure(IeltsGatewayFailureCode.TLS_REJECTED) }
        catch (_: SSLPeerUnverifiedException) { throw IeltsGatewayFailure(IeltsGatewayFailureCode.TLS_REJECTED) }
        catch (_: IOException) { throw IeltsGatewayFailure(IeltsGatewayFailureCode.TRANSPORT) }
        catch (_: Exception) { throw IeltsGatewayFailure(IeltsGatewayFailureCode.INVALID_RESPONSE) }
    }
    private suspend fun <T> decode(block: suspend () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: IeltsGatewayFailure) { throw failure }
    catch (_: Exception) { throw IeltsGatewayFailure(IeltsGatewayFailureCode.INVALID_RESPONSE) }

    companion object {
        private fun oneShotBody(json: String?): RequestBody {
            val body = (json ?: "").toRequestBody(if (json == null) null else "application/json; charset=utf-8".toMediaType())
            // Connection retry policy alone does not cover every HTTP follow-up
            // (for example 503 Retry-After: 0). Mutations are never replayable.
            return object : RequestBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
            }
        }
        /** Trust is restricted to a parsed, user-selected leaf. No caller-supplied verifier or TLS factory. */
        internal fun secureClient(trust: IeltsGatewayTrust? = null): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .apply { trust?.socketFactory()?.let { (factory, manager) -> sslSocketFactory(factory, manager) } }.build()
        /** A fake transport is confined to same-module protocol tests; no insecure production option. */
        internal fun forTest(endpoint: IeltsGatewayEndpoint, tokenProvider: () -> String?, calls: Call.Factory, expectedHealth: IeltsGatewayHealth? = null): IeltsGatewayClient =
            IeltsGatewayClient(endpoint, tokenProvider, expectedHealth, calls)
    }
}
