package pro.liliya.core.licensetransport

import java.net.URL
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ActivationRedemptionHttpClientContractTest {
    @Test
    fun fresh_install_activation_uses_dedicated_path_without_product_auth_and_returns_license() {
        var capturedPath: String? = null
        var capturedBearer: ByteArray? = byteArrayOf(1)
        val engine = LicenseHttpEngine { request, _ ->
            capturedPath = request.endpoint.path
            capturedBearer = request.authorizationBearer
            LicenseHttpEngineResult.Response(
                LicenseHttpEngineResponse(
                    status = 200,
                    body = activatedBody()
                )
            )
        }

        val result = client(engine).redeem(
            ActivationRedemptionHttpRequest(
                activationCode = "LAC1.example",
                attemptId = "attempt-1"
            )
        )

        val activated = assertIs<ActivationRedemptionHttpResult.Activated>(result)
        assertEquals("liliya-subject-v1:test", activated.subject)
        assertEquals("license-key-v1", activated.license.signingKeyId.value)
        assertEquals("/v1/activation/redeem", capturedPath)
        assertEquals(null, capturedBearer)
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

        val result = client(engine).redeem(
            ActivationRedemptionHttpRequest("LAC1.example", "attempt-2")
        )

        assertEquals(
            "CODE_EXHAUSTED",
            assertIs<ActivationRedemptionHttpResult.Rejected>(result).reason
        )
    }

    @Test
    fun malformed_or_incomplete_signed_license_fails_closed() {
        val engine = LicenseHttpEngine { _, _ ->
            LicenseHttpEngineResult.Response(
                LicenseHttpEngineResponse(
                    status = 200,
                    body = """{"wireVersion":1,"kind":"activated","subject":"x"}"""
                        .encodeToByteArray()
                )
            )
        }

        val result = client(engine).redeem(
            ActivationRedemptionHttpRequest("LAC1.example", "attempt-3")
        )

        assertEquals(
            LicenseClientTransportFailure.PROTOCOL_FAILURE,
            assertIs<ActivationRedemptionHttpResult.Failed>(result).reason
        )
    }

    private fun activatedBody(): ByteArray {
        val payload = Base64.getEncoder().encodeToString("payload".encodeToByteArray())
        val signature = Base64.getEncoder().encodeToString("signature".encodeToByteArray())
        return """
            {
              "wireVersion":1,
              "kind":"activated",
              "subject":"liliya-subject-v1:test",
              "schemaVersion":1,
              "algorithm":"ECDSA_P256_SHA256",
              "keyReference":"license-key-v1",
              "payloadBase64":"$payload",
              "signatureBase64":"$signature"
            }
        """.trimIndent().encodeToByteArray()
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
