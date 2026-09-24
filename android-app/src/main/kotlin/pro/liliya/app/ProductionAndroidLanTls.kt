package pro.liliya.app

import android.content.Context
import android.net.Network
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import pro.liliya.core.licensetransport.LicenseClientTransportFailure
import pro.liliya.core.licensetransport.LicenseHttpEngine
import pro.liliya.core.licensetransport.LicenseHttpEngineRequest
import pro.liliya.core.licensetransport.LicenseHttpEngineResponse
import pro.liliya.core.licensetransport.LicenseHttpEngineResult
import pro.liliya.core.licensetransport.LicenseTransportCancellation

/** Resolves one verified LAN connection from a caller-approved HTTPS profile. */
internal object ProductionAndroidLanConnection {
    fun discoverEngine(
        context: Context,
        profile: ProductionAndroidFirstRunProductProfile
    ): LicenseHttpEngine? {
        val endpoint = profile.transport.endpoint
        if (endpoint.protocol != "https" || endpoint.host != "liliya-licensing.internal") return null
        val port = if (endpoint.port == -1) 443 else endpoint.port
        val der = profile.tlsTrustAnchorDer ?: return null
        val factory = try {
            ProductionAndroidLanTls.trustedFactory(der)
        } catch (_: Exception) {
            return null
        }
        val found = ProductionAndroidLicensingDiscovery.discover(
            context, StrictLicensingReadyProbe(factory, port = port)
        ) ?: return null
        return ProductionAndroidLanLicenseHttpEngine(
            found.network, found.address, port, factory
        )
    }
}

/** Explicit deployment CA; never imports trust from a probed host or a raw IP. */
internal object ProductionAndroidLanTls {
    fun trustedFactory(caCertificateDer: ByteArray): SSLSocketFactory {
        require(caCertificateDer.isNotEmpty())
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(caCertificateDer.inputStream())
        val anchors = KeyStore.getInstance(KeyStore.getDefaultType())
        anchors.load(null, null)
        anchors.setCertificateEntry("deployment-ca", certificate)
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        manager.init(anchors)
        return SSLContext.getInstance("TLS").apply {
            init(null, manager.trustManagers, SecureRandom())
        }.socketFactory
    }

    fun connect(
        network: Network,
        address: Inet4Address,
        port: Int,
        factory: SSLSocketFactory,
        connectTimeout: Int,
        readTimeout: Int
    ): SSLSocket {
        val raw = network.socketFactory.createSocket()
        try {
            raw.connect(InetSocketAddress(address, port), connectTimeout)
            raw.soTimeout = readTimeout
            val tls = factory.createSocket(raw, "liliya-licensing.internal", port, true) as SSLSocket
            tls.soTimeout = readTimeout
            tls.sslParameters = tls.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                serverNames = listOf(SNIHostName("liliya-licensing.internal"))
            }
            tls.startHandshake()
            return tls
        } catch (error: Exception) {
            raw.close()
            throw error
        }
    }
}

