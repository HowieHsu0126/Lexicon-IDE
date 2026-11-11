package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Audited concept entry points, not a general URL fetcher or a claim to cover every institution. */
internal object OfficialBackgroundCatalog {
    data class Entry(val id: String, val aliases: Set<String>, val url: String, val provider: String,
        val heading: String, val selector: String, val expectedPhrase: String)
    val entries = listOf(
        Entry("us-electoral-college", setOf("electoral college", "us electoral college", "u.s. electoral college"),
            "https://www.archives.gov/electoral-college/about", "US National Archives",
            "What is the Electoral College?", "#block-system-main", "Electoral College"),
        Entry("fomc", setOf("fomc", "federal open market committee", "federal open market committee (fomc)"),
            "https://www.federalreserve.gov/faqs/about_12844.htm", "Federal Reserve Board",
            "What is the FOMC and when does it meet?", "#content", "Federal Open Market Committee"),
    )
    const val LICENSE = "官方网页节选；使用条件以来源网站为准"
    private fun normalized(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    fun match(term: String): Entry? = entries.singleOrNull { normalized(term) in it.aliases }
    fun validateSource(source: BackgroundSource) {
        val entry = entries.singleOrNull { it.url == source.url && it.provider == source.provider }
        requireNotNull(entry) { "Unregistered official source" }
        require(source.title == entry.heading && source.license == LICENSE && source.revisionId == null)
        require(source.excerpt.length in 80..1600 && source.excerpt.contains(entry.expectedPhrase, true))
        require(source.retrievedAt >= 0 && source.contentHash == sha256(source.excerpt))
    }
}

/** One bounded HTTPS document per exact concept. No search query, reading context or Key is sent. */
internal class OfficialBackgroundRetriever(
    private val clock: () -> Long = System::currentTimeMillis,
    client: OkHttpClient = OkHttpClient(),
    private val testBase: HttpUrl? = null,
) : BackgroundRetriever {
    init { require(testBase == null || (testBase.scheme == "http" && testBase.host in setOf("localhost", "127.0.0.1", "::1") &&
        testBase.username.isEmpty() && testBase.password.isEmpty() && testBase.query == null && testBase.fragment == null)) }
    private val http = OkHttpClient.Builder().dispatcher(client.dispatcher).connectionPool(client.connectionPool)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES).build()
    private val cooldown = java.util.concurrent.ConcurrentHashMap<String, Long>()
    override suspend fun retrieve(term: String, context: String): BackgroundRetrieval {
        require(term.trim().length in 1..160 && term.none { it.isISOControl() })
        val entry = OfficialBackgroundCatalog.match(term) ?: return BackgroundRetrieval(emptyList(), "no_results", "此概念暂无已核验官方入口。",
            providerOutcomes = listOf(BackgroundProviderOutcome("官方资料目录", "skipped", "当前目录覆盖 Electoral College 与 FOMC。")))
        fun unavailable(detail: String) = BackgroundRetrieval(emptyList(), "unavailable", detail,
            providerOutcomes = listOf(BackgroundProviderOutcome(entry.provider, "unavailable", detail)))
        if (clock() < (cooldown[entry.provider] ?: 0L)) return unavailable("官方资料暂时繁忙，请稍后刷新。")
        val url = testBase?.resolve(entry.url.toHttpUrl().encodedPath) ?: entry.url.toHttpUrl()
        return try {
            http.newCall(Request.Builder().url(url).header("Accept", "text/html")
                .header("User-Agent", WikipediaBackgroundRetriever.USER_AGENT).build()).awaitBackground().use { response ->
                if (response.code in setOf(429, 503)) {
                    val now = clock()
                    val retry = response.header("Retry-After")
                    val until = retry?.toLongOrNull()?.let { now + it.coerceIn(1, 86400) * 1000 }
                        ?: runCatching { ZonedDateTime.parse(retry, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                    cooldown[entry.provider] = until?.coerceIn(now + 1000, now + 86_400_000) ?: now + 60_000
                    return@use unavailable("官方资料暂时繁忙，请稍后刷新。")
                }
                if (!response.isSuccessful) return@use unavailable("官方资料暂不可用，其他来源仍可查看。")
                val body = response.body ?: return@use unavailable("官方资料未返回正文。")
                if (body.contentType()?.subtype !in setOf("html", "xhtml+xml")) return@use unavailable("官方资料格式变化，未采用此响应。")
                if (body.contentLength() > MAX_BYTES) throw IOException("Oversized source")
                val bytes = body.byteStream().readNBytes(MAX_BYTES + 1)
                if (bytes.size > MAX_BYTES) throw IOException("Oversized source")
                val source = parse(entry, bytes.toString(Charsets.UTF_8), clock())
                BackgroundRetrieval(listOf(source), "retrieved", "已取得官方概念说明。",
                    listOf(BackgroundCandidate(entry.id, source.title, source.provider, listOf("S1"))),
                    listOf(BackgroundProviderOutcome(entry.provider, "retrieved", "官方概念说明；不是当前新闻事实核查。")))
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { unavailable("官方资料检索未完成，其他来源仍可查看。") }
    }
    internal fun parse(entry: OfficialBackgroundCatalog.Entry, html: String, now: Long): BackgroundSource {
        val document = Jsoup.parse(html)
        document.select("script,style,noscript,iframe,nav,footer,header,form,aside").remove()
        val main = requireNotNull(document.selectFirst(entry.selector)) { "Official layout changed" }
        val heading = main.select("h1,h2,h3").single { it.text().trim() == entry.heading }
        val paragraphs = mutableListOf<String>()
        var element = heading.nextElementSibling()
        while (element != null && paragraphs.size < 2) {
            if (element.tagName() in setOf("h1", "h2", "h3")) break
            if (element.tagName() == "p") {
                val text = element.text().replace(Regex("[\\p{Cc}&&[^\\n\\t]]"), " ").trim()
                if (text.isNotBlank()) {
                    if (paragraphs.sumOf(String::length) + text.length + paragraphs.size * 2 > 1600) break
                    paragraphs += text
                }
            }
            element = element.nextElementSibling()
        }
        val excerpt = paragraphs.joinToString("\n\n")
        return BackgroundSource("S1", entry.heading, entry.url, excerpt, now, entry.provider,
            license = OfficialBackgroundCatalog.LICENSE, contentHash = sha256(excerpt)).also(OfficialBackgroundCatalog::validateSource)
    }
    companion object { private const val MAX_BYTES = 512 * 1024 }
}

/** Independent providers; an unavailable official page cannot discard readable encyclopedia material. */
internal class CompositeBackgroundRetriever(
    private val official: BackgroundRetriever = OfficialBackgroundRetriever(),
    private val encyclopedia: BackgroundRetriever = WikipediaBackgroundRetriever(),
) : BackgroundRetriever {
    override suspend fun retrieve(term: String, context: String): BackgroundRetrieval = coroutineScope {
        suspend fun bounded(provider: BackgroundRetriever, label: String) = try { provider.retrieve(term, context).also {
            require(it.status in setOf("retrieved", "no_results", "unavailable"))
            if (it.sources.isNotEmpty()) { require(it.status == "retrieved"); BackgroundEvidence.validateSources(it.sources) }
            else require(it.status != "retrieved")
        } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { BackgroundRetrieval(emptyList(), "unavailable", "检索未完成。",
            providerOutcomes = listOf(BackgroundProviderOutcome(label, "unavailable", "检索未完成。"))) }
        val a = async { bounded(official, "官方资料目录") }
        val b = async { bounded(encyclopedia, "Wikipedia (en)") }
        val responses = listOf(a.await(), b.await())
        val sources = responses.flatMap { it.sources }.distinctBy { it.url }.take(3).mapIndexed { index, source -> source.copy(id = "S${index + 1}") }
        if (sources.isNotEmpty()) BackgroundEvidence.validateSources(sources)
        // Each retrieved document is a selectable conceptual entry. Unconfirmed meanings are never combined.
        val candidates = sources.mapIndexed { index, source -> BackgroundCandidate("concept-${index + 1}", source.title,
            source.provider, listOf(source.id)) }
        val outcomes = responses.flatMapIndexed { index, response -> response.providerOutcomes.ifEmpty {
            listOf(BackgroundProviderOutcome(if (index == 0) "官方资料目录" else "Wikipedia (en)", response.status, response.detail))
        } }
        val status = if (sources.isNotEmpty()) "retrieved" else if (outcomes.any { it.status == "unavailable" }) "unavailable" else "no_results"
        BackgroundRetrieval(sources, status, if (sources.isNotEmpty()) "请选择与原文相符的概念。" else "暂未取得可用资料，可修改概念后重试。", candidates, outcomes)
    }
}
