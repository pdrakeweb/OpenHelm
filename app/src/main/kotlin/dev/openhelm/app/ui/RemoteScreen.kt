package dev.openhelm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
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

    // Back walks the modes before it tears anything down: full-screen remote → side-by-side →
    // confirm disconnect. Nothing here drops the session by surprise.
    BackHandler {
        if (!viewModel.mirroring) viewModel.selectMirroring(true) else confirmDisconnect = true
    }

    // The status bar sits over the picture, not across the whole window, so the control panel
    // starts at the top edge and gets the full height. That is worth more than the tidiness of a
    // full-width bar: the panel's size band is chosen from the height it is handed, so the ~64dp
    // the bar used to take off the top was coming straight out of every key and the dial.
    if (viewModel.mirroring) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                RemoteStatusBar(
                    viewModel = viewModel,
                    state = state,
                    palette = palette,
                    onDisconnectRequest = { confirmDisconnect = true },
                )
                VideoPane(viewModel, palette, Modifier.weight(1f).fillMaxWidth())
            }
            SidePanel(viewModel)
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            RemoteStatusBar(
                viewModel = viewModel,
                state = state,
                palette = palette,
                onDisconnectRequest = { confirmDisconnect = true },
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Keypad(
                    onKeyDown = viewModel::keyDown,
                    onKeyUp = viewModel::keyUp,
                    onRotate = viewModel::zoomStep,
                )
            }
        }
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
 * The status bar: what the connection is doing, in words, plus the two mode actions as real
 * buttons rather than the bare text links they used to be.
 */
@Composable
private fun RemoteStatusBar(
    viewModel: MainViewModel,
    state: ConnectionState,
    palette: HelmPalette,
    onDisconnectRequest: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(StatusBarHeight)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConnectionStatusText(viewModel, state, Modifier.weight(1f))

        // Deliberately the left-most action, the full width of the group away from Disconnect. It
        // is the one control here someone reaches for in the dark, and a mis-tap must not be able
        // to land on the button that ends the session.
        PaletteButton(palette = palette, onCycle = { viewModel.cyclePalette(palette) })

        MirrorModeSwitch(
            mirroring = viewModel.mirroring,
            onSelect = viewModel::selectMirroring,
        )
        HelmActionButton(
            icon = MfdIcons.Disconnect,
            label = "Disconnect",
            onClick = onDisconnectRequest,
            destructive = true,
        )
    }
}

/**
 * Connection state in words.
 *
 * State is never carried by colour alone — each one is spelled out, because colour is the first
 * thing to wash out in direct sun and roughly 8% of men have some colour-vision deficiency.
 */
@Composable
private fun ConnectionStatusText(
    viewModel: MainViewModel,
    state: ConnectionState,
    modifier: Modifier = Modifier,
) {
    val videoState by viewModel.videoState.collectAsStateWithLifecycle()

    val (text, color) = when (state) {
        is ConnectionState.Connected -> {
            // "Connected" beside a pane that is still a spinner is a mixed signal. Control and
            // video come up independently, so the line says which of the two is actually live.
            val videoPending = viewModel.mirroring &&
                (videoState is VideoState.Connecting || videoState is VideoState.Idle)
            if (videoPending) {
                "Controls ready · starting video…" to MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                "Connected · ${state.endpoint.host}" to MaterialTheme.colorScheme.secondary
            }
        }

        is ConnectionState.Connecting ->
            "Connecting to ${state.endpoint.host}…" to MaterialTheme.colorScheme.onSurfaceVariant

        is ConnectionState.Reconnecting ->
            "Reconnecting · ${friendlyReason(state.reason)}" to MaterialTheme.colorScheme.error

        ConnectionState.Idle -> "" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        maxLines = 2,
    )
}

/**
 * An action on the remote screen: icon and word, sized for a helm. These were bare text links —
 * small, visually identical to each other, and sitting side by side where one ends the session.
 *
 * A filled container, not a [TextButton]: the design-review fix these replaced asked for a visible
 * edge, and a `TextButton` has none, so the first version of this satisfied the letter of the
 * change and not the point of it. The destructive variant is additionally distinguished by its
 * container rather than by tint alone, because tint alone is exactly the cue that disappears in
 * direct sun.
 */
@Composable
private fun HelmActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    width: Dp = 150.dp,
) {
    val colors = if (destructive) {
        ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    } else {
        ButtonDefaults.filledTonalButtonColors()
    }

    FilledTonalButton(
        onClick = onClick,
        colors = colors,
        modifier = Modifier
            .height(MinHelmTarget)
            .width(width),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null, // the adjacent label already names the action
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 1)
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
