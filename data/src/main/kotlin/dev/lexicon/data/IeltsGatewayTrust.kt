package dev.lexicon.data

import android.net.http.X509TrustManagerExtensions
import dev.lexicon.domain.IeltsGatewayEndpoint
import dev.lexicon.domain.IeltsGatewayFailure
import dev.lexicon.domain.IeltsGatewayFailureCode
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.HttpUrl.Companion.toHttpUrl

/** A certificate explicitly imported by the user, scoped to one gateway origin. */
internal class IeltsGatewayTrust private constructor(
    val pem: String,
    private val host: String,
    private val certificate: X509Certificate,
) {
    val certificateSha256: String = sha256(certificate.encoded).joinToString("") { "%02x".format(it) }
    val expiresAt: Long = certificate.notAfter.time

    /** Also called before each authenticated request, including reused TLS connections. */
    fun checkValidity() {
        try { certificate.checkValidity() }
        catch (_: Exception) { throw IeltsGatewayFailure(IeltsGatewayFailureCode.CERTIFICATE_INVALID) }
    }

    fun socketFactory(): Pair<SSLSocketFactory, X509TrustManager> {
        checkValidity()
        try {
            // This store contains exactly the selected leaf, never the system or user CA stores.
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("paired-gateway-leaf", certificate)
            }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            val delegate = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
            val trust = PinnedLeafTrustManager(host, certificate, delegate)
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            return context.socketFactory to trust
        } catch (_: Exception) {
            throw IeltsGatewayFailure(IeltsGatewayFailureCode.CERTIFICATE_INVALID)
        }
    }

    companion object {
        private const val MAX_DER_BYTES = 8_192
        private const val MAX_PEM_CHARACTERS = 12_000
        private const val SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
        private val pemPattern = Regex("-----BEGIN CERTIFICATE-----\\r?\\n([A-Za-z0-9+/=\\r\\n]+)\\r?\\n-----END CERTIFICATE-----")

        /** checkDates=false only permits reading an expired saved identity; it cannot open TLS. */
        fun fromPem(endpoint: IeltsGatewayEndpoint, pem: String, checkDates: Boolean = true): IeltsGatewayTrust {
            try {
                require(pem.length in 1..MAX_PEM_CHARACTERS)
                val clean = pem.trim(' ', '\t', '\r', '\n')
                val payload = requireNotNull(pemPattern.matchEntire(clean)).groupValues[1]
                    .replace("\r", "").replace("\n", "")
                val der = Base64.getDecoder().decode(payload)
                require(der.size in 1..MAX_DER_BYTES && Base64.getEncoder().encodeToString(der) == payload)
                val input = ByteArrayInputStream(der)
                val certificate = CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
                require(input.available() == 0 && MessageDigest.isEqual(certificate.encoded, der))
                require(certificate.version == 3 && certificate.getExtensionValue("2.5.29.19") != null && certificate.basicConstraints == -1)
                require(!certificate.hasUnsupportedCriticalExtension())
                require(certificate.extendedKeyUsage?.contains(SERVER_AUTH) == true)
                requireNotNull(certificate.keyUsage).let { usage ->
                    require(usage.getOrElse(0) { false } && !usage.getOrElse(5) { false } && !usage.getOrElse(6) { false })
                }
                when (val key = certificate.publicKey) {
                    is RSAPublicKey -> require(key.modulus.bitLength() >= 2_048)
                    is ECPublicKey -> require(key.params.curve.field.fieldSize >= 256)
                    else -> throw CertificateException("unsupported_gateway_key")
                }
                // A leaf with a legacy signature must not bypass algorithm policy by being an anchor.
                require(certificate.sigAlgOID in setOf(
                    "1.2.840.113549.1.1.11", "1.2.840.113549.1.1.12", "1.2.840.113549.1.1.13",
                    "1.2.840.10045.4.3.2", "1.2.840.10045.4.3.3", "1.2.840.10045.4.3.4",
                ))
                // PKIX may skip a trust anchor's self-signature. Reject damaged self-signed
                // pairing certificates here instead of depending on that provider behavior.
                if (certificate.subjectX500Principal == certificate.issuerX500Principal)
                    certificate.verify(certificate.publicKey)
                val host = endpoint.url.toHttpUrl().host
                require(hasExactSubjectAlternativeName(certificate, host))
                require(certificate.notBefore.before(certificate.notAfter))
                if (checkDates) certificate.checkValidity()
                val canonical = "-----BEGIN CERTIFICATE-----\n" +
                    Base64.getEncoder().encodeToString(der).chunked(64).joinToString("\n") +
                    "\n-----END CERTIFICATE-----\n"
                return IeltsGatewayTrust(canonical, host, certificate)
            } catch (_: Exception) {
                throw IeltsGatewayFailure(IeltsGatewayFailureCode.CERTIFICATE_INVALID)
            }
        }

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun hasExactSubjectAlternativeName(certificate: X509Certificate, host: String): Boolean {
            val hostAddress = numericAddress(host)
            return certificate.subjectAlternativeNames.orEmpty().any { name ->
                if (name.size != 2) return@any false
                val text = name[1] as? String ?: return@any false
                if (hostAddress != null) {
                    name[0] == 7 && numericAddress(text)?.contentEquals(hostAddress) == true
                } else {
                    name[0] == 2 && text.all { it.code in 33..126 } && '*' !in text && text.equals(host, ignoreCase = true)
                }
            }
        }

        /** Only numeric literals reach InetAddress. Certificate import never performs DNS or HTTP. */
        private fun numericAddress(value: String): ByteArray? {
            if (':' in value) {
                if (value.any { it !in "0123456789abcdefABCDEF:." }) return null
                return runCatching { InetAddress.getByName(value).address }.getOrNull()
            }
            val parts = value.split('.')
            if (parts.size != 4 || parts.any { part -> part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } }) return null
            val octets = parts.map { it.toInt() }
            if (octets.any { it !in 0..255 }) return null
            return octets.map { it.toByte() }.toByteArray()
        }
    }

    // Public JVM visibility is required: Android's X509TrustManagerExtensions invokes the
    // three-argument overload reflectively. Keep it public if release shrinking is enabled.
    internal class PinnedLeafTrustManager(
        private val host: String,
        certificate: X509Certificate,
        private val delegate: X509TrustManager,
    ) : X509TrustManager {
        private val expectedDigest = sha256(certificate.encoded)

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers.clone()

        @Throws(CertificateException::class)
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("gateway_client_certificates_unsupported")
        }

        @Throws(CertificateException::class)
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = checked {
            requireLeaf(chain, authType)
            delegate.checkServerTrusted(chain, authType)
        }

        /** Retains Android's chain cleaner, including a CA-signed leaf used as the exact anchor. */
        @Throws(CertificateException::class)
        fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, hostname: String): List<X509Certificate> = checked {
            require(hostname.equals(host, ignoreCase = true))
            requireLeaf(chain, authType)
            val validated = X509TrustManagerExtensions(delegate).checkServerTrusted(chain, authType, hostname)
            requireLeaf(validated.toTypedArray(), authType)
            validated
        }

        private fun requireLeaf(chain: Array<X509Certificate>, authType: String) {
            require(chain.isNotEmpty() && authType.isNotBlank())
            val leaf = chain.first()
            require(MessageDigest.isEqual(expectedDigest, sha256(leaf.encoded)))
            leaf.checkValidity()
        }

        private inline fun <T> checked(block: () -> T): T = try { block() }
        catch (_: Exception) { throw CertificateException("gateway_certificate_rejected") }
    }
}
