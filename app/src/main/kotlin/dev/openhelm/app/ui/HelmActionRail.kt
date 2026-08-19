package dev.openhelm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.openhelm.app.ui.icons.MfdIcons

/**
 * The session's own actions, as a column of round buttons down the outside edge.
 *
 * These lived in a full-width bar across the top, which cost the picture ~64dp of height for four
 * controls — and height is the axis the video is short of, because a 5:3 picture in a landscape
 * window is almost always height-bound. Moving them beside the panel gives that height back to the
 * video, and because the aspect is fixed the picture grows in *both* directions.
 *
 * Round and iconic rather than labelled: these four are used often enough to be learned, they are
 * always in the same place, and every one carries a spoken description for the screen reader. The
 * exception is the pair below.
 *
 * **Mirror and Remote are one two-segment toggle, not two separate buttons.** A single button whose
 * meaning flips is ambiguous in a way no wording fixes — "Video off" reads equally as *the video is
 * off* and *tap to turn the video off*, and the two are opposites. But two free-standing buttons
 * with a gap between them misrepresent the choice the other way: they look like two independent
 * actions when only one of them can ever be true at once. A connected capsule with both segments
 * always visible says "pick one of these two" at a glance — the current mode and the alternative are
 * shown at the same time, so nothing has to be inferred from a verb, and the shared outline makes the
 * mutual exclusivity visible rather than assumed.
 */
@Composable
fun HelmActionRail(
    palette: HelmPalette,
    mirroring: Boolean,
    onCyclePalette: () -> Unit,
    onSelectMirroring: (Boolean) -> Unit,
    exitIcon: ImageVector,
    exitLabel: String,
    onExit: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top of the rail, furthest from Disconnect. Settings used to live only behind the connect
        // screen's overflow menu, which put every option in it — the palette detail, the delay
        // warning, the diagnostics — out of reach the moment a session started, i.e. exactly when
        // someone would want to change them. It opens *over* the session; nothing is torn down.
        FilledTonalIconButton(
            onClick = onSettings,
            modifier = Modifier
                .size(MinHelmTarget)
                .semantics { contentDescription = "Settings" },
        ) {
            Icon(MfdIcons.Settings, contentDescription = null, modifier = Modifier.size(24.dp))
        }

        PaletteButton(palette = palette, onCycle = onCyclePalette)

        MirrorRemoteToggle(mirroring = mirroring, onSelectMirroring = onSelectMirroring)

        // Destructive, so it is distinguished by its container rather than by tint alone — tint is
        // the first cue direct sun destroys — and it sits at the far end of the rail, as much of a
        // thumb's travel from the mode toggle as the rail allows.
        FilledIconButton(
            onClick = onExit,
            modifier = Modifier
                .size(MinHelmTarget)
                .semantics { contentDescription = exitLabel },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Icon(exitIcon, contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * Mirror vs. Remote as a single connected capsule: two segments sharing one outline instead of two
 * separate round buttons with a gap between them. [selectableGroup] plus [Role.RadioButton] on each
 * segment gives a screen reader the same "one of these two" grouping the shape shows sighted users.
 */
@Composable
private fun MirrorRemoteToggle(
    mirroring: Boolean,
    onSelectMirroring: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(MinHelmTarget / 2)
    Column(
        modifier = Modifier
            .width(MinHelmTarget)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .selectableGroup(),
    ) {
        ToggleSegment(
            selected = mirroring,
            icon = MfdIcons.VideoOn,
            spoken = "Mirror the display's screen beside the controls",
            onClick = { onSelectMirroring(true) },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ToggleSegment(
            selected = !mirroring,
            icon = MfdIcons.Keypad,
            spoken = "Remote only — full-screen controls, no video",
            onClick = { onSelectMirroring(false) },
        )
    }
}

/** One segment of [MirrorRemoteToggle]. Filled when it is the mode you are in. */
@Composable
private fun ToggleSegment(
    selected: Boolean,
    icon: ImageVector,
    spoken: String,
    onClick: () -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(MinHelmTarget)
            .background(containerColor)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics {
                contentDescription = spoken
                stateDescription = if (selected) "Selected" else "Not selected"
            },
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(24.dp))
    }
}

/** Width the rail occupies, so callers can reason about what is left for the picture. */
internal val ActionRailWidth = MinHelmTarget + 16.dp
