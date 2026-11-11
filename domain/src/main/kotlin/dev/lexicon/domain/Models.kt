package dev.lexicon.domain

import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.Flow

@Serializable enum class ReadingMode { NATIVE, EXTERNAL }
@Serializable enum class ContextOrigin { ORIGINAL, USER_SUPPLIED, AI_GENERATED, MISSING }
@Serializable enum class CardPhase { NEW, LEARNING, REVIEW, RELEARNING }
@Serializable enum class Rating { AGAIN, HARD, GOOD, EASY }
@Serializable enum class ThemeMode { SYSTEM, LIGHT, DARK }
object CaptureLimits { const val SELECTED_TEXT = 12_000; const val SENTENCE = 6_000; const val TARGET = 200 }

@Serializable data class Source(
    val id: String, val name: String, val feedUrl: String,
    val readingMode: ReadingMode = ReadingMode.EXTERNAL, val enabled: Boolean = false,
    val aiAllowed: Boolean = false, val license: String = "", val licenseUrl: String = "",
    val defaultTopic: String? = null, val etag: String? = null, val lastModified: String? = null,
    val lastFetchedAt: Long? = null, val lastError: String? = null,
)
@Serializable data class Article(
    val id: String, val guid: String, val sourceIds: List<String>, val title: String,
    val url: String, val summary: String, val author: String = "", val publishedAt: Long? = null,
    val topic: String? = null, val subtopic: String? = null, val topicOrigin: String? = null,
    val read: Boolean = false, val captured: Boolean = false, val cachedAt: Long = 0,
    val readingMode: ReadingMode = ReadingMode.EXTERNAL, val sourceName: String = "",
    val license: String = "", val wordCount: Int? = null,
)
@Serializable data class ArticleBody(
    val articleId: String, val text: String, val contentHash: String,
    val cachedAt: Long, val expiresAt: Long, val license: String, val author: String = "",
)
@Serializable data class SourceRef(
    val title: String? = null, val source: String? = null, val url: String? = null,
    val publishedAt: Long? = null,
) { fun display(): String = listOfNotNull(title, source).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "未关联来源" } }
@Serializable data class Capture(
    val id: String, val articleId: String? = null, val target: String, val selectedText: String,
    val sentence: String = "", val contextOrigin: ContextOrigin = ContextOrigin.MISSING,
    val sourceRef: SourceRef = SourceRef(), val selectionStart: Int? = null, val selectionEnd: Int? = null,
    val bodyHash: String? = null, val createdAt: Long, val updatedAt: Long = createdAt,
    val aiAllowed: Boolean = true,
)
@Serializable data class Collocation(val phrase: String, val example: String)
@Serializable data class VocabularyContent(
    val headword: String, val contextDefinition: String, val collocations: List<Collocation>,
    val paraphrase: String, val registerTag: String, val examRelevance: String,
)
@Serializable data class FsrsState(
    val phase: CardPhase = CardPhase.NEW, val stability: Double? = null,
    val difficulty: Double? = null, val dueAt: Long = 0, val lastReviewAt: Long? = null,
    val step: Int? = null, val reps: Int = 0, val lapses: Int = 0,
)
@Serializable data class Card(
    val id: String, val captureId: String, val content: VocabularyContent,
    val sourceSentence: String, val sourceRef: SourceRef, val topic: String? = null,
    val state: FsrsState = FsrsState(), val createdAt: Long, val updatedAt: Long = createdAt,
    val inaccurate: Boolean = false, val noteGuid: String = id, val ankiExportedAt: Long? = null,
    val contextOrigin: ContextOrigin = ContextOrigin.ORIGINAL,
    val reviewPaused: Boolean = false,
)
@Serializable data class ReviewLog(
    val id: String, val cardId: String, val reviewedAt: Long, val rating: Rating,
    val before: FsrsState, val after: FsrsState, val schedulerVersion: String = "FSRS-6",
    val parameters: String = "retention=0.9;learning=60,600;relearning=600;fuzz=false",
)
@Serializable data class DictionaryEntry(
    val word: String, val translation: String, val definition: String = "", val phonetic: String = "",
    val tags: String = "", val frequency: Int? = null,
)
@Serializable data class DictionaryStatus(
    val ready: Boolean = false, val name: String = "本地词典",
    val entryCount: Long = 0, val message: String = "请选择或导入本地词典",
    val id: String = "", val format: String = "", val language: String = "", val origin: String = "",
)
@Serializable data class InstalledDictionary(
    val id: String, val name: String, val format: String, val language: String,
    val entryCount: Long, val selected: Boolean, val origin: String, val fileName: String,
)
@Serializable data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM, val serif: Boolean = false, val warmPaper: Boolean = false,
    val focusTimer: Boolean = false, val titleClassification: Boolean = true,
    val reader: ReaderPreferences = ReaderPreferences(),
)
@Serializable data class AiJob(
    val id: String, val kind: String, val entityId: String, val status: String,
    val createdAt: Long, val updatedAt: Long, val errorCode: String? = null,
)
@Serializable data class LocalEvent(val id: String, val type: String, val createdAt: Long, val value: Int = 1)
data class SyncResult(val sourceCount: Int, val articleCount: Int, val failures: List<String>)
data class TopicInput(val id: String, val title: String, val sourceLabel: String)
data class TopicResult(val id: String, val topic: String?, val subtopic: String?)
data class AnalysisResult(val content: VocabularyContent, val model: String, val inputSignature: String? = null)

