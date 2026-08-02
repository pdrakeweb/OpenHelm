package dev.openhelm.app.rrc

import android.util.Log
import dev.openhelm.app.di.AppScope
import dev.openhelm.app.net.WifiNetworkBinder
import dev.openhelm.app.net.WifiUnavailableException
import dev.openhelm.protocol.MfdEndpoint
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    /** No endpoint chosen (or the last connection was given up on — see [RrcClient.consumeLoss]). */
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
 * **Resilience rules, in order:**
 *
 * 1. *No failure kills the loop.* Every exception a connection attempt can produce — including the
 *    ones nobody predicted — degrades into a retry with backoff, never into a dead coroutine. The
 *    catch is deliberately `Exception`, not `IOException`: the one time this mattered, the escape
 *    was an `IllegalArgumentException` from an out-of-range port, and it froze the app at
 *    "Connecting" until the process was killed.
 * 2. *Retries are bounded.* After [MAX_ATTEMPTS] consecutive failures the connection is treated as
 *    permanently broken: the loop records the reason (readable once via [consumeLoss]), returns
 *    the state to [ConnectionState.Idle], and exits — which hands the user back to the connect
 *    screen, where scanning resumes. Retrying a dead address forever just pins the user to a
 *    reconnect screen that will never deliver.
 * 3. *A stalled peer is a failure.* The MFD never speaks on this socket, so the reader only
 *    detects an orderly close; a half-open connection (AP power-cycled, phone walked out of range)
 *    leaves writes blocking silently for TCP-retransmit timescales. A write watchdog closes the
 *    socket if any single write is in flight longer than [WRITE_STALL_MS], turning the invisible
 *    hang into an ordinary reconnect.
 */
