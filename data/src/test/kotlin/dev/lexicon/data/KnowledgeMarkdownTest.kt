package dev.lexicon.data

import dev.lexicon.domain.*
import org.junit.Assert.*
import org.junit.Test

class KnowledgeMarkdownTest {
    @Test fun exportedSnapshotSeparatesEvidenceAndEscapesActiveMarkup() {
        val note = ReadingNote("n", null, null, "Exact <quote>\n![tracking](https://invalid/x)",
            "<script>alert(1)</script>\n# injected heading", listOf("Science"), 0,
            SourceRef("A title", "Publisher", "https://example.org/original"))
        val review = StudyAssist("review", "AI summary", "**Practice** from notes", "test", 1, "AI example")
        val text = KnowledgeMarkdown.render(ReadingLibrary(notes = listOf(note, note), reviews = listOf(review)))
        assertTrue(text.contains("单向内容快照")); assertTrue(text.contains("保存的原文片段")); assertTrue(text.contains("个人评论")); assertTrue(text.contains("AI 表达整理"))
        assertTrue(text.contains("&lt;quote&gt;")); assertFalse(text.contains("<script>")); assertFalse(text.contains("![tracking]"))
        assertTrue(text.contains("\\# injected heading"))
        assertEquals(1, Regex("### 1\\.").findAll(text).count())
        assertTrue(text.contains("https://example.org/original"))
    }
    @Test fun aiRewrittenNotesAreNeverExportedAsOriginalQuotes() {
        val note = ReadingNote("ai", "article", null, "AI learning adaptation.", "", emptyList(), 0, origin = ContextOrigin.AI_GENERATED)
        val text = KnowledgeMarkdown.render(ReadingLibrary(notes = listOf(note)))
        assertTrue(text.contains("AI 改写片段（不是原文引用）"))
        assertFalse(text.contains("**保存的原文片段**"))
    }
    @Test fun emptyKnowledgeExportIsReadableWithoutInventedNotes() {
        val text = KnowledgeMarkdown.render(ReadingLibrary())
        assertTrue(text.contains("暂无笔记。")); assertTrue(text.contains("暂无 AI 整理。"))
    }

    private fun background(explanation: Boolean = true): BackgroundRecord {
        val query = BackgroundQuery("bank", "Self-authored river context, not an offset-verified original.",
            source = SourceRef("Synthetic article", "Fixture publisher", "https://example.org/article", 100))
        val sources = (1..2).map { index -> BackgroundSource("S$index", "Fixture meaning $index",
            "https://en.wikipedia.org/w/index.php?curid=$index&oldid=${index + 100}",
            "Synthetic excerpt $index. This fixture includes <tag> and ![image](https://invalid/tracker) to verify that saved evidence cannot create active markup in an export.",
            100, "Wikipedia (en)", (index + 100).toString()) }
        val lookup = BackgroundResearch.createLookup(query,
            listOf(BackgroundCandidate("first", "Unselected meaning", "Other context", listOf("S1")),
                BackgroundCandidate("chosen", "Selected meaning", "A chosen concept", listOf("S2"))),
            sources, listOf(BackgroundProviderOutcome("Wikipedia (en)", "retrieved")), "retrieved", 100)
        val selected = BackgroundResearch.selectedSources(lookup, "chosen")
        val ai = if (explanation) StudyAssist("background", "AI concept explanation", "Saved explanation with evidence. [S1]",
            "fixture-model", 200, "External evidence with an AI explanation.", sources = selected,
            retrievalStatus = "retrieved", citedSourceIds = listOf("S1")) else null
        return BackgroundRecords.create(lookup, "chosen", 300, ai)
    }

    @Test fun backgroundExportKeepsContextMeaningEvidenceAndAiSeparateFromPersonalComment() {
        val record = BackgroundRecords.updateMetadata(background(), 1, "My own comment", listOf("culture"), 400)
        val text = KnowledgeMarkdown.render(ReadingLibrary(backgroundRecords = listOf(record)))
        assertTrue(text.contains("检索时语境")); assertTrue(text.contains(record.lookup.query.context))
        assertTrue(text.contains("Selected meaning")); assertFalse(text.contains("Unselected meaning"))
        assertTrue(text.contains("原文章出处")); assertTrue(text.contains("https://example.org/article"))
        assertTrue(text.contains("AI 背景解释")); assertTrue(text.contains("fixture-model"))
        assertTrue(text.contains("个人评论")); assertTrue(text.contains("My own comment"))
        val aiSection = text.indexOf("**AI 背景解释**")
        val referencesSection = text.indexOf("**保存的资料引用**")
        val commentSection = text.indexOf("**个人评论**")
        assertTrue(aiSection >= 0 && aiSection < referencesSection && referencesSection < commentSection)
        assertTrue(text.indexOf("Saved explanation with evidence.") in aiSection until referencesSection)
        assertTrue(text.indexOf("My own comment") > commentSection)
        assertTrue(text.contains("curid=2&amp;oldid=102")); assertFalse(text.contains("curid=1"))
        assertTrue(text.contains("#### S1")); assertTrue(text.contains("资料版本：102"))
        assertTrue(text.contains("CC BY-SA 4.0")); assertTrue(text.contains("检索时间"))
        assertTrue(text.contains("&lt;tag&gt;")); assertFalse(text.contains("![image]"))
        assertFalse(text.contains("**保存的原文片段**"))
    }

    @Test fun evidenceOnlyBackgroundDoesNotInventAnAiExplanationOrComment() {
        val record = background(explanation = false)
        val text = KnowledgeMarkdown.render(ReadingLibrary(backgroundRecords = listOf(record, record)))
        assertTrue(text.contains("保存的资料引用")); assertTrue(text.contains("Synthetic excerpt 2"))
        assertFalse(text.contains("**AI 背景解释**")); assertFalse(text.contains("**个人评论**"))
        assertEquals(1, Regex("### Selected meaning").findAll(text).count())
    }

    @Test fun rendererExplicitlyLabelsUnavailableBackgroundRecords() {
        val text = KnowledgeMarkdown.render(ReadingLibrary(backgroundRecordErrors = 2))
        assertTrue(text.contains("有 2 条背景知识项无法读取")); assertTrue(text.contains("未包含在本文件中"))
    }
}
