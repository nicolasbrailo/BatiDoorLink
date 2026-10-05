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
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.nicobrailo.batidoorlink.audio.MicStreamer
import com.nicobrailo.batidoorlink.rtsp.Backchannel
import java.util.concurrent.Executors

/**
 * A test bench for talking to a doorbell: plays its RTSP stream with Media3
 * and sends the microphone back over the ONVIF backchannel, with switches for
 * everything that matters to echo (half or full duplex, the audio mode, the
 * mic source and the platform's voice effects).
 *
 * The stream comes from the fields, or from an rtsp:// VIEW intent, which
 * fills them and connects: `adb shell am start -a android.intent.action.VIEW
 * -d rtsp://admin:@10.10.30.11/h264Preview_01_sub`.
 */
@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var audioManager: AudioManager
    private lateinit var playerView: PlayerView
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
    private lateinit var rtpTcp: SwitchMaterial
    private lateinit var talkOnConnect: SwitchMaterial
    private lateinit var commMode: SwitchMaterial
    private lateinit var voiceSource: SwitchMaterial
    private lateinit var aec: SwitchMaterial
    private lateinit var ns: SwitchMaterial

    private val main = Handler(Looper.getMainLooper())
    /** Opens and closes backchannels, which block on the network. One thread keeps them in order. */
    private val io = Executors.newSingleThreadExecutor { Thread(it, "talk-io") }

    private var player: ExoPlayer? = null
    /**
     * Connected is what the user (or an intent) asked for, which outlives the
     * screen: leaving it disconnects, and coming back connects again. That is
     * what makes an intent work that arrives under a screensaver, which stops
     * the activity as soon as it starts.
     */
    private var wantConnected = false
    private var url: String? = null
    /** Bumped on every connect and disconnect, so a backchannel that opens late knows it is stale. */
    private var generation = 0
    private var backchannel: Backchannel? = null
    private var talkState = "closed"
    private var mic: MicStreamer? = null
    /** Talking was asked for while the backchannel was still opening. */
    private var talkPending = false
    private var savedAudioMode: Int? = null
    private var playerState = "idle"
    private var playerError: String? = null
    private var decoderName: String? = null
    private var droppedFrames = 0
    /** Read on the mic's thread for every frame, so it takes effect at once. */
    @Volatile private var sendingMic = true
    private var refreshes = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        playerView = findViewById(R.id.player)
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
        rtpTcp = findViewById(R.id.rtp_tcp)
        talkOnConnect = findViewById(R.id.talk_on_connect)
        commMode = findViewById(R.id.comm_mode)
        voiceSource = findViewById(R.id.voice_source)
        aec = findViewById(R.id.aec)
        ns = findViewById(R.id.ns)

        loadFields()
        connectButton.setOnClickListener {
            wantConnected = player == null
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
        if (wantConnected && player == null) connect()
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
        rtpTcp.isChecked = prefs.getBoolean("rtp_tcp", true)
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
            .putBoolean("rtp_tcp", rtpTcp.isChecked)
            .putBoolean("talk_on_connect", talkOnConnect.isChecked)
            .putBoolean("comm_mode", commMode.isChecked)
            .putBoolean("voice_source", voiceSource.isChecked)
            .putBoolean("aec", aec.isChecked)
            .putBoolean("ns", ns.isChecked)
            .putBoolean("half_duplex", halfDuplex.isChecked)
            .putBoolean("send_mic", sendMic.isChecked)
            .apply()
    }

    /** The stream's URL with the credentials in it, which is how both Media3 and [Backchannel] take them. */
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
            playerError = "enter the camera's IP"
            return
        }
        saveFields()
        url = u
        generation++
        playerError = null
        decoderName = null
        droppedFrames = 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (commMode.isChecked) {
            savedAudioMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            // The Portal has no earpiece, but a call routes to one by default elsewhere.
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }

        // A doorbell wants the picture now, not smooth: start playing on the
        // first 100ms and keep little in hand. Even so, Media3 was measured
        // holding about 1.6s and playing 2 to 2.7s behind the door (see
        // AGENTS.md); the defaults weren't measured.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 2000, 100, 200)
            .build()
        val p = ExoPlayer.Builder(this).setLoadControl(loadControl).build()
        val attributes = if (commMode.isChecked) {
            AudioAttributes.Builder().setUsage(C.USAGE_VOICE_COMMUNICATION)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build()
        } else {
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
        }
        p.setAudioAttributes(attributes, false)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                playerState = when (state) {
                    Player.STATE_BUFFERING -> "buffering"
                    Player.STATE_READY -> "playing"
                    Player.STATE_ENDED -> "ended"
                    else -> "idle"
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "playback failed", error)
                playerError = "${error.errorCodeName}: ${error.cause?.message ?: error.message}"
            }
        })
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long,
            ) {
                this@MainActivity.decoderName = decoderName
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int,
                                              elapsedMs: Long) {
                this@MainActivity.droppedFrames += droppedFrames
            }
        })
        p.setMediaSource(RtspMediaSource.Factory()
            .setForceUseRtpTcp(rtpTcp.isChecked)
            .setTimeoutMs(5000)
            .createMediaSource(MediaItem.fromUri(u)))
        playerView.player = p
        p.playWhenReady = true
        p.prepare()
        player = p
        applyVolume()

        connectButton.text = "Disconnect"
        holdToTalk.isEnabled = true
        openMic.isEnabled = true
        if (talkOnConnect.isChecked) openBackchannel()
    }

    private fun disconnect() {
        generation++
        stopMic()
        talkPending = false
        openMic.isChecked = false
        closeBackchannel()
        player?.release()
        player = null
        playerView.player = null
        playerState = "idle"
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
        if (player == null) return
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
        player?.volume = if (silent) 0f else 1f
    }

    private val refreshStatus = object : Runnable {
        override fun run() {
            player?.let {
                // Every 2s, for following a test from adb.
                if (++refreshes % 4 == 0) {
                    Log.d(TAG, "position ${it.currentPosition}ms at ${android.os.SystemClock.elapsedRealtime()}, " +
                        "buffered ${it.totalBufferedDuration}ms, " +
                        "state $playerState, talk $talkState" + (mic?.let { m -> ", mic %.0f dBFS".format(m.levelDb) } ?: ""))
                }
            }
            status.text = statusText()
            main.postDelayed(this, 500)
        }
    }

    private fun statusText(): String {
        val p = player ?: return playerError ?: "not connected"
        val lines = mutableListOf<String>()
        val video = p.videoFormat
        lines += "video: " + (video?.let {
            "${it.width}x${it.height} ${it.codecs ?: it.sampleMimeType} " +
                (if (it.frameRate > 0) "%.0ffps ".format(it.frameRate) else "") + (decoderName ?: "")
        } ?: "none yet")
        lines += "audio: " + (p.audioFormat?.let { "${it.sampleMimeType} ${it.sampleRate}Hz" } ?: "none yet") +
            if (p.volume == 0f) " (muted)" else ""
        lines += "state: $playerState, buffered ${p.totalBufferedDuration}ms, dropped $droppedFrames"
        playerError?.let { lines += "error: $it" }
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
