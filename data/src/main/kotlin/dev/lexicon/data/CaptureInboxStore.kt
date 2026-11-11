package dev.lexicon.data

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import dev.lexicon.domain.CaptureInboxRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Independent metadata cannot be reset by a delayed capture save or an already authorized AI job. */
class CaptureInboxStore(
    private val dao: CaptureInboxDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : CaptureInboxRepository {
    override val archivedCaptureIds: Flow<Set<String>> = dao.observeRows().map { rows ->
        rows.mapNotNull(CaptureInboxMetadata::archivedId).toSet()
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override suspend fun setArchived(captureId: String, archived: Boolean): Boolean = withContext(Dispatchers.IO) {
        dao.setArchived(captureId, archived, clock())
    }
}

/** Each capture has its own row; concurrent decisions for other captures cannot overwrite it. */
@Dao abstract class CaptureInboxDao {
    @Query("SELECT * FROM preferences WHERE `key` LIKE 'capture-inbox:archived:v1:%' ORDER BY `key`")
    abstract fun observeRows(): Flow<List<PreferenceRow>>

    @Query("SELECT EXISTS(SELECT 1 FROM captures WHERE id=:id)")
    protected abstract suspend fun captureExists(id: String): Boolean

    @Query("SELECT * FROM preferences WHERE `key`=:key")
    protected abstract suspend fun entry(key: String): PreferenceRow?

    @Upsert protected abstract suspend fun put(row: PreferenceRow)

    @Query("DELETE FROM preferences WHERE `key`=:key")
    protected abstract suspend fun remove(key: String)

    @Transaction open suspend fun setArchived(captureId: String, archived: Boolean, now: Long): Boolean {
        require(CaptureInboxMetadata.validId(captureId) && now >= 0) { "捕获归档参数无效" }
        if (!captureExists(captureId)) return false
        val key = CaptureInboxMetadata.key(captureId)
        if (!archived) remove(key)
        else if (entry(key)?.let(CaptureInboxMetadata::archivedId) != captureId) {
            put(PreferenceRow(key, CaptureInboxMetadata.encode(captureId, now)))
        }
        return true
    }
}

/** This record contains only identity and list organization, never learning text or task state. */
internal object CaptureInboxMetadata {
    const val PREFIX = "capture-inbox:archived:v1:"
    private val fields = setOf("version", "captureId", "archivedAt")

    fun validId(id: String): Boolean = id.isNotBlank() && id.length <= 256 && id.none(Char::isISOControl)
    fun key(captureId: String): String = PREFIX + sha256(captureId)

    fun encode(captureId: String, now: Long): String {
        require(validId(captureId) && now >= 0)
        return buildJsonObject {
            put("version", 1)
            put("captureId", captureId)
            put("archivedAt", now)
        }.toString()
    }

    /** Unknown versions, partial writes and records bound to another key remain visible in the inbox. */
    fun archivedId(row: PreferenceRow): String? = runCatching {
        require(row.data.length <= 2_048)
        val root = Json.parseToJsonElement(row.data).jsonObject
        require(root.keys == fields)
        val version = root.getValue("version").jsonPrimitive
        require(!version.isString && version.intOrNull == 1)
        val id = root.getValue("captureId").jsonPrimitive
        require(id.isString && validId(id.content) && row.key == key(id.content))
        val time = root.getValue("archivedAt").jsonPrimitive
        require(!time.isString && time.longOrNull?.let { it >= 0 } == true)
        id.content
    }.getOrNull()
}
