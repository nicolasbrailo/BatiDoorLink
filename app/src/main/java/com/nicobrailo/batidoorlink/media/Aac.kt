package com.nicobrailo.batidoorlink.media

import com.nicobrailo.batidoorlink.rtsp.RtpPacket
import com.nicobrailo.batidoorlink.rtsp.SequenceTracker

/** An MPEG-4 AudioSpecificConfig: what MediaCodec needs as csd-0 to decode raw AAC frames. */
data class AacConfig(val bytes: ByteArray, val objectType: Int, val sampleRate: Int, val channels: Int) {
    companion object {
        private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000,
            11025, 8000, 7350)

        /** From an fmtp's `config` (hex): "1408" is AAC LC, 16 kHz, mono. */
        fun parse(hex: String?): AacConfig? {
            if (hex == null || hex.length < 4 || hex.length % 2 != 0) return null
            val bytes = try {
                ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            } catch (_: NumberFormatException) {
                return null
            }
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val objectType = b0 shr 3
            val rateIndex = ((b0 and 0x07) shl 1) or (b1 shr 7)
            // Index 15 means an explicit 24 bit rate follows, which nobody sends for this.
            val rate = RATES.getOrNull(rateIndex) ?: return null
            return AacConfig(bytes, objectType, rate, (b1 shr 3) and 0x0F)
        }
    }

    override fun equals(other: Any?) = other is AacConfig && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
}

/**
 * Unpacks AAC frames from RTP as RFC 3640 carries them in mode AAC-hbr: a
 * 16 bit length of the AU headers in bits, the headers (each a size and an
 * index, 13 and 3 bits by default), then the frames back to back. Frames
 * split over several packets aren't handled: at 16 kHz mono a frame is a few
 * hundred bytes, and a packet holds one or more whole ones.
 */
class AacDepacketizer(
    private val sizeLength: Int,
    private val indexLength: Int,
    private val indexDeltaLength: Int,
    private val onFrame: (ByteArray, Long) -> Unit,
) {
    private val sequence = SequenceTracker()
    val packetsLost get() = sequence.lost

    fun push(packet: RtpPacket) {
        sequence.next(packet.sequence)
        if (packet.payloadLength < 2) return
        val headersBits = (packet.payloadByte(0) shl 8) or packet.payloadByte(1)
        val headersBytes = (headersBits + 7) / 8
        if (2 + headersBytes > packet.payloadLength) return
        var bit = 0
        fun read(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = packet.payloadByte(2 + (bit shr 3))
                v = (v shl 1) or ((byte shr (7 - (bit and 7))) and 1)
                bit++
            }
            return v
        }
        val sizes = mutableListOf<Int>()
        while (bit + sizeLength <= headersBits) {
            sizes += read(sizeLength)
            read(if (sizes.size == 1) indexLength else indexDeltaLength)
        }
        var offset = 2 + headersBytes
        for (size in sizes) {
            if (offset + size > packet.payloadLength) return // A fragment: dropped, as said above.
            val frame = ByteArray(size)
            System.arraycopy(packet.data, packet.payloadOffset + offset, frame, 0, size)
            onFrame(frame, packet.timestamp)
            offset += size
        }
    }

    companion object {
        /** From the fmtp parameters, with RFC 3640's AAC-hbr values where they're missing. */
        fun fromFormat(format: Map<String, String>, onFrame: (ByteArray, Long) -> Unit) = AacDepacketizer(
            format["sizelength"]?.toIntOrNull() ?: 13,
            format["indexlength"]?.toIntOrNull() ?: 3,
            format["indexdeltalength"]?.toIntOrNull() ?: 3,
            onFrame,
        )
    }
}
