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
 * Naming and forgetting remembered displays. The vendor's model string is abbreviated (`E9` for
 * what is really an e95), so a human name is genuinely useful: this is where "E9" becomes "Helm"
 * or "Cockpit". The full endpoint is untouched — only the label the user sees changes, and that
 * label is all the connect screen shows.
 */
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    BackHandler { viewModel.backToConnect() }
    val remembered by viewModel.remembered.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Manage displays",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Light,
            )
            TextButton(onClick = viewModel::backToConnect) { Text("Done") }
        }

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
