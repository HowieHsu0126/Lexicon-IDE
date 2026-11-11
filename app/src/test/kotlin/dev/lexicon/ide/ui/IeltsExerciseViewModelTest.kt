package dev.lexicon.ide.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Platform-independent ViewModel sequencing on JDK 17. Device/Room/Compose checks remain instrumentation tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class IeltsExerciseViewModelTest {
    private val text = "  Solar panels support public clean energy investment.\n\nThe council approved them.  "
    private val article = Article("article-ui", "ui", listOf("source"), "Synthetic article", "https://example.invalid/article", "",
        readingMode = ReadingMode.NATIVE)
    private val body = ArticleBody(article.id, text, IeltsGatewayProtocol.bodyHash(text), 1, 1000, "Self-authored fixture")
    private val health = IeltsGatewayHealth("lexicon.ielts.health.v1", "ok", IeltsGatewayCapabilities("test-1", "synthetic-corpus",
        "e".repeat(64), listOf("multiple_choice", "identifying_information", "identifying_writers_views", "short_text_completion"),
        listOf("extent_of_agreement"), "official_public_samples_and_format_metadata"))
    private val pairingSecret = "PRIVATE-PAIRING-FILE-TOKEN"
    private val pairingDocument get() = "{\"token\":\"$pairingSecret\",\"fixture\":true}".encodeToByteArray()
    private val pairingPreview = IeltsPairingPreview("https://imported.example/", "7".repeat(64), 1_893_456_000_000)
    private val paragraphs = listOf(IeltsParagraph("p1", 2, text.indexOf('\n'), text.substring(2, text.indexOf('\n'))))
    private val request = IeltsExerciseRequest(IeltsContract.REQUEST, "request-practice", article.id, body.contentHash, paragraphs,
        "a".repeat(64), listOf(IeltsStyleSource("synthetic-source", "b".repeat(64), "Synthetic format.", IeltsStyleScope.PUBLIC_SAMPLE_FORMAT)),
        listOf(IeltsChoiceRequest("q1")), IeltsWritingRequest("w2", IeltsWritingType.EXTENT_OF_AGREEMENT))
    private val ref = paragraphs.first().let { IeltsEvidence(it.paragraphId, it.start, it.end, it.text) }
    private val exercise = IeltsExercisePackage(IeltsContract.PACKAGE, request.requestId, request.articleId, request.bodyHash,
        IeltsExerciseJson.requestHash(request), IeltsContract.LABEL, listOf("synthetic-source"), listOf(
            IeltsChoiceQuestion("q1", "What equipment is mentioned?", listOf(
                IeltsOption(IeltsChoice.A, "Solar panels"), IeltsOption(IeltsChoice.B, "A railway"),
                IeltsOption(IeltsChoice.C, "A ship"), IeltsOption(IeltsChoice.D, "A hospital")),
                1, IeltsChoice.A, "The passage mentions solar panels.", listOf(ref))),
        IeltsWritingTask2("w2", IeltsWritingType.EXTENT_OF_AGREEMENT,
            "Governments should support clean energy. To what extent do you agree or disagree?", 250, 40, listOf(ref)),
        IeltsUnavailableTask1("unavailable", "no_verified_visual"))

    private fun terminalGeneration(requestId: String = "request-history", cancelled: Boolean = false): IeltsGenerationRecord {
        val submit = IeltsGatewaySubmit(requestId = requestId, articleId = article.id, body = text,
            paragraphs = ParagraphFocus.paragraphs(text).mapIndexed { index, paragraph ->
                IeltsParagraph("p${index + 1}", paragraph.start, paragraph.endExclusive, text.substring(paragraph.start, paragraph.endExclusive))
            }, readingRequests = listOf(IeltsChoiceRequest("q1"), IeltsInformationRequest("q2"),
                IeltsViewRequest("q3"), IeltsCompletionRequest("q4", 1, false)),
            writingTask2Request = IeltsWritingRequest("w2", IeltsWritingType.EXTENT_OF_AGREEMENT))
        return IeltsGenerationRecord(submit = submit,
            submitHash = IeltsExerciseJson.canonicalSha256(IeltsGatewayProtocol.encodeSubmit(submit)),
            endpoint = "https://paired.example/", expectedHealth = health, attemptId = "attempt-history",
            revision = 3, createdAt = 100, updatedAt = 110, dispatchClaimed = true,
            job = IeltsGatewayJob("job-" + "a".repeat(32), requestId, "c".repeat(64),
                if (cancelled) IeltsGatewayJobState.CANCELLED else IeltsGatewayJobState.FAILED,
                3, 100, 110, if (cancelled) "cancelled" else "review_rejected", 1),
            stage = if (cancelled) IeltsGenerationStage.CANCELLED else IeltsGenerationStage.FAILED)
    }

    private inner class Practices : IeltsPracticeRepository {
        val values = mutableMapOf<String, IeltsSavedPractice>()
        val history = MutableStateFlow<List<IeltsPracticeHistoryItem>>(emptyList())
        val operations = mutableListOf<String>()
        var saveGate: CompletableDeferred<Unit>? = null
        var submitGate: CompletableDeferred<Unit>? = null
        var failSave = false
        var failAfterCommit = false
        var saves = 0
        var submits = 0
        var getGate: CompletableDeferred<Unit>? = null
        override suspend fun cache(request: IeltsExerciseRequest, exercise: IeltsExercisePackage) = IeltsExerciseJson.packageHash(exercise)
        override suspend fun begin(packageHash: String, attemptId: String, now: Long): IeltsSavedPractice =
            values.getOrPut(attemptId) { IeltsSavedPractice(attemptId, packageHash, request, exercise, emptyList(), "", null, 1, now, now) }
        override suspend fun get(attemptId: String): IeltsSavedPractice? {
            getGate?.await()
            return values[attemptId]
        }
        override fun observeHistory() = history
        override suspend fun saveDraft(id: String, expectedRevision: Long, responses: List<IeltsResponse>, writingText: String, now: Long): IeltsSavedPractice {
            saves++; operations += "save"
            val gate = saveGate.also { saveGate = null }; gate?.await()
            if (failSave) error("private-storage-content")
            val current = values.getValue(id)
            check(current.revision == expectedRevision && current.submission == null)
            return current.copy(draftResponses = responses.toList(), writingText = writingText, revision = current.revision + 1, updatedAt = now)
                .also { values[id] = it }
        }
        override suspend fun submit(id: String, expectedRevision: Long, now: Long): IeltsSavedPractice {
            submits++; operations += "submit"
            submitGate?.await()
            val current = values.getValue(id)
            if (current.submission != null) return current
            check(current.revision == expectedRevision)
            var session = IeltsPracticeSession.begin(current.request, current.exercise, id)
            current.draftResponses.forEach { session = session.answer(it.questionId, it.answer) }
            val pending = session.write(current.writingText).prepareSubmission(now)
            val result = current.copy(submission = pending.pendingSubmission(), revision = current.revision + 1, updatedAt = now)
            values[id] = result
            if (failAfterCommit) { failAfterCommit = false; error("private commit response") }
            return result
        }
        suspend fun seed(id: String = "attempt-ui") = begin(IeltsExerciseJson.packageHash(exercise), id, 100)
    }
    private inner class Generations : IeltsGenerationRepository {
        val history = MutableStateFlow<List<IeltsGenerationHistoryItem>>(emptyList())
        val values = mutableMapOf<String, IeltsGenerationRecord>()
        var connection = IeltsGatewayConnection("https://paired.example/", true, health)
        var generated = 0
        var reconciled = 0
        val reconciledIds = mutableListOf<String>()
        val getIds = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var getGate: CompletableDeferred<Unit>? = null
        var getFailure: Exception? = null
        val failingReadIds = mutableSetOf<String>()
        var generateFailureBeforeSave: Exception? = null
        var generateFailureAfterSave: Exception? = null
        var failReadAfterGenerate = false
        var pairFailure: Exception? = null
        var connectionGate: CompletableDeferred<Unit>? = null
        var lastSubmit: IeltsGatewaySubmit? = null
        var pairCalls = 0
        var inspections = 0
        var importedPairs = 0
        var inspectionGate: CompletableDeferred<Unit>? = null
        var importedPairGate: CompletableDeferred<Unit>? = null
        var inspectionFailure: Exception? = null
        var importedPairFailure: Exception? = null
        var nextPreview = pairingPreview
        val inspectedBuffers = mutableListOf<ByteArray>()
        val inspectedContents = mutableListOf<String>()
        val importedBuffers = mutableListOf<ByteArray>()
        val importedContents = mutableListOf<String>()
        override suspend fun connection(): IeltsGatewayConnection {
            connectionGate?.await()
            return connection
        }
        override suspend fun pair(endpoint: String, token: String): IeltsGatewayConnection {
            pairCalls++
            pairFailure?.let { throw it }; connection = connection.copy(endpoint = endpoint); return connection
        }
        override suspend fun inspectPairing(document: ByteArray): IeltsPairingPreview {
            inspections++; inspectedBuffers += document; inspectedContents += document.decodeToString()
            val result = nextPreview
            inspectionGate?.await()
            inspectionFailure?.let { throw it }
            return result
        }
        override suspend fun pairImported(document: ByteArray): IeltsGatewayConnection {
            importedPairs++; importedBuffers += document; importedContents += document.decodeToString()
            importedPairGate?.await()
            importedPairFailure?.let { throw it }
            connection = IeltsGatewayConnection(nextPreview.endpoint, true, health, nextPreview.certificateSha256)
            return connection
        }
        override suspend fun clearPairing() { connection = IeltsGatewayConnection(null, false, null) }
        override fun observeHistory() = history
        override suspend fun get(requestId: String): IeltsGenerationRecord? {
            getIds += requestId; getGate?.await()
            getFailure?.let { throw it }
            if (requestId in failingReadIds) throw IeltsGenerationFailure(IeltsGenerationIssue.STORAGE_UNAVAILABLE)
            return values[requestId]
        }
        override suspend fun generate(submit: IeltsGatewaySubmit): IeltsGenerationRecord {
            generated++; lastSubmit = submit; gate?.await()
            generateFailureBeforeSave?.let { throw it }
            val result = values.getOrPut(submit.requestId) { IeltsGenerationRecord(submit = submit,
                submitHash = IeltsExerciseJson.canonicalSha256(IeltsGatewayProtocol.encodeSubmit(submit)), endpoint = connection.endpoint!!,
                expectedHealth = health, attemptId = "attempt-ui", revision = 2, createdAt = 100, updatedAt = 101,
                dispatchClaimed = true, stage = IeltsGenerationStage.RECONCILING, issue = IeltsGenerationIssue.OUTCOME_UNKNOWN) }
            if (failReadAfterGenerate) failingReadIds += submit.requestId
            generateFailureAfterSave?.let { throw it }
            return result
        }
        override suspend fun reconcile(requestId: String): IeltsGenerationRecord {
            reconciled++; reconciledIds += requestId; return values.getValue(requestId)
        }
        override suspend fun cancel(requestId: String) = values.getValue(requestId)
    }
    private inner class Fixture(scope: TestScope, val handle: SavedStateHandle = SavedStateHandle()) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val practices = Practices()
        val generations = Generations()
        var ids = 0
        private val store = ViewModelStore()
        val vm: IeltsExerciseViewModel
        init {
            Dispatchers.setMain(dispatcher)
            vm = IeltsExerciseViewModel(generations, practices, handle, clock = { 500 }, idFactory = { "request-ui-${++ids}" },
                validationDispatcher = dispatcher)
            store.put("ielts-test", vm)
        }
        fun close() { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun preparingBeforePairingStillOptsIntoDeclaredTask1AtGenerate() = runTest {
        val f = Fixture(this)
        try {
            runCurrent()
            f.vm.prepare(article, body, true); advanceUntilIdle()
            f.generations.connection = f.generations.connection.copy(health = health.copy(capabilities =
                health.capabilities.copy(supportedWritingTask1Policies = listOf("source_numeric_visual_v1"))))
            f.vm.refresh(); advanceUntilIdle()
            f.vm.generate(); advanceUntilIdle()
            assertEquals(IeltsVisualRequest("w1"), f.generations.lastSubmit?.writingTask1Request)
        } finally { f.close() }
    }

    @Test fun explicitPrepareFreezesExactRangesAndRepeatedEntryDoesNotCreateAnotherIdOrPost() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.vm.prepare(article, body, true); advanceUntilIdle()
            assertTrue(f.vm.state.value.canGenerate); assertEquals(0, f.generations.generated)
            f.vm.prepare(article, body, true); advanceUntilIdle(); assertEquals(1, f.ids)
            assertEquals(setOf(IeltsExerciseViewModel.REQUEST_KEY), f.handle.keys())
            assertFalse(f.handle.get<String>(IeltsExerciseViewModel.REQUEST_KEY)!!.contains(text))
            f.vm.generate(); advanceUntilIdle()
            val sent = requireNotNull(f.generations.lastSubmit)
            assertEquals(text, sent.body)
            assertEquals(IeltsCompletionRequest("q4", 1, false), sent.readingRequests.last())
            sent.paragraphs.forEach { assertEquals(it.text, text.substring(it.start, it.end)) }
            assertEquals(2, sent.paragraphs.first().start); assertEquals(1, f.generations.generated)
        } finally { f.close() }
    }

    @Test fun prepareRequestedWhileInitializationIsRunningIsNotSilentlyDropped() = runTest {
        val f = Fixture(this)
        try {
            f.vm.prepare(article, body, true)
            advanceUntilIdle()
            assertEquals(article.id, f.vm.state.value.preparedArticleId)
            assertTrue(f.vm.state.value.canGenerate); assertEquals(1, f.ids)
            assertEquals(0, f.generations.generated)
        } finally { f.close() }
    }

    @Test fun leavingASelectionDisablesGenerateBeforeTheExitCallbackRuns() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.vm.prepare(article, body, true); advanceUntilIdle()
            assertTrue(f.vm.state.value.canGenerate)
            var closed = false
            f.vm.onBack { closed = true }
            assertFalse(f.vm.state.value.canGenerate)
            f.vm.generate(); advanceUntilIdle()
            assertTrue(closed); assertEquals(0, f.generations.generated)
            assertFalse(f.vm.state.value.canGenerate)
        } finally { f.close() }
    }

    @Test fun disabledSourceAndOverlongTextCannotReuseAnEarlierPreparedRequest() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.vm.prepare(article, body, true); advanceUntilIdle()
            f.vm.prepare(article, body, false); f.vm.generate(); advanceUntilIdle()
            assertFalse(f.vm.state.value.canGenerate); assertEquals(0, f.generations.generated)
            val large = "a".repeat(24_001)
            f.vm.prepare(article, body.copy(text = large, contentHash = IeltsGatewayProtocol.bodyHash(large)), true); advanceUntilIdle()
            assertFalse(f.vm.state.value.canGenerate); assertTrue(f.vm.state.value.error!!.contains("24,000"))
            assertEquals(0, f.generations.generated)
        } finally { f.close() }
    }

    @Test fun duplicateGenerateDuringAnInFlightOperationIsIgnoredAndRetryOnlyReconciles() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.vm.prepare(article, body, true); advanceUntilIdle()
            val gate = CompletableDeferred<Unit>(); f.generations.gate = gate
            f.vm.generate(); runCurrent(); f.vm.generate(); runCurrent()
            assertEquals(1, f.generations.generated); assertTrue(f.vm.state.value.busy)
            gate.complete(Unit); advanceUntilIdle(); f.vm.retry(); advanceUntilIdle()
            assertEquals(1, f.generations.generated); assertEquals(1, f.generations.reconciled)
        } finally { f.close() }
    }

    @Test fun terminalRegenerationCopiesSavedMaterialOnceAndPreservesOldRecordsAndPractice() = runTest {
        val f = Fixture(this)
        try {
            runCurrent()
            val original = terminalGeneration()
            f.generations.values[original.requestId] = original
            val practice = f.practices.seed().copy(writingText = "My retained paragraph.", revision = 2)
            f.practices.values[practice.attemptId] = practice
            f.vm.openGeneration(original.requestId); advanceUntilIdle()
            assertTrue(f.vm.state.value.canRegenerate)
            val gate = CompletableDeferred<Unit>(); f.generations.gate = gate
            f.vm.regenerate()
            assertTrue(f.vm.state.value.busy); assertTrue(f.vm.state.value.isSaving)
            assertTrue(f.vm.state.value.canRegenerate) // Keep the disabled button in place during the operation.
            f.vm.regenerate(); runCurrent()
            assertEquals(1, f.generations.generated)
            assertEquals(original.requestId, f.handle.get<String>(IeltsExerciseViewModel.REQUEST_KEY))
            val newId = requireNotNull(f.generations.lastSubmit).requestId
            assertNotEquals(original.requestId, newId)
            assertEquals(original.submit.copy(requestId = newId), f.generations.lastSubmit)
            assertEquals(newId, f.handle.get<String>(IeltsExerciseViewModel.PENDING_REQUEST_KEY))
            gate.complete(Unit); advanceUntilIdle()
            assertEquals(newId, f.handle.get<String>(IeltsExerciseViewModel.REQUEST_KEY))
            assertNull(f.handle.get<String>(IeltsExerciseViewModel.PENDING_REQUEST_KEY))
            assertEquals(newId, f.vm.state.value.generation?.requestId)
            assertEquals(original, f.generations.values[original.requestId])
            assertEquals(practice, f.practices.values[practice.attemptId])
            assertEquals(0, f.practices.saves); assertEquals(0, f.practices.submits)
            f.vm.refresh(); advanceUntilIdle(); f.vm.retry(); advanceUntilIdle()
            assertEquals(listOf(newId, newId), f.generations.reconciledIds)
            assertEquals(1, f.generations.generated)
        } finally { f.close() }
    }

    @Test fun cancelledAndFailedHistoryEntriesRegenerateFromTheDurableSubmitWithoutPrepare() = runTest {
        for (cancelled in listOf(false, true)) {
            val original = terminalGeneration(cancelled = cancelled)
            val handle = SavedStateHandle(mapOf(IeltsExerciseViewModel.REQUEST_KEY to original.requestId))
            val f = Fixture(this, handle)
            try {
                f.generations.values[original.requestId] = original; advanceUntilIdle()
                assertEquals(article.id, f.vm.state.value.preparedArticleId)
                assertEquals(original.submit.paragraphs.size, f.vm.state.value.preparedParagraphCount)
                assertTrue(f.vm.state.value.canRegenerate); assertFalse(f.vm.state.value.canGenerate)
                f.vm.regenerate(); advanceUntilIdle()
                assertEquals(1, f.generations.generated)
                val sent = requireNotNull(f.generations.lastSubmit)
                assertEquals(original.submit.copy(requestId = sent.requestId), sent)
                assertEquals(original, f.generations.values[original.requestId])
            } finally { f.close() }
        }
    }

    @Test fun regenerationRequiresAReapedMatchingTerminalJobAndKnownProviderOutcome() = runTest {
        val original = terminalGeneration()
        val job = requireNotNull(original.job)
        val cases = listOf(
            original.copy(job = null),
            original.copy(dispatchClaimed = false),
            original.copy(job = job.copy(processReaped = 0)),
            original.copy(job = job.copy(requestId = "request-other")),
            original.copy(job = job.copy(error = "provider_completion_unknown")),
            original.copy(issue = IeltsGenerationIssue.OUTCOME_UNKNOWN),
            original.copy(stage = IeltsGenerationStage.INTERRUPTED, job = job.copy(state = IeltsGatewayJobState.INTERRUPTED)),
            original.copy(stage = IeltsGenerationStage.RECONCILING),
            original.copy(stage = IeltsGenerationStage.UNAVAILABLE),
            original.copy(stage = IeltsGenerationStage.CANCELLED),
            original.copy(job = job.copy(state = IeltsGatewayJobState.CANCELLED)),
            original.copy(job = job.copy(state = IeltsGatewayJobState.GENERATING)),
            original.copy(packageHash = "f".repeat(64)),
        )
        for (record in cases) {
            val f = Fixture(this)
            try {
                runCurrent(); f.generations.values[record.requestId] = record
                f.vm.openGeneration(record.requestId); advanceUntilIdle()
                assertFalse("${record.stage}/${record.job?.state}/${record.job?.error}", f.vm.state.value.canRegenerate)
                if (record.job?.error == "provider_completion_unknown") {
                    assertEquals(IeltsGenerationIssue.OUTCOME_UNKNOWN, f.vm.state.value.generation?.issue)
                    assertEquals(IeltsGenerationStage.FAILED, f.vm.state.value.generation?.stage)
                }
                f.vm.regenerate(); advanceUntilIdle()
                assertEquals(0, f.generations.generated); assertEquals(0, f.ids)
                assertEquals(record, f.generations.values[record.requestId])
            } finally { f.close() }
        }
    }

    @Test fun terminalEligibilityIsRereadBeforeAllocatingOrSendingAnotherRequest() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); val original = terminalGeneration(); f.generations.values[original.requestId] = original
            f.vm.openGeneration(original.requestId); advanceUntilIdle(); assertTrue(f.vm.state.value.canRegenerate)
            val unknown = original.copy(job = requireNotNull(original.job).copy(error = "provider_completion_unknown"))
            f.generations.values[original.requestId] = unknown
            f.vm.regenerate(); advanceUntilIdle()
            assertFalse(f.vm.state.value.canRegenerate); assertEquals(0, f.generations.generated); assertEquals(0, f.ids)
            assertEquals(original.requestId, f.handle.get<String>(IeltsExerciseViewModel.REQUEST_KEY))
            assertEquals(unknown, f.generations.values[original.requestId])
        } finally { f.close() }
    }

    @Test fun disabledSourcePreventsRegenerationAfterRefreshAndHistoryReopen() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); val original = terminalGeneration(); f.generations.values[original.requestId] = original
            f.vm.openGeneration(original.requestId); advanceUntilIdle(); assertTrue(f.vm.state.value.canRegenerate)
            f.vm.prepare(article, body, false); f.vm.regenerate(); advanceUntilIdle()
            assertFalse(f.vm.state.value.canRegenerate)
            f.vm.refresh(); advanceUntilIdle(); f.vm.openGeneration(original.requestId); advanceUntilIdle()
            f.vm.regenerate(); advanceUntilIdle()
            assertFalse(f.vm.state.value.canRegenerate); assertEquals(0, f.generations.generated)
        } finally { f.close() }
    }

    @Test fun disablingAiWhileTheOriginalRecordIsBeingReadPreventsTheNewPost() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); val original = terminalGeneration(); f.generations.values[original.requestId] = original
            f.vm.openGeneration(original.requestId); advanceUntilIdle()
            val gate = CompletableDeferred<Unit>(); f.generations.getGate = gate
            f.vm.regenerate(); runCurrent(); f.vm.prepare(article, body, false)
            gate.complete(Unit); advanceUntilIdle()
            assertEquals(0, f.generations.generated); assertFalse(f.vm.state.value.canRegenerate)
            assertEquals(original.requestId, f.handle.get<String>(IeltsExerciseViewModel.REQUEST_KEY))
        } finally { f.close() }
    }

    @Test fun aFailedOriginalReadKeepsNavigationAndDoesNotAllocateANewId() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); val original = terminalGeneration(); f.generations.values[original.requestId] = original
            f.vm.openGeneration(original.requestId); advanceUntilIdle()
            f.generations.getFailure = IllegalStateException("private storage diagnostic")
            f.vm.regenerate(); advanceUntilIdle()
            assertEquals(0, f.generations.generated); assertEquals(0, f.ids)
            assertEquals(original.requestId, f.handle.get<String>(IeltsExerciseViewModel.REQUEST_KEY))
            assertEquals(original.requestId, f.vm.state.value.generation?.requestId)
            assertNull(f.handle.get<String>(IeltsExerciseViewModel.PENDING_REQUEST_KEY))
            assertEquals(original, f.generations.values[original.requestId])
            assertFalse(f.vm.state.value.error.orEmpty().contains("private"))
        } finally { f.close() }
    }

    @Test fun failedCreationKeepsTheOldSelectionUntilALocalLookupConfirmsNoNewRecord() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); val original = terminalGeneration(); f.generations.values[original.requestId] = original
            f.vm.openGeneration(original.requestId); advanceUntilIdle()
            f.generations.generateFailureBeforeSave = IeltsGenerationFailure(IeltsGenerationIssue.STORAGE_UNAVAILABLE)
            f.vm.regenerate(); advanceUntilIdle()
            val pending = requireNotNull(f.handle.get<String>(IeltsExerciseViewModel.PENDING_REQUEST_KEY))
            assertEquals(original.requestId, f.handle.get<String>(IeltsExerciseViewModel.REQUEST_KEY))
            assertEquals(original.requestId, f.vm.state.value.generation?.requestId)
            assertFalse(f.vm.state.value.canRegenerate); assertFalse(f.generations.values.containsKey(pending))
            f.vm.regenerate(); advanceUntilIdle(); assertEquals(1, f.generations.generated)
            f.vm.retry(); advanceUntilIdle()
            assertNull(f.handle.get<String>(IeltsExerciseViewModel.PENDING_REQUEST_KEY))
            assertEquals(original.requestId, f.handle.get<String>(IeltsExerciseViewModel.REQUEST_KEY))
            assertEquals(listOf(original.requestId), f.generations.reconciledIds)
            assertTrue(f.vm.state.value.canRegenerate); assertEquals(1, f.generations.generated)
            assertEquals(original, f.generations.values[original.requestId])
        } finally { f.close() }
    }

    @Test fun failureAfterSavingTheNewRecordRecoversByLookupWithoutAnotherGenerate() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); val original = terminalGeneration(); f.generations.values[original.requestId] = original
            f.vm.openGeneration(original.requestId); advanceUntilIdle()
            f.generations.generateFailureAfterSave = IeltsGenerationFailure(IeltsGenerationIssue.STORAGE_UNAVAILABLE)
            f.vm.regenerate(); advanceUntilIdle()
            val newId = requireNotNull(f.handle.get<String>(IeltsExerciseViewModel.PENDING_REQUEST_KEY))
            assertEquals(original.requestId, f.handle.get<String>(IeltsExerciseViewModel.REQUEST_KEY))
            assertFalse(f.vm.state.value.canRegenerate); assertTrue(f.generations.values.containsKey(newId))
            f.vm.openGeneration(original.requestId); advanceUntilIdle()
            assertEquals(newId, f.handle.get<String>(IeltsExerciseViewModel.PENDING_REQUEST_KEY))
            f.vm.retry(); advanceUntilIdle()
            assertEquals(newId, f.handle.get<String>(IeltsExerciseViewModel.REQUEST_KEY))
            assertNull(f.handle.get<String>(IeltsExerciseViewModel.PENDING_REQUEST_KEY))
            assertEquals(1, f.generations.generated); assertEquals(original, f.generations.values[original.requestId])
            f.vm.refresh(); advanceUntilIdle(); assertEquals(listOf(newId), f.generations.reconciledIds)
        } finally { f.close() }
    }

    @Test fun unavailableReadAfterGenerationPreservesBothIdsUntilTheSavedNewRecordCanBeConfirmed() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); val original = terminalGeneration(); f.generations.values[original.requestId] = original
            f.vm.openGeneration(original.requestId); advanceUntilIdle()
            f.generations.failReadAfterGenerate = true
            f.vm.regenerate(); advanceUntilIdle()
            val newId = requireNotNull(f.handle.get<String>(IeltsExerciseViewModel.PENDING_REQUEST_KEY))
            assertEquals(original.requestId, f.handle.get<String>(IeltsExerciseViewModel.REQUEST_KEY))
            f.vm.retry(); advanceUntilIdle()
            assertEquals(original.requestId, f.handle.get<String>(IeltsExerciseViewModel.REQUEST_KEY))
            assertFalse(f.vm.state.value.canRegenerate); assertEquals(1, f.generations.generated)
            f.generations.failingReadIds.clear(); f.vm.refresh(); advanceUntilIdle()
            assertEquals(newId, f.handle.get<String>(IeltsExerciseViewModel.REQUEST_KEY))
            assertEquals(1, f.generations.generated); assertEquals(original, f.generations.values[original.requestId])
        } finally { f.close() }
    }

    @Test fun processRestorationResolvesThePendingNewIdentityUsingLocalReadsOnly() = runTest {
        val original = terminalGeneration()
        val first = Fixture(this)
        val records: Map<String, IeltsGenerationRecord>
        val snapshot: Map<String, String>
        try {
            runCurrent(); first.generations.values[original.requestId] = original
            first.vm.openGeneration(original.requestId); advanceUntilIdle()
            first.generations.generateFailureAfterSave = IeltsGenerationFailure(IeltsGenerationIssue.STORAGE_UNAVAILABLE)
            first.vm.regenerate(); advanceUntilIdle()
            records = first.generations.values.toMap()
            snapshot = first.handle.keys().associateWith { requireNotNull(first.handle.get<String>(it)) }
        } finally { first.close() }
        val restored = Fixture(this, SavedStateHandle(snapshot))
        try {
            restored.generations.values.putAll(records); advanceUntilIdle()
            val newId = requireNotNull(snapshot[IeltsExerciseViewModel.PENDING_REQUEST_KEY])
            assertEquals(newId, restored.handle.get<String>(IeltsExerciseViewModel.REQUEST_KEY))
            assertEquals(newId, restored.vm.state.value.generation?.requestId)
            assertNull(restored.handle.get<String>(IeltsExerciseViewModel.PENDING_REQUEST_KEY))
            assertEquals(0, restored.generations.generated); assertEquals(0, restored.generations.reconciled)
            assertEquals(original, restored.generations.values[original.requestId])
            assertEquals(setOf(IeltsExerciseViewModel.REQUEST_KEY), restored.handle.keys())
        } finally { restored.close() }
    }

    @Test fun collidingNewRequestIdCannotReuseOrChangeAnExistingRecord() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); val original = terminalGeneration("request-ui-1"); f.generations.values[original.requestId] = original
            f.vm.openGeneration(original.requestId); advanceUntilIdle(); f.vm.regenerate(); advanceUntilIdle()
            assertEquals(0, f.generations.generated); assertEquals(original, f.generations.values[original.requestId])
            assertEquals(original.requestId, f.handle.get<String>(IeltsExerciseViewModel.REQUEST_KEY))
            assertNull(f.handle.get<String>(IeltsExerciseViewModel.PENDING_REQUEST_KEY))
        } finally { f.close() }
    }

    @Test fun restoringAnAttemptIdReadsLocalPracticeWithoutSendingOrPollingGeneration() = runTest {
        val handle = SavedStateHandle(mapOf(IeltsExerciseViewModel.ATTEMPT_KEY to "attempt-ui"))
        val f = Fixture(this, handle)
        try {
            f.practices.seed(); runCurrent()
            assertNotNull(f.vm.state.value.candidate)
            assertEquals(0, f.generations.generated); assertEquals(0, f.generations.reconciled)
            assertEquals(setOf(IeltsExerciseViewModel.ATTEMPT_KEY), handle.keys())
        } finally { f.close() }
    }

    @Test fun typingAppearsImmediatelyAndDebouncedSaveKeepsEditsMadeDuringThePreviousSave() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.practices.seed(); f.vm.openAttempt("attempt-ui"); advanceUntilIdle()
            f.vm.answer("q1", "A"); f.vm.write("first draft")
            assertEquals("first draft", f.vm.state.value.writingText); assertEquals(0, f.practices.saves)
            advanceTimeBy(449); runCurrent(); assertEquals(0, f.practices.saves)
            val gate = CompletableDeferred<Unit>(); f.practices.saveGate = gate
            advanceTimeBy(1); runCurrent(); assertEquals(1, f.practices.saves)
            f.vm.write("second draft")
            gate.complete(Unit); runCurrent()
            assertEquals("second draft", f.vm.state.value.writingText); assertTrue(f.vm.state.value.dirty)
            advanceUntilIdle()
            assertEquals("second draft", f.practices.values.getValue("attempt-ui").writingText)
            assertFalse(f.vm.state.value.dirty); assertEquals(2, f.practices.saves)
        } finally { f.close() }
    }

    @Test fun submitIgnoredDuringRefreshDoesNotCancelThePendingDraftSave() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.practices.seed(); f.vm.openAttempt("attempt-ui"); advanceUntilIdle()
            f.vm.answer("q1", "A"); f.vm.write("save while refreshing")
            val gate = CompletableDeferred<Unit>(); f.generations.connectionGate = gate
            f.vm.refresh(); runCurrent(); assertTrue(f.vm.state.value.busy)
            f.vm.submit(); advanceTimeBy(450); runCurrent()
            assertEquals(1, f.practices.saves); assertEquals(0, f.practices.submits)
            assertEquals("save while refreshing", f.practices.values.getValue("attempt-ui").writingText)
            gate.complete(Unit); advanceUntilIdle()
        } finally { f.close() }
    }

    @Test fun submitFirstFlushesAllInputAndShowsAnswersOnlyAfterTheCommitReturns() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.practices.seed(); f.vm.openAttempt("attempt-ui"); advanceUntilIdle()
            f.vm.answer("q1", "B"); f.vm.write("retained writing")
            val gate = CompletableDeferred<Unit>(); f.practices.submitGate = gate
            f.vm.submit(); runCurrent(); f.vm.submit(); f.vm.write("must not replace pending writing"); runCurrent()
            assertEquals(listOf("save", "submit"), f.practices.operations)
            assertNull(f.vm.state.value.answerView); assertTrue(f.vm.state.value.isSaving)
            assertEquals("retained writing", f.vm.state.value.writingText); assertEquals(1, f.practices.submits)
            gate.complete(Unit); advanceUntilIdle()
            assertEquals("B", f.vm.state.value.answerView!!.readingAnswers.single().submittedAnswer)
            assertEquals("A", f.vm.state.value.answerView!!.readingAnswers.single().referenceAnswer)
            assertFalse(f.vm.state.value.dirty)
        } finally { f.close() }
    }

    @Test fun failedFlushKeepsInputDoesNotSubmitAndRetryCompletesTheOriginalAttempt() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.practices.seed(); f.vm.openAttempt("attempt-ui"); advanceUntilIdle()
            f.vm.answer("q1", "A"); f.vm.write("preserve this draft"); f.practices.failSave = true
            f.vm.submit(); advanceUntilIdle()
            assertEquals(0, f.practices.submits); assertNull(f.vm.state.value.answerView)
            assertEquals("preserve this draft", f.vm.state.value.writingText); assertTrue(f.vm.state.value.dirty)
            assertFalse(f.vm.state.value.error!!.contains("private-storage"))
            f.practices.failSave = false; f.vm.retry(); advanceUntilIdle()
            assertNotNull(f.vm.state.value.answerView); assertEquals(1, f.practices.submits)
            assertEquals(1, f.practices.values.size)
        } finally { f.close() }
    }

    @Test fun unknownCommitResponseRetainsInputAndRetryReusesTheCommittedSubmission() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.practices.seed(); f.vm.openAttempt("attempt-ui"); advanceUntilIdle()
            f.vm.answer("q1", "A"); f.vm.write("same input"); f.practices.failAfterCommit = true
            f.vm.submit(); advanceUntilIdle()
            val committed = f.practices.values.getValue("attempt-ui").submission
            assertNotNull(committed); assertNull(f.vm.state.value.answerView)
            assertEquals("same input", f.vm.state.value.writingText)
            f.vm.retry(); advanceUntilIdle()
            assertNotNull(f.vm.state.value.answerView); assertEquals(committed, f.practices.values.getValue("attempt-ui").submission)
        } finally { f.close() }
    }

    @Test fun backWaitsForDraftCommitThenClearsOnlySelectionIdentities() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.practices.seed(); f.vm.openAttempt("attempt-ui"); advanceUntilIdle()
            f.vm.answer("q1", "A"); f.vm.write("save before leaving")
            val gate = CompletableDeferred<Unit>(); f.practices.saveGate = gate
            var closed = 0
            f.vm.onBack { closed++ }; runCurrent(); assertEquals(0, closed)
            f.vm.onBack { closed++ }; runCurrent(); assertEquals(0, closed)
            gate.complete(Unit); advanceUntilIdle()
            assertEquals(1, closed); assertNull(f.vm.state.value.candidate); assertTrue(f.handle.keys().isEmpty())
            assertEquals("save before leaving", f.practices.values.getValue("attempt-ui").writingText)
        } finally { f.close() }
    }

    @Test fun failedBackFlushOrNavigationCannotDiscardTheUnsavedAttempt() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.practices.seed(); f.practices.seed("attempt-other")
            f.vm.openAttempt("attempt-ui"); advanceUntilIdle(); f.vm.write("unsaved draft"); f.practices.failSave = true
            var closed = false; f.vm.onBack { closed = true }; advanceUntilIdle()
            assertFalse(closed); assertNotNull(f.vm.state.value.candidate); assertEquals("unsaved draft", f.vm.state.value.writingText)
            f.vm.openAttempt("attempt-other"); advanceUntilIdle()
            assertEquals("attempt-ui", f.handle.get<String>(IeltsExerciseViewModel.ATTEMPT_KEY))
            assertEquals("unsaved draft", f.vm.state.value.writingText)
        } finally { f.close() }
    }

    @Test fun pairingFailuresNeverExposeOrSaveExceptionTextOrTokens() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.generations.pairFailure = IllegalStateException("SECRET-TOKEN-in-raw-error")
            f.vm.pair("https://paired.example/", "SECRET-TOKEN"); advanceUntilIdle()
            assertFalse(f.vm.state.value.toString().contains("SECRET-TOKEN")); assertTrue(f.handle.keys().isEmpty())
            assertEquals(0, f.generations.generated)
        } finally { f.close() }
    }

    @Test fun inspectingAConnectionFileIsLocalAndKeepsSecretsOutOfPublicAndSavedState() = runTest {
        val f = Fixture(this)
        try {
            runCurrent()
            val previous = f.generations.connection
            val document = pairingDocument
            f.vm.inspectPairing(document)
            document.fill(0) // The picker owns and clears its input after handing it to the ViewModel.
            advanceUntilIdle()
            assertEquals(pairingPreview, f.vm.state.value.pairingPreview)
            assertEquals(previous, f.vm.state.value.connection)
            assertEquals(1, f.generations.inspections)
            assertEquals(pairingDocument.decodeToString(), f.generations.inspectedContents.single())
            assertEquals(0, f.generations.pairCalls); assertEquals(0, f.generations.importedPairs)
            assertEquals(0, f.generations.generated); assertEquals(0, f.generations.reconciled)
            assertTrue(f.handle.keys().isEmpty())
            assertFalse(f.vm.state.value.toString().contains(pairingSecret))
            assertFalse(f.vm.state.value.toString().contains("\"token\""))
            assertTrue(f.generations.inspectedBuffers.single().all { it == 0.toByte() })
            advanceTimeBy(60_000); runCurrent()
            assertEquals(0, f.generations.importedPairs)
        } finally { f.close() }
    }

    @Test fun failedImportedConfirmationPreservesPreviewForOneExplicitRetryWithoutGenerating() = runTest {
        val f = Fixture(this)
        try {
            runCurrent()
            val previous = f.generations.connection
            val original = pairingDocument.decodeToString()
            f.vm.inspectPairing(pairingDocument); advanceUntilIdle()
            f.generations.importedPairFailure = IllegalStateException("$pairingSecret raw connection failure")
            f.vm.confirmImportedPairing(); advanceUntilIdle()
            assertEquals(1, f.generations.importedPairs)
            assertEquals(pairingPreview, f.vm.state.value.pairingPreview)
            assertEquals(previous, f.vm.state.value.connection)
            assertNotNull(f.vm.state.value.error)
            assertFalse(f.vm.state.value.toString().contains(pairingSecret))
            assertTrue(f.handle.keys().isEmpty())
            assertTrue(f.generations.importedBuffers.single().all { it == 0.toByte() })
            advanceTimeBy(60_000); runCurrent()
            assertEquals(1, f.generations.importedPairs)

            f.generations.importedPairFailure = null
            val gate = CompletableDeferred<Unit>(); f.generations.importedPairGate = gate
            f.vm.confirmImportedPairing(); runCurrent()
            f.vm.confirmImportedPairing(); runCurrent()
            assertEquals(2, f.generations.importedPairs); assertTrue(f.vm.state.value.busy)
            gate.complete(Unit); advanceUntilIdle()
            assertEquals(listOf(original, original), f.generations.importedContents)
            assertNull(f.vm.state.value.pairingPreview); assertNull(f.vm.state.value.error)
            assertEquals(pairingPreview.endpoint, f.vm.state.value.connection.endpoint)
            assertEquals(pairingPreview.certificateSha256, f.vm.state.value.connection.certificateSha256)
            assertTrue(f.generations.importedBuffers.all { bytes -> bytes.all { it == 0.toByte() } })
            assertEquals(0, f.generations.pairCalls); assertEquals(0, f.generations.generated)
        } finally { f.close() }
    }

    @Test fun discardingPreviewKeepsTheExistingPairingAndMakesConfirmationANoOp() = runTest {
        val f = Fixture(this)
        try {
            runCurrent()
            val previous = f.vm.state.value.connection
            f.vm.inspectPairing(pairingDocument); advanceUntilIdle()
            f.vm.discardPairing(); f.vm.confirmImportedPairing(); advanceUntilIdle()
            assertNull(f.vm.state.value.pairingPreview)
            assertEquals(previous, f.vm.state.value.connection)
            assertEquals(previous, f.generations.connection)
            assertEquals(0, f.generations.importedPairs); assertEquals(0, f.generations.pairCalls)
            assertTrue(f.handle.keys().isEmpty())
            assertFalse(f.vm.state.value.toString().contains(pairingSecret))
        } finally { f.close() }
    }

    @Test fun unreadableReplacementDropsTheOldPreviewWithoutChangingTheExistingPairing() = runTest {
        val f = Fixture(this)
        try {
            runCurrent()
            val previous = f.vm.state.value.connection
            f.vm.inspectPairing(pairingDocument); advanceUntilIdle()
            f.vm.pairingImportFailed(); f.vm.confirmImportedPairing(); advanceUntilIdle()
            assertNull(f.vm.state.value.pairingPreview)
            assertEquals(previous, f.vm.state.value.connection)
            assertTrue(f.vm.state.value.error!!.contains("连接文件无法读取"))
            assertEquals(0, f.generations.importedPairs); assertTrue(f.handle.keys().isEmpty())
        } finally { f.close() }
    }

    @Test fun discardedInspectionCannotRestoreItsPreviewWhenItsLocalReadFinishesLate() = runTest {
        val f = Fixture(this)
        try {
            runCurrent()
            val gate = CompletableDeferred<Unit>(); f.generations.inspectionGate = gate
            f.vm.inspectPairing(pairingDocument); runCurrent()
            assertEquals(1, f.generations.inspections); assertTrue(f.vm.state.value.busy)
            f.vm.discardPairing()
            gate.complete(Unit); advanceUntilIdle()
            assertNull(f.vm.state.value.pairingPreview)
            assertEquals(0, f.generations.importedPairs)
            assertTrue(f.generations.inspectedBuffers.single().all { it == 0.toByte() })

            f.generations.inspectionGate = null
            val replacement = pairingPreview.copy(endpoint = "https://replacement.example/")
            f.generations.nextPreview = replacement
            f.vm.inspectPairing(pairingDocument); advanceUntilIdle()
            assertEquals(replacement, f.vm.state.value.pairingPreview)
            assertEquals(0, f.generations.importedPairs)
        } finally { f.close() }
    }

    @Test fun discardedInspectionFailureCannotReplaceTheNewerImportError() = runTest {
        val f = Fixture(this)
        try {
            runCurrent()
            val gate = CompletableDeferred<Unit>(); f.generations.inspectionGate = gate
            f.generations.inspectionFailure = IllegalStateException("$pairingSecret stale read failure")
            f.vm.inspectPairing(pairingDocument); runCurrent()
            f.vm.pairingImportFailed()
            val replacementError = f.vm.state.value.error
            assertNotNull(replacementError)
            gate.complete(Unit); advanceUntilIdle()
            assertEquals(replacementError, f.vm.state.value.error)
            assertNull(f.vm.state.value.pairingPreview)
            assertFalse(f.vm.state.value.toString().contains(pairingSecret))
            assertEquals(0, f.generations.importedPairs)
        } finally { f.close() }
    }

    @Test fun discardedConnectionFailureCannotReplaceTheNewerImportError() = runTest {
        val f = Fixture(this)
        try {
            runCurrent()
            val previous = f.vm.state.value.connection
            f.vm.inspectPairing(pairingDocument); advanceUntilIdle()
            val gate = CompletableDeferred<Unit>(); f.generations.importedPairGate = gate
            f.generations.importedPairFailure = IllegalStateException("$pairingSecret stale connection failure")
            f.vm.confirmImportedPairing(); runCurrent()
            assertEquals(1, f.generations.importedPairs)
            f.vm.pairingImportFailed()
            val replacementError = f.vm.state.value.error
            assertNotNull(replacementError)
            gate.complete(Unit); advanceUntilIdle()
            assertEquals(replacementError, f.vm.state.value.error)
            assertNull(f.vm.state.value.pairingPreview)
            assertEquals(previous, f.vm.state.value.connection)
            assertTrue(f.generations.importedBuffers.single().all { it == 0.toByte() })
            assertFalse(f.vm.state.value.toString().contains(pairingSecret))
        } finally { f.close() }
    }

    @Test fun aNewViewModelDoesNotRestoreOrAutomaticallyConfirmAnEphemeralPreview() = runTest {
        val handle = SavedStateHandle()
        val first = Fixture(this, handle)
        try {
            runCurrent(); first.vm.inspectPairing(pairingDocument); advanceUntilIdle()
            assertNotNull(first.vm.state.value.pairingPreview)
            assertTrue(handle.keys().isEmpty())
        } finally { first.close() }
        val restored = Fixture(this, handle)
        try {
            advanceUntilIdle(); restored.vm.confirmImportedPairing(); advanceUntilIdle()
            assertNull(restored.vm.state.value.pairingPreview)
            assertEquals(0, restored.generations.inspections); assertEquals(0, restored.generations.importedPairs)
            assertEquals(0, restored.generations.generated)
        } finally { restored.close() }
    }

    @Test fun resultArrivingAfterBackDoesNotReopenTheClosedSelection() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.vm.prepare(article, body, true); advanceUntilIdle()
            val gate = CompletableDeferred<Unit>(); f.generations.gate = gate
            f.vm.generate(); runCurrent()
            var closed = false; f.vm.onBack { closed = true }; runCurrent(); assertTrue(closed)
            gate.complete(Unit); advanceUntilIdle()
            assertNull(f.vm.state.value.generation); assertNull(f.vm.state.value.candidate); assertTrue(f.handle.keys().isEmpty())
            assertEquals(1, f.generations.values.size)
        } finally { f.close() }
    }

    @Test fun localPracticeReadArrivingAfterBackCannotReopenTheClosedSelection() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.practices.seed()
            val gate = CompletableDeferred<Unit>(); f.practices.getGate = gate
            f.vm.openAttempt("attempt-ui"); runCurrent()
            var closed = false; f.vm.onBack { closed = true }; runCurrent()
            assertTrue(closed); assertNull(f.vm.state.value.candidate)
            gate.complete(Unit); advanceUntilIdle()
            assertNull(f.vm.state.value.candidate); assertTrue(f.handle.keys().isEmpty())
        } finally { f.close() }
    }
}
