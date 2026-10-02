package pro.liliya.core.licensetransport

import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActivationRedemptionHttpClientContractTest {
    @Test
    fun activation_request_uses_dedicated_path_and_zeroizes_bearer_copy() {
        var capturedBearer: ByteArray? = null
        var capturedPath: String? = null
        val engine = LicenseHttpEngine { request, _ ->
            capturedBearer = request.authorizationBearer
            capturedPath = request.endpoint.path
            LicenseHttpEngineResult.Response(
                LicenseHttpEngineResponse(
                    status = 200,
                    body = """{"wireVersion":1,"kind":"activated","subject":"liliya-subject-v1:test"}"""
                        .encodeToByteArray()
                )
            )
        }
        val credential = LicenseHttpBearerCredential.of("product-auth".encodeToByteArray())

        try {
            val result = client(engine).redeem(
                ActivationRedemptionHttpRequest(
                    activationCode = "LAC1.example",
                    attemptId = "attempt-1"
                ),
                credential
            )

            assertEquals(
                "liliya-subject-v1:test",
                assertIs<ActivationRedemptionHttpResult.Activated>(result).subject
            )
            assertEquals("/v1/activation/redeem", capturedPath)
            assertTrue(capturedBearer?.all { it == 0.toByte() } == true)
        } finally {
            credential.close()
        }
    }

    @Test
    fun exhausted_code_maps_to_rejected_result() {
        val engine = LicenseHttpEngine { _, _ ->
            LicenseHttpEngineResult.Response(
                LicenseHttpEngineResponse(
                    status = 409,
                    body = """{"wireVersion":1,"kind":"rejected","reason":"CODE_EXHAUSTED"}"""
                        .encodeToByteArray()
                )
            )
        }
        val credential = LicenseHttpBearerCredential.of("product-auth".encodeToByteArray())

        try {
            val result = client(engine).redeem(
                ActivationRedemptionHttpRequest("LAC1.example", "attempt-2"),
                credential
            )
            assertEquals(
                "CODE_EXHAUSTED",
                assertIs<ActivationRedemptionHttpResult.Rejected>(result).reason
            )
        } finally {
            credential.close()
        }
    }

    @Test
    fun malformed_success_response_fails_closed() {
        val engine = LicenseHttpEngine { _, _ ->
            LicenseHttpEngineResult.Response(
                LicenseHttpEngineResponse(
                    status = 200,
                    body = """{"wireVersion":1,"kind":"activated","subject":""}"""
                        .encodeToByteArray()
                )
            )
        }
        val credential = LicenseHttpBearerCredential.of("product-auth".encodeToByteArray())

        try {
            val result = client(engine).redeem(
                ActivationRedemptionHttpRequest("LAC1.example", "attempt-3"),
                credential
            )
            assertEquals(
                LicenseClientTransportFailure.PROTOCOL_FAILURE,
                assertIs<ActivationRedemptionHttpResult.Failed>(result).reason
            )
        } finally {
            credential.close()
        }
    }

    private fun client(engine: LicenseHttpEngine) =
        ActivationRedemptionHttpClient(
            config = LicenseHttpTransportConfig(
                endpoint = URL("https://license.example/v1/license"),
                connectTimeoutMillis = 1_000,
                readTimeoutMillis = 1_000
            ),
            engine = engine
        )
}
