package dev.lexicon.data

import androidx.room3.*
import dev.lexicon.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Byte projection avoids SQLite text conversion before backup or corruption detection. */
data class RawPreferenceValue(val key: String, val bytes: ByteArray)

@Dao abstract class PersonalizationRecoveryDao {
    @Query("SELECT `key`, CAST(data AS BLOB) AS bytes FROM preferences WHERE `key`=:key")
    abstract suspend fun rawEntry(key: String): RawPreferenceValue?
    @Query("SELECT `key`, CAST(data AS BLOB) AS bytes FROM preferences WHERE `key`='personalization:profile'")
    abstract fun observeProfile(): Flow<RawPreferenceValue?>
    @Query("INSERT INTO preferences (`key`, data) SELECT :backupKey, data FROM preferences WHERE `key`='personalization:profile'")
    protected abstract suspend fun copyOriginal(backupKey: String)
    @Upsert protected abstract suspend fun put(row: PreferenceRow)

    /** The read, byte-for-byte backup, verification, and safe reset are one rollback boundary. */
    @Transaction open suspend fun recover(expectedRowDigest: String, backupKey: String): PersonalizationRecoveryReceipt {
        require(Regex("[a-f0-9]{64}").matches(expectedRowDigest))
        require(backupKey.startsWith(PersonalizationStorage.BACKUP_PREFIX) && backupKey.length in 24..120 &&
            backupKey.none { it.isISOControl() })
        val original = rawEntry(PersonalizationStorage.PROFILE_KEY)
            ?: throw PersonalizationConflict("偏好记录已改变，请重新查看")
        val health = PersonalizationStorage.health(original.bytes)
        if (health.rowDigest != expectedRowDigest || health.status != PersonalizationHealthStatus.CORRUPT)
            throw PersonalizationConflict("偏好记录已改变，请重新查看")
        require(rawEntry(backupKey) == null) { "本机备份位置已存在，原记录保持不变" }
        copyOriginal(backupKey)
        val backup = requireNotNull(rawEntry(backupKey)) { "本机备份未完成，原记录保持不变" }
        check(original.bytes.contentEquals(backup.bytes) && PersonalizationStorage.digest(backup.bytes) == expectedRowDigest) {
            "本机备份校验未通过，原记录保持不变"
        }
        val profile = PersonalizationProfile(enabled = false)
        put(PreferenceRow(PersonalizationStorage.PROFILE_KEY, Json { encodeDefaults = true }.encodeToString(profile)))
        check(PersonalizationStorage.decodeRaw(rawEntry(PersonalizationStorage.PROFILE_KEY)?.bytes) == profile)
        return PersonalizationRecoveryReceipt(profile, backupKey, expectedRowDigest, original.bytes.size, backupExists = true)
    }
}
