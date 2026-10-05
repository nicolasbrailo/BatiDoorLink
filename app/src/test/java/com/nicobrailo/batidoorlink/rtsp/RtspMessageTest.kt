package com.nicobrailo.batidoorlink.rtsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException

class RtspMessageTest {
    @Test
    fun readsResponsesBetweenInterleavedFrames() {
        val body = "v=0\r\n"
        val bytes = byteArrayOf('$'.code.toByte(), 1, 0, 3, 9, 8, 7) +
            ("RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Base: rtsp://h/p/\r\nContent-Length: ${body.length}\r\n\r\n$body").toByteArray() +
            "RTSP/1.0 401 Unauthorized\r\nWWW-Authenticate: Digest realm=\"r\"\r\n\r\n".toByteArray()
        val input = ByteArrayInputStream(bytes)

        val frame = RtspMessage.read(input) as RtspMessage.Interleaved
        assertEquals(1, frame.channel)
        assertArrayEquals(byteArrayOf(9, 8, 7), frame.data)

        val ok = RtspMessage.read(input) as RtspMessage.Response
        assertEquals(200, ok.status)
        assertEquals("rtsp://h/p/", ok.header("Content-Base"))
        assertEquals(body, ok.body)

        val denied = RtspMessage.read(input) as RtspMessage.Response
        assertEquals(401, denied.status)
        assertEquals("Unauthorized", denied.reason)

        try {
            RtspMessage.read(input)
            throw AssertionError("expected the end of the stream")
        } catch (_: EOFException) {
        }
    }

    @Test
    fun readsServerRequests() {
        val m = RtspMessage.read(ByteArrayInputStream("GET_PARAMETER rtsp://x RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray()))
        assertTrue(m is RtspMessage.Request)
    }
}
