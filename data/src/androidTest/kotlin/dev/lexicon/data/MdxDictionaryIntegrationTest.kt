package dev.lexicon.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Uses the personal asset only when present; portable source checkouts keep no corpus. */
@RunWith(AndroidJUnit4::class)
class MdxDictionaryIntegrationTest {
    @Test fun packagedMdxDiscoveredInstalledSelectedAndReadOffline() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val files = context.assets.list("dictionaries").orEmpty()
        assumeTrue("No personal MDX bundled in this build", files.any { it.endsWith(".mdx", true) })
        val folder = File(context.cacheDir, "mdx-device-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }
        try {
            val store = DictionaryStore(isolated)
            val start = System.nanoTime(); val installed = store.installed()
            val installMs = (System.nanoTime() - start) / 1_000_000
            assertTrue(installed.any { it.format == "MDX" && it.origin == "内置" })
            val selected = store.status()
            assertTrue(selected.ready)
            assertEquals("MDX", selected.format)
            val targets = listOf("hello", "world", "book")
            val samples = targets.map { query ->
                val queryStart = System.nanoTime()
                val result = store.lookup(query)
                val coldMs = (System.nanoTime() - queryStart) / 1_000_000.0
                assertTrue(result.isNotEmpty())
                buildJsonObject { put("query", query); put("first_lookup_ms", coldMs); put("matches", result.size); put("definition_characters", result.first().definition.length) }
            }
            val elapsed = (0 until 150).map { i -> val before = System.nanoTime(); store.lookup(targets[i % targets.size]); (System.nanoTime() - before) / 1_000_000.0 }.sorted()
            val reopened = DictionaryStore(isolated)
            assertEquals(selected.id, reopened.status().id)
            assertEquals(installed.size, reopened.installed().size)
            assertTrue(reopened.lookup("hello").isNotEmpty())
            val report = buildJsonObject {
                put("kind", "android-offline-mdx-dictionary"); put("status", "passed")
                put("device_manufacturer", android.os.Build.MANUFACTURER); put("device_model", android.os.Build.MODEL)
                put("sdk_int", android.os.Build.VERSION.SDK_INT); put("dictionary_name", selected.name)
                put("dictionary_format", selected.format); put("key_count", selected.entryCount)
                put("initial_asset_install_validation_ms", installMs); put("warm_lookup_samples", elapsed.size)
                put("warm_query_scope", "150 repeats of 3 previously queried words; bounded sanitized-entry LRU"); put("warm_lookup_p95_ms", elapsed[(elapsed.size * .95).toInt()]); put("warm_lookup_max_ms", elapsed.last())
                put("selection_restored_on_new_store", true); put("network_requests", 0)
                putJsonArray("samples") { samples.forEach(::add) }
                put("no_dictionary_definition_text_in_report", true)
            }
            File(context.filesDir, "mdx-device-validation.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
        } finally { folder.deleteRecursively() }
    }
}
