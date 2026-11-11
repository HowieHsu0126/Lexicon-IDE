package dev.lexicon.data

import dev.lexicon.domain.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.serialization.json.*

/** Imported from a file explicitly chosen by the user, never fetched from a prospective server. */
internal class IeltsPairingDocument private constructor(
    val endpoint: IeltsGatewayEndpoint,
    val token: String,
    val trust: IeltsGatewayTrust,
) {
    fun preview() = IeltsPairingPreview(endpoint.url, trust.certificateSha256, trust.expiresAt)
    override fun toString() = "IeltsPairingDocument(<redacted>)"

    companion object {
        const val MAX_BYTES = IeltsPairingPreview.MAX_DOCUMENT_BYTES
        fun parse(document: ByteArray): IeltsPairingDocument {
            try {
                require(document.isNotEmpty() && document.size <= MAX_BYTES)
                val raw = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(document)).toString()
                val value = Json.parseToJsonElement(IeltsExerciseJson.canonicalJson(raw)).jsonObject
                require(value.keys == setOf("schema", "endpoint", "certificate_pem", "pairing_token"))
                fun string(key: String): String = value.getValue(key).jsonPrimitive.let { require(it.isString); it.content }
                require(string("schema") == "lexicon.ielts.pairing.v1")
                val endpoint = IeltsGatewayEndpoint.parse(string("endpoint"))
                require(endpoint.url == string("endpoint"))
                val token = string("pairing_token").also(::requireIeltsPairingToken)
                require(Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(token)) == token)
                val trust = IeltsGatewayTrust.fromPem(endpoint, string("certificate_pem"))
                return IeltsPairingDocument(endpoint, token, trust)
            } catch (failure: IeltsGatewayFailure) {
                if (failure.code == IeltsGatewayFailureCode.CERTIFICATE_INVALID) throw failure
                throw IeltsGatewayFailure(IeltsGatewayFailureCode.PAIRING_DOCUMENT_INVALID)
            } catch (_: Exception) {
                throw IeltsGatewayFailure(IeltsGatewayFailureCode.PAIRING_DOCUMENT_INVALID)
            }
        }
    }
}
