package dev.openhelm.app.video

import android.net.Network
import android.view.Surface
import dev.openhelm.app.di.AppScope
import dev.openhelm.app.net.WifiNetworkBinder
import dev.openhelm.app.net.WifiUnavailableException
import dev.openhelm.protocol.video.RtpH264Depacketizer
import dev.openhelm.protocol.video.RtpPacket
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
enum class RtpTransport { UDP, TCP_INTERLEAVED }

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

    fun start(rtspUrl: String, surface: Surface, transport: RtpTransport) {
        val previous = job
        job = scope.launch(Dispatchers.IO) {
            previous?.cancelAndJoin()
            var attempt = 0
            while (currentCoroutineContext().isActive) {
                _state.value = VideoState.Connecting
                val reason = try {
                    runPipeline(rtspUrl, surface, transport)
                } catch (e: WifiUnavailableException) {
                    e.message ?: "Wi-Fi unavailable"
                } catch (e: IOException) {
                    e.message ?: e.javaClass.simpleName
                }
                closeSockets()
                attempt++
                _state.value = VideoState.Failed(reason)
                delay((RETRY_BACKOFF_MS * attempt).coerceAtMost(MAX_BACKOFF_MS))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        closeSockets()
        _state.value = VideoState.Idle
        _stats.value = VideoStats()
    }

    /** Runs until the stream fails; returns the reason. */
    private suspend fun runPipeline(rtspUrl: String, surface: Surface, transport: RtpTransport): String {
        val network = wifi.bind()

        val uri = URI(rtspUrl)
        val port = if (uri.port > 0) uri.port else 554
        val socket = network.socketFactory.createSocket()
        rtspSocket = socket
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(uri.host, port), CONNECT_TIMEOUT_MS)

        val session = RtspSession(socket, rtspUrl)
        val track = session.describe()

        var discontinuities = 0L
        val decoder = H264Decoder(
            onFirstFrame = { _state.value = VideoState.Streaming },
            onError = { /* surfaced through the stats/state below when the loop breaks */ },
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
                    session.setupUdp(track.control, rtp.localPort)
                    session.play()
                    // rtcp is bound so the port pair is honest, but nothing reads it: there is
                    // no receiver report worth sending on a one-hop LAN.
                    rtcp.close()

                    coroutineScope {
                        launch { keepaliveLoop(session) }
                        launch { statsLoop(decoder, transport) { discontinuities } }
                        receiveUdp(rtp, track.payloadType, depacketizer, decoder)
                    }
                }

                RtpTransport.TCP_INTERLEAVED -> {
                    session.setupInterleaved(track.control)
                    session.play()
                    coroutineScope {
                        launch { keepaliveLoop(session) }
                        launch { statsLoop(decoder, transport) { discontinuities } }
                        launch(Dispatchers.IO) {
                            session.readInterleaved { datagram ->
                                val packet = RtpPacket.parse(datagram)
                                if (packet != null && packet.payloadType == track.payloadType) {
                                    depacketizer.feed(packet)?.let { au ->
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
        payloadType: Int,
        depacketizer: RtpH264Depacketizer,
        decoder: H264Decoder,
    ): Nothing {
        val buffer = ByteArray(65_536)
        val datagram = DatagramPacket(buffer, buffer.size)
        socket.soTimeout = RTP_STALL_TIMEOUT_MS
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                socket.receive(datagram)
            } catch (e: SocketTimeoutException) {
                throw IOException("No video data for ${RTP_STALL_TIMEOUT_MS / 1000}s")
            }
            val packet = RtpPacket.parse(datagram.data, datagram.length) ?: continue
            if (packet.payloadType != payloadType) continue
            depacketizer.feed(packet)?.let { decoder.submit(it) }
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
            val rtp = DatagramSocket(null)
            try {
                rtp.reuseAddress = false
                rtp.bind(InetSocketAddress(0))
                val port = rtp.localPort
                val even = if (port % 2 == 0) port else port + 1
                if (even != port) {
                    rtp.close()
                    val rtpEven = DatagramSocket(null)
                    rtpEven.bind(InetSocketAddress(even))
                    val rtcp = DatagramSocket(null)
                    rtcp.bind(InetSocketAddress(even + 1))
                    network.bindSocket(rtpEven)
                    network.bindSocket(rtcp)
                    rtpEven.receiveBufferSize = RTP_RECV_BUFFER
                    return rtpEven to rtcp
                }
                val rtcp = DatagramSocket(null)
                rtcp.bind(InetSocketAddress(port + 1))
                network.bindSocket(rtp)
                network.bindSocket(rtcp)
                rtp.receiveBufferSize = RTP_RECV_BUFFER
                return rtp to rtcp
            } catch (e: IOException) {
                lastError = e
                rtp.close()
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
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
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
