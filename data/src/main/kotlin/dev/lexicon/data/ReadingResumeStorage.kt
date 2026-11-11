package dev.lexicon.data

import dev.lexicon.domain.ReadingResumeRecord
import dev.lexicon.domain.ReadingResumes
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The lease watermark has no article text; clearing a location cannot revive an old writer. */
@Serializable
internal data class ReadingResumeGeneration(val generation: Long, val bodyHash: String? = null,
    val previousHash: String? = null, val version: Int = 1)

internal object ReadingResumeStorage {
    const val RECORD_PREFIX = "reading-resume:record:"
    const val GENERATION_PREFIX = "reading-resume:generation:"
    private val json = Json { encodeDefaults = true }
    private val hash = Regex("[a-f0-9]{64}")

    fun recordKey(articleId: String): String = RECORD_PREFIX + sha256(articleId)
    fun generationKey(articleId: String): String = GENERATION_PREFIX + sha256(articleId)

    fun record(row: PreferenceRow): ReadingResumeRecord {
        require(row.key.startsWith(RECORD_PREFIX) && row.data.length <= 16_000)
        return json.decodeFromString<ReadingResumeRecord>(row.data).also {
            ReadingResumes.validate(it)
            require(row.key == recordKey(it.articleId))
        }
    }

    fun row(value: ReadingResumeRecord): PreferenceRow {
        ReadingResumes.validate(value)
        return PreferenceRow(recordKey(value.articleId), json.encodeToString(value))
    }

    fun generation(row: PreferenceRow?, articleId: String): ReadingResumeGeneration? {
        if (row == null) return null
        require(row.key == generationKey(articleId))
        return generation(row)
    }

    fun generation(row: PreferenceRow): ReadingResumeGeneration {
        require(row.key.startsWith(GENERATION_PREFIX) && hash.matches(row.key.removePrefix(GENERATION_PREFIX)) && row.data.length <= 512)
        return json.decodeFromString<ReadingResumeGeneration>(row.data).also {
            require(it.version == 1 && it.generation > 0 && (it.bodyHash == null || hash.matches(it.bodyHash)) &&
                (it.previousHash == null || hash.matches(it.previousHash)))
        }
    }

    fun previousHash(previous: ReadingResumeRecord?): String? = previous?.let { sha256(json.encodeToString(it)) }

    fun generationRow(articleId: String, generation: Long, bodyHash: String?, previous: ReadingResumeRecord? = null): PreferenceRow {
        require(generation > 0 && (bodyHash == null || hash.matches(bodyHash)))
        return PreferenceRow(generationKey(articleId), json.encodeToString(ReadingResumeGeneration(generation, bodyHash, previousHash(previous))))
    }
}
