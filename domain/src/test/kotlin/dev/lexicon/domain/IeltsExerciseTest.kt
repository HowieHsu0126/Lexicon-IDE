package dev.lexicon.domain

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Snapshots below were frozen with Python 3.9.6 tools/ielts_contract.py; all material is synthetic. */
class IeltsExerciseTest {
    private fun request() = IeltsExerciseJson.decodeRequest(REQUEST_JSON)
    private fun exercise() = IeltsExerciseJson.decodePackage(PACKAGE_JSON)
    private fun submission() = IeltsExerciseJson.decodeSubmission(SUBMISSION_JSON)
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid or unrevealed IELTS material to be rejected") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }
    private fun check(exercise: IeltsExercisePackage, request: IeltsExerciseRequest = request()) =
        IeltsExerciseValidator.validatePackage(request, exercise)
    private fun replaceQuestion(index: Int, replacement: IeltsReadingQuestion): IeltsExercisePackage =
        exercise().copy(readingQuestions = exercise().readingQuestions.mapIndexed { i, q -> if (i == index) replacement else q })
    private fun linked(request: IeltsExerciseRequest, exercise: IeltsExercisePackage = exercise()) =
        exercise.copy(requestSha256 = IeltsExerciseJson.requestHash(request))
    private fun completionMaterial(text: String, answer: String, last: Boolean = false): Pair<IeltsExerciseRequest, IeltsExercisePackage> {
        val paragraph = IeltsParagraph("p1", 0, text.length, text)
        val bodyHash = IeltsGatewayProtocol.bodyHash(text)
        val request = request().copy(bodyHash = bodyHash, paragraphs = listOf(paragraph),
            readingRequests = listOf(IeltsCompletionRequest("q4", 8, true)))
        val start = if (last) text.lastIndexOf(answer) else text.indexOf(answer)
        require(start >= 0)
        val completion = (exercise().readingQuestions[3] as IeltsCompletionQuestion).copy(answer = answer,
            maxWords = 8, allowNumbers = true, sourceRefs = listOf(IeltsEvidence("p1", start, start + answer.length, answer)))
        val exercise = exercise().copy(bodyHash = bodyHash, requestSha256 = IeltsExerciseJson.requestHash(request),
            readingQuestions = listOf(completion), writingTask2 = exercise().writingTask2.copy(
                sourceRefs = listOf(IeltsEvidence("p1", 0, text.length, text))))
        return request to exercise
    }
    private fun filled(): IeltsPracticeSession = IeltsPracticeSession.begin(request(), exercise(), "attempt-offline-1")
        .answer("q1", "A").answer("q2", "true").answer("q3", "not_given").answer("q4", "solar panels")
        .write("This is a deliberately short submitted draft.")

    @Test fun pythonRequestPackageSubmissionAndCandidateHashesMatchExactly() {
        val request = request(); val exercise = exercise(); val submission = submission()
        val receipt = check(exercise, request)
        assertEquals("35f8e342640a0c19d147e6dd0fcd15cc26a7656840d4ff13d18e627ddd96e5ef", receipt.requestSha256)
        assertEquals("bc02df361ad4a8529754d58bb357764632558a42be850aee39c5a4532ad32826", receipt.packageSha256)
        assertEquals("ac21b37f06497b4d93ba09530f580b3bd5b420aad311f04b57945f04dd4df47e", IeltsExerciseJson.submissionHash(submission))
        val candidate = IeltsExerciseValidator.candidateView(request, exercise)
        assertEquals("03147ca5cdbcd8a862528d911ff24c0479835a8eb9df2501d36288a12df92b55",
            IeltsExerciseJson.canonicalSha256(IeltsExerciseJson.encodeCandidate(candidate)))
        assertEquals(request, IeltsExerciseJson.decodeRequest(IeltsExerciseJson.encodeRequest(request)))
        assertEquals(exercise, IeltsExerciseJson.decodePackage(IeltsExerciseJson.encodePackage(exercise)))
        assertEquals(candidate, IeltsExerciseJson.decodeCandidate(IeltsExerciseJson.encodeCandidate(candidate)))
        assertTrue(receipt.semanticReviewRequired)
        assertEquals("supplied_paragraphs_only", receipt.sourceScope)
    }

