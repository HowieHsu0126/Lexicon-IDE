package dev.lexicon.data

import dev.lexicon.domain.IeltsGatewayEndpoint
import dev.lexicon.domain.IeltsGatewayFailure
import dev.lexicon.domain.IeltsGatewayFailureCode
import java.io.ByteArrayInputStream
import java.lang.reflect.Modifier
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Synthetic certificate material only. Local TLS fixtures never contact an external service. */
class IeltsGatewayTrustTest {
    private val endpoint = IeltsGatewayEndpoint.parse("https://gateway.example/")

    @Test fun importedLeafUsesItsDerFingerprintAndCanonicalPem() {
        val trust = IeltsGatewayTrust.fromPem(endpoint, "  \r\n" + VALID.replace("\n", "\r\n") + "\r\n\t")
        val certificate = certificate(VALID)
        val expected = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        assertEquals(expected, trust.certificateSha256)
        assertEquals(VALID + "\n", trust.pem)
        assertEquals(certificate.notAfter.time, trust.expiresAt)
        trust.checkValidity()
        assertNotEquals(trust.certificateSha256, IeltsGatewayTrust.fromPem(endpoint, RENEWED_SAME_KEY).certificateSha256)
    }

    @Test fun malformedMultipleOversizedAndTrailingDerInputsFailClosed() {
        val trailing = certificate(VALID).encoded + byteArrayOf(0)
        val derWithGarbage = "-----BEGIN CERTIFICATE-----\n" + Base64.getEncoder().encodeToString(trailing) + "\n-----END CERTIFICATE-----"
        val oversized = "-----BEGIN CERTIFICATE-----\n" + Base64.getEncoder().encodeToString(ByteArray(8_193)) + "\n-----END CERTIFICATE-----"
        listOf("", "not-a-certificate", VALID + "\n" + VALID, "comment\n" + VALID, VALID + "\ncomment",
            TEST_PRIVATE_KEY, derWithGarbage, oversized, " ".repeat(12_001), VALID.replace("MII", "M?I")).forEach {
            invalid { IeltsGatewayTrust.fromPem(endpoint, it) }
        }
    }

    @Test fun caMissingServerUsageWeakKeysAndUnsupportedExtensionsAreRejected() {
        listOf(CA, CLIENT_ONLY, MISSING_EKU, MISSING_KU, WEAK_RSA, WEAK_EC, UNKNOWN_CRITICAL).forEach {
            invalid { IeltsGatewayTrust.fromPem(endpoint, it) }
        }
        IeltsGatewayTrust.fromPem(endpoint, VALID_EC).checkValidity()
    }

    @Test fun selfSignedSignatureIsCheckedEvenWhenTheLeafBecomesATrustAnchor() {
        val altered = certificate(VALID).encoded.copyOf()
        altered[altered.lastIndex] = (altered.last().toInt() xor 1).toByte()
        val pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getEncoder().encodeToString(altered) + "\n-----END CERTIFICATE-----"
        invalid { IeltsGatewayTrust.fromPem(endpoint, pem) }
    }

    @Test fun exactSanIsRequiredWithoutCommonNameOrWildcardFallback() {
        listOf(COMMON_NAME_ONLY, WILDCARD).forEach { invalid { IeltsGatewayTrust.fromPem(endpoint, it) } }
        invalid { IeltsGatewayTrust.fromPem(IeltsGatewayEndpoint.parse("https://other.example"), VALID) }
        invalid { IeltsGatewayTrust.fromPem(IeltsGatewayEndpoint.parse("https://127.0.0.1"), IP_AS_DNS) }
        IeltsGatewayTrust.fromPem(IeltsGatewayEndpoint.parse("https://GATEWAY.EXAMPLE"), VALID)
        IeltsGatewayTrust.fromPem(IeltsGatewayEndpoint.parse("https://127.0.0.1"), VALID)
        IeltsGatewayTrust.fromPem(IeltsGatewayEndpoint.parse("https://[::1]"), VALID)
    }

    @Test fun readingExpiredSavedIdentityCannotCreateATlsConnection() {
        listOf(EXPIRED, FUTURE).forEach { value ->
            invalid { IeltsGatewayTrust.fromPem(endpoint, value) }
            val saved = IeltsGatewayTrust.fromPem(endpoint, value, checkDates = false)
            invalid { saved.checkValidity() }
            invalid { saved.socketFactory() }
        }
    }

    @Test fun trustStoreContainsOnlyTheImportedLeafAndRejectsSameKeyRenewal() {
        val trust = IeltsGatewayTrust.fromPem(endpoint, VALID)
        val (_, manager) = trust.socketFactory()
        assertEquals(1, manager.acceptedIssuers.size)
        assertArrayEquals(certificate(VALID).encoded, manager.acceptedIssuers.single().encoded)
        manager.checkServerTrusted(arrayOf(certificate(VALID)), "RSA")
        rejected { manager.checkServerTrusted(arrayOf(certificate(RENEWED_SAME_KEY)), "RSA") }
        rejected { manager.checkServerTrusted(arrayOf(certificate(RENEWED_SAME_KEY), certificate(VALID)), "RSA") }
        rejected { manager.checkServerTrusted(emptyArray(), "RSA") }
        rejected { manager.checkServerTrusted(arrayOf(certificate(VALID)), "") }
        rejected { manager.checkClientTrusted(arrayOf(certificate(VALID)), "RSA") }
    }

    @Test fun caSignedLeafIsAnExactTrustAnchorWithoutTrustingItsIssuer() {
        val (_, manager) = IeltsGatewayTrust.fromPem(endpoint, CA_SIGNED_LEAF).socketFactory()
        manager.checkServerTrusted(arrayOf(certificate(CA_SIGNED_LEAF)), "RSA")
        assertEquals(1, manager.acceptedIssuers.size)
        assertArrayEquals(certificate(CA_SIGNED_LEAF).encoded, manager.acceptedIssuers.single().encoded)
        rejected { manager.checkServerTrusted(arrayOf(certificate(VALID)), "RSA") }
    }

    @Test fun androidChainCleanerCanDiscoverThePublicHostnameAwareMethod() {
        val (_, manager) = IeltsGatewayTrust.fromPem(endpoint, VALID).socketFactory()
        val method = manager.javaClass.getMethod("checkServerTrusted", Array<X509Certificate>::class.java, String::class.java, String::class.java)
        assertTrue(Modifier.isPublic(manager.javaClass.modifiers))
        assertTrue(Modifier.isPublic(method.modifiers))
        assertEquals(List::class.java, method.returnType)
        // The Android-specific delegate needs an Android runtime. Wrong-host rejection occurs
        // before that delegate and is independently testable on the JVM.
        rejected {
            (manager as IeltsGatewayTrust.PinnedLeafTrustManager).checkServerTrusted(arrayOf(certificate(VALID)), "RSA", "other.example")
        }
    }

