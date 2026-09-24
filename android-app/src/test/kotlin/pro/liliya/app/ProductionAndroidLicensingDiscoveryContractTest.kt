package pro.liliya.app

import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ProductionAndroidLicensingDiscoveryContractTest {
    @Test
    fun local_scan_stays_inside_active_private_subnet_and_excludes_phone() {
        val own = ipv4("192.168.23.42")
        val addresses = ProductionAndroidLicensingDiscovery.localCandidates(own, 24)
        assertEquals(253, addresses.size)
        assertFalse(own in addresses)
        assertTrue(ipv4("192.168.23.1") in addresses)
        assertTrue(ipv4("192.168.23.254") in addresses)
        assertFalse(ipv4("192.168.24.1") in addresses)
    }

    @Test
    fun broad_and_public_networks_are_not_swept() {
        assertTrue(ProductionAndroidLicensingDiscovery.localCandidates(ipv4("10.0.0.4"), 16).isEmpty())
        assertTrue(ProductionAndroidLicensingDiscovery.localCandidates(ipv4("8.8.8.8"), 24).isEmpty())
        assertEquals(509, ProductionAndroidLicensingDiscovery.localCandidates(ipv4("172.19.4.42"), 23).size)
    }

    private fun ipv4(text: String): Inet4Address = InetAddress.getByName(text) as Inet4Address
}
