package com.castla.mirror.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflaredLogFilterTest {

    private fun line(level: String, msg: String) = "2026-09-25T10:00:00Z $level $msg"

    @Test
    fun `warnings errors and fatals are always persisted`() {
        assertTrue(CloudflaredLogFilter.shouldPersist(line("WRN", "Your version is outdated")))
        assertTrue(CloudflaredLogFilter.shouldPersist(line("ERR", "Failed to dial a quic connection error=\"timeout\"")))
        assertTrue(CloudflaredLogFilter.shouldPersist(line("FTL", "tunnel token is invalid")))
        assertTrue(CloudflaredLogFilter.shouldPersist(line("PNC", "boom")))
    }

    @Test
    fun `key lifecycle info lines are persisted`() {
        assertTrue(CloudflaredLogFilter.shouldPersist(
            line("INF", "Registered tunnel connection connIndex=0 location=fra08 protocol=quic")))
        assertTrue(CloudflaredLogFilter.shouldPersist(line("INF", "Unregistered tunnel connection connIndex=0")))
        assertTrue(CloudflaredLogFilter.shouldPersist(line("INF", "Starting tunnel tunnelID=abc")))
        assertTrue(CloudflaredLogFilter.shouldPersist(line("INF", "Version 2026.9.0 (Checksum abc)")))
        assertTrue(CloudflaredLogFilter.shouldPersist(line("INF", "Initial protocol quic")))
        assertTrue(CloudflaredLogFilter.shouldPersist(line("INF", "Retrying connection in up to 2s")))
    }

    @Test
    fun `noisy info and debug lines are dropped`() {
        assertFalse(CloudflaredLogFilter.shouldPersist(line("INF", "Starting metrics server on 127.0.0.1:20241/metrics")))
        assertFalse(CloudflaredLogFilter.shouldPersist(line("DBG", "Registered tunnel connection")))
        assertFalse(CloudflaredLogFilter.shouldPersist(""))
        assertFalse(CloudflaredLogFilter.shouldPersist("   "))
    }

    @Test
    fun `go panic header lines are persisted but stack frames are not`() {
        assertTrue(CloudflaredLogFilter.shouldPersist("panic: runtime error: invalid memory address"))
        assertTrue(CloudflaredLogFilter.shouldPersist("fatal error: out of memory"))
        assertFalse(CloudflaredLogFilter.shouldPersist("goroutine 1 [running]:"))
        assertFalse(CloudflaredLogFilter.shouldPersist("\t/go/src/runtime/panic.go:1038 +0x215"))
    }

    @Test
    fun `redact removes the connector token`() {
        val token = "eyJhIjoiYWJjZGVmMDEyMzQ1Njc4OSIsInQiOiIxMjM0NTY3OC0xMjM0LTEyMzQtMTIzNC0xMjM0NTY3ODkwMTIiLCJzIjoiT0RRMk5qVTROek0ifQ=="
        val r1 = CloudflaredLogFilter.redact("args: tunnel run --token $token")
        assertFalse(r1, r1.contains(token))
        assertTrue(r1, r1.contains("--token <redacted>"))

        val r2 = CloudflaredLogFilter.redact("Settings: map[token:$token ha-connections:1]")
        assertFalse(r2, r2.contains(token))

        val r3 = CloudflaredLogFilter.redact("blob $token end")
        assertFalse(r3, r3.contains(token))
        assertTrue(r3, r3.endsWith("end"))
    }

    @Test
    fun `redact leaves ordinary lines untouched`() {
        val l = line("INF", "Registered tunnel connection connIndex=0 ip=198.41.192.7 location=fra08")
        assertEquals(l, CloudflaredLogFilter.redact(l))
    }

    @Test
    fun `budget allows a burst then suppresses and counts`() {
        val b = PersistBudget(capacity = 3, refillIntervalMs = 10_000)
        assertTrue(b.tryAcquire(0))
        assertTrue(b.tryAcquire(1))
        assertTrue(b.tryAcquire(2))
        assertFalse(b.tryAcquire(3))
        assertFalse(b.tryAcquire(4))
        assertEquals(2, b.takeSuppressedCount())
        assertEquals(0, b.takeSuppressedCount())
    }

    @Test
    fun `budget refills over time but never above capacity`() {
        val b = PersistBudget(capacity = 2, refillIntervalMs = 1_000)
        assertTrue(b.tryAcquire(0))
        assertTrue(b.tryAcquire(0))
        assertFalse(b.tryAcquire(500))
        assertTrue(b.tryAcquire(1_000))
        assertFalse(b.tryAcquire(1_100))
        // A long quiet period refills only up to capacity.
        assertTrue(b.tryAcquire(100_000))
        assertTrue(b.tryAcquire(100_000))
        assertFalse(b.tryAcquire(100_000))
    }
}
