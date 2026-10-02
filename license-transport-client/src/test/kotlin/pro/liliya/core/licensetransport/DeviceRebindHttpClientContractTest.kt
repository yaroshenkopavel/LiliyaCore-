package pro.liliya.core.licensetransport

import java.net.URL
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeviceRebindHttpClientContractTest {
    @Test
    fun rebind_uses_dedicated_path_without_product_auth_and_returns_license() {
        var capturedPath: String? = null
        var capturedBearer: ByteArray? = byteArrayOf(1)
        var capturedBody: String? = null

        val engine = LicenseHttpEngine { request, _ ->
            capturedPath = request.endpoint.path
            capturedBearer = request.authorizationBearer
            capturedBody = request.body.toString(Charsets.UTF_8)
            LicenseHttpEngineResult.Response(
                LicenseHttpEngineResponse(
                    status = 200,
                    body = reboundBody()
                )
            )
        }

        val result = client(engine).rebind(
            DeviceRebindHttpRequest(
                rebindCode = "LDR1.example",
                attemptId = "attempt-1",
                installationId = "installation-B",
                deviceKeyFingerprint = "sha256:device-B"
            )
        )

        val rebound = assertIs<DeviceRebindHttpResult.Rebound>(result)
        assertEquals("liliya-subject-v1:test", rebound.subject)
        assertEquals("license-key-v1", rebound.license.signingKeyId.value)
        assertEquals("/v1/activation/rebind", capturedPath)
        assertEquals(null, capturedBearer)
        assertTrue(
            capturedBody.orEmpty().contains(
                "\"installationId\":\"installation-B\""
            )
        )
        assertTrue(
            capturedBody.orEmpty().contains(
                "\"deviceKeyFingerprint\":\"sha256:device-B\""
            )
        )
    }

    @Test
    fun occupied_device_seat_maps_to_rejected() {
        val engine = LicenseHttpEngine { _, _ ->
            LicenseHttpEngineResult.Response(
                LicenseHttpEngineResponse(
                    status = 409,
                    body =
                        """{"wireVersion":1,"kind":"rejected","reason":"DEVICE_LIMIT_REACHED"}"""
                            .encodeToByteArray()
                )
            )
        }

        val result = client(engine).rebind(request())

        assertEquals(
            "DEVICE_LIMIT_REACHED",
            assertIs<DeviceRebindHttpResult.Rejected>(result).reason
        )
    }

    @Test
    fun incomplete_rebound_license_fails_closed() {
        val engine = LicenseHttpEngine { _, _ ->
            LicenseHttpEngineResult.Response(
                LicenseHttpEngineResponse(
                    status = 200,
                    body =
                        """{"wireVersion":1,"kind":"rebound","subject":"x"}"""
                            .encodeToByteArray()
                )
            )
        }

        val result = client(engine).rebind(request())

        assertEquals(
            LicenseClientTransportFailure.PROTOCOL_FAILURE,
            assertIs<DeviceRebindHttpResult.Failed>(result).reason
        )
    }

    private fun request() = DeviceRebindHttpRequest(
        rebindCode = "LDR1.example",
        attemptId = "attempt-1",
        installationId = "installation-B",
        deviceKeyFingerprint = "sha256:device-B"
    )

    private fun reboundBody(): ByteArray {
        val payload =
            Base64.getEncoder().encodeToString("payload".encodeToByteArray())
        val signature =
            Base64.getEncoder().encodeToString("signature".encodeToByteArray())

        return """
            {
              "wireVersion":1,
              "kind":"rebound",
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
        DeviceRebindHttpClient(
            config = LicenseHttpTransportConfig(
                endpoint = URL("https://license.example/v1/license"),
                connectTimeoutMillis = 1_000,
                readTimeoutMillis = 1_000
            ),
            engine = engine
        )
}
