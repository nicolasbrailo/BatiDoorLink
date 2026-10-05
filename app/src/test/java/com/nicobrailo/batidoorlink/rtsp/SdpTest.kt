package com.nicobrailo.batidoorlink.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SdpTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test
    fun parsesTheReolinkDoorbell() {
        val sections = Sdp.parse(resource("reolink-6460.sdp"))
        assertEquals(listOf("video", "audio", "audio"), sections.map { it.media })
        assertEquals(listOf("recvonly", "recvonly", "sendonly"), sections.map { it.direction })
        assertEquals(listOf("track1", "track2", "track3"), sections.map { it.control })
        assertEquals("H264", sections[0].encodings[96])
        assertEquals("MPEG4-GENERIC", sections[1].encodings[97])
    }

    @Test
    fun readsFormatsAndClockRates() {
        val video = Sdp.parse(resource("reolink-6460.sdp"))[0]
        assertEquals(96, video.payloadTypeFor("h264"))
        assertEquals(90000, video.clockRates[96])
        assertEquals("1", video.formats[96]?.get("packetization-mode"))
        // Base64 padding survives splitting on '='.
        assertEquals("Z2QAM6wVFKCgPZA=,aO48sA==", video.formats[96]?.get("sprop-parameter-sets"))
        val audio = Sdp.parse(resource("reolink-6460.sdp"))[1]
        assertEquals(16000, audio.clockRates[97])
        assertEquals("1408", audio.formats[97]?.get("config"))
        assertEquals("13", audio.formats[97]?.get("sizelength"))
    }

    @Test
    fun findsThePcmuBackchannelOfCurrentFirmware() {
        assertEquals(BackchannelTrack("track3", G711.PCMU),
            Sdp.backchannel(Sdp.parse(resource("reolink-6460.sdp"))))
    }

    @Test
    fun findsThePcmaBackchannelOfOldFirmware() {
        assertEquals(BackchannelTrack("track3", G711.PCMA),
            Sdp.backchannel(Sdp.parse(resource("reolink-2033.sdp"))))
    }

    @Test
    fun noBackchannelWithoutTheRequireHeader() {
        // What the camera describes without it: the same, minus the third section.
        val sdp = resource("reolink-6460.sdp").substringBefore("m=audio 0 RTP/AVP 0")
        assertNull(Sdp.backchannel(Sdp.parse(sdp)))
    }

    @Test
    fun staticPayloadTypeNeedsNoRtpmap() {
        val sdp = "v=0\r\nm=audio 0 RTP/AVP 8\r\na=sendonly\r\na=control:back\r\n"
        assertEquals(BackchannelTrack("back", G711.PCMA), Sdp.backchannel(Sdp.parse(sdp)))
    }

    @Test
    fun skipsBackchannelsInCodecsWeCantProduce() {
        val sdp = "v=0\r\nm=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\na=sendonly\r\na=control:t\r\n"
        assertNull(Sdp.backchannel(Sdp.parse(sdp)))
    }

    @Test
    fun resolvesControlUrls() {
        val base = "rtsp://10.10.30.11/Preview_01_sub/"
        assertEquals("rtsp://10.10.30.11/Preview_01_sub/track3", Sdp.resolveControl("track3", base))
        assertEquals("rtsp://10.10.30.11/Preview_01_sub/track3",
            Sdp.resolveControl("track3", "rtsp://10.10.30.11/Preview_01_sub"))
        assertEquals(base, Sdp.resolveControl("*", base))
        assertEquals("rtsp://other/x", Sdp.resolveControl("rtsp://other/x", base))
    }
}
