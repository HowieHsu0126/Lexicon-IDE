package dev.lexicon.domain

import org.junit.Assert.*
import org.junit.Test

class SpeedSegmentationEditorTest {
    @Test fun ordinaryPrepositionHeuristicDoesNotLockTheFollowingClause() {
        val text = "For a short period people continued reading."
        val base = plan(text)
        val editable = requireNotNull(SpeedSegmentationEditor.sentence(base, text, 0))
        val naturalBoundary = text.indexOf("people")
        assertTrue(editable.legalBreaks.contains(naturalBoundary))
        val adjusted = SpeedSegmentationEditor.edit(base, text, 0, 0, listOf(naturalBoundary))
        SpeedChunker.validate(adjusted, text)
        assertEquals(listOf("For a short period ", "people continued reading."), adjusted.chunks.map { text.substring(it.start, it.end) })
    }

    @Test fun repeatedWordsKeepTheSelectedSentenceOffsetsAndUnchangedText() {
        val text = "The report matters. The report matters again."
        val base = plan(text)
        val index = base.chunks.indexOfFirst { it.sentence == 1 }
        val editable = requireNotNull(SpeedSegmentationEditor.sentence(base, text, index))
        assertEquals(text.indexOf("The report", 1), editable.start)
        assertEquals(text.substring(editable.start, editable.end), editable.text)
        assertTrue(editable.legalBreaks.all { it > editable.start && it < editable.end })
        assertFalse(editable.hasPersonalEdits)
        val first = base.chunks.filter { it.sentence == 0 }
        val adjusted = SpeedSegmentationEditor.edit(base, text, 0, 1, emptyList())
        assertEquals(first, adjusted.chunks.filter { it.sentence == 0 })
        assertEquals(text, adjusted.chunks.joinToString("") { text.substring(it.start, it.end) })
    }

    @Test fun unicodeApostrophesAbbreviationsAndNumbersRemainAtomic() {
        val text = "🧭 Dr. Lee's team measured 3.5 metres before returning."
        val base = plan(text)
        val editable = requireNotNull(SpeedSegmentationEditor.sentence(base, text, 0))
        val illegal = listOf(1, text.indexOf("Lee"), text.indexOf("Lee") + 2,
            text.indexOf("3.5") + 1, text.indexOf("metres"))
        illegal.forEach { offset ->
            assertFalse("Unexpected legal boundary $offset", offset in editable.legalBreaks)
            reject { SpeedSegmentationEditor.edit(base, text, 0, 0, listOf(offset)) }
        }
        val all = SpeedSegmentationEditor.edit(base, text, 0, 0, editable.legalBreaks)
        assertEquals(text, all.chunks.joinToString("") { text.substring(it.start, it.end) })
    }

    @Test fun fixedExpressionsCannotBeBrokenButMultipleNaturalAlternativesAreAllowed() {
        val text = "Researchers take into account the evidence and compare different explanations."
        val base = plan(text)
        val editable = requireNotNull(SpeedSegmentationEditor.sentence(base, text, 0))
        listOf("into", "account", "evidence", "explanations").forEach { token ->
            reject { SpeedSegmentationEditor.edit(base, text, 0, 0, listOf(text.indexOf(token))) }
        }
        assertTrue(editable.legalBreaks.size >= 2)
        val whole = SpeedSegmentationEditor.edit(base, text, 0, 0, emptyList())
        val alternative = SpeedSegmentationEditor.edit(base, text, 0, 0, listOf(editable.legalBreaks.last()))
        SpeedChunker.validate(whole, text)
        SpeedChunker.validate(alternative, text)
        assertEquals(1, whole.chunks.size)
        assertEquals(2, alternative.chunks.size)
    }

    @Test fun editsRejectDuplicateUnsortedAndCrossSentenceBoundaries() {
        val text = "Readers compare different explanations and identify assumptions. Another sentence follows."
        val base = plan(text)
        val editable = requireNotNull(SpeedSegmentationEditor.sentence(base, text, 0))
        assertTrue(editable.legalBreaks.size >= 2)
        val cuts = editable.legalBreaks.take(2)
        listOf(listOf(cuts[0], cuts[0]), cuts.reversed(), listOf(editable.start), listOf(editable.end),
            listOf(text.indexOf("sentence"))).forEach { offsets ->
            reject { SpeedSegmentationEditor.edit(base, text, 0, 0, offsets) }
        }
    }

    @Test fun manualVariantsKeepTheirOriginalPlanQuestionsAndUneditedSentences() {
        val text = "Readers compare different explanations and identify assumptions. They review the evidence.\n\nA second paragraph follows."
        val local = plan(text, count = 2)
        val p = ParagraphFocus.paragraphs(text)
        val base = keyed(local.copy(origin = "ai", questions = p.mapIndexed { index, range ->
            SpeedQuestion("q$index", index, "Which statement matches the paragraph?", listOf("Supported", "Unsupported"),
                0, range.start, range.endExclusive, "The original paragraph supports the first choice.")
        }))
        val adjusted = keyed(SpeedSegmentationEditor.edit(base, text, 0, 0, emptyList()))
        assertEquals("manual", adjusted.origin)
        assertEquals(base.planKey, adjusted.basePlanKey)
        assertEquals(base.questions, adjusted.questions)
        assertEquals(base.chunks.filter { it.sentence != 0 }, adjusted.chunks.filter { it.sentence != 0 })
        assertEquals(base.bodyHash, adjusted.bodyHash)
        val view = requireNotNull(SpeedSegmentationEditor.sentence(adjusted, text, 0, base))
        assertTrue(view.hasPersonalEdits)
        assertEquals(base.chunks.filter { it.sentence == 0 }.dropLast(1).map { it.end }, view.suggestedBreaks)
        assertEquals(emptyList<Int>(), view.breaks)
        val revised = SpeedSegmentationEditor.edit(adjusted, text, 0, 0, listOf(view.legalBreaks.last()), base)
        assertEquals(base.planKey, revised.basePlanKey)
        assertSame(base, SpeedSegmentationEditor.restore(revised, text, base))
    }

    @Test fun missingOrUnrelatedBasesCannotMasqueradeAsTheOriginalSuggestion() {
        val text = "Readers compare different explanations and identify assumptions."
        val base = plan(text)
        val adjusted = keyed(SpeedSegmentationEditor.edit(base, text, 0, 0, emptyList()))
        assertNull(SpeedSegmentationEditor.sentence(adjusted, text, 0))
        reject { SpeedSegmentationEditor.restore(adjusted, text, base.copy(articleId = "another")) }
        reject { SpeedSegmentationEditor.restore(adjusted, text, base.copy(planKey = "wrong-key")) }
        assertNull(SpeedSegmentationEditor.sentence(base, text, base.chunks.size))
    }

    @Test fun unchangedBoundariesDoNotCreateAnotherVariant() {
        val text = "Readers compare different explanations and identify assumptions."
        val base = plan(text)
        val editable = requireNotNull(SpeedSegmentationEditor.sentence(base, text, 0))
        assertSame(base, SpeedSegmentationEditor.edit(base, text, 0, 0, editable.breaks))
    }

    private fun keyed(plan: SpeedPlan) = plan.copy(planKey = SpeedChunker.planKey(plan))
    private fun plan(text: String, count: Int = 1) = keyed(SpeedChunker.plan("article", "body", text, SpeedGranularity.L2, 0, count, 1))
    private fun reject(action: () -> Unit) {
        try { action(); fail("Expected invalid segmentation") } catch (_: IllegalArgumentException) { }
    }
}
