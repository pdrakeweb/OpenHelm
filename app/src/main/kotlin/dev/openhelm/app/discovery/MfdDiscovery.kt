package dev.openhelm.app.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.openhelm.app.di.AppScope
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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

    fun start() {
        if (_searching.value) return
        synchronized(this) {
            rtspByHost.clear()
            rrcByHost.clear()
        }
        _endpoints.value = emptyList()
        _searching.value = true

        resolveWorker = scope.launch(Dispatchers.IO) {
            for (info in resolveQueue) {
                resolve(info)?.let { merge(it) }
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
                    _searching.value = false
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            listener
        }
    }

    fun stop() {
        if (!_searching.value && listeners.isEmpty()) return
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
        _searching.value = false
    }

    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            @Suppress("DEPRECATION") // replacement requires API 34; this must run on 26+
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    cont.resume(resolved)
                }

                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    cont.resume(null)
                }
            })
        }

    private fun merge(resolved: NsdServiceInfo) {
        val host = resolved.host?.hostAddress ?: return
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
}
