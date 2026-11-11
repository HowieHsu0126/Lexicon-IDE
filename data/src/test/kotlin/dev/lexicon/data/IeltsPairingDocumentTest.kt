package dev.lexicon.data

import dev.lexicon.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class IeltsPairingDocumentTest {
    private val token = "A".repeat(43)
    private fun document(endpoint: String = "https://gateway.example/", certificate: String = IeltsGatewayTrustTest.VALID,
        key: String = token): ByteArray = buildJsonObject {
        put("schema", "lexicon.ielts.pairing.v1"); put("endpoint", endpoint)
        put("certificate_pem", certificate); put("pairing_token", key)
    }.toString().toByteArray()
    private fun rejected(bytes: ByteArray, code: IeltsGatewayFailureCode = IeltsGatewayFailureCode.PAIRING_DOCUMENT_INVALID) {
        try { IeltsPairingDocument.parse(bytes); fail("Expected invalid connection document") }
        catch (e: IeltsGatewayFailure) { assertEquals(code, e.code); assertNull(e.cause); assertFalse(e.toString().contains(token)) }
    }
    @Test fun localPreviewExposesOnlyPublicIdentityAndDoesNotMutateInput() {
        val input = document(); val original = input.copyOf()
        val parsed = IeltsPairingDocument.parse(input)
        val preview = parsed.preview()
        assertEquals("https://gateway.example/", preview.endpoint)
        assertEquals(64, preview.certificateSha256.length)
        assertTrue(preview.expiresAt > System.currentTimeMillis())
        assertFalse(preview.toString().contains(token)); assertFalse(parsed.toString().contains(token))
        assertArrayEquals(original, input)
    }
    @Test fun duplicateUnknownAndNonStringFieldsAreRejected() {
        val raw = document().toString(Charsets.UTF_8)
        rejected(raw.replaceFirst("{", "{\"schema\":\"lexicon.ielts.pairing.v1\",").toByteArray())
        rejected(raw.replaceFirst("{", "{\"private_key\":\"unused\",").toByteArray())
        rejected(raw.replace("\"$token\"", "null").toByteArray())
        rejected(raw.replace("\"lexicon.ielts.pairing.v1\"", "\"lexicon.ielts.pairing.v2\"").toByteArray())
    }
    @Test fun formatBytesAndBoundsAreStrict() {
        rejected(byteArrayOf(0xc3.toByte(), 0x28))
        rejected(ByteArray(16_385) { 32 }); rejected(byteArrayOf())
        rejected(document(endpoint = "http://gateway.example/"))
        rejected(document(endpoint = "https://gateway.example"))
    }
    @Test fun pairingCodeRequiresCanonicalUnpaddedBase64() {
        for (key in listOf("short", "B".repeat(43), token + "=", "Bearer $token")) rejected(document(key = key))
    }
    @Test fun aCertificateForAnotherHostCannotBecomeAPreview() {
        rejected(document(endpoint = "https://other.example/"), IeltsGatewayFailureCode.CERTIFICATE_INVALID)
    }
    @Test fun expiredOrMultipleCertificatesFailBeforeAnyConnection() {
        rejected(document(certificate = IeltsGatewayTrustTest.EXPIRED), IeltsGatewayFailureCode.CERTIFICATE_INVALID)
        rejected(document(certificate = IeltsGatewayTrustTest.VALID + "\n" + IeltsGatewayTrustTest.VALID), IeltsGatewayFailureCode.CERTIFICATE_INVALID)
    }
}
