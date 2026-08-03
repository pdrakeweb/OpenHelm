package dev.openhelm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openhelm.app.rrc.ConnectionState
import dev.openhelm.app.ui.icons.MfdIcons
import dev.openhelm.app.video.VideoState

/**
 * The remote: a status bar, and either the side-by-side view (video left, always-on control panel
 * right, full height — the display's 5:3 picture leaves that column free in landscape) or the
 * control-only keypad when video is off. Control-only is a first-class mode: it is the fallback
 * whenever video is broken.
 */
@Composable
fun RemoteScreen(viewModel: MainViewModel, state: ConnectionState, palette: HelmPalette) {
    LockLandscape()
    // While a session is live the system bars get out of the way — this is a mounted display, and
    // the gesture pill otherwise sits over the bottom edge of the video.
    ImmersiveWhileConnected()
    // Held for the whole session, video or keypad. A helm-mounted phone gets few touches of its
    // own and must not blank mid-passage.
    KeepScreenOn()

    var confirmDisconnect by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // Back walks the modes before it tears anything down: full-screen remote → side-by-side →
    // confirm disconnect. Nothing here drops the session by surprise. Disabled while Settings is
    // open, which brings its own handler and must be what Back dismisses.
    BackHandler(enabled = !showSettings) {
        if (!viewModel.mirroring) viewModel.selectMirroring(true) else confirmDisconnect = true
    }

    // While the control link is down — connecting or between reconnect attempts — the command
    // controls are dimmed and inert. The ViewModel already refuses to send while not Connected
    // (the guard that makes it true); this is the treatment that makes it *visible*, so a key
    // that will do nothing does not look like a key that will. The status bar stays live: the
    // palette cycle, the mode switch and Disconnect must all keep working mid-outage.
    val controlsDisabled = state !is ConnectionState.Connected

    // No bar across the top any more. The session's four actions are a rail of round buttons down
    // the outside edge, which hands the ~64dp the bar occupied back to the picture — and since the
    // picture is a fixed 5:3 that is usually height-bound in a landscape window, height back means
    // a bigger picture on *both* axes. What the bar used to say is now said over the video, and
    // only when there is something worth saying.
    val rail: @Composable () -> Unit = {
        HelmActionRail(
            palette = palette,
            mirroring = viewModel.mirroring,
            onCyclePalette = { viewModel.cyclePalette(palette) },
            onSelectMirroring = viewModel::selectMirroring,
            exitIcon = MfdIcons.Disconnect,
            exitLabel = "Disconnect from the display",
            onExit = { confirmDisconnect = true },
            onSettings = { showSettings = true },
        )
    }

    if (viewModel.mirroring) {
        Row(Modifier.fillMaxSize().padding(horizontal = HelmEdgeInset)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                VideoPane(viewModel, palette, Modifier.fillMaxSize())
                ConnectionBanner(
                    viewModel = viewModel,
                    state = state,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )
            }
            DimmedWhenDisabled(controlsDisabled, Modifier.fillMaxHeight()) {
                SidePanel(viewModel)
            }
            rail()
        }
    } else {
        Row(Modifier.fillMaxSize().padding(horizontal = HelmEdgeInset)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                DimmedWhenDisabled(controlsDisabled, Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Keypad(
                            onKeyDown = viewModel::keyDown,
                            onKeyUp = viewModel::keyUp,
                            onRotate = viewModel::zoomStep,
                        )
                    }
                }
                ConnectionBanner(
                    viewModel = viewModel,
                    state = state,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )
            }
            rail()
        }
    }

    if (showSettings) {
        SessionSettingsOverlay(viewModel, palette) { showSettings = false }
    }

    if (confirmDisconnect) {
        DisconnectConfirmation(
            onConfirm = {
                confirmDisconnect = false
                viewModel.disconnect()
            },
            onDismiss = { confirmDisconnect = false },
        )
    }
}

/**
 * Settings, laid **over** a live session rather than navigated to.
 *
 * The distinction is load-bearing, not stylistic. Routing away from the remote screen would take
 * the video pane out of composition, which destroys its `SurfaceTexture`, which stops the player —
 * so a trip to Settings would cost a video restart and a wait for the next keyframe on the way
 * back. Composed on top, the pane underneath is merely covered: the control socket, the RTSP
 * session and the decoder all keep running, and dismissing this reveals a picture that never
 * stopped.
 *
 * Opaque and touch-consuming: the rail and the panel are still there behind it, and a stray tap
 * landing on Disconnect through a settings screen would be a genuinely bad surprise.
 */
@Composable
private fun SessionSettingsOverlay(
    viewModel: MainViewModel,
    palette: HelmPalette,
    onDone: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
    ) {
        SettingsScreen(viewModel, palette, onDone = onDone)
    }
}

/**
 * Dims its content and swallows its touches while [disabled].
 *
 * This is the visual half of the "controls do nothing while the link is down" contract — the
 * ViewModel's Connected-only send guard is the functional half. Both exist because either alone
 * lies: a guard without the dim leaves keys that look live but do nothing (indistinguishable from
 * a broken app), and a dim without the guard is a promise the code doesn't keep. The alpha is a
 * mild grey-out, not a blackout: the panel should still read as "your controls, temporarily
 * resting", with the status line above saying why.
 *
 * Touches are consumed, not just ignored: the keys underneath have their own pointer handlers and
 * press animations, and a key that flashes and buzzes while doing nothing teaches the user it is
 * broken. Every gesture is eaten whole here, the same way the stale-video scrim does it.
 */
