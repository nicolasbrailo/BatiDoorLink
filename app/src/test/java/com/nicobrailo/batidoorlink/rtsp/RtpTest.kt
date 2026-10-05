package com.nicobrailo.batidoorlink.rtsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RtpTest {
    private fun u(b: Byte) = b.toInt() and 0xFF

    @Test
    fun buildsHeaders() {
        val p = RtpPacketizer(payloadType = 0, ssrc = 0x01020304, sequence = 0xFFFF, timestamp = 1000)
        val first = p.packet(ByteArray(160) { 7 })
        assertEquals(172, first.size)
        assertEquals(0x80, u(first[0]))
        assertEquals(0x80, u(first[1])) // marker, payload type 0
        assertEquals(0xFFFF, (u(first[2]) shl 8) or u(first[3]))
        assertEquals(1000, (u(first[6]) shl 8) or u(first[7]))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), first.copyOfRange(8, 12))
        assertEquals(7, first[12].toInt())

        val second = p.packet(ByteArray(160), 80)
        assertEquals(92, second.size)
        assertEquals(0x00, u(second[1])) // no marker after the first
        assertEquals(0, (u(second[2]) shl 8) or u(second[3])) // sequence wraps
        assertEquals(1160, (u(second[6]) shl 8) or u(second[7])) // timestamp moved by 160 samples

        p.markNextTalkspurt()
        assertEquals(0x80, u(p.packet(ByteArray(1))[1]))
    }

    @Test
    fun carriesThePayloadType() {
        assertEquals(8, u(RtpPacketizer(8).packet(ByteArray(1))[1]) and 0x7F)
    }
}
