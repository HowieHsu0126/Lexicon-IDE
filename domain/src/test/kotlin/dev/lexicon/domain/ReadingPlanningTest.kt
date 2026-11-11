package dev.lexicon.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ReadingPlanningTest {
    private val now = Instant.parse("2026-10-29T12:00:00Z").toEpochMilli()
    private fun article(id: String, host: String, date: Long = now - 1000, read: Boolean = false) = Article(id, id, listOf(id), "Title $id", "https://$host/$id", "Summary", publishedAt = date, read = read)
    private fun sources(articles: List<Article>) = articles.map { Source(it.id, it.id, it.url, enabled = true) }
    @Test fun sourcesMustArriveBeforeTheFirstEditionIsFrozen() {
        val candidates = listOf(article("a", "ft.com"), article("b", "economist.com"))
        assertTrue(ReadingDailyPlanner.extend(null, candidates, emptyList(), now).articleIds.isEmpty())
        val available = listOf(Source("a", "FT", "https://ft.com/rss", enabled = true), Source("b", "Economist", "https://economist.com/rss", enabled = false))
        assertEquals(listOf("a"), ReadingDailyPlanner.extend(null, candidates, available, now).articleIds)
    }
    @Test fun dailyChoiceHasThreePublishersAndRemainsFixedAfterNewSync() {
        val articles = listOf(article("e1", "www.economist.com"), article("e2", "economist.com", now - 10),
            article("b", "www.bbc.com"), article("g", "www.theguardian.com"), article("n", "nature.com", now - 9999))
        val edition = ReadingDailyPlanner.extend(null, articles, sources(articles), now)
        assertEquals(3, edition.articleIds.size)
        assertEquals(3, edition.publisherKeys.distinct().size)
        assertEquals(1, edition.articleIds.count { it.startsWith("e") })
        val refreshedArticles = listOf(article("new", "ft.com", now)) + articles
        val refreshed = ReadingDailyPlanner.extend(edition, refreshedArticles, sources(refreshedArticles), now + 100)
        assertEquals(edition, refreshed)
    }
    @Test fun incompleteEditionFillsWithoutReplacingAndBbcHostsSharePublisher() {
        val firstArticles = listOf(article("b1", "bbc.co.uk"))
        val first = ReadingDailyPlanner.extend(null, firstArticles, sources(firstArticles), now)
        val candidates = listOf(article("b2", "bbc.com"), article("e", "economist.com"), article("g", "theguardian.com"))
        val next = ReadingDailyPlanner.extend(first, candidates, sources(candidates), now)
        assertEquals("b1", next.articleIds.first())
        assertEquals(3, next.articleIds.size)
        assertFalse(next.articleIds.contains("b2"))
        assertTrue(ReadingDailyPlanner.extend(null, emptyList(), emptyList(), now).articleIds.isEmpty())
    }
    @Test fun timezoneBoundaryAndPublisherFairnessAreDeterministic() {
        val instant = Instant.parse("2026-10-29T16:01:00Z").toEpochMilli()
        assertEquals("2026-10-30", ReadingDailyPlanner.extend(null, emptyList(), emptyList(), instant, "Asia/Shanghai").day)
        assertEquals("2026-10-29", ReadingDailyPlanner.extend(null, emptyList(), emptyList(), instant, "UTC").day)
        val candidates = listOf(article("busy", "economist.com"), article("a", "ft.com"), article("b", "nature.com"), article("c", "wired.com"))
        val history = listOf(DailyEdition("2026-10-28", listOf("old"), listOf("economist.com")))
        val selected = ReadingDailyPlanner.extend(null, candidates, sources(candidates), now, history = history)
        assertFalse(selected.articleIds.contains("busy"))
        assertEquals(selected, ReadingDailyPlanner.extend(null, candidates.reversed(), sources(candidates), now, history = history))
    }
    @Test fun skimPreservesWholeParagraphsAndQuestionTargets() {
        val text = (0..5).joinToString("\n\n") { i -> "Paragraph$i " + "word ".repeat(199) }
        val question = ReadingQuestion("q", "Question", "Answer", listOf(3))
        val plan = ReadingSkimPlanner.plan(text, 5, question = question)
        assertTrue(3 in plan.paragraphIndices)
        assertEquals(800, plan.wordBudget)
        assertEquals(800, plan.wordsSelected)
        assertFalse(plan.exceedsBudget)
        val long = ReadingSkimPlanner.plan("word ".repeat(900), 5)
        assertEquals(listOf(0), long.paragraphIndices)
        assertTrue(long.exceedsBudget)
        assertEquals(900, long.wordsSelected)
    }
    @Test fun skimRoleFilterUsesOnlyMatchingOriginalParagraphs() {
        val text = (0..5).joinToString("\n\n") { i -> "Paragraph$i " + "word ".repeat(199) }
        val study = ArticleStudy("a", "hash", "Summary", "B2", emptyList(), emptyList(),
            (0..5).map { StudyParagraph(it, if (it == 2 || it == 4) "evidence" else "claim", "Summary") }, emptyList(), emptyList(), "test", 0)
        val plan = ReadingSkimPlanner.plan(text, 5, study, roles = setOf("evidence"))
        assertEquals(listOf(2, 4), plan.paragraphIndices)
        assertEquals(400, plan.wordsSelected)
        assertTrue(ReadingSkimPlanner.plan(text, 5, roles = setOf("evidence")).paragraphIndices.isEmpty())
    }

    @Test fun progressDeduplicatesRecordsAndKeepsScoresAsLearningFeedback() {
        val session = ReadingSession("s", "a", StudyMode.INTENSIVE, "Science", now - 120000, now, 120000, 200, 1, 1, 0, true)
        val check = ComprehensionCheck("c", "a", "Science", "q", "Answer", .75, "AI", now)
        val progress = ReadingProgressCalculator.summarize(ReadingLibrary(sessions = listOf(session, session), checks = listOf(check, check)))
        assertEquals(1, progress.completed); assertEquals(200, progress.wordsRead)
        assertEquals(100.0, progress.wordsPerMinute!!, .001); assertEquals(75.0, progress.comprehensionPercent!!, .001)
        assertEquals(13, progress.growthPoints)
        assertTrue(progress.badges.single { it.id == "first-reading" }.achieved)
        assertFalse(progress.badges.single { it.id == "seven-days" }.achieved)
        assertNull(ReadingProgressCalculator.summarize(ReadingLibrary()).wordsPerMinute)
    }
    @Test fun syndicatedArticleOccupiesAllPublishersAcrossLaterFills() {
        val shared = article("shared", "ft.com", now - 1).copy(sourceIds = listOf("ft", "econ-disabled"))
        val economist = article("econ", "economist.com", now - 10)
        val wired = article("wired", "wired.com", now - 20)
        val nature = article("nature", "nature.com", now - 30)
        val catalog = listOf(Source("ft", "FT", "https://ft.com/rss", enabled = true),
            Source("econ-disabled", "The Economist · Business", "https://economist.com/business/rss.xml", enabled = false)) + sources(listOf(economist, wired, nature))
        val first = ReadingDailyPlanner.extend(null, listOf(shared), catalog, now)
        assertEquals(setOf("ft.com", "economist.com"), first.publisherKeys.toSet())
        val filled = ReadingDailyPlanner.extend(first, listOf(economist, wired, nature), catalog, now)
        assertEquals(listOf("shared", "wired", "nature"), filled.articleIds)
        assertEquals(4, filled.publisherKeys.size)
        assertFalse(filled.articleIds.contains("econ"))
        assertEquals(filled, ReadingDailyPlanner.extend(filled, emptyList(), emptyList(), now))
    }

    @Test fun alternateArticleCanFillAfterItsNewerCrossPublisherCandidateCollides() {
        val frozen = DailyEdition("2026-10-29", listOf("existing-econ"), listOf("economist.com"))
        val cross = article("cross", "ft.com", now - 1).copy(sourceIds = listOf("ft", "econ"))
        val older = article("older", "ft.com", now - 100).copy(sourceIds = listOf("ft"))
        val catalog = listOf(Source("ft", "FT", "https://ft.com/rss", enabled = true), Source("econ", "Economist", "https://economist.com/rss", enabled = true))
        val filled = ReadingDailyPlanner.extend(frozen, listOf(cross, older), catalog, now)
        assertEquals(listOf("existing-econ", "older"), filled.articleIds)
    }

    @Test fun missingSourceMetadataCannotHideAnotherPublisherAndAliasesStayStable() {
        val item = article("x", "bbc.com").copy(sourceIds = listOf("bbc", "second"))
        val known = Source("bbc", "BBC Science", "https://feeds.bbci.co.uk/science/rss.xml", enabled = true)
        assertTrue(ReadingDailyPlanner.extend(null, listOf(item), listOf(known), now).articleIds.isEmpty())
        assertEquals(setOf("bbc"), ReadingDailyPlanner.publisherKeys(item.copy(sourceIds = listOf("bbc")), listOf(known)))
        val oldLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            assertEquals("wired.com", ReadingDailyPlanner.publisherKey(article("w", "WIRED.com"), emptyList()))
            val fallback = item.copy(url = "not-a-url", sourceIds = emptyList(), sourceName = "INDEPENDENT · News")
            assertEquals("independent", ReadingDailyPlanner.publisherKey(fallback, emptyList()))
        } finally { java.util.Locale.setDefault(oldLocale) }
    }

    @Test fun newSourceAliasesExpandAnIncompleteEditionWithoutReplacingItsArticle() {
        val original = article("a", "ft.com").copy(sourceIds = listOf("ft"))
        val catalog = listOf(Source("ft", "FT", "https://ft.com/rss", enabled = true), Source("econ", "Economist", "https://economist.com/rss", enabled = true))
        val first = ReadingDailyPlanner.extend(null, listOf(original), catalog, now)
        val changed = original.copy(sourceIds = listOf("ft", "econ"))
        val other = article("other", "economist.com").copy(sourceIds = listOf("econ"))
        val refreshed = ReadingDailyPlanner.extend(first, listOf(changed, other), catalog, now)
        assertEquals(listOf("a"), refreshed.articleIds)
        assertEquals(setOf("ft.com", "economist.com"), refreshed.publisherKeys.toSet())
    }

    @Test fun sentenceRangesFitBudgetInsideALongParagraphAndPreserveRepeatedUnicodeText() {
        val sentence = "😀 Repeated " + "word ".repeat(98) + "ends."
        val text = (1..12).joinToString(" ") { sentence }
        val plan = ReadingSkimPlanner.plan(text, 5)
        assertEquals(listOf(0), plan.paragraphIndices)
        assertEquals(800, plan.wordsSelected)
        assertEquals(1200, plan.paragraphWordsSelected)
        assertEquals(8, plan.sentenceRanges.size)
        assertEquals(8, plan.sentenceRanges.map { it.start }.distinct().size)
        assertFalse(plan.exceedsBudget)
        plan.sentenceRanges.forEach { range ->
            assertEquals(sentence, text.substring(range.start, range.endExclusive))
            assertEquals(100, range.wordCount)
            assertFalse(range.start > 0 && text[range.start - 1].isHighSurrogate() && text[range.start].isLowSurrogate())
        }
        assertTrue(plan.sentenceRanges.zipWithNext().all { (left, right) -> left.endExclusive <= right.start })
    }

    @Test fun shorterAvailableSentenceAvoidsOverrunAndHonorificsStayWithTheirSentence() {
        val text = "word ".repeat(900) + "ends.\n\nDr. Fox measured 3.14 units. Another result followed."
        val plan = ReadingSkimPlanner.plan(text, 5)
        assertFalse(plan.exceedsBudget)
        assertEquals(listOf(1), plan.paragraphIndices)
        assertEquals("Dr. Fox measured 3.14 units.", text.substring(plan.sentenceRanges.first().start, plan.sentenceRanges.first().endExclusive))
        assertTrue(plan.wordsSelected <= plan.wordBudget)
        assertEquals(3, ReadingSkimPlanner.countWords("word—word can't"))
    }

    @Test fun shortOrZeroTimeVisitsDoNotInflateSpeedAndNoDataIsNotACorrectnessScore() {
        val normal = ReadingSession("normal", "a", StudyMode.INTENSIVE, null, now - 60000, now, 60000, 120, 1, 1, 0, true)
        val zero = normal.copy(id = "zero", startedAt = now, activeMillis = 0, wordsRead = 600)
        val brief = normal.copy(id = "brief", startedAt = now - 10000, activeMillis = 10000, wordsRead = 200, completed = false)
        val impossible = normal.copy(id = "bad", activeMillis = 60001)
        val progress = ReadingProgressCalculator.summarize(ReadingLibrary(sessions = listOf(normal, zero, brief, impossible)))
        assertEquals(120.0, progress.wordsPerMinute!!, .001)
        assertEquals(1, progress.speedSampleCount)
        assertEquals(320, progress.wordsRead)
        assertEquals(1, progress.completed)
        assertEquals(10, progress.growthPoints)
        assertNull(progress.comprehensionPercent)
        assertNull(ReadingProgressCalculator.summarize(ReadingLibrary(sessions = listOf(brief))).wordsPerMinute)
    }

    @Test fun progressMergesSessionSnapshotsAndUsesLatestQuestionFeedbackWithoutExtraPoints() {
        val full = ReadingSession("s", "a", StudyMode.INTENSIVE, "Science", now - 120000, now, 120000, 200, 2, 2, 1, true)
        val partial = full.copy(finishedAt = now - 60000, activeMillis = 30000, wordsRead = 50, paragraphsRead = 1, completed = false)
        val first = ComprehensionCheck("c1", "a", "Science", "q", "First answer", .2, "AI", now - 1000)
        val latest = first.copy(id = "c2", answer = "New answer", score = .8, createdAt = now)
        val invalid = latest.copy(id = "c3", questionId = "bad", score = Double.NaN)
        val library = ReadingLibrary(sessions = listOf(partial, full, partial), checks = listOf(first, latest, latest, invalid))
        val progress = ReadingProgressCalculator.summarize(library)
        assertEquals(1, progress.completed)
        assertEquals(200, progress.wordsRead)
        assertEquals(100.0, progress.wordsPerMinute!!, .001)
        assertEquals(80.0, progress.comprehensionPercent!!, .001)
        assertEquals(1, progress.evaluatedQuestions)
        assertEquals(2, progress.practiceAttempts)
        assertEquals(13, progress.growthPoints)
        assertEquals(progress, ReadingProgressCalculator.summarize(library.copy(sessions = library.sessions.reversed(), checks = library.checks.reversed())))
    }

}
