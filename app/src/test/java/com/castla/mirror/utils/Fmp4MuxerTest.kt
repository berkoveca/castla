package com.castla.mirror.utils

import org.junit.Assert.*
import org.junit.Test

class Fmp4MuxerTest {

    private fun startCode4(vararg bytes: Int): ByteArray =
        byteArrayOf(0, 0, 0, 1) + bytes.toByteArray()

    private fun findBox(data: ByteArray, type: String): Int {
        var i = 0
        while (i + 8 <= data.size) {
            val size = ((data[i].toLong() and 0xFF) shl 24) or
                ((data[i + 1].toLong() and 0xFF) shl 16) or
                ((data[i + 2].toLong() and 0xFF) shl 8) or
                (data[i + 3].toLong() and 0xFF)
            val t = String(data.copyOfRange(i + 4, i + 8), Charsets.US_ASCII)
            if (t == type) return i
            if (size <= 0) break
            i += size.toInt()
        }
        return -1
    }

    private fun u32At(data: ByteArray, off: Int): Long =
        ((data[off].toLong() and 0xFF) shl 24) or
            ((data[off + 1].toLong() and 0xFF) shl 16) or
            ((data[off + 2].toLong() and 0xFF) shl 8) or
            (data[off + 3].toLong() and 0xFF)

    private val CONTAINER_TYPES = setOf("moof", "traf", "moov", "trak", "mdia", "minf", "stbl", "mvex")

    /** Like [findBox] but descends into ISO-BMFF container boxes. */
    private fun findBoxDeep(data: ByteArray, type: String, offset: Int = 0): Int {
        var i = offset
        while (i + 8 <= data.size) {
            val size = u32At(data, i).toInt()
            if (size < 8) break
            val t = String(data.copyOfRange(i + 4, i + 8), Charsets.US_ASCII)
            if (t == type) return i
            if (t in CONTAINER_TYPES) {
                val found = findBoxDeep(data, type, i + 8)
                if (found >= 0) return found
            }
            i += size
        }
        return -1
    }

    @Test
    fun codecString_derivesAvc1FromSps() {
        val sps = startCode4(0x67, 0x42, 0x00, 0x1E, 0x9A, 0x66, 0x02, 0x80)
        val pps = startCode4(0x68, 0xCE, 0x3C, 0x80)
        var init: ByteArray? = null
        val muxer = Fmp4Muxer(sps, pps, 1280, 800, 30, onInit = { init = it }, onFragment = { _, _ -> })
        assertEquals("avc1.42001e", muxer.codecString())
        assertNotNull(init)
    }

    @Test
    fun initSegment_containsFtypMoovAndAvcc() {
        val sps = startCode4(0x67, 0x42, 0x00, 0x1E, 0x9A, 0x66, 0x02, 0x80)
        val pps = startCode4(0x68, 0xCE, 0x3C, 0x80)
        var init: ByteArray? = null
        Fmp4Muxer(sps, pps, 1280, 800, 30, onInit = { init = it }, onFragment = { _, _ -> })
        val seg = init!!
        assertEquals(0, findBox(seg, "ftyp"))
        assertTrue(findBox(seg, "moov") > 0)
        // avcC / avc1 are nested inside moov; check their presence in the raw bytes.
        val segStr = seg.toString(Charsets.US_ASCII)
        assertTrue(segStr.contains("avcC"))
        assertTrue(segStr.contains("avc1"))
    }

    @Test
    fun firstDeltaFrame_isDroppedUntilKeyframe() {
        val sps = startCode4(0x67, 0x42, 0x00, 0x1E)
        val pps = startCode4(0x68, 0xCE, 0x3C)
        val fragments = mutableListOf<ByteArray>()
        val muxer = Fmp4Muxer(sps, pps, 1280, 800, 30, onInit = {}, onFragment = { f, _ -> fragments.add(f) })
        // delta before keyframe -> dropped
        muxer.addFrame(startCode4(0x41, 0x01, 0x02), isKeyFrame = false)
        assertEquals(0, fragments.size)
        // keyframe -> emitted
        muxer.addFrame(startCode4(0x65, 0x01, 0x02), isKeyFrame = true)
        assertEquals(1, fragments.size)
        // subsequent delta -> emitted
        muxer.addFrame(startCode4(0x41, 0x03, 0x04), isKeyFrame = false)
        assertEquals(2, fragments.size)
    }

    @Test
    fun fragment_hasPatchedDataOffsetAndAvccSample() {
        val sps = startCode4(0x67, 0x42, 0x00, 0x1E)
        val pps = startCode4(0x68, 0xCE, 0x3C)
        var fragment: ByteArray? = null
        val muxer = Fmp4Muxer(sps, pps, 1280, 800, 30, onInit = {}, onFragment = { f, _ -> fragment = f })
        muxer.addFrame(startCode4(0x65, 0xAA, 0xBB, 0xCC), isKeyFrame = true)

        val frag = fragment!!
        assertEquals(0, findBox(frag, "moof"))
        val mdatIdx = findBox(frag, "mdat")
        assertTrue(mdatIdx > 0)

        val moofSize = u32At(frag, 0)

        val trunIdx = findBoxDeep(frag, "trun")
        assertTrue(trunIdx > 0)
        // data_offset is at trunBoxStart + 16 (size + type + fullbox + sample_count)
        val dataOffset = u32At(frag, trunIdx + 16)
        assertEquals((moofSize + 8).toInt(), dataOffset.toInt())

        val mdatSize = u32At(frag, mdatIdx)
        // mdat = 8-byte header + AVCC sample (4-byte length prefix + 4 NALU bytes)
        assertEquals(8 + 8, mdatSize.toInt())
        assertEquals(0, frag[mdatIdx + 8].toInt())
        assertEquals(0, frag[mdatIdx + 9].toInt())
        assertEquals(0, frag[mdatIdx + 10].toInt())
        assertEquals(4, frag[mdatIdx + 11].toInt())
        assertEquals(0x65.toByte(), frag[mdatIdx + 12])
        assertEquals(0xAA.toByte(), frag[mdatIdx + 13])
        assertEquals(0xBB.toByte(), frag[mdatIdx + 14])
        assertEquals(0xCC.toByte(), frag[mdatIdx + 15])
    }

    @Test
    fun frameDuration_scalesWithFps() {
        val sps = startCode4(0x67, 0x42, 0x00, 0x1E)
        val pps = startCode4(0x68, 0xCE, 0x3C)
        val decodeTimes = mutableListOf<Long>()
        val muxer = Fmp4Muxer(sps, pps, 1280, 800, 30, onInit = {}, onFragment = { f, _ ->
            val tfdt = findBoxDeep(f, "tfdt")
            // tfdt: size(4) + type(4) + fullbox(4) + baseMediaDecodeTime(8)
            var v = 0L
            for (k in 0..7) v = (v shl 8) or (f[tfdt + 12 + k].toLong() and 0xFF)
            decodeTimes.add(v)
        })
        muxer.addFrame(startCode4(0x65, 0x01), isKeyFrame = true)
        muxer.addFrame(startCode4(0x41, 0x02), isKeyFrame = false)
        assertEquals(0L, decodeTimes[0])
        // 30fps with timescale 90000 -> 3000 ticks per frame
        assertEquals(3000L, decodeTimes[1])
    }
}
