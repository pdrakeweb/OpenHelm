package dev.openhelm.app.video

import android.net.Network
import android.util.Log
import android.view.Surface
import dev.openhelm.app.di.AppScope
import dev.openhelm.app.net.WifiNetworkBinder
import dev.openhelm.app.net.WifiUnavailableException
import dev.openhelm.protocol.MfdEndpoint
import dev.openhelm.protocol.video.RtpH264Depacketizer
import dev.openhelm.protocol.video.RtpPacket
import java.io.IOException
import dev.openhelm.protocol.video.containsIdr
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
    val transport: RtpTransport = RtpTransport.UDP,
)

/**
 * The whole video path: RTSP negotiation, RTP reception, depacketization, decode, Surface — with
 * nothing between the socket and the decoder that holds frames. See `docs/modernization-plan.md`
 * §3.3 for why owning this pipeline (rather than using a stock player) is the entire point of
 * this app.
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

    private var job: Job? = null

    @Volatile
    private var rtspSocket: Socket? = null

    @Volatile
    private var rtpSocket: DatagramSocket? = null

    @Volatile
    private var rtcpSocket: DatagramSocket? = null

    /** Set by the decoder's error callback so the retry loop can report the true cause. */
    @Volatile
    private var decoderError: String? = null

    /** True once PLAY returned 200 on this attempt — i.e. the server agreed to stream. */
    @Volatile
    private var reachedPlay = false

    /** True once at least one access unit reached the decoder on this attempt. */
    @Volatile
    private var sawMedia = false

    fun start(endpoint: MfdEndpoint, surface: Surface, transport: RtpTransport) {
        val previous = job
        previous?.cancel()
        // Unblock whatever the previous pipeline is doing — cancellation cannot interrupt a
        // blocking read, and an RTSP server that accepted the connection and then went quiet
        // would otherwise stall the join below indefinitely.
        closeSockets()
        job = scope.launch(Dispatchers.IO) {
            previous?.join()
            var attempt = 0
            var active = transport
            while (currentCoroutineContext().isActive) {
                decoderError = null
                reachedPlay = false
                sawMedia = false
                _state.value = VideoState.Connecting
                val reason = try {
                    runPipeline(endpoint, surface, active)
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
                closeSockets()
                attempt++
                if (!currentCoroutineContext().isActive) return@launch

                // **Transport fallback.** A session that negotiates cleanly and then delivers
                // nothing is the signature failure of asking for the wrong transport: a real
                // display accepts a TCP-interleaved SETUP and then never sends a packet, forever.
                // The app cannot know which transport a given display serves, and a preference
                // persisted from testing against the simulator will happily brick video against
                // real hardware with no visible cause. So if we got as far as PLAY and no media
                // followed, try the other transport rather than repeating the same failure.
                if (reachedPlay && !sawMedia) {
                    active = if (active == RtpTransport.UDP) RtpTransport.TCP_INTERLEAVED
                    else RtpTransport.UDP
                    Log.w(TAG, "No media after PLAY — retrying with $active")
                }

                // A decoder failure closed the sockets, so `reason` is the misleading "Socket
                // closed" — the recorded decoder error is the truth worth showing.
                _state.value = VideoState.Failed(decoderError ?: reason)
                delay((RETRY_BACKOFF_MS * attempt).coerceAtMost(MAX_BACKOFF_MS))
            }
        }
    }

    fun stop() {
        val previous = job
        job = null
        previous?.cancel()
        closeSockets()
        _state.value = VideoState.Idle
        _stats.value = VideoStats()
        // The cancelled loop may still write a final Failed on its way out (its isActive check
        // and the write are not atomic). Re-assert Idle once it has fully unwound, unless a new
        // start() owns the state by then.
        if (previous != null) {
            scope.launch {
                previous.join()
                if (job == null) {
                    _state.value = VideoState.Idle
                    _stats.value = VideoStats()
                }
            }
        }
    }

    /** Runs until the stream fails; returns the reason. */
    private suspend fun runPipeline(endpoint: MfdEndpoint, surface: Surface, transport: RtpTransport): String {
        val network = wifi.bind()

        val socket = network.socketFactory.createSocket()
        rtspSocket = socket
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
            onFirstFrame = { _state.value = VideoState.Streaming },
            onError = { message ->
                // A dead decoder must fail the pipeline. Closing the sockets fails the receive
                // loop, which restarts the pipeline through the ordinary retry path — otherwise
                // the state stays Streaming over a frozen frame and nobody is told.
                decoderError = "Video decoder failed — $message"
                closeSockets()
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
                    rtpSocket = rtp
                    rtcpSocket = rtcp
                    session.setupUdp(track.control, rtp.localPort)
                    Log.i(TAG, "RTSP SETUP ok (udp): session=${session.sessionId} " +
                        "timeout=${session.timeoutSeconds}s clientPorts=${rtp.localPort}-${rtp.localPort + 1}")
                    session.play()
                    reachedPlay = true
                    Log.i(TAG, "RTSP PLAY ok — awaiting RTP on udp/${rtp.localPort} from $displayAddress")

                    // The RTCP socket is held open for the life of the session. It used to be
                    // closed here, on the reasoning that nothing reads receiver reports on a
                    // one-hop LAN — but RTCP is also where the *server* sends its sender reports,
                    // and a closed port answers those with ICMP port-unreachable. Whether a server
                    // treats that as "client gone" is server-specific, and the display's server is
                    // not one we can interrogate. Holding the port costs one idle socket.
                    coroutineScope {
                        launch { keepaliveLoop(session) }
                        launch { statsLoop(decoder, transport) { discontinuities } }
                        receiveUdp(rtp, displayAddress, track.payloadType, depacketizer, decoder)
                    }
                }

                RtpTransport.TCP_INTERLEAVED -> {
                    session.setupInterleaved(track.control)
                    session.play()
                    reachedPlay = true
                    // From here the socket carries the media stream itself: a longer silence
                    // means the stream stalled, and the timeout turns that into a visible
                    // failure instead of an eternal freeze.
                    socket.soTimeout = RTP_STALL_TIMEOUT_MS
                    coroutineScope {
                        launch { keepaliveLoop(session) }
                        launch { statsLoop(decoder, transport) { discontinuities } }
                        launch(Dispatchers.IO) {
                            session.readInterleaved { datagram ->
                                val packet = RtpPacket.parse(datagram)
                                if (packet != null && packet.payloadType == track.payloadType) {
                                    depacketizer.feed(packet)?.let { au ->
                                        if (!sawMedia) {
                                            sawMedia = true
                                            Log.i(TAG, "First access unit assembled (interleaved): " +
                                                "${au.size} bytes")
                                        }
                                        decoder.submit(au)
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
            try {
                session.teardown()
            } catch (_: IOException) {
            }
        }
    }

    private suspend fun receiveUdp(
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
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                socket.receive(datagram)
            } catch (e: SocketTimeoutException) {
                // Say which of the two silences this is. "Nothing at all" and "packets arriving
                // but all rejected" have completely different causes, and the difference was
                // previously invisible — the loop simply produced no video either way.
                throw IOException(
                    if (received == 0L && fromOtherSource == 0L) {
                        "No video data for ${RTP_STALL_TIMEOUT_MS / 1000}s"
                    } else {
                        "Video stalled after $received packets " +
                            "($fromOtherSource from another source)"
                    },
                )
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
                continue
            }
            received++
            if (!loggedFirst) {
                loggedFirst = true
                Log.i(TAG, "First RTP packet: $length bytes from ${datagram.address}")
            }
            val packet = RtpPacket.parse(datagram.data, length) ?: continue
            if (packet.payloadType != payloadType) continue
            depacketizer.feed(packet)?.let { au ->
                if (!sawMedia) {
                    sawMedia = true
                    Log.i(TAG, "First access unit assembled: ${au.size} bytes (idr=${containsIdr(au)})")
                }
                decoder.submit(au)
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
        decoder: H264Decoder,
        transport: RtpTransport,
        discontinuities: () -> Long,
    ): Nothing {
        while (true) {
            val d = decoder.stats
            _stats.value = VideoStats(
                fps = d.fps,
                rendered = d.rendered,
                dropped = d.dropped,
                discontinuities = discontinuities(),
                queueDepth = d.queueDepth,
                decodeMs = d.decodeMs,
                transport = transport,
            )
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

    private fun closeSockets() {
        try {
            rtspSocket?.close()
        } catch (_: IOException) {
        }
        rtspSocket = null
        rtpSocket?.close()
        rtpSocket = null
        rtcpSocket?.close()
        rtcpSocket = null
    }

    private companion object {
        const val TAG = "OpenHelm"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val RTSP_RESPONSE_TIMEOUT_MS = 15_000
        const val RTP_STALL_TIMEOUT_MS = 10_000
        const val RETRY_BACKOFF_MS = 3_000L
        const val MAX_BACKOFF_MS = 15_000L

        /**
         * Big enough to absorb an IDR burst arriving between two receive() calls, small enough
         * that the OS cannot silently warehouse seconds of video: at 2 Mbit/s this is ~1 frame
         * interval of headroom.
         */
        const val RTP_RECV_BUFFER = 256 * 1024
    }
}
