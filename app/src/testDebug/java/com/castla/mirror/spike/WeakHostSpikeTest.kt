package com.castla.mirror.spike

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeakHostSpikeTest {

    @Test fun `conflict when synthetic address already present (CLAT phone)`() {
        assertTrue(WeakHostSpike.addressConflict(listOf("192.168.1.5", "10.0.0.2", "192.0.0.8")))
    }

    @Test fun `no conflict when synthetic address absent`() {
        assertFalse(WeakHostSpike.addressConflict(listOf("192.168.1.5", "10.92.237.1", "100.64.0.3")))
    }

    @Test fun `no conflict on empty interface list`() {
        assertFalse(WeakHostSpike.addressConflict(emptyList()))
    }

    @Test fun `near-miss address does not count as conflict`() {
        assertFalse(WeakHostSpike.addressConflict(listOf("192.0.0.9", "192.0.0.80")))
    }
}
