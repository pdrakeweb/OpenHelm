package dev.openhelm.app.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
                    auto = viewModel.paletteAuto,
                    onSelect = viewModel::selectPalette,
                    onToggleAuto = viewModel::selectPaletteAuto,
                )
            }

            item(key = "pip") {
                PictureInPictureSection(
                    enabled = viewModel.pipEnabled,
                    onToggle = viewModel::selectPipEnabled,
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
 * The outline drawn around a selected choice.
 *
 * **Drawn to contrast with the card behind the chip, not with the chip itself.** It used to be
 * `onPrimaryContainer`, on the reasoning that the container's own content colour must contrast
 * with that container — true, but the wrong surface. A border straddles the boundary: half of it
 * lies over the chip and half over the card. In the bright palette `onPrimaryContainer` is pure
 * white and the card is near-white, so the outer half vanished and the selection cue with it —
 * exactly where legibility is the entire point of the palette.
 *
 * `primary` is the one role that is reliably far from the card surface in all three: a dark navy
 * on the bright palette's near-white card, a light blue on dark's navy, a red on night's near
 * black. `PaletteContrastTest` now measures it rather than trusting this note.
 */
@Composable
private fun SelectionOutline(): Color = MaterialTheme.colorScheme.primary

/**
 * The three light conditions, in the cycle order the status-bar button steps through.
 *
 * The captions are the reason each palette exists rather than what it looks like: someone choosing
 * one is answering "where am I and what is the light doing", not picking a colour they like.
 */
private val PaletteOptions = listOf(
    TrackOption(HelmPalette.HIGH_CONTRAST, "Bright", MfdIcons.PaletteDay, "Direct sun."),
    TrackOption(HelmPalette.DARK, "Dark", MfdIcons.PaletteDusk, "Overcast, or below decks."),
    TrackOption(HelmPalette.NIGHT, "Night", MfdIcons.PaletteNight, "Red, to keep night vision."),
)

private val DelayModeOptions = DelayNotification.entries.map { mode ->
    TrackOption(
        value = mode,
        label = mode.label,
        icon = when (mode) {
            DelayNotification.ALWAYS -> MfdIcons.AlwaysOn
            DelayNotification.WHEN_DELAYED -> MfdIcons.WhenLate
            DelayNotification.OFF -> MfdIcons.NotShown
        },
        detail = mode.detail,
    )
}

/**
 * The colours every segmented track on this screen draws with, enabled and disabled.
 *
 * Material's own disabled defaults are wrong here, and quietly so: `disabledActiveContainerColor`
 * defaults to `activeContainerColor`, so a disabled track keeps its full-strength selected fill
 * while only the *content* drops to 38%. On the bright palette that put near-black text on a solid
 * navy segment — about 1.3:1, i.e. the selected value became unreadable at the exact moment the
 * user most needs to see what it still is.
 *
 * So the fill is dimmed and the content is not. A disabled control still has to answer "what is
 * this set to"; only "can I change it" is in question, and that is carried by the washed-out fill
 * and the faded unselected segments around it. WCAG exempts disabled controls from its contrast
 * floors, but a screen meant to be read in direct sun is the wrong place to take that exemption.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun trackColors(outline: Color): SegmentedButtonColors {
    val scheme = MaterialTheme.colorScheme
    // Every track sits on a surfaceVariant card, so that is what a translucent colour lands on.
    val card = scheme.surfaceVariant
    return SegmentedButtonDefaults.colors(
        activeContainerColor = scheme.primaryContainer,
        activeContentColor = scheme.onPrimaryContainer,
        activeBorderColor = outline,
        disabledActiveContainerColor = disabledSelectedFill(scheme.primaryContainer, card),
        disabledActiveContentColor = scheme.onSurfaceVariant,
        disabledActiveBorderColor = outline.copy(alpha = 0.40f).compositeOver(card),
        disabledInactiveContentColor = scheme.onSurfaceVariant.copy(alpha = 0.50f),
        disabledInactiveBorderColor = scheme.outline.copy(alpha = 0.40f).compositeOver(card),
    )
}

/**
 * The fill behind the selected segment of a track that cannot currently be changed.
 *
 * A fifth-strength wash of the selected colour over the card: enough tint to say *this* is the
 * value, far too little to read as something you can press. Kept separate from [trackColors] so
 * the contrast test can measure the colour the app actually draws rather than restate the blend.
 */
internal fun disabledSelectedFill(selected: Color, card: Color): Color =
    selected.copy(alpha = 0.20f).compositeOver(card)

/** One option on a [ChoiceTrack]. */
internal data class TrackOption<T>(
    val value: T,
    val label: String,
    val icon: ImageVector?,
    /** The line shown under the track while this option is the selected one. */
    val detail: String? = null,
)

/**
 * A row of mutually exclusive options as one connected track, with the chosen one's explanation
 * on a line beneath.
 *
 * Every multi-choice setting on this screen uses it, so they cannot drift apart. Three properties
 * are load-bearing rather than decorative:
 *
 * - **One track, not N buttons.** Separate lozenges read as separate decisions; a divided track
 *   reads as one choice among alternatives, which is what it is.
 * - **Selection is marked three ways** — a filled segment, its icon, and a border in
 *   [SelectionOutline]. Never by one cue alone: fill can collapse in glare, and an outline that
 *   contrasts with the segment can still vanish against the card behind it, which is exactly the
 *   defect that made the bright palette's selection invisible.
 * - **The caption lives under the track, not inside it.** A segment cannot hold two lines without
 *   becoming the row of fat buttons this replaced, and the explanation is worth keeping: it is
 *   what tells a first-time user what "When late" actually does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ChoiceTrack(
    options: List<TrackOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = trackColors(SelectionOutline())
    Column(modifier) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(MinHelmTarget)) {
            options.forEachIndexed { index, option ->
                val isSelected = option.value == selected
                SegmentedButton(
                    selected = isSelected,
                    onClick = { onSelect(option.value) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    colors = colors,
                    icon = {
                        // Material puts a tick here by default when selected, which would replace
                        // the icon that identifies the option. The icon is the more useful of the
                        // two: the fill and the border already say which one is chosen, and only
                        // the glyph says *what it is* at a glance.
                        if (option.icon != null) {
                            Icon(option.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    },
                    modifier = Modifier.semantics {
                        stateDescription = if (isSelected) "Selected" else "Not selected"
                    },
                ) {
                    Text(
                        option.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }

        options.firstOrNull { it.value == selected }?.detail?.let { detail ->
            Spacer(Modifier.size(6.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
private fun PaletteSection(
    palette: HelmPalette,
    auto: Boolean,
    onSelect: (HelmPalette) -> Unit,
    onToggleAuto: (Boolean) -> Unit,
) {
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

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Automatic",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Follows the light sensor, or the sun's position if the phone has none or " +
                            "it isn't reading reliably. Picking a mode below overrides this until " +
                            "Automatic is turned back on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(16.dp))
                Switch(checked = auto, onCheckedChange = onToggleAuto)
            }

            ChoiceTrack(
                options = PaletteOptions,
                selected = palette,
                onSelect = onSelect,
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

            ChoiceTrack(
                options = DelayModeOptions,
                selected = mode,
                onSelect = onSelectMode,
            )

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
                ThresholdTrack(
                    selected = thresholdSeconds,
                    enabled = thresholdEnabled,
                    onSelect = onSelectThreshold,
                )
            }
        }
    }
}

/**
 * The threshold, as one connected track rather than a row of separate buttons.
 *
 * Five standalone pills for five numbers read as five decisions; a single divided track reads as
 * one — which is what it is, a value chosen along a scale. It is also far less ink for a secondary
 * control sitting under a primary one, so the card no longer looks like two competing rows of
 * lozenges.
 *
 * The selected segment is marked three ways, none of which is only an outline: Material's own tick,
 * a filled container, and bold text. The border it does carry is [SelectionOutline], drawn to
 * contrast with the card rather than with the segment — the distinction that made the previous
 * version invisible in the bright palette.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThresholdTrack(
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    val colors = trackColors(SelectionOutline())
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(MinHelmTarget)) {
        DelayThresholdChoices.forEachIndexed { index, seconds ->
            val isSelected = seconds == selected
            SegmentedButton(
                selected = isSelected,
                onClick = { onSelect(seconds) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, DelayThresholdChoices.size),
                colors = colors,
                modifier = Modifier.semantics {
                    stateDescription = if (isSelected) "Selected" else "Not selected"
                },
            ) {
                Text(
                    "${seconds}s",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
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

/**
 * Whether swiping away while mirroring keeps the video visible in a small window rather than
 * ending the session — see `MainActivity.onUserLeaveHint` and `VideoPane`'s `inPip` doc for what
 * that window actually shows (video only, no touch forwarding).
 *
 * The switch here is this app's own opt-out; the link below opens Android's *separate*,
 * per-app picture-in-picture permission, which this screen has no way to grant on the user's
 * behalf — some OEMs default it off, and the switch above does nothing until it is on.
 */
@Composable
private fun PictureInPictureSection(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Picture-in-picture",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Swiping away while mirroring keeps the video in a small window instead " +
                            "of ending the session. Tapping that window only ever brings the app " +
                            "back — it never sends a touch to the display.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(16.dp))
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            TextButton(
                onClick = {
                    // The action string, not a named Settings constant — AOSP does not expose one
                    // for this particular screen, only the string itself.
                    val intent = Intent(
                        "android.settings.PICTURE_IN_PICTURE_SETTINGS",
                        Uri.fromParts("package", context.packageName, null),
                    )
                    // Not every OEM ships this exact settings screen; failing quietly beats a crash
                    // over a settings shortcut that is convenience, not a required step.
                    runCatching { context.startActivity(intent) }
                },
            ) {
                Text("Also needs Android's picture-in-picture permission for this app — open it")
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
                ) {
                    Icon(MfdIcons.Confirm, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Save")
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { confirmForget = true },
                    modifier = Modifier.height(MinHelmTarget),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(MfdIcons.Forget, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Forget")
                }
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
