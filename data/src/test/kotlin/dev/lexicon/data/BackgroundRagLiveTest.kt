package dev.lexicon.data

import dev.lexicon.domain.StudyRequest
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

/** Explicit opt-in. At most two public-term RAG requests; city-only recheck is bounded to one, with no automatic repair. */
class BackgroundRagLiveTest {
    @Test fun publicTermsRetrieveRealEvidenceAndGenerateCitedBackground() = runBlocking {
        assumeTrue("Paid RAG test requires explicit opt-in", System.getenv("LEXICON_BACKGROUND_LIVE") == "1")
        val report = File(requireNotNull(System.getenv("LEXICON_BACKGROUND_LIVE_REPORT")))
        val key = File(requireNotNull(System.getenv("LEXICON_API_FILE"))).readText().trim().removePrefix("Bearer ").trim()
        require(Regex("sk-[A-Za-z0-9_-]{8,200}").matches(key)) { "API file must contain one key" }
        val maximum = if (System.getenv("LEXICON_BACKGROUND_CITY_ONLY") == "1") 1 else 2
        val count = AtomicInteger(); val allocated = AtomicInteger()
        val calls = mutableListOf<JsonObject>(); val outcomes = mutableListOf<JsonObject>()
        val started = Instant.now(); val json = Json { prettyPrint = true; encodeDefaults = true }
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(75, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS).retryOnConnectionFailure(false).addInterceptor { chain ->
                val attempt = count.incrementAndGet()
                if (attempt > maximum) throw IOException("RAG paid request budget reached")
                val buffer = Buffer(); chain.request().body?.writeTo(buffer)
                val budget = Json.parseToJsonElement(buffer.readUtf8()).jsonObject.getValue("max_tokens").jsonPrimitive.int
                if (allocated.addAndGet(budget) > maximum * 1800) throw IOException("RAG output allocation budget reached")
                val before = System.nanoTime(); val response = chain.proceed(chain.request())
                val parsed = runCatching { Json.parseToJsonElement(response.peekBody(1_000_000).string()).jsonObject }.getOrNull()
                calls += buildJsonObject {
                    put("request", attempt); put("http_status", response.code); put("elapsed_ms", (System.nanoTime() - before) / 1_000_000)
                    put("max_output_tokens", budget)
                    parsed?.get("model")?.jsonPrimitive?.contentOrNull?.let { put("model", it.take(150)) }
                    parsed?.get("usage")?.jsonObject?.let { usage -> putJsonObject("usage") {
                        listOf("prompt_tokens", "completion_tokens", "total_tokens").forEach { field -> usage[field]?.jsonPrimitive?.longOrNull?.let { put(field, it) } }
                    } }
                }
                response
            }.build()
        val ai = DeepSeekReadingAi({ key }, client, maxSchemaRepairs = 0)
        var failure: String? = null
        try {
            val requests = listOf(
                StudyRequest("background", "city council", "A city council is considering a school courtyard project. This is a self-authored example."),
                StudyRequest("background", "Thanksgiving", "The article mentions a family dinner in the United States at Thanksgiving. This is a self-authored example."),
            )
            requests.take(maximum).forEach { request ->
                val result = ai.assist(request)
                check(result.retrievalStatus == "retrieved" && result.sources.isNotEmpty() && result.citedSourceIds.isNotEmpty())
                check(result.model != "retrieval-only" && result.sources.all { it.url.startsWith("https://en.wikipedia.org/") })
                outcomes += buildJsonObject {
                    put("term", request.text); put("passed", true); put("result", Json.parseToJsonElement(json.encodeToString(result)))
                }
            }
        } catch (e: ApiFailure) { failure = e.code }
        catch (_: Exception) { failure = "retrieval_transport_or_contract_failure" }
        finally {
            report.parentFile?.mkdirs()
            report.writeText(json.encodeToString(buildJsonObject {
                put("kind", "production-WikipediaBackgroundRetriever-DeepSeekReadingAi-live-smoke")
                put("started_at_utc", started.toString()); put("completed_at_utc", Instant.now().toString())
                put("status", if (failure == null) "passed" else "failed"); put("failure_code", failure)
                put("maximum_paid_completions", maximum); put("attempted_paid_completions", count.get())
                put("max_allocated_output_tokens", maximum * 1800); put("allocated_output_tokens", allocated.get()); put("automatic_schema_repairs", 0)
                put("scope", "Public terms, self-authored short contexts, real Wikipedia evidence; no user articles or dictionary corpus. No key or raw request is recorded.")
                put("semantic_quality", "Client citation validation only; factual coverage and cultural interpretation require manual source review.")
                putJsonArray("calls") { calls.forEach(::add) }; putJsonArray("outcomes") { outcomes.forEach(::add) }
            }) + "\n")
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
        check(failure == null && outcomes.size == maximum) { "RAG live check did not finish; see sanitized report" }
    }
}
