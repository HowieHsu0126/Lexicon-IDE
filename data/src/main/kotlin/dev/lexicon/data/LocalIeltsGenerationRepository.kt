@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object IeltsGenerationStorage {
    const val PREFIX = "ielts.generation."
    private val codec = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = false; classDiscriminator = "type" }
    private val digest = Regex("[a-f0-9]{64}")
    fun key(requestId: String): String {
        IeltsGatewayProtocol.validateRequestId(requestId)
        return PREFIX + requestId
    }
    fun submitHash(submit: IeltsGatewaySubmit): String = IeltsExerciseJson.canonicalSha256(IeltsGatewayProtocol.encodeSubmit(submit))
    fun attemptId(requestId: String): String = "ielts-" + sha256(requestId).take(32)
    fun row(value: IeltsGenerationRecord): PreferenceRow = PreferenceRow(key(value.requestId), codec.encodeToString(value)).also { decode(it) }
    fun decode(row: PreferenceRow): IeltsGenerationRecord {
        val canonical = IeltsExerciseJson.canonicalJson(row.data)
        val value = codec.decodeFromString<IeltsGenerationRecord>(row.data)
        require(canonical == IeltsExerciseJson.canonicalJson(codec.encodeToString(value)))
        require(value.version == 1 && row.key == key(value.requestId))
        IeltsGatewayProtocol.requireSupported(value.submit, value.expectedHealth)
        require(value.submitHash == submitHash(value.submit) && value.attemptId == attemptId(value.requestId))
        require(IeltsGatewayEndpoint.parse(value.endpoint).url == value.endpoint)
        value.certificateSha256?.let { require(digest.matches(it)) }
        require(value.revision in 1..IeltsContract.MAX_TIMESTAMP && value.createdAt in 0..IeltsContract.MAX_TIMESTAMP &&
            value.updatedAt in value.createdAt..IeltsContract.MAX_TIMESTAMP)
        require(value.stage != IeltsGenerationStage.UNAVAILABLE)
        if (!value.dispatchClaimed) require(value.job == null && value.packageHash == null && !value.cancelRequested &&
            value.stage == IeltsGenerationStage.PREPARED)
        if (value.cancelRequested) require(value.dispatchClaimed)
        value.job?.let { job ->
            IeltsGatewayProtocol.validateJob(job)
            require(value.dispatchClaimed && job.requestId == value.requestId)
        }
        value.packageHash?.let { hash ->
            val job = requireNotNull(value.job)
            require(digest.matches(hash) && value.stage == IeltsGenerationStage.READY &&
                job.state == IeltsGatewayJobState.READY && job.processReaped == 1)
        }
        if (value.stage == IeltsGenerationStage.READY) require(value.packageHash != null)
        return value
    }
    fun requireTransition(previous: IeltsGenerationRecord, next: IeltsGenerationRecord) {
        require(next.submit == previous.submit && next.submitHash == previous.submitHash && next.endpoint == previous.endpoint &&
            next.expectedHealth == previous.expectedHealth && next.certificateSha256 == previous.certificateSha256 &&
            next.attemptId == previous.attemptId && next.createdAt == previous.createdAt)
        require(previous.revision < IeltsContract.MAX_TIMESTAMP && next.revision == previous.revision + 1 && next.updatedAt >= previous.updatedAt)
        require(!previous.dispatchClaimed || next.dispatchClaimed)
        require(!previous.cancelRequested || next.cancelRequested)
        previous.job?.let { old ->
            val job = requireNotNull(next.job)
            require(job.id == old.id && job.requestId == old.requestId && job.inputHash == old.inputHash && job.createdAt == old.createdAt &&
                job.revision >= old.revision)
            if (job.revision == old.revision) require(job == old)
        }
        if (previous.packageHash != null) require(next.packageHash == previous.packageHash && next.stage == IeltsGenerationStage.READY)
    }
    fun history(rows: List<PreferenceRow>): List<IeltsGenerationHistoryItem> = rows.map { row ->
        runCatching { summary(decode(row)) }.getOrElse {
            IeltsGenerationHistoryItem("unavailable:${sha256(row.key)}", null, null, null, null,
                IeltsGenerationStage.UNAVAILABLE, IeltsGenerationIssue.STORAGE_UNAVAILABLE, null, null)
        }
    }.sortedWith(compareByDescending<IeltsGenerationHistoryItem> { it.updatedAt ?: -1 }.thenBy { it.id })
    fun summary(value: IeltsGenerationRecord) = value.toHistoryItem()
}

