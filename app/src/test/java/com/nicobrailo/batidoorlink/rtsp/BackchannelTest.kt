package com.nicobrailo.batidoorlink.rtsp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs [Backchannel] against a fake camera that behaves like the Reolink
 * doorbell did on the wire: a digest challenge first, the backchannel listed
 * only when asked for, and Content-Base without the port.
 */
class BackchannelTest {
    private val server = ServerSocket(0)
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private val rtp = Collections.synchronizedList(mutableListOf<ByteArray>())
    private val tornDown = CountDownLatch(1)
    private val sdp = javaClass.classLoader!!.getResource("reolink-6460.sdp")!!.readText().replace("\n", "\r\n")
    private val nonce = "cc28ca4fb50f9a5f5285bcd54ea34d87"

    private fun serve() = Thread {
        val socket = server.accept()
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        try {
            while (true) {
                when (val m = RtspMessage.read(input)) {
                    is RtspMessage.Interleaved -> rtp += m.data
                    is RtspMessage.Request -> answer(m, out)
                    is RtspMessage.Response -> Unit
                }
            }
        } catch (_: IOException) {
        }
    }.apply { isDaemon = true; start() }

    private fun answer(m: RtspMessage.Request, out: OutputStream) {
        val (method, url) = m.requestLine.split(' ')
        requests += "$method $url"
        val cseq = m.headers["cseq"]
        val auth = m.headers["authorization"]
        fun send(status: String, extra: String = "", body: String = "") {
            out.write(("RTSP/1.0 $status\r\nCSeq: $cseq\r\n$extra" +
                (if (body.isNotEmpty()) "Content-Length: ${body.length}\r\n" else "") + "\r\n$body").toByteArray())
            out.flush()
        }
        val expected = Auth.authorization(AuthChallenge("Digest", mapOf("realm" to "BC Streaming Media", "nonce" to nonce)),
            "admin", "", method, url)
        if (auth == null || auth.substringAfter("response=") != expected.substringAfter("response=")) {
            send("401 Unauthorized", "WWW-Authenticate: Digest realm=\"BC Streaming Media\", nonce=\"$nonce\"\r\n")
            return
        }
        val backchannel = m.headers["require"] == "www.onvif.org/ver20/backchannel"
        when (method) {
            "DESCRIBE" -> send("200 OK",
                "Content-Base: rtsp://127.0.0.1/Preview_01_sub/\r\nContent-Type: application/sdp\r\n",
                if (backchannel) sdp else sdp.substringBefore("m=audio 0 RTP/AVP 0"))
            "SETUP" -> send("200 OK", "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\nSession: DC7CB932;timeout=65\r\n")
            "PLAY", "GET_PARAMETER" -> send("200 OK", "Session: DC7CB932\r\n")
            "TEARDOWN" -> {
                send("200 OK")
                tornDown.countDown()
            }
            else -> send("405 Method Not Allowed")
        }
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun opensSendsAndCloses() {
        serve()
        val bc = Backchannel.open("rtsp://admin:@127.0.0.1:${server.localPort}/h264Preview_01_sub")
        assertEquals(G711.PCMU, bc.codec)
        assertEquals(65, bc.sessionTimeoutSecs)

        bc.send(ByteArray(160) { 0x7F }, 160)
        bc.send(ByteArray(160) { 0x7F }, 160)
        bc.close()
        assertTrue(tornDown.await(2, TimeUnit.SECONDS))

        val base = "rtsp://127.0.0.1/Preview_01_sub/"
        assertEquals(listOf(
            "DESCRIBE rtsp://127.0.0.1:${server.localPort}/h264Preview_01_sub", // 401
            "DESCRIBE rtsp://127.0.0.1:${server.localPort}/h264Preview_01_sub",
            "SETUP ${base}track3",
            "PLAY $base",
            "TEARDOWN $base",
        ), requests.toList())
        assertEquals(2, rtp.size)
        assertEquals(172, rtp[0].size)
        assertEquals(0x80, rtp[0][1].toInt() and 0xFF) // marker + PCMU
        assertEquals(0x00, rtp[1][1].toInt() and 0xFF)
        assertEquals(2L, bc.packetsSent.get())
    }

    @Test(expected = IOException::class)
    fun failsCleanlyWhenTheCredentialsAreWrong() {
        serve()
        Backchannel.open("rtsp://admin:wrong@127.0.0.1:${server.localPort}/h264Preview_01_sub")
    }
}
