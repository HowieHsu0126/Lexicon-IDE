package dev.lexicon.data.mdict

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class PreparedMdxCancellationTest {
    private val source = "a".repeat(64)
    private val parser = "core-cancellation-fixture"

    @Test fun cancellationBeforePreparationDoesNotCreateOrRemoveFiles() = owned { directory ->
        val sentinel = sentinel(directory); val target = File(directory, "prepared.sqlite")
        val cancelled = CancellationException("test cancellation")
        var producerCalled = false
        val caught = assertThrows(CancellationException::class.java) {
            PreparedMdxIndex.build(target, source, parser, 1, checkCancelled = { throw cancelled }) { producerCalled = true }
        }
        assertSame(cancelled, caught)
        assertFalse(producerCalled)
        assertOnlySentinel(directory, sentinel)
    }

    @Test fun cancellationDuringProductionRollsBackOnlyTheOwnedIncompleteBuild() = owned { directory ->
        val sentinel = sentinel(directory); val target = File(directory, "prepared.sqlite")
        val cancelled = CancellationException("cancel after first row")
        var stop = false; var delivered = 0
        val caught = assertThrows(CancellationException::class.java) {
            PreparedMdxIndex.build(target, source, parser, 3, checkCancelled = { if (stop) throw cancelled }) { consume ->
                consume(0, "alpha", "first"); delivered++; stop = true
                consume(1, "beta", "second"); delivered++
                consume(2, "gamma", "third"); delivered++
            }
        }
        assertSame(cancelled, caught); assertEquals(1, delivered)
        assertOnlySentinel(directory, sentinel)
    }

    @Test fun cancellationAtTheOwnedReservationCleansItAndItCannotBeOpened() = owned { directory ->
        val sentinel = sentinel(directory); val target = File(directory, "prepared.sqlite")
        val cancelled = CancellationException("cancel reservation")
        var observedReservation = false
        val caught = assertThrows(CancellationException::class.java) {
            PreparedMdxIndex.build(target, source, parser, 1, checkCancelled = {
                if (prefix(target).startsWith("lexicon-prepared-mdx/pending-v1")) {
                    observedReservation = true
                    assertThrows(Exception::class.java) { PreparedMdxIndex.open(target, source, parser).close() }
                    throw cancelled
                }
            }) { consume -> consume(0, "alpha", "first") }
        }
        assertSame(cancelled, caught); assertTrue(observedReservation)
        assertOnlySentinel(directory, sentinel)
    }

    @Test fun cancellationAfterPublicationDoesNotDeleteTheCommittedArtifact() = owned { directory ->
        val sentinel = sentinel(directory); val target = File(directory, "prepared.sqlite")
        val cancelled = CancellationException("cancel after commit")
        val caught = assertThrows(CancellationException::class.java) {
            PreparedMdxIndex.build(target, source, parser, 1, checkCancelled = {
                if (prefix(target).startsWith("SQLite format 3")) throw cancelled
            }) { consume -> consume(0, "alpha", "first") }
        }
        assertSame(cancelled, caught)
        assertEquals(setOf(sentinel.name, target.name), directory.listFiles()!!.map { it.name }.toSet())
        PreparedMdxIndex.open(target, source, parser).use { assertEquals(listOf(MdxReader.Entry("alpha", "first")), it.lookup("alpha")) }
        assertEquals("unchanged", sentinel.readText())
    }

    @Test fun fullAndBoundOpenPropagateCancellationAndLeaveTheirArtifactUnchanged() = owned { directory ->
        val target = File(directory, "prepared.sqlite"); val built = largeBuild(target)
        val stopFull = CancellationException("full validation stop")
        var fullChecks = 0
        val fullFailure = assertThrows(CancellationException::class.java) {
            PreparedMdxIndex.open(target, source, parser, checkCancelled = {
                fullChecks++; if (fullChecks == 100) throw stopFull
            }).close()
        }
        assertSame(stopFull, fullFailure); assertEquals(100, fullChecks)
        assertEquals(built.artifactSha256, sha256(target))

        val stopBound = CancellationException("fingerprint stop")
        var boundChecks = 0
        val boundFailure = assertThrows(CancellationException::class.java) {
            PreparedMdxIndex.openBound(target, source, parser, built.artifactSha256, checkCancelled = {
                boundChecks++; if (boundChecks == 5) throw stopBound
            }).close()
        }
        assertSame(stopBound, boundFailure); assertEquals(5, boundChecks)
        assertEquals(built.artifactSha256, sha256(target))
        PreparedMdxIndex.openBound(target, source, parser, built.artifactSha256).use { assertEquals(128, it.rowCount) }
    }

    @Test fun cancellationDuringTheBuildersFinalFingerprintPreservesThePublishedFile() = owned { directory ->
        val target = File(directory, "prepared.sqlite")
        val cancelled = CancellationException("final fingerprint stop")
        var publishedChecks = 0
        val caught = assertThrows(CancellationException::class.java) {
            PreparedMdxIndex.build(target, source, parser, 128, checkCancelled = {
                if (prefix(target).startsWith("SQLite format 3")) {
                    publishedChecks++; if (publishedChecks == 4) throw cancelled
                }
            }) { consume -> repeat(128) { consume(it, "word${it.toString().padStart(3, '0')}", "definition ".repeat(100)) } }
        }
        assertSame(cancelled, caught); assertEquals(4, publishedChecks)
        assertEquals(listOf(target.name), directory.listFiles()!!.map { it.name })
        PreparedMdxIndex.open(target, source, parser).use { assertEquals(128, it.rowCount) }
    }

    private fun largeBuild(target: File) = PreparedMdxIndex.build(target, source, parser, 128) { consume ->
        repeat(128) { consume(it, "word${it.toString().padStart(3, '0')}", "definition ".repeat(100)) }
    }
    private fun prefix(file: File): String = if (!file.isFile) "" else file.inputStream().use { it.readNBytes(40).toString(Charsets.US_ASCII) }
    private fun sentinel(directory: File) = File(directory, "unrelated.txt").apply { writeText("unchanged") }
    private fun assertOnlySentinel(directory: File, sentinel: File) {
        assertEquals(listOf(sentinel.name), directory.listFiles()!!.map { it.name })
        assertEquals("unchanged", sentinel.readText())
    }
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun owned(block: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "prepared-mdx-cancel-${UUID.randomUUID()}")
        check(directory.mkdir())
        try { block(directory) } finally { check(directory.deleteRecursively()) }
    }
}
