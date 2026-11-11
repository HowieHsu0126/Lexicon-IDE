package dev.lexicon.domain

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class SkimNavigationTest {
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun body(text: String) = ArticleBody("article", text, hash(text), 1, 1_000, "Synthetic local passage")
    private fun paragraphs(count: Int) = body((0 until count).joinToString("\n\n") {
        "Paragraph $it records the original evidence."
    })
    private fun study(body: ArticleBody, scope: StudyScope? = null): ArticleStudy {
        val spans = ParagraphFocus.paragraphs(body.text)
        val indices = scope?.let { it.firstParagraph until it.endParagraphExclusive } ?: spans.indices
        val sentence = ReadingSkimPlanner.sentences(body.text).first { it.paragraphIndex == indices.first() }
        return ArticleStudy(body.articleId, body.contentHash, "An overview of the selected passage.", "B2",
            listOf(MindMapNode("root", null, "Evidence")), emptyList(),
            indices.map { StudyParagraph(it, "evidence", "A short AI description of paragraph $it.") },
            listOf(ReadingQuestion("question", "What does the paragraph record?", "It records the evidence.",
                listOf(indices.first()), listOf(sentence.evidenceRange()))),
            emptyList(), "synthetic-fixture", 10, schemaVersion = 2, scope = scope)
    }
    private fun range(unit: ReadingSentenceUnit, questions: List<String> = emptyList()) =
        SkimSentenceRange(unit.paragraphIndex, unit.start, unit.endExclusive, unit.wordCount, questions)

    @Test fun everyPlannedParagraphIsReachableAcrossEightRowPagesInOriginalOrder() {
        val body = paragraphs(35)
        val plan = ReadingSkimPlanner.plan(body.text, 5)
        val directory = SkimNavigation.build(body, plan)
        assertEquals(35, directory.totalParagraphs)
        assertEquals(plan.paragraphIndices, directory.entries.map { it.paragraphIndex })
        val pages = (0..4).map { SkimNavigation.page(directory, it) }
        assertEquals(listOf(8, 8, 8, 8, 3), pages.map { it.entries.size })
        assertTrue(pages.all { it.pageCount == 5 && it.totalEntries == 35 })
        assertEquals(directory.entries, pages.flatMap { it.entries })
        assertEquals(35, pages.flatMap { it.entries }.map { it.key }.distinct().size)
        assertFalse(pages.first().hasPrevious)
        assertTrue(pages.first().hasNext)
        assertTrue(pages.last().hasPrevious)
        assertFalse(pages.last().hasNext)
        assertEquals(body.text, paragraphs(35).text)
    }

    @Test fun partialStudyCannotHideSelectedParagraphsOutsideItsScope() {
        val body = paragraphs(20)
        val scope = ReadingStudyScopes.create(body, 7, 10)
        val study = study(body, scope)
        ReadingStudyScopes.validateStudy(study, body)
        val plan = ReadingSkimPlanner.plan(body.text, 5, study)
        val directory = SkimNavigation.build(body, plan, study)
        assertEquals((0 until 20).toList(), directory.entries.map { it.paragraphIndex })
        assertEquals(listOf(7, 8, 9), directory.entries.filter { it.annotation != null }.map { it.paragraphIndex })
        assertEquals(study.paragraphs, directory.entries.mapNotNull { it.annotation })
        directory.entries.forEach { entry ->
            val span = ParagraphFocus.paragraphs(body.text)[entry.paragraphIndex]
            assertEquals(span.start, entry.start)
            assertEquals(span.endExclusive, entry.endExclusive)
        }
    }

    @Test fun absentOrMismatchedStudyProvidesRawRowsWithoutInventingRoles() {
        val body = paragraphs(3)
        val valid = study(body)
        val plan = ReadingSkimPlanner.plan(body.text, 5)
        val unrelated = listOf(null, valid.copy(articleId = "different-article"), valid.copy(bodyHash = hash("different text")),
            valid.copy(paragraphs = valid.paragraphs.map { it.copy(role = "unknown") }))
        unrelated.forEach { candidate ->
            val directory = SkimNavigation.build(body, plan, candidate)
            assertEquals(3, directory.entries.size)
            assertTrue(directory.entries.all { it.annotation == null })
        }
    }

    @Test fun rewriteFlagExcludesOriginalAnnotationsEvenIfTheTextHappensToMatch() {
        val body = paragraphs(3)
        val study = study(body)
        val plan = ReadingSkimPlanner.plan(body.text, 5, study)
        val original = SkimNavigation.build(body, plan, study)
        val rewrite = SkimNavigation.build(body, plan, study, original = false)
        assertTrue(original.entries.all { it.annotation != null })
        assertTrue(rewrite.entries.all { it.annotation == null })
        assertNotEquals(original.identity, rewrite.identity)
        assertEquals(original.entries.map { it.start to it.endExclusive }, rewrite.entries.map { it.start to it.endExclusive })
    }

    @Test fun changedRenderedTextGetsItsOwnParagraphRangesAndIdentity() {
        val original = paragraphs(4)
        val rewritten = body("A simpler first paragraph.\n\nA different second paragraph.")
        val originalDirectory = SkimNavigation.build(original, ReadingSkimPlanner.plan(original.text, 5), study(original))
        val rewrite = SkimNavigation.build(rewritten, ReadingSkimPlanner.plan(rewritten.text, 5), study(original), original = false)
        assertEquals(2, rewrite.totalParagraphs)
        assertTrue(rewrite.entries.all { it.annotation == null })
        assertEquals("A different second paragraph.", rewritten.text.substring(rewrite.entries.last().start, rewrite.entries.last().endExclusive))
        assertNotEquals(originalDirectory.identity, rewrite.identity)
        assertTrue(originalDirectory.entries.map { it.key }.intersect(rewrite.entries.map { it.key }.toSet()).isEmpty())
    }

    @Test fun studyRefreshChangesDirectoryIdentityButNotOriginalParagraphKeys() {
        val body = paragraphs(3)
        val study = study(body)
        val updated = study.copy(paragraphs = study.paragraphs.map { it.copy(summary = "A refreshed explanation.") })
        val plan = ReadingSkimPlanner.plan(body.text, 5)
        val before = SkimNavigation.build(body, plan, study)
        val after = SkimNavigation.build(body, plan, updated)
        assertNotEquals(before.identity, after.identity)
        assertEquals(before.entries.map { it.key }, after.entries.map { it.key })
        assertTrue(after.entries.all { it.annotation?.summary == "A refreshed explanation." })
    }

    @Test fun directoryOnlyIncludesParagraphsWithAnExactSelectedSentence() {
        val body = paragraphs(4)
        val plan = ReadingSkimPlanner.plan(body.text, 5)
        val first = plan.sentenceRanges.first()
        val third = plan.sentenceRanges[2]
        val altered = plan.copy(paragraphIndices = listOf(3, 2, 0, 0, 99, -1), sentenceRanges = listOf(
            third, first, third,
            plan.sentenceRanges[1], // A valid sentence, but its paragraph is not selected by this plan.
            plan.sentenceRanges[3].copy(start = plan.sentenceRanges[3].start + 1),
            plan.sentenceRanges[3].copy(wordCount = 999),
            first.copy(paragraphIndex = 99), first.copy(start = Int.MIN_VALUE), first.copy(endExclusive = Int.MAX_VALUE),
        ))
        val directory = SkimNavigation.build(body, altered)
        assertEquals(listOf(0, 2), directory.entries.map { it.paragraphIndex })
        assertEquals(listOf(first, third), directory.entries.flatMap { it.sentenceRanges })
        assertEquals(4, directory.totalParagraphs)
    }

    @Test fun aListedParagraphWithOnlyPartialSentenceOrWrongWordCountHasNoRow() {
        val body = paragraphs(1)
        val plan = ReadingSkimPlanner.plan(body.text, 5)
        val sentence = plan.sentenceRanges.single()
        listOf(sentence.copy(endExclusive = sentence.endExclusive - 1), sentence.copy(wordCount = sentence.wordCount - 1)).forEach {
            assertTrue(SkimNavigation.build(body, plan.copy(sentenceRanges = listOf(it))).entries.isEmpty())
        }
        assertTrue(SkimNavigation.build(body, plan.copy(sentenceRanges = emptyList())).entries.isEmpty())
    }

    @Test fun conflictingDuplicateEvidenceIsNotResolvedByInventingAQuestionSelection() {
        val body = paragraphs(1)
        val plan = ReadingSkimPlanner.plan(body.text, 5)
        val one = plan.sentenceRanges.single().copy(questionIds = listOf("one"))
        val two = one.copy(questionIds = listOf("two"))
        assertTrue(SkimNavigation.build(body, plan.copy(sentenceRanges = listOf(one, two))).entries.isEmpty())
        assertEquals(listOf(one), SkimNavigation.build(body, plan.copy(sentenceRanges = listOf(one, one))).entries.single().sentenceRanges)
    }

    @Test fun sharedQuestionEvidenceKeepsItsExactRangesAndDoesNotDoubleCountRows() {
        val body = paragraphs(3)
        val initial = study(body)
        val first = initial.questions.single()
        val study = initial.copy(questions = listOf(first, first.copy(id = "second", question = "Which evidence is described?")))
        val plan = ReadingSkimPlanner.plan(body.text, 5, study, selectedQuestions = study.questions)
        val directory = SkimNavigation.build(body, plan, study)
        assertEquals(plan.sentenceRanges, directory.entries.flatMap { it.sentenceRanges })
        assertEquals(listOf("question", "second"), directory.entries.first().sentenceRanges.single().questionIds)
        assertEquals(plan.wordsSelected, directory.entries.flatMap { it.sentenceRanges }.sumOf { it.wordCount })
        assertEquals(3, directory.entries.size)
    }

    @Test fun legacyParagraphHintsNeverBecomeQuestionEvidenceInTheDirectory() {
        val body = paragraphs(3)
        val initial = study(body)
        val study = initial.copy(schemaVersion = 1, questions = initial.questions.map { it.copy(evidence = emptyList()) })
        val plan = ReadingSkimPlanner.plan(body.text, 5, study, selectedQuestions = study.questions)
        val directory = SkimNavigation.build(body, plan, study)
        assertEquals(SkimQuestionStatus.PARAGRAPH_ONLY, plan.questionCoverage.single().status)
        assertTrue(directory.entries.flatMap { it.sentenceRanges }.all { it.questionIds.isEmpty() })
        assertEquals(listOf("question"), plan.selectedQuestionIds)
    }

    @Test fun originalOffsetsDistinguishRepeatedUnicodeSentencesAcrossParagraphs() {
        val text = "🧭 The evidence matters.\r\n\r\n  Dr. Lee agrees. 🧭 The evidence matters.\r\n\r\nA final point."
        val body = body(text)
        val units = ReadingSkimPlanner.sentences(text)
        val selected = units.last { text.substring(it.start, it.endExclusive).contains("evidence") }
        val sentence = range(selected, listOf("chosen"))
        val plan = ReadingSkimPlanner.plan(text, 5).copy(paragraphIndices = listOf(selected.paragraphIndex), sentenceRanges = listOf(sentence))
        val directory = SkimNavigation.build(body, plan)
        val actual = directory.entries.single().sentenceRanges.single()
        assertEquals(sentence, actual)
        assertTrue(actual.start > text.indexOf("evidence"))
        assertEquals(text.substring(selected.start, selected.endExclusive), text.substring(actual.start, actual.endExclusive))
        assertEquals(1, directory.entries.single().paragraphIndex)
    }

    @Test fun initialPageUsesNearestSelectedParagraphAndEarlierTieWithoutOverflow() {
        val body = paragraphs(40)
        val plan = ReadingSkimPlanner.plan(body.text, 5)
        val odds = (1 until 36 step 2).toList()
        val directory = SkimNavigation.build(body, plan.copy(paragraphIndices = odds))
        assertEquals(0, SkimNavigation.initialPage(directory, 16)) // 15 and 17 are equally near; 15 ends page one.
        assertEquals(1, SkimNavigation.initialPage(directory, 17))
        assertEquals(2, SkimNavigation.initialPage(directory, 35))
        assertEquals(0, SkimNavigation.initialPage(directory, Int.MIN_VALUE))
        assertEquals(2, SkimNavigation.initialPage(directory, Int.MAX_VALUE))
    }

    @Test fun pageRequestsClampAfterThePlanShrinks() {
        val body = paragraphs(35)
        val plan = ReadingSkimPlanner.plan(body.text, 5)
        val directory = SkimNavigation.build(body, plan)
        assertEquals(0, SkimNavigation.page(directory, Int.MIN_VALUE).index)
        assertEquals(4, SkimNavigation.page(directory, Int.MAX_VALUE).index)
        val filtered = SkimNavigation.build(body, plan.copy(paragraphIndices = listOf(2, 10, 21)))
        val clamped = SkimNavigation.page(filtered, 4)
        assertEquals(0, clamped.index)
        assertEquals(1, clamped.pageCount)
        assertEquals(listOf(2, 10, 21), clamped.entries.map { it.paragraphIndex })
    }

    @Test fun anEmptyDirectoryHasNoInventedPageOrParagraph() {
        val body = body("")
        val directory = SkimNavigation.build(body, ReadingSkimPlanner.plan(body.text, 5))
        assertEquals(0, directory.totalParagraphs)
        assertEquals(0, SkimNavigation.initialPage(directory, 500))
        val page = SkimNavigation.page(directory, 500)
        assertEquals(0, page.index)
        assertEquals(0, page.pageCount)
        assertEquals(0, page.totalEntries)
        assertTrue(page.entries.isEmpty())
        assertFalse(page.hasPrevious)
        assertFalse(page.hasNext)
    }

    @Test fun invalidBodyIdentityIsRejectedBeforeExposingNavigationRanges() {
        val body = paragraphs(3)
        val plan = ReadingSkimPlanner.plan(body.text, 5)
        assertThrows(IllegalArgumentException::class.java) { SkimNavigation.build(body.copy(contentHash = hash("different")), plan) }
        assertThrows(IllegalArgumentException::class.java) { SkimNavigation.build(body.copy(articleId = ""), plan) }
    }

    @Test fun timeBudgetAndUnresolvedQuestionsParticipateInPresentationIdentity() {
        val body = paragraphs(3)
        val plan = ReadingSkimPlanner.plan(body.text, 5)
        val baseline = SkimNavigation.build(body, plan)
        val changedBudget = SkimNavigation.build(body, ReadingSkimPlanner.plan(body.text, 10))
        assertEquals(baseline.entries, changedBudget.entries)
        assertNotEquals(baseline.identity, changedBudget.identity)
        val unresolved = SkimQuestionCoverage("unresolved", emptyList(), emptyList(), listOf(1), SkimQuestionStatus.PARAGRAPH_ONLY)
        val selected = SkimNavigation.build(body, plan.copy(questionCoverage = listOf(unresolved)))
        assertEquals(baseline.entries, selected.entries)
        assertNotEquals(baseline.identity, selected.identity)
        assertEquals(baseline.identity, SkimNavigation.build(body, plan).identity)
    }

    @Test fun changingTheSelectedSentenceWithinOneParagraphInvalidatesThePageIdentity() {
        val body = body("The first sentence states the claim. The second sentence adds evidence.")
        val units = ReadingSkimPlanner.sentences(body.text)
        assertEquals(2, units.size)
        val plan = ReadingSkimPlanner.plan(body.text, 5)
        val first = SkimNavigation.build(body, plan.copy(sentenceRanges = listOf(range(units.first()))))
        val second = SkimNavigation.build(body, plan.copy(sentenceRanges = listOf(range(units.last()))))
        assertEquals(first.entries.single().key, second.entries.single().key)
        assertNotEquals(first.identity, second.identity)
    }
}
