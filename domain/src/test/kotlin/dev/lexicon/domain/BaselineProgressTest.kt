package dev.lexicon.domain

import org.junit.Assert.*
import org.junit.Test

class BaselineProgressTest {
    private val text = "Researchers compare the evidence before drawing a conclusion.\n\nDifferent explanations may fit the same initial observations."
    private val ranges = ParagraphFocus.paragraphs(text)
    private fun record(id: String = "a", hash: String = "body-$id", started: Long = 1_000, duration: Long = 30_000): BaselineProgressRecord {
        val draft = SpeedChunker.plan("article-$id", hash, text, SpeedGranularity.L2, 0, 2, 0).copy(
            origin = "ai", questions = ranges.mapIndexed { i, p ->
                SpeedQuestion("q$i", i, "What does this mean?", listOf("First", "Second"), i,
                    p.start, p.endExclusive, "Source evidence.")
            })
        val plan = draft.copy(planKey = SpeedChunker.planKey(draft))
        val attempt = ReadingBaselineAttempt(id, plan.articleId, hash, plan.planKey, started, started + duration + 10_000,
            ranges.indices.map { NaturalParagraphTiming(it, duration / 2, true) },
            listOf(SpeedAnswer("q0", 0, false), SpeedAnswer("q1", 0, true)), 2,
            readingFinishedAt = started + duration, revealedAt = started + duration + 10_000,
            completed = true, localUnseen = true, userConfirmedUnseen = true)
        return BaselineProgressRecord(attempt, plan, ReadingBaselineMaterial(plan.planKey, hash,
            SourceRef("Frozen title $id", "Frozen source", "https://example.org/$id", started),
            ranges.mapIndexed { i, p -> BaselineSourceParagraph(i, p.start, text.substring(p.start, p.endExclusive)) }, "Science & Technology"))
    }

    @Test fun aggregatesTimeWeightedSpeedAndFrozenAnswerCorrectnessWithoutDailyAveraging() {
        val first = record(); val second = record("b", started = 100_000, duration = 60_000)
        val summary = BaselineProgressCalculator.summarize(listOf(second, first))
        assertEquals(listOf("a", "b"), summary.points.map { it.attemptId })
        assertEquals(2, summary.materialCount)
        assertEquals(2, summary.correct); assertEquals(4, summary.questions)
        assertEquals(31_000, summary.points.first().measuredAt)
        assertEquals("Frozen title a", summary.points.first().sourceRef.title)
        assertEquals("Science & Technology", summary.points.first().topic)
        val words = first.plan!!.chunks.sumOf { it.wordCount }
        assertEquals(words * 2.0 * 60_000 / 90_000, summary.weightedWordsPerMinute!!, .0001)
        assertEquals(summary.points.first().wordsPerMinute / 2, summary.points.first().effectiveWordsPerMinute, .0001)
    }

    @Test fun excludesFamiliarContinuedUnmeasuredIncompleteAndUnavailableWithoutInventingZeros() {
        val base = record()
        val incomplete = base.copy(attempt = base.attempt.copy(id = "incomplete", completed = false, revealedAt = null, answers = emptyList()))
        val continued = base.copy(attempt = base.attempt.copy(id = "continued", resumedFromHistory = true))
        val familiar = base.copy(attempt = base.attempt.copy(id = "familiar", localUnseen = false))
        val offline = withPlan(base.copy(attempt = base.attempt.copy(id = "offline")), base.plan!!.copy(origin = "rules"))
        val unavailable = base.copy(attempt = base.attempt.copy(id = "unavailable"), material = null)
        val summary = BaselineProgressCalculator.summarize(listOf(incomplete, continued, familiar, offline, unavailable))
        assertTrue(summary.points.isEmpty()); assertNull(summary.weightedWordsPerMinute)
        assertEquals(0, summary.questions)
        listOf(BaselineExclusion.INCOMPLETE, BaselineExclusion.CONTINUATION, BaselineExclusion.FAMILIAR,
            BaselineExclusion.UNMEASURED, BaselineExclusion.UNAVAILABLE).forEach { assertEquals(1, summary.exclusions[it]) }
    }

    @Test fun duplicateIdsCollapseAndRepeatedMaterialKeepsOnlyEarliestQualifiedExposure() {
        val first = record(); val repeated = record("b", hash = first.attempt.bodyHash, started = 100_000)
        val summary = BaselineProgressCalculator.summarize(listOf(repeated, first, first))
        assertEquals(listOf("a"), summary.points.map { it.attemptId })
        assertEquals(1, summary.exclusions[BaselineExclusion.DUPLICATE])
        val conflict = first.copy(attempt = first.attempt.copy(pauses = 1))
        val conflicting = BaselineProgressCalculator.summarize(listOf(first, conflict, repeated))
        assertEquals(listOf("b"), conflicting.points.map { it.attemptId })
        assertEquals(1, conflicting.exclusions[BaselineExclusion.UNAVAILABLE])
    }

