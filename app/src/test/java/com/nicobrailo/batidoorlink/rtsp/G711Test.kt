package com.nicobrailo.batidoorlink.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class G711Test {
    // From the reference g711.c algorithm, and checked against ffmpeg's
    // encoders, which agree on all of them but mu-law at -1 (0x7E there):
    // ffmpeg rounds where g711.c truncates.
    private val samples = intArrayOf(0, 1, -1, 5, -5, 100, -100, 1000, -1000, 4000, -4000, 12345, -12345, 32767, -32768)
    private val alaw = intArrayOf(0xD5, 0xD5, 0x55, 0xD5, 0x55, 0xD3, 0x53, 0xFA, 0x7A, 0x9A, 0x1A, 0xBD, 0x3D, 0xAA, 0x2A)
    private val ulaw = intArrayOf(0xFF, 0xFF, 0x7F, 0xFE, 0x7E, 0xF2, 0x72, 0xCE, 0x4E, 0xAF, 0x2F, 0x97, 0x17, 0x80, 0x00)

    @Test
    fun encodesKnownValues() {
        for (i in samples.indices) {
            assertEquals("A-law of ${samples[i]}", alaw[i], G711.alaw(samples[i]))
            assertEquals("mu-law of ${samples[i]}", ulaw[i], G711.ulaw(samples[i]))
        }
    }

    @Test
    fun roundTripStaysWithinTheStepSize() {
        // The steps grow with the level: 1/16 of a segment, which tops out
        // around 1024 for A-law and 1056 for mu-law at full scale.
        for (codec in G711.entries) {
            for (s in -32768..32767 step 3) {
                val back = codec.decode(codec.encode(s))
                val allowed = maxOf(32, abs(s) / 16 + 16)
                assertTrue("$codec $s came back as $back", abs(back - s) <= allowed)
            }
        }
    }

    @Test
    fun encodesABuffer() {
        val pcm = shortArrayOf(0, 1000, -1000)
        val out = ByteArray(3)
        G711.PCMU.encode(pcm, 3, out)
        assertEquals(listOf(0xFF, 0xCE, 0x4E), out.map { it.toInt() and 0xFF })
    }

    @Test
    fun findsCodecsByNameAndPayloadType() {
        assertEquals(G711.PCMU, G711.fromPayloadType(0))
        assertEquals(G711.PCMA, G711.fromPayloadType(8))
        assertEquals(null, G711.fromPayloadType(97))
        assertEquals(G711.PCMA, G711.fromEncodingName("pcma"))
    }
}
