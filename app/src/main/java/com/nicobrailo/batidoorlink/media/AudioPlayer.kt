package com.nicobrailo.batidoorlink.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Decodes AAC and plays it straight away, holding as little as the audio
 * output allows. Network jitter would otherwise pile up as delay: whenever
 * more than [MAX_QUEUED_MS] waits to be played, decoded audio is dropped
 * until it is back under, which is heard as a skip rather than as a voice
 * that keeps getting later.
 */
class AudioPlayer(
    private val config: AacConfig,
    /** Plays as a call (USAGE_VOICE_COMMUNICATION) rather than as media. */
    private val voice: Boolean,
) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = true
    @Volatile private var track: AudioTrack? = null
    private var framesWritten = 0L
    @Volatile private var volume = 1f
    private val thread = Thread({ run() }, "audio-player").apply { start() }

    /** Audio written and not yet played, in ms. */
    @Volatile var queuedMs = 0
        private set
    @Volatile var droppedMs = 0L
        private set
    @Volatile var failure: String? = null
        private set

    fun offer(frame: ByteArray) {
        // The decoder keeps up with 16 kHz mono without trying; this only guards against a stall.
        if (queue.size < 50) queue.offer(frame)
    }

    fun setVolume(v: Float) {
        volume = v
        track?.setVolume(v)
    }

    fun release() {
        running = false
        thread.join(2000)
    }

    private fun run() {
        val codec = try {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        } catch (e: Exception) {
            failure = "no AAC decoder: ${e.message}"
            return
        }
        var t: AudioTrack? = null
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, config.sampleRate, config.channels)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(config.bytes))
            format.setInteger(MediaFormat.KEY_IS_ADTS, 0)
            codec.configure(format, null, null, 0)
            codec.start()
            val channelMask = if (config.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuffer = AudioTrack.getMinBufferSize(config.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(if (voice) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(config.sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
                .setBufferSizeInBytes(minBuffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            t.setVolume(volume)
            t.play()
            track = t
            loop(codec, t)
        } catch (e: Exception) {
            Log.w(TAG, "audio failed", e)
            failure = "audio: ${e.message}"
        } finally {
            track = null
            t?.release()
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
        }
    }

    private fun loop(codec: MediaCodec, t: AudioTrack) {
        val info = MediaCodec.BufferInfo()
        val bytesPerFrame = 2 * config.channels
        var pending: ByteArray? = null
        var pcm = ByteArray(0)
        while (running) {
            if (pending == null) pending = queue.poll(5, TimeUnit.MILLISECONDS)
            pending?.let { frame ->
                val index = codec.dequeueInputBuffer(0)
                if (index >= 0) {
                    codec.getInputBuffer(index)!!.apply { clear(); put(frame) }
                    codec.queueInputBuffer(index, 0, frame.size, 0, 0)
                    pending = null
                }
            }
            while (true) {
                val out = codec.dequeueOutputBuffer(info, 0)
                if (out < 0) break
                if (info.size > 0) {
                    if (pcm.size < info.size) pcm = ByteArray(info.size)
                    codec.getOutputBuffer(out)!!.apply {
                        position(info.offset)
                        get(pcm, 0, info.size)
                    }
                    val frames = info.size / bytesPerFrame
                    val queued = framesWritten - (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
                    queuedMs = (queued * 1000 / config.sampleRate).toInt()
                    if (queuedMs > MAX_QUEUED_MS) {
                        droppedMs += frames * 1000L / config.sampleRate
                    } else {
                        t.write(pcm, 0, info.size)
                        framesWritten += frames
                    }
                }
                codec.releaseOutputBuffer(out, false)
            }
        }
    }

    companion object {
        private const val TAG = "AudioPlayer"
        private const val MAX_QUEUED_MS = 200
    }
}
