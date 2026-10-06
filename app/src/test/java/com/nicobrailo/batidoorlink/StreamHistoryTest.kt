package com.nicobrailo.batidoorlink

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamHistoryTest {
    private val sub = "rtsp://admin:@10.10.30.11/h264Preview_01_sub"
    private val main = "rtsp://admin:@10.10.30.11/h264Preview_01_main"

    @Test
    fun keepsTheMostRecentFirstWithoutDuplicates() {
        var h = StreamHistory.add(emptyList(), sub)
        h = StreamHistory.add(h, main)
        h = StreamHistory.add(h, sub)
        assertEquals(listOf(sub, main), h)
    }

    @Test
    fun dropsTheOldestPastTheLimit() {
        val h = (1..12).fold(emptyList<String>()) { acc, i -> StreamHistory.add(acc, "rtsp://cam$i/live") }
        assertEquals(StreamHistory.MAX, h.size)
        assertEquals("rtsp://cam12/live", h.first())
        assertEquals("rtsp://cam3/live", h.last())
    }

    @Test
    fun roundTripsThroughStorage() {
        val h = listOf(sub, main)
        assertEquals(h, StreamHistory.decode(StreamHistory.encode(h)))
        assertEquals(emptyList<String>(), StreamHistory.decode(null))
        assertEquals(emptyList<String>(), StreamHistory.decode(""))
    }

    @Test
    fun labelsWithoutCredentials() {
        assertEquals("10.10.30.11/h264Preview_01_sub", StreamHistory.label(sub))
        assertEquals("cam:8554/live?channel=1", StreamHistory.label("rtsp://me:secret@cam:8554/live?channel=1"))
        assertEquals("not a url", StreamHistory.label("not a url"))
    }
}
