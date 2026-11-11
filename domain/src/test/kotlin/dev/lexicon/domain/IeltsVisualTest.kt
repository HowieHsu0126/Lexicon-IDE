package dev.lexicon.domain

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class IeltsVisualTest {
    private val root = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/ielts-task1/valid.json")).readText()).jsonObject
    private val request get() = IeltsExerciseJson.decodeRequest(root.getValue("request").toString())
    private val packageValue get() = IeltsExerciseJson.decodePackage(root.getValue("package").toString())
    @Test fun crossLanguageSourceNumbersAndHashesMatch() {
        val task = requireNotNull(packageValue.writingTask1.task)
        IeltsExerciseValidator.validatePackage(request, packageValue)
        val receipt = IeltsVisualContract.validateTask(request, task)
        assertEquals(root.getValue("visual_sha256").jsonPrimitive.content, receipt.visualSha256)
        assertEquals(4, receipt.cells)
        assertEquals("1650", receipt.maximum.toPlainString())
        assertEquals(task, IeltsVisualContract.decode(IeltsVisualContract.encode(task)))
    }
    @Test fun numericSubstringAndUnitConversionAreRejected() {
        val task = requireNotNull(packageValue.writingTask1.task)
        val visual = task.visual
        val first = visual.series[0].cells[0]
        listOf(first.copy(value = "1.2"), first.copy(value = "1", sourceRef = first.sourceRef.copy(
            quote = "1", end = first.sourceRef.start + 1))).forEach { bad ->
            val changed = visual.copy(series = listOf(visual.series[0].copy(cells = listOf(bad, visual.series[0].cells[1])), visual.series[1]))
            assertThrows(IllegalArgumentException::class.java) { IeltsVisualContract.validate(request, changed) }
        }
    }
    @Test fun decimalSpellingIsUnambiguous() {
        listOf("1.0", "01", "1e3", "-0", "NaN", "1000000000000", "1.00001").forEach {
            assertThrows(IllegalArgumentException::class.java) { IeltsVisualContract.number(it) }
        }
        assertEquals("0.25", IeltsVisualContract.number("0.25").toPlainString())
    }
    @Test fun bothWritingDraftsSurviveCommitAndCannotRevealBeforeSave() {
        val pkg = packageValue
        var state = IeltsPracticeSession.begin(request, pkg, "task1-attempt")
        pkg.readingQuestions.forEach { q -> state = state.answer(q.questionId, when(q) {
            is IeltsChoiceQuestion -> "B"
            is IeltsInformationQuestion -> "false"
            is IeltsViewQuestion -> "yes"
            is IeltsCompletionQuestion -> "panels"
        }) }
        state = state.write("Task two opinion.").writeTask1("Task one overview.").prepareSubmission(123)
        assertThrows(IllegalStateException::class.java) { state.answerView() }
        val pending = requireNotNull(state.pendingSubmission())
        assertEquals("Task one overview.", pending.writingTask1Text)
        val encoded = IeltsExerciseJson.encodeSubmission(pending)
        val committed = state.confirmSaved(IeltsExerciseJson.decodeSubmission(encoded)).answerView()
        assertEquals(3, committed.writingTask1Words)
        assertEquals(3, committed.writingWords)
        assertEquals(false, committed.writingTask1MeetsMinimum)
    }
    @Test fun missingTask1AnswerOrChangedHashCannotCommit() {
        val task = requireNotNull(packageValue.writingTask1.task)
        assertThrows(IllegalArgumentException::class.java) { IeltsVisualContract.validateTask(request, task.copy(requestSha256 = "0".repeat(64))) }
        val invalid = packageValue.copy(writingTask1 = IeltsWritingTask1("available", "no_verified_visual", task))
        assertThrows(IllegalArgumentException::class.java) { IeltsExerciseValidator.validatePackage(request, invalid) }
    }
    @Test fun unavailableRemainsUnavailableForInsufficientData() {
        val pkg = packageValue.copy(writingTask1 = IeltsWritingTask1("unavailable", "insufficient_comparable_data"))
        IeltsExerciseValidator.validatePackage(request, pkg)
        val state = IeltsPracticeSession.begin(request, pkg, "missing-chart")
        assertNull(state.writingTask1Text)
        assertThrows(IllegalStateException::class.java) { state.writeTask1("Invented response") }
    }
}