    @Test fun canonicalSortUsesUnicodeCodePointsNotUtf16CharOrder() {
        val raw = "{\"\uE000\":2,\"🔬\":1,\"A\":true,\"a\":1,\"z\":null}"
        assertEquals("{\"A\":true,\"a\":1,\"z\":null,\"\uE000\":2,\"🔬\":1}", IeltsExerciseJson.canonicalJson(raw))
        assertEquals("a4d1a4bb4af739ebc64e0dc77e27fd000a59c2bf4e7e0cf151bd6014cb079dc3", IeltsExerciseJson.canonicalSha256(raw))
    }

    @Test fun canonicalEscapesPreservePythonBooleansIntegersAndNegativeZero() {
        val raw = """{"z":"quote\" slash/ back\\ line\n tab\t","b":false,"a":-0}"""
        assertEquals("e56372f0810db3913611e9bb159d545362354e94c6de4eed6679b0d696bdf0e9", IeltsExerciseJson.canonicalSha256(raw))
        assertNotEquals(IeltsExerciseJson.canonicalSha256("{\"x\":true}"), IeltsExerciseJson.canonicalSha256("{\"x\":1}"))
        assertNotEquals(IeltsExerciseJson.canonicalSha256("{\"x\":1}"), IeltsExerciseJson.canonicalSha256("{\"x\":\"1\"}"))
        assertEquals("{\"x\":9007199254740991}", IeltsExerciseJson.canonicalJson("{\"x\":9007199254740991}"))
    }

    @Test fun floatsNonfiniteNumbersAndOutOfContractIntegersAreRejected() {
        for (number in listOf("1.0", "1e0", "NaN", "Infinity", "-Infinity", "9007199254740992", "01", "+1"))
            rejects { IeltsExerciseJson.canonicalJson("{\"x\":$number}") }
        rejects { IeltsExerciseJson.canonicalSha256(JsonPrimitive(Double.NaN)) }
        rejects { IeltsExerciseJson.canonicalSha256(JsonPrimitive(1.5)) }
    }

    @Test fun duplicateFieldsIncludingEscapedNamesAreRejectedBeforeDecoding() {
        for (raw in listOf("""{"x":1,"x":2}""", """{"x":1,"\u0078":2}""",
            """{"outer":{"x":1,"x":2}}""", """{"x":[1,]}""", """{"x":1,}"""))
            rejects { IeltsExerciseJson.canonicalJson(raw) }
    }

    @Test fun primitiveCoercionExplicitNullUnknownFieldAndMissingSchemaAreRejected() {
        rejects { IeltsExerciseJson.decodeRequest(REQUEST_JSON.replace("\"start\": 0", "\"start\": \"0\"")) }
        rejects { IeltsExerciseJson.decodeRequest(REQUEST_JSON.replace("\"allow_numbers\": false", "\"allow_numbers\": 0")) }
        rejects { IeltsExerciseJson.decodeSubmission(SUBMISSION_JSON.replace("\"submitted_at\": 1000", "\"submitted_at\": true")) }
        rejects { IeltsExerciseJson.decodePackage(PACKAGE_JSON.replace("\"prompt\": \"The council approved ____.\"", "\"unknown_key\": true, \"prompt\": \"The council approved ____.\"")) }
        rejects { IeltsExerciseJson.decodeRequest(REQUEST_JSON.replace("\"schema\": \"lexicon.ielts.request.v1\",", "")) }
        val info = "\"answer\": \"true\","
        rejects { IeltsExerciseJson.decodePackage(PACKAGE_JSON.replace(info, "$info \"reviewed_paragraph_ids\": null,")) }
    }

    @Test fun unpairedUnicodeTooDeepAndOversizedJsonAreRejected() {
        rejects { IeltsExerciseJson.canonicalJson("\"\\ud800\"") }
        rejects { IeltsExerciseJson.canonicalJson("\"\\udc00\"") }
        rejects { IeltsExerciseJson.canonicalJson("[".repeat(34) + "0" + "]".repeat(34)) }
        rejects { IeltsExerciseJson.canonicalJson(" ".repeat(IeltsContract.MAX_JSON_BYTES + 1)) }
    }

