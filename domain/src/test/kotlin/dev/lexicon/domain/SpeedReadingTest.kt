package dev.lexicon.domain

import org.junit.Assert.*
import org.junit.Test

class SpeedReadingTest {
    private val example = "The report, which was released on Tuesday, warns that coastal cities face growing risks from rising sea levels."
    private fun plan(text: String = example, level: SpeedGranularity = SpeedGranularity.L2, start: Int = 0, count: Int = 1) =
        SpeedChunker.plan("article", "body-hash", text, level, start, count, now = 1234)

    @Test fun allGranularitiesConserveOriginalUtf16AndParagraphIndices() {
        val text = "Introductory paragraph.\n\n  $example\n\nReaders examine the evidence before they accept a conclusion.\n\nLast paragraph."
        val paragraphs = ParagraphFocus.paragraphs(text)
        SpeedGranularity.entries.forEach { level ->
            val result = plan(text, level, start = 1, count = 2)
            SpeedChunker.validate(result, text)
            assertEquals(listOf(1, 2), result.paragraphIndices)
            for (index in result.paragraphIndices) {
                val original = text.substring(paragraphs[index].start, paragraphs[index].endExclusive)
                val reconstructed = result.chunks.filter { it.paragraph == index }.joinToString("") { text.substring(it.start, it.end) }
                assertEquals(original, reconstructed)
            }
            assertEquals(SpeedChunker.wordCount(example) + SpeedChunker.wordCount("Readers examine the evidence before they accept a conclusion."), result.chunks.sumOf { it.wordCount })
            assertTrue(result.chunks.all { it.wordCount > 0 && it.reason.isNotBlank() })
        }
    }

    @Test fun repeatedWordsUseRealRangesInsteadOfSearchingFirstOccurrence() {
        val text = "The evidence matters. The evidence matters again."
        val result = plan(text)
        assertEquals(text, result.chunks.joinToString("") { text.substring(it.start, it.end) })
        assertEquals(listOf(0, 1), result.chunks.map { it.sentence }.distinct())
        assertEquals(text.indexOf("The evidence", 1), result.chunks.first { it.sentence == 1 }.start)
    }

    @Test fun fixedCollocationsAndTheirInflectionsCannotBeSplit() {
        val text = "Researchers take into account the evidence in terms of public health, while the panel took into account the costs."
        for (level in SpeedGranularity.entries) {
            val result = plan(text, level)
            listOf("take into account", "in terms of", "public health", "took into account", "the evidence", "the costs").forEach { phrase ->
                val begin = text.indexOf(phrase); val end = begin + phrase.length
                assertTrue("$level split $phrase", result.chunks.none { it.end in begin + 1 until end })
            }
        }
    }

    @Test fun prepositionObjectAndDeterminerNounAreProtectedEvenInComfortMode() {
        val result = plan(example, SpeedGranularity.L1)
        listOf("The report", "on Tuesday", "from rising sea levels").forEach { phrase ->
            val start = example.indexOf(phrase); val end = start + phrase.length
            assertTrue(result.chunks.none { it.end in start + 1 until end })
        }
        assertTrue(result.chunks.any { it.reason.contains("从句") || it.reason.contains("标点") })
    }

    @Test fun longerAtomicGroupsRemainIntactInsteadOfForcingWordCount() {
        val text = "A remarkably detailed public economic report warns of rising costs."
        val result = plan(text, SpeedGranularity.L1)
        assertEquals(text, result.chunks.joinToString("") { text.substring(it.start, it.end) })
        val nounPhraseEnd = text.indexOf(" warns")
        assertTrue(result.chunks.none { it.end in 1 until nounPhraseEnd })
        // A long complete prepositional group may exceed the preferred 2–4 word width.
        val longText = "The report records changes from remarkably large new coastal city districts."
        val long = plan(longText, SpeedGranularity.L1)
        val start = longText.indexOf("from")
        assertTrue(long.chunks.any { it.start <= start && it.end == longText.length && it.wordCount > 4 })
    }

    @Test fun readingRoomAndTimeQuantityStayWholeAtEveryGranularity() {
        val text = "The council opened a quiet reading room and will review attendance after three months before deciding whether to expand it."
        for (level in SpeedGranularity.entries) {
            val result = plan(text, level)
            listOf("a quiet reading room", "after three months", "before deciding").forEach { phrase ->
                assertUnsplit(text, result, phrase)
            }
            // A time phrase does not absorb the next temporal phrase into a mandatory atomic span.
            SpeedChunker.validate(splitAt(result, text, text.indexOf("before deciding")), text)
        }
    }

