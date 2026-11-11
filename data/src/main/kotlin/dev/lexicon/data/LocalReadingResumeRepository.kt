package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Ordinary original-text continuation only. This path has no AI, network, or session-event writer. */
class LocalReadingResumeRepository(
    private val db: LexiconDatabase,
    private val validationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : ReadingResumeRepository {
    private val dao = db.readingDao()
    private val mutations = Mutex()
    override val library: Flow<ReadingResumeLibrary> = dao.observeReadingResumes().map { rows ->
        val decoded = rows.filter { it.key.startsWith(ReadingResumeStorage.RECORD_PREFIX) }
            .map { row -> runCatching { ReadingResumeStorage.record(row) } }
        val generationErrors = rows.filter { it.key.startsWith(ReadingResumeStorage.GENERATION_PREFIX) }
            .count { runCatching { ReadingResumeStorage.generation(it) }.isFailure }
        ReadingResumeLibrary(
            records = decoded.mapNotNull { it.getOrNull() }
                .sortedWith(compareByDescending<ReadingResumeRecord> { it.updatedAt }.thenBy { it.articleId }),
            errors = decoded.count { it.isFailure } + generationErrors,
        )
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override suspend fun begin(articleId: String, bodyHash: String): ReadingResumeLease = mutations.withLock {
        withContext(storageDispatcher) {
            ReadingResumes.validateIdentity(articleId, bodyHash)
            dao.beginReadingResume(articleId, bodyHash)
        }
    }

    override suspend fun checkpoint(lease: ReadingResumeLease, sequence: Long, body: ArticleBody, paragraphIndex: Int,
        controls: ReadingResumeControls, completed: Boolean): Boolean = mutations.withLock {
        val next = withContext(validationDispatcher) {
            ReadingResumes.create(lease, sequence, body, paragraphIndex, controls, clock(), completed)
        }
        withContext(storageDispatcher) { dao.checkpointReadingResume(lease, next) }
    }

    override suspend fun clear(record: ReadingResumeRecord): Boolean = mutations.withLock {
        withContext(storageDispatcher) {
            ReadingResumes.validate(record)
            dao.clearReadingResume(record)
        }
    }
}
