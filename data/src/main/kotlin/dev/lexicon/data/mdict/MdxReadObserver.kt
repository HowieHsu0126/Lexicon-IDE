package dev.lexicon.data.mdict

/** Optional local timing hook. Stages may be nested, so their durations must not be summed. */
internal fun interface MdxReadObserver {
    fun onStage(stage: MdxReadStage, elapsedNanos: Long)
}

internal enum class MdxReadStage {
    HEADER,
    KEY_METADATA,
    KEY_DECODE,
    RECORD_METADATA,
    END_OFFSETS,
    NORMALIZED_INDEX,
    LOOKUP_INDEX,
    LOOKUP_RESOLVE,
    LOOKUP_SANITIZE,
    RECORD_DECOMPRESS,
}
