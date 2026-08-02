package dev.openhelm.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.view.Surface
import dev.openhelm.app.config.EndpointStore
import dev.openhelm.app.config.RememberedDisplay
import dev.openhelm.app.discovery.MfdDiscovery
import dev.openhelm.app.net.LocalAddresses
import dev.openhelm.app.BuildConfig
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

    /**
     * The few most-recent displays the connect screen offers as one-tap buttons. Derived from
     * [remembered] rather than collecting the DataStore a second time.
     */
    val recentShortlist: StateFlow<List<RememberedDisplay>> =
        remembered
            .map { it.take(RECENT_BUTTONS) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Why the last session ended without the user asking it to, or null.
     *
     * Set when [RrcClient] exhausts its retry budget and gives the connection up as permanently
     * broken — at which point the state machine returns to Idle, the UI lands back on the connect
     * screen, and scanning resumes on its own. This is the sentence under the scan ring that
     * tells the user *why* they are looking at the connect screen again. Cleared by the next
     * successful connection or by an explicit user action.
     */
    private val _connectionLost = MutableStateFlow<String?>(null)
    val connectionLost: StateFlow<String?> = _connectionLost.asStateFlow()

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

    /**
     * Explore the app with no display present: a locally-rendered fake video feed and a fully
     * working keypad, with nothing sent over the network. Deliberately **session-only** — plain
     * in-memory state, never written to [store] — so the app always starts in real mode and nobody
     * boots it on the boat into a simulated session left on from last time.
     */
    var simulationMode by mutableStateOf(false)
        private set

    private var discoveryTimeoutJob: Job? = null
    private var probeJob: Job? = null

    /** Set by an explicit Disconnect; cleared the moment the user asks to connect again. */
    var autoConnectSuppressed by mutableStateOf(false)
        private set

    var manualText by mutableStateOf("")
        private set

    /**
     * True in **Mirror** — the display's picture beside the controls. False in **Remote only** —
     * the full-screen keypad, with the video pipeline stopped.
     *
     * Remote only is a first-class mode, not a degraded one: it is the fallback whenever video is
     * the broken half, and it is what you want anyway when the phone is a keypad on a bulkhead and
     * the chart is being read off the display itself.
     */
    var mirroring by mutableStateOf(true)
        private set

    /** UDP for real displays; TCP interleaving exists only for the simulator behind emulator NAT. */
    var transport by mutableStateOf(RtpTransport.UDP)
        private set

    /**
     * The palette the user has chosen, or null if they never have — in which case the UI shows
     * [DefaultPalette].
     */
    var palette by mutableStateOf<HelmPalette?>(null)
        private set

    /**
     * The last thing the user did, for simulation's action field.
     *
     * Simulation shows a chart that cannot respond, so pressing a key produces no visible result
     * anywhere — which makes it impossible to tell a working control from a dead one. Naming the
     * action is the substitute for the display reacting.
     *
     * Only written while [simulationMode] is on; a real session has the display itself as the
     * feedback and does not need a running commentary.
     */
    var simAction by mutableStateOf<String?>(null)
        private set

    /** How many times [simAction] has repeated without something else intervening. */
    var simActionRepeats by mutableIntStateOf(0)
        private set

    fun noteSimAction(text: String) {
        if (!simulationMode) return
        if (text == simAction) simActionRepeats++ else { simAction = text; simActionRepeats = 1 }
    }

    init {
        // Restored on launch. Unlike simulation mode this is deliberately sticky — see
        // EndpointStore.palette for why relaunching bright after dark is not acceptable.
        viewModelScope.launch {
            palette = parsePalette(store.palette.first())
        }

        // TCP-interleaved RTP is a simulator-only workaround — the AVD's NAT drops inbound UDP.
        // A real display **hangs** when asked to interleave, so this must never be restorable in a
        // release build. The picker itself is already debug-only, but the preference outlives the
        // build that wrote it: a debug install that set "tcp", upgraded in place to release, came
        // back up with interleaving on and no way to see or change it. Read the preference only
        // where the control that writes it exists.
        if (BuildConfig.DEBUG) {
            viewModelScope.launch {
                if (store.rtpTransport.first() == "tcp") transport = RtpTransport.TCP_INTERLEAVED
            }
        }

        // Persist every endpoint that actually connects, so it becomes a one-tap option next
        // time — and notice when the connection loop gives a connection up as permanently broken.
        // In that case the state returns to Idle, AppRoot lands back on the connect screen, and
        // the connect screen's own mount effect resumes scanning; all this collector adds is the
        // reason, so the screen can say why the session ended.
        viewModelScope.launch {
            rrc.state.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        store.remember(state.endpoint)
                        _connectionLost.value = null
                    }

                    ConnectionState.Idle -> {
                        rrc.consumeLoss()?.let { _connectionLost.value = it }
                    }

                    else -> {}
                }
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
     * Enter simulation. Any real session is torn down first — a simulated screen sitting on top of
     * a live connection would be genuinely confusing, and there is exactly one remote screen.
     */
    fun enterSimulation() {
        if (connection.value !is ConnectionState.Idle) disconnect()
        simulationMode = true
    }

    fun exitSimulation() {
        simulationMode = false
        simAction = null
        simActionRepeats = 0
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
            // Only local addresses are probed unprompted. A remembered display can have come from
            // an mDNS advertisement, and this runs at every launch with no user action — so an
            // entry pointing off the network would be an unattended connection attempt to an
            // arbitrary host. Such an entry is still offered as a button on the connect screen;
            // tapping it is a decision, this is not.
            val recents = store.rememberedDisplays.first()
                .filter { LocalAddresses.isLocal(it.endpoint.host) }
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
        _connectionLost.value = null
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
        _connectionLost.value = null
        probeJob?.cancel()
        _probingRecents.value = false
        player.stop()
        rrc.disconnect()
    }

    /** Switch between mirroring the display and remote-only. */
    fun selectMirroring(on: Boolean) {
        if (on == mirroring) return
        mirroring = on
        if (!mirroring) player.stop()
        // When re-enabled, the surface re-enters composition and onVideoSurfaceReady restarts it.
    }

    /**
     * Advance day → dusk → night → day, starting from whatever is on screen now.
     *
     * The caller passes the *effective* palette rather than reading [palette], because before the
     * first tap that is null and the visible palette comes from the system setting. Taking the
     * caller's value means the first tap always moves one step from what the user can see, instead
     * of jumping to a fixed starting point.
     *
     * A cycle, not a dialog: this is reachable mid-session and the hand doing the tapping may also
     * be holding a wheel. Three taps returns to where it started, so there is nothing to undo.
     */
    fun cyclePalette(from: HelmPalette) {
        selectPalette(HelmPalette.entries[(from.ordinal + 1) % HelmPalette.entries.size])
    }

    /** Choose a palette outright — what the named options in Settings do. */
    fun selectPalette(choice: HelmPalette) {
        palette = choice
        viewModelScope.launch { store.savePalette(choice.name) }
    }

    fun selectTransport(choice: RtpTransport) {
        transport = choice
        viewModelScope.launch {
            store.saveRtpTransport(if (choice == RtpTransport.TCP_INTERLEAVED) "tcp" else "udp")
        }
    }

    fun onVideoSurfaceReady(surface: Surface) {
        val endpoint = currentEndpoint() ?: return
        if (mirroring) player.start(endpoint, surface, transportFor(endpoint))
    }

    /**
     * The transport to *open with* for this endpoint.
     *
     * TCP interleaving exists for exactly one situation: the project's own simulator, reached
     * through an Android emulator's NAT, which cannot pass inbound UDP. Against a real display it
     * is not merely suboptimal — the display accepts the SETUP and then never sends a single
     * packet, which presents as a session that connects instantly and then waits forever.
     *
     * The setting is persisted, so a phone that was once pointed at the simulator carries it to
     * the boat and video silently never starts. Restricting it to loopback-ish hosts means the
     * preference can only do what it was meant for. [VideoPlayer] additionally falls back on its
     * own if a session negotiates and no media follows, so a wrong guess here self-corrects.
     */
    private fun transportFor(endpoint: MfdEndpoint): RtpTransport =
        if (transport == RtpTransport.TCP_INTERLEAVED && !isSimulatorHost(endpoint.host)) {
            RtpTransport.UDP
        } else {
            transport
        }

    /** The AVD's host alias and loopback — the only places the simulator ever lives. */
    private fun isSimulatorHost(host: String): Boolean =
        host == "10.0.2.2" || host == "127.0.0.1" || host == "::1" || host == "localhost"

    fun onVideoSurfaceDestroyed() {
        player.stop()
    }

    /**
     * Key events are forwarded exactly as the user produces them. DOWN on touch-down, UP on
     * touch-up — the time in between belongs to the MFD's own auto-repeat, which is what makes
     * holding an arrow key sweep the cursor across the screen.
     */
    fun keyDown(key: MfdKey) {
        noteSimAction(key.actionLabel())
        sendButton(key, KeyAction.DOWN)
    }

    fun keyUp(key: MfdKey) = sendButton(key, KeyAction.UP)

    private fun sendButton(key: MfdKey, action: KeyAction) {
        val endpoint = connectedEndpoint() ?: return
        rrc.send(Rrc.button(key, action, endpoint.rrcVersion))
    }

    /**
     * A dial-ring step. The zoom opcode changes the chart range — it is not a pointer, however
     * much its payload looks like coordinates. Positive steps zoom in, negative out.
     */
    fun zoomStep(step: Int, accumulated: Int) {
        noteSimAction(if (step > 0) "Zoom in" else "Zoom out")
        val endpoint = connectedEndpoint() ?: return
        rrc.send(Rrc.zoom(step, accumulated, endpoint.rrcVersion))
    }

    // ---- touch on the video -----------------------------------------------------------------
    // Positions arrive in view pixels and leave normalised 0..65535 across the video area.
    // The touch opcode is expected to work on HybridTouch units (the reference device is one);
    // the dial's arrow keys remain the first-class fallback for keypad-only models.

    private var touchGesture: TouchGesture? = null

    fun videoTouchDown(x: Float, y: Float, width: Int, height: Int) {
        val endpoint = connectedEndpoint() ?: return
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

    /**
     * The session's endpoint whatever the link is doing right now — what the *video pipeline*
     * keys on, because video is brought up in parallel with control and owns its own retries.
     */
    private fun currentEndpoint(): MfdEndpoint? = when (val s = connection.value) {
        is ConnectionState.Connected -> s.endpoint
        is ConnectionState.Connecting -> s.endpoint
        is ConnectionState.Reconnecting -> s.endpoint
        ConnectionState.Idle -> null
    }

    /**
     * The endpoint only while the control socket is actually open — what every *command* path
     * keys on. During Connecting/Reconnecting the controls are shown dimmed and must genuinely do
     * nothing: queueing a key press against a link that is down would either vanish silently or,
     * worse, arrive as a stale command after the reconnect. The UI dims the controls to say so;
     * this guard is what makes the promise true regardless of what the UI shows.
     */
    private fun connectedEndpoint(): MfdEndpoint? =
        (connection.value as? ConnectionState.Connected)?.endpoint

    private companion object {
        // Long enough for a healthy network to answer, short enough not to feel hung. Manual
        // connect is always one tap away, so this is a nudge toward it, not a wall.
        const val DISCOVERY_WINDOW_MS = 12_000L

        /** How many remembered displays the connect screen offers as buttons. */
        const val RECENT_BUTTONS = 4
    }
}

/**
 * Read a persisted palette name.
 *
 * The enum constants were renamed once (`DAY` and `DUSK` became [HelmPalette.HIGH_CONTRAST] and
 * [HelmPalette.DARK]), and the name is what gets written to disk. Without the legacy mapping
 * every existing install would silently fall back to the default on upgrade, which for anyone
 * who had chosen high contrast means the app quietly stops being readable in sun.
 *
 * Top-level rather than a ViewModel method so the upgrade mapping — pure string logic with
 * compatibility semantics — is testable on the JVM (`ParsePaletteTest`).
 */
internal fun parsePalette(saved: String?): HelmPalette? = when (saved) {
    null -> null
    "DAY" -> HelmPalette.HIGH_CONTRAST
    "DUSK" -> HelmPalette.DARK
    else -> HelmPalette.entries.firstOrNull { it.name == saved }
}
