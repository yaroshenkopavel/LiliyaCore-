package pro.liliya.core.licensetransport

import java.net.URL
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseServiceOperation
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseSubject

class LicenseHttpTlsTrustContractTest {
    @Test
    fun explicit_trust_defensively_copies_certificate_material() {
        val original = byteArrayOf(1, 2, 3, 4)
        val trust = LicenseHttpTlsTrust.ofCertificate(original)

        original.fill(9)

        val copied = trust.copyCertificates().single()
        assertContentEquals(byteArrayOf(1, 2, 3, 4), copied)

        copied.fill(7)
        assertContentEquals(
            byteArrayOf(1, 2, 3, 4),
            trust.copyCertificates().single()
        )
    }

    @Test
    fun explicit_trust_is_forwarded_to_the_single_engine_attempt() {
        val trust = LicenseHttpTlsTrust.ofCertificate(byteArrayOf(1))
        var captured: LicenseHttpTlsTrust? = null
        var calls = 0
        val engine = LicenseHttpEngine { request, _ ->
            calls++
            captured = request.tlsTrust
            LicenseHttpEngineResult.Failed(
                LicenseClientTransportFailure.TIMEOUT
            )
        }
        val client = LicenseHttpTransportClient(
            config = LicenseHttpTransportConfig(
                endpoint = URL("https://license.example/v1/license"),
                connectTimeoutMillis = 1000,
                readTimeoutMillis = 1000,
                tlsTrust = trust
            ),
            engine = engine
        )

        val result = client.execute(request())

        assertEquals(1, calls)
        assertTrue(captured === trust)
        assertEquals(
            LicenseClientTransportFailure.TIMEOUT,
            assertIs<LicenseClientTransportResult.Failed>(result).reason
        )
    }

    @Test
    fun explicit_trust_is_rejected_for_insecure_http_even_in_development_mode() {
        val trust = LicenseHttpTlsTrust.ofCertificate(byteArrayOf(1))

        assertFailsWith<IllegalArgumentException> {
            LicenseHttpTransportConfig(
                endpoint = URL("http://127.0.0.1:8080/v1/license"),
                connectTimeoutMillis = 1000,
                readTimeoutMillis = 1000,
                developmentAllowInsecureHttp = true,
                tlsTrust = trust
            )
        }
    }

    @Test
    fun malformed_certificate_material_fails_closed_as_tls_failure() {
        val client = LicenseHttpTransportClient(
            LicenseHttpTransportConfig(
                endpoint = URL("https://127.0.0.1:1/v1/license"),
                connectTimeoutMillis = 1000,
                readTimeoutMillis = 1000,
                tlsTrust = LicenseHttpTlsTrust.ofCertificate(
                    "not-an-x509-certificate".encodeToByteArray()
                )
            )
        )

        val result = client.execute(request())

        assertEquals(
            LicenseClientTransportFailure.TLS_FAILURE,
            assertIs<LicenseClientTransportResult.Failed>(result).reason
        )
    }

    @Test
    fun trust_material_is_redacted_from_diagnostics() {
        val marker = "certificate-secret-looking-marker"
        val trust = LicenseHttpTlsTrust.ofCertificate(marker.encodeToByteArray())
        val config = LicenseHttpTransportConfig(
            endpoint = URL("https://license.example/v1/license"),
            connectTimeoutMillis = 1000,
            readTimeoutMillis = 1000,
            tlsTrust = trust
        )

        assertFalse(marker in trust.toString())
        assertFalse(marker in config.toString())
        assertTrue("explicitTlsTrust=1" in config.toString())
    }

    private fun request() =
        LicenseServiceTransportRequest(
            protocolVersion = LicenseServiceProtocolVersion(1),
            operation = LicenseServiceOperation.ISSUE,
            productId = LicenseProductId("liliya-pro"),
            subjectReference = LicenseSubject("subject-private"),
            requestId = LicenseServiceRequestId("tls-trust-contract")
        )
}
