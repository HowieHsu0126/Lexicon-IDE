package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic evidence and local transport only. Retrieval and prose have independently counted calls. */
class BackgroundResearchAiTest {
    private val query = BackgroundQuery("bank", "The river bank is changing after heavy rain.")
    private fun source(id: Int, title: String) = BackgroundSource("S$id", title,
        "https://en.wikipedia.org/w/index.php?curid=$id&oldid=${id + 100}",
        "This self-authored fixture describes $title as one possible meaning. It supplies enough background text to exercise evidence handling safely.",
        1000, "Wikipedia (en)", (id + 100).toString())
    private class Retriever(private val sources: List<BackgroundSource>) : BackgroundRetriever {
        var calls = 0; var term = ""; var localContext = ""
        override suspend fun retrieve(term: String, context: String): BackgroundRetrieval {
            calls++; this.term = term; localContext = context
            return BackgroundRetrieval(sources, if (sources.isEmpty()) "no_results" else "retrieved", "Synthetic retrieval")
        }
    }
    private fun response(ids: String = "S1") = """{"title":"所选概念","text":"所选资料提供这一概念的背景。[${ids}]","cited_source_ids":["$ids"],"uncertainty":"没有据此推断当前事件。","score":null}"""
    private fun service(server: MockWebServer, retriever: BackgroundRetriever, key: () -> String? = { "synthetic-key" }) =
        DeepSeekReadingAi(key, OkHttpClient(), server.url("/chat/completions").toString(), clock = { 1001 }, backgroundRetriever = retriever)

