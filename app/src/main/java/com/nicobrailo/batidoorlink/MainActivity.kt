package com.nicobrailo.batidoorlink

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.nicobrailo.batidoorlink.audio.MicStreamer
import com.nicobrailo.batidoorlink.media.AccessUnit
import com.nicobrailo.batidoorlink.media.AudioPlayer
import com.nicobrailo.batidoorlink.media.VideoDecoder
import com.nicobrailo.batidoorlink.rtsp.Backchannel
import com.nicobrailo.batidoorlink.rtsp.StreamSession
import java.util.concurrent.Executors

/**
 * A test bench for talking to a doorbell: plays its RTSP stream through a
 * pipeline of our own built for latency ([StreamSession], [VideoDecoder],
 * [AudioPlayer]) and sends the microphone back over the ONVIF backchannel,
 * with switches for everything that matters to echo (half or full duplex,
 * the audio mode, the mic source and the platform's voice effects).
 *
 * The stream comes from the fields, or from an rtsp:// VIEW intent, which
 * fills them and connects: `adb shell am start -a android.intent.action.VIEW
 * -d rtsp://admin:@10.10.30.11/h264Preview_01_sub`.
 */
class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private lateinit var prefs: SharedPreferences
    private lateinit var audioManager: AudioManager
    private lateinit var videoFrame: VideoFrame
    private lateinit var status: TextView
    private lateinit var host: EditText
    private lateinit var stream: EditText
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var connectButton: Button
    private lateinit var holdToTalk: Button
    private lateinit var openMic: SwitchMaterial
    private lateinit var halfDuplex: SwitchMaterial
    private lateinit var muteDoorbell: SwitchMaterial
    private lateinit var sendMic: SwitchMaterial
    private lateinit var useTcp: SwitchMaterial
    private lateinit var talkOnConnect: SwitchMaterial
    private lateinit var commMode: SwitchMaterial
    private lateinit var voiceSource: SwitchMaterial
    private lateinit var aec: SwitchMaterial
    private lateinit var ns: SwitchMaterial

    private val main = Handler(Looper.getMainLooper())
    /** Opens and closes backchannels, which block on the network. One thread keeps them in order. */
    private val io = Executors.newSingleThreadExecutor { Thread(it, "talk-io") }
    /** The same for the stream, apart, so the talk channel doesn't wait out a UDP attempt. */
    private val streamIo = Executors.newSingleThreadExecutor { Thread(it, "stream-io") }

    /**
     * Connected is what the user (or an intent) asked for, which outlives the
     * screen: leaving it disconnects, and coming back connects again. That is
     * what makes an intent work that arrives under a screensaver, which stops
     * the activity as soon as it starts.
     */
    private var wantConnected = false
    private var connected = false
    private var surface: Surface? = null
    private var url: String? = null
    /** Bumped on every connect and disconnect, so whatever opens late knows it is stale. */
    private var generation = 0
    @Volatile private var session: StreamSession? = null
    @Volatile private var videoDecoder: VideoDecoder? = null
    @Volatile private var audioPlayer: AudioPlayer? = null
    private var streamState = "idle"
    private var backchannel: Backchannel? = null
    private var talkState = "closed"
    private var mic: MicStreamer? = null
    /** Talking was asked for while the backchannel was still opening. */
    private var talkPending = false
    private var savedAudioMode: Int? = null
    /** Read on the mic's thread for every frame, so it takes effect at once. */
    @Volatile private var sendingMic = true
    private var refreshes = 0
    private var lastFrames = 0L
    private var lastFramesAt = 0L
    private var fps = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        videoFrame = findViewById(R.id.video_frame)
        findViewById<SurfaceView>(R.id.video).holder.addCallback(this)
        status = findViewById(R.id.status)
        host = findViewById(R.id.host)
        stream = findViewById(R.id.stream)
        user = findViewById(R.id.user)
        password = findViewById(R.id.password)
        connectButton = findViewById(R.id.connect)
        holdToTalk = findViewById(R.id.hold_to_talk)
        openMic = findViewById(R.id.open_mic)
        halfDuplex = findViewById(R.id.half_duplex)
        muteDoorbell = findViewById(R.id.mute_doorbell)
        sendMic = findViewById(R.id.send_mic)
        useTcp = findViewById(R.id.use_tcp)
        talkOnConnect = findViewById(R.id.talk_on_connect)
        commMode = findViewById(R.id.comm_mode)
        voiceSource = findViewById(R.id.voice_source)
        aec = findViewById(R.id.aec)
        ns = findViewById(R.id.ns)

        loadFields()
        connectButton.setOnClickListener {
            wantConnected = !connected
            if (wantConnected) connect() else disconnect()
        }
        setUpTalkControls()
        muteDoorbell.setOnCheckedChangeListener { _, _ -> applyVolume() }
        halfDuplex.setOnCheckedChangeListener { _, _ -> applyVolume() }
        sendMic.setOnCheckedChangeListener { _, checked -> sendingMic = checked }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        main.post(refreshStatus)
        if (wantConnected && !connected) connect()
    }

    override fun onStop() {
        super.onStop()
        // A doorbell view nobody can see shouldn't keep the camera or the mic open.
        disconnect()
        main.removeCallbacks(refreshStatus)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
        streamIo.shutdown()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        if (wantConnected && !connected && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            connect()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // The decoder draws on this surface, so it has to stop before the surface goes.
        disconnect()
        surface = null
    }

    /** An rtsp:// URL fills the fields (credentials included) and connects, now or once on screen. */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW || !data.scheme.equals("rtsp", true)) return
        host.setText(if (data.port > 0) "${data.host}:${data.port}" else data.host)
        stream.setText(data.encodedPath.orEmpty().trimStart('/') +
            (data.encodedQuery?.let { "?$it" } ?: ""))
        data.encodedUserInfo?.let { info ->
            user.setText(Uri.decode(info.substringBefore(':')))
            password.setText(if (':' in info) Uri.decode(info.substringAfter(':')) else "")
        }
        disconnect()
        wantConnected = true
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) connect()
    }

    private fun loadFields() {
        host.setText(prefs.getString("host", ""))
        stream.setText(prefs.getString("stream", "h264Preview_01_sub"))
        user.setText(prefs.getString("user", "admin"))
        password.setText(prefs.getString("password", ""))
        // TCP by default: UDP was no faster here, slower to start, and froze on Wi-Fi losses (AGENTS.md).
        useTcp.isChecked = prefs.getBoolean("use_tcp", true)
        talkOnConnect.isChecked = prefs.getBoolean("talk_on_connect", true)
        commMode.isChecked = prefs.getBoolean("comm_mode", true)
        voiceSource.isChecked = prefs.getBoolean("voice_source", true)
        aec.isChecked = prefs.getBoolean("aec", true)
        ns.isChecked = prefs.getBoolean("ns", false)
        halfDuplex.isChecked = prefs.getBoolean("half_duplex", false)
        sendMic.isChecked = prefs.getBoolean("send_mic", true)
        sendingMic = sendMic.isChecked
    }

    private fun saveFields() {
        prefs.edit()
            .putString("host", host.text.toString().trim())
            .putString("stream", stream.text.toString().trim())
            .putString("user", user.text.toString())
            .putString("password", password.text.toString())
            .putBoolean("use_tcp", useTcp.isChecked)
            .putBoolean("talk_on_connect", talkOnConnect.isChecked)
            .putBoolean("comm_mode", commMode.isChecked)
            .putBoolean("voice_source", voiceSource.isChecked)
            .putBoolean("aec", aec.isChecked)
            .putBoolean("ns", ns.isChecked)
            .putBoolean("half_duplex", halfDuplex.isChecked)
            .putBoolean("send_mic", sendMic.isChecked)
            .apply()
    }

    /** The stream's URL with the credentials in it, which is how both sessions take them. */
    private fun buildUrl(): String? {
        val s = stream.text.toString().trim()
        val credentials = Uri.encode(user.text.toString()) + ":" + Uri.encode(password.text.toString()) + "@"
        if (s.startsWith("rtsp://", true)) {
            // A full URL keeps any credentials of its own.
            return if ('@' in s.substringAfter("://").substringBefore('/')) s
                else "rtsp://" + credentials + s.substring("rtsp://".length)
        }
        val h = host.text.toString().trim()
        if (h.isEmpty()) return null
        return "rtsp://$credentials$h/${s.trimStart('/')}"
    }

    private fun connect() {
        val u = buildUrl() ?: run {
            streamState = "enter the camera's IP"
            return
        }
        // The decoder needs somewhere to draw; surfaceCreated connects once there is.
        val target = surface ?: return
        saveFields()
        url = u
        generation++
        connected = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (commMode.isChecked) {
            savedAudioMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            // The Portal has no earpiece, but a call routes to one by default elsewhere.
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
        startStream(u, target, generation)

        connectButton.text = "Disconnect"
        holdToTalk.isEnabled = true
        openMic.isEnabled = true
        if (talkOnConnect.isChecked) openBackchannel()
    }

    private fun startStream(u: String, target: Surface, gen: Int) {
        streamState = "connecting"
        val tcp = useTcp.isChecked
        val voice = commMode.isChecked
        streamIo.execute {
            if (gen != generation) return@execute
            try {
                val s = StreamSession.describe(u)
                session = s
                // The decoders exist before PLAY, since the camera starts with a keyframe.
                val size = s.videoSize
                if (s.hasVideo) {
                    videoDecoder = VideoDecoder(target, s.parameterSets, size?.width ?: 1920, size?.height ?: 1080) { w, h ->
                        main.post { videoFrame.setVideoSize(w, h) }
                    }
                    size?.let { main.post { videoFrame.setVideoSize(it.width, it.height) } }
                }
                s.audioConfig?.let { audioPlayer = AudioPlayer(it, voice) }
                main.post { applyVolume() }
                s.play(object : StreamSession.Listener {
                    override fun onVideo(unit: AccessUnit) {
                        videoDecoder?.offer(unit)
                    }

                    override fun onAudio(frame: ByteArray, rtpTimestamp: Long) {
                        audioPlayer?.offer(frame)
                    }
                }, preferTcp = tcp)
                main.post { if (gen == generation) streamState = "playing over ${s.transport}" }
            } catch (e: Exception) {
                Log.w(TAG, "stream failed", e)
                main.post { if (gen == generation) streamState = "failed: ${e.message}" }
            }
        }
    }

    private fun stopStream() {
        // In this order: nothing more arrives, then the decoders go. The video
        // decoder goes here and now, while its surface still exists.
        val s = session
        session = null
        val v = videoDecoder
        videoDecoder = null
        val a = audioPlayer
        audioPlayer = null
        v?.release()
        a?.release()
        streamIo.execute { s?.close() }
        streamState = "idle"
    }

    private fun disconnect() {
        generation++
        stopMic()
        talkPending = false
        openMic.isChecked = false
        closeBackchannel()
        stopStream()
        connected = false
        savedAudioMode?.let {
            audioManager.mode = it
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
        savedAudioMode = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        connectButton.text = "Connect"
        holdToTalk.isEnabled = false
        openMic.isEnabled = false
    }

    private fun openBackchannel() {
        val u = url ?: return
        if (backchannel != null || talkState == "opening") return
        val gen = generation
        talkState = "opening"
        io.execute {
            val result = runCatching { Backchannel.open(u) }
            main.post {
                val bc = result.getOrNull()
                if (gen != generation) {
                    // Disconnected (or reconnected) meanwhile.
                    bc?.let { io.execute { it.close() } }
                    return@post
                }
                if (bc == null) {
                    talkState = "failed: ${result.exceptionOrNull()?.message}"
                    Log.w(TAG, "backchannel failed", result.exceptionOrNull())
                    talkPending = false
                    return@post
                }
                backchannel = bc
                talkState = "open, ${bc.codec.encodingName}, session timeout ${bc.sessionTimeoutSecs}s"
                if (talkPending) startMic()
            }
        }
    }

    private fun closeBackchannel() {
        val bc = backchannel ?: run {
            talkState = "closed"
            return
        }
        backchannel = null
        talkState = "closed"
        io.execute { bc.close() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpTalkControls() {
        holdToTalk.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    startTalking()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (!openMic.isChecked) stopTalking()
                }
            }
            true
        }
        openMic.setOnCheckedChangeListener { _, checked ->
            if (checked) startTalking() else stopTalking()
        }
    }

    private fun startTalking() {
        if (!connected) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (backchannel == null) {
            talkPending = true
            openBackchannel()
        } else {
            startMic()
        }
    }

    private fun stopTalking() {
        talkPending = false
        stopMic()
    }

    private fun startMic() {
        talkPending = false
        val bc = backchannel ?: return
        if (mic != null) return
        val options = MicStreamer.Options(voiceSource.isChecked, aec.isChecked, ns.isChecked)
        saveFields()
        bc.markTalkspurt()
        mic = MicStreamer(bc.codec, options) { frame, length ->
            if (sendingMic) bc.send(frame, length)
        }.also { it.start() }
        applyVolume()
    }

    private fun stopMic() {
        mic?.stop()
        mic = null
        applyVolume()
    }

    private fun applyVolume() {
        val silent = muteDoorbell.isChecked || (halfDuplex.isChecked && mic != null)
        audioPlayer?.setVolume(if (silent) 0f else 1f)
    }

    private val refreshStatus = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.elapsedRealtime()
            videoDecoder?.let {
                val frames = it.framesRendered
                if (lastFramesAt > 0) fps = (frames - lastFrames) * 1000.0 / (now - lastFramesAt).coerceAtLeast(1)
                lastFrames = frames
            } ?: run {
                lastFrames = 0
                fps = 0.0
            }
            lastFramesAt = now
            // Every 2s, for following a test from adb.
            if (connected && ++refreshes % 4 == 0) Log.d(TAG, statusText().replace("\n", " | "))
            status.text = statusText()
            main.postDelayed(this, 500)
        }
    }

    private fun statusText(): String {
        if (!connected) return if (streamState == "idle") "not connected" else streamState
        val lines = mutableListOf("stream: $streamState")
        val s = session
        val v = videoDecoder
        if (v != null) {
            lines += "video: ${s?.videoSize?.let { "${it.width}x${it.height} " } ?: ""}${v.decoderName}, " +
                "%.0f fps, decode %.0fms".format(fps, v.decodeLatencyMs)
            lines += "video loss: ${s?.videoPacketsLost ?: 0} packets, ${s?.videoUnitsDropped ?: 0} pictures dropped, " +
                "${v.framesSkipped} skipped"
            v.failure?.let { lines += "video error: $it" }
        }
        val a = audioPlayer
        if (a != null) {
            lines += "audio: AAC ${s?.audioConfig?.sampleRate ?: 0}Hz, queued ${a.queuedMs}ms, " +
                "dropped ${a.droppedMs}ms, lost ${s?.audioPacketsLost ?: 0} packets" +
                if (muteDoorbell.isChecked || (halfDuplex.isChecked && mic != null)) " (muted)" else ""
            a.failure?.let { lines += "audio error: $it" }
        }
        s?.failure?.let { lines += "stream failing: ${it.message}" }
        val bc = backchannel
        lines += "talk: $talkState" + (bc?.let { ", ${it.packetsSent.get()} packets sent" } ?: "") +
            (bc?.failure?.let { ", failing: ${it.message}" } ?: "")
        mic?.let {
            lines += "mic: ${it.description}, %.0f dBFS".format(it.levelDb) + if (sendingMic) "" else " (not sent)"
        }
        lines += "audio mode: " + when (audioManager.mode) {
            AudioManager.MODE_IN_COMMUNICATION -> "in communication"
            AudioManager.MODE_NORMAL -> "normal"
            else -> audioManager.mode.toString()
        }
        return lines.joinToString("\n")
    }

    companion object {
        private const val TAG = "BatiDoorLink"
    }
}
