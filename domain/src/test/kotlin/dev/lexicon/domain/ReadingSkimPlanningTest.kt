package dev.lexicon.domain

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReadingSkimPlanningTest {
    @Test fun sharedEvidenceSentenceMapsToSeveralQuestionsWithoutDuplicateSelection() {
        val text = "Researchers compared the competing explanations. Their observations favored one account."
        val first = question(text, "first", 0)
        val second = question(text, "second", 0)
        val study = study(text, listOf(first, second))
        val plan = ReadingSkimPlanner.plan(text, 5, study, selectedQuestions = listOf(first, second))
        assertEquals(listOf("first", "second"), plan.selectedQuestionIds)
        assertEquals(listOf("first", "second"), plan.coveredQuestionIds)
        assertTrue(plan.unresolvedQuestionIds.isEmpty())
        assertEquals(listOf("first", "second"), plan.sentenceRanges.first().questionIds)
        assertTrue(plan.sentenceRanges[1].questionIds.isEmpty())
        assertEquals(2, plan.sentenceRanges.size)
        assertEquals(ReadingSkimPlanner.countWords(text), plan.wordsSelected)
    }

    @Test fun evidenceIsVisitedOncePerQuestionBeforeReturningForMoreEvidence() {
        val text = (0..3).joinToString("\n\n") { sentence(200) }
        val broad = question(text, "broad", 0, 1, 2)
        val focused = question(text, "focused", 3)
        val plan = ReadingSkimPlanner.plan(text, 5, study(text, listOf(broad, focused)),
            selectedQuestions = listOf(broad, focused), planningWpm = 80)
        assertEquals(400, plan.wordBudget)
        assertEquals(400, plan.wordsSelected)
        assertEquals(listOf(0, 3), plan.paragraphIndices)
        assertEquals(listOf("focused"), plan.coveredQuestionIds)
        assertEquals(listOf("broad"), plan.unresolvedQuestionIds)
        val partial = plan.questionCoverage.first()
        assertEquals(SkimQuestionStatus.PARTIAL, partial.status)
        assertEquals(3, partial.evidenceSentenceIds.size)
        assertEquals(1, partial.selectedSentenceIds.size)
        assertFalse(plan.exceedsBudget)
    }

    @Test fun oneSharedSentenceCanCompleteTwoQuestionsInsideOneBudget() {
        val text = sentence(200) + "\n\n" + sentence(200)
        val first = question(text, "first", 0, 1)
        val second = question(text, "second", 1)
        val plan = ReadingSkimPlanner.plan(text, 5, study(text, listOf(first, second)),
            selectedQuestions = listOf(first, second), planningWpm = 80)
        assertEquals(400, plan.wordsSelected)
        assertEquals(2, plan.sentenceRanges.size)
        assertEquals(listOf("first", "second"), plan.sentenceRanges[1].questionIds)
        assertEquals(listOf("first", "second"), plan.coveredQuestionIds)
    }

    @Test fun rolesRestrictEvidenceWithoutPretendingThatPartialCoverageIsACompleteAnswer() {
        val text = "A background assumption frames this debate.\n\nThe measurements support the central claim."
        val both = question(text, "both", 0, 1)
        val backgroundOnly = question(text, "background", 0)
        val study = study(text, listOf(both, backgroundOnly), listOf("background", "evidence"))
        val plan = ReadingSkimPlanner.plan(text, 5, study, roles = setOf("evidence"), selectedQuestions = study.questions)
        assertEquals(listOf(1), plan.paragraphIndices)
        assertEquals(listOf("both"), plan.sentenceRanges.single().questionIds)
        assertTrue(plan.coveredQuestionIds.isEmpty())
        assertEquals(listOf("both", "background"), plan.unresolvedQuestionIds)
        assertTrue(plan.questionCoverage.all { it.status == SkimQuestionStatus.ROLE_EXCLUDED })
        assertEquals(1, plan.questionCoverage.first().selectedSentenceIds.size)
        assertTrue(plan.questionCoverage.last().selectedSentenceIds.isEmpty())
        val noMatches = ReadingSkimPlanner.plan(text, 5, study, roles = setOf("conclusion"), selectedQuestions = study.questions)
        assertTrue(noMatches.sentenceRanges.isEmpty())
        assertEquals(2, noMatches.unresolvedQuestionIds.size)
        assertTrue(noMatches.questionCoverage.all { it.status == SkimQuestionStatus.ROLE_EXCLUDED })
    }

    @Test fun anAnswerThatDoesNotFitIsReportedByQuestionIdInsteadOfSilentlyOmitted() {
        val text = sentence(600) + "\n\n" + sentence(400)
        val costly = question(text, "costly", 0)
        val shorter = question(text, "shorter", 1)
        val plan = ReadingSkimPlanner.plan(text, 5, study(text, listOf(costly, shorter)),
            selectedQuestions = listOf(costly, shorter), planningWpm = 80)
        assertEquals(listOf(1), plan.paragraphIndices)
        assertEquals(listOf("shorter"), plan.coveredQuestionIds)
        assertEquals(listOf("costly"), plan.unresolvedQuestionIds)
        assertEquals(SkimQuestionStatus.BUDGET_EXCLUDED, plan.questionCoverage.first().status)
        assertEquals(0, plan.budgetOverflowWords)
    }

    @Test fun soleOversizedSentenceIsPreservedWithAnExplicitOverrun() {
        val text = sentence(901)
        val question = question(text, "long", 0)
        val plan = ReadingSkimPlanner.plan(text, 5, study(text, listOf(question)), selectedQuestions = listOf(question))
        assertEquals(text, text.substring(plan.sentenceRanges.single().start, plan.sentenceRanges.single().endExclusive))
        assertTrue(plan.exceedsBudget)
        assertEquals(101, plan.budgetOverflowWords)
        assertEquals(901.0 / 160, plan.estimatedMinutes, .000001)
        assertEquals(listOf("long"), plan.coveredQuestionIds)
        assertTrue(plan.note.contains("超过"))
    }

    @Test fun planningSpeedChangesBothBudgetAndEstimatedMinutesWithinUserBounds() {
        val text = (0..4).joinToString("\n\n") { sentence(200) }
        val slower = ReadingSkimPlanner.plan(text, 5, planningWpm = 80)
        val usual = ReadingSkimPlanner.plan(text, 5)
        val faster = ReadingSkimPlanner.plan(text, 5, planningWpm = 400)
        val longer = ReadingSkimPlanner.plan(text, 10, planningWpm = 80)
        assertEquals(80, slower.planningWpm)
        assertEquals(400, slower.wordsSelected)
        assertEquals(800, usual.wordsSelected)
        assertEquals(1_000, faster.wordsSelected)
        assertEquals(2.5, faster.estimatedMinutes, .000001)
        assertEquals(800, longer.wordBudget)
        assertTrue(faster.note.contains("400"))
        for (invalid in listOf(0, 79, 401, Int.MAX_VALUE)) assertFails { ReadingSkimPlanner.plan(text, 5, planningWpm = invalid) }
        assertFails { ReadingSkimPlanner.plan(text, 3) }
    }

    @Test fun oldParagraphReferencesRemainNavigationHintsWithoutExactQuestionHighlights() {
        val text = (0..3).joinToString("\n\n") { sentence(400) }
        val legacy = ReadingQuestion("legacy", "What is the central claim?", "A reference answer", listOf(3))
        val plan = ReadingSkimPlanner.plan(text, 5, question = legacy)
        assertTrue(3 in plan.paragraphIndices)
        assertEquals(SkimQuestionStatus.PARAGRAPH_ONLY, plan.questionCoverage.single().status)
        assertEquals(listOf(3), plan.questionCoverage.single().relatedParagraphIndices)
        assertTrue(plan.sentenceRanges.all { it.questionIds.isEmpty() })
        assertTrue(plan.coveredQuestionIds.isEmpty())
        assertEquals(listOf("legacy"), plan.unresolvedQuestionIds)
    }

    @Test fun oldQuestionJsonDecodesWithUnknownExactEvidence() {
        val old = Json.decodeFromString<ReadingQuestion>("""{"id":"legacy","question":"Question?","answer":"Answer","paragraphs":[1]}""")
        assertTrue(old.evidence.isEmpty())
        assertEquals(listOf(1), old.paragraphs)
    }

    @Test fun newSelectionsMustMatchTheOfficialStudyQuestionIncludingItsStructure() {
        val text = "The available observations support a limited conclusion."
        val official = question(text, "official", 0)
        val study = study(text, listOf(official))
        val impostor = official.copy(answer = "A changed answer")
        val plan = ReadingSkimPlanner.plan(text, 5, study, selectedQuestions = listOf(impostor))
        assertEquals(SkimQuestionStatus.INVALID_EVIDENCE, plan.questionCoverage.single().status)
        assertTrue(plan.sentenceRanges.single().questionIds.isEmpty())
        assertTrue(plan.questionCoverage.single().relatedParagraphIndices.isEmpty())
        val absent = ReadingSkimPlanner.plan(text, 5, selectedQuestions = listOf(official))
        assertEquals(SkimQuestionStatus.INVALID_EVIDENCE, absent.questionCoverage.single().status)
        val independent = ReadingSkimPlanner.plan(text, 5, question = official)
        assertEquals(listOf("official"), independent.coveredQuestionIds)
    }

    @Test fun invalidOffsetsIdsParagraphsOrDuplicateEvidenceCannotBecomeAnswerHighlights() {
        val text = "A clear account distinguishes observation from interpretation.\n\nThe second paragraph adds a qualification."
        val good = question(text, "question", 0)
        val span = good.evidence.single()
        val invalid = listOf(
            good.copy(evidence = listOf(span.copy(start = span.start + 1))),
            good.copy(evidence = listOf(span.copy(endExclusive = span.endExclusive - 1))),
            good.copy(evidence = listOf(span.copy(sentenceId = "unknown"))),
            good.copy(evidence = listOf(span.copy(paragraphIndex = 1))),
            good.copy(paragraphs = listOf(1)),
            good.copy(evidence = listOf(span, span)),
        )
        for (candidate in invalid) {
            val plan = ReadingSkimPlanner.plan(text, 5, study(text, listOf(candidate)), selectedQuestions = listOf(candidate))
            assertEquals(SkimQuestionStatus.INVALID_EVIDENCE, plan.questionCoverage.single().status)
            assertTrue(plan.sentenceRanges.all { it.questionIds.isEmpty() })
        }
    }

    @Test fun staleStudySnapshotCannotCertifyQuestionEvidenceForTheCurrentArticle() {
        val text = "The explanation depends on a specific observation."
        val question = question(text, "question", 0)
        val stale = study(text, listOf(question)).copy(bodyHash = "outdated-snapshot")
        val plan = ReadingSkimPlanner.plan(text, 5, stale, selectedQuestions = listOf(question))
        assertEquals(SkimQuestionStatus.INVALID_EVIDENCE, plan.questionCoverage.single().status)
        assertTrue(plan.sentenceRanges.single().questionIds.isEmpty())
    }

    @Test fun duplicateSelectionsAreDeduplicatedButConflictingVersionsAreNotTrusted() {
        val text = "A careful comparison clarifies the competing claims."
        val question = question(text, "question", 0)
        val study = study(text, listOf(question))
        val same = ReadingSkimPlanner.plan(text, 5, study, question = question, selectedQuestions = listOf(question, question))
        assertEquals(listOf("question"), same.selectedQuestionIds)
        assertEquals(listOf("question"), same.sentenceRanges.single().questionIds)
        val conflicting = ReadingSkimPlanner.plan(text, 5, study, selectedQuestions = listOf(question, question.copy(question = "Changed?")))
        assertEquals(SkimQuestionStatus.INVALID_EVIDENCE, conflicting.questionCoverage.single().status)
    }

    @Test fun stableSentenceIdsPreserveRepeatedUnicodeTextAndExactOriginalPositions() {
        val repeated = "😀 Dr. Fox measured 3.14 units."
        val text = "$repeated $repeated\n\nA later result followed."
        val units = ReadingSkimPlanner.sentences(text)
        assertEquals(3, units.size)
        assertEquals(units, ReadingSkimPlanner.sentences(text))
        assertEquals(repeated, text.substring(units[0].start, units[0].endExclusive))
        assertEquals(repeated, text.substring(units[1].start, units[1].endExclusive))
        assertNotEquals(units[0].id, units[1].id)
        assertTrue(units.zipWithNext().all { (a, b) -> a.endExclusive <= b.start })
        units.forEach { unit ->
            assertFalse(unit.start > 0 && text[unit.start - 1].isHighSurrogate() && text[unit.start].isLowSurrogate())
            assertEquals(unit.wordCount, ReadingSkimPlanner.countWords(text.substring(unit.start, unit.endExclusive)))
            assertEquals(unit.id, unit.evidenceRange().sentenceId)
        }
        val changed = text.replaceFirst("Fox", "Cat")
        assertNotEquals(units[0].id, ReadingSkimPlanner.sentences(changed)[0].id)
        assertEquals(units[1].id, ReadingSkimPlanner.sentences(changed)[1].id)
    }

    private fun question(text: String, id: String, vararg sentenceIndices: Int): ReadingQuestion {
        val sentences = ReadingSkimPlanner.sentences(text)
        val evidence = sentenceIndices.map { sentences[it].evidenceRange() }
        return ReadingQuestion(id, "What does the evidence establish?", "A bounded reference answer.", evidence.map { it.paragraphIndex }.distinct(), evidence)
    }

    private fun study(text: String, questions: List<ReadingQuestion>, roles: List<String> = emptyList()) = ArticleStudy(
        "article", digest(text), "Summary", "B2", emptyList(), emptyList(),
        ParagraphFocus.paragraphs(text).mapIndexed { index, _ -> StudyParagraph(index, roles.getOrNull(index) ?: "other", "A paragraph.") },
        questions, emptyList(), "synthetic", 0, schemaVersion = 2,
    )

    private fun sentence(words: Int) = "Evidence" + " word".repeat(words - 1) + "."
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun assertFails(block: () -> Unit) {
        try { block(); fail("Expected an invalid planning contract to fail") }
        catch (_: IllegalArgumentException) { }
    }
}
