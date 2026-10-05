package com.nicobrailo.batidoorlink.rtsp

import kotlin.random.Random

/**
 * Builds the RTP packets of one outgoing audio stream (RFC 3550, no CSRCs or
 * extensions). G.711 is one byte per sample, so the timestamp advances by the
 * payload's length.
 */
class RtpPacketizer(
    private val payloadType: Int,
    private val ssrc: Int = Random.nextInt(),
    private var sequence: Int = Random.nextInt(0x10000),
    private var timestamp: Int = Random.nextInt(),
) {
    private var first = true

    fun packet(payload: ByteArray, length: Int = payload.size): ByteArray {
        val p = ByteArray(HEADER_SIZE + length)
        p[0] = 0x80.toByte() // version 2
        // The marker flags the start of a talkspurt (RFC 3551 4.1).
        p[1] = ((if (first) 0x80 else 0) or (payloadType and 0x7F)).toByte()
        putShort(p, 2, sequence)
        putInt(p, 4, timestamp)
        putInt(p, 8, ssrc)
        System.arraycopy(payload, 0, p, HEADER_SIZE, length)
        first = false
        sequence = (sequence + 1) and 0xFFFF
        timestamp += length
        return p
    }

    /** The next packet starts a new talkspurt, as after the talk button was let go. */
    fun markNextTalkspurt() {
        first = true
    }

    companion object {
        const val HEADER_SIZE = 12

        private fun putShort(b: ByteArray, at: Int, v: Int) {
            b[at] = (v shr 8).toByte()
            b[at + 1] = v.toByte()
        }

        private fun putInt(b: ByteArray, at: Int, v: Int) {
            putShort(b, at, v ushr 16)
            putShort(b, at + 2, v)
        }
    }
}
