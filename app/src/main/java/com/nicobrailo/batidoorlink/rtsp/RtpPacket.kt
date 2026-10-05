package com.nicobrailo.batidoorlink.rtsp

/**
 * A received RTP packet (RFC 3550): the header fields we use, and where the
 * payload is inside [data], past any CSRCs, extension and padding.
 */
class RtpPacket(
    val data: ByteArray,
    val marker: Boolean,
    val payloadType: Int,
    val sequence: Int,
    /** Unsigned 32 bits, in the media's clock. */
    val timestamp: Long,
    val ssrc: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
) {
    fun payloadByte(i: Int): Int = data[payloadOffset + i].toInt() and 0xFF

    companion object {
        /** Null for anything that isn't a well formed RTP version 2 packet. */
        fun parse(data: ByteArray, length: Int = data.size): RtpPacket? {
            if (length < 12) return null
            val b0 = data[0].toInt() and 0xFF
            if (b0 shr 6 != 2) return null
            val b1 = data[1].toInt() and 0xFF
            var offset = 12 + 4 * (b0 and 0x0F)
            if (b0 and 0x10 != 0) {
                if (length < offset + 4) return null
                val words = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
                offset += 4 + 4 * words
            }
            var end = length
            if (b0 and 0x20 != 0) end -= data[length - 1].toInt() and 0xFF
            if (end < offset) return null
            return RtpPacket(
                data = data,
                marker = b1 and 0x80 != 0,
                payloadType = b1 and 0x7F,
                sequence = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF),
                timestamp = ((data[4].toLong() and 0xFF) shl 24) or ((data[5].toLong() and 0xFF) shl 16) or
                    ((data[6].toLong() and 0xFF) shl 8) or (data[7].toLong() and 0xFF),
                ssrc = ((data[8].toInt() and 0xFF) shl 24) or ((data[9].toInt() and 0xFF) shl 16) or
                    ((data[10].toInt() and 0xFF) shl 8) or (data[11].toInt() and 0xFF),
                payloadOffset = offset,
                payloadLength = end - offset,
            )
        }
    }
}

/** Counts packets lost from a stream's sequence numbers, which wrap at 16 bits. */
class SequenceTracker {
    private var last = -1
    var lost = 0L
        private set

    /**
     * True when [sequence] follows the previous packet directly (or is the
     * first one). False after a gap, which counts as lost, and for a packet
     * that arrives late or twice, which is out of place but loses nothing.
     */
    fun next(sequence: Int): Boolean {
        if (last < 0) {
            last = sequence
            return true
        }
        val gap = (sequence - last - 1) and 0xFFFF
        // A huge gap is a packet from behind, not 60000 lost ones; it doesn't move the count on.
        if (gap > 0x8000) return false
        last = sequence
        lost += gap
        return gap == 0
    }
}
