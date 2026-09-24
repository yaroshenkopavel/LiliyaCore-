package pro.liliya.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.Callable
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
        ProductionAndroidLanTls.connect(network, address, port, factory, 350, 1200)
            .use { tls ->
                tls.outputStream.write(
                    "GET /health/ready HTTP/1.1\r\nHost: $logicalHost\r\n".toByteArray(Charsets.US_ASCII) +
                        "Connection: close\r\nAccept: application/json\r\n\r\n".toByteArray(Charsets.US_ASCII)
                )
                val response = readLicensingLanResponse(tls.inputStream)
                response.status == 200 &&
                    response.body.contentEquals("{\"status\":\"ready\"}".toByteArray())
        }
    } catch (_: Exception) {
        false
    }
}

internal object ProductionAndroidLicensingDiscovery {
    private const val MAX_CANDIDATES = 512

    data class Found(val network: Network, val address: Inet4Address)

    /** Probes local Wi-Fi/Ethernet even when cellular remains the default network. */
    fun discover(context: Context, probe: LicensingReadyProbe): Found? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return null
        val active = connectivity.activeNetwork
        val networks = connectivity.allNetworks.filter { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        }.sortedBy { if (it == active) 0 else 1 }
        val perNetwork = networks.map { network ->
            val candidates = LinkedHashSet<Found>()
            val properties = connectivity.getLinkProperties(network) ?: return@map emptyList()
            properties.routes.forEach { route ->
                (route.gateway as? Inet4Address)?.takeIf(::isPrivate)?.let {
                    candidates.add(Found(network, it))
                }
            }
            properties.linkAddresses.forEach linkLoop@{ link ->
                val local = link.address as? Inet4Address ?: return@linkLoop
                localCandidates(local, link.prefixLength).forEach { address ->
                    candidates.add(Found(network, address))
                }
            }
            candidates.toList()
        }
        val ordered = ArrayList<Found>(MAX_CANDIDATES)
        var offset = 0
        while (ordered.size < MAX_CANDIDATES && perNetwork.any { offset < it.size }) {
            perNetwork.forEach { networkCandidates ->
                if (offset < networkCandidates.size && ordered.size < MAX_CANDIDATES) {
                    ordered += networkCandidates[offset]
                }
            }
            offset++
        }
        if (ordered.isEmpty()) return null
        val executor = Executors.newFixedThreadPool(16)
        try {
            val completion = ExecutorCompletionService<Found?>(executor)
            ordered.forEach { candidate ->
                completion.submit(Callable<Found?> {
                    if (runCatching {
                            probe.isReady(candidate.network, candidate.address)
                        }.getOrDefault(false)) {
                        candidate
                    } else null
                })
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            repeat(ordered.size) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return null
                val result = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: return null
                result.get()?.let { return it }
            }
        } finally {
            executor.shutdownNow()
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