object Topics {
    val children = linkedMapOf(
        "Politics" to listOf("Elections", "International Relations", "Public Policy"),
        "Business" to listOf("Economics", "Finance", "Work"),
        "Science & Technology" to listOf("AI", "Biotech", "Space", "Climate Tech"),
        "Culture" to listOf("Arts", "History", "Language"),
        "Health" to listOf("Medicine", "Public Health", "Psychology"),
        "Environment" to listOf("Climate", "Conservation", "Energy"),
        "Education" to listOf("Higher Education", "Learning", "Schools"),
        "Law & Society" to listOf("Justice", "Ethics", "Inequality"),
    )
}

interface FeedRepository {
    val articles: Flow<List<Article>>
    val sources: Flow<List<Source>>
    suspend fun sync(): SyncResult
    suspend fun setSourceEnabled(id: String, enabled: Boolean)
    suspend fun openArticle(id: String): ArticleBody?
    suspend fun markArticleRead(id: String)
    suspend fun classifyPending()
}
interface CaptureRepository {
    val captures: Flow<List<Capture>>
    suspend fun saveCapture(capture: Capture)
    suspend fun lookup(text: String): List<DictionaryEntry>
}
interface LearningAiService {
    suspend fun analyze(capture: Capture, deep: Boolean = false, dictionary: List<DictionaryEntry> = emptyList()): AnalysisResult
    suspend fun classify(inputs: List<TopicInput>): List<TopicResult>
}
interface Scheduler {
    fun review(state: FsrsState, rating: Rating, now: Long): FsrsState
    fun retrievability(state: FsrsState, now: Long): Double?
}
interface AnkiExporter { suspend fun export(cards: List<Card>, destination: java.io.File): java.io.File }
interface AppRepository : FeedRepository, CaptureRepository {
    val reviewHistory: ReviewHistoryRepository? get() = null
    val captureInbox: CaptureInboxRepository? get() = null
    val vocabulary: VocabularyLearningRepository? get() = null
    val reading: ReadingRepository? get() = null
    val speedReading: SpeedReadingRepository? get() = null
    val baseline: ReadingBaselineRepository? get() = null
    val personalization: PersonalizationRepository? get() = null
    val ieltsPractice: IeltsPracticeRepository? get() = null
    val ieltsGeneration: IeltsGenerationRepository? get() = null
    /** Fetches native text without recording an article open or starting reading assistance. */
    suspend fun prepareBaselineBody(id: String): ArticleBody? =
        throw UnsupportedOperationException("Unassisted reading preparation is unavailable")
    val cards: Flow<List<Card>>
    val settings: Flow<AppSettings>
    val status: Flow<String>
    val jobs: Flow<List<AiJob>>
    suspend fun initialize()
    suspend fun updateSettings(settings: AppSettings)
    suspend fun hasApiKey(): Boolean
    suspend fun setApiKey(key: String)
    suspend fun clearApiKey()
    suspend fun dictionaryStatus(): DictionaryStatus
    suspend fun importDictionary(file: java.io.File): DictionaryStatus
    suspend fun installedDictionaries(): List<InstalledDictionary> = emptyList()
    suspend fun selectDictionary(id: String): DictionaryStatus = dictionaryStatus()
    /** Reads local results only. Opening a panel never starts AI or writes a capture. */
    suspend fun availableCaptureAnalysis(capture: Capture): CaptureAnalysisAvailability = CaptureAnalysisAvailability.Missing
    suspend fun saveAvailableCaptureAnalysis(capture: Capture, expectedAnalysisId: String): Card =
        throw UnsupportedOperationException("Saved analysis is unavailable")
    suspend fun analyzeCapture(capture: Capture, deep: Boolean = false): AnalysisResult
    suspend fun saveCard(capture: Capture, analysis: AnalysisResult): Card
    suspend fun updateCard(card: Card)
    suspend fun setCardReviewPaused(cardId: String, paused: Boolean): Card? =
        throw UnsupportedOperationException("Card review control is unavailable")
    suspend fun reviewCard(cardId: String, rating: Rating, expectedReps: Int, now: Long = System.currentTimeMillis()): ReviewOutcome
    suspend fun exportAnki(destination: java.io.File): java.io.File
    /** Called only after the user's destination stream has been successfully closed. */
    suspend fun confirmAnkiExport(exportFile: java.io.File)
    suspend fun exportEvents(destination: java.io.File): java.io.File
    suspend fun recordEvent(type: String, value: Int = 1)
}