@Singleton
class RrcClient @Inject constructor(
    private val wifi: WifiNetworkBinder,
    @AppScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Set when the loop gives up (rule 2 above); read-and-cleared by the ViewModel. */
    private val lastLoss = AtomicReference<String?>(null)

    /**
     * The reason the last connection was abandoned as permanently broken, or null. Reading it
     * clears it: the loss is a one-time event to surface, not a lasting state.
     */
    fun consumeLoss(): String? = lastLoss.getAndSet(null)

    private var connectionJob: Job? = null

    @Volatile
    private var currentSocket: Socket? = null

    /**
     * Outbound frames. Small and lossy on purpose: when the writer stalls, old key events are
     * worse than no key events, so the oldest are discarded first.
     */
    @Volatile
    private var frames = Channel<ByteArray>(FRAME_QUEUE, BufferOverflow.DROP_OLDEST)

    /** Nanotime when the in-flight write started; 0 when no write is in flight. */
    @Volatile
    private var writeStartedNs = 0L

    @Volatile
    private var writeStalled = false

    /** Start (or restart) the connection loop against [endpoint]. */
    fun connect(endpoint: MfdEndpoint) {
        lastLoss.set(null)
        val previous = connectionJob
        previous?.cancel()
        // Close the socket the previous loop may be *blocked on*. Cancellation cannot interrupt
        // blocking socket I/O, so without this the join below could sit out a full connect
        // timeout — switching displays would appear to hang for up to 45 seconds.
        closeQuietly()
        connectionJob = scope.launch(Dispatchers.IO) {
            previous?.join()
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
            } catch (e: IllegalArgumentException) {
                // A malformed endpoint (bad host or port) is as unreachable as a dead one.
            }
        }
        null
    }

    /** Stop the loop and return to [ConnectionState.Idle]. */
    fun disconnect() {
        val job = connectionJob
        connectionJob = null
        job?.cancel()
        closeQuietly()
        wifi.unbind()
        lastLoss.set(null)
        _state.value = ConnectionState.Idle
        // The cancelled loop cannot be interrupted mid-statement: it may still write a final
        // Reconnecting after the Idle above (it checks isActive first, but the check-then-write
        // pair is not atomic). Re-asserting Idle after the loop has fully unwound closes that
        // window — unless a new connect() has taken over in the meantime, in which case the new
        // loop owns the state.
        if (job != null) {
            scope.launch {
                job.join()
                if (connectionJob == null) _state.value = ConnectionState.Idle
            }
        }
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
                // The first attempt is generous — slow associations on boat Wi-Fi are real, and a
                // tight watchdog aborts connects that would have succeeded. Retries are shorter:
                // by then the network has already been up once, and the retry budget (MAX_ATTEMPTS
                // × timeout) is the ceiling on how long a user can be stuck watching
                // "Reconnecting" before being handed back to the connect screen.
                val timeout = if (attempt == 0) CONNECT_TIMEOUT_MS else RETRY_CONNECT_TIMEOUT_MS
                socket.connect(InetSocketAddress(endpoint.host, endpoint.rrcPort), timeout)

                drainStale()
                attempt = 0
                _state.value = ConnectionState.Connected(endpoint)
                pumpUntilFailure(socket)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WifiUnavailableException) {
                e.message ?: "Wi-Fi is not available"
            } catch (e: Exception) {
                // Everything else — IOException, but also whatever nobody predicted. An unexpected
                // exception must degrade into a retry, never kill this loop: the loop IS the
                // recovery mechanism, and a dead loop freezes the UI at a state no input can leave.
                Log.w(TAG, "Connection attempt failed", e)
                e.message ?: e.javaClass.simpleName
            } finally {
                closeQuietly()
            }
            attempt++
            if (attempt > MAX_ATTEMPTS) {
                // Rule 2: permanently broken. Record why, go Idle, hand back to the connect
                // screen. The loss reason must be set before the state, so an observer that
                // reacts to Idle finds it already there.
                lastLoss.set(reason)
                if (currentCoroutineContext().isActive) {
                    wifi.unbind()
                    _state.value = ConnectionState.Idle
                }
                return
            }
            if (!currentCoroutineContext().isActive) return
            _state.value = ConnectionState.Reconnecting(endpoint, reason)
            delay(backoffMs(attempt))
        }
    }

    /**
     * Runs until the connection dies, then returns the reason. Three ways it dies: the reader
     * notices the MFD closing the socket, a write fails outright, or the write watchdog closes the
     * socket under a write that has been blocking too long ([WRITE_STALL_MS]) — the half-open
     * case, which otherwise shows "Connected" while every key press silently vanishes.
     */
    private suspend fun pumpUntilFailure(socket: Socket): String = try {
        writeStalled = false
        writeStartedNs = 0L
        coroutineScope {
            launch(Dispatchers.IO) {
                val input = socket.getInputStream()
                val scratch = ByteArray(64)
                while (input.read(scratch) != -1) {
                    // The protocol has no replies; anything read is ignored.
                }
                throw IOException("Connection closed by the display")
            }
            launch {
                // The write watchdog. It closes the socket rather than throwing directly: the
                // writer is *blocked inside write()* and only the close can unblock it — a
                // cancellation would leave this scope waiting on the blocked writer forever.
                while (true) {
                    delay(WATCHDOG_POLL_MS)
                    val started = writeStartedNs
                    if (started != 0L && System.nanoTime() - started >= WRITE_STALL_MS * 1_000_000L) {
                        writeStalled = true
                        closeQuietly()
                    }
                }
            }
            val output = socket.getOutputStream()
            try {
                for (frame in frames) {
                    writeStartedNs = System.nanoTime()
                    output.write(frame)
                    output.flush()
                    writeStartedNs = 0L
                }
            } catch (e: IOException) {
                // Close before rethrowing: the reader is blocked in read() and coroutineScope
                // will not finish tearing down until the close unblocks it.
                closeQuietly()
                throw e
            }
            error("frame channel closed unexpectedly")
        }
    } catch (e: IOException) {
        // The frame being written is dropped with the socket. Do not retry it: by the time a
        // reconnect succeeds it would be a stale input, and re-sending old key events to a
        // chartplotter is worse than losing them.
        if (writeStalled) "Send timed out — the display stopped responding"
        else e.message ?: e.javaClass.simpleName
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
        const val TAG = "OpenHelm"
        const val FRAME_QUEUE = 64
        const val CONNECT_TIMEOUT_MS = 45_000
        const val RETRY_CONNECT_TIMEOUT_MS = 20_000
        const val PROBE_TIMEOUT_MS = 3_000

        /**
         * Consecutive failures before the connection is declared permanently broken. With the
         * backoff sequence (1s, 2s, 4s, 8s, 15s) and the retry connect timeout, the worst case
         * from first failure to giving up is a couple of minutes of visible "Reconnecting" — long
         * enough to ride out an AP reboot, short enough that a display that is actually gone
         * returns the user to the connect screen rather than pinning them to a spinner.
         */
        const val MAX_ATTEMPTS = 5
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 15_000L

        /** A single write blocked this long means the peer is gone, whatever TCP still hopes. */
        const val WRITE_STALL_MS = 5_000L
        const val WATCHDOG_POLL_MS = 1_000L
    }
}