/** Each call performs one request/reconciliation pass. It never schedules or repeats a paid POST. */
class LocalIeltsGenerationRepository(
    private val db: LexiconDatabase,
    private val access: IeltsGatewayAccess,
    private val practice: IeltsPracticeRepository = LocalIeltsPracticeRepository(db),
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : IeltsGenerationRepository {
    private val dao = db.ieltsPracticeDao()
    override suspend fun connection(): IeltsGatewayConnection = access.snapshot()
    override suspend fun pair(endpoint: String, token: String): IeltsGatewayConnection {
        access.pair(endpoint, token)
        return access.snapshot()
    }
    override suspend fun inspectPairing(document: ByteArray) = access.inspectPairing(document)
    override suspend fun pairImported(document: ByteArray): IeltsGatewayConnection {
        access.pairImported(document)
        return access.snapshot()
    }
    override suspend fun clearPairing() = access.clearPairing()
    override fun observeHistory(): Flow<List<IeltsGenerationHistoryItem>> = dao.observeGenerationRows()
        .distinctUntilChanged().map(IeltsGenerationStorage::history).distinctUntilChanged().flowOn(Dispatchers.Default)

    override suspend fun get(requestId: String): IeltsGenerationRecord? = withContext(storageDispatcher) {
        dao.generationRow(IeltsGenerationStorage.key(requestId))?.let(::decode)
    }

    override suspend fun generate(submit: IeltsGatewaySubmit): IeltsGenerationRecord {
        val frozen = withContext(Dispatchers.Default) {
            IeltsGatewayProtocol.decodeSubmit(IeltsGatewayProtocol.encodeSubmit(submit))
        }
        val existing = get(frozen.requestId)
        if (existing != null) {
            if (existing.submitHash != IeltsGenerationStorage.submitHash(frozen) || existing.submit != frozen)
                throw IeltsGenerationFailure(IeltsGenerationIssue.REQUEST_REJECTED)
            if (existing.dispatchClaimed) return reconcile(existing.requestId)
        }
        val stored = existing ?: run {
            val connection = access.snapshot()
            val endpoint = connection.endpoint
            val health = connection.health
            if (!connection.paired || endpoint == null || health == null)
                throw IeltsGenerationFailure(IeltsGenerationIssue.NOT_PAIRED)
            IeltsGatewayProtocol.requireSupported(frozen, health)
            val now = now()
            val initial = IeltsGenerationRecord(submit = frozen, submitHash = IeltsGenerationStorage.submitHash(frozen),
                endpoint = IeltsGatewayEndpoint.parse(endpoint).url, expectedHealth = health,
                certificateSha256 = connection.certificateSha256,
                attemptId = IeltsGenerationStorage.attemptId(frozen.requestId), revision = 1, createdAt = now, updatedAt = now)
            withContext(storageDispatcher) { decode(dao.createGeneration(IeltsGenerationStorage.row(initial))) }
        }
        if (stored.dispatchClaimed) return reconcile(stored.requestId)
        // Obtain a client bound to the saved origin before consuming this one-shot claim.
        val client = try { boundClient(stored) } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { return issue(stored.requestId, category(failure)) }
        val claim = withContext(storageDispatcher) { dao.claimGeneration(stored.requestId, now()) }
        if (!claim) return requireRecord(stored.requestId)
        return try {
            val job = client.submit(stored.submit)
            accept(stored.requestId, job, client)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { issue(stored.requestId, category(failure)) }
    }

    override suspend fun reconcile(requestId: String): IeltsGenerationRecord {
        val current = requireRecord(requestId)
        if (current.packageHash != null) return current
        return try {
            val client = boundClient(current)
            val knownJob = current.job
            val job = if (knownJob != null) client.get(knownJob.id) else client.byRequest(requestId)
            if (job == null) issue(requestId, if (current.dispatchClaimed) IeltsGenerationIssue.OUTCOME_UNKNOWN else IeltsGenerationIssue.NOT_FOUND)
            else accept(requestId, job, client)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { issue(requestId, category(failure)) }
    }

    override suspend fun cancel(requestId: String): IeltsGenerationRecord {
        var current = requireRecord(requestId)
        if (current.packageHash != null || current.job?.state in terminalStates) return current
        if (current.cancelRequested) return reconcile(requestId)
        return try {
            val client = boundClient(current)
            if (current.job == null) {
                val found = client.byRequest(requestId)
                    ?: return issue(requestId, IeltsGenerationIssue.OUTCOME_UNKNOWN)
                current = accept(requestId, found, client)
            }
            if (current.packageHash != null || current.job?.state in terminalStates) return current
            val claimed = update(requestId) { value ->
                if (value.cancelRequested || value.packageHash != null || value.job?.state in terminalStates) value
                else value.copy(cancelRequested = true, stage = IeltsGenerationStage.CANCELLING, issue = null)
            }
            if (!claimed.changed) return reconcile(requestId)
            accept(requestId, client.cancel(requireNotNull(claimed.record.job).id), client)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { issue(requestId, category(failure)) }
    }

    private suspend fun boundClient(value: IeltsGenerationRecord): IeltsGateway {
        fun check(connection: IeltsGatewayConnection) {
            if (!connection.paired) throw IeltsGenerationFailure(IeltsGenerationIssue.NOT_PAIRED)
            if (connection.endpoint != value.endpoint || connection.health != value.expectedHealth ||
                connection.certificateSha256 != value.certificateSha256)
                throw IeltsGenerationFailure(IeltsGenerationIssue.CONNECTION_CHANGED)
        }
        check(access.snapshot())
        val client = access.createClient()
        check(access.snapshot())
        if (client.health() != value.expectedHealth) throw IeltsGenerationFailure(IeltsGenerationIssue.CONNECTION_CHANGED)
        return client
    }

    private suspend fun accept(requestId: String, job: IeltsGatewayJob, client: IeltsGateway): IeltsGenerationRecord {
        IeltsGatewayProtocol.validateJob(job)
        require(job.requestId == requestId)
        val accepted = update(requestId) { current ->
            if (current.packageHash != null) return@update current
            val old = current.job
            if (old != null) {
                require(job.id == old.id && job.inputHash == old.inputHash && job.createdAt == old.createdAt)
                if (job.revision < old.revision) return@update current
                if (job.revision == old.revision) require(job == old)
            }
            current.copy(dispatchClaimed = true, job = job, stage = stage(job.state), issue = null)
        }.record
        if (accepted.packageHash != null || accepted.job?.state != IeltsGatewayJobState.READY) return accepted
        val boundJob = requireNotNull(accepted.job)
        // The gateway validates independently reviewed answers against this exact source and health snapshot.
        val material = client.fetchPackage(boundJob, accepted.submit, accepted.expectedHealth)
        withContext(Dispatchers.Default) {
            IeltsGatewayProtocol.validateVerifiedPackage(material, boundJob, accepted.submit, accepted.expectedHealth)
        }
        val packageHash = practice.cache(material.request, material.exercisePackage)
        require(packageHash == material.provenance.packageSha256)
        practice.begin(packageHash, accepted.attemptId, now())
        return update(requestId) { current ->
            val currentJob = requireNotNull(current.job)
            require(currentJob.id == boundJob.id && currentJob.inputHash == boundJob.inputHash)
            current.copy(packageHash = packageHash, stage = IeltsGenerationStage.READY, issue = null)
        }.record
    }

    private data class Changed(val record: IeltsGenerationRecord, val changed: Boolean)
    private suspend fun update(requestId: String, transform: (IeltsGenerationRecord) -> IeltsGenerationRecord): Changed =
        withContext(storageDispatcher) {
            val previous = dao.generationRow(IeltsGenerationStorage.key(requestId))
                ?: throw IeltsGenerationFailure(IeltsGenerationIssue.STORAGE_UNAVAILABLE)
            val current = decode(previous)
            val transformed = transform(current)
            if (transformed == current) return@withContext Changed(current, false)
            if (current.revision == IeltsContract.MAX_TIMESTAMP) throw IeltsGenerationFailure(IeltsGenerationIssue.STORAGE_UNAVAILABLE)
            val next = transformed.copy(revision = current.revision + 1, updatedAt = maxOf(now(), current.updatedAt))
            val replacement = IeltsGenerationStorage.row(next)
            val changed = dao.changeGeneration(previous, replacement)
            Changed(decode(requireNotNull(dao.generationRow(previous.key))), changed)
        }

    private suspend fun issue(requestId: String, issue: IeltsGenerationIssue): IeltsGenerationRecord = update(requestId) {
        if (it.packageHash != null) it else it.copy(issue = issue)
    }.record
    private suspend fun requireRecord(id: String): IeltsGenerationRecord = get(id)
        ?: throw IeltsGenerationFailure(IeltsGenerationIssue.STORAGE_UNAVAILABLE)
    private fun decode(row: PreferenceRow): IeltsGenerationRecord = try { IeltsGenerationStorage.decode(row) }
        catch (_: Exception) { throw IeltsGenerationFailure(IeltsGenerationIssue.STORAGE_UNAVAILABLE) }
    private fun now(): Long = clock().also { require(it in 0..IeltsContract.MAX_TIMESTAMP) }
    private fun category(failure: Exception): IeltsGenerationIssue = when (failure) {
        is IeltsGenerationFailure -> failure.issue
        is IeltsGatewaySubmissionUncertain -> IeltsGenerationIssue.OUTCOME_UNKNOWN
        is IeltsGatewayFailure -> when (failure.code) {
            IeltsGatewayFailureCode.NOT_PAIRED -> IeltsGenerationIssue.NOT_PAIRED
            IeltsGatewayFailureCode.CONFIGURATION_CHANGED -> IeltsGenerationIssue.CONNECTION_CHANGED
            IeltsGatewayFailureCode.UNAUTHORIZED -> IeltsGenerationIssue.UNAUTHORIZED
            IeltsGatewayFailureCode.TRANSPORT, IeltsGatewayFailureCode.TLS_REJECTED -> IeltsGenerationIssue.OUTCOME_UNKNOWN
            IeltsGatewayFailureCode.STORAGE_UNAVAILABLE -> IeltsGenerationIssue.STORAGE_UNAVAILABLE
            IeltsGatewayFailureCode.NOT_FOUND -> IeltsGenerationIssue.NOT_FOUND
            IeltsGatewayFailureCode.CONFLICT, IeltsGatewayFailureCode.SUBMIT_REJECTED,
            IeltsGatewayFailureCode.HTTPS_REQUIRED, IeltsGatewayFailureCode.TOKEN_INVALID,
            IeltsGatewayFailureCode.PAIRING_DOCUMENT_INVALID, IeltsGatewayFailureCode.CERTIFICATE_INVALID -> IeltsGenerationIssue.REQUEST_REJECTED
            IeltsGatewayFailureCode.INVALID_RESPONSE -> IeltsGenerationIssue.INVALID_RESPONSE
            IeltsGatewayFailureCode.PACKAGE_UNAVAILABLE, IeltsGatewayFailureCode.REMOTE_FAILURE -> IeltsGenerationIssue.REMOTE_FAILURE
        }
        is IllegalArgumentException, is IllegalStateException -> IeltsGenerationIssue.INVALID_RESPONSE
        else -> IeltsGenerationIssue.STORAGE_UNAVAILABLE
    }
    private fun stage(value: IeltsGatewayJobState): IeltsGenerationStage = when (value) {
        IeltsGatewayJobState.QUEUED -> IeltsGenerationStage.QUEUED
        IeltsGatewayJobState.GENERATING -> IeltsGenerationStage.GENERATING
        IeltsGatewayJobState.REVIEWING -> IeltsGenerationStage.REVIEWING
        IeltsGatewayJobState.READY -> IeltsGenerationStage.RECONCILING
        IeltsGatewayJobState.CANCELLING -> IeltsGenerationStage.CANCELLING
        IeltsGatewayJobState.CANCELLED -> IeltsGenerationStage.CANCELLED
        IeltsGatewayJobState.FAILED -> IeltsGenerationStage.FAILED
        IeltsGatewayJobState.INTERRUPTED -> IeltsGenerationStage.INTERRUPTED
    }
    private val terminalStates = setOf(IeltsGatewayJobState.READY, IeltsGatewayJobState.CANCELLED,
        IeltsGatewayJobState.FAILED, IeltsGatewayJobState.INTERRUPTED)
}
