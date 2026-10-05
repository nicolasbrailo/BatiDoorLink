package com.nicobrailo.batidoorlink.rtsp

/**
 * The RTP the Reolink doorbell sent over 6s of its sub stream (firmware
 * v3.0.0.6460), as it came off the TCP connection: video on channel 0, audio
 * on channel 2. Stored as channel (1 byte), length (2) and the RTP packet.
 */
object Capture {
    class Packet(val channel: Int, val data: ByteArray) {
        val rtp get() = RtpPacket.parse(data)!!
    }

    val packets: List<Packet> by lazy {
        val d = javaClass.classLoader!!.getResource("reolink-sub-6s.rtp")!!.readBytes()
        val out = mutableListOf<Packet>()
        var i = 0
        while (i < d.size) {
            val n = ((d[i + 1].toInt() and 0xFF) shl 8) or (d[i + 2].toInt() and 0xFF)
            out += Packet(d[i].toInt(), d.copyOfRange(i + 3, i + 3 + n))
            i += 3 + n
        }
        out
    }

    val video get() = packets.filter { it.channel == 0 }
    val audio get() = packets.filter { it.channel == 2 }
}
