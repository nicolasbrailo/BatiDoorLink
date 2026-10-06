package com.nicobrailo.batidoorlink.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Decodes H.264 onto a surface and shows each picture as soon as the decoder
 * lets go of it, with no pacing: the network's timing is the only timing, so
 * the picture is as fresh as it can be, at the price of the odd uneven frame.
 */
class VideoDecoder(
    private val surface: Surface,
    private val parameterSets: List<ByteArray>,
    private val width: Int,
    private val height: Int,
    /** The decoded picture's size, once known, on the decoder's thread. */
    private val onSize: (Int, Int) -> Unit,
    /** The first picture went to the screen, on the decoder's thread. */
    private val onFirstPicture: () -> Unit = {},
) {
    private val queue = LinkedBlockingDeque<AccessUnit>()
    @Volatile private var running = true
    private val thread = Thread({ run() }, "video-decoder").apply { start() }
    private var firstTimestamp = -1L
    /** When each picture in the decoder finished arriving, by its presentation time. */
    private val arrivals = HashMap<Long, Long>()

    @Volatile var decoderName = ""
        private set
    @Volatile var framesRendered = 0L
        private set
    /** Pictures thrown away because the decoder fell behind. */
    @Volatile var framesSkipped = 0L
        private set
    /** From a picture's last packet arriving to it going to the screen, smoothed. */
    @Volatile var decodeLatencyMs = 0.0
        private set
    @Volatile var failure: String? = null
        private set
    private var waitingForKeyframe = false

    fun offer(unit: AccessUnit) {
        // A decoder that can't keep up would put the picture further behind with
        // every frame, so a backlog is thrown away and decoding resumes at the
        // next keyframe, which needs nothing before it.
        if (queue.size >= MAX_BACKLOG) {
            framesSkipped += queue.size
            queue.clear()
            waitingForKeyframe = true
        }
        if (waitingForKeyframe && !unit.keyframe) {
            framesSkipped++
            return
        }
        waitingForKeyframe = false
        queue.offer(unit)
    }

    fun release() {
        running = false
        thread.join(2000)
    }

    private fun run() {
        val codec = try {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (e: Exception) {
            failure = "no H.264 decoder: ${e.message}"
            return
        }
        try {
            decoderName = codec.name
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            parameterSets.getOrNull(0)?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            parameterSets.getOrNull(1)?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
            format.setInteger(MediaFormat.KEY_PRIORITY, 0) // real time
            // Qualcomm's decoders hold pictures back to reorder them unless told
            // the stream needs none; other vendors ignore keys they don't know.
            format.setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
            format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            codec.configure(format, surface, null, 0)
            codec.start()
            Log.i(TAG, "timing: decoder started")
            loop(codec)
        } catch (e: Exception) {
            Log.w(TAG, "decoder failed", e)
            failure = "decoder: ${e.message}"
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
        }
    }

    private fun loop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var pending: AccessUnit? = null
        while (running) {
            if (pending == null) pending = queue.poll(5, TimeUnit.MILLISECONDS)
            val unit = pending
            if (unit != null) {
                val index = codec.dequeueInputBuffer(0)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    buffer.clear()
                    buffer.put(unit.data)
                    if (firstTimestamp < 0) {
                        firstTimestamp = unit.rtpTimestamp
                        Log.i(TAG, "timing: first picture into the decoder")
                    }
                    // 90 kHz RTP clock, which wraps at 32 bits.
                    val ptsUs = ((unit.rtpTimestamp - firstTimestamp) and 0xFFFFFFFFL) * 1000 / 90
                    synchronized(arrivals) {
                        arrivals[ptsUs] = unit.completedAtNanos
                    }
                    codec.queueInputBuffer(index, 0, unit.data.size, ptsUs,
                        if (unit.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    pending = null
                }
            }
            while (true) {
                val out = codec.dequeueOutputBuffer(info, 0)
                when {
                    out >= 0 -> {
                        codec.releaseOutputBuffer(out, true)
                        if (framesRendered == 0L) {
                            Log.i(TAG, "timing: first picture on screen")
                            onFirstPicture()
                        }
                        framesRendered++
                        val arrived = synchronized(arrivals) { arrivals.remove(info.presentationTimeUs) }
                        if (arrived != null) {
                            val ms = (System.nanoTime() - arrived) / 1e6
                            decodeLatencyMs = if (decodeLatencyMs == 0.0) ms else decodeLatencyMs * 0.9 + ms * 0.1
                        }
                    }
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        val w = if (f.containsKey("crop-right")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1
                            else f.getInteger(MediaFormat.KEY_WIDTH)
                        val h = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
                            else f.getInteger(MediaFormat.KEY_HEIGHT)
                        onSize(w, h)
                    }
                    else -> break
                }
            }
            synchronized(arrivals) {
                // Pictures the decoder dropped never come out; don't let their entries pile up.
                if (arrivals.size > 100) arrivals.clear()
            }
        }
    }

    companion object {
        private const val TAG = "VideoDecoder"
        private const val MAX_BACKLOG = 8
    }
}
