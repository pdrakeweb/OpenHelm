package dev.openhelm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openhelm.app.config.RememberedDisplay
import dev.openhelm.app.ui.icons.MfdIcons

/**
 * Settings: exploring the app without a display, and managing the ones already known.
 *
 * The vendor's model string is abbreviated (`E9` for what is really an e95), so naming a display is
 * genuinely useful — this is where "E9" becomes "Helm" or "Cockpit". The full endpoint is
 * untouched by naming; only the label the user sees changes, and that label is all the connect
 * screen shows.
 */
@Composable
fun SettingsScreen(viewModel: MainViewModel, palette: HelmPalette) {
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
            NavActionButton(
                icon = MfdIcons.Confirm,
                label = "Done",
                onClick = viewModel::backToConnect,
            )
        }

        PaletteSection(
            palette = palette,
            onSelect = viewModel::selectPalette,
        )

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
 * Day / dusk / night, as three named choices you can see all of at once.
 *
 * The status bar carries the same setting as a one-tap cycle, because mid-passage is when it is
 * actually needed and a trip through Settings is not on offer then. This is the other half of the
 * pairing: a cycle is fast but it never shows you what the options *are*, and someone setting the
 * boat up alongside wants to see all three named, with a line saying what each is for.
 *
 * Both write the same persisted value, so whichever you touch last is what the app comes back up in.
 */
@Composable
private fun PaletteSection(palette: HelmPalette, onSelect: (HelmPalette) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(
                    "Screen brightness",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    "Also on the remote screen, as a single button that steps through these — the " +
                        "light changes while you are underway, not while you are in here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaletteChoice(HelmPalette.HIGH_CONTRAST, "High contrast", "Direct sun", palette, onSelect, Modifier.weight(1f))
                PaletteChoice(HelmPalette.DARK, "Dark", "Overcast, below decks", palette, onSelect, Modifier.weight(1f))
                PaletteChoice(HelmPalette.NIGHT, "Night", "Red, keeps night vision", palette, onSelect, Modifier.weight(1f))
            }
        }
    }
}

/**
 * One palette option.
 *
 * Selection is carried by a **border** plus a spoken selected state, not by a bright fill. The
 * obvious spelling — a filled `Button` for the chosen one — made the selected chip the single
 * brightest object on the screen, which is a poor way to present the control whose entire job is
 * removing bright objects: choosing Night lit up a large red block. A border reads just as
 * unambiguously and costs no luminance, and the state is announced regardless, so nothing here
 * depends on seeing colour at all.
 */
@Composable
private fun PaletteChoice(
    value: HelmPalette,
    label: String,
    detail: String,
    current: HelmPalette,
    onSelect: (HelmPalette) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = value == current
    val icon = when (value) {
        HelmPalette.HIGH_CONTRAST -> MfdIcons.PaletteDay
        HelmPalette.DARK -> MfdIcons.PaletteDusk
        HelmPalette.NIGHT -> MfdIcons.PaletteNight
    }

    FilledTonalButton(
        onClick = { onSelect(value) },
        modifier = modifier
            .heightIn(min = 84.dp)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                stateDescription = if (selected) "Selected" else "Not selected"
            },
        colors = if (selected) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            ButtonDefaults.filledTonalButtonColors()
        },
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
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
    var confirmForget by remember { mutableStateOf(false) }

    Card(
        // Constrained rather than full-bleed: a name field spanning a 10.9" tablet is neither
        // readable nor reachable.
        Modifier.fillMaxWidth().widthIn(max = 480.dp),
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Save is the affirmative action, so it gets the filled weight. Forget is
                // destructive and stays quiet until it is actually reached for — previously it
                // was the *brighter* of the two, sitting next to a greyed-out Save.
                Button(
                    onClick = { onSaveName(name) },
                    enabled = dirty,
                    modifier = Modifier.height(MinHelmTarget),
                ) { Text("Save") }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { confirmForget = true },
                    modifier = Modifier.height(MinHelmTarget),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Forget") }
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget ${display.label}?") },
            text = {
                Text(
                    "OpenHelm will stop reconnecting to it automatically, and any name you gave " +
                        "it is lost. You can connect to it again from the connect screen.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmForget = false
                        onForget()
                    },
                ) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text("Keep") }
            },
        )
    }
}