    @Test fun typedQuestionWhitelistDoesNotCollapseInformationAndWriterViews() {
        val questions = exercise().readingQuestions
        assertTrue(questions[0] is IeltsChoiceQuestion)
        assertTrue(questions[1] is IeltsInformationQuestion)
        assertTrue(questions[2] is IeltsViewQuestion)
        assertTrue(questions[3] is IeltsCompletionQuestion)
        rejects { IeltsExerciseJson.decodePackage(PACKAGE_JSON.replace("\"answer\": \"true\"", "\"answer\": \"yes\"")) }
        rejects { IeltsExerciseJson.decodePackage(PACKAGE_JSON.replace("\"type\": \"multiple_choice\"", "\"type\": \"unimplemented_type\"")) }
    }

    @Test fun requestOffsetsCountUtf16AndCannotOverflowTheirBounds() {
        val request = request(); val first = request.paragraphs.first()
        assertEquals(2, "🔬".length)
        rejects { IeltsExerciseValidator.validateRequest(request.copy(paragraphs = listOf(first.copy(end = first.end - 1)))) }
        rejects { IeltsExerciseValidator.validateRequest(request.copy(paragraphs = listOf(first.copy(start = Int.MAX_VALUE, end = Int.MIN_VALUE + 1, text = "ab")))) }
        rejects { IeltsExerciseValidator.validateRequest(request.copy(paragraphs = request.paragraphs.reversed())) }
        rejects { IeltsExerciseValidator.validateRequest(request.copy(paragraphs = emptyList())) }
    }

    @Test fun wholeEmojiReferenceWorksAndHalfPairFails() {
        val choice = exercise().readingQuestions[0] as IeltsChoiceQuestion
        val ref = IeltsEvidence("p1", 0, 2, "🔬")
        check(replaceQuestion(0, choice.copy(sourceRefs = listOf(ref))))
        rejects { check(replaceQuestion(0, choice.copy(sourceRefs = listOf(ref.copy(end = 1))))) }
    }

    @Test fun repeatedSentenceKeepsDeclaredOccurrenceInsteadOfFirstMatchingText() {
        val choice = exercise().readingQuestions[0] as IeltsChoiceQuestion
        val info = exercise().readingQuestions[1] as IeltsInformationQuestion
        assertEquals(choice.sourceRefs.single().quote, info.sourceRefs.single().quote)
        assertTrue(choice.sourceRefs.single().start > info.sourceRefs.single().end)
        check(exercise())
        val wrong = choice.sourceRefs.single().copy(quote = "A similarly sized but invented sentence.")
        rejects { check(replaceQuestion(0, choice.copy(sourceRefs = listOf(wrong)))) }
    }

    @Test fun referencesRejectForeignParagraphNegativeZeroLengthAndDuplicateRanges() {
        val choice = exercise().readingQuestions[0] as IeltsChoiceQuestion
        val ref = choice.sourceRefs.single()
        for (wrong in listOf(ref.copy(paragraphId = "other"), ref.copy(start = -1), ref.copy(end = ref.start), ref.copy(end = 99999)))
            rejects { check(replaceQuestion(0, choice.copy(sourceRefs = listOf(wrong)))) }
        rejects { check(replaceQuestion(0, choice.copy(sourceRefs = listOf(ref, ref)))) }
    }

    @Test fun requestIdentitySourceHashAndQuestionTypeMustMatch() {
        val exercise = exercise()
        for (wrong in listOf(exercise.copy(requestId = "other"), exercise.copy(articleId = "other"),
            exercise.copy(bodyHash = "a".repeat(64)), exercise.copy(requestSha256 = "a".repeat(64)))) rejects { check(wrong) }
        val changed = request().copy(styleSources = request().styleSources.map { it.copy(summary = "Different official-format summary") })
        rejects { check(exercise, changed) }
        val wanted = request().copy(readingRequests = listOf(IeltsViewRequest("q1")) + request().readingRequests.drop(1))
        rejects { check(linked(wanted), wanted) }
    }

    @Test fun fourOptionsAndClientQuestionOrderRemainFixed() {
        val choice = exercise().readingQuestions[0] as IeltsChoiceQuestion
        rejects { check(replaceQuestion(0, choice.copy(options = choice.options.dropLast(1)))) }
        rejects { check(replaceQuestion(0, choice.copy(numberOfAnswers = 2))) }
        rejects { check(replaceQuestion(0, choice.copy(options = choice.options.reversed()))) }
        rejects { check(exercise().copy(readingQuestions = exercise().readingQuestions.reversed())) }
        rejects { check(exercise().copy(readingQuestions = emptyList())) }
    }

