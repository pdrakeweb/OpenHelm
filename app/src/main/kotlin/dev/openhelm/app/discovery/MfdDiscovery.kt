package dev.openhelm.app.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import dev.openhelm.app.di.AppScope
import dev.openhelm.app.net.LocalAddresses
import dev.openhelm.protocol.Discovery
import dev.openhelm.protocol.MfdEndpoint
import dev.openhelm.protocol.parseRrcVersion
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * mDNS discovery of MFDs, via [NsdManager].
 *
 * An MFD advertises two services: `_rtsp._tcp` (video: port, path, model, serial) and
 * `_rym_rrc._tcp` (control: port, protocol version). Both are needed to build a usable
 * [MfdEndpoint], so results are merged per host address and published only when complete.
 *
 * mDNS on real boat Wi-Fi is unreliable — that is a finding, not an opinion — which is why the UI
 * treats manual address entry as a peer of this, not a fallback.
 */
@Singleton
class MfdDiscovery @Inject constructor(
    @ApplicationContext context: Context,
    @AppScope private val scope: CoroutineScope,
) {
    private val nsd = context.getSystemService(NsdManager::class.java)

    /**
     * Held only while searching: many devices filter multicast in the Wi-Fi driver unless a lock
     * is held, and mDNS *is* multicast — without this, discovery fails silently on exactly the
     * hardware where it matters.
     */
    private val multicastLock = context.getSystemService(WifiManager::class.java)
        .createMulticastLock("openhelm-discovery")
        .apply { setReferenceCounted(false) }

    private val _endpoints = MutableStateFlow<List<MfdEndpoint>>(emptyList())
    val endpoints: StateFlow<List<MfdEndpoint>> = _endpoints.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private data class RtspHalf(val port: Int, val path: String, val model: String?, val serial: String?)
    private data class RrcHalf(val port: Int, val version: Int)

    private val rtspByHost = mutableMapOf<String, RtspHalf>()
    private val rrcByHost = mutableMapOf<String, RrcHalf>()

    /**
     * Resolutions are strictly serialized: NsdManager rejects concurrent resolve requests with
     * FAILURE_ALREADY_ACTIVE, so found services queue here and one worker drains the queue.
     */
    private val resolveQueue = Channel<NsdServiceInfo>(32, BufferOverflow.DROP_OLDEST)
    private var resolveWorker: Job? = null

    private var listeners = listOf<NsdManager.DiscoveryListener>()

    /**
     * Synchronized with [stop] because NsdManager delivers `onStartDiscoveryFailed` on its own
     * thread, and that callback calls [stop]. Unsynchronized, a failure reported for the first
     * listener while this was still registering the second ran its teardown against a listener
     * list that had not been assigned yet — leaving the second listener registered with nothing
     * that would ever unregister it.
     */
    @Synchronized
    fun start() {
        if (_searching.value) return
        // A previous session that *failed to start* leaves debris — one successfully registered
        // listener of the pair, a live resolve worker, a held multicast lock — and the
        // `_searching` flag alone does not see it. Without this teardown, every "Scan again"
        // after a start failure leaked another listener into NsdManager until its per-app limit
        // broke discovery for the rest of the process's life.
        stop()
        synchronized(this) {
            rtspByHost.clear()
            rrcByHost.clear()
        }
        _endpoints.value = emptyList()
        // Whatever the last search found and never got round to resolving belongs to that search.
        while (resolveQueue.tryReceive().isSuccess) {
            // discard
        }
        _searching.value = true
        multicastLock.acquire()

        resolveWorker = scope.launch(Dispatchers.IO) {
            for (info in resolveQueue) {
                resolveWithRetry(info)?.let { merge(it) }
            }
        }

        listeners = listOf(Discovery.SERVICE_RTSP, Discovery.SERVICE_RRC).map { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    resolveQueue.trySend(info)
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    // Losing the advert does not mean the device is gone — mDNS flaps on weak
                    // Wi-Fi. Entries persist until the next start(); connecting will tell.
                }

                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    // Full teardown, not just the flag: the *other* listener of the pair may have
                    // registered successfully, and the lock and worker are still live. stop() is
                    // idempotent and unregisters whatever actually exists.
                    stop()
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            listener
        }
    }

    /** Idempotent: safe to call on a session that never fully started, or twice. */
    @Synchronized
    fun stop() {
        listeners.forEach {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (_: IllegalArgumentException) {
                // Listener was never registered because starting it failed; nothing to stop.
            }
        }
        listeners = emptyList()
        resolveWorker?.cancel()
        resolveWorker = null
        if (multicastLock.isHeld) multicastLock.release()
        _searching.value = false
    }

    /** What one resolve attempt came to. A timeout is the absence of one of these. */
    private class Resolution(val info: NsdServiceInfo?)

    /**
     * Resolve [info], trying again if NsdManager *reports* a failure.
     *
     * A service is announced once per search, so a resolve that fails is a display that stays
     * invisible until the user scans again — and reported failures are routine rather than rare:
     * `FAILURE_ALREADY_ACTIVE` whenever a resolve from the previous search (cancelled on our side,
     * still running on the platform's) has not finished, and transient errors on exactly the weak
     * links this app lives on. A short pause clears both.
     *
     * A *timeout* is not retried: that is the resolver not answering at all, and another five
     * seconds of this single worker's time is better spent on whatever is queued behind it.
     */
    private suspend fun resolveWithRetry(info: NsdServiceInfo): NsdServiceInfo? {
        repeat(RESOLVE_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RESOLVE_RETRY_DELAY_MS)
            val result = resolve(info) ?: return null
            result.info?.let { return it }
        }
        return null
    }

    private suspend fun resolve(info: NsdServiceInfo): Resolution? =
        // Bounded: NsdManager has a known failure mode where a resolve never calls back, and this
        // worker is a single serial queue — one silent resolve would otherwise wedge discovery of
        // everything behind it, forever, with nothing on screen to say why. On timeout this entry
        // is skipped; the service will be re-found on the next scan.
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                @Suppress("DEPRECATION") // replacement requires API 34; this must run on 26+
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    // A listener can be called after its wait was abandoned (the timeout above, or
                    // the search being stopped); there is then nobody to tell.
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(Resolution(resolved))
                    }

                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(Resolution(null))
                    }
                })
            }
        }

    private fun merge(resolved: NsdServiceInfo) {
        val host = resolved.host?.hostAddress ?: return
        // An mDNS response is whatever a device on this Wi-Fi chose to say. Anything claiming an
        // address off the local network is dropped here, before it can be published, connected to
        // or remembered — see LocalAddresses for why that path is worth closing.
        if (!LocalAddresses.isLocal(host)) return
        val txt = resolved.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) }

        synchronized(this) {
            when {
                resolved.serviceType.contains("_rtsp._tcp") -> {
                    // Only MFDs carry these TXT keys; any other RTSP device on the network is
                    // not ours and is ignored.
                    val path = txt[Discovery.TXT_RTSP_PATH] ?: return
                    rtspByHost[host] = RtspHalf(
                        port = resolved.port,
                        path = path,
                        model = txt[Discovery.TXT_MODEL],
                        serial = txt[Discovery.TXT_SERIAL],
                    )
                }

                resolved.serviceType.contains("_rym_rrc._tcp") -> {
                    rrcByHost[host] = RrcHalf(
                        port = resolved.port,
                        version = parseRrcVersion(txt[Discovery.TXT_RRC_VERSION]),
                    )
                }

                else -> return
            }

            _endpoints.value = rtspByHost.keys.intersect(rrcByHost.keys).map { h ->
                val rtsp = rtspByHost.getValue(h)
                val rrc = rrcByHost.getValue(h)
                MfdEndpoint(
                    host = h,
                    rtspPort = rtsp.port,
                    rrcPort = rrc.port,
                    rtspPath = rtsp.path,
                    rrcVersion = rrc.version,
                    model = rtsp.model,
                    serial = rtsp.serial,
                )
            }.sortedBy { it.host }
        }
    }

    private companion object {
        const val RESOLVE_TIMEOUT_MS = 5_000L
        const val RESOLVE_ATTEMPTS = 3
        const val RESOLVE_RETRY_DELAY_MS = 400L
    }
}
