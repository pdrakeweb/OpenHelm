package dev.openhelm.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Thrown when no Wi-Fi network becomes available within the wait window. */
class WifiUnavailableException : Exception("Wi-Fi is not available")

/**
 * Pins this process to the Wi-Fi network.
 *
 * The MFD lives on an isolated access point with no internet behind it. When mobile data is up,
 * Android routes new sockets over cellular by default — and the MFD is then simply unreachable,
 * with nothing but connect timeouts to show for it. So before *any* socket opens, on *every*
 * connect path including retries, the process must be bound to the Wi-Fi network.
 *
 * **Serialized by a [Mutex], and stateless between calls.** This class is shared by two
 * independent retry loops (the control channel and the video pipeline) that both re-bind on every
 * attempt, concurrently, from `Dispatchers.IO`. An earlier version kept the network callback in a
 * field and unregistered it at the start of each bind — which meant one loop's `bind()` could
 * unregister the *other* loop's callback while it was still waiting for `onAvailable`, and the
 * victim then timed out and reported "Wi-Fi is not available" on a network that was fine. The
 * window was widest while the phone was still associating with the boat AP — exactly when the
 * reconnect paths run. Now each call owns its callback for its own lifetime, unregisters it before
 * returning, and the mutex keeps two binds from interleaving at all.
 *
 * Deliberately never inspects SSID or BSSID: those are location-redacted on modern Android and a
 * check against them can never pass. Any Wi-Fi network is accepted; if it is the wrong one, the
 * connect attempt fails visibly and the user can see why.
 */
@Singleton
class WifiNetworkBinder @Inject constructor(@ApplicationContext context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val mutex = Mutex()

    /**
     * Wait for a Wi-Fi network, bind the process to it, and return it.
     *
     * Callers use [Network.getSocketFactory] for their sockets as well — the process-wide bind
     * covers anything else (DNS, libraries), the per-socket factory covers this connection even if
     * something later rebinds the process.
     *
     * Safe to call repeatedly and from concurrent coroutines; each call re-asserts the binding,
     * which is exactly what a reconnect path needs.
     */
    suspend fun bind(timeoutMs: Long = WIFI_WAIT_MS): Network = mutex.withLock {
        // No NET_CAPABILITY_INTERNET requirement: the MFD's access point has no internet behind
        // it, and requiring internet could exclude exactly the network we want.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val first = CompletableDeferred<Network>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                first.complete(network)
            }
        }
        connectivity.registerNetworkCallback(request, cb)
        try {
            val network = withTimeoutOrNull(timeoutMs) { first.await() }
                ?: throw WifiUnavailableException()
            connectivity.bindProcessToNetwork(network)
            network
        } finally {
            // The callback's one job is done the moment onAvailable fires (or the wait is
            // abandoned). Unregistering here, not at the next bind, is what keeps callbacks from
            // accumulating and from being anyone else's to clobber.
            connectivity.unregisterNetworkCallback(cb)
        }
    }

    /**
     * Drop the process binding. Callers own the decision of *when* — the binding is process-wide,
     * so this belongs at the end of the whole session, not of any one socket.
     */
    fun unbind() {
        connectivity.bindProcessToNetwork(null)
    }

    private companion object {
        const val WIFI_WAIT_MS = 15_000L
    }
}
