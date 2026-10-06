package com.nicobrailo.batidoorlink.rtsp

import android.util.Log
import com.nicobrailo.batidoorlink.media.AacConfig
import com.nicobrailo.batidoorlink.media.AacDepacketizer
import com.nicobrailo.batidoorlink.media.AccessUnit
import com.nicobrailo.batidoorlink.media.H264Depacketizer
import com.nicobrailo.batidoorlink.media.SpsSize
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Receives a camera's H.264 video and AAC audio over RTSP, and hands on each
 * picture and audio frame the moment it is complete. Nothing here buffers or
 * paces: that is the point of it, since a general player (Media3, measured)
 * kept a doorbell about 2s behind.
 *
 * It is opened in two steps so nothing is lost at the start: [describe] says
 * what the stream holds, which the decoders need before they can start, and
 * [play] sets the tracks up and starts them. The Reolink sends nothing
 * after PLAY until its next keyframe (measured over TCP: 0.6 to 3s on the
 * sub stream, which has one every 4s, 0.2 to 1.3s on the main, every 2s),
 * so the first packet is a keyframe, and a decoder that misses it waits a
 * whole interval for the next.
 */
class StreamSession private constructor(
    private val url: RtspUrl,
    private val user: String,
    private val password: String,
    private var connection: RtspConnection,
    private val base: String,
    private val videoSection: MediaSection?,
    private val audioSection: MediaSection?,
) : Closeable {
    interface Listener {
        /** On a receiving thread. */
        fun onVideo(unit: AccessUnit)

        /** One raw AAC frame, on a receiving thread. */
        fun onAudio(frame: ByteArray, rtpTimestamp: Long)
    }

    private val videoPayloadType = videoSection?.payloadTypeFor("H264")
    private val audioPayloadType = audioSection?.payloadTypeFor("MPEG4-GENERIC")

    /** The SPS and PPS, with start codes, from the SDP; the stream repeats them before keyframes. */
    val parameterSets: List<ByteArray> =
        H264Depacketizer.parameterSets(videoPayloadType?.let { videoSection?.formats?.get(it)?.get("sprop-parameter-sets") })
    val videoSize: SpsSize? = parameterSets.firstOrNull()?.let { SpsSize.parse(it) }
    val hasVideo get() = videoPayloadType != null
    val audioConfig: AacConfig? = audioPayloadType?.let { AacConfig.parse(audioSection?.formats?.get(it)?.get("config")) }

    /** "UDP" or "TCP" once playing. */
    var transport = ""
        private set
    val videoPackets = AtomicLong()
    val audioPackets = AtomicLong()
    private var video: H264Depacketizer? = null
    private var audio: AacDepacketizer? = null
    val videoPacketsLost get() = video?.packetsLost ?: 0
    val videoUnitsDropped get() = video?.accessUnitsDropped ?: 0
    val audioPacketsLost get() = audio?.packetsLost ?: 0
    val failure: Exception? get() = connection.keepAliveFailure

    private val sockets = mutableListOf<DatagramSocket>()
    private var rtcp: java.util.concurrent.ScheduledExecutorService? = null
    @Volatile private var closed = false

    /**
     * Sets the tracks up and starts them. Over UDP, if nothing arrives within
     * [udpWaitMs] (a firewall between the camera's network and ours, say), it
     * starts again over TCP, where the media travels on the RTSP connection
     * itself and so gets wherever the requests did.
     *
     * Over UDP the Reolink's first packet came 0.3 to 3.2s after PLAY on the
     * sub stream (5 tries), where over TCP it starts at once; within its 4s
     * keyframe interval, so probably it waits for the next keyframe, though
     * that wasn't checked. A 3s wait fell back to TCP on a network where UDP
     * worked, hence 5s.
     */
    fun play(listener: Listener, preferTcp: Boolean, udpWaitMs: Long = 5000) {
        video = videoPayloadType?.let { pt ->
            H264Depacketizer { listener.onVideo(it) }.also { Log.i(TAG, "video: H264 pt $pt, $videoSize") }
        }
        audio = if (audioConfig != null && audioPayloadType != null) {
            AacDepacketizer.fromFormat(audioSection!!.formats[audioPayloadType] ?: emptyMap()) { frame, ts ->
                listener.onAudio(frame, ts)
            }
        } else null
        if (!preferTcp) {
            val first = CountDownLatch(1)
            setUpUdp(first)
            if (first.await(udpWaitMs, TimeUnit.MILLISECONDS)) {
                transport = "UDP"
                return
            }
            Log.w(TAG, "nothing over UDP in ${udpWaitMs}ms, trying TCP")
            // A new session on a new connection: the old one's tracks are set up for UDP.
            stopUdp()
            try {
                connection.request("TEARDOWN", base, timeoutMs = 2000)
            } catch (_: IOException) {
            }
            connection.close()
            connection = RtspConnection(url.host, url.port, user, password)
            connection.request("DESCRIBE", url.withoutCredentials, mapOf("Accept" to "application/sdp"))
            // The depacketizers start over too, or the jump in sequence numbers counts as a loss.
            video = videoPayloadType?.let { H264Depacketizer { listener.onVideo(it) } }
            audio = audio?.let {
                AacDepacketizer.fromFormat(audioSection!!.formats[audioPayloadType] ?: emptyMap()) { frame, ts ->
                    listener.onAudio(frame, ts)
                }
            }
        }
        setUpTcp()
        transport = "TCP"
    }

    private fun tracks(): List<Pair<MediaSection, (RtpPacket) -> Unit>> = listOfNotNull(
        videoSection?.takeIf { video != null }?.let { it to { p: RtpPacket ->
            videoPackets.incrementAndGet()
            if (p.payloadType == videoPayloadType) video?.push(p)
        } },
        audioSection?.takeIf { audio != null }?.let { it to { p: RtpPacket ->
            audioPackets.incrementAndGet()
            if (p.payloadType == audioPayloadType) audio?.push(p)
        } },
    )

    private fun setUpTcp() {
        val handlers = mutableMapOf<Int, (RtpPacket) -> Unit>()
        var timeout = 60
        tracks().forEachIndexed { i, (section, handle) ->
            val channel = i * 2
            handlers[channel] = handle
            val r = connection.request("SETUP", Sdp.resolveControl(section.control ?: "", base),
                mapOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=$channel-${channel + 1}"))
            if (connection.session == null) {
                val header = r.header("session") ?: throw IOException("SETUP returned no session")
                connection.session = header.substringBefore(';').trim()
                timeout = RtspConnection.sessionTimeout(header)
            }
        }
        // Odd channels are the server's RTCP, which we have no use for.
        connection.onInterleaved = { m -> handlers[m.channel]?.let { h -> RtpPacket.parse(m.data)?.let(h) } }
        connection.request("PLAY", base, mapOf("Range" to "npt=0.000-"))
        connection.startKeepAlive(base, timeout)
    }

    private fun setUpUdp(first: CountDownLatch) {
        val camera = InetAddress.getByName(url.host)
        var timeout = 60
        val receivers = mutableListOf<Pair<DatagramSocket, (RtpPacket) -> Unit>>()
        val rtcpTargets = mutableListOf<Pair<DatagramSocket, Int>>()
        for ((section, handle) in tracks()) {
            val (rtpSocket, rtcpSocket) = bindPortPair()
            sockets += rtpSocket
            sockets += rtcpSocket
            val r = connection.request("SETUP", Sdp.resolveControl(section.control ?: "", base),
                mapOf("Transport" to "RTP/AVP;unicast;client_port=${rtpSocket.localPort}-${rtcpSocket.localPort}"))
            if (connection.session == null) {
                val header = r.header("session") ?: throw IOException("SETUP returned no session")
                connection.session = header.substringBefore(';').trim()
                timeout = RtspConnection.sessionTimeout(header)
            }
            Log.i(TAG, "UDP ${rtpSocket.localPort}-${rtcpSocket.localPort} set up as: ${r.header("transport")}")
            val serverPorts = Regex("server_port=(\\d+)-(\\d+)").find(r.header("transport") ?: "")
            if (serverPorts != null) {
                // Packets out from our ports open the way back through a stateful
                // firewall between the networks, as ffmpeg does: an empty RTP
                // header to the camera's RTP port, an empty receiver report to its RTCP.
                val rtpPort = serverPorts.groupValues[1].toInt()
                val rtcpPort = serverPorts.groupValues[2].toInt()
                send(rtpSocket, camera, rtpPort, ByteArray(12).also { it[0] = 0x80.toByte() })
                send(rtcpSocket, camera, rtcpPort, receiverReport)
                rtcpTargets += rtcpSocket to rtcpPort
            }
            receivers += rtpSocket to handle
        }
        for ((socket, handle) in receivers) {
            Thread({ receive(socket, handle, first) }, "rtp-${socket.localPort}").apply { isDaemon = true }.start()
        }
        connection.request("PLAY", base, mapOf("Range" to "npt=0.000-"))
        playedAt = System.nanoTime()
        connection.startKeepAlive(base, timeout)
        // Some cameras stop sending over UDP to a receiver that never reports.
        rtcp = Executors.newSingleThreadScheduledExecutor { Thread(it, "rtcp").apply { isDaemon = true } }.apply {
            scheduleWithFixedDelay({
                rtcpTargets.forEach { (socket, port) -> send(socket, camera, port, receiverReport) }
            }, 5, 5, TimeUnit.SECONDS)
        }
    }

    private fun receive(socket: DatagramSocket, handle: (RtpPacket) -> Unit, first: CountDownLatch) {
        // The handlers copy what they keep, so one buffer does for every packet.
        val buffer = ByteArray(65536)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            while (!closed) {
                packet.length = buffer.size
                socket.receive(packet)
                val rtp = RtpPacket.parse(buffer, packet.length) ?: continue
                if (first.count > 0) Log.i(TAG, "first UDP packet on ${socket.localPort} ${(System.nanoTime() - playedAt) / 1_000_000}ms after PLAY")
                first.countDown()
                handle(rtp)
            }
        } catch (e: SocketException) {
            if (!closed) Log.w(TAG, "UDP receive stopped: $e")
        } catch (e: IOException) {
            Log.w(TAG, "UDP receive failed: $e")
        }
    }

    @Volatile private var playedAt = 0L
    private val ssrc = Random.nextInt()

    /** An RTCP receiver report with no report blocks: version 2, type 201, length 1, our SSRC. */
    private val receiverReport = byteArrayOf(0x80.toByte(), 201.toByte(), 0, 1,
        (ssrc shr 24).toByte(), (ssrc shr 16).toByte(), (ssrc shr 8).toByte(), ssrc.toByte())

    private fun send(socket: DatagramSocket, to: InetAddress, port: Int, data: ByteArray) {
        try {
            socket.send(DatagramPacket(data, data.size, to, port))
        } catch (e: IOException) {
            Log.d(TAG, "send to $port: $e")
        }
    }

    private fun stopUdp() {
        rtcp?.shutdownNow()
        rtcp = null
        sockets.forEach { it.close() }
        sockets.clear()
    }

    override fun close() {
        closed = true
        stopUdp()
        try {
            connection.request("TEARDOWN", base, timeoutMs = 2000)
        } catch (e: IOException) {
            Log.d(TAG, "teardown: $e")
        }
        connection.close()
    }

    companion object {
        private const val TAG = "StreamSession"
        /** A big keyframe on the main stream is a burst of hundreds of packets. */
        private const val RECEIVE_BUFFER = 2 * 1024 * 1024

        /** Connects and describes the stream at [url] (credentials in it or given apart). */
        fun describe(url: String, user: String = "", password: String = ""): StreamSession {
            val parsed = RtspUrl.parse(url)
            val u = parsed.user ?: user
            val p = parsed.password ?: password
            val connection = RtspConnection(parsed.host, parsed.port, u, p)
            try {
                val described = connection.request("DESCRIBE", parsed.withoutCredentials,
                    mapOf("Accept" to "application/sdp"))
                val base = described.header("content-base") ?: described.header("content-location")
                    ?: parsed.withoutCredentials
                val sections = Sdp.parse(described.body)
                val video = sections.firstOrNull { it.media == "video" && it.payloadTypeFor("H264") != null }
                val audio = sections.firstOrNull {
                    it.media == "audio" && it.direction != "sendonly" && it.payloadTypeFor("MPEG4-GENERIC") != null
                }
                if (video == null && audio == null) throw IOException("no H.264 video or AAC audio in the stream")
                return StreamSession(parsed, u, p, connection, base, video, audio)
            } catch (e: Exception) {
                connection.close()
                throw e
            }
        }

        /** An even port for RTP and the next one up for RTCP, as RFC 3550 wants. */
        private fun bindPortPair(): Pair<DatagramSocket, DatagramSocket> {
            repeat(20) {
                val rtp = DatagramSocket(0)
                if (rtp.localPort % 2 == 0) {
                    try {
                        val rtcp = DatagramSocket(rtp.localPort + 1)
                        rtp.receiveBufferSize = RECEIVE_BUFFER
                        return rtp to rtcp
                    } catch (_: SocketException) {
                    }
                }
                rtp.close()
            }
            throw IOException("couldn't bind a pair of UDP ports")
        }
    }
}
