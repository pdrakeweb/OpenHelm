package dev.openhelm.app.rrc

import android.util.Log
import dev.openhelm.app.di.AppScope
import dev.openhelm.app.net.SocketOwner
import dev.openhelm.app.net.TcpLiveness
import dev.openhelm.app.net.WifiNetworkBinder
import dev.openhelm.app.net.WifiUnavailableException
import dev.openhelm.protocol.MfdEndpoint
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
 * 2. *Retries are bounded — generously, once a session has worked.* A connection that has never
 *    been established gets [MAX_ATTEMPTS] tries: a wrong address should hand the user back to the
 *    connect screen promptly. A session that *was* working is a different thing. Giving it up
 *    takes the remote screen away, and with it the last chart picture — which on a sailboat is
 *    still a useful reference minutes after the link drops, as long as it is plainly marked as
 *    old. So an established session keeps retrying for [OUTAGE_GIVE_UP_MS] before the loop
 *    records the reason (readable once via [consumeLoss]), returns to [ConnectionState.Idle] and
 *    exits.
 * 3. *A stalled peer is a failure.* The MFD never speaks on this socket, so the reader only
 *    detects an orderly close; a peer that simply vanishes (display powered off behind a separate
 *    access point, phone walked out of range) is invisible to plain TCP for minutes to hours.
 *    [TcpLiveness] shortens that to seconds for both the idle link and the unacknowledged write,
 *    and a write watchdog closes the socket if any single write is in flight longer than
 *    [WRITE_STALL_MS] as the backstop on a device that refuses those socket options.
 * 4. *A loop owns its socket and its right to speak.* Each call to [connect] gets its own
 *    [SocketOwner] and its own frame queue, and may publish to [state] only while it is the
 *    current loop. A loop that has been replaced or disconnected can therefore neither close its
 *    successor's socket nor overwrite its successor's state, however long it takes to unwind.
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

    /** One connection loop: its job, and the only handle for unblocking its socket I/O. */
    private class Link(val job: Job, val sockets: SocketOwner)

    /** The loop that currently owns [state], or null. Guarded by `this`. */
    private var current: Link? = null

    /**
     * The most recently launched loop, *including* one already disconnected. A new loop waits for
     * it to finish unwinding before opening its own socket, so two loops never talk to the display
     * at once. Guarded by `this`.
     */
    private var lastJob: Job? = null

    /**
     * Outbound frames. Small and lossy on purpose: when the writer stalls, old key events are
     * worse than no key events, so the oldest are discarded first.
     */
    @Volatile
    private var frames = Channel<ByteArray>(FRAME_QUEUE, BufferOverflow.DROP_OLDEST)

    /** Start (or restart) the connection loop against [endpoint]. */
    @Synchronized
    fun connect(endpoint: MfdEndpoint) {
        lastLoss.set(null)
        // Cancels the previous loop *and closes the socket it may be blocked on*. Cancellation
        // cannot interrupt blocking socket I/O, so without the close the join below could sit out
        // a full connect timeout — switching displays would appear to hang for up to 45 seconds.
        retireCurrent()
        val previous = lastJob
        val sockets = SocketOwner()
        val queue = Channel<ByteArray>(FRAME_QUEUE, BufferOverflow.DROP_OLDEST)
        frames = queue
        // Lazy so the Link exists before the loop's first statement can ask whether it is current.
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            previous?.join()
            runConnection(coroutineContext.job, endpoint, sockets, queue)
        }
        current = Link(job, sockets)
        lastJob = job
        _state.value = ConnectionState.Connecting(endpoint)
        job.start()
    }

    /**
     * Try the given endpoints in order and return the first that accepts a control-socket
     * connection within [timeoutMs], or null if none do. Used at launch to pick which remembered
     * display to reconnect to before falling back to a scan — bounded and ordered, so priority
     * (most-recent first) is honoured and a long list of dead addresses cannot hang startup.
     *
     * This only probes reachability; it opens and immediately closes each socket and does not
     * start the connection loop. Binds the process to Wi-Fi first, like every other connect path.
     *
     * Never throws (other than for cancellation): callers are UI-scoped coroutines with no
     * exception handler, where an escaped exception is a crashed app. Anything that goes wrong
     * while probing an address simply means that address is not reachable.
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No Wi-Fi — or the platform refusing the network request outright, which it does
            // with an unchecked exception.
            return@withContext null
        }
        for (endpoint in endpoints) {
            if (!currentCoroutineContext().isActive) return@withContext null
            try {
                network.socketFactory.createSocket().use { socket ->
                    socket.connect(InetSocketAddress(endpoint.host, endpoint.rrcPort), timeoutMs)
                }
                return@withContext endpoint
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unreachable, refused, or malformed (bad host or port) — all equally "not this
                // one"; try the next remembered display.
            }
        }
        null
    }

    /** Conflated: any number of [retryNow] calls during one wait amount to one retry. */
    private val retrySignal = Channel<Unit>(Channel.CONFLATED)

    /**
     * Stop waiting and try again immediately. A no-op while connected or idle.
     *
     * The backoff exists to avoid hammering a display that is down; it knows nothing about *why*
     * a retry might suddenly be worth making. The app returning to the foreground is exactly such
     * a reason — the phone has usually just woken, its Wi-Fi has just re-associated, and the user
     * is looking at the screen — and without this they watched "Reconnecting" for up to
     * [MAX_BACKOFF_MS] (or the rest of a connect attempt begun while the radio was still asleep)
     * before anything happened.
     *
     * Also abandons a connect attempt already in flight: one started before whatever changed is
     * as likely to be waiting on a route that no longer exists as to be about to succeed.
     */
    @Synchronized
    fun retryNow() {
        val link = current ?: return
        if (_state.value is ConnectionState.Connected) return
        retrySignal.trySend(Unit)
        link.sockets.closeAll()
    }

    /** Stop the loop and return to [ConnectionState.Idle]. */
    @Synchronized
    fun disconnect() {
        retireCurrent()
        wifi.unbind()
        lastLoss.set(null)
        _state.value = ConnectionState.Idle
    }

    /**
     * Queue one wire frame. Never blocks, never fails loudly: when there is no connection the
     * frame is simply dropped, which is the correct fate for a stale key press.
     */
    fun send(frame: ByteArray) {
        frames.trySend(frame)
    }

    /** Cancel the current loop and unblock whatever it is doing. Caller holds the lock. */
    private fun retireCurrent() {
        val link = current ?: return
        current = null
        link.job.cancel()
        link.sockets.shutdown()
    }

    /**
     * Publish [next] — but only on behalf of the loop that still owns the connection. A loop that
     * has been disconnected or replaced keeps running until its blocking I/O returns, and must not
     * be able to write "Reconnecting" over the Idle (or over the next loop's "Connecting") that
     * replaced it. Taking the same lock as [connect] and [disconnect] makes the check-and-write
     * atomic, which an `isActive` check alone never was.
     */
    private fun publish(owner: Job, next: ConnectionState) {
        synchronized(this) {
            if (current?.job === owner) _state.value = next
        }
    }

    private suspend fun runConnection(
        self: Job,
        endpoint: MfdEndpoint,
        sockets: SocketOwner,
        queue: Channel<ByteArray>,
    ) {
        var attempt = 0
        // True once a connection in this loop has held: from then on this is a session worth
        // waiting for, not an address that may simply be wrong.
        var established = false
        // When the current run of failures began; 0 while the link is up.
        var outageStartNs = 0L
        while (currentCoroutineContext().isActive) {
            if (attempt == 0 && !established) publish(self, ConnectionState.Connecting(endpoint))
            var connectedAtNs = 0L
            val reason: String = try {
                // F1b: re-assert the Wi-Fi binding on every attempt, not just the first.
                val network = wifi.bind()
                // Adopted before anything can block on it, so disconnect() can always reach it —
                // and refused outright if disconnect() already happened while we waited for Wi-Fi.
                val socket = sockets.adopt(network.socketFactory.createSocket())
                socket.tcpNoDelay = true
                socket.keepAlive = true
                // The first attempt is generous — slow associations on boat Wi-Fi are real, and a
                // tight watchdog aborts connects that would have succeeded. Retries are shorter:
                // by then the network has already been up once, and the retry budget (MAX_ATTEMPTS
                // × timeout) is the ceiling on how long a user can be stuck watching
                // "Reconnecting" before being handed back to the connect screen.
                val timeout = if (attempt == 0) CONNECT_TIMEOUT_MS else RETRY_CONNECT_TIMEOUT_MS
                socket.connect(InetSocketAddress(endpoint.host, endpoint.rrcPort), timeout)
                TcpLiveness.enable(socket)

                drainStale(queue)
                connectedAtNs = System.nanoTime()
                publish(self, ConnectionState.Connected(endpoint))
                pumpUntilFailure(socket, queue)
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
                sockets.closeAll()
            }
            if (!currentCoroutineContext().isActive) return

            // The retry budget is refilled only by a connection that actually *held*. Resetting it
            // on every successful connect let a display that accepts and then immediately drops
            // the socket (it serves one remote at a time, say, and another is attached) flap
            // Connected/Reconnecting once a second forever, never reaching rule 2.
            val now = System.nanoTime()
            val heldMs = if (connectedAtNs == 0L) 0L else (now - connectedAtNs) / 1_000_000L
            if (heldMs >= STABLE_CONNECTION_MS) {
                attempt = 0
                established = true
                outageStartNs = 0L
            }
            if (outageStartNs == 0L) outageStartNs = now

            attempt++
            val exhausted = if (established) {
                (now - outageStartNs) / 1_000_000L >= OUTAGE_GIVE_UP_MS
            } else {
                attempt > MAX_ATTEMPTS
            }
            if (exhausted) {
                giveUp(self, reason)
                return
            }
            publish(self, ConnectionState.Reconnecting(endpoint, reason))
            // Wait out the backoff — unless someone has reason to believe now is a better moment
            // than later ([retryNow]), in which case go at once and start the backoff over.
            val nudged = withTimeoutOrNull(backoffMs(attempt)) { retrySignal.receive() } != null
            if (nudged) attempt = 0
        }
    }

    /**
     * Rule 2: permanently broken. Record why, go Idle, hand back to the connect screen — unless
     * this loop was replaced or disconnected in the meantime, in which case its opinion about the
     * connection is no longer anyone's business. The loss reason is set before the state, so an
     * observer that reacts to Idle finds it already there.
     */
    private fun giveUp(owner: Job, reason: String) {
        synchronized(this) {
            if (current?.job !== owner) return
            current = null
            lastLoss.set(reason)
            wifi.unbind()
            _state.value = ConnectionState.Idle
        }
    }

    /**
     * Runs until the connection dies, then returns the reason. Three ways it dies: the reader
     * notices the socket ending — an orderly close by the MFD, or the kernel declaring the peer
     * dead ([TcpLiveness]); a write fails outright; or the write watchdog closes the socket under
     * a write that has been blocking too long ([WRITE_STALL_MS]).
     */
    private suspend fun pumpUntilFailure(socket: Socket, queue: ReceiveChannel<ByteArray>): String {
        // Nanotime when the in-flight write started; 0 when no write is in flight.
        val writeStartedNs = AtomicLong(0L)
        val writeStalled = AtomicBoolean(false)
        return try {
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
                        val started = writeStartedNs.get()
                        if (started != 0L && System.nanoTime() - started >= WRITE_STALL_MS * 1_000_000L) {
                            writeStalled.set(true)
                            closeQuietly(socket)
                        }
                    }
                }
                val output = socket.getOutputStream()
                try {
                    for (frame in queue) {
                        writeStartedNs.set(System.nanoTime())
                        output.write(frame)
                        output.flush()
                        writeStartedNs.set(0L)
                    }
                } catch (e: IOException) {
                    // Close before rethrowing: the reader is blocked in read() and coroutineScope
                    // will not finish tearing down until the close unblocks it.
                    closeQuietly(socket)
                    throw e
                }
                error("frame channel closed unexpectedly")
            }
        } catch (e: IOException) {
            // The frame being written is dropped with the socket. Do not retry it: by the time a
            // reconnect succeeds it would be a stale input, and re-sending old key events to a
            // chartplotter is worse than losing them.
            if (writeStalled.get()) "Send timed out — the display stopped responding"
            else e.message ?: e.javaClass.simpleName
        }
    }

    /** Anything queued before the connection opened is by definition stale input. */
    private fun drainStale(queue: ReceiveChannel<ByteArray>) {
        while (queue.tryReceive().isSuccess) {
            // discard
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
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
         * Consecutive failures before a connection that has **never worked** is abandoned. With
         * the backoff sequence (1s, 2s, 4s, 8s, 15s) that is about half a minute against an
         * address that refuses, a couple of minutes against one that silently drops packets —
         * short enough that a mistyped or stale address returns the user to the connect screen
         * rather than pinning them to a spinner.
         */
        const val MAX_ATTEMPTS = 5

        /**
         * How long an **established** session keeps trying to come back before it is given up.
         *
         * Ten minutes, because ending the session clears the picture and a chart that is a few
         * minutes old is still worth having in front of you — the remote screen holds the last
         * frame, marked with its age, for as long as the session lasts. Past this the display is
         * treated as gone; the connect screen then keeps watching for it on its own.
         */
        const val OUTAGE_GIVE_UP_MS = 10 * 60_000L
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 15_000L

        /**
         * How long a connection must stay up before it counts as having worked, refilling the
         * retry budget. Deliberately short: it only has to tell "connected" apart from "accepted
         * and immediately dropped". A link that genuinely works for a few seconds at a time is
         * poor, not broken, and keeps its fast first-backoff reconnect.
         */
        const val STABLE_CONNECTION_MS = 2_000L

        /** A single write blocked this long means the peer is gone, whatever TCP still hopes. */
        const val WRITE_STALL_MS = 5_000L
        const val WATCHDOG_POLL_MS = 1_000L
    }
}
