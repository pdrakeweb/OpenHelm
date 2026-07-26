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
 * The entry screen. Manual address entry sits above the discovered list and is always visible:
 * mDNS on boat Wi-Fi fails often enough that typing the address is a normal way in, not a
 * fallback buried behind a failure.
 */
@Composable
fun DiscoveryScreen(viewModel: MainViewModel) {
    val discovered by viewModel.discovered.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()

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

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (searching) "Searching for displays…" else "Discovery stopped",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (searching) {
                Spacer(Modifier.size(12.dp))
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }

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
            items(discovered, key = { it.host }) { endpoint ->
                DiscoveredCard(endpoint, onConnect = { viewModel.connect(endpoint) })
            }
        }
    }
}

@Composable
private fun DiscoveredCard(endpoint: MfdEndpoint, onConnect: () -> Unit) {
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
