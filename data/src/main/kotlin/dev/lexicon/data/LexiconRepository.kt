package dev.lexicon.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.lexicon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Application-scoped owner of local truth. No API key, input text or model response is logged. */
class LexiconRepository(context: Context, database: LexiconDatabase? = null, learningAi: LearningAiService? = null) : AppRepository {
    private val app = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val db = database ?: Room.databaseBuilder<LexiconDatabase>(app, "lexicon.sqlite")
        .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
    private val dao = db.dao()
    override val reviewHistory: ReviewHistoryRepository = LocalReviewHistoryRepository(db.reviewHistoryDao())
    override val captureInbox: CaptureInboxRepository = CaptureInboxStore(db.captureInboxDao())
    private val captureAnalysisStore = CaptureAnalysisStore(db.captureAnalysisDao(), db.vocabularyDao())
    private val keyStore = ApiKeyStore(app)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dictionary = DictionaryStore(app, preparationScope = appScope)
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build()
    private val ai: LearningAiService = learningAi ?: DeepSeekService(keyStore, client)
    private val readingAi = DeepSeekReadingAi({ keyStore.read() }, client, sourceAllowed = ::readingSourceAllowed)
    override val reading: ReadingRepository = LocalReadingRepository(db, readingAi,
        hasKey = { !keyStore.read().isNullOrBlank() }, sourceAllowed = ::readingSourceAllowed)

    override val personalization: PersonalizationRepository = LocalPersonalizationRepository(db)
    override val speedReading: SpeedReadingRepository = LocalSpeedReadingRepository(db,
        DeepSeekSpeedReadingAi({ keyStore.read() }, client),
        hasKey = { !keyStore.read().isNullOrBlank() }, sourceAllowed = ::readingSourceAllowed)
    override val baseline: ReadingBaselineRepository = LocalReadingBaselineRepository(db, speedReading)
    override val ieltsPractice: IeltsPracticeRepository = LocalIeltsPracticeRepository(db)
    override val ieltsGeneration: IeltsGenerationRepository = LocalIeltsGenerationRepository(db, IeltsGatewaySettings(app), ieltsPractice)
    private val vocabularyData = LocalVocabularyLearningRepository(db,
        analyze = { analyzeCaptureImpl(it, deep = false, persistInput = false) },
        saveCard = { capture, result -> saveCard(capture, result) }, hasKey = { hasApiKey() })
    override val vocabulary: VocabularyLearningRepository get() = vocabularyData

    private suspend fun readingSourceAllowed(article: Article): Boolean =
        article.sourceIds.distinct().mapNotNull { source(it) }.any { it.aiAllowed }

    private val exporter = LegacyAnkiExporter()
    private val initLock = Mutex()
    private val syncLock = Mutex()
    private val classificationLock = Mutex()
    private val analysisLock = Mutex()
    private val mutationLock = Mutex()
    private val _status = MutableStateFlow("[ready] 本地数据已就绪")
    override val status: StateFlow<String> = _status.asStateFlow()
    private var initialized = false
    private val analysisCache = mutableMapOf<String, AnalysisResult>()
    private data class PreparedExport(val versions: Map<String, Long>, val contentHash: String)
    private val preparedExports = LinkedHashMap<String, PreparedExport>()

