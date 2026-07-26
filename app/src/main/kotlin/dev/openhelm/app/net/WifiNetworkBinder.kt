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
 * Deliberately never inspects SSID or BSSID: those are location-redacted on modern Android and a
 * check against them can never pass. Any Wi-Fi network is accepted; if it is the wrong one, the
 * connect attempt fails visibly and the user can see why.
 */
@Singleton
class WifiNetworkBinder @Inject constructor(@ApplicationContext context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Wait for a Wi-Fi network, bind the process to it, and return it.
     *
     * Callers use [Network.getSocketFactory] for their sockets as well — the process-wide bind
     * covers anything else (DNS, libraries), the per-socket factory covers this connection even if
     * something later rebinds the process.
     *
     * Safe to call repeatedly; each call re-asserts the binding, which is exactly what a reconnect
     * path needs.
     */
    suspend fun bind(timeoutMs: Long = WIFI_WAIT_MS): Network {
        unbind()

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
        callback = cb

        val network = withTimeoutOrNull(timeoutMs) { first.await() }
        if (network == null) {
            unbind()
            throw WifiUnavailableException()
        }
        connectivity.bindProcessToNetwork(network)
        return network
    }

    /** Drop the process binding and stop tracking Wi-Fi. */
    fun unbind() {
        callback?.let { connectivity.unregisterNetworkCallback(it) }
        callback = null
        connectivity.bindProcessToNetwork(null)
    }

    private companion object {
        const val WIFI_WAIT_MS = 15_000L
    }
}
