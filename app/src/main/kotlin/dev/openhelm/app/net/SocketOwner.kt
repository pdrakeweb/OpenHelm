package dev.openhelm.app.net

import java.io.Closeable
import java.io.IOException

/**
 * The sockets belonging to **one** connection loop, and the only handle anyone else has for
 * unblocking that loop.
 *
 * Cancelling a coroutine cannot interrupt blocking socket I/O; closing the socket is the only way
 * to get a blocked `connect`/`read`/`receive` to return. So whoever stops a loop needs to reach
 * its sockets from another thread. That used to be done through a field on the client shared by
 * every loop the client ever ran, which had two failure modes this class exists to remove:
 *
 * - **A stale loop closing its successor's socket.** A loop that had been told to stop, but was
 *   still unwinding, ran its own "close the current socket" cleanup against a field that by then
 *   held the *next* loop's socket. Stop followed promptly by start — backgrounding and coming
 *   straight back, toggling mirroring off and on, disconnect then reconnect — produced a spurious
 *   "Socket closed" failure and a backoff on a perfectly healthy link.
 * - **A socket published after the close.** The stopper closed "the current socket" while the loop
 *   was between sockets (waiting for Wi-Fi, say). The loop then created a new one and blocked on it
 *   for a full connect timeout with nobody left to close it, and the next loop waited behind it.
 *
 * One instance per loop fixes the first: a loop can only ever close what it adopted itself.
 * [shutdown] latching fixes the second: once a loop has been told to stop, any socket it tries to
 * adopt afterwards is closed on the spot and the adoption fails.
 *
 * Thread-safe. Pure JVM, so the latch behaviour is unit-tested.
 */
class SocketOwner {
    private val sockets = ArrayList<Closeable>(3)
    private var shutDown = false

    /**
     * Register [socket] *before* blocking on it. Returns it for chaining.
     *
     * @throws IOException if [shutdown] has already been called — [socket] is closed first, so the
     *   caller has nothing to clean up.
     */
    @Synchronized
    fun <T : Closeable> adopt(socket: T): T {
        if (shutDown) {
            closeQuietly(socket)
            throw IOException("Connection cancelled")
        }
        sockets.add(socket)
        return socket
    }

    /**
     * Close everything adopted so far, and accept new sockets afterwards — what a loop does
     * between its own attempts, and what a watchdog does to fail the attempt in progress.
     */
    @Synchronized
    fun closeAll() {
        sockets.forEach(::closeQuietly)
        sockets.clear()
    }

    /** Close everything and refuse anything further: the loop this belongs to is being stopped. */
    @Synchronized
    fun shutdown() {
        shutDown = true
        closeAll()
    }

    private fun closeQuietly(socket: Closeable) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}
