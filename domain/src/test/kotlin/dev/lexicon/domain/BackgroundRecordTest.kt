package dev.lexicon.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Synthetic evidence exercises offline record semantics; data owns provider and cache identity checks. */
class BackgroundRecordTest {
    private val now = 1_000_000_000L
    private val codec = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private fun source(id: String, title: String) = BackgroundSource(id, title, "https://example.org/$id",
        "Synthetic evidence about $title.", now, "Synthetic provider", license = "Synthetic test material")
    private fun lookup(createdAt: Long = now): BackgroundLookup = BackgroundResearch.createLookup(
        BackgroundQuery("council", "The council discusses local services.", "article", "a".repeat(64),
            SourceRef("A local decision", "Synthetic publisher", "https://example.org/article", 1), false),
        listOf(BackgroundCandidate("meaning-1", "Local council", "A local institution.", listOf("original-a")),
            BackgroundCandidate("meaning-2", "Other council", "A different institution.", listOf("original-b"))),
        listOf(source("original-a", "Local council"), source("original-b", "Other council")),
        listOf(BackgroundProviderOutcome("Synthetic provider", "retrieved")), "retrieved", createdAt,
    )
    private fun explanation(lookup: BackgroundLookup = lookup(), candidate: String = "meaning-1") = StudyAssist(
        "background", "Local decisions", "The selected institution makes local decisions [S1].", "synthetic-model", now,
        "Only the selected synthetic source supports this explanation.", sources = BackgroundResearch.selectedSources(lookup, candidate),
        retrievalStatus = "retrieved", citedSourceIds = listOf("S1"),
    )
    private fun record() = BackgroundRecords.create(lookup(), "meaning-1", now + 100)
    private fun invalid(action: () -> Unit) = assertThrows(IllegalArgumentException::class.java) { action() }

    @Test fun identityIsStableForOneFrozenLookupAndMeaning() {
        val first = record()
        val later = BackgroundRecords.create(first.lookup, first.selectedCandidateId, now + 10_000)
        assertEquals(first.id, later.id)
        assertTrue(first.id.matches(Regex("[a-f0-9]{64}:[a-f0-9]{64}")))
        assertEquals(first.id, BackgroundRecords.id(first.lookup.id, "meaning-1"))
        assertEquals(1, first.revision)
        assertEquals(1, first.version)
    }

    @Test fun differentMeaningsAndRefreshedLookupsDoNotOverwriteOldIdentity() {
        val original = record()
        assertNotEquals(original.id, BackgroundRecords.create(original.lookup, "meaning-2", now + 100).id)
        assertNotEquals(original.id, BackgroundRecords.create(lookup(now + 1), "meaning-1", now + 100).id)
        invalid { BackgroundRecords.id("not-a-lookup", "meaning-1") }
        invalid { BackgroundRecords.id(original.lookup.id, "not a candidate") }
    }

    @Test fun savingEvidenceDoesNotRequireOrInventAnExplanation() {
        val saved = record()
        assertNull(saved.explanation)
        assertFalse(saved.lookup.query.aiAllowed)
        assertEquals("", saved.comment)
        assertTrue(saved.tags.isEmpty())
        assertEquals(2, saved.lookup.candidates.size)
        assertEquals(2, saved.lookup.sources.size)
        assertEquals("meaning-1", saved.selectedCandidateId)
    }

    @Test fun selectedEvidenceRetainsOriginalReceiptAndHasLocalCitationNumbers() {
        val lookup = lookup()
        val saved = BackgroundRecords.create(lookup, "meaning-2", now, explanation(lookup, "meaning-2"))
        val savedExplanation = requireNotNull(saved.explanation)
        assertEquals(listOf("original-a", "original-b"), saved.lookup.sources.map { it.id })
        assertEquals(listOf("S1"), savedExplanation.sources.map { it.id })
        assertEquals("Other council", savedExplanation.sources.single().title)
        assertEquals(BackgroundResearch.selectedSources(saved.lookup, saved.selectedCandidateId), savedExplanation.sources)
    }

    @Test fun offlineSaveRemainsValidAfterSevenDayEvidenceExpiry() {
        val lookup = lookup()
        val savedAt = now + BackgroundResearch.CACHE_TTL_MILLIS + 1
        assertTrue(BackgroundResearch.expired(lookup, savedAt))
        val saved = BackgroundRecords.create(lookup, "meaning-1", savedAt, explanation(lookup))
        BackgroundRecords.validate(saved)
        assertEquals(now, saved.lookup.sources.first().retrievedAt)
        assertEquals(savedAt, saved.createdAt)
    }

