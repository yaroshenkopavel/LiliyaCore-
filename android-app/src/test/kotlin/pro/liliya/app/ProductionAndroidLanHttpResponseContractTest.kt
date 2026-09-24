package pro.liliya.app

import java.io.IOException
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class ProductionAndroidLanHttpResponseContractTest {
    @Test
    fun accepts_exact_fixed_length_license_response() {
        val wire = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
            "Content-Length: 2\r\nConnection: close\r\n\r\n{}"
        val response = parseLicensingLanResponse(wire.toByteArray())
        assertEquals(200, response.status)
        assertEquals("{}", response.body.toString(Charsets.US_ASCII))
    }

    @Test
    fun rejects_truncated_and_ambiguous_http_framing() {
        assertFailsWith<IOException> {
            parseLicensingLanResponse("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\n{}".toByteArray())
        }
        assertFailsWith<IOException> {
            parseLicensingLanResponse(
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n".toByteArray() +
                    "Content-Length: 2\r\n\r\n{}".toByteArray()
            )
        }
        assertFailsWith<IOException> {
            parseLicensingLanResponse(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n".toByteArray()
            )
        }
    }

    @Test
    fun reads_only_framed_bytes_without_waiting_for_connection_close() {
        val wire = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".toByteArray()
        val source = object : InputStream() {
            var index = 0
            override fun read(): Int = if (index < wire.size) wire[index++].toInt() and 0xff
            else error("must not wait for TLS connection close")
        }
        assertEquals("{}", readLicensingLanResponse(source).body.toString(Charsets.UTF_8))
    }
}
