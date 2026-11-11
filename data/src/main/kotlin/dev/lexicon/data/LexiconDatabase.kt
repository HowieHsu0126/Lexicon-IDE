package dev.lexicon.data

import androidx.room3.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sources") data class SourceRow(@PrimaryKey val id: String, val data: String)
@Entity(tableName = "articles", indices = [Index(value = ["url"], unique = true), Index("publishedAt")])
data class ArticleRow(@PrimaryKey val id: String, val url: String, val publishedAt: Long, val data: String)
@Entity(tableName = "article_sources", primaryKeys = ["sourceId", "guid"], indices = [Index("articleId")])
data class ArticleSourceRow(val sourceId: String, val guid: String, val articleId: String)
@Entity(tableName = "article_bodies", indices = [Index("expiresAt")])
data class BodyRow(@PrimaryKey val articleId: String, val expiresAt: Long, val data: String)
@Entity(tableName = "captures", indices = [Index("createdAt")])
data class CaptureRow(@PrimaryKey val id: String, val createdAt: Long, val data: String)
@Entity(tableName = "cards", indices = [Index(value = ["captureId"], unique = true), Index("dueAt")])
data class CardRow(@PrimaryKey val id: String, val captureId: String, val dueAt: Long, val reps: Int, val updatedAt: Long, val data: String)
@Entity(tableName = "review_logs", indices = [Index("cardId")])
data class ReviewRow(@PrimaryKey val id: String, val cardId: String, val reviewedAt: Long, val data: String)
@Entity(tableName = "ai_jobs", indices = [Index("status")])
data class JobRow(@PrimaryKey val id: String, val status: String, val updatedAt: Long, val data: String)
@Entity(tableName = "local_events") data class EventRow(@PrimaryKey val id: String, val createdAt: Long, val data: String)
@Entity(tableName = "preferences") data class PreferenceRow(@PrimaryKey val key: String, val data: String)
@Entity(tableName = "topic_cache") data class TopicCacheRow(@PrimaryKey val titleHash: String, val topic: String?, val subtopic: String?)

