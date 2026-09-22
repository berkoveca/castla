package com.castla.mirror.policy

import org.junit.Assert.*
import org.junit.Test

class ClientReconnectPolicyTest {

    @Test
    fun `backoff grows then caps`() {
        val a0 = ClientReconnectPolicy.delayMs(0)
        val a1 = ClientReconnectPolicy.delayMs(1)
        val a2 = ClientReconnectPolicy.delayMs(2)
        assertEquals(ClientReconnectPolicy.BASE_DELAY_MS, a0)
        assertTrue(a1 > a0)
        assertTrue(a2 > a1)
        assertEquals(
            ClientReconnectPolicy.MAX_DELAY_MS,
            ClientReconnectPolicy.delayMs(20)
        )
    }

    @Test
    fun `negative attempts use the base delay`() {
        assertEquals(ClientReconnectPolicy.BASE_DELAY_MS, ClientReconnectPolicy.delayMs(-3))
    }
}
