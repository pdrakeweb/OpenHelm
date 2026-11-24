package dev.openhelm.app.video

import android.net.Network
import android.util.Log
import android.view.Surface
import dev.openhelm.app.di.AppScope
import dev.openhelm.app.net.SocketOwner
import dev.openhelm.app.net.WifiNetworkBinder
import dev.openhelm.app.net.WifiUnavailableException
import dev.openhelm.protocol.MfdEndpoint
import dev.openhelm.protocol.video.RtpH264Depacketizer
import dev.openhelm.protocol.video.RtpPacket
import dev.openhelm.protocol.video.containsIdr
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How RTP reaches us. UDP is the only transport a real display can serve — a TCP-interleaved
 * request makes it accept the session and then deliver nothing, forever. Interleaved mode exists
 * solely for the simulator viewed from an Android emulator, whose NAT drops inbound UDP.
 */
enum class RtpTransport(val label: String) {
    UDP("UDP"),
    TCP_INTERLEAVED("TCP (testing only)"),
}

sealed interface VideoState {
    data object Idle : VideoState
    data object Connecting : VideoState

    /** Frames are on the glass. */
    data object Streaming : VideoState
    data class Failed(val reason: String) : VideoState
}

/** Numbers for the overlay; refreshed roughly once a second while streaming. */
data class VideoStats(
    val fps: Int = 0,
    val rendered: Long = 0,
    val dropped: Long = 0,
    val discontinuities: Long = 0,
    val queueDepth: Int = 0,
    val decodeMs: Int = 0,
    /**
     * How far behind the live picture is, in milliseconds, measured from a frame's data being
     * complete here to that frame reaching the glass. Zero until the first frame renders.
     *
     * Covers this app's whole contribution — queue wait, decode, render — on a single clock, so
     * it is exact. It does **not** include the display's own capture-and-encode time, which is
     * unmeasurable from here without the two devices agreeing on the time of day.
     */
    val latencyMs: Int = 0,
    val transport: RtpTransport = RtpTransport.UDP,
)

/**
 * The whole video path: RTSP negotiation, RTP reception, depacketization, decode, Surface — with
 * nothing between the socket and the decoder that holds frames. `docs/protocol.md` §2 has the
 * video wire facts; `tests/11-latency.md` has why owning this pipeline rather than handing the
 * URL to a stock player is the entire point of the app — a stock player's own buffering was
 * ~4.6 s of the 6-7 s lag this replaces, and it exposes no knob to tune it.
 *
 * **Resilience rules**, matching [dev.openhelm.app.rrc.RrcClient]'s where they apply:
 *
 * - *No failure kills the loop.* Any exception a pipeline attempt can produce — socket, RTSP,
 *   `MediaCodec` configuration, anything unforeseen — degrades into a retry with backoff. The
 *   catch is `Exception`, not `IOException`; the loop is the recovery mechanism and must outlive
 *   every failure it exists to recover from.
 * - *A dead decoder is a dead pipeline.* `MediaCodec` errors close the sockets, which fails the
 *   receive loop and restarts the pipeline. Without that, RTP keeps feeding a corpse while the
 *   state still says Streaming over a frozen frame — the exact stale-chart hazard the UI's
 *   overlay exists to prevent.
 * - *Video retries forever* (capped backoff), unlike the control channel. Losing video degrades
 *   the session to remote-only, which is a first-class mode; only losing *control* ends it.
 * - *Streaming means frames are arriving.* Packets flowing is not the same thing: a decoder
 *   waiting on a keyframe that never comes renders nothing while every counter upstream of it
 *   looks healthy. A session that goes [RENDER_STALL_MS] without putting a frame on the glass is
 *   failed and restarted, which is also the fastest way to be sent a fresh keyframe.
 * - *A pipeline owns its sockets and its right to speak.* Every [start] gets its own [Run]; only
 *   the current one may publish state, and each closes only sockets it opened. A pipeline still
 *   unwinding after [stop] can neither close its successor's sockets nor report over it.
 */
