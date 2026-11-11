package dev.lexicon.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in public HTTP only. No Key, DeepSeek request or invented fallback evidence. */
class OfficialBackgroundLiveTest {
    @Test fun actualElectoralCollegePageReturnsBoundedAttributedEvidence() = verify("electoral college")
    @Test fun actualFomcPageReturnsBoundedAttributedEvidence() = verify("FOMC")

    private fun verify(term: String) = runBlocking {
        assumeTrue(System.getenv("LEXICON_OFFICIAL_LIVE") == "1")
        val retriever=OfficialBackgroundRetriever()
            val result=retriever.retrieve(term, "context must remain on device")
            println("OFFICIAL_LOOKUP term=$term status=${result.status} detail=${result.detail}")
            assertEquals(term, "retrieved",result.status)
            val source=result.sources.single();BackgroundEvidence.validateSources(listOf(source))
            assertTrue(source.excerpt.length in 80..1600);assertNotNull(source.contentHash)
            println("OFFICIAL_SOURCE provider=${source.provider} status=${result.status} excerptChars=${source.excerpt.length} contentHash=${source.contentHash}")
    }
}
