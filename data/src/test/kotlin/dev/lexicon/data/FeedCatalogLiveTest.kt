package dev.lexicon.data

import dev.lexicon.domain.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Fetches official feed XML only. Keeps metadata receipts; never stores downloaded bodies or calls AI. */
class FeedCatalogLiveTest {
    @Test fun productionParserAcrossOfficialCatalog() = runBlocking {
        assumeTrue("Live feed requests require explicit opt-in", System.getenv("LEXICON_FEED_LIVE") == "1")
        val reportFile = File(requireNotNull(System.getenv("LEXICON_FEED_REPORT")) { "Missing feed report path" })
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build()
        val started = Instant.now()
        val gate = Semaphore(3)
        val outcomes = try {
            SourceCatalog.sources.map { source -> async(Dispatchers.IO) {
                gate.withPermit { checkSource(client, source) }
            } }.awaitAll()
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        val failures = outcomes.count { it["passed"]?.jsonPrimitive?.booleanOrNull != true }
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(Json { prettyPrint = true }.encodeToString(buildJsonObject {
            put("kind", "production-FeedParser-live-catalog")
            put("started_at_utc", started.toString())
            put("completed_at_utc", Instant.now().toString())
            put("catalog_size", SourceCatalog.sources.size)
            put("passed", outcomes.size - failures)
            put("failed", failures)
            put("scope", "official feed XML only; no article page requests; no AI requests")
            putJsonArray("sources") { outcomes.forEach(::add) }
        }))
        check(failures == 0) { "$failures feed checks failed; independent results are in the metadata report" }
    }

    private fun checkSource(client: OkHttpClient, source: Source): JsonObject {
        val fields = linkedMapOf<String, JsonElement>("id" to JsonPrimitive(source.id), "url" to JsonPrimitive(source.feedUrl))
        val started = System.nanoTime()
        try {
            client.newCall(Request.Builder().url(source.feedUrl)
                .header("User-Agent", "LexiconIDE/1.0 (personal RSS reader)").build()).execute().use { response ->
                fields["http_status"] = JsonPrimitive(response.code)
                fields["final_url"] = JsonPrimitive(response.request.url.toString())
                fields["content_type"] = JsonPrimitive(response.header("Content-Type").orEmpty())
                if (!response.isSuccessful) {
                    fields["passed"] = JsonPrimitive(false)
                    fields["error"] = JsonPrimitive("http_${response.code}")
                } else {
                    val xml = response.boundedBody(12_000_000)
                    fields["root"] = JsonPrimitive(Jsoup.parse(xml, source.feedUrl, Parser.xmlParser()).children().firstOrNull()?.tagName().orEmpty())
                    val entries = FeedParser.parse(xml, source.feedUrl)
                    fields["entry_count"] = JsonPrimitive(entries.size)
                    fields["date_present_count"] = JsonPrimitive(entries.count { it.publishedAt != null })
                    fields["title_present_count"] = JsonPrimitive(entries.count { it.title.isNotBlank() })
                    fields["summary_max_length"] = JsonPrimitive(entries.maxOfOrNull { it.summary.length } ?: 0)
                    fields["passed"] = JsonPrimitive(entries.isNotEmpty())
                    if (entries.isEmpty()) fields["error"] = JsonPrimitive("empty_feed")
                }
            }
        } catch (e: Exception) {
            fields["passed"] = JsonPrimitive(false)
            fields["error"] = JsonPrimitive(if (e is IllegalArgumentException) "invalid_feed" else "transport_${e.javaClass.simpleName}")
        }
        fields["elapsed_ms"] = JsonPrimitive((System.nanoTime() - started) / 1_000_000)
        return JsonObject(fields)
    }
}
