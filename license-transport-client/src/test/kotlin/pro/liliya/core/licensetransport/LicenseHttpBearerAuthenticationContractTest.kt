package pro.liliya.core.licensetransport

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseServiceOperation
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseSubject

class LicenseHttpBearerAuthenticationContractTest {
    @Test
    fun authenticated_execute_carries_exact_bearer_outside_wire_dto_and_zeroizes_attempt_copy() {
        val credential = LicenseHttpBearerCredential.of("transport-secret".encodeToByteArray())
        var captured: ByteArray? = null
        val engine = LicenseHttpEngine { request, _ ->
            captured = request.authorizationBearer
            assertEquals("transport-secret", request.authorizationBearer?.toString(Charsets.UTF_8))
            assertTrue("transport-secret" !in request.toString())
            rejectedAuthentication()
        }

        try {
            val result = client(engine).execute(request(), credential)

            assertEquals(
                LicenseRemoteServiceFailure.AUTHENTICATION_REQUIRED,
                assertIs<LicenseClientTransportResult.ServiceRejected>(result).reason
            )
            assertTrue(captured?.all { it == 0.toByte() } == true)
            assertTrue("transport-secret" !in credential.toString())
        } finally {
            credential.close()
        }
    }

    @Test
    fun closed_credential_fails_locally_before_network_attempt() {
        var calls = 0
        val credential = LicenseHttpBearerCredential.of("one-shot-secret".encodeToByteArray())
        credential.close()
        val engine = LicenseHttpEngine { _, _ ->
            calls++
            error("closed credential must fail before the engine")
        }

        val result = client(engine).execute(request(), credential)

        assertEquals(0, calls)
        assertEquals(
            LicenseClientTransportFailure.INVALID_LOCAL_REQUEST,
            assertIs<LicenseClientTransportResult.Failed>(result).reason
        )
    }

    @Test
    fun credential_rejects_blank_invalid_utf8_and_http_line_breaks() {
        assertFailsWith<IllegalArgumentException> {
            LicenseHttpBearerCredential.of("   ".encodeToByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            LicenseHttpBearerCredential.of(byteArrayOf(0xC3.toByte(), 0x28))
        }
        assertFailsWith<IllegalArgumentException> {
            LicenseHttpBearerCredential.of("secret\r\nInjected: value".encodeToByteArray())
        }
    }

    @Test
    fun url_connection_engine_emits_backend_compatible_authorization_bearer_header() {
        val authorization = AtomicReference<String?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/license") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.requestBody.use { it.readBytes() }
            val body = rejectedAuthenticationBody()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()

        val credential = LicenseHttpBearerCredential.of("service-auth-token".encodeToByteArray())
        try {
            val port = server.address.port
            val client = LicenseHttpTransportClient(
                LicenseHttpTransportConfig(
                    endpoint = URL("http://127.0.0.1:$port/v1/license"),
                    connectTimeoutMillis = 2_000,
                    readTimeoutMillis = 2_000,
                    developmentAllowInsecureHttp = true
                )
            )

            val result = client.execute(request(), credential)

            assertEquals("Bearer service-auth-token", authorization.get())
            assertEquals(
                LicenseRemoteServiceFailure.AUTHENTICATION_REQUIRED,
                assertIs<LicenseClientTransportResult.ServiceRejected>(result).reason
            )
        } finally {
            credential.close()
            server.stop(0)
        }
    }

    private fun client(engine: LicenseHttpEngine) =
        LicenseHttpTransportClient(
            config = LicenseHttpTransportConfig(
                endpoint = URL("https://license.example/v1/license"),
                connectTimeoutMillis = 1_000,
                readTimeoutMillis = 1_000
            ),
            engine = engine
        )

    private fun request() =
        LicenseServiceTransportRequest(
            protocolVersion = LicenseServiceProtocolVersion(1),
            operation = LicenseServiceOperation.ISSUE,
            productId = LicenseProductId("liliya-pro"),
            subjectReference = LicenseSubject("subject-private"),
            requestId = LicenseServiceRequestId("authenticated-attempt-001")
        )

    private fun rejectedAuthentication(): LicenseHttpEngineResult =
        LicenseHttpEngineResult.Response(
            LicenseHttpEngineResponse(
                status = 401,
                body = rejectedAuthenticationBody()
            )
        )

    private fun rejectedAuthenticationBody(): ByteArray =
        """
            {"wireVersion":1,"kind":"rejected","reason":"AUTHENTICATION_REQUIRED"}
        """.trimIndent().encodeToByteArray()
}
