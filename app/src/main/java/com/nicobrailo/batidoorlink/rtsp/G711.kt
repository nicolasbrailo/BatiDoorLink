package com.nicobrailo.batidoorlink.rtsp

/**
 * The two codecs an ONVIF backchannel offers. Reolink's doorbell asked for
 * PCMA on firmware v3.0.0.2033 and PCMU from v3.0.0.3215 on, so both are
 * needed. Both are 8 kHz, one byte per sample.
 */
enum class G711(val payloadType: Int, val encodingName: String) {
    PCMU(0, "PCMU"),
    PCMA(8, "PCMA");

    fun encode(sample: Int): Int = when (this) {
        PCMU -> ulaw(sample)
        PCMA -> alaw(sample)
    }

    fun decode(byte: Int): Int = when (this) {
        PCMU -> ulawDecode(byte)
        PCMA -> alawDecode(byte)
    }

    /** Encodes [count] samples of [pcm] into [out], from its start. */
    fun encode(pcm: ShortArray, count: Int, out: ByteArray) {
        for (i in 0 until count) out[i] = encode(pcm[i].toInt()).toByte()
    }

    companion object {
        const val SAMPLE_RATE = 8000

        fun fromPayloadType(pt: Int): G711? = entries.firstOrNull { it.payloadType == pt }

        fun fromEncodingName(name: String): G711? =
            entries.firstOrNull { it.encodingName.equals(name, ignoreCase = true) }

        /** Signed 16-bit sample to A-law, as in the reference g711.c. */
        fun alaw(sample: Int): Int {
            var pcm = sample shr 3
            val mask: Int
            if (pcm >= 0) {
                mask = 0xD5
            } else {
                mask = 0x55
                pcm = -pcm - 1
            }
            val seg = ALAW_SEGMENT_ENDS.indexOfFirst { pcm <= it }
            if (seg < 0) return 0x7F xor mask
            val low = if (seg < 2) pcm shr 1 else pcm shr seg
            return ((seg shl 4) or (low and 0x0F)) xor mask
        }

        /** Signed 16-bit sample to mu-law. */
        fun ulaw(sample: Int): Int {
            val sign = if (sample < 0) 0x80 else 0
            val magnitude = minOf(kotlin.math.abs(sample), 32635) + 0x84
            val exponent = (32 - Integer.numberOfLeadingZeros(magnitude) - 8).coerceIn(0, 7)
            val mantissa = (magnitude shr (exponent + 3)) and 0x0F
            return (sign or (exponent shl 4) or mantissa).inv() and 0xFF
        }

        fun alawDecode(byte: Int): Int {
            val a = byte xor 0x55
            var t = (a and 0x0F) shl 4
            val seg = (a and 0x70) shr 4
            t = when (seg) {
                0 -> t + 8
                1 -> t + 0x108
                else -> (t + 0x108) shl (seg - 1)
            }
            return if (a and 0x80 != 0) t else -t
        }

        fun ulawDecode(byte: Int): Int {
            val u = byte.inv() and 0xFF
            val t = (((u and 0x0F) shl 3) + 0x84) shl ((u and 0x70) shr 4)
            return if (u and 0x80 != 0) 0x84 - t else t - 0x84
        }

        private val ALAW_SEGMENT_ENDS = intArrayOf(0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF)
    }
}