    @Test fun lookupDoesNotReadAKeyOrGenerateEvenWhenAiPermissionIsFalse() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val retriever = Retriever(listOf(source(1, "Bank (geography)")))
            val ai = service(server, retriever) { throw AssertionError("Lookup must not read a Key") }
            val result = ai.lookup(query.copy(term = "  river   bank  ", aiAllowed = false))
            assertEquals("river bank", result.query.term); assertFalse(result.query.aiAllowed)
            assertEquals(result.query.term, retriever.term); assertEquals(query.context, retriever.localContext)
            assertEquals(1, retriever.calls); assertEquals(0, server.requestCount)
            BackgroundResearch.validateLookup(result)
        }
    }

    @Test fun alternativeMeaningsRemainIndependentAndSelectedEvidenceIsRenumbered() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val sources = listOf(source(1, "Bank (finance)"), source(2, "Bank (geography)"))
            val lookup = service(server, Retriever(sources)).lookup(query)
            assertEquals(2, lookup.candidates.size)
            assertEquals(listOf(listOf("S1"), listOf("S2")), lookup.candidates.map { it.sourceIds })
            assertEquals(listOf(sources[1].copy(id = "S1")), BackgroundResearch.selectedSources(lookup, lookup.candidates[1].id))
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun explainUsesOnlyFrozenSelectedSourcesAndIncludesProviderAttribution() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ReadingAiFixtures.response(response())); server.start()
            val retriever = Retriever(emptyList())
            val selected = listOf(source(2, "Bank (geography)").copy(id = "S1"))
            val result = service(server, retriever).explain(query, selected)
            assertEquals(selected, result.sources); assertEquals(0, retriever.calls); assertEquals(1, server.requestCount)
            val request = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val payload = Json.parseToJsonElement(request.getValue("messages").jsonArray[1].jsonObject.getValue("content").jsonPrimitive.content).jsonObject
            val source = payload.getValue("retrieved_evidence").jsonArray.single().jsonObject
            assertEquals(selected.single().excerpt, source.getValue("excerpt").jsonPrimitive.content)
            assertEquals(selected.single().provider, source.getValue("provider").jsonPrimitive.content)
            assertEquals(selected.single().license, source.getValue("license").jsonPrimitive.content)
            assertFalse(payload.toString().contains("Bank (finance)")); assertFalse(payload.toString().contains(selected.single().url))
            assertEquals(query.context, payload.getValue("reading_context").jsonPrimitive.content)
        }
    }

    @Test fun explanationRepairKeepsOneFrozenPayloadAndDoesNotLookUpAgain() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ReadingAiFixtures.response(response("S9"))); server.enqueue(ReadingAiFixtures.response(response())); server.start()
            val retriever = Retriever(emptyList()); val selected = listOf(source(1, "Bank (geography)"))
            assertEquals(selected, service(server, retriever).explain(query, selected).sources)
            assertEquals(0, retriever.calls); assertEquals(2, server.requestCount)
            val requests = (1..2).map { Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject }
            assertEquals(requests[0].getValue("messages").jsonArray[1], requests[1].getValue("messages").jsonArray[1])
        }
    }

    @Test fun authenticationFailureDoesNotRetryOrReplaceFrozenSources() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401)); server.start()
            val retriever = Retriever(emptyList()); val selected = listOf(source(1, "Bank (geography)"))
            val before = selected.toList()
            try { service(server, retriever).explain(query, selected); fail("Expected authentication") }
            catch (failure: ApiFailure) { assertEquals("authentication", failure.code) }
            assertEquals(before, selected); assertEquals(1, server.requestCount); assertEquals(0, retriever.calls)
        }
    }

    @Test fun sourcePermissionAndFrozenEvidenceValidationPrecedeModelTransport() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val retriever = Retriever(emptyList()); val ai = service(server, retriever)
            try { ai.explain(query.copy(aiAllowed = false), listOf(source(1, "Bank"))); fail("Expected restriction") }
            catch (failure: ApiFailure) { assertEquals("source_restricted", failure.code) }
            try { ai.explain(query, listOf(source(1, "Bank").copy(url = "https://invented.invalid/"))); fail("Expected invalid evidence") }
            catch (_: IllegalArgumentException) { }
            assertEquals(0, server.requestCount); assertEquals(0, retriever.calls)
        }
    }

    @Test fun officialEvidenceRetainsItsClientProvenanceWithoutWikipediaMislabeling() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ReadingAiFixtures.response(response())); server.start()
            val entry = OfficialBackgroundCatalog.entries.first()
            val excerpt = "The ${entry.expectedPhrase} is discussed in this self-authored transport fixture. This is not a quotation from the official website and does not claim current institutional facts."
            val selected = listOf(BackgroundSource("S1", entry.heading, entry.url, excerpt, 1000,
                entry.provider, license = OfficialBackgroundCatalog.LICENSE, contentHash = sha256(excerpt)))
            val result = service(server, Retriever(emptyList())).explain(query.copy(term = "electoral college"), selected)
            assertEquals(selected, result.sources); assertFalse(result.evidence.contains("Wikipedia"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun actualSubmissionRechecksAuthorizationAfterWaitingForTheSharedModelGate() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ReadingAiFixtures.response("""{"title":"翻译","text":"合成译文","score":null}""")
                .setBodyDelay(600, TimeUnit.MILLISECONDS))
            server.start()
            val retriever = Retriever(emptyList()); val ai = service(server, retriever)
            val allowed = AtomicBoolean(true); val checks = AtomicInteger()
            val occupied = async(Dispatchers.IO) { ai.assist(StudyRequest("translation", "A synthetic sentence.")) }
            withContext(Dispatchers.IO) { checkNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            val pending = async(Dispatchers.IO) { runCatching {
                ai.explain(query, listOf(source(1, "Bank"))) {
                    checks.incrementAndGet()
                    if (!allowed.get()) throw ApiFailure("source_restricted", "Synthetic revocation while queued")
                }
            } }
            allowed.set(false)
            occupied.await()
            assertEquals("source_restricted", (pending.await().exceptionOrNull() as ApiFailure).code)
            assertEquals(1, checks.get()); assertEquals(1, server.requestCount); assertEquals(0, retriever.calls)
        }
    }

    @Test fun authorizationIsCheckedAgainBeforeAJsonRepairRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ReadingAiFixtures.response(response("S9"))); server.start()
            val retriever = Retriever(emptyList()); var checks = 0
            try {
                service(server, retriever).explain(query, listOf(source(1, "Bank"))) {
                    checks++
                    if (checks == 2) throw ApiFailure("paused", "Synthetic pause before repair")
                }
                fail("Expected the repair to be blocked")
            } catch (failure: ApiFailure) { assertEquals("paused", failure.code) }
            assertEquals(2, checks); assertEquals(1, server.requestCount); assertEquals(0, retriever.calls)
        }
    }
}
