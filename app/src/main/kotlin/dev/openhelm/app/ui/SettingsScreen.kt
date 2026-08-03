package dev.openhelm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.key
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
fun SettingsScreen(
    viewModel: MainViewModel,
    palette: HelmPalette,
    /**
     * How to leave. Parameterised because this screen is reached two ways: pushed over the connect
     * screen, where leaving means going back to it, and laid over a *live session*, where leaving
     * must do nothing but dismiss — the session underneath has to be untouched.
     */
    onDone: () -> Unit = viewModel::backToConnect,
) {
    BackHandler(onBack = onDone)
    val remembered by viewModel.remembered.collectAsStateWithLifecycle()

    // The header is pinned and only the content below it scrolls.
    //
    // This screen used to be a plain Column with no scrolling at all, so on any window shorter
    // than its content — a phone in landscape reaches it in about half the height it needs — the
    // remembered displays were simply clipped away with no way to reach them. That is the same
    // defect ManualConnectScreen was fixed for, and the *worse* version of it: a name field you
    // cannot scroll to is a feature that silently does not exist.
    //
    // Note this cannot be fixed by wrapping the old Column in `verticalScroll`: it contained a
    // LazyColumn, and a lazy list inside a vertically-scrolling parent is measured with infinite
    // height and throws. One LazyColumn for the whole content is the fix, not a nested pair.
    //
    // Done stays outside the scroll deliberately. It is the way off this screen, and an exit that
    // can scroll out of sight is the thing you most need when the content is too tall to fit.
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
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
                onClick = onDone,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(key = "palette") {
                PaletteSection(
                    palette = palette,
                    onSelect = viewModel::selectPalette,
                )
            }

            item(key = "simulation") {
                SimulationSection(
                    enabled = viewModel.simulationMode,
                    onToggle = { on -> if (on) viewModel.enterSimulation() else viewModel.exitSimulation() },
                )
            }

            item(key = "delay") {
                DelaySection(
                    mode = viewModel.delayNotification,
                    thresholdSeconds = viewModel.delayThresholdSeconds,
                    onSelectMode = viewModel::selectDelayNotification,
                    onSelectThreshold = viewModel::selectDelayThreshold,
                )
            }

            item(key = "diagnostics") {
                DiagnosticsSection(
                    enabled = viewModel.showDiagnostics,
                    onToggle = viewModel::selectDiagnostics,
                )
            }

            // The whole Displays section is one item so it keeps its own internal rhythm — the
            // cards belong to each other more closely than the top-level sections do, which the
            // list's 24dp arrangement would have flattened. Not lazily composed, and deliberately
            // so: MAX_REMEMBERED caps this at 12 rows, where laziness buys nothing and costs the
            // grouping.
            item(key = "displays") {
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
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            remembered.forEach { display ->
                                key(display.endpoint.host) {
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

            // IntrinsicSize.Max so all three match the tallest, rather than each sizing to its own
            // caption: "Direct sun" is one line and the others wrap to two, which left the row
            // looking like three unrelated buttons.
            Row(
                Modifier.height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PaletteChoice(HelmPalette.HIGH_CONTRAST, "Bright", "Direct sun", palette, onSelect, Modifier.weight(1f))
                PaletteChoice(HelmPalette.DARK, "Dark", "Overcast, below decks", palette, onSelect, Modifier.weight(1f))
                PaletteChoice(HelmPalette.NIGHT, "Night", "Red, night vision", palette, onSelect, Modifier.weight(1f))
            }
        }
    }
}

/**
 * One palette option.
 *
 * Selection is carried by a **tick and a border**, plus a spoken selected state — never by fill
 * alone. A filled `Button` for the chosen one was the first attempt and made the selected chip the
 * brightest object on the screen, which is a poor way to present the control whose job is removing
 * bright objects. Falling back to the container colour was the second, and failed differently: in
 * the bright palette every container is a dark slab by design, so `primaryContainer` and
 * `secondaryContainer` sit within a shade of each other and the selected chip was
 * indistinguishable. The tick owes nothing to the palette, and the border is drawn in the
 * container's *content* colour so it contrasts with the chip by construction in all three.
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
            .fillMaxHeight()
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
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.onPrimaryContainer)
        } else {
            null
        },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    Icon(MfdIcons.Confirm, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.size(4.dp))
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
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

/**
 * When to be told the picture has fallen behind, and by how much before it counts.
 *
 * The two halves belong together: "when late" is meaningless without saying what late is, and a
 * threshold is meaningless if the readout never appears. They are one card for that reason.
 *
 * The threshold stays visible and usable in **Always** as well as **When late** — in Always it is
 * what turns the readout amber — and is only greyed for **Off**, where nothing is shown at all.
 */
@Composable
private fun DelaySection(
    mode: DelayNotification,
    thresholdSeconds: Int,
    onSelectMode: (DelayNotification) -> Unit,
    onSelectThreshold: (Int) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(
                    "Delay warning",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    "The mirrored picture always trails the display a little. This says how far " +
                        "behind it is, in the corner of the video.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // IntrinsicSize.Max so the three match the tallest rather than each sizing to its own
            // caption — the same reason the palette row does it.
            Row(
                Modifier.height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DelayNotification.entries.forEach { option ->
                    DelayModeChoice(
                        value = option,
                        current = mode,
                        onSelect = onSelectMode,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            val thresholdEnabled = mode != DelayNotification.OFF
            Column {
                Text(
                    if (mode == DelayNotification.ALWAYS) "Count as late after" else "Show it after",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (thresholdEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DelayThresholdChoices.forEach { seconds ->
                        val selected = seconds == thresholdSeconds
                        FilledTonalButton(
                            onClick = { onSelectThreshold(seconds) },
                            enabled = thresholdEnabled,
                            modifier = Modifier
                                .height(MinHelmTarget)
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
                            border = if (selected) {
                                BorderStroke(2.dp, MaterialTheme.colorScheme.onPrimaryContainer)
                            } else {
                                null
                            },
                        ) {
                            Text(
                                "${seconds}s",
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One of the three delay-notification options.
 *
 * Marked by a tick and a border rather than fill alone, for the same reason the palette choices
 * are: in the bright palette every container is a dark slab by design, so selection carried by
 * container colour alone is invisible exactly where legibility matters most.
 */
@Composable
private fun DelayModeChoice(
    value: DelayNotification,
    current: DelayNotification,
    onSelect: (DelayNotification) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = value == current
    FilledTonalButton(
        onClick = { onSelect(value) },
        modifier = modifier
            .fillMaxHeight()
            .heightIn(min = 76.dp)
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
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.onPrimaryContainer) else null,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    Icon(MfdIcons.Confirm, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.size(4.dp))
                }
                Text(
                    value.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
            Text(
                value.detail,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/**
 * The engineering readouts, as a setting rather than a build flag.
 *
 * Unlike simulation mode this **is** persisted: it gets turned on because something is wrong, and
 * whatever is wrong is very likely to involve reconnecting or relaunching. A diagnostic that
 * switches itself off during the fault you are chasing is not a diagnostic.
 */
@Composable
private fun DiagnosticsSection(enabled: Boolean, onToggle: (Boolean) -> Unit) {
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
                    "Show diagnostics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    "Adds the display's address to the status bar and a frame-rate, queue, decode " +
                        "and packet-loss readout over the picture. Useful when video misbehaves; " +
                        "clutter when it doesn't.",
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
