package dev.lexicon.data.mdict

/** Optional text-cleanup profiling; absent in normal application calls. */
internal fun interface MdxTextObserver {
    fun onStage(stage: MdxTextStage, elapsedNanos: Long)
}

internal enum class MdxTextStage {
    PARSE, PHONETICS, REMOVE_ELEMENTS, INSERT_BREAKS, EXTRACT_TEXT, NORMALIZE_LINES, FINALIZE,
}
