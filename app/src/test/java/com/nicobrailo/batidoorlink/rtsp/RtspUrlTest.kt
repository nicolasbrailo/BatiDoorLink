package com.nicobrailo.batidoorlink.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class RtspUrlTest {
    @Test
    fun splitsCredentialsOff() {
        val u = RtspUrl.parse("rtsp://admin:@10.10.30.11/h264Preview_01_sub")
        assertEquals("10.10.30.11", u.host)
        assertEquals(554, u.port)
        assertEquals("admin", u.user)
        assertEquals("", u.password)
        assertEquals("rtsp://10.10.30.11/h264Preview_01_sub", u.withoutCredentials)
    }

    @Test
    fun decodesCredentialsAndKeepsThePortAndQuery() {
        val u = RtspUrl.parse("rtsp://me:p%40ss+1@cam:8554/live?channel=1")
        assertEquals("p@ss+1", u.password)
        assertEquals(8554, u.port)
        assertEquals("rtsp://cam:8554/live?channel=1", u.withoutCredentials)
    }

    @Test
    fun noCredentials() {
        val u = RtspUrl.parse("rtsp://cam/live")
        assertNull(u.user)
        assertNull(u.password)
    }

    @Test(expected = IOException::class)
    fun rejectsOtherSchemes() {
        RtspUrl.parse("http://cam/live")
    }
}
