package com.castla.mirror.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IpSelectorTest {

    private fun candidate(iface: String, ip: String) =
        IpCandidate(ip, iface, IpSelector.priorityOf(iface, ip))

    // ── Priority ordering ──

    @Test
    fun `hotspot gateway IP beats everything`() {
        assertEquals(20, IpSelector.priorityOf("wlan0", "192.168.43.1"))
        assertEquals(20, IpSelector.priorityOf("swlan0", "192.168.49.1"))
    }

    @Test
    fun `known hotspot interface with other private IP is next`() {
        assertEquals(15, IpSelector.priorityOf("swlan0", "100.64.0.1"))
        assertEquals(15, IpSelector.priorityOf("ap0", "192.168.1.5"))
        assertEquals(15, IpSelector.priorityOf("softap0", "10.0.0.1"))
    }

    @Test
    fun `wlan private IPs rank below hotspot, cellular ranks last`() {
        assertEquals(10, IpSelector.priorityOf("wlan0", "192.168.1.7"))
        assertEquals(5, IpSelector.priorityOf("wlan0", "10.0.5.3"))
        assertEquals(3, IpSelector.priorityOf("eth0", "192.0.2.9"))
        assertEquals(1, IpSelector.priorityOf("rmnet_data0", "100.80.1.2"))
    }

    // ── CLAT/464XLAT dummy address (192.0.0.x): visible above cellular, never beats WiFi (issue #51) ──

    @Test
    fun `CLAT dummy address ranks above cellular but below every WiFi and hotspot tier`() {
        val clat = IpSelector.priorityOf("rmnet_data9", "192.0.0.8")
        assertEquals(4, clat)
        assertTrue(clat > IpSelector.priorityOf("rmnet_data0", "100.80.1.2")) // above cellular
        assertTrue(clat < IpSelector.priorityOf("wlan0", "10.0.5.3"))         // below wlan (10.x)
        assertTrue(clat < IpSelector.priorityOf("wlan0", "192.168.1.7"))      // below wlan (192.168)
        assertTrue(clat < IpSelector.priorityOf("swlan0", "10.69.97.36"))     // below hotspot iface
    }

    @Test
    fun `CLAT address does not change the first-try pick when WiFi is present`() {
        val selected = IpSelector.select(listOf(
            candidate("rmnet_data9", "192.0.0.8"),
            candidate("wlan0", "192.168.1.7")
        ))
        assertEquals("192.168.1.7", selected?.ip) // WiFi still wins the first try — no regression
    }

    @Test
    fun `CLAT address is surfaced above cellular in the alternatives list`() {
        val alts = IpSelector.alternativesTo("192.168.1.7", listOf(
            candidate("wlan0", "192.168.1.7"),
            candidate("rmnet_data0", "100.80.1.2"), // generic cellular
            candidate("rmnet_data9", "192.0.0.8")   // CLAT dummy
        ))
        assertEquals(listOf("192.0.0.8", "100.80.1.2"), alts)
    }

    // ── Learned preference beats the priority guess (issue #51) ──

    @Test
    fun `preferred IP wins even when it ranks lowest`() {
        val selected = IpSelector.select(listOf(
            candidate("rmnet_data8", "192.0.0.8"),
            candidate("swlan0", "10.69.97.36")
        ), preferred = "192.0.0.8")
        assertEquals("192.0.0.8", selected?.ip)
    }

    @Test
    fun `preferred IP that is no longer a candidate falls back to priority`() {
        val selected = IpSelector.select(listOf(
            candidate("rmnet_data8", "192.0.0.8"),
            candidate("swlan0", "10.69.97.36")
        ), preferred = "10.1.2.3")
        assertEquals("10.69.97.36", selected?.ip)
    }

    @Test
    fun `no preference keeps the priority pick`() {
        val selected = IpSelector.select(listOf(
            candidate("rmnet_data8", "192.0.0.8"),
            candidate("swlan0", "10.69.97.36")
        ), preferred = null)
        assertEquals("10.69.97.36", selected?.ip)
    }

    // ── Advertised URL (issue #51: hostnames die to DNS64 on IPv6-only carriers) ──

    @Test
    fun `advertised URL is the raw IP, never a hostname`() {
        val url = IpSelector.advertiseUrl("192.0.0.8", 9090)
        assertEquals("http://192.0.0.8:9090", url)
        assertFalse("must not wrap the IP in a hostname: $url", url.contains("sslip"))
    }

    @Test
    fun `advertised URL keeps dots so DNS is never consulted`() {
        assertEquals("http://10.69.97.36:9090", IpSelector.advertiseUrl("10.69.97.36", 9090))
    }

    // ── Alternatives (issue #51: no single priority is right on every device) ──

    @Test
    fun `alternatives exclude the selected IP and keep priority order`() {
        val alts = IpSelector.alternativesTo("10.69.97.36", listOf(
            candidate("rmnet_data8", "192.0.0.8"),
            candidate("swlan0", "10.69.97.36"),
            candidate("wlan0", "192.168.1.7")
        ))
        assertEquals(listOf("192.168.1.7", "192.0.0.8"), alts)
    }

    @Test
    fun `alternatives dedupe repeated IPs`() {
        val alts = IpSelector.alternativesTo("10.69.97.36", listOf(
            candidate("rmnet_data8", "192.0.0.8"),
            candidate("rmnet_data10", "192.0.0.8"),
            candidate("swlan0", "10.69.97.36")
        ))
        assertEquals(listOf("192.0.0.8"), alts)
    }

    @Test
    fun `alternatives are empty when the selected IP is the only candidate`() {
        val alts = IpSelector.alternativesTo("10.69.97.36", listOf(
            candidate("swlan0", "10.69.97.36")
        ))
        assertEquals(emptyList<String>(), alts)
    }

    // ── Selection scenarios (0a acceptance) ──

    @Test
    fun `hotspot and cellular both present selects hotspot`() {
        val selected = IpSelector.select(listOf(
            candidate("rmnet_data0", "100.80.1.2"),
            candidate("wlan0", "192.168.43.1")
        ))
        assertEquals("192.168.43.1", selected?.ip)
    }

    @Test
    fun `cellular lost while hotspot remains keeps hotspot`() {
        val selected = IpSelector.select(listOf(candidate("swlan0", "192.168.43.1")))
        assertEquals("192.168.43.1", selected?.ip)
    }

    @Test
    fun `hotspot variants all beat plain wifi`() {
        for (iface in listOf("swlan0", "ap0", "softap0")) {
            val selected = IpSelector.select(listOf(
                candidate("wlan0", "192.168.1.7"),
                candidate(iface, "172.16.0.1")
            ))
            assertEquals(iface, selected?.iface)
        }
    }

    @Test
    fun `no candidates selects null`() {
        assertNull(IpSelector.select(emptyList()))
    }
}
