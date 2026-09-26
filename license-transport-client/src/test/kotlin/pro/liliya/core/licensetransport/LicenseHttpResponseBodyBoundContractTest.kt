package pro.liliya.core.licensetransport

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LicenseHttpResponseBodyBoundContractTest {
    @Test
    fun response_body_at_limit_is_accepted_exactly() {
        val payload = ByteArray(LICENSE_HTTP_MAX_RESPONSE_BYTES) { index ->
            (index and 0x7f).toByte()
        }

        val loaded = readLicenseHttpResponseBody(ByteArrayInputStream(payload))

        assertEquals(LICENSE_HTTP_MAX_RESPONSE_BYTES, loaded.size)
        assertContentEquals(payload, loaded)
    }

    @Test
    fun response_body_above_limit_is_rejected_before_unbounded_growth() {
        val payload = ByteArray(LICENSE_HTTP_MAX_RESPONSE_BYTES + 1)

        assertFailsWith<LicenseHttpResponseBodyLimitExceeded> {
            readLicenseHttpResponseBody(ByteArrayInputStream(payload))
        }
    }

    @Test
    fun missing_response_stream_is_an_empty_body() {
        assertContentEquals(byteArrayOf(), readLicenseHttpResponseBody(null))
    }

    @Test
    fun url_connection_engine_rejects_oversized_response_fail_closed() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/oversized") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val body = ByteArray(LICENSE_HTTP_MAX_RESPONSE_BYTES + 1)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()

        try {
            val endpoint = URL("http://127.0.0.1:" + server.address.port + "/oversized")
            val result = UrlConnectionLicenseHttpEngine().execute(
                LicenseHttpEngineRequest(
                    endpoint = endpoint,
                    connectTimeoutMillis = 2_000,
                    readTimeoutMillis = 2_000,
                    body = byteArrayOf(1)
                ),
                LicenseTransportCancellation()
            )

            assertEquals(
                LicenseClientTransportFailure.PROTOCOL_FAILURE,
                assertIs<LicenseHttpEngineResult.Failed>(result).reason
            )
        } finally {
            server.stop(0)
        }
    }
}
