package dev.lexicon.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class IeltsVisualStorageTest {
    @Test fun twoEssaysPersistAcrossRepositoryReopenAndCommitExactlyOnce() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = InstrumentationRegistry.getInstrumentation().context.assets.open("ielts-task1.json").bufferedReader().use { it.readText() }
        val root = Json.parseToJsonElement(input).jsonObject
        val request = IeltsExerciseJson.decodeRequest(root.getValue("request").toString())
        val pkg = IeltsExerciseJson.decodePackage(root.getValue("package").toString())
        val db = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver()).build()
        try {
            val repository = LocalIeltsPracticeRepository(db)
            val hash = repository.cache(request, pkg)
            val fresh = repository.begin(hash, "visual-storage", 1)
            assertEquals("", fresh.writingTask1Text)
            val responses = pkg.readingQuestions.map { q -> IeltsResponse(q.questionId, when(q) {
                is IeltsChoiceQuestion -> "B"
                is IeltsInformationQuestion -> "true"
                is IeltsViewQuestion -> "not_given"
                is IeltsCompletionQuestion -> "panels"
            }) }
            val draft = repository.saveWritingDrafts(fresh.attemptId, fresh.revision, responses, "Opinion essay", "Chart overview", 2)
            val reopened = LocalIeltsPracticeRepository(db).get(draft.attemptId)!!
            assertEquals("Chart overview", reopened.writingTask1Text)
            assertEquals("Opinion essay", reopened.writingText)
            assertNull(reopened.answerView())
            val legacyEdit = repository.saveDraft(draft.attemptId, draft.revision, responses, "Updated opinion", 3)
            assertEquals("Chart overview", legacyEdit.writingTask1Text)
            val committed = repository.submit(draft.attemptId, legacyEdit.revision, 4)
            val again = repository.submit(draft.attemptId, legacyEdit.revision, 999)
            assertEquals(committed.submission, again.submission)
            assertEquals("Chart overview", committed.submission!!.writingTask1Text)
            assertEquals(2, committed.answerView()!!.writingTask1Words)
        } finally { db.close() }
    }
}
