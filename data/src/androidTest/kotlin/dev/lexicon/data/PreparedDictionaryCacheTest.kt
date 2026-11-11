package dev.lexicon.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.data.mdict.MdxReader
import dev.lexicon.data.mdict.PreparedDictionaryFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
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
class PreparedDictionaryCacheTest {
    @Test fun completedReceiptSurvivesNewCacheAndMatchesRawWithoutChangingTheSource() = runBlocking {
        PreparedDictionaryFixture().use { area ->
            val source = area.mdx("A", "A synthetic English meaning."); val sha = area.sha(source)
            val original = source.readBytes(); val cache = area.cache()
            cache.prepare(source, sha)
            val receiptBytes = area.receipt(sha).readBytes()
            val expected = MdxReader(source).use { listOf(it.lookup("shared"), it.lookup("sha"), it.lookup("missing")) }
            requireNotNull(area.cache().tryOpen(source, sha)).use { prepared ->
                assertEquals(expected, listOf(prepared.lookup("shared"), prepared.lookup("sha"), prepared.lookup("missing")))
            }
            cache.prepare(source, sha)
            assertArrayEquals(receiptBytes, area.receipt(sha).readBytes())
            assertArrayEquals(original, source.readBytes())
            assertNull(PreparedDictionaryCache(area.preparedFolder, parserVersion = "different-parser").tryOpen(source, sha))
            assertNull(cache.tryOpen(source, "a".repeat(64)))
        }
    }

    @Test fun corruptedArtifactAndChangedSourceNeverReuseTrustedContent() = runBlocking {
        PreparedDictionaryFixture().use { area ->
            val source = area.mdx("A", "Meaning before damage."); val sha = area.sha(source)
            area.cache().prepare(source, sha)
            val receipt = Json.decodeFromString<PreparedDictionaryCache.Receipt>(area.receipt(sha).readText())
            val artifact = File(area.area(sha), receipt.artifactFile)
            val originalSource = source.readBytes()
            try {
                val changed = originalSource.copyOf(); changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
                source.writeBytes(changed)
                assertNull(area.cache().tryOpen(source, sha))
            } finally { source.writeBytes(originalSource) }
            val bytes = artifact.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); artifact.writeBytes(bytes)
            val damagedBytes = artifact.readBytes()
            val cache = area.cache()
            assertNull(cache.tryOpen(source, sha))
            assertNull(cache.tryOpen(source, sha))
            assertArrayEquals("A read failure must not edit the suspect artifact", damagedBytes, artifact.readBytes())
            cache.prepare(source, sha)
            requireNotNull(cache.tryOpen(source, sha)).use { assertEquals("Meaning before damage.", it.lookup("shared").single().text) }
            assertArrayEquals(originalSource, source.readBytes())
        }
    }

    @Test fun cancellationBeforeReceiptCommitRemovesOnlyTheNewBuildAndCanRetry() = runBlocking {
        PreparedDictionaryFixture().use { area ->
            val source = area.mdx("A", "Cancellation fixture."); val sha = area.sha(source)
            val original = source.readBytes(); val cache = area.cache()
            var cancelled = false
            try {
                cache.prepare(source, sha) {
                    if (area.area(sha).listFiles().orEmpty().any { it.name.startsWith(".receipt-") }) throw CancellationException("Synthetic publication cancellation")
                }
            } catch (_: CancellationException) { cancelled = true }
            assertTrue("The check must reach receipt publication", cancelled)
            assertFalse(area.receipt(sha).exists())
            assertTrue("The cancelled completed artifact and partials belong only to this attempt", area.area(sha).listFiles().orEmpty().isEmpty())
            assertArrayEquals(original, source.readBytes())
            cache.prepare(source, sha)
            requireNotNull(cache.tryOpen(source, sha)).close()
            var openCancelled = false
            try { cache.tryOpen(source, sha, checkCancelled = { throw CancellationException("Synthetic opening cancellation") }) }
            catch (_: CancellationException) { openCancelled = true }
            assertTrue("Cancellation must propagate instead of becoming a raw-fallback miss", openCancelled)
        }
    }

    @Test fun receiptWriteFailurePreservesPreviouslyBuiltArtifactAndSource() = runBlocking {
        PreparedDictionaryFixture().use { area ->
            val source = area.mdx("A", "Preserved publication fixture."); val sha = area.sha(source)
            val cache = area.cache(); cache.prepare(source, sha)
            val receiptFile = area.receipt(sha); val receiptBytes = receiptFile.readBytes()
            val receipt = Json.decodeFromString<PreparedDictionaryCache.Receipt>(receiptBytes.toString(Charsets.UTF_8))
            val previousArtifact = File(area.area(sha), receipt.artifactFile); val previousBytes = previousArtifact.readBytes()
            check(receiptFile.delete()); check(receiptFile.mkdir())
            File(receiptFile, "owned-write-rejection").writeText("synthetic")
            var failed = false
            try {
                try { cache.prepare(source, sha) } catch (_: Exception) { failed = true }
                assertTrue("A nonempty directory must reject the ready receipt", failed)
                assertArrayEquals(previousBytes, previousArtifact.readBytes())
                assertEquals(setOf(previousArtifact.name, receiptFile.name), area.area(sha).list().orEmpty().toSet())
                assertEquals(sha, area.sha(source))
            } finally { check(receiptFile.deleteRecursively()); receiptFile.writeBytes(receiptBytes) }
            requireNotNull(area.cache().tryOpen(source, sha)).close()
        }
    }

    @Test fun singleWriterDeduplicatesAndSupersededSelectionCannotPublish() = runBlocking {
        PreparedDictionaryFixture().use { area ->
            val a = area.mdx("A", "Meaning from A."); val aSha = area.sha(a)
            val b = area.mdx("B", "Meaning from B."); val bSha = area.sha(b)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val cache = PreparedDictionaryCache(area.preparedFolder, scope)
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val first = AtomicBoolean(true); val bEntered = AtomicBoolean(false)
            val aJob = requireNotNull(cache.schedule(a, aSha, 1) {
                if (first.compareAndSet(true, false)) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            })
            var bJob: kotlinx.coroutines.Job? = null
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                assertSame(aJob, cache.schedule(a, aSha, 1))
                bJob = requireNotNull(cache.schedule(b, bSha, 2) { bEntered.set(true) })
                assertSame(bJob, cache.schedule(a, aSha, 1))
                assertFalse("B cannot enter the writer while A owns it", bEntered.get())
                release.countDown()
                withTimeout(15_000) { joinAll(aJob, requireNotNull(bJob)) }
                assertTrue(aJob.isCancelled)
                assertTrue(bEntered.get())
                assertFalse(area.receipt(aSha).exists())
                requireNotNull(cache.tryOpen(b, bSha)).use { assertEquals("Meaning from B.", it.lookup("shared").single().text) }
            } finally {
                release.countDown(); aJob.cancel(); bJob?.cancel()
                withTimeout(15_000) { aJob.join(); bJob?.join() }
                scope.cancel()
            }
        }
    }
}