    @Test fun missingMeaningEmptyResultsAndChangedReceiptsCannotBeSaved() {
        invalid { BackgroundRecords.create(lookup(), "absent", now) }
        invalid { BackgroundRecords.create(lookup().copy(query = BackgroundQuery("different")), "meaning-1", now) }
        val empty = BackgroundResearch.createLookup(BackgroundQuery("unknown"), emptyList(), emptyList(),
            listOf(BackgroundProviderOutcome("Synthetic provider", "no_results")), "no_results", now)
        invalid { BackgroundRecords.create(empty, "meaning-1", now) }
        invalid { BackgroundRecords.create(lookup(), "meaning-1", -1) }
    }

    @Test fun recordRoundTripIsSelfContainedWithoutLookupOrExplanationCache() {
        val saved = BackgroundRecords.create(lookup(), "meaning-1", now + 100, explanation())
        val decoded = codec.decodeFromString<BackgroundRecord>(codec.encodeToString(saved))
        assertEquals(saved, decoded)
        BackgroundRecords.validate(decoded)
        assertEquals("The council discusses local services.", decoded.lookup.query.context)
        val decodedExplanation = requireNotNull(decoded.explanation)
        assertEquals("https://example.org/original-a", decodedExplanation.sources.single().url)
        assertEquals("Synthetic test material", decodedExplanation.sources.single().license)
    }

    @Test fun oldReadingLibrariesDoNotCreateRecordsFromOtherFields() {
        val restored = codec.decodeFromString<ReadingLibrary>("""{"notes":[],"reviews":[],"unknown":true}""")
        assertTrue(restored.backgroundRecords.isEmpty())
        assertEquals(0, restored.backgroundRecordErrors)
        val library = ReadingLibrary(backgroundRecords = listOf(record()), backgroundRecordErrors = 2)
        assertEquals(library, codec.decodeFromString<ReadingLibrary>(codec.encodeToString(library)))
    }

    @Test fun recordCreationCopiesCallerOwnedEvidenceAndCitationLists() {
        val original = lookup()
        val ids = mutableListOf("original-a")
        val candidates = original.candidates.map { if (it.id == "meaning-1") it.copy(sourceIds = ids) else it }.toMutableList()
        val sources = original.sources.toMutableList()
        val outcomes = original.providerOutcomes.toMutableList()
        val mutableLookup = original.copy(candidates = candidates, sources = sources, providerOutcomes = outcomes)
        val explanationSources = BackgroundResearch.selectedSources(mutableLookup, "meaning-1").toMutableList()
        val citationIds = mutableListOf("S1")
        val generated = explanation(mutableLookup).copy(sources = explanationSources, citedSourceIds = citationIds)
        val saved = BackgroundRecords.create(mutableLookup, "meaning-1", now, generated)
        ids.clear(); candidates.clear(); sources.clear(); outcomes.clear(); explanationSources.clear(); citationIds.clear()
        BackgroundRecords.validate(saved)
        assertEquals(2, saved.lookup.candidates.size)
        assertEquals(listOf("S1"), saved.explanation!!.citedSourceIds)
    }

    @Test fun personalEditsLeaveContextEvidenceMeaningAndExplanationUntouched() {
        val saved = BackgroundRecords.create(lookup(), "meaning-1", now + 100, explanation())
        val tags = mutableListOf("politics", "institutions")
        val edited = BackgroundRecords.updateMetadata(saved, 1, "My own interpretation.", tags, now + 200)
        tags.clear()
        assertEquals(saved.lookup, edited.lookup)
        assertEquals(saved.explanation, edited.explanation)
        assertEquals(saved.selectedCandidateId, edited.selectedCandidateId)
        assertEquals(saved.id, edited.id)
        assertEquals(saved.createdAt, edited.createdAt)
        assertEquals(2, edited.revision)
        assertEquals(now + 200, edited.updatedAt)
        assertEquals(listOf("politics", "institutions"), edited.tags)
        assertEquals("", saved.comment)
    }

