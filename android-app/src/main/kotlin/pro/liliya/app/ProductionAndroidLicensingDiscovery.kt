package pro.liliya.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** A candidate is selected only after its TLS identity and readiness response are verified. */
internal fun interface LicensingReadyProbe {
    fun isReady(network: Network, address: Inet4Address): Boolean
}

/** The caller supplies a factory anchored to its explicit, trusted deployment CA. */
internal class StrictLicensingReadyProbe(
    private val factory: SSLSocketFactory,
    private val logicalHost: String = "liliya-licensing.internal",
    private val port: Int = 8443
) : LicensingReadyProbe {
    init {
        require(logicalHost == "liliya-licensing.internal" && port in 1..65535)
    }

    override fun isReady(network: Network, address: Inet4Address): Boolean = try {
        network.socketFactory.createSocket().use { raw ->
            raw.connect(InetSocketAddress(address, port), 350)
            raw.soTimeout = 1200
            (factory.createSocket(raw, logicalHost, port, true) as SSLSocket).use { tls ->
                tls.soTimeout = 1200
                tls.sslParameters = tls.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    serverNames = listOf(SNIHostName(logicalHost))
                }
                tls.startHandshake()
                tls.outputStream.write(
                    "GET /health/ready HTTP/1.1\r\nHost: $logicalHost\r\n".toByteArray(Charsets.US_ASCII) +
                        "Connection: close\r\nAccept: application/json\r\n\r\n".toByteArray(Charsets.US_ASCII)
                )
                val response = ByteArray(4096)
                var length = 0
                while (length < response.size) {
                    val count = tls.inputStream.read(response, length, response.size - length)
                    if (count < 0) break
                    length += count
                }
                val text = String(response, 0, length, Charsets.US_ASCII)
                val separator = text.indexOf("\r\n\r\n")
                separator >= 0 && text.startsWith("HTTP/1.1 200 ") &&
                    text.substring(separator + 4) == "{\"status\":\"ready\"}"
            }
        }
    } catch (_: Exception) {
        false
    }
}

internal object ProductionAndroidLicensingDiscovery {
    private const val MAX_CANDIDATES = 512

    /** Uses only the active Android network; no fixed gateway, interface name or IP range. */
    fun discover(context: Context, probe: LicensingReadyProbe): Inet4Address? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return null
        val network = connectivity.activeNetwork ?: return null
        val properties = connectivity.getLinkProperties(network) ?: return null
        val candidates = LinkedHashSet<Inet4Address>()
        properties.routes.forEach { route ->
            (route.gateway as? Inet4Address)?.takeIf(::isPrivate)?.let { candidates.add(it) }
        }
        properties.linkAddresses.forEach { link ->
            val local = link.address as? Inet4Address ?: return@forEach
            candidates.addAll(localCandidates(local, link.prefixLength))
        }
        val deadline = System.nanoTime() + 12_000_000_000L
        for (address in candidates.take(MAX_CANDIDATES)) {
            if (System.nanoTime() >= deadline) break
            if (runCatching { probe.isReady(network, address) }.getOrDefault(false)) {
                return address
            }
        }
        return null
    }

    /** At most one /23 per interface. Larger routes are deliberately not swept. */
    internal fun localCandidates(address: Inet4Address, prefixLength: Int): List<Inet4Address> {
        if (prefixLength !in 23..30 || !isPrivate(address)) return emptyList()
        val own = address.address.fold(0L) { n, byte ->
            (n shl 8) or (byte.toLong() and 0xff)
        }
        val mask = (0xffffffffL shl (32 - prefixLength)) and 0xffffffffL
        val base = own and mask
        val size = 1 shl (32 - prefixLength)
        return (1 until size - 1).asSequence().map { offset ->
            val value = base + offset
            InetAddress.getByAddress(byteArrayOf(
                (value ushr 24).toByte(), (value ushr 16).toByte(),
                (value ushr 8).toByte(), value.toByte()
            )) as Inet4Address
        }.filter { it != address && isPrivate(it) }.take(MAX_CANDIDATES).toList()
    }

    private fun isPrivate(address: Inet4Address): Boolean {
        val octets = address.address
        val first = octets[0].toInt() and 0xff
        val second = octets[1].toInt() and 0xff
        return first == 10 || first == 172 && second in 16..31 ||
            first == 192 && second == 168
    }
}
