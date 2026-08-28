package com.castla.mirror.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
