package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Explicitly opt-in: exercises the production HTTP implementation using synthetic learning material. */
class DeepSeekLiveTest {
    @Test fun productionClientAgainstOfficialService() = runBlocking {
        assumeTrue("Live API calls require explicit opt-in", System.getenv("LEXICON_DEEPSEEK_LIVE") == "1")
        val keyFile = File(requireNotNull(System.getenv("LEXICON_API_FILE")) { "Missing key file path" })
        val reportFile = File(requireNotNull(System.getenv("LEXICON_LIVE_REPORT")) { "Missing report path" })
        val key = keyFile.readText().trim().removePrefix("Bearer ").trim()
        require(Regex("sk-[A-Za-z0-9_-]{8,200}").matches(key)) { "API.txt must contain one API key" }
        val calls = mutableListOf<JsonObject>()
        val outcomes = mutableListOf<JsonObject>()
        val json = Json { prettyPrint = true; encodeDefaults = true }
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(75, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .addInterceptor(Interceptor { chain ->
                val started = System.nanoTime()
                val response = chain.proceed(chain.request())
                val parsed = runCatching { Json.parseToJsonElement(response.peekBody(2_000_000).string()).jsonObject }.getOrNull()
                calls += buildJsonObject {
                    put("path", chain.request().url.encodedPath)
                    put("http_status", response.code)
                    put("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
                    parsed?.get("model")?.jsonPrimitive?.contentOrNull?.let { put("returned_model", it.take(150)) }
                    parsed?.get("usage")?.jsonObject?.let { usage ->
                        putJsonObject("usage") {
                            listOf("prompt_tokens", "completion_tokens", "total_tokens", "prompt_cache_hit_tokens", "prompt_cache_miss_tokens").forEach { field ->
                                usage[field]?.jsonPrimitive?.longOrNull?.let { put(field, it) }
                            }
                        }
                    }
                }
                response
            }).build()
        val service = DeepSeekService({ key }, client)
        var failureCode: String? = null
        try {
            client.newCall(Request.Builder().url("https://api.deepseek.com/models")
                .header("Authorization", "Bearer $key").build()).execute().use { response ->
                if (!response.isSuccessful) throw ApiFailure("models_http_${response.code}", "Model discovery failed")
                val modelIds = Json.parseToJsonElement(response.boundedBody(1_000_000)).jsonObject.getValue("data").jsonArray
                    .map { it.jsonObject.getValue("id").jsonPrimitive.content }
                check("deepseek-flash" in modelIds && "deepseek-v4-pro" in modelIds) { "Required model IDs unavailable" }
                outcomes += buildJsonObject { put("task", "model_discovery"); put("passed", true)
                    putJsonArray("required_models_available") { add("deepseek-flash"); add("deepseek-v4-pro") } }
            }
            val tasks = listOf(
                Capture("live-flash", target = "mitigate", selectedText = "mitigate",
                    sentence = "Urban trees can mitigate the effects of extreme heat by providing shade.",
                    sourceRef = SourceRef(title = "Synthetic urban climate example", source = "Lexicon integration test"),
                    contextOrigin = ContextOrigin.USER_SUPPLIED, createdAt = 1L) to false,
                Capture("live-pro", target = "robust", selectedText = "robust",
                    sentence = "The conclusion remains robust when the analysis uses a different sample.",
                    sourceRef = SourceRef(title = "Synthetic research methods example", source = "Lexicon integration test"),
                    contextOrigin = ContextOrigin.USER_SUPPLIED, createdAt = 1L) to true,
            )
            for ((capture, deep) in tasks) {
                val started = System.nanoTime()
                val result = service.analyze(capture, deep, emptyList())
                ContentValidator.requireValid(result.content)
                outcomes += buildJsonObject {
                    put("task", if (deep) "pro_deep_analysis" else "flash_vocabulary")
                    put("passed", true); put("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
                    put("returned_model", result.model)
                    put("synthetic_target", capture.target)
                    put("synthetic_sentence", capture.sentence)
                    put("validated_result", Json.parseToJsonElement(json.encodeToString(result.content)))
                }
            }
            val inputs = listOf(
                TopicInput("climate", "Researchers test ways to reduce urban heat", "Synthetic test titles"),
                TopicInput("learning", "How school libraries support student learning", "Synthetic test titles"),
                TopicInput("ai", "New AI system detects errors in computer code", "Synthetic test titles"),
            )
            val started = System.nanoTime()
            val classifications = service.classify(inputs)
            outcomes += buildJsonObject {
                put("task", "title_classification"); put("passed", true)
                put("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
                put("input_count", inputs.size)
                putJsonArray("classifications") { classifications.forEach { row -> add(buildJsonObject {
                    put("id", row.id); put("topic", row.topic); put("subtopic", row.subtopic)
                }) } }
            }
        } catch (e: ApiFailure) { failureCode = e.code }
        catch (_: Exception) { failureCode = "transport_or_contract_failure" }
        finally {
            reportFile.parentFile?.mkdirs()
            reportFile.writeText(json.encodeToString(buildJsonObject {
                put("kind", "production-DeepSeekService-live-smoke")
                put("started_with_user_supplied_key", true)
                put("official_base_url", "https://api.deepseek.com")
                put("checked_at_utc", Instant.now().toString())
                put("data", "synthetic vocabulary sentences and titles only")
                put("status", if (failureCode == null) "passed" else "failed")
                put("failure_code", failureCode)
                put("semantic_quality", "requires review; schema checks are not a quality benchmark")
                put("requests_observed", calls.size)
                putJsonArray("calls") { calls.forEach(::add) }
                putJsonArray("outcomes") { outcomes.forEach(::add) }
            }))
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        check(failureCode == null) { "Live DeepSeek check failed: $failureCode. See sanitized report." }
    }
}
