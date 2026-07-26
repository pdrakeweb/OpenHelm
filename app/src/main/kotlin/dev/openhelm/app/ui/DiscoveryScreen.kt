package dev.openhelm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openhelm.app.video.RtpTransport
import dev.openhelm.protocol.MfdEndpoint

/**
 * The entry screen. Discovery runs on its own, but it never dead-ends: manual address entry sits
 * on the same screen always, remembered displays offer one-tap reconnect, and a search that turns
 * up nothing resolves to a plain-language "couldn't find a display" with a way forward — not an
 * endless spinner and not a modal that quits the app on an outside tap.
 */
@Composable
fun DiscoveryScreen(viewModel: MainViewModel) {
    val discovered by viewModel.discovered.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val timedOut by viewModel.discoveryTimedOut.collectAsStateWithLifecycle()
    val remembered by viewModel.remembered.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        viewModel.startDiscovery()
        onDispose { viewModel.stopDiscovery() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("OpenHelm", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = viewModel.manualText,
            onValueChange = viewModel::onManualTextChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Display address") },
            placeholder = { Text("192.168.131.1") },
            supportingText = {
                if (viewModel.manualText.isNotBlank() && viewModel.manualEndpoint == null) {
                    Text("Enter the display's IP address — standard ports are filled in for you")
                } else {
                    Text("Some boat networks block automatic discovery; entering the address directly is normal")
                }
            },
            singleLine = true,
        )

        Button(
            onClick = viewModel::connectManual,
            enabled = viewModel.manualEndpoint != null,
        ) {
            Text("Connect")
        }

        DiscoveryStatus(
            searching = searching,
            timedOut = timedOut,
            found = discovered.isNotEmpty(),
            onSearchAgain = viewModel::startDiscovery,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Video transport:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = viewModel::toggleTransport) {
                Text(
                    when (viewModel.transport) {
                        RtpTransport.UDP -> "UDP (displays require this)"
                        RtpTransport.TCP_INTERLEAVED -> "TCP — simulator only, hangs a real display"
                    },
                )
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (remembered.isNotEmpty()) {
                item {
                    Text(
                        "Recent",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(remembered, key = { "recent-${it.host}" }) { endpoint ->
                    EndpointCard(endpoint, onConnect = { viewModel.connect(endpoint) })
                }
            }
            if (discovered.isNotEmpty()) {
                item {
                    Text(
                        "Found on the network",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(discovered, key = { "found-${it.host}" }) { endpoint ->
                    EndpointCard(endpoint, onConnect = { viewModel.connect(endpoint) })
                }
            }
        }
    }
}

@Composable
private fun DiscoveryStatus(
    searching: Boolean,
    timedOut: Boolean,
    found: Boolean,
    onSearchAgain: () -> Unit,
) {
    when {
        searching -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Searching for displays…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }

        timedOut && !found -> Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Couldn't find a display", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Some boat networks block automatic discovery. If you know the display's " +
                        "address, enter it above — that's a normal way to connect, not a fallback.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onSearchAgain) { Text("Search again") }
            }
        }
    }
}

@Composable
private fun EndpointCard(endpoint: MfdEndpoint, onConnect: () -> Unit) {
    Card(
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                endpoint.model ?: "Display",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${endpoint.host} · control ${endpoint.rrcPort} · video ${endpoint.rtspPort}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            endpoint.serial?.let {
                Text(
                    "S/N $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