    override val sources: Flow<List<Source>> = dao.observeSources().map { list -> list.map { json.decodeFromString<Source>(it.data) } }
    override val articles: Flow<List<Article>> = dao.observeArticles().map { list -> list.map { json.decodeFromString<Article>(it.data) } }
    override val captures: Flow<List<Capture>> = dao.observeCaptures().map { list -> list.map { json.decodeFromString<Capture>(it.data) } }
    override val cards: Flow<List<Card>> = dao.observeCards().map { list -> list.map { json.decodeFromString<Card>(it.data) } }
    override val jobs: Flow<List<AiJob>> = dao.observeJobs().map { list -> list.map { json.decodeFromString<AiJob>(it.data) } }
    override val settings: Flow<AppSettings> = dao.observePreference("settings").map { it?.let { row -> json.decodeFromString<AppSettings>(row.data) } ?: AppSettings() }

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        initLock.withLock {
            if (initialized) return@withLock
            seedSources.forEach { catalog ->
                val saved = source(catalog.id)
                val refreshed = SourceCatalog.refresh(catalog, saved)
                if (refreshed != saved) putSource(refreshed)
            }
            db.readingDao().expireBodiesPreservingExposure(System.currentTimeMillis())
            // A process death cannot prove a remote model request failed. Never automatically replay it.
            dao.runningJobs().forEach { row ->
                val job = json.decodeFromString<AiJob>(row.data)
                putJob(job.copy(status = "interrupted", updatedAt = System.currentTimeMillis(), errorCode = "process_interrupted"))
            }
            initialized = true
        }
    }

    override suspend fun updateSettings(settings: AppSettings) = withContext(Dispatchers.IO) {
        val enabledClassification = mutationLock.withLock {
            val previous = dao.preference("settings")?.let { json.decodeFromString<AppSettings>(it.data) } ?: AppSettings()
            dao.putPreference(PreferenceRow("settings", json.encodeToString(settings)))
            !previous.titleClassification && settings.titleClassification
        }
        if (enabledClassification) classifyInBackground()
    }
    override suspend fun hasApiKey(): Boolean = withContext(Dispatchers.IO) { !keyStore.read().isNullOrBlank() }
    override suspend fun setApiKey(key: String) = withContext(Dispatchers.IO) {
        keyStore.save(key.trim())
        dao.putPreference(PreferenceRow("ai_paused", "false"))
        _status.value = "[done] API Key 已加密保存"
        classifyInBackground()
    }
    override suspend fun clearApiKey() = withContext(Dispatchers.IO) {
        keyStore.clear(); _status.value = "[done] API Key 已移除"
    }

    override suspend fun setSourceEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        initialize()
        val source = source(id) ?: return@withContext
        putSource(source.copy(enabled = enabled))
    }

    override suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        initialize()
        syncLock.withLock {
            val enabled = allSources().filter { it.enabled }
            val errors = mutableListOf<String>()
            var count = 0
            enabled.forEachIndexed { index, source ->
                ensureActive()
                _status.value = "[syncing] ${index + 1}/${enabled.size} ${source.name}"
                try {
                    val request = Request.Builder().url(source.feedUrl).header("User-Agent", "LexiconIDE/1.0 (personal RSS reader)")
                    source.etag?.let { request.header("If-None-Match", it) }
                    source.lastModified?.let { request.header("If-Modified-Since", it) }
                    client.newCall(request.build()).await().use { response ->
                        val now = System.currentTimeMillis()
                        if (response.code == 304) {
                            putSource((source(source.id) ?: source).copy(lastFetchedAt = now, lastError = null))
                        } else {
                            check(response.isSuccessful) { "HTTP ${response.code}" }
                            val entries = FeedParser.parse(response.boundedBody(12_000_000), source.feedUrl)
                            for (entry in entries) {
                                val old = dao.articleByGuid(source.id, entry.guid)?.let { json.decodeFromString<Article>(it.data) }
                                    ?: dao.articleByUrl(entry.url)?.let { json.decodeFromString<Article>(it.data) }
                                val topic = FeedParser.topic(entry.categories, source.defaultTopic)
                                val preferredSource = if (old?.readingMode == ReadingMode.NATIVE && source.readingMode != ReadingMode.NATIVE) null else source
                                val article = Article(
                                    id = old?.id ?: UUID.nameUUIDFromBytes(entry.url.toByteArray()).toString(),
                                    guid = old?.guid ?: entry.guid,
                                    sourceIds = (old?.sourceIds.orEmpty() + source.id).distinct(),
                                    title = entry.title, url = entry.url, summary = entry.summary,
                                    author = entry.author.ifBlank { old?.author.orEmpty() }, publishedAt = entry.publishedAt ?: old?.publishedAt,
                                    topic = topic ?: old?.takeIf { it.title == entry.title }?.topic,
                                    subtopic = if (topic != null) null else old?.takeIf { it.title == entry.title }?.subtopic,
                                    topicOrigin = if (topic != null) "feed" else old?.takeIf { it.title == entry.title }?.topicOrigin,
                                    read = old?.read ?: false, captured = old?.captured ?: false, cachedAt = now,
                                    readingMode = preferredSource?.readingMode ?: old?.readingMode ?: ReadingMode.EXTERNAL,
                                    sourceName = preferredSource?.name ?: old?.sourceName.orEmpty(),
                                    license = if (source.readingMode == ReadingMode.NATIVE) entry.rights.ifBlank { source.license } else old?.license ?: source.license,
                                    wordCount = old?.wordCount,
                                )
                                dao.putArticleAndSource(article.row(), ArticleSourceRow(source.id, entry.guid, article.id))
                                if (old == null) count++
                            }
                            putSource((source(source.id) ?: source).copy(etag = response.header("ETag"), lastModified = response.header("Last-Modified"), lastFetchedAt = now, lastError = null))
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    val message = "${source.name} 同步失败，可稍后重试"
                    errors.add(message)
                    putSource((source(source.id) ?: source).copy(lastError = message))
                }
            }
            dao.expireBodies(System.currentTimeMillis())
            _status.value = if (errors.isEmpty()) "[done] $count 篇新文章" else "[error] ${errors.size} 个来源失败；已保存 $count 篇新文章"
            recordEvent("sync_completed", count)
            classifyInBackground()
            SyncResult(enabled.size, count, errors)
        }
    }

    override suspend fun markArticleRead(id: String) = withContext(Dispatchers.IO) {
        initialize()
        db.readingDao().recordArticleOpen(id, System.currentTimeMillis())
        Unit
    }

    override suspend fun openArticle(id: String): ArticleBody? = loadArticleBody(id, markRead = true)

    override suspend fun prepareBaselineBody(id: String): ArticleBody? = loadArticleBody(id, markRead = false)

    private suspend fun loadArticleBody(id: String, markRead: Boolean): ArticleBody? = withContext(Dispatchers.IO) {
        initialize()
        val article = article(id) ?: return@withContext null
        val now = System.currentTimeMillis()
        if (markRead) db.readingDao().recordArticleOpen(id, now)
        db.readingDao().expireBodiesPreservingExposure(now)
        dao.body(id, now)?.let { return@withContext json.decodeFromString<ArticleBody>(it.data) }
        val available = article.sourceIds.mapNotNull { source(it) }.filter { it.enabled }
        val preferred = available.firstOrNull { it.readingMode == ReadingMode.NATIVE } ?: available.firstOrNull()
        if (preferred == null) return@withContext null
        _status.value = "[reading] 加载正文…"
        val readingClient = client.newBuilder().callTimeout(18, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
        // Only the official feed and normal article URL are requested. No alternate/paywall endpoints.
        val feedEntry = try {
            readingClient.newCall(Request.Builder().url(preferred.feedUrl).build()).await().use { response ->
                if (!response.isSuccessful) null else FeedParser.parse(response.boundedBody(12_000_000), preferred.feedUrl)
                    .firstOrNull { it.url == article.url || it.guid == article.guid }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val feedText = feedEntry?.body?.takeIf { PublicArticleParser.isSubstantial(it) }
        val publicText = if (feedText == null) try {
            readingClient.newCall(Request.Builder().url(article.url).build()).await().use { response ->
                if (!response.isSuccessful) null else PublicArticleParser.extract(response.boundedBody(6_000_000), article.url)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null } else null
        val text = feedText ?: publicText
        if (text == null) {
            _status.value = "[ready]"
            return@withContext null // The UI opens the page inside this app, including its normal login.
        }
        val attribution = feedEntry?.rights?.takeIf { it.isNotBlank() } ?: "${article.sourceName} · 私人阅读缓存"
        val result = ArticleBody(id, text, sha256(text), now, now + 30L * 24 * 60 * 60 * 1000,
            attribution, feedEntry?.author.orEmpty().ifBlank { article.author })
        val words = Regex("\\b[\\p{L}]+(?:['’-][\\p{L}]+)*\\b").findAll(result.text).count()
        db.readingDao().cacheNativeBody(result, words, markRead, now)
        _status.value = "[ready]"
        result
    }

    override suspend fun saveCapture(capture: Capture) = withContext(Dispatchers.IO) {
        initialize()
        require(capture.target.length <= CaptureLimits.TARGET && capture.selectedText.length <= CaptureLimits.SELECTED_TEXT && capture.sentence.length <= CaptureLimits.SENTENCE) { "捕获文本过长，请缩小选区" }
        require(capture.selectedText.isNotBlank() || capture.target.isNotBlank()) { "请先选择或输入文本" }
        val associated = capture.articleId?.let { article(it) }
        val allowed = associated?.sourceIds?.mapNotNull { source(it) }?.any { it.aiAllowed } ?: capture.aiAllowed
        val saved = capture.copy(aiAllowed = allowed, contextOrigin = if (capture.sentence.isBlank()) ContextOrigin.MISSING else capture.contextOrigin)
        val existing = dao.capture(saved.id)
        dao.putCapture(CaptureRow(saved.id, saved.createdAt, json.encodeToString(saved)))
        associated?.let { putArticle(it.copy(captured = true)) }
        if (existing == null) recordEvent("capture_saved")
    }

    override suspend fun lookup(text: String): List<DictionaryEntry> = dictionary.lookup(text)

    override suspend fun dictionaryStatus(): DictionaryStatus = dictionary.status()
    override suspend fun installedDictionaries(): List<InstalledDictionary> = dictionary.installed()
    override suspend fun selectDictionary(id: String): DictionaryStatus = dictionary.select(id)

    override suspend fun importDictionary(file: File): DictionaryStatus {
        val result = dictionary.import(file)
        _status.value = "[done] ${result.message}"
        return result
    }

    override suspend fun availableCaptureAnalysis(capture: Capture): CaptureAnalysisAvailability =
        captureAnalysisStore.available(capture)

    override suspend fun saveAvailableCaptureAnalysis(capture: Capture, expectedAnalysisId: String): Card =
        captureAnalysisStore.save(capture, expectedAnalysisId)

    override suspend fun analyzeCapture(capture: Capture, deep: Boolean): AnalysisResult = analyzeCaptureImpl(capture, deep, persistInput = true)

    private suspend fun analyzeCaptureImpl(capture: Capture, deep: Boolean, persistInput: Boolean): AnalysisResult = withContext(Dispatchers.IO) {
        initialize()
        if (persistInput) saveCapture(capture)
        val storedRecord = dao.capture(capture.id)?.let { json.decodeFromString<Capture>(it.data) }
            ?: if (persistInput) capture else throw ApiFailure("capture_changed", "捕获记录不可用，请先核对原句")
        if (!persistInput && !VocabularyLearning.sameCapture(storedRecord, capture))
            throw ApiFailure("capture_changed", "捕获已被编辑，请先核对原句")
        val stored = if (persistInput) storedRecord else storedRecord.copy(aiAllowed = storedRecord.articleId?.let { id ->
            article(id)?.let { readingSourceAllowed(it) }
        } ?: false)
        if (stored.sentence.isBlank()) throw ApiFailure("missing_context", "捕获已保存。补充原句后即可生成语境卡")
        analysisLock.withLock {
            val signature = inputSignature(stored)
            if (!deep) dao.cardByCapture(stored.id)?.let { row ->
                val saved = json.decodeFromString<Card>(row.data)
                if (!cardMatches(saved, stored)) throw ApiFailure("capture_changed", "此捕获已有不同内容的卡片，请编辑卡片或新建捕获")
                return@withLock AnalysisResult(saved.content, "saved-card", signature)
            }
            if (!stored.aiAllowed) throw ApiFailure("source_restricted", "此来源未开启 AI 分析，已有材料已保留")
            val cacheKey = signature + if (deep) ":deep" else ":flash"
            analysisCache[cacheKey]?.let { return@withLock it }
            if (!deep) (captureAnalysisStore.available(stored) as? CaptureAnalysisAvailability.Ready)?.let { cached ->
                rememberAnalysis(cacheKey, signature, cached.analysis)
                return@withLock cached.analysis
            }
            if (dao.preference("ai_paused")?.data == "true") throw ApiFailure("ai_paused", "AI 任务已暂停，请检查 API Key 或余额后重新保存 Key")
            val now = System.currentTimeMillis()
            val job = AiJob("analysis:$cacheKey", if (deep) "deep_analysis" else "vocabulary", stored.id, "running", now, now)
            putJob(job)
            _status.value = if (deep) "[analyzing] DeepSeek 深度分析中" else "[generating] 正在生成生词卡"
            recordEvent(if (deep) "deep_analysis_started" else "card_generation_started")
            try {
                val evidence = runCatching { dictionary.lookup(stored.target) }.getOrDefault(emptyList())
                val result = ai.analyze(stored, deep, evidence).copy(inputSignature = signature)
                captureAnalysisStore.persist(stored, result, deep)
                rememberAnalysis(cacheKey, signature, result)
                putJob(job.copy(status = "completed", updatedAt = System.currentTimeMillis()))
                dao.putPreference(PreferenceRow("actual_model:${job.id}", result.model))
                recordEvent("analysis_schema_valid")
                _status.value = "[done] 分析完成；AI 例示已单独标注"
                result
            } catch (e: CancellationException) {
                withContext(NonCancellable) { putJob(job.copy(status = "interrupted", updatedAt = System.currentTimeMillis(), errorCode = "cancelled")) }
                throw e
            } catch (e: Exception) {
                val failure = safeFailure(e)
                putJob(job.copy(status = "failed", updatedAt = System.currentTimeMillis(), errorCode = failure.code))
                if (failure.code in setOf("authentication", "quota")) dao.putPreference(PreferenceRow("ai_paused", "true"))
                _status.value = "[error] ${failure.message}"
                recordEvent("analysis_failed")
                throw failure
            }
        }
    }

    override suspend fun saveCard(capture: Capture, analysis: AnalysisResult): Card = withContext(Dispatchers.IO) {
        initialize()
        require(capture.sentence.isNotBlank()) { "请先补充原句" }
        DeepSeekProtocol.validateContent(analysis.content)
        analysis.inputSignature?.let { require(it == inputSignature(capture)) { "目标词或原句已更改，请重新分析" } }
        mutationLock.withLock {
            val id = UUID.nameUUIDFromBytes("vocab:${capture.id}".toByteArray()).toString()
            val now = System.currentTimeMillis()
            val proposed = Card(id, capture.id, analysis.content, capture.sentence, capture.sourceRef,
                state = FsrsState(dueAt = now), createdAt = now, updatedAt = now, contextOrigin = capture.contextOrigin)
            val saved = db.vocabularyDao().commitCard(capture, proposed)
            _status.value = "[done] 已存入生词卡"
            saved
        }
    }

    override suspend fun updateCard(card: Card) = withContext(Dispatchers.IO) {
        DeepSeekProtocol.validateContent(card.content)
        check(db.cardLifecycleDao().edit(card, System.currentTimeMillis()) != null) { "卡片已不可用" }
    }

    override suspend fun setCardReviewPaused(cardId: String, paused: Boolean): Card? = withContext(Dispatchers.IO) {
        db.cardLifecycleDao().setReviewPaused(cardId, paused)
    }

    override suspend fun reviewCard(cardId: String, rating: Rating, expectedReps: Int, now: Long): ReviewOutcome = withContext(Dispatchers.IO) {
        db.cardLifecycleDao().review(cardId, rating, expectedReps, now).also {
            if (it == ReviewOutcome.APPLIED) _status.value = "[done] 已记录复习"
        }
    }

    override suspend fun exportAnki(destination: File): File = withContext(Dispatchers.IO) {
        initialize()
        val snapshot = dao.allCards().map { json.decodeFromString<Card>(it.data) }
        _status.value = "[exporting] ${snapshot.size} 张生词卡"
        try {
            val result = exporter.export(snapshot, destination)
            mutationLock.withLock {
                preparedExports[result.absolutePath] = PreparedExport(snapshot.associate { it.id to it.updatedAt }, fileHash(result))
                while (preparedExports.size > 8) preparedExports.remove(preparedExports.keys.first())
            }
            _status.value = "[exporting] 快照已准备，正在写入所选位置"
            result
        } catch (e: Exception) { _status.value = "[error] 导出失败，卡片仍完整保留"; throw e }
    }

    override suspend fun confirmAnkiExport(exportFile: File) = withContext(Dispatchers.IO) {
        mutationLock.withLock {
            val snapshot = preparedExports[exportFile.absolutePath] ?: error("找不到待确认的导出快照")
            check(fileHash(exportFile) == snapshot.contentHash) { "导出快照已发生变化" }
            val exportedAt = System.currentTimeMillis()
            db.cardLifecycleDao().confirmExport(snapshot.versions, exportedAt)
            preparedExports.remove(exportFile.absolutePath)
            _status.value = "[done] 已导出 ${snapshot.versions.size} 张卡片（单向内容快照）"
        }
    }

    private fun fileHash(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override suspend fun exportEvents(destination: File): File = withContext(Dispatchers.IO) {
        val events = dao.allEvents().map { json.decodeFromString<LocalEvent>(it.data) }
        destination.parentFile?.mkdirs()
        destination.writeText(Json { prettyPrint = true }.encodeToString(events))
        destination
    }

    override suspend fun recordEvent(type: String, value: Int) = withContext(Dispatchers.IO) {
        // Only fixed event names and counts enter the metrics file, never arbitrary UI text.
        if (!Regex("[a-z_]{1,60}").matches(type)) return@withContext
        val event = LocalEvent(UUID.randomUUID().toString(), type, System.currentTimeMillis(), value)
        dao.putEvent(EventRow(event.id, event.createdAt, json.encodeToString(event)))
    }

    override suspend fun classifyPending() = withContext(Dispatchers.IO) {
        initialize()
        if (!classificationLock.tryLock()) return@withContext
        try {
            if (!currentSettings().titleClassification || !hasApiKey() || dao.preference("ai_paused")?.data == "true") return@withContext
            val allowed = allSources().filter { it.enabled && it.aiAllowed }.map { it.id }.toSet()
            val pending = dao.allArticles().map { json.decodeFromString<Article>(it.data) }
                .filter { it.topic == null && it.topicOrigin != "ai_unknown" && it.sourceIds.any(allowed::contains) }
            for (chunk in pending.chunked(20)) {
                ensureActive()
                if (!currentSettings().titleClassification || !hasApiKey()) break
                val uncached = mutableListOf<Article>()
                for (article in chunk) {
                    val cached = dao.cachedTopic(titleHash(article))
                    if (cached == null) uncached.add(article)
                    else applyTopic(article.id, cached.topic, cached.subtopic)
                }
                if (uncached.isEmpty()) continue
                // Card generation has priority over background classification between requests.
                if (analysisLock.isLocked) analysisLock.withLock { }
                val now = System.currentTimeMillis()
                val jobId = "classify:" + sha256(uncached.joinToString("|") { titleHash(it) })
                val job = AiJob(jobId, "title_classification", uncached.joinToString(",") { it.id }, "running", now, now)
                putJob(job)
                try {
                    val results = ai.classify(uncached.map { TopicInput(it.id, it.title, it.sourceName) })
                    for (result in results) {
                        val original = uncached.first { it.id == result.id }
                        dao.putTopic(TopicCacheRow(titleHash(original), result.topic, result.subtopic))
                        applyTopic(result.id, result.topic, result.subtopic)
                    }
                    putJob(job.copy(status = "completed", updatedAt = System.currentTimeMillis()))
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { putJob(job.copy(status = "interrupted", updatedAt = System.currentTimeMillis(), errorCode = "cancelled")) }
                    throw e
                } catch (e: Exception) {
                    val failure = safeFailure(e)
                    putJob(job.copy(status = "failed", updatedAt = System.currentTimeMillis(), errorCode = failure.code))
                    if (failure.code in setOf("authentication", "quota")) dao.putPreference(PreferenceRow("ai_paused", "true"))
                    _status.value = "[info] ${failure.message}；未分类文章仍可阅读"
                    break
                }
            }
        } finally { classificationLock.unlock() }
    }

    private fun classifyInBackground() {
        appScope.launch {
            try { classifyPending() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* No hidden retry and no content-bearing error logging. */ }
        }
    }
    private suspend fun applyTopic(id: String, topic: String?, subtopic: String?) {
        val current = article(id) ?: return
        if (current.topicOrigin == "feed") return
        putArticle(current.copy(topic = topic, subtopic = subtopic, topicOrigin = if (topic == null) "ai_unknown" else "ai_title"))
    }
    private fun titleHash(article: Article) = sha256(article.title + "\u0000" + article.sourceName)
    private fun inputSignature(capture: Capture) = VocabularyCardInput.analysisSignature(capture)
    private fun rememberAnalysis(cacheKey: String, signature: String, result: AnalysisResult) {
        analysisCache[cacheKey] = result.copy(inputSignature = signature)
        if (analysisCache.size > 40) analysisCache.remove(analysisCache.keys.first())
    }
    private suspend fun cardMatches(card: Card, capture: Capture): Boolean {
        val result = captureAnalysisStore.available(capture) as? CaptureAnalysisAvailability.ExistingCard
        return result?.card?.id == card.id && result.matchesInput
    }
    private fun safeFailure(e: Exception): ApiFailure = when (e) {
        is ApiFailure -> e
        is IllegalArgumentException -> ApiFailure("invalid_input", "请检查目标词和原句后重试")
        else -> ApiFailure("request_failed", "操作未完成，请稍后重试；捕获已保留")
    }
    private suspend fun currentSettings() = dao.preference("settings")?.let { json.decodeFromString<AppSettings>(it.data) } ?: AppSettings()
    private suspend fun allSources() = dao.allSources().map { json.decodeFromString<Source>(it.data) }
    private suspend fun source(id: String) = dao.source(id)?.let { json.decodeFromString<Source>(it.data) }
    private suspend fun article(id: String) = dao.article(id)?.let { json.decodeFromString<Article>(it.data) }
    private suspend fun putSource(source: Source) = dao.putSource(SourceRow(source.id, json.encodeToString(source)))
    private suspend fun putArticle(article: Article) = dao.putArticle(article.row())
    private suspend fun putJob(job: AiJob) = dao.putJob(JobRow(job.id, job.status, job.updatedAt, json.encodeToString(job)))
    private fun Article.row() = ArticleRow(id, url, publishedAt ?: cachedAt, json.encodeToString(this))
    private fun Card.row() = CardRow(id, captureId, state.dueAt, state.reps, updatedAt, json.encodeToString(this))

    companion object {
        val seedSources: List<Source> = SourceCatalog.sources
    }
}
