package com.nicobrailo.batidoorlink.media

import com.nicobrailo.batidoorlink.rtsp.RtpPacket
import com.nicobrailo.batidoorlink.rtsp.SequenceTracker
import java.io.ByteArrayOutputStream
import java.util.Base64

/** One picture's NAL units, in Annex B form (each after a 00 00 00 01 start code), as MediaCodec takes them. */
class AccessUnit(val data: ByteArray, val rtpTimestamp: Long, val keyframe: Boolean, val completedAtNanos: Long)

/**
 * Reassembles H.264 access units from RTP (RFC 6184, packetization mode 1:
 * single NAL units, STAP-A and FU-A, which is what the Reolink sends).
 *
 * A picture ends at the packet with the marker bit, or when the timestamp
 * changes. The marker only counts once the picture has a slice in it: the
 * Reolink sets it on the SPS it sends ahead of a keyframe too, which would
 * otherwise split the parameter sets off their picture. A lost packet spoils the picture it was in and every one after
 * it that refers back, so after a loss nothing is passed on until the next
 * keyframe: a frozen picture is better than a smeared one.
 */
class H264Depacketizer(private val onAccessUnit: (AccessUnit) -> Unit) {
    private val sequence = SequenceTracker()
    private val current = ByteArrayOutputStream()
    private var currentTimestamp = -1L
    private var currentKeyframe = false
    private var currentHasSlice = false
    private var currentBroken = false
    private var fragment: ByteArrayOutputStream? = null
    private var waitingForKeyframe = true

    val packetsLost get() = sequence.lost
    /** Pictures thrown away, broken by a loss or waiting for a keyframe after one. */
    var accessUnitsDropped = 0L
        private set

    fun push(packet: RtpPacket, nowNanos: Long = System.nanoTime()) {
        val inOrder = sequence.next(packet.sequence)
        if (packet.timestamp != currentTimestamp) {
            // A packet missing at the boundary could have been the end of the
            // picture being assembled or the start of this one: both are spoilt.
            if (!inOrder) currentBroken = true
            finish(nowNanos)
            currentTimestamp = packet.timestamp
        }
        if (!inOrder) {
            currentBroken = true
            fragment = null
        }
        if (packet.payloadLength > 0) {
            val type = packet.payloadByte(0) and 0x1F
            when (type) {
                in 1..23 -> addNal(packet.data, packet.payloadOffset, packet.payloadLength)
                STAP_A -> unpackStapA(packet)
                FU_A -> addFragment(packet)
                else -> Unit // STAP-B, MTAP and FU-B aren't allowed in mode 1.
            }
        }
        if (packet.marker && currentHasSlice) finish(nowNanos)
    }

    private fun unpackStapA(packet: RtpPacket) {
        var i = 1
        while (i + 2 <= packet.payloadLength) {
            val size = (packet.payloadByte(i) shl 8) or packet.payloadByte(i + 1)
            i += 2
            if (size == 0 || i + size > packet.payloadLength) {
                currentBroken = true
                return
            }
            addNal(packet.data, packet.payloadOffset + i, size)
            i += size
        }
    }

    private fun addFragment(packet: RtpPacket) {
        if (packet.payloadLength < 2) return
        val indicator = packet.payloadByte(0)
        val header = packet.payloadByte(1)
        val start = header and 0x80 != 0
        val end = header and 0x40 != 0
        if (start) {
            fragment = ByteArrayOutputStream().apply { write((indicator and 0xE0) or (header and 0x1F)) }
        }
        val f = fragment ?: return // The start was lost; currentBroken already says so.
        f.write(packet.data, packet.payloadOffset + 2, packet.payloadLength - 2)
        if (end) {
            val nal = f.toByteArray()
            addNal(nal, 0, nal.size)
            fragment = null
        }
    }

    private fun addNal(data: ByteArray, offset: Int, length: Int) {
        val type = data[offset].toInt() and 0x1F
        if (type == NAL_IDR) currentKeyframe = true
        if (type in 1..5) currentHasSlice = true
        current.write(START_CODE)
        current.write(data, offset, length)
    }

    private fun finish(nowNanos: Long) {
        if (current.size() > 0 || fragment != null) {
            val broken = currentBroken || fragment != null
            when {
                broken -> {
                    waitingForKeyframe = true
                    accessUnitsDropped++
                }
                waitingForKeyframe && !currentKeyframe -> accessUnitsDropped++
                else -> {
                    waitingForKeyframe = false
                    onAccessUnit(AccessUnit(current.toByteArray(), currentTimestamp, currentKeyframe, nowNanos))
                }
            }
        }
        current.reset()
        fragment = null
        currentKeyframe = false
        currentHasSlice = false
        currentBroken = false
    }

