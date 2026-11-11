package dev.lexicon.data

import dev.lexicon.domain.BackgroundRecord
import dev.lexicon.domain.BackgroundRecords
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One self-contained knowledge item; cache cleanup cannot remove its saved evidence. */
internal object BackgroundRecordStorage {
    const val PREFIX = "reading-library:background:"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun key(id: String): String = PREFIX + id
    fun revisionKey(id: String): String = "background-record-revision:$id"

    fun revision(data: String?): Int {
        if (data == null) return 0
        require(data.matches(Regex("[1-9][0-9]{0,9}")))
        return requireNotNull(data.toIntOrNull()).also { require(it > 0) }
    }

    fun decode(row: PreferenceRow): BackgroundRecord {
        require(row.key.startsWith(PREFIX) && row.data.length <= 200_000)
        return json.decodeFromString<BackgroundRecord>(row.data).also { record ->
            require(row.key == key(record.id))
            BackgroundRecords.validate(record)
            BackgroundEvidence.validateSources(record.lookup.sources)
        }
    }

    fun row(record: BackgroundRecord): PreferenceRow {
        BackgroundRecords.validate(record)
        BackgroundEvidence.validateSources(record.lookup.sources)
        return PreferenceRow(key(record.id), json.encodeToString(record)).also { require(it.data.length <= 200_000) }
    }
}
