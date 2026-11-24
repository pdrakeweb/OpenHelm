package dev.openhelm.app.net

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.net.Socket

/**
 * Makes the kernel notice a dead peer in seconds rather than minutes-to-hours.
 *
 * The control protocol is one-way: the display never sends a byte, and there is no ping frame to
 * borrow. That leaves two silent failures when the far end vanishes without closing the socket
 * (display powered off behind a separate access point, a cable pulled, a router rebooted while the
 * phone stays associated):
 *
 * - **Idle link.** Nothing is written, so nothing fails. `SO_KEEPALIVE` alone uses the system
 *   default of two *hours* before the first probe. The UI says "Connected" the whole time.
 * - **Writes into the void.** A key press is a dozen bytes; `write()` returns the moment they are
 *   in the kernel's send buffer, whether or not anyone will ever acknowledge them. The write only
 *   *blocks* once that buffer is full, which takes thousands of key presses — so a watchdog on
 *   blocked writes almost never fires — and TCP's own retransmission gives up after ~15 minutes.
 *
 * Both are closed here with per-socket options the Java API does not expose: short keepalive
 * timing for the idle case, and `TCP_USER_TIMEOUT` for the unacknowledged-write case. Either one
 * tripping fails the socket with an ordinary error, which the connection loop already turns into
 * "Reconnecting".
 *
 * The option numbers are the Linux ABI values (`<linux/tcp.h>`), identical on every architecture
 * Android runs on; only some of them have public `OsConstants` names, so they are spelled out.
 *
 * Best-effort by design: if a device refuses any of this the connection carries on exactly as it
 * did before, with the write watchdog as the remaining backstop.
 */
internal object TcpLiveness {

    /**
     * Apply the options to a **connected** [socket]. Returns false if the platform refused.
     *
     * With the defaults a silent peer is detected after roughly [idleSeconds] +
     * [intervalSeconds] × [probes] of quiet, or [userTimeoutMs] after an unacknowledged write,
     * whichever comes first.
     */
    fun enable(
        socket: Socket,
        idleSeconds: Int = 5,
        intervalSeconds: Int = 2,
        probes: Int = 3,
        userTimeoutMs: Int = 10_000,
    ): Boolean = try {
        // A dup of the socket's descriptor: options set through it land on the same underlying
        // socket, and closing the dup leaves the original open.
        val pfd = ParcelFileDescriptor.fromSocket(socket)
        try {
            val fd = pfd.fileDescriptor
            Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_KEEPALIVE, 1)
            Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPIDLE, idleSeconds)
            Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPINTVL, intervalSeconds)
            Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPCNT, probes)
            Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_USER_TIMEOUT, userTimeoutMs)
        } finally {
            pfd.close()
        }
        true
    } catch (e: Exception) {
        // ErrnoException from a kernel that refuses an option, or anything a vendor build throws
        // from fromSocket. None of it may cost the connection that was just established.
        Log.w(TAG, "Could not enable fast dead-peer detection; falling back to defaults", e)
        false
    }

    private const val TAG = "OpenHelm"
    private const val TCP_KEEPIDLE = 4
    private const val TCP_KEEPINTVL = 5
    private const val TCP_KEEPCNT = 6
    private const val TCP_USER_TIMEOUT = 18
}
