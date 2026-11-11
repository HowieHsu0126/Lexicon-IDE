package dev.lexicon.domain

import java.util.Locale

/** Business validation is intentionally independent of JSON syntax validation. */
object ContentValidator {
    fun validate(content: VocabularyContent): List<String> = buildList {
        field("headword", content.headword, 120)
        field("context_definition", content.contextDefinition, 1_200)
        field("paraphrase", content.paraphrase, 600)
        if (content.registerTag !in setOf("formal", "neutral", "informal")) add("register_tag 无效")
        if (content.examRelevance !in setOf("high", "medium", "low")) add("exam_relevance 无效")
        if (content.collocations.size !in 2..4) add("collocations 必须包含 2–4 项")
        content.collocations.forEachIndexed { i, c ->
            field("collocations[$i].phrase", c.phrase, 180)
            field("collocations[$i].example", c.example, 600)
        }
        if (content.collocations.map { it.phrase.trim().lowercase(Locale.ROOT) }.distinct().size != content.collocations.size)
            add("collocations 包含重复搭配")
    }

    fun requireValid(content: VocabularyContent) {
        val errors = validate(content)
        require(errors.isEmpty()) { errors.joinToString("；") }
    }

    fun validateCapture(capture: Capture): List<String> = buildList {
        field("目标词或短语", capture.target, 120)
        field("选中文本", capture.selectedText, 4_000)
        field("原句", capture.sentence, 4_000)
        if (capture.contextOrigin == ContextOrigin.MISSING) add("请先补充并确认原句")
        if (!capture.aiAllowed) add("该来源暂不支持 AI 分析")
    }

    private fun MutableList<String>.field(name: String, value: String, max: Int) {
        if (value.isBlank()) add("$name 不能为空")
        if (value.length > max) add("$name 不能超过 $max 个字符")
        if (value.any { it.code == 0 || (it.isISOControl() && it !in "\n\r\t") }) add("$name 包含无效控制字符")
    }
}