    @Test fun optionUniquenessUsesPythonCompatibleWhitespaceAndUnicodeCaseFolding() {
        val choice = exercise().readingQuestions[0] as IeltsChoiceQuestion
        for ((left, right) in listOf(" solar\tpanels " to "SOLAR PANELS", "Straße" to "STRASSE", "σ" to "ς", "ﬀ" to "ff", "Ꭰ" to "ꭰ")) {
            val options = choice.options.mapIndexed { i, option -> option.copy(text = when (i) { 0 -> left; 1 -> right; else -> option.text }) }
            rejects { check(replaceQuestion(0, choice.copy(options = options))) }
        }
        val distinct = choice.options.mapIndexed { i, option -> option.copy(text = when (i) { 0 -> "ı"; 1 -> "i"; else -> option.text }) }
        check(replaceQuestion(0, choice.copy(options = distinct)))
    }

    @Test fun notGivenRequiresScopeWithoutInventedPositiveQuoteAndRemainsUnverified() {
        val views = exercise().readingQuestions[2] as IeltsViewQuestion
        rejects { check(replaceQuestion(2, views.copy(reviewedParagraphIds = listOf("p1")))) }
        rejects { check(replaceQuestion(2, views.copy(absenceJustification = ""))) }
        rejects { check(replaceQuestion(2, views.copy(sourceRefs = exercise().readingQuestions[0].sourceRefs))) }
        val positive = exercise().readingQuestions[1] as IeltsInformationQuestion
        rejects { check(replaceQuestion(1, positive.copy(sourceRefs = emptyList()))) }
        rejects { check(replaceQuestion(1, positive.copy(absenceJustification = "not applicable"))) }
        assertTrue(check(exercise()).semanticReviewRequired)
    }

    @Test fun extractiveAnswerNeedsExactFullReferenceAndPerItemWordRule() {
        val completion = exercise().readingQuestions[3] as IeltsCompletionQuestion
        rejects { check(replaceQuestion(3, completion.copy(answer = "clean energy"))) }
        rejects { check(replaceQuestion(3, completion.copy(normalizationRule = "unspecified"))) }
        rejects { check(replaceQuestion(3, completion.copy(prompt = "No blank."))) }
        rejects { check(replaceQuestion(3, completion.copy(prompt = "Two ____ and ____ blanks."))) }
        rejects { check(replaceQuestion(3, completion.copy(answer = "3 panels"))) }
        val request = request().copy(readingRequests = request().readingRequests.map { if (it is IeltsCompletionRequest) it.copy(maxWords = 1) else it })
        rejects { check(linked(request, replaceQuestion(3, completion.copy(maxWords = 1))), request) }
    }

    @Test fun completionRejectsPrefixesSuffixesAndCutsInsideDeclaredTokens() {
        val cases = listOf("🔬 reflective surfaces" to "reflect", "sunlight" to "light",
            "well-maintained" to "well", "well‑maintained" to "maintained", "owner's" to "owner",
            "owner’s" to "owner", "can't" to "can", "CO2" to "CO", "m²" to "m", "3.5" to "3", "𐐀rea" to "rea")
        for ((text, answer) in cases) {
            val (request, exercise) = completionMaterial(text, answer)
            rejects { check(exercise, request) }
        }
    }

    @Test fun completionAllowsWholeTokensWithSurroundingPunctuationAndWhitespace() {
        val cases = listOf("🔬 'reflect' sunlight" to "'reflect'", "(well-maintained) roofs" to "(well-maintained)",
            "owner’s" to "owner’s", "solar,\n panels" to "solar,\n panels", "low–cost" to "low",
            "low—cost" to "cost", "10%" to "10%", "𐐀rea studies" to "𐐀rea")
        for ((text, answer) in cases) {
            val (request, exercise) = completionMaterial(text, answer)
            check(exercise, request)
        }
    }

    @Test fun wholeMatchingOccurrenceDoesNotHideAnotherPartialTokenReference() {
        val text = "🔬 reflective coatings reflect sunlight."
        val (request, exercise) = completionMaterial(text, "reflect", last = true)
        check(exercise, request)
        val question = exercise.readingQuestions.single() as IeltsCompletionQuestion
        val start = text.indexOf("reflect")
        val cut = IeltsEvidence("p1", start, start + "reflect".length, "reflect")
        assertTrue(question.sourceRefs.single().start > cut.end)
        rejects { check(exercise.copy(readingQuestions = listOf(question.copy(sourceRefs = listOf(cut) + question.sourceRefs))), request) }
    }

