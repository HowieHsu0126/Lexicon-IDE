package dev.lexicon.ide.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class DictionaryImportBoundTest {
    @Test fun exactLimitIsAcceptedWithoutChangingBytes() {
        val input = ByteArray(10) { it.toByte() }
        val out = ByteArrayOutputStream()
        copyDictionaryInput(ByteArrayInputStream(input), out, 10)
        assertArrayEquals(input, out.toByteArray())
    }
    @Test fun oversizedInputIsRejectedBeforeDiskLimitIsExceeded() {
        val out = ByteArrayOutputStream()
        try {
            copyDictionaryInput(ByteArrayInputStream(ByteArray(20_000)), out, 10_000)
            fail("Oversized import should be rejected")
        } catch (_: IOException) {
            assertTrue(out.size() <= 10_000)
        }
    }
}
