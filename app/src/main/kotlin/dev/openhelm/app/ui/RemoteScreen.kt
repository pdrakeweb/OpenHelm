package dev.openhelm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
fun RemoteScreen(viewModel: MainViewModel, state: ConnectionState) {
    LockLandscape()
    // While a session is live the system bars get out of the way — this is a mounted display, and
    // the gesture pill otherwise sits over the bottom edge of the video.
    ImmersiveWhileConnected()

    var confirmDisconnect by remember { mutableStateOf(false) }

    // Back walks the modes before it tears anything down: full-screen remote → side-by-side →
    // confirm disconnect. Nothing here drops the session by surprise.
    BackHandler {
        if (!viewModel.videoEnabled) viewModel.toggleVideo() else confirmDisconnect = true
    }

    Column(Modifier.fillMaxSize()) {
        RemoteStatusBar(
            viewModel = viewModel,
            state = state,
            onDisconnectRequest = { confirmDisconnect = true },
        )
        if (viewModel.videoEnabled) {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                VideoPane(viewModel, Modifier.weight(1f).fillMaxHeight())
                SidePanel(viewModel)
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Keypad(
                    onKeyDown = viewModel::keyDown,
                    onKeyUp = viewModel::keyUp,
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
    onDisconnectRequest: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConnectionStatusText(viewModel, state, Modifier.weight(1f))

        HelmActionButton(
            icon = if (viewModel.videoEnabled) MfdIcons.VideoOff else MfdIcons.VideoOn,
            label = if (viewModel.videoEnabled) "Video off" else "Video on",
            onClick = viewModel::toggleVideo,
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
            val videoPending = viewModel.videoEnabled &&
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
 */
@Composable
private fun HelmActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val contentColor =
        if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    TextButton(
        onClick = onClick,
        modifier = Modifier
            .height(MinHelmTarget)
            .width(140.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = contentColor, maxLines = 1)
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
        else -> reason
    }
}