@Singleton
class VideoPlayer @Inject constructor(
    private val wifi: WifiNetworkBinder,
    @AppScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<VideoState>(VideoState.Idle)
    val state: StateFlow<VideoState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(VideoStats())
    val stats: StateFlow<VideoStats> = _stats.asStateFlow()

    /**
     * Everything belonging to one [start]: the retry loop's job, the sockets it has open, and what
     * its current attempt has learned.
     *
     * These used to be fields on the player, shared by every pipeline it ever ran. A pipeline that
     * had been stopped but was still unwinding (sending its TEARDOWN, releasing its decoder) then
     * shared them with the one started after it — and its cleanup closed the new pipeline's
     * sockets. Stop-then-start within the teardown window is not exotic: it is what the screen
     * turning off and straight back on does, and what toggling mirroring does.
     */
    private class Run {
        lateinit var job: Job

        /** The RTSP connection. Kept apart from [media] so [stop] can leave it open for TEARDOWN. */
        val control = SocketOwner()

        /** The RTP/RTCP datagram sockets (UDP transport only). */
        val media = SocketOwner()

        /** The transport the current attempt opened with — see [stop]. */
        @Volatile
        var transport = RtpTransport.UDP

        /**
         * Set by whatever *deliberately* failed the attempt by closing its sockets — a dead
         * decoder, the frozen-picture watchdog — so the retry loop can report the true cause
         * rather than the "Socket closed" it produced.
         */
        @Volatile
        var failure: String? = null

        /** True once PLAY returned 200 on this attempt — i.e. the server agreed to stream. */
        @Volatile
        var reachedPlay = false

        /** True once at least one access unit reached the decoder on this attempt. */
        @Volatile
        var sawMedia = false

        fun newAttempt(transport: RtpTransport) {
            this.transport = transport
            failure = null
            reachedPlay = false
            sawMedia = false
        }

        /** Fail the attempt in progress: closing the sockets is what unblocks its receive loop. */
        fun fail(reason: String) {
            if (failure == null) failure = reason
            closeSockets()
        }

        fun closeSockets() {
            media.closeAll()
            control.closeAll()
        }

        fun shutdown() {
            media.shutdown()
            control.shutdown()
        }
    }

    /** The pipeline that currently owns [state] and [stats], or null. Guarded by `this`. */
    private var current: Run? = null

    /**
     * The most recently launched pipeline, *including* one already stopped. The next pipeline
     * waits for it to finish — TEARDOWN sent, decoder released — before negotiating its own
     * session, so the display never sees two sessions from this phone and the decoder never finds
     * the Surface still attached to its predecessor. Guarded by `this`.
     */
    private var lastJob: Job? = null

    @Synchronized
    fun start(endpoint: MfdEndpoint, surface: Surface, transport: RtpTransport) {
        // Unblock whatever the previous pipeline is doing — cancellation cannot interrupt a
        // blocking read, and an RTSP server that accepted the connection and then went quiet
        // would otherwise stall the join below.
        current?.let {
            it.job.cancel()
            it.shutdown()
        }
        val previous = lastJob
        val run = Run()
        // Lazy so `current` is set before the loop's first statement can ask whether it is current.
        run.job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            // Bounded: the predecessor's sockets are closed (or about to be — see stop()), so the
            // only thing that could hold it past this is a wedged MediaCodec, and video must not
            // stay down for the life of the process because of one.
            if (previous != null) withTimeoutOrNull(PREDECESSOR_WAIT_MS) { previous.join() }
            var attempt = 0
            var active = transport
            while (currentCoroutineContext().isActive) {
                run.newAttempt(active)
                publish(run, VideoState.Connecting)
                val reason = try {
                    runPipeline(run, endpoint, surface, active)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: WifiUnavailableException) {
                    e.message ?: "Wi-Fi unavailable"
                } catch (e: Exception) {
                    // Not just IOException: a codec configuration failure or anything unforeseen
                    // must become a retry, never a dead loop stuck at "Connecting".
                    Log.w(TAG, "Video pipeline attempt failed", e)
                    e.message ?: e.javaClass.simpleName
                }
                run.closeSockets()
                if (!currentCoroutineContext().isActive) return@launch

                // An attempt that put media through was a working stream that has now broken, not
                // another failure in a row: start the backoff over. Without this the counter only
                // ever grew, so after a few blips across a long passage every later one-second
                // dropout cost the full maximum backoff before video even tried to come back.
                if (run.sawMedia) attempt = 0
                attempt++

                // **Transport fallback.** A session that negotiates cleanly and then delivers
                // nothing is the signature failure of asking for the wrong transport: a real
                // display accepts a TCP-interleaved SETUP and then never sends a packet, forever.
                // The app cannot know which transport a given display serves, and a preference
                // persisted from testing against the simulator will happily brick video against
                // real hardware with no visible cause. So if we got as far as PLAY and no media
                // followed, try the other transport rather than repeating the same failure.
                if (run.reachedPlay && !run.sawMedia) {
                    active = if (active == RtpTransport.UDP) RtpTransport.TCP_INTERLEAVED
                    else RtpTransport.UDP
                    Log.w(TAG, "No media after PLAY — retrying with $active")
                }

                // A deliberate failure closed the sockets, so `reason` is the misleading "Socket
                // closed" — the recorded cause is the truth worth showing.
                publish(run, VideoState.Failed(run.failure ?: reason))
                delay((RETRY_BACKOFF_MS * attempt).coerceAtMost(MAX_BACKOFF_MS))
            }
        }
        current = run
        lastJob = run.job
        _state.value = VideoState.Connecting
        _stats.value = VideoStats()
        run.job.start()
    }

    /**
     * Stop streaming — and tell the display so, rather than just walking away from the socket.
     *
     * This used to close every socket, including the RTSP control connection, in the same breath
     * as cancelling the pipeline job. That closed the connection [runPipeline]'s own `finally`
     * block needed to send RTSP TEARDOWN on, so TEARDOWN either never went out or landed on an
     * already-dead socket and was swallowed as "the point of TEARDOWN is politeness, the socket is
     * closing either way" ([RtspSession.teardown]). The MFD's RTSP server was left holding a
     * session nobody told it to end — which is what turning the phone off (or backgrounding the
     * app; see [dev.openhelm.app.ui.MainViewModel.onBackgrounded]) surfaced as: video working fine
     * before sleep, and failing to reconnect for a while after waking, because the reconnect had to
     * fight a server-side session already in an unknown state instead of a clean one.
     *
     * The fix closes only the UDP sockets first. RTP over UDP keeps the media path entirely
     * separate from the RTSP control connection, so shutting down just the [Run.media] sockets
     * unblocks [receiveUdp]'s blocked `receive()` — the actual reason `stop()` ever needed to close
     * anything synchronously — without touching the socket TEARDOWN needs. The cancelled pipeline
     * then dies with an ordinary `IOException`, unwinds through its own `finally`, and sends
     * TEARDOWN on a control socket that is still open. Only once that has had a bounded chance to
     * happen ([TEARDOWN_GRACE_MS]) does this force-close everything, in case the pipeline is stuck
     * somewhere TEARDOWN cannot reach (a dead peer, a decoder wedged in [H264Decoder.stop]).
     *
     * TCP-interleaved carries RTP *inside* the control connection, so there is no separate socket
     * to close for it — that transport only ever talks to the project's own simulator, never a real
     * display, so it keeps the old close-everything-at-once behaviour.
     */
    @Synchronized
    fun stop() {
        val run = current
        current = null
        // Immediate, and final: with `current` cleared under the lock, the stopped pipeline can no
        // longer publish anything over this, however long its teardown takes.
        _state.value = VideoState.Idle
        _stats.value = VideoStats()
        if (run == null) return
        run.job.cancel()
        if (run.transport == RtpTransport.UDP) run.media.shutdown() else run.shutdown()
        scope.launch {
            withTimeoutOrNull(TEARDOWN_GRACE_MS) { run.job.join() }
            run.shutdown()
        }
    }

    /** Publish on behalf of [run] — only while it is still the current pipeline. */
    private fun publish(run: Run, next: VideoState) {
        synchronized(this) {
            if (current === run) _state.value = next
        }
    }

    private fun publish(run: Run, next: VideoStats) {
        synchronized(this) {
            if (current === run) _stats.value = next
        }
    }

    /** Runs until the stream fails; returns the reason. */
    private suspend fun runPipeline(
        run: Run,
        endpoint: MfdEndpoint,
        surface: Surface,
        transport: RtpTransport,
    ): String {
        val network = wifi.bind()

        // Adopted before anything can block on it — and refused outright if stop() already
        // happened while this was waiting for Wi-Fi.
        val socket = run.control.adopt(network.socketFactory.createSocket())
        socket.tcpNoDelay = true
        // Negotiation must never block indefinitely: a server that accepts the TCP connection
        // and then goes quiet would otherwise hang DESCRIBE's read forever — beyond the reach of
        // coroutine cancellation, which cannot interrupt a blocking read.
        socket.soTimeout = RTSP_RESPONSE_TIMEOUT_MS
        socket.connect(InetSocketAddress(endpoint.host, endpoint.rtspPort), CONNECT_TIMEOUT_MS)
        // The address actually connected to — the only peer allowed to put pixels on screen.
        val displayAddress = socket.inetAddress

        Log.i(TAG, "RTSP connected to ${endpoint.host}:${endpoint.rtspPort} (${endpoint.rtspUrl}) via $transport")

        val session = RtspSession(socket, endpoint.rtspUrl)
        val track = session.describe()
        Log.i(TAG, "RTSP DESCRIBE ok: payloadType=${track.payloadType} control=${track.control} " +
            "sps=${track.sps?.size ?: 0}B pps=${track.pps?.size ?: 0}B clock=${track.clockRate}")

        var discontinuities = 0L
        val decoder = H264Decoder(
            onFirstFrame = { publish(run, VideoState.Streaming) },
            onError = { message ->
                // A dead decoder must fail the pipeline. Closing the sockets fails the receive
                // loop, which restarts the pipeline through the ordinary retry path — otherwise
                // the state stays Streaming over a frozen frame and nobody is told.
                run.fail("Video decoder failed — $message")
            },
        )
        val depacketizer = RtpH264Depacketizer(onDiscontinuity = {
            // A gap means the frame would be torn: never display it, rejoin at the next IDR.
            discontinuities++
            decoder.requestIdrResync()
        })

        try {
            decoder.start(surface, track.sps, track.pps)

            when (transport) {
                RtpTransport.UDP -> {
                    val (rtp, rtcp) = openUdpPair(network)
                    try {
                        run.media.adopt(rtp)
                        run.media.adopt(rtcp)
                    } catch (e: IOException) {
                        // Stopped while opening: whichever was not adopted is still ours to close.
                        rtp.close()
                        rtcp.close()
                        throw e
                    }
                    session.setupUdp(track.control, rtp.localPort)
                    Log.i(TAG, "RTSP SETUP ok (udp): session=${session.sessionId} " +
                        "timeout=${session.timeoutSeconds}s clientPorts=${rtp.localPort}-${rtp.localPort + 1}")
                    session.play()
                    run.reachedPlay = true
                    Log.i(TAG, "RTSP PLAY ok — awaiting RTP on udp/${rtp.localPort} from $displayAddress")

                    // The RTCP socket is held open for the life of the session. It used to be
                    // closed here, on the reasoning that nothing reads receiver reports on a
                    // one-hop LAN — but RTCP is also where the *server* sends its sender reports,
                    // and a closed port answers those with ICMP port-unreachable. Whether a server
                    // treats that as "client gone" is server-specific, and the display's server is
                    // not one we can interrogate. Holding the port costs one idle socket.
                    coroutineScope {
                        launch { keepaliveLoop(session) }
                        launch { statsLoop(run, decoder, transport, depacketizer) { discontinuities } }
                        receiveUdp(run, rtp, displayAddress, track.payloadType, depacketizer, decoder)
                    }
                }

                RtpTransport.TCP_INTERLEAVED -> {
                    session.setupInterleaved(track.control)
                    session.play()
                    run.reachedPlay = true
                    // From here the socket carries the media stream itself: a longer silence
                    // means the stream stalled, and the timeout turns that into a visible
                    // failure instead of an eternal freeze.
                    socket.soTimeout = RTP_STALL_TIMEOUT_MS
                    coroutineScope {
                        launch { keepaliveLoop(session) }
                        launch { statsLoop(run, decoder, transport, depacketizer) { discontinuities } }
                        launch(Dispatchers.IO) {
                            session.readInterleaved { datagram ->
                                val packet = RtpPacket.parse(datagram)
                                if (packet != null && packet.payloadType == track.payloadType) {
                                    depacketizer.feed(packet)?.let { au ->
                                        if (!run.sawMedia) {
                                            run.sawMedia = true
                                            Log.i(TAG, "First access unit assembled (interleaved): " +
                                                "${au.size} bytes")
                                        }
                                        decoder.submit(au, System.nanoTime())
                                    }
                                }
                            }
                        }
                    }
                }
            }
            error("pipeline scope exited without an exception")
        } catch (e: IOException) {
            return e.message ?: e.javaClass.simpleName
        } finally {
            decoder.stop()
            // TEARDOWN waits for its answer, and on the way out of a *failed* stream the likeliest
            // reason for the failure is that the display is no longer there to give one. Left on
            // the negotiation timeout, every retry of a dead link sat here for a further
            // [RTSP_RESPONSE_TIMEOUT_MS] before it could even start backing off.
            try {
                socket.soTimeout = TEARDOWN_RESPONSE_TIMEOUT_MS
            } catch (_: IOException) {
                // Already closed; teardown() below will find that out for itself.
            }
            session.teardown()
        }
    }

    private suspend fun receiveUdp(
        run: Run,
        socket: DatagramSocket,
        expectedSource: InetAddress,
        payloadType: Int,
        depacketizer: RtpH264Depacketizer,
        decoder: H264Decoder,
    ): Nothing {
        val buffer = ByteArray(65_536)
        val datagram = DatagramPacket(buffer, buffer.size)
        socket.soTimeout = RTP_STALL_TIMEOUT_MS
        var received = 0L
        var fromOtherSource = 0L
        var loggedFirst = false
        var loggedForeign: InetAddress? = null
        var lastFromDisplayNs = System.nanoTime()
        // Say which of the two silences this is. "Nothing at all" and "packets arriving but all
        // rejected" have completely different causes, and the difference was previously
        // invisible — the loop simply produced no video either way.
        fun stalled() = IOException(
            if (received == 0L && fromOtherSource == 0L) {
                "No video data for ${RTP_STALL_TIMEOUT_MS / 1000}s"
            } else {
                "Video stalled after $received packets ($fromOtherSource from another source)"
            },
        )
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                socket.receive(datagram)
            } catch (e: SocketTimeoutException) {
                throw stalled()
            }
            // DatagramPacket carries the PREVIOUS receive's length into the next one, so without
            // this reset each receive can take no more bytes than the last packet did and the
            // usable buffer ratchets down toward the smallest packet seen — silently truncating
            // every packet after the first small one, which corrupts the bitstream rather than
            // failing loudly. This is the single easiest way to get a stream that "connects" and
            // never decodes.
            val length = datagram.length
            datagram.setLength(buffer.size)

            // Anything on the Wi-Fi can aim datagrams at this port, and the port is observable.
            // Only the display we negotiated with may put pixels on the glass — a chart is the
            // one surface in this app that must never be spoofable. A rejection is *counted and
            // logged once* rather than dropped in silence: a filter that discards every packet
            // looks exactly like a dead network, and that ambiguity costs hours.
            if (datagram.address != expectedSource) {
                fromOtherSource++
                if (loggedForeign != datagram.address) {
                    loggedForeign = datagram.address
                    Log.w(TAG, "Ignoring RTP from ${datagram.address} — expected $expectedSource")
                }
                // The socket timeout measures silence on the *port*, and a rejected packet is not
                // silence: anything else on the Wi-Fi sending here would keep a dead stream
                // looking alive indefinitely. The stall that matters is the display's.
                if (System.nanoTime() - lastFromDisplayNs >= RTP_STALL_TIMEOUT_MS * 1_000_000L) {
                    throw stalled()
                }
                continue
            }
            received++
            lastFromDisplayNs = System.nanoTime()
            if (!loggedFirst) {
                loggedFirst = true
                Log.i(TAG, "First RTP packet: $length bytes from ${datagram.address}")
            }
            val packet = RtpPacket.parse(datagram.data, length) ?: continue
            if (packet.payloadType != payloadType) continue
            depacketizer.feed(packet)?.let { au ->
                if (!run.sawMedia) {
                    run.sawMedia = true
                    Log.i(TAG, "First access unit assembled: ${au.size} bytes (idr=${containsIdr(au)})")
                }
                // Stamp the frame the instant it is complete here. Everything after this point is
                // delay this app is accountable for, and that is what the readout reports.
                decoder.submit(au, System.nanoTime())
            }
        }
    }

    private suspend fun keepaliveLoop(session: RtspSession): Nothing {
        while (true) {
            delay((session.timeoutSeconds * 1000L / 2).coerceAtLeast(5_000L))
            session.keepalive()
        }
    }

    private suspend fun statsLoop(
        run: Run,
        decoder: H264Decoder,
        transport: RtpTransport,
        depacketizer: RtpH264Depacketizer,
        discontinuities: () -> Long,
    ): Nothing {
        var ticks = 0
        var lastRendered = 0L
        var lastProgressNs = System.nanoTime()
        while (true) {
            val d = decoder.stats

            // The frozen-picture watchdog. The receive loop only notices *silence*; this notices
            // a stream that keeps arriving and never becomes a picture — a decoder waiting, after
            // one lost packet, for a keyframe the display is in no hurry to send, or one that has
            // quietly stopped producing output. Either way the last frame sits on the glass
            // looking live. Failing the attempt puts the honest overlay up and renegotiates,
            // which is also what gets a new keyframe sent.
            val now = System.nanoTime()
            if (d.rendered != lastRendered) {
                lastRendered = d.rendered
                lastProgressNs = now
            } else if (run.sawMedia && now - lastProgressNs >= RENDER_STALL_MS * 1_000_000L) {
                Log.w(TAG, "No frame rendered for ${RENDER_STALL_MS / 1000}s — restarting video " +
                    "(${depacketizer.diagnostics()})")
                run.fail("Picture frozen for ${RENDER_STALL_MS / 1000}s")
                lastProgressNs = now
            }

            publish(run, VideoStats(
                fps = d.fps,
                rendered = d.rendered,
                dropped = d.dropped,
                discontinuities = discontinuities(),
                queueDepth = d.queueDepth,
                decodeMs = d.decodeMs,
                latencyMs = d.latencyMs,
                transport = transport,
            ))
            // Say what the far end is doing, not just what we ended up with. A rising gap count
            // with nothing decoded has several possible causes that look identical on screen, and
            // the depacketizer is the only layer that can tell them apart.
            ticks++
            if (ticks <= 5 || ticks % 15 == 0) {
                Log.i(TAG, "pipeline: ${depacketizer.diagnostics()} | " +
                    "rendered=${d.rendered} dropped=${d.dropped} q=${d.queueDepth} " +
                    "fps=${d.fps} decode=${d.decodeMs}ms")
            }
            delay(1_000)
        }
    }

    /**
     * An RTP/RTCP port pair on consecutive ports with the RTP one even, as RFC 3550 asks and
     * some servers require. Both are bound to the Wi-Fi [network] — the process-wide bind covers
     * this, but per-socket binding keeps the pipeline correct even if something rebinds the
     * process.
     */
    private fun openUdpPair(network: Network): Pair<DatagramSocket, DatagramSocket> {
        var lastError: IOException? = null
        repeat(20) {
            // Both tracked from birth so the failure path can close whichever exist — an earlier
            // version leaked the re-bound RTP socket when the RTCP bind lost the port race.
            var rtp: DatagramSocket? = null
            var rtcp: DatagramSocket? = null
            try {
                rtp = DatagramSocket(null).apply {
                    reuseAddress = false
                    bind(InetSocketAddress(0))
                }
                if (rtp.localPort % 2 != 0) {
                    val even = rtp.localPort + 1
                    rtp.close()
                    rtp = DatagramSocket(null).apply { bind(InetSocketAddress(even)) }
                }
                val rtcpPort = rtp.localPort + 1
                rtcp = DatagramSocket(null).apply { bind(InetSocketAddress(rtcpPort)) }
                network.bindSocket(rtp)
                network.bindSocket(rtcp)
                rtp.receiveBufferSize = RTP_RECV_BUFFER
                return rtp to rtcp
            } catch (e: IOException) {
                lastError = e
                rtp?.close()
                rtcp?.close()
            }
        }
        throw lastError ?: IOException("Could not allocate an RTP port pair")
    }

    private companion object {
        const val TAG = "OpenHelm"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val RTSP_RESPONSE_TIMEOUT_MS = 15_000
        const val RTP_STALL_TIMEOUT_MS = 10_000
        const val RETRY_BACKOFF_MS = 3_000L
        const val MAX_BACKOFF_MS = 15_000L

        /**
         * How long [stop] waits for the pipeline's own graceful RTSP TEARDOWN before force-closing
         * the control socket out from under it. Generous for a one-hop LAN round trip, short enough
         * that a genuinely dead peer does not make backgrounding feel sluggish.
         */
        const val TEARDOWN_GRACE_MS = 2_000L

        /**
         * How long TEARDOWN itself waits for the display's answer. Inside [TEARDOWN_GRACE_MS], so a
         * stop() normally ends by the pipeline finishing on its own rather than being force-closed.
         */
        const val TEARDOWN_RESPONSE_TIMEOUT_MS = 1_500

        /** How long a new pipeline waits for the one before it to finish unwinding. */
        const val PREDECESSOR_WAIT_MS = 5_000L

        /**
         * How long media may keep arriving without a single frame reaching the glass before the
         * session is restarted. Longer than any keyframe interval a live encoder plausibly uses,
         * so ordinary loss recovery (skip to the next keyframe) always gets its chance first.
         */
        const val RENDER_STALL_MS = 15_000L

        /**
         * Big enough to absorb an IDR burst arriving between two receive() calls, small enough
         * that the OS cannot silently warehouse seconds of video: at 2 Mbit/s this is ~1 frame
         * interval of headroom.
         */
        const val RTP_RECV_BUFFER = 256 * 1024
    }
}
