package dev.lexicon.data

import dev.lexicon.domain.BackgroundSource
import dev.lexicon.domain.BackgroundCandidate
import dev.lexicon.domain.BackgroundProviderOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Retrieval outcomes never imply that a model has verified the evidence. */
internal data class BackgroundRetrieval(val sources: List<BackgroundSource>, val status: String, val detail: String,
    val candidates: List<BackgroundCandidate> = emptyList(),
    val providerOutcomes: List<BackgroundProviderOutcome> = emptyList())

internal fun interface BackgroundRetriever {
    /** Only term is sent to the provider. Context is used locally to rank retrieved candidates. */
    suspend fun retrieve(term: String, context: String): BackgroundRetrieval
}

internal object BackgroundEvidence {
    const val CACHE_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
    fun validateSources(sources: List<BackgroundSource>) {
        require(sources.size in 1..3 && sources.map { it.id } == sources.indices.map { "S${it + 1}" })
        require(sources.map { it.url }.distinct().size == sources.size)
        sources.forEach { source ->
            val url = source.url.toHttpUrl()
            require(url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty())
            if (source.provider != "Wikipedia (en)") {
                OfficialBackgroundCatalog.validateSource(source)
                return@forEach
            }
            require(url.host == "en.wikipedia.org")
            require(url.encodedPath == "/w/index.php" && url.queryParameter("curid")?.toLongOrNull()?.let { it > 0 } == true)
            require(source.revisionId == url.queryParameter("oldid"))
            require(source.revisionId?.let { revision -> revision.toLongOrNull()?.let { it > 0 } == true } != false)
            require(source.title.isNotBlank() && source.title.length <= 251 && source.excerpt.length in 80..1601)
            require(source.retrievedAt >= 0 && source.provider == "Wikipedia (en)" && source.license == "CC BY-SA 4.0")
        }
    }
}

