package pro.liliya.core.licensetransport

import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseCanonicalPayload
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicenseSignature
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseVersion

class LicenseS6PrivacyNegativeContractTest {
    @Test
    fun transport_config_rendering_hides_userinfo_path_query_and_fragment() {
        val marker = "PRIVATE-ENDPOINT-MARKER"
        val config = LicenseHttpTransportConfig(
            endpoint = URL(
                "https://user:pass@license.example/private/$marker" +
                    "?token=$marker#$marker"
            ),
            connectTimeoutMillis = 1000,
            readTimeoutMillis = 2000
        )

        val rendered = config.toString()

        assertFalse("user:pass" in rendered)
        assertFalse(marker in rendered)
        assertFalse("token=" in rendered)
        assertEquals(true, "https://license.example/<redacted-path>" in rendered)
    }

    @Test
    fun engine_request_and_response_rendering_redact_body_bytes() {
        val marker = "PRIVATE-REMOTE-BODY-MARKER"
        val request = LicenseHttpEngineRequest(
            endpoint = URL("https://license.example/private/$marker"),
            connectTimeoutMillis = 1000,
            readTimeoutMillis = 2000,
            body = marker.encodeToByteArray()
        )
        val response = LicenseHttpEngineResponse(
            status = 500,
            body = marker.encodeToByteArray()
        )

        assertFalse(marker in request.toString())
        assertFalse(marker in response.toString())
        assertEquals(true, "body=<redacted>" in request.toString())
        assertEquals(true, "body=<redacted>" in response.toString())
    }

    @Test
    fun signed_result_rendering_does_not_expose_payload_or_signature() {
        val payloadMarker = "PRIVATE-PAYLOAD-MARKER"
        val signatureMarker = "PRIVATE-SIGNATURE-MARKER"
        val result = LicenseClientTransportResult.Signed(
            LicenseSignedEnvelope(
                schemaVersion = LicenseVersion(1),
                algorithm = LicenseAlgorithm("ECDSA-P256-SHA256"),
                signingKeyId = LicenseKeyId("key-v2"),
                payload = LicenseCanonicalPayload.of(payloadMarker.encodeToByteArray()),
                signature = LicenseSignature.of(signatureMarker.encodeToByteArray())
            )
        )

        val rendered = result.toString()

        assertFalse(payloadMarker in rendered)
        assertFalse(signatureMarker in rendered)
    }

    @Test
    fun success_status_with_rejection_body_is_protocol_failure() {
        val result = client(
            response(
                200,
                """{"wireVersion":1,"kind":"rejected","reason":"REFRESH_REJECTED"}"""
            )
        ).execute(request())

        assertEquals(
            LicenseClientTransportFailure.PROTOCOL_FAILURE,
            assertIs<LicenseClientTransportResult.Failed>(result).reason
        )
    }

    @Test
    fun client_error_status_with_signed_success_body_is_protocol_failure() {
        val result = client(
            response(
                409,
                signedWire()
            )
        ).execute(request())

        assertEquals(
            LicenseClientTransportFailure.PROTOCOL_FAILURE,
            assertIs<LicenseClientTransportResult.Failed>(result).reason
        )
    }

    @Test
    fun redirect_status_is_protocol_failure_and_not_treated_as_success() {
        var attempts = 0
        val client = LicenseHttpTransportClient(
            config = config(),
            engine = LicenseHttpEngine { _, _ ->
                attempts++
                response(302, "redirect body")
            }
        )

        val result = client.execute(request())

        assertEquals(1, attempts)
        assertEquals(
            LicenseClientTransportFailure.PROTOCOL_FAILURE,
            assertIs<LicenseClientTransportResult.Failed>(result).reason
        )
    }

    @Test
    fun server_error_secret_body_is_not_exposed() {
        val marker = "PRIVATE-SERVER-ERROR-MARKER"
        val result = client(
            response(503, marker)
        ).execute(request())

        val failed = assertIs<LicenseClientTransportResult.Failed>(result)
        assertEquals(LicenseClientTransportFailure.SERVICE_UNAVAILABLE, failed.reason)
        assertFalse(marker in failed.toString())
    }

    @Test
    fun injected_tls_and_connect_failures_are_typed_and_never_retried() {
        for (reason in listOf(
            LicenseClientTransportFailure.TLS_FAILURE,
            LicenseClientTransportFailure.CONNECT_FAILURE
        )) {
            var attempts = 0
            val client = LicenseHttpTransportClient(
                config = config(),
                engine = LicenseHttpEngine { _, _ ->
                    attempts++
                    LicenseHttpEngineResult.Failed(reason)
                }
            )

            val result = client.execute(request())

            assertEquals(1, attempts)
            assertEquals(
                reason,
                assertIs<LicenseClientTransportResult.Failed>(result).reason
            )
        }
    }

    private fun client(result: LicenseHttpEngineResult) =
        LicenseHttpTransportClient(
            config = config(),
            engine = LicenseHttpEngine { _, _ -> result }
        )

    private fun response(status: Int, body: String): LicenseHttpEngineResult =
        LicenseHttpEngineResult.Response(
            LicenseHttpEngineResponse(
                status = status,
                body = body.encodeToByteArray()
            )
        )

    private fun config() =
        LicenseHttpTransportConfig(
            endpoint = URL("https://license.example/v1/license"),
            connectTimeoutMillis = 1000,
            readTimeoutMillis = 1000
        )

    private fun request() =
        LicenseServiceTransportRequest(
            protocolVersion = pro.liliya.core.license.LicenseServiceProtocolVersion(1),
            operation = pro.liliya.core.license.LicenseServiceOperation.ISSUE,
            productId = pro.liliya.core.license.LicenseProductId("liliya-pro"),
            subjectReference = pro.liliya.core.license.LicenseSubject("private-subject"),
            requestId = pro.liliya.core.license.LicenseServiceRequestId("private-request")
        )

    private fun signedWire(): String =
        """{"wireVersion":1,"kind":"success","schemaVersion":1,"algorithm":"ECDSA-P256-SHA256","keyReference":"key-v2","payloadBase64":"AQI=","signatureBase64":"AwQ="}"""
}
