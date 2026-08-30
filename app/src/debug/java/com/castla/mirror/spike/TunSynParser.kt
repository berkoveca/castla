package com.castla.mirror.spike

/**
 * DEBUG-ONLY. Pure classifier for raw IP packets read from a TUN fd (issue #51,
 * Phase 0.5a). Decides only "is this an inbound TCP SYN (connection attempt)?"
 * — it does not parse payloads, reassemble, or route. No Android deps so it is
 * unit-testable off-device.
 */
object TunSynParser {

    private const val MIN_IPV4_HEADER = 20
    private const val PROTO_TCP = 6
    private const val TCP_FLAG_SYN = 0x02
    private const val TCP_FLAG_ACK = 0x10

    /** [isSyn] = SYN set and ACK clear (a fresh connection attempt, not a handshake reply). */
    data class Result(val isSyn: Boolean, val srcIp: String?, val dstIp: String?)

    private val NOT_TCP_SYN = Result(false, null, null)

    /** Parse the first [len] bytes of [buf] as one IPv4 packet. Any malformed/non-TCP input → not-a-SYN. */
    fun parse(buf: ByteArray, len: Int): Result {
        if (len < MIN_IPV4_HEADER) return NOT_TCP_SYN
        val versionIhl = buf[0].toInt() and 0xFF
        if (versionIhl ushr 4 != 4) return NOT_TCP_SYN                 // IPv4 only
        val ihl = (versionIhl and 0x0F) * 4
        if (ihl < MIN_IPV4_HEADER || ihl > len) return NOT_TCP_SYN     // bogus / truncated header
        if ((buf[9].toInt() and 0xFF) != PROTO_TCP) return NOT_TCP_SYN // e.g. UDP
        val flagsOffset = ihl + 13                                     // TCP flags byte
        if (flagsOffset >= len) return NOT_TCP_SYN                     // truncated TCP header
        val flags = buf[flagsOffset].toInt() and 0xFF
        val isSyn = (flags and TCP_FLAG_SYN) != 0 && (flags and TCP_FLAG_ACK) == 0
        return Result(isSyn, ipv4(buf, 12), ipv4(buf, 16))
    }

    private fun ipv4(buf: ByteArray, off: Int): String =
        "${buf[off].toInt() and 0xFF}.${buf[off + 1].toInt() and 0xFF}." +
            "${buf[off + 2].toInt() and 0xFF}.${buf[off + 3].toInt() and 0xFF}"
}
