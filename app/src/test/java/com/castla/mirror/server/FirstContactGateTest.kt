package com.castla.mirror.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class FirstContactGateTest {

    @Test
    fun `first non-loopback source acquires, second does not`() {
        val gate = FirstContactGate()
        assertTrue(gate.tryAcquire("192.168.43.17"))
        assertFalse(gate.tryAcquire("192.168.43.17"))
        assertFalse(gate.tryAcquire("192.168.43.99"))
    }

    @Test
    fun `loopback never acquires and never consumes the slot`() {
        val gate = FirstContactGate()
        assertFalse(gate.tryAcquire("127.0.0.1"))
        assertFalse(gate.tryAcquire("::1"))
        assertFalse(gate.tryAcquire("0:0:0:0:0:0:0:1"))
        assertFalse(gate.tryAcquire(null))
        // Self-probe traffic must not have used up first-contact
        assertTrue(gate.tryAcquire("192.168.43.17"))
    }

    @Test
    fun `concurrent parallel requests acquire exactly once`() {
        val gate = FirstContactGate()
        val threads = 16
        val start = CountDownLatch(1)
        val acquired = AtomicInteger(0)
        val workers = (1..threads).map {
            Thread {
                start.await()
                if (gate.tryAcquire("192.168.43.17")) acquired.incrementAndGet()
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals(1, acquired.get())
    }
}
