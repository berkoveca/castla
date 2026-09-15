package com.castla.mirror.utils

import java.io.ByteArrayOutputStream

/**
 * Minimal fragmented-MP4 (ISO BMFF) muxer for live H.264 streaming over MSE.
 *
 * The Android [com.castla.mirror.capture.VideoEncoder] emits Annex-B NALUs
 * (SPS/PPS + per-frame access units). Browsers (including Tesla MCU2's
 * Chromium 88) cannot consume raw NALUs through Media Source Extensions — they
 * require fMP4. This muxer turns the NALU stream into:
 *   - one init segment (`ftyp` + `moov`/`avcC`) emitted via [onInit]
 *   - one `moof`+`mdat` fragment per frame emitted via [onFragment]
 *
 * Each fragment is self-contained and uses `default-base-is-moof` with a single
 * sample, which is what Chromium's MSE parser expects for low-latency appending.
 */
class Fmp4Muxer(
    spsWithStartCode: ByteArray,
    ppsWithStartCode: ByteArray,
    private val width: Int,
    private val height: Int,
    fps: Int,
    private val timescale: Int = 90_000,
    private val onInit: (ByteArray) -> Unit,
    private val onFragment: (ByteArray, Boolean) -> Unit
) {
    private val sps = stripStartCode(spsWithStartCode)
    private val pps = stripStartCode(ppsWithStartCode)
    private val frameDuration = if (fps > 0) (timescale.toLong() / fps) else timescale.toLong()
    private var decodeTime: Long = 0L
    private var seq: Long = 1L
    private var haveKeyframe = false

    init {
        require(sps.size > 4) { "Invalid SPS" }
        require(pps.size > 0) { "Invalid PPS" }
        onInit(buildInitSegment())
    }

    fun codecString(): String {
        val p = (sps[1].toInt() and 0xFF).toString(16).padStart(2, '0')
        val c = (sps[2].toInt() and 0xFF).toString(16).padStart(2, '0')
        val l = (sps[3].toInt() and 0xFF).toString(16).padStart(2, '0')
        return "avc1.$p$c$l"
    }

    /**
     * Feed one encoded frame. [annexBFrame] must NOT include the 8-byte network
     * header used by the video socket — only the Annex-B access unit.
     */
    fun addFrame(annexBFrame: ByteArray, isKeyFrame: Boolean) {
        if (!haveKeyframe && !isKeyFrame) {
            // Decoder cannot start without an IDR; drop leading delta frames.
            return
        }
        haveKeyframe = true
        val sample = annexBToAvcc(annexBFrame)
        val fragment = buildFragment(sample, isKeyFrame)
        onFragment(fragment, isKeyFrame)
        decodeTime += frameDuration
    }

    // ---- segment builders ----

    private fun buildInitSegment(): ByteArray {
        val ftyp = box(
            "ftyp",
            concat("isom".ascii(), u32(0), "isom".ascii(), "avc1".ascii(), "mp42".ascii(), "dash".ascii())
        )
        val moov = box("moov", concat(mvhd(), trak(), mvex()))
        return concat(ftyp, moov)
    }

    private fun mvhd(): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(fullBoxHeader(0, 0))
        p.write(u32(0)) // creation_time
        p.write(u32(0)) // modification_time
        p.write(u32(timescale.toLong())) // timescale
        p.write(u32(0)) // duration
        p.write(u32(0x00010000)) // rate
        p.write(u16(0x0100)) // volume
        p.write(u16(0)) // reserved
        p.write(u32(0)); p.write(u32(0)) // reserved[2]
        p.write(u32(0x00010000)); p.write(u32(0)); p.write(u32(0))
        p.write(u32(0)); p.write(u32(0x00010000)); p.write(u32(0))
        p.write(u32(0)); p.write(u32(0)); p.write(u32(0x40000000)) // matrix
        for (i in 0..5) p.write(u32(0)) // predefined[6]
        p.write(u32(2)) // next_track_ID
        return box("mvhd", p.toByteArray())
    }

    private fun trak(): ByteArray = box("trak", concat(tkhd(), mdia()))

    private fun tkhd(): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(fullBoxHeader(0, 0x000007))
        p.write(u32(0)); p.write(u32(0)) // creation/modification
        p.write(u32(1)) // track_ID
        p.write(u32(0)) // reserved
        p.write(u32(0)) // duration
        p.write(u32(0)); p.write(u32(0)) // reserved[2]
        p.write(u16(0)) // layer
        p.write(u16(0)) // alternate_group
        p.write(u16(0)) // volume
        p.write(u16(0)) // reserved
        p.write(u32(0x00010000)); p.write(u32(0)); p.write(u32(0))
        p.write(u32(0)); p.write(u32(0x00010000)); p.write(u32(0))
        p.write(u32(0)); p.write(u32(0)); p.write(u32(0x40000000)) // matrix
        p.write(u32((width shl 16).toLong())) // width
        p.write(u32((height shl 16).toLong())) // height
        return box("tkhd", p.toByteArray())
    }

    private fun mdia(): ByteArray = box("mdia", concat(mdhd(), hdlr(), minf()))

    private fun mdhd(): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(fullBoxHeader(0, 0))
        p.write(u32(0)); p.write(u32(0))
        p.write(u32(timescale.toLong()))
        p.write(u32(0)) // duration
        p.write(u16(0x55C4)) // language (und)
        p.write(u16(0)) // predefined
        return box("mdhd", p.toByteArray())
    }

    private fun hdlr(): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(fullBoxHeader(0, 0))
        p.write(u32(0)) // predefined
        p.write("vide".ascii()) // handler_type
        p.write(u32(0)); p.write(u32(0)); p.write(u32(0)) // reserved
        val name = "VideoHandler".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        p.write(name)
        return box("hdlr", p.toByteArray())
    }

    private fun minf(): ByteArray = box("minf", concat(vmhd(), dinf(), stbl()))

    private fun vmhd(): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(fullBoxHeader(0, 0x000001))
        p.write(u16(0)) // graphicsmode
        p.write(u16(0)); p.write(u16(0)); p.write(u16(0)) // opcolor
        return box("vmhd", p.toByteArray())
    }

    private fun dinf(): ByteArray {
        val dref = ByteArrayOutputStream().apply {
            write(fullBoxHeader(0, 0))
            write(u32(1)) // entry_count
            write(box("url ", fullBoxHeader(0, 0x000001))) // self-contained
        }
        return box("dinf", dref.toByteArray())
    }

    private fun stbl(): ByteArray = box("stbl", concat(stsd(), stts(), stsc(), stsz(), stco()))

    private fun stsd(): ByteArray {
        val avcC = buildAvcC()
        val avc1 = ByteArrayOutputStream().apply {
            write(ByteArray(6)) // reserved
            write(u16(1)) // data_reference_index
            write(u16(0)); write(u16(0)) // predefined, reserved
            write(u32(0)); write(u32(0)); write(u32(0)) // predefined[3]
            write(u16(width)); write(u16(height))
            write(u32(0x00480000)) // horizresolution
            write(u32(0x00480000)) // vertresolution
            write(u32(0)) // reserved
            write(u16(1)) // frame_count
            write(ByteArray(32)) // compressorname
            write(u16(0x0018)) // depth
            write(u16(0xFFFF)) // pre_defined
            write(avcC)
        }
        val p = ByteArrayOutputStream().apply {
            write(fullBoxHeader(0, 0))
            write(u32(1)) // entry_count
            write(box("avc1", avc1.toByteArray()))
        }
        return box("stsd", p.toByteArray())
    }

    private fun buildAvcC(): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(1) // configurationVersion
        p.write(sps[1].toInt() and 0xFF) // AVCProfileIndication
        p.write(sps[2].toInt() and 0xFF) // profile_compatibility
        p.write(sps[3].toInt() and 0xFF) // AVCLevelIndication
        p.write(0xFF) // 6 bits reserved (1) + lengthSizeMinusOne = 3
        p.write(0xE1) // 3 bits reserved (1) + numOfSequenceParameterSets = 1
        p.write(u16(sps.size))
        p.write(sps)
        p.write(1) // numOfPictureParameterSets
        p.write(u16(pps.size))
        p.write(pps)
        return box("avcC", p.toByteArray())
    }

    private fun stts(): ByteArray {
        val p = ByteArrayOutputStream().apply { write(fullBoxHeader(0, 0)); write(u32(0)) }
        return box("stts", p.toByteArray())
    }

    private fun stsc(): ByteArray {
        val p = ByteArrayOutputStream().apply { write(fullBoxHeader(0, 0)); write(u32(0)) }
        return box("stsc", p.toByteArray())
    }

    private fun stsz(): ByteArray {
        val p = ByteArrayOutputStream().apply { write(fullBoxHeader(0, 0)); write(u32(0)); write(u32(0)) }
        return box("stsz", p.toByteArray())
    }

    private fun stco(): ByteArray {
        val p = ByteArrayOutputStream().apply { write(fullBoxHeader(0, 0)); write(u32(0)) }
        return box("stco", p.toByteArray())
    }

    private fun mvex(): ByteArray {
        val trex = ByteArrayOutputStream().apply {
            write(fullBoxHeader(0, 0))
            write(u32(1)) // track_ID
            write(u32(1)) // default_sample_description_index
            write(u32(frameDuration)) // default_sample_duration
            write(u32(0)) // default_sample_size
            write(u32(0x01010000)) // default_sample_flags (non-sync)
        }
        return box("mvex", box("trex", trex.toByteArray()))
    }

    private fun buildFragment(sample: ByteArray, isKeyFrame: Boolean): ByteArray {
        val sampleFlags = if (isKeyFrame) 0x02000000 else 0x01010000

        val trun = ByteArrayOutputStream().apply {
            write(fullBoxHeader(0, 0x000001 or 0x000004 or 0x000008 or 0x000100))
            write(u32(1)) // sample_count
            write(u32(0)) // data_offset placeholder
            write(u32(frameDuration)) // sample_duration
            write(u32(sample.size.toLong())) // sample_size
            write(u32(sampleFlags.toLong())) // sample_flags
        }.let { box("trun", it.toByteArray()) }

        val tfhd = ByteArrayOutputStream().apply {
            write(fullBoxHeader(0, 0x020000 or 0x000008)) // default-base-is-moof | default-sample-flags-present
            write(u32(1)) // track_ID
            write(u32(sampleFlags.toLong())) // default_sample_flags
        }.let { box("tfhd", it.toByteArray()) }

        val tfdt = ByteArrayOutputStream().apply {
            write(fullBoxHeader(1, 0)) // version 1 -> 64-bit baseMediaDecodeTime
            write(u64(decodeTime))
        }.let { box("tfdt", it.toByteArray()) }

        val traf = box("traf", concat(tfhd, tfdt, trun))
        val mfhd = ByteArrayOutputStream().apply {
            write(fullBoxHeader(0, 0))
            write(u32(seq))
        }.let { box("mfhd", it.toByteArray()) }

        val moof = box("moof", concat(mfhd, traf))
        // Patch trun data_offset (relative to moof start) now that moof size is known.
        val dataOffset = moof.size + 8 // + mdat box header
        // trun is the last box in moof; its data_offset field sits 16 bytes from
        // the end (sample_flags, sample_size, sample_duration, data_offset).
        val patchAt = moof.size - 16
        System.arraycopy(u32(dataOffset.toLong()), 0, moof, patchAt, 4)

        val mdat = box("mdat", sample)
        seq++
        return concat(moof, mdat)
    }

    // ---- Annex-B helpers ----

    private fun annexBToAvcc(annexB: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (nalu in splitNalus(annexB)) {
            val body = stripStartCode(nalu)
            out.write(u32(body.size.toLong()))
            out.write(body)
        }
        return out.toByteArray()
    }

    private fun findStartCodes(data: ByteArray): List<Int> {
        val positions = mutableListOf<Int>()
        var i = 0
        while (i < data.size - 2) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (i + 2 < data.size && data[i + 2] == 1.toByte()) {
                    positions.add(i); i += 3; continue
                }
                if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    positions.add(i); i += 4; continue
                }
            }
            i++
        }
        return positions
    }

    private fun splitNalus(data: ByteArray): List<ByteArray> {
        val starts = findStartCodes(data)
        if (starts.isEmpty()) return emptyList()
        val result = mutableListOf<ByteArray>()
        for (k in starts.indices) {
            val s = starts[k]
            val e = if (k + 1 < starts.size) starts[k + 1] else data.size
            if (e > s) result.add(data.copyOfRange(s, e))
        }
        return result
    }

    // ---- byte helpers ----

    private fun stripStartCode(data: ByteArray): ByteArray {
        if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            data[2] == 0.toByte() && data[3] == 1.toByte()
        ) return data.copyOfRange(4, data.size)
        if (data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
            return data.copyOfRange(3, data.size)
        }
        return data
    }

    private fun u32(v: Long): ByteArray =
        ByteArray(4) { i -> ((v shr (24 - i * 8)) and 0xFF).toByte() }

    private fun u16(v: Int): ByteArray =
        ByteArray(2) { i -> ((v shr (8 - i * 8)) and 0xFF).toByte() }

    private fun u64(v: Long): ByteArray =
        ByteArray(8) { i -> ((v shr (56 - i * 8)) and 0xFF).toByte() }

    private fun fullBoxHeader(version: Int, flags: Int): ByteArray {
        val b = ByteArray(4)
        b[0] = version.toByte()
        b[1] = ((flags shr 16) and 0xFF).toByte()
        b[2] = ((flags shr 8) and 0xFF).toByte()
        b[3] = (flags and 0xFF).toByte()
        return b
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8L + payload.size
        val out = ByteArrayOutputStream()
        out.write(u32(size))
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(payload)
        return out.toByteArray()
    }

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (a in arrays) out.write(a)
        return out.toByteArray()
    }

    private fun String.ascii(): ByteArray = this.toByteArray(Charsets.US_ASCII)
}
