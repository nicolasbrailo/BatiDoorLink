package com.nicobrailo.batidoorlink.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpPacketTest {
    @Test
    fun parsesWhatThePacketizerBuilds() {
        val built = RtpPacketizer(payloadType = 96, ssrc = 0x0A0B0C0D, sequence = 7, timestamp = -1).packet(byteArrayOf(1, 2, 3))
        val p = RtpPacket.parse(built)!!
        assertTrue(p.marker)
        assertEquals(96, p.payloadType)
        assertEquals(7, p.sequence)
        assertEquals(0xFFFFFFFFL, p.timestamp)
        assertEquals(0x0A0B0C0D, p.ssrc)
        assertEquals(12, p.payloadOffset)
        assertEquals(3, p.payloadLength)
    }

    @Test
    fun skipsCsrcsExtensionAndPadding() {
        val data = byteArrayOf(
            0xB1.toByte(), 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, // V2, padding, extension, 1 CSRC
            9, 9, 9, 9, // the CSRC
            0, 0, 0, 1, 8, 8, 8, 8, // extension header, one word
            5, 6, // payload
            0, 0, 3, // padding, its last byte its length
        ).also { it[0] = (0x80 or 0x20 or 0x10 or 1).toByte() }
        val p = RtpPacket.parse(data)!!
        assertEquals(24, p.payloadOffset)
        assertEquals(2, p.payloadLength)
        assertEquals(5, p.payloadByte(0))
    }

    @Test
    fun rejectsWhatIsntRtp() {
        assertNull(RtpPacket.parse(ByteArray(11)))
        assertNull(RtpPacket.parse(ByteArray(12))) // version 0
    }

    @Test
    fun parsesTheCamerasPackets() {
        val video = Capture.video.map { it.rtp }
        assertTrue(video.all { it.payloadType == 96 })
        assertTrue(Capture.audio.all { it.rtp.payloadType == 97 })
        // Sequence numbers run on without a gap in a TCP capture.
        val t = SequenceTracker()
        video.forEach { t.next(it.sequence) }
        assertEquals(0, t.lost)
    }

    @Test
    fun countsLossesAcrossTheWrap() {
        val t = SequenceTracker()
        assertTrue(t.next(65534))
        assertTrue(t.next(65535))
        assertTrue(t.next(0))
        assertFalse(t.next(3)) // 1 and 2 lost
        assertEquals(2, t.lost)
        assertFalse(t.next(2)) // late: out of place, but no more lost
        assertTrue(t.next(4))
        assertEquals(2, t.lost)
    }
}
