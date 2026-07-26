package dev.openhelm.app.rrc

import dev.openhelm.app.di.AppScope
import dev.openhelm.app.net.WifiNetworkBinder
import dev.openhelm.app.net.WifiUnavailableException
import dev.openhelm.protocol.MfdEndpoint
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Connection lifecycle, written only by [RrcClient], observed by the UI. */
sealed interface ConnectionState {
    /** No endpoint chosen. */
    data object Idle : ConnectionState

    /** First attempt against [endpoint] in progress. */
    data class Connecting(val endpoint: MfdEndpoint) : ConnectionState

    /** Socket open; frames flow. */
    data class Connected(val endpoint: MfdEndpoint) : ConnectionState

    /** Lost or never established; retrying with backoff. [reason] is for the user. */
    data class Reconnecting(val endpoint: MfdEndpoint, val reason: String) : ConnectionState
}

/**
 * The control-channel client. One TCP socket, one writer coroutine, one frame queue.
 *
 * Failure policy, deliberately boring: a frame that cannot be written is **dropped**, the socket is
 * closed, and the single connection loop backs off and reconnects. Nothing is ever retried
 * per-frame and nothing ever spawns per-failure — a control channel wants the freshest input or
 * none, and unbounded retry machinery is how a client turns one dead socket into a resource storm.
 *
 * The MFD never speaks on this socket; the read side exists only to notice the peer closing.
 */
@Singleton
class RrcClient @Inject constructor(
    private val wifi: WifiNetworkBinder,
    @AppScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var connectionJob: Job? = null

    @Volatile
    private var currentSocket: Socket? = null

    /**
     * Outbound frames. Small and lossy on purpose: when the writer stalls, old key events are
     * worse than no key events, so the oldest are discarded first.
     */
    @Volatile
    private var frames = Channel<ByteArray>(FRAME_QUEUE, BufferOverflow.DROP_OLDEST)

    /** Start (or restart) the connection loop against [endpoint]. */
    fun connect(endpoint: MfdEndpoint) {
        val previous = connectionJob
        connectionJob = scope.launch(Dispatchers.IO) {
            previous?.cancelAndJoin()
            frames = Channel(FRAME_QUEUE, BufferOverflow.DROP_OLDEST)
            runConnection(endpoint)
        }
    }

    /**
     * Try the given endpoints in order and return the first that accepts a control-socket
     * connection within [timeoutMs], or null if none do. Used at launch to pick which remembered
     * display to reconnect to before falling back to a scan — bounded and ordered, so priority
     * (most-recent first) is honoured and a long list of dead addresses cannot hang startup.
     *
     * This only probes reachability; it opens and immediately closes each socket and does not
     * start the connection loop. Binds the process to Wi-Fi first, like every other connect path.
     */
    suspend fun firstReachable(
        endpoints: List<MfdEndpoint>,
        timeoutMs: Int = PROBE_TIMEOUT_MS,
    ): MfdEndpoint? = withContext(Dispatchers.IO) {
        // Explicitly on IO: these are blocking socket connects, and callers are UI-scoped
        // coroutines that default to the main dispatcher.
        if (endpoints.isEmpty()) return@withContext null
        val network = try {
            wifi.bind()
        } catch (e: WifiUnavailableException) {
            return@withContext null
        }
        for (endpoint in endpoints) {
            if (!currentCoroutineContext().isActive) return@withContext null
            try {
                network.socketFactory.createSocket().use { socket ->
                    socket.connect(InetSocketAddress(endpoint.host, endpoint.rrcPort), timeoutMs)
                }
                return@withContext endpoint
            } catch (e: IOException) {
                // Unreachable or refused — try the next remembered display.
            }
        }
        null
    }

    /** Stop the loop and return to [ConnectionState.Idle]. */
    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        closeQuietly()
        wifi.unbind()
        _state.value = ConnectionState.Idle
    }

    /**
     * Queue one wire frame. Never blocks, never fails loudly: when there is no connection the
     * frame is simply dropped, which is the correct fate for a stale key press.
     */
    fun send(frame: ByteArray) {
        frames.trySend(frame)
    }

    private suspend fun runConnection(endpoint: MfdEndpoint) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            if (attempt == 0) _state.value = ConnectionState.Connecting(endpoint)
            val reason: String = try {
                // F1b: re-assert the Wi-Fi binding on every attempt, not just the first.
                val network = wifi.bind()
                val socket = network.socketFactory.createSocket()
                currentSocket = socket
                socket.tcpNoDelay = true
                socket.keepAlive = true
                // Generous on purpose: slow associations on boat Wi-Fi are real, and a tight
                // watchdog aborts connects that would have succeeded.
                socket.connect(InetSocketAddress(endpoint.host, endpoint.rrcPort), CONNECT_TIMEOUT_MS)

                drainStale()
                attempt = 0
                _state.value = ConnectionState.Connected(endpoint)
                pumpUntilFailure(socket)
            } catch (e: WifiUnavailableException) {
                e.message ?: "Wi-Fi is not available"
            } catch (e: IOException) {
                e.message ?: e.javaClass.simpleName
            } finally {
                closeQuietly()
            }
            attempt++
            _state.value = ConnectionState.Reconnecting(endpoint, reason)
            delay(backoffMs(attempt))
        }
    }

    /**
     * Runs until the connection dies, then returns the reason. The reader exists only to detect
     * the MFD closing the socket; when either side fails, [coroutineScope] tears down the other.
     */
    private suspend fun pumpUntilFailure(socket: Socket): String = try {
        coroutineScope {
            launch(Dispatchers.IO) {
                val input = socket.getInputStream()
                val scratch = ByteArray(64)
                while (input.read(scratch) != -1) {
                    // The protocol has no replies; anything read is ignored.
                }
                throw IOException("Connection closed by the display")
            }
            val output = socket.getOutputStream()
            for (frame in frames) {
                output.write(frame)
                output.flush()
            }
            error("frame channel closed unexpectedly")
        }
    } catch (e: IOException) {
        // The frame being written is dropped with the socket. Do not retry it: by the time a
        // reconnect succeeds it would be a stale input, and re-sending old key events to a
        // chartplotter is worse than losing them.
        e.message ?: e.javaClass.simpleName
    }

    /** Anything queued before the connection opened is by definition stale input. */
    private fun drainStale() {
        while (frames.tryReceive().isSuccess) {
            // discard
        }
    }

    private fun closeQuietly() {
        try {
            currentSocket?.close()
        } catch (_: IOException) {
        }
        currentSocket = null
    }

    private fun backoffMs(attempt: Int): Long =
        (INITIAL_BACKOFF_MS shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(MAX_BACKOFF_MS)

    private companion object {
        const val FRAME_QUEUE = 64
        const val CONNECT_TIMEOUT_MS = 45_000
        const val PROBE_TIMEOUT_MS = 3_000
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 15_000L
    }
}
