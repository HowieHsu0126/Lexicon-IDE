package dev.lexicon.domain

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ReadingStudyScopeTest {
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun body(text: String) = ArticleBody("article", text, hash(text), 1, 1000, "Self-authored test fixture")
    private val longBody = body((0..95).joinToString("\r\n\r\n") { index ->
        "Paragraph $index. " + "The evidence remains useful. ".repeat(13) + "Dr. Lee compares the evidence. 🧭"
    })
    private fun study(body: ArticleBody, scope: StudyScope?, legacy: Boolean = false): ArticleStudy {
        val spans = ParagraphFocus.paragraphs(body.text)
        val indices = scope?.let { it.firstParagraph until it.endParagraphExclusive } ?: spans.indices
        val first = indices.first()
        val unit = ReadingSkimPlanner.sentences(body.text).first { it.paragraphIndex == first }
        val highlightText = body.text.substring(spans[first].start, spans[first].endExclusive)
        val quote = if (highlightText.contains("evidence")) "evidence" else highlightText
        val offset = body.text.indexOf(quote, spans[first].start)
        return ArticleStudy(body.articleId, body.contentHash, "An analysis of the selected material.", "B2",
            listOf(MindMapNode("root", null, "Selected material"), MindMapNode("detail", "root", "Evidence", first)),
            listOf(StudyHighlight("evidence", quote, offset, offset + quote.length, "vocabulary", "The evidence in this passage.")),
            indices.map { StudyParagraph(it, "evidence", "A short paragraph description.") },
            listOf(ReadingQuestion(if (scope == null) "q1" else "sc-0123456789abcdef-q1", "What does the selected passage discuss?",
                "It discusses evidence.", listOf(first), if (legacy) emptyList() else listOf(unit.evidenceRange()))),
            listOf(CultureBackground(quote, "A contextual explanation.", "It is used in this paragraph.", first)),
            "synthetic-fixture", 10, schemaVersion = if (legacy) 1 else 2, scope = scope)
    }

    @Test fun selectionPreservesGlobalUtf16OffsetsCrLfWhitespaceAndEmoji() {
        val raw = "  First paragraph. 🧭\r\n\r\n  Dr. Lee cites evidence. More evidence follows. \r\n\r\n Third paragraph.  "
        val body = body(raw)
        val scope = ReadingStudyScopes.create(body, 1, 3)
        val expected = "Dr. Lee cites evidence. More evidence follows. \r\n\r\n Third paragraph."
        assertEquals(raw.indexOf("Dr. Lee"), scope.start)
        assertEquals(raw.indexOf("Third paragraph.") + "Third paragraph.".length, scope.endExclusive)
        assertEquals(expected, raw.substring(scope.start, scope.endExclusive))
        assertEquals(hash(expected), scope.contentHash)
        assertEquals(3, scope.totalParagraphs)
        ReadingStudyScopes.validate(body, scope)
        assertEquals(raw, body.text)
    }

    @Test fun longArticleScopeAcceptsParagraphsAndOffsetsBeyondFullRequestLimits() {
        assertTrue(longBody.text.length > 24_000)
        assertFalse(ReadingStudyScopes.supportsFull(longBody))
        val scope = ReadingStudyScopes.create(longBody, 84, 87)
        assertTrue(scope.firstParagraph > 80)
        assertTrue(scope.start > 24_000)
        assertTrue(scope.endExclusive - scope.start < 24_000)
        val study = study(longBody, scope)
        ReadingStudyScopes.validateStudy(study, longBody)
        assertEquals(listOf(84, 85, 86), study.paragraphs.map { it.index })
        val range = study.questions.single().evidence.single()
        assertEquals(84, range.paragraphIndex)
        assertTrue(range.start > 24_000)
        assertEquals(range, ReadingSkimPlanner.sentences(longBody.text).single { it.id == range.sentenceId }.evidenceRange())
    }

    @Test fun repeatedTextInEarlierParagraphCannotSatisfySelectedEvidence() {
        val scope = ReadingStudyScopes.create(longBody, 84, 86)
        val good = study(longBody, scope)
        ReadingStudyScopes.validateStudy(good, longBody)
        val outside = ReadingSkimPlanner.sentences(longBody.text).first { it.paragraphIndex == 0 }
        val wrong = good.copy(questions = listOf(good.questions.single().copy(paragraphs = listOf(0), evidence = listOf(outside.evidenceRange()))))
        assertThrows(IllegalArgumentException::class.java) { ReadingStudyScopes.validateStudy(wrong, longBody) }
        val h = good.highlights.single()
        val oldPosition = longBody.text.indexOf(h.quote)
        assertThrows(IllegalArgumentException::class.java) {
            ReadingStudyScopes.validateStudy(good.copy(highlights = listOf(h.copy(start = oldPosition, end = oldPosition + h.quote.length))), longBody)
        }
    }

    @Test fun repeatedOccurrenceInsideTheSelectedParagraphRetainsItsExactLocation() {
        val body = body("Earlier evidence.\n\nThe evidence changed. The evidence changed.")
        val scope = ReadingStudyScopes.create(body, 1, 2)
        val good = study(body, scope)
        val second = body.text.lastIndexOf("evidence")
        val changed = good.copy(highlights = listOf(good.highlights.single().copy(start = second, end = second + 8)))
        ReadingStudyScopes.validateStudy(changed, body)
        assertEquals(second, changed.highlights.single().start)
        val units = ReadingSkimPlanner.sentences(body.text).filter { it.paragraphIndex == 1 }
        assertEquals(2, units.size)
        assertNotEquals(units[0].id, units[1].id)
    }

    @Test fun defaultScopeStartsAtTheCurrentParagraphAndStopsBeforeBudgetOverflow() {
        val body = body("a".repeat(9_000) + "\n\n" + "b".repeat(9_000) + "\n\n" + "c".repeat(9_000))
        val scope = requireNotNull(ReadingStudyScopes.defaultScope(body, 0))
        assertEquals(0, scope.firstParagraph)
        assertEquals(2, scope.endParagraphExclusive)
        assertEquals(18_002, scope.endExclusive - scope.start)
        assertEquals(2, ReadingStudyScopes.defaultScope(body, Int.MAX_VALUE)?.firstParagraph)
        assertEquals(0, ReadingStudyScopes.defaultScope(body, -5, 0)?.firstParagraph)
        assertEquals(1, ReadingStudyScopes.defaultScope(body, -5, 0)?.endParagraphExclusive)
    }

    @Test fun oneOversizedParagraphIsNeverSilentlyTruncatedOrSkipped() {
        val body = body("x".repeat(24_001) + "\n\nShort next paragraph.")
        assertNull(ReadingStudyScopes.defaultScope(body, 0))
        assertThrows(IllegalArgumentException::class.java) { ReadingStudyScopes.create(body, 0, 1) }
        assertEquals(1, ReadingStudyScopes.defaultScope(body, 1)?.firstParagraph)
    }

    @Test fun fullAndScopedBudgetsCountExactlyIncludingInternalSeparators() {
        val exact = body("x".repeat(24_000))
        assertTrue(ReadingStudyScopes.supportsFull(exact))
        assertEquals(24_000, ReadingStudyScopes.create(exact, 0, 1).endExclusive)
        val eighty = body((1..80).joinToString("\n\n") { "A paragraph." })
        assertTrue(ReadingStudyScopes.supportsFull(eighty))
        val eightyOne = body(eighty.text + "\n\nAnother paragraph.")
        assertFalse(ReadingStudyScopes.supportsFull(eightyOne))
        assertThrows(IllegalArgumentException::class.java) { ReadingStudyScopes.create(eightyOne, 0, 81) }
        assertEquals(80, ReadingStudyScopes.create(eightyOne, 1, 81).endParagraphExclusive - 1)
    }

    @Test fun malformedScopeMetadataAndForgedRangesAreRejected() {
        val scope = ReadingStudyScopes.create(longBody, 84, 86)
        val variants = listOf(scope.copy(firstParagraph = -1), scope.copy(endParagraphExclusive = 84),
            scope.copy(endParagraphExclusive = Int.MAX_VALUE), scope.copy(start = -1), scope.copy(start = scope.start + 1),
            scope.copy(endExclusive = scope.endExclusive - 1), scope.copy(totalParagraphs = 999), scope.copy(contentHash = "0".repeat(64)))
        variants.forEach { altered ->
            assertThrows(IllegalArgumentException::class.java) { ReadingStudyScopes.validate(longBody, altered) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReadingStudyScopes.validateMetadata(scope.copy(start = 0, endExclusive = Int.MAX_VALUE))
        }
    }

    @Test fun modifiedBodyOutsideTheSelectionStillInvalidatesTheOriginalSnapshot() {
        val scope = ReadingStudyScopes.create(longBody, 84, 86)
        val tampered = longBody.copy(text = "Z" + longBody.text.drop(1))
        assertFalse(ReadingStudyScopes.supportsFull(tampered))
        assertThrows(IllegalArgumentException::class.java) { ReadingStudyScopes.validate(tampered, scope) }
        val changed = tampered.copy(contentHash = hash(tampered.text))
        assertThrows(IllegalArgumentException::class.java) { ReadingStudyScopes.validateStudy(study(longBody, scope), changed) }
    }

    @Test fun cacheKeysPreserveLegacyWholeArticleNamespaceAndSeparateSelections() {
        val whole = ReadingStudyScopes.cacheKey(longBody.articleId, longBody.contentHash, 2)
        assertEquals("reading-cache:study:2:${hash(longBody.articleId)}:${longBody.contentHash}", whole)
        val first = ReadingStudyScopes.create(longBody, 84, 86)
        val next = ReadingStudyScopes.create(longBody, 85, 87)
        val key = ReadingStudyScopes.cacheKey(longBody.articleId, longBody.contentHash, 2, first)
        assertEquals("$whole:range:84:86", key)
        assertNotEquals(whole, key)
        assertNotEquals(key, ReadingStudyScopes.cacheKey(longBody.articleId, longBody.contentHash, 2, next))
        assertEquals(key, ReadingStudyScopes.cacheKey(longBody.articleId, longBody.contentHash, 2,
            ReadingStudyScopes.create(longBody.copy(cachedAt = 10, expiresAt = 99999), 84, 86)))
    }

    @Test fun labelsAndResultIdentityNeverCallAPartialStudyFullArticle() {
        val first = ReadingStudyScopes.create(longBody, 84, 86)
        assertEquals("全文", ReadingStudyScopes.label(null))
        assertEquals("第 85–86 段", ReadingStudyScopes.label(first))
        assertEquals("第 85 段", ReadingStudyScopes.label(ReadingStudyScopes.create(longBody, 84, 85)))
        val study = study(longBody, first)
        assertEquals(ReadingStudyScopes.identity(study), ReadingStudyScopes.identity(study.copy()))
        assertNotEquals(ReadingStudyScopes.identity(study), ReadingStudyScopes.identity(study.copy(summary = "A changed summary.")))
        assertNotEquals(ReadingStudyScopes.identity(study), ReadingStudyScopes.identity(study.copy(scope = ReadingStudyScopes.create(longBody, 85, 87))))
    }

    @Test fun exactCoverageAndScopedNodeReferencesAreRequired() {
        val scope = ReadingStudyScopes.create(longBody, 84, 86)
        val good = study(longBody, scope)
        val variants = listOf(
            good.copy(paragraphs = good.paragraphs.take(1)),
            good.copy(paragraphs = good.paragraphs + good.paragraphs.first()),
            good.copy(nodes = good.nodes.map { if (it.paragraph != null) it.copy(paragraph = 0) else it }),
            good.copy(nodes = listOf(MindMapNode("a", "b", "Cycle"), MindMapNode("b", "a", "Cycle"))),
            good.copy(nodes = listOf(MindMapNode("root", null, "Root"), MindMapNode("child", "missing", "Missing"))),
            good.copy(backgrounds = listOf(good.backgrounds.single().copy(paragraph = 0))),
            good.copy(scope = null),
        )
        variants.forEach { bad -> assertThrows(IllegalArgumentException::class.java) { ReadingStudyScopes.validateStudy(bad, longBody) } }
    }

    @Test fun scopedQuestionIdsCanCarryTheirNamespaceButEvidenceMustBeExact() {
        val good = study(longBody, ReadingStudyScopes.create(longBody, 84, 86))
        val question = good.questions.single()
        ReadingStudyScopes.validateStudy(good.copy(questions = listOf(question.copy(id = "sc-0123456789abcdef-" + "a".repeat(48)))), longBody)
        val evidence = question.evidence.single()
        val variants = listOf(question.copy(id = "x".repeat(81)), question.copy(evidence = listOf(evidence.copy(endExclusive = evidence.endExclusive - 1))),
            question.copy(evidence = listOf(evidence, evidence)), question.copy(paragraphs = listOf(84, 85)), question.copy(evidence = emptyList()))
        variants.forEach { bad -> assertThrows(IllegalArgumentException::class.java) {
            ReadingStudyScopes.validateStudy(good.copy(questions = listOf(bad)), longBody)
        } }
    }

    @Test fun legacyFullStudyWithoutPreciseEvidenceRemainsReadableButCannotBecomeScoped() {
        val body = body("This paragraph presents the evidence.")
        val old = study(body, null, legacy = true)
        ReadingStudyScopes.validateStudy(old, body)
        assertThrows(IllegalArgumentException::class.java) {
            ReadingStudyScopes.validateStudy(old.copy(scope = ReadingStudyScopes.create(body, 0, 1)), body)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReadingStudyScopes.validateStudy(old.copy(questions = listOf(old.questions.single().copy(id = "x".repeat(49)))), body)
        }
    }

    @Test fun emptyOrInvalidSnapshotsHaveNoSuggestedScope() {
        val empty = body(" \r\n\r\n ")
        assertFalse(ReadingStudyScopes.supportsFull(empty))
        assertNull(ReadingStudyScopes.defaultScope(empty, 0))
        assertThrows(IllegalArgumentException::class.java) { ReadingStudyScopes.create(empty, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { ReadingStudyScopes.defaultScope(longBody.copy(contentHash = "bad"), 0) }
    }
}
