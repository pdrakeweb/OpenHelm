package dev.openhelm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
 * The remote itself: a status line and the keypad. This is the control-only mode that phase 1
 * ships — genuinely useful on its own, and the fallback whenever video is broken.
 */
@Composable
fun RemoteScreen(viewModel: MainViewModel, state: ConnectionState) {
    BackHandler { viewModel.disconnect() }

    Column(Modifier.fillMaxSize()) {
        StatusBar(viewModel, state)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Keypad(
                onKeyDown = viewModel::keyDown,
                onKeyUp = viewModel::keyUp,
            )
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
            .padding(horizontal = 16.dp, vertical = 4.dp),
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
        TextButton(onClick = viewModel::disconnect) { Text("Disconnect") }
    }
}
