package com.nicobrailo.batidoorlink.media

import com.nicobrailo.batidoorlink.rtsp.Capture
import com.nicobrailo.batidoorlink.rtsp.RtpPacket
import com.nicobrailo.batidoorlink.rtsp.RtpPacketizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AacTest {
    @Test
    fun readsTheCamerasConfig() {
        val c = AacConfig.parse("1408")!!
        assertEquals(2, c.objectType) // AAC LC
        assertEquals(16000, c.sampleRate)
        assertEquals(1, c.channels)
        assertNull(AacConfig.parse("14"))
        assertNull(AacConfig.parse("zz08"))
    }

    @Test
    fun unpacksTheCamerasFrames() {
        val frames = mutableListOf<ByteArray>()
        val d = AacDepacketizer(13, 3, 3) { f, _ -> frames += f }
        val audio = Capture.audio.map { it.rtp }
        audio.forEach { d.push(it) }
        assertTrue(frames.all { it.isNotEmpty() })
        // One 1024 sample frame a packet, though the camera's timestamps step by
        // 950 to 961 rather than 1024: they aren't sample counts, so nothing
        // here relies on them.
        assertEquals(audio.size, frames.size)
        assertEquals(0L, d.packetsLost)
    }

    @Test
    fun unpacksSeveralFramesInAPacket() {
        // Two AU headers of 16 bits each (13 size + 3 index): 32 bits of headers.
        val payload = byteArrayOf(0, 32, (3 shr 5).toByte(), ((3 shl 3) and 0xFF).toByte(),
            (2 shr 5).toByte(), ((2 shl 3) and 0xFF).toByte(), 1, 2, 3, 4, 5)
        val frames = mutableListOf<ByteArray>()
        AacDepacketizer(13, 3, 3) { f, _ -> frames += f }.push(RtpPacket.parse(RtpPacketizer(97).packet(payload))!!)
        assertEquals(listOf(listOf<Byte>(1, 2, 3), listOf<Byte>(4, 5)), frames.map { it.toList() })
    }
}