@Dao abstract class LexiconDao {
    @Query("SELECT * FROM sources ORDER BY id") abstract fun observeSources(): Flow<List<SourceRow>>
    @Query("SELECT * FROM sources ORDER BY id") abstract suspend fun allSources(): List<SourceRow>
    @Query("SELECT * FROM sources WHERE id=:id") abstract suspend fun source(id: String): SourceRow?
    @Upsert abstract suspend fun putSource(row: SourceRow)
    @Query("SELECT * FROM articles ORDER BY publishedAt DESC") abstract fun observeArticles(): Flow<List<ArticleRow>>
    @Query("SELECT * FROM articles ORDER BY publishedAt DESC") abstract suspend fun allArticles(): List<ArticleRow>
    @Query("SELECT * FROM articles WHERE id=:id") abstract suspend fun article(id: String): ArticleRow?
    @Query("SELECT * FROM articles WHERE url=:url") abstract suspend fun articleByUrl(url: String): ArticleRow?
    @Query("SELECT a.* FROM articles a JOIN article_sources s ON a.id=s.articleId WHERE s.sourceId=:sourceId AND s.guid=:guid LIMIT 1")
    abstract suspend fun articleByGuid(sourceId: String, guid: String): ArticleRow?
    @Upsert abstract suspend fun putArticle(row: ArticleRow)
    @Upsert abstract suspend fun putArticleSource(row: ArticleSourceRow)
    @Transaction open suspend fun putArticleAndSource(article: ArticleRow, source: ArticleSourceRow) {
        putArticle(article); putArticleSource(source)
    }
    @Query("SELECT * FROM article_bodies WHERE articleId=:id AND expiresAt>:now") abstract suspend fun body(id: String, now: Long): BodyRow?
    @Upsert abstract suspend fun putBody(row: BodyRow)
    @Query("DELETE FROM article_bodies WHERE expiresAt<=:now") abstract suspend fun expireBodies(now: Long)
    @Query("SELECT * FROM captures ORDER BY createdAt DESC") abstract fun observeCaptures(): Flow<List<CaptureRow>>
    @Query("SELECT * FROM captures WHERE id=:id") abstract suspend fun capture(id: String): CaptureRow?
    @Upsert abstract suspend fun putCapture(row: CaptureRow)
    @Query("SELECT * FROM cards ORDER BY updatedAt DESC") abstract fun observeCards(): Flow<List<CardRow>>
    @Query("SELECT * FROM cards ORDER BY updatedAt DESC") abstract suspend fun allCards(): List<CardRow>
    @Query("SELECT * FROM cards WHERE id=:id") abstract suspend fun card(id: String): CardRow?
    @Query("SELECT * FROM cards WHERE captureId=:id") abstract suspend fun cardByCapture(id: String): CardRow?
    @Upsert abstract suspend fun putCard(row: CardRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertCard(row: CardRow): Long
    @Query("UPDATE cards SET dueAt=:dueAt, reps=:reps, updatedAt=:updatedAt, data=:data WHERE id=:id AND reps=:expectedReps")
    abstract suspend fun compareAndSetReview(id: String, expectedReps: Int, dueAt: Long, reps: Int, updatedAt: Long, data: String): Int
    @Insert abstract suspend fun putReview(row: ReviewRow)
    @Transaction open suspend fun applyReview(row: CardRow, expectedReps: Int, log: ReviewRow): Boolean {
        if (compareAndSetReview(row.id, expectedReps, row.dueAt, row.reps, row.updatedAt, row.data) != 1) return false
        putReview(log)
        return true
    }
    @Query("SELECT * FROM ai_jobs ORDER BY updatedAt DESC LIMIT 100") abstract fun observeJobs(): Flow<List<JobRow>>
    @Query("SELECT * FROM ai_jobs WHERE status='running'") abstract suspend fun runningJobs(): List<JobRow>
    @Upsert abstract suspend fun putJob(row: JobRow)
    @Insert abstract suspend fun putEvent(row: EventRow)
    @Transaction open suspend fun confirmExport(cards: List<CardRow>, event: EventRow) {
        cards.forEach { putCard(it) }
        putEvent(event)
    }
    @Query("SELECT * FROM local_events ORDER BY createdAt") abstract suspend fun allEvents(): List<EventRow>
    @Query("SELECT * FROM preferences WHERE `key`=:key") abstract fun observePreference(key: String): Flow<PreferenceRow?>
    @Query("SELECT * FROM preferences WHERE `key`=:key") abstract suspend fun preference(key: String): PreferenceRow?
    @Upsert abstract suspend fun putPreference(row: PreferenceRow)
    @Query("SELECT * FROM topic_cache WHERE titleHash=:hash") abstract suspend fun cachedTopic(hash: String): TopicCacheRow?
    @Upsert abstract suspend fun putTopic(row: TopicCacheRow)
}

@Database(
    entities = [SourceRow::class, ArticleRow::class, ArticleSourceRow::class, BodyRow::class,
        CaptureRow::class, CardRow::class, ReviewRow::class, JobRow::class, EventRow::class,
        PreferenceRow::class, TopicCacheRow::class],
    version = 1, exportSchema = true,
)
abstract class LexiconDatabase : RoomDatabase() {
    abstract fun ieltsPracticeDao(): IeltsPracticeDao
    abstract fun reviewHistoryDao(): ReviewHistoryDao
    abstract fun captureInboxDao(): CaptureInboxDao
    abstract fun cardLifecycleDao(): CardLifecycleDao
    abstract fun captureAnalysisDao(): CaptureAnalysisDao
    abstract fun vocabularyDao(): VocabularyDao
    abstract fun dao(): LexiconDao
    abstract fun readingDao(): ReadingDao
    abstract fun readingBaselineDao(): ReadingBaselineDao
    abstract fun personalizationRecoveryDao(): PersonalizationRecoveryDao
}
