package com.nicobrailo.batidoorlink.media

import com.nicobrailo.batidoorlink.rtsp.Capture
import com.nicobrailo.batidoorlink.rtsp.RtpPacket
import com.nicobrailo.batidoorlink.rtsp.RtpPacketizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class H264Test {
    private fun nalTypes(unit: AccessUnit): List<Int> {
        val types = mutableListOf<Int>()
        val d = unit.data
        var i = 0
        while (i + 4 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1) {
                types += d[i + 4].toInt() and 0x1F
                i += 4
            } else i++
        }
        return types
    }

    @Test
    fun reassemblesTheCamerasPictures() {
        val units = mutableListOf<AccessUnit>()
        val d = H264Depacketizer { units += it }
        val video = Capture.video.map { it.rtp }
        video.forEach { d.push(it) }
        // One picture per timestamp, the first a keyframe with the parameter sets.
        assertEquals(video.map { it.timestamp }.distinct().size, units.size)
        assertEquals(listOf(7, 8, 5), nalTypes(units[0]))
        assertTrue(units[0].keyframe)
        assertTrue(units.drop(1).all { !it.keyframe && nalTypes(it) == listOf(1) })
        assertEquals(0L, d.packetsLost)
        assertEquals(0L, d.accessUnitsDropped)
    }

    @Test
    fun dropsEverythingFromALossToTheNextKeyframe() {
        val units = mutableListOf<AccessUnit>()
        val d = H264Depacketizer { units += it }
        val video = Capture.video.map { it.rtp }
        // Lose a packet in the middle of the keyframe, which is in FU-A fragments.
        video.filterIndexed { i, _ -> i != 10 }.forEach { d.push(it) }
        // There is no second keyframe in the 6s, so nothing gets through.
        assertEquals(0, units.size)
        assertEquals(1L, d.packetsLost)
        assertEquals(video.map { it.timestamp }.distinct().size.toLong(), d.accessUnitsDropped)
    }

    @Test
    fun waitsForAKeyframeAtTheStart() {
        val units = mutableListOf<AccessUnit>()
        val d = H264Depacketizer { units += it }
        // Joining after the keyframe: P frames alone can't be decoded.
        Capture.video.map { it.rtp }.dropWhile { it.timestamp == Capture.video[0].rtp.timestamp }.forEach { d.push(it) }
        assertEquals(0, units.size)
    }

    @Test
    fun aMarkerOnTheSpsDoesntEndThePicture() {
        // As the Reolink sends it: the SPS alone in a packet with the marker set.
        val units = mutableListOf<AccessUnit>()
        val d = H264Depacketizer { units += it }
        val p = RtpPacketizer(96, timestamp = 100)
        d.push(RtpPacket.parse(p.packet(byteArrayOf(0x67, 1)))!!) // marker: first packet
        d.push(RtpPacket.parse(p.packet(byteArrayOf(0x68, 2)).also { it[1] = 96.toByte() })!!)
        assertEquals(0, units.size)
        d.push(RtpPacket.parse(p.packet(byteArrayOf(0x65, 3)).also { it[1] = (0x80 or 96).toByte() })!!)
        assertEquals(1, units.size)
        assertTrue(units[0].keyframe)
    }

    @Test
    fun unpacksStapA() {
        val sps = byteArrayOf(0x67, 1, 2)
        val pps = byteArrayOf(0x68, 3)
        val idr = byteArrayOf(0x65, 4, 5, 6)
        val payload = byteArrayOf(0x78) + // STAP-A
            byteArrayOf(0, 3) + sps + byteArrayOf(0, 2) + pps + byteArrayOf(0, 4) + idr
        val units = mutableListOf<AccessUnit>()
        H264Depacketizer { units += it }.push(RtpPacket.parse(RtpPacketizer(96).packet(payload))!!)
        assertEquals(1, units.size)
        val sc = H264Depacketizer.START_CODE
        assertArrayEquals(sc + sps + sc + pps + sc + idr, units[0].data)
        assertTrue(units[0].keyframe)
    }

    @Test
    fun readsTheParameterSetsFromTheSdp() {
        val sets = H264Depacketizer.parameterSets("Z2QAM6wVFKCgPZA=,aO48sA==")
        assertEquals(2, sets.size)
        assertEquals(0x67, sets[0][4].toInt())
        assertEquals(0x68, sets[1][4].toInt())
    }

    @Test
    fun readsThePictureSizeOfBothStreams() {
        val sub = H264Depacketizer.parameterSets("Z2QAM6wVFKCgPZA=,aO48sA==")[0]
        val main = H264Depacketizer.parameterSets("Z2QAM6wVFKAoAPGQ,aO48sA==")[0]
        assertEquals(SpsSize(640, 480), SpsSize.parse(sub))
        assertEquals(SpsSize(2560, 1920), SpsSize.parse(main))
        assertEquals(SpsSize(640, 480), SpsSize.parse(sub.copyOfRange(4, sub.size))) // without the start code
    }
}
