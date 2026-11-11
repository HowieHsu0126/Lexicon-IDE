package dev.lexicon.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.io.File

@Serializable enum class StudyMode { INTENSIVE, SKIM }
@Serializable enum class ReaderFont { MONO, SERIF, SYSTEM, OPEN_DYSLEXIC }
@Serializable enum class ReadingComfort { DEFAULT, LOW_STRESS, HIGH_CONTRAST, NIGHT_PAPER }
@Serializable data class ReaderPreferences(
    val font: ReaderFont = ReaderFont.SERIF, val sizeSp: Float = 18f, val lineHeight: Float = 1.8f,
    val letterSpacingSp: Float = 0f, val comfort: ReadingComfort = ReadingComfort.LOW_STRESS,
    val background: String = "default", val automaticStudy: Boolean = true,
    /** User-chosen planning pace. It is never interpreted as measured reading ability. */
    val skimPlanningWpm: Int = 160,
) {
    fun bounded() = copy(sizeSp = sizeSp.coerceIn(14f, 32f), lineHeight = lineHeight.coerceIn(1.2f, 3f), letterSpacingSp = letterSpacingSp.coerceIn(0f, 4f),
        skimPlanningWpm = skimPlanningWpm.coerceIn(80, 400))
}
@Serializable data class MindMapNode(val id: String, val parentId: String?, val label: String, val paragraph: Int? = null)
@Serializable data class StudyHighlight(val id: String, val quote: String, val start: Int, val end: Int,
    val kind: String, val explanation: String, val exam: String = "both")
@Serializable data class StudyParagraph(val index: Int, val role: String, val summary: String)
/** A complete original sentence, resolved by the client against the frozen UTF-16 snapshot. */
@Serializable data class ReadingEvidenceRange(
    val sentenceId: String, val paragraphIndex: Int, val start: Int, val endExclusive: Int,
)
@Serializable data class ReadingQuestion(val id: String, val question: String, val answer: String, val paragraphs: List<Int>,
    /** Empty in earlier studies: paragraph links alone are not precise answer evidence. */
    val evidence: List<ReadingEvidenceRange> = emptyList())
@Serializable data class CultureBackground(val term: String, val meaning: String, val whyHere: String, val paragraph: Int,
    val uncertainty: String = "AI 背景提示，需结合原文判断")
@Serializable data class ArticleStudy(val articleId: String, val bodyHash: String, val summary: String,
    val cefrEstimate: String, val nodes: List<MindMapNode>, val highlights: List<StudyHighlight>,
    val paragraphs: List<StudyParagraph>, val questions: List<ReadingQuestion>, val backgrounds: List<CultureBackground>,
    val model: String, val createdAt: Long, val schemaVersion: Int = 1, val scope: StudyScope? = null)
@Serializable data class StudyRequest(val kind: String, val text: String, val context: String = "", val targetLevel: String = "",
    val title: String = "", val source: String = "", val aiAllowed: Boolean = true)
/** Retrieved evidence is client-owned. The model may cite IDs but cannot supply source URLs. */
@Serializable data class BackgroundSource(val id: String, val title: String, val url: String, val excerpt: String,
    val retrievedAt: Long, val provider: String, val revisionId: String? = null, val license: String = "CC BY-SA 4.0",
    val contentHash: String? = null)
@Serializable data class StudyAssist(val kind: String, val title: String, val text: String, val model: String,
    val createdAt: Long, val evidence: String = "", val score: Double? = null,
    val sources: List<BackgroundSource> = emptyList(), val retrievalStatus: String = "not_requested",
    val citedSourceIds: List<String> = emptyList())
@Serializable data class ReadingNote(val id: String, val articleId: String?, val bodyHash: String?, val quote: String,
    val comment: String, val tags: List<String>, val createdAt: Long, val source: SourceRef = SourceRef(),
    val start: Int? = null, val end: Int? = null, val mindMap: Boolean = false, val origin: ContextOrigin = ContextOrigin.ORIGINAL)
@Serializable data class ReadingSession(val id: String, val articleId: String, val mode: StudyMode, val topic: String?,
    val startedAt: Long, val finishedAt: Long, val activeMillis: Long, val wordsRead: Int,
    val paragraphsRead: Int, val totalParagraphs: Int, val assistanceCount: Int, val completed: Boolean,
    val level: String = "original")
@Serializable data class ComprehensionCheck(val id: String, val articleId: String, val topic: String?,
    val questionId: String, val answer: String, val score: Double, val evaluatedBy: String, val createdAt: Long)
@Serializable data class DailyEdition(val day: String, val articleIds: List<String>, val publisherKeys: List<String>)
@Serializable data class ReadingLibrary(val notes: List<ReadingNote> = emptyList(), val sessions: List<ReadingSession> = emptyList(),
    val checks: List<ComprehensionCheck> = emptyList(), val editions: List<DailyEdition> = emptyList(), val reviews: List<StudyAssist> = emptyList(),
    val backgroundRecords: List<BackgroundRecord> = emptyList(), val backgroundRecordErrors: Int = 0)

interface ReadingAi {
    suspend fun analyze(article: Article, body: ArticleBody): ArticleStudy
    suspend fun analyzeRange(article: Article, body: ArticleBody, scope: StudyScope): ArticleStudy =
        throw UnsupportedOperationException("Selected paragraph analysis is unavailable")
    suspend fun assist(request: StudyRequest): StudyAssist
    /** External snippets only: implementations must not call a generative model on this path. */
    suspend fun retrieveBackground(request: StudyRequest): StudyAssist = throw UnsupportedOperationException("Retrieval-only background is unavailable")
    suspend fun review(notes: List<ReadingNote>): StudyAssist
}

/** Feature boundary shared by both reading modes. Implementations own persistence and network policy. */
interface ReadingRepository {
    val backgrounds: BackgroundRepository? get() = null
    val resumes: ReadingResumeRepository? get() = null
    val library: Flow<ReadingLibrary>
    suspend fun dailyEdition(articles: List<Article>, sources: List<Source>, now: Long, zoneId: String): DailyEdition
    suspend fun cachedStudy(articleId: String, bodyHash: String): ArticleStudy?
    suspend fun cachedStudies(articleId: String, bodyHash: String): List<ArticleStudy> = listOfNotNull(cachedStudy(articleId, bodyHash))
    suspend fun studyRange(article: Article, body: ArticleBody, scope: StudyScope, aiAllowed: Boolean, explicitRetry: Boolean = false): ArticleStudy =
        throw UnsupportedOperationException("Selected paragraph analysis is unavailable")
    suspend fun study(article: Article, body: ArticleBody, aiAllowed: Boolean): ArticleStudy
    /** Explicit user retry; automatic reading must call study instead. */
    suspend fun retryStudy(article: Article, body: ArticleBody, aiAllowed: Boolean): ArticleStudy = study(article, body, aiAllowed)
    suspend fun assist(request: StudyRequest): StudyAssist
    suspend fun cachedAssist(request: StudyRequest): StudyAssist? = null
    suspend fun saveNote(note: ReadingNote)
    suspend fun deleteNote(id: String)
    suspend fun recordSession(session: ReadingSession)
    suspend fun saveCheck(check: ComprehensionCheck)
    suspend fun expressionReview(): StudyAssist
    suspend fun exportKnowledge(destination: File): File
}
