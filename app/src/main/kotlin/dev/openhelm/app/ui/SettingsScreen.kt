package dev.openhelm.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openhelm.app.config.RememberedDisplay

/**
 * Settings: exploring the app without a display, and managing the ones already known.
 *
 * The vendor's model string is abbreviated (`E9` for what is really an e95), so naming a display is
 * genuinely useful — this is where "E9" becomes "Helm" or "Cockpit". The full endpoint is
 * untouched by naming; only the label the user sees changes, and that label is all the connect
 * screen shows.
 */
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    BackHandler { viewModel.backToConnect() }
    val remembered by viewModel.remembered.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Light,
            )
            TextButton(onClick = viewModel::backToConnect) { Text("Done") }
        }

        SimulationSection(
            enabled = viewModel.simulationMode,
            onToggle = { on -> if (on) viewModel.enterSimulation() else viewModel.exitSimulation() },
        )

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Displays",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )

            if (remembered.isEmpty()) {
                Text(
                    "No remembered displays yet. Once you connect to one, it appears here to name or remove.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(remembered, key = { it.endpoint.host }) { display ->
                        DisplaySettingRow(
                            display = display,
                            onSaveName = { viewModel.renameDisplay(display.endpoint.host, it) },
                            onForget = { viewModel.forgetDisplay(display.endpoint.host) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Simulation mode drops straight into a fake connected session — animated video, working controls,
 * nothing sent over the network — so the app can be explored without a display. Deliberately **not
 * saved**: the switch always starts off, so leaving Settings or relaunching never leaves a
 * simulated session running silently in the background.
 */
@Composable
private fun SimulationSection(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Simulation mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    "Explore the app with a fake video feed and working controls — nothing is " +
                        "sent to a real display. Turns off automatically; it is never remembered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(16.dp))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun DisplaySettingRow(
    display: RememberedDisplay,
    onSaveName: (String) -> Unit,
    onForget: () -> Unit,
) {
    // Local edit buffer keyed to this host, so typing does not write DataStore on every keystroke;
    // the change is committed with Save.
    var name by rememberSaveable(display.endpoint.host) { mutableStateOf(display.name ?: "") }
    val dirty = name.trim() != (display.name ?: "")

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Name") },
                placeholder = { Text(display.endpoint.model ?: "Display") },
                singleLine = true,
            )
            Text(
                buildString {
                    append(display.endpoint.host)
                    display.endpoint.model?.let { append(" · model ").append(it) }
                    display.endpoint.serial?.let { append(" · S/N ").append(it) }
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onSaveName(name) }, enabled = dirty) { Text("Save") }
                Spacer(Modifier.size(4.dp))
                TextButton(onClick = onForget) { Text("Forget") }
            }
        }
    }
}
