package com.nicobrailo.batidoorlink.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.nicobrailo.batidoorlink.rtsp.G711
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Records the microphone at 8 kHz and hands it on in 20 ms frames of G.711,
 * the size RTP audio usually travels in. The recording paces itself, so the
 * frames go out in real time without a timer.
 */
class MicStreamer(
    private val codec: G711,
    private val options: Options,
    /** Called on the recording thread with each frame (160 bytes). */
    private val onFrame: (ByteArray, Int) -> Unit,
) {
    data class Options(
        /** VOICE_COMMUNICATION is the source the platform applies its voice processing to. */
        val voiceCommunicationSource: Boolean,
        val echoCanceller: Boolean,
        val noiseSuppressor: Boolean,
    )

    /** What was actually set up, for the status line: an effect can be asked for and missing. */
    @Volatile var description = ""
        private set

    /** The last frame's level in dBFS, for a meter. */
    @Volatile var levelDb = -90.0
        private set

    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // The activity asks for RECORD_AUDIO before starting this.
    fun start() {
        if (running) return
        running = true
        thread = Thread({ record() }, "mic").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun record() {
        val source = if (options.voiceCommunicationSource) MediaRecorder.AudioSource.VOICE_COMMUNICATION
            else MediaRecorder.AudioSource.MIC
        val minBuffer = AudioRecord.getMinBufferSize(G711.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(source, G711.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, FRAME * 2 * 10))
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            description = "mic failed to open"
            record.release()
            running = false
            return
        }
        val effects = mutableListOf<AudioEffect>()
        val notes = mutableListOf(if (options.voiceCommunicationSource) "voice source" else "mic source")
        if (options.echoCanceller) {
            notes += attach("AEC", AcousticEchoCanceler.isAvailable(), effects) {
                AcousticEchoCanceler.create(record.audioSessionId)
            }
        }
        if (options.noiseSuppressor) {
            notes += attach("NS", NoiseSuppressor.isAvailable(), effects) {
                NoiseSuppressor.create(record.audioSessionId)
            }
        }
        description = notes.joinToString(", ")
        Log.i(TAG, "recording: $description")

        val pcm = ShortArray(FRAME)
        val encoded = ByteArray(FRAME)
        try {
            record.startRecording()
            while (running) {
                var got = 0
                while (got < FRAME && running) {
                    val n = record.read(pcm, got, FRAME - got)
                    if (n < 0) throw IllegalStateException("AudioRecord.read returned $n")
                    got += n
                }
                if (!running) break
                levelDb = level(pcm)
                codec.encode(pcm, FRAME, encoded)
                onFrame(encoded, FRAME)
            }
        } catch (e: Exception) {
            Log.w(TAG, "recording stopped: $e")
            description = "mic stopped: ${e.message}"
        } finally {
            effects.forEach { it.release() }
            record.stop()
            record.release()
            running = false
        }
    }

    private fun attach(name: String, available: Boolean, into: MutableList<AudioEffect>,
                       create: () -> AudioEffect?): String {
        if (!available) return "$name unavailable"
        val effect = create() ?: return "$name failed"
        effect.enabled = true
        into += effect
        return "$name on"
    }

    companion object {
        private const val TAG = "MicStreamer"
        /** 20 ms at 8 kHz. */
        const val FRAME = 160

        fun level(pcm: ShortArray): Double {
            var sum = 0.0
            for (s in pcm) sum += s.toDouble() * s
            val rms = sqrt(sum / pcm.size)
            return if (rms < 1) -90.0 else 20 * log10(rms / 32768)
        }
    }
}
