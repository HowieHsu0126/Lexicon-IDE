package dev.lexicon.ide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class ApiKeyFileTest {
    @Test fun importsPlainUtf8KeyWithBomAndNewline() {
        val synthetic = "sk-SYNTHETIC_1234567890"
        assertEquals(synthetic, readApiKeyInput(ByteArrayInputStream("\uFEFF$synthetic\n".toByteArray())))
    }
    @Test fun rejectsOversizedOrMalformedFileWithoutEchoingContents() {
        for (input in listOf("private-not-a-key", "x".repeat(5000))) {
            try { readApiKeyInput(ByteArrayInputStream(input.toByteArray())); fail("Invalid key accepted") }
            catch (e: IllegalArgumentException) { assertFalse(e.message.orEmpty().contains(input)) }
        }
    }
}
