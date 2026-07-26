package dev.openhelm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openhelm.app.video.RtpTransport

/**
 * Typing in a display's address, for the networks where discovery cannot work. Kept on its own
 * screen so the front door stays a single scanning state.
 *
 * The video transport lives here and only here: UDP is what every real display serves, and the
 * interleaved-TCP option exists solely to let a simulator behind an Android emulator's NAT deliver
 * frames. Putting it on the main screen would invite someone to change it on a boat, where it
 * produces a connection that establishes and then never shows a picture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualConnectScreen(viewModel: MainViewModel) {
    BackHandler { viewModel.backToConnect() }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Manual connect",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Light,
            )
            TextButton(onClick = viewModel::backToConnect) { Text("Back") }
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                OutlinedTextField(
                    value = viewModel.manualText,
                    onValueChange = viewModel::onManualTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Display address") },
                    placeholder = { Text("192.168.131.1") },
                    supportingText = {
                        Text(
                            if (viewModel.manualText.isNotBlank() && viewModel.manualEndpoint == null) {
                                "Enter the display's IP address — standard ports are filled in for you"
                            } else {
                                "The display's IP address is enough; standard ports are assumed"
                            },
                        )
                    },
                    singleLine = true,
                )

                TransportDropdown(
                    selected = viewModel.transport,
                    onSelect = viewModel::selectTransport,
                )

                Button(
                    onClick = {
                        viewModel.connectManual()
                    },
                    enabled = viewModel.manualEndpoint != null,
                    modifier = Modifier.height(52.dp).widthIn(min = 160.dp),
                ) {
                    Text("Connect", fontSize = 16.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransportDropdown(selected: RtpTransport, onSelect: (RtpTransport) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Video transport") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            supportingText = {
                Text(
                    if (selected == RtpTransport.UDP) {
                        "Correct for every real display"
                    } else {
                        "Testing only — a real display accepts this and then never sends a picture"
                    },
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RtpTransport.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
