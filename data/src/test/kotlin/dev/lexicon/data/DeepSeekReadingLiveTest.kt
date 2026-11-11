package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Explicitly opt-in, three synthetic-material completions at most; no reading or dictionary corpus. */
class DeepSeekReadingLiveTest {
    @Test fun analyzeRewriteAndBackgroundUsingProductionService() = runBlocking {
        assumeTrue("Paid reading AI check requires opt-in", System.getenv("LEXICON_READING_LIVE") == "1")
        val reportFile = File(requireNotNull(System.getenv("LEXICON_READING_LIVE_REPORT")) { "Missing report path" })
        val keyFile = File(requireNotNull(System.getenv("LEXICON_API_FILE")) { "Missing API file path" })
        val key = keyFile.readText().trim().removePrefix("Bearer ").trim()
        require(Regex("sk-[A-Za-z0-9_-]{8,200}").matches(key)) { "API file must contain one key" }
        val attempts = AtomicInteger()
        val allocatedTokens = AtomicInteger()
        val calls = mutableListOf<JsonObject>()
        val outcomes = mutableListOf<JsonObject>()
        val started = Instant.now()
        val json = Json { prettyPrint = true; encodeDefaults = true }
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(105, TimeUnit.SECONDS).retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                val count = attempts.incrementAndGet()
                if (count > 3) throw IOException("Live request budget reached")
                val buffer = Buffer(); chain.request().body?.writeTo(buffer)
                val configured = Json.parseToJsonElement(buffer.readUtf8()).jsonObject.getValue("max_tokens").jsonPrimitive.int
                if (allocatedTokens.addAndGet(configured) > 12_000) throw IOException("Live output budget reached")
                val before = System.nanoTime()
                val response = chain.proceed(chain.request())
                val parsed = runCatching { Json.parseToJsonElement(response.peekBody(2_000_000).string()).jsonObject }.getOrNull()
                calls += buildJsonObject {
                    put("request", count); put("http_status", response.code); put("elapsed_ms", (System.nanoTime() - before) / 1_000_000)
                    put("max_output_tokens", configured)
                    parsed?.get("model")?.jsonPrimitive?.contentOrNull?.let { put("model", it.take(150)) }
                    parsed?.get("usage")?.jsonObject?.let { usage -> putJsonObject("usage") {
                        listOf("prompt_tokens", "completion_tokens", "total_tokens").forEach { name ->
                            usage[name]?.jsonPrimitive?.longOrNull?.let { put(name, it) }
                        }
                    } }
                }
                response
            }.build()
        val ai = DeepSeekReadingAi({ key }, client, sourceAllowed = { it.sourceIds == listOf("synthetic") }, maxSchemaRepairs = 0)
        val text = """A city council is testing whether trees can make a school courtyard more comfortable during hot weather. The team calls the extra warmth of built-up places an urban heat island. This synthetic example describes a proposed study, not results from a real city.

            The researchers plan to compare shaded and unshaded areas at the same times of day. They will record temperature and ask students how comfortable they feel. They expect shade to help, but differences in wind and ground surfaces could affect the observations.

            Before recommending a wider project, the team will examine both the measurements and the uncertainty. A lower temperature at one location would not show that every planting scheme works equally well. The council must also consider maintenance costs and access to water.
        """.trimIndent()
        val article = Article("synthetic-reading-live", "synthetic", listOf("synthetic"), "A synthetic school courtyard study",
            "https://example.org/synthetic-study", "", sourceName = "Lexicon synthetic integration material")
        val body = ArticleBody(article.id, text, sha256(text), 0, Long.MAX_VALUE, "Self-authored synthetic fixture")
        var failure: String? = null
        try {
            val study = ai.analyze(article, body)
            check(study.paragraphs.size == 3 && study.nodes.isNotEmpty() && study.questions.isNotEmpty())
            outcomes += buildJsonObject { put("task", "whole_article_study"); put("passed", true); put("result", Json.parseToJsonElement(json.encodeToString(study))) }
            val rewritten = ai.assist(StudyRequest("rewrite", text, targetLevel = "B1", title = article.title, source = article.sourceName))
            check(rewritten.score == null)
            outcomes += buildJsonObject { put("task", "B1_rewrite"); put("passed", true); put("result", Json.parseToJsonElement(json.encodeToString(rewritten))) }
            val background = ai.assist(StudyRequest("background", "urban heat island", context = text, title = article.title, source = article.sourceName))
            check(background.retrievalStatus == "retrieved" && background.sources.isNotEmpty() && background.citedSourceIds.isNotEmpty())
            outcomes += buildJsonObject { put("task", "background"); put("passed", true); put("result", Json.parseToJsonElement(json.encodeToString(background))) }
        } catch (e: ApiFailure) { failure = e.code }
        catch (_: Exception) { failure = "transport_or_contract_failure" }
        finally {
            reportFile.parentFile?.mkdirs()
            reportFile.writeText(json.encodeToString(buildJsonObject {
                put("kind", "production-DeepSeekReadingAi-live-smoke"); put("started_at_utc", started.toString())
                put("completed_at_utc", Instant.now().toString()); put("status", if (failure == null) "passed" else "failed")
                put("failure_code", failure); put("attempted_completions", attempts.get()); put("maximum_completions", 3)
                put("allocated_max_output_tokens", allocatedTokens.get()); put("max_total_output_tokens", 12_000)
                put("automatic_schema_repairs", 0); put("input_scope", "self-authored synthetic article only; no user articles or dictionary content")
                put("semantic_quality", "requires manual review; schema success is not an educational or CEFR validation")
                putJsonArray("calls") { calls.forEach(::add) }; putJsonArray("outcomes") { outcomes.forEach(::add) }
            }))
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
        check(failure == null && outcomes.size == 3) { "Reading AI live check did not complete; see sanitized report" }
    }
}
