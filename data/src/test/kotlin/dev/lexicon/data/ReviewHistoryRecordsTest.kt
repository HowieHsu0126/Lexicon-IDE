package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReviewHistoryRecordsTest {
    private val codec = Json { encodeDefaults = true }
    private val range = ReviewDayRange(0, 10_000)
    private fun log(id: String = "log-a", cardId: String = "card", at: Long = 2_000, reps: Int = 4) = ReviewLog(
        id, cardId, at, Rating.GOOD,
        FsrsState(CardPhase.REVIEW, 3.0, 5.0, 1000, 500, null, reps, 1),
        FsrsState(CardPhase.REVIEW, 4.0, 4.0, 5000, at, null, reps + 1, 1),
    )
    private fun row(log: ReviewLog = log()) = ReviewRow(log.id, log.cardId, log.reviewedAt, codec.encodeToString(log))
    private fun unreadable(rows: List<ReviewRow>) {
        val failure = assertThrows(ReviewHistoryUnavailableException::class.java) { ReviewHistoryRecords.summary(rows, range) }
        assertEquals("Review history is unavailable", failure.message)
        assertNull("Parser details must not expose stored JSON", failure.cause)
    }

    @Test fun emptyCommittedSnapshotHasNoReceipt() {
        assertEquals(ReviewDaySummary(), ReviewHistoryRecords.summary(emptyList(), range))
    }

    @Test fun repetitionsOfOneCardAreDistinctReviewsButDuplicateLogIdsCountOnce() {
        val first = log(); val second = log("log-b", at = 3_000, reps = 5)
        val result = ReviewHistoryRecords.summary(listOf(row(second), row(first), row(first)), range)
        assertEquals(2, result.count)
        assertEquals(ReviewReceipt("log-b", "card", 3_000, Rating.GOOD, 5, 6), result.latestReceipt)
    }

    @Test fun timestampAndLogIdDefineOneStableLatestReceiptRegardlessOfRowOrder() {
        val rows = listOf(row(log("z", "first", 2_000)), row(log("a", "second", 2_000)), row(log("zz", "older", 1_999)))
        val expected = ReviewDaySummary(3, ReviewReceipt("z", "first", 2_000, Rating.GOOD, 4, 5))
        assertEquals(expected, ReviewHistoryRecords.summary(rows, range))
        assertEquals(expected, ReviewHistoryRecords.summary(rows.reversed(), range))
    }

    @Test fun recordMustMatchItsDatabaseIdentityTimeAndRequestedWindow() {
        val original = row()
        listOf(original.copy(id = "other"), original.copy(cardId = "other"), original.copy(reviewedAt = 2_001),
            original.copy(id = ""), original.copy(cardId = " "), row(log(at = -1)), row(log(at = range.endExclusive)))
            .forEach { unreadable(listOf(it)) }
        assertEquals(1, ReviewHistoryRecords.summary(listOf(row(log(at = range.startInclusive))), range).count)
        assertEquals(1, ReviewHistoryRecords.summary(listOf(row(log(at = range.endExclusive - 1))), range).count)
    }

    @Test fun malformedPartialAndWronglyTypedRecordsDoNotFabricateACompletedCount() {
        val original = row()
        val root = codec.parseToJsonElement(original.data).jsonObject
        val before = root.getValue("before").jsonObject
        val invalid = listOf(
            "", "null", "[]", "{", "{}",
            JsonObject(root - "id").toString(),
            JsonObject(root + ("reviewedAt" to JsonPrimitive("2000"))).toString(),
            JsonObject(root + ("rating" to JsonPrimitive("KNOWN"))).toString(),
            JsonObject(root + ("before" to JsonObject(before - "reps"))).toString(),
            JsonObject(root + ("before" to JsonObject(before + ("reps" to JsonPrimitive("4"))))).toString(),
            JsonObject(root + ("before" to JsonObject(before + ("phase" to JsonPrimitive("FINISHED"))))).toString(),
            JsonObject(root + ("before" to JsonObject(before + ("stability" to JsonPrimitive("NaN"))))).toString(),
            JsonObject(root + ("parameters" to JsonPrimitive(false))).toString(),
            JsonObject(root + ("schedulerVersion" to JsonPrimitive(""))).toString(),
        )
        invalid.forEach { unreadable(listOf(original.copy(data = it))) }
        // A valid record cannot hide a damaged record in the same snapshot.
        unreadable(listOf(original, ReviewRow("damaged", "card", 3_000, "{}")))
    }

    @Test fun committedTransitionRequiresOneAddedRepetitionAndItsRecordedTime() {
        val original = log()
        listOf(
            original.copy(after = original.after.copy(reps = original.before.reps)),
            original.copy(after = original.after.copy(reps = original.before.reps + 2)),
            original.copy(before = original.before.copy(reps = -1), after = original.after.copy(reps = 0)),
            original.copy(before = original.before.copy(reps = Int.MAX_VALUE), after = original.after.copy(reps = 0)),
            original.copy(after = original.after.copy(lastReviewAt = original.reviewedAt + 1)),
            original.copy(after = original.after.copy(lastReviewAt = null)),
        ).forEach { unreadable(listOf(row(it))) }
        Rating.entries.forEach { rating ->
            assertEquals(rating, ReviewHistoryRecords.summary(listOf(row(original.copy(rating = rating))), range).latestReceipt?.rating)
        }
    }

    @Test fun unknownMetadataAndOmittedDefaultAlgorithmLabelsRemainReadableWithoutRewriting() {
        val original = row()
        val root = codec.parseToJsonElement(original.data).jsonObject
        val stored = original.copy(data = JsonObject(root - "schedulerVersion" - "parameters" +
            ("future_metadata" to buildJsonObject { put("keep", "exact raw bytes") })).toString())
        val before = stored.data
        assertEquals(1, ReviewHistoryRecords.summary(listOf(stored), range).count)
        assertEquals(before, stored.data)
    }

    @Test fun conflictingCopiesOfTheSameLogAreRejectedInsteadOfChoosingOneSilently() {
        val original = log()
        unreadable(listOf(row(original), row(original.copy(rating = Rating.EASY))))
        unreadable(listOf(row(original), row(original.copy(after = original.after.copy(stability = 5.0)))))
    }
}
