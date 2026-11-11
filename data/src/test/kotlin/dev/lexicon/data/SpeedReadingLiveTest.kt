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

/** Opt-in only: one paid completion, self-authored text, no automatic repair or transport retry. */
class SpeedReadingLiveTest {
    @Test fun aRealCompletionPreservesSourceRangesAndGroundsOneQuestion() = runBlocking {
        assumeTrue("Paid speed-reading check requires explicit opt-in", System.getenv("LEXICON_SPEED_LIVE") == "1")
        val report = File(requireNotNull(System.getenv("LEXICON_SPEED_LIVE_REPORT")))
        val key = File(requireNotNull(System.getenv("LEXICON_API_FILE"))).readText().trim().removePrefix("Bearer ").trim()
        require(Regex("sk-[A-Za-z0-9_-]{8,200}").matches(key)) { "API file must contain one key" }
        val count = AtomicInteger(); val allocated = AtomicInteger()
        val calls = mutableListOf<JsonObject>(); val started = Instant.now()
        val diagnostics = mutableListOf<SpeedSchemaDiagnostic>()
        val json = Json { prettyPrint = true; encodeDefaults = true }
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(75, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                val attempt = count.incrementAndGet()
                if (attempt > 1) throw IOException("Speed-reading paid request budget reached")
                val buffer = Buffer(); chain.request().body?.writeTo(buffer)
                val budget = Json.parseToJsonElement(buffer.readUtf8()).jsonObject.getValue("max_tokens").jsonPrimitive.int
                if (allocated.addAndGet(budget) > 6000) throw IOException("Speed-reading output allocation budget reached")
                val before = System.nanoTime(); val response = chain.proceed(chain.request())
                val parsed = runCatching { Json.parseToJsonElement(response.peekBody(1_000_000).string()).jsonObject }.getOrNull()
                calls += buildJsonObject {
                    put("request", attempt); put("http_status", response.code)
                    put("elapsed_ms", (System.nanoTime() - before) / 1_000_000); put("max_output_tokens", budget)
                    parsed?.get("model")?.jsonPrimitive?.contentOrNull?.let { put("model", it.take(150)) }
                    parsed?.get("usage")?.jsonObject?.let { usage -> putJsonObject("usage") {
                        listOf("prompt_tokens", "completion_tokens", "total_tokens").forEach { field ->
                            usage[field]?.jsonPrimitive?.longOrNull?.let { put(field, it) }
                        }
                    } }
                }
                response
            }.build()
        val text = "The local library opened a quiet reading room on Monday. Visitors can use it without booking. The council will review visitor feedback after three months before deciding whether to extend the opening hours."
        val article = Article("speed-live-self-authored", "self-authored", listOf("self-authored"),
            "A new reading room", "https://example.org/self-authored", "", sourceName = "Self-authored test")
        val body = ArticleBody(article.id, text, sha256(text), 0, Long.MAX_VALUE, "Self-authored")
        val proposal = SpeedChunker.plan(article.id, body.contentHash, text, SpeedGranularity.L2, 0, 1, 0)
        var outcome: JsonObject? = null
        var failure: String? = null
        try {
            val result = DeepSeekSpeedReadingAi({ key }, client, maxSchemaRepairs = 0,
                onSchemaDiagnostic = diagnostics::add).refine(article, body, proposal)
            SpeedChunker.validate(result, text)
            check(result.origin == "ai" && result.questions.size == 1 && result.model.isNotBlank())
            outcome = buildJsonObject {
                put("model", result.model); put("origin", result.origin); put("source_characters", text.length)
                put("source_words", SpeedChunker.wordCount(text)); put("chunk_count", result.chunks.size)
                put("question_count", result.questions.size)
                putJsonArray("chunks") { result.chunks.forEach { chunk -> add(buildJsonObject {
                    put("start", chunk.start); put("end", chunk.end); put("sentence", chunk.sentence)
                    put("word_count", chunk.wordCount); put("reason", chunk.reason)
                    put("quote", text.substring(chunk.start, chunk.end))
                }) } }
                putJsonArray("questions") { result.questions.forEach { question -> add(buildJsonObject {
                    put("prompt", question.prompt); putJsonArray("options") { question.options.forEach { add(it) } }
                    put("answer_index", question.answerIndex); put("explanation", question.explanation)
                    put("evidence_start", question.evidenceStart); put("evidence_end", question.evidenceEnd)
                    put("evidence_quote", text.substring(question.evidenceStart, question.evidenceEnd))
                }) } }
            }
        } catch (e: ApiFailure) { failure = e.code }
        catch (_: Exception) { failure = "transport_or_contract_failure" }
        finally {
            report.parentFile?.mkdirs()
            report.writeText(json.encodeToString(buildJsonObject {
                put("kind", "production-DeepSeekSpeedReadingAi-live-smoke")
                put("started_at_utc", started.toString()); put("completed_at_utc", Instant.now().toString())
                put("status", if (failure == null && outcome != null) "passed" else "failed"); put("failure_code", failure)
                put("maximum_paid_completions", 1); put("attempted_paid_completions", count.get())
                put("max_allocated_output_tokens", 6000); put("allocated_output_tokens", allocated.get())
                put("automatic_schema_repairs", 0)
                put("request_protocol_version", SpeedReadingProtocol.VERSION); put("stored_plan_version", 1)
                put("schema_diagnostics", Json.parseToJsonElement(json.encodeToString(diagnostics)))
                put("scope", "One self-authored paragraph; no user article, dictionary corpus, key or raw request is recorded.")
                put("semantic_quality", "Exact source coverage and question structure are client-validated. Question quality and natural chunking require manual review; this is not an online success-rate estimate.")
                putJsonArray("calls") { calls.forEach(::add) }; put("outcome", outcome ?: JsonNull)
            }) + "\n")
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
        check(failure == null && outcome != null && count.get() == 1) { "Speed-reading live check did not pass; see sanitized report" }
    }
}