/** Public MediaWiki API; bounded introductions, no scripts, cookies, credentials or automatic retries. */
internal class WikipediaBackgroundRetriever(
    private val clock: () -> Long = System::currentTimeMillis,
    endpoint: String = "https://en.wikipedia.org/w/api.php",
    client: OkHttpClient = OkHttpClient(),
) : BackgroundRetriever {
    private val endpoint = endpoint.toHttpUrl().also {
        require((it.scheme == "https" && it.host == "en.wikipedia.org" && it.port == 443 && it.encodedPath == "/w/api.php") ||
            (it.scheme == "http" && it.host in setOf("localhost", "127.0.0.1", "::1"))) { "Unsupported retrieval endpoint" }
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null)
    }
    // Start a new builder: inheriting the learning client's interceptors could expose API credentials.
    private val http = OkHttpClient.Builder().dispatcher(client.dispatcher).connectionPool(client.connectionPool)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).build()
    @Volatile private var retryAfter = 0L

    override suspend fun retrieve(term: String, context: String): BackgroundRetrieval {
        val query = term.trim().replace(Regex("\\s+"), " ")
        require(query.isNotBlank() && query.length <= MAX_TERM && query.none { it.isISOControl() }) { "背景检索词需为 1–160 个字符" }
        if (clock() < retryAfter) return BackgroundRetrieval(emptyList(), "unavailable", "检索服务请求过多，请稍后手动重试。")
        // Exact titles resolve redirects to the conceptual article. Search augments
        // this evidence, rather than replacing a concept with a highly ranked unrelated city's page.
        val exact = fetch(query, exact = true)
        if (exact.error != null) return BackgroundRetrieval(emptyList(), "unavailable", exact.error)
        val search = fetch(query, exact = false)
        val result = runCatching { parse(exact.pages + search.pages, query, context.take(2000), clock(),
            exact.pages.mapNotNull { it.jsonObject["pageid"]?.jsonPrimitive?.longOrNull }.toSet()) }.getOrElse {
                return BackgroundRetrieval(emptyList(), "unavailable", "检索资料结构无效，没有生成背景解释。")
            }
        return if (result.sources.isEmpty() && search.error != null) BackgroundRetrieval(emptyList(), "unavailable", search.error) else result
    }

    private data class Pages(val pages: List<JsonElement> = emptyList(), val error: String? = null)
    private suspend fun fetch(query: String, exact: Boolean): Pages {
        val url = endpoint.newBuilder().addQueryParameter("action", "query").addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2").apply {
                if (exact) { addQueryParameter("titles", query); addQueryParameter("redirects", "1") }
                else { addQueryParameter("generator", "search"); addQueryParameter("gsrsearch", query)
                    addQueryParameter("gsrnamespace", "0"); addQueryParameter("gsrlimit", "5") }
            }
            .addQueryParameter("prop", "extracts|revisions|pageprops").addQueryParameter("exintro", "1")
            .addQueryParameter("explaintext", "1").addQueryParameter("exchars", "1200").addQueryParameter("exlimit", "5")
            .addQueryParameter("rvprop", "ids").addQueryParameter("ppprop", "disambiguation").addQueryParameter("maxlag", "5").build()
        return try {
            val request = Request.Builder().url(url).header("Accept", "application/json")
                .header("User-Agent", USER_AGENT).build()
            http.newCall(request).awaitBackground().use { response ->
                if (response.code == 429 || response.code == 503) {
                    retryAfter = retryDeadline(response.header("Retry-After"), clock())
                    return@use Pages(error = "检索服务暂时繁忙，请稍后手动重试。")
                }
                if (!response.isSuccessful) return@use Pages(error = "外部检索暂不可用，未生成背景解释。")
                val body = response.body ?: return@use Pages(error = "检索服务未返回证据。")
                if (body.contentType()?.subtype?.contains("json", ignoreCase = true) != true)
                    return@use Pages(error = "检索服务返回的内容格式无效。")
                if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("Retrieval response too large")
                val bytes = body.byteStream().readNBytes(MAX_RESPONSE_BYTES + 1)
                if (bytes.size > MAX_RESPONSE_BYTES) throw IOException("Retrieval response too large")
                val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                if (root["error"] != null) return@use Pages(error = "检索服务未能完成查询，请稍后重试。")
                val pages = root["query"]?.jsonObject?.get("pages")?.jsonArray.orEmpty()
                require(pages.size <= 10)
                Pages(pages)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Pages(error = "外部检索未完成，请联网后手动重试；没有生成无证据解释。") }
    }

    private fun parse(pages: List<JsonElement>, term: String, context: String, now: Long, exactIds: Set<Long>): BackgroundRetrieval {
        val terms = tokens(term); val contextTokens = tokens(context) - terms - STOP_WORDS
        val candidates = pages.mapNotNull { entry ->
            val page = entry.jsonObject
            if (page["missing"] != null || page["pageprops"]?.jsonObject?.containsKey("disambiguation") == true) return@mapNotNull null
            val pageId = page["pageid"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
            val title = plain(page["title"]?.jsonPrimitive?.content.orEmpty(), 250)
            val excerpt = plain(page["extract"]?.jsonPrimitive?.content.orEmpty(), MAX_EXCERPT)
            if (title.isBlank() || excerpt.length < 80) return@mapNotNull null
            val revision = page["revisions"]?.jsonArray?.firstOrNull()?.jsonObject?.get("revid")?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
            val titleWords = tokens(title); val contentWords = tokens(excerpt)
            val overlap = terms.count { it in titleWords || it in contentWords }
            if (overlap == 0 && pageId !in exactIds) return@mapNotNull null
            val score = (if (pageId in exactIds) 200 else 0) + (if (title.equals(term, true)) 100 else 0) + terms.count { it in titleWords } * 10 +
                overlap * 2 + contextTokens.count { it in titleWords || it in contentWords }.coerceAtMost(12)
            val rank = page["index"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE
            val sourceUrl = "https://en.wikipedia.org/w/index.php".toHttpUrl().newBuilder()
                .addQueryParameter("curid", pageId.toString()).apply { if (revision != null) addQueryParameter("oldid", revision.toString()) }.build().toString()
            Triple(BackgroundSource("", title, sourceUrl, excerpt, now, "Wikipedia (en)", revision?.toString()), score, rank)
        }.distinctBy { it.first.url.toHttpUrl().queryParameter("curid") }.sortedWith(compareByDescending<Triple<BackgroundSource, Int, Int>> { it.second }.thenBy { it.third })
        val hasExactConcept = candidates.any { it.first.url.toHttpUrl().queryParameter("curid")?.toLongOrNull() in exactIds }
        val contextQualifiers = qualifierTokens(context)
        val termQualifiers = qualifierTokens(term)
        val relevant = candidates.filter { candidate ->
            val source = candidate.first
            if (!hasExactConcept || source.url.toHttpUrl().queryParameter("curid")?.toLongOrNull() in exactIds) true
            else {
                // A generic lookup must not introduce an unmentioned city, country or other scope.
                // For example, US family-dinner context supports Thanksgiving (United States) and
                // Thanksgiving dinner, but city council alone does not support Vancouver City Council.
                val qualifiers = qualifierTokens(source.title) - termQualifiers
                qualifiers.all { it in contextQualifiers }
            }
        }
        val sources = relevant.take(3).mapIndexed { index, candidate -> candidate.first.copy(id = "S${index + 1}") }
        return if (sources.isEmpty()) BackgroundRetrieval(emptyList(), "no_results", "没有找到足够匹配的外部证据。可调整检索词后重试。")
        else BackgroundRetrieval(sources, "retrieved", "已检索 Wikipedia 条目导言；资料仅供背景阅读，请核对来源及语境。")
    }

    private fun tokens(text: String) = Regex("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*")
        .findAll(text.lowercase(Locale.ROOT)).map { it.value }.toSet()
    private fun qualifierTokens(text: String): Set<String> {
        val normalized = text
            .replace(Regex("\\bUnited States(?: of America)?\\b", RegexOption.IGNORE_CASE), " geographyusa ")
            .replace(Regex("\\bAmerican\\b", RegexOption.IGNORE_CASE), " geographyusa ")
            .replace(Regex("\\bU\\.?S\\.?(?:A\\.?)?\\b"), " geographyusa ")
            .replace(Regex("\\bUnited Kingdom\\b", RegexOption.IGNORE_CASE), " geographyuk ")
            .replace(Regex("\\bU\\.?K\\.?\\b"), " geographyuk ")
        return tokens(normalized) - STOP_WORDS
    }
    private fun plain(value: String, limit: Int): String = Jsoup.parse(value).apply { select("script,style,noscript,iframe").remove() }
        .text().replace(Regex("[\\p{Cc}&&[^\\n\\t]]"), " ").trim().let {
            if (it.length <= limit) it else it.take(if (it[limit - 1].isHighSurrogate()) limit - 1 else limit).trimEnd() + "…"
        }
    private fun retryDeadline(header: String?, now: Long): Long {
        val seconds = header?.trim()?.toLongOrNull()
        val parsed = if (seconds != null) now + seconds.coerceIn(1, 86_400) * 1000
            else runCatching { ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        return parsed?.coerceIn(now + 1000, now + 86_400_000) ?: now + 60_000
    }
    companion object {
        const val MAX_TERM = 160
        const val MAX_EXCERPT = 1600
        const val MAX_RESPONSE_BYTES = 256 * 1024
        const val USER_AGENT = "LexiconIDE/1.1 (personal educational reader; contact: haoweixu@stu.pku.edu.cn)"
        private val STOP_WORDS = setOf("a", "an", "the", "and", "or", "of", "in", "on", "at", "to", "is", "was", "are", "for", "with", "this", "that", "it")
    }
}

internal suspend fun Call.awaitBackground(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
