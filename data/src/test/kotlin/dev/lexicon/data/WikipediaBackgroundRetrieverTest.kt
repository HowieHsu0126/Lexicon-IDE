package dev.lexicon.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Deterministic provider-contract tests. Every request terminates at a local MockWebServer. */
class WikipediaBackgroundRetrieverTest {
    private val now = Instant.parse("2026-11-01T08:00:00Z").toEpochMilli()
    private fun retriever(server: MockWebServer, clock: () -> Long = { now }, client: OkHttpClient = OkHttpClient()) =
        WikipediaBackgroundRetriever(clock, server.url("/w/api.php").toString(), client)

    @Test fun sendsOnlyNormalizedTermAndNeverInheritsCredentialsOrContext() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response())
            server.enqueue(response(page(10, "City council", extract = "A city council is a local governmental body. It considers local public services and civic matters. This is synthetic evidence.")))
            server.start()
            val unsafeClient = OkHttpClient.Builder()
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer synthetic-secret").header("X-Private", "private").build()) }
                .addNetworkInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("X-Private-Network", "private").build()) }
                .cookieJar(object : CookieJar {
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                    override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().domain(url.host).name("session").value("synthetic-cookie").build())
                }).build()
            val result = retriever(server, client = unsafeClient).retrieve("  city   council  ", "PRIVATE_CONTEXT_83271 exact sentence that must stay local")
            assertEquals("retrieved", result.status)
            val exact = server.takeRequest(1, TimeUnit.SECONDS)!!
            val search = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("city council", exact.requestUrl!!.queryParameter("titles"))
            assertEquals("1", exact.requestUrl!!.queryParameter("redirects"))
            assertNull(exact.requestUrl!!.queryParameter("generator"))
            assertEquals("city council", search.requestUrl!!.queryParameter("gsrsearch"))
            assertEquals("5", search.requestUrl!!.queryParameter("gsrlimit"))
            for (request in listOf(exact, search)) {
                assertEquals("GET", request.method)
                assertEquals("1", request.requestUrl!!.queryParameter("explaintext"))
                assertEquals("1", request.requestUrl!!.queryParameter("exintro"))
                assertEquals("1200", request.requestUrl!!.queryParameter("exchars"))
                assertEquals("5", request.requestUrl!!.queryParameter("maxlag"))
                assertEquals(0L, request.bodySize)
                assertFalse(request.path!!.contains("PRIVATE_CONTEXT"))
                assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
                assertNull(request.getHeader("X-Private")); assertNull(request.getHeader("X-Private-Network"))
                assertEquals(WikipediaBackgroundRetriever.USER_AGENT, request.getHeader("User-Agent"))
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun sanitizesEvidenceDeduplicatesPagesAndUsesClientOwnedRevisionUrls() = runBlocking {
        MockWebServer().use { server ->
            val cleanable = "<p>A city council &amp; its elected members discuss local services for residents.</p>" +
                "<script>secret_script()</script><style>secret_style</style><iframe>secret_frame</iframe>" +
                "<p>The excerpt is a synthetic explanation of municipal decisions and public matters.</p>"
            val valid = page(7, "<b>City council</b>", cleanable, revision = 70, rank = 4).toMutableMap().apply {
                put("fullurl", JsonPrimitive("https://untrusted.invalid/pretend-source")); put("unknown_provider_field", JsonPrimitive(true))
            }.let(::JsonObject)
            server.enqueue(response())
            server.enqueue(response(
                valid, valid,
                page(8, "City council selection", "City council selection is described in this synthetic civic text about local municipal structures and elections.", revision = 80),
                page(9, "City council chamber", "City council chambers are meeting spaces in this synthetic account of local civic offices and public deliberations.", revision = null),
                page(10, "City council proceedings", "City council proceedings are the subject of this synthetic introductory text about local civic meetings and deliberations."),
                page(11, "City council", "City council may refer to different subjects and locations. This disambiguation fixture must not be evidence.", disambiguation = true),
                page(12, "City council", "City council text should not be returned because this page is marked missing even though it contains sample text.", missing = true),
            ))
            server.start()
            val result = retriever(server).retrieve("city council", "local services")
            assertEquals("retrieved", result.status)
            assertEquals(listOf("S1", "S2", "S3"), result.sources.map { it.id })
            assertEquals(3, result.sources.map { it.url }.distinct().size)
            val source = result.sources.first()
            assertEquals("City council", source.title)
            assertTrue(source.excerpt.contains("& its elected"))
            assertFalse(source.excerpt.contains("secret_")); assertFalse(source.excerpt.contains("<"))
            assertEquals("https://en.wikipedia.org/w/index.php?curid=7&oldid=70", source.url)
            assertEquals("70", source.revisionId); assertEquals("Wikipedia (en)", source.provider)
            assertEquals(now, source.retrievedAt)
            assertTrue(result.sources.all { it.excerpt.length <= WikipediaBackgroundRetriever.MAX_EXCERPT + 1 })
            assertTrue(result.sources.none { it.url.contains("untrusted.invalid") || it.url.contains("curid=11") || it.url.contains("curid=12") })
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun withoutExactEvidenceContextRanksButDoesNotFilterSearchCandidates() = runBlocking {
        MockWebServer().use { server ->
            val finance = page(20, "Bank finance", "A bank manages finance credit deposits and payments. " + "Synthetic financial background. ".repeat(90), rank = 1)
            val river = page(21, "Bank geography", "A bank borders a river where water and erosion affect nearby ground. " + "Synthetic geographical background. ".repeat(90), rank = 2)
            server.enqueue(response()); server.enqueue(response(finance, river)); server.start()
            val result = retriever(server).retrieve("bank", "river water erosion geography")
            assertEquals("Bank geography", result.sources.first().title)
            assertEquals(setOf("Bank geography", "Bank finance"), result.sources.map { it.title }.toSet())
            assertTrue(result.sources.all { it.excerpt.length <= WikipediaBackgroundRetriever.MAX_EXCERPT + 1 })
            assertTrue(result.sources.all { it.excerpt.endsWith("…") })
            val exact = server.takeRequest(1, TimeUnit.SECONDS)!!
            val search = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("bank", exact.requestUrl!!.queryParameter("titles"))
            assertEquals("bank", search.requestUrl!!.queryParameter("gsrsearch"))
            for (request in listOf(exact, search)) {
                assertFalse(request.path!!.contains("river")); assertFalse(request.path!!.contains("erosion"))
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun exactTitleRedirectEvidencePrecedesSearchAndSamePageIsNotDuplicated() = runBlocking {
        MockWebServer().use { server ->
            val concept = page(40, "Municipal council", "A municipal council is a form of local representative government. This synthetic introduction describes the general civic institution.", revision = 400)
            server.enqueue(jsonResponse(buildJsonObject {
                putJsonObject("query") {
                    putJsonArray("redirects") { add(buildJsonObject { put("from", "city council"); put("to", "Municipal council") }) }
                    putJsonArray("pages") { add(concept) }
                }
            }.toString()))
            server.enqueue(response(
                concept.toMutableMap().apply { put("revisions", buildJsonArray { add(buildJsonObject { put("revid", 401) }) }) }.let(::JsonObject),
                page(41, "City council", "A city council in a specific place is discussed by this synthetic search result. It must not displace the directly resolved general concept.", rank = 1),
            ))
            server.start()
            val result = retriever(server).retrieve("city council", "")
            assertEquals("retrieved", result.status)
            assertEquals("Municipal council", result.sources.first().title)
            assertEquals("400", result.sources.first().revisionId)
            assertEquals(1, result.sources.count { it.url.contains("curid=40&") })
            assertEquals(listOf("S1", "S2"), result.sources.map { it.id })
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun exactConceptEvidenceExcludesCitySpecificCouncilsUnsupportedByContext() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response(page(50, "Local government",
                "Local government is public administration at the local level. This synthetic introduction includes city council institutions and local civic services.")))
            server.enqueue(response(
                page(51, "Vancouver City Council",
                    "Vancouver City Council is the local governing body described by this synthetic introduction. Its municipal responsibilities include civic services and decisions."),
                page(52, "Glasgow City Council",
                    "Glasgow City Council is the municipal body described by this synthetic introduction. It makes local decisions about public services and community facilities."),
            ))
            server.start()
            val result = retriever(server).retrieve("city council", "The school courtyard is being renovated.")
            assertEquals("retrieved", result.status)
            assertEquals(listOf("Local government"), result.sources.map { it.title })
            assertEquals(listOf("S1"), result.sources.map { it.id })
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun exactHolidayEvidenceKeepsOnlyContextSupportedCountryAndDinnerQualifiers() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response(page(60, "Thanksgiving",
                "Thanksgiving is a holiday observed in several countries. This synthetic introduction describes its general traditions and the meals associated with it.")))
            server.enqueue(response(
                page(61, "Thanksgiving (United States)",
                    "Thanksgiving in the United States is described by this synthetic introduction. Family gatherings and shared meals are part of the holiday traditions.", rank = 2),
                page(62, "Thanksgiving dinner",
                    "Thanksgiving dinner is a shared holiday meal described by this synthetic introduction. Families gather and prepare foods associated with the celebration.", rank = 3),
                page(63, "Thanksgiving (Canada)",
                    "Thanksgiving in Canada is described by this synthetic introduction. Family gatherings and shared meals are part of the holiday traditions.", rank = 1),
            ))
            server.start()
            val result = retriever(server).retrieve("Thanksgiving", "A US family gathers for dinner.")
            assertEquals("retrieved", result.status)
            assertEquals("Thanksgiving", result.sources.first().title)
            assertEquals(setOf("Thanksgiving", "Thanksgiving (United States)", "Thanksgiving dinner"),
                result.sources.map { it.title }.toSet())
            assertEquals(listOf("S1", "S2", "S3"), result.sources.map { it.id })
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun unknownPageShapesReturnUnavailableInsteadOfThrowingFromCombinedParsing() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse("{\"query\":{\"pages\":[\"unexpected page shape\"]}}"))
            server.enqueue(response())
            server.start()
            val result = retriever(server).retrieve("city council", "")
            assertEquals("unavailable", result.status); assertTrue(result.sources.isEmpty())
            assertTrue(server.requestCount in 1..2)
        }
    }

    @Test fun clippingDoesNotLeaveUnpairedUtf16Surrogates() = runBlocking {
        MockWebServer().use { server ->
            val prefix = "City council "
            val extract = prefix + "x".repeat(WikipediaBackgroundRetriever.MAX_EXCERPT - prefix.length - 1) + "😀 trailing evidence"
            server.enqueue(response()); server.enqueue(response(page(1, "City council", extract))); server.start()
            val excerpt = retriever(server).retrieve("city council", "").sources.single().excerpt
            assertTrue(excerpt.endsWith("…"))
            for (i in excerpt.indices) {
                if (excerpt[i].isHighSurrogate()) assertTrue(i + 1 < excerpt.length && excerpt[i + 1].isLowSurrogate())
                if (excerpt[i].isLowSurrogate()) assertTrue(i > 0 && excerpt[i - 1].isHighSurrogate())
            }
        }
    }

    @Test fun emptyMissingDisambiguationAndUnrelatedPagesNeverBecomeEvidence() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response()); server.enqueue(response())
            server.enqueue(response())
            server.enqueue(response(
                page(1, "City council", "short"),
                page(2, "City council", "City council may refer to various subjects. This synthetic disambiguation introduction must be excluded.", disambiguation = true),
                page(3, "City council", "City council page is unavailable. This synthetic missing-page introduction must be excluded from citations.", missing = true),
                page(4, "Botanical garden", "Plants and trees grow in landscaped settings where horticultural collections can be studied by visiting botanists."),
                page(-1, "City council", "City council invalid page identifiers must never become source links even when this synthetic introduction is long enough."),
            ))
            server.start(); val service = retriever(server)
            repeat(2) {
                val result = service.retrieve("city council", "")
                assertEquals("no_results", result.status); assertTrue(result.sources.isEmpty())
            }
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun malformedAndUnexpectedResponsesAreUnavailableWithoutInventedSources() = runBlocking {
        MockWebServer().use { server ->
            listOf(
                jsonResponse("not json"),
                jsonResponse("{\"query\":{\"pages\":{\"unexpected\":true}}}"),
                jsonResponse("{\"error\":{\"code\":\"maxlag\",\"info\":\"synthetic\"}}"),
                MockResponse().setHeader("Content-Type", "text/html").setBody("<html>login or verification page</html>"),
                jsonResponse("x".repeat(WikipediaBackgroundRetriever.MAX_RESPONSE_BYTES + 1)),
            ).forEach(server::enqueue)
            server.start(); val service = retriever(server)
            repeat(5) {
                val result = service.retrieve("city council", "")
                assertEquals("unavailable", result.status); assertTrue(result.sources.isEmpty())
            }
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun numericRetryAfterSuppressesNetworkUntilSharedRetrieverCooldownExpires() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
            server.enqueue(response()); server.enqueue(response(page(1, "City council")))
            server.start(); var time = now; val service = retriever(server, { time })
            assertEquals("unavailable", service.retrieve("city council", "").status)
            time += 119_000
            assertEquals("unavailable", service.retrieve("public library", "different query does not bypass cooldown").status)
            assertEquals(1, server.requestCount)
            time += 1001
            assertEquals("retrieved", service.retrieve("city council", "").status)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun httpDateRetryAfterIsHonoredAnd503IsNotAutomaticallyRetried() = runBlocking {
        MockWebServer().use { server ->
            val deadline = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now + 60_000).atZone(ZoneOffset.UTC))
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", deadline))
            server.enqueue(response()); server.enqueue(response(page(1, "City council")))
            server.start(); var time = now; val service = retriever(server, { time })
            assertEquals("unavailable", service.retrieve("city council", "").status)
            time += 59_000
            assertEquals("unavailable", service.retrieve("city council", "").status)
            assertEquals(1, server.requestCount)
            time += 1001
            assertEquals("retrieved", service.retrieve("city council", "").status)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun redirectsAreNotFollowedToAnotherHostOrPath() = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { destination ->
            destination.enqueue(response(page(1, "City council"))); destination.start()
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/private-target"))); first.start()
            val result = retriever(first).retrieve("city council", "")
            assertEquals("unavailable", result.status); assertTrue(result.sources.isEmpty())
            assertEquals(1, first.requestCount)
            assertNull(destination.takeRequest(100, TimeUnit.MILLISECONDS))
            assertEquals(0, destination.requestCount)
        } }
    }

    @Test fun disconnectedTransportReturnsUnavailableAndDoesNotRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.start()
            val result = retriever(server).retrieve("city council", "")
            assertEquals("unavailable", result.status); assertTrue(result.sources.isEmpty())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cancellationIsPropagatedInsteadOfBeingTurnedIntoUnavailableEvidence() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start()
            val service = retriever(server)
            val deferred = async { service.retrieve("city council", "") }
            val recorded = withContext(Dispatchers.IO) { server.takeRequest(1, TimeUnit.SECONDS) }
            assertNotNull(recorded)
            withTimeout(2000) { deferred.cancelAndJoin() }
            assertTrue(deferred.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun invalidTermsAndNonProviderEndpointsAreRejectedBeforeNetwork() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val service = retriever(server)
            for (term in listOf("", " ", "x".repeat(161), "city\u0000council")) {
                try { service.retrieve(term, "context"); fail("Expected invalid term") } catch (_: IllegalArgumentException) { }
            }
            assertEquals(0, server.requestCount)
        }
        for (endpoint in listOf("https://example.org/w/api.php", "http://en.wikipedia.org/w/api.php", "https://en.wikipedia.org/wiki/Test", "https://en.wikipedia.org/w/api.php?injected=true")) {
            assertThrows(IllegalArgumentException::class.java) { WikipediaBackgroundRetriever(endpoint = endpoint) }
        }
    }

    private fun response(vararg pages: JsonObject) = jsonResponse(buildJsonObject {
        putJsonObject("query") { putJsonArray("pages") { pages.forEach(::add) } }
        put("unknown_top_level", "ignored future metadata")
    }.toString())
    private fun jsonResponse(body: String) = MockResponse().setHeader("Content-Type", "application/json; charset=utf-8").setBody(body)
    private fun page(id: Long, title: String, extract: String = "A city council is a local civic organization in this synthetic introduction. It discusses public services and decisions for residents.",
        revision: Long? = id * 10, rank: Int = 1, missing: Boolean = false, disambiguation: Boolean = false): JsonObject = buildJsonObject {
        put("pageid", id); put("title", title); put("extract", extract); put("index", rank)
        if (revision != null) putJsonArray("revisions") { add(buildJsonObject { put("revid", revision) }) }
        if (missing) put("missing", true)
        if (disambiguation) putJsonObject("pageprops") { put("disambiguation", "") }
    }
}
