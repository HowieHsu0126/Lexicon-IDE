package dev.lexicon.domain

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class SkimDisclosurePlanningTest {
    private val article = Article("source", "guid", emptyList(), "Reading evidence", "https://theconversation.com/test-123", "",
        author = "Alex Morgan, Professor, Example University", readingMode = ReadingMode.NATIVE)
    private val credit = "Alex Morgan is affiliated with Example University."
    private fun body(text: String) = ArticleBody(article.id, text, hash(text), 1, 100, "Source retained", article.author)
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun defaultOriginalPlanKeepsConclusionAndExcludesOnlyTheIdentifiedDeclaration() {
        val body = body("An observation raises a question.\n\nA conclusion follows from the evidence.\n\n$credit")
        val before = body.copy()
        val plan = ReadingSkimPlanner.plan(body.text, 5, original = SkimOriginal(article, body))
        assertEquals(listOf(0, 1), plan.paragraphIndices)
        assertEquals(listOf(2), plan.disclosures.map { it.paragraphIndex })
        assertEquals(ReadingSkimPlanner.countWords(body.text.substringBeforeLast("\n\n")), plan.wordsSelected)
        assertEquals(before, body)
        assertFalse(plan.includesDisclosures)
    }

    @Test fun explicitInclusionRestoresTheSameCompleteSourceSentences() {
        val body = body("One observation.\n\nOne conclusion.\n\n$credit")
        val included = ReadingSkimPlanner.plan(body.text, 5, original = SkimOriginal(article, body), includeDisclosures = true)
        assertTrue(included.includesDisclosures)
        assertEquals(ReadingSkimPlanner.plan(body.text, 5).sentenceRanges, included.sentenceRanges)
    }

    @Test fun aUserSelectedExactQuestionCanReadTheDeclarationWithoutEnablingAutomaticInclusion() {
        val body = body("One observation.\n\nOne conclusion.\n\n$credit")
        val evidence = ReadingSkimPlanner.sentences(body.text).last().evidenceRange()
        val question = ReadingQuestion("q", "What relationship does the author disclose?", "Example University.", listOf(2), listOf(evidence))
        val study = ArticleStudy(article.id, body.contentHash, "Guide", "B2", emptyList(), emptyList(),
            listOf(StudyParagraph(0, "evidence", "Observation"), StudyParagraph(1, "conclusion", "Conclusion"), StudyParagraph(2, "other", "Declaration")),
            listOf(question), emptyList(), "fixture", 1, schemaVersion = 2)
        val plan = ReadingSkimPlanner.plan(body.text, 5, study, selectedQuestions = listOf(question), original = SkimOriginal(article, body))
        assertTrue(2 in plan.paragraphIndices)
        assertEquals(listOf("q"), plan.coveredQuestionIds)
        assertEquals(listOf("q"), plan.sentenceRanges.last().questionIds)
        assertFalse(plan.includesDisclosures)
        val roleRestricted = ReadingSkimPlanner.plan(body.text, 5, study, selectedQuestions = listOf(question), roles = setOf("evidence"),
            original = SkimOriginal(article, body))
        assertEquals(SkimQuestionStatus.ROLE_EXCLUDED, roleRestricted.questionCoverage.single().status)
    }

    @Test fun aUserSelectedLegacyParagraphQuestionAlsoKeepsItsDeclaration() {
        val body = body("One observation.\n\nOne conclusion.\n\n$credit")
        val question = ReadingQuestion("q", "What is disclosed?", "An affiliation.", listOf(2))
        val plan = ReadingSkimPlanner.plan(body.text, 5, question = question, original = SkimOriginal(article, body))
        assertTrue(2 in plan.paragraphIndices)
        assertEquals(SkimQuestionStatus.PARAGRAPH_ONLY, plan.questionCoverage.single().status)
    }

    @Test fun staleSourceContextCannotExcludeARangeFromDifferentRenderedText() {
        val body = body("First observation.\n\nConclusion.\n\n$credit")
        val rewrite = "A different observation.\n\nA different conclusion.\n\nAn important final argument."
        val plan = ReadingSkimPlanner.plan(rewrite, 5, original = SkimOriginal(article, body))
        assertTrue(plan.disclosures.isEmpty())
        assertEquals(ReadingSkimPlanner.plan(rewrite, 5).sentenceRanges, plan.sentenceRanges)
    }

    @Test fun shortBudgetPromotesLastSubstantiveParagraphBeforeDeclaration() {
        val observation = (1..220).joinToString(" ") { "Evidence" } + "."
        val middle = (1..180).joinToString(" ") { "Context" } + "."
        val conclusion = (1..175).joinToString(" ") { "Conclusion" } + "."
        val body = body("$observation\n\n$middle\n\n$conclusion\n\n$credit")
        val plan = ReadingSkimPlanner.plan(body.text, 5, planningWpm = 80, original = SkimOriginal(article, body))
        assertEquals(listOf(0, 2), plan.paragraphIndices)
        assertEquals(395, plan.wordsSelected)
        assertFalse(plan.exceedsBudget)
    }
}
