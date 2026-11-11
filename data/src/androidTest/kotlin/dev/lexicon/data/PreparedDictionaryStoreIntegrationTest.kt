package dev.lexicon.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.data.mdict.MdxReader
import dev.lexicon.data.mdict.PreparedMdxIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class PreparedDictionaryStoreIntegrationTest {
    @Test fun preparationDoesNotHoldQueryLockOrHotSwapTheCurrentRawReader() = runBlocking {
        PreparedDictionaryFixture().use { area ->
            val store = area.store(); val installed = store.import(area.mdx("A", "English meaning from A."))
            val source = area.installed(store, installed.id)
            val cache = area.field(store, "preparedCache") as PreparedDictionaryCache
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val first = AtomicBoolean(true)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val preparation = scope.async {
                cache.prepare(source, installed.id) {
                    if (first.compareAndSet(true, false)) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                }
            }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val results = withTimeout(5_000) { store.lookup("shared") }
                assertEquals("English meaning from A.", results.single().definition)
                val originalReader = area.field(store, "reader") as MdxReader
                assertNull(area.field(store, "prepared"))
                assertFalse("The builder is still deliberately blocked", preparation.isCompleted)
                release.countDown(); withTimeout(15_000) { preparation.await() }
                assertEquals(results, store.lookup("shared"))
                assertSame(originalReader, area.field(store, "reader"))
                assertNull("A completed build must not replace an open raw reader", area.field(store, "prepared"))

                val reopened = area.store()
                assertEquals(installed.id, reopened.status().id)
                assertEquals(results, reopened.lookup("shared"))
                val prepared = area.field(reopened, "prepared") as PreparedMdxIndex
                assertNull(area.field(reopened, "reader"))
                reopened.select(installed.id)
                assertSame(prepared, area.field(reopened, "prepared"))
            } finally {
                release.countDown(); preparation.cancel()
                withTimeout(15_000) { preparation.join() }
                scope.cancel()
            }
        }
    }

    @Test fun damagedPreparedArtifactFallsBackToTheSameRawDictionary() = runBlocking {
        PreparedDictionaryFixture().use { area ->
            val store = area.store(); val installed = store.import(area.mdx("A", "The original definition remains available."))
            val source = area.installed(store, installed.id); val sourceBytes = source.readBytes()
            area.cache().prepare(source, installed.id)
            val receipt = Json.decodeFromString<PreparedDictionaryCache.Receipt>(area.receipt(installed.id).readText())
            val artifact = File(area.area(installed.id), receipt.artifactFile)
            val data = artifact.readBytes(); data[data.lastIndex] = (data.last().toInt() xor 1).toByte(); artifact.writeBytes(data)
            val damaged = artifact.readBytes()
            val reopened = area.store()
            assertEquals("The original definition remains available.", reopened.lookup("shared").single().definition)
            val raw = area.field(reopened, "reader")
            assertNotNull(raw); assertNull(area.field(reopened, "prepared"))
            assertEquals(installed.id, reopened.status().id)
            assertEquals(installed.id, area.selectedFile.readText())
            reopened.lookup("shared")
            assertSame(raw, area.field(reopened, "reader"))
            assertArrayEquals(sourceBytes, source.readBytes())
            assertArrayEquals("The no-background default must not mutate rejected artifacts", damaged, artifact.readBytes())
        }
    }

    @Test fun aPreparedReadFailureRetriesRawOnceAndDoesNotReopenTheFailedBackend() = runBlocking {
        PreparedDictionaryFixture().use { area ->
            val store = area.store(); val installed = store.import(area.mdx("A", "Meaning from the preserved MDX."))
            area.cache().prepare(area.installed(store, installed.id), installed.id)
            assertEquals("Meaning from the preserved MDX.", store.lookup("shared").single().definition)
            val index = area.field(store, "prepared") as PreparedMdxIndex
            index.close() // A deterministic read failure, without corrupting the MDX or receipt.
            assertEquals("Meaning from the preserved MDX.", store.lookup("shared").single().definition)
            assertNotNull(area.field(store, "reader")); assertNull(area.field(store, "prepared"))
            area.closeStore(store)
            assertEquals("Meaning from the preserved MDX.", store.lookup("shared").single().definition)
            assertNull("This process must not repeatedly reopen a failed prepared backend", area.field(store, "prepared"))
        }
    }

    @Test fun failedDurableSelectionKeepsTheOldReaderWhenPreparedCandidateWasOpened() = runBlocking {
        PreparedDictionaryFixture().use { area ->
            val store = area.store()
            val a = store.import(area.mdx("A", "Meaning from A."))
            val b = store.import(area.mdx("B", "Meaning from B."))
            store.select(a.id); store.lookup("shared")
            val rawA = area.field(store, "reader")
            val originalCatalog = area.catalogFile.readBytes(); val originalSelection = area.selectedFile.readBytes()
            area.cache().prepare(area.installed(store, b.id), b.id)
            check(area.selectedFile.delete()); check(area.selectedFile.mkdir())
            File(area.selectedFile, "owned-write-rejection").writeText("synthetic")
            var failed = false
            try {
                try { store.select(b.id) } catch (_: Exception) { failed = true }
                assertTrue(failed)
                assertSame(rawA, area.field(store, "reader")); assertNull(area.field(store, "prepared"))
                assertEquals(a.id, store.status().id)
                assertEquals("Meaning from A.", store.lookup("shared").single().definition)
                assertArrayEquals(originalCatalog, area.catalogFile.readBytes())
            } finally { check(area.selectedFile.deleteRecursively()); area.selectedFile.writeBytes(originalSelection) }
            store.select(b.id)
            assertEquals("Meaning from B.", store.lookup("shared").single().definition)
            assertNotNull(area.field(store, "prepared")); assertNull(area.field(store, "reader"))
            assertEquals(b.id, area.selectedFile.readText())
        }
    }

    @Test fun preparedMdxAndLegacySqliteKeepTheirOwnIdentityAndCloseReplacedHandles() = runBlocking {
        PreparedDictionaryFixture().use { area ->
            val store = area.store(); val mdx = store.import(area.mdx("A", "MDX meaning."))
            val sqlite = store.import(area.sqlite("SQLite meaning."))
            area.cache().prepare(area.installed(store, mdx.id), mdx.id)
            store.select(mdx.id)
            val firstPrepared = area.field(store, "prepared") as PreparedMdxIndex
            assertEquals("MDX meaning.", store.lookup("shared").single().definition)
            store.select(sqlite.id)
            assertNull(area.field(store, "prepared")); assertNotNull(area.field(store, "connection"))
            assertThrows(IllegalStateException::class.java) { firstPrepared.lookup("shared") }
            assertEquals("SQLite meaning.", store.lookup("shareds").single().definition)
            store.select(mdx.id)
            assertNull(area.field(store, "connection")); assertNotNull(area.field(store, "prepared"))
            assertEquals("MDX meaning.", store.lookup("shared").single().definition)
            val reopened = area.store()
            assertEquals(mdx.id, reopened.status().id)
            assertEquals("MDX meaning.", reopened.lookup("shared").single().definition)
        }
    }
}
