package pro.liliya.core.licensetransport

import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LicenseActivationTransportClientContractTest {
    @Test
    fun activation_request_contains_server_contract_and_never_uses_authorization_header() {
        var captured: LicenseHttpEngineRequest? = null
        val client = client(
            LicenseHttpEngine { request, _ ->
                captured = request
                LicenseHttpEngineResult.Response(
                    LicenseHttpEngineResponse(
                        status = 401,
                        body = rejected("AUTHENTICATION_REQUIRED")
                    )
                )
            }
        )

        val secret = "0123456789abcdef".repeat(4).encodeToByteArray()
        val result = client.execute(
            activationCode = "LIL-0011-2233-4455-6677-8899-AABB-CCDD-EEFF",
            activationRequestId = "activation-request-1",
            installId = "liliya-00112233445566778899aabbccddeeff",
            installSecret = secret
        )

        assertIs<LicenseClientTransportResult.ServiceRejected>(result)
        val request = requireNotNull(captured)
        assertEquals(null, request.authorizationBearer)
        val body = request.body.toString(Charsets.UTF_8)
        assertTrue(body.contains("\"kind\":\"activate\""))
        assertTrue(body.contains("\"activationRequestId\":\"activation-request-1\""))
        assertTrue(body.contains("\"installId\":\"liliya-00112233445566778899aabbccddeeff\""))
        assertTrue(body.contains("\"installSecret\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\""))
        secret.fill(0)
    }

    @Test
    fun signed_activation_response_is_preserved() {
        val client = client(
            LicenseHttpEngine { _, _ ->
                LicenseHttpEngineResult.Response(
                    LicenseHttpEngineResponse(
                        status = 200,
                        body = """
                            {"wireVersion":1,"kind":"success","schemaVersion":1,
                             "algorithm":"ECDSA-P256-SHA256","keyReference":"primary",
                             "payloadBase64":"AQID","signatureBase64":"BAUG"}
                        """.trimIndent().encodeToByteArray()
                    )
                )
            }
        )

        val result = client.execute(
            activationCode = "LIL-0011-2233-4455-6677-8899-AABB-CCDD-EEFF",
            activationRequestId = "activation-request-2",
            installId = "liliya-00112233445566778899aabbccddeeff",
            installSecret = "0123456789abcdef".repeat(4).encodeToByteArray()
        )

        assertIs<LicenseClientTransportResult.Signed>(result)
    }

    @Test
    fun invalid_binary_install_secret_fails_before_network() {
        var calls = 0
        val client = client(
            LicenseHttpEngine { _, _ ->
                calls++
                error("invalid local secret must not reach network")
            }
        )

        val result = client.execute(
            activationCode = "LIL-0011-2233-4455-6677-8899-AABB-CCDD-EEFF",
            activationRequestId = "activation-request-3",
            installId = "liliya-00112233445566778899aabbccddeeff",
            installSecret = ByteArray(32) { 0xff.toByte() }
        )

        assertEquals(
            LicenseClientTransportFailure.INVALID_LOCAL_REQUEST,
            assertIs<LicenseClientTransportResult.Failed>(result).reason
        )
        assertEquals(0, calls)
    }

    @Test
    fun transport_failures_are_preserved() {
        val client = client(
            LicenseHttpEngine { _, _ ->
                LicenseHttpEngineResult.Failed(LicenseClientTransportFailure.TLS_FAILURE)
            }
        )

        val result = client.execute(
            activationCode = "LIL-0011-2233-4455-6677-8899-AABB-CCDD-EEFF",
            activationRequestId = "activation-request-4",
            installId = "liliya-00112233445566778899aabbccddeeff",
            installSecret = "0123456789abcdef".repeat(4).encodeToByteArray()
        )

        assertEquals(
            LicenseClientTransportFailure.TLS_FAILURE,
            assertIs<LicenseClientTransportResult.Failed>(result).reason
        )
    }

    private fun client(engine: LicenseHttpEngine) =
        LicenseActivationTransportClient(
            config = LicenseActivationTransportConfig(
                endpoint = URL("https://liliya-licensing.internal:8443/v1/activate"),
                connectTimeoutMillis = 2_000,
                readTimeoutMillis = 5_000
            ),
            engine = engine
        )

    private fun rejected(reason: String): ByteArray =
        """{"wireVersion":1,"kind":"rejected","reason":"$reason"}""".encodeToByteArray()
}
