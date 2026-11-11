package dev.lexicon.data

import dev.lexicon.domain.*
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

/** Fake HTTP transport only. Production client construction never accepts this factory. */
class IeltsGatewayClientTest {
    private val endpoint = IeltsGatewayEndpoint.parse("https://gateway.example/")
    private val token = "A".repeat(43)
    private val health = IeltsGatewayHealth("lexicon.ielts.health.v1", "ok", IeltsGatewayCapabilities("0.2.1-rc.5", "test-corpus", "a".repeat(64), listOf("multiple_choice"), listOf("extent_of_agreement"), "official_public_samples_and_format_metadata"))
    private val submit = IeltsGatewaySubmit(requestId = "request-one", articleId = "article-one", body = "Original text.", paragraphs = listOf(IeltsParagraph("p1", 0, 14, "Original text.")), readingRequests = listOf(IeltsChoiceRequest("q1")), writingTask2Request = IeltsWritingRequest("w2", IeltsWritingType.EXTENT_OF_AGREEMENT))
    private val job = IeltsGatewayJob("job-" + "a".repeat(32), submit.requestId, "b".repeat(64), IeltsGatewayJobState.QUEUED, 0, 1, 1, null, 1)
    private class Scripted : Call.Factory {
        val requests = mutableListOf<Request>()
        val answers = ArrayDeque<(Request) -> Response>()
        fun response(code: Int, text: String, type: String = "application/json; charset=utf-8", location: String? = null) {
            answers.add { request -> Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(text.toResponseBody(type.toMediaType())).apply { if (location != null) header("Location", location) }.build() }
        }
        fun failure(error: IOException) { answers.add { throw error } }
        override fun newCall(request: Request): Call {
            requests += request
            val answer = answers.removeFirst()
            return object : Call {
                private var executed = false
                private var cancelled = false
                override fun request() = request
                override fun execute(): Response { executed = true; return answer(request) }
                override fun enqueue(callback: Callback) { executed = true; try { callback.onResponse(this, answer(request)) } catch (error: IOException) { callback.onFailure(this, error) } }
                override fun cancel() { cancelled = true }
                override fun isExecuted() = executed
                override fun isCanceled() = cancelled
                override fun clone(): Call = error("Unexpected retry/clone")
                override fun timeout() = Timeout.NONE
            }
        }
    }
    private fun client(script: Scripted, key: String? = token, expected: IeltsGatewayHealth? = health) = IeltsGatewayClient.forTest(endpoint, { key }, script, expected)
    private suspend fun failure(code: IeltsGatewayFailureCode, block: suspend () -> Unit) {
        try { block(); fail("Expected fixed gateway failure") } catch (error: IeltsGatewayFailure) { assertEquals(code, error.code); assertNull(error.cause) }
    }
    @Test fun productionTransportKeepsDefaultTlsAndDisablesRedirectAndConnectionRetry() {
        val client = IeltsGatewayClient.secureClient()
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects); assertFalse(client.retryOnConnectionFailure)
        assertSame(OkHttpClient().hostnameVerifier, client.hostnameVerifier)
        assertTrue(client.interceptors.isEmpty()); assertTrue(client.networkInterceptors.isEmpty())
        assertSame(Authenticator.NONE, client.authenticator); assertSame(Authenticator.NONE, client.proxyAuthenticator)
    }
    @Test fun healthUsesOnlyBearerHeaderAndTypedPinnedCapabilities() = runBlocking {
        val script = Scripted().apply { response(200, IeltsGatewayProtocol.encodeHealth(health)) }
        assertEquals(health, client(script).health())
        val request = script.requests.single()
        assertEquals("https://gateway.example/health", request.url.toString())
        assertEquals("Bearer $token", request.header("Authorization")); assertFalse(request.url.toString().contains(token))
        assertEquals("GET", request.method); assertNull(request.body)
    }
    @Test fun changedHealthFailsInsteadOfSilentlyRepinningTheHost() = runBlocking {
        val script = Scripted().apply { response(200, IeltsGatewayProtocol.encodeHealth(health.copy(capabilities = health.capabilities.copy(corpusSha256 = "c".repeat(64))))) }
        failure(IeltsGatewayFailureCode.CONFIGURATION_CHANGED) { client(script).health() }
    }
    @Test fun submitUsesExactlyTheAcceptedSchemaAndReturnsTheDurableJob() = runBlocking {
        val script = Scripted().apply { response(202, IeltsGatewayProtocol.encodeJob(job)) }
        assertEquals(job, client(script).submit(submit))
        val sent = script.requests.single(); assertEquals("POST", sent.method)
        assertTrue(sent.body!!.isOneShot())
        val buffer = okio.Buffer(); sent.body!!.writeTo(buffer)
        assertEquals(submit, IeltsGatewayProtocol.decodeSubmit(buffer.readUtf8()))
        assertFalse(sent.url.toString().contains(token))
    }
    @Test fun timeoutRetainsRequestIdAndOnlyExplicitByRequestReconciles() = runBlocking {
        val script = Scripted().apply { failure(SocketTimeoutException("secret-upstream-error-$token")); response(200, IeltsGatewayProtocol.encodeJob(job)) }
        val client = client(script)
        try { client.submit(submit); fail("Expected uncertain submission") }
        catch (error: IeltsGatewaySubmissionUncertain) { assertEquals(submit.requestId, error.requestId); assertNull(error.cause); assertFalse(error.toString().contains(token)) }
        assertEquals(1, script.requests.size)
        assertEquals(job, client.byRequest(submit.requestId))
        assertEquals(listOf("POST", "GET"), script.requests.map { it.method })
        assertEquals("/exercise-jobs/by-request/request-one", script.requests.last().url.encodedPath)
    }
    @Test fun hostUnknownOutcomeAndMalformed202NeverTriggerASecondPost() = runBlocking {
        for ((code, text) in listOf(504 to """{"schema":"lexicon.ielts.http-error.v1","error":{"code":"submit_outcome_unknown","request_id":"request-one"}}""", 202 to "{}", 202 to IeltsGatewayProtocol.encodeJob(job.copy(requestId = "wrong")))) {
            val script = Scripted().apply { response(code, text) }
            try { client(script).submit(submit); fail("Expected uncertain outcome") }
            catch (error: IeltsGatewaySubmissionUncertain) { assertEquals("request-one", error.requestId) }
            assertEquals(1, script.requests.size)
        }
    }
    @Test fun unexpectedSuccessOrRedirectRetainsTheSubmissionIdForReconciliation() = runBlocking {
        for (status in listOf(200, 201, 204, 301, 307, 308)) {
            val script = Scripted().apply { response(status, IeltsGatewayProtocol.encodeJob(job), location = "https://other.example/") }
            try { client(script).submit(submit); fail("Expected uncertain submission receipt") }
            catch (error: IeltsGatewaySubmissionUncertain) { assertEquals(submit.requestId, error.requestId) }
            assertEquals(1, script.requests.size)
        }
    }
    @Test fun redirectNeverTransfersTokenToAnotherOrigin() = runBlocking {
        val script = Scripted().apply { response(307, "{}", location = "https://other.example/steal") }
        failure(IeltsGatewayFailureCode.INVALID_RESPONSE) { client(script).health() }
        assertEquals(1, script.requests.size); assertEquals("gateway.example", script.requests.single().url.host)
    }
    @Test fun invalidOrMissingPairingTokenFailsBeforeCreatingAnyCall() = runBlocking {
        for ((key, code) in listOf(null to IeltsGatewayFailureCode.NOT_PAIRED, "short" to IeltsGatewayFailureCode.TOKEN_INVALID, "B".repeat(42) + " " to IeltsGatewayFailureCode.TOKEN_INVALID)) {
            val script = Scripted()
            failure(code) { client(script, key).health() }
            assertTrue(script.requests.isEmpty())
        }
    }
    @Test fun getByRequestAndCancelUseFixedPathsAndCheckResponseIdentity() = runBlocking {
        val cancelling = job.copy(state = IeltsGatewayJobState.CANCELLING, processReaped = 0)
        val script = Scripted().apply {
            response(200, IeltsGatewayProtocol.encodeJob(job)); response(202, IeltsGatewayProtocol.encodeJob(cancelling))
            response(404, """{"schema":"lexicon.ielts.http-error.v1","error":{"code":"not_found"}}""")
        }
        val client = client(script)
        assertEquals(job, client.get(job.id)); assertEquals(cancelling, client.cancel(job.id)); assertNull(client.byRequest("missing"))
        val cancel = script.requests[1]; assertEquals("POST", cancel.method); assertEquals(0L, cancel.body!!.contentLength())
        assertTrue(cancel.body!!.isOneShot())
        assertTrue(cancel.url.encodedPath.endsWith("/cancel"))
    }
    @Test fun conflictingRequestAndUnauthorizedHostAreDefiniteFailures() = runBlocking {
        val script = Scripted().apply { response(409, """{"schema":"lexicon.ielts.http-error.v1","error":{"code":"request_id_conflict"}}""") }
        failure(IeltsGatewayFailureCode.CONFLICT) { client(script).submit(submit) }
        val denied = Scripted().apply { response(401, """{"schema":"lexicon.ielts.http-error.v1","error":{"code":"unauthorized"}}""") }
        failure(IeltsGatewayFailureCode.UNAUTHORIZED) { client(denied).health() }
    }
    @Test fun untrustedContentTypeOversizeAndRawErrorTextCannotEnterUiErrors() = runBlocking {
        for ((body, type) in listOf("private-$token" to "text/html", "x".repeat(IeltsGatewayProtocol.MAX_RESPONSE_BYTES + 1) to "application/json", "{}" to "application/json; charset=iso-8859-1")) {
            val script = Scripted().apply { response(200, body, type) }
            failure(IeltsGatewayFailureCode.INVALID_RESPONSE) { client(script).health() }
        }
        val script = Scripted().apply { failure(IOException("Authorization: Bearer $token")) }
        failure(IeltsGatewayFailureCode.TRANSPORT) { client(script).health() }
    }
    @Test fun invalidIdentifiersCannotBecomeUrlPaths() = runBlocking {
        val script = Scripted(); val client = client(script)
        for (operation in listOf<suspend () -> Unit>({ client.get("../health") }, { client.byRequest("x?token=private") }, { client.cancel("job-invalid") })) {
            try { operation(); fail("Expected identifier rejection") } catch (_: IllegalArgumentException) { }
        }
        assertTrue(script.requests.isEmpty())
    }
}
