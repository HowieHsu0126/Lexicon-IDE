package dev.lexicon.domain

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReadingResumeTest {
    private val now = 1_000_000L
    private val text = "  The council discusses local services. 🧭\r\n\r\n\tThe council discusses local services. 🧭\r\n\r\nA final paragraph explains the evidence.  "
    private val codec = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun body(value: String = text, articleId: String = "article") = ArticleBody(articleId, value, hash(value), now, now + 1000, "Synthetic test material")
    private fun lease(body: ArticleBody = body(), generation: Long = 1, previous: ReadingResumeRecord? = null) =
        ReadingResumeLease(body.articleId, body.contentHash, generation, previous)
    private fun record(body: ArticleBody = body(), paragraph: Int = 1, completed: Boolean = false) =
        ReadingResumes.create(lease(body), 1, body, paragraph, ReadingResumeControls(), now, completed)
    private fun invalid(action: () -> Unit) = assertThrows(IllegalArgumentException::class.java) { action() }

    @Test fun defaultControlsRepresentExplicitIntensiveReading() {
        val controls = ReadingResumeControls()
        assertEquals(StudyMode.INTENSIVE, controls.mode)
        assertFalse(controls.quiet)
        assertEquals(5, controls.budgetMinutes)
        assertEquals("全部", controls.roleFilter)
        assertEquals("导图", controls.toolTab)
        ReadingResumes.validateControls(controls)
    }

    @Test fun allExistingReaderControlCombinationsAreAcceptedWithoutChangingThem() {
        for (mode in StudyMode.entries) for (budget in listOf(5, 10)) {
            for (role in ReadingResumes.roleFilters) for (tab in ReadingResumes.toolTabs) {
                val controls = ReadingResumeControls(mode, true, budget, role, tab)
                ReadingResumes.validateControls(controls)
                val saved = record().copy(controls = controls)
                ReadingResumes.validate(saved)
                assertEquals(controls, saved.controls)
            }
        }
    }

    @Test fun unsupportedControlsAreRejectedRatherThanSilentlyReset() {
        listOf(ReadingResumeControls(budgetMinutes = 0), ReadingResumeControls(budgetMinutes = 7),
            ReadingResumeControls(budgetMinutes = 15), ReadingResumeControls(roleFilter = "unknown"),
            ReadingResumeControls(roleFilter = "全部 "), ReadingResumeControls(toolTab = "unknown"),
            ReadingResumeControls(toolTab = "")).forEach { controls -> invalid { ReadingResumes.validateControls(controls) } }
    }

    @Test fun identityRequiresBoundedNonblankWellFormedIdsAndFullLowercaseHash() {
        val body = body()
        listOf("", " ", "x".repeat(201), "bad\u0000id", "line\nid", "bad\uD83D").forEach { id ->
            invalid { ReadingResumes.validateIdentity(id, body.contentHash) }
        }
        listOf("", "a".repeat(63), "a".repeat(65), "g".repeat(64), "A".repeat(64)).forEach { digest ->
            invalid { ReadingResumes.validateIdentity("article", digest) }
        }
        ReadingResumes.validateIdentity("x".repeat(200), body.contentHash)
        ReadingResumes.validateIdentity("原文 🧭", body.contentHash)
    }

    @Test fun checkpointUsesTheExactTrimmedOriginalParagraphRange() {
        val body = body()
        val paragraph = ParagraphFocus.paragraphs(body.text)[1]
        val controls = ReadingResumeControls(StudyMode.SKIM, true, 10, "证据", "略读")
        val saved = ReadingResumes.create(lease(body), 4, body, 1, controls, now)
        assertEquals(paragraph.start, saved.start)
        assertEquals(paragraph.endExclusive, saved.end)
        assertEquals(1, saved.paragraphIndex)
        assertEquals(controls, saved.controls)
        assertEquals("The council discusses local services. 🧭", body.text.substring(saved.start, saved.end))
        ReadingResumes.validate(saved, body)
    }

    @Test fun repeatedParagraphTextDoesNotMoveARecordToTheFirstOccurrence() {
        val body = body()
        val first = record(body, 0)
        val second = record(body, 1)
        assertEquals(body.text.substring(first.start, first.end), body.text.substring(second.start, second.end))
        assertNotEquals(first.start, second.start)
        assertEquals(1, ReadingResumes.anchor(second, body)?.paragraphIndex(body))
        invalid { ReadingResumes.validate(second.copy(start = first.start, end = first.end), body) }
    }

    @Test fun longArticlesAndLateParagraphsHaveNoAiInputBudgetLimit() {
        val body = body((0..120).joinToString("\n\n") { "Paragraph $it. " + "Original material. ".repeat(20) })
        val saved = record(body, 110)
        assertTrue(saved.start > 24_000)
        assertTrue(saved.paragraphIndex > 80)
        assertEquals(saved, ReadingResumes.restore(saved, body))
    }

    @Test fun matchingHashLabelsDoNotAllowChangedBodyBytes() {
        val original = body()
        val saved = record(original)
        val changed = original.copy(text = "X" + original.text.drop(1))
        invalid { ReadingResumes.validate(saved, changed) }
        invalid { ReadingResumes.create(lease(original), 1, changed, 1, ReadingResumeControls(), now) }
        assertNull(ReadingResumes.restore(saved, changed))
        assertNull(ReadingResumes.anchor(saved, changed))
    }

    @Test fun recordAndLeaseCannotCrossArticleOrBodyIdentity() {
        val original = body()
        val otherArticle = original.copy(articleId = "other")
        val changedBody = body(original.text + " Updated.")
        invalid { ReadingResumes.create(lease(original), 1, otherArticle, 1, ReadingResumeControls(), now) }
        invalid { ReadingResumes.create(lease(original), 1, changedBody, 1, ReadingResumeControls(), now) }
        assertNull(ReadingResumes.restore(record(original), otherArticle))
        assertNull(ReadingResumes.restore(record(original), changedBody))
    }

    @Test fun fullRangeBindingRejectsPartialParagraphsGapsAndOutOfBoundsIndices() {
        val body = body()
        val saved = record(body)
        val values = listOf(saved.copy(start = saved.start + 1), saved.copy(end = saved.end - 1),
            saved.copy(start = saved.start - 1), saved.copy(end = saved.end + 1),
            saved.copy(paragraphIndex = 0), saved.copy(paragraphIndex = Int.MAX_VALUE), saved.copy(end = Int.MAX_VALUE))
        values.forEach { value -> invalid { ReadingResumes.validate(value, body) }; assertNull(ReadingResumes.anchor(value, body)) }
        invalid { ReadingResumes.create(lease(body), 1, body, -1, ReadingResumeControls(), now) }
        invalid { ReadingResumes.create(lease(body), 1, body, 3, ReadingResumeControls(), now) }
    }

    @Test fun malformedUnicodeAndBlankOriginalBodiesCannotProducePositions() {
        listOf("", " \n\t ", "A malformed \uD83D paragraph.", "A malformed \uDE00 paragraph.").forEach { value ->
            val body = body(value)
            invalid { ReadingResumes.create(lease(body), 1, body, 0, ReadingResumeControls(), now) }
        }
        val unicodeBody = body("🧭 A paragraph ending in a supplementary character 🧬")
        val saved = record(unicodeBody, 0)
        ReadingResumes.validate(saved, unicodeBody)
        invalid { ReadingResumes.validate(saved.copy(start = 1), unicodeBody) }
        invalid { ReadingResumes.validate(saved.copy(end = saved.end - 1), unicodeBody) }
    }

    @Test fun metadataValidationDoesNotNeedCachedTextButRejectsMalformedState() {
        val saved = record()
        val values = listOf(saved.copy(paragraphIndex = -1), saved.copy(start = -1), saved.copy(end = saved.start),
            saved.copy(end = saved.start - 1), saved.copy(generation = 0), saved.copy(generation = -1),
            saved.copy(sequence = 0), saved.copy(sequence = -1), saved.copy(updatedAt = -1),
            saved.copy(articleId = ""), saved.copy(bodyHash = "unknown"), saved.copy(controls = ReadingResumeControls(budgetMinutes = 1)))
        values.forEach { value -> invalid { ReadingResumes.validate(value) } }
        ReadingResumes.validate(saved)
    }

    @Test fun beginMayRetainAnOlderBodyWithoutPretendingItsPositionStillMatches() {
        val previous = record()
        val newBody = body(text + " New source material.")
        val nextLease = lease(newBody, 2, previous)
        ReadingResumes.validateLease(nextLease)
        assertEquals(previous, nextLease.previous)
        assertNull(ReadingResumes.restore(previous, newBody))
        val next = ReadingResumes.create(nextLease, 1, newBody, 2, ReadingResumeControls(), now)
        assertEquals(newBody.contentHash, next.bodyHash)
        assertEquals(2L, next.generation)
    }

    @Test fun leaseMustAdvanceTheSameArticlesPriorGeneration() {
        val previous = record()
        val valid = lease(generation = 2, previous = previous)
        ReadingResumes.validateLease(valid)
        listOf(valid.copy(generation = 0), valid.copy(generation = 1),
            valid.copy(previous = previous.copy(generation = 3)), valid.copy(previous = previous.copy(articleId = "other")),
            valid.copy(previous = previous.copy(sequence = 0))).forEach { value -> invalid { ReadingResumes.validateLease(value) } }
    }

    @Test fun completionRemainsReadableButDoesNotOfferAContinuationAnchor() {
        val body = body()
        val completed = record(body, completed = true)
        ReadingResumes.validate(completed, body)
        assertTrue(completed.completed)
        assertNull(ReadingResumes.restore(completed, body))
        assertNull(ReadingResumes.anchor(completed, body))
        val fresh = ReadingResumes.create(lease(body, 2, completed), 1, body, 0, ReadingResumeControls(), now)
        assertFalse(fresh.completed)
    }

    @Test fun expiredBodyMetadataDoesNotEraseAnOtherwiseExactSavedPosition() {
        val body = body().copy(cachedAt = 0, expiresAt = 1)
        val saved = record(body)
        assertEquals(saved, ReadingResumes.restore(saved, body))
        assertEquals(ReaderAnchor(saved.bodyHash, saved.start, saved.end), ReadingResumes.anchor(saved, body))
    }

    @Test fun checkpointRequiresPositiveSequenceAndNonnegativeTime() {
        val body = body()
        listOf(0L, -1L, Long.MIN_VALUE).forEach { sequence ->
            invalid { ReadingResumes.create(lease(body), sequence, body, 0, ReadingResumeControls(), now) }
        }
        invalid { ReadingResumes.create(lease(body), 1, body, 0, ReadingResumeControls(), -1) }
        ReadingResumes.validate(record(body).copy(generation = Long.MAX_VALUE, sequence = Long.MAX_VALUE, updatedAt = 0))
    }

    @Test fun wallClockRollbackDoesNotDetermineWriterOrder() {
        val body = body()
        val previous = record(body)
        val reopened = ReadingResumes.create(lease(body, 2, previous), 1, body, 2, ReadingResumeControls(), now - 100)
        val later = ReadingResumes.create(lease(body, 2, previous), 2, body, 1, ReadingResumeControls(), now - 200)
        ReadingResumes.validate(reopened)
        ReadingResumes.validate(later)
        assertTrue(later.sequence > reopened.sequence)
        assertTrue(later.updatedAt < reopened.updatedAt)
    }

    @Test fun jsonRoundTripPreservesControlsPositionAndLeaseWithoutOriginalText() {
        val saved = record().copy(controls = ReadingResumeControls(StudyMode.SKIM, true, 10, "背景", "重点"))
        val stored = codec.encodeToString(saved)
        assertFalse(stored.contains("The council"))
        assertFalse(stored.contains("Synthetic test material"))
        assertEquals(saved, codec.decodeFromString<ReadingResumeRecord>(stored))
        val lease = lease(generation = 2, previous = saved)
        assertEquals(lease, codec.decodeFromString<ReadingResumeLease>(codec.encodeToString(lease)))
        val library = ReadingResumeLibrary(listOf(saved), errors = 1)
        assertEquals(library, codec.decodeFromString<ReadingResumeLibrary>(codec.encodeToString(library)))
        assertEquals(ReadingResumeLibrary(), codec.decodeFromString<ReadingResumeLibrary>("{}"))
    }

    @Test fun entryIntentAndControlsDoNotIntroduceHiddenStateOrUnknownEnumFallbacks() {
        for (kind in ReadingEntryKind.entries) {
            assertEquals(kind, codec.decodeFromString<ReadingEntryKind>(codec.encodeToString(kind)))
        }
        val controls = codec.encodeToString(ReadingResumeControls())
        assertFalse(controls.contains("question"))
        assertFalse(controls.contains("rewrite"))
        assertFalse(controls.contains("toolsOpen"))
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            codec.decodeFromString<ReadingResumeControls>(controls.replace("INTENSIVE", "INFERRED_ABILITY"))
        }
    }
}
