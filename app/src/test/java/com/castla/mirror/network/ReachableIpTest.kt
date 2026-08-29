package com.castla.mirror.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReachableIpTest {

    @Test
    fun `extracts the IPv4 literal from a Host header with a port`() {
        assertEquals("192.0.0.8", ReachableIp.ipFromHostHeader("192.0.0.8:9090"))
    }

    @Test
    fun `extracts the IPv4 literal from a Host header without a port`() {
        assertEquals("10.69.97.36", ReachableIp.ipFromHostHeader("10.69.97.36"))
    }

    @Test
    fun `rejects hostnames — only a literal proves which address was reached`() {
        assertNull(ReachableIp.ipFromHostHeader("192-0-0-8.sslip.io:9090"))
        assertNull(ReachableIp.ipFromHostHeader("localhost:9090"))
    }

    @Test
    fun `rejects IPv6 literals, missing and malformed values`() {
        assertNull(ReachableIp.ipFromHostHeader("[::1]:9090"))
        assertNull(ReachableIp.ipFromHostHeader(null))
        assertNull(ReachableIp.ipFromHostHeader(""))
        assertNull(ReachableIp.ipFromHostHeader("999.1.1.1"))
    }
}
