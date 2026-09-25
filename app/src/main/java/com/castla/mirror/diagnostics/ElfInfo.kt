package com.castla.mirror.diagnostics

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal ELF64 reader that answers one question about the bundled cloudflared
 * binary: which dynamic linker (PT_INTERP) does it ask for?
 *
 *  - `/system/bin/linker64` → built against Android's bionic libc (the Termux
 *    build): DNS goes through netd like any app.
 *  - no interpreter → a static build (the upstream `cloudflared-linux-arm64`
 *    release): does its own DNS via /etc/resolv.conf, which Android lacks.
 */
object ElfInfo {

    private const val PT_INTERP = 3

    fun isElf(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()

    /** The PT_INTERP path, or null for static / non-ELF64-LE / unreadable input. */
    fun interpreter(bytes: ByteArray): String? = try {
        if (!isElf(bytes) || bytes.size < 64 || bytes[4] != 2.toByte() || bytes[5] != 1.toByte()) {
            null
        } else {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val phOff = buf.getLong(0x20)
            val phEntSize = buf.getShort(0x36).toInt() and 0xFFFF
            val phNum = buf.getShort(0x38).toInt() and 0xFFFF
            var result: String? = null
            if (phOff > 0 && phOff < bytes.size && phEntSize >= 56) {
                for (i in 0 until phNum) {
                    val base = phOff + i.toLong() * phEntSize
                    if (base + 56 > bytes.size) break
                    val b = base.toInt()
                    if (buf.getInt(b) != PT_INTERP) continue
                    val off = buf.getLong(b + 8)
                    val size = buf.getLong(b + 32)
                    if (off < 0 || size <= 0 || off + size > bytes.size) break
                    result = String(bytes, off.toInt(), size.toInt(), Charsets.US_ASCII).trimEnd('\u0000')
                    break
                }
            }
            result
        }
    } catch (_: Throwable) {
        null
    }

    fun describe(bytes: ByteArray): String {
        if (!isElf(bytes)) return "not an ELF binary"
        val interp = interpreter(bytes)
        return when {
            interp == null -> "static (no ELF interpreter — upstream Linux build; DNS may not work on Android)"
            interp.contains("/system/bin/linker") -> "bionic (interp=$interp — Android/Termux build)"
            interp.contains("ld-linux") -> "glibc (interp=$interp — will not run on Android)"
            else -> "dynamic (interp=$interp)"
        }
    }
}