    @Test fun completeSourceTokenHelperUsesUtf16AndRejectsSurrogateSplits() {
        val text = "🔬 𐐀rea reflects"
        assertTrue(IeltsExerciseValidator.completeSourceTokens(text, 3, 8))
        assertFalse(IeltsExerciseValidator.completeSourceTokens(text, 5, 8))
        rejects { IeltsExerciseValidator.completeSourceTokens(text, 4, 8) }
        assertFalse(IeltsExerciseValidator.completeSourceTokens("...", 0, 3))
    }

    @Test fun writingAndSourceIdentifiersHaveExplicitBoundaries() {
        rejects { check(exercise().copy(writingTask2 = exercise().writingTask2.copy(minimumWords = 150))) }
        rejects { check(exercise().copy(writingTask2 = exercise().writingTask2.copy(suggestedMinutes = 20))) }
        rejects { check(exercise().copy(writingTask1 = IeltsUnavailableTask1("available", "invented_chart"))) }
        rejects { check(exercise().copy(styleSourceIds = listOf("invented"))) }
        val choice = exercise().readingQuestions[0] as IeltsChoiceQuestion
        rejects { check(replaceQuestion(0, choice.copy(explanation = "See https://invented.example/evidence"))) }
    }

    @Test fun candidateTypeHasNoAnswerExplanationOrEvidenceFields() {
        val view = IeltsExerciseValidator.candidateView(request(), exercise())
        val raw = IeltsExerciseJson.encodeCandidate(view)
        for (private in listOf("SECRET_RATIONALE", "PRIVATE_", "\"answer\"", "\"explanation\"", "\"source_refs\"", "\"absence_justification\""))
            assertFalse(raw.contains(private))
        val decoded = IeltsExerciseJson.decodeCandidate(raw)
        IeltsExerciseValidator.validateCandidate(request(), exercise(), decoded)
        assertEquals(IeltsInformationAnswer.entries, (decoded.readingQuestions[1] as IeltsCandidateInformation).responseValues)
        rejects { IeltsExerciseValidator.validateCandidate(request(), exercise(), decoded.copy(packageSha256 = "b".repeat(64))) }
    }

    @Test fun typedSubmissionChecksPackageStatusAndCompleteResponseOrder() {
        val submission = submission()
        IeltsExerciseValidator.validateSubmission(request(), exercise(), submission)
        for (wrong in listOf(submission.copy(status = "draft"), submission.copy(packageSha256 = "b".repeat(64)),
            submission.copy(requestId = "other"), submission.copy(readingAnswers = submission.readingAnswers.dropLast(1)),
            submission.copy(readingAnswers = submission.readingAnswers.reversed()))) rejects {
                IeltsExerciseValidator.validateSubmission(request(), exercise(), wrong)
            }
    }

    @Test fun draftAndPendingSaveCannotRevealReferenceAnswers() {
        val empty = IeltsPracticeSession.begin(request(), exercise(), "practice-test")
        rejects { empty.answerView() }; rejects { empty.prepareSubmission(1000) }
        val partial = empty.answer("q1", "A")
        rejects { partial.prepareSubmission(1000) }
        val pending = filled().prepareSubmission(1000)
        assertEquals(IeltsPracticeStage.AWAITING_SAVE, pending.stage)
        assertEquals(submission(), pending.pendingSubmission())
        rejects { pending.answerView() }; rejects { pending.answer("q1", "B") }; rejects { pending.write("changed") }
    }

    @Test fun onlyExactReadBackOfPreparedSubmissionUnlocksAnswerView() {
        val pending = filled().prepareSubmission(1000)
        val proposed = requireNotNull(pending.pendingSubmission())
        rejects { pending.confirmSaved(proposed.copy(attemptId = "different-attempt")) }
        rejects { pending.confirmSaved(proposed.copy(submittedAt = 1001)) }
        rejects { pending.confirmSaved(proposed.copy(writingTask2Text = "Other draft")) }
        val confirmed = pending.confirmSaved(proposed)
        assertEquals(IeltsPracticeStage.SUBMITTED, confirmed.stage)
        assertEquals("A", confirmed.answerView().readingAnswers.first().referenceAnswer)
        assertTrue(confirmed.answerView().semanticReviewRequired)
        assertNull(confirmed.answerView().officialBandScore)
        rejects { confirmed.answer("q1", "B") }; rejects { confirmed.prepareSubmission(2000) }; rejects { confirmed.confirmSaved(proposed) }
    }

