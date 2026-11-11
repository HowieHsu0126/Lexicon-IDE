package dev.lexicon.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import dev.lexicon.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Every existing-card mutation reads and merges inside the database transaction. This also protects
 * independent Repository instances: a content edit or export receipt cannot restore stale scheduling.
 */
@Dao abstract class CardLifecycleDao {
    private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scheduler = Fsrs6Scheduler()

    @Query("SELECT * FROM cards WHERE id=:id")
    protected abstract suspend fun row(id: String): CardRow?

    @Query("UPDATE cards SET dueAt=:dueAt, reps=:reps, updatedAt=:updatedAt, data=:data WHERE id=:id")
    protected abstract suspend fun update(id: String, dueAt: Long, reps: Int, updatedAt: Long, data: String): Int

    @Insert protected abstract suspend fun insertReview(row: ReviewRow)
    @Insert protected abstract suspend fun insertEvent(row: EventRow)

    /** An idempotent local choice, unrelated to note content, familiarity or FSRS progress. */
    @Transaction open suspend fun setReviewPaused(id: String, paused: Boolean): Card? {
        val previous = row(id) ?: return null
        val card = codec.decodeFromString<Card>(previous.data)
        if (card.reviewPaused == paused) return card
        return card.copy(reviewPaused = paused).also { write(previous, it, setOf("reviewPaused")) }
    }

    /** The pause guard, expected-repetition guard, state, log and existing completion event are atomic. */
    @Transaction open suspend fun review(id: String, rating: Rating, expectedReps: Int, now: Long): ReviewOutcome {
        val previous = row(id) ?: return ReviewOutcome.MISSING
        val card = codec.decodeFromString<Card>(previous.data)
        if (card.reviewPaused) return ReviewOutcome.PAUSED
        if (card.state.reps != expectedReps) return ReviewOutcome.STALE
        val next = scheduler.review(card.state, rating, now)
        val log = ReviewLog(UUID.randomUUID().toString(), card.id, now, rating, card.state, next)
        write(previous, card.copy(state = next), setOf("state"))
        insertReview(ReviewRow(log.id, card.id, now, codec.encodeToString(log)))
        event("review_completed", now)
        return ReviewOutcome.APPLIED
    }

    /** The editor supplies only content and its inaccuracy flag; all other fields come from storage. */
    @Transaction open suspend fun edit(proposed: Card, now: Long): Card? {
        ContentValidator.requireValid(proposed.content)
        val previous = row(proposed.id) ?: return null
        val card = codec.decodeFromString<Card>(previous.data)
        val contentChanged = card.content != proposed.content
        if (!contentChanged && card.inaccurate == proposed.inaccurate) return card
        val edited = card.copy(
            content = proposed.content,
            inaccurate = proposed.inaccurate,
            // Keep export-version comparisons reliable even for two edits within one millisecond.
            updatedAt = if (contentChanged) maxOf(now, Math.addExact(card.updatedAt, 1L)) else card.updatedAt,
            ankiExportedAt = if (contentChanged) null else card.ankiExportedAt,
        )
        write(previous, edited, setOf("content", "inaccurate", "updatedAt", "ankiExportedAt"))
        if (edited.inaccurate && !card.inaccurate) event("card_flagged_inaccurate", now)
        return edited
    }

    /** Mark only the content versions actually exported, retaining any newer review/pause decisions. */
    @Transaction open suspend fun confirmExport(versions: Map<String, Long>, exportedAt: Long): Int {
        var marked = 0
        versions.forEach { (id, version) ->
            val previous = row(id) ?: return@forEach
            val card = codec.decodeFromString<Card>(previous.data)
            if (card.updatedAt == version) {
                write(previous, card.copy(ankiExportedAt = exportedAt), setOf("ankiExportedAt"))
                marked++
            }
        }
        event("anki_export_completed", exportedAt, versions.size)
        return marked
    }

    private suspend fun write(previous: CardRow, card: Card, fields: Set<String>) {
        val saved = codec.parseToJsonElement(previous.data).jsonObject
        val changed = codec.encodeToJsonElement(card).jsonObject
        // Preserve unknown metadata and fields outside this operation's ownership.
        val merged = JsonObject(saved + fields.associateWith { changed.getValue(it) })
        check(update(previous.id, card.state.dueAt, card.state.reps, card.updatedAt, merged.toString()) == 1)
    }

    private suspend fun event(type: String, now: Long, value: Int = 1) {
        val event = LocalEvent(UUID.randomUUID().toString(), type, now, value)
        insertEvent(EventRow(event.id, now, codec.encodeToString(event)))
    }
}