/** Sends only to a TLS-verified candidate; the URL must retain the deployment hostname. */
internal class ProductionAndroidLanLicenseHttpEngine(
    private val network: Network,
    private val address: Inet4Address,
    private val port: Int,
    private val factory: SSLSocketFactory
) : LicenseHttpEngine {
    override fun execute(
        request: LicenseHttpEngineRequest,
        cancellation: LicenseTransportCancellation
    ): LicenseHttpEngineResult {
        if (request.endpoint.protocol != "https" ||
            request.endpoint.host != "liliya-licensing.internal" ||
            (if (request.endpoint.port == -1) 443 else request.endpoint.port) != port ||
            request.endpoint.path !in setOf("/v1/activate", "/v1/license") ||
            request.endpoint.query != null || request.endpoint.ref != null ||
            request.body.size > 131072 ||
            request.authorizationBearer?.any { (it.toInt() and 0xff) !in 33..126 } == true
        ) return LicenseHttpEngineResult.Failed(LicenseClientTransportFailure.INVALID_LOCAL_REQUEST)
        if (cancellation.isCancelled()) {
            return LicenseHttpEngineResult.Failed(LicenseClientTransportFailure.CANCELLED)
        }
        return try {
            ProductionAndroidLanTls.connect(
                network, address, port, factory,
                request.connectTimeoutMillis.coerceAtMost(1000),
                request.readTimeoutMillis.coerceAtMost(4000)
            ).use { socket ->
                if (cancellation.isCancelled()) {
                    return LicenseHttpEngineResult.Failed(LicenseClientTransportFailure.CANCELLED)
                }
                val headers = buildString {
                    append("POST ${request.endpoint.path} HTTP/1.1\r\n")
                    append("Host: liliya-licensing.internal:$port\r\n")
                    append("Content-Type: application/json\r\nAccept: application/json\r\n")
                    append("Content-Length: ${request.body.size}\r\nConnection: close\r\n")
                    request.authorizationBearer?.let { bearer ->
                        append("Authorization: Bearer ${bearer.toString(Charsets.UTF_8)}\r\n")
                    }
                    append("\r\n")
                }
                socket.outputStream.write(headers.toByteArray(Charsets.UTF_8))
                socket.outputStream.write(request.body)
                socket.outputStream.flush()
                val response = readResponse(socket.inputStream)
                if (cancellation.isCancelled()) {
                    LicenseHttpEngineResult.Failed(LicenseClientTransportFailure.CANCELLED)
                } else {
                    LicenseHttpEngineResult.Response(response)
                }
            }
        } catch (_: SocketTimeoutException) {
            LicenseHttpEngineResult.Failed(LicenseClientTransportFailure.TIMEOUT)
        } catch (_: SSLException) {
            LicenseHttpEngineResult.Failed(LicenseClientTransportFailure.TLS_FAILURE)
        } catch (_: IOException) {
            LicenseHttpEngineResult.Failed(LicenseClientTransportFailure.CONNECT_FAILURE)
        } catch (_: RuntimeException) {
            LicenseHttpEngineResult.Failed(LicenseClientTransportFailure.PROTOCOL_FAILURE)
        }
    }

    private fun readResponse(input: InputStream): LicenseHttpEngineResponse {
        val bytes = ByteArrayOutputStream()
        val chunk = ByteArray(2048)
        while (bytes.size() <= 131072) {
            val count = input.read(chunk)
            if (count < 0) break
            bytes.write(chunk, 0, count)
        }
        if (bytes.size() > 131072) throw IOException("oversized license response")
        return parseLicensingLanResponse(bytes.toByteArray())
    }
}

internal fun parseLicensingLanResponse(wire: ByteArray): LicenseHttpEngineResponse {
        val marker = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val split = (0..wire.size - marker.size).firstOrNull { offset ->
            marker.indices.all { wire[offset + it] == marker[it] }
        } ?: throw IOException("invalid HTTP response")
        if (split > 8192) throw IOException("oversized HTTP headers")
        val header = String(wire, 0, split, Charsets.US_ASCII)
        val status = Regex("^HTTP/1\\.[01] ([1-5][0-9][0-9])(?: |\\r?\\n)")
            .find(header)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IOException("invalid HTTP status")
        val body = wire.copyOfRange(split + marker.size, wire.size)
        if (Regex("(?im)^Transfer-Encoding:").containsMatchIn(header)) {
            throw IOException("unexpected transfer encoding")
        }
        val lengths = Regex("(?im)^Content-Length: ([0-9]+)\\r?$")
            .findAll(header).map { it.groupValues[1].toIntOrNull() }.toList()
        val length = lengths.singleOrNull()
        if (length == null || length != body.size) throw IOException("invalid HTTP length")
        return LicenseHttpEngineResponse(status, body)
}
