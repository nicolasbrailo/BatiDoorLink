package com.nicobrailo.batidoorlink.rtsp

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * One RTSP connection over TCP. A single thread reads everything the server
 * sends, because responses and interleaved RTCP share the socket: whoever is
 * waiting for a response takes it from [responses], and the rest is dropped.
 * Requests are serialised, which is all RTSP needs, and the credentials are
 * answered with whatever the server's 401 asked for.
 */
class RtspConnection(
    host: String,
    port: Int,
    private val user: String,
    private val password: String,
    connectTimeoutMs: Int = 5000,
) : Closeable {
    private val socket = Socket()
    private val output: BufferedOutputStream
    private val responses = LinkedBlockingQueue<Result<RtspMessage.Response>>()
    private var cseq = 0
    private var challenge: AuthChallenge? = null
    private var nonceCount = 0
    @Volatile private var closed = false

    /** Set once SETUP has returned one; sent with every later request. */
    var session: String? = null

    /** Called on the reader thread for each interleaved packet: media over TCP, or the server's RTCP. */
    var onInterleaved: ((RtspMessage.Interleaved) -> Unit)? = null

    /** The last keepalive that failed, if any; the session is probably gone then. */
    @Volatile var keepAliveFailure: Exception? = null
        private set
    private var keepAlive: ScheduledExecutorService? = null

    init {
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket.tcpNoDelay = true
        output = BufferedOutputStream(socket.getOutputStream())
        val input = BufferedInputStream(socket.getInputStream())
        Thread({
            try {
                while (true) {
                    when (val m = RtspMessage.read(input)) {
                        is RtspMessage.Response -> responses.put(Result.success(m))
                        is RtspMessage.Interleaved -> onInterleaved?.invoke(m)
                        is RtspMessage.Request -> Log.d(TAG, "ignoring server request ${m.requestLine}")
                    }
                }
            } catch (e: IOException) {
                if (!closed) Log.w(TAG, "connection lost: $e")
                responses.put(Result.failure(e))
            }
        }, "rtsp-reader").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun request(method: String, url: String, headers: Map<String, String> = emptyMap(),
                timeoutMs: Long = 5000, expectOk: Boolean = true): RtspMessage.Response {
        repeat(2) {
            val sb = StringBuilder("$method $url RTSP/1.0\r\nCSeq: ${++cseq}\r\nUser-Agent: BatiDoorLink\r\n")
            challenge?.let {
                sb.append("Authorization: ")
                    .append(Auth.authorization(it, user, password, method, url, ++nonceCount)).append("\r\n")
            }
            session?.let { sb.append("Session: $it\r\n") }
            headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            sb.append("\r\n")
            synchronized(output) {
                output.write(sb.toString().toByteArray())
                output.flush()
            }
            val response = responses.poll(timeoutMs, TimeUnit.MILLISECONDS)?.getOrThrow()
                ?: throw IOException("$method: no answer in ${timeoutMs}ms")
            if (response.status == 401 && challenge == null) {
                challenge = response.header("www-authenticate")?.let { AuthChallenge.parse(it) }
                    ?: throw IOException("$method: 401 without a challenge we understand")
                nonceCount = 0
                return@repeat
            }
            if (expectOk && response.status != 200) throw IOException("$method: ${response.status} ${response.reason}")
            return response
        }
        throw IOException("$method: credentials refused")
    }

    /** Sends one interleaved frame (`$`, channel, length, data). */
    fun sendInterleaved(channel: Int, data: ByteArray) {
        val header = byteArrayOf('$'.code.toByte(), channel.toByte(),
            (data.size shr 8).toByte(), data.size.toByte())
        synchronized(output) {
            output.write(header)
            output.write(data)
            output.flush()
        }
    }

    /**
     * Servers drop a session they hear nothing from for its timeout (65s on
     * the Reolink with old firmware, 30s with new), and RTP doesn't count for
     * all of them, so this asks something at under half of it. GET_PARAMETER
     * is the usual ping; a server that doesn't know it gets OPTIONS instead.
     */
    fun startKeepAlive(url: String, sessionTimeoutSecs: Int) {
        val period = (sessionTimeoutSecs / 2).coerceIn(5, 30).toLong()
        var method = "GET_PARAMETER"
        keepAlive = Executors.newSingleThreadScheduledExecutor { Thread(it, "rtsp-keepalive").apply { isDaemon = true } }
            .apply {
                scheduleWithFixedDelay({
                    try {
                        val r = request(method, url, expectOk = method == "OPTIONS")
                        if (r.status != 200) {
                            Log.i(TAG, "$method answered ${r.status}, pinging with OPTIONS")
                            method = "OPTIONS"
                            request(method, url)
                        }
                    } catch (e: IOException) {
                        Log.w(TAG, "keepalive failed: $e")
                        keepAliveFailure = e
                    }
                }, period, period, TimeUnit.SECONDS)
            }
    }

    override fun close() {
        keepAlive?.shutdownNow()
        closed = true
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val TAG = "RtspConnection"

        /** The timeout in a Session header ("DC7CB932;timeout=65"), or RFC 2326's default of 60s. */
        fun sessionTimeout(sessionHeader: String): Int =
            Regex("timeout=(\\d+)").find(sessionHeader)?.groupValues?.get(1)?.toInt() ?: 60
    }
}
