package dev.lexicon.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable enum class IeltsGenerationStage {
    PREPARED, RECONCILING, QUEUED, GENERATING, REVIEWING, READY, CANCELLING, CANCELLED, FAILED, INTERRUPTED, UNAVAILABLE,
}
@Serializable enum class IeltsGenerationIssue {
    OUTCOME_UNKNOWN, NOT_FOUND, CONNECTION_CHANGED, NOT_PAIRED, UNAUTHORIZED, STORAGE_UNAVAILABLE,
    INVALID_RESPONSE, REMOTE_FAILURE, REQUEST_REJECTED,
}

/** A durable request, bound to one paired origin and one corpus/runtime health snapshot. No token. */
@Serializable data class IeltsGenerationRecord(
    val version: Int = 1,
    val submit: IeltsGatewaySubmit,
    val submitHash: String,
    val endpoint: String,
    val expectedHealth: IeltsGatewayHealth,
    val attemptId: String,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val dispatchClaimed: Boolean = false,
    val cancelRequested: Boolean = false,
    val job: IeltsGatewayJob? = null,
    val packageHash: String? = null,
    val stage: IeltsGenerationStage = IeltsGenerationStage.PREPARED,
    val issue: IeltsGenerationIssue? = null,
    val certificateSha256: String? = null,
) {
    val requestId: String get() = submit.requestId
}

/** The library contains neither original prose nor student answers nor gateway error text. */
data class IeltsGenerationHistoryItem(
    val id: String,
    val requestId: String?,
    val articleId: String?,
    val endpoint: String?,
    val attemptId: String?,
    val stage: IeltsGenerationStage,
    val issue: IeltsGenerationIssue?,
    val createdAt: Long?,
    val updatedAt: Long?,
)

/** Project the same safe status for the selected task and the saved task list. */
fun IeltsGenerationRecord.toHistoryItem(): IeltsGenerationHistoryItem {
    val visibleIssue = if (packageHash == null && job?.error == "provider_completion_unknown")
        IeltsGenerationIssue.OUTCOME_UNKNOWN else issue
    return IeltsGenerationHistoryItem(requestId, requestId, submit.articleId, endpoint,
        attemptId.takeIf { packageHash != null }, stage, visibleIssue, createdAt, updatedAt)
}

interface IeltsGenerationRepository {
    suspend fun connection(): IeltsGatewayConnection
    suspend fun pair(endpoint: String, token: String): IeltsGatewayConnection
    suspend fun inspectPairing(document: ByteArray): IeltsPairingPreview =
        throw IeltsGatewayFailure(IeltsGatewayFailureCode.PAIRING_DOCUMENT_INVALID)
    suspend fun pairImported(document: ByteArray): IeltsGatewayConnection =
        throw IeltsGatewayFailure(IeltsGatewayFailureCode.PAIRING_DOCUMENT_INVALID)
    suspend fun clearPairing()
    fun observeHistory(): Flow<List<IeltsGenerationHistoryItem>>
    suspend fun get(requestId: String): IeltsGenerationRecord?

    /** Save immutable input, claim its single POST, then send. Reusing an ID cannot change its input. */
    suspend fun generate(submit: IeltsGatewaySubmit): IeltsGenerationRecord

    /** One bounded lookup/fetch pass. Never POSTs a generation request, including after a restart. */
    suspend fun reconcile(requestId: String): IeltsGenerationRecord

    /** Cancel the bound job once; an unknown cancellation is reconciled by reads thereafter. */
    suspend fun cancel(requestId: String): IeltsGenerationRecord
}

class IeltsGenerationFailure(val issue: IeltsGenerationIssue) : Exception(issue.name)