    @Test fun missingTopicsStayUnknownAndCorruptSourceRangesCannotBecomePoints() {
        val first = record()
        val unknown = first.copy(material = first.material!!.copy(topic = "invented"))
        assertNull(BaselineProgressCalculator.summarize(listOf(unknown)).points.single().topic)
        val bad = first.copy(material = first.material.copy(paragraphs = first.material.paragraphs.map { it.copy(text = "") }))
        assertEquals(mapOf(BaselineExclusion.UNAVAILABLE to 1), BaselineProgressCalculator.summarize(listOf(bad)).exclusions)
        assertTrue(BaselineProgressCalculator.summarize(emptyList()).points.isEmpty())
    }

    @Test fun mismatchedPlanKeysAndInflatedWordCountsCannotProduceAReadingSpeed() {
        val base = record()
        val plan = requireNotNull(base.plan)
        val badKey = base.copy(plan = plan.copy(planKey = "unverified"))
        val inflated = withPlan(base, plan.copy(chunks = plan.chunks.map { it.copy(wordCount = it.wordCount + 20) }))
        for (corrupt in listOf(badKey, inflated)) {
            val summary = BaselineProgressCalculator.summarize(listOf(corrupt))
            assertTrue(summary.points.isEmpty())
            assertNull(summary.weightedWordsPerMinute)
            assertEquals(mapOf(BaselineExclusion.UNAVAILABLE to 1), summary.exclusions)
        }
    }

    @Test fun missingRepeatedOrCrossParagraphRangesAreExcludedEvenWithARecomputedPlanKey() {
        val base = record()
        val plan = requireNotNull(base.plan)
        val corruptPlans = listOf(
            plan.copy(chunks = plan.chunks.drop(1)),
            plan.copy(chunks = listOf(plan.chunks.first()) + plan.chunks),
            plan.copy(chunks = plan.chunks.mapIndexed { index, chunk -> if (index == 0) chunk.copy(end = chunk.end - 1) else chunk }),
            plan.copy(questions = plan.questions.mapIndexed { index, question ->
                if (index == 0) question.copy(evidenceStart = ranges[1].start, evidenceEnd = ranges[1].endExclusive) else question
            }),
        )
        corruptPlans.forEach { corrupt ->
            val summary = BaselineProgressCalculator.summarize(listOf(withPlan(base, corrupt)))
            assertTrue(summary.points.isEmpty())
            assertEquals(1, summary.exclusions[BaselineExclusion.UNAVAILABLE])
        }
    }

    @Test fun validFragmentsWithLargeOriginalOffsetsNeedNoFullArticleReconstruction() {
        val base = record()
        val shift = 1_000_000_000
        val plan = requireNotNull(base.plan)
        val moved = plan.copy(paragraphIndices = plan.paragraphIndices.map { it + 7 },
            chunks = plan.chunks.map { it.copy(start = it.start + shift, end = it.end + shift, paragraph = it.paragraph + 7) },
            questions = plan.questions.map { it.copy(evidenceStart = it.evidenceStart + shift, evidenceEnd = it.evidenceEnd + shift,
                paragraph = it.paragraph + 7) })
        val record = withPlan(base.copy(attempt = base.attempt.copy(paragraphs = base.attempt.paragraphs.map { it.copy(paragraph = it.paragraph + 7) }),
            material = base.material!!.copy(paragraphs = base.material.paragraphs.map { it.copy(start = it.start + shift, paragraph = it.paragraph + 7) })), moved)
        val summary = BaselineProgressCalculator.summarize(listOf(record))
        assertEquals(1, summary.points.size)
        assertTrue(summary.exclusions.isEmpty())
        assertEquals(BaselineProgressCalculator.summarize(listOf(base)).weightedWordsPerMinute, summary.weightedWordsPerMinute)
    }

    @Test fun sourceOverflowBlankOrEmbeddedExtraParagraphsFailSafely() {
        val base = record()
        val material = requireNotNull(base.material)
        val corrupt = listOf(
            material.copy(paragraphs = material.paragraphs.mapIndexed { index, part -> if (index == 0) part.copy(start = Int.MAX_VALUE) else part }),
            material.copy(paragraphs = material.paragraphs.mapIndexed { index, part -> if (index == 0) part.copy(text = "\n\n") else part }),
            material.copy(paragraphs = material.paragraphs.mapIndexed { index, part -> if (index == 0) part.copy(text = "First.\n\nSecond.") else part }),
        )
        corrupt.forEach {
            val summary = BaselineProgressCalculator.summarize(listOf(base.copy(material = it)))
            assertTrue(summary.points.isEmpty())
            assertEquals(1, summary.exclusions[BaselineExclusion.UNAVAILABLE])
        }
    }