    companion object {
        const val NAL_IDR = 5
        const val NAL_SPS = 7
        const val NAL_PPS = 8
        private const val STAP_A = 24
        private const val FU_A = 28
        val START_CODE = byteArrayOf(0, 0, 0, 1)

        /** The SPS and PPS from an fmtp's `sprop-parameter-sets`, each with its start code, as csd-0 and csd-1. */
        fun parameterSets(sprop: String?): List<ByteArray> =
            sprop?.split(',')?.mapNotNull {
                try {
                    START_CODE + Base64.getDecoder().decode(it.trim())
                } catch (_: IllegalArgumentException) {
                    null
                }
            } ?: emptyList()
    }
}

/** The picture size a sequence parameter set describes (ITU-T H.264 7.3.2.1.1), after cropping. */
data class SpsSize(val width: Int, val height: Int) {
    companion object {
        /** [nal] is the SPS NAL unit, with or without a start code. Null if it can't be read. */
        fun parse(nal: ByteArray): SpsSize? = try {
            var start = 0
            // Skip a start code if there is one.
            if (nal.size > 4 && nal[0].toInt() == 0 && nal[1].toInt() == 0) {
                start = if (nal[2].toInt() == 1) 3 else 4
            }
            if ((nal[start].toInt() and 0x1F) != H264Depacketizer.NAL_SPS) null
            else read(BitReader(unescape(nal, start + 1)))
        } catch (_: IndexOutOfBoundsException) {
            null
        }

        private fun read(r: BitReader): SpsSize {
            val profile = r.bits(8)
            r.bits(16) // constraint flags, level
            r.ue() // seq_parameter_set_id
            var chromaFormat = 1
            if (profile in HIGH_PROFILES) {
                chromaFormat = r.ue()
                if (chromaFormat == 3) r.bits(1) // separate_colour_plane_flag
                r.ue() // bit_depth_luma_minus8
                r.ue() // bit_depth_chroma_minus8
                r.bits(1) // qpprime_y_zero_transform_bypass_flag
                if (r.bits(1) == 1) { // seq_scaling_matrix_present_flag
                    repeat(if (chromaFormat != 3) 8 else 12) { i ->
                        if (r.bits(1) == 1) skipScalingList(r, if (i < 6) 16 else 64)
                    }
                }
            }
            r.ue() // log2_max_frame_num_minus4
            when (r.ue()) { // pic_order_cnt_type
                0 -> r.ue()
                1 -> {
                    r.bits(1)
                    r.se()
                    r.se()
                    repeat(r.ue()) { r.se() }
                }
            }
            r.ue() // max_num_ref_frames
            r.bits(1) // gaps_in_frame_num_value_allowed_flag
            val widthMbs = r.ue() + 1
            val heightMapUnits = r.ue() + 1
            val frameMbsOnly = r.bits(1)
            if (frameMbsOnly == 0) r.bits(1) // mb_adaptive_frame_field_flag
            r.bits(1) // direct_8x8_inference_flag
            var width = widthMbs * 16
            var height = (2 - frameMbsOnly) * heightMapUnits * 16
            if (r.bits(1) == 1) { // frame_cropping_flag
                val left = r.ue()
                val right = r.ue()
                val top = r.ue()
                val bottom = r.ue()
                val cropX = if (chromaFormat == 1 || chromaFormat == 2) 2 else 1
                val cropY = (if (chromaFormat == 1) 2 else 1) * (2 - frameMbsOnly)
                width -= (left + right) * cropX
                height -= (top + bottom) * cropY
            }
            return SpsSize(width, height)
        }

        private fun skipScalingList(r: BitReader, size: Int) {
            var last = 8
            var next = 8
            repeat(size) {
                if (next != 0) next = (last + r.se() + 256) % 256
                if (next != 0) last = next
            }
        }

        /** Drops the emulation prevention bytes (00 00 03 becomes 00 00). */
        private fun unescape(b: ByteArray, from: Int): ByteArray {
            val out = ByteArrayOutputStream(b.size)
            var zeros = 0
            for (i in from until b.size) {
                val v = b[i].toInt() and 0xFF
                if (zeros >= 2 && v == 3) {
                    zeros = 0
                    continue
                }
                zeros = if (v == 0) zeros + 1 else 0
                out.write(v)
            }
            return out.toByteArray()
        }

        private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
    }
}

private class BitReader(private val data: ByteArray) {
    private var bit = 0

    fun bits(n: Int): Int {
        var v = 0
        repeat(n) {
            val byte = data[bit shr 3].toInt()
            v = (v shl 1) or ((byte shr (7 - (bit and 7))) and 1)
            bit++
        }
        return v
    }

    /** Unsigned Exp-Golomb. */
    fun ue(): Int {
        var zeros = 0
        while (bits(1) == 0) zeros++
        return (1 shl zeros) - 1 + bits(zeros)
    }

    /** Signed Exp-Golomb. */
    fun se(): Int {
        val k = ue()
        return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
    }
}
