package dev.lexicon.data

import androidx.room3.Room
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Isolated real Room transactions; no model calls or inferred learner scores. */
class IeltsStudyStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun database() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context)
        .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
    private fun material(): Pair<IeltsExerciseRequest, IeltsExercisePackage> {
        val raw = InstrumentationRegistry.getInstrumentation().context.assets.open("ielts-task1.json").bufferedReader().use { it.readText() }
        val root = Json.parseToJsonElement(raw).jsonObject
        return IeltsExerciseJson.decodeRequest(root.getValue("request").toString()) to IeltsExerciseJson.decodePackage(root.getValue("package").toString())
    }
    private suspend fun seed(db: LexiconDatabase, id: String = "practice", submit: Boolean = true): IeltsSavedPractice {
        val (request, exercise) = material(); val repo = LocalIeltsPracticeRepository(db)
        val hash = repo.cache(request, exercise); val start = repo.begin(hash, id, 10)
        val answers = exercise.readingQuestions.map { q -> IeltsResponse(q.questionId, when(q) {
            is IeltsChoiceQuestion -> q.answer.name
            is IeltsInformationQuestion -> q.answer.wire
            is IeltsViewQuestion -> q.answer.wire
            is IeltsCompletionQuestion -> q.answer
        }) }
        val draft = repo.saveWritingDrafts(id, start.revision, answers, "Short draft", "Numeric draft", 20)
        return if (submit) repo.submit(id, draft.revision, 30) else draft
    }
    private suspend fun rejects(code: IeltsStudyFailureCode, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (e: IeltsStudyFailure) { assertEquals(code, e.code) }
    }
    private fun request(id: String, kind: IeltsReadingType?) = IeltsGatewaySubmit(requestId = id, articleId = "next-article",
        body = "A new article offers another reading opportunity.", paragraphs = listOf(IeltsParagraph("p1", 0, 49,
            "A new article offers another reading opportunity.")), readingRequests = IeltsStudyPolicy.requests(kind),
        writingTask2Request = IeltsWritingRequest("w2", IeltsWritingType.EXTENT_OF_AGREEMENT))

    @Test fun onlyCommittedKnownQuestionsCanCreateFeedback() = runBlocking {
        val db = database()
        try {
            seed(db, submit = false); val repo = LocalIeltsStudyRepository(db)
            rejects(IeltsStudyFailureCode.NOT_SUBMITTED) { repo.reflect("practice", "q1", IeltsStudyIntent.PRACTICE_AGAIN, 0, 0, 40) }
            assertNull(db.dao().preference(IeltsStudyStorage.PROFILE))
            val practice = LocalIeltsPracticeRepository(db).get("practice")!!
            LocalIeltsPracticeRepository(db).submit("practice", practice.revision, 30)
            rejects(IeltsStudyFailureCode.INVALID) { repo.reflect("practice", "missing", IeltsStudyIntent.PRACTICE_AGAIN, 0, 0, 40) }
            assertNull(db.dao().preference(IeltsStudyStorage.PROFILE))
        } finally { db.close() }
    }

    @Test fun explicitChoiceRestoresAndNeverChangesTheOriginalAnswersOrOtherSettings() = runBlocking {
        val db = database()
        try {
            val saved = seed(db); val attempt = db.dao().preference(IeltsPracticeStorage.attemptKey(saved.attemptId))
            val pkg = db.dao().preference(IeltsPracticeStorage.packageKey(saved.packageHash))
            db.dao().putPreference(PreferenceRow("personalization", "untouched"))
            val repo = LocalIeltsStudyRepository(db)
            val marked = repo.reflect("practice", "q2", IeltsStudyIntent.PRACTICE_AGAIN, 0, 0, 40)
            assertEquals(IeltsReadingType.IDENTIFYING_INFORMATION, marked.profile.focus!!.kind)
            assertEquals(IeltsExerciseJson.submissionHash(saved.submission!!), marked.profile.focus!!.submissionHash)
            assertEquals(marked, LocalIeltsStudyRepository(db).load("practice"))
            assertEquals(attempt, db.dao().preference(attempt!!.key)); assertEquals(pkg, db.dao().preference(pkg!!.key))
            assertEquals("untouched", db.dao().preference("personalization")!!.data)
        } finally { db.close() }
    }

    @Test fun questionableAndUndoRemoveOnlyTheApplicableFocusAndKeepVersionedEvents() = runBlocking {
        val db = database()
        try {
            seed(db); val repo = LocalIeltsStudyRepository(db)
            val one = repo.reflect("practice", "q1", IeltsStudyIntent.PRACTICE_AGAIN, 0, 0, 40)
            val two = repo.reflect("practice", "q2", IeltsStudyIntent.QUESTIONABLE, 0, one.profile.revision, 50)
            assertEquals(one.profile.focus, two.profile.focus)
            val three = repo.reflect("practice", "q1", IeltsStudyIntent.QUESTIONABLE, 1, two.profile.revision, 60)
            assertNull(three.profile.focus)
            val four = repo.reflect("practice", "q1", null, 2, three.profile.revision, 70)
            assertNull(four.feedback.single { it.questionId == "q1" }.intent)
            assertNotNull(db.dao().preference(IeltsStudyStorage.eventKey(one.feedback.single())))
            assertEquals(3, four.feedback.single { it.questionId == "q1" }.revision.toInt())
        } finally { db.close() }
    }

    @Test fun sameMaterialQuestionInAnotherAttemptCanInvalidateAChosenQuestion() = runBlocking {
        val db = database()
        try {
            seed(db); seed(db, "repeat"); val repo = LocalIeltsStudyRepository(db)
            repo.reflect("practice", "q1", IeltsStudyIntent.PRACTICE_AGAIN, 0, 0, 40)
            val next = repo.reflect("repeat", "q1", IeltsStudyIntent.QUESTIONABLE, 0, 1, 50)
            assertNull(next.profile.focus)
        } finally { db.close() }
    }

    @Test fun competingScreensCommitExactlyOneChoiceAndRejectStaleUndo() = runBlocking {
        val db = database()
        try {
            seed(db)
            val results = listOf("q1", "q2").map { q -> async(Dispatchers.Default) {
                runCatching { LocalIeltsStudyRepository(db).reflect("practice", q, IeltsStudyIntent.PRACTICE_AGAIN, 0, 0, 40) }
            } }.awaitAll()
            assertEquals(1, results.count { it.isSuccess }); assertTrue(results.single { it.isFailure }.exceptionOrNull() is IeltsStudyFailure)
            val repo = LocalIeltsStudyRepository(db); val saved = repo.load("practice")
            rejects(IeltsStudyFailureCode.CONFLICT) { repo.reflect("practice", saved.feedback.single().questionId, null, 0, 0, 50) }
            assertEquals(saved, repo.load("practice"))
        } finally { db.close() }
    }

    @Test fun boundRequestsRemainImmutableAfterTheUserChangesFocusAndReopeningDoesNotWrite() = runBlocking {
        val db = database()
        try {
            seed(db); val repo = LocalIeltsStudyRepository(db)
            val focused = repo.reflect("practice", "q2", IeltsStudyIntent.PRACTICE_AGAIN, 0, 0, 40)
            val submit = request("request-one", focused.profile.focus!!.kind)
            val frozen = repo.freeze(submit, focused.profile.revision, 50)
            val balanced = repo.useBalanced(focused.profile.revision, 60)
            assertNull(balanced.profile.focus)
            assertEquals(frozen, repo.freeze(submit, balanced.profile.revision, 70))
            assertEquals(frozen, LocalIeltsStudyRepository(db).requestBinding(submit.requestId))
            rejects(IeltsStudyFailureCode.INVALID) { repo.freeze(submit.copy(readingRequests = IeltsStudyPolicy.requests(null)), balanced.profile.revision, 70) }
            assertEquals(frozen, repo.requestBinding(submit.requestId))
            assertEquals(focused.feedback, repo.load("practice").feedback)
        } finally { db.close() }
    }

    @Test fun frozenChoiceSurvivesFeedbackUndoButMissingOriginEventIsRejected() = runBlocking {
        val db = database()
        try {
            seed(db); val repo = LocalIeltsStudyRepository(db)
            val focus = repo.reflect("practice", "q2", IeltsStudyIntent.PRACTICE_AGAIN, 0, 0, 40)
            val submitted = request("request-two", focus.profile.focus!!.kind)
            val frozen = repo.freeze(submitted, 1, 50)
            repo.reflect("practice", "q2", null, 1, 1, 60)
            assertEquals(frozen, repo.requestBinding(submitted.requestId))
            db.dao().putPreference(PreferenceRow(IeltsStudyStorage.eventKey(focus.profile.focus!!), "{}"))
            rejects(IeltsStudyFailureCode.INVALID) { repo.requestBinding(submitted.requestId) }
        } finally { db.close() }
    }

    @Test fun corruptAndMisboundFeedbackStayUnchangedInsteadOfResettingOrRewritingAnswers() = runBlocking {
        val db = database()
        try {
            val saved = seed(db); val repo = LocalIeltsStudyRepository(db)
            val good = repo.reflect("practice", "q1", IeltsStudyIntent.PRACTICE_AGAIN, 0, 0, 40)
            val row = IeltsStudyStorage.row(good.feedback.single().copy(submissionHash = "d".repeat(64)))
            db.dao().putPreference(row)
            rejects(IeltsStudyFailureCode.INVALID) { repo.load("practice") }
            rejects(IeltsStudyFailureCode.INVALID) { repo.reflect("practice", "q1", null, 1, 1, 50) }
            assertEquals(row, db.dao().preference(row.key))
            assertEquals(saved, LocalIeltsPracticeRepository(db).get("practice"))
        } finally { db.close() }
    }

    @Test fun clockRollbackKeepsMonotonicFeedbackAndRejectsUnknownVersion() = runBlocking {
        val db = database()
        try {
            seed(db); val repo = LocalIeltsStudyRepository(db)
            val original = repo.reflect("practice", "q1", IeltsStudyIntent.PRACTICE_AGAIN, 0, 0, 40)
            val later = repo.reflect("practice", "q1", null, 1, 1, 1)
            assertEquals(40L, later.profile.updatedAt)
            val row = IeltsStudyStorage.row(later.profile).let { it.copy(data = it.data.replace("\"version\":1", "\"version\":2")) }
            db.dao().putPreference(row)
            rejects(IeltsStudyFailureCode.INVALID) { repo.load() }
            assertNotNull(original.profile.focus)
        } finally { db.close() }
    }

    @Test fun profileWriteFailureRollsBackTheFeedbackAndItsAuditEvent() = runBlocking {
        val db = database()
        try {
            seed(db); val repo = LocalIeltsStudyRepository(db)
            db.useWriterConnection { connection -> connection.usePrepared(
                "CREATE TRIGGER study_failure BEFORE INSERT ON preferences WHEN NEW.key='ielts.study.profile' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END"
            ) { it.step() } }
            assertTrue(runCatching { repo.reflect("practice", "q2", IeltsStudyIntent.PRACTICE_AGAIN, 0, 0, 40) }.isFailure)
            assertNull(db.dao().preference(IeltsStudyStorage.PROFILE))
            assertNull(db.dao().preference(IeltsStudyStorage.feedbackKey("practice", "q2")))
            db.useWriterConnection { connection -> connection.usePrepared("DROP TRIGGER study_failure") { it.step() } }
            // An orphaned event would conflict here rather than silently overwrite it.
            assertNotNull(repo.reflect("practice", "q2", IeltsStudyIntent.PRACTICE_AGAIN, 0, 0, 40).profile.focus)
        } finally { db.close() }
    }

    @Test fun explicitRetryInheritsItsOriginalChoiceDespiteLaterPreferenceChanges() = runBlocking {
        val db = database()
        try {
            seed(db); val repo = LocalIeltsStudyRepository(db)
            repo.reflect("practice", "q2", IeltsStudyIntent.PRACTICE_AGAIN, 0, 0, 40)
            val first = request("original", IeltsReadingType.IDENTIFYING_INFORMATION)
            val binding = repo.freeze(first, 1, 50)
            repo.useBalanced(1, 60)
            val replacement = first.copy(requestId = "retry")
            val inherited = repo.inherit(first, replacement, 70)!!
            assertEquals(binding.profile, inherited.profile)
            assertEquals(inherited, repo.inherit(first, replacement, 80))
            rejects(IeltsStudyFailureCode.INVALID) { repo.inherit(first, replacement.copy(readingRequests = IeltsStudyPolicy.requests(null)), 80) }
        } finally { db.close() }
    }

    @Test fun feedbackAndFrozenChoiceSurviveClosingAndReopeningTheDatabaseFile() = runBlocking {
        val file = java.io.File(context.cacheDir, "study-${java.util.UUID.randomUUID()}.sqlite")
        fun open() = Room.databaseBuilder<LexiconDatabase>(context, file.absolutePath)
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
        val first = open()
        val snapshot: IeltsStudySnapshot; val binding: IeltsStudyRequest; val saved: IeltsSavedPractice
        try {
            saved = seed(first); val repo = LocalIeltsStudyRepository(first)
            snapshot = repo.reflect(saved.attemptId, "q2", IeltsStudyIntent.PRACTICE_AGAIN, 0, 0, 40)
            binding = repo.freeze(request("disk-request", snapshot.profile.focus!!.kind), 1, 50)
        } finally { first.close() }
        val second = open()
        try {
            assertEquals(snapshot, LocalIeltsStudyRepository(second).load(saved.attemptId))
            assertEquals(binding, LocalIeltsStudyRepository(second).requestBinding("disk-request"))
            assertEquals(saved, LocalIeltsPracticeRepository(second).get(saved.attemptId))
        } finally {
            second.close()
            listOf(file, java.io.File(file.path + "-wal"), java.io.File(file.path + "-shm")).forEach { it.delete() }
        }
    }
}
