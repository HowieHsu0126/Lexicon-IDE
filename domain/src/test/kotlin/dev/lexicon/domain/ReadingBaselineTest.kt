package dev.lexicon.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReadingBaselineTest {
    private val text = "Researchers compare the evidence before drawing a conclusion.\n\n" +
        "Different explanations may fit the same initial observations.\n\n" +
        "Further measurements help distinguish these competing explanations."
    private val ranges = ParagraphFocus.paragraphs(text)
    private val plan = SpeedChunker.plan("article", "body-hash", text, SpeedGranularity.L2, 0, 3, 0).copy(
        planKey = "baseline-plan", origin = "ai", questions = ranges.mapIndexed { index, range ->
            SpeedQuestion("question-$index", index, "Question $index?", listOf("First", "Second", "Third", "Fourth"),
                index, range.start, range.endExclusive, "The original evidence supports this choice.")
        })

    private fun attempt() = ReadingBaselineAttempt("baseline", plan.articleId, plan.bodyHash, plan.planKey,
        1_000, 45_000, (0..2).map { NaturalParagraphTiming(it, 10_000, true) },
        listOf(SpeedAnswer("question-0", 0, false), SpeedAnswer("question-1", 0, false), SpeedAnswer("question-2", 2, false)),
        3, readingFinishedAt = 31_000, revealedAt = 45_000, completed = true, localUnseen = true, userConfirmedUnseen = true)

    @Test fun completeBaselineUsesOnlyReadingTimeAndFrozenQuestionAnswers() {
        val summary = ReadingBaselineMetrics.summarize(attempt(), plan)
        assertEquals(30_000L, summary.activeMillis)
        assertEquals(plan.chunks.sumOf { it.wordCount }, summary.wordsRead)
        assertTrue(summary.completeReading)
        assertEquals(3, summary.answeredQuestions)
        assertEquals(3, summary.totalQuestions)
        assertEquals(2.0 / 3, requireNotNull(summary.accuracy), 0.0001)
        assertEquals(summary.wordsRead * 2.0, requireNotNull(summary.wordsPerMinute), 0.0001)
        assertEquals(summary.wordsRead * 2.0 * 2 / 3, requireNotNull(summary.effectiveWordsPerMinute), 0.0001)
        assertTrue(summary.qualifiesAsBaseline)
        val longerAnswers = attempt().copy(finishedAt = 180_000, revealedAt = 180_000)
        assertEquals(summary, ReadingBaselineMetrics.summarize(longerAnswers, plan))
        val forgedCorrectFlags = attempt().copy(answers = attempt().answers.map { it.copy(correct = true) })
        assertEquals(summary, ReadingBaselineMetrics.summarize(forgedCorrectFlags, plan))
    }

    @Test fun answersStayHiddenUntilTheWholeCheckIsCompletedAndRevealed() {
        val readingDone = attempt().copy(answers = emptyList(), completed = false, revealedAt = null)
        val oneAnswered = readingDone.copy(answers = attempt().answers.take(1))
        val allUnrevealed = readingDone.copy(answers = attempt().answers)
        for (pending in listOf(readingDone, oneAnswered, allUnrevealed)) {
            val summary = ReadingBaselineMetrics.summarize(pending, plan)
            assertTrue(summary.completeReading)
            assertNotNull(summary.wordsPerMinute)
            assertNull(summary.accuracy)
            assertNull(summary.effectiveWordsPerMinute)
            assertFalse(summary.qualifiesAsBaseline)
        }
    }

    @Test fun previouslySeenOrUnconfirmedTextRetainsExerciseAccuracyButNotBaselineStatus() {
        for (candidate in listOf(attempt().copy(localUnseen = false), attempt().copy(userConfirmedUnseen = false))) {
            val summary = ReadingBaselineMetrics.summarize(candidate, plan)
            assertEquals(2.0 / 3, requireNotNull(summary.accuracy), 0.0001)
            assertNotNull(summary.wordsPerMinute)
            assertNull(summary.effectiveWordsPerMinute)
            assertFalse(summary.qualifiesAsBaseline)
        }
    }

    @Test fun partialReadingAndZeroQuestionOfflineReadingDoNotInventComprehension() {
        val partial = attempt().copy(paragraphs = listOf(NaturalParagraphTiming(0, 10_000, true),
            NaturalParagraphTiming(1, 2_000, false), NaturalParagraphTiming(2, 0, false)),
            answers = emptyList(), readingFinishedAt = null, completed = false, revealedAt = null)
        val summary = ReadingBaselineMetrics.summarize(partial, plan)
        assertEquals(12_000L, summary.activeMillis)
        assertEquals(plan.chunks.filter { it.paragraph == 0 }.sumOf { it.wordCount }, summary.wordsRead)
        assertFalse(summary.completeReading)
        assertNull(summary.wordsPerMinute)
        assertNull(summary.accuracy)

        val offline = ReadingBaselineMetrics.summarize(attempt().copy(answers = emptyList(), questionCount = 0, revealedAt = null),
            plan.copy(questions = emptyList(), origin = "rules"))
        assertTrue(offline.completeReading)
        assertNotNull(offline.wordsPerMinute)
        assertEquals(0, offline.totalQuestions)
        assertNull(offline.accuracy)
        assertNull(offline.effectiveWordsPerMinute)
        assertFalse(offline.qualifiesAsBaseline)
    }

    @Test fun tenSecondsOfActiveReadingIsRequiredBeforeReportingSpeed() {
        val short = attempt().copy(paragraphs = (0..2).map { NaturalParagraphTiming(it, 3_000, true) }, readingFinishedAt = 10_000)
        val tooShort = ReadingBaselineMetrics.summarize(short, plan)
        assertNull(tooShort.wordsPerMinute)
        assertNotNull(tooShort.accuracy)
        assertFalse(tooShort.qualifiesAsBaseline)
        val boundary = short.copy(paragraphs = short.paragraphs.map { if (it.paragraph == 0) it.copy(activeMillis = 4_000) else it }, readingFinishedAt = 11_000)
        assertNotNull(ReadingBaselineMetrics.summarize(boundary, plan).wordsPerMinute)
    }

    @Test fun mismatchedSnapshotsImpossibleTimingAndEarlyAnswersAreRejected() {
        val original = attempt()
        val invalid = listOf(
            original.copy(articleId = "different"), original.copy(bodyHash = "different"), original.copy(planKey = "different"),
            original.copy(paragraphs = original.paragraphs.reversed()),
            original.copy(paragraphs = original.paragraphs.map { it.copy(activeMillis = -1) }),
            original.copy(readingFinishedAt = 30_999),
            original.copy(paragraphs = original.paragraphs.map { it.copy(completed = false) }),
            original.copy(readingFinishedAt = null),
            original.copy(answers = original.answers + original.answers.first()),
            original.copy(answers = original.answers.map { it.copy(questionId = "unknown") }),
            original.copy(answers = original.answers.map { it.copy(selectedIndex = 4) }),
            original.copy(questionCount = 2), original.copy(revealedAt = 30_000),
            original.copy(answers = original.answers.dropLast(1)), original.copy(completed = false), original.copy(pauses = -1),
        )
        invalid.forEach { candidate ->
            try { ReadingBaselineMetrics.summarize(candidate, plan); fail("Accepted malformed baseline") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun missingExposureEvidenceDefaultsToUnknownRatherThanFirstExposure() {
        val codec = Json { encodeDefaults = true }
        val encoded = codec.encodeToString(attempt()).replace(",\"localUnseen\":true", "")
            .replace(",\"userConfirmedUnseen\":true", "")
        val restored = codec.decodeFromString<ReadingBaselineAttempt>(encoded)
        assertFalse(restored.localUnseen)
        assertFalse(restored.userConfirmedUnseen)
        assertFalse(ReadingBaselineMetrics.summarize(restored, plan).qualifiesAsBaseline)
    }

    @Test fun everySelectedParagraphNeedsOneQuestionAndAnOriginalAiPlanForBaselineStatus() {
        val missingParagraph = plan.copy(questions = plan.questions.take(2))
        try {
            ReadingBaselineMetrics.summarize(attempt().copy(answers = attempt().answers.take(2), questionCount = 2), missingParagraph)
            fail("A selected paragraph had no question")
        } catch (_: IllegalArgumentException) { }
        for (changed in listOf(plan.copy(origin = "rules"), plan.copy(origin = "manual", basePlanKey = "base"), plan.copy(basePlanKey = "base"))) {
            val summary = ReadingBaselineMetrics.summarize(attempt(), changed)
            assertNotNull(summary.accuracy)
            assertNotNull(summary.wordsPerMinute)
            assertNull(summary.effectiveWordsPerMinute)
            assertFalse(summary.qualifiesAsBaseline)
        }
    }

    @Test fun historyContinuationRetainsExerciseMeasuresButCannotBecomeAnUninterruptedBaseline() {
        val summary = ReadingBaselineMetrics.summarize(attempt().copy(resumedFromHistory = true), plan)
        assertNotNull(summary.wordsPerMinute)
        assertNotNull(summary.accuracy)
        assertFalse(summary.qualifiesAsBaseline)
        assertNull(summary.effectiveWordsPerMinute)
    }
}
