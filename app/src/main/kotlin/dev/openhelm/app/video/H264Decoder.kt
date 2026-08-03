package dev.openhelm.app.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import dev.openhelm.protocol.video.containsIdr
import java.util.ArrayDeque

/**
 * H.264 → Surface with the smallest possible standing queue. This class is where the latency
 * budget is won or lost, so its rules are strict:
 *
 * - **Output buffers are rendered the moment they appear** — `releaseOutputBuffer(i, true)` in the
 *   callback, never scheduled against a presentation timestamp. Rendering "on time" against a
 *   stream clock is precisely how ordinary players build up seconds of lag.
 * - **At most [MAX_PENDING] access units wait for the decoder.** Beyond that the queue is cleared
 *   and decoding resumes at the next IDR: a dropped frame costs a flicker, a queued frame costs
 *   latency forever after.
 * - Low-latency decode is requested where the platform supports it (API 30+), and the codec runs
 *   at realtime priority.
 *
 * All MediaCodec interaction happens on one handler thread; [submit] may be called from any
 * thread.
 */
class H264Decoder(
    private val onFirstFrame: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** An access unit waiting for the decoder, with the instant its data finished arriving. */
    private class Queued(val au: ByteArray, val arrivedNanos: Long)

    private val pending = ArrayDeque<Queued>()
    private val freeInputs = ArrayDeque<Int>()
    private val submitTimesNs = ArrayDeque<Long>()

    /** Arrival instants of the access units currently inside the codec, oldest first. */
    private val inFlightArrivalNs = ArrayDeque<Long>()
    private var awaitIdr = true
    private var renderedFrames = 0L
    private var droppedAus = 0L
    private var decodeMsEma = 0.0
    private var latencyMsEma = 0.0
    private var windowFrames = 0
    private var windowStartNs = 0L
    private var fps = 0

    /** Rolling pipeline numbers for the on-screen overlay. */
    data class Stats(
        val fps: Int,
        val rendered: Long,
        val dropped: Long,
        val queueDepth: Int,
        val decodeMs: Int,
        /**
         * Milliseconds from a frame's data being complete in this process to that frame being on
         * the glass — queue wait, decode and render, measured on one clock so it is exact.
         *
         * This is the delay **the app is responsible for**. It is deliberately not called
         * glass-to-glass: it cannot include what the display spends capturing and encoding before
         * the packets leave it, because measuring that needs the two devices' clocks to agree, and
         * a wrong number on a chart is worse than an honest partial one.
         */
        val latencyMs: Int,
    )

    @Volatile
    var stats: Stats = Stats(0, 0, 0, 0, 0, 0)
        private set

    fun start(surface: Surface, sps: ByteArray?, pps: ByteArray?) {
        val t = HandlerThread("openhelm-decode").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT)
        // Out-of-band parameter sets when the SDP carried them; otherwise the stream's own
        // in-band SPS/PPS (which precede every IDR on the servers this talks to) initialise the
        // decoder.
        sps?.let { format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(it)) }
        pps?.let { format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(it)) }
        format.setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
        if (Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }

        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec = c
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                freeInputs.add(index)
                feed()
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                // Render immediately. No clock, no scheduling, no exceptions.
                codec.releaseOutputBuffer(index, true)
                val now = System.nanoTime()
                submitTimesNs.pollFirst()?.let { submitted ->
                    val ms = (now - submitted) / 1_000_000.0
                    decodeMsEma = if (renderedFrames == 0L) ms else decodeMsEma * 0.9 + ms * 0.1
                }
                // The number the user sees: everything this app added between having the frame
                // and showing it. Averaged the same way as decode, so a single slow frame does
                // not make the readout jump.
                inFlightArrivalNs.pollFirst()?.let { arrived ->
                    val ms = (now - arrived) / 1_000_000.0
                    latencyMsEma = if (renderedFrames == 0L) ms else latencyMsEma * 0.9 + ms * 0.1
                }
                if (renderedFrames == 0L) onFirstFrame()
                renderedFrames++
                if (windowStartNs == 0L) windowStartNs = now
                windowFrames++
                if (now - windowStartNs >= 1_000_000_000L) {
                    fps = windowFrames
                    windowFrames = 0
                    windowStartNs = now
                }
                publishStats()
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                onError(e.diagnosticInfo ?: e.message ?: "decoder error")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                // 800×480 is what every known unit sends; nothing to do but note it happened.
            }
        }, h)
        c.configure(format, surface, null, 0)
        c.start()
    }

    /**
     * Hand one access unit to the decoder. Thread-safe; returns immediately.
     *
     * [arrivedNanos] is when this frame's data finished arriving, taken by the caller at the
     * moment of receipt. It is carried all the way to the render callback so the delay the user
     * is shown covers the queue wait as well as the decode — the two are indistinguishable from
     * the outside, and a frame that sat in a queue is exactly as stale as one that decoded slowly.
     */
    fun submit(au: ByteArray, arrivedNanos: Long = System.nanoTime()) {
        handler?.post {
            pending.add(Queued(au, arrivedNanos))
            if (pending.size > MAX_PENDING) {
                // Latency that accumulates is never recovered: drop to the newest and rejoin at
                // an IDR rather than let the queue deepen.
                droppedAus += pending.size - 1
                val newest = pending.last
                pending.clear()
                pending.add(newest)
                awaitIdr = true
            }
            feed()
        }
    }

    /** Called on stream discontinuity (packet loss): rejoin at the next clean IDR. */
    fun requestIdrResync() {
        handler?.post { awaitIdr = true }
    }

    private fun feed() {
        val c = codec ?: return
        while (freeInputs.isNotEmpty() && pending.isNotEmpty()) {
            val queued = pending.poll()
            val au = queued.au
            if (awaitIdr) {
                if (containsIdr(au)) awaitIdr = false
                else {
                    droppedAus++
                    publishStats()
                    continue
                }
            }
            val index = freeInputs.poll()
            val buffer = c.getInputBuffer(index) ?: continue
            buffer.clear()
            buffer.put(au)
            submitTimesNs.add(System.nanoTime())
            inFlightArrivalNs.add(queued.arrivedNanos)
            c.queueInputBuffer(index, 0, au.size, System.nanoTime() / 1000, 0)
        }
        publishStats()
    }

    private fun publishStats() {
        stats = Stats(
            fps, renderedFrames, droppedAus, pending.size,
            decodeMsEma.toInt(), latencyMsEma.toInt(),
        )
    }

    fun stop() {
        // The queues are only ever touched on the handler thread, so teardown happens there too.
        val h = handler
        val t = thread
        val c = codec
        handler = null
        thread = null
        codec = null
        h?.post {
            try {
                c?.stop()
            } catch (_: IllegalStateException) {
            }
            c?.release()
            pending.clear()
            freeInputs.clear()
            submitTimesNs.clear()
            inFlightArrivalNs.clear()
            t?.quitSafely()
        }
    }

    private companion object {
        const val WIDTH = 800
        const val HEIGHT = 480
        const val MAX_PENDING = 2
    }
}
