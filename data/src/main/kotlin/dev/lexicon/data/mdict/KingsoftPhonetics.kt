package dev.lexicon.data.mdict

/**
 * Character positions verified against the Kingsoft Phonetic Plain glyph table.
 * This is text normalization, not a pronunciation generator. No font file is bundled.
 * Unverified symbols produce no transcription instead of guessing a Unicode glyph.
 */
internal object KingsoftPhonetics {
    private val symbols = mapOf(
        'A' to "æ", 'B' to "ɑ", 'C' to "ɔ", 'D' to "ã", 'E' to "ə", 'F' to "ʃ",
        'I' to "ɪ", 'J' to "ʊ", 'L' to "ɚ", 'N' to "ŋ", 'O' to "œ", 'Q' to "ʌ",
        'T' to "ð", 'V' to "ʒ", 'W' to "θ", 'Z' to "ɛ", '\\' to "ɜ",
        '5' to "ˈ", '7' to "ˌ", ':' to "ː", '4' to ":", '1' to "…", '6' to "!", '8' to "?", '9' to "/",
    )
    fun decode(encoded: String): String? = buildString {
        for (character in encoded) {
            val converted = symbols[character]
            when {
                converted != null -> append(converted)
                character in 'a'..'z' || character.isWhitespace() || character in "(),-./;'" -> append(character)
                else -> return null
            }
        }
    }
}