    @Test fun completedRecordsWithoutRevealedComprehensionOrUserUnseenConfirmationAreExcluded() {
        val base = record()
        val unrevealed = base.copy(attempt = base.attempt.copy(id = "unrevealed", revealedAt = null))
        val userFamiliar = base.copy(attempt = base.attempt.copy(id = "user-familiar", userConfirmedUnseen = false))
        val noQuestions = withPlan(base.copy(attempt = base.attempt.copy(id = "no-questions", questionCount = 0, answers = emptyList(), revealedAt = null)),
            base.plan!!.copy(origin = "rules", questions = emptyList()))
        val summary = BaselineProgressCalculator.summarize(listOf(unrevealed, userFamiliar, noQuestions))
        assertTrue(summary.points.isEmpty())
        assertNull(summary.weightedWordsPerMinute)
        assertEquals(2, summary.exclusions[BaselineExclusion.UNMEASURED])
        assertEquals(1, summary.exclusions[BaselineExclusion.FAMILIAR])
    }

    @Test fun invalidQuestionAnswersFailButGenuineZeroAccuracyRemainsAMeasuredResult() {
        val base = record()
        val allWrong = base.copy(attempt = base.attempt.copy(answers = listOf(SpeedAnswer("q0", 1, true), SpeedAnswer("q1", 0, true))))
        val summary = BaselineProgressCalculator.summarize(listOf(allWrong))
        assertEquals(1, summary.points.size)
        assertEquals(0, summary.correct)
        assertEquals(2, summary.questions)
        assertEquals(0.0, summary.points.single().effectiveWordsPerMinute, .00001)
        assertNotNull(summary.weightedWordsPerMinute)
        val duplicate = base.copy(attempt = base.attempt.copy(answers = listOf(SpeedAnswer("q0", 0, true), SpeedAnswer("q0", 0, true))))
        val unknown = base.copy(attempt = base.attempt.copy(answers = listOf(SpeedAnswer("missing", 0, true), SpeedAnswer("q1", 1, true))))
        val outside = base.copy(attempt = base.attempt.copy(answers = listOf(SpeedAnswer("q0", 9, true), SpeedAnswer("q1", 1, true))))
        listOf(duplicate, unknown, outside).forEach {
            assertEquals(mapOf(BaselineExclusion.UNAVAILABLE to 1), BaselineProgressCalculator.summarize(listOf(it)).exclusions)
        }
    }

    @Test fun materialBindingsMustMatchAndInputOrderCannotChangeTheTrend() {
        val first = record()
        val second = record("b", started = 100_000)
        val good = BaselineProgressCalculator.summarize(listOf(first, second))
        assertEquals(good, BaselineProgressCalculator.summarize(listOf(second, first)))
        val wrongMaterial = first.copy(material = first.material!!.copy(bodyHash = "another-body"))
        val wrongAttempt = first.copy(attempt = first.attempt.copy(bodyHash = "another-body"))
        listOf(wrongMaterial, wrongAttempt).forEach {
            assertEquals(mapOf(BaselineExclusion.UNAVAILABLE to 1), BaselineProgressCalculator.summarize(listOf(it)).exclusions)
        }
    }

    @Test fun tooShortAndImpossibleDurationsDoNotBecomeChartedSpeeds() {
        val short = record(duration = 6_000)
        val shortSummary = BaselineProgressCalculator.summarize(listOf(short))
        assertTrue(shortSummary.points.isEmpty())
        assertEquals(1, shortSummary.exclusions[BaselineExclusion.UNMEASURED])
        assertNull(shortSummary.weightedWordsPerMinute)
        val base = record()
        val impossible = base.copy(attempt = base.attempt.copy(readingFinishedAt = base.attempt.startedAt + 1))
        assertEquals(mapOf(BaselineExclusion.UNAVAILABLE to 1), BaselineProgressCalculator.summarize(listOf(impossible)).exclusions)
    }

    private fun withPlan(record: BaselineProgressRecord, draft: SpeedPlan): BaselineProgressRecord {
        val plan = draft.copy(planKey = SpeedChunker.planKey(draft))
        return record.copy(plan = plan, attempt = record.attempt.copy(planKey = plan.planKey),
            material = record.material?.copy(planKey = plan.planKey))
    }
}
