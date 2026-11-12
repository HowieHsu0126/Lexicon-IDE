package dev.lexicon.domain

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class IeltsStudyTest {
    private val feedback = IeltsStudyFeedback("attempt", "q2", "a".repeat(64), "b".repeat(64),
        IeltsReadingType.IDENTIFYING_INFORMATION, IeltsStudyIntent.PRACTICE_AGAIN, 1, 100)
    @Test fun balancedRetainsTheExistingFourFormsAndIds() {
        assertEquals(listOf(IeltsChoiceRequest("q1"), IeltsInformationRequest("q2"), IeltsViewRequest("q3"),
            IeltsCompletionRequest("q4", 1, false)), IeltsStudyPolicy.requests(null))
    }
    @Test fun eachExplicitFocusRequestsExactlyFourOfThatFormWithoutSharingIds() {
        for (kind in IeltsReadingType.entries) {
            val requests = IeltsStudyPolicy.requests(kind)
            assertEquals(listOf("q1", "q2", "q3", "q4"), requests.map { it.questionId })
            assertEquals(1, requests.map { it::class }.distinct().size)
            requests.filterIsInstance<IeltsCompletionRequest>().forEach { assertEquals(1, it.maxWords); assertFalse(it.allowNumbers) }
        }
    }
    @Test fun questionableOrUndoneFeedbackCannotBeAProfileFocus() {
        for (intent in listOf(IeltsStudyIntent.QUESTIONABLE, null)) {
            assertThrows(IllegalArgumentException::class.java) { IeltsStudyPolicy.validate(IeltsStudyProfile(1, 100, feedback.copy(intent = intent))) }
        }
    }
    @Test fun profileAndRequestValidateVersionsDatesAndSourceIdentities() {
        val profile = IeltsStudyProfile(1, 100, feedback)
        IeltsStudyPolicy.validate(profile)
        val request = IeltsStudyRequest("next", "c".repeat(64), profile, 100)
        IeltsStudyPolicy.validate(request)
        assertThrows(IllegalArgumentException::class.java) { IeltsStudyPolicy.validate(request.copy(createdAt = 99)) }
        assertThrows(IllegalArgumentException::class.java) { IeltsStudyPolicy.validate(profile.copy(revision = 0)) }
        assertThrows(IllegalArgumentException::class.java) { IeltsStudyPolicy.validate(feedback.copy(packageHash = "unknown")) }
        assertThrows(IllegalArgumentException::class.java) { IeltsStudyPolicy.validate(feedback.copy(revision = 0)) }
    }

    @Test fun newFocusedDeliveriesRequireForwardEvidenceWhileSavedMaterialRemainsReadable() {
        val root = Json.parseToJsonElement(javaClass.getResource("/ielts-task1/valid.json")!!.readText()).jsonObject
        val original = IeltsExerciseJson.decodeRequest(root.getValue("request").toString())
        val source = IeltsExerciseJson.decodePackage(root.getValue("package").toString())
        val request = original.copy(readingRequests = listOf(IeltsInformationRequest("q1"), IeltsInformationRequest("q2")), writingTask1Request = null)
        fun question(id: String, p: IeltsParagraph) = IeltsInformationQuestion(id, "A statement about the article.", IeltsInformationAnswer.TRUE,
            "Self-authored structure fixture.", listOf(IeltsEvidence(p.paragraphId, p.start, p.end, p.text)))
        val reversed = source.copy(requestSha256 = IeltsExerciseJson.requestHash(request),
            readingQuestions = listOf(question("q1", request.paragraphs.last()), question("q2", request.paragraphs.first())),
            writingTask1 = IeltsWritingTask1("unavailable", "no_verified_visual"))
        IeltsExerciseValidator.validatePackage(request, reversed)
        IeltsPracticeSession.begin(request, reversed, "legacy-readable")
        assertThrows(IllegalArgumentException::class.java) { IeltsStudyPolicy.validateFocusedSequence(request, reversed) }
        val ordered = reversed.copy(readingQuestions = listOf(question("q1", request.paragraphs.first()), question("q2", request.paragraphs.last())))
        IeltsStudyPolicy.validateFocusedSequence(request, ordered)
    }
}