    @Test fun metadataLimitsRejectOversizedAndMalformedInputWithoutTruncation() {
        val saved = record()
        invalid { BackgroundRecords.updateMetadata(saved, 1, "x".repeat(12_001), emptyList(), now) }
        invalid { BackgroundRecords.updateMetadata(saved, 1, "", List(21) { "tag-$it" }, now) }
        invalid { BackgroundRecords.updateMetadata(saved, 1, "", listOf("x".repeat(101)), now) }
        invalid { BackgroundRecords.updateMetadata(saved, 1, "", listOf(" "), now) }
        invalid { BackgroundRecords.updateMetadata(saved, 1, "bad\u0000comment", emptyList(), now) }
        invalid { BackgroundRecords.updateMetadata(saved, 1, "bad\uD83D", emptyList(), now) }
        invalid { BackgroundRecords.updateMetadata(saved, 1, "", listOf("line\nbreak"), now) }
        invalid { BackgroundRecords.updateMetadata(saved, 1, "", listOf("\uDE00"), now) }
        val boundary = BackgroundRecords.updateMetadata(saved, 1, "x".repeat(12_000), List(20) { "x".repeat(100) }, now)
        assertEquals(12_000, boundary.comment.length)
        assertEquals(20, boundary.tags.size)
    }

    @Test fun unicodeAndMultilineCommentsRemainExact() {
        val comment = "自己的理解 🧭\r\nA second line.\tA note."
        val saved = BackgroundRecords.updateMetadata(record(), 1, comment, listOf("制度 🧭"), now)
        assertEquals(comment, saved.comment)
        assertEquals(listOf("制度 🧭"), saved.tags)
    }

    @Test fun staleRevisionCannotEditOrAttachAnExplanationEvenForAnOtherwiseValidPayload() {
        val saved = record()
        val edited = BackgroundRecords.updateMetadata(saved, 1, "A note.", emptyList(), now)
        invalid { BackgroundRecords.updateMetadata(edited, 1, "Another note.", emptyList(), now) }
        invalid { BackgroundRecords.withExplanation(edited, 1, explanation(), now) }
        invalid { BackgroundRecords.updateMetadata(saved, 0, "", emptyList(), now) }
    }

    @Test fun repeatedUnchangedEditsDoNotCreateNewRevisionsOrTimes() {
        val saved = record()
        assertSame(saved, BackgroundRecords.updateMetadata(saved, 1, saved.comment, saved.tags, now + 200))
        val withExplanation = BackgroundRecords.withExplanation(saved, 1, explanation(), now + 200)
        assertSame(withExplanation, BackgroundRecords.withExplanation(withExplanation, 2, explanation(), now + 300))
        invalid { BackgroundRecords.updateMetadata(saved, 1, saved.comment, saved.tags, -1) }
    }

    @Test fun clockRollbackDoesNotMoveUpdateTimeBackwards() {
        val saved = record()
        val edited = BackgroundRecords.updateMetadata(saved, 1, "A note.", emptyList(), now - 100)
        assertEquals(saved.updatedAt, edited.updatedAt)
        assertEquals(2, edited.revision)
        val generated = BackgroundRecords.withExplanation(edited, 2, explanation(), now - 100)
        assertEquals(edited.updatedAt, generated.updatedAt)
        assertEquals(3, generated.revision)
    }

    @Test fun revisionOverflowCannotWrapAround() {
        val saved = record().copy(revision = Int.MAX_VALUE)
        invalid { BackgroundRecords.updateMetadata(saved, Int.MAX_VALUE, "Changed.", emptyList(), now) }
        invalid { BackgroundRecords.withExplanation(saved, Int.MAX_VALUE, explanation(), now) }
        assertSame(saved, BackgroundRecords.updateMetadata(saved, Int.MAX_VALUE, saved.comment, saved.tags, now))
    }

    @Test fun addingDisplayedExplanationPreservesPersonalMetadataAndSaveIdentity() {
        val saved = BackgroundRecords.updateMetadata(record(), 1, "My note.", listOf("culture"), now + 200)
        val added = BackgroundRecords.withExplanation(saved, 2, explanation(), now + 300)
        assertEquals(saved.comment, added.comment)
        assertEquals(saved.tags, added.tags)
        assertEquals(saved.lookup, added.lookup)
        assertEquals(saved.id, added.id)
        assertEquals(saved.createdAt, added.createdAt)
        assertEquals(3, added.revision)
        assertEquals(explanation(), added.explanation)
        assertNull(saved.explanation)
    }

