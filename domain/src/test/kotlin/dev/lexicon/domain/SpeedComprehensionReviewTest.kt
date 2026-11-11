package dev.lexicon.domain

import org.junit.Assert.*
import org.junit.Test

class SpeedComprehensionReviewTest {
    private val quote = "The council changed the hours. Residents had asked for evening access."
    private val question = SpeedQuestion("q1", 2, "Why did the hours change?",
        listOf("Residents requested evening access", "The building closed", "The budget fell"),
        0, 120, 120 + quote.length, "Residents requested access after work.")
    private val second = question.copy(id = "q2", paragraph = 3, answerIndex = 2,
        prompt = "Which answer is the reference for the second test item?")
    private val plan = SpeedPlan("a", "body", SpeedGranularity.L2, listOf(2, 3), emptyList(),
        listOf(question, second), origin = "ai", planKey = "plan")
    private val run = SpeedRun("r", "a", "body", SpeedGranularity.L2, "plan", 0, 1000, 1000, 10, 1,
        listOf(1000L), 0, 0, listOf(SpeedAnswer("q1", 1, false), SpeedAnswer("q2", 2, true)),
        2, true, 60, "ai")
    private val evidence = mapOf("q1" to quote, "q2" to quote)
    private fun inspect(r: SpeedRun = run, p: SpeedPlan = plan, e: Map<String, String> = evidence) =
        SpeedComprehensionReviews.inspect(r, p, e)

    @Test fun wrongAndCorrectAnswersRetainExactFrozenTextInQuestionOrder() {
        val result = inspect(run.copy(answers = run.answers.reversed()))
        assertEquals(SpeedComprehensionReviewStatus.AVAILABLE, result.status)
        assertEquals(listOf("q1", "q2"), result.items.map { it.question.id })
        assertEquals(run.answers, result.items.map { it.answer })
        assertEquals(quote, result.items.first().evidenceText)
        assertEquals(question, result.items.first().question)
    }

    @Test fun everySourceIdentityMustMatchTheSavedRun() {
        listOf(run.copy(articleId = "different"), run.copy(bodyHash = "changed"), run.copy(planKey = "other"),
            run.copy(granularity = SpeedGranularity.L1), run.copy(planOrigin = "rules"), run.copy(id = "")).forEach {
            assertEquals(SpeedComprehensionReviewStatus.UNAVAILABLE, inspect(it).status)
        }
    }

    @Test fun conflictingScoresUnknownOrDuplicateAnswersAreNotRegraded() {
        val variants = listOf(run.answers.map { it.copy(correct = !it.correct) },
            run.answers + run.answers.first(), listOf(SpeedAnswer("unknown", 0, true)),
            listOf(SpeedAnswer("q1", 3, false), run.answers.last()))
        variants.forEach { answers ->
            val result = inspect(run.copy(answers = answers))
            assertEquals(SpeedComprehensionReviewStatus.UNAVAILABLE, result.status)
            assertTrue(result.items.isEmpty())
        }
        assertFalse(run.answers.first().correct)
    }

    @Test fun unfinishedOrPartiallyAnsweredRunsNeverExposeSolutions() {
        listOf(run.copy(completed = false), run.copy(answers = run.answers.take(1)), run.copy(answers = emptyList())).forEach {
            val result = inspect(it)
            assertEquals(SpeedComprehensionReviewStatus.INCOMPLETE, result.status)
            assertTrue(result.items.isEmpty())
        }
    }

    @Test fun absentOrMismatchedEvidenceDoesNotExposeAnUnverifiedQuote() {
        listOf(emptyMap(), mapOf("q1" to quote), evidence + ("q1" to quote.dropLast(1))).forEach {
            assertEquals(SpeedComprehensionReviewStatus.UNAVAILABLE, inspect(e = it).status)
        }
    }

    @Test fun ruleAndFadedRunsHaveNoInventedComprehension() {
        assertEquals(SpeedComprehensionReviewStatus.NO_QUESTIONS,
            inspect(run.copy(round = SpeedPacedRound.FADED, questionCount = 0, answers = emptyList())).status)
        assertEquals(SpeedComprehensionReviewStatus.NO_QUESTIONS,
            inspect(run.copy(planOrigin = "rules", questionCount = 0, answers = emptyList()),
                plan.copy(origin = "rules", questions = emptyList()), emptyMap()).status)
    }

    @Test fun malformedQuestionsAndDeclaredCountsCannotAppearVerified() {
        assertEquals(SpeedComprehensionReviewStatus.UNAVAILABLE, inspect(run.copy(questionCount = 3)).status)
        listOf(question.copy(options = listOf("same", "same", "third")), question.copy(prompt = ""),
            question.copy(explanation = ""), question.copy(evidenceStart = -1)).forEach {
            assertEquals(SpeedComprehensionReviewStatus.UNAVAILABLE, inspect(p = plan.copy(questions = listOf(it, second))).status)
        }
    }
}