    @Test fun quantityAndUnitProtectionGeneralizesBeyondTheLiveExample() {
        val examples = listOf(
            "Researchers waited after twenty five days before returning to the site." to listOf("after twenty five days", "before returning"),
            "The study covered 1,200 households across three rural districts." to listOf("1,200 households", "three rural districts"),
            "Volunteers walked 3.5 kilometres after two hours before resting." to listOf("3.5 kilometres", "after two hours", "before resting"),
            "The council invited one hundred and twenty new students." to listOf("one hundred and twenty new students"),
            "Observers counted twenty-three rare birds near the coast." to listOf("twenty-three rare birds"),
        )
        for ((text, phrases) in examples) for (level in SpeedGranularity.entries) {
            val result = plan(text, level)
            phrases.forEach { assertUnsplit(text, result, it) }
        }
        assertEquals(4, SpeedChunker.wordCount("1,200 households 3.5 kilometres"))
    }

    @Test fun nominalProtectionIncludesBareAndModifiedPhrasesWithoutAbsorbingFiniteClauses() {
        val examples = listOf(
            "Quiet reading rooms support careful independent study." to listOf("Quiet reading rooms", "careful independent study"),
            "A very bright public hall offers a safe social space." to listOf("A very bright public hall", "a safe social space"),
            "The group discussed sustainable urban development before they reached a decision." to listOf("sustainable urban development"),
        )
        for ((text, phrases) in examples) for (level in SpeedGranularity.entries) {
            val result = plan(text, level)
            phrases.forEach { assertUnsplit(text, result, it) }
            if ("before they" in text) {
                // The pronoun starts a finite clause, so it is not mistaken for a preposition's noun object.
                SpeedChunker.validate(splitAt(result, text, text.indexOf("they reached")), text)
            }
        }
    }