    @Test fun explanationCannotSubstituteSourcesOrAnotherMeaning() {
        val saved = record()
        val generated = explanation()
        invalid { BackgroundRecords.withExplanation(saved, 1, explanation(candidate = "meaning-2"), now) }
        val changed = generated.copy(sources = generated.sources.map { it.copy(excerpt = "Invented replacement material.") })
        invalid { BackgroundRecords.withExplanation(saved, 1, changed, now) }
        invalid { BackgroundRecords.withExplanation(saved, 1, generated.copy(sources = emptyList()), now) }
    }

    @Test fun explanationSchemaRejectsInvalidKindsLengthsModelsAndScores() {
        val generated = explanation()
        val invalidResults = listOf(generated.copy(kind = "review"), generated.copy(retrievalStatus = "no_results"),
            generated.copy(score = 0.8), generated.copy(model = "retrieval-only"), generated.copy(model = " "),
            generated.copy(model = "x".repeat(201)), generated.copy(createdAt = -1), generated.copy(title = " "),
            generated.copy(title = "x".repeat(161)), generated.copy(text = " "), generated.copy(text = "x".repeat(1601)),
            generated.copy(evidence = " "), generated.copy(evidence = "x".repeat(2401)))
        invalidResults.forEach { result -> invalid { BackgroundRecords.validateExplanation(result, generated.sources) } }
        BackgroundRecords.validateExplanation(generated.copy(title = "x".repeat(160), model = "x".repeat(200),
            evidence = "x".repeat(2400), text = "x".repeat(1596) + "[S1]"), generated.sources)
    }

    @Test fun explanationCitationSetMustMatchTheTextAndSelectedEvidence() {
        val generated = explanation()
        val invalidResults = listOf(generated.copy(citedSourceIds = emptyList()), generated.copy(citedSourceIds = listOf("S1", "S1")),
            generated.copy(citedSourceIds = listOf("S2")), generated.copy(text = "No citation here."),
            generated.copy(text = "A citation [S1] plus an unknown source [S2]."), generated.copy(text = "An unknown [note]."))
        invalidResults.forEach { result -> invalid { BackgroundRecords.validateExplanation(result, generated.sources) } }
        BackgroundRecords.validateExplanation(generated.copy(text = "One claim [S1]. Another claim [S1]."), generated.sources)
    }

    @Test fun explanationCannotInsertNewLinksOrHtmlIntoTitleOrText() {
        val generated = explanation()
        val values = listOf(generated.copy(title = "https://example.org"), generated.copy(text = "See https://example.org [S1]."),
            generated.copy(text = "See www.example.org [S1]."), generated.copy(text = "See [S1](https://example.org)."),
            generated.copy(text = "<b>Claim</b> [S1]."))
        values.forEach { result -> invalid { BackgroundRecords.validateExplanation(result, generated.sources) } }
    }

    @Test fun recordValidationRejectsWrongIdentityVersionsTimesAndForgedMetadata() {
        val saved = record()
        val values = listOf(saved.copy(id = "other"), saved.copy(version = 2), saved.copy(revision = 0),
            saved.copy(createdAt = -1), saved.copy(updatedAt = saved.createdAt - 1), saved.copy(selectedCandidateId = "meaning-2"),
            saved.copy(comment = "x".repeat(12001)), saved.copy(tags = listOf("")), saved.copy(explanation = explanation(candidate = "meaning-2")))
        values.forEach { value -> invalid { BackgroundRecords.validate(value) } }
    }

    @Test fun savedExplanationCannotBeReplacedByAnotherOtherwiseValidExplanation() {
        val saved = BackgroundRecords.create(lookup(), "meaning-1", now, explanation())
        val changed = explanation().copy(text = "A different interpretation of the selected evidence [S1].")
        invalid { BackgroundRecords.withExplanation(saved, 1, changed, now + 1) }
        assertSame(saved, BackgroundRecords.withExplanation(saved, 1, explanation(), now + 1))
        assertEquals(explanation(), saved.explanation)
    }

    @Test fun sharedExplanationSchemaPreservesExistingMultilineAndUnicodeContent() {
        val generated = explanation().copy(text = "制度背景 🧭 [S1].\r\n\tA second contextual line [S1].",
            evidence = "Retrieved evidence.\n\tPlease read the cited source.")
        BackgroundRecords.validateExplanation(generated, generated.sources)
        val saved = BackgroundRecords.create(lookup(), "meaning-1", now, generated)
        assertEquals(generated, saved.explanation)
        assertEquals(generated.text, requireNotNull(saved.explanation).text)
    }
}
