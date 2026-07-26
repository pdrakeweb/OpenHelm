package dev.openhelm.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.view.Surface
import dev.openhelm.app.config.EndpointStore
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    /** Displays connected to before, most-recent first, for one-tap reconnect. */
    val remembered: StateFlow<List<MfdEndpoint>> =
        store.rememberedDisplays.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** True once a search has run its window and turned up nothing — drives the §5.1 dead-end-free failure screen. */
    private val _discoveryTimedOut = MutableStateFlow(false)
    val discoveryTimedOut: StateFlow<Boolean> = _discoveryTimedOut.asStateFlow()

    private var discoveryTimeoutJob: Job? = null

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
            store.manualAddress.first()?.let { saved ->
                if (manualText.isEmpty()) manualText = saved
            }
            if (store.rtpTransport.first() == "tcp") transport = RtpTransport.TCP_INTERLEAVED

            // Auto-reconnect to the last display on launch. The user lands straight on the remote,
            // "Connecting…", with Disconnect as the visible cancel — the common case on a boat is
            // the same display every time, so this saves the discovery ritual.
            store.rememberedDisplays.first().firstOrNull()?.let { last ->
                if (connection.value is ConnectionState.Idle) rrc.connect(last)
            }
        }

        // Persist every endpoint that actually connects, so it becomes a one-tap option next time.
        viewModelScope.launch {
            rrc.state.collect { state ->
                if (state is ConnectionState.Connected) store.remember(state.endpoint)
            }
        }
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
        rrc.connect(endpoint)
    }

    fun connectManual() {
        val endpoint = manualEndpoint ?: return
        viewModelScope.launch { store.saveManualAddress(manualText.trim()) }
        rrc.connect(endpoint)
    }

    fun disconnect() {
        player.stop()
        rrc.disconnect()
    }

    fun toggleVideo() {
        videoEnabled = !videoEnabled
        if (!videoEnabled) player.stop()
        // When re-enabled, the surface re-enters composition and onVideoSurfaceReady restarts it.
    }

    fun toggleTransport() {
        transport = when (transport) {
            RtpTransport.UDP -> RtpTransport.TCP_INTERLEAVED
            RtpTransport.TCP_INTERLEAVED -> RtpTransport.UDP
        }
        viewModelScope.launch {
            store.saveRtpTransport(if (transport == RtpTransport.TCP_INTERLEAVED) "tcp" else "udp")
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
        // Long enough for a healthy network to answer, short enough not to feel hung. The manual
        // field is visible the whole time, so this is a nudge toward it, not a wall.
        const val DISCOVERY_WINDOW_MS = 12_000L
    }
}
