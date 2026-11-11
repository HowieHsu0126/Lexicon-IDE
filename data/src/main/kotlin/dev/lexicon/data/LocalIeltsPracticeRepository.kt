package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** No network, model, old learning-table writer, or ArticleBody dependency is present here. */
class LocalIeltsPracticeRepository(
    private val db: LexiconDatabase,
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : IeltsPracticeRepository {
    private val dao = db.ieltsPracticeDao()

    override suspend fun cache(request: IeltsExerciseRequest, exercise: IeltsExercisePackage): String =
        withContext(storageDispatcher) { dao.cache(request, exercise) }

    override suspend fun begin(packageHash: String, attemptId: String, now: Long): IeltsSavedPractice =
        withContext(storageDispatcher) { dao.begin(packageHash, attemptId, now) }

    override suspend fun get(attemptId: String): IeltsSavedPractice? =
        withContext(storageDispatcher) { dao.get(attemptId) }

    override fun observeHistory(): Flow<List<IeltsPracticeHistoryItem>> = dao.observeRows()
        .distinctUntilChanged().map(IeltsPracticeStorage::history).distinctUntilChanged().flowOn(Dispatchers.Default)

    override suspend fun saveDraft(id: String, expectedRevision: Long, responses: List<IeltsResponse>,
        writingText: String, now: Long): IeltsSavedPractice {
        val snapshot = responses.toList()
        return withContext(storageDispatcher) { dao.saveDraft(id, expectedRevision, snapshot, writingText, now) }
    }

    override suspend fun saveWritingDrafts(id: String, expectedRevision: Long, responses: List<IeltsResponse>,
        writingText: String, writingTask1Text: String?, now: Long): IeltsSavedPractice {
        val snapshot = responses.toList()
        return withContext(storageDispatcher) { dao.saveWritingDrafts(id, expectedRevision, snapshot, writingText, writingTask1Text, now) }
    }

    override suspend fun submit(id: String, expectedRevision: Long, now: Long): IeltsSavedPractice =
        withContext(storageDispatcher) { dao.submit(id, expectedRevision, now) }
}
