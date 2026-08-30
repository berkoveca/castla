package com.castla.mirror.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunSynParserTest {

    /** Build a minimal IPv4 packet: 20-byte IP header + [tcpFlags]-carrying TCP header. */
    private fun ipv4Packet(proto: Int, tcpFlags: Int, withTcpHeader: Boolean = true): ByteArray {
        val tcpLen = if (withTcpHeader) 20 else 0
        val p = ByteArray(20 + tcpLen)
        p[0] = 0x45                      // version 4, IHL 5 (20 bytes)
        p[9] = proto.toByte()            // protocol
        p[12] = 10; p[13] = 92; p[14] = 237.toByte(); p[15] = 5   // src 10.92.237.5
        p[16] = 198.toByte(); p[17] = 51; p[18] = 100; p[19] = 2  // dst 198.51.100.2
        if (withTcpHeader) p[20 + 13] = tcpFlags.toByte()         // TCP flags at ihl+13
        return p
    }

    private val SYN = 0x02
    private val ACK = 0x10

    @Test fun `IPv4 TCP SYN is detected`() {
        val r = TunSynParser.parse(ipv4Packet(proto = 6, tcpFlags = SYN), 40)
        assertTrue(r.isSyn)
        assertEquals("10.92.237.5", r.srcIp)
        assertEquals("198.51.100.2", r.dstIp)
    }

    @Test fun `SYN-ACK is not a fresh SYN`() {
        assertFalse(TunSynParser.parse(ipv4Packet(proto = 6, tcpFlags = SYN or ACK), 40).isSyn)
    }

    @Test fun `ACK-only is not a SYN`() {
        assertFalse(TunSynParser.parse(ipv4Packet(proto = 6, tcpFlags = ACK), 40).isSyn)
    }

    @Test fun `UDP packet is not a SYN`() {
        assertFalse(TunSynParser.parse(ipv4Packet(proto = 17, tcpFlags = SYN), 40).isSyn)
    }

    @Test fun `too-short packet is not a SYN`() {
        assertFalse(TunSynParser.parse(byteArrayOf(0x45, 0, 0, 0), 4).isSyn)
    }

    @Test fun `truncated TCP header is not a SYN`() {
        // Full IP header present but TCP flags byte missing (len stops mid TCP header).
        assertFalse(TunSynParser.parse(ipv4Packet(proto = 6, tcpFlags = SYN), 25).isSyn)
    }

    @Test fun `non-IPv4 version is rejected`() {
        val p = ipv4Packet(proto = 6, tcpFlags = SYN)
        p[0] = 0x65 // version 6, IHL 5
        assertFalse(TunSynParser.parse(p, 40).isSyn)
    }
}
