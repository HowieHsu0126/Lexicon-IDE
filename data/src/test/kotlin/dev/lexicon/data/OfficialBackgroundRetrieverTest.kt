package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class OfficialBackgroundRetrieverTest {
    private val entry = OfficialBackgroundCatalog.entries.first()
    private val evidence = "The Electoral College is described in this self-authored protocol fixture. It is an institutional process, and this paragraph is not a quotation from the real source."
    private fun html(text: String = evidence) = "<html><nav><p>UNRELATED_NAV</p></nav><main id='block-system-main'><h1>${entry.heading}</h1><p>$text<script>UNTRUSTED_SCRIPT</script></p><h2>Other section</h2><p>UNRELATED_SECTION</p></main></html>"
    private fun wikipedia(id: String = "S1", title: String = "River bank") = BackgroundSource(id, title,
        "https://en.wikipedia.org/w/index.php?curid=10&oldid=100", "This synthetic encyclopedia evidence describes the river bank and its geography. It is only a protocol fixture, not a live Wikipedia quotation.",
        10, "Wikipedia (en)", "100")

    @Test fun officialLookupRequiresAnExactRegisteredConceptNotAContainedKeyword() {
        assertEquals(entry, OfficialBackgroundCatalog.match("  ELECTORAL   COLLEGE  "))
        assertNull(OfficialBackgroundCatalog.match("abolish electoral college"))
        assertNull(OfficialBackgroundCatalog.match("the Fed"))
        assertEquals("fomc", OfficialBackgroundCatalog.match("FOMC")!!.id)
    }
    @Test fun extractionUsesTheVerifiedHeadingAndCompleteBoundedParagraphs() {
        val source = OfficialBackgroundRetriever().parse(entry, html(), 123)
        assertEquals(evidence, source.excerpt); assertEquals(entry.url, source.url)
        assertEquals(sha256(evidence), source.contentHash); assertEquals(123, source.retrievedAt)
        assertNull(source.revisionId); assertFalse(source.excerpt.contains("UNRELATED"))
        BackgroundEvidence.validateSources(listOf(source))
    }
    @Test fun officialChangedLayoutDoesNotFallBackToNavigationOrMetadata() {
        val parser = OfficialBackgroundRetriever()
        for (invalid in listOf(html().replace(entry.heading, "Unknown layout"), html().replace("block-system-main", "other"), html("x".repeat(1700)))) {
            assertThrows(Exception::class.java) { parser.parse(entry, invalid, 123) }
        }
    }
    @Test fun officialProvenanceCannotBeRelabeledOrRedirectedBySavedContent() {
        val source = OfficialBackgroundRetriever().parse(entry, html(), 123)
        val changed = listOf(source.copy(url = entry.url + "?redirect=other"), source.copy(url = "https://www.archives.gov.evil.test/electoral-college/about"),
            source.copy(provider = "Wikipedia (en)"), source.copy(contentHash = "a".repeat(64)), source.copy(license = "CC BY-SA 4.0"), source.copy(revisionId = "invented"))
        changed.forEach { value -> assertThrows(IllegalArgumentException::class.java) { BackgroundEvidence.validateSources(listOf(value)) } }
    }
    @Test fun officialTransportDoesNotInheritAuthenticationContextCookiesOrInterceptors() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(html())); server.start()
            val unsafe = OkHttpClient.Builder().addInterceptor { throw AssertionError("Learning interceptor must not run") }.build()
            val result = OfficialBackgroundRetriever({ 123 }, unsafe, server.url("/")).retrieve("electoral college", "PRIVATE_READING_CONTEXT")
            assertEquals("retrieved", result.status); assertEquals(entry.url, result.sources.single().url)
            val request = server.takeRequest(); assertEquals("/electoral-college/about", request.path)
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
            assertFalse(request.headers.toString().contains("PRIVATE_READING_CONTEXT")); assertEquals(0L, request.bodySize)
        }
    }
    @Test fun unknownConceptMakesNoHttpRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val retriever = OfficialBackgroundRetriever(testBase = server.url("/"))
            assertEquals("no_results", retriever.retrieve("unknown concept", "").status); assertEquals(0, server.requestCount)
        }
    }
    @Test fun redirectAndUnexpectedMimeTypeCannotBecomeEvidence() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(html())); server.start()
            val retriever = OfficialBackgroundRetriever(testBase = server.url("/"))
            repeat(2) { assertTrue(retriever.retrieve("electoral college", "").sources.isEmpty()) }
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun rateLimitHonorsProviderCooldownWithoutStartingAnotherRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(html())); server.start()
            var now=1000L;val retriever=OfficialBackgroundRetriever({now},testBase=server.url("/"))
            assertEquals("unavailable",retriever.retrieve("electoral college","").status)
            now+=119_000;assertEquals("unavailable",retriever.retrieve("electoral college","").status);assertEquals(1,server.requestCount)
            now+=1000;assertEquals("retrieved",retriever.retrieve("electoral college","").status);assertEquals(2,server.requestCount)
        }
    }
    @Test fun unavailableOfficialProviderDoesNotDiscardEncyclopediaEvidence() = runBlocking {
        val official=BackgroundRetriever { _,_->throw java.io.IOException("PRIVATE_DIAGNOSTIC") }
        val wiki=BackgroundRetriever { _,_->BackgroundRetrieval(listOf(wikipedia()),"retrieved","Found") }
        val result=CompositeBackgroundRetriever(official,wiki).retrieve("bank","river context")
        assertEquals("retrieved",result.status);assertEquals("River bank",result.candidates.single().title)
        assertTrue(result.providerOutcomes.any { it.status=="unavailable" });assertFalse(result.toString().contains("PRIVATE_DIAGNOSTIC"))
    }
    @Test fun conceptCandidatesRemainSeparateAndOnlyChosenEvidenceIsExplained() = runBlocking {
        val officialSource=OfficialBackgroundRetriever().parse(entry,html(),10)
        val official=BackgroundRetriever { _,_->BackgroundRetrieval(listOf(officialSource),"retrieved","Found") }
        val wiki=BackgroundRetriever { _,_->BackgroundRetrieval(listOf(wikipedia()),"retrieved","Found") }
        val result=CompositeBackgroundRetriever(official,wiki).retrieve("bank","river context")
        assertEquals(2,result.candidates.size);assertEquals(listOf("S1","S2"),result.sources.map {it.id})
        val lookup=BackgroundResearch.createLookup(BackgroundQuery("bank","river context"),result.candidates,result.sources,result.providerOutcomes,result.status,10)
        val chosen=BackgroundResearch.selectedSources(lookup,result.candidates[1].id)
        assertEquals(listOf(wikipedia()),chosen);assertEquals("River bank",chosen.single().title)
    }
    @Test fun independentProviderRequestsMayRunConcurrently() = runBlocking {
        val entered=CompletableDeferred<Unit>();val resume=CompletableDeferred<Unit>()
        val slow=BackgroundRetriever { _,_->entered.complete(Unit);resume.await();BackgroundRetrieval(emptyList(),"no_results", "") }
        val other=BackgroundRetriever { _,_->entered.await();resume.complete(Unit);BackgroundRetrieval(listOf(wikipedia()),"retrieved","Found") }
        assertEquals("retrieved",withTimeout(2000){CompositeBackgroundRetriever(slow,other).retrieve("bank","")}.status)
    }
}
