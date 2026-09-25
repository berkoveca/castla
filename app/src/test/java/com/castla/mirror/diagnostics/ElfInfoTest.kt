package com.castla.mirror.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ElfInfoTest {

    /** Minimal little-endian ELF64 with one program header. */
    private fun elf(phType: Int, interp: String? = null): ByteArray {
        val interpOffset = 200
        val buf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0, 0x7F); buf.put(1, 'E'.code.toByte()); buf.put(2, 'L'.code.toByte()); buf.put(3, 'F'.code.toByte())
        buf.put(4, 2) // ELFCLASS64
        buf.put(5, 1) // ELFDATA2LSB
        buf.putLong(0x20, 64L) // e_phoff
        buf.putShort(0x36, 56) // e_phentsize
        buf.putShort(0x38, 1)  // e_phnum
        buf.putInt(64, phType) // p_type
        if (interp != null) {
            val bytes = (interp + "\u0000").toByteArray()
            buf.putLong(64 + 8, interpOffset.toLong())   // p_offset
            buf.putLong(64 + 32, bytes.size.toLong())    // p_filesz
            bytes.forEachIndexed { i, b -> buf.put(interpOffset + i, b) }
        }
        return buf.array()
    }

    @Test
    fun `reads the bionic interpreter`() {
        assertEquals("/system/bin/linker64", ElfInfo.interpreter(elf(3, "/system/bin/linker64")))
    }

    @Test
    fun `static binary has no interpreter`() {
        assertNull(ElfInfo.interpreter(elf(1)))
        assertTrue(ElfInfo.describe(elf(1)).contains("static"))
    }

    @Test
    fun `describe flags a bionic build`() {
        assertTrue(ElfInfo.describe(elf(3, "/system/bin/linker64")).contains("bionic"))
    }

    @Test
    fun `describe flags a glibc build`() {
        val d = ElfInfo.describe(elf(3, "/lib/ld-linux-aarch64.so.1"))
        assertTrue(d, d.contains("glibc"))
    }

    @Test
    fun `non elf input is handled`() {
        assertNull(ElfInfo.interpreter(ByteArray(10)))
        assertTrue(ElfInfo.describe("hello world".toByteArray()).contains("not an ELF"))
    }

    @Test
    fun `truncated or corrupt headers do not throw`() {
        val e = elf(3, "/system/bin/linker64")
        assertNull(ElfInfo.interpreter(e.copyOf(80)))
        val corrupt = e.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(0x20, Long.MAX_VALUE) }
        assertNull(ElfInfo.interpreter(corrupt))
    }
}