    @Test fun modelRangesCannotSplitAdjectiveNounOrNumberUnitGroups() {
        val cases = listOf(
            "They opened a quiet reading room." to "reading room",
            "The trial ended after three months before deciding the next step." to "months",
            "They recruited twenty five volunteers." to "volunteers",
            "The sensors measured 3.5 metres." to "metres",
        )
        for ((text, rightHalf) in cases) {
            val invalid = splitAt(plan(text), text, text.indexOf(rightHalf))
            try {
                SpeedChunker.validate(invalid, text)
                fail("Accepted an internal boundary before $rightHalf")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun abbreviationsDecimalsQuotesPossessivesAndEmojiRemainUnchanged() {
        val text = "“Dr. Chen's team measured 3.14 units in the U.S. office.” 🧭 Their result remained stable; other teams agreed."
        val result = plan(text)
        assertEquals(text, result.chunks.joinToString("") { text.substring(it.start, it.end) })
        assertEquals(2, result.chunks.map { it.sentence }.distinct().size)
        val emoji = text.indexOf("🧭")
        assertTrue(result.chunks.none { it.start == emoji + 1 || it.end == emoji + 1 })
        val dr = text.indexOf("Dr."); val chen = text.indexOf("Chen")
        assertTrue(result.chunks.none { it.end > dr && it.end <= chen })
        assertEquals(4, SpeedChunker.wordCount("Chen's 3.14 U.S. well-established"))
    }

    @Test fun granularityProducesStableOffsetsAndUsuallyFewerChallengeGroups() {
        val text = "Scientific evidence supports informed decisions about public policy, while careful readers compare alternative explanations and identify the assumptions behind a claim."
        val comfort = plan(text, SpeedGranularity.L1)
        val challenge = plan(text, SpeedGranularity.L3)
        assertEquals(comfort, plan(text, SpeedGranularity.L1))
        assertTrue(challenge.chunks.size <= comfort.chunks.size)
        assertEquals(comfort.chunks.sumOf { it.wordCount }, challenge.chunks.sumOf { it.wordCount })
    }

    @Test fun invalidRangesCountsAndProtectedBoundariesAreRejected() {
        val result = plan()
        fun reject(value: SpeedPlan) {
            try { SpeedChunker.validate(value, example); fail("Expected invalid plan") } catch (_: IllegalArgumentException) { }
        }
        reject(result.copy(chunks = result.chunks.drop(1)))
        reject(result.copy(chunks = result.chunks.reversed()))
        reject(result.copy(chunks = result.chunks.mapIndexed { i, c -> if (i == 0) c.copy(wordCount = c.wordCount + 1) else c }))
        reject(result.copy(chunks = result.chunks.mapIndexed { i, c -> if (i == 0) c.copy(start = c.start + 1) else c }))
        val protectedText = "The report warns."
        val invalid = plan(protectedText).copy(chunks = listOf(
            SenseChunk(0, 4, 0, 0, 1, "manual"), SenseChunk(4, protectedText.length, 0, 0, 2, "manual"),
        ))
        try { SpeedChunker.validate(invalid, protectedText); fail("Expected protected-boundary rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun unitBoundsAndNonWordParagraphsAreRejectedWithNoSilentTruncation() {
        listOf({ plan("Short text.", count = 4) }, { plan("Short text.", start = 2) },
            { plan("word ".repeat(2000)) }, { plan("🧭✨") }).forEach { action ->
            try { action(); fail("Expected bounded input failure") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(listOf(0), plan("Short text.", count = 3).paragraphIndices)
    }

    @Test fun questionsNeedOnePerSelectedParagraphAndExactLocalEvidence() {
        val text = "A report describes evidence.\n\nThe public reviews it."
        val result = plan(text, count = 2)
        val p = ParagraphFocus.paragraphs(text)
        val questions = p.mapIndexed { i, bounds ->
            SpeedQuestion("q$i", i, "What does this paragraph say?", listOf("Supported", "Unsupported"), 0, bounds.start, bounds.endExclusive, "See the quoted evidence.")
        }
        SpeedChunker.validate(result.copy(questions = questions), text)
        try { SpeedChunker.validate(result.copy(questions = questions.take(1)), text); fail("Missing paragraph question") } catch (_: IllegalArgumentException) { }
        try { SpeedChunker.validate(result.copy(questions = questions.mapIndexed { i, q -> if (i == 1) q.copy(evidenceStart = 0) else q }), text); fail("Cross-paragraph evidence") } catch (_: IllegalArgumentException) { }
    }

    @Test fun canonicalPlanKeyIgnoresStorageTimeButIncludesQuestionAndBoundaryContent() {
        val result = plan()
        val key = SpeedChunker.planKey(result)
        assertEquals(key, SpeedChunker.planKey(result.copy(createdAt = 999999, planKey = "storage-alias")))
        assertNotEquals(key, SpeedChunker.planKey(result.copy(granularity = SpeedGranularity.L3)))
        assertNotEquals(key, SpeedChunker.planKey(result.copy(chunks = result.chunks.map { it.copy(reason = "AI alternative") })))
    }

    @Test fun longerGroupsReceiveMoreTimeAndEquivalentRateUsesActualPlan() {
        val short = SenseChunk(0, 2, 0, 0, 2, "test")
        val long = short.copy(wordCount = 7)
        assertTrue(SpeedPacing.durationMillis(long, 90) > SpeedPacing.durationMillis(short, 90))
        assertTrue(SpeedPacing.durationMillis(short, 120) < SpeedPacing.durationMillis(short, 60))
        assertEquals(SpeedPacing.durationMillis(short, 30), SpeedPacing.durationMillis(short, -1))
        assertEquals(SpeedPacing.durationMillis(short, 180), SpeedPacing.durationMillis(short, 1000))
        val result = plan()
        val expected = 90.0 * result.chunks.sumOf { it.wordCount } / result.chunks.size
        assertEquals(expected, SpeedPacing.equivalentWordsPerMinute(result, 90)!!, 1.0)
    }

    private fun run() = SpeedRun("run", "article", "hash", SpeedGranularity.L2, "key", 1000, 61000,
        60_000, 120, 30, listOf(1000, 2000, 3000), 2, 1,
        listOf(SpeedAnswer("q1", 0, true), SpeedAnswer("q2", 1, false)), 2, true, 90, "ai")

    @Test fun metricsKeepComprehensionAndEffectiveSpeedSeparateFromPlaybackSetting() {
        val value = SpeedMetrics.summarize(run())
        assertEquals(120.0, value.wordsPerMinute!!, .0001)
        assertEquals(30.0, value.groupsPerMinute!!, .0001)
        assertEquals(.5, value.comprehension!!, .0001)
        assertEquals(60.0, value.effectiveWordsPerMinute!!, .0001)
        assertEquals(2000.0, value.meanGroupMillis!!, .0001)
        assertEquals(2, value.answeredQuestions)
    }

    @Test fun missingRepeatedOrIncompleteQuestionsNeverBecomeAccuracyOrAdaptiveEvidence() {
        listOf(run().copy(answers = emptyList(), questionCount = 0), run().copy(answers = run().answers.take(1)),
            run().copy(firstAttempt = false), run().copy(completed = false),
            run().copy(answers = listOf(run().answers[0], run().answers[0]))).forEach { source ->
            val metrics = SpeedMetrics.summarize(source)
            assertNull(metrics.comprehension); assertNull(metrics.effectiveWordsPerMinute)
        }
        assertNull(SpeedMetrics.summarize(run().copy(activeMillis = 0)).wordsPerMinute)
        assertNull(SpeedMetrics.summarize(run().copy(activeMillis = 60_001)).wordsPerMinute)
    }

    private fun assertUnsplit(text: String, result: SpeedPlan, phrase: String) {
        val start = text.indexOf(phrase)
        assertTrue("Fixture is missing $phrase", start >= 0)
        val end = start + phrase.length
        assertTrue("${result.granularity} split $phrase", result.chunks.none { it.end in start + 1 until end })
    }

    private fun splitAt(result: SpeedPlan, text: String, offset: Int): SpeedPlan = result.copy(
        chunks = result.chunks.flatMap { chunk ->
            if (offset <= chunk.start || offset >= chunk.end) listOf(chunk)
            else listOf(
                chunk.copy(end = offset, wordCount = SpeedChunker.wordCount(text.substring(chunk.start, offset))),
                chunk.copy(start = offset, wordCount = SpeedChunker.wordCount(text.substring(offset, chunk.end))),
            )
        },
    )
}
