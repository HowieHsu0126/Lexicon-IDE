package dev.lexicon.domain

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Synthetic evidence only: provider URL and attribution checks are exercised by data tests. */
class BackgroundResearchTest {
    private val now = 1_000_000_000L
    private val query = BackgroundQuery("city council", "The city council reviews local services.", "article", "a".repeat(64),
        SourceRef("A local decision", "Synthetic publisher", "https://example.org/article", 1), false)
    private fun source(id: String = "S1", retrievedAt: Long = now, host: String = "example.org") = BackgroundSource(
        id, "Synthetic source $id", "https://$host/background/$id", "A synthetic introduction describes local institutions and the decisions they make about public services.",
        retrievedAt, "Synthetic provider", "revision-$id", "Synthetic test material", "b".repeat(64),
    )
    private fun candidate(id: String = "concept-1", ids: List<String> = listOf("S1")) =
        BackgroundCandidate(id, "Local council", "An institution responsible for local decisions.", ids)
    private fun lookup(
        query: BackgroundQuery = this.query,
        sources: List<BackgroundSource> = listOf(source()),
        candidates: List<BackgroundCandidate> = listOf(candidate()),
        outcomes: List<BackgroundProviderOutcome> = listOf(BackgroundProviderOutcome("Synthetic provider", "retrieved")),
        status: String = "retrieved",
        createdAt: Long = now,
    ) = BackgroundResearch.createLookup(query, candidates, sources, outcomes, status, createdAt)
    private fun resign(value: BackgroundLookup) = value.copy(id = BackgroundResearch.lookupId(value.query,
        value.candidates, value.sources, value.providerOutcomes, value.status, value.createdAt))

    @Test fun normalizationOnlyChangesWhitespaceAndPreservesPolicyAndSource() {
        val raw = query.copy(term = " \tCity\n\r  Council\u00a0", context = "\r\n  A  line.\r\nAnother line.  \n")
        val normalized = BackgroundResearch.normalize(raw)
        assertEquals("City Council", normalized.term)
        assertEquals("A  line.\r\nAnother line.", normalized.context)
        assertFalse(normalized.aiAllowed); assertEquals(raw.source, normalized.source)
        assertEquals(raw.articleId, normalized.articleId); assertEquals(raw.bodyHash, normalized.bodyHash)
        assertEquals(normalized, BackgroundResearch.normalize(normalized))
        assertNotEquals(BackgroundResearch.queryKey(normalized), BackgroundResearch.queryKey(normalized.copy(term = "city council")))
    }

    @Test fun completeNormalizedQueryDeterminesCacheIdentity() {
        assertEquals(BackgroundResearch.queryKey(query), BackgroundResearch.queryKey(query.copy(term = " city  council ", context = " ${query.context} ")))
        val original = BackgroundResearch.queryKey(query)
        val variants = listOf(query.copy(term = "council"), query.copy(context = "A different sentence."),
            query.copy(articleId = "another"), query.copy(bodyHash = "c".repeat(64)), query.copy(aiAllowed = true),
            query.copy(source = query.source.copy(title = "Changed title")), query.copy(source = query.source.copy(source = "Other publisher")),
            query.copy(source = query.source.copy(url = "https://example.org/other")), query.copy(source = query.source.copy(publishedAt = 2)))
        assertTrue(original.matches(Regex("[a-f0-9]{64}")))
        variants.forEach { assertNotEquals(original, BackgroundResearch.queryKey(it)) }
    }

