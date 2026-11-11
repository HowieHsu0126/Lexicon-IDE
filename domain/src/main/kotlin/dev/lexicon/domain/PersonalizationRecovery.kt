package dev.lexicon.domain

/** Health is separate from the safe display fallback so damage remains visible and recoverable. */
enum class PersonalizationHealthStatus { OK, MISSING, CORRUPT }

data class PersonalizationHealth(
    val status: PersonalizationHealthStatus,
    /** SHA-256 of the exact persisted bytes; used only for an explicit compare-and-set recovery. */
    val rowDigest: String? = null,
)

data class PersonalizationRecoveryReceipt(
    val profile: PersonalizationProfile,
    val backupKey: String,
    val originalDigest: String,
    val originalBytes: Int,
    val backupExists: Boolean,
)
