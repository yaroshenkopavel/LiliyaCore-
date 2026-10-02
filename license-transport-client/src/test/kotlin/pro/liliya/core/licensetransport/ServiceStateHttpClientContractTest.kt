package pro.liliya.core.licensetransport

import java.net.URL
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseServiceEvidencePurpose
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseServiceSecurityScope
import pro.liliya.core.license.LicenseSubject

class ServiceStateHttpClientContractTest {
    private val config = LicenseHttpTransportConfig(
        endpoint = URL("https://licensing.example/v1/license"),
        connectTimeoutMillis = 1_000,
        readTimeoutMillis = 1_000
    )

    @Test
    fun uses_dedicated_path_and_product_auth_without_trusting_response() {
        var capturedPath: String? = null
        var capturedBearer: ByteArray? = null
        var capturedBody: String? = null
        val engine = LicenseHttpEngine { request, _ ->
            capturedPath = request.endpoint.path
            capturedBearer = request.authorizationBearer?.copyOf()
            capturedBody = request.body.toString(Charsets.UTF_8)
            LicenseHttpEngineResult.Response(
                LicenseHttpEngineResponse(
                    status = 200,
                    body = evidenceResponse()
                )
            )
        }

        val result = ServiceStateHttpClient(config, engine).execute(
            request(),
            LicenseHttpBearerCredential.of("secret".toByteArray())
        )

        val evidence = assertIs<ServiceStateHttpResult.Evidence>(result)
        assertEquals(ServiceStateHttpClient.PATH, capturedPath)
        assertContentEquals("secret".toByteArray(), capturedBearer)
        assertTrue(capturedBody.orEmpty().contains("\"productId\":\"liliya-pro\""))
        assertTrue(capturedBody.orEmpty().contains("\"subjectReference\":\"subject-1\""))
        assertEquals(LicenseServiceEvidencePurpose.SECURITY_STATE, evidence.envelope.purpose)
        assertEquals("service-state-key-v1", evidence.envelope.signingKeyId.value)
        assertContentEquals(byteArrayOf(1, 2, 3), evidence.envelope.payload.copyBytes())
        assertContentEquals(byteArrayOf(4, 5, 6), evidence.envelope.proof.copyBytes())
    }

    @Test
    fun malformed_response_fails_closed() {
        val engine = LicenseHttpEngine { _, _ ->
            LicenseHttpEngineResult.Response(
                LicenseHttpEngineResponse(200, """{"wireVersion":1,"kind":"service-state"}""".toByteArray())
            )
        }

        val result = ServiceStateHttpClient(config, engine).execute(
            request(),
            LicenseHttpBearerCredential.of("secret".toByteArray())
        )

        assertEquals(
            LicenseClientTransportFailure.PROTOCOL_FAILURE,
            assertIs<ServiceStateHttpResult.Failed>(result).reason
        )
    }

    @Test
    fun rejected_response_is_not_evidence() {
        val engine = LicenseHttpEngine { _, _ ->
            LicenseHttpEngineResult.Response(
                LicenseHttpEngineResponse(
                    401,
                    """{"wireVersion":1,"kind":"rejected","reason":"AUTHENTICATION_REQUIRED"}"""
                        .toByteArray()
                )
            )
        }

        val result = ServiceStateHttpClient(config, engine).execute(
            request(),
            LicenseHttpBearerCredential.of("secret".toByteArray())
        )

        assertEquals(
            ServiceStateRemoteFailure.AUTHENTICATION_REQUIRED,
            assertIs<ServiceStateHttpResult.ServiceRejected>(result).reason
        )
    }

    private fun request() = ServiceStateHttpRequest(
        protocolVersion = LicenseServiceProtocolVersion(1),
        scope = LicenseServiceSecurityScope(
            productId = LicenseProductId("liliya-pro"),
            subject = LicenseSubject("subject-1")
        ),
        requestId = LicenseServiceRequestId("state-request-1")
    )

    private fun evidenceResponse(): ByteArray {
        val payload = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val proof = Base64.getEncoder().encodeToString(byteArrayOf(4, 5, 6))
        return """
            {
              "wireVersion":1,
              "kind":"service-state",
              "protocolVersion":1,
              "purpose":"SECURITY_STATE",
              "profile":"ECDSA-P256-SHA256-SERVICE-STATE-V1",
              "signingKeyId":"service-state-key-v1",
              "payloadBase64":"$payload",
              "proofBase64":"$proof"
            }
        """.trimIndent().toByteArray()
    }
}
