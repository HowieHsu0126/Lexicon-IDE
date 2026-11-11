package dev.lexicon.data

import android.content.Context
import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic practice in real Room. No model/API, credentials, official corpus, or production DB. */
@RunWith(AndroidJUnit4::class)
class IeltsPracticeStorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val first = "The council approved solar panels."
    private val second = "The writer argues that public investment should support clean energy."
    private val text = "$first\n\n$second"
    private val paragraphs = listOf(IeltsParagraph("p1", 0, first.length, first),
        IeltsParagraph("p2", first.length + 2, text.length, second))
    private val request = IeltsExerciseRequest(IeltsContract.REQUEST, "storage-request", "storage-article", sha256(text), paragraphs,
        "a".repeat(64), listOf(IeltsStyleSource("synthetic-format", "b".repeat(64),
            "A synthetic storage fixture, not an official source retrieval.", IeltsStyleScope.PUBLIC_SAMPLE_FORMAT)),
        listOf(IeltsChoiceRequest("q1"), IeltsInformationRequest("q2"), IeltsViewRequest("q3"), IeltsCompletionRequest("q4", 2, false)),
        IeltsWritingRequest("w2", IeltsWritingType.EXTENT_OF_AGREEMENT))
    private val firstRef = IeltsEvidence("p1", 0, first.length, first)
    private val secondRef = IeltsEvidence("p2", first.length + 2, text.length, second)
    private val exercise = IeltsExercisePackage(IeltsContract.PACKAGE, request.requestId, request.articleId, request.bodyHash,
        IeltsExerciseJson.requestHash(request), IeltsContract.LABEL, listOf("synthetic-format"), listOf(
            IeltsChoiceQuestion("q1", "What did the council approve?", listOf(
                IeltsOption(IeltsChoice.A, "Solar panels"), IeltsOption(IeltsChoice.B, "A school"),
                IeltsOption(IeltsChoice.C, "A railway"), IeltsOption(IeltsChoice.D, "A hospital")),
                1, IeltsChoice.A, "The passage names solar panels.", listOf(firstRef)),
            IeltsInformationQuestion("q2", "The council approved equipment associated with solar energy.",
                IeltsInformationAnswer.TRUE, "The decision is explicit in the passage.", listOf(firstRef)),
            IeltsViewQuestion("q3", "The writer wants the budget to double next year.", IeltsViewAnswer.NOT_GIVEN,
                "The writer does not specify a budget amount.", emptyList(), listOf("p1", "p2"),
                "Neither supplied paragraph states a future budget amount."),
            IeltsCompletionQuestion("q4", "The council approved ____.", 2, false, IeltsContract.WORD_RULE,
                "solar panels", "The exact phrase names the approved equipment.", listOf(IeltsEvidence("p1",
                    first.indexOf("solar panels"), first.indexOf("solar panels") + "solar panels".length, "solar panels"))),
        ), IeltsWritingTask2("w2", IeltsWritingType.EXTENT_OF_AGREEMENT,
            "Public investment should support clean energy. To what extent do you agree or disagree?",
            250, 40, listOf(secondRef)), IeltsUnavailableTask1("unavailable", "no_verified_visual"))
    private val answers = listOf(IeltsResponse("q1", "B"), IeltsResponse("q2", "true"),
        IeltsResponse("q3", "not_given"), IeltsResponse("q4", "solar panels"))

    private fun database() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).build()
    private fun fileDatabase(file: File) = Room.databaseBuilder<LexiconDatabase>(context, file.absolutePath)
        .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
    private fun repo(db: LexiconDatabase) = LocalIeltsPracticeRepository(db)
    private suspend fun begin(repository: IeltsPracticeRepository, id: String = "attempt-one", now: Long = 100): IeltsSavedPractice =
        repository.begin(repository.cache(request, exercise), id, now)
    private suspend fun filled(repository: IeltsPracticeRepository, id: String = "attempt-one"): IeltsSavedPractice {
        val empty = begin(repository, id)
        return repository.saveDraft(id, empty.revision, answers, "An intentionally short retained student draft.", 200)
    }
    private suspend fun fails(code: String, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: ApiFailure) { assertEquals(code, error.code) }
    }
    private suspend fun rejects(action: suspend () -> Unit) {
        try { action(); fail("Expected an invalid practice payload to fail") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }

    @Test fun cacheAndBeginAreIdempotentWithoutReplacingOriginalMaterialOrTimestamp() = runBlocking {
        val db = database()
        try {
            val repository = repo(db)
            val opened = begin(repository)
            val before = snapshot(db)
            assertEquals(opened.packageHash, repository.cache(request, exercise))
            assertEquals(opened, repo(db).begin(opened.packageHash, opened.attemptId, 900))
            assertEquals(before, snapshot(db))
            assertEquals(request, opened.request); assertEquals(exercise, opened.exercise)
            assertEquals(1L, opened.revision); assertNull(opened.answerView())
            val history = repository.observeHistory().first().single()
            assertEquals(IeltsPracticeHistoryStatus.DRAFT, history.status)
            assertEquals(0, history.answeredCount); assertEquals(4, history.questionCount)
            assertNull(history.submittedAt)
        } finally { db.close() }
    }

    @Test fun oneAttemptCannotBeReboundToADifferentValidCachedPackage() = runBlocking {
        val db = database()
        try {
            val repository = repo(db); val original = begin(repository)
            val changed = exercise.copy(writingTask2 = exercise.writingTask2.copy(
                prompt = "Governments should prioritize clean energy. To what extent do you agree or disagree?"))
            val otherHash = repository.cache(request, changed)
            assertNotEquals(original.packageHash, otherHash)
            val before = snapshot(db)
            fails("ielts_attempt_conflict") { repository.begin(otherHash, original.attemptId, 900) }
            assertEquals(original, repository.get(original.attemptId)); assertEquals(before, snapshot(db))
        } finally { db.close() }
    }

    @Test fun partialDraftRestoresAcrossInstancesAndOmittedResponseClearsOnlyThatAnswer() = runBlocking {
        val db = database()
        try {
            val opened = begin(repo(db))
            val firstDraft = repo(db).saveDraft(opened.attemptId, 1,
                listOf(answers[1], answers[0]), "Original writing", 200)
            assertEquals(listOf(answers[0], answers[1]), firstDraft.draftResponses)
            assertEquals(firstDraft, repo(db).get(opened.attemptId))
            val cleared = repo(db).saveDraft(opened.attemptId, firstDraft.revision, listOf(answers[1]), "Original writing", 300)
            assertEquals(listOf(answers[1]), cleared.draftResponses)
            assertEquals(firstDraft.revision + 1, cleared.revision)
            assertNull(cleared.answerView()); assertEquals(request, cleared.request)
            assertEquals(exercise, cleared.exercise)
            val before = snapshot(db)
            assertEquals(cleared, repo(db).saveDraft(cleared.attemptId, cleared.revision, cleared.draftResponses, cleared.writingText, 900))
            assertEquals(before, snapshot(db))
        } finally { db.close() }
    }

    @Test fun simultaneousIndependentRepositoriesAllowExactlyOneWriteAtExpectedRevision() = runBlocking {
        val db = database()
        try {
            val opened = begin(repo(db))
            val start = CompletableDeferred<Unit>()
            val results = coroutineScope {
                val workers = listOf("A", "B").map { choice -> async(Dispatchers.Default) {
                    start.await()
                    try { repo(db).saveDraft(opened.attemptId, opened.revision, listOf(IeltsResponse("q1", choice)), "", 200); "saved" }
                    catch (error: ApiFailure) { error.code }
                } }
                start.complete(Unit); workers.awaitAll()
            }
            assertEquals(1, results.count { it == "saved" })
            assertEquals(1, results.count { it == "ielts_revision_conflict" })
            val current = requireNotNull(repo(db).get(opened.attemptId))
            assertEquals(2L, current.revision); assertEquals(1, current.draftResponses.size)
            assertEquals(exercise, current.exercise)
        } finally { db.close() }
    }

    @Test fun staleDraftAndStaleSubmitCannotOverwriteNewerSavedInput() = runBlocking {
        val db = database()
        try {
            val repository = repo(db); val complete = filled(repository)
            val changed = repository.saveDraft(complete.attemptId, complete.revision, answers, "A later draft", 400)
            val before = snapshot(db)
            fails("ielts_revision_conflict") { repository.saveDraft(complete.attemptId, complete.revision, answers, "Stale", 500) }
            fails("ielts_revision_conflict") { repository.submit(complete.attemptId, complete.revision, 500) }
            assertEquals(changed, repository.get(complete.attemptId)); assertEquals(before, snapshot(db))
        } finally { db.close() }
    }

    @Test fun incompleteOrInvalidInputRetainsTheExistingDraftAndDoesNotRevealAnswers() = runBlocking {
        val db = database()
        try {
            val repository = repo(db); val opened = begin(repository)
            val partial = repository.saveDraft(opened.attemptId, 1, listOf(answers.first()), "Keep my draft", 200)
            val before = snapshot(db)
            rejects { repository.submit(partial.attemptId, partial.revision, 300) }
            for (invalid in listOf(listOf(IeltsResponse("q1", "Z")), listOf(IeltsResponse("q2", "yes")),
                    listOf(IeltsResponse("missing", "A")), listOf(answers[0], answers[0]), listOf(IeltsResponse("q4", "")))) {
                rejects { repository.saveDraft(partial.attemptId, partial.revision, invalid, "changed", 300) }
            }
            rejects { repository.saveDraft(partial.attemptId, partial.revision, answers, "x".repeat(30_001), 300) }
            assertEquals(partial, repository.get(partial.attemptId)); assertNull(repository.get(partial.attemptId)!!.answerView())
            assertEquals(before, snapshot(db))
        } finally { db.close() }
    }

    @Test fun committedSubmissionIsReadBackAndRestoredWithExactEvidenceAndStudentText() = runBlocking {
        val db = database()
        try {
            val repository = repo(db); val complete = filled(repository)
            val submitted = repository.submit(complete.attemptId, complete.revision, 300)
            val reread = requireNotNull(repo(db).get(complete.attemptId))
            assertEquals(submitted, reread); assertTrue(reread.submitted)
            assertEquals(complete.revision + 1, reread.revision)
            assertEquals(answers, reread.submission!!.readingAnswers)
            assertEquals(complete.writingText, reread.submission!!.writingTask2Text)
            val revealed = requireNotNull(reread.answerView())
            assertEquals("B", revealed.readingAnswers[0].submittedAnswer)
            assertEquals("A", revealed.readingAnswers[0].referenceAnswer)
            assertEquals(listOf(firstRef), revealed.readingAnswers[0].sourceRefs)
            assertNull(revealed.officialBandScore)
            assertEquals(IeltsExerciseJson.submissionHash(reread.submission!!), revealed.submissionSha256)
            assertEquals(IeltsPracticeHistoryStatus.SUBMITTED, repository.observeHistory().first().single().status)
        } finally { db.close() }
    }

    @Test fun repeatedAndConcurrentSubmitReturnTheFirstCommitWithoutRewritingItsTimestamp() = runBlocking {
        val db = database()
        try {
            val complete = filled(repo(db)); val start = CompletableDeferred<Unit>()
            val submitted = coroutineScope {
                val workers = listOf(300L, 400L).map { now -> async(Dispatchers.Default) {
                    start.await(); repo(db).submit(complete.attemptId, complete.revision, now)
                } }
                start.complete(Unit); workers.awaitAll()
            }
            assertEquals(submitted[0], submitted[1]); assertEquals(complete.revision + 1, submitted[0].revision)
            val before = snapshot(db)
            assertEquals(submitted[0], repo(db).submit(complete.attemptId, 1, 9999))
            assertEquals(before, snapshot(db))
        } finally { db.close() }
    }

    @Test fun submittedInputCannotBeEditedEvenWithTheCurrentRevision() = runBlocking {
        val db = database()
        try {
            val repository = repo(db); val complete = filled(repository)
            val submitted = repository.submit(complete.attemptId, complete.revision, 300)
            val before = snapshot(db)
            fails("ielts_submitted") { repository.saveDraft(submitted.attemptId, submitted.revision,
                answers.map { if (it.questionId == "q1") it.copy(answer = "A") else it }, "Changed writing", 400) }
            assertEquals(before, snapshot(db)); assertEquals(submitted, repository.get(submitted.attemptId))
        } finally { db.close() }
    }

    @Test fun failedSqlCommitPreservesDraftAndRequiresASuccessfulRetryBeforeReveal() = runBlocking {
        val db = database()
        try {
            val repository = repo(db); val complete = filled(repository)
            db.useWriterConnection { connection ->
                connection.usePrepared("CREATE TRIGGER fail_ielts_commit BEFORE UPDATE ON preferences WHEN NEW.key LIKE 'ielts.attempt.%' BEGIN SELECT RAISE(ABORT, 'synthetic storage failure'); END") { it.step() }
            }
            val before = snapshot(db)
            assertTrue(runCatching { repository.submit(complete.attemptId, complete.revision, 300) }.isFailure)
            assertEquals(before, snapshot(db))
            assertEquals(complete, repository.get(complete.attemptId)); assertNull(repository.get(complete.attemptId)!!.answerView())
            assertEquals(IeltsPracticeHistoryStatus.DRAFT, repository.observeHistory().first().single().status)
            db.useWriterConnection { connection -> connection.usePrepared("DROP TRIGGER fail_ielts_commit") { it.step() } }
            val saved = repository.submit(complete.attemptId, complete.revision, 400)
            assertNotNull(saved.answerView()); assertEquals(400L, saved.submission!!.submittedAt)
        } finally { db.close() }
    }

    @Test fun closingAndReopeningTheDatabaseRetainsDraftThenSubmittedReview() = runBlocking {
        val folder = File(context.filesDir, "ielts-storage-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val file = File(folder, "practice.db")
        var db: LexiconDatabase? = null
        try {
            val firstDb = fileDatabase(file).also { db = it }
            val complete = filled(repo(firstDb))
            firstDb.close(); db = null
            val secondDb = fileDatabase(file).also { db = it }
            assertEquals(complete, repo(secondDb).get(complete.attemptId))
            val submitted = repo(secondDb).submit(complete.attemptId, complete.revision, 300)
            secondDb.close(); db = null
            val thirdDb = fileDatabase(file).also { db = it }
            val restored = requireNotNull(repo(thirdDb).get(complete.attemptId))
            assertEquals(submitted, restored); assertNotNull(restored.answerView())
            assertEquals(submitted, repo(thirdDb).submit(complete.attemptId, 1, 900))
        } finally { db?.close(); folder.deleteRecursively() }
    }

    @Test fun expiringTheArticleBodyKeepsFrozenQuestionsDraftSubmissionAndReviewComplete() = runBlocking {
        val db = database()
        try {
            val repository = repo(db)
            db.dao().putBody(BodyRow(request.articleId, 100, "an unrelated disposable body cache"))
            val complete = filled(repository)
            db.dao().expireBodies(100)
            assertNull(db.readingDao().bodyRow(request.articleId))
            assertEquals(complete, repo(db).get(complete.attemptId))
            val submitted = repo(db).submit(complete.attemptId, complete.revision, 300)
            db.dao().expireBodies(Long.MAX_VALUE)
            val reread = requireNotNull(repo(db).get(complete.attemptId))
            assertEquals(submitted, reread); assertEquals(paragraphs, reread.candidate().paragraphs)
            assertEquals(listOf(firstRef), reread.answerView()!!.readingAnswers.first().sourceRefs)
        } finally { db.close() }
    }

    @Test fun allOperationsLeaveSpeedBaselineFsrsPersonalizationAndOtherTableBytesUntouched() = runBlocking {
        val db = database()
        try {
            db.dao().putSource(SourceRow("old-source", "old-source-data"))
            db.dao().putArticle(ArticleRow("old-article", "https://example.invalid/old", 1, "old-article-data"))
            db.dao().putArticleSource(ArticleSourceRow("old-source", "old-guid", "old-article"))
            db.dao().putBody(BodyRow("old-article", 1, "old-body-data"))
            db.dao().putCapture(CaptureRow("old-capture", 1, "old-capture-data"))
            db.dao().putCard(CardRow("old-card", "old-capture", 200, 7, 1, "old-fsrs-data"))
            db.dao().putReview(ReviewRow("old-review", "old-card", 1, "old-review-data"))
            db.dao().putJob(JobRow("old-job", "failed", 1, "old-job-data"))
            db.dao().putEvent(EventRow("old-event", 1, "old-event-data"))
            db.dao().putTopic(TopicCacheRow("old-topic", "Education", null))
            listOf("speed-library:run:old", "baseline:old", "personalization:profile", "unrelated").forEach {
                db.dao().putPreference(PreferenceRow(it, "keep these exact old bytes"))
            }
            val before = snapshot(db)
            val repository = repo(db); val complete = filled(repository)
            repository.submit(complete.attemptId, complete.revision, 300)
            repository.get(complete.attemptId); repository.observeHistory().first()
            assertEquals(before, withoutPractice(snapshot(db)))
            val prefKeys = snapshot(db).getValue("preferences").mapNotNull { it.firstOrNull() }
            assertEquals(2, prefKeys.count { it.startsWith("ielts.") })
        } finally { db.close() }
    }

    @Test fun corruptAttemptRemainsUnavailableWithoutRenderingRawDataOrOverwritingIt() = runBlocking {
        val db = database()
        try {
            val repository = repo(db); val original = begin(repository)
            val corrupt = PreferenceRow(IeltsPracticeStorage.attemptKey(original.attemptId), "{ private writing must not appear in history")
            db.dao().putPreference(corrupt)
            val before = snapshot(db)
            val history = repository.observeHistory().first().single()
            assertEquals(IeltsPracticeHistoryStatus.UNAVAILABLE, history.status)
            assertNull(history.attemptId); assertNull(history.articleId); assertNull(history.questionCount)
            assertNull(history.answeredCount); assertNull(history.updatedAt)
            assertFalse(history.toString().contains("private writing"))
            fails("ielts_storage_invalid") { repository.get(original.attemptId) }
            fails("ielts_storage_invalid") { repository.begin(original.packageHash, original.attemptId, 500) }
            fails("ielts_storage_invalid") { repository.saveDraft(original.attemptId, 1, answers, "new", 500) }
            fails("ielts_storage_invalid") { repository.submit(original.attemptId, 1, 500) }
            assertEquals(before, snapshot(db))
        } finally { db.close() }
    }

    @Test fun corruptOrIdentityMismatchedCachedPackageNeverGetsSilentlyReplaced() = runBlocking {
        val db = database()
        try {
            val repository = repo(db); val original = begin(repository)
            val key = IeltsPracticeStorage.packageKey(original.packageHash)
            val valid = requireNotNull(db.dao().preference(key))
            val changed = Json.parseToJsonElement(valid.data).jsonObject.toMutableMap().apply {
                put("packageHash", JsonPrimitive("c".repeat(64)))
            }
            for (data in listOf("{broken package", JsonObject(changed).toString())) {
                val corrupt = PreferenceRow(key, data); db.dao().putPreference(corrupt)
                val before = snapshot(db)
                fails("ielts_storage_invalid") { repository.cache(request, exercise) }
                fails("ielts_storage_invalid") { repository.get(original.attemptId) }
                assertEquals(IeltsPracticeHistoryStatus.UNAVAILABLE, repository.observeHistory().first().single().status)
                assertEquals(before, snapshot(db)); assertEquals(corrupt, db.dao().preference(key))
            }
        } finally { db.close() }
    }

    @Test fun missingPackageKeepsTheAttemptVisibleAsUnavailableWithoutInventingResult() = runBlocking {
        val db = database()
        try {
            val repository = repo(db); val complete = filled(repository)
            val submitted = repository.submit(complete.attemptId, complete.revision, 300)
            db.readingDao().delete(IeltsPracticeStorage.packageKey(submitted.packageHash))
            val before = snapshot(db)
            fails("ielts_package_missing") { repository.get(submitted.attemptId) }
            val history = repository.observeHistory().first().single()
            assertEquals(IeltsPracticeHistoryStatus.UNAVAILABLE, history.status)
            assertNull(history.questionCount); assertNull(history.submittedAt)
            assertEquals(before, snapshot(db))
        } finally { db.close() }
    }

    @Test fun rowIdentityUnknownVersionDuplicateFieldsAndChangedCommittedPayloadAreRejected() = runBlocking {
        val db = database()
        try {
            val repository = repo(db); val complete = filled(repository)
            val submitted = repository.submit(complete.attemptId, complete.revision, 300)
            val key = IeltsPracticeStorage.attemptKey(submitted.attemptId)
            val valid = requireNotNull(db.dao().preference(key))
            val raw = Json.parseToJsonElement(valid.data).jsonObject
            val variants = listOf(
                JsonObject(raw + ("attemptId" to JsonPrimitive("another-attempt"))).toString(),
                JsonObject(raw + ("version" to JsonPrimitive(2))).toString(),
                "{\"version\":1," + valid.data.drop(1),
                JsonObject(raw + ("writingText" to JsonPrimitive("changed after submission"))).toString(),
                JsonObject(raw + ("submissionHash" to JsonPrimitive("d".repeat(64)))).toString(),
            )
            variants.forEach { data ->
                db.dao().putPreference(PreferenceRow(key, data)); val before = snapshot(db)
                fails("ielts_storage_invalid") { repository.get(submitted.attemptId) }
                fails("ielts_storage_invalid") { repository.submit(submitted.attemptId, 1, 900) }
                assertEquals(IeltsPracticeHistoryStatus.UNAVAILABLE, repository.observeHistory().first().single().status)
                assertEquals(before, snapshot(db))
            }
        } finally { db.close() }
    }

    @Test fun wallClockRollbackDoesNotReviveOldRevisionAndRevisionOverflowPreservesInput() = runBlocking {
        val db = database()
        try {
            val repository = repo(db); val complete = filled(repository)
            val earlierClock = repository.saveDraft(complete.attemptId, complete.revision, answers, "A changed draft", 50)
            assertEquals(complete.updatedAt, earlierClock.updatedAt)
            assertEquals(complete.revision + 1, earlierClock.revision)
            val key = IeltsPracticeStorage.attemptKey(complete.attemptId)
            val row = requireNotNull(db.dao().preference(key))
            val parsed = Json.parseToJsonElement(row.data).jsonObject
            val limited = PreferenceRow(key, JsonObject(parsed + ("revision" to JsonPrimitive(IeltsContract.MAX_TIMESTAMP))).toString())
            db.dao().putPreference(limited)
            val before = snapshot(db)
            fails("ielts_revision_limit") { repository.saveDraft(complete.attemptId, IeltsContract.MAX_TIMESTAMP, answers, "Changed", 500) }
            fails("ielts_revision_limit") { repository.submit(complete.attemptId, IeltsContract.MAX_TIMESTAMP, 500) }
            assertEquals(before, snapshot(db)); assertEquals(limited, db.dao().preference(key))
        } finally { db.close() }
    }

    @Test fun modifyingReturnedCollectionsCannotChangeStoredSourceOrDraft() = runBlocking {
        val db = database()
        try {
            val repository = repo(db); val complete = filled(repository)
            val original = requireNotNull(repository.get(complete.attemptId))
            val expectedRequest = IeltsExerciseJson.encodeRequest(original.request)
            val expectedPackage = IeltsExerciseJson.encodePackage(original.exercise)
            (complete.request.paragraphs as MutableList<IeltsParagraph>).clear()
            (complete.exercise.readingQuestions as MutableList<IeltsReadingQuestion>).clear()
            (complete.draftResponses as MutableList<IeltsResponse>).clear()
            val reread = requireNotNull(repo(db).get(complete.attemptId))
            assertEquals(expectedRequest, IeltsExerciseJson.encodeRequest(reread.request))
            assertEquals(expectedPackage, IeltsExerciseJson.encodePackage(reread.exercise))
            assertEquals(answers, reread.draftResponses)
        } finally { db.close() }
    }

    private suspend fun snapshot(db: LexiconDatabase): Map<String, List<List<String?>>> = db.useReaderConnection { connection ->
        val names = connection.usePrepared("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name") { statement ->
            buildList { while (statement.step()) add(statement.getText(0)) }
        }
        names.associateWith { table ->
            val quoted = "\"" + table.replace("\"", "\"\"") + "\""
            connection.usePrepared("SELECT * FROM $quoted ORDER BY 1") { statement ->
                buildList { while (statement.step()) add((0 until statement.getColumnCount()).map { column ->
                    if (statement.isNull(column)) null else statement.getText(column)
                }) }
            }
        }
    }

    private fun withoutPractice(value: Map<String, List<List<String?>>>): Map<String, List<List<String?>>> =
        value.toMutableMap().apply { put("preferences", value.getValue("preferences").filterNot {
            it[0]?.startsWith(IeltsPracticeStorage.PACKAGE_PREFIX) == true || it[0]?.startsWith(IeltsPracticeStorage.ATTEMPT_PREFIX) == true
        }) }
}