@Composable
private fun DimmedWhenDisabled(
    disabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier) {
        Box(Modifier.graphicsLayer { alpha = if (disabled) DISABLED_CONTROLS_ALPHA else 1f }) {
            content()
        }
        if (disabled) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                                if (event.changes.none { it.pressed }) break
                            }
                        }
                    },
            )
        }
    }
}

/** Dim, not invisible: the panel must still be findable, only unmistakably not-live. */
private const val DISABLED_CONTROLS_ALPHA = 0.45f

/**
 * Ending the session is destructive and used to be a bare text link a thumb-width from the video
 * toggle. A mis-tap mid-manoeuvre killed the control channel, so it now asks first.
 */
@Composable
private fun DisconnectConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disconnect from the display?") },
        text = { Text("You'll stop controlling the display and return to the connect screen.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Disconnect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Stay connected") } },
    )
}

/**
 * Connection state in words, over the top-left of whatever the screen is showing.
 *
 * Renders **nothing at all** when the session is healthy and quiet. This replaced a permanent bar
 * across the top: four buttons and a line of text that, in the ordinary case, said only that
 * everything was fine — while costing the picture the height it most needed. A readout that is
 * silent when there is nothing to report is the one that gets read when there is.
 *
 * State is never carried by colour alone — each one is spelled out, because colour is the first
 * thing to wash out in direct sun and roughly 8% of men have some colour-vision deficiency.
 */
@Composable
private fun ConnectionBanner(
    viewModel: MainViewModel,
    state: ConnectionState,
    modifier: Modifier = Modifier,
) {
    val (text, color) = connectionStatus(viewModel, state)
    if (text.isEmpty()) return
    Text(
        text = text,
        modifier = modifier
            .background(
                color = Color(0xB3000000),
                shape = MaterialTheme.shapes.small,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        maxLines = 2,
    )
}

@Composable
private fun connectionStatus(
    viewModel: MainViewModel,
    state: ConnectionState,
): Pair<String, Color> {
    val videoState by viewModel.videoState.collectAsStateWithLifecycle()

    return when (state) {
        is ConnectionState.Connected -> {
            // "Connected" beside a pane that is still a spinner is a mixed signal. Control and
            // video come up independently, so the line says which of the two is actually live.
            val videoPending = viewModel.mirroring &&
                (videoState is VideoState.Connecting || videoState is VideoState.Idle)
            when {
                videoPending ->
                    "Controls ready · starting video…" to MaterialTheme.colorScheme.onSurfaceVariant
                // A working session says nothing. The address is engineering detail the user
                // already knows — they are looking at the display — and a status bar that only
                // speaks when something is wrong is one that gets read when it does.
                viewModel.showDiagnostics ->
                    "Connected · ${state.endpoint.host}" to MaterialTheme.colorScheme.secondary
                else -> "" to MaterialTheme.colorScheme.secondary
            }
        }

        is ConnectionState.Connecting ->
            "Connecting to ${state.endpoint.host}…" to MaterialTheme.colorScheme.onSurfaceVariant

        is ConnectionState.Reconnecting ->
            "Reconnecting · ${friendlyReason(state.reason)}" to MaterialTheme.colorScheme.error

        ConnectionState.Idle -> "" to MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/**
 * Turn a transport-level failure into something a person can act on.
 *
 * The raw text comes from socket exceptions — "Socket closed", "Connection reset by peer" — which
 * is implementation jargon to be surfacing as primary status on a boat. Anything unrecognised is
 * passed through rather than swallowed, so a genuinely novel failure is still visible.
 */
internal fun friendlyReason(reason: String): String {
    val r = reason.lowercase()
    return when {
        "socket closed" in r || "closed by the display" in r -> "the display closed the connection"
        "reset" in r -> "the connection dropped"
        "timed out" in r || "timeout" in r -> "the display stopped responding"
        "refused" in r -> "the display refused the connection"
        "unreachable" in r -> "the display is unreachable"

        // An unrecognised failure is still shown — swallowing it would hide a novel fault — but it
        // is sanitised first. This string ends up in the safety banner over a frozen chart, and it
        // originates in bytes from the network: an RTSP reason-phrase or a server error string.
        // Unsanitised, a hostile or merely broken display could put newlines and arbitrary text
        // into the one part of the UI whose whole job is to be believed.
        else -> sanitiseReason(reason)
    }
}

/**
 * Flatten a server-supplied string to a single short line of printable characters.
 *
 * Control characters (including the newlines that would let injected text pose as a second,
 * app-authored sentence) become spaces, runs of whitespace collapse, and the result is clipped —
 * a banner is not a log viewer, and an unbounded string would push the real message off screen.
 */
private fun sanitiseReason(reason: String): String {
    val flattened = reason.asSequence()
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()
    return when {
        flattened.isEmpty() -> "the connection failed"
        flattened.length > MAX_REASON_CHARS -> flattened.take(MAX_REASON_CHARS).trimEnd() + "…"
        else -> flattened
    }
}

private const val MAX_REASON_CHARS = 120
