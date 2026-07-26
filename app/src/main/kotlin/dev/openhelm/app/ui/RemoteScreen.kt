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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.openhelm.app.rrc.ConnectionState

/**
 * The remote: a status line, and either the side-by-side view (video left, always-on control
 * panel right, full height — the display's 5:3 picture leaves that column free on a phone in
 * landscape) or the control-only keypad when video is off. Control-only is a first-class mode:
 * it is the fallback whenever video is broken, and the whole app in phase 1.
 */
@Composable
fun RemoteScreen(viewModel: MainViewModel, state: ConnectionState) {
    BackHandler { viewModel.disconnect() }

    Column(Modifier.fillMaxSize()) {
        StatusBar(viewModel, state)
        if (viewModel.videoEnabled) {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                VideoPane(viewModel, Modifier.weight(1f).fillMaxHeight())
                Box(
                    Modifier
                        .width(300.dp)
                        .fillMaxHeight()
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Keypad(
                        onKeyDown = viewModel::keyDown,
                        onKeyUp = viewModel::keyUp,
                        keySize = 56.dp,
                        arrangement = KeypadArrangement.STACKED,
                    )
                }
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
}

@Composable
private fun StatusBar(viewModel: MainViewModel, state: ConnectionState) {
    val (text, color) = when (state) {
        is ConnectionState.Connected ->
            "Connected · ${state.endpoint.host}" to MaterialTheme.colorScheme.secondary

        is ConnectionState.Connecting ->
            "Connecting to ${state.endpoint.host}…" to MaterialTheme.colorScheme.onSurfaceVariant

        is ConnectionState.Reconnecting ->
            "Reconnecting · ${state.reason}" to MaterialTheme.colorScheme.error

        ConnectionState.Idle -> "" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = viewModel::toggleVideo) {
            Text(if (viewModel.videoEnabled) "Video off" else "Video on")
        }
        TextButton(onClick = viewModel::disconnect) { Text("Disconnect") }
    }
}
