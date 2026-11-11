package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Durable local knowledge, daily selection and AI work. UI never performs SQL or HTTP itself. */
class LocalReadingRepository(
    private val db: LexiconDatabase,
    private val ai: ReadingAi,
    private val hasKey: suspend () -> Boolean,
    private val sourceAllowed: suspend (Article) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : ReadingRepository {
    override val resumes: ReadingResumeRepository = LocalReadingResumeRepository(db, clock = clock)
    override val backgrounds: BackgroundRepository? = (ai as? BackgroundResearchAi)?.let {
        LocalBackgroundRepository(db, it, hasKey, sourceAllowed, clock)
    }
    private val dao = db.readingDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val initLock = Mutex()
    private val aiLock = Mutex()
    private var initialized = false
    override val library: Flow<ReadingLibrary> = dao.observeLibrary().map(::decodeLibrary).flowOn(Dispatchers.Default)

    suspend fun initialize() = initLock.withLock {
        if (!initialized) {
            dao.runningJobs().forEach { row ->
                val job = json.decodeFromString<AiJob>(row.data)
                dao.putJob(job.copy(status = "interrupted", updatedAt = clock(), errorCode = "process_interrupted").row())
            }
            initialized = true
        }
    }

    override suspend fun dailyEdition(articles: List<Article>, sources: List<Source>, now: Long, zoneId: String): DailyEdition = withContext(Dispatchers.IO) {
        initialize()
        val day = Instant.ofEpochMilli(now).atZone(ZoneId.of(zoneId)).toLocalDate().toString()
        dao.dailyEdition("reading-library:edition:$day", articles, sources, now, zoneId)
    }

    override suspend fun cachedStudy(articleId: String, bodyHash: String): ArticleStudy? = withContext(Dispatchers.IO) {
        for (version in listOf(ReadingAiProtocol.VERSION, LEGACY_STUDY_SCHEMA)) {
            val row = dao.entry(studyKey(articleId, bodyHash, version)) ?: continue
            val study = runCatching { decodeStudy(row.data, articleId, bodyHash, version).also { require(it.scope == null) } }.getOrNull()
            if (study != null) return@withContext study
        }
        null
    }

    /** Enumerating cached ranges is read-only and never upgrades or retries a prior request. */
    override suspend fun cachedStudies(articleId: String, bodyHash: String): List<ArticleStudy> = withContext(Dispatchers.IO) {
        val full = cachedStudy(articleId, bodyHash)
        val prefix = studyKey(articleId, bodyHash) + ":range:"
        val scoped = dao.studyRows(prefix).mapNotNull { row ->
            runCatching {
                decodeStudy(row.data, articleId, bodyHash, ReadingAiProtocol.VERSION).also { study ->
                    require(study.scope != null && ReadingStudyScopes.cacheKey(articleId, bodyHash, study.schemaVersion, study.scope) == row.key)
                }
            }.getOrNull()
        }
        listOfNotNull(full) + scoped.sortedWith(compareBy<ArticleStudy> { it.scope!!.firstParagraph }
            .thenBy { it.scope!!.endParagraphExclusive }.thenByDescending { it.createdAt })
    }

    override suspend fun studyRange(article: Article, body: ArticleBody, scope: StudyScope, aiAllowed: Boolean,
        explicitRetry: Boolean): ArticleStudy = withContext(Dispatchers.IO) {
        initialize()
        if (!aiAllowed || !sourceAllowed(article)) throw ApiFailure("source_restricted", "此来源未开启 AI 研读，仍可阅读和本地查词")
        ReadingAiProtocol.analysisPayload(article, body, scope)
        val key = ReadingStudyScopes.cacheKey(article.id, body.contentHash, ReadingAiProtocol.VERSION, scope)
        cached(key, "reading_study_range", article.id,
            { decodeStudy(it, article.id, body.contentHash, ReadingAiProtocol.VERSION).also { study ->
                require(study.scope == scope); validateStudy(study, article, body)
            } }, { json.encodeToString(it) }, allowReplay = explicitRetry,
            beforeRequest = {
                if (!explicitRetry && dao.entry(key) != null)
                    throw ApiFailure("retry_required", "所选段落的旧研读记录不可用，请明确重试；应用不会自动重放可能已收费的请求")
                null
            }) {
            if (!sourceAllowed(article)) throw ApiFailure("source_restricted", "此来源已关闭 AI 研读，已有材料保留")
            ai.analyzeRange(article, body, scope).also {
                require(it.schemaVersion == ReadingAiProtocol.VERSION && it.scope == scope)
                validateStudy(it, article, body)
                if (!sourceAllowed(article)) throw ApiFailure("source_restricted", "此来源已关闭 AI 研读，旧结果保留")
            }
        }
    }

    override suspend fun study(article: Article, body: ArticleBody, aiAllowed: Boolean): ArticleStudy = performStudy(article, body, aiAllowed, false)
    override suspend fun retryStudy(article: Article, body: ArticleBody, aiAllowed: Boolean): ArticleStudy = performStudy(article, body, aiAllowed, true)

    private suspend fun performStudy(article: Article, body: ArticleBody, aiAllowed: Boolean, explicitRetry: Boolean): ArticleStudy = withContext(Dispatchers.IO) {
        initialize()
        if (!aiAllowed || !sourceAllowed(article)) throw ApiFailure("source_restricted", "此来源未开启全文 AI 研读，仍可阅读和本地查词")
        ReadingAiProtocol.analysisPayload(article, body)
        val key = studyKey(article.id, body.contentHash)
        cached(key, "reading_study", article.id,
            { decodeStudy(it, article.id, body.contentHash, ReadingAiProtocol.VERSION).also { study ->
                require(study.scope == null); validateStudy(study, article, body)
            } },
            { json.encodeToString(it) }, allowReplay = explicitRetry,
            beforeRequest = {
                if (explicitRetry) null else {
                    val oldKey = studyKey(article.id, body.contentHash, LEGACY_STUDY_SCHEMA)
                    val oldRow = dao.entry(oldKey)
                    val oldStudy = oldRow?.let { row -> runCatching {
                        decodeStudy(row.data, article.id, body.contentHash, LEGACY_STUDY_SCHEMA).also { validateStudy(it, article, body) }
                    }.getOrNull() }
                    if (oldStudy != null) oldStudy else {
                        val oldJobRow = dao.job("reading-ai:${sha256(oldKey)}")
                        val oldJob = oldJobRow?.let { runCatching { json.decodeFromString<AiJob>(it.data) }.getOrNull() }
                        if (oldRow != null || dao.entry(key) != null ||
                            (oldJobRow != null && oldJob?.errorCode !in setOf("missing_key", "paused"))) {
                            throw ApiFailure("retry_required", "旧研读记录没有可用的问题定位，请点击“更新问题定位”；应用不会自动重放可能已收费的请求")
                        }
                        null
                    }
                }
            }) {
            ai.analyze(article, body).also {
                require(it.schemaVersion == ReadingAiProtocol.VERSION && it.scope == null) { "新全文研读结果的版本或范围不符" }
                validateStudy(it, article, body)
            }
        }
    }

    override suspend fun cachedAssist(request: StudyRequest): StudyAssist? = withContext(Dispatchers.IO) {
        if (!request.aiAllowed) return@withContext null
        val wantsGeneratedBackground = request.kind == "background" && hasKey() && dao.entry("ai_paused")?.data != "true"
        dao.entry(assistKey(request))?.let { row ->
            runCatching { decodeAssistCache(row.data, request, wantsGeneratedBackground) }.getOrNull()
        }
    }

    override suspend fun assist(request: StudyRequest): StudyAssist = withContext(Dispatchers.IO) {
        initialize()
        ReadingAiProtocol.assistPayload(request)
        val signature = sha256(json.encodeToString(request))
        val background = request.kind == "background"
        val wantsGeneratedBackground = background && hasKey() && dao.entry("ai_paused")?.data != "true"
        cached(assistKey(request), if (background) "reading_background" else "reading_assist", signature,
            { decodeAssistCache(it, request, wantsGeneratedBackground) }, { json.encodeToString(it) },
            requiresKey = !background, shouldCache = { !background || it.retrievalStatus == "retrieved" },
            outcomeStatus = { if (background && it.retrievalStatus != "retrieved") it.retrievalStatus else "succeeded" }) {
            val retrievalOnly = background && (!hasKey() || dao.entry("ai_paused")?.data == "true")
            val result = if (retrievalOnly) ai.retrieveBackground(request) else ai.assist(request)
            if (retrievalOnly) require(result.model == "retrieval-only") { "暂停生成时只能返回检索片段" }
            result.also { validateAssist(it, request.kind) }
        }
    }

    private fun assistKey(request: StudyRequest): String {
        val signature = sha256(json.encodeToString(request))
        return if (request.kind == "background") "reading-cache:background:3:$signature" else "reading-cache:assist:$STUDY_SCHEMA:$signature"
    }

    private fun decodeAssistCache(data: String, request: StudyRequest, wantsGeneratedBackground: Boolean): StudyAssist =
        json.decodeFromString<StudyAssist>(data).also { result ->
            validateAssist(result, request.kind)
            if (request.kind == "background") {
                require(result.retrievalStatus == "retrieved")
                val now = clock()
                require(result.sources.all { it.retrievedAt <= now && now - it.retrievedAt < BackgroundEvidence.CACHE_TTL_MILLIS })
                require(!wantsGeneratedBackground || result.model != "retrieval-only")
            }
        }

    override suspend fun saveNote(note: ReadingNote) = withContext(Dispatchers.IO) {
        initialize(); validId(note.id)
        require(note.quote.isNotBlank() || note.comment.isNotBlank()) { "笔记内容不能为空" }
        require(note.quote.length <= 12_000 && note.comment.length <= 12_000) { "单条笔记的原文和评论各支持最多 12,000 字符" }
        require(note.tags.size <= 20 && note.tags.all { it.isNotBlank() && it.length <= 100 }) { "笔记标签过多或过长" }
        require(note.createdAt >= 0 && note.source.title.orEmpty().length <= 1000 && note.source.source.orEmpty().length <= 500 && note.source.url.orEmpty().length <= 4000)
        val start = note.start; val end = note.end; val articleId = note.articleId
        val previous = dao.entry("reading-library:note:${note.id}")?.let { json.decodeFromString<ReadingNote>(it.data) }
        require(previous == null || previous.origin == note.origin) { "修改笔记时不能更换内容来源类型" }
        if (note.origin == ContextOrigin.AI_GENERATED) require(start == null && end == null) { "AI 改写片段不能标为原文精确选区" }
        if (start != null || end != null) {
            require(start != null && end != null && start >= 0 && end > start && end - start == note.quote.length) { "笔记选区与原文长度不一致" }
            require(articleId != null && !note.bodyHash.isNullOrBlank()) { "精确选区需要关联正文快照" }
            val stored = db.dao().body(articleId, Long.MIN_VALUE)?.let { json.decodeFromString<ArticleBody>(it.data) }
            val preservesVerifiedQuote = previous != null && previous.articleId == articleId && previous.bodyHash == note.bodyHash &&
                previous.start == start && previous.end == end && previous.quote == note.quote && previous.source == note.source && previous.createdAt == note.createdAt
            if (stored != null && stored.contentHash == note.bodyHash) {
                require(end <= stored.text.length && stored.text.substring(start, end) == note.quote) { "笔记引用与正文快照不一致" }
            } else {
                require(preservesVerifiedQuote) { "找不到匹配的正文快照，请重新打开文章后保存精确引用" }
            }
        }
        dao.put(PreferenceRow("reading-library:note:${note.id}", json.encodeToString(note)))
    }

    override suspend fun deleteNote(id: String) = withContext(Dispatchers.IO) { validId(id); dao.delete("reading-library:note:$id") }

    override suspend fun recordSession(session: ReadingSession) = withContext(Dispatchers.IO) {
        initialize(); validId(session.id); validId(session.articleId)
        require(session.startedAt >= 0 && session.finishedAt >= session.startedAt && session.activeMillis >= 0 &&
            session.activeMillis <= session.finishedAt - session.startedAt && session.activeMillis <= 24L * 60 * 60 * 1000) { "阅读时间记录无效" }
        require(session.wordsRead in 0..1_000_000 && session.totalParagraphs in 0..10000 && session.paragraphsRead in 0..session.totalParagraphs && session.assistanceCount in 0..10000) { "阅读进度记录无效" }
        dao.recordSession(session)
        Unit
    }

    override suspend fun saveCheck(check: ComprehensionCheck) = withContext(Dispatchers.IO) {
        initialize(); validId(check.id); validId(check.articleId); validId(check.questionId)
        require(check.answer.isNotBlank() && check.answer.length <= 12000 && check.evaluatedBy.isNotBlank() && check.evaluatedBy.length <= 200)
        require(check.score.isFinite() && check.score in 0.0..1.0 && check.createdAt >= 0) { "理解练习评分须在 0 到 1 之间" }
        val event = LocalEvent("reading-check:${check.id}", "reading_comprehension_checked", check.createdAt)
        dao.recordOnce(PreferenceRow("reading-library:check:${check.id}", json.encodeToString(check)), event.row())
        Unit
    }

    override suspend fun expressionReview(): StudyAssist = withContext(Dispatchers.IO) {
        initialize()
        val notes = decodeLibrary(dao.libraryRows()).notes.sortedWith(compareByDescending<ReadingNote> { it.createdAt }.thenBy { it.id }).distinctBy { it.id }
        if (notes.isEmpty()) throw ApiFailure("no_notes", "先保存一些阅读笔记，再生成表达回顾")
        val now = clock()
        val zone = ZoneId.of("Asia/Shanghai")
        val endDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val startDay = endDay.minusDays(6)
        val windowStart = startDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val window = notes.filter { it.createdAt in windowStart..now }
        val windowLabel = "$startDay—$endDay（上海，最近 7 个自然日）"
        if (window.isEmpty()) throw ApiFailure("no_recent_notes", "$windowLabel 没有笔记；较早的笔记仍保留，可查看或导出")
        val permissions = mutableMapOf<String, Boolean>()
        suspend fun permitted(note: ReadingNote): Boolean {
            val sourceUrl = note.source.url
            val permissionKey = note.articleId?.let { "article:$it" } ?: sourceUrl?.let { "url:$it" } ?: "user-material"
            permissions[permissionKey]?.let { return it }
            val article = note.articleId?.let { db.dao().article(it) }?.let { json.decodeFromString<Article>(it.data) }
            val allowed = when {
                article != null -> sourceAllowed(article)
                note.articleId != null -> false
                sourceUrl.isNullOrBlank() -> true
                else -> db.dao().articleByUrl(sourceUrl)?.let { sourceAllowed(json.decodeFromString<Article>(it.data)) } ?: false
            }
            permissions[permissionKey] = allowed
            return allowed
        }
        val selected = mutableListOf<ReadingNote>()
        var restricted = 0
        var outsideBudget = 0
        for (note in window) {
            if (!permitted(note)) { restricted++; continue }
            if (selected.size == ReadingAiProtocol.MAX_REVIEW_NOTES) { outsideBudget++; continue }
            try {
                // Validate whole notes against the same metadata-inclusive input budget as the API.
                ReadingAiProtocol.reviewPayload(selected + note)
                selected += note
            } catch (failure: ApiFailure) {
                if (failure.code != "input_too_long") throw failure
                outsideBudget++
            }
        }
        if (selected.isEmpty()) {
            if (restricted == window.size) throw ApiFailure("source_restricted", "$windowLabel 的 ${window.size} 条笔记均受来源限制，本次没有发送 AI 请求；笔记仍保留")
            throw ApiFailure("input_too_long", "$windowLabel 内可处理的笔记均超过单次 24,000 字符预算；请缩短评论后重试，原笔记仍保留")
        }
        val signature = sha256("review-provenance-v2\n" + json.encodeToString(selected))
        val cacheKey = "reading-cache:review:$STUDY_SCHEMA:$signature"
        val analysis = cached(cacheKey, "reading_review", signature,
            { json.decodeFromString<StudyAssist>(it).also { result -> validateAssist(result, "review") } }, { json.encodeToString(it) }) {
            ai.review(selected).also { validateAssist(it, "review") }
        }
        val coverage = "回顾窗口：$windowLabel；窗口内 ${window.size} 条，入选 ${selected.size} 条。" +
            "未纳入：来源限制 $restricted 条，数量/输入上限 $outsideBudget 条，窗口外 ${notes.size - window.size} 条。所有笔记均保留。"
        val result = analysis.copy(evidence = "$coverage\n${analysis.evidence}")
        // Coverage is recomputed even when the unchanged selected material reuses a cached analysis.
        dao.put(PreferenceRow("reading-library:review:$signature", json.encodeToString(result)))
        result
    }

    override suspend fun exportKnowledge(destination: File): File = withContext(Dispatchers.IO) {
        initialize()
        val library = decodeLibrary(dao.libraryRows())
        if (library.backgroundRecordErrors > 0) throw ApiFailure("background_record_invalid",
            "有 ${library.backgroundRecordErrors} 条背景知识项无法读取，导出未完成；原记录保留")
        val text = KnowledgeMarkdown.render(library)
        val parent = requireNotNull(destination.absoluteFile.parentFile).apply { mkdirs() }
        val temporary = File(parent, ".knowledge-${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            try { Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temporary.delete() }
        destination
    }

    private suspend fun <T> cached(key: String, kind: String, entity: String, decode: (String) -> T,
        encode: (T) -> String, libraryKey: String? = null, allowReplay: Boolean = true,
        requiresKey: Boolean = true, shouldCache: (T) -> Boolean = { true }, outcomeStatus: (T) -> String = { "succeeded" },
        beforeRequest: suspend () -> T? = { null }, operation: suspend () -> T): T = aiLock.withLock {
        dao.entry(key)?.let { row -> runCatching { decode(row.data) }.getOrNull()?.let { return@withLock it } }
        beforeRequest()?.let { return@withLock it }
        val id = "reading-ai:${sha256(key)}"
        val old = dao.job(id)?.let { json.decodeFromString<AiJob>(it.data) }
        if (!allowReplay && old != null && old.errorCode !in setOf("missing_key", "paused")) {
            throw ApiFailure("retry_required", "上次研读任务未得到可用结果，请点击“重试”；应用不会自动重放可能已收费的请求")
        }
        val start = clock()
        val job = AiJob(id, kind, entity, "running", old?.createdAt ?: start, start)
        dao.putJob(job.row())
        try {
            if (requiresKey && !hasKey()) throw ApiFailure("missing_key", "请先在设置中保存 DeepSeek API Key")
            if (requiresKey && dao.entry("ai_paused")?.data == "true") throw ApiFailure("paused", "AI 任务已暂停，请在设置中检查并重新保存 API Key")
            val result = operation()
            val completed = job.copy(status = outcomeStatus(result), updatedAt = clock()).row()
            if (shouldCache(result)) {
                val encoded = encode(result)
                dao.finishJob(PreferenceRow(key, encoded), completed, libraryKey?.let { PreferenceRow(it, encoded) })
            } else dao.putJob(completed)
            result
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.putJob(job.copy(status = "interrupted", updatedAt = clock(), errorCode = "cancelled").row()) }
            throw e
        } catch (e: Exception) {
            val code = (e as? ApiFailure)?.code ?: "reading_failed"
            dao.failJob(job.copy(status = "failed", updatedAt = clock(), errorCode = code).row(), code in setOf("authentication", "quota"))
            if (e is ApiFailure) throw e
            throw ApiFailure(code, "研读任务未完成，原文和笔记已保留，可稍后重试")
        }
    }

    private fun decodeStudy(data: String, articleId: String, bodyHash: String, version: Int): ArticleStudy {
        require(data.length <= 250_000)
        return json.decodeFromString<ArticleStudy>(data).also { study ->
            require(study.articleId == articleId && study.bodyHash == bodyHash && study.schemaVersion == version)
            validateStudyShape(study)
        }
    }

    private fun validateStudyShape(study: ArticleStudy) {
        require(study.schemaVersion in setOf(LEGACY_STUDY_SCHEMA, ReadingAiProtocol.VERSION))
        val scope = study.scope
        if (scope != null) {
            require(study.schemaVersion == ReadingAiProtocol.VERSION)
            ReadingStudyScopes.validateMetadata(scope)
            require(study.paragraphs.map { it.index }.sorted() == (scope.firstParagraph until scope.endParagraphExclusive).toList())
            require(study.highlights.all { it.start >= scope.start && it.end > it.start && it.end <= scope.endExclusive })
            require(study.nodes.all { it.paragraph == null || it.paragraph in scope.firstParagraph until scope.endParagraphExclusive })
            require(study.backgrounds.all { it.paragraph in scope.firstParagraph until scope.endParagraphExclusive })
        }
        require(study.summary.isNotBlank() && study.summary.length <= 1800 && study.cefrEstimate in ReadingAiProtocol.cefr)
        require(study.questions.size in 1..5 && study.questions.map { it.id }.distinct().size == study.questions.size)
        study.questions.forEach { question ->
            require(question.id.isNotBlank() && question.id.length <= (if (scope == null) 48 else 80) && question.id.none { it.isISOControl() })
            require(question.question.isNotBlank() && question.question.length <= 700 && question.answer.isNotBlank() && question.answer.length <= 1400)
            require(question.paragraphs.size in 1..8 && question.paragraphs.distinct().size == question.paragraphs.size &&
                question.paragraphs.all { it in (scope?.let { range -> range.firstParagraph until range.endParagraphExclusive }
                    ?: (0 until ReadingAiProtocol.MAX_PARAGRAPHS)) })
            if (study.schemaVersion == LEGACY_STUDY_SCHEMA) require(question.evidence.isEmpty())
            else {
                require(question.evidence.size in 1..ReadingAiProtocol.MAX_QUESTION_SENTENCES)
                require(question.evidence.map { it.sentenceId }.distinct().size == question.evidence.size)
                require(question.evidence.map { it.start to it.endExclusive }.distinct().size == question.evidence.size)
                require(question.evidence.all { range -> range.sentenceId.isNotBlank() && range.sentenceId.length <= 120 &&
                    range.start >= (scope?.start ?: 0) && range.endExclusive > range.start &&
                    range.endExclusive <= (scope?.endExclusive ?: ReadingAiProtocol.MAX_INPUT) })
                require(question.evidence.map { it.paragraphIndex }.toSet() == question.paragraphs.toSet())
            }
        }
    }

    private fun validateStudy(study: ArticleStudy, article: Article, body: ArticleBody) {
        require(study.articleId == article.id && study.bodyHash == body.contentHash)
        validateStudyShape(study)
        val spans = ParagraphFocus.paragraphs(body.text)
        val indices = study.scope?.let { ReadingStudyScopes.validate(body, it); it.firstParagraph until it.endParagraphExclusive } ?: spans.indices
        require(study.paragraphs.map { it.index }.sorted() == indices.toList())
        require(study.highlights.all { it.start >= 0 && it.end > it.start && it.end <= body.text.length && body.text.substring(it.start, it.end) == it.quote })
        if (study.scope != null) ReadingStudyScopes.validateStudy(study, body)
        val units = ReadingSkimPlanner.sentences(body.text).filter { it.paragraphIndex in indices }.associateBy { it.id }
        study.questions.forEach { question ->
            require(question.paragraphs.all { it in indices })
            require(question.evidence.all { evidence -> units[evidence.sentenceId]?.evidenceRange() == evidence }) {
                "问题定位与原文快照不一致"
            }
        }
    }
    private fun validateAssist(result: StudyAssist, kind: String) {
        require(result.kind == kind && result.title.isNotBlank() && result.title.length <= 160 && result.text.isNotBlank() && result.text.length <= 32000)
        val score = result.score
        require(score == null || (kind == "check_answer" && score.isFinite() && score in 0.0..1.0))
        if (kind == "background") {
            require(result.retrievalStatus in setOf("retrieved", "no_results", "unavailable"))
            if (result.retrievalStatus == "retrieved") {
                BackgroundEvidence.validateSources(result.sources)
                require(result.citedSourceIds.isNotEmpty() && result.citedSourceIds.distinct() == result.citedSourceIds &&
                    result.citedSourceIds.all { id -> result.sources.any { it.id == id } })
            } else require(result.sources.isEmpty() && result.citedSourceIds.isEmpty() && result.model == "retrieval-only")
        }
    }
    private fun decodeLibrary(rows: List<PreferenceRow>): ReadingLibrary {
        fun <T> values(prefix: String, decode: (String) -> T) = rows.filter { it.key.startsWith(prefix) }.mapNotNull { runCatching { decode(it.data) }.getOrNull() }
        val backgroundResults = rows.filter { it.key.startsWith(BackgroundRecordStorage.PREFIX) }
            .map { row -> runCatching { BackgroundRecordStorage.decode(row) } }
        return ReadingLibrary(
            notes = values("reading-library:note:") { json.decodeFromString<ReadingNote>(it) }.sortedByDescending { it.createdAt },
            sessions = values("reading-library:session:") { json.decodeFromString<ReadingSession>(it) }.sortedByDescending { it.startedAt },
            checks = values("reading-library:check:") { json.decodeFromString<ComprehensionCheck>(it) }.sortedByDescending { it.createdAt },
            editions = values("reading-library:edition:") { json.decodeFromString<DailyEdition>(it) }.sortedByDescending { it.day },
            reviews = values("reading-library:review:") { json.decodeFromString<StudyAssist>(it) }.sortedByDescending { it.createdAt },
            backgroundRecords = backgroundResults.mapNotNull { it.getOrNull() }
                .sortedWith(compareByDescending<BackgroundRecord> { it.createdAt }.thenBy { it.id }),
            backgroundRecordErrors = backgroundResults.count { it.isFailure },
        )
    }
    private fun studyKey(articleId: String, bodyHash: String, version: Int = ReadingAiProtocol.VERSION) =
        ReadingStudyScopes.cacheKey(articleId, bodyHash, version, null)
    private fun validId(id: String) { require(id.isNotBlank() && id.length <= 200 && id.none { it.isISOControl() }) { "记录标识无效" } }
    private fun AiJob.row() = JobRow(id, status, updatedAt, json.encodeToString(this))
    private fun LocalEvent.row() = EventRow(id, createdAt, json.encodeToString(this))
    companion object {
        private const val STUDY_SCHEMA = 1 // Assist and expression-review contracts did not change.
        private const val LEGACY_STUDY_SCHEMA = 1
    }
}
