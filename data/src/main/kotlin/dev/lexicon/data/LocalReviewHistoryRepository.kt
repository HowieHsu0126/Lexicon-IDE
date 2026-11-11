package dev.lexicon.data

import androidx.room3.Dao
import androidx.room3.Query
import dev.lexicon.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*

/** One query gives the count and latest receipt the same committed database snapshot. */
class LocalReviewHistoryRepository(private val dao: ReviewHistoryDao) : ReviewHistoryRepository {
    override fun observeDay(range: ReviewDayRange): Flow<ReviewDaySummary> =
        dao.observeRows(range.startInclusive, range.endExclusive)
            .map { ReviewHistoryRecords.summary(it, range) }
            .distinctUntilChanged()
            .catch { error ->
                if (error is CancellationException) throw error
                throw ReviewHistoryUnavailableException()
            }
            .flowOn(Dispatchers.Default)
}

@Dao abstract class ReviewHistoryDao {
    @Query("SELECT * FROM review_logs WHERE reviewedAt >= :startInclusive AND reviewedAt < :endExclusive ORDER BY reviewedAt, id")
    abstract fun observeRows(startInclusive: Long, endExclusive: Long): Flow<List<ReviewRow>>
}

/** Preserves legacy/unknown fields in storage and validates only the existing review contract. */
internal object ReviewHistoryRecords {
    private val codec = Json { ignoreUnknownKeys = true }

    fun summary(rows: List<ReviewRow>, range: ReviewDayRange): ReviewDaySummary = try {
        val logs = LinkedHashMap<String, ReviewLog>()
        for (row in rows) {
            val log = decode(row, range)
            val existing = logs.putIfAbsent(log.id, log)
            require(existing == null || existing == log)
        }
        val latest = logs.values.maxWithOrNull(compareBy<ReviewLog> { it.reviewedAt }.thenBy { it.id })
        ReviewDaySummary(logs.size, latest?.let {
            ReviewReceipt(it.id, it.cardId, it.reviewedAt, it.rating, it.before.reps, it.after.reps)
        })
    } catch (_: Exception) {
        // Do not attach a parser exception: it can contain the raw stored JSON.
        throw ReviewHistoryUnavailableException()
    }

    private fun decode(row: ReviewRow, range: ReviewDayRange): ReviewLog {
        require(row.id.isNotBlank() && row.cardId.isNotBlank())
        require(row.reviewedAt >= range.startInclusive && row.reviewedAt < range.endExclusive)
        val root = codec.parseToJsonElement(row.data).jsonObject
        require(root.string("id") == row.id && root.string("cardId") == row.cardId)
        require(root.long("reviewedAt") == row.reviewedAt)
        Rating.valueOf(root.string("rating"))
        validateState(root.getValue("before").jsonObject)
        validateState(root.getValue("after").jsonObject)
        root["schedulerVersion"]?.let { require(it.jsonPrimitive.isString && it.jsonPrimitive.content.isNotBlank()) }
        root["parameters"]?.let { require(it.jsonPrimitive.isString && it.jsonPrimitive.content.isNotBlank()) }
        val log = codec.decodeFromJsonElement<ReviewLog>(root)
        require(log.before.reps >= 0 && log.before.reps < Int.MAX_VALUE)
        require(log.after.reps == log.before.reps + 1)
        require(log.after.lastReviewAt == log.reviewedAt)
        return log
    }

    private fun validateState(state: JsonObject) {
        CardPhase.valueOf(state.string("phase"))
        state.long("dueAt")
        require(state.int("reps") >= 0 && state.int("lapses") >= 0)
        state["lastReviewAt"]?.takeUnless { it == JsonNull }?.let { require(!it.jsonPrimitive.isString && it.jsonPrimitive.longOrNull != null) }
        state["step"]?.takeUnless { it == JsonNull }?.let { require(!it.jsonPrimitive.isString && it.jsonPrimitive.intOrNull?.let { value -> value >= 0 } == true) }
        listOf("stability", "difficulty").forEach { key ->
            state[key]?.takeUnless { it == JsonNull }?.let {
                val value = it.jsonPrimitive
                require(!value.isString && value.doubleOrNull?.let { number -> number.isFinite() && number > 0 } == true)
            }
        }
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let { require(it.isString); it.content }
    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.let { require(!it.isString); requireNotNull(it.longOrNull) }
    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.let { require(!it.isString); requireNotNull(it.intOrNull) }
}