    @Test fun failedSaveKeepsDraftAndRequiresAnotherConfirmedSave() {
        val pending = filled().prepareSubmission(1000)
        val restored = pending.saveFailed()
        assertEquals(IeltsPracticeStage.DRAFT, restored.stage)
        assertEquals(4, restored.answeredCount)
        assertEquals("This is a deliberately short submitted draft.", restored.writingText)
        assertNull(restored.pendingSubmission())
        rejects { restored.answerView() }
        val retried = restored.answer("q1", "B").prepareSubmission(1001)
        val confirmed = retried.confirmSaved(requireNotNull(retried.pendingSubmission()))
        assertEquals("B", confirmed.answerView().readingAnswers.first().submittedAnswer)
        assertEquals("A", confirmed.answerView().readingAnswers.first().referenceAnswer)
    }

    @Test fun overlongStudentCompletionAndShortWritingAreRetainedWithoutZeroBandClaims() {
        val pending = filled().answer("q4", "several expensive solar panels").prepareSubmission(1000)
        val view = pending.confirmSaved(requireNotNull(pending.pendingSubmission())).answerView()
        val answer = view.readingAnswers.last()
        assertEquals("several expensive solar panels", answer.submittedAnswer)
        assertEquals(4, answer.wordCount)
        assertEquals(false, answer.withinWordLimit)
        assertFalse(view.writingMeetsMinimum)
        assertNull(view.officialBandScore)
    }

    @Test fun wordRuleHandlesHyphensApostrophesAndDecimalsWithoutCountingEmoji() {
        assertEquals(3, IeltsExerciseValidator.wordCount("solar-powered panels don't"))
        assertEquals(2, IeltsExerciseValidator.wordCount("3.5 million"))
        assertEquals(0, IeltsExerciseValidator.wordCount("... 🔬"))
        val pending = filled().write(List(250) { "word" }.joinToString(" ")).prepareSubmission(1000)
        assertTrue(pending.confirmSaved(requireNotNull(pending.pendingSubmission())).answerView().writingMeetsMinimum)
    }

    @Test fun mutableCallerCollectionsAndReturnedViewsCannotChangeSessionMaterial() {
        val paragraphs = request().paragraphs.toMutableList()
        val questions = exercise().readingQuestions.toMutableList()
        val input = request().copy(paragraphs = paragraphs)
        val packageInput = exercise().copy(readingQuestions = questions)
        val session = IeltsPracticeSession.begin(input, packageInput, "frozen-session")
        val before = session.candidate()
        paragraphs[0] = paragraphs[0].copy(text = "changed outside the session")
        questions[0] = (questions[0] as IeltsChoiceQuestion).copy(prompt = "changed outside the session")
        assertEquals(before, session.candidate())
        val view = session.candidate()
        (view.paragraphs as MutableList<IeltsParagraph>)[0] = view.paragraphs[0].copy(text = "changed returned view")
        assertEquals(before, session.candidate())
    }

    private val REQUEST_JSON = """
        {
          "schema": "lexicon.ielts.request.v1",
          "request_id": "request-offline-1",
          "article_id": "article-offline-1",
          "body_hash": "4de3d79abb8114e9ae31c279fc404256fff53605c47e7e544745098ee0f6837a",
          "paragraphs": [
            {
              "paragraph_id": "p1",
              "start": 0,
              "end": 72,
              "text": "🔬 The council approved solar panels. The council approved solar panels."
            },
            {
              "paragraph_id": "p2",
              "start": 74,
              "end": 143,
              "text": "The writer argues that public investment should support clean energy."
            }
          ],
          "style_blueprint_sha256": "5d63cf2c109c250b9045f1e05a6f68caaccfb6994360e20d485c95869ac0fe9c",
          "style_sources": [
            {
              "source_id": "style-fixture",
              "sha256": "a712350bade2884871a31eaf7e0298090d9db41163b36aa18f368e3667d9fb4d",
              "summary": "Format metadata fixture, not an official source retrieval.",
              "scope": "official_public_sample_format"
            }
          ],
          "reading_requests": [
            {
              "question_id": "q1",
              "type": "multiple_choice"
            },
            {
              "question_id": "q2",
              "type": "identifying_information"
            },
            {
              "question_id": "q3",
              "type": "identifying_writers_views"
            },
            {
              "question_id": "q4",
              "type": "short_text_completion",
              "max_words": 2,
              "allow_numbers": false
            }
          ],
          "writing_task2_request": {
            "task_id": "w2",
            "task_type": "extent_of_agreement"
          }
        }
    """.trimIndent()

