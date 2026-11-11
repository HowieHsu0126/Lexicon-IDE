package dev.lexicon.data

import dev.lexicon.domain.ReadingLibrary
import dev.lexicon.domain.ContextOrigin
import dev.lexicon.domain.BackgroundRecord
import dev.lexicon.domain.BackgroundRecords
import dev.lexicon.domain.BackgroundResearch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A portable local snapshot for Markdown/Notion import. No network account or credentials required. */
internal object KnowledgeMarkdown {
    fun render(library: ReadingLibrary): String = buildString {
        appendLine("# Lexicon 阅读知识库")
        appendLine(); appendLine("本文件是单向内容快照。原文引用、AI 改写、个人评论与 AI 整理分别标注；后续编辑不自动同步。")
        appendLine(); appendLine("## 阅读笔记")
        if (library.notes.isEmpty()) appendLine("暂无笔记。")
        library.notes.distinctBy { it.id }.sortedByDescending { it.createdAt }.forEachIndexed { index, note ->
            appendLine(); appendLine("### ${index + 1}. ${escape(note.source.title ?: "未关联文章")}")
            appendLine(); appendLine("记录时间：${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochMilli(note.createdAt))}（上海）")
            appendLine(); appendLine("来源：${escape(note.source.display())}")
            note.source.url?.takeIf { it.isNotBlank() }?.let { appendLine(); appendLine("原文地址：${escape(it)}") }
            if (note.tags.isNotEmpty()) { appendLine(); appendLine("标签：${note.tags.joinToString(" · ") { escape(it) }}") }
            if (note.quote.isNotBlank()) {
                val label = when (note.origin) {
                    ContextOrigin.ORIGINAL -> "保存的原文片段"
                    ContextOrigin.USER_SUPPLIED -> "用户补充片段"
                    ContextOrigin.AI_GENERATED -> "AI 改写片段（不是原文引用）"
                    ContextOrigin.MISSING -> "保存的文本（来源未确认）"
                }
                appendLine(); appendLine("**$label**")
                appendLine(); note.quote.lineSequence().forEach { appendLine("> ${escape(it)}") }
            }
            if (note.comment.isNotBlank()) { appendLine(); appendLine("**个人评论**"); appendLine(); appendLine(escape(note.comment)) }
        }
        appendLine(); appendLine("## 文化背景资料")
        if (library.backgroundRecordErrors > 0) {
            appendLine(); appendLine("有 ${library.backgroundRecordErrors} 条背景知识项无法读取，未包含在本文件中；原记录保留。")
        }
        if (library.backgroundRecords.isEmpty()) appendLine("暂无保存的背景资料。")
        library.backgroundRecords.distinctBy { it.id }
            .sortedWith(compareByDescending<BackgroundRecord> { it.createdAt }.thenBy { it.id })
            .forEach { appendBackground(it) }
        appendLine(); appendLine("## AI 表达整理")
        if (library.reviews.isEmpty()) appendLine("暂无 AI 整理。")
        library.reviews.sortedByDescending { it.createdAt }.forEach { review ->
            appendLine(); appendLine("### ${escape(review.title)}"); appendLine(); appendLine(escape(review.evidence))
            appendLine(); appendLine(escape(review.text))
        }
    }

    private fun StringBuilder.appendBackground(record: BackgroundRecord) {
        BackgroundRecords.validate(record)
        val query = record.lookup.query
        val candidate = record.lookup.candidates.single { it.id == record.selectedCandidateId }
        val sources = BackgroundResearch.selectedSources(record.lookup, record.selectedCandidateId)
        appendLine(); appendLine("### ${escape(candidate.title)}")
        appendLine(); appendLine("检索词：${escape(query.term)}")
        appendLine(); appendLine("保存时间：${date(record.createdAt)}；更新：${date(record.updatedAt)}")
        appendLine(); appendLine("原文章出处：${escape(query.source.display())}")
        query.source.url?.takeIf { it.isNotBlank() }?.let { appendLine(); appendLine("原文章地址：${escape(it)}") }
        if (query.context.isNotBlank()) {
            appendLine(); appendLine("**检索时语境**")
            appendLine(); query.context.lineSequence().forEach { appendLine("> ${escape(it)}") }
        }
        appendLine(); appendLine("**选定含义**")
        appendLine(); appendLine(escape(candidate.title))
        if (candidate.description.isNotBlank()) { appendLine(); appendLine(escape(candidate.description)) }
        record.explanation?.let { explanation ->
            appendLine(); appendLine("**AI 背景解释**")
            appendLine(); appendLine(escape(explanation.title))
            appendLine(); appendLine(escape(explanation.text))
            appendLine(); appendLine("模型：${escape(explanation.model)}；生成时间：${date(explanation.createdAt)}")
            appendLine(); appendLine(escape(explanation.evidence))
            appendLine(); appendLine("引用编号（对应下方本条资料）：${explanation.citedSourceIds.joinToString("、") { escape(it) }}")
        }
        appendLine(); appendLine("**保存的资料引用**")
        sources.forEach { source ->
            appendLine(); appendLine("#### ${escape(source.id)} · ${escape(source.title)}")
            appendLine(); appendLine("来源：${escape(source.provider)}")
            appendLine(); appendLine("地址：${escape(source.url)}")
            appendLine(); appendLine("检索时间：${date(source.retrievedAt)}")
            appendLine(); appendLine("许可：${escape(source.license)}")
            source.revisionId?.let { appendLine(); appendLine("资料版本：${escape(it)}") }
            source.contentHash?.let { appendLine(); appendLine("内容摘要值：${escape(it)}") }
            appendLine(); source.excerpt.lineSequence().forEach { appendLine("> ${escape(it)}") }
        }
        if (record.comment.isNotBlank()) { appendLine(); appendLine("**个人评论**"); appendLine(); appendLine(escape(record.comment)) }
        if (record.tags.isNotEmpty()) { appendLine(); appendLine("标签：${record.tags.joinToString(" · ") { escape(it) }}") }
        appendLine(); appendLine("快照标识：${escape(record.lookup.id)}；候选：${escape(record.selectedCandidateId)}；修订：${record.revision}")
    }

    private fun date(value: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochMilli(value)) + "（上海）"

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("([\\\\`*_{}\\[\\]()#+!|])")) { "\\" + it.value }
}
