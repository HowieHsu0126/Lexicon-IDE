package dev.lexicon.domain

/** Offline lexical signposts. These are not a parser or a claim about a sentence's grammar. */
object SentenceCues {
    private val signals = linkedMapOf(
        "although" to "让步：先辨认让步条件，再找主句。", "however" to "转折：比较前后两部分的立场。",
        "whereas" to "对照：找出两项比较对象。", "because" to "原因：区分原因与结论。",
        "therefore" to "结论：回看前文依据。", "which" to "指代：检查它修饰的名词或前一分句。",
        "despite" to "让步：留意后接名词或动名词。", "if" to "条件：区分假设与已经发生的事实。",
        "could" to "可能性：不要把可能结果读成确定结论。", "may" to "可能性：留意作者保留的条件。",
        "would" to "情态：结合条件、过去语境或推测判断。", "not" to "否定：确认否定的作用范围。",
    )
    fun inSentence(text: String): List<String> = signals.filterKeys {
        Regex("\\b${it}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }.map { (word, cue) -> "$word · $cue" }.take(5)
}
