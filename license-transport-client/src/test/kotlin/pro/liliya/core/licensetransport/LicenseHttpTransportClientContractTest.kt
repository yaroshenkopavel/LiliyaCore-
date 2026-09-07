package pro.liliya.core.licensetransport

import java.net.URL
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseServiceOperation
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseSubject

class LicenseHttpTransportClientContractTest {
    @Test
    fun config_requires_finite_positive_timeouts_and_https_by_default() {
        assertFailsWith<IllegalArgumentException> {
            LicenseHttpTransportConfig(
                endpoint = URL("https://license.example/v1/license"),
                connectTimeoutMillis = 0,
                readTimeoutMillis = 1000
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LicenseHttpTransportConfig(
                endpoint = URL("http://license.example/v1/license"),
                connectTimeoutMillis = 1000,
                readTimeoutMillis = 1000
            )
        }

        val config = LicenseHttpTransportConfig(
            endpoint = URL("https://license.example/v1/license"),
            connectTimeoutMillis = 1500,
            readTimeoutMillis = 2500
        )

        assertEquals(1, config.attemptLimit)
    }

    @Test
    fun signed_2xx_uses_one_engine_attempt_and_returns_unverified_envelope_only() {
        var calls = 0
        val engine = LicenseHttpEngine { _, _ ->
            calls++
            LicenseHttpEngineResult.Response(
                LicenseHttpEngineResponse(
                    status = 200,
                    body = signedSuccess()
                )
            )
        }
        val client = client(engine)

        val result = client.execute(request())

        assertEquals(1, calls)
        assertIs<LicenseClientTransportResult.Signed>(result)
    }

    @Test
    fun typed_4xx_service_rejection_is_not_retried() {
        var calls = 0
        val engine = LicenseHttpEngine { _, _ ->
            calls++
            LicenseHttpEngineResult.Response(
                LicenseHttpEngineResponse(
                    status = 409,
                    body = """
                        {"wireVersion":1,"kind":"rejected","reason":"REPLAY_CONFLICT"}
                    """.trimIndent().encodeToByteArray()
                )
            )
        }

        val result = client(engine).execute(request())

        assertEquals(1, calls)
        assertEquals(
            LicenseRemoteServiceFailure.REPLAY_CONFLICT,
            assertIs<LicenseClientTransportResult.ServiceRejected>(result).reason
        )
    }

    @Test
    fun five_xx_is_service_unavailable_even_if_body_looks_like_rejection() {
        var calls = 0
        val engine = LicenseHttpEngine { _, _ ->
            calls++
            LicenseHttpEngineResult.Response(
                LicenseHttpEngineResponse(
                    status = 503,
                    body = """
                        {"wireVersion":1,"kind":"rejected","reason":"SIGNING_KEY_UNAVAILABLE"}
                    """.trimIndent().encodeToByteArray()
                )
            )
        }

        val result = client(engine).execute(request())

        assertEquals(1, calls)
        assertEquals(
            LicenseClientTransportFailure.SERVICE_UNAVAILABLE,
            assertIs<LicenseClientTransportResult.Failed>(result).reason
        )
    }

    @Test
    fun malformed_2xx_body_is_protocol_failure_without_retry() {
        var calls = 0
        val engine = LicenseHttpEngine { _, _ ->
            calls++
            LicenseHttpEngineResult.Response(
                LicenseHttpEngineResponse(
                    status = 200,
                    body = "{broken".encodeToByteArray()
                )
            )
        }

        val result = client(engine).execute(request())

        assertEquals(1, calls)
        assertEquals(
            LicenseClientTransportFailure.PROTOCOL_FAILURE,
            assertIs<LicenseClientTransportResult.Failed>(result).reason
        )
    }

    @Test
    fun pre_cancelled_request_performs_zero_network_attempts() {
        var calls = 0
        val engine = LicenseHttpEngine { _, _ ->
            calls++
            error("must not execute")
        }
        val cancellation = LicenseTransportCancellation().apply { cancel() }

        val result = client(engine).execute(request(), cancellation)

        assertEquals(0, calls)
        assertEquals(
            LicenseClientTransportFailure.CANCELLED,
            assertIs<LicenseClientTransportResult.Failed>(result).reason
        )
    }

    @Test
    fun engine_failure_is_returned_exactly_without_hidden_retry() {
        var calls = 0
        val engine = LicenseHttpEngine { _, _ ->
            calls++
            LicenseHttpEngineResult.Failed(
                LicenseClientTransportFailure.TIMEOUT
            )
        }

        val result = client(engine).execute(request())

        assertEquals(1, calls)
        assertEquals(
            LicenseClientTransportFailure.TIMEOUT,
            assertIs<LicenseClientTransportResult.Failed>(result).reason
        )
    }

    private fun client(engine: LicenseHttpEngine) =
        LicenseHttpTransportClient(
            config = LicenseHttpTransportConfig(
                endpoint = URL("https://license.example/v1/license"),
                connectTimeoutMillis = 1000,
                readTimeoutMillis = 1000
            ),
            engine = engine
        )

    private fun request() =
        LicenseServiceTransportRequest(
            protocolVersion = LicenseServiceProtocolVersion(1),
            operation = LicenseServiceOperation.ISSUE,
            productId = LicenseProductId("liliya-pro"),
            subjectReference = LicenseSubject("subject-private"),
            requestId = LicenseServiceRequestId("attempt-001")
        )

    private fun signedSuccess(): ByteArray {
        val payload = byteArrayOf(1, 2, 3, 4)
        val signature = byteArrayOf(9, 8, 7)
        return """
            {
              "wireVersion":1,
              "kind":"success",
              "schemaVersion":1,
              "algorithm":"ECDSA-P256-SHA256",
              "keyReference":"openbao-prod-v2",
              "payloadBase64":"${Base64.getEncoder().encodeToString(payload)}",
              "signatureBase64":"${Base64.getEncoder().encodeToString(signature)}"
            }
        """.trimIndent().encodeToByteArray()
    }
}