    @Test fun correctImportedCertificateSendsHeadersAndBodyOnlyAfterTls() {
        server(VALID).use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            val client = client(IeltsGatewayTrust.fromPem(IeltsGatewayEndpoint.parse(server.url("/").toString()), VALID))
            try {
                client.newCall(request(server)).execute().use { assertEquals(200, it.code) }
                val received = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("Bearer synthetic-pairing-token", received.getHeader("Authorization"))
                assertEquals("synthetic-request", received.body.readUtf8())
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun differentLeafWithSameKeyReceivesNoAuthorizationOrBody() {
        server(RENEWED_SAME_KEY).use { server ->
            val client = client(IeltsGatewayTrust.fromPem(IeltsGatewayEndpoint.parse(server.url("/").toString()), VALID))
            try {
                rejectedTls { client.newCall(request(server)).execute().close() }
                assertEquals(0, server.requestCount)
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun hostnameMismatchReceivesNoAuthorizationOrBody() {
        server(REMOTE_ONLY).use { server ->
            val client = client(IeltsGatewayTrust.fromPem(endpoint, REMOTE_ONLY))
            try {
                rejectedTls { client.newCall(request(server)).execute().close() }
                assertEquals(0, server.requestCount)
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun systemTrustDoesNotAcquireTheImportedLeaf() {
        IeltsGatewayTrust.fromPem(endpoint, VALID).socketFactory()
        server(VALID).use { server ->
            val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
            try {
                rejectedTls { client.newCall(request(server)).execute().close() }
                assertEquals(0, server.requestCount)
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    private fun client(trust: IeltsGatewayTrust): OkHttpClient {
        val (factory, manager) = trust.socketFactory()
        return OkHttpClient.Builder().sslSocketFactory(factory, manager).callTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build().also {
                assertSame(OkHttpClient().hostnameVerifier, it.hostnameVerifier)
            }
    }

    private fun request(server: MockWebServer) = Request.Builder().url(server.url("/pair"))
        .header("Authorization", "Bearer synthetic-pairing-token").post("synthetic-request".toRequestBody()).build()

    private fun server(pem: String): MockWebServer {
        val encoded = TEST_PRIVATE_KEY.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)))
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("synthetic-server", key, "fixture".toCharArray(), arrayOf(certificate(pem)))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "fixture".toCharArray()) }
        val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        return MockWebServer().apply { useHttps(tls.socketFactory, false); start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0) }
    }

    private fun certificate(pem: String) = CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(pem.toByteArray(Charsets.US_ASCII))) as X509Certificate

    private fun invalid(block: () -> Unit) {
        try { block(); fail("Expected invalid certificate") }
        catch (failure: IeltsGatewayFailure) { assertEquals(IeltsGatewayFailureCode.CERTIFICATE_INVALID, failure.code); assertNull(failure.cause) }
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejected TLS certificate") }
        catch (failure: CertificateException) { assertNull(failure.cause) }
    }
    private fun rejectedTls(block: () -> Unit) {
        try { block(); fail("Expected TLS rejection before HTTP") }
        catch (_: SSLException) { }
    }

    internal companion object {
        // Generated exclusively for tests; certificates last to 2100 except explicit date cases.
        // This private key authenticates only the synthetic local test server and is not a credential.
        val VALID = """
            -----BEGIN CERTIFICATE-----
            MIIDJTCCAg2gAwIBAgIBATANBgkqhkiG9w0BAQsFADAaMRgwFgYDVQQDDA9nYXRl
            d2F5LmV4YW1wbGUwIBcNMjUwMTAxMDAwMDAwWhgPMjEwMDAxMDEwMDAwMDBaMBox
            GDAWBgNVBAMMD2dhdGV3YXkuZXhhbXBsZTCCASIwDQYJKoZIhvcNAQEBBQADggEP
            ADCCAQoCggEBANnq50kkzNv0NkoZJ/sht0ChpmOIiMpzhAFD05TC5RYMyjOghUig
            3TRbkXgn6lF8vuYuucD2TrHCKaVLXPiFG+IsP8V9fRttmYATRCPstnSa1z5OHhue
            caCqfJQtSX2/qN+ZZBWQtwm5AyaiTsSt649kE1Ovgxp/f2x5pwrQCB6qGRU0tk+4
            aC8D9cJ3lIn9LnrWQl6zW13mlkuqDriV3DBHKeKMOmqacrKlDY2w1ycWzB+cN7RY
            SgD3r9HxE7L7kgCGe5MS6hWbUksdRSij/QOfRmcVzzM/AH7PX5a7XeSy/jCK4uxB
            sVambVYoCmVcXBF6yIJpDc96JywjSya5IKcCAwEAAaN0MHIwDAYDVR0TAQH/BAIw
            ADATBgNVHSUEDDAKBggrBgEFBQcDATA9BgNVHREENjA0gg9nYXRld2F5LmV4YW1w
            bGWCCWxvY2FsaG9zdIcEfwAAAYcQAAAAAAAAAAAAAAAAAAAAATAOBgNVHQ8BAf8E
            BAMCBaAwDQYJKoZIhvcNAQELBQADggEBAHB4bdqeVCkG4EHFt/83Ll7bxmof5otF
            dvrjX7ZFLVGuXw4F2AaKpb+szkKpLxcrDqepdt1mV3lg9qZmKTDONHlTGG/m7jEd
            GYHq62+4gLEC8q4GCH/DAmAnVcR/Ju+opvVxGZkNKoud21fywQ1SO9/SsmVeb02l
            P9lIae12uHBVg7288s9LPjbhUbIq0Rn9fa9PnH7NiFPrJbnPK3oXEuDMLTBmjCVn
            PHEWDBCZeuPO+rKoMsg4qFTWimCNDYeX7GJ7oPIFWy7i4gK40x6xAG+tnkVZpANz
            0yjDlLi0vrx0PYpJCOBJeXzJHo+sbxrUX045IFWS/Npz4Uk2Ay+uOw0=
            -----END CERTIFICATE-----
        """.trimIndent()
        val RENEWED_SAME_KEY = """
            -----BEGIN CERTIFICATE-----
            MIIDJTCCAg2gAwIBAgIBAjANBgkqhkiG9w0BAQsFADAaMRgwFgYDVQQDDA9nYXRl
            d2F5LmV4YW1wbGUwIBcNMjUwMTAxMDAwMDAwWhgPMjEwMDAxMDEwMDAwMDBaMBox
            GDAWBgNVBAMMD2dhdGV3YXkuZXhhbXBsZTCCASIwDQYJKoZIhvcNAQEBBQADggEP
            ADCCAQoCggEBANnq50kkzNv0NkoZJ/sht0ChpmOIiMpzhAFD05TC5RYMyjOghUig
            3TRbkXgn6lF8vuYuucD2TrHCKaVLXPiFG+IsP8V9fRttmYATRCPstnSa1z5OHhue
            caCqfJQtSX2/qN+ZZBWQtwm5AyaiTsSt649kE1Ovgxp/f2x5pwrQCB6qGRU0tk+4
            aC8D9cJ3lIn9LnrWQl6zW13mlkuqDriV3DBHKeKMOmqacrKlDY2w1ycWzB+cN7RY
            SgD3r9HxE7L7kgCGe5MS6hWbUksdRSij/QOfRmcVzzM/AH7PX5a7XeSy/jCK4uxB
            sVambVYoCmVcXBF6yIJpDc96JywjSya5IKcCAwEAAaN0MHIwDAYDVR0TAQH/BAIw
            ADATBgNVHSUEDDAKBggrBgEFBQcDATA9BgNVHREENjA0gg9nYXRld2F5LmV4YW1w
            bGWCCWxvY2FsaG9zdIcEfwAAAYcQAAAAAAAAAAAAAAAAAAAAATAOBgNVHQ8BAf8E
            BAMCBaAwDQYJKoZIhvcNAQELBQADggEBALmZYonAIsX40saKUzR0ijQjBwDwuu0h
            yAUVz7E7H2yZBxO/UKoYT0lM78Vr26Dv1qKodRmySFwElbtBJ8rAo178e9K8nxGO
            IgFPyltJ3YEFyWafBQPY+8LJ7SLPKoQoJSQ2q0H6reJ5qhx4CP35llzAD6Enpd5z
            SR+saMnpy7KRrQFoBcKx7nx4q6GGh2D5eIQlETWUJWKQHODmnmnvlDXaKUQzbGyL
            gc3qyRc4p44l/uz87N5+j2/vi/fpgmFs3kuChA+soaPghL0iJvT0qdWBG1m3zsSQ
            PzOx1DO9R6/K5EAL6BgK1JirCB6j87DLQalNUcDraFkjUC6b2w3SAPg=
            -----END CERTIFICATE-----
        """.trimIndent()
        val CA = """
            -----BEGIN CERTIFICATE-----
            MIIDKDCCAhCgAwIBAgIBAzANBgkqhkiG9w0BAQsFADAaMRgwFgYDVQQDDA9nYXRl
            d2F5LmV4YW1wbGUwIBcNMjUwMTAxMDAwMDAwWhgPMjEwMDAxMDEwMDAwMDBaMBox
            GDAWBgNVBAMMD2dhdGV3YXkuZXhhbXBsZTCCASIwDQYJKoZIhvcNAQEBBQADggEP
            ADCCAQoCggEBANnq50kkzNv0NkoZJ/sht0ChpmOIiMpzhAFD05TC5RYMyjOghUig
            3TRbkXgn6lF8vuYuucD2TrHCKaVLXPiFG+IsP8V9fRttmYATRCPstnSa1z5OHhue
            caCqfJQtSX2/qN+ZZBWQtwm5AyaiTsSt649kE1Ovgxp/f2x5pwrQCB6qGRU0tk+4
            aC8D9cJ3lIn9LnrWQl6zW13mlkuqDriV3DBHKeKMOmqacrKlDY2w1ycWzB+cN7RY
            SgD3r9HxE7L7kgCGe5MS6hWbUksdRSij/QOfRmcVzzM/AH7PX5a7XeSy/jCK4uxB
            sVambVYoCmVcXBF6yIJpDc96JywjSya5IKcCAwEAAaN3MHUwDwYDVR0TAQH/BAUw
            AwEB/zATBgNVHSUEDDAKBggrBgEFBQcDATA9BgNVHREENjA0gg9nYXRld2F5LmV4
            YW1wbGWCCWxvY2FsaG9zdIcEfwAAAYcQAAAAAAAAAAAAAAAAAAAAATAOBgNVHQ8B
            Af8EBAMCAaYwDQYJKoZIhvcNAQELBQADggEBAHoCrx2seLsv+myRSo02XIYjzyzP
            Xb5+i0H20/aOYbi4Bgxy0+ZkOHLapWicIOCpkcrZ3hUyz3slwagURPwkzrsuu2XL
            Xt0Dux0hrpeEbvZkZXRA1OSefOfgTtBoclRpEvedC43AKkiY8xeqyzawVEHnGLQM
            sbJr1RIKFicDMgMztVhLpLRPQB9/V5NxzvgotfSEQ3zJZJS5ylcczw++8FfPNfrP
            lemlFsdfJVs52BQpfN1hoPvZDFIvP0jlLH/NaeY9+7dC9IXHm+k/p2zex7Umugdp
            C4FhbeeNxR/7UzTgjgblth87opW484iuOJb4hsJwdGQ9asTeaPrX7g3h9u0=
            -----END CERTIFICATE-----
        """.trimIndent()
        val CLIENT_ONLY = """
            -----BEGIN CERTIFICATE-----
            MIIDJTCCAg2gAwIBAgIBBDANBgkqhkiG9w0BAQsFADAaMRgwFgYDVQQDDA9nYXRl
            d2F5LmV4YW1wbGUwIBcNMjUwMTAxMDAwMDAwWhgPMjEwMDAxMDEwMDAwMDBaMBox
            GDAWBgNVBAMMD2dhdGV3YXkuZXhhbXBsZTCCASIwDQYJKoZIhvcNAQEBBQADggEP
            ADCCAQoCggEBANnq50kkzNv0NkoZJ/sht0ChpmOIiMpzhAFD05TC5RYMyjOghUig
            3TRbkXgn6lF8vuYuucD2TrHCKaVLXPiFG+IsP8V9fRttmYATRCPstnSa1z5OHhue
            caCqfJQtSX2/qN+ZZBWQtwm5AyaiTsSt649kE1Ovgxp/f2x5pwrQCB6qGRU0tk+4
            aC8D9cJ3lIn9LnrWQl6zW13mlkuqDriV3DBHKeKMOmqacrKlDY2w1ycWzB+cN7RY
            SgD3r9HxE7L7kgCGe5MS6hWbUksdRSij/QOfRmcVzzM/AH7PX5a7XeSy/jCK4uxB
            sVambVYoCmVcXBF6yIJpDc96JywjSya5IKcCAwEAAaN0MHIwDAYDVR0TAQH/BAIw
            ADATBgNVHSUEDDAKBggrBgEFBQcDAjA9BgNVHREENjA0gg9nYXRld2F5LmV4YW1w
            bGWCCWxvY2FsaG9zdIcEfwAAAYcQAAAAAAAAAAAAAAAAAAAAATAOBgNVHQ8BAf8E
            BAMCBaAwDQYJKoZIhvcNAQELBQADggEBAG3FEnp/dnvXruX0MGIo9dTx5flNCXdV
            uzPxac3kRn+uCYFm7RL3/W+YOn772IHKvGJP+BVoUiTZcf3ZjHA59xLqG48Oz/dT
            qZHvzoUBR+3Xh2nllFZVazZ0wgvmv34N1pHklZO5grffGP7OY8uAZd4LVC8B1HOZ
            7myNTVdincEPGV2TJzydvulphiWWduvN7TV3up3TwIAubaAQ5sCFRN7ZMfLaq/yq
            gKky4Z8IFyRWo5USOOa8GDdUrXSicRbJzN2yF+Aaxh8Ru7e9cYc2CuvHH580vN6q
            cs7rhzyeGJQE7EH+C7sc7e2Pt/QrBxv2HIM+P6JE4eCOkPbdtYbfL3Y=
            -----END CERTIFICATE-----
        """.trimIndent()
        val MISSING_EKU = """
            -----BEGIN CERTIFICATE-----
            MIIDEDCCAfigAwIBAgIBBTANBgkqhkiG9w0BAQsFADAaMRgwFgYDVQQDDA9nYXRl
            d2F5LmV4YW1wbGUwIBcNMjUwMTAxMDAwMDAwWhgPMjEwMDAxMDEwMDAwMDBaMBox
            GDAWBgNVBAMMD2dhdGV3YXkuZXhhbXBsZTCCASIwDQYJKoZIhvcNAQEBBQADggEP
            ADCCAQoCggEBANnq50kkzNv0NkoZJ/sht0ChpmOIiMpzhAFD05TC5RYMyjOghUig
            3TRbkXgn6lF8vuYuucD2TrHCKaVLXPiFG+IsP8V9fRttmYATRCPstnSa1z5OHhue
            caCqfJQtSX2/qN+ZZBWQtwm5AyaiTsSt649kE1Ovgxp/f2x5pwrQCB6qGRU0tk+4
            aC8D9cJ3lIn9LnrWQl6zW13mlkuqDriV3DBHKeKMOmqacrKlDY2w1ycWzB+cN7RY
            SgD3r9HxE7L7kgCGe5MS6hWbUksdRSij/QOfRmcVzzM/AH7PX5a7XeSy/jCK4uxB
            sVambVYoCmVcXBF6yIJpDc96JywjSya5IKcCAwEAAaNfMF0wDAYDVR0TAQH/BAIw
            ADA9BgNVHREENjA0gg9nYXRld2F5LmV4YW1wbGWCCWxvY2FsaG9zdIcEfwAAAYcQ
            AAAAAAAAAAAAAAAAAAAAATAOBgNVHQ8BAf8EBAMCBaAwDQYJKoZIhvcNAQELBQAD
            ggEBAFHt6vVD/rj8Me33K1kkkxAUeEvfYYrikGRSuyWhod0XGPJ/G3CQuhYyBlN1
            jiNMztxypKgm5Kfl5L1KFEBSJp8gkfP+XpoI5n/KzgZlf2VOHEQJDh5HKdlwzbjU
            Rym96iAubedmb34OB71PS9cF5CZcxHLkXIIYw1a1NqCFyu07ECV0xcP5yy+Ip7/r
            fSIzMz9PtausdxvHbq/BMPkDNru6DirQSXPECNb5/Qcs7JA0HSjyzrL5aOGVDPl5
            iBjuAg9bwFGbJxL/kIbIvF9HovNDsk27BmOaGnok+fACpjxM0YX+3XiQEZZXUIOc
            lcMfa3h3N2GmVoN5twATVbNeEcI=
            -----END CERTIFICATE-----
        """.trimIndent()
        val COMMON_NAME_ONLY = """
            -----BEGIN CERTIFICATE-----
            MIIC5jCCAc6gAwIBAgIBBjANBgkqhkiG9w0BAQsFADAaMRgwFgYDVQQDDA9nYXRl
            d2F5LmV4YW1wbGUwIBcNMjUwMTAxMDAwMDAwWhgPMjEwMDAxMDEwMDAwMDBaMBox
            GDAWBgNVBAMMD2dhdGV3YXkuZXhhbXBsZTCCASIwDQYJKoZIhvcNAQEBBQADggEP
            ADCCAQoCggEBANnq50kkzNv0NkoZJ/sht0ChpmOIiMpzhAFD05TC5RYMyjOghUig
            3TRbkXgn6lF8vuYuucD2TrHCKaVLXPiFG+IsP8V9fRttmYATRCPstnSa1z5OHhue
            caCqfJQtSX2/qN+ZZBWQtwm5AyaiTsSt649kE1Ovgxp/f2x5pwrQCB6qGRU0tk+4
            aC8D9cJ3lIn9LnrWQl6zW13mlkuqDriV3DBHKeKMOmqacrKlDY2w1ycWzB+cN7RY
            SgD3r9HxE7L7kgCGe5MS6hWbUksdRSij/QOfRmcVzzM/AH7PX5a7XeSy/jCK4uxB
            sVambVYoCmVcXBF6yIJpDc96JywjSya5IKcCAwEAAaM1MDMwDAYDVR0TAQH/BAIw
            ADATBgNVHSUEDDAKBggrBgEFBQcDATAOBgNVHQ8BAf8EBAMCBaAwDQYJKoZIhvcN
            AQELBQADggEBAIisKEZs07ptNrlzuLHS0eB4TRaFkC63e/5IOMcWgL5p/KTbtTsM
            Ct+l0ZBwAHwgPJYJQbDxLdJ2v4Z1qMsKswJe1hhJLWp9NJfUkeCaZpxonhG8Ln6l
            DcIY3CBNUQQYfUoAi8SwhgTcc3AExkIAibwOeVJW4ralbKf9j1GOTF2TJN/zleFH
            zHG3BZCBtSba8xGIe7eJ3JK7HpI0LUvxjfRB0vFt5UUOzwAA7hkJUP972jYHvU9U
            ZPK+MECxvGegsXoiiYD4DejgkbLe9drpE+l54oze8pIEX7WGtR5ecafisqOmgmyY
            kpXNEWadfjU7fVpI7ASX7F4RHOZ4RFrNdVw=
            -----END CERTIFICATE-----
        """.trimIndent()
        val WILDCARD = """
            -----BEGIN CERTIFICATE-----
            MIIC/DCCAeSgAwIBAgIBBzANBgkqhkiG9w0BAQsFADAaMRgwFgYDVQQDDA9nYXRl
            d2F5LmV4YW1wbGUwIBcNMjUwMTAxMDAwMDAwWhgPMjEwMDAxMDEwMDAwMDBaMBox
            GDAWBgNVBAMMD2dhdGV3YXkuZXhhbXBsZTCCASIwDQYJKoZIhvcNAQEBBQADggEP
            ADCCAQoCggEBANnq50kkzNv0NkoZJ/sht0ChpmOIiMpzhAFD05TC5RYMyjOghUig
            3TRbkXgn6lF8vuYuucD2TrHCKaVLXPiFG+IsP8V9fRttmYATRCPstnSa1z5OHhue
            caCqfJQtSX2/qN+ZZBWQtwm5AyaiTsSt649kE1Ovgxp/f2x5pwrQCB6qGRU0tk+4
            aC8D9cJ3lIn9LnrWQl6zW13mlkuqDriV3DBHKeKMOmqacrKlDY2w1ycWzB+cN7RY
            SgD3r9HxE7L7kgCGe5MS6hWbUksdRSij/QOfRmcVzzM/AH7PX5a7XeSy/jCK4uxB
            sVambVYoCmVcXBF6yIJpDc96JywjSya5IKcCAwEAAaNLMEkwDAYDVR0TAQH/BAIw
            ADATBgNVHSUEDDAKBggrBgEFBQcDATAUBgNVHREEDTALggkqLmV4YW1wbGUwDgYD
            VR0PAQH/BAQDAgWgMA0GCSqGSIb3DQEBCwUAA4IBAQAndBuqm1I/ZY/OZWQtnCYw
            fvAM4qhCTW0ufwekBdWtxBdaoOnbzceH7c+oLdrfitwIH7uYP+3nJsu0iBp47Lhm
            z9K48gTQUddGHRD/TkLTk44KvaaxiEtm4fBntTYQShD75+Z6QVDsT3Z+weJS1ItN
            v1XoId7Fdo7hlmPzSAvDQCYAa14Vg85Jt71nGXsjvc4YXheQaI1KVRpENopBer5y
            nWtVypBdGwlwSiwTDMrSk0biZscF+9nnUU9HfsEsvAEWMRHsyJW0QLwF5S2XNjiD
            FSdZuS02AN7MWPWJrHXM7hgafrlD2xyI0jJGvqMUZqOtlvNPWruWdlWZwujW9aPw
            -----END CERTIFICATE-----
        """.trimIndent()
        val IP_AS_DNS = """
            -----BEGIN CERTIFICATE-----
            MIIC/DCCAeSgAwIBAgIBCDANBgkqhkiG9w0BAQsFADAaMRgwFgYDVQQDDA9nYXRl
            d2F5LmV4YW1wbGUwIBcNMjUwMTAxMDAwMDAwWhgPMjEwMDAxMDEwMDAwMDBaMBox
            GDAWBgNVBAMMD2dhdGV3YXkuZXhhbXBsZTCCASIwDQYJKoZIhvcNAQEBBQADggEP
            ADCCAQoCggEBANnq50kkzNv0NkoZJ/sht0ChpmOIiMpzhAFD05TC5RYMyjOghUig
            3TRbkXgn6lF8vuYuucD2TrHCKaVLXPiFG+IsP8V9fRttmYATRCPstnSa1z5OHhue
            caCqfJQtSX2/qN+ZZBWQtwm5AyaiTsSt649kE1Ovgxp/f2x5pwrQCB6qGRU0tk+4
            aC8D9cJ3lIn9LnrWQl6zW13mlkuqDriV3DBHKeKMOmqacrKlDY2w1ycWzB+cN7RY
            SgD3r9HxE7L7kgCGe5MS6hWbUksdRSij/QOfRmcVzzM/AH7PX5a7XeSy/jCK4uxB
            sVambVYoCmVcXBF6yIJpDc96JywjSya5IKcCAwEAAaNLMEkwDAYDVR0TAQH/BAIw
            ADATBgNVHSUEDDAKBggrBgEFBQcDATAUBgNVHREEDTALggkxMjcuMC4wLjEwDgYD
            VR0PAQH/BAQDAgWgMA0GCSqGSIb3DQEBCwUAA4IBAQCezlki7Ef93kvoNi3crxRy
            LNETEZdsb4EBJQo1inzLtnyCeZUKvS/rmmvjvBfnSK3RXxf6EsUz+Mj1SEtkaGi/
            v3FTVSc16gyAfuDGaVCPW9qpwVOhGzpY1qN/yHLa+cr5PE2+nh50A1ukEep67LZr
            D3smuT82eVVKpdZw+k6/zIeXqEnPsH7Ts9rGc2NfhrHMFt6WUsUURPtKyz1ZAteo
            Fftf3smNyQl5FXQbKtYnILbSLhnGhM42NI3qCh6JdqvsJCvq5ytTJJ1eMpCDAtUE
            7WIMbJM+NW/v/LPeNFqrq9Lz7JqPrqNOXGYYcaQNo7U+x8V1kRaNY1E7iwjCPboI
            -----END CERTIFICATE-----
        """.trimIndent()
        val REMOTE_ONLY = """
            -----BEGIN CERTIFICATE-----
            MIIDAjCCAeqgAwIBAgIBCTANBgkqhkiG9w0BAQsFADAaMRgwFgYDVQQDDA9nYXRl
            d2F5LmV4YW1wbGUwIBcNMjUwMTAxMDAwMDAwWhgPMjEwMDAxMDEwMDAwMDBaMBox
            GDAWBgNVBAMMD2dhdGV3YXkuZXhhbXBsZTCCASIwDQYJKoZIhvcNAQEBBQADggEP
            ADCCAQoCggEBANnq50kkzNv0NkoZJ/sht0ChpmOIiMpzhAFD05TC5RYMyjOghUig
            3TRbkXgn6lF8vuYuucD2TrHCKaVLXPiFG+IsP8V9fRttmYATRCPstnSa1z5OHhue
            caCqfJQtSX2/qN+ZZBWQtwm5AyaiTsSt649kE1Ovgxp/f2x5pwrQCB6qGRU0tk+4
            aC8D9cJ3lIn9LnrWQl6zW13mlkuqDriV3DBHKeKMOmqacrKlDY2w1ycWzB+cN7RY
            SgD3r9HxE7L7kgCGe5MS6hWbUksdRSij/QOfRmcVzzM/AH7PX5a7XeSy/jCK4uxB
            sVambVYoCmVcXBF6yIJpDc96JywjSya5IKcCAwEAAaNRME8wDAYDVR0TAQH/BAIw
            ADATBgNVHSUEDDAKBggrBgEFBQcDATAaBgNVHREEEzARgg9nYXRld2F5LmV4YW1w
            bGUwDgYDVR0PAQH/BAQDAgWgMA0GCSqGSIb3DQEBCwUAA4IBAQB/Oent3Wq1aKzp
            jP4Z7KLjqMMjBcv7DwWjiuQJiqOSUu0Dy+thk8H+bALcYzSjMXgpMtzLexITocVU
            NA09JcULwpQzJ2czXqmDyrDA5o/AjGTClvP9A9XaeJRaac3HKcKXlI/l8qr/SVVr
            1ojC5+554Z7Bf4/HL5BA4y889Tc6WBRyQZU0bBc5QNB+7KXVCSXxT1xIczEZqLKV
            CBnhC1fSWncfVETIMH2YHPFSdJHRosi6+45Hs0nDaysMaySeYm2RWbPkJTf+UXdj
            tOF5Ki3kjCjihhb0AScoXUhmFQntCFj4t8QIeAHDnwENs9AVNxbuhHP2gUSp+Bie
            5Pmrk3dn
            -----END CERTIFICATE-----
        """.trimIndent()
        val WEAK_RSA = """
            -----BEGIN CERTIFICATE-----
            MIICIDCCAYmgAwIBAgIBCjANBgkqhkiG9w0BAQsFADAaMRgwFgYDVQQDDA9nYXRl
            d2F5LmV4YW1wbGUwIBcNMjUwMTAxMDAwMDAwWhgPMjEwMDAxMDEwMDAwMDBaMBox
            GDAWBgNVBAMMD2dhdGV3YXkuZXhhbXBsZTCBnzANBgkqhkiG9w0BAQEFAAOBjQAw
            gYkCgYEA16U35p+/bbpWGNKkPSlsUEX0MEvbasceQ5Cbz8nbXDNuaq6EMJDvE5rO
            ketkxJ3E72YxBnErgLAO5ozTK1h1/APQgBeiURlcK75sDEJZ7EYk0+jckM3DLAg/
            HrvkYMFAp/uKPjQRdbmcvtJNjAG2bVc1XoIAntcRpcU/8y1IaX8CAwEAAaN0MHIw
            DAYDVR0TAQH/BAIwADATBgNVHSUEDDAKBggrBgEFBQcDATA9BgNVHREENjA0gg9n
            YXRld2F5LmV4YW1wbGWCCWxvY2FsaG9zdIcEfwAAAYcQAAAAAAAAAAAAAAAAAAAA
            ATAOBgNVHQ8BAf8EBAMCBaAwDQYJKoZIhvcNAQELBQADgYEAjOoLpXghAf737Gla
            tgWJpeuheMGm+gweKqaIMEbm0pXXyraUa4cDqz44Vep84UhwgCEj7Hq82Ftgi/TJ
            Y1SC1hH3IVQKaR4fwEDL9S3qc93s/qvc6xqibKLTCYw+0ov2j5DjV9HxEtL6cQMM
            HzhZEADMePEHDOsnIR+AX8FfLlA=
            -----END CERTIFICATE-----
        """.trimIndent()
        val VALID_EC = """
            -----BEGIN CERTIFICATE-----
            MIIBmTCCAT+gAwIBAgIBCzAKBggqhkjOPQQDAjAaMRgwFgYDVQQDDA9nYXRld2F5
            LmV4YW1wbGUwIBcNMjUwMTAxMDAwMDAwWhgPMjEwMDAxMDEwMDAwMDBaMBoxGDAW
            BgNVBAMMD2dhdGV3YXkuZXhhbXBsZTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IA
            BDLHIKgqKkfA3n1KjoldCgJFbv3RWgk6kS2XbbpC90Os0L6k3IhlmE7BL4bxxBx1
            SesJJh7kO/s7nd0saYzZVY2jdDByMAwGA1UdEwEB/wQCMAAwEwYDVR0lBAwwCgYI
            KwYBBQUHAwEwPQYDVR0RBDYwNIIPZ2F0ZXdheS5leGFtcGxlgglsb2NhbGhvc3SH
            BH8AAAGHEAAAAAAAAAAAAAAAAAAAAAEwDgYDVR0PAQH/BAQDAgWgMAoGCCqGSM49
            BAMCA0gAMEUCIH9Afqt1L2U4qRCaJmPBHFHsOoKZRoC8TyT7bSQJXb6EAiEAqbA8
            7ZUkU5Jcb/khFvgv1FdQL7m97vsg0xmic0bj04o=
            -----END CERTIFICATE-----
        """.trimIndent()
        val WEAK_EC = """
            -----BEGIN CERTIFICATE-----
            MIIBhTCCATSgAwIBAgIBDDAKBggqhkjOPQQDAjAaMRgwFgYDVQQDDA9nYXRld2F5
            LmV4YW1wbGUwIBcNMjUwMTAxMDAwMDAwWhgPMjEwMDAxMDEwMDAwMDBaMBoxGDAW
            BgNVBAMMD2dhdGV3YXkuZXhhbXBsZTBOMBAGByqGSM49AgEGBSuBBAAhAzoABLan
            wkKjsx7lQp7mNaJ5AqxvqfPt2KSvWUC2XkosmVMNqopsHnY1JeNcHdr6qI+E1JSm
            GUnRGBM2o3QwcjAMBgNVHRMBAf8EAjAAMBMGA1UdJQQMMAoGCCsGAQUFBwMBMD0G
            A1UdEQQ2MDSCD2dhdGV3YXkuZXhhbXBsZYIJbG9jYWxob3N0hwR/AAABhxAAAAAA
            AAAAAAAAAAAAAAABMA4GA1UdDwEB/wQEAwIFoDAKBggqhkjOPQQDAgM/ADA8AhxW
            yVSKM1x+fkVunBi2EnvGi6Isgo6sf6GZH4W2AhwY2tVJFeyj79WwmmijSSPa7sJ0
            IySsQlTnMXlp
            -----END CERTIFICATE-----
        """.trimIndent()
        val EXPIRED = """
            -----BEGIN CERTIFICATE-----
            MIIDIzCCAgugAwIBAgIBDTANBgkqhkiG9w0BAQsFADAaMRgwFgYDVQQDDA9nYXRl
            d2F5LmV4YW1wbGUwHhcNMTkwMTAxMDAwMDAwWhcNMjAwMTAxMDAwMDAwWjAaMRgw
            FgYDVQQDDA9nYXRld2F5LmV4YW1wbGUwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAw
            ggEKAoIBAQDZ6udJJMzb9DZKGSf7IbdAoaZjiIjKc4QBQ9OUwuUWDMozoIVIoN00
            W5F4J+pRfL7mLrnA9k6xwimlS1z4hRviLD/FfX0bbZmAE0Qj7LZ0mtc+Th4bnnGg
            qnyULUl9v6jfmWQVkLcJuQMmok7EreuPZBNTr4Maf39seacK0AgeqhkVNLZPuGgv
            A/XCd5SJ/S561kJes1td5pZLqg64ldwwRynijDpqmnKypQ2NsNcnFswfnDe0WEoA
            96/R8ROy+5IAhnuTEuoVm1JLHUUoo/0Dn0ZnFc8zPwB+z1+Wu13ksv4wiuLsQbFW
            pm1WKAplXFwResiCaQ3PeicsI0smuSCnAgMBAAGjdDByMAwGA1UdEwEB/wQCMAAw
            EwYDVR0lBAwwCgYIKwYBBQUHAwEwPQYDVR0RBDYwNIIPZ2F0ZXdheS5leGFtcGxl
            gglsb2NhbGhvc3SHBH8AAAGHEAAAAAAAAAAAAAAAAAAAAAEwDgYDVR0PAQH/BAQD
            AgWgMA0GCSqGSIb3DQEBCwUAA4IBAQBIcq2WeFIS/PCT1XEG43ksEEZ4wvgybit5
            oG0Yp/iaMjmGW+JqjQ7JXi350X4ajBP6x8p0FT0U0InyEgQOxAOYYzeqkT6UXHjl
            ONRqRH97qMVeMpetTINNdcHHRTRsqYVyTnGjPr5Nc4XKxZS4rUypa9aS0ZcTmNO9
            5P4yg7RwieBLiN0/jSw7hADLG751oSyAzUcyqY87FX3f6acZ/pZwaA0lYd1iY8mC
            +VERpvFHxqkW5A095lK0Zx2jnBQVlebgnz4E8bSaO2wMam3mzQ8Ii7gH4hUlQHl+
            6pZ8SZsj6YDdLsDmY4zU46jyiOE2JohZff7HmUuWoxEzn6W6T/dp
            -----END CERTIFICATE-----
        """.trimIndent()
        val FUTURE = """
            -----BEGIN CERTIFICATE-----
            MIIDJzCCAg+gAwIBAgIBDjANBgkqhkiG9w0BAQsFADAaMRgwFgYDVQQDDA9nYXRl
            d2F5LmV4YW1wbGUwIhgPMjEyMDAxMDEwMDAwMDBaGA8yMTIxMDEwMTAwMDAwMFow
            GjEYMBYGA1UEAwwPZ2F0ZXdheS5leGFtcGxlMIIBIjANBgkqhkiG9w0BAQEFAAOC
            AQ8AMIIBCgKCAQEA2ernSSTM2/Q2Shkn+yG3QKGmY4iIynOEAUPTlMLlFgzKM6CF
            SKDdNFuReCfqUXy+5i65wPZOscIppUtc+IUb4iw/xX19G22ZgBNEI+y2dJrXPk4e
            G55xoKp8lC1Jfb+o35lkFZC3CbkDJqJOxK3rj2QTU6+DGn9/bHmnCtAIHqoZFTS2
            T7hoLwP1wneUif0uetZCXrNbXeaWS6oOuJXcMEcp4ow6appysqUNjbDXJxbMH5w3
            tFhKAPev0fETsvuSAIZ7kxLqFZtSSx1FKKP9A59GZxXPMz8Afs9flrtd5LL+MIri
            7EGxVqZtVigKZVxcEXrIgmkNz3onLCNLJrkgpwIDAQABo3QwcjAMBgNVHRMBAf8E
            AjAAMBMGA1UdJQQMMAoGCCsGAQUFBwMBMD0GA1UdEQQ2MDSCD2dhdGV3YXkuZXhh
            bXBsZYIJbG9jYWxob3N0hwR/AAABhxAAAAAAAAAAAAAAAAAAAAABMA4GA1UdDwEB
            /wQEAwIFoDANBgkqhkiG9w0BAQsFAAOCAQEASTFUZ4ChWxwr+X7jLrbCOsuESXgx
            owsTich/+NqIHLbsrnfdumxWYPVYRdPaoOQRLC+k+fkMUpA2PnuZyXRH+q5qXDCe
            SAMI8j1CxshZLopsscYPctS3B2UIalZIzRjb/TLZV60ajs+zkS3RHw7v2uYRYDAL
            rFe0yX13sT+//NTviev6rnbUjeCkDZA+1iQKAWLE1CjNs+91+Xhq8x50xp1+yJCV
            LM4uh1pE2LgfIi58DYR3UtTHZA4QTrnUS5hHIjYy48MiChw4EERaQnbdykbzK+nu
            70DT/vHmISv8ESFQ4ZRLFm0/Jp9O0+uNQibJUKksORc0DcK8Wm8Xm6bw4g==
            -----END CERTIFICATE-----
        """.trimIndent()
        val UNKNOWN_CRITICAL = """
            -----BEGIN CERTIFICATE-----
            MIIDNzCCAh+gAwIBAgIBDzANBgkqhkiG9w0BAQsFADAaMRgwFgYDVQQDDA9nYXRl
            d2F5LmV4YW1wbGUwIBcNMjUwMTAxMDAwMDAwWhgPMjEwMDAxMDEwMDAwMDBaMBox
            GDAWBgNVBAMMD2dhdGV3YXkuZXhhbXBsZTCCASIwDQYJKoZIhvcNAQEBBQADggEP
            ADCCAQoCggEBANnq50kkzNv0NkoZJ/sht0ChpmOIiMpzhAFD05TC5RYMyjOghUig
            3TRbkXgn6lF8vuYuucD2TrHCKaVLXPiFG+IsP8V9fRttmYATRCPstnSa1z5OHhue
            caCqfJQtSX2/qN+ZZBWQtwm5AyaiTsSt649kE1Ovgxp/f2x5pwrQCB6qGRU0tk+4
            aC8D9cJ3lIn9LnrWQl6zW13mlkuqDriV3DBHKeKMOmqacrKlDY2w1ycWzB+cN7RY
            SgD3r9HxE7L7kgCGe5MS6hWbUksdRSij/QOfRmcVzzM/AH7PX5a7XeSy/jCK4uxB
            sVambVYoCmVcXBF6yIJpDc96JywjSya5IKcCAwEAAaOBhTCBgjAMBgNVHRMBAf8E
            AjAAMBMGA1UdJQQMMAoGCCsGAQUFBwMBMD0GA1UdEQQ2MDSCD2dhdGV3YXkuZXhh
            bXBsZYIJbG9jYWxob3N0hwR/AAABhxAAAAAAAAAAAAAAAAAAAAABMA4GA1UdDwEB
            /wQEAwIFoDAOBgUqAwSsLgEB/wQCBQAwDQYJKoZIhvcNAQELBQADggEBAAUlfkBy
            jEukz3qGTt+TwjsClbGzIJx4RsAWtfO9Yo/hq5Q+xTyeoe95mApST+eNUSbE2hi4
            3QRgEgZie4o13rpAN77aUoNvfbW7izC9o7j3kDAVO1Y52eAt6p31tbC0zwDNdF1s
            Y4s4MYqCJ1xX2v0zFdRKKHQgeK9eYgXzwLf08EyElDy1mzyWw0dx5ig3VadabfIy
            sIDcaExNwf2u71qzlcqYo1HMBtwRpk8PSmD3SSU1k3MadzUoChFA//L1TOQBD0Vl
            uhmTB6dWsLjPBhefopII2bURhpJfa3P882BiXam6E4OarHKUGMIaXONBI+ZZJtBV
            QYV0WLhdbYUiD2w=
            -----END CERTIFICATE-----
        """.trimIndent()
        val CA_SIGNED_LEAF = """
            -----BEGIN CERTIFICATE-----
            MIIDBzCCAe+gAwIBAgIBZjANBgkqhkiG9w0BAQsFADAfMR0wGwYDVQQDDBRTeW50
            aGV0aWMgZml4dHVyZSBDQTAgFw0yNTAxMDEwMDAwMDBaGA8yMTAwMDEwMTAwMDAw
            MFowGjEYMBYGA1UEAwwPZ2F0ZXdheS5leGFtcGxlMIIBIjANBgkqhkiG9w0BAQEF
            AAOCAQ8AMIIBCgKCAQEAn7kTzHHn+nMe7TC9ErGCz9vKQaqLYN1I2e+YSUtqilBv
            ZbqW8mU9YNvpKtsoWFudXN1dXJpKfuA6+vJ56WhWc4zexhcZVuLzyqcT7l+6KwC6
            FAvW9h2mX3xKCaJmUZ0LowidmnA2rckFXdKWW1M1s2pjn7kZiWiTEs7qjIJSrbmU
            xoZXiW0x40VqYSyhj06j5TAB5JyDttKEOsmO1Neqa5+Y0OGHtQKkh82+tFczeLIs
            cJ8R56Oro7n4tirQ0WlkqgfIYcvq9hj7IkvD82idZSo328UIxK2In4PGn7ZPHE8h
            nTPEE0qTb69MxSsc2GRjVrjl7uO6NEuSSxooB23maQIDAQABo1EwTzAMBgNVHRMB
            Af8EAjAAMBMGA1UdJQQMMAoGCCsGAQUFBwMBMBoGA1UdEQQTMBGCD2dhdGV3YXku
            ZXhhbXBsZTAOBgNVHQ8BAf8EBAMCBaAwDQYJKoZIhvcNAQELBQADggEBAJWjCiLS
            YGm++JUDSxNr56h6OV1srCGatLQsdZ8aETuXiSIwiN7j2OlS4dL03ztjWMwoHlrp
            UyosFJAu4QCItMS88peBdt4jT1xcD6rBcQ9A2PTQm1LaGD7fFiec/RTCSeGvZ5IH
            dZSIF02lloGyAJyD+qyO6FttUeOwtZdR1Vktg9TU1Lt1T68qzMo2WeqlKdLoKnvp
            TRx7OlMNRzwklc52ae74EqTGTcTU3WZ+0sOZoN1ROOsHMzXb6v5fRRUuExA+IJ3K
            gakyPxC1/3tDpY75wxtEe+TU/Enrp8ogxDFe0ykEcr6pCNNCqELzYgV7/84y7KaE
            7O9v4h5XcQU5azc=
            -----END CERTIFICATE-----
        """.trimIndent()
        val MISSING_KU = """
            -----BEGIN CERTIFICATE-----
            MIIC9zCCAd+gAwIBAgIBZjANBgkqhkiG9w0BAQsFADAfMR0wGwYDVQQDDBRTeW50
            aGV0aWMgZml4dHVyZSBDQTAgFw0yNTAxMDEwMDAwMDBaGA8yMTAwMDEwMTAwMDAw
            MFowGjEYMBYGA1UEAwwPZ2F0ZXdheS5leGFtcGxlMIIBIjANBgkqhkiG9w0BAQEF
            AAOCAQ8AMIIBCgKCAQEAn7kTzHHn+nMe7TC9ErGCz9vKQaqLYN1I2e+YSUtqilBv
            ZbqW8mU9YNvpKtsoWFudXN1dXJpKfuA6+vJ56WhWc4zexhcZVuLzyqcT7l+6KwC6
            FAvW9h2mX3xKCaJmUZ0LowidmnA2rckFXdKWW1M1s2pjn7kZiWiTEs7qjIJSrbmU
            xoZXiW0x40VqYSyhj06j5TAB5JyDttKEOsmO1Neqa5+Y0OGHtQKkh82+tFczeLIs
            cJ8R56Oro7n4tirQ0WlkqgfIYcvq9hj7IkvD82idZSo328UIxK2In4PGn7ZPHE8h
            nTPEE0qTb69MxSsc2GRjVrjl7uO6NEuSSxooB23maQIDAQABo0EwPzAMBgNVHRMB
            Af8EAjAAMBMGA1UdJQQMMAoGCCsGAQUFBwMBMBoGA1UdEQQTMBGCD2dhdGV3YXku
            ZXhhbXBsZTANBgkqhkiG9w0BAQsFAAOCAQEA3lv7oBU+hJ9qOnMu628Mz6dOKWvk
            +Q5/RV7w8ABqqNnQR2OU1cmjmTkvAeKBw+lZ9VXoMUp9wRrFs4RlnnvrotN//hit
            QXyRzTkXxCe4mNbS30E4WRl0qPYaQtWMoDn0q0qWFx3IVG1L/EoZHkqe5rV74sSw
            lmBFpZnfWO/+z+W/PAXbW7bXi+2k9hVnFnyqmw8j6ufX6tAZfLZuvIk66uQ3aBVr
            L0O8bUIMRp+Dw6+KwEZVZp2OpHq4uD6lLj6Z1VabQbq3OaphUISwQDwcbOneMDBR
            JLKg01qZesYkDbqCu/octLEgmk803YFg1vFWaKNf/+8Ly0+u/Vv1pGZp2A==
            -----END CERTIFICATE-----
        """.trimIndent()
        val TEST_PRIVATE_KEY = """
            -----BEGIN PRIVATE KEY-----
            MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQDZ6udJJMzb9DZK
            GSf7IbdAoaZjiIjKc4QBQ9OUwuUWDMozoIVIoN00W5F4J+pRfL7mLrnA9k6xwiml
            S1z4hRviLD/FfX0bbZmAE0Qj7LZ0mtc+Th4bnnGgqnyULUl9v6jfmWQVkLcJuQMm
            ok7EreuPZBNTr4Maf39seacK0AgeqhkVNLZPuGgvA/XCd5SJ/S561kJes1td5pZL
            qg64ldwwRynijDpqmnKypQ2NsNcnFswfnDe0WEoA96/R8ROy+5IAhnuTEuoVm1JL
            HUUoo/0Dn0ZnFc8zPwB+z1+Wu13ksv4wiuLsQbFWpm1WKAplXFwResiCaQ3Peics
            I0smuSCnAgMBAAECggEAbOzwgAyvg7Bt5Jrvc6WWOm2yxGTOb1fPX2x+WYkTxnJN
            LgIqLTQPbejpbUSSaPnwWBF6ExxrGIpjk7Z4edbbKbIRjE9eFjQx+qVmtYhQaTeu
            qHuWrBqGVJdzXwPGDN+c82tybh8ghvEeRJTzx3bh3JJa4MVFxJx/XoLufsaekGhs
            qM8+qjfyasJz+HU58fyR1uUl6BWb4BOnJHoUdNr9g2IdBCfSthIE3hN4VgP5amwC
            bN0uAZ9uD8b/XGbaLmQgNCScz2DNkWuXILIhRtARwEsxEmGA1wvb9NWvTtp1BAsF
            MWHTGimodVB9XfWEyyBdapjuRX0M37HKzpEfLnBu+QKBgQDwdV+w2owRiOvUrVHM
            mctcM+kyT5omBj9lSKui7gCzx6iuiulAHWncpbKLFdOUJIjrIOFeFHMonNLcOSgM
            xvNYFjoKzmvhn8+ix5jcyJsGipMfGNIFbAwKl3Y/I+unYeHRMvYxRoao8JKHsw0q
            BtJxs1b75cblhdFmAXx6Bu+FaQKBgQDoAJFkfUy3x+Plvuv817H5IuFpl0dI17qk
            BEUSKw9svvGitwVg3wPy3KU/6PQI1nHBNp/J4B8GUbhXRMJdQvQQOqrF3Pyc614Y
            SLiHTHHRIIyzHX8Eu3mxJdt71oSVMlghiO0AO5aidJETkJ27k4SH4efibWoCZVt6
            ba7vOuZjjwKBgQDtvk8fT+XfTG5HeBoBXioH/rdZbATwBYtWu/QfgowlNIoeEPAv
            2SUa6KVQCQOJXFoXiAnrd/KoO+3rmYN0ppxhApDesFtP2C92NTx5RsqoPAjcXTEB
            9WMucbSTFxPfCdM4gNeTvrRTgpCOJMUmQVp+2p9tLTFrK9Tusxd8mzDkyQKBgQDF
            g+5EQlS1Ri2k5EVfDneLpoGH9u4OevOAPc5cKGLKb0mjAPRIQ7XF5smdPoO8nm1c
            VFf3c6KWK66oRXbAFYdZ5ciMCZbbNZr3S7omHhPsOizvNRZ1ffKduaBBryFTL/hO
            HJfBkfmtt1Xn9UAEeRPk55QWdpZBEe+urIC5VUjdywKBgQDagpa+2RUThLucFpeG
            6gMyA5sDUkOf+DbxG+gdI4HrXrQz5ff6Nr1KOWBPpkgBFwvS9XF6tqRsn9ASccSK
            lsSJsXJT++/CFO/fGq8+axDqlRrjf01rU/4f/BhMqeVL7YQTZPlEOduBfZ9FnHNH
            D0gIUAMnKRsa2d13Eq2jnHVYRQ==
            -----END PRIVATE KEY-----
        """.trimIndent()
    }
}