    @Test fun oversizedAndMalformedQueriesAreRejectedWithoutTruncatingMaterial() {
        val invalid = listOf(query.copy(term = " "), query.copy(term = "x".repeat(161)), query.copy(context = "x".repeat(1201)),
            query.copy(term = "bad\u0000term"), query.copy(context = "bad\u0000context"), query.copy(context = "\uD83D"),
            query.copy(articleId = ""), query.copy(articleId = "x".repeat(201)), query.copy(bodyHash = "unknown"),
            query.copy(source = SourceRef(title = "x".repeat(1001))), query.copy(source = SourceRef(source = "x".repeat(501))),
            query.copy(source = SourceRef(url = "x".repeat(4001))), query.copy(source = SourceRef(publishedAt = -1)))
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { BackgroundResearch.normalize(it) } }
        assertEquals(160, BackgroundResearch.normalize(query.copy(term = "x".repeat(160))).term.length)
        assertEquals(1200, BackgroundResearch.normalize(query.copy(context = "x".repeat(1200))).context.length)
        assertEquals(BackgroundQuery("Magna Carta"), BackgroundResearch.normalize(BackgroundQuery("Magna Carta")))
    }

    @Test fun snapshotIdentityBindsEveryEvidenceAndSelectionField() {
        val original = lookup()
        val variants = listOf(
            original.copy(query = query.copy(context = "A different context.")),
            original.copy(candidates = listOf(candidate().copy(title = "Different concept"))),
            original.copy(candidates = listOf(candidate().copy(description = "Different meaning"))),
            original.copy(sources = listOf(source().copy(excerpt = "Changed synthetic evidence."))),
            original.copy(sources = listOf(source().copy(url = "https://another.example/evidence"))),
            original.copy(sources = listOf(source().copy(provider = "Another provider"))),
            original.copy(sources = listOf(source().copy(revisionId = "another-revision"))),
            original.copy(sources = listOf(source().copy(contentHash = "c".repeat(64)))),
            original.copy(sources = listOf(source().copy(retrievedAt = now - 1))),
            original.copy(providerOutcomes = listOf(BackgroundProviderOutcome("Synthetic provider", "retrieved", "Different detail"))),
            original.copy(createdAt = now + 1),
        )
        BackgroundResearch.validateLookup(original)
        variants.forEach {
            assertThrows(IllegalArgumentException::class.java) { BackgroundResearch.validateLookup(it) }
            assertNotEquals(original.id, resign(it).id)
        }
    }

    @Test fun serializationRoundTripPreservesFrozenSnapshotIdentity() {
        val value = lookup()
        val codec = Json { encodeDefaults = true }
        val restored = codec.decodeFromString<BackgroundLookup>(codec.encodeToString(value))
        assertEquals(value, restored)
        BackgroundResearch.validateLookup(restored)
        assertEquals(value.id, BackgroundResearch.lookupId(value.query, value.candidates, value.sources, value.providerOutcomes, value.status, value.createdAt))
    }

    @Test fun sourceProviderAndUrlPolicyIsNotHardcodedToWikipediaInDomain() {
        val official = source(host = "institution.example").copy(provider = "Example institution", license = "Example stated terms")
        val value = lookup(sources = listOf(official), outcomes = listOf(BackgroundProviderOutcome("Example institution", "retrieved")))
        BackgroundResearch.validateLookup(value)
        assertEquals(official, value.sources.single())
    }

    @Test fun candidatesReferenceExistingSourcesAndCoverTheExactRetrievedSet() {
        val sources = listOf(source(), source("S2"))
        val grouped = lookup(sources = sources, candidates = listOf(candidate(ids = listOf("S1", "S2"))))
        BackgroundResearch.validateLookup(grouped)
        val separate = lookup(sources = sources, candidates = listOf(candidate(), candidate("concept-2", listOf("S2"))))
        BackgroundResearch.validateLookup(separate)
        val variants = listOf(
            grouped.copy(candidates = listOf(candidate())),
            grouped.copy(candidates = listOf(candidate(ids = listOf("S1", "missing")))),
            grouped.copy(candidates = listOf(candidate(ids = listOf("S1", "S1", "S2")))),
            grouped.copy(candidates = listOf(candidate(), candidate(ids = listOf("S2")))),
            grouped.copy(candidates = listOf(candidate(ids = emptyList()))),
            grouped.copy(candidates = listOf(candidate(id = "invalid id", ids = listOf("S1", "S2")))),
        )
        variants.forEach { assertThrows(IllegalArgumentException::class.java) { BackgroundResearch.validateLookup(resign(it)) } }
    }

    @Test fun selectedConceptUsesOnlyItsSourcesAndRenumbersWithoutMutatingTheSnapshot() {
        val sources = listOf(source("first"), source("second"), source("third"))
        val value = lookup(sources = sources, candidates = listOf(candidate("meaning-a", listOf("third", "first")), candidate("meaning-b", listOf("second"))))
        val selection = BackgroundResearch.selectedSources(value, "meaning-a")
        assertEquals(listOf("S1", "S2"), selection.map { it.id })
        assertEquals(listOf(sources[2].copy(id = "S1"), sources[0].copy(id = "S2")), selection)
        assertEquals(sources, value.sources)
        assertEquals(listOf(sources[1].copy(id = "S1")), BackgroundResearch.selectedSources(value, "meaning-b"))
        assertThrows(IllegalArgumentException::class.java) { BackgroundResearch.selectedSources(value, "not-selected") }
    }

    @Test fun sourceShapeDuplicateIdsAndDuplicateUrlsAreRejectedEvenWithARecomputedDigest() {
        val original = lookup()
        val variants = listOf(
            original.copy(sources = listOf(source().copy(id = "bad source"))),
            original.copy(sources = listOf(source().copy(title = ""))),
            original.copy(sources = listOf(source().copy(contentHash = "invalid"))),
            original.copy(sources = listOf(source().copy(excerpt = "x".repeat(1602)))),
            original.copy(sources = listOf(source().copy(retrievedAt = -1))),
            original.copy(sources = listOf(source(), source())),
            original.copy(sources = listOf(source(), source("S2").copy(url = source().url)), candidates = listOf(candidate(ids = listOf("S1", "S2")))),
        )
        variants.forEach { assertThrows(IllegalArgumentException::class.java) { BackgroundResearch.validateLookup(resign(it)) } }
        assertThrows(IllegalArgumentException::class.java) { lookup(sources = (1..4).map { source("S$it") }, candidates = listOf(candidate(ids = (1..4).map { "S$it" }))) }
    }

    @Test fun emptyOutcomesAreDistinctFromRetrievedEvidenceAndNeedNoFabricatedSources() {
        for (status in listOf("no_results", "unavailable")) {
            val empty = lookup(sources = emptyList(), candidates = emptyList(), outcomes = listOf(BackgroundProviderOutcome("Synthetic provider", status)), status = status)
            BackgroundResearch.validateLookup(empty)
            assertTrue(empty.sources.isEmpty()); assertTrue(empty.candidates.isEmpty())
            assertThrows(IllegalArgumentException::class.java) { BackgroundResearch.selectedSources(empty, "concept-1") }
        }
        assertThrows(IllegalArgumentException::class.java) { lookup(sources = emptyList(), candidates = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { lookup(status = "no_results") }
        assertThrows(IllegalArgumentException::class.java) { lookup(status = "unknown") }
    }

    @Test fun providerFailuresRemainVisibleAlongsideSuccessfulEvidence() {
        val outcomes = listOf(BackgroundProviderOutcome("Synthetic provider", "retrieved"),
            BackgroundProviderOutcome("Other provider", "unavailable", "Service unavailable"),
            BackgroundProviderOutcome("Unused provider", "skipped", "No matching concept"))
        val value = lookup(outcomes = outcomes)
        assertEquals(outcomes, value.providerOutcomes); BackgroundResearch.validateLookup(value)
        assertThrows(IllegalArgumentException::class.java) { lookup(outcomes = outcomes + outcomes.first()) }
        assertThrows(IllegalArgumentException::class.java) { lookup(outcomes = listOf(outcomes.first().copy(status = "unknown"))) }
    }

    @Test fun versionIdentityAndCanonicalStoredQueryAreMandatory() {
        val value = lookup()
        assertThrows(IllegalArgumentException::class.java) { BackgroundResearch.validateLookup(value.copy(version = 2)) }
        assertThrows(IllegalArgumentException::class.java) { BackgroundResearch.validateLookup(value.copy(id = "0".repeat(64))) }
        assertThrows(IllegalArgumentException::class.java) { BackgroundResearch.validateLookup(value.copy(query = query.copy(term = " city council "))) }
        assertThrows(IllegalArgumentException::class.java) { lookup(createdAt = -1) }
    }

    @Test fun snapshotCreationCopiesCallerOwnedLists() {
        val refs = mutableListOf("S1")
        val candidates = mutableListOf(candidate(ids = refs))
        val sources = mutableListOf(source())
        val outcomes = mutableListOf(BackgroundProviderOutcome("Synthetic provider", "retrieved"))
        val value = lookup(sources = sources, candidates = candidates, outcomes = outcomes)
        refs.clear(); candidates.clear(); sources.clear(); outcomes.clear()
        assertEquals(listOf("S1"), value.candidates.single().sourceIds)
        assertEquals(1, value.sources.size); assertEquals(1, value.providerOutcomes.size)
        BackgroundResearch.validateLookup(value)
    }

    @Test fun oldestSourceControlsSevenDayExpiryWithoutChangingSnapshotAge() {
        val day = 24L * 60 * 60 * 1000
        val sources = listOf(source(retrievedAt = now - 6 * day), source("S2"))
        val value = lookup(sources = sources, candidates = listOf(candidate(ids = listOf("S1", "S2"))))
        assertFalse(BackgroundResearch.expired(value, now))
        assertFalse(BackgroundResearch.expired(value, now + day - 1))
        assertTrue(BackgroundResearch.expired(value, now + day))
        assertEquals(sources, value.sources); assertEquals(now, value.createdAt)
    }

    @Test fun futureEvidenceAndEmptyLookupAgeAreHandledConservatively() {
        assertTrue(BackgroundResearch.expired(lookup(), now - 1))
        assertTrue(BackgroundResearch.expired(lookup(sources = listOf(source(retrievedAt = now + 1))), now))
        val empty = lookup(sources = emptyList(), candidates = emptyList(), outcomes = emptyList(), status = "no_results")
        assertFalse(BackgroundResearch.expired(empty, now))
        assertTrue(BackgroundResearch.expired(empty, now + BackgroundResearch.CACHE_TTL_MILLIS))
        assertTrue(BackgroundResearch.expired(lookup(), Long.MAX_VALUE))
    }

    @Test fun contextAroundALateRepeatedTermKeepsTheSelectedOccurrence() {
        val text = "A council in an earlier context. " + "Earlier material. ".repeat(100) +
            "The council in this river town discusses flood planning. " + "Later material. ".repeat(100)
        val start = text.indexOf("council in this river")
        assertTrue(start > 1200)
        val context = BackgroundResearch.contextAround(text, start, start + "council".length)
        assertTrue(context.length <= 1200)
        assertTrue(context.contains("council in this river town"))
        assertFalse(context.contains("council in an earlier context"))
        assertTrue(text.contains(context))
        assertEquals(context, BackgroundResearch.contextAround(text, start + "council".length, start))
    }

    @Test fun contextKeepsCrossParagraphSelectionAndOriginalWhitespace() {
        val text = "  A first paragraph.\r\n\r\nA second paragraph. 🧭  "
        val from = text.indexOf("first")
        val to = text.indexOf("paragraph. 🧭") + "paragraph. 🧭".length
        assertEquals(text, BackgroundResearch.contextAround(text, from, to))
        val exact = BackgroundResearch.contextAround(text, from, to, to - from)
        assertEquals(text.substring(from, to), exact)
        assertTrue(exact.contains("\r\n\r\n"))
    }

    @Test fun contextWindowEdgesNeverSplitASurrogatePair() {
        val text = "ab😀cdef😀gh"
        val selected = text.indexOf("cd")
        val window = BackgroundResearch.contextAround(text, selected, selected + 2, 5)
        assertEquals("cdef", window)
        assertTrue(window.length <= 5)
        for (budget in 2..text.length) {
            val context = BackgroundResearch.contextAround(text, selected, selected + 2, budget)
            assertTrue(context.contains("cd")); assertTrue(context.length <= budget)
            assertFalse(context.first().isLowSurrogate()); assertFalse(context.last().isHighSurrogate())
        }
    }

    @Test fun invalidAndOversizedSelectionsFailInsteadOfDroppingTheTarget() {
        val text = "A 🧭 council in context."
        val emoji = text.indexOf("🧭")
        val invalid = listOf(-1 to 2, 0 to text.length + 1, 4 to 4, emoji + 1 to emoji + 2, emoji to emoji + 1)
        invalid.forEach { (from, to) -> assertThrows(IllegalArgumentException::class.java) { BackgroundResearch.contextAround(text, from, to) } }
        assertThrows(IllegalArgumentException::class.java) { BackgroundResearch.contextAround(text, 0, 4, 3) }
        assertThrows(IllegalArgumentException::class.java) { BackgroundResearch.contextAround(text, 0, 1, 0) }
        assertThrows(IllegalArgumentException::class.java) { BackgroundResearch.contextAround("   ", 0, 2) }
        assertThrows(IllegalArgumentException::class.java) { BackgroundResearch.contextAround("x\uD83Dy", 0, 1) }
    }

    @Test fun snapshotOrderAndSourceBytesRemainPartOfIdentity() {
        val sources = listOf(source(), source("S2"))
        val candidates = listOf(candidate(), candidate("concept-2", listOf("S2")))
        val first = lookup(sources = sources, candidates = candidates)
        assertNotEquals(first.id, lookup(sources = sources.reversed(), candidates = candidates).id)
        assertNotEquals(first.id, lookup(sources = sources, candidates = candidates.reversed()).id)
        assertEquals(hash(Json { encodeDefaults = true }.encodeToString(BackgroundResearch.normalize(query))), BackgroundResearch.queryKey(query))
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
