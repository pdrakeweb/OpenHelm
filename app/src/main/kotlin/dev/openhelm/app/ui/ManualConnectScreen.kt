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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import dev.openhelm.app.BuildConfig
import dev.openhelm.app.ui.icons.MfdIcons
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

    val invalid = viewModel.manualText.isNotBlank() && viewModel.manualEndpoint == null
    val empty = viewModel.manualText.isBlank()

    Column(
        Modifier
            .fillMaxSize()
            // Compact-height landscape put Connect below the fold with no way to reach it, and
            // the IME covered the field it was editing. Both are scroll/inset problems.
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
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
            NavActionButton(
                icon = MfdIcons.Back,
                label = "Back",
                onClick = viewModel::backToConnect,
            )
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                // M3's readable-width guidance: a field stretched across a 10.9" tablet is neither
                // readable nor reachable.
                Modifier.widthIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                OutlinedTextField(
                    value = viewModel.manualText,
                    onValueChange = viewModel::onManualTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Display address") },
                    placeholder = { Text("192.168.131.1") },
                    isError = invalid,
                    supportingText = {
                        Text(
                            if (invalid) {
                                "That doesn't look like an address. Enter the display's IP, " +
                                    "e.g. 192.168.131.1"
                            } else {
                                "The display's IP address is enough; standard ports are assumed"
                            },
                        )
                    },
                    singleLine = true,
                )

                // Debug builds only. The alternative transport exists solely so a simulator behind
                // an emulator's NAT can deliver frames; against a real display it establishes a
                // session and then never sends a picture, so shipping it as a user-facing choice
                // is offering a setting whose only effect is to break video.
                if (BuildConfig.DEBUG) {
                    TransportDropdown(
                        selected = viewModel.transport,
                        onSelect = viewModel::selectTransport,
                    )
                }

                Button(
                    onClick = viewModel::connectManual,
                    enabled = viewModel.manualEndpoint != null,
                    modifier = Modifier.height(MinHelmTarget).widthIn(min = 160.dp),
                ) {
                    Text("Connect", fontSize = 16.sp)
                }

                // Say *why* the primary action won't fire. A disabled button with no explanation
                // is the app's one primary action failing silently.
                if (empty || invalid) {
                    Text(
                        text = if (empty) {
                            "Enter the display's address to connect."
                        } else {
                            "Fix the address above to connect."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
