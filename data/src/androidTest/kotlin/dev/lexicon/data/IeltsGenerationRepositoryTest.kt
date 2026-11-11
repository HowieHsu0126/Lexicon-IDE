package dev.lexicon.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real isolated Room, synthetic gateway receipts. This suite supplies no runtime or provider evidence. */
@RunWith(AndroidJUnit4::class)
class IeltsGenerationRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val health = IeltsGatewayHealth("lexicon.ielts.health.v1", "ok", IeltsGatewayCapabilities("test-1", "synthetic-corpus",
        "e".repeat(64), listOf("multiple_choice", "identifying_information", "identifying_writers_views", "short_text_completion"),
        listOf("extent_of_agreement"), "official_public_samples_and_format_metadata"))
    private val body = "The council approved solar panels to support public clean energy investment."
    private fun submit(id: String = "request-local") = IeltsGatewaySubmit(requestId = id, articleId = "article-local", body = body,
        paragraphs = listOf(IeltsParagraph("p1", 0, body.length, body)), readingRequests = listOf(IeltsChoiceRequest("q1")),
        writingTask2Request = IeltsWritingRequest("w2", IeltsWritingType.EXTENT_OF_AGREEMENT))
    private fun request(submit: IeltsGatewaySubmit) = IeltsExerciseRequest(IeltsContract.REQUEST, submit.requestId, submit.articleId,
        IeltsGatewayProtocol.bodyHash(submit.body), submit.paragraphs, "a".repeat(64),
        listOf(IeltsStyleSource("synthetic-source", "b".repeat(64), "Self-authored test format metadata.", IeltsStyleScope.PUBLIC_SAMPLE_FORMAT)),
        submit.readingRequests, submit.writingTask2Request)
    private fun job(submit: IeltsGatewaySubmit, state: IeltsGatewayJobState = IeltsGatewayJobState.QUEUED, revision: Long = 1): IeltsGatewayJob =
        IeltsGatewayJob("job-" + IeltsGatewayProtocol.bodyHash(submit.requestId).take(32), submit.requestId,
            IeltsGatewayProtocol.jobInputHash(request(submit), submit.body, health.capabilities.corpusSha256), state,
            revision, 100, 100 + revision, null, if (state in setOf(IeltsGatewayJobState.READY, IeltsGatewayJobState.CANCELLED)) 1 else 0)
    private fun artifact(submit: IeltsGatewaySubmit): IeltsGatewayVerifiedPackage {
        val request = request(submit)
        val evidence = submit.paragraphs.first().let { IeltsEvidence(it.paragraphId, it.start, it.end, it.text) }
        val exercise = IeltsExercisePackage(IeltsContract.PACKAGE, request.requestId, request.articleId, request.bodyHash,
            IeltsExerciseJson.requestHash(request), IeltsContract.LABEL, listOf("synthetic-source"),
            listOf(IeltsChoiceQuestion("q1", "What did the council approve?", listOf(
                IeltsOption(IeltsChoice.A, "Solar panels"), IeltsOption(IeltsChoice.B, "A school"),
                IeltsOption(IeltsChoice.C, "A railway"), IeltsOption(IeltsChoice.D, "A hospital")),
                1, IeltsChoice.A, "The original paragraph identifies solar panels.", listOf(evidence))),
            IeltsWritingTask2("w2", IeltsWritingType.EXTENT_OF_AGREEMENT,
                "Public investment should support clean energy. To what extent do you agree or disagree?",
                250, 40, listOf(evidence)), IeltsUnavailableTask1("unavailable", "no_verified_visual"))
        return IeltsGatewayVerifiedPackage("lexicon.ielts.verified-package.v1", request, exercise,
            IeltsGatewayProvenance("deepseek_harness", health.capabilities.runtimeVersion, "synthetic-generator", "synthetic-reviewer",
                IeltsExerciseJson.requestHash(request), IeltsExerciseJson.packageHash(exercise), "c".repeat(64),
                health.capabilities.corpusSha256, "independent_answers_agree", false, 3, 200,
                explanationReviewSessionId = "synthetic-explanation-reviewer", explanationReviewSha256 = "d".repeat(64),
                explanationReview = "source_faithfulness_checked"))
    }
    private fun db() = Room.inMemoryDatabaseBuilder<LexiconDatabase>(context).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO).build()

    private inner class FakeGateway : IeltsGateway {
        val posts = AtomicInteger(); val lookups = AtomicInteger(); val gets = AtomicInteger(); val cancels = AtomicInteger(); val fetches = AtomicInteger()
        var current: IeltsGatewayJob? = null
        var posted: IeltsGatewaySubmit? = null
        var beforePost: suspend (IeltsGatewaySubmit) -> Unit = {}
        var postFailure: Exception? = null
        var cancelFailure: Exception? = null
        var suppliedArtifact: IeltsGatewayVerifiedPackage? = null
        override suspend fun health() = this@IeltsGenerationRepositoryTest.health
        override suspend fun submit(payload: IeltsGatewaySubmit): IeltsGatewayJob {
            posts.incrementAndGet(); beforePost(payload); posted = payload
            val result = current ?: job(payload).also { current = it }
            postFailure?.let { throw it }; return result
        }
        override suspend fun get(jobId: String): IeltsGatewayJob { gets.incrementAndGet(); return requireNotNull(current) }
        override suspend fun byRequest(requestId: String): IeltsGatewayJob? { lookups.incrementAndGet(); return current }
        override suspend fun cancel(jobId: String): IeltsGatewayJob {
            cancels.incrementAndGet()
            current = requireNotNull(current).copy(state = IeltsGatewayJobState.CANCELLED, revision = current!!.revision + 1, processReaped = 1)
            cancelFailure?.let { throw it }; return current!!
        }
        override suspend fun fetchPackage(job: IeltsGatewayJob, originalSubmit: IeltsGatewaySubmit, expectedHealth: IeltsGatewayHealth): IeltsGatewayVerifiedPackage {
            fetches.incrementAndGet()
            assertEquals(health, expectedHealth)
            return suppliedArtifact ?: artifact(originalSubmit)
        }
    }
    private inner class Access(val gateway: FakeGateway) : IeltsGatewayAccess {
        var value = IeltsGatewayConnection("https://original.example/", true, health)
        override suspend fun snapshot() = value
        override suspend fun pair(endpoint: String, token: String): IeltsGatewayHealth {
            value = IeltsGatewayConnection(IeltsGatewayEndpoint.parse(endpoint).url, true, health); return health
        }
        override suspend fun clearPairing() { value = IeltsGatewayConnection(null, false, null) }
        override suspend fun createClient(): IeltsGateway = gateway
    }
    private fun newRepository(db: LexiconDatabase, access: Access, practice: IeltsPracticeRepository = LocalIeltsPracticeRepository(db)) =
        LocalIeltsGenerationRepository(db, access, practice, clock = { 500 })
    private suspend fun fails(issue: IeltsGenerationIssue, action: suspend () -> Unit) {
        try { action(); fail("Expected $issue") } catch (failure: IeltsGenerationFailure) { assertEquals(issue, failure.issue) }
    }

    @Test fun immutableRequestAndDispatchClaimAreSavedBeforeTheOnlyPost() = runBlocking {
        val db = db()
        try {
            val gateway = FakeGateway(); val access = Access(gateway); val repository = newRepository(db, access)
            val payload = submit()
            gateway.beforePost = { sent ->
                val stored = requireNotNull(repository.get(sent.requestId))
                assertTrue(stored.dispatchClaimed); assertEquals(payload, stored.submit)
                assertEquals(IeltsGenerationStage.RECONCILING, stored.stage)
                assertEquals("https://original.example/", stored.endpoint); assertEquals(health, stored.expectedHealth)
            }
            val result = repository.generate(payload)
            assertEquals(1, gateway.posts.get()); assertEquals(IeltsGenerationStage.QUEUED, result.stage)
            assertEquals(result, newRepository(db, access).get(payload.requestId))
            val summary = repository.observeHistory().first().single()
            assertEquals(result.requestId, summary.requestId); assertFalse(summary.toString().contains(body))
        } finally { db.close() }
    }

    @Test fun upstreamUnknownResultIsProjectedAfterReopenWithoutRewritingOrPosting() = runBlocking {
        val db = db()
        try {
            val gateway = FakeGateway(); val access = Access(gateway); val repository = newRepository(db, access)
            val payload = submit()
            gateway.current = job(payload, IeltsGatewayJobState.FAILED).copy(error = "provider_completion_unknown", processReaped = 1)
            val original = repository.generate(payload)
            assertNull(original.issue)
            val reopened = newRepository(db, access)
            val summary = reopened.observeHistory().first().single()
            assertEquals(IeltsGenerationIssue.OUTCOME_UNKNOWN, summary.issue)
            assertEquals(IeltsGenerationStage.FAILED, summary.stage)
            assertEquals(original, reopened.get(payload.requestId))
            reopened.reconcile(payload.requestId)
            assertEquals(1, gateway.posts.get())
            assertEquals(original.job, reopened.get(payload.requestId)?.job)
            assertFalse(summary.toString().contains("provider_completion_unknown"))
            assertFalse(summary.toString().contains(body))
        } finally { db.close() }
    }

    @Test fun sameRequestReusesTheJobAndChangedPayloadNeverWritesOrPosts() = runBlocking {
        val db = db()
        try {
            val gateway = FakeGateway(); val access = Access(gateway); val repository = newRepository(db, access)
            val payload = submit(); val initial = repository.generate(payload)
            repository.generate(payload)
            assertEquals(1, gateway.posts.get())
            val before = db.readingDao().studyRows(IeltsGenerationStorage.PREFIX)
            fails(IeltsGenerationIssue.REQUEST_REJECTED) { repository.generate(payload.copy(articleId = "different-article")) }
            assertEquals(before, db.readingDao().studyRows(IeltsGenerationStorage.PREFIX))
            assertEquals(initial.submit, repository.get(payload.requestId)!!.submit)
        } finally { db.close() }
    }

    @Test fun uncertainAcceptedPostRestoresByRequestAndOnlyThenFetchesReviewedMaterial() = runBlocking {
        val db = db()
        try {
            val gateway = FakeGateway(); val access = Access(gateway); val payload = submit()
            gateway.postFailure = IeltsGatewaySubmissionUncertain(payload.requestId)
            val result = newRepository(db, access).generate(payload)
            assertEquals(IeltsGenerationIssue.OUTCOME_UNKNOWN, result.issue); assertNull(result.job)
            val restored = newRepository(db, access)
            val queued = restored.reconcile(payload.requestId)
            assertEquals(IeltsGenerationStage.QUEUED, queued.stage); assertEquals(1, gateway.lookups.get())
            gateway.current = job(payload, IeltsGatewayJobState.READY, 3)
            val ready = restored.reconcile(payload.requestId)
            assertEquals(IeltsGenerationStage.READY, ready.stage); assertNotNull(ready.packageHash)
            val practice = requireNotNull(LocalIeltsPracticeRepository(db).get(ready.attemptId))
            assertEquals(payload.paragraphs, practice.candidate().paragraphs); assertNull(practice.answerView())
            assertEquals(1, gateway.posts.get()); assertEquals(1, gateway.fetches.get())
            assertEquals(ready, restored.reconcile(payload.requestId)); assertEquals(1, gateway.fetches.get())
        } finally { db.close() }
    }

    @Test fun crashAfterClaimBeforePostNeverResendsWhenByRequestReturnsNotFound() = runBlocking {
        val db = db()
        try {
            val gateway = FakeGateway(); val access = Access(gateway); val payload = submit()
            val initial = IeltsGenerationRecord(submit = payload, submitHash = IeltsGenerationStorage.submitHash(payload),
                endpoint = access.value.endpoint!!, expectedHealth = health, attemptId = IeltsGenerationStorage.attemptId(payload.requestId),
                revision = 1, createdAt = 100, updatedAt = 100)
            db.ieltsPracticeDao().createGeneration(IeltsGenerationStorage.row(initial))
            assertTrue(db.ieltsPracticeDao().claimGeneration(payload.requestId, 101))
            val restored = newRepository(db, access)
            assertEquals(IeltsGenerationIssue.OUTCOME_UNKNOWN, restored.reconcile(payload.requestId).issue)
            assertEquals(IeltsGenerationIssue.OUTCOME_UNKNOWN, restored.generate(payload).issue)
            assertEquals(0, gateway.posts.get()); assertEquals(2, gateway.lookups.get())
        } finally { db.close() }
    }

    @Test fun concurrentRepositoriesCanClaimOnlyOnePostForTheSameRequest() = runBlocking {
        val db = db()
        try {
            val gateway = FakeGateway(); val access = Access(gateway); val start = CompletableDeferred<Unit>()
            coroutineScope {
                val calls = (1..4).map { async(Dispatchers.Default) { start.await(); newRepository(db, access).generate(submit()) } }
                start.complete(Unit); calls.awaitAll()
            }
            assertEquals(1, gateway.posts.get())
            assertEquals(1, newRepository(db, access).observeHistory().first().size)
        } finally { db.close() }
    }

    @Test fun newOriginOrHealthNeverReceivesOldJobLookupsOrContent() = runBlocking {
        val db = db()
        try {
            val gateway = FakeGateway(); val access = Access(gateway); val repository = newRepository(db, access)
            repository.generate(submit())
            val original = access.value
            for (connection in listOf(original.copy(endpoint = "https://new.example/"),
                    original.copy(certificateSha256 = "d".repeat(64)),
                    original.copy(health = health.copy(capabilities = health.capabilities.copy(runtimeVersion = "test-2"))))) {
                access.value = connection
                assertEquals(IeltsGenerationIssue.CONNECTION_CHANGED, repository.reconcile(submit().requestId).issue)
                assertEquals(IeltsGenerationIssue.CONNECTION_CHANGED, repository.cancel(submit().requestId).issue)
                assertEquals(0, gateway.gets.get()); assertEquals(0, gateway.lookups.get()); assertEquals(0, gateway.cancels.get())
            }
            access.value = original
            assertNull(repository.reconcile(submit().requestId).issue); assertEquals(1, gateway.gets.get())
            assertEquals(1, gateway.posts.get())
        } finally { db.close() }
    }

    @Test fun certificateIdentityIsFrozenAndLegacySystemTrustRecordsRemainReadable() = runBlocking {
        val db = db()
        try {
            val gateway = FakeGateway(); val access = Access(gateway); val payload = submit()
            val legacy = IeltsGenerationRecord(submit = payload, submitHash = IeltsGenerationStorage.submitHash(payload),
                endpoint = access.value.endpoint!!, expectedHealth = health, attemptId = IeltsGenerationStorage.attemptId(payload.requestId),
                revision = 1, createdAt = 100, updatedAt = 100)
            val legacyRow = IeltsGenerationStorage.row(legacy)
            assertFalse(legacyRow.data.contains("certificateSha256"))
            assertEquals(legacy, IeltsGenerationStorage.decode(legacyRow))
            val pin = "b".repeat(64)
            access.value = access.value.copy(certificateSha256 = pin)
            val stored = newRepository(db, access).generate(payload)
            assertEquals(pin, stored.certificateSha256)
            assertEquals(pin, newRepository(db, access).get(payload.requestId)!!.certificateSha256)
            val before = gateway.gets.get()
            access.value = access.value.copy(certificateSha256 = "c".repeat(64))
            assertEquals(IeltsGenerationIssue.CONNECTION_CHANGED, newRepository(db, access).reconcile(payload.requestId).issue)
            assertEquals(before, gateway.gets.get())
            try {
                IeltsGenerationStorage.requireTransition(stored, stored.copy(certificateSha256 = "c".repeat(64), revision = stored.revision + 1))
                fail("Cannot change the trust identity of an existing job")
            } catch (_: IllegalArgumentException) { }
        } finally { db.close() }
    }

    @Test fun invalidReviewedArtifactCannotCreateAnyPracticeOrCachedPackage() = runBlocking {
        val db = db()
        try {
            val gateway = FakeGateway(); val access = Access(gateway); val payload = submit()
            gateway.current = job(payload, IeltsGatewayJobState.READY, 3)
            gateway.suppliedArtifact = artifact(payload).let { it.copy(provenance = it.provenance.copy(reviewSessionId = it.provenance.generationSessionId)) }
            val result = newRepository(db, access).generate(payload)
            assertEquals(IeltsGenerationIssue.INVALID_RESPONSE, result.issue); assertNull(result.packageHash)
            assertTrue(LocalIeltsPracticeRepository(db).observeHistory().first().isEmpty())
            assertTrue(db.readingDao().studyRows(IeltsPracticeStorage.PACKAGE_PREFIX).isEmpty())
        } finally { db.close() }
    }

    @Test fun legacyBlindReviewOnlyArtifactCannotEnterTheNewNetworkPracticeQueue() = runBlocking {
        val db = db()
        try {
            val gateway = FakeGateway(); val access = Access(gateway); val payload = submit()
            gateway.current = job(payload, IeltsGatewayJobState.READY, 3)
            gateway.suppliedArtifact = artifact(payload).let { it.copy(provenance = it.provenance.copy(
                explanationReviewSessionId = null, explanationReviewSha256 = null, explanationReview = null,
                realProviderRequestCount = 2)) }
            val result = newRepository(db, access).generate(payload)
            assertEquals(IeltsGenerationIssue.INVALID_RESPONSE, result.issue); assertNull(result.packageHash)
            assertTrue(LocalIeltsPracticeRepository(db).observeHistory().first().isEmpty())
            assertTrue(db.readingDao().studyRows(IeltsPracticeStorage.PACKAGE_PREFIX).isEmpty())
            assertEquals(1, gateway.posts.get())
        } finally { db.close() }
    }

    @Test fun previouslySavedPracticeKeepsItsDraftWithoutRevalidatingAsANewNetworkImport() = runBlocking {
        val db = db()
        try {
            val legacy = artifact(submit()).let { it.copy(provenance = it.provenance.copy(
                explanationReviewSessionId = null, explanationReviewSha256 = null, explanationReview = null,
                realProviderRequestCount = 2)) }
            val local = LocalIeltsPracticeRepository(db)
            val hash = local.cache(legacy.request, legacy.exercisePackage)
            val original = local.begin(hash, "existing-practice", 100)
            val saved = local.saveDraft(original.attemptId, original.revision, emptyList(), "An existing draft.", 200)
            assertEquals(saved, LocalIeltsPracticeRepository(db).get(saved.attemptId))
            assertEquals(hash, LocalIeltsPracticeRepository(db).observeHistory().first().single().packageHash)
        } finally { db.close() }
    }

    @Test fun failureBetweenPackageCacheAndAttemptCreationResumesIdempotently() = runBlocking {
        val db = db()
        try {
            val gateway = FakeGateway(); val access = Access(gateway); val payload = submit()
            gateway.current = job(payload, IeltsGatewayJobState.READY, 3)
            val local = LocalIeltsPracticeRepository(db)
            val broken = object : IeltsPracticeRepository by local {
                override suspend fun begin(packageHash: String, attemptId: String, now: Long): IeltsSavedPractice = error("synthetic storage failure")
            }
            assertNull(newRepository(db, access, broken).generate(payload).packageHash)
            assertEquals(1, db.readingDao().studyRows(IeltsPracticeStorage.PACKAGE_PREFIX).size)
            val resumed = newRepository(db, access).reconcile(payload.requestId)
            assertEquals(IeltsGenerationStage.READY, resumed.stage)
            assertEquals(1, local.observeHistory().first().size)
            assertEquals(1, db.readingDao().studyRows(IeltsPracticeStorage.PACKAGE_PREFIX).size)
            assertEquals(1, gateway.posts.get())
        } finally { db.close() }
    }

    @Test fun cancellationUncertaintyUsesReadsAndDoesNotSendAnotherCancel() = runBlocking {
        val db = db()
        try {
            val gateway = FakeGateway(); val access = Access(gateway); val repository = newRepository(db, access)
            repository.generate(submit()); gateway.cancelFailure = IeltsGatewayFailure(IeltsGatewayFailureCode.TRANSPORT)
            assertEquals(IeltsGenerationIssue.OUTCOME_UNKNOWN, repository.cancel(submit().requestId).issue)
            assertEquals(IeltsGenerationStage.CANCELLED, newRepository(db, access).cancel(submit().requestId).stage)
            assertEquals(1, gateway.cancels.get()); assertEquals(1, gateway.posts.get())
        } finally { db.close() }
    }

    @Test fun lowerRemoteRevisionOrDifferentJobIdentityCannotReplaceAcceptedProgress() = runBlocking {
        val db = db()
        try {
            val gateway = FakeGateway(); val access = Access(gateway); val repository = newRepository(db, access); val payload = submit()
            repository.generate(payload)
            gateway.current = job(payload, IeltsGatewayJobState.REVIEWING, 4)
            val reviewing = repository.reconcile(payload.requestId)
            gateway.current = job(payload, IeltsGatewayJobState.GENERATING, 2)
            assertEquals(reviewing, repository.reconcile(payload.requestId))
            gateway.current = job(payload, IeltsGatewayJobState.GENERATING, 5).copy(id = "job-" + "f".repeat(32))
            val rejected = repository.reconcile(payload.requestId)
            assertEquals(IeltsGenerationIssue.INVALID_RESPONSE, rejected.issue)
            assertEquals(reviewing.job, rejected.job); assertEquals(IeltsGenerationStage.REVIEWING, rejected.stage)
        } finally { db.close() }
    }

    @Test fun corruptGenerationStaysUnavailableWithoutLeakingOrOverwritingItsPayload() = runBlocking {
        val db = db()
        try {
            val gateway = FakeGateway(); val access = Access(gateway); val repository = newRepository(db, access)
            repository.generate(submit())
            val row = PreferenceRow(IeltsGenerationStorage.key(submit().requestId), "{ private source and credentials must stay hidden")
            db.dao().putPreference(row)
            val item = repository.observeHistory().first().single()
            assertEquals(IeltsGenerationStage.UNAVAILABLE, item.stage); assertNull(item.requestId)
            assertFalse(item.toString().contains("private source"))
            fails(IeltsGenerationIssue.STORAGE_UNAVAILABLE) { repository.get(submit().requestId) }
            fails(IeltsGenerationIssue.STORAGE_UNAVAILABLE) { repository.generate(submit()) }
            assertEquals(row, db.dao().preference(row.key)); assertEquals(1, gateway.posts.get())
        } finally { db.close() }
    }

    @Test fun unpairingDoesNotRemovePendingRequestsAndRePairingDoesNotSubmitThem() = runBlocking {
        val db = db()
        try {
            val gateway = FakeGateway(); val access = Access(gateway); val repository = newRepository(db, access)
            val initial = repository.generate(submit())
            repository.clearPairing()
            assertEquals(IeltsGenerationIssue.NOT_PAIRED, repository.reconcile(submit().requestId).issue)
            assertEquals(initial.submit, repository.get(submit().requestId)!!.submit)
            repository.pair("https://original.example", "synthetic-token-not-used")
            assertEquals(1, gateway.posts.get()); repository.reconcile(submit().requestId)
            assertEquals(1, gateway.posts.get()); assertNull(repository.get(submit().requestId)!!.issue)
        } finally { db.close() }
    }
}