    private val PACKAGE_JSON = """
        {
          "schema": "lexicon.ielts.package.v1",
          "request_id": "request-offline-1",
          "article_id": "article-offline-1",
          "body_hash": "4de3d79abb8114e9ae31c279fc404256fff53605c47e7e544745098ee0f6837a",
          "request_sha256": "35f8e342640a0c19d147e6dd0fcd15cc26a7656840d4ff13d18e627ddd96e5ef",
          "label": "AI 模拟练习",
          "style_source_ids": [
            "style-fixture"
          ],
          "reading_questions": [
            {
              "question_id": "q1",
              "type": "multiple_choice",
              "prompt": "What did the council approve?",
              "options": [
                {
                  "option_id": "A",
                  "text": "Solar panels"
                },
                {
                  "option_id": "B",
                  "text": "A school"
                },
                {
                  "option_id": "C",
                  "text": "A railway"
                },
                {
                  "option_id": "D",
                  "text": "A hospital"
                }
              ],
              "number_of_answers": 1,
              "answer": "A",
              "explanation": "SECRET_RATIONALE_Q1",
              "source_refs": [
                {
                  "paragraph_id": "p1",
                  "start": 38,
                  "end": 72,
                  "quote": "The council approved solar panels."
                }
              ]
            },
            {
              "question_id": "q2",
              "type": "identifying_information",
              "prompt": "The council approved equipment associated with solar energy.",
              "answer": "true",
              "explanation": "PRIVATE_INFORMATION_EXPLANATION",
              "source_refs": [
                {
                  "paragraph_id": "p1",
                  "start": 3,
                  "end": 37,
                  "quote": "The council approved solar panels."
                }
              ]
            },
            {
              "question_id": "q3",
              "type": "identifying_writers_views",
              "prompt": "The writer believes the budget should double next year.",
              "answer": "not_given",
              "explanation": "PRIVATE_VIEW_EXPLANATION",
              "source_refs": [],
              "reviewed_paragraph_ids": [
                "p1",
                "p2"
              ],
              "absence_justification": "PRIVATE_NG_JUSTIFICATION"
            },
            {
              "question_id": "q4",
              "type": "short_text_completion",
              "prompt": "The council approved ____.",
              "max_words": 2,
              "allow_numbers": false,
              "normalization_rule": "lexicon-extractive-v1",
              "answer": "solar panels",
              "explanation": "PRIVATE_COMPLETION_EXPLANATION",
              "source_refs": [
                {
                  "paragraph_id": "p1",
                  "start": 24,
                  "end": 36,
                  "quote": "solar panels"
                }
              ]
            }
          ],
          "writing_task2": {
            "task_id": "w2",
            "task_type": "extent_of_agreement",
            "prompt": "Public funds should prioritise clean energy. To what extent do you agree or disagree?",
            "minimum_words": 250,
            "suggested_minutes": 40,
            "source_refs": [
              {
                "paragraph_id": "p2",
                "start": 74,
                "end": 143,
                "quote": "The writer argues that public investment should support clean energy."
              }
            ]
          },
          "writing_task1": {
            "status": "unavailable",
            "reason": "no_verified_visual"
          }
        }
    """.trimIndent()

    private val SUBMISSION_JSON = """
        {
          "schema": "lexicon.ielts.submission.v1",
          "attempt_id": "attempt-offline-1",
          "request_id": "request-offline-1",
          "package_sha256": "bc02df361ad4a8529754d58bb357764632558a42be850aee39c5a4532ad32826",
          "status": "submitted",
          "submitted_at": 1000,
          "reading_answers": [
            {
              "question_id": "q1",
              "answer": "A"
            },
            {
              "question_id": "q2",
              "answer": "true"
            },
            {
              "question_id": "q3",
              "answer": "not_given"
            },
            {
              "question_id": "q4",
              "answer": "solar panels"
            }
          ],
          "writing_task2_text": "This is a deliberately short submitted draft."
        }
    """.trimIndent()
}
