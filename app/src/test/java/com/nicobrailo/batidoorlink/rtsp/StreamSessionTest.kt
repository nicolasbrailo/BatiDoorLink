package com.nicobrailo.batidoorlink.rtsp

import com.nicobrailo.batidoorlink.media.AccessUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs [StreamSession] against a fake camera that answers like the Reolink
 * and, on PLAY, replays the RTP captured from it, over whichever transport
 * was set up.
 */
class StreamSessionTest {
    private val server = ServerSocket(0)
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private val sdp = javaClass.classLoader!!.getResource("reolink-6460.sdp")!!.readText()
        .substringBefore("m=audio 0 RTP/AVP 0").replace("\n", "\r\n")
    /** When true the camera accepts UDP but sends nothing over it, like a firewall in between. */
    private var udpBlocked = false

    private fun serve() = Thread {
        while (true) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                return@Thread
            }
            Thread { handle(socket.getOutputStream(), BufferedInputStream(socket.getInputStream())) }
                .apply { isDaemon = true }.start()
        }
    }.apply { isDaemon = true; start() }

    private fun handle(out: OutputStream, input: BufferedInputStream) {
        val udpPorts = mutableListOf<Int>()
        try {
            while (true) {
                val m = RtspMessage.read(input) as? RtspMessage.Request ?: continue
                val (method, url) = m.requestLine.split(' ')
                val transport = m.headers["transport"] ?: ""
                requests += "$method $url" + if (transport.isNotEmpty()) " $transport" else ""
                val cseq = m.headers["cseq"]
                fun send(extra: String = "", body: String = "") {
                    synchronized(out) {
                        out.write(("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n$extra" +
                            (if (body.isNotEmpty()) "Content-Length: ${body.length}\r\n" else "") + "\r\n$body").toByteArray())
                        out.flush()
                    }
                }
                when (method) {
                    "DESCRIBE" -> send("Content-Base: rtsp://127.0.0.1/Preview_01_sub/\r\nContent-Type: application/sdp\r\n", sdp)
                    "SETUP" -> {
                        Regex("client_port=(\\d+)").find(transport)?.let { udpPorts += it.groupValues[1].toInt() }
                        val reply = if (udpPorts.isNotEmpty()) "$transport;server_port=6970-6971" else transport
                        send("Transport: $reply\r\nSession: ABC;timeout=30\r\n")
                    }
                    "PLAY" -> {
                        send("Session: ABC\r\n")
                        if (udpPorts.isEmpty()) replayTcp(out) else if (!udpBlocked) replayUdp(udpPorts)
                    }
                    else -> send()
                }
            }
        } catch (_: IOException) {
        }
    }

    private fun replayTcp(out: OutputStream) {
        for (p in Capture.packets) {
            synchronized(out) {
                out.write(byteArrayOf('$'.code.toByte(), p.channel.toByte(), (p.data.size shr 8).toByte(), p.data.size.toByte()))
                out.write(p.data)
            }
        }
        out.flush()
    }

    private fun replayUdp(ports: List<Int>) {
        DatagramSocket().use { s ->
            val to = InetAddress.getLoopbackAddress()
            for (p in Capture.packets) {
                // Channel 0 is the first track set up (video), channel 2 the second.
                s.send(DatagramPacket(p.data, p.data.size, to, ports[p.channel / 2]))
                // Loopback drops what a burst overflows; the camera paces itself too.
                Thread.sleep(1)
            }
        }
    }

    private class Collector : StreamSession.Listener {
        val units = Collections.synchronizedList(mutableListOf<AccessUnit>())
        val audioFrames = Collections.synchronizedList(mutableListOf<ByteArray>())
        val done = CountDownLatch(1)

        override fun onVideo(unit: AccessUnit) {
            units += unit
            if (units.size == EXPECTED_PICTURES) done.countDown()
        }

        override fun onAudio(frame: ByteArray, rtpTimestamp: Long) {
            audioFrames += frame
        }
    }

    @After
    fun tearDown() = server.close()

    private fun open(): StreamSession {
        serve()
        return StreamSession.describe("rtsp://admin:@127.0.0.1:${server.localPort}/h264Preview_01_sub")
    }

    @Test
    fun describesTheStream() {
        open().use { s ->
            assertTrue(s.hasVideo)
            assertEquals(640, s.videoSize?.width)
            assertEquals(480, s.videoSize?.height)
            assertEquals(2, s.parameterSets.size)
            assertEquals(16000, s.audioConfig?.sampleRate)
        }
    }

    @Test
    fun playsOverTcp() {
        open().use { s ->
            val c = Collector()
            s.play(c, preferTcp = true)
            assertTrue(c.done.await(5, TimeUnit.SECONDS))
            assertEquals("TCP", s.transport)
            assertTrue(c.units[0].keyframe)
            assertTrue(c.audioFrames.isNotEmpty())
            val base = "rtsp://127.0.0.1/Preview_01_sub/"
            assertEquals(listOf(
                "DESCRIBE rtsp://127.0.0.1:${server.localPort}/h264Preview_01_sub",
                "SETUP ${base}track1 RTP/AVP/TCP;unicast;interleaved=0-1",
                "SETUP ${base}track2 RTP/AVP/TCP;unicast;interleaved=2-3",
                "PLAY $base",
            ), requests.toList())
        }
    }

    @Test
    fun playsOverUdp() {
        open().use { s ->
            val c = Collector()
            s.play(c, preferTcp = false)
            assertTrue(c.done.await(5, TimeUnit.SECONDS))
            assertEquals("UDP", s.transport)
            assertTrue(c.units[0].keyframe)
            assertTrue(c.audioFrames.isNotEmpty())
            assertTrue(requests.any { it.contains("RTP/AVP;unicast;client_port=") })
        }
    }

    @Test
    fun fallsBackToTcpWhenUdpNeverArrives() {
        udpBlocked = true
        open().use { s ->
            val c = Collector()
            s.play(c, preferTcp = false, udpWaitMs = 500)
            assertTrue(c.done.await(5, TimeUnit.SECONDS))
            assertEquals("TCP", s.transport)
            assertTrue(requests.any { it.startsWith("TEARDOWN") })
            assertTrue(requests.any { it.contains("interleaved=0-1") })
            assertEquals(0L, s.videoPacketsLost)
        }
    }

    companion object {
        /** One picture per timestamp in the capture. */
        private val EXPECTED_PICTURES = Capture.video.map { it.rtp.timestamp }.distinct().size
    }
}
