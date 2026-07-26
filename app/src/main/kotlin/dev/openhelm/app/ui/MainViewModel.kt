package dev.openhelm.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.view.Surface
import dev.openhelm.app.config.EndpointStore
import dev.openhelm.app.config.RememberedDisplay
import dev.openhelm.app.discovery.MfdDiscovery
import dev.openhelm.app.rrc.ConnectionState
import dev.openhelm.app.rrc.RrcClient
import dev.openhelm.app.video.RtpTransport
import dev.openhelm.app.video.VideoPlayer
import dev.openhelm.app.video.VideoState
import dev.openhelm.app.video.VideoStats
import dev.openhelm.protocol.KeyAction
import dev.openhelm.protocol.MfdEndpoint
import dev.openhelm.protocol.MfdKey
import dev.openhelm.protocol.Rrc
import dev.openhelm.protocol.TouchGesture
import dev.openhelm.protocol.normalise
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Screens reachable from the connect flow. */
enum class Route { CONNECT, MANUAL, SETTINGS }

@HiltViewModel
class MainViewModel @Inject constructor(
    private val rrc: RrcClient,
    private val discovery: MfdDiscovery,
    private val store: EndpointStore,
    private val player: VideoPlayer,
) : ViewModel() {

    val connection: StateFlow<ConnectionState> = rrc.state
    val discovered: StateFlow<List<MfdEndpoint>> = discovery.endpoints
    val searching: StateFlow<Boolean> = discovery.searching
    val videoState: StateFlow<VideoState> = player.state
    val videoStats: StateFlow<VideoStats> = player.stats

    /** Displays connected to before, most-recent first, for one-tap reconnect and naming. */
    val remembered: StateFlow<List<RememberedDisplay>> =
        store.rememberedDisplays.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The few most-recent displays the connect screen offers as one-tap buttons. */
    val recentShortlist: StateFlow<List<RememberedDisplay>> =
        store.rememberedDisplays
            .map { it.take(RECENT_BUTTONS) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** True once a search has run its window and turned up nothing. */
    private val _discoveryTimedOut = MutableStateFlow(false)
    val discoveryTimedOut: StateFlow<Boolean> = _discoveryTimedOut.asStateFlow()

    /**
     * True while the launch flow is trying the remembered displays, before the network scan. The
     * connect screen shows this as part of one continuous "Scanning" state — recents first,
     * because they are the fastest way to the display that is actually aboard.
     */
    private val _probingRecents = MutableStateFlow(false)
    val probingRecents: StateFlow<Boolean> = _probingRecents.asStateFlow()

    /** Which screen is showing. Connect is home; the others are pushed on top of it. */
    var route by mutableStateOf(Route.CONNECT)
        private set

    private var discoveryTimeoutJob: Job? = null
    private var probeJob: Job? = null

    /** Set by an explicit Disconnect; cleared the moment the user asks to connect again. */
    var autoConnectSuppressed by mutableStateOf(false)
        private set

    var manualText by mutableStateOf("")
        private set

    /** Control-only mode is one toggle away — invaluable whenever video is the broken half. */
    var videoEnabled by mutableStateOf(true)
        private set

    /** UDP for real displays; TCP interleaving exists only for the simulator behind emulator NAT. */
    var transport by mutableStateOf(RtpTransport.UDP)
        private set

    init {
        viewModelScope.launch {
            if (store.rtpTransport.first() == "tcp") transport = RtpTransport.TCP_INTERLEAVED
        }

        // Persist every endpoint that actually connects, so it becomes a one-tap option next time.
        viewModelScope.launch {
            rrc.state.collect { state ->
                if (state is ConnectionState.Connected) store.remember(state.endpoint)
            }
        }

        // Auto-connect to whatever the scan turns up. These networks carry exactly one display, so
        // finding one and then asking the user to tap it would be ceremony for its own sake.
        viewModelScope.launch {
            discovery.endpoints.collect { found ->
                val endpoint = found.firstOrNull() ?: return@collect
                if (connection.value is ConnectionState.Idle && route == Route.CONNECT) {
                    stopDiscovery()
                    rrc.connect(endpoint)
                }
            }
        }
    }

    fun openManual() {
        route = Route.MANUAL
    }

    fun openSettings() {
        route = Route.SETTINGS
    }

    /** Back to the connect screen from a pushed screen. */
    fun backToConnect() {
        route = Route.CONNECT
    }

    /**
     * The whole automatic path, in one continuous "scanning" state: try the remembered displays
     * first (fastest route to the display that is actually aboard), and if none answer, sweep the
     * network. Either way the user does nothing — a found display is connected to automatically.
     */
    fun startAutoConnect() {
        // An explicit Disconnect must stick. Without this the connect screen would re-mount,
        // immediately find the display it was just disconnected from, and reconnect — making the
        // button look broken.
        if (autoConnectSuppressed) return
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            val recents = store.rememberedDisplays.first()
            if (recents.isNotEmpty() && connection.value is ConnectionState.Idle) {
                _probingRecents.value = true
                try {
                    val reachable = rrc.firstReachable(recents.map { it.endpoint })
                    if (reachable != null && connection.value is ConnectionState.Idle) {
                        rrc.connect(reachable)
                        return@launch
                    }
                } finally {
                    _probingRecents.value = false
                }
            }
            if (connection.value is ConnectionState.Idle) startDiscovery()
        }
    }

    /** Abandon the launch probe; the network scan carries on. */
    fun skipRecentProbe() {
        probeJob?.cancel()
        probeJob = null
        _probingRecents.value = false
    }

    fun renameDisplay(host: String, name: String) {
        viewModelScope.launch { store.rename(host, name) }
    }

    fun forgetDisplay(host: String) {
        viewModelScope.launch { store.forget(host) }
    }

    fun onManualTextChange(text: String) {
        manualText = text
    }

    /**
     * The endpoint [manualText] describes, or null. A bare address (`192.168.1.7`) is enough:
     * the ports, path and version below are what every captured unit uses, and nobody should
     * have to type them. The full compact form still works for unusual setups.
     */
    val manualEndpoint: MfdEndpoint?
        get() {
            val text = manualText.trim()
            MfdEndpoint.parse(text)?.let { return it }
            if (text.isNotEmpty() && !text.contains(':') && !text.contains(' ')) {
                return MfdEndpoint(
                    host = text,
                    rtspPort = 8554,
                    rrcPort = 50000,
                    rtspPath = "RAYMARINEMFD",
                    rrcVersion = 0x10, // every captured unit advertises "1.10" -> 0x10
                )
            }
            return null
        }

    /**
     * Begin (or restart) discovery, arming a timeout. mDNS is routinely blocked or flaky on boat
     * Wi-Fi, so if the search window elapses with nothing found, [discoveryTimedOut] flips and the
     * UI offers a real way forward — search again, or the always-present manual entry — rather
     * than an endless spinner.
     */
    fun startDiscovery() {
        _discoveryTimedOut.value = false
        discovery.start()
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = viewModelScope.launch {
            delay(DISCOVERY_WINDOW_MS)
            if (discovered.value.isEmpty()) {
                discovery.stop()
                _discoveryTimedOut.value = true
            }
        }
    }

    fun stopDiscovery() {
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        discovery.stop()
    }

    fun connect(endpoint: MfdEndpoint) {
        autoConnectSuppressed = false
        stopDiscovery()
        rrc.connect(endpoint)
    }

    /** Resume automatic connecting after an explicit disconnect — the "Scan again" affordance. */
    fun resumeAutoConnect() {
        autoConnectSuppressed = false
        startAutoConnect()
    }

    fun connectManual() {
        // A manual address is remembered only if it actually connects (via the state collector in
        // init), so a mistyped address never lingers in the recents.
        val endpoint = manualEndpoint ?: return
        stopDiscovery()
        route = Route.CONNECT
        rrc.connect(endpoint)
    }

    fun disconnect() {
        autoConnectSuppressed = true
        probeJob?.cancel()
        _probingRecents.value = false
        player.stop()
        rrc.disconnect()
    }

    fun toggleVideo() {
        videoEnabled = !videoEnabled
        if (!videoEnabled) player.stop()
        // When re-enabled, the surface re-enters composition and onVideoSurfaceReady restarts it.
    }

    fun selectTransport(choice: RtpTransport) {
        transport = choice
        viewModelScope.launch {
            store.saveRtpTransport(if (choice == RtpTransport.TCP_INTERLEAVED) "tcp" else "udp")
        }
    }

    fun onVideoSurfaceReady(surface: Surface) {
        val endpoint = currentEndpoint() ?: return
        if (videoEnabled) player.start(endpoint.rtspUrl, surface, transport)
    }

    fun onVideoSurfaceDestroyed() {
        player.stop()
    }

    /**
     * Key events are forwarded exactly as the user produces them. DOWN on touch-down, UP on
     * touch-up — the time in between belongs to the MFD's own auto-repeat, which is what makes
     * holding an arrow key sweep the cursor across the screen.
     */
    fun keyDown(key: MfdKey) = sendButton(key, KeyAction.DOWN)

    fun keyUp(key: MfdKey) = sendButton(key, KeyAction.UP)

    private fun sendButton(key: MfdKey, action: KeyAction) {
        val endpoint = currentEndpoint() ?: return
        rrc.send(Rrc.button(key, action, endpoint.rrcVersion))
    }

    /**
     * A dial-ring step. The zoom opcode changes the chart range — it is not a pointer, however
     * much its payload looks like coordinates. Positive steps zoom in, negative out.
     */
    fun zoomStep(step: Int, accumulated: Int) {
        val endpoint = currentEndpoint() ?: return
        rrc.send(Rrc.zoom(step, accumulated, endpoint.rrcVersion))
    }

    // ---- touch on the video -----------------------------------------------------------------
    // Positions arrive in view pixels and leave normalised 0..65535 across the video area.
    // The touch opcode is expected to work on HybridTouch units (the reference device is one);
    // the dial's arrow keys remain the first-class fallback for keypad-only models.

    private var touchGesture: TouchGesture? = null

    fun videoTouchDown(x: Float, y: Float, width: Int, height: Int) {
        val endpoint = currentEndpoint() ?: return
        val (nx, ny) = normalise(x, y, width, height)
        val gesture = TouchGesture(endpoint.rrcVersion)
        touchGesture = gesture
        rrc.send(gesture.down(nx, ny))
    }

    fun videoTouchMove(x: Float, y: Float, width: Int, height: Int) {
        val gesture = touchGesture ?: return
        val (nx, ny) = normalise(x, y, width, height)
        rrc.send(gesture.move(nx, ny))
    }

    fun videoTouchUp(x: Float, y: Float, width: Int, height: Int) {
        val gesture = touchGesture ?: return
        touchGesture = null
        val (nx, ny) = normalise(x, y, width, height)
        rrc.send(gesture.up(nx, ny))
    }

    private fun currentEndpoint(): MfdEndpoint? = when (val s = connection.value) {
        is ConnectionState.Connected -> s.endpoint
        is ConnectionState.Connecting -> s.endpoint
        is ConnectionState.Reconnecting -> s.endpoint
        ConnectionState.Idle -> null
    }

    private companion object {
        // Long enough for a healthy network to answer, short enough not to feel hung. Manual
        // connect is always one tap away, so this is a nudge toward it, not a wall.
        const val DISCOVERY_WINDOW_MS = 12_000L

        /** How many remembered displays the connect screen offers as buttons. */
        const val RECENT_BUTTONS = 4
    }
}
