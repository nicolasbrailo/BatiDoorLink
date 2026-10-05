package com.nicobrailo.batidoorlink.rtsp

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The ONVIF audio backchannel (ONVIF Streaming Specification 5.3): a session
 * of its own, separate from the one Media3 plays, holding only the track we
 * send on. DESCRIBE with the `Require` header makes the server list that
 * track, marked sendonly, and after SETUP and PLAY we send RTP over the RTSP
 * connection. Measured on a Reolink doorbell (firmware v3.0.0.6460): a
 * talk-only session plays fine, without the video tracks.
 *
 * Every method blocks on the network, so none may run on the main thread.
 */
class Backchannel private constructor(
    private val connection: RtspConnection,
    private val aggregateUrl: String,
    val codec: G711,
    val sessionTimeoutSecs: Int,
) : Closeable {
    private val packetizer = RtpPacketizer(codec.payloadType)
    private val keepAlive = Executors.newSingleThreadScheduledExecutor { Thread(it, "rtsp-keepalive").apply { isDaemon = true } }
    private var keepAliveTask: ScheduledFuture<*>? = null
    @Volatile var failure: Exception? = null
        private set
    val packetsSent = AtomicLong()

    private fun startKeepAlive() {
        // Servers drop a session they hear nothing from for its timeout (65s on
        // Reolink), and RTP doesn't count for all of them, so ask something at
        // under half of it.
        // GET_PARAMETER is the usual ping; a server that doesn't know it gets OPTIONS instead.
        val period = (sessionTimeoutSecs / 2).coerceIn(5, 30).toLong()
        var method = "GET_PARAMETER"
        keepAliveTask = keepAlive.scheduleWithFixedDelay({
            try {
                val r = connection.request(method, aggregateUrl, expectOk = method == "OPTIONS")
                if (r.status != 200) {
                    Log.i(TAG, "$method answered ${r.status}, pinging with OPTIONS")
                    method = "OPTIONS"
                    connection.request(method, aggregateUrl)
                }
            } catch (e: IOException) {
                Log.w(TAG, "keepalive failed: $e")
                failure = e
            }
        }, period, period, TimeUnit.SECONDS)
    }

    /** Sends [length] bytes of G.711 (one byte a sample, 8 kHz) as one RTP packet. */
    fun send(payload: ByteArray, length: Int) {
        try {
            connection.sendInterleaved(CHANNEL, packetizer.packet(payload, length))
            packetsSent.incrementAndGet()
        } catch (e: IOException) {
            failure = e
            throw e
        }
    }

    /** The next packet starts a talkspurt (sets the RTP marker), as after a pause. */
    fun markTalkspurt() = packetizer.markNextTalkspurt()

    override fun close() {
        keepAliveTask?.cancel(false)
        keepAlive.shutdownNow()
        try {
            connection.request("TEARDOWN", aggregateUrl, timeoutMs = 2000)
        } catch (e: IOException) {
            Log.d(TAG, "teardown: $e")
        }
        connection.close()
    }

    companion object {
        private const val TAG = "Backchannel"
        private const val REQUIRE = "www.onvif.org/ver20/backchannel"
        private const val CHANNEL = 0

        /**
         * Opens the backchannel of the stream at [url]. Credentials can be in
         * the URL (`rtsp://user:pass@host/...`) or given apart; the URL wins.
         */
        fun open(url: String, user: String = "", password: String = ""): Backchannel {
            val parsed = RtspUrl.parse(url)
            val connection = RtspConnection(parsed.host, parsed.port,
                parsed.user ?: user, parsed.password ?: password)
            try {
                val require = mapOf("Require" to REQUIRE)
                val described = connection.request("DESCRIBE", parsed.withoutCredentials,
                    require + ("Accept" to "application/sdp"))
                val base = described.header("content-base") ?: described.header("content-location")
                    ?: parsed.withoutCredentials
                val track = Sdp.backchannel(Sdp.parse(described.body))
                    ?: throw IOException("the camera offers no audio backchannel")
                val setup = connection.request("SETUP", Sdp.resolveControl(track.control, base),
                    require + ("Transport" to "RTP/AVP/TCP;unicast;interleaved=$CHANNEL-${CHANNEL + 1}"))
                val sessionHeader = setup.header("session") ?: throw IOException("SETUP returned no session")
                connection.session = sessionHeader.substringBefore(';').trim()
                val timeout = Regex("timeout=(\\d+)").find(sessionHeader)?.groupValues?.get(1)?.toInt() ?: 60
                connection.request("PLAY", base, require + ("Range" to "npt=0.000-"))
                return Backchannel(connection, base, track.codec, timeout).also { it.startKeepAlive() }
            } catch (e: Exception) {
                connection.close()
                throw e
            }
        }
    }
}

/** The parts of an rtsp:// URL that a connection needs. */
data class RtspUrl(
    val host: String,
    val port: Int,
    val user: String?,
    val password: String?,
    /** The URL as requests must name it: without the user info, which servers don't expect there. */
    val withoutCredentials: String,
) {
    companion object {
        const val DEFAULT_PORT = 554

        fun parse(url: String): RtspUrl {
            val uri = try {
                URI(url)
            } catch (e: Exception) {
                throw IOException("not a URL: $url")
            }
            if (!uri.scheme.equals("rtsp", ignoreCase = true) || uri.host == null) {
                throw IOException("not an rtsp:// URL: $url")
            }
            val userInfo = uri.rawUserInfo
            val user = userInfo?.substringBefore(':')?.let { decode(it) }
            val password = userInfo?.let { if (':' in it) decode(it.substringAfter(':')) else "" }
            val clean = "rtsp://" + uri.host + (if (uri.port > 0) ":${uri.port}" else "") +
                (uri.rawPath ?: "") + (uri.rawQuery?.let { "?$it" } ?: "")
            return RtspUrl(uri.host, if (uri.port > 0) uri.port else DEFAULT_PORT, user, password, clean)
        }

        // URLDecoder is for forms, where '+' is a space; in user info it is a '+'.
        private fun decode(s: String): String = java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    }
}
